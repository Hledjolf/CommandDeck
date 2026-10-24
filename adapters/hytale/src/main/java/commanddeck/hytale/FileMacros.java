package commanddeck.hytale;

import com.hypixel.hytale.logger.HytaleLogger;
import commanddeck.deck.MacroLibrary;
import commanddeck.hytale.window.MacroShelf;
import commanddeck.json.Json;
import commanddeck.macro.Limits;
import commanddeck.macro.Macro;
import commanddeck.macro.MacroReader;
import commanddeck.macro.MacroValidator;
import commanddeck.macro.MacroWriter;
import commanddeck.macro.Problem;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Saved macros as JSON files in the data folder: {@code macros/server/<id>.json} for the server's, and
 * {@code macros/players/<uuid>/<id>.json} for each player's own. Read at boot and on reload. A file that
 * doesn't read is logged and left out. Saving checks the macro exactly as reading does, plus the operator's
 * limits, and replaces the file in one step, so a crash can't leave half a macro.
 */
final class FileMacros implements MacroLibrary, MacroShelf {
    /** File names Windows keeps for devices; a macro with such an id gets a prefixed file name. */
    private static final Set<String> RESERVED = Set.of("con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6",
        "com7", "com8", "com9", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    private record Saved(Macro macro, Path file) {}

    private final Path root;
    private final HytaleLogger log;
    private final Supplier<Limits> limits;
    private volatile Map<String, Saved> server = Map.of();
    private final Map<String, Map<String, Saved>> players = new ConcurrentHashMap<>();

    FileMacros(Path dataDir, HytaleLogger log, Supplier<Limits> limits) {
        this.root = dataDir.resolve("macros");
        this.log = log;
        this.limits = limits;
    }

    /** Reads every macro again. Returns how many were read. */
    synchronized int reload() {
        server = readDir(root.resolve("server"));
        players.clear();
        int n = server.size();
        Path dir = root.resolve("players");
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> each = Files.newDirectoryStream(dir)) {
                for (Path p : each) {
                    if (!Files.isDirectory(p)) continue;
                    Map<String, Saved> mine = readDir(p);
                    players.put(p.getFileName().toString(), mine);
                    n += mine.size();
                }
            } catch (IOException e) {
                log.atWarning().log("[CommandDeck] Can't read %s: %s", dir, e.getMessage());
            }
        }
        return n;
    }

    private Map<String, Saved> readDir(Path dir) {
        Map<String, Saved> out = new TreeMap<>();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path f : files) {
                String where = root.getParent().relativize(f).toString().replace('\\', '/');
                try {
                    MacroReader.Result<Macro> r = MacroReader.readDocument(Json.parse(Files.readString(f, StandardCharsets.UTF_8)));
                    if (!r.ok()) {
                        log.atWarning().log("[CommandDeck] Rejected macro %s: %s", where, r.problem().message());
                    } else if (out.containsKey(r.value().id())) {
                        log.atWarning().log("[CommandDeck] Rejected macro %s: the id %s is already used", where, r.value().id());
                    } else {
                        out.put(r.value().id(), new Saved(r.value(), f));
                    }
                } catch (IOException | RuntimeException e) {
                    log.atWarning().log("[CommandDeck] Rejected macro %s: %s", where, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.atWarning().log("[CommandDeck] Can't read %s: %s", dir, e.getMessage());
        }
        return out;
    }

    // ---- reading ------------------------------------------------------------------------------------

    @Override
    public Macro playerMacro(String presserId, String id) {
        Map<String, Saved> mine = players.get(presserId);
        Saved s = mine == null ? null : mine.get(id);
        return s == null ? null : s.macro();
    }

    @Override
    public Macro serverMacro(String id) {
        Saved s = server.get(id);
        return s == null ? null : s.macro();
    }

    @Override
    public List<Macro> list(Scope scope, String presserId) {
        Map<String, Saved> m = scope == Scope.SERVER ? server : players.get(presserId);
        List<Macro> out = new ArrayList<>();
        if (m != null) {
            for (Saved s : m.values()) out.add(s.macro());
        }
        return out;
    }

    List<Macro> server() {
        return list(Scope.SERVER, null);
    }

    List<Macro> player(String presserId) {
        return list(Scope.PLAYER, presserId);
    }

    // ---- writing ------------------------------------------------------------------------------------

    @Override
    public synchronized Problem save(Scope scope, String presserId, Macro macro, String replacing) {
        // Read back what would be written: the file must be one this CommandDeck reads.
        Map<String, Object> json = MacroWriter.document(macro);
        MacroReader.Result<Macro> back = MacroReader.readDocument(Json.parse(Json.write(json)));
        if (!back.ok()) return back.problem();
        Problem limit = MacroValidator.check(back.value().body(), limits.get());
        if (limit != null) return limit;
        Macro m = back.value();
        Map<String, Saved> map = mapFor(scope, presserId);
        boolean renamed = replacing != null && !replacing.equals(m.id());
        if ((replacing == null || renamed) && map.containsKey(m.id())) {
            return Problem.invalid("/id", "A macro called " + m.id() + " already exists here");
        }
        if (renamed && !map.containsKey(replacing)) return Problem.of("unknown_macro", "No macro called " + replacing + " to rename", "id", replacing);
        Saved old = replacing == null ? null : map.get(replacing);
        Path dir = dirFor(scope, presserId);
        Path file = old != null && !renamed ? old.file() : dir.resolve(fileName(m.id()));
        try {
            Files.createDirectories(dir);
            writeAtomically(file, Json.pretty(json) + "\n");
            if (renamed && old != null && !old.file().equals(file)) Files.deleteIfExists(old.file());
        } catch (IOException e) {
            log.atWarning().log("[CommandDeck] Can't save macro %s: %s", file, e.getMessage());
            return Problem.of("io", "Couldn't save the macro: " + e.getMessage());
        }
        Map<String, Saved> next = new TreeMap<>(map);
        if (renamed) next.remove(replacing);
        next.put(m.id(), new Saved(m, file));
        store(scope, presserId, next);
        log.atInfo().log("[CommandDeck] Saved %s macro %s%s", scope == Scope.SERVER ? "server" : "player", m.id(),
            renamed ? " (was " + replacing + ")" : "");
        return null;
    }

    @Override
    public synchronized Problem delete(Scope scope, String presserId, String id) {
        Map<String, Saved> map = mapFor(scope, presserId);
        Saved s = map.get(id);
        if (s == null) return Problem.of("unknown_macro", "No macro called " + id, "id", id);
        try {
            Files.deleteIfExists(s.file());
        } catch (IOException e) {
            return Problem.of("io", "Couldn't delete the macro: " + e.getMessage());
        }
        Map<String, Saved> next = new TreeMap<>(map);
        next.remove(id);
        store(scope, presserId, next);
        log.atInfo().log("[CommandDeck] Deleted %s macro %s", scope == Scope.SERVER ? "server" : "player", id);
        return null;
    }

    private Map<String, Saved> mapFor(Scope scope, String presserId) {
        if (scope == Scope.SERVER) return server;
        Map<String, Saved> m = players.get(presserId);
        return m == null ? Map.of() : m;
    }

    private void store(Scope scope, String presserId, Map<String, Saved> next) {
        if (scope == Scope.SERVER) {
            server = next;
        } else {
            players.put(presserId, next);
        }
    }

    private Path dirFor(Scope scope, String presserId) {
        return scope == Scope.SERVER ? root.resolve("server") : root.resolve("players").resolve(safeDir(presserId));
    }

    /** A player's folder is their UUID; anything else is reduced to safe characters. */
    private static String safeDir(String presserId) {
        return presserId.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    static String fileName(String id) {
        int dot = id.indexOf('.');
        String base = (dot < 0 ? id : id.substring(0, dot)).toLowerCase(Locale.ROOT);
        return (RESERVED.contains(base) ? "macro-" : "") + id + ".json";
    }

    /** Writes a temporary file next to the target, then moves it over the target in one step. */
    static void writeAtomically(Path file, String text) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
