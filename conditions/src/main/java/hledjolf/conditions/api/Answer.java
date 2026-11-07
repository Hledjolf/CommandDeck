package hledjolf.conditions.api;

/** A condition's answer. UNKNOWN means it couldn't be answered, and is never turned into FALSE. */
public enum Answer {
    TRUE, FALSE, UNKNOWN;

    public static Answer of(boolean b) {
        return b ? TRUE : FALSE;
    }

    /** Kleene's not: TRUE and FALSE swap, UNKNOWN stays. */
    public Answer not() {
        return this == TRUE ? FALSE : this == FALSE ? TRUE : UNKNOWN;
    }
}
