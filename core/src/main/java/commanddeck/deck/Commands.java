package commanddeck.deck;

import commanddeck.macro.Node;
import java.util.ArrayList;
import java.util.List;

/** The command nodes of a body in tree order, with the same paths the runner reports. Loops aren't unrolled. */
final class Commands {
    private Commands() {}

    record Found(String path, Node.Command command) {}

    static List<Found> inTreeOrder(Node body) {
        List<Found> out = new ArrayList<>();
        List<Node> stack = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        stack.add(body);
        paths.add("body");
        while (!stack.isEmpty()) {
            Node n = stack.remove(stack.size() - 1);
            String path = paths.remove(paths.size() - 1);
            if (n instanceof Node.Command) {
                out.add(new Found(path, (Node.Command) n));
            } else if (n instanceof Node.Repeat) {
                stack.add(((Node.Repeat) n).body());
                paths.add(path + ".body");
            } else if (n instanceof Node.Sequence) {
                List<Node> steps = ((Node.Sequence) n).steps();
                for (int i = steps.size() - 1; i >= 0; i--) {
                    stack.add(steps.get(i));
                    paths.add(path + ".steps[" + i + "]");
                }
            }
        }
        return out;
    }
}
