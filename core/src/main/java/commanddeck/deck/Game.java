package commanddeck.deck;

import commanddeck.api.Presser;
import commanddeck.macro.Placeholders;
import commanddeck.macro.Problem;
import commanddeck.run.CommandSink;

/** What the deck needs from the game adapter. Called from any thread unless noted. */
public interface Game {
    boolean online(String presserId);

    /** May this player use CommandDeck at all (on Hytale, {@code commanddeck.use})? */
    boolean mayUse(Presser presser);

    /** May this player stop other players' runs ({@code /deck stop all} rights)? */
    boolean isOperator(Presser presser);

    /**
     * Does this player hold a macro's or preset's {@code permission} node (format 1.1)? Operators and the
     * singleplayer world owner pass, as for CommandDeck's own nodes.
     */
    boolean holds(Presser presser, String permission);

    /** May this player name someone else as a run's target (on Hytale, {@code commanddeck.run_others})? */
    boolean mayRunForOthers(Presser presser);

    /** The name of the player with this id if they are online now, else null. For a run's target. */
    String onlineName(String playerId);

    /** Runs lines as this player, with the player's own permissions. Used on the tick thread. */
    CommandSink sinkFor(Presser presser);

    /** The player's state for placeholders, right now. Null when the player is offline. */
    Placeholders.Context context(Presser presser);

    /**
     * Null when a written line can pass the game's parser as it stands. Otherwise an {@code unsendable}
     * problem whose detail has the {@code reason} code and the {@code positions} of the offending characters.
     */
    Problem checkLine(String line);

    /** How placeholder values are escaped for the argument they land in; null when the game needs no escaping. */
    default Placeholders.Escaper escaper() {
        return null;
    }

    /** Is this presser the server console, which has its own run limit ({@code max_running_console})? */
    default boolean isConsole(Presser presser) {
        return false;
    }

    /**
     * {@code PreviewLine.permitted}: does the presser hold the game's own permission for this line's command?
     * Null when the game can't tell (an unknown command, a raw one, or a game without such a check).
     */
    default Boolean permitted(Presser presser, String line) {
        return null;
    }
}
