package commanddeck.run;

import commanddeck.api.CommandOutcome;
import commanddeck.api.RunListener;
import commanddeck.api.RunResult;
import commanddeck.api.RunResult.End;
import commanddeck.api.RunStatus;
import commanddeck.api.StepEvent;
import commanddeck.api.StopPoint;
import commanddeck.macro.ConditionPort;
import commanddeck.macro.Limits;
import commanddeck.macro.Node;
import commanddeck.macro.Placeholders;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs one macro body on the game's tick. A state machine over an explicit stack
 * of frames: it never blocks and never sleeps. Each {@link #tick} call does at most {@code steps_per_tick}
 * command steps. {@code sequence}, {@code repeat} and a resolved {@code call} cost nothing; a {@code wait} occupies whole ticks, and
 * the next step runs on the tick after it ends.
 *
 * <p>A loop's check ({@code while}, {@code until}) costs no tick either: it's answered at the start of the slot in
 * which the loop's next step would run, so it sees the game as that step would. A pass in which no command or wait
 * ran ends the tick, so a loop can never spin inside one.
 *
 * <p>The next leaf (command or wait) is always looked up in advance, so the run finishes on the tick of its
 * last step, and a stopped run can say which line would have run next.
 */
public final class MacroRunner {
    /** Fills placeholders from the presser's state at the moment a step runs. */
    public interface ContextSource {
        Placeholders.Context now();

        /** How the game escapes placeholder values; null when it doesn't. */
        default Placeholders.Escaper escaper() {
            return null;
        }
    }

    /** A node on the stack: for a sequence the next child index, for a repeat or a loop the passes started. */
    private static final class Frame {
        final Node node;
        final String path;
        long counter;
        /** A loop's answered check, waiting for {@link #nextLeaf} to act on it; null when there's none. */
        Boolean holds;
        /** A loop that has made all the passes it may. */
        boolean atLimit;
        /** Commands and waits taken when the loop's current pass started, to tell an empty pass. */
        long takenAtPassStart;
        /** For an empty pass: the tick from which its check may be answered (0 until the pass is found empty). */
        long checkFrom;

        Frame(Node node, String path) {
            this.node = node;
            this.path = path;
        }
    }

    /** A command or wait, with the innermost repeat's iteration at the time it was reached. */
    private record Leaf(Node node, String path, int iteration, int iterations) {}

    private final String runId;
    private final String macroId;
    private final String source;
    private final Limits limits;
    private final CommandSink sink;
    private final ContextSource context;
    private final RunListener listener;
    private final RunLog log;

    private final List<Frame> stack = new ArrayList<>();
    private final List<StepEvent> steps = new ArrayList<>();
    private Leaf next;
    private Leaf sentLeaf;
    /** The command being rendered and sent right now, so a fault in the adapter can say where it happened. */
    private Leaf sending;
    private String sentLine;
    private CommandSink.Pending pending;
    private long waitTicksLeft;
    private long waitUntilMillis = -1;
    private String stopReason;
    private ConditionPort conditions;
    private String actorId;
    private String targetId;
    /** How many commands and waits the run has taken, so a loop can tell a pass in which nothing ran. */
    private long taken;
    private boolean started;
    private long startedTick;
    private long startedMillis;
    private RunResult result;

    public MacroRunner(String runId, String macroId, String source, Node body, Limits limits, CommandSink sink,
                       ContextSource context, RunListener listener, RunLog log) {
        this.runId = runId;
        this.macroId = macroId;
        this.source = source;
        this.limits = limits;
        this.sink = sink;
        this.context = context;
        this.listener = listener;
        this.log = log == null ? RunLog.NONE : log;
        stack.add(new Frame(body, "body"));
        next = nextLeaf();
    }

    /**
     * The game's condition library, and who the run's conditions are about ({@code {self}} and {@code {target}}),
     * for {@code while} and {@code until}. Without it, a loop's check is UNKNOWN and stops the run.
     */
    public void useConditions(ConditionPort conditions, String actorId, String targetId) {
        this.conditions = conditions;
        this.actorId = actorId;
        this.targetId = targetId;
    }

    /** Ask for a stop before the next step: {@code stop_requested}, or {@code stopped_by_operator}. */
    public void requestStop(String reason) {
        if (result == null && stopReason == null) stopReason = reason;
    }

    /**
     * Ends the run at once with {@code ERROR}: for a fault outside the macro (the game adapter threw), so one
     * broken run can't stop the others. Where it stopped is logged as for any other ending.
     */
    public void fail(long tick, String reason, String message) {
        if (result != null) return;
        finish(End.ERROR, reason, message, tick, pointAt(pending != null ? sentLeaf : sending != null ? sending : next));
    }

    public boolean finished() {
        return result != null;
    }

    public RunResult result() {
        return result;
    }

    public String runId() {
        return runId;
    }

    public RunStatus status(String presserId) {
        return status(presserId, null);
    }

    /** {@code targetId} is null when the run's target is the presser. */
    public RunStatus status(String presserId, String targetId) {
        RunStatus.State state = result != null ? RunStatus.State.FINISHED
            : waitTicksLeft > 0 || waitUntilMillis >= 0 || pending != null ? RunStatus.State.WAITING : RunStatus.State.RUNNING;
        String at = sentLeaf != null && pending != null ? sentLeaf.path() : next != null ? next.path() : null;
        return new RunStatus(runId, presserId, source, macroId, state, steps.size(), at, result, targetId);
    }

    /** One game tick. {@code nowMillis} is real time, for {@code wait} in seconds and the runtime limit. */
    public void tick(long tick, long nowMillis) {
        if (result != null) return;
        if (!started) {
            started = true;
            startedTick = tick;
            startedMillis = nowMillis;
        }
        if (stopReason != null) {
            finish(End.STOPPED, stopReason, "Stopped before the next step", tick, pointAt(pending != null ? sentLeaf : next));
            return;
        }
        if ((nowMillis - startedMillis) > limits.maxRuntimeSeconds() * 1000L) {
            finish(End.LIMIT, "max_runtime_seconds", "Ran longer than " + limits.maxRuntimeSeconds() + " seconds", tick,
                pointAt(pending != null ? sentLeaf : next));
            return;
        }
        int budget = limits.stepsPerTick();
        if (pending != null) {
            // Still waiting for the game to answer the last command; that answer may arrive on this tick.
            if (!pending.done()) return;
            if (!settle(tick)) return;
            budget--;
            if (next == null) {
                finish(End.COMPLETED, null, null, tick, null);
                return;
            }
        }
        if (waitTicksLeft > 0) {
            waitTicksLeft--;
            if (waitTicksLeft == 0 && next == null) finish(End.COMPLETED, null, null, tick, null);
            return;
        }
        if (waitUntilMillis >= 0) {
            if (nowMillis < waitUntilMillis) return;
            waitUntilMillis = -1;
            if (next == null) {
                finish(End.COMPLETED, null, null, tick, null);
                return;
            }
        }
        boolean ranThisTick = budget < limits.stepsPerTick();
        while (budget > 0) {
            Leaf leaf = next;
            if (leaf == null) {
                finish(End.COMPLETED, null, null, tick, null);
                return;
            }
            if (leaf.node() instanceof Node.Loop) {
                if (!check(leaf, tick)) return;
                next = nextLeaf();
                continue;
            }
            taken++;
            next = nextLeaf();
            if (leaf.node() instanceof Node.Wait) {
                Node.Wait w = (Node.Wait) leaf.node();
                if (w.ticks() > 0) {
                    if (ranThisTick) {
                        // A command already ran this tick: the wait occupies the following ticks.
                        waitTicksLeft = w.ticks();
                    } else {
                        waitTicksLeft = w.ticks() - 1;
                        if (waitTicksLeft == 0 && next == null) finish(End.COMPLETED, null, null, tick, null);
                    }
                } else {
                    waitUntilMillis = nowMillis + (long) Math.ceil(w.seconds() * 1000.0);
                }
                return;
            }
            Node.Command c = (Node.Command) leaf.node();
            sending = leaf;
            Placeholders.Rendered r = Placeholders.render(c.line(), context.now(), context.escaper());
            if (!r.ok()) {
                finish(End.ERROR, "placeholder", r.problem().message(), tick, pointAt(leaf));
                return;
            }
            sentLeaf = leaf;
            sentLine = r.line();
            pending = sink.send(sentLine);
            sending = null;
            ranThisTick = true;
            if (!pending.done()) return;
            if (!settle(tick)) return;
            budget--;
            if (next == null) {
                finish(End.COMPLETED, null, null, tick, null);
                return;
            }
        }
    }

    /**
     * Answers the loop check {@code leaf} stands for, or ends the run (its limit, or UNKNOWN). False when nothing
     * more happens this tick: the run ended, or the pass was empty and the check waits for the next tick.
     */
    private boolean check(Leaf leaf, long tick) {
        Frame f = stack.get(stack.size() - 1);
        Node.Loop l = (Node.Loop) leaf.node();
        if (f.atLimit) {
            finish(End.LIMIT, "max_iterations", "The " + l.type() + " at " + leaf.path() + " made its " + f.counter + " passes", tick, pointAt(leaf));
            return false;
        }
        boolean emptyPass = f.counter > 0 && taken == f.takenAtPassStart;
        if (emptyPass) {
            if (f.checkFrom == 0) f.checkFrom = tick + 1;
            if (tick < f.checkFrom) return false;
        }
        ConditionPort.Result r;
        try {
            r = conditions == null ? new ConditionPort.Result(ConditionPort.Answer.UNKNOWN, "conditions can't be answered here")
                : conditions.evaluate(l.condition(), actorId, targetId, tick);
        } catch (RuntimeException e) {
            r = new ConditionPort.Result(ConditionPort.Answer.UNKNOWN, "the check failed: " + e);
        }
        if (r == null || r.answer() == ConditionPort.Answer.UNKNOWN) {
            String why = r == null || r.reason() == null ? "no reason given" : r.reason();
            finish(End.ERROR, "condition_unknown", "The " + l.type() + " condition at " + leaf.path() + " couldn't be answered: " + why,
                tick, pointAt(leaf));
            return false;
        }
        f.holds = r.answer() == ConditionPort.Answer.TRUE;
        return true;
    }

    /** Records the answered command. False if the run ended because the game refused it or it failed. */
    private boolean settle(long tick) {
        CommandSink.Pending p = pending;
        pending = null;
        StepEvent e = new StepEvent(runId, steps.size() + 1, sentLeaf.path(), sentLeaf.iteration(), sentLine,
            p.outcome(), p.basis(), p.outcome() == CommandOutcome.SENT && !p.decided(), List.copyOf(p.response()), tick);
        steps.add(e);
        log.step(e);
        if (listener != null) {
            try {
                listener.onStep(e);
            } catch (RuntimeException ignored) {
                // A listener that throws is ignored; the run keeps going.
            }
        }
        if (p.outcome() == CommandOutcome.SENT) return true;
        boolean refused = p.outcome() == CommandOutcome.REFUSED;
        String why = (refused ? "The game refused " : "The game failed to run ") + sentLine
            + (p.response().isEmpty() ? "" : ": " + String.join(" ", p.response()));
        finish(End.ERROR, refused ? "command_refused" : "command_failed", why, tick, pointAt(sentLeaf));
        return false;
    }

    private StopPoint pointAt(Leaf leaf) {
        if (leaf == null) return new StopPoint(null, 0, 0, null);
        Leaf following = leaf == next ? leaf : next;
        String nextLine = following != null && following.node() instanceof Node.Command
            ? ((Node.Command) following.node()).line() : null;
        return new StopPoint(leaf.path(), leaf.iteration(), leaf.iterations(), nextLine);
    }

    private void finish(End end, String reason, String message, long tick, StopPoint at) {
        int completed = 0;
        for (StepEvent e : steps) {
            if (e.outcome() == CommandOutcome.SENT) completed++;
        }
        result = new RunResult(runId, macroId, source, end, reason, message, completed, List.copyOf(steps),
            end == End.COMPLETED ? null : at, startedTick, tick);
        log.finished(result);
        if (listener != null) {
            try {
                listener.onFinish(result);
            } catch (RuntimeException ignored) {
                // As above: a listener can't break the runner.
            }
        }
    }

    /** Walks the frames to the next command or wait; null when the body is done. */
    private Leaf nextLeaf() {
        while (!stack.isEmpty()) {
            Frame f = stack.get(stack.size() - 1);
            Node n = f.node;
            if (n instanceof Node.Sequence) {
                List<Node> children = ((Node.Sequence) n).steps();
                if (f.counter < children.size()) {
                    int i = (int) f.counter++;
                    stack.add(new Frame(children.get(i), f.path + ".steps[" + i + "]"));
                } else {
                    stack.remove(stack.size() - 1);
                }
            } else if (n instanceof Node.Call) {
                // A call costs nothing: its copied-in body runs in place, and paths cross it with its reference.
                Node.Call c = (Node.Call) n;
                stack.remove(stack.size() - 1);
                if (c.body() == null) throw new IllegalStateException("A call at " + f.path + " wasn't resolved before the run");
                stack.add(new Frame(c.body(), f.path + ">" + c.ref() + ">body"));
            } else if (n instanceof Node.Repeat) {
                Node.Repeat r = (Node.Repeat) n;
                if (f.counter < r.times()) {
                    f.counter++;
                    stack.add(new Frame(r.body(), f.path + ".body"));
                } else {
                    stack.remove(stack.size() - 1);
                }
            } else if (n instanceof Node.Loop) {
                Node.Loop l = (Node.Loop) n;
                if (f.holds == null) {
                    // until runs its first pass unchecked; every other pass waits for its check.
                    if (l.until() && f.counter == 0) {
                        startPass(f, l);
                        continue;
                    }
                    return new Leaf(n, f.path, (int) f.counter, (int) maxPasses(l));
                }
                boolean again = f.holds != l.until();
                f.holds = null;
                if (!again) {
                    stack.remove(stack.size() - 1);
                } else if (f.counter >= maxPasses(l)) {
                    f.atLimit = true;
                    return new Leaf(n, f.path, (int) f.counter, (int) maxPasses(l));
                } else {
                    startPass(f, l);
                }
            } else {
                stack.remove(stack.size() - 1);
                int iteration = 0;
                int iterations = 0;
                for (int i = stack.size() - 1; i >= 0; i--) {
                    Node outer = stack.get(i).node;
                    if (outer instanceof Node.Repeat || outer instanceof Node.Loop) {
                        iteration = (int) stack.get(i).counter;
                        iterations = (int) (outer instanceof Node.Repeat ? ((Node.Repeat) outer).times() : maxPasses((Node.Loop) outer));
                        break;
                    }
                }
                return new Leaf(n, f.path, iteration, iterations);
            }
        }
        return null;
    }

    private void startPass(Frame f, Node.Loop l) {
        f.counter++;
        f.takenAtPassStart = taken;
        f.checkFrom = 0;
        stack.add(new Frame(l.body(), f.path + ".body"));
    }

    /** A loop's own limit, or the operator's. */
    private long maxPasses(Node.Loop l) {
        return l.maxIterations() > 0 ? Math.min(l.maxIterations(), limits.maxIterations()) : limits.maxIterations();
    }
}
