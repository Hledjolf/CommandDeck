package commanddeck.command;

import commanddeck.macro.Problem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The commands the browser shows, built from the game's registry and the sidecars in effect, plus the
 * sidecars' presets. Rebuilt whole on reload; an instance never changes.
 */
public final class CommandCatalog {
    private final List<CommandInfo> commands;
    private final List<Problem> warnings;
    private final Map<String, CommandInfo> byNameOrAlias = new LinkedHashMap<>();
    private final Map<String, Preset> presets = new LinkedHashMap<>();
    private final Map<String, String> modNames = new LinkedHashMap<>();

    private CommandCatalog(List<CommandInfo> commands, List<Problem> warnings, List<Sidecar> sidecars) {
        this.commands = commands;
        this.warnings = warnings;
        for (CommandInfo c : commands) {
            for (String a : c.aliases()) byNameOrAlias.putIfAbsent(a, c);
        }
        for (CommandInfo c : commands) byNameOrAlias.put(c.name(), c);
        for (Sidecar s : sidecars) {
            for (Preset p : s.presets()) presets.put(s.modId() + "/" + p.id(), p);
            if (s.modName() != null) modNames.put(s.modId(), s.modName());
        }
    }

    public static CommandCatalog build(CommandSource source, Sidecars sidecars) {
        List<Sidecar> effective = sidecars.effective();
        CommandMerge.Result r = CommandMerge.merge(source.discover(), effective);
        return new CommandCatalog(r.commands(), r.warnings(), effective);
    }

    public List<CommandInfo> commands() {
        return commands;
    }

    /** The browser's list: every command not marked hidden. */
    public List<CommandInfo> visible() {
        List<CommandInfo> out = new ArrayList<>();
        for (CommandInfo c : commands) {
            if (!c.hidden()) out.add(c);
        }
        return out;
    }

    /** What the merge ignored, for the log. */
    public List<Problem> warnings() {
        return warnings;
    }

    /** By name or alias, without the slash; null if unknown. */
    public CommandInfo find(String nameOrAlias) {
        return byNameOrAlias.get(nameOrAlias);
    }

    /**
     * The command a typed line runs: the longest name or alias that the line starts with, word by word, after
     * an optional slash. Null if none matches.
     */
    public CommandInfo match(String line) {
        String rest = line.startsWith("/") ? line.substring(1) : line;
        String[] words = rest.trim().split(" +");
        for (int n = words.length; n > 0; n--) {
            CommandInfo c = byNameOrAlias.get(String.join(" ", java.util.Arrays.copyOf(words, n)));
            if (c != null) return c;
        }
        return null;
    }

    /** The presets, keyed {@code <mod id>/<preset id>}. */
    public Map<String, Preset> presets() {
        return presets;
    }

    /** The display name a sidecar gives its mod ({@code mod.name}), or the id if it gives none. */
    public String modName(String modId) {
        return modNames.getOrDefault(modId, modId);
    }
}
