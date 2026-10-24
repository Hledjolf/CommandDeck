package commanddeck.macro;

/**
 * Fills {@code {self}}, {@code {target}}, {@code {x}} {@code {y}} {@code {z}} and {@code {look}} from the
 * presser's context. Only {@code {name}} with lowercase letters is a
 * placeholder, so JSON such as {@code {"a":1}} in a line is left alone. An unknown placeholder, or one that
 * can't be filled now, is a {@code placeholder} problem; it is never left in the line.
 */
public final class Placeholders {
    private Placeholders() {}

    /** The presser's position in game units. */
    public record Position(double x, double y, double z) {}

    /**
     * What a placeholder can be filled from. {@code target} null means the presser; {@code position} and
     * {@code look} null mean the game can't tell right now. {@code floorCoordinates} is the game's choice
     * (true on Hytale: whole blocks).
     */
    public record Context(String self, String target, Position position, String look, boolean floorCoordinates) {
        public static Context of(String self, Position position) {
            return new Context(self, null, position, null, true);
        }
    }

    public record Rendered(String line, Problem problem) {
        public boolean ok() {
            return problem == null;
        }
    }

    /**
     * Escapes a placeholder's value for the argument it lands in, by the game's rules. Given the line as
     * written and the index of the placeholder's opening brace, it returns the text to insert, or null when the
     * value can't be sent there with its meaning unchanged.
     */
    public interface Escaper {
        String escape(String line, int at, String value);
    }

    public static Rendered render(String line, Context ctx) {
        return render(line, ctx, null);
    }

    /** As {@link #render(String, Context)}, with each value passed through {@code escaper} (none if null). */
    public static Rendered render(String line, Context ctx, Escaper escaper) {
        StringBuilder out = new StringBuilder(line.length());
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            int end = c == '{' ? nameEnd(line, i + 1) : -1;
            if (end < 0) {
                out.append(c);
                i++;
                continue;
            }
            String name = line.substring(i + 1, end);
            String value = value(name, ctx);
            if (value == null) {
                boolean known = isKnown(name);
                String why = known ? "{" + name + "} can't be filled right now" : "{" + name + "} is not a placeholder";
                return new Rendered(null, Problem.of("placeholder", why, "name", name, "position", (long) i, "known", known));
            }
            if (escaper != null) {
                String escaped = escaper.escape(line, i, value);
                if (escaped == null) {
                    return new Rendered(null, Problem.of("unsendable", "The value of {" + name + "}, \"" + value
                        + "\", can't be sent there unchanged", "reason", "placeholder_escape", "name", name, "position", (long) i));
                }
                value = escaped;
            }
            out.append(value);
            i = end + 1;
        }
        return new Rendered(out.toString(), null);
    }

    /** Index of the closing brace if {@code line[from..]} is lowercase letters then '}', else -1. */
    private static int nameEnd(String line, int from) {
        int j = from;
        while (j < line.length() && line.charAt(j) >= 'a' && line.charAt(j) <= 'z') j++;
        return j > from && j < line.length() && line.charAt(j) == '}' ? j : -1;
    }

    private static boolean isKnown(String name) {
        switch (name) {
            case "self":
            case "target":
            case "x":
            case "y":
            case "z":
            case "look":
                return true;
            default:
                return false;
        }
    }

    private static String value(String name, Context ctx) {
        switch (name) {
            case "self":
                return ctx.self();
            case "target":
                return ctx.target() != null ? ctx.target() : ctx.self();
            case "x":
                return ctx.position() == null ? null : coord(ctx.position().x(), ctx.floorCoordinates());
            case "y":
                return ctx.position() == null ? null : coord(ctx.position().y(), ctx.floorCoordinates());
            case "z":
                return ctx.position() == null ? null : coord(ctx.position().z(), ctx.floorCoordinates());
            case "look":
                return ctx.look();
            default:
                return null;
        }
    }

    private static String coord(double v, boolean floor) {
        if (floor) return Long.toString((long) Math.floor(v));
        return commanddeck.json.Json.formatDouble(v);
    }
}
