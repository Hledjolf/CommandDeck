package hledjolf.conditions.api;

/** Tells who owns an entity, given its id (on Hytale the entity's UUID as text). Same rules as an {@link Evaluator}. */
@FunctionalInterface
public interface OwnerSource {
    Owner ownerOf(String entityId);
}
