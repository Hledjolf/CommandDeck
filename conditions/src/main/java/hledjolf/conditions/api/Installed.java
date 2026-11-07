package hledjolf.conditions.api;

/** Where the hosting mod installs the library, once per game process. Consumers use {@link Conditions#lookup()}. */
public final class Installed {
    private static volatile Conditions current;

    private Installed() {}

    static Conditions current() {
        return current;
    }

    /** Installs the library. Only the first call counts, so no other mod can replace it. */
    public static synchronized boolean install(Conditions library) {
        if (current != null || library == null) return false;
        current = library;
        return true;
    }
}
