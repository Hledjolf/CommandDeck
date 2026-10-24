package commanddeck.api;

/** A run's state for {@code Deck.status}; {@code result} is set once it has finished. */
public record RunStatus(String runId, String presserId, String source, String macroId,
                        State state, int stepsCompleted, String currentPath, RunResult result) {
    public enum State {
        RUNNING,
        WAITING,
        FINISHED
    }
}
