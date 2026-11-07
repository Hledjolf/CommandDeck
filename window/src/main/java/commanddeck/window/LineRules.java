package commanddeck.window;

import commanddeck.command.ArgStyle;
import commanddeck.command.ArgType;
import commanddeck.macro.Problem;
import java.util.List;

/**
 * A game's command-line rules, as the window needs them: build the line for a form, check a line typed whole,
 * and say in words why a value can't be sent. The rules themselves stay in the game adapter.
 */
public interface LineRules {
    /**
     * One form field: its argument and the value typed. A flag's value is {@code true} or {@code false}.
     * {@code tokens} above 1 is a game type that reads that many words (a position, a rotation): the value is
     * that many words, each sent as a token of its own. {@code list} is a list argument: {@code a, b} or
     * {@code [a, b]}, sent as typed, because its commas and brackets are the list.
     */
    record Field(String name, ArgType type, ArgStyle style, boolean required, String value, int tokens, boolean list) {
        public Field(String name, ArgType type, ArgStyle style, boolean required, String value) {
            this(name, type, style, required, value, 1, false);
        }
    }

    /** Why a value can't be sent: a reason code and the character positions in the value (or line). */
    record Unsendable(String reason, List<Long> positions, String field) {}

    /** A built line, or why it can't be built. */
    record Built(String line, Unsendable unsendable) {
        public boolean sendable() {
            return unsendable == null;
        }
    }

    /** The line for a command and its form, or why it can't be built. */
    Built build(String command, List<Field> fields);

    /**
     * Null when a whole typed line can be sent as it stands; otherwise an {@code unsendable} problem whose detail
     * has the {@code reason} and the {@code positions} of the offending characters.
     */
    Problem checkLine(String line);

    /** A sentence for people, for an unsendable reason code. */
    String message(String reason);
}
