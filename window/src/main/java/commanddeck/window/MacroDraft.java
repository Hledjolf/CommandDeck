package commanddeck.window;

import commanddeck.json.Json;
import commanddeck.macro.Macro;
import commanddeck.macro.MacroReader;
import commanddeck.macro.Node;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A macro being edited, as the editor shows it: a flat list of steps, each with a depth. A container step
 * ({@code repeat}, {@code while}, {@code until} or {@code sequence}) contains the steps after it that are one level
 * deeper. The top level is the macro's body sequence; a loop holding several steps holds them in a sequence.
 *
 * <p>A {@code while} or {@code until} is followed by its checks, one level in, and then by its body. Several checks
 * directly under a loop must all hold: they're saved as one {@code all}. "All of", "Any of" and "Not" checks hold
 * the checks one level deeper than they are.
 *
 * <p>Every change keeps the list well formed: the first step is at depth 0, and each step is at most one
 * level deeper than the step before it, and only when that step is a container; checks only sit under a loop's
 * row or under a logic check, before the loop's body.
 */
public final class MacroDraft {
    public enum Kind {
        COMMAND, WAIT, REPEAT, SEQUENCE, CALL, WHILE, UNTIL, CHECK;

        boolean container() {
            return this == REPEAT || this == SEQUENCE || this == WHILE || this == UNTIL;
        }

        /** Repeat, While or Until: one row whose kind the editor switches. */
        public boolean loop() {
            return this == REPEAT || this == WHILE || this == UNTIL;
        }

        /** While or Until: a loop with checks. */
        public boolean checked() {
            return this == WHILE || this == UNTIL;
        }
    }

    /** The check a new one starts as. */
    public static final String NEW_CHECK = "item_count";

    /** Whether a check type holds other checks: {@code all}, {@code any} or {@code not}. */
    public static boolean logic(String type) {
        return "all".equals(type) || "any".equals(type) || "not".equals(type);
    }

    /**
     * One step. {@code text} is the command line, the repeat count, the wait's amount, the called macro's
     * reference, a while's or until's most times (blank for the operator's limit), or a check's type; {@code flag}
     * is a command's "ask first" or a wait counted in seconds rather than ticks; {@code note} is the step's own note
     * (null or blank for none). A check keeps its other fields in {@code fields}, as JSON values.
     */
    public static final class Step {
        /** Changes only between the loop kinds, when the editor's selector switches them. */
        public Kind kind;
        int depth;
        public String text;
        public boolean flag;
        public String note;
        /** A check's fields as JSON values, without {@code type}, and without {@code of} for a logic check. */
        public final Map<String, Object> fields = new LinkedHashMap<>();
        /** Which of its forms a check's field shows while it's empty: Item or Tag; Position, Placeholder or Marker. */
        final Map<String, String> choices = new LinkedHashMap<>();
        /** A check's fields typed as JSON that doesn't read: the text as typed. The check can't be saved with them. */
        final Map<String, String> unparsed = new LinkedHashMap<>();
        /** A check read from a file that the editor can't take apart: saved exactly as read. */
        Object raw;
        /** A loop's count for the other kind (times while it's a While or Until, most times while a Repeat). */
        String otherText;
        /** A While's or Until's checks while it's switched to Repeat, at depths relative to the loop. */
        List<Step> keptChecks;

        Step(Kind kind, int depth, String text, boolean flag, String note) {
            this.kind = kind;
            this.depth = depth;
            this.text = text;
            this.flag = flag;
            this.note = note;
        }

        public int depth() {
            return depth;
        }

        /** A check saved as it was read, because its shape isn't one the editor can show. */
        public boolean raw() {
            return raw != null;
        }
    }

    public String id;
    public String name;
    public String description;
    /** Groups the macro in lists (format 1.1), or null for none. */
    public String category;
    public boolean dangerous;
    /**
     * The permission node a player needs to see or run the macro (format 1.1), or null. The editor has no
     * field for it, so a copy of a preset keeps the preset's check and can't drop it.
     */
    public String permission;
    private final List<Step> steps = new ArrayList<>();

    public MacroDraft(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public static MacroDraft of(Macro m) {
        MacroDraft d = new MacroDraft(m.id(), m.name());
        d.description = m.description();
        d.category = m.category();
        d.dangerous = m.dangerous();
        d.permission = m.permission();
        Node body = m.body();
        if (body instanceof Node.Sequence && ((Node.Sequence) body).note() == null) {
            for (Node n : ((Node.Sequence) body).steps()) d.flatten(n, 0);
        } else {
            d.flatten(body, 0);
        }
        return d;
    }

    /** A draft holding a preset's or another macro's steps, under a new id. */
    public static MacroDraft copyOf(String id, String name, Node body) {
        return copyOf(id, name, body, null);
    }

    /** As {@link #copyOf(String, String, Node)}, keeping the original's {@code permission}. */
    public static MacroDraft copyOf(String id, String name, Node body, String permission) {
        MacroDraft d = of(new Macro("1.0", id, name, null, false, body, permission));
        d.id = id;
        return d;
    }

    private void flatten(Node n, int depth) {
        if (n instanceof Node.Command) {
            Node.Command c = (Node.Command) n;
            steps.add(new Step(Kind.COMMAND, depth, c.line(), c.confirm(), c.note()));
        } else if (n instanceof Node.Wait) {
            Node.Wait w = (Node.Wait) n;
            boolean seconds = w.ticks() == 0;
            steps.add(new Step(Kind.WAIT, depth, seconds ? number(w.seconds()) : Long.toString(w.ticks()), seconds, w.note()));
        } else if (n instanceof Node.Repeat) {
            Node.Repeat r = (Node.Repeat) n;
            steps.add(new Step(Kind.REPEAT, depth, Long.toString(r.times()), false, r.note()));
            flattenBody(r.body(), depth + 1);
        } else if (n instanceof Node.Loop) {
            Node.Loop l = (Node.Loop) n;
            String most = l.maxIterations() == 0 ? "" : Long.toString(l.maxIterations());
            steps.add(new Step(l.until() ? Kind.UNTIL : Kind.WHILE, depth, most, false, l.note()));
            flattenCheck(l.condition(), depth + 1);
            flattenBody(l.body(), depth + 1);
        } else if (n instanceof Node.Sequence) {
            Node.Sequence s = (Node.Sequence) n;
            steps.add(new Step(Kind.SEQUENCE, depth, "", false, s.note()));
            for (Node c : s.steps()) flatten(c, depth + 1);
        } else if (n instanceof Node.Call) {
            Node.Call c = (Node.Call) n;
            steps.add(new Step(Kind.CALL, depth, c.macro(), false, c.note()));
        } else {
            // A node this editor can't show must never be dropped by saving what it shows.
            throw new IllegalArgumentException("The editor can't show a " + n.type() + " step");
        }
    }

    /** A container's body: an unnamed sequence is the container's own steps. */
    private void flattenBody(Node body, int depth) {
        if (body instanceof Node.Sequence && ((Node.Sequence) body).note() == null) {
            for (Node s : ((Node.Sequence) body).steps()) flatten(s, depth);
        } else {
            flatten(body, depth);
        }
    }

    /**
     * One check row for the condition, and rows under it for what an {@code all}, {@code any} or {@code not} holds.
     * Anything the rows couldn't give back exactly is kept whole, as read.
     */
    @SuppressWarnings("unchecked")
    private void flattenCheck(Object c, int depth) {
        Map<String, Object> m = c instanceof Map ? (Map<String, Object>) c : null;
        Object type = m == null ? null : m.get("type");
        Step s = new Step(Kind.CHECK, depth, type instanceof String ? (String) type : "", false, null);
        steps.add(s);
        boolean shaped = type instanceof String;
        if (shaped && logic((String) type)) {
            Object of = m.get("of");
            shaped = m.size() == 2 && ("not".equals(type) ? of instanceof Map : of instanceof List);
        }
        if (!shaped) {
            s.raw = Json.copy(c);
            return;
        }
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!e.getKey().equals("type") && !(logic(s.text) && e.getKey().equals("of"))) s.fields.put(e.getKey(), Json.copy(e.getValue()));
        }
        if (!logic(s.text)) return;
        Object of = m.get("of");
        if (of instanceof Map) {
            flattenCheck(of, depth + 1);
        } else {
            for (Object x : (List<Object>) of) flattenCheck(x, depth + 1);
        }
    }

    private static String number(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d);
    }

    public List<Step> steps() {
        return steps;
    }

    /** Replaces each call step's reference with what {@code scope} makes of it. */
    public void scopeCalls(java.util.function.UnaryOperator<String> scope) {
        for (Step s : steps) {
            if (s.kind == Kind.CALL && s.text != null && !s.text.isBlank()) s.text = scope.apply(s.text.trim());
        }
    }

    /** Whether any step has a note, so the editor shows the notes when a macro is opened. */
    public boolean hasNotes() {
        for (Step s : steps) {
            if (s.note != null && !s.note.isBlank()) return true;
        }
        return false;
    }

    // ---- editing ------------------------------------------------------------------------------------

    /** Adds a step at the end, at the top level; a While or Until comes with a new check. */
    public void add(Kind kind) {
        if (kind == Kind.CHECK) return;
        String text = kind == Kind.COMMAND ? "/" : kind == Kind.WAIT ? "20" : kind == Kind.REPEAT ? "2" : "";
        steps.add(new Step(kind, 0, text, false, null));
        if (kind.checked()) steps.add(newCheck(1));
    }

    /** Adds a command step at the end, at the top level. */
    public void addCommand(String line) {
        steps.add(new Step(Kind.COMMAND, 0, line, false, null));
    }

    private static Step newCheck(int depth) {
        Step c = new Step(Kind.CHECK, depth, NEW_CHECK, false, null);
        c.fields.put("op", "ge");
        return c;
    }

    /** Adds a new check after the other checks of the While or Until at {@code i}; its index, or -1. */
    public int addCheck(int i) {
        if (!valid(i) || !steps.get(i).kind.checked()) return -1;
        int at = checksEnd(i);
        steps.add(at, newCheck(steps.get(i).depth + 1));
        return at;
    }

    /** The index after the checks of the While or Until at {@code i}: where its body starts. */
    int checksEnd(int i) {
        int d = steps.get(i).depth;
        int j = i + 1;
        while (j < steps.size() && steps.get(j).depth > d && steps.get(j).kind == Kind.CHECK) j++;
        return j;
    }

    /**
     * Switches a loop between Repeat, While and Until, keeping its body. A Repeat keeps a While's checks aside
     * and gives them back when it's switched back; each kind keeps its own count.
     */
    public boolean setLoopKind(int i, Kind kind) {
        if (!valid(i) || !kind.loop() || !steps.get(i).kind.loop() || steps.get(i).kind == kind) return false;
        Step s = steps.get(i);
        if ((s.kind == Kind.REPEAT) != (kind == Kind.REPEAT)) {
            String count = s.text;
            s.text = s.otherText != null ? s.otherText : kind == Kind.REPEAT ? "2" : "";
            s.otherText = count;
        }
        if (kind == Kind.REPEAT) {
            List<Step> kept = cut(i + 1, checksEnd(i));
            for (Step c : kept) c.depth -= s.depth;
            s.keptChecks = kept;
        } else if (s.kind == Kind.REPEAT) {
            List<Step> back = s.keptChecks == null || s.keptChecks.isEmpty() ? List.of(newCheck(1)) : s.keptChecks;
            s.keptChecks = null;
            for (Step c : back) c.depth += s.depth;
            steps.addAll(i + 1, back);
        }
        s.kind = kind;
        return true;
    }

    /**
     * Gives a check another type, emptying its fields. A check that stops holding others lets them out, to just
     * after it.
     */
    public boolean setCheckType(int i, String type) {
        if (!valid(i) || steps.get(i).kind != Kind.CHECK || steps.get(i).raw() || type == null || type.isEmpty()) return false;
        Step s = steps.get(i);
        if (type.equals(s.text)) return false;
        if (logic(s.text) && !logic(type)) {
            for (int j = i + 1; j < blockEnd(i); j++) steps.get(j).depth--;
        }
        s.text = type;
        s.fields.clear();
        s.choices.clear();
        s.unparsed.clear();
        return true;
    }

    /** The index after the last step inside step {@code i} (itself included). */
    int blockEnd(int i) {
        int d = steps.get(i).depth;
        int j = i + 1;
        while (j < steps.size() && steps.get(j).depth > d) j++;
        return j;
    }

    /** The previous step at the same depth with the same parent, or -1. */
    int previousSibling(int i) {
        int d = steps.get(i).depth;
        for (int j = i - 1; j >= 0; j--) {
            if (steps.get(j).depth == d) return j;
            if (steps.get(j).depth < d) return -1;
        }
        return -1;
    }

    /** The next step at the same depth with the same parent, or -1. */
    int nextSibling(int i) {
        int end = blockEnd(i);
        return end < steps.size() && steps.get(end).depth == steps.get(i).depth ? end : -1;
    }

    /** The step that contains step {@code i}, or -1 at the top level. */
    public int parent(int i) {
        int d = steps.get(i).depth;
        for (int j = i - 1; j >= 0; j--) {
            if (steps.get(j).depth < d) return j;
        }
        return -1;
    }

    private boolean isCheck(int i) {
        return steps.get(i).kind == Kind.CHECK;
    }

    /** A check that holds others: All of, Any of or Not. */
    private boolean holdsChecks(int i) {
        return isCheck(i) && !steps.get(i).raw() && logic(steps.get(i).text);
    }

    /** Checks move among checks, and steps among steps. */
    public boolean canMoveUp(int i) {
        if (!valid(i)) return false;
        int p = previousSibling(i);
        return p >= 0 && isCheck(p) == isCheck(i);
    }

    public boolean canMoveDown(int i) {
        if (!valid(i)) return false;
        int n = nextSibling(i);
        return n >= 0 && isCheck(n) == isCheck(i);
    }

    /** Into the container just before it, as that container's last step; a check only into All of, Any of or Not. */
    public boolean canIndent(int i) {
        if (!valid(i)) return false;
        int p = previousSibling(i);
        if (p < 0) return false;
        return isCheck(i) ? holdsChecks(p) : !isCheck(p) && steps.get(p).kind.container();
    }

    /** Out of its container, to just after it; a check only out of another check, so it stays in the condition. */
    public boolean canOutdent(int i) {
        if (!valid(i) || steps.get(i).depth == 0) return false;
        return !isCheck(i) || isCheck(parent(i));
    }

    private boolean valid(int i) {
        return i >= 0 && i < steps.size();
    }

    /** Swaps the step (with what it contains) with the one before it at the same level. */
    public boolean moveUp(int i) {
        if (!canMoveUp(i)) return false;
        int p = previousSibling(i);
        List<Step> block = cut(i, blockEnd(i));
        steps.addAll(p, block);
        return true;
    }

    public boolean moveDown(int i) {
        if (!canMoveDown(i)) return false;
        int n = nextSibling(i);
        List<Step> next = cut(n, blockEnd(n));
        steps.addAll(i, next);
        return true;
    }

    public boolean indent(int i) {
        if (!canIndent(i)) return false;
        for (int j = i; j < blockEnd(i); j++) steps.get(j).depth++;
        return true;
    }

    public boolean outdent(int i) {
        if (!canOutdent(i)) return false;
        int parent = parent(i);
        int end = blockEnd(i);
        List<Step> block = cut(i, end);
        for (Step s : block) s.depth--;
        int at = blockEnd(parent);
        steps.addAll(at, block);
        return true;
    }

    /** Removes the step and everything inside it. Returns how many steps went. */
    public int delete(int i) {
        if (!valid(i)) return 0;
        return cut(i, blockEnd(i)).size();
    }

    private List<Step> cut(int from, int to) {
        List<Step> part = new ArrayList<>(steps.subList(from, to));
        steps.subList(from, to).clear();
        return part;
    }

    // ---- building -----------------------------------------------------------------------------------

    /** The body as a macro node, or why it can't be one yet: a message and the step's index (-1 for none). */
    public record Built(Node body, String problem, int step) {
        public boolean ok() {
            return problem == null;
        }
    }

    public Built build() {
        if (steps.isEmpty()) return new Built(null, "A macro needs at least one step", -1);
        int[] at = {0};
        List<Node> top = new ArrayList<>();
        try {
            while (at[0] < steps.size()) top.add(node(at));
        } catch (Bad b) {
            return new Built(null, b.getMessage(), b.step);
        }
        // A lone step is the body itself, except an unnamed group, which would read back as the top level.
        boolean lone = top.size() == 1 && !(top.get(0) instanceof Node.Sequence && ((Node.Sequence) top.get(0)).note() == null);
        return new Built(lone ? top.get(0) : new Node.Sequence(List.copyOf(top), null), null, -1);
    }

    /** The draft as a macro document; check {@link #build} first. */
    public Macro toMacro() {
        Built b = build();
        if (!b.ok()) throw new IllegalStateException(b.problem());
        String desc = description == null || description.isBlank() ? null : description;
        String cat = category == null || category.isBlank() ? null : category.trim();
        return new Macro(permission == null && cat == null ? "1.0" : "1.1", id == null ? "" : id.trim(), name == null ? "" : name.trim(),
            desc, dangerous, b.body(), permission, cat);
    }

    /** The condition of the While or Until at {@code i} as it would be saved, or null while its checks can't make one. */
    public Map<String, Object> condition(int i) {
        if (!valid(i) || !steps.get(i).kind.checked()) return null;
        try {
            return condition(new int[] {i + 1}, i);
        } catch (Bad b) {
            return null;
        }
    }

    /**
     * Which check each part of the condition of the While or Until at {@code i} comes from: a JSON Pointer within
     * the condition, to the step's index. Several checks right under the loop are an {@code all}, whose pointer
     * ({@code ""}) is the loop's own row.
     */
    public Map<String, Integer> conditionSteps(int i) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (!valid(i) || !steps.get(i).kind.checked()) return out;
        List<Integer> top = children(i);
        if (top.size() == 1) {
            pointers(top.get(0), "", out);
        } else {
            out.put("", i);
            for (int k = 0; k < top.size(); k++) pointers(top.get(k), "/of/" + k, out);
        }
        return out;
    }

    private void pointers(int j, String at, Map<String, Integer> out) {
        out.put(at, j);
        if (!holdsChecks(j)) return;
        List<Integer> of = children(j);
        if ("not".equals(steps.get(j).text)) {
            if (!of.isEmpty()) pointers(of.get(0), at + "/of", out);
        } else {
            for (int k = 0; k < of.size(); k++) pointers(of.get(k), at + "/of/" + k, out);
        }
    }

    /** The checks directly inside step {@code i} (a loop's, or a logic check's), by index. */
    private List<Integer> children(int i) {
        List<Integer> out = new ArrayList<>();
        int d = steps.get(i).depth;
        for (int j = i + 1; j < steps.size() && steps.get(j).depth > d; j++) {
            if (steps.get(j).depth != d + 1) continue;
            if (!isCheck(j)) break;
            out.add(j);
        }
        return out;
    }

    private static final class Bad extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int step;

        Bad(String message, int step) {
            super(message, null, false, false);
            this.step = step;
        }
    }

    private Node node(int[] at) {
        int i = at[0]++;
        Step s = steps.get(i);
        String t = s.text == null ? "" : s.text.trim();
        String note = s.note == null || s.note.isBlank() ? null : s.note.trim();
        switch (s.kind) {
            case COMMAND:
                if (t.isEmpty() || t.equals("/")) throw new Bad("Step " + (i + 1) + ": the command line is empty", i);
                return new Node.Command(t, s.flag, note);
            case WAIT: {
                if (s.flag) {
                    double sec = parseDouble(t, i, "seconds");
                    if (!(sec > 0)) throw new Bad("Step " + (i + 1) + ": wait more than 0 seconds", i);
                    return new Node.Wait(0, sec, note);
                }
                long ticks = parseLong(t, i, "ticks");
                if (ticks < 1) throw new Bad("Step " + (i + 1) + ": wait at least 1 tick", i);
                return new Node.Wait(ticks, 0, note);
            }
            case REPEAT: {
                long times = parseLong(t, i, "times");
                if (times < 1) throw new Bad("Step " + (i + 1) + ": repeat at least once", i);
                List<Node> inside = children(at, s.depth);
                if (inside.isEmpty()) throw new Bad("Step " + (i + 1) + ": put at least one step inside the repeat (use the > button)", i);
                return new Node.Repeat(times, inside.size() == 1 ? inside.get(0) : new Node.Sequence(List.copyOf(inside), null), note);
            }
            case WHILE:
            case UNTIL: {
                long most = 0;
                if (!t.isEmpty()) {
                    most = parseLong(t, i, "most times");
                    if (most < 1) throw new Bad("Step " + (i + 1) + ": most times is at least 1, or blank for the server's limit", i);
                }
                Map<String, Object> condition = condition(at, i);
                List<Node> inside = children(at, s.depth);
                String what = s.kind == Kind.WHILE ? "while" : "until";
                if (inside.isEmpty()) throw new Bad("Step " + (i + 1) + ": put at least one step inside the " + what + " loop, after its checks (use the > button)", i);
                return new Node.Loop(s.kind == Kind.UNTIL, condition, inside.size() == 1 ? inside.get(0) : new Node.Sequence(List.copyOf(inside), null),
                    most, note);
            }
            case CALL: {
                if (t.isEmpty()) throw new Bad("Step " + (i + 1) + ": name the macro to call", i);
                if (!MacroReader.validReference(t)) {
                    throw new Bad("Step " + (i + 1) + ": call a macro id, or player:<id>, server:<id> or preset:<mod id>/<id>", i);
                }
                return new Node.Call(t, note);
            }
            case CHECK:
                throw new Bad("Step " + (i + 1) + ": a check belongs under a While or Until, before its steps", i);
            default: {
                List<Node> inside = children(at, s.depth);
                if (inside.isEmpty()) throw new Bad("Step " + (i + 1) + ": put at least one step inside the group (use the > button)", i);
                return new Node.Sequence(List.copyOf(inside), note);
            }
        }
    }

    /** The checks of the loop at {@code loop}, starting at {@code at}: one check, or all of several. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> condition(int[] at, int loop) {
        int depth = steps.get(loop).depth;
        List<Object> checks = new ArrayList<>();
        while (at[0] < steps.size() && steps.get(at[0]).depth > depth && isCheck(at[0])) checks.add(check(at));
        if (checks.isEmpty()) throw new Bad("Step " + (loop + 1) + ": add a check, so the loop knows when to stop (use + Check)", loop);
        if (checks.size() > 1) {
            Map<String, Object> all = new LinkedHashMap<>();
            all.put("type", "all");
            all.put("of", checks);
            return all;
        }
        if (!(checks.get(0) instanceof Map)) throw new Bad("Step " + (loop + 2) + ": this check can't be used on its own", loop + 1);
        return (Map<String, Object>) checks.get(0);
    }

    private Object check(int[] at) {
        int i = at[0]++;
        Step s = steps.get(i);
        if (s.raw()) return Json.copy(s.raw);
        if (s.text == null || s.text.isEmpty()) throw new Bad("Step " + (i + 1) + ": pick what to check", i);
        for (String name : s.unparsed.keySet()) throw new Bad("Step " + (i + 1) + ": " + name + " isn't JSON yet", i);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", s.text);
        for (Map.Entry<String, Object> e : s.fields.entrySet()) m.put(e.getKey(), Json.copy(e.getValue()));
        if (!logic(s.text)) return m;
        List<Object> of = new ArrayList<>();
        List<Integer> where = new ArrayList<>();
        while (at[0] < steps.size() && steps.get(at[0]).depth > s.depth) {
            where.add(at[0]);
            of.add(check(at));
        }
        if (of.isEmpty()) throw new Bad("Step " + (i + 1) + ": put at least one check inside (use the > button on the check below it)", i);
        if (s.text.equals("not")) {
            if (of.size() > 1) throw new Bad("Step " + (where.get(1) + 1) + ": Not holds one check; move this one out (use the < button)", where.get(1));
            m.put("of", of.get(0));
        } else {
            m.put("of", of);
        }
        return m;
    }

    private List<Node> children(int[] at, int depth) {
        List<Node> out = new ArrayList<>();
        while (at[0] < steps.size() && steps.get(at[0]).depth > depth) out.add(node(at));
        return out;
    }

    private static long parseLong(String t, int i, String what) {
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            throw new Bad("Step " + (i + 1) + ": " + what + " must be a whole number", i);
        }
    }

    private static double parseDouble(String t, int i, String what) {
        try {
            double d = Double.parseDouble(t);
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new NumberFormatException();
            return d;
        } catch (NumberFormatException e) {
            throw new Bad("Step " + (i + 1) + ": " + what + " must be a number", i);
        }
    }
}
