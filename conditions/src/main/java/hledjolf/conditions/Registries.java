package hledjolf.conditions;

import hledjolf.conditions.api.RegistryEntry;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The open registries ({@code weather}, {@code fluid}, {@code item_tag}, {@code entity_tag}, and any a mod opens).
 * An entry says which categories an id belongs to. Entries for the same id from several sources are combined, and
 * nothing is ever removed by another source. Reads see a snapshot, so evaluation never waits on a registration.
 */
public final class Registries {
    /** registry → id → source → categories. Changed only under the lock, then published as {@link #view}. */
    private final Map<String, Map<String, Map<String, Set<String>>>> bySource = new HashMap<>();
    private volatile View view = new View(Map.of(), Map.of());

    /** registry → id → combined categories; and registry → every id or category that some entry lists. */
    private record View(Map<String, Map<String, Set<String>>> entries, Map<String, Set<String>> listed) {}

    synchronized void add(String registry, List<RegistryEntry> entries, String source) {
        Map<String, Map<String, Set<String>>> reg = bySource.computeIfAbsent(registry, k -> new HashMap<>());
        for (RegistryEntry e : entries) reg.computeIfAbsent(e.id(), k -> new HashMap<>()).computeIfAbsent(source, k -> new HashSet<>()).addAll(e.memberOf());
        publish();
    }

    synchronized void replaceSource(String source, Map<String, List<RegistryEntry>> byRegistry) {
        for (Map<String, Map<String, Set<String>>> reg : bySource.values()) {
            reg.values().forEach(sources -> sources.remove(source));
            reg.values().removeIf(Map::isEmpty);
        }
        byRegistry.forEach((registry, entries) -> {
            Map<String, Map<String, Set<String>>> reg = bySource.computeIfAbsent(registry, k -> new HashMap<>());
            for (RegistryEntry e : entries) reg.computeIfAbsent(e.id(), k -> new HashMap<>()).computeIfAbsent(source, k -> new HashSet<>()).addAll(e.memberOf());
        });
        publish();
    }

    private void publish() {
        Map<String, Map<String, Set<String>>> entries = new HashMap<>();
        Map<String, Set<String>> listed = new HashMap<>();
        bySource.forEach((registry, ids) -> {
            Map<String, Set<String>> reg = new HashMap<>();
            Set<String> names = new HashSet<>();
            ids.forEach((id, sources) -> {
                Set<String> cats = new TreeSet<>();
                sources.values().forEach(cats::addAll);
                reg.put(id, Set.copyOf(cats));
                names.add(id);
                names.addAll(cats);
            });
            entries.put(registry, Map.copyOf(reg));
            listed.put(registry, Set.copyOf(names));
        });
        view = new View(Map.copyOf(entries), Map.copyOf(listed));
    }

    /** The categories an id belongs to, or null when no entry has that id. */
    public Set<String> categories(String registry, String id) {
        Map<String, Set<String>> reg = view.entries().get(registry);
        return reg == null ? null : reg.get(id);
    }

    /** Whether some entry has this id or lists it as a category. */
    public boolean listed(String registry, String idOrCategory) {
        Set<String> names = view.listed().get(registry);
        return names != null && names.contains(idOrCategory);
    }

    /**
     * Whether {@code id} matches {@code wanted}: it is that id, or its entry lists it. An id with no entry is UNKNOWN
     * for every category but its own id (null here).
     */
    public Boolean matches(String registry, String id, String wanted) {
        if (id.equals(wanted)) return true;
        Set<String> cats = categories(registry, id);
        return cats == null ? null : cats.contains(wanted);
    }

    /** How many entries a registry has (for the log). */
    public int size(String registry) {
        Map<String, Set<String>> reg = view.entries().get(registry);
        return reg == null ? 0 : reg.size();
    }
}
