package commanddeck.macro;

import java.util.ArrayList;
import java.util.List;

/**
 * The save-time checks that need the operator's limits or the game: nesting depth, {@code repeat} counts and
 * loops' own {@code max_iterations}, then loop conditions. All count through a {@code call} once {@link Calls} has
 * copied the called body in. Schema and node-version checks happen earlier, in {@link MacroReader}. Depth is
 * checked before repeat counts, and conditions last, so a macro that breaks several reports the first of these.
 */
public final class MacroValidator {
    private MacroValidator() {}

    /**
     * Null when the body is within the limits and every loop's condition can be used here, otherwise the first
     * problem. {@code conditions} is the game's port, or null where there's none (loops are then refused).
     */
    public static Problem check(Node body, Limits limits, ConditionPort conditions) {
        int depth = depth(body);
        if (depth > limits.maxDepth()) {
            return Problem.of("depth_exceeded", "Nested " + depth + " deep; the limit is " + limits.maxDepth(),
                "depth", (long) depth, "max_depth", (long) limits.maxDepth());
        }
        // Walk in tree order with an explicit stack, so the first repeat in reading order is reported.
        List<Node> stack = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        List<Node.Loop> loops = new ArrayList<>();
        List<String> loopPaths = new ArrayList<>();
        stack.add(body);
        paths.add("body");
        while (!stack.isEmpty()) {
            Node n = stack.remove(stack.size() - 1);
            String path = paths.remove(paths.size() - 1);
            if (n instanceof Node.Repeat) {
                Node.Repeat r = (Node.Repeat) n;
                if (r.times() > limits.maxIterations()) {
                    return Problem.of("iterations_exceeded", "A repeat of " + r.times() + " at " + path + " is over the limit of "
                        + limits.maxIterations(), "times", r.times(), "max_iterations", (long) limits.maxIterations(), "path", path);
                }
                stack.add(r.body());
                paths.add(path + ".body");
            } else if (n instanceof Node.Loop) {
                Node.Loop l = (Node.Loop) n;
                if (l.maxIterations() > limits.maxIterations()) {
                    return Problem.of("iterations_exceeded", "A " + l.type() + " allowing " + l.maxIterations() + " passes at " + path
                        + " is over the limit of " + limits.maxIterations(), "times", l.maxIterations(), "max_iterations",
                        (long) limits.maxIterations(), "path", path);
                }
                loops.add(l);
                loopPaths.add(path);
                stack.add(l.body());
                paths.add(path + ".body");
            } else if (n instanceof Node.Call && ((Node.Call) n).body() != null) {
                Node.Call c = (Node.Call) n;
                stack.add(c.body());
                paths.add(path + ">" + c.ref() + ">body");
            } else if (n instanceof Node.Sequence) {
                List<Node> steps = ((Node.Sequence) n).steps();
                for (int i = steps.size() - 1; i >= 0; i--) {
                    stack.add(steps.get(i));
                    paths.add(path + ".steps[" + i + "]");
                }
            }
        }
        for (int i = 0; i < loops.size(); i++) {
            List<ConditionPort.Issue> issues = ConditionPort.issues(conditions, loops.get(i).condition());
            if (issues.isEmpty()) continue;
            ConditionPort.Issue first = issues.get(0);
            String path = loopPaths.get(i);
            String at = pointer(path) + "/condition" + first.pointer();
            return Problem.of("invalid", "The " + loops.get(i).type() + " condition at " + path + " can't be used: " + first.message()
                + " (at " + at + ")", "pointer", at, "path", path, "problem", first.code());
        }
        return null;
    }

    /**
     * A node path ({@code body.steps[1].body}) as a JSON Pointer into the macro ({@code /body/steps/1/body}). A path
     * that crosses a call points into the called macro, so it's given from the call on.
     */
    public static String pointer(String path) {
        int cross = path.lastIndexOf('>');
        String p = cross >= 0 ? path.substring(cross + 1) : path;
        StringBuilder b = new StringBuilder("/");
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == '.' || c == '[') b.append('/');
            else if (c != ']') b.append(c);
        }
        return b.toString();
    }

    /**
     * Container nodes ({@code sequence}, {@code repeat}, {@code while}, {@code until}, {@code call}) on the deepest path. A call adds the
     * called macro's depth once its body is copied in.
     */
    public static int depth(Node n) {
        if (n instanceof Node.Sequence) {
            int max = 0;
            for (Node s : ((Node.Sequence) n).steps()) max = Math.max(max, depth(s));
            return 1 + max;
        }
        if (n instanceof Node.Repeat) return 1 + depth(((Node.Repeat) n).body());
        if (n instanceof Node.Loop) return 1 + depth(((Node.Loop) n).body());
        if (n instanceof Node.Call) {
            Node body = ((Node.Call) n).body();
            return 1 + (body == null ? 0 : depth(body));
        }
        return 0;
    }
}
