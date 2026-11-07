package hledjolf.conditions.api;

import java.util.Map;

/**
 * What a condition is evaluated for. {@code actor} is {@code {self}} and {@code target} is {@code {target}}: each
 * a player's or an entity's id, or null. {@code placeholders} are the consumer's own (names without braces), and
 * {@code counters} feed {@code counter}.
 */
public record Context(String actor, String target, Map<String, String> placeholders, Map<String, Long> counters, long tick) {
    public Context {
        placeholders = placeholders == null ? Map.of() : Map.copyOf(placeholders);
        counters = counters == null ? Map.of() : Map.copyOf(counters);
    }

    public static Context of(String actor) {
        return new Context(actor, null, null, null, 0);
    }
}
