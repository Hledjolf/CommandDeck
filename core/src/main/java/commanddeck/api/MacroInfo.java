package commanddeck.api;

/**
 * One macro or preset a presser can run by {@code MACRO_REF}, from {@code Deck.macros}. {@code mod} is the
 * owning mod's id for a preset, else null; {@code description} and {@code category} may be null.
 * {@code dangerous} means running it will ask first. {@code ref} is its scoped reference: {@code player:<id>},
 * {@code server:<id>} or {@code preset:<mod id>/<id>}. Since 1.1.
 */
public record MacroInfo(String id, String name, String description, String category, Origin origin, String mod,
                        boolean dangerous, String ref) {
    public enum Origin {
        PLAYER,
        SERVER,
        PRESET
    }

    /** Without a reference given: it is worked out from the origin, the mod and the id. */
    public MacroInfo(String id, String name, String description, String category, Origin origin, String mod, boolean dangerous) {
        this(id, name, description, category, origin, mod, dangerous, ref(origin, mod, id));
    }

    /** The scoped reference for a macro of this origin. */
    public static String ref(Origin origin, String mod, String id) {
        if (origin == null) return null;
        switch (origin) {
            case PLAYER:
                return "player:" + id;
            case SERVER:
                return "server:" + id;
            default:
                return "preset:" + mod + "/" + id;
        }
    }
}
