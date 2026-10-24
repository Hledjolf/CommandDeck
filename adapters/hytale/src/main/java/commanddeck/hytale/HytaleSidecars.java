package commanddeck.hytale;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import commanddeck.command.Sidecar;
import commanddeck.command.SidecarReader;
import commanddeck.command.Sidecars;
import commanddeck.macro.MacroReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Finds the command sidecars: {@code commanddeck/commands.json} inside each mod's jar (or folder), and the
 * server owner's {@code sidecars/*.json} in CommandDeck's data folder, which win over a mod's own. A sidecar
 * that doesn't read is logged and left out; it never stops the others.
 */
final class HytaleSidecars {
    static final String ENTRY = "commanddeck/commands.json";

    private HytaleSidecars() {}

    static Sidecars load(Path dataDir, HytaleLogger log) {
        Sidecars out = new Sidecars();
        for (PluginBase p : PluginManager.get().getPlugins()) {
            if (!(p instanceof JavaPlugin jp) || jp.getFile() == null) continue;
            String source = jp.getFile().getFileName() + "!/" + ENTRY;
            try {
                String text = readBundled(jp.getFile(), ENTRY);
                if (text != null) {
                    Sidecar s = read(text, source, log);
                    if (s != null) out.addFromMod(s);
                }
            } catch (IOException e) {
                log.atWarning().log("[CommandDeck] Can't read %s: %s", source, e.getMessage());
            }
        }
        Path dir = dataDir.resolve("sidecars");
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
                for (Path f : files) {
                    Sidecar s = read(Files.readString(f, StandardCharsets.UTF_8), "sidecars/" + f.getFileName(), log);
                    if (s != null) out.addFromOwner(s);
                }
            } catch (IOException e) {
                log.atWarning().log("[CommandDeck] Can't read %s: %s", dir, e.getMessage());
            }
        }
        return out;
    }

    private static Sidecar read(String text, String source, HytaleLogger log) {
        MacroReader.Result<Sidecar> r = SidecarReader.readText(text);
        if (!r.ok()) {
            log.atWarning().log("[CommandDeck] Rejected sidecar %s: %s", source, r.problem().message());
            return null;
        }
        log.atInfo().log("[CommandDeck] Sidecar %s for %s: %d commands, %d presets", source, r.value().modId(),
            r.value().commands().size(), r.value().presets().size());
        return r.value();
    }

    /** A text file from a plugin jar or an unpacked plugin folder, or null. */
    static String readBundled(Path jarOrDir, String entry) throws IOException {
        if (Files.isDirectory(jarOrDir)) {
            Path f = jarOrDir.resolve(entry);
            return Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
        }
        if (!Files.isRegularFile(jarOrDir)) return null;
        try (ZipFile zf = new ZipFile(jarOrDir.toFile())) {
            ZipEntry e = zf.getEntry(entry);
            if (e == null) return null;
            try (InputStream in = zf.getInputStream(e)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }
}
