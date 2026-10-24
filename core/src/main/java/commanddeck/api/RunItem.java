package commanddeck.api;

/** One thing to run: a command line, a saved macro's id, or a macro body node as JSON text. */
public record RunItem(Kind kind, String value) {
    public enum Kind {
        COMMAND_LINE,
        MACRO_REF,
        MACRO_JSON
    }

    public static RunItem line(String line) {
        return new RunItem(Kind.COMMAND_LINE, line);
    }

    public static RunItem ref(String macroId) {
        return new RunItem(Kind.MACRO_REF, macroId);
    }

    public static RunItem json(String bodyNode) {
        return new RunItem(Kind.MACRO_JSON, bodyNode);
    }
}
