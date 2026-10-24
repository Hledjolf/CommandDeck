package commanddeck.macro;

/**
 * The operator's macro limits. The game adapter reads the real values from CommandDeck's settings; these
 * defaults apply until it does.
 */
public record Limits(int maxIterations, int maxDepth, int maxRuntimeSeconds, int stepsPerTick, int maxRunningPerPlayer) {
    public static final Limits DEFAULTS = new Limits(100, 8, 300, 1, 1);

    public Limits withMaxIterations(int v) {
        return new Limits(v, maxDepth, maxRuntimeSeconds, stepsPerTick, maxRunningPerPlayer);
    }

    public Limits withMaxDepth(int v) {
        return new Limits(maxIterations, v, maxRuntimeSeconds, stepsPerTick, maxRunningPerPlayer);
    }

    public Limits withMaxRuntimeSeconds(int v) {
        return new Limits(maxIterations, maxDepth, v, stepsPerTick, maxRunningPerPlayer);
    }

    public Limits withStepsPerTick(int v) {
        return new Limits(maxIterations, maxDepth, maxRuntimeSeconds, v, maxRunningPerPlayer);
    }

    public Limits withMaxRunningPerPlayer(int v) {
        return new Limits(maxIterations, maxDepth, maxRuntimeSeconds, stepsPerTick, v);
    }
}
