package commanddeck.command;

import commanddeck.macro.Problem;
import java.util.List;

/**
 * A mod's command sidecar, read and checked. Its {@code commands} are refinements, not commands yet: their
 * {@code level} is {@link Level#DESCRIBED}, and {@link CommandMerge} decides what each becomes.
 * {@code skipped} holds one problem for each command or preset left out because it didn't read (from 1.1, a
 * bad entry skips only itself), for the log.
 */
public record Sidecar(String formatVersion, String modId, String modName, String modVersion, List<CommandInfo> commands,
                      List<Preset> presets, List<Problem> skipped) {
    /** A sidecar with nothing skipped. */
    public Sidecar(String formatVersion, String modId, String modName, String modVersion, List<CommandInfo> commands,
                   List<Preset> presets) {
        this(formatVersion, modId, modName, modVersion, commands, presets, List.of());
    }
}
