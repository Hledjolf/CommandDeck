package commanddeck.command;

import java.util.List;

/**
 * One argument of a command form. {@code defaultValue}, {@code min}, {@code max}, {@code step},
 * {@code options} and {@code description} are null when not given; {@code placeholders} says whether
 * {@code {self}}, {@code {x}} and the like may be used in the value.
 */
public record Arg(String name, ArgType type, ArgStyle style, boolean required, Object defaultValue, Double min, Double max,
                  Double step, List<String> options, String description, boolean placeholders) {

    /** An argument as a registry reports it: name, type, style and whether it's required. */
    public static Arg of(String name, ArgType type, ArgStyle style, boolean required) {
        return new Arg(name, type, style, required, null, null, null, null, null, null, true);
    }
}
