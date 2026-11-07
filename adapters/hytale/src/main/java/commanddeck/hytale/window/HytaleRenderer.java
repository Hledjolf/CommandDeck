package commanddeck.hytale.window;

import commanddeck.window.ViewOps;
import commanddeck.window.ViewOps.Child;
import commanddeck.window.ViewOps.Part;
import commanddeck.window.ViewOps.Ref;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The window drawn on Hytale: each of the window model's commands becomes the matching command on CommandDeck's
 * {@code .ui} page. A part is an element id ({@code #Rows}), a row is an index into it ({@code #Rows[3]}), a child
 * is an element inside the row ({@code #Rows[3] #Input}), and a row kind is one of the page's row documents.
 * A binding's captures are sent back as {@code @<name>}; {@link #event} removes the {@code @} again.
 */
public final class HytaleRenderer {
    private HytaleRenderer() {}

    public static final String DIR = "CommandDeck/";

    /** The page's document for each row kind (path relative to {@code Common/UI/Custom}). */
    public static String document(ViewOps.RowKind kind) {
        return switch (kind) {
            case LIST_ROW -> DIR + "ListRow.ui";
            case LIST_HEADING -> DIR + "ListHeading.ui";
            case NOTE_ROW -> DIR + "NoteRow.ui";
            case TEXT_ROW -> DIR + "TextRow.ui";
            case CHECK_ROW -> DIR + "CheckRow.ui";
            case CHOICE_ROW -> DIR + "ChoiceRow.ui";
            case STEP_ROW -> DIR + "StepRow.ui";
            case RUN_ROW -> DIR + "RunRow.ui";
            case FORM_FOOTER -> DIR + "FormFooter.ui";
            case EDITOR_FOOTER -> DIR + "EditorFooter.ui";
            case RUNS_FOOTER -> DIR + "RunsFooter.ui";
            case LOOP_ROW -> DIR + "LoopRow.ui";
            case CONDITION_ROW -> DIR + "ConditionRow.ui";
            case FIELD_ROW -> DIR + "FieldRow.ui";
            case ADD_CHECK_ROW -> DIR + "AddCheckRow.ui";
        };
    }

    public static UiOps render(ViewOps view) {
        UiOps ops = new UiOps();
        for (ViewOps.Op op : view.ops()) {
            if (op instanceof ViewOps.Set s) {
                ops.set(selector(s.ref()) + "." + prop(s.prop()), s.value());
            } else if (op instanceof ViewOps.Entries e) {
                List<UiOps.Entry> entries = new ArrayList<>();
                for (ViewOps.Entry x : e.entries()) entries.add(new UiOps.Entry(x.label(), x.value()));
                ops.entries(selector(e.ref()), entries);
            } else if (op instanceof ViewOps.Clear c) {
                ops.clear(part(c.part(), -1));
            } else if (op instanceof ViewOps.Append a) {
                ops.append(part(a.part(), -1), document(a.kind()));
            } else if (op instanceof ViewOps.Bind b) {
                Map<String, String> data = new LinkedHashMap<>();
                data.put("Action", b.action());
                data.putAll(b.params());
                for (Map.Entry<String, Ref> c : b.captures().entrySet()) data.put("@" + c.getKey(), selector(c.getValue()) + ".Value");
                ops.bind(selector(b.ref()), b.trigger() == ViewOps.Trigger.ACTIVATE ? "Activating" : "ValueChanged", data);
            }
        }
        return ops;
    }

    /** An event's data as the model reads it: captured values under their names, without the {@code @}. */
    public static Map<String, Object> event(Map<String, Object> data) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : data.entrySet()) {
            out.put(e.getKey().startsWith("@") ? e.getKey().substring(1) : e.getKey(), e.getValue());
        }
        return out;
    }

    static String selector(Ref r) {
        String s = part(r.part(), r.index());
        return r.child() == Child.NONE ? s : s + " " + child(r.child());
    }

    private static String part(Part p, int index) {
        String id = switch (p) {
            case TAB -> "#Tab" + cap(commanddeck.window.WindowModel.Tab.values()[index].name());
            case SEARCH -> "#Search";
            case FILTER -> "#Filter";
            case FILTER_ROW -> "#FilterRow";
            case LIST -> "#List";
            case COUNT -> "#Count";
            case HEADING -> "#Heading";
            case META -> "#Meta";
            case ROWS -> "#Rows";
            case FOOTER -> "#Footer";
            case PREVIEW -> "#Preview";
            case PREVIEW_NOTE -> "#PreviewNote";
            case STATUS -> "#Status";
            case CONFIRM_BOX -> "#ConfirmBox";
            case CONFIRM_TEXT -> "#ConfirmText";
            case CONFIRM_RISK -> "#ConfirmRisk";
            case CONFIRM_YES -> "#ConfirmYes";
            case CONFIRM_NO -> "#ConfirmNo";
        };
        return p == Part.TAB || index < 0 ? id : id + "[" + index + "]";
    }

    private static String child(Child c) {
        return switch (c) {
            case BUTTON_LABEL -> "#Button #Label";
            case FLAG_LABEL -> "#FlagLabel";
            case ADD_TO_MACRO -> "#AddToMacro";
            case RUN_MACRO -> "#RunMacro";
            case STOP_ALL -> "#StopAll";
            case ADD_COMMAND -> "#AddCommand";
            case ADD_WAIT -> "#AddWait";
            case ADD_LOOP -> "#AddLoop";
            case ADD_CHECK -> "#AddCheck";
            case ADD_SEQUENCE -> "#AddSequence";
            case ADD_CALL -> "#AddCall";
            default -> "#" + cap(c.name());
        };
    }

    private static String prop(ViewOps.Prop p) {
        return cap(p.name());
    }

    private static String cap(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
}
