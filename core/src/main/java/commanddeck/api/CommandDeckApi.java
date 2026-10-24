package commanddeck.api;

/** Where other mods get the {@link Deck}: CommandDeck's plugin publishes it at setup. Null before then. */
public final class CommandDeckApi {
    private static volatile Deck instance;

    private CommandDeckApi() {}

    public static Deck get() {
        return instance;
    }

    /** Called by CommandDeck's own plugin only. */
    public static void publish(Deck deck) {
        instance = deck;
    }
}
