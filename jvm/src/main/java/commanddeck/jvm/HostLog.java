package commanddeck.jvm;

/** The game's server log, for the files and the deck to report to. */
public interface HostLog {
    void info(String message);

    void warn(String message);
}
