package commanddeck.deck;

import commanddeck.command.CommandCatalog;
import commanddeck.command.Preset;
import commanddeck.macro.Calls;
import commanddeck.macro.ConditionPort;
import commanddeck.macro.Limits;
import commanddeck.macro.Macro;
import commanddeck.macro.MacroValidator;
import commanddeck.macro.Node;
import commanddeck.macro.Problem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a macro reference names, for a run ({@link DeckService}) and for saving.
 */
public final class MacroLookup {
    private MacroLookup() {}

    /**
     * What a reference names, looked up from where the calling macro lives. A scoped reference
     * ({@code player:<id>}, {@code server:<id>}, {@code preset:<mod id>/<id>}) means the same from anywhere;
     * {@code player:} is always the presser's. A plain id looks in the presser's own macros, then the server's,
     * then presets in sidecar order; from a server macro, in the server's, then presets; from a preset, only its
     * own mod's presets. A preset's mod id runs to the last '/', whatever the game's mod ids look like, because
     * macro ids can't contain '/'.
     */
    public static Calls.Target find(MacroLibrary macros, CommandCatalog cat, String presserId, String ref, Calls.Place from) {
        if (ref.startsWith("player:")) return target(macros.playerMacro(presserId, ref.substring(7)), Calls.Place.PLAYER);
        if (ref.startsWith("server:")) return target(macros.serverMacro(ref.substring(7)), Calls.Place.SERVER);
        if (ref.startsWith("preset:")) return preset(cat, ref.substring(7));
        switch (from.kind()) {
            case PLAYER: {
                Calls.Target t = target(macros.playerMacro(presserId, ref), Calls.Place.PLAYER);
                if (t == null) t = target(macros.serverMacro(ref), Calls.Place.SERVER);
                return t != null ? t : firstPreset(cat, ref);
            }
            case SERVER: {
                Calls.Target t = target(macros.serverMacro(ref), Calls.Place.SERVER);
                return t != null ? t : firstPreset(cat, ref);
            }
            default:
                return preset(cat, from.mod() + "/" + ref);
        }
    }

    public static Calls.Lookup lookup(MacroLibrary macros, CommandCatalog cat, String presserId) {
        return (ref, from) -> find(macros, cat, presserId, ref, from);
    }

    /**
     * The save-time checks after reading: calls that don't resolve, then
     * cycles, then depth and repeat counts over the expanded tree, then loop conditions ({@code conditions} may be null). {@code macros} must already show the macro
     * being saved in its new place, so a cycle through it is found. Null when it may be saved.
     */
    public static Problem checkAtSave(Macro m, Calls.Place place, MacroLibrary macros, CommandCatalog cat, String presserId, Limits limits,
        ConditionPort conditions) {
        String ref = (place == Calls.Place.SERVER ? "server:" : "player:") + m.id();
        Calls.Expanded x = Calls.expand(m.body(), ref, m.id(), place, lookup(macros, cat, presserId), null, limits.maxNodes());
        if (!x.ok()) return x.problem();
        return MacroValidator.check(x.body(), limits, conditions);
    }

    /**
     * Checks every preset's calls when sidecars load, against the presets and server macros there are then.
     * A preset whose call doesn't resolve, or that loops, stays listed;
     * each gets a {@code preset_call} warning for the merge report, and is refused when run. A call to
     * {@code player:<id>} can only be checked for a presser, so it passes here.
     */
    public static List<Problem> presetWarnings(CommandCatalog cat, MacroLibrary macros) {
        List<Problem> out = new ArrayList<>();
        if (cat == null) return out;
        Calls.Lookup atLoad = (ref, from) -> ref.startsWith("player:")
            ? new Calls.Target(ref, ref.substring(7), ref, new Node.Sequence(List.of(), null), false, null, Calls.Place.PLAYER)
            : find(macros, cat, "", ref, from);
        for (Map.Entry<String, Preset> e : cat.presets().entrySet()) {
            String key = e.getKey();
            String mod = key.substring(0, key.lastIndexOf('/'));
            Calls.Expanded x = Calls.expand(e.getValue().macro(), "preset:" + key, e.getValue().id(), Calls.Place.preset(mod), atLoad, null);
            if (!x.ok()) {
                out.add(Problem.of("preset_call", "Preset " + key + " can't run as it is: " + x.problem().message(), "preset", key,
                    "problem", x.problem().code()));
            }
        }
        return out;
    }

    static Calls.Target target(Macro m, Calls.Place place) {
        if (m == null) return null;
        String scope = place == Calls.Place.SERVER ? "server:" : "player:";
        return new Calls.Target(scope + m.id(), m.id(), m.name(), m.body(), m.dangerous(), m.permission(), place);
    }

    /** The preset whose key is {@code <mod id>/<id>}, or null. */
    static Calls.Target preset(CommandCatalog cat, String key) {
        Preset p = cat == null ? null : cat.presets().get(key);
        if (p == null) return null;
        String mod = key.substring(0, key.lastIndexOf('/'));
        return new Calls.Target("preset:" + key, p.id(), p.name(), p.macro(), false, p.permission(), Calls.Place.preset(mod));
    }

    /** The first preset with this id, in sidecar order. */
    static Calls.Target firstPreset(CommandCatalog cat, String id) {
        if (cat == null) return null;
        for (Map.Entry<String, Preset> e : cat.presets().entrySet()) {
            if (e.getValue().id().equals(id)) return preset(cat, e.getKey());
        }
        return null;
    }
}
