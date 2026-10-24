package commanddeck.api;

import java.util.List;

/** What to run, in order as one run, plus a label for the log (max 120 characters). */
public record RunRequest(List<RunItem> items, String source) {}
