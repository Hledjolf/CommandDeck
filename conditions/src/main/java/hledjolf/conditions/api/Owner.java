package hledjolf.conditions.api;

/** Who owns an entity, as an {@link OwnerSource} answers: a player's id, nobody, or not known to that source. */
public record Owner(Kind kind, String playerId) {
    public enum Kind { PLAYER, NONE, UNKNOWN }

    public static final Owner NONE = new Owner(Kind.NONE, null);
    public static final Owner UNKNOWN = new Owner(Kind.UNKNOWN, null);

    public static Owner player(String id) {
        return new Owner(Kind.PLAYER, id);
    }
}
