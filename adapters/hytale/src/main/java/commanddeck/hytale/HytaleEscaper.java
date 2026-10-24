package commanddeck.hytale;

import commanddeck.command.Arg;
import commanddeck.command.ArgStyle;
import commanddeck.command.ArgType;
import commanddeck.command.CommandCatalog;
import commanddeck.command.CommandInfo;
import commanddeck.command.Variant;
import commanddeck.macro.Placeholders;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Escapes a placeholder's value for the argument it lands in. The line is matched to its command, and its
 * words are counted to find the argument the placeholder sits in. When every form of the command agrees that
 * it's a single-token argument, the value is escaped; when every form agrees it's a greedy string, the value
 * is checked but left as it is. When the argument can't be told, a value that needs no escaping either way
 * goes in unchanged, and any other value can't be sent. An argument takes as many words as the game says its
 * type reads (a rotation three, a chunk position two); where the game doesn't say, the argument can't be told.
 * A list can span words, so a placeholder in or after one can't be told, and neither can one that shares its
 * word with other text.
 */
final class HytaleEscaper implements Placeholders.Escaper {
    private enum Kind { SINGLE, GREEDY, UNKNOWN }

    /** How the game reads a form's argument. {@code form} is -1 for the main form, else the variant's index. */
    interface Shapes {
        /** The words the argument takes; 0 when unknown. */
        int tokens(String command, int form, String arg);

        boolean isList(String command, int form, String arg);
    }

    private final Supplier<CommandCatalog> catalog;
    private final Shapes shapes;

    HytaleEscaper(Supplier<CommandCatalog> catalog) {
        this(catalog, null);
    }

    HytaleEscaper(Supplier<CommandCatalog> catalog, Shapes shapes) {
        this.catalog = catalog;
        this.shapes = shapes;
    }

    @Override
    public String escape(String line, int at, String value) {
        String out;
        switch (kindAt(line, at)) {
            case SINGLE:
                out = HytaleLines.checkSingle(value) == null ? HytaleLines.escape(value) : null;
                break;
            case GREEDY:
                out = HytaleLines.checkGreedy(value) == null ? value : null;
                break;
            default:
                out = plain(value) ? value : null;
        }
        if (out == null || !HytaleLines.endsWithEscape(out)) return out;
        // The tokenizer skips the character after an escape, so the space after this value would join it (CD-055).
        int close = line.indexOf('}', at);
        String rest = close < 0 ? "" : line.substring(close + 1);
        return rest.isBlank() ? out : null;
    }

    /** Needs no escaping as a single token and none as part of a greedy string. */
    static boolean plain(String value) {
        if (HytaleLines.checkSingle(value) != null || !HytaleLines.escape(value).equals(value)) return false;
        return HytaleLines.checkGreedy(value) == null;
    }

    private Kind kindAt(String line, int at) {
        CommandCatalog cat = catalog.get();
        if (cat == null) return Kind.UNKNOWN;
        CommandInfo c = cat.match(line);
        if (c == null) return Kind.UNKNOWN;
        String prefix = line.substring(0, at);
        if (prefix.startsWith("/")) prefix = prefix.substring(1);
        List<String> words = words(prefix);
        // A placeholder that shares its word with other text can't be told apart from that text.
        if (!prefix.isEmpty() && !HytaleLines.isSpace(prefix.charAt(prefix.length() - 1))) return Kind.UNKNOWN;
        int close = line.indexOf('}', at);
        if (close >= 0 && close + 1 < line.length() && !HytaleLines.isSpace(line.charAt(close + 1))) return Kind.UNKNOWN;
        int token = words.size();
        int nameWords = nameWords(cat, c, words(line.startsWith("/") ? line.substring(1) : line));
        if (nameWords < 0 || token < nameWords) return Kind.UNKNOWN;

        // Walk the words between the command name and the placeholder: named values, flags and positionals.
        int positional = 0;
        for (int i = nameWords; i <= token; i++) {
            String w = i < words.size() ? words.get(i) : "";
            if (w.startsWith("--")) {
                String name = w.substring(2);
                if (name.indexOf('=') >= 0) return Kind.UNKNOWN;
                ArgStyle style = namedStyle(c, name);
                if (style == ArgStyle.NAMED) {
                    int width = namedWidth(c, name);
                    if (width < 1) return Kind.UNKNOWN;
                    if (token <= i + width) return Kind.SINGLE;
                    i += width;
                } else if (style == null) {
                    return Kind.UNKNOWN;
                }
                continue;
            }
            if (i == token) return positionalKind(c, positional);
            positional++;
        }
        return Kind.UNKNOWN;
    }

    /** Every form's argument at this positional word must agree. */
    private Kind positionalKind(CommandInfo c, int word) {
        Kind agreed = null;
        List<List<Arg>> forms = forms(c);
        for (int f = 0; f < forms.size(); f++) {
            Kind k = null;
            int start = 0;
            for (Arg a : forms.get(f)) {
                if (a.style() != ArgStyle.POSITIONAL) continue;
                if (a.type() == ArgType.GREEDY_STRING) {
                    if (word >= start) k = Kind.GREEDY;
                    break;
                }
                if (shapes != null && shapes.isList(c.name(), f - 1, a.name())) {
                    k = Kind.UNKNOWN;
                    break;
                }
                int width = width(c, f - 1, a);
                if (width < 1) {
                    k = Kind.UNKNOWN;
                    break;
                }
                if (word < start + width) {
                    k = Kind.SINGLE;
                    break;
                }
                start += width;
            }
            if (k == null) continue;
            if (agreed != null && agreed != k) return Kind.UNKNOWN;
            agreed = k;
        }
        return agreed == null ? Kind.UNKNOWN : agreed;
    }

    /** The words an argument takes, as the game reports its type; -1 when the game doesn't say. */
    private int width(CommandInfo c, int form, Arg a) {
        int t = shapes == null ? 0 : shapes.tokens(c.name(), form, a.name());
        return t > 0 ? t : -1;
    }

    /** The words a {@code --name} value takes if every form that has it agrees and none is a list; else -1. */
    private int namedWidth(CommandInfo c, String name) {
        int agreed = -1;
        List<List<Arg>> forms = forms(c);
        for (int f = 0; f < forms.size(); f++) {
            for (Arg a : forms.get(f)) {
                if (!a.name().equals(name) || a.style() != ArgStyle.NAMED) continue;
                if (shapes != null && shapes.isList(c.name(), f - 1, a.name())) return -1;
                int w = width(c, f - 1, a);
                if (w < 1 || agreed >= 0 && agreed != w) return -1;
                agreed = w;
            }
        }
        return agreed;
    }

    /** The style of a {@code --name} argument if every form that has it agrees; null if none does or they differ. */
    private static ArgStyle namedStyle(CommandInfo c, String name) {
        ArgStyle agreed = null;
        for (List<Arg> form : forms(c)) {
            for (Arg a : form) {
                if (!a.name().equals(name) || a.style() == ArgStyle.POSITIONAL) continue;
                if (agreed != null && agreed != a.style()) return null;
                agreed = a.style();
            }
        }
        return agreed;
    }

    private static List<List<Arg>> forms(CommandInfo c) {
        List<List<Arg>> out = new ArrayList<>();
        out.add(c.args());
        for (Variant v : c.variants()) out.add(v.args());
        return out;
    }

    /** How many words of the line the command's name (or alias) takes; -1 if none matches. */
    private static int nameWords(CommandCatalog cat, CommandInfo c, List<String> lineWords) {
        for (int n = lineWords.size(); n > 0; n--) {
            CommandInfo found = cat.find(String.join(" ", lineWords.subList(0, n)));
            if (found == c) return n;
        }
        return -1;
    }

    private static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        for (String w : s.trim().split("[ \t]+")) {
            if (!w.isEmpty()) out.add(w);
        }
        return out;
    }
}
