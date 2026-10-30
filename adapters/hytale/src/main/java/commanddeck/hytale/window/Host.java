package commanddeck.hytale.window;

import commanddeck.api.Presser;
import commanddeck.api.RunListener;
import commanddeck.command.ArgType;
import commanddeck.command.CommandCatalog;
import commanddeck.hytale.LockedDeck;
import commanddeck.macro.Limits;
import java.util.List;

/** What one player's window needs from the plugin. */
public interface Host {
    Presser presser();

    /** The deck, behind the lock that makes it safe from any thread. */
    LockedDeck deck();

    /** The current command catalog (it changes on reload); may be null before boot. */
    CommandCatalog catalog();

    MacroShelf macros();

    Prefs prefs();

    Limits limits();

    /** May this player save, rename and delete their own macros? */
    boolean canEditOwn();

    /** May this player edit server macros and stop everyone's runs? */
    boolean isOperator();

    /** Names of the players online now, for player pickers. */
    List<String> onlinePlayers();

    /** Told how a run started from the window ends (on the tick thread). */
    RunListener reporter();

    /**
     * Whether the game would let this player run the command, by the game's own permission check: a hint for
     * the window, never a refusal (the game decides at run time). Null when it can't tell.
     */
    default Boolean mayRun(String commandName) {
        return null;
    }

    /**
     * How many words the game reads for an argument (a rotation takes three) and whether it is a list; 0 and
     * false when the game doesn't say. {@code form} is -1 for the main form, else the variant's index.
     */
    default int tokens(String commandName, int form, String arg) {
        return 0;
    }

    default boolean isList(String commandName, int form, String arg) {
        return false;
    }

    /** The ids the game knows for an item or block argument, sorted; empty when it has no list (a text field then). */
    default List<String> assetIds(ArgType type) {
        return List.of();
    }
}
