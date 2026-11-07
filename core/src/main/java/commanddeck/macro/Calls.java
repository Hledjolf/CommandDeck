package commanddeck.macro;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Resolves every {@code call} in a body by copying the called macro's body in (macro format 1.2, "Calling
 * another macro"), depth first in tree order. The run then uses the copy, so editing or deleting a macro
 * during a run changes nothing.
 *
 * <p>Each call is checked at its place in tree order: {@code unknown_macro} (the reference as written), then
 * {@code cycle} (the path, such as {@code a>b>a}), then {@code no_permission} for the called macro's
 * {@code permission}. At save, permissions aren't checked, and every call that doesn't resolve is reported
 * before any cycle.
 */
public final class Calls {
    /**
     * The most nodes an expanded tree may hold. Calls copy bodies, so a few macros that each call the next
     * many times could otherwise expand without bound before the depth limit is checked.
     */
    public static final int MAX_NODES = 10_000;

    private Calls() {}

    /** Where a macro lives, which decides where its plain ids look. {@code mod} is set for a preset. */
    public record Place(Kind kind, String mod) {
        public enum Kind { PLAYER, SERVER, PRESET }

        public static final Place PLAYER = new Place(Kind.PLAYER, null);
        public static final Place SERVER = new Place(Kind.SERVER, null);

        public static Place preset(String mod) {
            return new Place(Kind.PRESET, mod);
        }
    }

    /** A macro or preset a reference names. {@code ref} is its scoped reference, such as {@code server:restock}. */
    public record Target(String ref, String id, String name, Node body, boolean dangerous, String permission, Place place) {}

    /** Finds what a reference names, looked up from where the calling macro lives; null for nothing. */
    @FunctionalInterface
    public interface Lookup {
        Target find(String reference, Place from);
    }

    /** The expanded body and every macro it called, in tree order; or the first problem. */
    public record Expanded(Node body, Problem problem, List<Target> called) {
        public boolean ok() {
            return problem == null;
        }
    }

    /**
     * @param top   the top macro's scoped reference, or null for a body that isn't a saved macro
     * @param label how the top macro is named in a cycle's path
     * @param holds whether the presser holds a permission; null at save, where permissions aren't checked
     */
    public static Expanded expand(Node body, String top, String label, Place from, Lookup lookup, Predicate<String> holds) {
        return expand(body, top, label, from, lookup, holds, MAX_NODES);
    }

    /** As above, with the operator's {@code max_nodes}. */
    public static Expanded expand(Node body, String top, String label, Place from, Lookup lookup, Predicate<String> holds, int maxNodes) {
        Walk w = new Walk(lookup, holds, maxNodes);
        if (top != null) {
            w.refs.add(top);
            w.labels.add(label);
        }
        try {
            Node out = w.node(body, from);
            if (w.firstCycle != null) return new Expanded(null, w.firstCycle, List.copyOf(w.called));
            return new Expanded(out, null, List.copyOf(w.called));
        } catch (Stop s) {
            return new Expanded(null, s.problem, List.copyOf(w.called));
        }
    }

    private static final class Stop extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final transient Problem problem;

        Stop(Problem problem) {
            super(problem.message(), null, false, false);
            this.problem = problem;
        }
    }

    private static final class Walk {
        final Lookup lookup;
        final Predicate<String> holds;
        final boolean atSave;
        /** The scoped references of the macros on the current path, and how each was named. */
        final List<String> refs = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        final List<Target> called = new ArrayList<>();
        Problem firstCycle;
        int nodes;

        final int maxNodes;

        Walk(Lookup lookup, Predicate<String> holds, int maxNodes) {
            this.lookup = lookup;
            this.holds = holds;
            this.maxNodes = maxNodes;
            this.atSave = holds == null;
        }

        Node node(Node n, Place from) {
            if (++nodes > maxNodes) {
                throw new Stop(Problem.of("depth_exceeded", "The calls expand to more than " + maxNodes + " steps",
                    "nodes", (long) nodes, "max_nodes", (long) maxNodes));
            }
            if (n instanceof Node.Sequence) {
                Node.Sequence s = (Node.Sequence) n;
                List<Node> steps = new ArrayList<>();
                for (Node step : s.steps()) steps.add(node(step, from));
                return new Node.Sequence(List.copyOf(steps), s.note());
            }
            if (n instanceof Node.Repeat) {
                Node.Repeat r = (Node.Repeat) n;
                return new Node.Repeat(r.times(), node(r.body(), from), r.note());
            }
            if (n instanceof Node.Loop) {
                Node.Loop l = (Node.Loop) n;
                return new Node.Loop(l.until(), l.condition(), node(l.body(), from), l.maxIterations(), l.note());
            }
            if (!(n instanceof Node.Call)) return n;
            Node.Call c = (Node.Call) n;
            Target t = lookup.find(c.macro(), from);
            if (t == null) throw new Stop(Problem.of("unknown_macro", "No macro or preset called \"" + c.macro() + "\"", "macro", c.macro()));
            if (refs.contains(t.ref())) {
                List<String> path = new ArrayList<>(labels.subList(refs.indexOf(t.ref()), labels.size()));
                path.add(c.macro());
                Problem p = Problem.of("cycle", "These macros call each other in a loop: " + String.join(" > ", path), "path", List.copyOf(path));
                if (!atSave) throw new Stop(p);
                if (firstCycle == null) firstCycle = p;
                return c;
            }
            if (holds != null && t.permission() != null && !holds.test(t.permission())) {
                throw new Stop(Problem.of("no_permission", "Running " + t.name() + " (called as " + c.macro() + ") needs the permission "
                    + t.permission(), "permission", t.permission()));
            }
            called.add(t);
            refs.add(t.ref());
            labels.add(c.macro());
            Node inside = node(t.body(), t.place());
            refs.remove(refs.size() - 1);
            labels.remove(labels.size() - 1);
            return new Node.Call(c.macro(), c.note(), inside, t.ref());
        }
    }
}
