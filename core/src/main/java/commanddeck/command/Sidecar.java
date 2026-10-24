package commanddeck.command;

import java.util.List;

/**
 * A mod's command sidecar, read and checked. Its {@code commands} are refinements, not commands yet: their
 * {@code level} is {@link Level#DESCRIBED}, and {@link CommandMerge} decides what each becomes.
 */
public record Sidecar(String formatVersion, String modId, String modName, String modVersion, List<CommandInfo> commands,
                      List<Preset> presets) {}
