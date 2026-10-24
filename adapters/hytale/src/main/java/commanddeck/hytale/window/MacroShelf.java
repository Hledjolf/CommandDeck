package commanddeck.hytale.window;

import commanddeck.macro.Macro;
import commanddeck.macro.Problem;
import java.util.List;

/** Where the window reads and saves macros: a player's own, or the server's. */
public interface MacroShelf {
    enum Scope {
        PLAYER,
        SERVER
    }

    /** In id order. {@code presserId} is ignored for the server's. */
    List<Macro> list(Scope scope, String presserId);

    /**
     * Saves a macro after checking it like a macro read from a file, and against the operator's limits.
     * {@code replacing} is the id it had before (null for a new macro); a different id renames it. Null when
     * saved, otherwise why not.
     */
    Problem save(Scope scope, String presserId, Macro macro, String replacing);

    Problem delete(Scope scope, String presserId, String id);
}
