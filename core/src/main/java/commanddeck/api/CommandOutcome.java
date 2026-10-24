package commanddeck.api;

/** What the game did with one command line. Each game's adapter decides which one applies. */
public enum CommandOutcome {
    /** The game accepted and ran it. */
    SENT,
    /** The game refused it: unknown command, bad arguments, no permission. */
    REFUSED,
    /** The command threw or errored. */
    FAILED
}
