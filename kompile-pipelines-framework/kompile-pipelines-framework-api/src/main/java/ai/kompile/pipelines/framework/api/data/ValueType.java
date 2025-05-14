package ai.kompile.pipelines.framework.api.data;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Defines the possible types of values that can be stored within a {@link Data} object.
 * This enum helps in type checking, serialization, and ensuring data consistency
 * across pipeline steps.
 */
public enum ValueType implements Serializable {
    BOOLEAN,
    STRING,
    INT64, // Using INT64 to align with common data interchange formats like Protobuf (long in Java)
    DOUBLE,
    BYTES,   // Raw byte array
    IMAGE,
    NDARRAY,
    NONE,
    POINT,
    BOUNDING_BOX,
    LIST,    // Represents a list of one of the other ValueTypes
    DATA;    // Represents a nested Data object

    private static final Map<String, ValueType> NAME_MAP = new HashMap<>();

    static {
        for (ValueType vt : values()) {
            NAME_MAP.put(vt.name().toUpperCase(), vt);
        }
    }

    /**
     * Case-insensitive lookup of ValueType by its name.
     * @param name The string name of the ValueType.
     * @return The corresponding ValueType, or null if not found.
     */
    @JsonCreator
    public static ValueType fromString(@JsonProperty("valueType") String name) {
        if (name == null) {
            return null;
        }
        return NAME_MAP.get(name.toUpperCase());
    }
}