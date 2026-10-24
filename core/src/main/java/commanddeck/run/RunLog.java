package commanddeck.run;

import commanddeck.api.RunResult;
import commanddeck.api.StepEvent;
import commanddeck.api.StopPoint;

/**
 * The run log: who started what, every line as sent with the game's response, and how
 * it ended, with where it stopped for any run that didn't complete. Lines go to an {@link Appender} (on Hytale,
 * {@code runs.log}).
 */
public final class RunLog {
    /** Receives finished log lines, without a trailing newline. */
    public interface Appender {
        void append(String line);
    }

    public static final RunLog NONE = new RunLog(l -> {});

    private final Appender out;

    public RunLog(Appender out) {
        this.out = out;
    }

    public void started(String runId, String presser, String macroId, String source) {
        out.append("run " + runId + " started by " + presser + " macro=" + (macroId == null ? "-" : macroId)
            + " source=" + (source == null ? "-" : quote(source)));
    }

    public void step(StepEvent e) {
        StringBuilder b = new StringBuilder("run ").append(e.runId()).append(" step ").append(e.stepIndex())
            .append(" tick=").append(e.tick()).append(" at ").append(e.path());
        if (e.iteration() > 0) b.append(" iteration ").append(e.iteration());
        b.append(" sent ").append(quote(e.line())).append(" -> ").append(e.outcome().name().toLowerCase())
            .append(" (").append(e.outcomeBasis().name().toLowerCase()).append(e.uncertain() ? ", uncertain" : "").append(")");
        for (String r : e.response()) b.append(" | ").append(r);
        out.append(b.toString());
    }

    /** The presser confirmed what they were shown; logged before the run starts. */
    public void confirmed(String runId, String presser, String prompt) {
        out.append("run " + runId + " confirmed by " + presser + ": " + quote(prompt));
    }

    /** A run's starter or an observer threw from a callback; the run went on. */
    public void listenerFailed(String runId, String who, RuntimeException e) {
        out.append("run " + runId + " listener of " + who + " failed and was ignored: " + e);
    }

    public void refused(String presser, String code, String message) {
        out.append("refused for " + presser + ": " + code + ": " + message);
    }

    public void finished(RunResult r) {
        StringBuilder b = new StringBuilder("run ").append(r.runId()).append(" ended ").append(r.end().name().toLowerCase());
        if (r.reason() != null) b.append(" (").append(r.reason()).append(")");
        b.append(" after ").append(r.stepsCompleted()).append(" steps");
        StopPoint p = r.stoppedAt();
        if (p != null) {
            b.append("; stopped at ").append(p.path());
            if (p.iterations() > 0) b.append(", iteration ").append(p.iteration()).append(" of ").append(p.iterations());
            b.append("; next: ").append(p.nextLine() == null ? "nothing" : quote(p.nextLine()));
        }
        if (r.message() != null) b.append("; ").append(r.message());
        out.append(b.toString());
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
