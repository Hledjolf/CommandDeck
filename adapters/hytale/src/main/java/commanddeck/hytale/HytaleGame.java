package commanddeck.hytale;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.Constants;
import com.hypixel.hytale.server.core.console.ConsoleSender;
import com.hypixel.hytale.server.core.modules.singleplayer.SingleplayerModule;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import commanddeck.api.Presser;
import commanddeck.command.CommandCatalog;
import commanddeck.deck.Game;
import commanddeck.macro.Placeholders;
import commanddeck.macro.Problem;
import commanddeck.run.CommandSink;
import java.util.UUID;
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

    private final Settings settings;
    private final HytaleEscaper escaper;
    private final HytaleLogger log;

    HytaleGame(Settings settings, Supplier<CommandCatalog> catalog, HytaleEscaper.Shapes shapes, HytaleLogger log) {
        this.settings = settings;
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
        return new Placeholders.Context(p.getUsername(), null, pos, null, true);
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
