package commanddeck.macro;

import java.util.List;
import java.util.Map;

/**
 * One node of a macro tree. CommandDeck 1.0 has four node types, and 1.2 adds {@code call}, {@code while} and
 * {@code until}.
 */
public interface Node {
    /** The node's {@code type} as written in JSON. */
    String type();

    /** Run one command line after filling placeholders. {@code confirm} asks before this step. */
    record Command(String line, boolean confirm, String note) implements Node {
        @Override
        public String type() {
            return "command";
        }
    }

    /** Run the steps in order. */
    record Sequence(List<Node> steps, String note) implements Node {
        @Override
        public String type() {
            return "sequence";
        }
    }

    /** Run the body {@code times} times. */
    record Repeat(long times, Node body, String note) implements Node {
        @Override
        public String type() {
            return "repeat";
        }
    }

    /** Pause for {@code ticks} ticks, or for {@code seconds} of real time (exactly one is set; the other is 0). */
    record Wait(long ticks, double seconds, String note) implements Node {
        @Override
        public String type() {
            return "wait";
        }
    }

    /**
     * {@code while} (check, then run the body, while the condition holds) or {@code until} (run the body, then
     * check, until it holds), format 1.2. {@code condition} is the condition object as read, checked by the game's
     * {@link ConditionPort}. {@code maxIterations} is the loop's own limit, or 0 for the operator's.
     */
    record Loop(boolean until, Map<String, Object> condition, Node body, long maxIterations, String note) implements Node {
        @Override
        public String type() {
            return until ? "until" : "while";
        }
    }

    /**
     * Run another macro in place (format 1.2). {@code macro} is the reference as written. As read, {@code body}
     * and {@code ref} are null; once the run is checked ({@link Calls}), {@code body} is a copy of the called
     * macro's body and {@code ref} its scoped reference, such as {@code server:restock}.
     */
    record Call(String macro, String note, Node body, String ref) implements Node {
        public Call(String macro, String note) {
            this(macro, note, null, null);
        }

        @Override
        public String type() {
            return "call";
        }
    }
}
