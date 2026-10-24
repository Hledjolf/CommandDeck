package commanddeck.run;

import commanddeck.api.CommandOutcome;
import commanddeck.api.OutcomeBasis;
import java.util.List;

/**
 * Where the runner sends command lines: the game adapter, running each line as the presser. Sending never
 * blocks; the runner polls {@link Pending#done()} on later ticks until the game has answered.
 */
public interface CommandSink {
    Pending send(String line);

    /** A sent command whose outcome may still be on its way. */
    interface Pending {
        boolean done();

        /** Valid once {@link #done()}. */
        CommandOutcome outcome();

        OutcomeBasis basis();

        /** False when nothing could confirm a {@code SENT}: the step is logged as uncertain. */
        boolean decided();

        List<String> response();
    }

    /** An outcome that is already known. */
    record Done(CommandOutcome outcome, OutcomeBasis basis, boolean decided, List<String> response) implements Pending {
        @Override
        public boolean done() {
            return true;
        }

        public static Done sent() {
            return new Done(CommandOutcome.SENT, OutcomeBasis.GAME_SIGNAL, true, List.of());
        }
    }
}
