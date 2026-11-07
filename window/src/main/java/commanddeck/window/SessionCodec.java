package commanddeck.window;

import commanddeck.api.CheckResult;
import commanddeck.api.Presser;
import commanddeck.api.PreviewLine;
import commanddeck.api.Refusal;
import commanddeck.api.RunItem;
import commanddeck.api.RunRequest;
import commanddeck.api.RunStatus;
import commanddeck.api.StartResult;
import commanddeck.api.Target;
import commanddeck.command.Arg;
import commanddeck.command.ArgStyle;
import commanddeck.command.ArgType;
import commanddeck.command.CommandCatalog;
import commanddeck.command.CommandInfo;
import commanddeck.command.NameRule;
import commanddeck.command.Danger;
import commanddeck.command.Example;
import commanddeck.command.Level;
import commanddeck.command.Preset;
import commanddeck.command.Variant;
import commanddeck.json.Json;
import commanddeck.macro.Limits;
import commanddeck.macro.Macro;
import commanddeck.macro.MacroReader;
import commanddeck.macro.MacroWriter;
import commanddeck.macro.Node;
import commanddeck.macro.Problem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@link DeckSession} messages as JSON text, written and read with the core's {@link Json}. A request is
 * {@code {"op": <name>, ...arguments}}; its reply is {@code {"op": <name>, "value": ...}}, or
 * {@code {"op": <name>, "error": <message>}}. Both ends are the same jar, so the format has no version of its own.
 */
public final class SessionCodec {
    private SessionCodec() {}

    /** A request or a reply: its op and the rest of its fields. */
    public record Message(String op, Map<String, Object> body) {
        public Object get(String key) {
            return body.get(key);
        }
    }

    public static String write(Message m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("op", m.op());
        out.putAll(m.body());
        return Json.write(out);
    }

    public static Message read(String text) {
        Map<String, Object> m = new LinkedHashMap<>(Json.parseObject(text));
        Object op = m.remove("op");
        return new Message(op == null ? null : op.toString(), m);
    }

    /** A message with fields given as key, value, key, value; null values are left out. */
    public static Message message(String op, Object... keyValues) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) body.put((String) keyValues[i], keyValues[i + 1]);
        }
        return new Message(op, body);
    }

    // ---- small helpers ---------------------------------------------------------------------------------

    private static Map<String, Object> obj(Object... keyValues) {
        return message("", keyValues).body();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object v) {
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object v) {
        return v == null ? List.of() : (List<Object>) v;
    }

    private static String s(Object v) {
        return v == null ? null : v.toString();
    }

    private static boolean b(Object v) {
        return Boolean.TRUE.equals(v);
    }

    private static int i(Object v) {
        return v == null ? 0 : ((Number) v).intValue();
    }

    private static Double d(Object v) {
        return v == null ? null : ((Number) v).doubleValue();
    }

    public static List<String> strings(Object v) {
        List<String> out = new ArrayList<>();
        for (Object o : list(v)) out.add(s(o));
        return out;
    }

    private static <E extends Enum<E>> E e(Class<E> type, Object v) {
        return v == null ? null : Enum.valueOf(type, v.toString());
    }

    // ---- who is at the window -------------------------------------------------------------------------

    public static Object opened(DeckSession.Opened o) {
        return obj("presser", presser(o.presser()), "canEditOwn", o.canEditOwn(), "operator", o.operator(), "coordinates", o.coordinates());
    }

    public static DeckSession.Opened opened(Object v) {
        Map<String, Object> m = map(v);
        return new DeckSession.Opened(presser(m.get("presser")), b(m.get("canEditOwn")), b(m.get("operator")), i(m.get("coordinates")));
    }

    public static Object presser(Presser p) {
        return obj("id", p.id(), "name", p.name());
    }

    public static Presser presser(Object v) {
        Map<String, Object> m = map(v);
        return new Presser(s(m.get("id")), s(m.get("name")));
    }

    public static Object limits(Limits l) {
        return obj("maxIterations", l.maxIterations(), "maxDepth", l.maxDepth(), "maxRuntimeSeconds", l.maxRuntimeSeconds(),
            "stepsPerTick", l.stepsPerTick(), "maxRunningPerPlayer", l.maxRunningPerPlayer(), "maxRunningConsole", l.maxRunningConsole());
    }

    public static Limits limits(Object v) {
        Map<String, Object> m = map(v);
        return new Limits(i(m.get("maxIterations")), i(m.get("maxDepth")), i(m.get("maxRuntimeSeconds")), i(m.get("stepsPerTick")),
            i(m.get("maxRunningPerPlayer")), i(m.get("maxRunningConsole")));
    }

    // ---- the catalog ----------------------------------------------------------------------------------

    public static Object catalog(CommandCatalog c) {
        if (c == null) return null;
        List<Object> commands = new ArrayList<>();
        for (CommandInfo x : c.commands()) commands.add(command(x));
        Map<String, Object> presets = new LinkedHashMap<>();
        for (Map.Entry<String, Preset> p : c.presets().entrySet()) presets.put(p.getKey(), preset(p.getValue()));
        return obj("commands", commands, "presets", presets, "modNames", new LinkedHashMap<>(c.modNames()), "names", c.names().name());
    }

    public static CommandCatalog catalog(Object v) {
        if (v == null) return null;
        Map<String, Object> m = map(v);
        List<CommandInfo> commands = new ArrayList<>();
        for (Object o : list(m.get("commands"))) commands.add(command(o));
        Map<String, Preset> presets = new LinkedHashMap<>();
        for (Map.Entry<String, Object> p : map(m.get("presets")).entrySet()) presets.put(p.getKey(), preset(p.getValue()));
        Map<String, String> modNames = new LinkedHashMap<>();
        for (Map.Entry<String, Object> n : map(m.get("modNames")).entrySet()) modNames.put(n.getKey(), s(n.getValue()));
        return CommandCatalog.of(commands, presets, modNames, m.get("names") == null ? NameRule.EXACT : e(NameRule.class, m.get("names")));
    }

    static Object command(CommandInfo c) {
        List<Object> args = new ArrayList<>();
        for (Arg a : c.args()) args.add(arg(a));
        List<Object> variants = new ArrayList<>();
        for (Variant x : c.variants()) {
            List<Object> va = new ArrayList<>();
            for (Arg a : x.args()) va.add(arg(a));
            variants.add(obj("label", x.label(), "description", x.description(), "args", va));
        }
        List<Object> examples = new ArrayList<>();
        for (Example x : c.examples()) examples.add(obj("line", x.line(), "explanation", x.explanation()));
        return obj("name", c.name(), "aliases", c.aliases(), "level", c.level().name(), "help", c.help(), "label", c.label(),
            "description", c.description(), "category", c.category(), "danger", c.danger().name(), "permission", c.permission(),
            "args", args, "variants", variants, "examples", examples, "hidden", c.hidden());
    }

    static CommandInfo command(Object v) {
        Map<String, Object> m = map(v);
        List<Arg> args = new ArrayList<>();
        for (Object o : list(m.get("args"))) args.add(arg(o));
        List<Variant> variants = new ArrayList<>();
        for (Object o : list(m.get("variants"))) {
            Map<String, Object> x = map(o);
            List<Arg> va = new ArrayList<>();
            for (Object a : list(x.get("args"))) va.add(arg(a));
            variants.add(new Variant(s(x.get("label")), s(x.get("description")), va));
        }
        List<Example> examples = new ArrayList<>();
        for (Object o : list(m.get("examples"))) examples.add(new Example(s(map(o).get("line")), s(map(o).get("explanation"))));
        return new CommandInfo(s(m.get("name")), strings(m.get("aliases")), e(Level.class, m.get("level")), s(m.get("help")),
            s(m.get("label")), s(m.get("description")), s(m.get("category")), e(Danger.class, m.get("danger")), s(m.get("permission")),
            args, variants, examples, b(m.get("hidden")));
    }

    static Object arg(Arg a) {
        return obj("name", a.name(), "type", a.type().name(), "style", a.style().name(), "required", a.required(),
            "default", a.defaultValue(), "min", a.min(), "max", a.max(), "step", a.step(), "options", a.options(),
            "description", a.description(), "placeholders", a.placeholders());
    }

    static Arg arg(Object v) {
        Map<String, Object> m = map(v);
        return new Arg(s(m.get("name")), e(ArgType.class, m.get("type")), e(ArgStyle.class, m.get("style")), b(m.get("required")),
            m.get("default"), d(m.get("min")), d(m.get("max")), d(m.get("step")), m.containsKey("options") ? strings(m.get("options")) : null,
            s(m.get("description")), b(m.get("placeholders")));
    }

    static Object preset(Preset p) {
        return obj("id", p.id(), "name", p.name(), "description", p.description(), "category", p.category(),
            "permission", p.permission(), "macro", MacroWriter.node(p.macro()));
    }

    static Preset preset(Object v) {
        Map<String, Object> m = map(v);
        Node macro = MacroReader.readNode(m.get("macro")).value();
        return new Preset(s(m.get("id")), s(m.get("name")), s(m.get("description")), s(m.get("category")), macro, s(m.get("permission")));
    }

    // ---- runs -----------------------------------------------------------------------------------------

    public static Object request(RunRequest r) {
        List<Object> items = new ArrayList<>();
        for (RunItem x : r.items()) items.add(obj("kind", x.kind().name(), "value", x.value()));
        return obj("items", items, "source", r.source(),
            "target", r.target() == null ? null : obj("id", r.target().id(), "name", r.target().name()));
    }

    public static RunRequest request(Object v) {
        Map<String, Object> m = map(v);
        List<RunItem> items = new ArrayList<>();
        for (Object o : list(m.get("items"))) items.add(new RunItem(e(RunItem.Kind.class, map(o).get("kind")), s(map(o).get("value"))));
        Map<String, Object> t = m.get("target") == null ? null : map(m.get("target"));
        return new RunRequest(items, s(m.get("source")), t == null ? null : new Target(s(t.get("id")), s(t.get("name"))));
    }

    public static Object refusal(Refusal r) {
        return r == null ? null : obj("code", r.code(), "message", r.message(), "detail", r.detail());
    }

    public static Refusal refusal(Object v) {
        if (v == null) return null;
        Map<String, Object> m = map(v);
        return new Refusal(s(m.get("code")), s(m.get("message")), s(m.get("detail")));
    }

    public static Object check(CheckResult c) {
        List<Object> preview = new ArrayList<>();
        for (PreviewLine p : c.preview()) {
            preview.add(obj("path", p.path(), "line", p.line(), "sendable", p.sendable(), "problem", p.problem(), "permitted", p.permitted()));
        }
        return obj("ok", c.ok(), "refusal", refusal(c.refusal()), "preview", preview, "needsConfirmation", c.needsConfirmation(),
            "confirmPrompt", c.confirmPrompt(), "saveRisk", c.saveRisk(), "confirmToken", c.confirmToken());
    }

    public static CheckResult check(Object v) {
        Map<String, Object> m = map(v);
        List<PreviewLine> preview = new ArrayList<>();
        for (Object o : list(m.get("preview"))) {
            Map<String, Object> p = map(o);
            preview.add(new PreviewLine(s(p.get("path")), s(p.get("line")), b(p.get("sendable")), s(p.get("problem")), (Boolean) p.get("permitted")));
        }
        return new CheckResult(b(m.get("ok")), refusal(m.get("refusal")), preview, b(m.get("needsConfirmation")),
            s(m.get("confirmPrompt")), b(m.get("saveRisk")), s(m.get("confirmToken")));
    }

    public static Object start(StartResult r) {
        return obj("started", r.started(), "runId", r.runId(), "refusal", refusal(r.refusal()));
    }

    public static StartResult start(Object v) {
        Map<String, Object> m = map(v);
        return new StartResult(b(m.get("started")), s(m.get("runId")), refusal(m.get("refusal")));
    }

    /** A run as the window shows it. A finished run's result isn't carried: the window lists runs still going. */
    public static Object status(RunStatus r) {
        return obj("runId", r.runId(), "presserId", r.presserId(), "source", r.source(), "macroId", r.macroId(),
            "state", r.state().name(), "stepsCompleted", r.stepsCompleted(), "currentPath", r.currentPath(), "targetId", r.targetId());
    }

    public static RunStatus status(Object v) {
        Map<String, Object> m = map(v);
        return new RunStatus(s(m.get("runId")), s(m.get("presserId")), s(m.get("source")), s(m.get("macroId")),
            e(RunStatus.State.class, m.get("state")), i(m.get("stepsCompleted")), s(m.get("currentPath")), null, s(m.get("targetId")));
    }

    // ---- macros and problems ----------------------------------------------------------------------------

    public static Object macro(Macro m) {
        return MacroWriter.document(m);
    }

    public static Macro macro(Object v) {
        MacroReader.Result<Macro> r = MacroReader.readDocument(v);
        if (!r.ok()) throw new IllegalArgumentException("not a macro: " + r.problem().message());
        return r.value();
    }

    public static Object problem(Problem p) {
        return p == null ? null : obj("code", p.code(), "message", p.message(), "detail", p.detail());
    }

    public static Problem problem(Object v) {
        if (v == null) return null;
        Map<String, Object> m = map(v);
        return new Problem(s(m.get("code")), s(m.get("message")), m.get("detail") == null ? Map.of() : map(m.get("detail")));
    }
}
