package hledjolf.conditions;

import hledjolf.conditions.api.JsonObjectView;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The library's own small JSON reader and writer, so it needs no other mod's classes. Objects decode to
 * {@link LinkedHashMap}, arrays to {@link ArrayList}, numbers to {@link Long} when written as a whole number
 * without a fraction or exponent and to {@link BigDecimal} otherwise (so the decimals written are the decimals
 * checked), plus {@link String}, {@link Boolean} and null.
 */
public final class Jv {
    private Jv() {}

    /** Text that isn't JSON; the message says where. */
    public static final class ParseException extends RuntimeException {
        ParseException(String message) {
            super(message);
        }
    }

    public static Object parse(String text) {
        Jv.Reader r = new Jv.Reader(text);
        r.space();
        Object v = r.value();
        r.space();
        if (r.i < text.length()) throw r.fail("text after the value");
        return v;
    }

    private static final class Reader {
        final String s;
        int i;

        Reader(String s) {
            this.s = s;
        }

        ParseException fail(String what) {
            return new ParseException(what + " at character " + (i + 1));
        }

        void space() {
            while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t' || s.charAt(i) == '\n' || s.charAt(i) == '\r')) i++;
        }

        Object value() {
            if (i >= s.length()) throw fail("end of text");
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            if (c == '-' || (c >= '0' && c <= '9')) return number();
            throw fail("unexpected '" + c + "'");
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            space();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                space();
                if (i >= s.length() || s.charAt(i) != '"') throw fail("expected a field name");
                String k = string();
                if (m.containsKey(k)) throw fail("field \"" + k + "\" given twice");
                space();
                if (i >= s.length() || s.charAt(i) != ':') throw fail("expected ':'");
                i++;
                space();
                m.put(k, value());
                space();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
                throw fail("expected ',' or '}'");
            }
        }

        List<Object> array() {
            List<Object> a = new ArrayList<>();
            i++;
            space();
            if (i < s.length() && s.charAt(i) == ']') { i++; return a; }
            while (true) {
                space();
                a.add(value());
                space();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                if (i < s.length() && s.charAt(i) == ']') { i++; return a; }
                throw fail("expected ',' or ']'");
            }
        }

        String string() {
            StringBuilder b = new StringBuilder();
            i++;
            while (true) {
                if (i >= s.length()) throw fail("unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c < 0x20) throw fail("control character in a string");
                if (c != '\\') { b.append(c); continue; }
                if (i >= s.length()) throw fail("unterminated escape");
                char e = s.charAt(i++);
                switch (e) {
                    case '"': case '\\': case '/': b.append(e); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw fail("short \\u escape");
                        try {
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException x) {
                            throw fail("bad \\u escape");
                        }
                        i += 4;
                        break;
                    default: throw fail("bad escape");
                }
            }
        }

        Object number() {
            int start = i;
            if (s.charAt(i) == '-') i++;
            if (i >= s.length()) throw fail("bad number");
            if (s.charAt(i) == '0') i++;
            else if (s.charAt(i) >= '1' && s.charAt(i) <= '9') { while (i < s.length() && Character.isDigit(s.charAt(i))) i++; }
            else throw fail("bad number");
            boolean whole = true;
            if (i < s.length() && s.charAt(i) == '.') {
                whole = false;
                i++;
                int d = i;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
                if (i == d) throw fail("bad number");
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                whole = false;
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                int d = i;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
                if (i == d) throw fail("bad number");
            }
            String t = s.substring(start, i);
            if (whole && t.length() <= 18) return Long.parseLong(t);
            return new BigDecimal(t);
        }
    }

    /** Compact JSON. */
    public static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v);
        return b.toString();
    }

    private static void write(StringBuilder b, Object v) {
        if (v == null) b.append("null");
        else if (v instanceof String str) quote(b, str);
        else if (v instanceof BigDecimal d) b.append(d.toString());
        else if (v instanceof Number || v instanceof Boolean) b.append(v);
        else if (v instanceof Map<?, ?> m) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) b.append(',');
                first = false;
                quote(b, String.valueOf(e.getKey()));
                b.append(':');
                write(b, e.getValue());
            }
            b.append('}');
        } else if (v instanceof List<?> l) {
            b.append('[');
            for (int k = 0; k < l.size(); k++) {
                if (k > 0) b.append(',');
                write(b, l.get(k));
            }
            b.append(']');
        } else if (v instanceof View view) write(b, view.map);
        else throw new IllegalArgumentException("not a JSON value: " + v.getClass().getName());
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }

    /** A JSON number as a BigDecimal, or null for anything else. */
    static BigDecimal decimal(Object v) {
        if (v instanceof BigDecimal d) return d;
        if (v instanceof Long l) return BigDecimal.valueOf(l);
        return null;
    }

    /** The read-only view evaluators get. */
    static final class View implements JsonObjectView {
        final Map<String, Object> map;

        View(Map<String, Object> map) {
            this.map = map;
        }

        @Override public Set<String> fields() { return Collections.unmodifiableSet(map.keySet()); }
        @Override public boolean has(String f) { return map.containsKey(f); }
        @Override public String string(String f) { return map.get(f) instanceof String s ? s : null; }
        @Override public BigDecimal number(String f) { return decimal(map.get(f)); }
        @Override public Boolean bool(String f) { return map.get(f) instanceof Boolean b ? b : null; }
        @Override public String json() { return write(map); }

        @Override
        public List<Object> list(String f) {
            return map.get(f) instanceof List<?> l ? viewList(l) : null;
        }

        @Override
        @SuppressWarnings("unchecked")
        public JsonObjectView object(String f) {
            return map.get(f) instanceof Map<?, ?> m ? new View((Map<String, Object>) m) : null;
        }

        @SuppressWarnings("unchecked")
        private static List<Object> viewList(List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) out.add(new View((Map<String, Object>) m));
                else if (o instanceof List<?> inner) out.add(viewList(inner));
                else if (o instanceof Long n) out.add(BigDecimal.valueOf(n));
                else out.add(o);
            }
            return Collections.unmodifiableList(out);
        }
    }
}
