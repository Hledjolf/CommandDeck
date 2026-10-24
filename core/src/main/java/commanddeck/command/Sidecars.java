package commanddeck.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The sidecars in effect: each mod's own, unless the server owner put one for the same mod in the data
 * folder, which then replaces it whole. Gathered at boot and again on reload.
 */
public final class Sidecars {
    private final Map<String, Sidecar> fromMods = new LinkedHashMap<>();
    private final Map<String, Sidecar> fromOwner = new LinkedHashMap<>();

    /** A sidecar shipped inside a mod. A second one for the same mod replaces the first. */
    public void addFromMod(Sidecar s) {
        fromMods.put(s.modId(), s);
    }

    /** A sidecar the server owner put in the data folder. It wins over the mod's own. */
    public void addFromOwner(Sidecar s) {
        fromOwner.put(s.modId(), s);
    }

    /** The mods' sidecars in the order they were added, each replaced by the owner's where there is one; then owner-only ones. */
    public List<Sidecar> effective() {
        List<Sidecar> out = new ArrayList<>();
        for (Map.Entry<String, Sidecar> e : fromMods.entrySet()) {
            Sidecar owner = fromOwner.get(e.getKey());
            out.add(owner != null ? owner : e.getValue());
        }
        for (Map.Entry<String, Sidecar> e : fromOwner.entrySet()) {
            if (!fromMods.containsKey(e.getKey())) out.add(e.getValue());
        }
        return out;
    }
}
