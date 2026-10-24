package commanddeck.macro;

import java.util.List;

/**
 * One node of a macro tree. CommandDeck 1.0 has four node types; {@code while}, {@code until}
 * and {@code call} are known names that the reader refuses with {@code needs_newer}.
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
}
