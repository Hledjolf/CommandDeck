package commanddeck.hytale;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.Constants;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.console.ConsoleSender;
import com.hypixel.hytale.server.core.modules.singleplayer.SingleplayerModule;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import commanddeck.api.Presser;
import commanddeck.command.CommandCatalog;
import commanddeck.command.CommandInfo;
import commanddeck.deck.Game;
import commanddeck.macro.Placeholders;
import commanddeck.macro.Problem;
import commanddeck.run.CommandSink;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * The players side of Hytale for the deck. A presser is a player by UUID, or the server console (its own
 * UUID), which is always online, may do everything, and has no position.
 */
final class HytaleGame implements Game {
    static final String USE = "commanddeck.use";
    static final String MACROS_EDIT = "commanddeck.macros.edit";
    static final String RUN_OTHERS = "commanddeck.run_others";
    /** Stop other players' runs and reload: {@code /deck stop all}, {@code /deck reload}. Operators have it. */
    static final String OPERATOR = "commanddeck.operator";

    static final String CONSOLE_ID = ConsoleSender.INSTANCE.getUuid().toString();

    /** How far {@code {look}} reaches, in blocks: the distance the game's own commands give its target lookup. */
    static final double REACH = 10.0;
    /** How long {@code {look}} waits for the player's world thread when asked from another. */
    static final long LOOK_WAIT_MILLIS = 250;

    private final Settings settings;
    private final Supplier<CommandCatalog> catalog;
    private final Supplier<HytaleCommands> commands;
    private final HytaleEscaper escaper;
    private final HytaleLogger log;

    HytaleGame(Settings settings, Supplier<CommandCatalog> catalog, Supplier<HytaleCommands> commands, HytaleEscaper.Shapes shapes,
               HytaleLogger log) {
        this.settings = settings;
        this.catalog = catalog;
        this.commands = commands;
        this.escaper = new HytaleEscaper(catalog, shapes);
        this.log = log;
    }

    static boolean isConsole(String presserId) {
        return CONSOLE_ID.equals(presserId);
    }

    static Presser console() {
        return new Presser(CONSOLE_ID, ConsoleSender.INSTANCE.getUsername());
    }

    static PlayerRef player(String presserId) {
        if (presserId == null || isConsole(presserId)) return null;
        try {
            Universe u = Universe.get();
            return u == null ? null : u.getPlayer(UUID.fromString(presserId));
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    @Override
    public boolean online(String presserId) {
        return isConsole(presserId) || player(presserId) != null;
    }

    @Override
    public boolean mayUse(Presser presser) {
        if (isConsole(presser.id())) return true;
        if (Constants.SINGLEPLAYER && settings.everyoneInSingleplayer()) return true;
        return has(presser.id(), USE);
    }

    @Override
    public boolean isOperator(Presser presser) {
        if (isConsole(presser.id())) return true;
        UUID id = uuid(presser.id());
        if (id != null && Constants.SINGLEPLAYER && SingleplayerModule.isOwner(id)) return true;
        return has(presser.id(), OPERATOR);
    }

    /** A macro's or preset's permission: the console and the singleplayer owner pass; operators have it through {@code *}. */
    @Override
    public boolean holds(Presser presser, String permission) {
        return isConsoleOrOwner(presser.id()) || has(presser.id(), permission);
    }

    @Override
    public boolean mayRunForOthers(Presser presser) {
        return isConsoleOrOwner(presser.id()) || has(presser.id(), RUN_OTHERS);
    }

    @Override
    public String onlineName(String playerId) {
        if (isConsole(playerId)) return ConsoleSender.INSTANCE.getUsername();
        PlayerRef p = player(playerId);
        return p == null ? null : p.getUsername();
    }

    private static boolean isConsoleOrOwner(String presserId) {
        if (isConsole(presserId)) return true;
        UUID id = uuid(presserId);
        return id != null && Constants.SINGLEPLAYER && SingleplayerModule.isOwner(id);
    }

    static boolean has(String presserId, String node) {
        if (isConsole(presserId)) return true;
        UUID id = uuid(presserId);
        PermissionsModule perms = PermissionsModule.get();
        return id != null && perms != null && perms.hasPermission(id, node);
    }

    private static UUID uuid(String presserId) {
        try {
            return UUID.fromString(presserId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public CommandSink sinkFor(Presser presser) {
        long timeout = settings.commandTimeoutSeconds() * 1000L;
        if (isConsole(presser.id())) return new HytaleSink(ConsoleSender.INSTANCE, null, settings.plainSender(), timeout, log);
        PlayerRef p = player(presser.id());
        if (p == null) {
            // Gone between check and start: the deck stops the run before its first step.
            return line -> new CommandSink.Done(commanddeck.api.CommandOutcome.REFUSED, commanddeck.api.OutcomeBasis.GAME_SIGNAL, true,
                java.util.List.of(presser.name() + " isn't online"));
        }
        return new HytaleSink(p, p.getUuid(), settings.plainSender(), timeout, log);
    }

    @Override
    public Placeholders.Context context(Presser presser) {
        if (isConsole(presser.id())) return new Placeholders.Context(presser.name(), null, null, null, true);
        PlayerRef p = player(presser.id());
        if (p == null) return null;
        Placeholders.Position pos = null;
        Transform t = p.getTransform();
        if (t != null && t.getPosition() != null) pos = new Placeholders.Position(t.getPosition().x, t.getPosition().y, t.getPosition().z);
        return new Placeholders.Context(p.getUsername(), null, pos, null, true, () -> look(p));
    }

    /**
     * {@code {look}}: the id of the block the player's crosshair is on, within {@link #REACH}, or null for none
     * (air, or too far). The game's own target lookup, as its commands use it, on the player's world thread:
     * from another thread it waits up to {@link #LOOK_WAIT_MILLIS} for that thread, and gives null if it doesn't
     * answer in time.
     */
    static String look(PlayerRef p) {
        Universe u = Universe.get();
        UUID worldId = p.getWorldUuid();
        World w = u == null || worldId == null ? null : u.getWorld(worldId);
        if (w == null) return null;
        if (w.isInThread()) return lookNow(p);
        CompletableFuture<String> found = new CompletableFuture<>();
        w.execute(() -> {
            try {
                found.complete(lookNow(p));
            } catch (Throwable e) {
                found.completeExceptionally(e);
            }
        });
        try {
            return found.get(LOOK_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException e) {
            return null;
        }
    }

    /** On the world thread. The lookup's test sees each block id along the ray; the first that isn't empty is hit. */
    private static String lookNow(PlayerRef p) {
        Ref<EntityStore> ref = p.getReference();
        if (ref == null || !ref.isValid()) return null;
        int[] hit = {BlockType.EMPTY_ID};
        var at = TargetUtil.getTargetBlock(ref, id -> {
            if (id == BlockType.EMPTY_ID) return false;
            hit[0] = id;
            return true;
        }, REACH, ref.getStore());
        if (at == null || hit[0] == BlockType.EMPTY_ID) return null;
        BlockType type = BlockType.getAssetMap().getAsset(hit[0]);
        return type == null ? null : type.getId();
    }

    @Override
    public boolean isConsole(Presser presser) {
        return isConsole(presser.id());
    }

    /** The game's own permission check for the line's command, as the window's hint uses it. The console may do everything. */
    @Override
    public Boolean permitted(Presser presser, String line) {
        if (isConsole(presser.id())) return true;
        CommandCatalog cat = catalog.get();
        HytaleCommands c = commands.get();
        CommandInfo info = cat == null ? null : cat.match(line);
        if (info == null || c == null) return null;
        return HytaleCommands.mayRun(c.find(info.name()), player(presser.id()));
    }

    @Override
    public Problem checkLine(String line) {
        return HytaleLines.checkLine(line);
    }

    @Override
    public Placeholders.Escaper escaper() {
        return escaper;
    }
}
