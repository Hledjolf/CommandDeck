package hledjolf.conditions.api;

import java.util.List;

/** An entry in an open registry: a namespaced id and the categories it belongs to (membership, not translation). */
public record RegistryEntry(String id, List<String> memberOf) {
    public RegistryEntry {
        memberOf = memberOf == null ? List.of() : List.copyOf(memberOf);
    }
}
