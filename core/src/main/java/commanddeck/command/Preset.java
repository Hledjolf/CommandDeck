package commanddeck.command;

import commanddeck.macro.Node;

/**
 * A named macro a mod ships in its sidecar. {@code description}, {@code category} and {@code permission}
 * (sidecar 1.1: a node a player must hold to see or run it) may be null.
 */
public record Preset(String id, String name, String description, String category, Node macro, String permission) {
    /** A preset without a permission. */
    public Preset(String id, String name, String description, String category, Node macro) {
        this(id, name, description, category, macro, null);
    }
}
