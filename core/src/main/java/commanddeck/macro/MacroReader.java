package commanddeck.macro;

import static commanddeck.json.Checked.array;
import static commanddeck.json.Checked.bool;
import static commanddeck.json.Checked.integer;
import static commanddeck.json.Checked.maxLength;
import static commanddeck.json.Checked.need;
import static commanddeck.json.Checked.number;
import static commanddeck.json.Checked.object;
import static commanddeck.json.Checked.only;
import static commanddeck.json.Checked.string;

import commanddeck.json.Checked;
import commanddeck.json.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a macro document or a body node from parsed JSON and checks every rule of the macro format, by hand so
 * every rule is explicit and translates to the other cores. The first problem found wins, reported as
 * {@code invalid} with a JSON Pointer. A loop's condition is only read as an object here; the game's
 * {@link ConditionPort} checks it when the macro is saved or run.
 */
public final class MacroReader {
    /** The macro format version this CommandDeck runs. */
    public static final String SUPPORTED = "1.2";

    private static final Set<String> DOC_KEYS = Set.of("format", "format_version", "id", "name", "description", "dangerous", "body",
        "permission", "category");
    private static final Set<String> COMMAND_KEYS = Set.of("type", "line", "confirm", "note");
    private static final Set<String> SEQUENCE_KEYS = Set.of("type", "steps", "note");
    private static final Set<String> REPEAT_KEYS = Set.of("type", "times", "body", "note");
    private static final Set<String> WAIT_KEYS = Set.of("type", "ticks", "seconds", "note");
    private static final Set<String> CALL_KEYS = Set.of("type", "macro", "note");
    private static final Set<String> LOOP_KEYS = Set.of("type", "condition", "body", "max_iterations", "note");

    private MacroReader() {}

    /** Either a value or the problem that stopped it. */
    public record Result<T>(T value, Problem problem) {
        public boolean ok() {
            return problem == null;
        }
    }

    /** Thrown inside the reader to unwind with the first problem. */
    private static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final transient Problem problem;

        Refused(Problem problem) {
            super(problem.message(), null, false, false);
            this.problem = problem;
        }
    }

    public static Result<Macro> readDocument(Object json) {
        try {
            Checked.inRange(json, "");
            Map<String, Object> m = object(json, "");
            only(m, DOC_KEYS, "");
            Object format = need(m, "format", "");
            if (!"commanddeck.macro".equals(format)) throw invalid("/format", "format must be \"commanddeck.macro\"");
            String version = string(need(m, "format_version", ""), "/format_version");
            if (!Checked.isFormatVersion(version)) throw invalid("/format_version", "format_version must look like 1.0");
            String id = string(need(m, "id", ""), "/id");
            if (!validId(id)) {
                throw invalid("/id", "Macro ids use lowercase letters, digits, _ . and -; ':' and '/' are reserved for scoped references");
            }
            String name = string(need(m, "name", ""), "/name");
            maxLength(name, 60, "/name");
            String description = null;
            if (m.containsKey("description")) {
                description = string(m.get("description"), "/description");
                maxLength(description, 1000, "/description");
            }
            boolean dangerous = m.containsKey("dangerous") && bool(m.get("dangerous"), "/dangerous");
            String permission = null;
            if (m.containsKey("permission")) {
                permission = string(m.get("permission"), "/permission");
                if (permission.isEmpty()) throw invalid("/permission", "permission must not be empty");
                if (minor(version) < 1) throw invalid("/permission", "permission needs format_version 1.1");
            }
            String category = null;
            if (m.containsKey("category")) {
                category = string(m.get("category"), "/category");
                maxLength(category, 40, "/category");
                if (minor(version) < 1) throw invalid("/category", "category needs format_version 1.1");
            }
            Node body = node(need(m, "body", ""), "/body", minor(version));
            return new Result<>(new Macro(version, id, name, description, dangerous, body, permission, category), null);
        } catch (Refused r) {
            return new Result<>(null, r.problem);
        } catch (Checked.Invalid e) {
            return new Result<>(null, Problem.invalid(e.pointer, e.getMessage()));
        }
    }

    /** A body node on its own: a preset's {@code macro}, or a {@code MACRO_JSON} item from the integration API. */
    public static Result<Node> readNode(Object json) {
        try {
            Checked.inRange(json, "");
            return new Result<>(node(json, "", -1), null);
        } catch (Refused r) {
            return new Result<>(null, r.problem);
        } catch (Checked.Invalid e) {
            return new Result<>(null, Problem.invalid(e.pointer, e.getMessage()));
        }
    }

    /** {@link #readNode} from JSON text; a syntax error is {@code invalid} at the top. */
    public static Result<Node> readNodeText(String text) {
        Object json;
        try {
            json = Json.parse(text);
        } catch (Json.ParseException e) {
            return new Result<>(null, Problem.invalid("", "Not valid JSON: " + e.getMessage()));
        }
        return readNode(json);
    }

    /** The minor number of a format version that looks like {@code 1.N}. */
    public static int minor(String version) {
        try {
            return Integer.parseInt(version.substring(2));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** The 1.0 macro id rule: {@code ^[a-z0-9][a-z0-9_.-]*$}. */
    public static boolean validId(String id) {
        if (id.isEmpty()) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean alnum = c >= 'a' && c <= 'z' || c >= '0' && c <= '9';
            if (!alnum && (i == 0 || c != '_' && c != '.' && c != '-')) return false;
        }
        return true;
    }

    /**
     * A {@code call}'s reference: a macro id, or {@code player:<id>}, {@code server:<id>} or
     * {@code preset:<mod id>/<id>} (the schema's {@code ^(player:|server:|preset:.+/)?[a-z0-9][a-z0-9_.-]*$}).
     */
    public static boolean validReference(String ref) {
        if (ref.startsWith("player:")) return validId(ref.substring(7));
        if (ref.startsWith("server:")) return validId(ref.substring(7));
        if (ref.startsWith("preset:")) {
            int slash = ref.lastIndexOf('/');
            return slash > 7 && validId(ref.substring(slash + 1));
        }
        return validId(ref);
    }

    // ---- nodes ----------------------------------------------------------------------------------------

    private static Node node(Object json, String at, int minor) {
        Map<String, Object> m = object(json, at);
        String type = string(need(m, "type", at), at + "/type");
        switch (type) {
            case "command": {
                only(m, COMMAND_KEYS, at);
                String line = string(need(m, "line", at), at + "/line");
                if (line.isEmpty()) throw invalid(at + "/line", "line must not be empty");
                maxLength(line, 1000, at + "/line");
                boolean confirm = m.containsKey("confirm") && bool(m.get("confirm"), at + "/confirm");
                return new Node.Command(line, confirm, note(m, at, 200));
            }
            case "sequence": {
                only(m, SEQUENCE_KEYS, at);
                List<Object> raw = array(need(m, "steps", at), at + "/steps");
                if (raw.isEmpty()) throw invalid(at + "/steps", "a sequence needs at least one step");
                List<Node> steps = new ArrayList<>();
                for (int i = 0; i < raw.size(); i++) steps.add(node(raw.get(i), at + "/steps/" + i, minor));
                return new Node.Sequence(List.copyOf(steps), note(m, at, 200));
            }
            case "repeat": {
                only(m, REPEAT_KEYS, at);
                long times = integer(need(m, "times", at), at + "/times");
                if (times < 1) throw invalid(at + "/times", "times must be at least 1");
                Node body = node(need(m, "body", at), at + "/body", minor);
                return new Node.Repeat(times, body, note(m, at, 200));
            }
            case "wait": {
                only(m, WAIT_KEYS, at);
                boolean hasTicks = m.containsKey("ticks");
                boolean hasSeconds = m.containsKey("seconds");
                if (hasTicks == hasSeconds) throw invalid(at, "a wait needs exactly one of ticks or seconds");
                if (hasTicks) {
                    long ticks = integer(m.get("ticks"), at + "/ticks");
                    if (ticks < 1) throw invalid(at + "/ticks", "ticks must be at least 1");
                    return new Node.Wait(ticks, 0, note(m, at, -1));
                }
                double seconds = number(m.get("seconds"), at + "/seconds");
                if (!(seconds > 0)) throw invalid(at + "/seconds", "seconds must be more than 0");
                return new Node.Wait(0, seconds, note(m, at, -1));
            }
            case "call": {
                only(m, CALL_KEYS, at);
                String ref = string(need(m, "macro", at), at + "/macro");
                if (!validReference(ref)) {
                    throw invalid(at + "/macro", "A call names a macro id, or player:<id>, server:<id> or preset:<mod id>/<id>");
                }
                if (minor >= 0 && minor < 2) throw invalid(at + "/type", "call needs format_version 1.2");
                return new Node.Call(ref, note(m, at, 200));
            }
            case "while":
            case "until": {
                only(m, LOOP_KEYS, at);
                Map<String, Object> condition = object(need(m, "condition", at), at + "/condition");
                Node body = node(need(m, "body", at), at + "/body", minor);
                long max = 0;
                if (m.containsKey("max_iterations")) {
                    max = integer(m.get("max_iterations"), at + "/max_iterations");
                    if (max < 1) throw invalid(at + "/max_iterations", "max_iterations must be at least 1");
                }
                if (minor >= 0 && minor < 2) throw invalid(at + "/type", type + " needs format_version 1.2");
                return new Node.Loop(type.equals("until"), condition, body, max, note(m, at, 200));
            }
            default:
                throw invalid(at + "/type", "unknown node type \"" + type + "\"");
        }
    }

    private static String note(Map<String, Object> m, String at, int max) {
        if (!m.containsKey("note")) return null;
        String n = string(m.get("note"), at + "/note");
        if (max >= 0) maxLength(n, max, at + "/note");
        return n;
    }

    // ---- JSON shape helpers ---------------------------------------------------------------------------

    private static Checked.Invalid invalid(String pointer, String message) {
        return new Checked.Invalid(pointer, message);
    }
}
