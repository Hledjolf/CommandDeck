package commanddeck.command;

import commanddeck.macro.Problem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges what the game reports with what sidecars add. The game's registry decides which commands exist; a
 * sidecar only refines them. On a game that can't list its commands, the sidecars are the list.
 *
 * <ul>
 * <li>A sidecar entry for a command the game didn't register is ignored ({@code sidecar_unknown_command}).
 * <li>The game's help text is kept; the sidecar's description is shown in addition.
 * <li>Arguments are matched by name. On a raw command the sidecar's arguments are taken as they are. On a
 *     typed one, the game's type, style and required-ness win; the sidecar adds bounds, options, a default,
 *     a description and the placeholder switch. An argument the game doesn't know is ignored
 *     ({@code sidecar_unknown_arg}). A type that isn't a narrowing of the game's is ignored
 *     ({@code sidecar_type_conflict}).
 * <li>A sidecar variant refines the game's variant with the same positional argument names in order, where
 *     each argument's style is the game's whenever that variant knows the name. An unmatched one is ignored
 *     ({@code sidecar_unknown_variant}).
 * </ul>
 */
public final class CommandMerge {
    private CommandMerge() {}

    /** The merged commands, in the game's order (or the sidecars' order with no discovery), and the warnings. */
    public record Result(List<CommandInfo> commands, List<Problem> warnings) {}

    /** @param discovered the game's commands, or null when the game can't list them */
    public static Result merge(List<CommandInfo> discovered, List<Sidecar> sidecars) {
        List<Problem> warnings = new ArrayList<>();
        Map<String, CommandInfo> byName = new LinkedHashMap<>();
        if (discovered != null) {
            for (CommandInfo c : discovered) byName.put(c.name(), c);
        }
        for (Sidecar s : sidecars) {
            for (CommandInfo entry : s.commands()) {
                CommandInfo base = byName.get(entry.name());
                if (base == null && discovered != null) {
                    warnings.add(Problem.of("sidecar_unknown_command", "The sidecar of " + s.modId() + " describes /" + entry.name()
                        + ", which the game hasn't registered", "name", entry.name(), "mod", s.modId()));
                    continue;
                }
                byName.put(entry.name(), base == null ? entry : refine(base, entry, warnings));
            }
        }
        return new Result(List.copyOf(byName.values()), List.copyOf(warnings));
    }

    private static CommandInfo refine(CommandInfo base, CommandInfo side, List<Problem> warnings) {
        boolean typed = base.level() != Level.RAW;
        List<Arg> args;
        List<Variant> variants;
        if (typed) {
            args = refineArgs(base.name(), null, base.args(), side.args(), warnings);
            variants = refineVariants(base, side, warnings);
        } else {
            args = side.args().isEmpty() ? base.args() : side.args();
            variants = side.variants().isEmpty() ? base.variants() : side.variants();
        }
        List<String> aliases = new ArrayList<>(base.aliases());
        for (String a : side.aliases()) {
            if (!aliases.contains(a)) aliases.add(a);
        }
        return new CommandInfo(base.name(), List.copyOf(aliases), Level.DESCRIBED, base.help(),
            or(side.label(), base.label()), or(side.description(), base.description()), or(side.category(), base.category()),
            side.danger() != Danger.NONE ? side.danger() : base.danger(), or(base.permission(), side.permission()), args,
            variants, side.examples().isEmpty() ? base.examples() : side.examples(), side.hidden() || base.hidden());
    }

    private static List<Variant> refineVariants(CommandInfo base, CommandInfo side, List<Problem> warnings) {
        List<Variant> out = new ArrayList<>(base.variants());
        boolean[] taken = new boolean[out.size()];
        for (int v = 0; v < side.variants().size(); v++) {
            Variant sv = side.variants().get(v);
            int match = -1;
            for (int i = 0; i < out.size() && match < 0; i++) {
                if (!taken[i] && out.get(i).positionalNames().equals(positionalNamesAgainst(sv, out.get(i)))) match = i;
            }
            if (match < 0) {
                String label = sv.label() != null ? sv.label() : "variant " + v;
                warnings.add(Problem.of("sidecar_unknown_variant", "/" + base.name() + " has no form with the arguments of the sidecar's "
                    + label, "name", base.name(), "label", label));
                continue;
            }
            taken[match] = true;
            Variant dv = out.get(match);
            String label = sv.label() != null ? sv.label() : dv.label();
            out.set(match, new Variant(label, or(sv.description(), dv.description()),
                refineArgs(base.name(), label, dv.args(), sv.args(), warnings)));
        }
        return List.copyOf(out);
    }

    /** A sidecar variant's positional names, taking each argument's style from {@code dv} where it knows the name. */
    private static List<String> positionalNamesAgainst(Variant sv, Variant dv) {
        List<String> names = new ArrayList<>();
        for (Arg a : sv.args()) {
            Arg known = find(dv.args(), a.name());
            ArgStyle style = known != null ? known.style() : a.style();
            if (style == ArgStyle.POSITIONAL) names.add(a.name());
        }
        return names;
    }

    private static List<Arg> refineArgs(String command, String variant, List<Arg> base, List<Arg> side, List<Problem> warnings) {
        List<Arg> out = new ArrayList<>(base);
        for (Arg s : side) {
            int i = indexOf(out, s.name());
            if (i < 0) {
                Problem w = Problem.of("sidecar_unknown_arg", "/" + command + " has no argument " + s.name(), "name", command, "arg", s.name());
                if (variant != null) w.detail().put("variant", variant);
                warnings.add(w);
                continue;
            }
            Arg b = out.get(i);
            ArgType type = b.type();
            if (s.type() != b.type()) {
                if (s.type().narrows(b.type())) {
                    type = s.type();
                } else {
                    warnings.add(Problem.of("sidecar_type_conflict", "The sidecar makes " + s.name() + " of /" + command + " a "
                        + s.type().id() + ", but the game reads a " + b.type().id(), "name", command, "arg", s.name(),
                        "type", b.type().id(), "sidecar_type", s.type().id()));
                }
            }
            out.set(i, new Arg(b.name(), type, b.style(), b.required(), or(s.defaultValue(), b.defaultValue()),
                or(s.min(), b.min()), or(s.max(), b.max()), or(s.step(), b.step()), or(s.options(), b.options()),
                or(s.description(), b.description()), s.placeholders() && b.placeholders()));
        }
        return List.copyOf(out);
    }

    private static Arg find(List<Arg> args, String name) {
        int i = indexOf(args, name);
        return i < 0 ? null : args.get(i);
    }

    private static int indexOf(List<Arg> args, String name) {
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).name().equals(name)) return i;
        }
        return -1;
    }

    private static <T> T or(T first, T second) {
        return first != null ? first : second;
    }
}
