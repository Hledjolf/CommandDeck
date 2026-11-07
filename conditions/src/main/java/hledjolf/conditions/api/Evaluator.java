package hledjolf.conditions.api;

/**
 * Answers one registered type. It runs on the game's thread, must return quickly (the operator's budget, 2 ms by
 * default) and must never change the game. One that throws or runs late gives UNKNOWN.
 */
@FunctionalInterface
public interface Evaluator {
    Evaluation evaluate(JsonObjectView condition, Context context);
}
