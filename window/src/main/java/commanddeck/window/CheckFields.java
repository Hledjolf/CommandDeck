package commanddeck.window;

import commanddeck.json.Json;
import commanddeck.macro.ConditionPort.Field;
import commanddeck.macro.ConditionPort.FieldKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A check's fields as the editor shows them: the text in each box, and what typing in it makes of the check's JSON.
 * A game id is shown without the game's {@code <game>:} prefix and saved with it; text that already names another
 * namespace ({@code core:rain}) is kept as typed. Numbers are saved as numbers when they read as one, and as typed
 * otherwise, so the library's check says what's wrong with them.
 *
 * <p>Every change compares with what the box shows first, so a click that sends every box's value back changes
 * nothing it doesn't have to.
 */
final class CheckFields {
    /** The comparisons, as saved and as shown. */
    static final List<String> OPS = List.of("eq", "ne", "lt", "le", "gt", "ge");
    static final List<String> OP_LABELS = List.of("=", "≠", "<", "≤", ">", "≥");
    static final List<String> UNITS = List.of("points", "percent");
    /** What a {@link FieldKind#PLACE} holds. */
    static final List<String> PLACES = List.of("position", "placeholder", "marker");
    static final String MARKER = "marker:";

    private final String game;
    private final int coordinates;

    /** {@code game} is the prefix game ids take, or null for none; {@code coordinates} how many numbers a position has. */
    CheckFields(String game, int coordinates) {
        this.game = game;
        this.coordinates = coordinates;
    }

    int coordinates() {
        return coordinates;
    }

    // ---- what the boxes show ------------------------------------------------------------------------

    /** Which of its forms a field shows: an EITHER's field name, a PLACE's kind, or a selector's value. */
    String choice(Field f, MacroDraft.Step s) {
        Object v = s.fields.get(f.name());
        switch (f.kind()) {
            case EITHER:
                for (String o : f.options()) if (s.fields.containsKey(o)) return o;
                return s.choices.getOrDefault(f.name(), f.options().isEmpty() ? f.name() : f.options().get(0));
            case PLACE:
                if (v instanceof List) return "position";
                if (v instanceof String) return ((String) v).startsWith(MARKER) ? "marker" : "placeholder";
                return s.choices.getOrDefault(f.name(), "position");
            case UNIT:
                return v instanceof String ? (String) v : "points";
            case OP:
            case ENUM:
                return v instanceof String ? (String) v : "";
            default:
                return "";
        }
    }

    /** The text in a field's box. */
    String text(Field f, MacroDraft.Step s) {
        if (f.kind() == FieldKind.JSON && s.unparsed.containsKey(f.name())) return s.unparsed.get(f.name());
        Object v = s.fields.get(f.kind() == FieldKind.EITHER ? choice(f, s) : f.name());
        if (v == null) return "";
        switch (f.kind()) {
            case GAME_ID:
            case EITHER:
                return v instanceof String ? shown((String) v) : Json.write(v);
            case NUMBER:
            case WHOLE:
                return v instanceof String ? (String) v : WindowModel.str(v);
            case LIST:
            case GAME_ID_LIST: {
                if (!(v instanceof List)) return v instanceof String ? (String) v : Json.write(v);
                StringBuilder b = new StringBuilder();
                for (Object o : (List<?>) v) {
                    if (b.length() > 0) b.append(", ");
                    String t = o instanceof String ? (String) o : Json.write(o);
                    b.append(f.kind() == FieldKind.GAME_ID_LIST ? shown(t) : t);
                }
                return b.toString();
            }
            case PLACE: {
                if (!(v instanceof String)) return "";
                String t = (String) v;
                return t.startsWith(MARKER) ? t.substring(MARKER.length()) : t;
            }
            case JSON:
                return Json.write(v);
            default:
                return v instanceof String ? (String) v : Json.write(v);
        }
    }

    /** One coordinate of a position. */
    String coord(Field f, MacroDraft.Step s, int k) {
        Object v = s.fields.get(f.name());
        if (!(v instanceof List) || ((List<?>) v).size() <= k) return "";
        Object c = ((List<?>) v).get(k);
        return c instanceof String ? (String) c : WindowModel.str(c);
    }

    boolean flag(Field f, MacroDraft.Step s) {
        return Boolean.TRUE.equals(s.fields.get(f.name()));
    }

    // ---- what typing does ---------------------------------------------------------------------------

    /** The box's text typed; whether the check changed. */
    boolean setText(Field f, MacroDraft.Step s, String typed) {
        String text = typed == null ? "" : typed;
        if (text.equals(text(f, s))) return false;
        String t = text.trim();
        switch (f.kind()) {
            case EITHER: {
                String key = choice(f, s);
                for (String o : f.options()) s.fields.remove(o);
                s.choices.put(f.name(), key);
                if (!t.isEmpty()) s.fields.put(key, withGame(t));
                return true;
            }
            case GAME_ID:
                return put(s, f.name(), t.isEmpty() ? null : withGame(t));
            case NUMBER:
            case WHOLE:
                return put(s, f.name(), t.isEmpty() ? null : number(t));
            case LIST:
            case GAME_ID_LIST: {
                List<Object> items = new ArrayList<>();
                int from = 0;
                while (from <= t.length()) {
                    int comma = t.indexOf(',', from);
                    String item = t.substring(from, comma < 0 ? t.length() : comma).trim();
                    if (!item.isEmpty()) items.add(f.kind() == FieldKind.GAME_ID_LIST ? withGame(item) : item);
                    if (comma < 0) break;
                    from = comma + 1;
                }
                return put(s, f.name(), items.isEmpty() ? null : items);
            }
            case PLACE: {
                String kind = choice(f, s);
                if (kind.equals("position")) return false;
                return put(s, f.name(), t.isEmpty() ? null : kind.equals("marker") ? MARKER + t : t);
            }
            case JSON: {
                s.unparsed.remove(f.name());
                if (t.isEmpty()) {
                    s.fields.remove(f.name());
                    return true;
                }
                try {
                    s.fields.put(f.name(), Json.parse(t));
                } catch (RuntimeException e) {
                    s.fields.remove(f.name());
                    s.unparsed.put(f.name(), text);
                }
                return true;
            }
            default:
                return put(s, f.name(), t.isEmpty() ? null : t);
        }
    }

    /** A selector picked; whether the check changed. */
    boolean setChoice(Field f, MacroDraft.Step s, String value) {
        String v = value == null ? "" : value;
        if (v.equals(choice(f, s))) return false;
        switch (f.kind()) {
            case EITHER: {
                if (!f.options().contains(v)) return false;
                Object old = null;
                for (String o : f.options()) {
                    Object x = s.fields.remove(o);
                    if (x != null) old = x;
                }
                s.choices.put(f.name(), v);
                if (old != null) s.fields.put(v, old);
                return true;
            }
            case PLACE:
                if (!PLACES.contains(v)) return false;
                s.choices.put(f.name(), v);
                s.fields.remove(f.name());
                return true;
            case OP:
            case UNIT:
            case ENUM:
                return put(s, f.name(), v.isEmpty() ? null : v);
            default:
                return false;
        }
    }

    /** One coordinate typed; whether the check changed. A position with every coordinate blank is left out. */
    boolean setCoord(Field f, MacroDraft.Step s, int k, String typed) {
        String t = typed == null ? "" : typed.trim();
        if (k < 0 || k >= coordinates || t.equals(coord(f, s, k))) return false;
        List<Object> pos = new ArrayList<>();
        boolean any = false;
        for (int i = 0; i < coordinates; i++) {
            String c = i == k ? t : coord(f, s, i);
            pos.add(c.isEmpty() ? "" : number(c));
            any |= !c.isEmpty();
        }
        if (f.kind() == FieldKind.PLACE) s.choices.put(f.name(), "position");
        return put(s, f.name(), any ? pos : null);
    }

    boolean setFlag(Field f, MacroDraft.Step s, boolean value) {
        if (value == flag(f, s)) return false;
        return put(s, f.name(), value);
    }

    /** Fills a position with where the presser stands. */
    boolean here(Field f, MacroDraft.Step s, double[] at) {
        if (at == null || at.length < coordinates) return false;
        List<Object> pos = new ArrayList<>();
        for (int i = 0; i < coordinates; i++) {
            double d = Math.round(at[i] * 1000) / 1000.0;
            pos.add(d == Math.rint(d) && Math.abs(d) < 1e15 ? (Object) (long) d : (Object) d);
        }
        if (f.kind() == FieldKind.PLACE) s.choices.put(f.name(), "position");
        return put(s, f.name(), pos);
    }

    private static boolean put(MacroDraft.Step s, String name, Object v) {
        if (Objects.equals(v, s.fields.get(name))) return false;
        if (v == null) s.fields.remove(name);
        else s.fields.put(name, v);
        return true;
    }

    // ---- game ids and numbers -----------------------------------------------------------------------

    /** Whether text starts with a namespace: lower case letters, digits and {@code _}, a letter first, then {@code :}. */
    static boolean namespaced(String t) {
        int colon = t.indexOf(':');
        if (colon < 1) return false;
        char first = t.charAt(0);
        if (first < 'a' || first > 'z') return false;
        for (int i = 1; i < colon; i++) {
            char c = t.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_')) return false;
        }
        return true;
    }

    /** A game id as saved: with the game's prefix unless it names a namespace already. */
    String withGame(String t) {
        return game == null || namespaced(t) ? t : game + ":" + t;
    }

    /** A game id as shown: the game's own prefix hidden, unless what's left would read as another namespace. */
    String shown(String id) {
        if (game == null || !id.startsWith(game + ":")) return id;
        String rest = id.substring(game.length() + 1);
        return rest.isEmpty() || namespaced(rest) ? id : rest;
    }

    /** A whole number, a fraction, or the text itself when it reads as neither. */
    static Object number(String t) {
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            // not a whole number
        }
        try {
            double d = Double.parseDouble(t);
            if (!Double.isNaN(d) && !Double.isInfinite(d)) return d;
        } catch (NumberFormatException e) {
            // not a number either
        }
        return t;
    }
}
