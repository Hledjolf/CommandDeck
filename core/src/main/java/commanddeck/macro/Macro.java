package commanddeck.macro;

/** A macro document. */
public record Macro(String formatVersion, String id, String name, String description, boolean dangerous, Node body) {}
