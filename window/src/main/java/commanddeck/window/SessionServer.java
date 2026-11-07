package commanddeck.window;

import static commanddeck.window.SessionCodec.message;

import commanddeck.api.RunStatus;
import commanddeck.command.ArgType;
import commanddeck.macro.Macro;
import commanddeck.window.MacroShelf.Scope;
import commanddeck.window.SessionCodec.Message;
import java.util.ArrayList;
import java.util.List;

/**
 * The deck's end of a window session drawn elsewhere: reads a request, answers it from a {@link DeckSession}
 * (normally a {@link LocalSession} for the presser), and writes the reply. {@link RemoteSession} is the other end.
 */
public final class SessionServer {
    private final DeckSession session;

    public SessionServer(DeckSession session) {
        this.session = session;
    }

    /** One request's reply; a request it can't answer gets an {@code error} reply, never an exception. */
    public String handle(String request) {
        Message m;
        try {
            m = SessionCodec.read(request);
        } catch (RuntimeException e) {
            return SessionCodec.write(message("error", "error", "not a request: " + e.getMessage()));
        }
        try {
            return SessionCodec.write(message(m.op(), "value", answer(m)));
        } catch (RuntimeException e) {
            return SessionCodec.write(message(m.op(), "error", String.valueOf(e.getMessage())));
        }
    }

    private Object answer(Message m) {
        String op = m.op() == null ? "" : m.op();
        switch (op) {
            case "open":
                return SessionCodec.opened(session.open());
            case "limits":
                return SessionCodec.limits(session.limits());
            case "catalog":
                return SessionCodec.catalog(session.catalog());
            case "onlinePlayers":
                return session.onlinePlayers();
            case "mayRun":
                return session.mayRun((String) m.get("command"));
            case "tokens":
                return session.tokens((String) m.get("command"), ((Number) m.get("form")).intValue(), (String) m.get("arg"));
            case "isList":
                return session.isList((String) m.get("command"), ((Number) m.get("form")).intValue(), (String) m.get("arg"));
            case "assetIds":
                return session.assetIds(ArgType.valueOf((String) m.get("type")));
            case "check":
                return SessionCodec.check(session.check(SessionCodec.request(m.get("request"))));
            case "start":
                return SessionCodec.start(session.start(SessionCodec.request(m.get("request")), (String) m.get("token")));
            case "running": {
                List<Object> out = new ArrayList<>();
                for (RunStatus s : session.running()) out.add(SessionCodec.status(s));
                return out;
            }
            case "stop":
                return session.stop((String) m.get("runId"));
            case "stopAll":
                return session.stopAll();
            case "holds":
                return session.holds((String) m.get("permission"));
            case "macros": {
                List<Object> out = new ArrayList<>();
                for (Macro x : session.macros(Scope.valueOf((String) m.get("scope")))) out.add(SessionCodec.macro(x));
                return out;
            }
            case "saveMacro":
                return SessionCodec.problem(session.saveMacro(Scope.valueOf((String) m.get("scope")), SessionCodec.macro(m.get("macro")),
                    (String) m.get("replacing")));
            case "deleteMacro":
                return SessionCodec.problem(session.deleteMacro(Scope.valueOf((String) m.get("scope")), (String) m.get("id")));
            case "favorites":
                return new ArrayList<>(session.favorites());
            case "toggleFavorite":
                return session.toggleFavorite((String) m.get("command"));
            case "history":
                return session.history((String) m.get("command"));
            case "remember":
                session.remember((String) m.get("command"), (String) m.get("text"));
                return null;
            default:
                throw new IllegalArgumentException("unknown op " + op);
        }
    }
}
