package hledjolf.conditions.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * A read-only view of a parsed condition object, so evaluators don't depend on anyone's JSON classes. List items
 * are {@code String}, {@code BigDecimal}, {@code Boolean}, {@code JsonObjectView}, {@code List} or null. Each
 * getter gives null when the field is absent or of another kind.
 */
public interface JsonObjectView {
    Set<String> fields();

    boolean has(String field);

    String string(String field);

    BigDecimal number(String field);

    Boolean bool(String field);

    List<Object> list(String field);

    JsonObjectView object(String field);

    /** The object as compact JSON text. */
    String json();
}
