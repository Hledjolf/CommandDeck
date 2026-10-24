package commanddeck.macro;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Why a macro was refused: a stable {@code code} (the same code the integration API reports, such as
 * {@code invalid} or {@code depth_exceeded}), a message for people, and named detail fields
 * ({@code pointer}, {@code depth}, {@code max_depth}…) in a fixed order.
 */
public record Problem(String code, String message, Map<String, Object> detail) {
    public static Problem of(String code, String message, Object... keyValues) {
        Map<String, Object> d = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) d.put((String) keyValues[i], keyValues[i + 1]);
        return new Problem(code, message, d);
    }

    /** A schema failure at a JSON Pointer such as {@code /body/steps/2/line}. */
    public static Problem invalid(String pointer, String message) {
        return of("invalid", message + " (at " + (pointer.isEmpty() ? "/" : pointer) + ")", "pointer", pointer);
    }
}
