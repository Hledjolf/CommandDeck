package commanddeck.command;

/** What an argument holds, which decides the form field and how the game's line builder escapes it. */
public enum ArgType {
    STRING, GREEDY_STRING, INT, FLOAT, BOOL, ENUM, PLAYER, ITEM, BLOCK, ENTITY, POSITION, DURATION;

    /** The name used in sidecar files: {@code greedy_string}, {@code int}… */
    public String id() {
        return name().toLowerCase();
    }

    /** Null for an unknown name. */
    public static ArgType fromId(String id) {
        for (ArgType t : values()) {
            if (t.id().equals(id)) return t;
        }
        return null;
    }

    /**
     * Whether a sidecar may give this type to an argument discovery reported as {@code discovered}: the same
     * type, or a plain string narrowed to one picker type. A game reports types it has no mapping for as
     * {@code string}, so that is the one a sidecar can make more specific.
     */
    public boolean narrows(ArgType discovered) {
        if (this == discovered) return true;
        if (discovered != STRING) return false;
        return this == ENUM || this == PLAYER || this == ITEM || this == BLOCK || this == ENTITY || this == DURATION;
    }
}
