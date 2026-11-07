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
    private final NameRule names;

    private CommandCatalog(List<CommandInfo> commands, List<Problem> warnings, Map<String, Preset> presets, Map<String, String> modNames,
                           NameRule names) {
        this.commands = commands;
        this.warnings = warnings;
        this.names = names;
        for (CommandInfo c : commands) {
            for (String a : c.aliases()) byNameOrAlias.putIfAbsent(names.key(a), c);
        }
        for (CommandInfo c : commands) byNameOrAlias.put(names.key(c.name()), c);
        this.presets.putAll(presets);
        this.modNames.putAll(modNames);
    }

    public static CommandCatalog build(CommandSource source, Sidecars sidecars) {
        List<Sidecar> effective = sidecars.effective();
        NameRule names = source.names();
        CommandMerge.Result r = CommandMerge.merge(source.discover(), effective, names);
        Map<String, Preset> presets = new LinkedHashMap<>();
        Map<String, String> modNames = new LinkedHashMap<>();
        for (Sidecar s : effective) {
            for (Preset p : s.presets()) presets.put(s.modId() + "/" + p.id(), p);
            if (s.modName() != null) modNames.put(s.modId(), s.modName());
        }
        return new CommandCatalog(r.commands(), r.warnings(), presets, modNames, names);
    }

    /**
     * A catalog as another process built it: its commands in order, its presets by {@code <mod id>/<id>} and the
     * mods' names. For a window drawn away from the deck; it has no warnings.
     */
    public static CommandCatalog of(List<CommandInfo> commands, Map<String, Preset> presets, Map<String, String> modNames,
                                    NameRule names) {
        return new CommandCatalog(List.copyOf(commands), List.of(), presets, modNames, names);
    }

    /** How the game compares command names. */
    public NameRule names() {
        return names;
    }

    /** The mods' names by id, for {@link #of}. */
    public Map<String, String> modNames() {
        return java.util.Collections.unmodifiableMap(modNames);
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

    /** By name or alias, without the slash, compared the game's way; null if unknown. */
    public CommandInfo find(String nameOrAlias) {
        return byNameOrAlias.get(names.key(nameOrAlias));
    }

    /** The words of a line split at runs of spaces, as {@code split(" +")} would (one empty word for ""). */
    static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (true) {
            int end = s.indexOf(' ', i);
            if (end < 0) {
                out.add(s.substring(i));
                return out;
            }
            out.add(s.substring(i, end));
            i = end;
            while (i < s.length() && s.charAt(i) == ' ') i++;
        }
    }

    /**
     * The command a typed line runs: the longest name or alias that the line starts with, word by word, after
     * an optional slash, compared the game's way. Null if none matches.
     */
    public CommandInfo match(String line) {
        String rest = line.startsWith("/") ? line.substring(1) : line;
        List<String> words = words(rest.trim());
        for (int n = words.size(); n > 0; n--) {
            CommandInfo c = byNameOrAlias.get(names.key(String.join(" ", words.subList(0, n))));
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
