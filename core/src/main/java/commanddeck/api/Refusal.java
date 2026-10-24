package commanddeck.api;

/** Why {@code check} or {@code start} refused: a stable code, a message for people, and detail. */
public record Refusal(String code, String message, String detail) {}
