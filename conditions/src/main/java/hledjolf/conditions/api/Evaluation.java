package hledjolf.conditions.api;

/** What an evaluation gives: the answer, and for UNKNOWN why, naming the condition's JSON Pointer. */
public record Evaluation(Answer answer, String reason) {
    public Evaluation {
        if (answer == null) throw new IllegalArgumentException("answer");
    }

    public static Evaluation of(boolean b) {
        return new Evaluation(Answer.of(b), null);
    }

    public static Evaluation of(Answer a) {
        return new Evaluation(a, null);
    }

    public static Evaluation unknown(String reason) {
        return new Evaluation(Answer.UNKNOWN, reason);
    }
}
