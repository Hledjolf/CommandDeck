package hledjolf.conditions.api;

/** Why a condition document can't be used here: where (a JSON Pointer), what kind, and a message for people. */
public record Problem(String pointer, Code code, String message) {
    public enum Code {
        /** The document fails its schema or the number rules. */
        INVALID,
        /** No type of that name is registered. */
        UNKNOWN_TYPE,
        /** The type exists but this game can't answer it, or this consumer refuses it. */
        UNSUPPORTED_HERE,
        /** A game id names another game. */
        WRONG_GAME;

        /** The name documents and logs use: {@code invalid}, {@code unknown_type}, ... */
        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }
}
