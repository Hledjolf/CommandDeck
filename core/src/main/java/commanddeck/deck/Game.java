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
}
