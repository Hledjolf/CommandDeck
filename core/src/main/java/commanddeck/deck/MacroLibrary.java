package commanddeck.deck;

import commanddeck.macro.Macro;

/** The saved macros a run can refer to by id. Presets come from the command catalog instead. */
public interface MacroLibrary {
    /** One of the player's own macros, or null. */
    Macro playerMacro(String presserId, String id);

    /** A macro the server's operators saved, or null. */
    Macro serverMacro(String id);
}
