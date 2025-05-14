package ai.kompile.pipelines.framework.api.data;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Defines the underlying data type of an N-Dimensional Array (NDArray).
 * This is used within the {@link NDArray} class to specify the precision
 * and type of its elements.
 */
public enum NDArrayType implements Serializable {
    // Standard Java types
    BOOLEAN, // Typically represented as 0s and 1s
    BYTE,    // Corresponds to int8 or uint8 depending on signedness context
    SHORT,   // int16
    INT,     // int32
    LONG,    // int64
    FLOAT,   // float32
    DOUBLE,  // float64
    UTF8,    // For NDArrays of strings (less common, but possible in some frameworks)

    // Explicitly sized integer types for clarity, mapping to common native types
    INT8,
    UINT8,
    INT16,
    UINT16,
    INT32,
    UINT32,
    // INT64 is already covered by LONG from standard Java types
    UINT64,

    // Explicitly sized float types
    FLOAT16, // Half-precision float
    // FLOAT32 is covered by FLOAT
    // FLOAT64 is covered by DOUBLE
    BFLOAT16; // Brain Floating Point format


    private static final Map<String, NDArrayType> NAME_MAP = new HashMap<>();

    static {
        for (NDArrayType nt : values()) {
            NAME_MAP.put(nt.name().toUpperCase(), nt);
        }
    }

    /**
     * Case-insensitive lookup of NDArrayType by its name.
     * @param name The string name of the NDArrayType.
     * @return The corresponding NDArrayType, or null if not found.
     */
    @JsonCreator
    public static NDArrayType fromString(@JsonProperty("ndArrayType") String name) {
        if (name == null) {
            return null;
        }
        return NAME_MAP.get(name.toUpperCase());
    }
}