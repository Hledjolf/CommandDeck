package commanddeck.window;

import commanddeck.api.CheckResult;
import commanddeck.api.Presser;
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

/**
 * The window's only door to the deck, for one presser. Where the window and the deck share a process, a
 * {@link LocalSession} calls the deck directly. Where the window is drawn on a player's machine and the deck runs
 * on the server, each call is a message, written by {@code SessionCodec}.
 */
public interface DeckSession {
    /**
     * Who is at the window and what they may do, asked again whenever the window needs it, and how many
     * coordinates the game has (2 or 3), which is how many words a position reads.
     */
    record Opened(Presser presser, boolean canEditOwn, boolean operator, int coordinates) {}

    Opened open();

    Limits limits();

    /** The game's condition library, for checking loops as they're edited; null where it isn't available here. */
    default commanddeck.macro.ConditionPort conditions() {
        return null;
    }

    /**
     * Where the presser stands now, one number per coordinate, for the editor's Here buttons; null where it isn't
     * known here.
     */
    default double[] position() {
        return null;
    }

    /** The current command catalog (it changes on reload); null before the game has listed its commands. */
    CommandCatalog catalog();

    /** Names of the players online now, for player pickers. */
    List<String> onlinePlayers();

    // ---- what the game says about a command's arguments --------------------------------------------------

    /** Whether the game would let the presser run the command: a hint, never a refusal. Null when it can't tell. */
    Boolean mayRun(String commandName);

    /** How many words the game reads for an argument; 0 when it doesn't say. {@code form} -1 is the main form. */
    int tokens(String commandName, int form, String arg);

    boolean isList(String commandName, int form, String arg);

    /** The ids the game knows for an item or block argument, sorted; empty when it has no list. */
    List<String> assetIds(ArgType type);

    // ---- runs ------------------------------------------------------------------------------------------

    CheckResult check(RunRequest request);

    /** Starts a run; how it ends is reported to the presser by the game, as for {@code /deck run}. */
    StartResult start(RunRequest request, String confirmToken);

    /** The presser's runs, or everyone's for an operator. */
    List<RunStatus> running();

    boolean stop(String runId);

    /** Stops everyone's runs (operators); how many were stopped. */
    int stopAll();

    /** Does the presser hold a macro's or preset's {@code permission}? */
    boolean holds(String permission);

    // ---- macros ----------------------------------------------------------------------------------------

    List<Macro> macros(Scope scope);

    /** Saves, or renames when {@code replacing} is another id. Null when saved, otherwise why not. */
    Problem saveMacro(Scope scope, Macro macro, String replacing);

    Problem deleteMacro(Scope scope, String id);

    // ---- favourites and typed history --------------------------------------------------------------------

    Set<String> favorites();

    /** Adds or removes a favourite; whether it is one now. */
    boolean toggleFavorite(String command);

    /** Most recent first. */
    List<String> history(String command);

    void remember(String command, String text);
}
