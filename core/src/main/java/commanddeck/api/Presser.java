package commanddeck.api;

/** A player as the integration API names them: the game's stable id (on Hytale, the UUID) and a name for logs and {@code {self}}. */
public record Presser(String id, String name) {}
