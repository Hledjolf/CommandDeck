package commanddeck.api;

/**
 * One command node of a request, in tree order, with placeholders filled at check time. {@code permitted}
 * (since 1.1) says whether the presser holds the game's own permission for the line's command: true, false, or
 * null when the game can't tell. It is a hint for display and never refuses anything.
 */
public record PreviewLine(String path, String line, boolean sendable, String problem, Boolean permitted) {
    /** The 1.0 shape: {@code permitted} unknown. */
    public PreviewLine(String path, String line, boolean sendable, String problem) {
        this(path, line, sendable, problem, null);
    }
}
