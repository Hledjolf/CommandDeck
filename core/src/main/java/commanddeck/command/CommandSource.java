package commanddeck.command;

import java.util.List;

/** The game adapter's view of the game's command registry. */
public interface CommandSource {
    /**
     * Every command the running game has registered, typed or raw, in registration order. Several
     * registrations of one name become one command with variants. Null when the game can't list its
     * commands; the sidecars are then the list.
     */
    List<CommandInfo> discover();

    /** How the game compares command names. */
    default NameRule names() {
        return NameRule.EXACT;
    }
}
