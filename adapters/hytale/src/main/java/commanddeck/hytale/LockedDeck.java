package commanddeck.hytale;

import commanddeck.api.CheckResult;
import commanddeck.api.Deck;
import commanddeck.api.MacroInfo;
import commanddeck.api.Presser;
import commanddeck.api.RunListener;
import commanddeck.api.RunRequest;
import commanddeck.api.RunStatus;
import commanddeck.api.StartResult;
import commanddeck.deck.DeckService;
import commanddeck.macro.Limits;
import java.util.List;

/**
 * The single-threaded core made safe from any thread: every call into the deck, the tick included, holds this
 * one lock. Hytale calls in from its scheduler (the tick), from world threads (commands and the window) and
 * from other mods' threads. The lock is reentrant, so a listener called during the tick may ask for
 * {@code status}. Calls are short; the longest wait is {@code {look}}, which gives up after
 * {@link HytaleGame#LOOK_WAIT_MILLIS} if the player's world thread is busy.
 */
public final class LockedDeck implements Deck {
    private final DeckService deck;

    public LockedDeck(DeckService deck) {
        this.deck = deck;
    }

    @Override
    public String apiVersion() {
        return deck.apiVersion();
    }

    @Override
    public synchronized CheckResult check(Presser presser, RunRequest request) {
        return deck.check(presser, request);
    }

    @Override
    public synchronized StartResult start(Presser presser, RunRequest request, RunListener listener, String confirmToken) {
        return deck.start(presser, request, listener, confirmToken);
    }

    @Override
    public synchronized boolean stop(String runId, Presser by) {
        return deck.stop(runId, by);
    }

    @Override
    public synchronized RunStatus status(String runId) {
        return deck.status(runId);
    }

    @Override
    public synchronized List<RunStatus> running(Presser presser) {
        return deck.running(presser);
    }

    @Override
    public synchronized String observe(Presser presser, RunListener listener) {
        return deck.observe(presser, listener);
    }

    @Override
    public synchronized void unobserve(String observerId) {
        deck.unobserve(observerId);
    }

    @Override
    public synchronized List<MacroInfo> macros(Presser presser, String category) {
        return deck.macros(presser, category);
    }

    // ---- CommandDeck's own calls, not part of the API ----

    /** One game tick: every run takes its step. */
    public synchronized void tick(long tick, long nowMillis) {
        deck.tick(tick, nowMillis);
    }

    /** {@code /deck stop all}: the number of runs asked to stop, or -1 if {@code by} isn't an operator. */
    public synchronized int stopAll(Presser by) {
        return deck.stopAll(by);
    }

    /** Whether the presser holds a macro's or preset's {@code permission}; true when there is none. */
    public synchronized boolean holds(Presser presser, String permission) {
        return deck.holds(presser, permission);
    }

    public synchronized void setLimits(Limits limits) {
        deck.setLimits(limits);
    }

    public synchronized void setEnabled(boolean enabled) {
        deck.setEnabled(enabled);
    }

    /** On reload: every observer ends. */
    public synchronized void clearObservers() {
        deck.clearObservers();
    }
}
