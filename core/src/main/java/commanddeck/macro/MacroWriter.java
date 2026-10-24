package commanddeck.macro;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes macros back to JSON values ({@code Map}, {@code List}, {@code String}, {@code Long}, {@code Double},
 * {@code Boolean}), the inverse of {@link MacroReader}. Fields are written in the format's usual order, and
 * optional fields only when set.
 */
public final class MacroWriter {
    private MacroWriter() {}

    public static Map<String, Object> document(Macro m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("format", "commanddeck.macro");
        out.put("format_version", m.formatVersion() == null ? MacroReader.SUPPORTED : m.formatVersion());
        out.put("id", m.id());
        out.put("name", m.name());
        if (m.description() != null) out.put("description", m.description());
        if (m.dangerous()) out.put("dangerous", true);
        out.put("body", node(m.body()));
        return out;
    }

    public static Map<String, Object> node(Node n) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", n.type());
        String note = null;
        if (n instanceof Node.Command) {
            Node.Command c = (Node.Command) n;
            out.put("line", c.line());
            if (c.confirm()) out.put("confirm", true);
            note = c.note();
        } else if (n instanceof Node.Sequence) {
            Node.Sequence s = (Node.Sequence) n;
            List<Object> steps = new ArrayList<>();
            for (Node step : s.steps()) steps.add(node(step));
            out.put("steps", steps);
            note = s.note();
        } else if (n instanceof Node.Repeat) {
            Node.Repeat r = (Node.Repeat) n;
            out.put("times", r.times());
            out.put("body", node(r.body()));
            note = r.note();
        } else if (n instanceof Node.Wait) {
            Node.Wait w = (Node.Wait) n;
            if (w.ticks() > 0) {
                out.put("ticks", w.ticks());
            } else {
                out.put("seconds", w.seconds());
            }
            note = w.note();
        }
        if (note != null) out.put("note", note);
        return out;
    }
}
