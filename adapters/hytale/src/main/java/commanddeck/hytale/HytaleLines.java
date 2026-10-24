package commanddeck.hytale;

import commanddeck.command.ArgStyle;
import commanddeck.command.ArgType;
import commanddeck.macro.Problem;
import java.util.ArrayList;
import java.util.List;

/**
 * Hytale's command-line rules, as measured on the server's text path. The tokenizer checks the whole line,
 * then each argument takes its tokens, so the rules depend on the argument type:
 *
 * <ul>
 * <li>A <b>single-token</b> value (every type except greedy strings, and every named value) gets a backslash
 *     before each of {@code \ , [ ] "} and before a leading {@code '}. A space or tab, a leading {@code --}, an
 *     empty required value, two of {@code \ , [ ] " '} next to each other, or a value that is exactly {@code ,}
 *     {@code [} or {@code ]} can't be sent.
 * <li>A <b>greedy string</b> reaches the command exactly as written, so it is never escaped. It can't be sent
 *     with an invalid escape, unmatched, empty or nested brackets, two of {@code , [ ]} next to each other
 *     (other than {@code ] [}), an odd number of quotes, a word starting with {@code --}, or a leading comma.
 * </ul>
 *
 * A value that can't be sent is never changed to make it fit: the result names the reason and the offending
 * character positions.
 *
 * <p><b>The escape skip</b> (measured 2026-10-23, CD-055): after an escape the tokenizer removes the backslash and
 * moves on two characters, so the character right after an escaped one is never examined and stays in the token,
 * even a space. So escaped characters can't stand next to each other, and a value that ends with an escaped
 * character can't have another word after it: one space merges the words ({@code a\, x} arrives as {@code a, x})
 * and two keep the first space in the value ({@code a\,  x} arrives as {@code "a, "} and {@code x}, measured
 * 2026-10-24). Both are unsendable ({@code escape_adjacent}). The line check simulates the skip.
 */
public final class HytaleLines {
    private HytaleLines() {}

    /** The five characters CommandDeck escapes anywhere in a one-word value. */
    static final String ESCAPABLE = "\\,[]\"";

    /** The tokenizer's escape list: the five, and {@code '} (escaped only at the start of a value). */
    static final String ESCAPES = "\\,[]\"'";

    /**
     * One form field: its argument and the value typed. A flag's value is {@code true} or {@code false}.
     * {@code tokens} above 1 is a game type that reads that many words (a position, a rotation): the value is
     * that many words, each sent as a token of its own. {@code list} is a list argument: {@code a, b} or
     * {@code [a, b]}, sent as typed, because its commas and brackets are the list.
     */
    public record Field(String name, ArgType type, ArgStyle style, boolean required, String value, int tokens, boolean list) {
        public Field(String name, ArgType type, ArgStyle style, boolean required, String value) {
            this(name, type, style, required, value, 1, false);
        }
    }

    /** Why a value can't be sent: a reason code and the character positions in the value (or line). */
    public record Unsendable(String reason, List<Long> positions, String field) {}

    /** A built line, or why it can't be built. */
    public record Built(String line, Unsendable unsendable) {
        public boolean sendable() {
            return unsendable == null;
        }
    }

    /**
     * The line for a command and its form: positional values in order, then named values as
     * {@code --name value} and set flags as {@code --name}, in the order given. Empty optional values and
     * unset flags are left out.
     */
    public static Built build(String command, List<Field> fields) {
        List<String> positional = new ArrayList<>();
        List<String> named = new ArrayList<>();
        // For each word: why it can't be sent if another word follows it (null: it can be).
        List<Unsendable> positionalIfFollowed = new ArrayList<>();
        List<Unsendable> namedIfFollowed = new ArrayList<>();
        for (Field f : fields) {
            String v = f.value() == null ? "" : f.value();
            if (f.style() == ArgStyle.FLAG) {
                if ("true".equalsIgnoreCase(v.trim())) {
                    named.add("--" + f.name());
                    namedIfFollowed.add(null);
                }
                continue;
            }
            if (v.isEmpty()) {
                if (f.required()) return new Built(null, new Unsendable("empty", List.of(), f.name()));
                continue;
            }
            boolean greedy = f.type() == ArgType.GREEDY_STRING && f.style() == ArgStyle.POSITIONAL;
            List<String> text = new ArrayList<>();
            if (f.list()) {
                Unsendable u = checkList(v);
                if (u != null) return new Built(null, new Unsendable(u.reason(), u.positions(), f.name()));
                text.add(v.strip());
            } else if (f.tokens() > 1) {
                String[] parts = v.strip().split("[ 	]+");
                if (parts.length != f.tokens()) return new Built(null, new Unsendable("count", List.of(), f.name()));
                for (String part : parts) {
                    Unsendable u = checkSingle(part);
                    if (u != null) return new Built(null, new Unsendable(u.reason(), List.of(), f.name()));
                    text.add(escape(part));
                }
            } else {
                Unsendable u = greedy ? checkGreedy(v) : checkSingle(v);
                if (u != null) return new Built(null, new Unsendable(u.reason(), u.positions(), f.name()));
                text.add(greedy ? v : escape(v));
            }
            // A word ending with an escaped character takes the space after it into the value (the skip, CD-055).
            List<Unsendable> ifFollowed = new ArrayList<>();
            for (String word : text) {
                List<Long> at = text.size() == 1 ? List.of((long) v.stripTrailing().length() - 1) : List.of();
                ifFollowed.add(endsWithEscape(word) ? new Unsendable("escape_adjacent", at, f.name()) : null);
            }
            if (f.style() == ArgStyle.NAMED) {
                named.add("--" + f.name());
                namedIfFollowed.add(null);
                named.addAll(text);
                namedIfFollowed.addAll(ifFollowed);
            } else {
                positional.addAll(text);
                positionalIfFollowed.addAll(ifFollowed);
            }
        }
        positional.addAll(named);
        positionalIfFollowed.addAll(namedIfFollowed);
        StringBuilder line = new StringBuilder("/").append(command);
        for (int i = 0; i < positional.size(); i++) {
            if (i + 1 < positional.size() && positionalIfFollowed.get(i) != null) return new Built(null, positionalIfFollowed.get(i));
            line.append(' ').append(positional.get(i));
        }
        return new Built(line.toString(), null);
    }

    /** Whether the tokenizer, skip included, reads {@code text}'s last character as an escaped one. */
    public static boolean endsWithEscape(CharSequence text) {
        int n = text.length();
        for (int i = 0; i < n; i++) {
            if (text.charAt(i) != '\\' || i + 1 >= n || ESCAPES.indexOf(text.charAt(i + 1)) < 0) continue;
            if (i + 1 == n - 1) return true;
            i += 2;
        }
        return false;
    }

    /** A backslash before each of {@code \ , [ ] "}, and before a leading {@code '}. */
    public static String escape(String value) {
        StringBuilder b = new StringBuilder(value.length() + 4);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (ESCAPABLE.indexOf(c) >= 0 || i == 0 && c == '\'') b.append('\\');
            b.append(c);
        }
        return b.toString();
    }

    /** Null when a single-token value can be sent once {@link #escape escaped}. */
    public static Unsendable checkSingle(String value) {
        if (value.isEmpty()) return new Unsendable("empty", List.of(), null);
        if (value.startsWith("--")) return new Unsendable("option", List.of(0L), null);
        List<Long> spaces = new ArrayList<>();
        for (int i = 0; i < value.length(); i++) {
            if (isSpace(value.charAt(i))) spaces.add((long) i);
        }
        if (!spaces.isEmpty()) return new Unsendable("space", spaces, null);
        // Exactly , [ or ] is list syntax even escaped (measured, CD-055).
        if (value.equals(",")) return new Unsendable("comma", List.of(0L), null);
        if (value.equals("[") || value.equals("]")) return new Unsendable("brackets", List.of(0L), null);
        // Escaped characters next to each other meet the escape skip (CD-055).
        List<Long> adjacent = new ArrayList<>();
        for (int i = 0; i + 1 < value.length(); i++) {
            if (ESCAPES.indexOf(value.charAt(i)) < 0 || ESCAPES.indexOf(value.charAt(i + 1)) < 0) continue;
            if (adjacent.isEmpty() || adjacent.get(adjacent.size() - 1) != i) adjacent.add((long) i);
            adjacent.add((long) i + 1);
        }
        return adjacent.isEmpty() ? null : new Unsendable("escape_adjacent", adjacent, null);
    }

    /**
     * Null when a list argument's value can be sent as typed (measured 2026-10-23): items separated by commas,
     * with optional spaces around them, optionally inside one pair of brackets. An item can't be empty or
     * hold a space, a quote, a backslash or another bracket, and the value can't start with {@code --}.
     */
    public static Unsendable checkList(String value) {
        String v = value.strip();
        int lead = value.indexOf(v.isEmpty() ? " " : v.substring(0, 1));
        if (v.isEmpty()) return new Unsendable("empty", List.of(), null);
        if (v.startsWith("--")) return new Unsendable("option", List.of((long) lead), null);
        int from = 0;
        int to = v.length();
        if (v.startsWith("[")) {
            if (!v.endsWith("]") || v.length() < 2) return new Unsendable("brackets", List.of((long) lead), null);
            from = 1;
            to = v.length() - 1;
        }
        int start = from;
        for (int i = from; i <= to; i++) {
            if (i == to || v.charAt(i) == ',') {
                String item = v.substring(start, i).strip();
                if (item.isEmpty()) return new Unsendable("comma", List.of((long) (lead + Math.min(i, v.length() - 1))), null);
                start = i + 1;
                continue;
            }
            char c = v.charAt(i);
            String reason = c == '[' || c == ']' ? "brackets" : c == '"' ? "quote" : c == '\\' ? "backslash" : null;
            if (reason == null && isSpace(c)) {
                // Spaces may only stand next to a comma or the brackets, not inside an item.
                String before = v.substring(start, i).strip();
                String after = v.substring(i, to).strip();
                boolean insideItem = !before.isEmpty() && !after.isEmpty() && after.charAt(0) != ',';
                if (insideItem) reason = "space";
            }
            if (reason != null) return new Unsendable(reason, List.of((long) (lead + i)), null);
        }
        return null;
    }

    /** Null when a greedy string can be sent exactly as it is. */
    public static Unsendable checkGreedy(String value) {
        if (value.isEmpty()) return new Unsendable("empty", List.of(), null);
        Unsendable u = structure(value);
        if (u != null) return u;
        List<Long> options = new ArrayList<>();
        for (int i = 0; i + 1 < value.length(); i++) {
            boolean wordStart = i == 0 || isSpace(value.charAt(i - 1));
            if (wordStart && value.charAt(i) == '-' && value.charAt(i + 1) == '-') options.add((long) i);
        }
        if (!options.isEmpty()) return new Unsendable("option", options, null);
        // A leading comma leaves the command with nothing (measured: "Expected: 1, actual: 0").
        if (value.stripLeading().startsWith(",")) return new Unsendable("comma", List.of((long) value.indexOf(',')), null);
        return null;
    }

    /**
     * Null when a line written as typed in chat can pass the tokenizer, escape skip included: valid escapes, no
     * escaped character followed by another of {@code \ , [ ] " '} ({@code escape_adjacent}), no word that is
     * an escaped {@code ,} {@code [} or {@code ]} alone, no word opening a {@code '} quote it doesn't close,
     * matched and non-empty
     * brackets that don't nest, no two of {@code , [ ]} next to each other (other than {@code ] [}), and an even
     * number of quotes. Otherwise an {@code unsendable} problem with the
     * {@code reason} and the {@code positions} in the line. Whether the command accepts its arguments is for
     * the game to say when it runs.
     */
    public static Problem checkLine(String line) {
        Unsendable u = structure(line);
        if (u == null) return null;
        return Problem.of("unsendable", message(u.reason()) + " (at " + u.positions() + ")", "reason", u.reason(),
            "positions", u.positions());
    }

    /** The checks the tokenizer makes on any text: escapes, brackets, neighbouring special tokens, then quotes. */
    private static Unsendable structure(String s) {
        int n = s.length();
        List<Long> badEscapes = new ArrayList<>();
        List<Long> adjacent = new ArrayList<>();
        // Characters that a valid backslash escapes don't count as brackets or quotes. The character after an
        // escaped one is skipped by the tokenizer (CD-055): never examined, so a space there doesn't split.
        boolean[] escaped = new boolean[n];
        boolean[] skipped = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (s.charAt(i) != '\\') continue;
            if (i + 1 < n && ESCAPES.indexOf(s.charAt(i + 1)) >= 0) {
                escaped[i + 1] = true;
                if (i + 2 < n) {
                    skipped[i + 2] = true;
                    if (ESCAPES.indexOf(s.charAt(i + 2)) >= 0) {
                        adjacent.add((long) i + 1);
                        adjacent.add((long) i + 2);
                    }
                }
                i += 2;
            } else {
                badEscapes.add((long) i);
            }
        }
        if (!adjacent.isEmpty()) return new Unsendable("escape_adjacent", adjacent, null);
        if (!badEscapes.isEmpty()) return new Unsendable("backslash", badEscapes, null);

        // Words, as the tokenizer splits them: an escaped , [ or ] alone is still list syntax, and a word
        // starting with ' opens a quote (measured: 'ab is refused, 'ab' arrives as 'ab').
        int start = -1;
        for (int i = 0; i <= n; i++) {
            boolean split = i == n || isSpace(s.charAt(i)) && !skipped[i];
            if (!split) {
                if (start < 0) start = i;
                continue;
            }
            if (start < 0) continue;
            String word = s.substring(start, i);
            if (word.equals("\\,")) return new Unsendable("comma", List.of((long) start), null);
            if (word.equals("\\[") || word.equals("\\]")) return new Unsendable("brackets", List.of((long) start), null);
            if (word.charAt(0) == '\'' && (word.length() < 2 || word.indexOf('\'', 1) != word.length() - 1 || escaped[i - 1])) {
                return new Unsendable("quote", List.of((long) start), null);
            }
            start = -1;
        }

        List<Long> badBrackets = new ArrayList<>();
        int open = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped[i]) continue;
            if (c == '[') {
                if (open >= 0) {
                    // Nested brackets aren't measured yet, so they are never sent.
                    badBrackets.add((long) i);
                } else {
                    open = i;
                }
            } else if (c == ']') {
                if (open < 0) {
                    badBrackets.add((long) i);
                } else {
                    if (s.substring(open + 1, i).isBlank()) {
                        badBrackets.add((long) open);
                        badBrackets.add((long) i);
                    }
                    open = -1;
                }
            }
        }
        if (open >= 0 && !badBrackets.contains((long) open)) badBrackets.add((long) open);
        if (!badBrackets.isEmpty()) {
            badBrackets.sort(null);
            return new Unsendable("brackets", badBrackets, null);
        }

        // The tokenizer refuses two of , [ ] next to each other (spaces between don't count), except ] then [.
        int prev = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isSpace(c)) continue;
            boolean special = !escaped[i] && (c == ',' || c == '[' || c == ']');
            if (special && prev >= 0) {
                char p = s.charAt(prev);
                if (!(p == ']' && c == '[')) {
                    return new Unsendable(p == ',' && c == ',' ? "comma" : "brackets", List.of((long) prev, (long) i), null);
                }
            }
            prev = special ? i : -1;
        }

        int quotes = 0;
        int lastQuote = -1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '"' && !escaped[i]) {
                quotes++;
                lastQuote = i;
            }
        }
        if (quotes % 2 == 1) return new Unsendable("quote", List.of((long) lastQuote), null);
        return null;
    }

    static boolean isSpace(char c) {
        return c == ' ' || c == '\t';
    }

    /** A sentence for people, for an unsendable reason code. */
    public static String message(String reason) {
        switch (reason) {
            case "backslash":
                return "A backslash that doesn't escape \\ , [ ] \" or ' can't be sent";
            case "escape_adjacent":
                return "Two of \\ , [ ] \" ' next to each other, or one of them ending a value with more after it, can't be sent (the game skips the character after an escape)";
            case "brackets":
                return "Brackets must come in non-empty, unnested pairs";
            case "quote":
                return "Quotes must come in pairs";
            case "option":
                return "A word starting with -- is read as a named argument";
            case "comma":
                return "A lone comma can't be sent";
            case "space":
                return "This argument is one word: it can't contain a space or tab";
            case "empty":
                return "This argument is required";
            case "count":
                return "This argument takes a fixed number of values, separated by spaces";
            default:
                return reason;
        }
    }
}
