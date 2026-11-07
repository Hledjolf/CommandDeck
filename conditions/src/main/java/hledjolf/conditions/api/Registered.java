package hledjolf.conditions.api;

/** The result of a registration: ok, or refused with a reason ({@code namespace_taken}, {@code duplicate}, ...). */
public record Registered(boolean ok, String reason) {
    public static Registered accepted() {
        return new Registered(true, null);
    }

    public static Registered refused(String reason) {
        return new Registered(false, reason);
    }
}
