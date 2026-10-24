package commanddeck.api;

import java.util.List;

/**
 * One command step of a run: the line exactly as sent, the outcome, how it was decided, and the game's
 * response as plain lines (for people, not for parsing). {@code uncertain} is true when nothing decided the
 * outcome: it is then {@code SENT} with basis {@code MESSAGE_HEURISTIC}, and the run keeps going.
 * {@code REFUSED} and {@code FAILED} are always decisions, never uncertain.
 */
public record StepEvent(String runId, int stepIndex, String path, int iteration,
                        String line, CommandOutcome outcome, OutcomeBasis outcomeBasis,
                        boolean uncertain, List<String> response, long tick) {}
