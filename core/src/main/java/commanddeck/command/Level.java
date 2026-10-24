package commanddeck.command;

/** How much CommandDeck knows about a command, which decides what its form shows. */
public enum Level {
    /** From the game's registry, with argument names and types: one field per argument. */
    TYPED,
    /** A sidecar added types and text: like typed, plus labels, help and examples. */
    DESCRIBED,
    /** Only a name and help text: the help in full and one free-text field. */
    RAW;

    public String id() {
        return name().toLowerCase();
    }
}
