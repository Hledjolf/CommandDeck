package commanddeck.deck;

import commanddeck.api.CheckResult;
import commanddeck.api.Deck;
import commanddeck.api.Presser;
import commanddeck.api.PreviewLine;
import commanddeck.api.Refusal;
import commanddeck.api.RunItem;
import commanddeck.api.RunListener;
import commanddeck.api.RunRequest;
import commanddeck.api.RunResult;
import commanddeck.api.RunStatus;
import commanddeck.api.StartResult;
import commanddeck.api.StepEvent;
import commanddeck.command.CommandCatalog;
import commanddeck.command.CommandInfo;
import commanddeck.command.Danger;
import commanddeck.command.Preset;
import commanddeck.macro.Limits;
import commanddeck.macro.Macro;
import commanddeck.macro.MacroReader;
import commanddeck.macro.MacroValidator;
import commanddeck.macro.Node;
import commanddeck.macro.Placeholders;
import commanddeck.macro.Problem;
import commanddeck.run.MacroRunner;
import commanddeck.run.RunLog;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The deck: every run, whether from the window, {@code /deck run} or another mod, goes through here, with the
 * same checks, limits, confirmation and log. {@link #check}, {@link #start}, {@link #stop} and the status
 * calls are safe from any thread. Runs only advance in {@link #tick}, which the adapter calls once per game
 * tick on the tick thread, so a run's first step always comes after {@code start} has returned.
 */
public final class DeckService implements Deck {
    static final long TOKEN_LIFETIME_MILLIS = 60_000;
    static final long KEEP_FINISHED_MILLIS = 10 * 60_000;
    static final int MAX_SOURCE = 120;
    static final int MAX_LINE = 1000;
    private static final int PROMPT_LINES = 5;

    private final Game game;
    private final MacroLibrary macros;
    private final Supplier<CommandCatalog> catalog;
    private final RunLog log;
    private final LongSupplier clock;
    private final SecureRandom random = new SecureRandom();
    private final String idPrefix;

    private volatile Limits limits = Limits.DEFAULTS;
    private volatile boolean enabled = true;

    // Guarded by this.
    private final Map<String, Run> runs = new LinkedHashMap<>();
    private final Map<String, Token> tokens = new LinkedHashMap<>();
    private final Map<String, Observer> observers = new LinkedHashMap<>();
    private long nextId = 1;
    private long nextObserver = 1;

    /** Someone following one presser's runs. {@code active} turns off on unobserve, even for runs already going. */
    private static final class Observer {
        final String presserId;
        final RunListener listener;
        volatile boolean active = true;

        Observer(String presserId, RunListener listener) {
            this.presserId = presserId;
            this.listener = listener;
        }
    }

    /**
     * Hands each callback to the run's starter and then to every observer of its presser, each on its own, so
     * one that throws is logged and the others still hear about the run.
     */
    private final class Fanout implements RunListener {
        final String runId;
        final String starter;
        final RunListener first;
        final List<Observer> watching;

        Fanout(String runId, String starter, RunListener first, List<Observer> watching) {
            this.runId = runId;
            this.starter = starter;
            this.first = first;
            this.watching = watching;
        }

        @Override
        public void onStep(StepEvent step) {
            if (first != null) {
                try {
                    first.onStep(step);
                } catch (RuntimeException e) {
                    log.listenerFailed(runId, starter, e);
                }
            }
            for (Observer o : watching) {
                if (!o.active) continue;
                try {
                    o.listener.onStep(step);
                } catch (RuntimeException e) {
                    log.listenerFailed(runId, "an observer", e);
                }
            }
        }

        @Override
        public void onFinish(RunResult result) {
            if (first != null) {
                try {
                    first.onFinish(result);
                } catch (RuntimeException e) {
                    log.listenerFailed(runId, starter, e);
                }
            }
            for (Observer o : watching) {
                if (!o.active) continue;
                try {
                    o.listener.onFinish(result);
                } catch (RuntimeException e) {
                    log.listenerFailed(runId, "an observer", e);
                }
            }
        }
    }

    private static final class Run {
        final Presser presser;
        final MacroRunner runner;
        long finishedAtMillis = -1;

        Run(Presser presser, MacroRunner runner) {
            this.presser = presser;
            this.runner = runner;
        }
    }

    private record Token(String presserId, String request, long expiresAtMillis) {}

    /** A checked request: either a refusal, or what would run and what it needs first. */
    private record Prepared(Refusal refusal, Node body, String macroId, List<PreviewLine> preview, boolean needsConfirmation,
                            String prompt, boolean saveRisk) {
        static Prepared refused(Refusal r, List<PreviewLine> preview) {
            return new Prepared(r, null, null, preview, false, null, false);
        }
    }

    /**
     * @param catalog the current command catalog, asked again on every check (it changes on reload)
     * @param clock   real time in milliseconds, for confirmation tokens
     */
    public DeckService(Game game, MacroLibrary macros, Supplier<CommandCatalog> catalog, RunLog log, LongSupplier clock) {
        this.game = game;
        this.macros = macros;
        this.catalog = catalog;
        this.log = log == null ? RunLog.NONE : log;
        this.clock = clock;
        this.idPrefix = Long.toString(clock.getAsLong(), 36);
    }

    /** The operator's limits, for runs started from now on. */
    public void setLimits(Limits limits) {
        this.limits = limits;
    }

    /** False turns every new run off ({@code disabled}); runs already going keep going. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String apiVersion() {
        return API_VERSION;
    }

    // ---- check and start ----------------------------------------------------------------------------

    @Override
    public CheckResult check(Presser presser, RunRequest request) {
        Prepared p = prepare(presser, request);
        if (p.refusal() != null) return new CheckResult(false, p.refusal(), p.preview(), false, null, false, null);
        String token = null;
        if (p.needsConfirmation()) {
            token = newToken();
            synchronized (this) {
                purgeTokens(clock.getAsLong());
                tokens.put(token, new Token(presser.id(), key(request), clock.getAsLong() + TOKEN_LIFETIME_MILLIS));
            }
        }
        return new CheckResult(true, null, p.preview(), p.needsConfirmation(), p.prompt(), p.saveRisk(), token);
    }

    @Override
    public StartResult start(Presser presser, RunRequest request, RunListener listener, String confirmToken) {
        Prepared p = prepare(presser, request);
        if (p.refusal() != null) return refuse(presser, p.refusal());
        synchronized (this) {
            int mine = 0;
            for (Run r : runs.values()) {
                if (!r.runner.finished() && r.presser.id().equals(presser.id())) mine++;
            }
            Limits l = limits;
            if (mine >= l.maxRunningPerPlayer()) {
                return refuse(presser, new Refusal("busy", presser.name() + " already has " + mine + " run"
                    + (mine == 1 ? "" : "s") + " going; the limit is " + l.maxRunningPerPlayer(), "max_running_per_player=" + l.maxRunningPerPlayer()));
            }
            if (p.needsConfirmation()) {
                if (confirmToken == null) {
                    return refuse(presser, new Refusal("needs_confirmation", "This needs confirmation first: " + p.prompt(), null));
                }
                long now = clock.getAsLong();
                purgeTokens(now);
                Token t = tokens.get(confirmToken);
                if (t == null || !t.presserId().equals(presser.id()) || !t.request().equals(key(request))) {
                    return refuse(presser, new Refusal("confirmation_mismatch",
                        "The confirmation has expired or was for a different request; check again", null));
                }
                tokens.remove(confirmToken);
            }
            String runId = idPrefix + "-" + nextId++;
            List<Observer> watching = new ArrayList<>();
            for (Observer o : observers.values()) {
                if (o.presserId.equals(presser.id())) watching.add(o);
            }
            RunListener fanout = new Fanout(runId, "the starter", listener, List.copyOf(watching));
            MacroRunner runner = new MacroRunner(runId, p.macroId(), request.source(), p.body(), l, game.sinkFor(presser),
                new MacroRunner.ContextSource() {
                    @Override
                    public Placeholders.Context now() {
                        return game.context(presser);
                    }

                    @Override
                    public Placeholders.Escaper escaper() {
                        return game.escaper();
                    }
                }, fanout, log);
            runs.put(runId, new Run(presser, runner));
            if (p.needsConfirmation()) log.confirmed(runId, label(presser), p.prompt());
            log.started(runId, label(presser), p.macroId(), request.source());
            return new StartResult(true, runId, null);
        }
    }

    private StartResult refuse(Presser presser, Refusal r) {
        log.refused(presser == null ? "nobody" : label(presser), r.code(), r.message());
        return new StartResult(false, null, r);
    }

    /** Every check {@code check} and {@code start} share, in the order a refusal is reported. */
    private Prepared prepare(Presser presser, RunRequest request) {
        if (!enabled) return Prepared.refused(new Refusal("disabled", "Commands are turned off on this server", null), List.of());
        if (presser == null || presser.id() == null) return Prepared.refused(invalid("presser", "No presser given"), List.of());
        if (!game.online(presser.id())) {
            return Prepared.refused(new Refusal("player_offline", presser.name() + " isn't online", null), List.of());
        }
        if (!game.mayUse(presser)) {
            return Prepared.refused(new Refusal("no_permission", presser.name() + " may not use CommandDeck", null), List.of());
        }
        if (request == null || request.items() == null || request.items().isEmpty()) {
            return Prepared.refused(invalid("items", "Nothing to run"), List.of());
        }
        if (request.source() != null && request.source().codePointCount(0, request.source().length()) > MAX_SOURCE) {
            return Prepared.refused(invalid("source", "The source label is longer than " + MAX_SOURCE + " characters"), List.of());
        }

        CommandCatalog cat = catalog.get();
        List<Node> nodes = new ArrayList<>();
        List<String> askFirst = new ArrayList<>();
        String macroId = null;
        for (int i = 0; i < request.items().size(); i++) {
            RunItem item = request.items().get(i);
            String where = request.items().size() == 1 ? "" : " (item " + (i + 1) + ")";
            if (item == null || item.kind() == null || item.value() == null) {
                return Prepared.refused(invalid("items/" + i, "An item needs a kind and a value" + where), List.of());
            }
            switch (item.kind()) {
                case COMMAND_LINE: {
                    String line = item.value();
                    if (line.isBlank()) return Prepared.refused(invalid("items/" + i, "An empty command line" + where), List.of());
                    if (line.codePointCount(0, line.length()) > MAX_LINE) {
                        return Prepared.refused(invalid("items/" + i, "A command line longer than " + MAX_LINE + " characters" + where), List.of());
                    }
                    nodes.add(new Node.Command(line, false, null));
                    break;
                }
                case MACRO_REF: {
                    String id = item.value();
                    Macro m = macros.playerMacro(presser.id(), id);
                    if (m == null) m = macros.serverMacro(id);
                    if (m != null) {
                        nodes.add(m.body());
                        if (m.dangerous()) askFirst.add(m.name());
                    } else {
                        Preset preset = preset(cat, id);
                        if (preset == null) {
                            return Prepared.refused(new Refusal("unknown_macro", "No macro or preset called \"" + id + "\"" + where, id), List.of());
                        }
                        nodes.add(preset.macro());
                    }
                    if (request.items().size() == 1) macroId = id;
                    break;
                }
                case MACRO_JSON: {
                    MacroReader.Result<Node> r = MacroReader.readNodeText(item.value());
                    if (!r.ok()) return Prepared.refused(refusal(r.problem(), where), List.of());
                    nodes.add(r.value());
                    break;
                }
                default:
                    return Prepared.refused(invalid("items/" + i, "Unknown item kind" + where), List.of());
            }
        }
        // Several items run as if wrapped in a sequence, and depth counts that sequence.
        Node body = nodes.size() == 1 ? nodes.get(0) : new Node.Sequence(List.copyOf(nodes), null);
        Problem limitProblem = MacroValidator.check(body, limits);
        if (limitProblem != null) return Prepared.refused(refusal(limitProblem, ""), List.of());

        Placeholders.Context ctx = game.context(presser);
        if (ctx == null) return Prepared.refused(new Refusal("player_offline", presser.name() + " isn't online", null), List.of());
        List<PreviewLine> preview = new ArrayList<>();
        Refusal first = null;
        boolean saveRisk = false;
        List<String> shown = new ArrayList<>();
        List<Commands.Found> found = Commands.inTreeOrder(body);
        for (Commands.Found f : found) {
            Node.Command c = f.command();
            if (c.confirm()) askFirst.add(c.note() != null ? c.note() : c.line());
            Placeholders.Rendered r = Placeholders.render(c.line(), ctx, game.escaper());
            if (!r.ok()) {
                preview.add(new PreviewLine(f.path(), c.line(), false, r.problem().message()));
                if (first == null) first = refusal(r.problem(), "");
                continue;
            }
            Problem unsendable = game.checkLine(r.line());
            preview.add(new PreviewLine(f.path(), r.line(), unsendable == null, unsendable == null ? null : unsendable.message()));
            if (unsendable != null && first == null) first = refusal(unsendable, "");
            if (!shown.contains(r.line())) shown.add(r.line());
            CommandInfo info = cat == null ? null : cat.match(r.line());
            if (info != null && info.danger() != Danger.NONE) {
                String what = info.label() != null ? info.label() : "/" + info.name();
                if (!askFirst.contains(what)) askFirst.add(what);
                if (info.danger() == Danger.SAVE_RISK) saveRisk = true;
            }
        }
        if (first != null) return Prepared.refused(first, List.copyOf(preview));

        boolean needs = !askFirst.isEmpty();
        String prompt = needs ? prompt(shown, askFirst, saveRisk) : null;
        return new Prepared(null, body, macroId, List.copyOf(preview), needs, prompt, saveRisk);
    }

    /** For example {@code Runs /calendar set day 90. Ask first: Set the day.} */
    private static String prompt(List<String> lines, List<String> askFirst, boolean saveRisk) {
        StringBuilder b = new StringBuilder("Runs ");
        int n = Math.min(lines.size(), PROMPT_LINES);
        for (int i = 0; i < n; i++) b.append(i == 0 ? "" : ", ").append(lines.get(i));
        if (lines.size() > n) b.append(" and ").append(lines.size() - n).append(" more");
        b.append(". Ask first: ").append(String.join("; ", askFirst)).append('.');
        if (saveRisk) b.append(" This can damage the save.");
        return b.toString();
    }

    /** The first preset with this id, in sidecar order. */
    private static Preset preset(CommandCatalog cat, String id) {
        if (cat == null) return null;
        for (Preset p : cat.presets().values()) {
            if (p.id().equals(id)) return p;
        }
        return null;
    }

    private static Refusal invalid(String path, String message) {
        return new Refusal("invalid", message, path);
    }

    /**
     * A refusal from a core problem. The detail is the JSON Pointer for {@code invalid}, and otherwise the
     * problem's detail fields as {@code key=value}, space-separated, in order.
     */
    static Refusal refusal(Problem p, String where) {
        String detail;
        if ("invalid".equals(p.code())) {
            detail = String.valueOf(p.detail().get("pointer"));
        } else {
            StringBuilder b = new StringBuilder();
            for (Map.Entry<String, Object> e : p.detail().entrySet()) {
                if (b.length() > 0) b.append(' ');
                b.append(e.getKey()).append('=').append(e.getValue());
            }
            detail = b.length() == 0 ? null : b.toString();
        }
        return new Refusal(p.code(), p.message() + where, detail);
    }

    /** What a confirmation token is tied to: every item and the source. */
    private static String key(RunRequest r) {
        StringBuilder b = new StringBuilder();
        for (RunItem i : r.items()) b.append(i.kind()).append('\u0000').append(i.value()).append('\u0001');
        return b.append(r.source()).toString();
    }

    private String newToken() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder b = new StringBuilder();
        for (byte x : bytes) b.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        return b.toString();
    }

    private void purgeTokens(long now) {
        tokens.values().removeIf(t -> t.expiresAtMillis() <= now);
    }

    private static String label(Presser p) {
        return p.name() + " (" + p.id() + ")";
    }

    // ---- running ------------------------------------------------------------------------------------

    /**
     * One game tick, on the tick thread: every run takes its step. A run whose presser has left stops before
     * its next step ({@code player_offline}). Finished runs are forgotten after ten minutes.
     */
    public synchronized void tick(long tick, long nowMillis) {
        // A copy, so a listener that starts a run during this tick doesn't disturb the loop.
        for (Run r : new ArrayList<>(runs.values())) {
            if (r.runner.finished()) continue;
            try {
                if (!game.online(r.presser.id())) r.runner.requestStop("player_offline");
                r.runner.tick(tick, nowMillis);
            } catch (RuntimeException e) {
                // A fault in the game adapter ends this run only; the others keep going.
                r.runner.fail(tick, "internal_error", "CommandDeck failed while running this step: " + e);
            }
            if (r.runner.finished()) r.finishedAtMillis = nowMillis;
        }
        for (Iterator<Run> it = runs.values().iterator(); it.hasNext(); ) {
            Run r = it.next();
            if (r.finishedAtMillis >= 0 && nowMillis - r.finishedAtMillis > KEEP_FINISHED_MILLIS) it.remove();
        }
        purgeTokens(clock.getAsLong());
    }

    @Override
    public synchronized boolean stop(String runId, Presser by) {
        Run r = runs.get(runId);
        if (r == null || r.runner.finished() || by == null) return false;
        if (r.presser.id().equals(by.id())) {
            r.runner.requestStop("stop_requested");
        } else if (game.isOperator(by)) {
            r.runner.requestStop("stopped_by_operator");
        } else {
            return false;
        }
        return true;
    }

    /** {@code /deck stop all}: asks every run to stop. The number asked, or -1 if {@code by} isn't an operator. */
    public synchronized int stopAll(Presser by) {
        if (!game.isOperator(by)) return -1;
        int n = 0;
        for (Run r : runs.values()) {
            if (r.runner.finished()) continue;
            r.runner.requestStop(r.presser.id().equals(by.id()) ? "stop_requested" : "stopped_by_operator");
            n++;
        }
        return n;
    }

    @Override
    public synchronized String observe(Presser presser, RunListener listener) {
        if (presser == null || presser.id() == null || listener == null) return null;
        String id = idPrefix + "-o" + nextObserver++;
        observers.put(id, new Observer(presser.id(), listener));
        return id;
    }

    @Override
    public synchronized void unobserve(String observerId) {
        Observer o = observerId == null ? null : observers.remove(observerId);
        if (o != null) o.active = false;
    }

    /** On reload: every observer ends, as if each had called {@code unobserve}. */
    public synchronized void clearObservers() {
        for (Observer o : observers.values()) o.active = false;
        observers.clear();
    }

    @Override
    public synchronized RunStatus status(String runId) {
        Run r = runs.get(runId);
        return r == null ? null : r.runner.status(r.presser.id());
    }

    @Override
    public synchronized List<RunStatus> running(Presser presser) {
        List<RunStatus> out = new ArrayList<>();
        for (Run r : runs.values()) {
            if (!r.runner.finished() && (presser == null || r.presser.id().equals(presser.id()))) {
                out.add(r.runner.status(r.presser.id()));
            }
        }
        return out;
    }
}
