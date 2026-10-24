package commanddeck.command;

import java.util.List;

/**
 * One command as the browser shows it. {@code name} has no slash, and a subcommand is written with spaces
 * ({@code calendar set day}). {@code help} is the game's own help text, {@code description} the sidecar's,
 * shown in addition. {@code args} is the main form; {@code variants} are the other forms. {@code permission}
 * is for display only: the game decides who may run a command. Text fields are null when unknown; lists are
 * never null.
 */
public record CommandInfo(String name, List<String> aliases, Level level, String help, String label, String description,
                          String category, Danger danger, String permission, List<Arg> args, List<Variant> variants,
                          List<Example> examples, boolean hidden) {

    /** A command known only by name and help text. */
    public static CommandInfo raw(String name, String help) {
        return new CommandInfo(name, List.of(), Level.RAW, help, null, null, null, Danger.NONE, null, List.of(), List.of(),
            List.of(), false);
    }

    /** A command from a registry that reports arguments. */
    public static CommandInfo typed(String name, List<String> aliases, String help, String permission, List<Arg> args,
                                    List<Variant> variants) {
        return new CommandInfo(name, List.copyOf(aliases), Level.TYPED, help, null, null, null, Danger.NONE, permission,
            List.copyOf(args), List.copyOf(variants), List.of(), false);
    }
}
