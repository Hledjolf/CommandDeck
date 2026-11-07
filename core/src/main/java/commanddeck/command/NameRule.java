package commanddeck.command;

/**
 * How the game compares command names, for matching a sidecar entry, a typed line or a registration to a
 * command. The adapter says which: exact on Hytale, ignoring case on Necesse.
 */
public enum NameRule {
    EXACT,
    /** As Java's {@code String.equalsIgnoreCase}: character by character, upper case then lower case. */
    IGNORE_CASE;

    /** The name as a lookup key: two names are the same command when their keys are equal. */
    public String key(String name) {
        if (this == EXACT || name == null) return name;
        StringBuilder b = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) b.append(Character.toLowerCase(Character.toUpperCase(name.charAt(i))));
        return b.toString();
    }
}
