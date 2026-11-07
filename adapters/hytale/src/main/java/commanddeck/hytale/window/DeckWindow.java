package commanddeck.hytale.window;

import commanddeck.json.Json;
import commanddeck.window.DeckSession;
import commanddeck.window.LineRules;
import commanddeck.window.MacroDraft;
import commanddeck.window.ViewOps.RowKind;
import commanddeck.window.WindowModel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One player's CommandDeck window on Hytale: the game-neutral {@link WindowModel}, drawn on CommandDeck's
 * {@code .ui} page by {@link HytaleRenderer}. The page's events arrive here with their data, and each answer is
 * a partial update of the page.
 */
public final class DeckWindow {
    public static final String DIR = HytaleRenderer.DIR;
    public static final String DOC = DIR + "Window.ui";
    public static final String LIST_ROW = HytaleRenderer.document(RowKind.LIST_ROW);
    public static final String LIST_HEADING = HytaleRenderer.document(RowKind.LIST_HEADING);
    public static final String NOTE_ROW = HytaleRenderer.document(RowKind.NOTE_ROW);
    public static final String TEXT_ROW = HytaleRenderer.document(RowKind.TEXT_ROW);
    public static final String CHECK_ROW = HytaleRenderer.document(RowKind.CHECK_ROW);
    public static final String CHOICE_ROW = HytaleRenderer.document(RowKind.CHOICE_ROW);
    public static final String STEP_ROW = HytaleRenderer.document(RowKind.STEP_ROW);
    public static final String RUN_ROW = HytaleRenderer.document(RowKind.RUN_ROW);
    public static final String FORM_FOOTER = HytaleRenderer.document(RowKind.FORM_FOOTER);
    public static final String EDITOR_FOOTER = HytaleRenderer.document(RowKind.EDITOR_FOOTER);
    public static final String RUNS_FOOTER = HytaleRenderer.document(RowKind.RUNS_FOOTER);
    public static final String LOOP_ROW = HytaleRenderer.document(RowKind.LOOP_ROW);
    public static final String CONDITION_ROW = HytaleRenderer.document(RowKind.CONDITION_ROW);
    public static final String FIELD_ROW = HytaleRenderer.document(RowKind.FIELD_ROW);
    public static final String ADD_CHECK_ROW = HytaleRenderer.document(RowKind.ADD_CHECK_ROW);

    /** Every document the window uses (the plugin checks they are registered). */
    public static final List<String> DOCUMENTS = List.of(DOC, LIST_ROW, LIST_HEADING, NOTE_ROW, TEXT_ROW, CHECK_ROW, CHOICE_ROW, STEP_ROW, RUN_ROW,
        FORM_FOOTER, EDITOR_FOOTER, RUNS_FOOTER, LOOP_ROW, CONDITION_ROW, FIELD_ROW, ADD_CHECK_ROW);

    static final String NO_PERMISSION = WindowModel.NO_PERMISSION;
    static final String NOT_SENT = WindowModel.NOT_SENT;
    static final int HEADER_ROWS = WindowModel.HEADER_ROWS;

    private final WindowModel model;

    public DeckWindow(DeckSession session, LineRules lines) {
        this.model = new WindowModel(session, lines);
    }

    public synchronized WindowModel.Tab tab() {
        return model.tab();
    }

    public synchronized String status() {
        return model.status();
    }

    public synchronized int generation() {
        return model.generation();
    }

    public synchronized MacroDraft draft() {
        return model.draft();
    }

    /** Whether the macro being edited has changes that aren't saved. */
    public synchronized boolean unsaved() {
        return model.unsaved();
    }

    public synchronized String selectedCommand() {
        return model.selectedCommand();
    }

    /** Everything after the main document is appended: the static bindings plus both sides. */
    public synchronized UiOps open() {
        return HytaleRenderer.render(model.open());
    }

    /** Whether the page should redraw this event's result only once typing pauses (see {@link #deferredOps}). */
    public static boolean deferred(String action) {
        return WindowModel.deferred(action);
    }

    /** The redraw for a deferred event, built from the latest state. */
    public synchronized UiOps deferredOps(String action) {
        return HytaleRenderer.render(model.deferredOps(action));
    }

    /** A client event: its {@code Action} and the rest of its data. */
    public record Event(String action, Map<String, Object> data) {}

    /**
     * Decodes the data string of a client's page event (the JSON object built from a binding, with each
     * {@code @} key replaced by the element's value). Throws if it isn't a JSON object.
     */
    public static Event decode(String rawData) {
        Map<String, Object> data = new LinkedHashMap<>(Json.parseObject(rawData));
        Object a = data.remove("Action");
        return new Event(a == null ? null : a.toString(), data);
    }

    /** Handles one event from the page. The returned ops are sent as a partial update (possibly none). */
    public synchronized UiOps onEvent(String action, Map<String, Object> data) {
        return HytaleRenderer.render(model.onEvent(action, HytaleRenderer.event(data)));
    }
}
