package commanddeck.macro;

/**
 * A macro document. {@code permission} (format 1.1) is a node the presser must hold to see or run it; null
 * for none. {@code category} (format 1.1) groups it in lists, like a preset's; null for none.
 */
public record Macro(String formatVersion, String id, String name, String description, boolean dangerous, Node body,
                    String permission, String category) {
    /** A macro without a permission or a category. */
    public Macro(String formatVersion, String id, String name, String description, boolean dangerous, Node body) {
        this(formatVersion, id, name, description, dangerous, body, null, null);
    }

    /** A macro without a category. */
    public Macro(String formatVersion, String id, String name, String description, boolean dangerous, Node body,
                 String permission) {
        this(formatVersion, id, name, description, dangerous, body, permission, null);
    }
}
