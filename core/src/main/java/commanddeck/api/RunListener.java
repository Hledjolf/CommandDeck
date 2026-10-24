package commanddeck.api;

/** Callbacks for one run, on the game's tick thread. They must return quickly; one that throws is logged
 * and ignored. */
public interface RunListener {
    /** After each command is sent and its response collected. */
    void onStep(StepEvent step);

    /** Exactly once, last. */
    void onFinish(RunResult result);
}
