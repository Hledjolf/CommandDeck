package commanddeck.api;

import java.util.List;

/** The result of {@code Deck.check}: what would run, and whether it needs confirmation first. */
public record CheckResult(
    boolean ok,
    Refusal refusal,
    List<PreviewLine> preview,
    boolean needsConfirmation,
    String confirmPrompt,
    boolean saveRisk,
    String confirmToken) {}
