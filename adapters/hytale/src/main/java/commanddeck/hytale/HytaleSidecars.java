package commanddeck.hytale;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import commanddeck.command.Sidecars;
import commanddeck.jvm.HostLog;
import commanddeck.jvm.SidecarFiles;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The command sidecars on Hytale: {@link SidecarFiles} over every Java plugin's jar or folder. */
final class HytaleSidecars {
    private HytaleSidecars() {}

    static Sidecars load(Path dataDir, HostLog log) {
        List<Path> jars = new ArrayList<>();
        for (PluginBase p : PluginManager.get().getPlugins()) {
            if (p instanceof JavaPlugin jp && jp.getFile() != null) jars.add(jp.getFile());
        }
        return SidecarFiles.load(jars, dataDir, log);
    }
}
