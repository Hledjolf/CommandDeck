package commanddeck.deck;

import commanddeck.api.CheckResult;
import commanddeck.api.Deck;
import commanddeck.api.MacroInfo;
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
import commanddeck.api.Target;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The deck: every run, whether from the window, {@code /deck run} or another mod, goes through here, with the
 * same checks, limits, confirmation and log. Runs only advance in {@link #tick}, which the adapter calls once
 * per game tick, so a run's first step always comes after {@code start} has returned.
 *
 * <p>Single-threaded, as every CommandDeck core is: it is called on
 * one thread at a time and never blocks. The adapter makes it safe to call from any thread.
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

    private Limits limits = Limits.DEFAULTS;
    private boolean enabled = true;

    private final Map<String, Run> runs = new LinkedHashMap<>();
    private final Map<String, Token> tokens = new LinkedHashMap<>();
    private final Map<String, Observer> observers = new LinkedHashMap<>();
    private long nextId = 1;
    private long nextObserver = 1;

    /** Someone following one presser's runs. {@code active} turns off on unobserve, even for runs already going. */
    private static final class Observer {
        final String presserId;
        final RunListener listener;
        boolean active = true;

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
        /** Null when the run is for the presser. */
        final String targetId;
        final MacroRunner runner;
        long finishedAtMillis = -1;

        Run(Presser presser, String targetId, MacroRunner runner) {
            this.presser = presser;
            this.targetId = targetId;
            this.runner = runner;
        }
    }

    private record Token(String presserId, String request, long expiresAtMillis) {}

    /**
     * A checked request: either a refusal, or what would run and what it needs first. {@code targetId} and
     * {@code targetName} are null when the run is for the presser.
     */
    private record Prepared(Refusal refusal, Node body, String macroId, List<PreviewLine> preview, boolean needsConfirmation,
                            String prompt, boolean saveRisk, String targetId, String targetName) {
        static Prepared refused(Refusal r, List<PreviewLine> preview) {
            return new Prepared(r, null, null, preview, false, null, false, null, null);
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
            purgeTokens(clock.getAsLong());
            tokens.put(token, new Token(presser.id(), key(request, p.targetId()), clock.getAsLong() + TOKEN_LIFETIME_MILLIS));
        }
        return new CheckResult(true, null, p.preview(), p.needsConfirmation(), p.prompt(), p.saveRisk(), token);
    }

    @Override
    public StartResult start(Presser presser, RunRequest request, RunListener listener, String confirmToken) {
        Prepared p = prepare(presser, request);
        if (p.refusal() != null) return refuse(presser, p.refusal());
        int mine = 0;
        for (Run r : runs.values()) {
            if (!r.runner.finished() && r.presser.id().equals(presser.id())) mine++;
        }
        Limits l = limits;
        // The console has its own limit, so mods acting for the server don't block each other at one run.
        boolean console = game.isConsole(presser);
        int max = console ? l.maxRunningConsole() : l.maxRunningPerPlayer();
        if (mine >= max) {
            return refuse(presser, new Refusal("busy", presser.name() + " already has " + mine + " run"
                + (mine == 1 ? "" : "s") + " going; the limit is " + max, (console ? "max_running_console=" : "max_running_per_player=") + max));
        }
        if (p.needsConfirmation()) {
            if (confirmToken == null) {
                return refuse(presser, new Refusal("needs_confirmation", "This needs confirmation first: " + p.prompt(), null));
            }
            long now = clock.getAsLong();
            purgeTokens(now);
            Token t = tokens.get(confirmToken);
            if (t == null || !t.presserId().equals(presser.id()) || !t.request().equals(key(request, p.targetId()))) {
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
        String targetId = p.targetId();
        String targetName = p.targetName();
        MacroRunner runner = new MacroRunner(runId, p.macroId(), request.source(), p.body(), l, game.sinkFor(presser),
            new MacroRunner.ContextSource() {
                @Override
                public Placeholders.Context now() {
                    if (targetId == null) return game.context(presser);
                    String name = game.onlineName(targetId);
                    return withTarget(game.context(presser), name != null ? name : targetName);
                }

                @Override
                public Placeholders.Escaper escaper() {
                    return game.escaper();
                }
            }, fanout, log);
        runs.put(runId, new Run(presser, targetId, runner));
        if (p.needsConfirmation()) log.confirmed(runId, label(presser), p.prompt());
        log.started(runId, label(presser), targetId == null ? null : targetName + " (" + targetId + ")", p.macroId(), request.source());
        return new StartResult(true, runId, null);
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
        // The target only fills {target}; naming anyone but yourself needs the "run for others" permission.
        String targetId = targetOf(presser, request);
        String targetName = null;
        if (targetId != null) {
            if (!game.mayRunForOthers(presser)) {
                return Prepared.refused(new Refusal("no_permission", presser.name() + " may not run commands for other players",
                    "target=" + targetId), List.of());
            }
            targetName = game.onlineName(targetId);
            if (targetName == null) {
                String who = request.target().name() != null ? request.target().name() : targetId;
                return Prepared.refused(new Refusal("target_offline", who + " isn't online", "target=" + targetId), List.of());
            }
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
                    Found f = resolve(presser, cat, item.value(), i, where);
                    if (f.refusal() != null) return Prepared.refused(f.refusal(), List.of());
                    nodes.add(f.body());
                    if (f.dangerous()) askFirst.add(f.name());
                    if (!holds(presser, f.permission())) {
                        return Prepared.refused(new Refusal("no_permission", presser.name() + " doesn't have permission to run " + f.name() + where,
                            "permission=" + f.permission()), List.of());
                    }
                    if (request.items().size() == 1) macroId = f.id();
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
        if (targetId != null) ctx = withTarget(ctx, targetName);
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
            preview.add(new PreviewLine(f.path(), r.line(), unsendable == null, unsendable == null ? null : unsendable.message(),
                game.permitted(presser, r.line())));
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
        return new Prepared(null, body, macroId, List.copyOf(preview), needs, prompt, saveRisk, targetId, targetName);
    }

    /** The request's target id, or null when it names nobody or the presser themselves. */
    private static String targetOf(Presser presser, RunRequest request) {
        Target t = request == null ? null : request.target();
        if (t == null || t.id() == null || t.id().equals(presser.id())) return null;
        return t.id();
    }

    private static Placeholders.Context withTarget(Placeholders.Context ctx, String targetName) {
        if (ctx == null) return null;
        return ctx.withTarget(targetName);
    }

    /**
     * Whether the presser holds a macro's or preset's {@code permission}; true when there is none. Also for the
     * window and {@code /deck list}, which hide what the presser can't run.
     */
    public boolean holds(Presser presser, String permission) {
        return permission == null || game.holds(presser, permission);
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

    /** What a {@code MACRO_REF} names: a saved macro or a preset, or why it names nothing. */
    private record Found(Refusal refusal, Node body, String id, String name, boolean dangerous, String permission) {
        static Found refused(Refusal r) {
            return new Found(r, null, null, null, false, null);
        }

        static Found of(Macro m) {
            return new Found(null, m.body(), m.id(), m.name(), m.dangerous(), m.permission());
        }

        static Found of(Preset p) {
            return new Found(null, p.macro(), p.id(), p.name(), false, p.permission());
        }
    }

    /**
     * A plain id is looked up in the presser's own macros, then the server's, then presets in sidecar order. A
     * scoped reference ({@code player:<id>}, {@code server:<id>}, {@code preset:<mod id>/<id>}) names one. The
     * scope is the text before the first ':'. A preset's mod id runs to the last '/', whatever the game's mod
     * ids look like, because macro ids can't contain '/'.
     */
    private Found resolve(Presser presser, CommandCatalog cat, String ref, int item, String where) {
        Refusal unknown = new Refusal("unknown_macro", "No macro or preset called \"" + ref + "\"" + where, ref);
        int colon = ref.indexOf(':');
        if (colon < 0) {
            Macro m = macros.playerMacro(presser.id(), ref);
            if (m == null) m = macros.serverMacro(ref);
            if (m != null) return Found.of(m);
            Preset p = firstPreset(cat, ref);
            return p != null ? Found.of(p) : Found.refused(unknown);
        }
        String scope = ref.substring(0, colon);
        String rest = ref.substring(colon + 1);
        switch (scope) {
            case "player": {
                Macro m = macros.playerMacro(presser.id(), rest);
                return m != null ? Found.of(m) : Found.refused(unknown);
            }
            case "server": {
                Macro m = macros.serverMacro(rest);
                return m != null ? Found.of(m) : Found.refused(unknown);
            }
            case "preset": {
                int slash = rest.lastIndexOf('/');
                if (slash <= 0 || slash == rest.length() - 1) {
                    return Found.refused(invalid("items/" + item, "A preset reference looks like preset:<mod id>/<id>" + where));
                }
                Preset p = cat == null ? null : cat.presets().get(rest);
                return p != null ? Found.of(p) : Found.refused(unknown);
            }
            default:
                return Found.refused(invalid("items/" + item, "Unknown reference scope \"" + scope + "\": use player:, server: or preset:" + where));
        }
    }

    /** The first preset with this id, in sidecar order. */
    private static Preset firstPreset(CommandCatalog cat, String id) {
        if (cat == null) return null;
        for (Preset p : cat.presets().values()) {
            if (p.id().equals(id)) return p;
        }
        return null;
    }

    /**
     * Whether running it will ask first ({@code MacroInfo.dangerous}): the macro is marked {@code dangerous}, or
     * any of its commands has {@code confirm: true} or a sidecar {@code danger} of {@code confirm} or
     * {@code save_risk}. Worked out from the nodes as written; {@code check} still decides.
     */
    private static boolean asksFirst(boolean dangerous, Node body, CommandCatalog cat) {
        if (dangerous) return true;
        for (Commands.Found f : Commands.inTreeOrder(body)) {
            if (f.command().confirm()) return true;
            CommandInfo info = cat == null ? null : cat.match(f.command().line());
            if (info != null && info.danger() != Danger.NONE) return true;
        }
        return false;
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

    /** What a confirmation token is tied to: every item, the source and the target. */
    private static String key(RunRequest r, String targetId) {
        StringBuilder b = new StringBuilder();
        for (RunItem i : r.items()) b.append(i.kind()).append('\u0000').append(i.value()).append('\u0001');
        return b.append(r.source()).append('\u0002').append(targetId).toString();
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
    public void tick(long tick, long nowMillis) {
        // A copy, so a listener that starts a run during this tick doesn't disturb the loop.
        for (Run r : new ArrayList<>(runs.values())) {
            if (r.runner.finished()) continue;
            try {
                if (!game.online(r.presser.id())) r.runner.requestStop("player_offline");
                if (r.targetId != null && game.onlineName(r.targetId) == null) r.runner.requestStop("target_offline");
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
    public boolean stop(String runId, Presser by) {
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
    public int stopAll(Presser by) {
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
    public String observe(Presser presser, RunListener listener) {
        if (presser == null || presser.id() == null || listener == null) return null;
        String id = idPrefix + "-o" + nextObserver++;
        observers.put(id, new Observer(presser.id(), listener));
        return id;
    }

    @Override
    public void unobserve(String observerId) {
        Observer o = observerId == null ? null : observers.remove(observerId);
        if (o != null) o.active = false;
    }

    /** On reload: every observer ends, as if each had called {@code unobserve}. */
    public void clearObservers() {
        for (Observer o : observers.values()) o.active = false;
        observers.clear();
    }

    // ---- listing ------------------------------------------------------------------------------------

    @Override
    public List<MacroInfo> macros(Presser presser, String category) {
        if (presser == null || presser.id() == null || !game.mayUse(presser)) return List.of();
        List<MacroInfo> out = new ArrayList<>();
        CommandCatalog cat = catalog.get();
        // An id is listed once, as a plain MACRO_REF resolves it; if that one is off limits, the id is left out.
        // The set is only asked whether it holds an id, so its order never reaches the list.
        Set<String> seen = new HashSet<>();
        for (Macro m : macros.playerMacros(presser.id())) {
            if (seen.add(m.id()) && holds(presser, m.permission()) && (category == null || category.equals(m.category()))) {
                out.add(new MacroInfo(m.id(), m.name(), m.description(), m.category(), MacroInfo.Origin.PLAYER, null,
                    asksFirst(m.dangerous(), m.body(), cat)));
            }
        }
        for (Macro m : macros.serverMacros()) {
            if (seen.add(m.id()) && holds(presser, m.permission()) && (category == null || category.equals(m.category()))) {
                out.add(new MacroInfo(m.id(), m.name(), m.description(), m.category(), MacroInfo.Origin.SERVER, null,
                    asksFirst(m.dangerous(), m.body(), cat)));
            }
        }
        if (cat != null) {
            for (Map.Entry<String, Preset> e : cat.presets().entrySet()) {
                Preset p = e.getValue();
                if (!seen.add(p.id()) || !holds(presser, p.permission())) continue;
                if (category != null && !category.equals(p.category())) continue;
                String mod = e.getKey().substring(0, e.getKey().lastIndexOf('/'));
                out.add(new MacroInfo(p.id(), p.name(), p.description(), p.category(), MacroInfo.Origin.PRESET, mod,
                    asksFirst(false, p.macro(), cat)));
            }
        }
        return List.copyOf(out);
    }

    @Override
    public RunStatus status(String runId) {
        Run r = runs.get(runId);
        return r == null ? null : r.runner.status(r.presser.id(), r.targetId);
    }

    @Override
    public List<RunStatus> running(Presser presser) {
        List<RunStatus> out = new ArrayList<>();
        for (Run r : runs.values()) {
            if (!r.runner.finished() && (presser == null || r.presser.id().equals(presser.id()))) {
                out.add(r.runner.status(r.presser.id(), r.targetId));
            }
        }
        return out;
    }
}
