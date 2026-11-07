package hledjolf.conditions.api;

/**
 * A mod's own condition type. {@code type} is namespaced ({@code buffeffects.has_effect}); {@code schema} is
 * JSON Schema text for the condition object, {@code type} included; {@code owner} is the registering mod's id.
 */
public record TypeRegistration(String type, String owner, String schema, Evaluator evaluator, String description) {}
