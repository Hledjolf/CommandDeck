package commanddeck.macro;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * How the core reaches the game's condition library for {@code while} and {@code until}: checking a condition when
 * a macro is saved or run, and answering it when a loop checks. The adapter supplies it; without one, a loop is
 * refused, since nothing could answer it.
 */
public interface ConditionPort {
    /** TRUE, FALSE, or UNKNOWN (couldn't be answered). */
    enum Answer { TRUE, FALSE, UNKNOWN }

    /** A problem with a condition: {@code pointer} within the condition ({@code ""} is the condition itself). */
    record Issue(String pointer, String code, String message) {}

    /** An answer, and for UNKNOWN the library's reason. */
    record Result(Answer answer, String reason) {}

    /** Problems that stop the condition being used here; empty when it's valid and this game answers every type in it. */
    List<Issue> check(Map<String, Object> condition);

    /** Answers the condition for a run: {@code actor} is the presser's id, {@code target} the run target's or null. */
    Result evaluate(Map<String, Object> condition, String actor, String target, long tick);

    /** The game's name as condition game ids write it ({@code hytale} in {@code hytale:Rock_Stone}), or null. */
    default String game() {
        return null;
    }

    /**
     * The condition types the macro editor offers, each with its form: the shared types this game answers first,
     * then the types mods registered, by owner. {@code all}, {@code any}, {@code not} and {@code counter} aren't
     * listed. Empty when there's nothing to offer.
     */
    default List<Form> forms() {
        return List.of();
    }

    /** How the editor shows a field. */
    enum FieldKind {
        /** Who the condition is about: a name or a placeholder, {@code {self}} when left out. */
        SUBJECT,
        /** Plain text, kept as typed. */
        TEXT,
        /** A game id, shown without the {@code <game>:} prefix and saved with it. */
        GAME_ID,
        /**
         * One of two fields, chosen with a selector ({@link Field#options()} names both, {@code item} and
         * {@code tag} say); the value is a game id, as {@link #GAME_ID}.
         */
        EITHER,
        /** A comparison: {@code eq}, {@code ne}, {@code lt}, {@code le}, {@code gt}, {@code ge}. */
        OP,
        /** A number, fractions allowed. */
        NUMBER,
        /** A whole number. */
        WHOLE,
        /** {@code points} or {@code percent}. */
        UNIT,
        /** A list of strings, written comma separated and kept as typed. */
        LIST,
        /** A list of game ids, written comma separated. */
        GAME_ID_LIST,
        /** A position: one number per coordinate. */
        POSITION,
        /** A position, a placeholder, or {@code marker:<name>}. */
        PLACE,
        BOOLEAN,
        /** One of {@link Field#options()}. */
        ENUM,
        /** Anything else, written as JSON. */
        JSON
    }

    /**
     * One field of a form: its JSON name, a title and a hint for the editor, how it's shown, whether the type
     * needs it, and the choices where the kind has them.
     */
    record Field(String name, String title, String description, FieldKind kind, boolean required, List<String> options) {
        public Field {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    /** A condition type as the editor offers it: a plain-words label, who defined it (null for the shared types), its fields. */
    record Form(String type, String label, String owner, List<Field> fields) {
        public Form {
            fields = List.copyOf(fields);
        }
    }

    /**
     * Where a condition uses {@code counter}, which macros refuse until a node can change counters (it would always
     * read 0), or null.
     */
    @SuppressWarnings("unchecked")
    static String counterAt(Object condition, String at) {
        if (!(condition instanceof Map)) return null;
        Map<String, Object> m = (Map<String, Object>) condition;
        if ("counter".equals(m.get("type"))) return at;
        Object of = m.get("of");
        if (of instanceof Map) return counterAt(of, at + "/of");
        if (of instanceof List) {
            List<Object> list = (List<Object>) of;
            for (int i = 0; i < list.size(); i++) {
                String p = counterAt(list.get(i), at + "/of/" + i);
                if (p != null) return p;
            }
        }
        return null;
    }

    /** Every problem with a loop's condition, {@code counter} first. Pointers here start at the condition. */
    static List<Issue> issues(ConditionPort port, Map<String, Object> condition) {
        List<Issue> out = new ArrayList<>();
        String counter = counterAt(condition, "");
        if (counter != null) {
            out.add(new Issue(counter, "invalid", "counter can't be used in a macro yet: no step can change a counter, so it would always be 0"));
            return out;
        }
        if (port == null) {
            out.add(new Issue("", "unsupported_here", "conditions can't be checked on this game"));
            return out;
        }
        out.addAll(port.check(condition));
        return out;
    }
}
