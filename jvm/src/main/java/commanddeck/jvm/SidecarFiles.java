package commanddeck.jvm;

import commanddeck.command.Sidecar;
import commanddeck.command.SidecarReader;
import commanddeck.command.Sidecars;
import commanddeck.macro.MacroReader;
import commanddeck.macro.Problem;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Finds the command sidecars: {@code commanddeck/commands.json} inside each mod's jar (or folder), and the server
 * owner's {@code sidecars/*.json} in CommandDeck's data folder, which win over a mod's own. A sidecar that doesn't
 * read is logged and left out; it never stops the others. The game adapter lists the mods' jars.
 */
public final class SidecarFiles {
    public static final String ENTRY = "commanddeck/commands.json";

    private SidecarFiles() {}

    /** @param modJars every loaded mod's jar or unpacked folder, in load order */
    public static Sidecars load(List<Path> modJars, Path dataDir, HostLog log) {
        Sidecars out = new Sidecars();
        for (Path jar : modJars) {
            String source = jar.getFileName() + "!/" + ENTRY;
            try {
                String text = readBundled(jar, ENTRY);
                if (text != null) {
                    Sidecar s = read(text, source, log);
                    if (s != null) out.addFromMod(s);
                }
            } catch (IOException e) {
                log.warn(String.format("[CommandDeck] Can't read %s: %s", source, e.getMessage()));
            }
        }
        Path dir = dataDir.resolve("sidecars");
        if (Files.isDirectory(dir)) {
            // In name order, so the result never depends on the order the file system lists them: with two
            // files for one mod, the later name wins. The mod is the file's own mod.id, whatever its name.
            List<Path> sorted = new ArrayList<>();
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
                for (Path f : files) sorted.add(f);
                sorted.sort(Comparator.comparing(p -> p.getFileName().toString()));
                for (Path f : sorted) {
                    Sidecar s = read(Files.readString(f, StandardCharsets.UTF_8), "sidecars/" + f.getFileName(), log);
                    if (s != null) out.addFromOwner(s);
                }
            } catch (IOException e) {
                log.warn(String.format("[CommandDeck] Can't read %s: %s", dir, e.getMessage()));
            }
        }
        return out;
    }

    private static Sidecar read(String text, String source, HostLog log) {
        MacroReader.Result<Sidecar> r = SidecarReader.readText(text);
        if (!r.ok()) {
            log.warn(String.format("[CommandDeck] Rejected sidecar %s: %s", source, r.problem().message()));
            return null;
        }
        for (Problem p : r.value().skipped()) {
            log.warn(String.format("[CommandDeck] Sidecar %s: skipped %s: %s", source, p.detail().get("pointer"), p.message()));
        }
        log.info(String.format("[CommandDeck] Sidecar %s for %s: %d commands, %d presets%s", source, r.value().modId(),
            r.value().commands().size(), r.value().presets().size(),
            r.value().skipped().isEmpty() ? "" : ", " + r.value().skipped().size() + " skipped"));
        return r.value();
    }

    /** A text file from a mod jar or an unpacked mod folder, or null. */
    public static String readBundled(Path jarOrDir, String entry) throws IOException {
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
