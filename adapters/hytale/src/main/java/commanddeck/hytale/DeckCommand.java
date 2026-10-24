package commanddeck.hytale;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.ParseResult;
import com.hypixel.hytale.server.core.command.system.ParserContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.console.ConsoleSender;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import commanddeck.api.CheckResult;
import commanddeck.api.Presser;
import commanddeck.api.RunItem;
import commanddeck.api.RunListener;
import commanddeck.api.RunRequest;
import commanddeck.api.RunResult;
import commanddeck.api.RunStatus;
import commanddeck.api.StartResult;
import commanddeck.api.StepEvent;
import commanddeck.api.StopPoint;
import commanddeck.command.Preset;
import commanddeck.macro.Macro;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /deck}: run a saved macro or preset by id, confirm one that asks first, stop your runs (operators:
 * everyone's), list what you can run, and reload. Works from the console too. The game still checks every
 * command a macro sends, as the player who ran it.
 */
final class DeckCommand extends AbstractAsyncCommand {
    static final String NAME = "deck";
    private static final long CONFIRM_MILLIS = 60_000;

    private final CommandDeckPlugin plugin;
    private final Map<String, Waiting> waiting = new ConcurrentHashMap<>();

    /** A run that needs the presser's confirmation: the request and the token from check. */
    private record Waiting(RunRequest request, String token, long until) {}

    /** The line as typed, for the call in progress: the context only has the tokens with escapes removed. */
    private static final ThreadLocal<String> RAW_INPUT = new ThreadLocal<>();

    DeckCommand(CommandDeckPlugin plugin) {
        super(NAME, "CommandDeck: run, stop and list macros. /deck help for more");
        this.plugin = plugin;
        setAllowsExtraArguments(true);
        requireNoPermission();
    }

    @Override
    public CompletableFuture<Void> acceptCall(CommandSender sender, ParserContext parser, ParseResult result) {
        RAW_INPUT.set(parser.getRawInput());
        try {
            return super.acceptCall(sender, parser, result);
        } finally {
            RAW_INPUT.remove();
        }
    }

    @Override
    protected CompletableFuture<Void> executeAsync(CommandContext context) {
        String raw = RAW_INPUT.get();
        List<String> args = words(raw != null ? raw : context.getInputString());
        Presser presser = context.isPlayer() ? new Presser(context.sender().getUuid().toString(), context.sender().getUsername())
            : HytaleGame.console();
        List<String> out = new ArrayList<>();
        try {
            handle(presser, args, out);
        } catch (RuntimeException e) {
            plugin.getLogger().atSevere().withCause(e).log("[CommandDeck] /deck failed");
            out.add("/deck failed: " + e.getMessage());
        }
        for (String l : out) context.sendMessage(Message.raw(l));
        return CompletableFuture.completedFuture(null);
    }

    private void handle(Presser presser, List<String> args, List<String> out) {
        String sub = args.isEmpty() ? "" : args.get(0).toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "run":
                if (args.size() != 2) {
                    out.add("Usage: /deck run <macro id>");
                } else {
                    run(presser, new RunRequest(List.of(RunItem.ref(args.get(1))), "deck"), null, out);
                }
                return;
            case "confirm": {
                Waiting w = waiting.remove(presser.id());
                if (w == null || w.until() < System.currentTimeMillis()) {
                    out.add("Nothing is waiting for your confirmation.");
                } else {
                    run(presser, w.request(), w.token(), out);
                }
                return;
            }
            case "stop":
                if (args.size() == 2 && args.get(1).equalsIgnoreCase("all")) {
                    int n = plugin.deck().stopAll(presser);
                    out.add(n < 0 ? "Only operators can stop everyone's runs." : "Asked " + n + " run" + (n == 1 ? "" : "s") + " to stop.");
                } else if (args.size() == 1) {
                    int n = 0;
                    for (RunStatus s : plugin.deck().running(presser)) {
                        if (plugin.deck().stop(s.runId(), presser)) n++;
                    }
                    out.add(n == 0 ? "You have no runs going." : "Asked " + n + " run" + (n == 1 ? "" : "s") + " to stop.");
                } else {
                    out.add("Usage: /deck stop, or /deck stop all");
                }
                return;
            case "list":
                list(presser, out);
                return;
            case "reload":
                if (!plugin.game().isOperator(presser)) {
                    out.add("Only operators can reload CommandDeck.");
                } else {
                    out.add(plugin.reload());
                }
                return;
            case "":
                openWindow(presser, out);
                return;
            default:
                usage(out);
        }
    }

    private void openWindow(Presser presser, List<String> out) {
        PlayerRef p = HytaleGame.player(presser.id());
        if (p == null) {
            out.add("The window needs a player. From the console:");
            usage(out);
            return;
        }
        if (!plugin.game().mayUse(presser)) {
            out.add("You may not use CommandDeck on this server.");
            return;
        }
        if (!DeckPage.open(p, plugin.windowFor(presser), plugin.getLogger())) out.add("Can't open the window: you aren't in a world.");
    }

    private static void usage(List<String> out) {
        out.add("/deck: open the window");
        out.add("/deck run <id>: run one of your macros, a server macro or a preset");
        out.add("/deck confirm: run what's waiting for your confirmation (within 60 seconds)");
        out.add("/deck stop: stop your runs. /deck stop all: everyone's (operators)");
        out.add("/deck list: what you can run, and your runs");
        out.add("/deck reload: read the sidecars, macros and settings again (operators)");
    }

    private void run(Presser presser, RunRequest request, String token, List<String> out) {
        if (token == null) {
            CheckResult c = plugin.deck().check(presser, request);
            if (!c.ok()) {
                out.add("Can't run that: " + c.refusal().message());
                return;
            }
            if (c.needsConfirmation()) {
                waiting.put(presser.id(), new Waiting(request, c.confirmToken(), System.currentTimeMillis() + CONFIRM_MILLIS));
                out.add(c.confirmPrompt() + " Type /deck confirm within 60 seconds to run it.");
                return;
            }
        }
        StartResult s = plugin.deck().start(presser, request, new Reporter(presser), token);
        if (!s.started()) {
            out.add("Can't run that: " + s.refusal().message());
        } else {
            out.add("Started run " + s.runId() + ".");
        }
    }

    private void list(Presser presser, List<String> out) {
        List<String> mine = new ArrayList<>();
        for (Macro m : plugin.macros().player(presser.id())) mine.add(m.id() + " (" + m.name() + ")");
        List<String> server = new ArrayList<>();
        for (Macro m : plugin.macros().server()) server.add(m.id() + " (" + m.name() + ")");
        List<String> presets = new ArrayList<>();
        if (plugin.catalog() != null) {
            for (Map.Entry<String, Preset> e : plugin.catalog().presets().entrySet()) {
                presets.add(e.getValue().id() + " (" + e.getValue().name() + ", from " + e.getKey().substring(0, e.getKey().lastIndexOf('/')) + ")");
            }
        }
        out.add("Your macros: " + (mine.isEmpty() ? "none" : String.join(", ", mine)));
        out.add("Server macros: " + (server.isEmpty() ? "none" : String.join(", ", server)));
        out.add("Presets: " + (presets.isEmpty() ? "none" : String.join(", ", presets)));
        List<RunStatus> runs = plugin.deck().running(presser);
        if (runs.isEmpty()) {
            out.add("You have no runs going.");
        } else {
            for (RunStatus r : runs) {
                out.add(RunText.line(r));
            }
        }
    }

    private static List<String> words(String raw) {
        List<String> out = new ArrayList<>();
        for (String w : raw.trim().split("[ \t]+")) {
            if (!w.isEmpty()) out.add(w);
        }
        if (!out.isEmpty() && out.get(0).replace("/", "").equalsIgnoreCase(NAME)) out.remove(0);
        return out;
    }

    /** Tells the presser how a {@code /deck run} ended. Runs on the tick thread, so it only sends a message. */
    static final class Reporter implements RunListener {
        private final Presser presser;

        Reporter(Presser presser) {
            this.presser = presser;
        }

        @Override
        public void onStep(StepEvent step) {}

        @Override
        public void onFinish(RunResult r) {
            StringBuilder b = new StringBuilder("Run ").append(r.runId()).append(' ').append(r.end().name().toLowerCase())
                .append(" after ").append(r.stepsCompleted()).append(" step").append(r.stepsCompleted() == 1 ? "" : "s");
            StopPoint p = r.stoppedAt();
            if (p != null) {
                b.append("; stopped at ").append(p.path());
                if (p.iterations() > 0) b.append(", iteration ").append(p.iteration()).append(" of ").append(p.iterations());
                if (p.nextLine() != null) b.append("; next would have been ").append(p.nextLine());
            }
            if (r.message() != null) b.append(". ").append(r.message());
            tell(presser, b.toString());
        }
    }

    static void tell(Presser presser, String text) {
        if (HytaleGame.isConsole(presser.id())) {
            ConsoleSender.INSTANCE.sendMessage(Message.raw(text));
            return;
        }
        PlayerRef p = HytaleGame.player(presser.id());
        if (p != null) p.sendMessage(Message.raw(text));
    }
}
