package commanddeck.hytale;

import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.console.ConsoleSender;
import com.hypixel.hytale.server.core.event.events.BootEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.util.thread.TickingThread;
import com.hypixel.hytale.server.core.Constants;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import commanddeck.api.CommandDeckApi;
import commanddeck.jvm.FileMacros;
import commanddeck.jvm.FilePrefs;
import commanddeck.jvm.HostLog;
import commanddeck.jvm.LockedDeck;
import commanddeck.jvm.RunsFile;
import commanddeck.api.Deck;
import commanddeck.api.Presser;
import commanddeck.api.RunListener;
import commanddeck.command.ArgType;
import commanddeck.command.CommandCatalog;
import commanddeck.deck.MacroLookup;
import commanddeck.command.CommandInfo;
import commanddeck.command.Level;
import commanddeck.command.Sidecars;
import commanddeck.deck.DeckService;
import commanddeck.hytale.window.DeckWindow;
import commanddeck.window.LocalSession;
import commanddeck.window.MacroShelf;
import commanddeck.window.Prefs;
import commanddeck.macro.Limits;
import commanddeck.macro.Problem;
import commanddeck.run.RunLog;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * CommandDeck for Hytale: an in-game command browser and macro builder. Wires the Hytale side (the command
 * registry, players, the tick) to the game-agnostic deck. The deck is published for other mods at setup; the
 * command list is read at boot, once every mod has registered its commands, and again on {@code /deck reload}.
 */
public final class CommandDeckPlugin extends JavaPlugin {
    private static volatile CommandDeckPlugin instance;

    private Settings settings;
    private FilePrefs prefs;
    private FileMacros macros;
    private HytaleAssets assets;
    private HytaleGame game;
    /** The shared condition library this process hosts, and its port for macros' loops. */
    private hledjolf.conditions.Library conditionLibrary;
    private HytaleConditions conditions;
    private LockedDeck deck;
    private volatile CommandCatalog catalog;
    private volatile HytaleCommands commands;
    private volatile ScheduledFuture<?> ticker;
    /** Each player's window, kept while they are online so closing it loses nothing (an unsaved macro included). */
    private final Map<String, DeckWindow> windows = new ConcurrentHashMap<>();
    private long tick;

    /** The plugin's log, for the shared files and sidecars. */
    private HostLog hostLog;

    public CommandDeckPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        Path data = getDataDirectory();
        settings = new Settings();
        String problem = settings.load(data.resolve("config.json"));
        if (problem != null) getLogger().atWarning().log("[CommandDeck] %s", problem);
        hostLog = new HostLog() {
            @Override
            public void info(String message) {
                getLogger().atInfo().log("%s", message);
            }

            @Override
            public void warn(String message) {
                getLogger().atWarning().log("%s", message);
            }
        };
        macros = new FileMacros(data, hostLog, () -> settings.limits());
        prefs = new FilePrefs(data, hostLog);
        assets = new HytaleAssets(getLogger());
        game = new HytaleGame(settings, () -> catalog, () -> commands, new HytaleEscaper.Shapes() {
            @Override
            public int tokens(String command, int form, String arg) {
                HytaleCommands c = commands;
                HytaleCommands.Shape s = c == null ? null : c.shape(command, form, arg);
                return s == null ? 0 : s.tokens();
            }

            @Override
            public boolean isList(String command, int form, String arg) {
                HytaleCommands c = commands;
                HytaleCommands.Shape s = c == null ? null : c.shape(command, form, arg);
                return s != null && s.list();
            }
        }, getLogger());
        RunLog runLog = new RunLog(new RunsFile(data.resolve("runs.log"), hostLog));
        macros.usePresets(() -> catalog);
        // The condition library is up from CommandDeck's setup on, so other mods can register in theirs.
        conditionLibrary = new hledjolf.conditions.Library(new HytaleFacts(), m -> getLogger().atWarning().log("[Conditions] %s", m),
            System::nanoTime, () -> settings.conditionBudgetMs());
        if (!hledjolf.conditions.api.Installed.install(conditionLibrary)) {
            getLogger().atWarning().log("[CommandDeck] Another condition library is already installed; loops use their own");
        }
        conditions = new HytaleConditions(conditionLibrary);
        game.useConditions(conditions);
        macros.useConditions(() -> conditions);
        deck = new LockedDeck(new DeckService(game, macros, () -> catalog, runLog, System::currentTimeMillis));
        applySettings();
        CommandDeckApi.publish(deck);
        instance = this;

        PermissionsModule.registerPermission(HytaleGame.USE);
        PermissionsModule.registerPermission(HytaleGame.MACROS_EDIT);
        PermissionsModule.registerPermission(HytaleGame.RUN_OTHERS);
        PermissionsModule.registerPermission(HytaleGame.OPERATOR);
        PermissionsModule.registerPermission(HytaleGame.HEAL);
        getCommandRegistry().registerCommand(new DeckCommand(this));
        getEventRegistry().register(BootEvent.class, e -> boot());
        getEventRegistry().register(PlayerDisconnectEvent.class, e -> windows.remove(e.getPlayerRef().getUuid().toString()));
    }

    private synchronized void boot() {
        HytaleSink.watchMessages();
        try {
            getLogger().atInfo().log("[CommandDeck] conditions: %s", conditions.seed(conditionLibrary));
        } catch (RuntimeException e) {
            getLogger().atWarning().withCause(e).log("[CommandDeck] Couldn't read the game's weathers, fluids and items for conditions");
        }
        String loaded = load();
        long stepNanos = 1_000_000_000L / TickingThread.TPS;
        if (ticker == null) ticker = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(this::tick, stepNanos, stepNanos, TimeUnit.NANOSECONDS);
        getLogger().atInfo().log("[CommandDeck] ready (API %s): %s", Deck.API_VERSION, loaded);
    }

    @Override
    protected synchronized void shutdown() {
        if (ticker != null) ticker.cancel(false);
        ticker = null;
        windows.clear();
        HytaleSink.stopWatching();
        if (CommandDeckApi.get() == deck) CommandDeckApi.publish(null);
        if (instance == this) instance = null;
    }

    private void tick() {
        try {
            deck.tick(++tick, System.currentTimeMillis());
        } catch (Throwable t) {
            // An exception would cancel the scheduled task and stop every run for good.
            getLogger().atSevere().withCause(t).log("[CommandDeck] tick %d failed", tick);
        }
    }

    /** {@code /deck reload}: settings, sidecars, the command list and macros. Observers of runs end. */
    String reload() {
        String problem = settings.load(getDataDirectory().resolve("config.json"));
        if (problem != null) getLogger().atWarning().log("[CommandDeck] %s", problem);
        applySettings();
        deck.clearObservers();
        String loaded = load();
        getLogger().atInfo().log("[CommandDeck] reloaded: %s", loaded);
        return "Reloaded: " + loaded + (problem == null ? "" : " Settings: " + problem);
    }

    /**
     * Mod Settings Manager's live-apply hook: {@code config.json} is already written when this runs. Every
     * setting takes effect for runs started from now on.
     */
    public boolean onSettingsChanged(Map<String, Object> changed) {
        String problem = settings.load(getDataDirectory().resolve("config.json"));
        if (problem != null) getLogger().atWarning().log("[CommandDeck] %s", problem);
        applySettings();
        getLogger().atInfo().log("[CommandDeck] settings applied: %s", String.join(", ", changed.keySet()));
        return true;
    }

    private void applySettings() {
        deck.setLimits(settings.limits());
        deck.setEnabled(settings.enabled());
    }

    private String load() {
        int registryEntries = conditions.port().readRegistryFiles(getDataDirectory().resolve("registries"), hostLog::warn);
        Sidecars sidecars = HytaleSidecars.load(getDataDirectory(), hostLog);
        HytaleCommands found = new HytaleCommands();
        CommandCatalog cat = CommandCatalog.build(found, sidecars);
        commands = found;
        for (Problem w : cat.warnings()) getLogger().atWarning().log("[CommandDeck] %s: %s", w.code(), w.message());
        catalog = cat;
        int described = 0;
        for (CommandInfo c : cat.commands()) {
            if (c.level() == Level.DESCRIBED) described++;
        }
        int n = macros.reload();
        // Presets' calls are checked once the server macros they may name are read.
        List<Problem> calls = MacroLookup.presetWarnings(cat, macros);
        for (Problem w : calls) getLogger().atWarning().log("[CommandDeck] %s: %s", w.code(), w.message());
        return cat.commands().size() + " commands (" + described + " described by sidecars), " + sidecars.effective().size()
            + " sidecars, " + cat.presets().size() + " presets, " + n + " saved macros, " + registryEntries + " registry entries from files, "
            + (cat.warnings().size() + calls.size()) + " warnings.";
    }

    /** The current command list (rebuilt on reload); null before boot. */
    public CommandCatalog catalog() {
        return catalog;
    }

    /** The running plugin, or null before setup. For the headless scenarios. */
    public static CommandDeckPlugin instance() {
        return instance;
    }

    /** A fresh window for a presser. Public for the headless scenarios, which drive it without a client. */
    public DeckWindow window(Presser presser) {
        return new DeckWindow(new LocalSession(new WindowHost(presser)), HytaleLines.RULES);
    }

    /** The presser's own window: the same one every time until they leave, so a closed window keeps its state. */
    public DeckWindow windowFor(Presser presser) {
        return windows.computeIfAbsent(presser.id(), id -> window(presser));
    }

    /** What one presser's window sees of the plugin. */
    private final class WindowHost implements LocalSession.Game {
        private final Presser presser;
        private final RunListener reporter;

        WindowHost(Presser presser) {
            this.presser = presser;
            this.reporter = new DeckCommand.Reporter(presser);
        }

        @Override
        public Presser presser() {
            return presser;
        }

        @Override
        public LockedDeck deck() {
            return deck;
        }

        @Override
        public int stopAll(Presser by) {
            return deck.stopAll(by);
        }

        @Override
        public boolean holds(Presser p, String permission) {
            return deck.holds(p, permission);
        }

        @Override
        public CommandCatalog catalog() {
            return catalog;
        }

        @Override
        public MacroShelf macros() {
            return macros;
        }

        @Override
        public Prefs prefs() {
            return prefs;
        }

        @Override
        public Limits limits() {
            return settings.limits();
        }

        @Override
        public commanddeck.macro.ConditionPort conditions() {
            return conditions;
        }

        /** For the editor's Here button: where the presser stands now. */
        @Override
        public double[] position() {
            PlayerRef p = HytaleGame.player(presser.id());
            com.hypixel.hytale.math.vector.Transform t = p == null ? null : p.getTransform();
            if (t == null || t.getPosition() == null) return null;
            return new double[] {t.getPosition().x, t.getPosition().y, t.getPosition().z};
        }

        @Override
        public boolean canEditOwn() {
            return HytaleGame.has(presser.id(), HytaleGame.MACROS_EDIT) || Constants.SINGLEPLAYER && settings.everyoneInSingleplayer()
                || game.isOperator(presser);
        }

        @Override
        public boolean isOperator() {
            return game.isOperator(presser);
        }

        @Override
        public int coordinates() {
            return 3;
        }

        @Override
        public List<String> onlinePlayers() {
            List<String> out = new ArrayList<>();
            Universe u = Universe.get();
            if (u != null) {
                for (PlayerRef p : u.getPlayers()) out.add(p.getUsername());
            }
            out.sort(String.CASE_INSENSITIVE_ORDER);
            return out;
        }

        @Override
        public RunListener reporter() {
            return reporter;
        }

        @Override
        public int tokens(String commandName, int form, String arg) {
            HytaleCommands c = commands;
            HytaleCommands.Shape s = c == null ? null : c.shape(commandName, form, arg);
            return s == null ? 0 : s.tokens();
        }

        @Override
        public boolean isList(String commandName, int form, String arg) {
            HytaleCommands c = commands;
            HytaleCommands.Shape s = c == null ? null : c.shape(commandName, form, arg);
            return s != null && s.list();
        }

        @Override
        public Boolean mayRun(String commandName) {
            HytaleCommands c = commands;
            if (c == null) return null;
            CommandSender sender = HytaleGame.isConsole(presser.id()) ? ConsoleSender.INSTANCE : HytaleGame.player(presser.id());
            return HytaleCommands.mayRun(c.find(commandName), sender);
        }

        @Override
        public List<String> assetIds(ArgType type) {
            return assets.ids(type);
        }
    }

    LockedDeck deck() {
        return deck;
    }

    HytaleGame game() {
        return game;
    }

    FileMacros macros() {
        return macros;
    }
}
