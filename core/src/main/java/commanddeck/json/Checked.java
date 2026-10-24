package commanddeck.json;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shape checks for hand-written format readers. Each check names the value by its JSON Pointer and throws
 * {@link Invalid} on the first value that doesn't fit, so a reader reports exactly one problem and where.
 */
public final class Checked {
    private Checked() {}

    /** The first value that doesn't fit, at a JSON Pointer such as {@code /commands/2/args/0/type}. */
    public static final class Invalid extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final String pointer;

        public Invalid(String pointer, String message) {
            super(message, null, false, false);
            this.pointer = pointer;
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object v, String at) {
        if (!(v instanceof Map)) throw new Invalid(at, "expected an object, got " + Json.typeName(v));
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> array(Object v, String at) {
        if (!(v instanceof List)) throw new Invalid(at, "expected an array, got " + Json.typeName(v));
        return (List<Object>) v;
    }

    public static Object need(Map<String, Object> m, String key, String at) {
        if (!m.containsKey(key)) throw new Invalid(at + "/" + key, "missing " + key);
        return m.get(key);
    }

    public static void only(Map<String, Object> m, Set<String> allowed, String at) {
        for (String k : m.keySet()) {
            if (!allowed.contains(k)) throw new Invalid(at + "/" + k, "unknown field " + k);
        }
    }

    public static String string(Object v, String at) {
        if (!(v instanceof String)) throw new Invalid(at, "expected text, got " + Json.typeName(v));
        return (String) v;
    }

    /** An optional text field, or null when absent; {@code max} is in characters, or -1 for no limit. */
    public static String optString(Map<String, Object> m, String key, String at, int max) {
        if (!m.containsKey(key)) return null;
        String s = string(m.get(key), at + "/" + key);
        if (max >= 0) maxLength(s, max, at + "/" + key);
        return s;
    }

    public static boolean bool(Object v, String at) {
        if (!(v instanceof Boolean)) throw new Invalid(at, "expected true or false, got " + Json.typeName(v));
        return (Boolean) v;
    }

    public static boolean optBool(Map<String, Object> m, String key, String at, boolean otherwise) {
        return m.containsKey(key) ? bool(m.get(key), at + "/" + key) : otherwise;
    }

    public static long integer(Object v, String at) {
        if (!(v instanceof Long)) throw new Invalid(at, "expected a whole number, got " + Json.typeName(v));
        return (Long) v;
    }

    public static double number(Object v, String at) {
        if (v instanceof Long) return ((Long) v).doubleValue();
        if (v instanceof Double) return (Double) v;
        throw new Invalid(at, "expected a number, got " + Json.typeName(v));
    }

    public static Double optNumber(Map<String, Object> m, String key, String at) {
        return m.containsKey(key) ? number(m.get(key), at + "/" + key) : null;
    }

    public static void maxLength(String s, int max, String at) {
        if (s.codePointCount(0, s.length()) > max) throw new Invalid(at, "longer than " + max + " characters");
    }
}
