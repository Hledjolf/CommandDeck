package commanddeck.hytale;

import commanddeck.json.Json;
import commanddeck.macro.Limits;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CommandDeck's own settings, {@code config.json} in its data folder. Written with the defaults on first boot;
 * a missing or wrong value falls back to its default, with a warning.
 */
final class Settings {
    private boolean enabled = true;
    private boolean everyoneInSingleplayer = true;
    private boolean plainSender = false;
    private int commandTimeoutSeconds = 10;
    private Limits limits = Limits.DEFAULTS;

    boolean enabled() {
        return enabled;
    }

    boolean everyoneInSingleplayer() {
        return everyoneInSingleplayer;
    }

    /** Run lines through the server's own handler, without the response or a refusal signal. */
    boolean plainSender() {
        return plainSender;
    }

    int commandTimeoutSeconds() {
        return commandTimeoutSeconds;
    }

    Limits limits() {
        return limits;
    }

    /** Reads the file, writing it first if it doesn't exist. Returns a problem for the log, or null. */
    String load(Path file) {
        if (!Files.exists(file)) {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, Json.pretty(toJson()) + "\n", StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "Can't write " + file + ": " + e.getMessage();
            }
            return null;
        }
        Map<String, Object> m;
        try {
            m = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return "Can't read " + file + ", using the defaults: " + e.getMessage();
        }
        StringBuilder warnings = new StringBuilder();
        enabled = bool(m, "Enabled", true, warnings);
        everyoneInSingleplayer = bool(m, "EveryoneInSingleplayer", true, warnings);
        plainSender = bool(m, "PlainSender", false, warnings);
        commandTimeoutSeconds = integer(m, "CommandTimeoutSeconds", 10, 1, 600, warnings);
        Limits d = Limits.DEFAULTS;
        limits = new Limits(integer(m, "MaxIterations", d.maxIterations(), 1, 100_000, warnings),
            integer(m, "MaxDepth", d.maxDepth(), 1, 64, warnings), integer(m, "MaxRuntimeSeconds", d.maxRuntimeSeconds(), 1, 86_400, warnings),
            integer(m, "StepsPerTick", d.stepsPerTick(), 1, 100, warnings),
            integer(m, "MaxRunningPerPlayer", d.maxRunningPerPlayer(), 1, 100, warnings),
            integer(m, "MaxRunningForConsole", d.maxRunningConsole(), 1, 100, warnings));
        return warnings.length() == 0 ? null : warnings.toString().trim();
    }

    Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Enabled", enabled);
        m.put("EveryoneInSingleplayer", everyoneInSingleplayer);
        m.put("PlainSender", plainSender);
        m.put("CommandTimeoutSeconds", (long) commandTimeoutSeconds);
        m.put("MaxIterations", (long) limits.maxIterations());
        m.put("MaxDepth", (long) limits.maxDepth());
        m.put("MaxRuntimeSeconds", (long) limits.maxRuntimeSeconds());
        m.put("StepsPerTick", (long) limits.stepsPerTick());
        m.put("MaxRunningPerPlayer", (long) limits.maxRunningPerPlayer());
        m.put("MaxRunningForConsole", (long) limits.maxRunningConsole());
        return m;
    }

    private static boolean bool(Map<String, Object> m, String key, boolean def, StringBuilder warnings) {
        Object v = m.get(key);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        warnings.append(key).append(" should be true or false; using ").append(def).append(". ");
        return def;
    }

    private static int integer(Map<String, Object> m, String key, int def, int min, int max, StringBuilder warnings) {
        Object v = m.get(key);
        if (v == null) return def;
        if (v instanceof Long l && l >= min && l <= max) return l.intValue();
        warnings.append(key).append(" should be a whole number from ").append(min).append(" to ").append(max)
            .append("; using ").append(def).append(". ");
        return def;
    }
}
