package commanddeck.api;

/** One command node of a request, in tree order, with placeholders filled at check time. */
public record PreviewLine(String path, String line, boolean sendable, String problem) {}
