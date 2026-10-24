package commanddeck.hytale.window;

import java.util.List;
import java.util.Set;

/** A player's favorite commands and the free text they typed for raw commands, kept between sessions. */
public interface Prefs {
    Set<String> favorites(String presserId);

    /** Adds or removes a favorite; returns whether it is one now. */
    boolean toggleFavorite(String presserId, String command);

    /** Most recent first. */
    List<String> history(String presserId, String command);

    void remember(String presserId, String command, String text);
}
