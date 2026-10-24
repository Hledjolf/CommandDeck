package commanddeck.hytale;

import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.command.system.CommandOwner;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.arguments.system.AbstractOptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.Argument;
import com.hypixel.hytale.server.core.command.system.arguments.system.DefaultArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.FlagArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgumentType;
import com.hypixel.hytale.server.core.command.system.arguments.types.EnumArgumentType;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import commanddeck.command.Arg;
import commanddeck.command.ArgStyle;
import commanddeck.command.ArgType;
import commanddeck.command.CommandInfo;
import commanddeck.command.CommandSource;
import commanddeck.command.Danger;
import commanddeck.command.Level;
import commanddeck.command.Variant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The game's command registry as CommandDeck commands. Every command and subcommand is typed: required
 * arguments are positional, optional and default arguments are named ({@code --name value}), and flags are
 * {@code --name} alone. Usage variants become the command's other forms. Descriptions are translation keys
 * resolved in the server's default language, or plain text. A command's category is the mod that registered
 * it.
 */
final class HytaleCommands implements CommandSource {
    /** The game's command behind each discovered name, from the last discovery. */
    private volatile Map<String, AbstractCommand> found = Map.of();

    @Override
    public List<CommandInfo> discover() {
        Map<String, CommandInfo> out = new LinkedHashMap<>();
        Map<String, AbstractCommand> commands = new LinkedHashMap<>();
        for (AbstractCommand c : CommandManager.get().getCommandRegistration().values()) add(out, commands, c, c.getName(), owner(c));
        found = Map.copyOf(commands);
        return new ArrayList<>(out.values());
    }

    /** The game's command for a discovered name (with spaces for a subcommand), or null. */
    AbstractCommand find(String name) {
        return found.get(name);
    }

    /**
     * How a form's argument is read by the game: how many words it takes and whether it's a list. Null when the
     * command, form or argument is unknown.
     */
    Shape shape(String name, int form, String arg) {
        AbstractCommand c = find(name);
        if (c != null && form >= 0) {
            List<AbstractCommand> variants = new ArrayList<>(c.getVariantCommands());
            c = form < variants.size() ? variants.get(form) : null;
        }
        if (c == null) return null;
        for (RequiredArg<?> a : c.getRequiredArguments()) {
            if (a.getName().equals(arg)) return shape(a.getArgumentType());
        }
        AbstractOptionalArg<?, ?> o = c.getOptionalArguments().get(arg);
        return o == null || o instanceof FlagArg ? null : shape(o.getArgumentType());
    }

    private static Shape shape(ArgumentType<?> t) {
        if (t == null) return null;
        return new Shape(Math.max(1, t.getNumberOfParameters()), t.isListArgument());
    }

    /** The words an argument takes, and whether it is a list. */
    record Shape(int tokens, boolean list) {}

    /**
     * Whether the game would let this sender run the command, by the game's own check (its permission and its
     * parents'). Null when the command is unknown. A hint for the window only: the game decides at run time.
     */
    static Boolean mayRun(AbstractCommand c, CommandSender sender) {
        if (c == null || sender == null) return null;
        try {
            return c.hasPermission(sender);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void add(Map<String, CommandInfo> out, Map<String, AbstractCommand> commands, AbstractCommand c, String name,
                            String owner) {
        if (out.containsKey(name)) return;
        commands.put(name, c);
        List<Variant> variants = new ArrayList<>();
        for (AbstractCommand v : c.getVariantCommands()) variants.add(new Variant(null, null, args(v)));
        String help = text(c.getDescription());
        out.put(name, new CommandInfo(name, List.copyOf(c.getAliases()), Level.TYPED, help, null, null, owner, Danger.NONE,
            c.getPermission(), args(c), List.copyOf(variants), List.of(), false));
        for (Map.Entry<String, AbstractCommand> s : c.getSubCommands().entrySet()) {
            add(out, commands, s.getValue(), name + " " + s.getValue().getName(), owner);
        }
    }

    private static List<Arg> args(AbstractCommand c) {
        List<Arg> out = new ArrayList<>();
        for (RequiredArg<?> a : c.getRequiredArguments()) out.add(arg(a, ArgStyle.POSITIONAL, true));
        for (AbstractOptionalArg<?, ?> a : c.getOptionalArguments().values()) {
            out.add(arg(a, a instanceof FlagArg ? ArgStyle.FLAG : ArgStyle.NAMED, false));
        }
        return List.copyOf(out);
    }

    private static Arg arg(Argument<?, ?> a, ArgStyle style, boolean required) {
        ArgumentType<?> t = a.getArgumentType();
        ArgType type = style == ArgStyle.FLAG ? ArgType.BOOL : type(t);
        List<String> options = null;
        if (type == ArgType.ENUM && t.getExamples() != null && t.getExamples().length > 0) options = List.of(t.getExamples());
        if (type == ArgType.ENUM && options == null) type = ArgType.STRING;
        String description = text(a.getDescription());
        if (t != null && t.isListArgument()) {
            // List arguments aren't measured yet: shown as raw text, typed with their own brackets.
            description = (description == null ? "" : description + " ") + "(a list, written as [a, b])";
        } else if (type == ArgType.STRING && t != null && t != ArgTypes.STRING && t.getArgumentUsage() != null) {
            // An unmapped type: its own usage text says what to type.
            String usage = plain(t.getArgumentUsage().getAnsiMessage());
            if (usage != null && !usage.isBlank()) description = (description == null ? "" : description + " ") + "(" + usage + ")";
        }
        Object defaultValue = null;
        if (a instanceof DefaultArg<?> d) {
            Object v = d.getDefaultValue();
            if (v instanceof String || v instanceof Number || v instanceof Boolean) defaultValue = v;
        }
        return new Arg(a.getName(), type, style, required, defaultValue, null, null, null, options, description, true);
    }

    /** The profile's mapping from the game's argument types; anything unmapped is a text field. */
    static ArgType type(ArgumentType<?> t) {
        if (t == null || t.isListArgument()) return ArgType.STRING;
        if (t == ArgTypes.STRING) return ArgType.STRING;
        if (t == ArgTypes.GREEDY_STRING || t.isGreedyString()) return ArgType.GREEDY_STRING;
        if (t == ArgTypes.INTEGER) return ArgType.INT;
        if (t == ArgTypes.FLOAT || t == ArgTypes.DOUBLE) return ArgType.FLOAT;
        if (t == ArgTypes.BOOLEAN) return ArgType.BOOL;
        if (t instanceof EnumArgumentType<?>) return ArgType.ENUM;
        if (t == ArgTypes.PLAYER_REF || t == ArgTypes.CONNECTED_PROFILE_LOOKUP) return ArgType.PLAYER;
        if (t == ArgTypes.ITEM_ASSET) return ArgType.ITEM;
        if (t == ArgTypes.BLOCK_TYPE_KEY || t == ArgTypes.BLOCK_TYPE_ASSET) return ArgType.BLOCK;
        if (t == ArgTypes.RELATIVE_POSITION || t == ArgTypes.RELATIVE_BLOCK_POSITION || t == ArgTypes.RELATIVE_CHUNK_POSITION
            || t == ArgTypes.RELATIVE_VECTOR3I) {
            return ArgType.POSITION;
        }
        if (t == ArgTypes.RELATIVE_DOUBLE_COORD || t == ArgTypes.RELATIVE_INT_COORD) return ArgType.FLOAT;
        return ArgType.STRING;
    }

    /** Which mod registered it: {@code Group:Name}, or {@code HytaleServer} for the game's own commands. */
    private static String owner(AbstractCommand c) {
        CommandOwner o = c.getOwner();
        String name = o == null ? null : o.getName();
        return name == null ? null : name.length() > 40 ? name.substring(0, 40) : name;
    }

    /** A translation key in the server's default language, or the text itself when it isn't a key. */
    static String text(String keyOrText) {
        if (keyOrText == null || keyOrText.isEmpty()) return null;
        try {
            I18nModule m = I18nModule.get();
            String v = m == null ? null : m.getMessage(I18nModule.DEFAULT_LANGUAGE, keyOrText);
            if (v != null) return v;
        } catch (RuntimeException ignored) {
            // Not a key the module knows: the text is used as it is.
        }
        return keyOrText;
    }

    static String plain(String ansi) {
        return ansi == null ? null : ansi.replaceAll("\u001b\\[[0-9;]*m", "");
    }
}
