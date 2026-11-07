package hledjolf.conditions;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The JSON Schema subset condition schemas use: {@code type}, {@code const}, {@code enum}, {@code required},
 * {@code properties}, {@code additionalProperties}, {@code pattern}, {@code minLength}, {@code maxLength},
 * {@code minimum}, {@code maximum}, {@code x-decimals}, {@code items}, {@code minItems}, {@code maxItems},
 * {@code oneOf}, {@code anyOf} and local {@code $ref}s. Annotations ({@code description}, {@code default}, ...)
 * are ignored. A reference to the {@code condition} definition is handed back to the caller, which checks the
 * nested condition by its own type.
 */
final class Schema {
    private Schema() {}

    /** Where a value fails, and why. */
    record Fail(String pointer, String message) {}

    /** Checks a nested condition at a pointer; gives null when it's fine. */
    interface Nested {
        Fail check(Object value, String pointer);
    }

    static final Set<String> KEYWORDS = Set.of("$schema", "$id", "$ref", "$defs", "title", "description", "default", "examples",
        "type", "const", "enum", "required", "properties", "additionalProperties", "pattern", "minLength", "maxLength",
        "minimum", "maximum", "x-decimals", "x-since", "items", "minItems", "maxItems", "oneOf", "anyOf");

    /** The first place {@code value} fails {@code schema}, or null. {@code root} holds the {@code $defs}. */
    @SuppressWarnings("unchecked")
    static Fail check(Map<String, Object> schema, Object value, String at, Map<String, Object> root, Nested nested) {
        Object ref = schema.get("$ref");
        if (ref instanceof String r) {
            String name = r.startsWith("#/$defs/") ? r.substring(8) : null;
            if ("condition".equals(name) && nested != null) return nested.check(value, at);
            Object defs = root.get("$defs");
            Object def = name != null && defs instanceof Map<?, ?> d ? d.get(name) : null;
            if (!(def instanceof Map<?, ?>)) return new Fail(at, "the schema refers to " + r + ", which it doesn't define");
            Fail f = check((Map<String, Object>) def, value, at, root, nested);
            if (f != null) return f;
        }
        Object type = schema.get("type");
        if (type != null && !typeMatches(type, value)) return new Fail(at, "should be " + describe(type) + ", not " + kind(value));
        if (schema.containsKey("const") && !same(schema.get("const"), value)) return new Fail(at, "should be " + Jv.write(schema.get("const")));
        if (schema.get("enum") instanceof List<?> options && options.stream().noneMatch(o -> same(o, value))) {
            return new Fail(at, "should be one of " + Jv.write(options));
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> obj = (Map<String, Object>) m;
            if (schema.get("required") instanceof List<?> req) {
                for (Object k : req) if (!obj.containsKey(String.valueOf(k))) return new Fail(at + "/" + k, "is required");
            }
            Map<String, Object> props = schema.get("properties") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
            Object extra = schema.get("additionalProperties");
            for (Map.Entry<String, Object> e : obj.entrySet()) {
                String child = at + "/" + escape(e.getKey());
                Object sub = props.get(e.getKey());
                if (sub instanceof Map<?, ?> s) {
                    Fail f = check((Map<String, Object>) s, e.getValue(), child, root, nested);
                    if (f != null) return f;
                } else if (Boolean.FALSE.equals(extra)) {
                    return new Fail(child, "isn't a field of this type");
                } else if (extra instanceof Map<?, ?> s) {
                    Fail f = check((Map<String, Object>) s, e.getValue(), child, root, nested);
                    if (f != null) return f;
                }
            }
        }
        if (value instanceof String s) {
            if (schema.get("pattern") instanceof String p && !pattern(p).matcher(s).find()) return new Fail(at, "doesn't have the right form");
            if (schema.get("minLength") instanceof Long n && s.codePointCount(0, s.length()) < n) return new Fail(at, "is too short");
            if (schema.get("maxLength") instanceof Long n && s.codePointCount(0, s.length()) > n) return new Fail(at, "is too long");
        }
        BigDecimal num = Jv.decimal(value);
        if (num != null) {
            BigDecimal min = Jv.decimal(schema.get("minimum"));
            BigDecimal max = Jv.decimal(schema.get("maximum"));
            if (min != null && num.compareTo(min) < 0) return new Fail(at, "should be at least " + min.toPlainString());
            if (max != null && num.compareTo(max) > 0) return new Fail(at, "should be at most " + max.toPlainString());
            if (schema.get("x-decimals") instanceof Long n && decimals(num) > n) return new Fail(at, "has more than " + n + " decimal places");
        }
        if (value instanceof List<?> list) {
            if (schema.get("minItems") instanceof Long n && list.size() < n) return new Fail(at, "needs at least " + n + " item" + (n == 1 ? "" : "s"));
            if (schema.get("maxItems") instanceof Long n && list.size() > n) return new Fail(at, "has more than " + n + " item" + (n == 1 ? "" : "s"));
            if (schema.get("items") instanceof Map<?, ?> s) {
                for (int i = 0; i < list.size(); i++) {
                    Fail f = check((Map<String, Object>) s, list.get(i), at + "/" + i, root, nested);
                    if (f != null) return f;
                }
            }
        }
        if (schema.get("anyOf") instanceof List<?> any) {
            Fail first = null;
            for (Object s : any) {
                Fail f = s instanceof Map<?, ?> m ? check((Map<String, Object>) m, value, at, root, nested) : null;
                if (f == null) return null;
                if (first == null) first = f;
            }
            if (first != null) return first;
        }
        if (schema.get("oneOf") instanceof List<?> one) {
            int passed = 0;
            Fail first = null;
            for (Object s : one) {
                Fail f = s instanceof Map<?, ?> m ? check((Map<String, Object>) m, value, at, root, nested) : null;
                if (f == null) passed++;
                else if (first == null) first = f;
            }
            if (passed == 0) return value instanceof Map<?, ?> ? new Fail(at, "needs exactly one of its alternatives (" + first.message() + ")") : first;
            if (passed > 1) return new Fail(at, "matches more than one of its alternatives; give only one");
        }
        return null;
    }

    /** Decimal places as written, ignoring trailing zeros. */
    static int decimals(BigDecimal d) {
        return Math.max(0, d.stripTrailingZeros().scale());
    }

    private static final Map<String, Pattern> PATTERNS = new java.util.concurrent.ConcurrentHashMap<>();

    static Pattern pattern(String p) {
        return PATTERNS.computeIfAbsent(p, Pattern::compile);
    }

    private static boolean typeMatches(Object type, Object v) {
        if (type instanceof List<?> l) return l.stream().anyMatch(t -> typeMatches(t, v));
        switch (String.valueOf(type)) {
            case "object": return v instanceof Map<?, ?>;
            case "array": return v instanceof List<?>;
            case "string": return v instanceof String;
            case "boolean": return v instanceof Boolean;
            case "integer": return v instanceof Long;
            case "number": return v instanceof Long || v instanceof BigDecimal;
            case "null": return v == null;
            default: return false;
        }
    }

    private static String describe(Object type) {
        if (type instanceof List<?> l) return String.join(" or ", l.stream().map(t -> describe(t)).toList());
        String t = String.valueOf(type);
        return t.equals("integer") ? "a whole number" : t.equals("object") || t.equals("array") ? "an " + t : "a " + t;
    }

    static String kind(Object v) {
        if (v == null) return "null";
        if (v instanceof Map<?, ?>) return "an object";
        if (v instanceof List<?>) return "a list";
        if (v instanceof String) return "text";
        if (v instanceof Boolean) return "true or false";
        if (v instanceof Long) return "a whole number";
        return "a number with a fraction";
    }

    /** JSON equality: numbers by value, so 5 and 5.0 are the same. */
    static boolean same(Object a, Object b) {
        BigDecimal x = Jv.decimal(a), y = Jv.decimal(b);
        if (x != null && y != null) return x.compareTo(y) == 0;
        return a == null ? b == null : a.equals(b);
    }

    static String escape(String key) {
        return key.replace("~", "~0").replace("/", "~1");
    }

    /**
     * Why a mod's schema can't be used for {@code type}, or null: it must describe an object whose {@code type} is
     * fixed to the type's name, use only the subset, and have patterns that compile.
     */
    @SuppressWarnings("unchecked")
    static String unusable(Object schema, String type) {
        if (!(schema instanceof Map<?, ?> m)) return "the schema isn't a JSON object";
        Map<String, Object> s = (Map<String, Object>) m;
        if (!"object".equals(s.get("type"))) return "the schema must describe an object (\"type\": \"object\")";
        Object props = s.get("properties");
        Object t = props instanceof Map<?, ?> p ? p.get("type") : null;
        if (!(t instanceof Map<?, ?> tm) || !type.equals(tm.get("const"))) return "the schema's \"type\" property must have \"const\": \"" + type + "\"";
        return keywords(s, "");
    }

    @SuppressWarnings("unchecked")
    private static String keywords(Map<String, Object> s, String at) {
        for (Map.Entry<String, Object> e : s.entrySet()) {
            String k = e.getKey();
            if (!KEYWORDS.contains(k)) return "the schema uses \"" + k + "\" at " + (at.isEmpty() ? "its top" : at) + ", which conditions don't support";
            Object v = e.getValue();
            if (k.equals("pattern")) {
                if (!(v instanceof String p)) return "a pattern must be text";
                try {
                    pattern(p);
                } catch (PatternSyntaxException x) {
                    return "the pattern at " + at + " doesn't compile: " + x.getDescription();
                }
            }
            if (k.equals("$ref") && !(v instanceof String r && r.startsWith("#/$defs/"))) return "only local references (#/$defs/...) are supported";
            boolean schemaMap = k.equals("properties") || k.equals("$defs");
            if (schemaMap && v instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> c : m.entrySet()) {
                    if (!(c.getValue() instanceof Map<?, ?> sub)) return "the schema at " + at + "/" + k + "/" + c.getKey() + " isn't an object";
                    String r = keywords((Map<String, Object>) sub, at + "/" + k + "/" + c.getKey());
                    if (r != null) return r;
                }
            } else if ((k.equals("items") || k.equals("additionalProperties")) && v instanceof Map<?, ?> sub) {
                String r = keywords((Map<String, Object>) sub, at + "/" + k);
                if (r != null) return r;
            } else if ((k.equals("oneOf") || k.equals("anyOf")) && v instanceof List<?> l) {
                for (int i = 0; i < l.size(); i++) {
                    if (!(l.get(i) instanceof Map<?, ?> sub)) return "the schema at " + at + "/" + k + "/" + i + " isn't an object";
                    String r = keywords((Map<String, Object>) sub, at + "/" + k + "/" + i);
                    if (r != null) return r;
                }
            }
        }
        return null;
    }
}
