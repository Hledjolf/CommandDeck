package commanddeck.api;

/** The result of {@code Deck.start}: a run id, or why nothing started. */
public record StartResult(boolean started, String runId, Refusal refusal) {}
