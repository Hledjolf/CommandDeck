package commanddeck.window;

import commanddeck.api.CheckResult;
import commanddeck.api.Deck;
import commanddeck.api.Presser;
import commanddeck.api.RunListener;
import commanddeck.api.RunRequest;
import commanddeck.api.RunStatus;
import commanddeck.api.StartResult;
import commanddeck.command.ArgType;
import commanddeck.command.CommandCatalog;
import commanddeck.macro.Limits;
import commanddeck.macro.Macro;
import commanddeck.macro.Problem;
import commanddeck.window.MacroShelf.Scope;
import java.util.List;
import java.util.Set;

/** A {@link DeckSession} in the deck's own process: each call goes straight to the deck and the game. */
public final class LocalSession implements DeckSession {
    /** What the session needs from the game adapter, for one presser. */
    public interface Game {
        Presser presser();

        /** The deck, safe to call from the window's thread. */
        Deck deck();

        /** Stops everyone's runs as this presser; how many. Not part of the integration API. */
        int stopAll(Presser by);

        boolean holds(Presser presser, String permission);

        CommandCatalog catalog();

        MacroShelf macros();

        Prefs prefs();

        Limits limits();

        /** The game's condition library, or null. */
        default commanddeck.macro.ConditionPort conditions() {
            return null;
        }

        /** Where the presser stands, one number per coordinate, or null when the game can't tell. */
        default double[] position() {
            return null;
        }

        boolean canEditOwn();

        boolean isOperator();

        /** How many coordinates the game has: 2 or 3. */
        int coordinates();

        List<String> onlinePlayers();

        /** Told how a run started from the window ends. */
        RunListener reporter();

        default Boolean mayRun(String commandName) {
            return null;
        }

        default int tokens(String commandName, int form, String arg) {
            return 0;
        }

        default boolean isList(String commandName, int form, String arg) {
            return false;
        }

        default List<String> assetIds(ArgType type) {
            return List.of();
        }
    }

    private final Game game;

    public LocalSession(Game game) {
        this.game = game;
    }

    private String id() {
        return game.presser().id();
    }

    @Override
    public Opened open() {
        return new Opened(game.presser(), game.canEditOwn(), game.isOperator(), game.coordinates());
    }

    @Override
    public Limits limits() {
        return game.limits();
    }

    @Override
    public commanddeck.macro.ConditionPort conditions() {
        return game.conditions();
    }

    @Override
    public double[] position() {
        return game.position();
    }

    @Override
    public CommandCatalog catalog() {
        return game.catalog();
    }

    @Override
    public List<String> onlinePlayers() {
        return game.onlinePlayers();
    }

    @Override
    public Boolean mayRun(String commandName) {
        return game.mayRun(commandName);
    }

    @Override
    public int tokens(String commandName, int form, String arg) {
        return game.tokens(commandName, form, arg);
    }

    @Override
    public boolean isList(String commandName, int form, String arg) {
        return game.isList(commandName, form, arg);
    }

    @Override
    public List<String> assetIds(ArgType type) {
        return game.assetIds(type);
    }

    @Override
    public CheckResult check(RunRequest request) {
        return game.deck().check(game.presser(), request);
    }

    @Override
    public StartResult start(RunRequest request, String confirmToken) {
        return game.deck().start(game.presser(), request, game.reporter(), confirmToken);
    }

    @Override
    public List<RunStatus> running() {
        return game.deck().running(game.isOperator() ? null : game.presser());
    }

    @Override
    public boolean stop(String runId) {
        return game.deck().stop(runId, game.presser());
    }

    @Override
    public int stopAll() {
        return game.stopAll(game.presser());
    }

    @Override
    public boolean holds(String permission) {
        return game.holds(game.presser(), permission);
    }

    @Override
    public List<Macro> macros(Scope scope) {
        return game.macros().list(scope, id());
    }

    @Override
    public Problem saveMacro(Scope scope, Macro macro, String replacing) {
        return game.macros().save(scope, id(), macro, replacing);
    }

    @Override
    public Problem deleteMacro(Scope scope, String id) {
        return game.macros().delete(scope, id(), id);
    }

    @Override
    public Set<String> favorites() {
        return game.prefs().favorites(id());
    }

    @Override
    public boolean toggleFavorite(String command) {
        return game.prefs().toggleFavorite(id(), command);
    }

    @Override
    public List<String> history(String command) {
        return game.prefs().history(id(), command);
    }

    @Override
    public void remember(String command, String text) {
        game.prefs().remember(id(), command, text);
    }
}
