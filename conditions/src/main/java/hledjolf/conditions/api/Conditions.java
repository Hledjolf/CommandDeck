package hledjolf.conditions.api;

import java.util.List;

/**
 * The shared condition library: one per game process. Register types and registry entries in your own setup or
 * later, validate documents when they load, and evaluate them on the game's thread.
 */
public interface Conditions {
    /** The library, or null when it isn't installed or hasn't started yet. */
    static Conditions lookup() {
        return Installed.current();
    }

    /** {@code "1.0"}. */
    String apiVersion();

    Registered registerType(TypeRegistration registration);

    Registered registerEntries(String registry, List<RegistryEntry> entries, String owner);

    /** Problems with a condition document; empty means valid, and every type in it is registered and answered here. */
    List<Problem> validate(String conditionJson);

    /** Evaluates a condition document. Never refuses: what it can't answer is UNKNOWN. */
    Evaluation evaluate(String conditionJson, Context context);

    /** Every registered type, sorted by name. */
    List<TypeInfo> types();

    Registered registerOwnerSource(String owner, OwnerSource source);
}
