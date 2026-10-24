package commanddeck.hytale;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.ServerMessage;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.ParseResult;
import com.hypixel.hytale.server.core.command.system.ParserContext;
import com.hypixel.hytale.server.core.command.system.Tokenizer;
import com.hypixel.hytale.server.core.command.system.exceptions.CommandException;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import commanddeck.api.CommandOutcome;
import commanddeck.api.OutcomeBasis;
import commanddeck.run.CommandSink;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ForkJoinPool;

/**
 * Runs command lines as one presser. The line goes through the same steps the server's own command handler
 * takes (tokenizer, parser context, the command's own permission and argument checks), on the same thread
 * pool, but CommandDeck reads the parse result itself, so a refusal is a signal from the game rather than a
 * guess from its messages:
 *
 * <ul>
 * <li>an unknown command, a tokenizer or parse failure, a missing permission, or a {@link CommandException}
 *     (the command's own "bad input") is {@code REFUSED};
 * <li>any other exception is {@code FAILED};
 * <li>a command that completes is {@code SENT}.
 * </ul>
 *
 * The response is every chat message the presser receives while the command runs, up to the tick after it
 * finishes: a player's through an outbound packet watcher, so the command still runs as the player itself;
 * the console's through a sender that records and forwards. A command that hasn't finished after the timeout
 * is {@code SENT}, uncertain.
 *
 * <p>In plain mode the line goes to the server's handler unchanged, and only an exception is a signal; the
 * response is empty and every other step is uncertain.
 */
final class HytaleSink implements CommandSink {
    /** Messages to each player while a command of theirs runs. */
    private static final Map<UUID, List<Capture>> CAPTURES = new ConcurrentHashMap<>();
    /** How long after its timeout a capture nobody collected (its run was stopped) is dropped. */
    static final long ABANDONED_MILLIS = 5_000;
    /** The most lines one command's response keeps. */
    static final int MAX_LINES = 200;
    private static volatile PacketFilter watcher;

    private final CommandSender sender;
    private final UUID player;
    private final boolean plain;
    private final long timeoutMillis;
    private final HytaleLogger log;

    HytaleSink(CommandSender sender, UUID player, boolean plain, long timeoutMillis, HytaleLogger log) {
        this.sender = sender;
        this.player = player;
        this.plain = plain;
        this.timeoutMillis = timeoutMillis;
        this.log = log;
    }

    /** Starts watching the messages sent to players. Once per server. */
    static synchronized void watchMessages() {
        if (watcher != null) return;
        // Runs on the network thread for every packet to every player: it must be cheap and must never throw.
        watcher = PacketAdapters.registerOutbound((PlayerRef ref, com.hypixel.hytale.protocol.Packet packet) -> {
            try {
                if (!(packet instanceof ServerMessage m) || m.message == null) return;
                List<Capture> open = CAPTURES.get(ref.getUuid());
                if (open == null || open.isEmpty()) return;
                record(open, plainText(new Message(m.message)), System.currentTimeMillis());
            } catch (Throwable ignored) {
                // A message CommandDeck can't read is left out of the response; the packet is sent as usual.
            }
        });
    }

    /** Adds a message to every open capture, dropping those nobody will collect any more. */
    static void record(List<Capture> open, String text, long now) {
        open.removeIf(c -> now > c.expiresAt);
        for (Capture c : open) c.add(text);
    }

    static synchronized void stopWatching() {
        if (watcher != null) PacketAdapters.deregisterOutbound(watcher);
        watcher = null;
        CAPTURES.clear();
    }

    static String plainText(Message m) {
        String s = m.getAnsiMessage();
        return s == null ? "" : s.replaceAll("\u001b\\[[0-9;]*m", "");
    }

    /** The messages a running command produced, up to {@link #MAX_LINES}. */
    static final class Capture {
        final List<String> lines = new CopyOnWriteArrayList<>();
        final long expiresAt;

        Capture(long expiresAt) {
            this.expiresAt = expiresAt;
        }

        void add(String line) {
            if (lines.size() < MAX_LINES) lines.add(line);
        }
    }

    /** Open captures for a player, for tests. */
    static int openCaptures(UUID player) {
        List<Capture> open = CAPTURES.get(player);
        return open == null ? 0 : open.size();
    }

    @Override
    public Pending send(String line) {
        String text = line.startsWith("/") ? line.substring(1) : line;
        Step step = new Step(System.currentTimeMillis() + timeoutMillis);
        try {
            start(text, step);
        } catch (Throwable t) {
            // Never into the deck's tick: a send that throws would stop every run on the server.
            log.atSevere().withCause(t).log("[CommandDeck] Couldn't send %s for %s", text, sender.getUsername());
            step.finish(CommandOutcome.FAILED, OutcomeBasis.GAME_SIGNAL, true);
        }
        return step;
    }

    private void start(String text, Step step) {
        if (player != null && !plain) {
            List<Capture> open = CAPTURES.computeIfAbsent(player, k -> new CopyOnWriteArrayList<>());
            long now = System.currentTimeMillis();
            open.removeIf(c -> now > c.expiresAt);
            open.add(step.capture);
        }
        CommandSender as = sender;
        if (player == null && !plain) as = new RecordingSender(sender, step.capture);
        CommandSender runAs = as;
        log.atInfo().log("[CommandDeck] %s executed command: %s", sender.getUsername(), text);
        if (plain) {
            CommandManager.get().handleCommand(runAs, text).whenComplete((v, err) -> step.finish(
                err == null ? CommandOutcome.SENT : CommandOutcome.FAILED, err == null ? OutcomeBasis.MESSAGE_HEURISTIC : OutcomeBasis.GAME_SIGNAL,
                err != null));
        } else {
            ForkJoinPool.commonPool().execute(() -> run(runAs, text, step));
        }
    }

    private void run(CommandSender as, String text, Step step) {
        try {
            int space = text.indexOf(' ');
            String name = (space < 0 ? text : text.substring(0, space)).toLowerCase(Locale.ROOT);
            AbstractCommand command = CommandManager.get().resolveCommand(name);
            if (command == null) {
                as.sendMessage(Message.translation("server.modules.command.notFound").param("cmd", name));
                step.finish(CommandOutcome.REFUSED, OutcomeBasis.GAME_SIGNAL, true);
                return;
            }
            ParseResult result = new ParseResult();
            List<String> tokens = Tokenizer.parseArguments(text, result);
            if (refused(result, as, step)) return;
            ParserContext context = ParserContext.of(tokens, text, result);
            if (refused(result, as, step)) return;
            CompletableFuture<Void> f = command.acceptCall(as, context, result);
            if (refused(result, as, step)) return;
            if (f == null) {
                step.finish(CommandOutcome.SENT, OutcomeBasis.GAME_SIGNAL, true);
            } else {
                f.whenComplete((v, err) -> {
                    if (err == null) {
                        step.finish(CommandOutcome.SENT, OutcomeBasis.GAME_SIGNAL, true);
                    } else {
                        threw(as, text, err, step);
                    }
                });
            }
        } catch (Throwable t) {
            threw(as, text, t, step);
        }
    }

    private static boolean refused(ParseResult result, CommandSender as, Step step) {
        if (!result.failed()) return false;
        result.sendMessages(as);
        step.finish(CommandOutcome.REFUSED, OutcomeBasis.GAME_SIGNAL, true);
        return true;
    }

    /** As the server's own handler does: the command's message for its own exceptions, a logged error for others. */
    private void threw(CommandSender as, String text, Throwable t, Step step) {
        Throwable cause = t instanceof java.util.concurrent.CompletionException && t.getCause() != null ? t.getCause() : t;
        if (cause instanceof CommandException ce) {
            ce.sendTranslatedMessage(as);
            step.finish(CommandOutcome.REFUSED, OutcomeBasis.GAME_SIGNAL, true);
            return;
        }
        log.atSevere().withCause(cause).log("[CommandDeck] Failed to execute command %s for %s", text, as.getUsername());
        Message why = cause.getMessage() == null ? Message.translation("server.modules.command.noProvidedExceptionMessage")
            : Message.raw(cause.getMessage());
        as.sendMessage(Message.translation("server.modules.command.error").param("cmd", text).param("msg", why));
        step.finish(CommandOutcome.FAILED, OutcomeBasis.GAME_SIGNAL, true);
    }

    /** One sent line. Done on the first poll after the command finished, so late messages still count. */
    private final class Step implements Pending {
        final Capture capture;
        private final long deadline;
        private volatile CommandOutcome outcome;
        private volatile OutcomeBasis basis;
        private volatile boolean decided;
        private volatile boolean closed;
        private List<String> response = List.of();

        Step(long deadline) {
            this.deadline = deadline;
            this.capture = new Capture(deadline + ABANDONED_MILLIS);
        }

        /** The first outcome wins, whether it comes from the command's thread or the timeout. */
        synchronized void finish(CommandOutcome o, OutcomeBasis b, boolean d) {
            if (outcome != null) return;
            basis = b;
            decided = d;
            outcome = o;
        }

        @Override
        public synchronized boolean done() {
            if (closed) return true;
            if (outcome == null) {
                if (System.currentTimeMillis() < deadline) return false;
                finish(CommandOutcome.SENT, OutcomeBasis.MESSAGE_HEURISTIC, false);
            }
            closed = true;
            if (player != null) {
                List<Capture> open = CAPTURES.get(player);
                if (open != null) open.remove(capture);
            }
            response = List.copyOf(new ArrayList<>(capture.lines));
            return true;
        }

        @Override
        public CommandOutcome outcome() {
            return outcome;
        }

        @Override
        public OutcomeBasis basis() {
            return basis;
        }

        @Override
        public boolean decided() {
            return decided;
        }

        @Override
        public synchronized List<String> response() {
            return response;
        }
    }

    /** The console as a sender that also records what it's told. Never used for players: it wouldn't be one. */
    static final class RecordingSender implements CommandSender {
        private final CommandSender inner;
        private final Capture capture;

        RecordingSender(CommandSender inner, Capture capture) {
            this.inner = inner;
            this.capture = capture;
        }

        @Override
        public void sendMessage(Message m) {
            capture.lines.add(plainText(m));
            inner.sendMessage(m);
        }

        @Override
        public String getUsername() {
            return inner.getUsername();
        }

        @Override
        public UUID getUuid() {
            return inner.getUuid();
        }

        @Override
        public boolean hasPermission(String node) {
            return inner.hasPermission(node);
        }

        @Override
        public boolean hasPermission(String node, boolean def) {
            return inner.hasPermission(node, def);
        }
    }
}
