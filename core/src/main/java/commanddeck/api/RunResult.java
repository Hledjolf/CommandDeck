package commanddeck.api;

import java.util.List;

/** The final record of a run, delivered once through {@link RunListener#onFinish}. */
public record RunResult(
    String runId,
    String macroId,
    String source,
    End end,
    String reason,
    String message,
    int stepsCompleted,
    List<StepEvent> steps,
    StopPoint stoppedAt,
    long startedTick,
    long endedTick) {

    /** How a run ended. */
    public enum End {
        COMPLETED,
        STOPPED,
        LIMIT,
        ERROR
    }
}
