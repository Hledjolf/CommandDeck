package commanddeck.api;

/** Where a run that didn't complete stopped: the node path, the innermost repeat's iteration of its
 * iterations (0 of 0 outside any repeat), and the line that would have run next (null if none). */
public record StopPoint(String path, int iteration, int iterations, String nextLine) {}
