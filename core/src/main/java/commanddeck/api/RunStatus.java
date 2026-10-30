package commanddeck.api;

/**
 * A run's state for {@code Deck.status}; {@code result} is set once it has finished. {@code targetId} (since
 * 1.1) is null when the target is the presser.
 */
public record RunStatus(String runId, String presserId, String source, String macroId,
                        State state, int stepsCompleted, String currentPath, RunResult result, String targetId) {
    public enum State {
        RUNNING,
        WAITING,
        FINISHED
    }

    /** The 1.0 shape: no target. */
    public RunStatus(String runId, String presserId, String source, String macroId,
                     State state, int stepsCompleted, String currentPath, RunResult result) {
        this(runId, presserId, source, macroId, state, stepsCompleted, currentPath, result, null);
    }
}
