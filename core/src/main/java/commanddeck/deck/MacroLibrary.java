package commanddeck.deck;

import commanddeck.macro.Macro;
import java.util.List;

/** The saved macros a run can refer to by id. Presets come from the command catalog instead. */
public interface MacroLibrary {
    /** One of the player's own macros, or null. */
    Macro playerMacro(String presserId, String id);

    /** A macro the server's operators saved, or null. */
    Macro serverMacro(String id);

    /** All of the player's own macros, for {@code Deck.macros}. */
    default List<Macro> playerMacros(String presserId) {
        return List.of();
    }

    /** All the server's macros, for {@code Deck.macros}. */
    default List<Macro> serverMacros() {
        return List.of();
    }
}
