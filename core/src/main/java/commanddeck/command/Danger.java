package commanddeck.command;

/** Whether a command asks before it runs. */
public enum Danger {
    NONE,
    /** Ask first. */
    CONFIRM,
    /** Ask first, and warn that it can damage a save. */
    SAVE_RISK;

    public String id() {
        return name().toLowerCase();
    }

    public static Danger fromId(String id) {
        for (Danger d : values()) {
            if (d.id().equals(id)) return d;
        }
        return null;
    }
}
