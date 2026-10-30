package commanddeck.command;

import static commanddeck.json.Checked.array;
import static commanddeck.json.Checked.need;
import static commanddeck.json.Checked.object;
import static commanddeck.json.Checked.only;
import static commanddeck.json.Checked.optBool;
import static commanddeck.json.Checked.optNumber;
import static commanddeck.json.Checked.optString;
import static commanddeck.json.Checked.string;

import commanddeck.json.Checked;
import commanddeck.json.Json;
import commanddeck.macro.MacroReader;
import commanddeck.macro.Node;
import commanddeck.macro.Problem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a command sidecar ({@code commanddeck/commands.json} in a mod, or a server owner's override) and
 * checks every rule of the format by hand. A problem in the document itself refuses the whole sidecar:
 * {@code invalid} with a JSON Pointer. Since 1.1, a problem inside one command or preset skips only that
 * entry, and the problem goes in {@link Sidecar#skipped} (a preset's own macro problem with its pointer made
 * relative to the whole file).
 */
public final class SidecarReader {
    private static final Set<String> DOC_KEYS = Set.of("format", "format_version", "mod", "commands", "presets");
    private static final Set<String> MOD_KEYS = Set.of("id", "name", "version");
    private static final Set<String> COMMAND_KEYS = Set.of("name", "aliases", "label", "description", "category", "danger",
        "permission", "args", "variants", "examples", "hidden");
    private static final Set<String> ARG_KEYS = Set.of("name", "type", "style", "required", "default", "min", "max", "step",
        "options", "description", "placeholders");
    private static final Set<String> VARIANT_KEYS = Set.of("label", "description", "args");
    private static final Set<String> EXAMPLE_KEYS = Set.of("line", "explanation");
    private static final Set<String> PRESET_KEYS = Set.of("id", "name", "description", "category", "permission", "macro");

    private SidecarReader() {}

    public static MacroReader.Result<Sidecar> read(Object json) {
        try {
            return new MacroReader.Result<>(sidecar(json), null);
        } catch (Checked.Invalid e) {
            return new MacroReader.Result<>(null, Problem.invalid(e.pointer, e.getMessage()));
        } catch (PresetRefused e) {
            return new MacroReader.Result<>(null, e.problem);
        }
    }

    /** {@link #read} from JSON text; a syntax error is {@code invalid} at the top. */
    public static MacroReader.Result<Sidecar> readText(String text) {
        Object json;
        try {
            json = Json.parse(text);
        } catch (Json.ParseException e) {
            return new MacroReader.Result<>(null, Problem.invalid("", "Not valid JSON: " + e.getMessage()));
        }
        return read(json);
    }

    /** A preset's macro problem, carried out of the reader. */
    private static final class PresetRefused extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final transient Problem problem;

        PresetRefused(Problem problem) {
            super(problem.message(), null, false, false);
            this.problem = problem;
        }
    }

    private static Sidecar sidecar(Object json) {
        Map<String, Object> m = object(json, "");
        only(m, DOC_KEYS, "");
        if (!"commanddeck.commands".equals(need(m, "format", ""))) {
            throw new Checked.Invalid("/format", "format must be \"commanddeck.commands\"");
        }
        String version = string(need(m, "format_version", ""), "/format_version");
        if (!version.matches("1\\.[0-9]+")) throw new Checked.Invalid("/format_version", "format_version must look like 1.0");
        Map<String, Object> mod = object(need(m, "mod", ""), "/mod");
        only(mod, MOD_KEYS, "/mod");
        String modId = string(need(mod, "id", "/mod"), "/mod/id");
        String modName = optString(mod, "name", "/mod", -1);
        String modVersion = optString(mod, "version", "/mod", -1);
        List<Object> rawCommands = array(need(m, "commands", ""), "/commands");
        List<Problem> skipped = new ArrayList<>();
        List<CommandInfo> commands = new ArrayList<>();
        for (int i = 0; i < rawCommands.size(); i++) {
            try {
                commands.add(command(rawCommands.get(i), "/commands/" + i));
            } catch (Checked.Invalid e) {
                skipped.add(Problem.invalid(e.pointer, e.getMessage()));
            }
        }
        List<Preset> presets = new ArrayList<>();
        if (m.containsKey("presets")) {
            List<Object> raw = array(m.get("presets"), "/presets");
            for (int i = 0; i < raw.size(); i++) {
                try {
                    presets.add(preset(raw.get(i), "/presets/" + i, MacroReader.minor(version)));
                } catch (Checked.Invalid e) {
                    skipped.add(Problem.invalid(e.pointer, e.getMessage()));
                } catch (PresetRefused e) {
                    skipped.add(e.problem);
                }
            }
        }
        return new Sidecar(version, modId, modName, modVersion, List.copyOf(commands), List.copyOf(presets), List.copyOf(skipped));
    }

    private static CommandInfo command(Object json, String at) {
        Map<String, Object> m = object(json, at);
        only(m, COMMAND_KEYS, at);
        String name = string(need(m, "name", at), at + "/name");
        if (!validCommandName(name)) {
            throw new Checked.Invalid(at + "/name", "a command name is words of letters, digits and _ . : - separated by single spaces");
        }
        List<String> aliases = m.containsKey("aliases") ? strings(m.get("aliases"), at + "/aliases", false) : List.of();
        String label = optString(m, "label", at, 60);
        String description = optString(m, "description", at, 1000);
        String category = optString(m, "category", at, 40);
        Danger danger = Danger.NONE;
        if (m.containsKey("danger")) {
            String d = string(m.get("danger"), at + "/danger");
            danger = Danger.fromId(d);
            if (danger == null) throw new Checked.Invalid(at + "/danger", "danger must be none, confirm or save_risk");
        }
        String permission = optString(m, "permission", at, -1);
        List<Arg> args = m.containsKey("args") ? args(m.get("args"), at + "/args") : List.of();
        List<Variant> variants = new ArrayList<>();
        if (m.containsKey("variants")) {
            List<Object> raw = array(m.get("variants"), at + "/variants");
            for (int i = 0; i < raw.size(); i++) variants.add(variant(raw.get(i), at + "/variants/" + i));
        }
        List<Example> examples = new ArrayList<>();
        if (m.containsKey("examples")) {
            List<Object> raw = array(m.get("examples"), at + "/examples");
            for (int i = 0; i < raw.size(); i++) {
                String e = at + "/examples/" + i;
                Map<String, Object> x = object(raw.get(i), e);
                only(x, EXAMPLE_KEYS, e);
                examples.add(new Example(string(need(x, "line", e), e + "/line"), optString(x, "explanation", e, -1)));
            }
        }
        boolean hidden = optBool(m, "hidden", at, false);
        return new CommandInfo(name, aliases, Level.DESCRIBED, null, label, description, category, danger, permission, args,
            List.copyOf(variants), List.copyOf(examples), hidden);
    }

    private static Variant variant(Object json, String at) {
        Map<String, Object> m = object(json, at);
        only(m, VARIANT_KEYS, at);
        List<Arg> args = args(need(m, "args", at), at + "/args");
        return new Variant(optString(m, "label", at, 60), optString(m, "description", at, 1000), args);
    }

    private static List<Arg> args(Object json, String at) {
        List<Object> raw = array(json, at);
        List<Arg> args = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) args.add(arg(raw.get(i), at + "/" + i));
        return List.copyOf(args);
    }

    private static Arg arg(Object json, String at) {
        Map<String, Object> m = object(json, at);
        only(m, ARG_KEYS, at);
        String name = string(need(m, "name", at), at + "/name");
        if (!validArgName(name)) {
            throw new Checked.Invalid(at + "/name", "an argument name starts with a letter or _ and uses letters, digits, _ and -");
        }
        String typeId = string(need(m, "type", at), at + "/type");
        ArgType type = ArgType.fromId(typeId);
        if (type == null) throw new Checked.Invalid(at + "/type", "unknown argument type \"" + typeId + "\"");
        ArgStyle style = ArgStyle.POSITIONAL;
        if (m.containsKey("style")) {
            String s = string(m.get("style"), at + "/style");
            style = ArgStyle.fromId(s);
            if (style == null) throw new Checked.Invalid(at + "/style", "style must be positional, named or flag");
        }
        boolean required = optBool(m, "required", at, true);
        Object defaultValue = m.containsKey("default") ? Json.copy(m.get("default")) : null;
        Double min = optNumber(m, "min", at);
        Double max = optNumber(m, "max", at);
        Double step = optNumber(m, "step", at);
        List<String> options = null;
        if (m.containsKey("options")) {
            options = strings(m.get("options"), at + "/options", true);
        }
        String description = optString(m, "description", at, 500);
        boolean placeholders = optBool(m, "placeholders", at, true);
        return new Arg(name, type, style, required, defaultValue, min, max, step, options, description, placeholders);
    }

    private static Preset preset(Object json, String at, int minor) {
        Map<String, Object> m = object(json, at);
        only(m, PRESET_KEYS, at);
        String id = string(need(m, "id", at), at + "/id");
        if (!validPresetId(id)) throw new Checked.Invalid(at + "/id", "a preset id uses lowercase letters, digits, _ and -");
        String name = string(need(m, "name", at), at + "/name");
        String description = optString(m, "description", at, -1);
        String category = optString(m, "category", at, -1);
        String permission = null;
        if (m.containsKey("permission")) {
            permission = string(m.get("permission"), at + "/permission");
            if (permission.isEmpty()) throw new Checked.Invalid(at + "/permission", "permission must not be empty");
            if (minor < 1) throw new Checked.Invalid(at + "/permission", "permission needs format_version 1.1");
        }
        Object macro = object(need(m, "macro", at), at + "/macro");
        MacroReader.Result<Node> body = MacroReader.readNode(macro);
        if (!body.ok()) throw new PresetRefused(prefixed(body.problem(), at + "/macro"));
        return new Preset(id, name, description, category, body.value(), permission);
    }

    /** The same problem, with its pointer made relative to the whole sidecar. */
    private static Problem prefixed(Problem p, String prefix) {
        Map<String, Object> detail = new LinkedHashMap<>(p.detail());
        Object pointer = detail.get("pointer");
        if (pointer instanceof String) detail.put("pointer", prefix + pointer);
        return new Problem(p.code(), p.message() + " (in " + prefix + ")", detail);
    }

    private static List<String> strings(Object json, String at, boolean nonEmpty) {
        List<Object> raw = array(json, at);
        if (nonEmpty && raw.isEmpty()) throw new Checked.Invalid(at, "needs at least one value");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) out.add(string(raw.get(i), at + "/" + i));
        return List.copyOf(out);
    }

    /** {@code ^[A-Za-z0-9_.:-]+( [A-Za-z0-9_.:-]+)*$} */
    static boolean validCommandName(String name) {
        if (name.isEmpty() || name.charAt(0) == ' ' || name.charAt(name.length() - 1) == ' ') return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == ' ') {
                if (name.charAt(i - 1) == ' ') return false;
            } else if (!(letterOrDigit(c) || c == '_' || c == '.' || c == ':' || c == '-')) {
                return false;
            }
        }
        return true;
    }

    /** {@code ^[A-Za-z_][A-Za-z0-9_-]*$} */
    static boolean validArgName(String name) {
        if (name.isEmpty()) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean letter = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '_';
            if (!letter && (i == 0 || !(c >= '0' && c <= '9' || c == '-'))) return false;
        }
        return true;
    }

    /** {@code ^[a-z0-9][a-z0-9_-]*$} */
    static boolean validPresetId(String id) {
        if (id.isEmpty()) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean alnum = c >= 'a' && c <= 'z' || c >= '0' && c <= '9';
            if (!alnum && (i == 0 || c != '_' && c != '-')) return false;
        }
        return true;
    }

    private static boolean letterOrDigit(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9';
    }
}
