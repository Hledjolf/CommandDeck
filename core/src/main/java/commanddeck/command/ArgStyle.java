package commanddeck.command;

/** How an argument appears in the line. */
public enum ArgStyle {
    /** By place in the line. */
    POSITIONAL,
    /** {@code --name value}, after the positional arguments, in any order. */
    NAMED,
    /** {@code --name} on its own: type bool, present means true. */
    FLAG;

    public String id() {
        return name().toLowerCase();
    }

    public static ArgStyle fromId(String id) {
        for (ArgStyle s : values()) {
            if (s.id().equals(id)) return s;
        }
        return null;
    }
}
