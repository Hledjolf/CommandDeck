package commanddeck.command;

import java.util.List;

/** Another form of the same command, with its own arguments. {@code label} and {@code description} may be null. */
public record Variant(String label, String description, List<Arg> args) {
    /** The names of the positional arguments, in order: what identifies a variant when merging. */
    public List<String> positionalNames() {
        return args.stream().filter(a -> a.style() == ArgStyle.POSITIONAL).map(Arg::name).toList();
    }
}
