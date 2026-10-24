package commanddeck.hytale.window;

import commanddeck.macro.Macro;
import commanddeck.macro.Node;
import java.util.ArrayList;
import java.util.List;

/**
 * A macro being edited, as the editor shows it: a flat list of steps, each with a depth. A {@code repeat} or
 * {@code sequence} step contains the steps after it that are one level deeper. The top level is the macro's
 * body sequence; a repeat holding several steps holds them in a sequence.
 *
 * <p>Every change keeps the list well formed: the first step is at depth 0, and each step is at most one
 * level deeper than the step before it, and only when that step is a container.
 */
public final class MacroDraft {
    public enum Kind {
        COMMAND, WAIT, REPEAT, SEQUENCE;

        boolean container() {
            return this == REPEAT || this == SEQUENCE;
        }
    }

    /**
     * One step. {@code text} is the command line, the repeat count, or the wait's amount; {@code flag} is a
     * command's "ask first" or a wait counted in seconds rather than ticks; {@code note} is the step's own note
     * (null or blank for none).
     */
    public static final class Step {
        public final Kind kind;
        int depth;
        public String text;
        public boolean flag;
        public String note;

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
    }

    public String id;
    public String name;
    public String description;
    public boolean dangerous;
    private final List<Step> steps = new ArrayList<>();

    public MacroDraft(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public static MacroDraft of(Macro m) {
        MacroDraft d = new MacroDraft(m.id(), m.name());
        d.description = m.description();
        d.dangerous = m.dangerous();
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
        MacroDraft d = of(new Macro("1.0", id, name, null, false, body));
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
            Node body = r.body();
            if (body instanceof Node.Sequence && ((Node.Sequence) body).note() == null) {
                for (Node s : ((Node.Sequence) body).steps()) flatten(s, depth + 1);
            } else {
                flatten(body, depth + 1);
            }
        } else if (n instanceof Node.Sequence) {
            Node.Sequence s = (Node.Sequence) n;
            steps.add(new Step(Kind.SEQUENCE, depth, "", false, s.note()));
            for (Node c : s.steps()) flatten(c, depth + 1);
        }
    }

    private static String number(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d);
    }

    public List<Step> steps() {
        return steps;
    }

    /** Whether any step has a note, so the editor shows the notes when a macro is opened. */
    public boolean hasNotes() {
        for (Step s : steps) {
            if (s.note != null && !s.note.isBlank()) return true;
        }
        return false;
    }

    // ---- editing ------------------------------------------------------------------------------------

    /** Adds a step at the end, at the top level. */
    public void add(Kind kind) {
        String text = kind == Kind.COMMAND ? "/" : kind == Kind.WAIT ? "20" : kind == Kind.REPEAT ? "2" : "";
        steps.add(new Step(kind, 0, text, false, null));
    }

    /** Adds a command step at the end, at the top level. */
    public void addCommand(String line) {
        steps.add(new Step(Kind.COMMAND, 0, line, false, null));
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

    int parent(int i) {
        int d = steps.get(i).depth;
        for (int j = i - 1; j >= 0; j--) {
            if (steps.get(j).depth < d) return j;
        }
        return -1;
    }

    public boolean canMoveUp(int i) {
        return valid(i) && previousSibling(i) >= 0;
    }

    public boolean canMoveDown(int i) {
        return valid(i) && nextSibling(i) >= 0;
    }

    /** Into the container just before it, as that container's last step. */
    public boolean canIndent(int i) {
        if (!valid(i)) return false;
        int p = previousSibling(i);
        return p >= 0 && steps.get(p).kind.container();
    }

    /** Out of its container, to just after it. */
    public boolean canOutdent(int i) {
        return valid(i) && steps.get(i).depth > 0;
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
        return new Macro("1.0", id == null ? "" : id.trim(), name == null ? "" : name.trim(), desc, dangerous, b.body());
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
            default: {
                List<Node> inside = children(at, s.depth);
                if (inside.isEmpty()) throw new Bad("Step " + (i + 1) + ": put at least one step inside the group (use the > button)", i);
                return new Node.Sequence(List.copyOf(inside), note);
            }
        }
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
