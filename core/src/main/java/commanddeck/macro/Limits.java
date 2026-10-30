package commanddeck.macro;

/**
 * The operator's macro limits. The game adapter reads the real values from CommandDeck's settings; these
 * defaults apply until it does. {@code maxRunningConsole} (1.1) is the console's run limit, in place of
 * {@code maxRunningPerPlayer}.
 */
public record Limits(int maxIterations, int maxDepth, int maxRuntimeSeconds, int stepsPerTick, int maxRunningPerPlayer,
                     int maxRunningConsole) {
    public static final Limits DEFAULTS = new Limits(100, 8, 300, 1, 1, 4);

    /** The 1.0 limits, with the console's default limit. */
    public Limits(int maxIterations, int maxDepth, int maxRuntimeSeconds, int stepsPerTick, int maxRunningPerPlayer) {
        this(maxIterations, maxDepth, maxRuntimeSeconds, stepsPerTick, maxRunningPerPlayer, 4);
    }

    public Limits withMaxIterations(int v) {
        return new Limits(v, maxDepth, maxRuntimeSeconds, stepsPerTick, maxRunningPerPlayer, maxRunningConsole);
    }

    public Limits withMaxDepth(int v) {
        return new Limits(maxIterations, v, maxRuntimeSeconds, stepsPerTick, maxRunningPerPlayer, maxRunningConsole);
    }

    public Limits withMaxRuntimeSeconds(int v) {
        return new Limits(maxIterations, maxDepth, v, stepsPerTick, maxRunningPerPlayer, maxRunningConsole);
    }

    public Limits withStepsPerTick(int v) {
        return new Limits(maxIterations, maxDepth, maxRuntimeSeconds, v, maxRunningPerPlayer, maxRunningConsole);
    }

    public Limits withMaxRunningPerPlayer(int v) {
        return new Limits(maxIterations, maxDepth, maxRuntimeSeconds, stepsPerTick, v, maxRunningConsole);
    }

    public Limits withMaxRunningConsole(int v) {
        return new Limits(maxIterations, maxDepth, maxRuntimeSeconds, stepsPerTick, maxRunningPerPlayer, v);
    }
}
