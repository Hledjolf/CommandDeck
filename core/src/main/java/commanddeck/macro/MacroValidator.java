package commanddeck.macro;

import java.util.ArrayList;
import java.util.List;

/**
 * The save-time checks that need the operator's limits: nesting depth and
 * {@code repeat} counts. Schema and node-version checks happen earlier, in {@link MacroReader}. Depth is
 * checked before repeat counts, so a macro that breaks both reports {@code depth_exceeded}.
 */
public final class MacroValidator {
    private MacroValidator() {}

    /** Null when the body is within the limits, otherwise the first problem. */
    public static Problem check(Node body, Limits limits) {
        int depth = depth(body);
        if (depth > limits.maxDepth()) {
            return Problem.of("depth_exceeded", "Nested " + depth + " deep; the limit is " + limits.maxDepth(),
                "depth", (long) depth, "max_depth", (long) limits.maxDepth());
        }
        // Walk in tree order with an explicit stack, so the first repeat in reading order is reported.
        List<Node> stack = new ArrayList<>();
        List<String> paths = new ArrayList<>();
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
            } else if (n instanceof Node.Sequence) {
                List<Node> steps = ((Node.Sequence) n).steps();
                for (int i = steps.size() - 1; i >= 0; i--) {
                    stack.add(steps.get(i));
                    paths.add(path + ".steps[" + i + "]");
                }
            }
        }
        return null;
    }

    /** Container nodes ({@code sequence}, {@code repeat}) on the deepest path. */
    public static int depth(Node n) {
        if (n instanceof Node.Sequence) {
            int max = 0;
            for (Node s : ((Node.Sequence) n).steps()) max = Math.max(max, depth(s));
            return 1 + max;
        }
        if (n instanceof Node.Repeat) return 1 + depth(((Node.Repeat) n).body());
        return 0;
    }
}
