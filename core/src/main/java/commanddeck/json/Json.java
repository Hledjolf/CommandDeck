package commanddeck.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer for config files that must survive a round trip.
 *
 * <p>Objects decode to {@link LinkedHashMap} (key order kept), arrays to {@link ArrayList}, numbers to
 * {@link Long} when written without a fraction or exponent and to {@link Double} otherwise, plus
 * {@link String}, {@link Boolean} and {@code null}. Writing uses the same types, so a value read as
 * {@code 5} is written as {@code 5} and one read as {@code 5.0} stays {@code 5.0}.
 */
public final class Json {
    private Json() {}

    /** Thrown for text that is not valid JSON; the message names the line and column. */
    public static final class ParseException extends RuntimeException {
        public ParseException(String message) {
            super(message);
        }
    }

    // ---- reading ----------------------------------------------------------------------------------

    public static Object parse(String text) {
        Reader r = new Reader(text);
        r.skipWs();
        Object v = r.value();
        r.skipWs();
        if (!r.atEnd()) throw r.error("unexpected text after the JSON value");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new ParseException("expected a JSON object at the top level, got " + typeName(v));
        return (Map<String, Object>) v;
    }

    private static final class Reader {
        private final String s;
        private int i;

        Reader(String text) {
            this.s = text.startsWith("﻿") ? text.substring(1) : text;
        }

        boolean atEnd() {
            return i >= s.length();
        }

        ParseException error(String msg) {
            int line = 1, col = 1;
            for (int k = 0; k < Math.min(i, s.length()); k++) {
                if (s.charAt(k) == '\n') {
                    line++;
                    col = 1;
                } else {
                    col++;
                }
            }
            return new ParseException(msg + " (line " + line + ", column " + col + ")");
        }

        void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
        }

        Object value() {
            if (atEnd()) throw error("unexpected end of text");
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) yield number();
                    throw error("unexpected character '" + c + "'");
                }
            };
        }

        Object literal(String word, Object v) {
            if (!s.startsWith(word, i)) throw error("expected " + word);
            i += word.length();
            return v;
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            skipWs();
            if (!atEnd() && s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                skipWs();
                if (atEnd() || s.charAt(i) != '"') throw error("expected a key in quotes");
                String k = string();
                skipWs();
                if (atEnd() || s.charAt(i) != ':') throw error("expected ':' after key \"" + k + "\"");
                i++;
                skipWs();
                m.put(k, value());
                skipWs();
                if (atEnd()) throw error("unterminated object");
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') {
                    i--;
                    throw error("expected ',' or '}'");
                }
            }
        }

        List<Object> array() {
            List<Object> a = new ArrayList<>();
            i++;
            skipWs();
            if (!atEnd() && s.charAt(i) == ']') {
                i++;
                return a;
            }
            while (true) {
                skipWs();
                a.add(value());
                skipWs();
                if (atEnd()) throw error("unterminated array");
                char c = s.charAt(i++);
                if (c == ']') return a;
                if (c != ',') {
                    i--;
                    throw error("expected ',' or ']'");
                }
            }
        }

        String string() {
            StringBuilder b = new StringBuilder();
            i++;
            while (true) {
                if (atEnd()) throw error("unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    if (atEnd()) throw error("unterminated escape");
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"' -> b.append('"');
                        case '\\' -> b.append('\\');
                        case '/' -> b.append('/');
                        case 'b' -> b.append('\b');
                        case 'f' -> b.append('\f');
                        case 'n' -> b.append('\n');
                        case 'r' -> b.append('\r');
                        case 't' -> b.append('\t');
                        case 'u' -> {
                            if (i + 4 > s.length()) throw error("bad \\u escape");
                            try {
                                b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            } catch (NumberFormatException ex) {
                                throw error("bad \\u escape");
                            }
                            i += 4;
                        }
                        default -> throw error("bad escape \\" + e);
                    }
                } else if (c < 0x20) {
                    i--;
                    throw error("control character in string");
                } else {
                    b.append(c);
                }
            }
        }

        Object number() {
            int start = i;
            if (s.charAt(i) == '-') i++;
            boolean fraction = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') {
                    i++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    fraction = true;
                    i++;
                } else {
                    break;
                }
            }
            String t = s.substring(start, i);
            if (!t.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) {
                i = start;
                throw error("bad number '" + t + "'");
            }
            if (!fraction) {
                try {
                    return Long.parseLong(t);
                } catch (NumberFormatException tooBig) {
                    return new BigDecimal(t).doubleValue();
                }
            }
            return Double.parseDouble(t);
        }
    }

    // ---- writing ----------------------------------------------------------------------------------

    /** Compact single-line JSON, e.g. for log lines and read-only display. */
    public static String write(Object v) {
        StringBuilder b = new StringBuilder();
        writeCompact(v, b);
        return b.toString();
    }

    /** Pretty JSON with two-space indentation and a trailing newline. */
    public static String pretty(Object v) {
        return pretty(v, "  ");
    }

    /** Pretty JSON with the given indent unit (spaces or a tab) and a trailing newline. */
    public static String pretty(Object v, String indent) {
        StringBuilder b = new StringBuilder();
        writePretty(v, b, indent, 0);
        return b.append('\n').toString();
    }

    /**
     * The indent unit a file uses: the leading whitespace of its first indented line ("  " if none),
     * so a rewritten file looks like the original.
     */
    public static String detectIndent(String text) {
        for (String line : text.split("\n", -1)) {
            int n = 0;
            while (n < line.length() && (line.charAt(n) == ' ' || line.charAt(n) == '\t')) n++;
            if (n > 0 && n < line.length()) {
                String ws = line.substring(0, n);
                if (ws.startsWith("\t")) return "\t";
                return " ".repeat(Math.min(n, 8));
            }
        }
        return "  ";
    }

    private static void writeCompact(Object v, StringBuilder b) {
        if (v instanceof Map<?, ?> m) {
            b.append('{');
            boolean first = true;
            for (var e : m.entrySet()) {
                if (!first) b.append(',');
                first = false;
                quote(String.valueOf(e.getKey()), b);
                b.append(':');
                writeCompact(e.getValue(), b);
            }
            b.append('}');
        } else if (v instanceof List<?> l) {
            b.append('[');
            for (int k = 0; k < l.size(); k++) {
                if (k > 0) b.append(',');
                writeCompact(l.get(k), b);
            }
            b.append(']');
        } else {
            scalar(v, b);
        }
    }

    private static void writePretty(Object v, StringBuilder b, String indent, int depth) {
        if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                b.append("{}");
                return;
            }
            b.append('{');
            boolean first = true;
            for (var e : m.entrySet()) {
                b.append(first ? "\n" : ",\n");
                first = false;
                b.append(indent.repeat(depth + 1));
                quote(String.valueOf(e.getKey()), b);
                b.append(": ");
                writePretty(e.getValue(), b, indent, depth + 1);
            }
            b.append('\n').append(indent.repeat(depth)).append('}');
        } else if (v instanceof List<?> l) {
            if (l.isEmpty()) {
                b.append("[]");
                return;
            }
            boolean flat = l.stream().noneMatch(x -> x instanceof Map || x instanceof List);
            if (flat) {
                // Short lists of scalars stay on one line, as people tend to write them.
                StringBuilder line = new StringBuilder("[");
                for (int k = 0; k < l.size(); k++) {
                    if (k > 0) line.append(", ");
                    scalar(l.get(k), line);
                }
                if (line.length() <= 100) {
                    b.append(line).append(']');
                    return;
                }
            }
            b.append('[');
            for (int k = 0; k < l.size(); k++) {
                b.append(k == 0 ? "\n" : ",\n").append(indent.repeat(depth + 1));
                writePretty(l.get(k), b, indent, depth + 1);
            }
            b.append('\n').append(indent.repeat(depth)).append(']');
        } else {
            scalar(v, b);
        }
    }

    private static void scalar(Object v, StringBuilder b) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String s) {
            quote(s, b);
        } else if (v instanceof Boolean x) {
            b.append(x.booleanValue());
        } else if (v instanceof Double || v instanceof Float) {
            b.append(formatDouble(((Number) v).doubleValue()));
        } else if (v instanceof Number n) {
            b.append(n.longValue());
        } else {
            quote(v.toString(), b);
        }
    }

    /** A double as JSON: always with a fraction or exponent, so it reads back as a Double. */
    public static String formatDouble(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return "null";
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return (long) d + ".0";
        String s = Double.toString(d);
        return s;
    }

    private static void quote(String s, StringBuilder b) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        b.append('"');
    }

    public static String typeName(Object v) {
        if (v == null) return "null";
        if (v instanceof Map) return "object";
        if (v instanceof List) return "array";
        if (v instanceof Boolean) return "boolean";
        if (v instanceof Long || v instanceof Integer) return "integer";
        if (v instanceof Number) return "number";
        return "string";
    }

    /** Deep copy of a decoded JSON value (maps and lists copied, scalars shared). */
    @SuppressWarnings("unchecked")
    public static <T> T copy(T v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (var e : m.entrySet()) out.put((String) e.getKey(), copy(e.getValue()));
            return (T) out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object x : l) out.add(copy(x));
            return (T) out;
        }
        return v;
    }
}
