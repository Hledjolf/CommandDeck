package commanddeck.api;

import java.util.List;

/**
 * What to run, in order as one run, plus a label for the log (max 120 characters). {@code target} (since 1.1)
 * is the player {@code {target}} stands for; null means the presser.
 */
public record RunRequest(List<RunItem> items, String source, Target target) {
    /** The 1.0 shape: no target. */
    public RunRequest(List<RunItem> items, String source) {
        this(items, source, null);
    }
}
