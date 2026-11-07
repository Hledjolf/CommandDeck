package commanddeck.window;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the window draws, as a list of commands any toolkit can follow, in order. Elements are named by their
 * part of the window, a row index and a child, never by a toolkit's selectors; rows are named by kind, never by
 * a layout file. Each game has a renderer that turns these into its own UI calls.
 *
 * <p>A binding sends its action and params when it fires, plus the current value of each captured element under
 * the capture's name. Clicks capture the fields of the form or the editor, because a client may drop field
 * events while an update is unacknowledged.
 */
public final class ViewOps {
    /** The parts of the window. {@link #TAB} is indexed by the tab's ordinal. */
    public enum Part {
        TAB, SEARCH, FILTER, FILTER_ROW, LIST, COUNT, HEADING, META, ROWS, FOOTER, PREVIEW, PREVIEW_NOTE, STATUS,
        CONFIRM_BOX, CONFIRM_TEXT, CONFIRM_RISK, CONFIRM_YES, CONFIRM_NO
    }

    /** Elements inside a row, a list entry or the footer. */
    public enum Child {
        NONE, LABEL, INPUT, HINT, BUTTON, BUTTON_LABEL, TEXT, KIND, DEPTH, FLAG, FLAG_LABEL, STOP, UP, DOWN, OUT, IN, DEL,
        RUN, ADD_TO_MACRO, FAVORITE, SAVE, RUN_MACRO, DELETE, REFRESH, STOP_ALL, ADD_COMMAND, ADD_WAIT, ADD_LOOP,
        ADD_SEQUENCE, ADD_CALL,
        /** A loop row's limit, shown beside its count. */
        LIMIT,
        /** A row's problem, under it. */
        PROBLEM,
        /** A condition field's selector, and its coordinates. */
        CHOICE, X, Y, Z, HERE, ADD_CHECK
    }

    /**
     * The kinds of row appended into the list, the form or editor, and the footer. In the editor, a loop has a
     * {@link #LOOP_ROW}, each of its checks a {@link #CONDITION_ROW} with a {@link #FIELD_ROW} per field, and the
     * loop's block ends with an {@link #ADD_CHECK_ROW}.
     */
    public enum RowKind {
        LIST_ROW, LIST_HEADING, NOTE_ROW, TEXT_ROW, CHECK_ROW, CHOICE_ROW, STEP_ROW, RUN_ROW, FORM_FOOTER, EDITOR_FOOTER,
        RUNS_FOOTER, LOOP_ROW, CONDITION_ROW, FIELD_ROW, ADD_CHECK_ROW
    }

    public enum Prop {
        TEXT, VALUE, VISIBLE, DISABLED
    }

    public enum Trigger {
        /** A button press. */
        ACTIVATE,
        /** A field's value changed. */
        CHANGE
    }

    /** An element: a part, the index of a row in it (-1 for the part itself), and a child (NONE for the row itself). */
    public record Ref(Part part, int index, Child child) {
        public static Ref of(Part part) {
            return new Ref(part, -1, Child.NONE);
        }

        public static Ref row(int index, Child child) {
            return new Ref(Part.ROWS, index, child);
        }

        public static Ref footer(Child child) {
            return new Ref(Part.FOOTER, 0, child);
        }
    }

    public sealed interface Op permits Set, Entries, Clear, Append, Bind {}

    /** Sets a property; the value is a String, Boolean or Double. */
    public record Set(Ref ref, Prop prop, Object value) implements Op {}

    /** Sets a dropdown's entries. */
    public record Entries(Ref ref, List<Entry> entries) implements Op {}

    public record Entry(String label, String value) {}

    /** Removes every row of a part. */
    public record Clear(Part part) implements Op {}

    /** Appends a row of a kind at the end of a part; it gets the next index. */
    public record Append(Part part, RowKind kind) implements Op {}

    /** Binds an event: its action and params, and the current values of the captured elements, by name. */
    public record Bind(Ref ref, Trigger trigger, String action, Map<String, String> params, Map<String, Ref> captures) implements Op {}

    private final List<Op> ops = new ArrayList<>();

    public ViewOps set(Ref ref, Prop prop, Object value) {
        if (!(value instanceof String || value instanceof Boolean || value instanceof Double)) {
            throw new IllegalArgumentException("UI values are String, Boolean or Double, not " + (value == null ? "null" : value.getClass().getSimpleName()));
        }
        ops.add(new Set(ref, prop, value));
        return this;
    }

    public ViewOps text(Ref ref, String text) {
        return set(ref, Prop.TEXT, text == null ? "" : text);
    }

    public ViewOps value(Ref ref, Object value) {
        return set(ref, Prop.VALUE, value);
    }

    public ViewOps visible(Ref ref, boolean visible) {
        return set(ref, Prop.VISIBLE, visible);
    }

    public ViewOps disabled(Ref ref, boolean disabled) {
        return set(ref, Prop.DISABLED, disabled);
    }

    public ViewOps entries(Ref ref, List<Entry> entries) {
        ops.add(new Entries(ref, List.copyOf(entries)));
        return this;
    }

    public ViewOps clear(Part part) {
        ops.add(new Clear(part));
        return this;
    }

    public ViewOps append(Part part, RowKind kind) {
        ops.add(new Append(part, kind));
        return this;
    }

    public ViewOps bind(Ref ref, Trigger trigger, String action, Map<String, String> params, Map<String, Ref> captures) {
        ops.add(new Bind(ref, trigger, action, new LinkedHashMap<>(params), new LinkedHashMap<>(captures)));
        return this;
    }

    public List<Op> ops() {
        return List.copyOf(ops);
    }

    public boolean isEmpty() {
        return ops.isEmpty();
    }
}
