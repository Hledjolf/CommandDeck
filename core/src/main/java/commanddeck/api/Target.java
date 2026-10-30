package commanddeck.api;

/**
 * The player a run is for: {@code {target}} stands for them (Generous Operator's "give this to Alex"). Same
 * shape as {@link Presser}. It only fills {@code {target}}: commands still run as the presser. Since 1.1.
 */
public record Target(String id, String name) {}
