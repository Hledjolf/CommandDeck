package commanddeck.api;

import java.util.List;

/** The integration API: run command lines and macros as a given player, with every
 * rule a button press in CommandDeck's window has. */
public interface Deck {
    String API_VERSION = "1.1";

    String apiVersion();

    CheckResult check(Presser presser, RunRequest request);

    StartResult start(Presser presser, RunRequest request, RunListener listener, String confirmToken);

    /** False if the run isn't running, or {@code by} may not stop it. */
    boolean stop(String runId, Presser by);

    /** Null if unknown. */
    RunStatus status(String runId);

    List<RunStatus> running(Presser presser);

    /**
     * Reports every run of this presser, whoever started it (CommandDeck's window, {@code /deck run}, another
     * mod), for runs that begin after this call: the same {@code onStep} and {@code onFinish} calls the run's
     * starter gets. Observing never changes a run. Returns an observer id for {@link #unobserve}. An observer
     * lasts until then, or until CommandDeck reloads.
     */
    String observe(Presser presser, RunListener listener);

    void unobserve(String observerId);

    /**
     * Since 1.1. What this presser can run by {@code MACRO_REF}: their own macros, then server macros, then
     * presets, each id once, as a reference would resolve it, leaving out any whose {@code permission} the
     * presser doesn't hold. {@code category} null means all; otherwise it is compared exactly. A snapshot: a
     * later {@code check} decides. A default, so implementations written for 1.0 still compile.
     */
    default List<MacroInfo> macros(Presser presser, String category) {
        return List.of();
    }
}
