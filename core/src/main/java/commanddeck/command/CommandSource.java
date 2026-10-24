package commanddeck.command;

import java.util.List;

/** The game adapter's view of the game's command registry. */
public interface CommandSource {
    /**
     * Every command the running game has registered, typed or raw. Null when the game can't list its
     * commands; the sidecars are then the list.
     */
    List<CommandInfo> discover();
}
