package commanddeck.command;

import commanddeck.macro.Node;

/** A named macro a mod ships in its sidecar. {@code description} and {@code category} may be null. */
public record Preset(String id, String name, String description, String category, Node macro) {}
