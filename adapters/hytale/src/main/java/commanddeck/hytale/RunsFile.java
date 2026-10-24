package commanddeck.hytale;

import com.hypixel.hytale.logger.HytaleLogger;
import commanddeck.run.RunLog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** {@code runs.log} in the data folder: one line per event, with the time in UTC. Appended to, never rewritten. */
final class RunsFile implements RunLog.Appender {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss'Z'");

    private final Path file;
    private final HytaleLogger log;
    private boolean warned;

    RunsFile(Path file, HytaleLogger log) {
        this.file = file;
        this.log = log;
    }

    @Override
    public synchronized void append(String line) {
        String entry = OffsetDateTime.now(ZoneOffset.UTC).format(TIME) + " " + line.replace('\n', ' ') + "\n";
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            if (!warned) log.atWarning().log("[CommandDeck] Can't write %s: %s", file, e.getMessage());
            warned = true;
        }
    }
}
