package hledjolf.conditions;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a game tells the library, so the shared types are answered the same way on every game. The library does the
 * comparing, the registries and the logic; a game only reports facts. Ids here are the game's own, without the
 * {@code <game>:} prefix. Every method runs on the game's thread and gives null when the game can't tell, which
 * makes the condition UNKNOWN. A subject is the id {@link #subject} gave.
 */
public interface GameFacts {
    /** The profile's game name, e.g. {@code hytale}. */
    String game();

    /** How many numbers a position has: 3 on Hytale, 2 on Necesse. */
    int dimensions();

    /** The shared types this game answers ({@code all}, {@code any}, {@code not} and {@code counter} need no game). */
    Set<String> answers();

    /** A loaded subject's id, from a player's name or an id; null when it isn't online or loaded. */
    String subject(String nameOrId);

    /** Whether a player is connected, by name or id. */
    default Boolean online(String nameOrId) { return null; }

    /** Item id to count, over everything the subject carries. */
    default Map<String, Long> inventory(String subject) { return null; }

    /** A stat's current value, in points or as a percentage (0 to 100) of its range. */
    default Double stat(String subject, String stat, boolean percent) { return null; }

    /** The stat {@code health} means, or null when the game has none. */
    default String healthStat() { return null; }

    /** Hours, 0 to 24, in the subject's world. */
    default Double hour(String subject) { return null; }

    default Boolean night(String subject) { return null; }

    default double[] position(String subject) { return null; }

    default String world(String subject) { return null; }

    /** A named marker's position, for {@code distance}'s {@code marker:<name>}. */
    default double[] marker(String name) { return null; }

    default String weather(String subject) { return null; }

    default String zone(String subject) { return null; }

    /** The fluid the subject is in, or the empty string for none. */
    default String fluidAt(String subject) { return null; }

    /** The fluids within {@code radius} of the subject. */
    default Set<String> fluidsNear(String subject, double radius) { return null; }

    /** The entities within {@code radius} of the subject, the subject itself left out. */
    default List<Entity> entitiesNear(String subject, double radius) { return null; }

    /** The held item, or the empty string for none. */
    default String held(String subject) { return null; }

    /** What the subject wears, empty for nothing. */
    default List<String> worn(String subject) { return null; }

    /**
     * An entity: its id (as owner sources get it), its kind (the game's own id for what it is: a Hytale NPC role, or
     * {@code Player}), and the {@code entity_tag} entries the game itself knows it belongs to ({@code core:hostile}).
     */
    record Entity(String id, String kind, Set<String> tags) {}
}
