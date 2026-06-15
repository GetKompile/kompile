package ai.kompile.compute.graph.drools;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A named fact for fine-grained rule matching.
 * Each input variable is inserted as a separate NamedFact so rules
 * can match on individual values by name and type.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NamedFact {

    private String name;
    private Object value;

    /**
     * Get value as a specific type.
     */
    @SuppressWarnings("unchecked")
    public <T> T getValueAs(Class<T> type) {
        if (value == null) return null;
        if (type.isInstance(value)) return (T) value;
        if (type == Double.class && value instanceof Number) {
            return (T) Double.valueOf(((Number) value).doubleValue());
        }
        if (type == Integer.class && value instanceof Number) {
            return (T) Integer.valueOf(((Number) value).intValue());
        }
        if (type == String.class) {
            return (T) String.valueOf(value);
        }
        throw new ClassCastException("Cannot cast " + value.getClass().getName() + " to " + type.getName());
    }
}
