package commanddeck.hytale;

import com.hypixel.hytale.logger.HytaleLogger;
import commanddeck.hytale.window.Prefs;
import commanddeck.json.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Each player's favorites and free-text history, in {@code prefs/<uuid>.json} in the data folder. Read when a
 * player first opens the window, written after each change.
 */
final class FilePrefs implements Prefs {
    static final int HISTORY = 10;

    private static final class One {
        final Set<String> favorites = new LinkedHashSet<>();
        final Map<String, List<String>> history = new LinkedHashMap<>();
    }

    private final Path dir;
    private final HytaleLogger log;
    private final Map<String, One> players = new ConcurrentHashMap<>();

    FilePrefs(Path dataDir, HytaleLogger log) {
        this.dir = dataDir.resolve("prefs");
        this.log = log;
    }

    private One of(String presserId) {
        return players.computeIfAbsent(presserId, this::read);
    }

    @SuppressWarnings("unchecked")
    private One read(String presserId) {
        One one = new One();
        Path f = file(presserId);
        if (!Files.isRegularFile(f)) return one;
        try {
            Map<String, Object> m = Json.parseObject(Files.readString(f, StandardCharsets.UTF_8));
            if (m.get("favorites") instanceof List) {
                for (Object o : (List<Object>) m.get("favorites")) {
                    if (o instanceof String) one.favorites.add((String) o);
                }
            }
            if (m.get("history") instanceof Map) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) m.get("history")).entrySet()) {
                    if (!(e.getValue() instanceof List)) continue;
                    List<String> lines = new ArrayList<>();
                    for (Object o : (List<Object>) e.getValue()) {
                        if (o instanceof String && lines.size() < HISTORY) lines.add((String) o);
                    }
                    one.history.put(e.getKey(), lines);
                }
            }
        } catch (IOException | RuntimeException e) {
            log.atWarning().log("[CommandDeck] Can't read %s, starting afresh: %s", f, e.getMessage());
        }
        return one;
    }

    private Path file(String presserId) {
        return dir.resolve(presserId.replaceAll("[^A-Za-z0-9_-]", "_") + ".json");
    }

    private void write(String presserId, One one) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("favorites", new ArrayList<Object>(one.favorites));
        Map<String, Object> h = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : one.history.entrySet()) h.put(e.getKey(), new ArrayList<Object>(e.getValue()));
        m.put("history", h);
        try {
            Files.createDirectories(dir);
            FileMacros.writeAtomically(file(presserId), Json.pretty(m) + "\n");
        } catch (IOException e) {
            log.atWarning().log("[CommandDeck] Can't save %s: %s", file(presserId), e.getMessage());
        }
    }

    @Override
    public Set<String> favorites(String presserId) {
        One one = of(presserId);
        synchronized (one) {
            return Set.copyOf(one.favorites);
        }
    }

    @Override
    public boolean toggleFavorite(String presserId, String command) {
        One one = of(presserId);
        synchronized (one) {
            boolean now = !one.favorites.remove(command);
            if (now) one.favorites.add(command);
            write(presserId, one);
            return now;
        }
    }

    @Override
    public List<String> history(String presserId, String command) {
        One one = of(presserId);
        synchronized (one) {
            return List.copyOf(one.history.getOrDefault(command, List.of()));
        }
    }

    @Override
    public void remember(String presserId, String command, String text) {
        One one = of(presserId);
        synchronized (one) {
            List<String> lines = new ArrayList<>(one.history.getOrDefault(command, List.of()));
            lines.remove(text);
            lines.add(0, text);
            while (lines.size() > HISTORY) lines.remove(lines.size() - 1);
            one.history.put(command, lines);
            write(presserId, one);
        }
    }
}
