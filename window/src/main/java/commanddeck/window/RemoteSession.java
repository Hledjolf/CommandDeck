package commanddeck.window;

import static commanddeck.window.SessionCodec.message;

import commanddeck.api.CheckResult;
import commanddeck.api.RunRequest;
import commanddeck.api.RunStatus;
import commanddeck.api.StartResult;
import commanddeck.command.ArgType;
import commanddeck.command.CommandCatalog;
import commanddeck.macro.Limits;
import commanddeck.macro.Macro;
import commanddeck.macro.Problem;
import commanddeck.window.MacroShelf.Scope;
import commanddeck.window.SessionCodec.Message;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * A {@link DeckSession} whose deck is in another process: each call is a request written by
 * {@link SessionCodec}, sent through a transport that returns the {@link SessionServer}'s reply. The transport
 * here answers each request in turn; a game whose messages arrive later adds its own caching in front.
 */
public final class RemoteSession implements DeckSession {
    private final Function<String, String> transport;

    public RemoteSession(Function<String, String> transport) {
        this.transport = transport;
    }

    private Object call(Message request) {
        Message reply = SessionCodec.read(transport.apply(SessionCodec.write(request)));
        if (reply.body().containsKey("error")) throw new IllegalStateException(request.op() + ": " + reply.get("error"));
        return reply.get("value");
    }

    @Override
    public Opened open() {
        return SessionCodec.opened(call(message("open")));
    }

    @Override
    public Limits limits() {
        return SessionCodec.limits(call(message("limits")));
    }

    @Override
    public CommandCatalog catalog() {
        return SessionCodec.catalog(call(message("catalog")));
    }

    @Override
    public List<String> onlinePlayers() {
        return SessionCodec.strings(call(message("onlinePlayers")));
    }

    @Override
    public Boolean mayRun(String commandName) {
        return (Boolean) call(message("mayRun", "command", commandName));
    }

    @Override
    public int tokens(String commandName, int form, String arg) {
        return ((Number) call(message("tokens", "command", commandName, "form", form, "arg", arg))).intValue();
    }

    @Override
    public boolean isList(String commandName, int form, String arg) {
        return Boolean.TRUE.equals(call(message("isList", "command", commandName, "form", form, "arg", arg)));
    }

    @Override
    public List<String> assetIds(ArgType type) {
        return SessionCodec.strings(call(message("assetIds", "type", type.name())));
    }

    @Override
    public CheckResult check(RunRequest request) {
        return SessionCodec.check(call(message("check", "request", SessionCodec.request(request))));
    }

    @Override
    public StartResult start(RunRequest request, String confirmToken) {
        return SessionCodec.start(call(message("start", "request", SessionCodec.request(request), "token", confirmToken)));
    }

    @Override
    public List<RunStatus> running() {
        List<RunStatus> out = new ArrayList<>();
        for (Object o : (List<?>) call(message("running"))) out.add(SessionCodec.status(o));
        return out;
    }

    @Override
    public boolean stop(String runId) {
        return Boolean.TRUE.equals(call(message("stop", "runId", runId)));
    }

    @Override
    public int stopAll() {
        return ((Number) call(message("stopAll"))).intValue();
    }

    @Override
    public boolean holds(String permission) {
        return Boolean.TRUE.equals(call(message("holds", "permission", permission)));
    }

    @Override
    public List<Macro> macros(Scope scope) {
        List<Macro> out = new ArrayList<>();
        for (Object o : (List<?>) call(message("macros", "scope", scope.name()))) out.add(SessionCodec.macro(o));
        return out;
    }

    @Override
    public Problem saveMacro(Scope scope, Macro macro, String replacing) {
        return SessionCodec.problem(call(message("saveMacro", "scope", scope.name(), "macro", SessionCodec.macro(macro), "replacing", replacing)));
    }

    @Override
    public Problem deleteMacro(Scope scope, String id) {
        return SessionCodec.problem(call(message("deleteMacro", "scope", scope.name(), "id", id)));
    }

    @Override
    public Set<String> favorites() {
        return new LinkedHashSet<>(SessionCodec.strings(call(message("favorites"))));
    }

    @Override
    public boolean toggleFavorite(String command) {
        return Boolean.TRUE.equals(call(message("toggleFavorite", "command", command)));
    }

    @Override
    public List<String> history(String command) {
        return SessionCodec.strings(call(message("history", "command", command)));
    }

    @Override
    public void remember(String command, String text) {
        call(message("remember", "command", command, "text", text));
    }
}
