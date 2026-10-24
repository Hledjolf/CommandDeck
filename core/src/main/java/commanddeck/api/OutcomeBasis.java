package commanddeck.api;

/**
 * How a step's {@link CommandOutcome} was decided, or what was tried. Whether anything decided it is
 * {@link StepEvent#uncertain()}.
 */
public enum OutcomeBasis {
    /** The game reported the outcome through its API. */
    GAME_SIGNAL,
    /** Worked out from the messages the game sent back. */
    MESSAGE_HEURISTIC
}
