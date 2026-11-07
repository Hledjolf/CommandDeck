package commanddeck.window;

import commanddeck.api.RunStatus;

import java.util.Locale;

/**
 * A run in words, for the window's Runs tab and {@code /deck list}. The run's position comes as a path into the
 * macro ({@code body.steps[2].body}); players see step numbers instead ("inside step 3").
 */
public final class RunText {
    private RunText() {}

    /** "morning: waiting, 1 step done, next: inside step 3, from window (run mvms1a30-1)". */
    public static String line(RunStatus s) {
        StringBuilder b = new StringBuilder(s.macroId() == null ? "Commands" : s.macroId());
        b.append(": ").append(s.state().name().toLowerCase(Locale.ROOT));
        b.append(", ").append(count(s.stepsCompleted(), "step")).append(" done");
        String at = position(s.currentPath());
        if (at != null) b.append(", next: ").append(at);
        if (s.source() != null) b.append(", from ").append(s.source());
        return b.append(" (run ").append(s.runId()).append(')').toString();
    }

    /** body = "the start"; body.steps[2] = "step 3"; body.steps[2].body = "inside step 3";
     *  body.steps[2].body.steps[0] = "step 3.1"; body.body = "inside step 1". Null for no path; the path itself
     *  if it isn't one of these. */
    public static String position(String path) {
        if (path == null) return null;
        if (!path.startsWith("body")) return path;
        String rest = path.substring(4);
        StringBuilder num = new StringBuilder();
        boolean inside = false;
        int i = 0;
        while (i < rest.length()) {
            int close = stepAt(rest, i);
            if (close >= 0) {
                if (num.length() > 0) num.append('.');
                num.append(Integer.parseInt(rest.substring(i + STEPS.length(), close)) + 1);
                inside = false;
                i = close + 1;
            } else if (rest.startsWith(".body", i)) {
                if (num.length() == 0) num.append('1'); // a macro that is one repeat: that repeat is step 1
                inside = true;
                i += 5;
            } else {
                return path;
            }
        }
        if (num.length() == 0) return "the start";
        return (inside ? "inside step " : "step ") + num;
    }

    private static final String STEPS = ".steps[";

    /** Where the closing bracket is when {@code .steps[<digits>]} starts at {@code i}, else -1. */
    private static int stepAt(String s, int i) {
        if (!s.startsWith(STEPS, i)) return -1;
        int j = i + STEPS.length();
        int k = j;
        while (k < s.length() && s.charAt(k) >= '0' && s.charAt(k) <= '9') k++;
        return k > j && k < s.length() && s.charAt(k) == ']' ? k : -1;
    }

    /** "1 run", "2 runs". */
    public static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }
}
