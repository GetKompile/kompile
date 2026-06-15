/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.pipelines.framework.core.config;

import ai.kompile.pipelines.framework.api.configschema.ParameterSchema;
import ai.kompile.pipelines.framework.api.configschema.StepSchema;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.data.ValueType;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Utility class for accessing parameters from a {@link Data} object
 * based on a provided {@link StepSchema}. It handles:
 * <ul>
 * <li>Validation of required parameters.</li>
 * <li>Type checking of provided parameters against the schema.</li>
 * <li>Application of default values for optional parameters from the schema.</li>
 * <li>Validation against allowed values if specified in the schema.</li>
 * <li>Convenience methods for typed parameter retrieval.</li>
 * </ul>
 * This class is intended to be used by {@link ai.kompile.pipelines.framework.api.PipelineStepRunner}
 * implementations during their {@code init} phase.
 */

public class ConfigAccessor {
    private final Data parameters; // This will be the Data object after defaults are applied.
    private final StepSchema schema;
    private final String runnerClassName; // For more informative error messages

    /**
     * Creates a ConfigAccessor and immediately applies default values and validates parameters.
     * The provided {@code parametersFromConfig} Data object will be duplicated, and defaults
     * will be applied to this copy.
     *
     * @param parametersFromConfig The raw {@link Data} object from {@link ai.kompile.pipelines.framework.api.StepConfig#getParameters()}. Must not be null.
     * @param schema The {@link StepSchema} for the runner. Must not be null.
     * @throws NullPointerException if parametersFromConfig or schema is null.
     * @throws IllegalArgumentException if validation against the schema fails.
     */
    public ConfigAccessor(@lombok.NonNull Data parametersFromConfig, @lombok.NonNull StepSchema schema) {
        this.schema = schema;
        this.runnerClassName = schema.getRunnerClassName(); // Store for logging
        this.parameters = parametersFromConfig.dup(); // Work on a mutable copy

        applyDefaultValues();
        validateParameters();
    }

    /**
     * Applies default values specified in the schema to the internal parameters Data object
     * if the parameters are not already present.
     */
    private void applyDefaultValues() {
        for (ParameterSchema paramSchema : schema.getParameters()) {
            if (!parameters.has(paramSchema.getName())) { // Only apply if not present
                Object defaultValue = paramSchema.getDefaultTypedValue(); // Assumes this method gives correct type
                if (defaultValue != null) {
                    parameters.put(paramSchema.getName(), defaultValue);
                }
            }
        }
    }

    /**
     * Validates the internal parameters Data object against the StepSchema.
     * Checks for required parameters, type compatibility, and allowed values.
     *
     * @throws IllegalArgumentException if any validation check fails.
     */
    private void validateParameters() {
        for (ParameterSchema paramSchema : schema.getParameters()) {
            String paramName = paramSchema.getName();

            // 1. Check for required parameters
            if (paramSchema.isRequired() && !parameters.has(paramName)) {
                throw new IllegalArgumentException(String.format(
                        "Runner '%s': Missing required parameter '%s'. Description: %s",
                        runnerClassName, paramName, paramSchema.getDescription()));
            }

            // If parameter is present, perform further checks
            if (parameters.has(paramName)) {
                Object rawValue = parameters.get(paramName); // Get the raw object from Data
                ValueType actualType = parameters.type(paramName); // Get type from Data store
                ValueType expectedType = paramSchema.getType();

                // 2. Check for null if required (even after defaults, if default was null for a required param, it's an issue)
                if (paramSchema.isRequired() && rawValue == null) {
                    throw new IllegalArgumentException(String.format(
                            "Runner '%s': Required parameter '%s' has a null value after applying defaults (if any). Schema expects type %s. Description: %s",
                            runnerClassName, paramName, expectedType, paramSchema.getDescription()));
                }

                // 3. Type compatibility check (only if value is not null)
                if (rawValue != null && expectedType != null && actualType != expectedType) {
                    boolean compatible = false;
                    // Allow some implicit conversions that are generally safe or expected by Jackson
                    if (expectedType == ValueType.DOUBLE && actualType == ValueType.INT64) compatible = true;
                    if (expectedType == ValueType.INT64 && actualType == ValueType.STRING && rawValue.toString().matches("-?\\d+")) compatible = true; // String to Long
                    if (expectedType == ValueType.DOUBLE && actualType == ValueType.STRING && isParsableAsDouble(rawValue.toString())) compatible = true; // String to Double
                    if (expectedType == ValueType.BOOLEAN && actualType == ValueType.STRING && isParsableAsBoolean(rawValue.toString())) compatible = true; // String to Boolean
                    if (expectedType == ValueType.STRING) compatible = true; // Most types can be reasonably stringified

                    if (!compatible) {
                        if (expectedType == ValueType.LIST) {
                            ValueType expectedListElementType = paramSchema.getListElementType();
                            ValueType actualListElementType = parameters.listType(paramName);
                            if (expectedListElementType != null && actualListElementType != null && actualListElementType != expectedListElementType) {
                                throw new IllegalArgumentException(String.format(
                                        "Runner '%s': Parameter '%s' list element type mismatch. Expected elements of type: %s, Found elements of type: %s. Description: %s",
                                        runnerClassName, paramName, expectedListElementType, actualListElementType, paramSchema.getDescription()));
                            }
                            // If actualListElementType is null (e.g., empty list from user, or mixed types not yet strictly typed in JData.put(Object))
                            // and expectedListElementType is not null, this might be a warning or an error depending on strictness.
                            // For now, we assume JData.putList would have set listElementType if it was a typed list.
                        } else {
                            throw new IllegalArgumentException(String.format(
                                    "Runner '%s': Parameter '%s' type mismatch. Expected: %s, Found: %s. Value snippet: '%s'. Description: %s",
                                    runnerClassName, paramName, expectedType, actualType,
                                    rawValue.toString().substring(0, Math.min(50, rawValue.toString().length())),
                                    paramSchema.getDescription()));
                        }
                    }
                }

                // 4. Allowed values check (primarily for STRING type)
                if (paramSchema.getAllowedValues() != null && !paramSchema.getAllowedValues().isEmpty()) {
                    // This check is most meaningful for STRING types, but can be extended.
                    if (actualType == ValueType.STRING) {
                        String strValue = parameters.getString(paramName);
                        if (strValue != null && !paramSchema.getAllowedValues().contains(strValue)) {
                            throw new IllegalArgumentException(String.format(
                                    "Runner '%s': Parameter '%s' has value '%s' which is not in allowed values: %s. Description: %s",
                                    runnerClassName, paramName, strValue, paramSchema.getAllowedValues(), paramSchema.getDescription()));
                        }
                    }
                }

                // 5. Nested Schema Validation (if type is DATA and nestedSchemaRef is present)
                if (expectedType == ValueType.DATA && paramSchema.getNestedSchemaRef() != null && rawValue instanceof Data) {
                    String nestedSchemaId = paramSchema.getNestedSchemaRef();
                    Optional<StepSchema> nestedSchemaOpt = SchemaRegistry.getInstance().getSchema(nestedSchemaId); // Assuming nested schema ID can be a runner class name or some other ID
                    if (nestedSchemaOpt.isPresent()) {
                        new ConfigAccessor((Data) rawValue, nestedSchemaOpt.get()); // Recursive validation
                    }
                }
            }
        }

    }

    // --- Helper methods for type parsing during validation ---
    private boolean isParsableAsDouble(String s) {
        if (s == null) return false;
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isParsableAsBoolean(String s) {
        if (s == null) return false;
        return "true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s);
    }


    // --- Typed Getters ---
    // These getters assume validation and default application have already occurred.
    // They retrieve from the (potentially modified by defaults) internal 'parameters' Data object.

    public String getString(String key) {
        return parameters.getString(key);
    }
    public String getString(String key, String defaultValue) {
        // The default from schema should have been applied already if key was missing.
        // This secondary default is if the key is present but value is null, or if schema had no default.
        return parameters.getString(key, defaultValue);
    }

    public Long getInt64(String key) {
        return parameters.getInt64(key);
    }
    public Long getInt64(String key, Long defaultValue) {
        return parameters.getInt64(key, defaultValue);
    }

    public Integer getInt32(String key) {
        return parameters.getInt32(key);
    }
    public Integer getInt32(String key, Integer defaultValue) {
        return parameters.getInt32(key, defaultValue);
    }

    public Double getDouble(String key) {
        return parameters.getDouble(key);
    }
    public Double getDouble(String key, Double defaultValue) {
        return parameters.getDouble(key, defaultValue);
    }

    public Float getFloat(String key) {
        return parameters.getFloat(key);
    }
    public Float getFloat(String key, Float defaultValue) {
        return parameters.getFloat(key, defaultValue);
    }

    public Boolean getBoolean(String key) {
        return parameters.getBoolean(key);
    }
    public Boolean getBoolean(String key, Boolean defaultValue) {
        return parameters.getBoolean(key, defaultValue);
    }

    public byte[] getBytes(String key) {
        return parameters.getBytes(key);
    }
    public byte[] getBytes(String key, byte[] defaultValue) {
        return parameters.getBytes(key, defaultValue);
    }

    public Data getData(String key) {
        return parameters.getData(key);
    }
    public Data getData(String key, Data defaultValue) {
        return parameters.getData(key, defaultValue);
    }

    /**
     * Retrieves a list of the specified element type.
     * Assumes the schema has defined {@link ParameterSchema#getListElementType()}.
     *
     * @param key The parameter key.
     * @param listElementType The expected {@link ValueType} of elements in the list.
     * @param <T> The Java type corresponding to listElementType.
     * @return The list, or an empty list if not found (or null, depending on Data impl).
     */
    public <T> List<T> getList(String key, ValueType listElementType) {
        return parameters.getList(key, listElementType, Collections.emptyList());
    }
    public <T> List<T> getList(String key, ValueType listElementType, List<T> defaultValue) {
        return parameters.getList(key, listElementType, defaultValue);
    }

    // Convenience methods for common list types
    public List<String> getStringList(String key) {
        return getList(key, ValueType.STRING, Collections.emptyList());
    }
    public List<String> getStringList(String key, List<String> defaultValue) {
        return getList(key, ValueType.STRING, defaultValue);
    }

    public List<Long> getInt64List(String key) {
        return getList(key, ValueType.INT64, Collections.emptyList());
    }
    public List<Long> getInt64List(String key, List<Long> defaultValue) {
        return getList(key, ValueType.INT64, defaultValue);
    }

    public List<Integer> getInt32List(String key) {
        List<Long> longList = getList(key, ValueType.INT64, Collections.emptyList());
        if (longList == null) return Collections.emptyList();
        return longList.stream().map(l -> {
            if (l > Integer.MAX_VALUE || l < Integer.MIN_VALUE) throw new ClassCastException("Long value " + l + " out of Integer range for list " + key);
            return l.intValue();
        }).collect(Collectors.toList());
    }
    public List<Integer> getInt32List(String key, List<Integer> defaultValue) {
        List<Long> longList = parameters.getList(key, ValueType.INT64);
        if (longList == null) return defaultValue;
        try {
            return longList.stream().map(l -> {
                if (l > Integer.MAX_VALUE || l < Integer.MIN_VALUE) throw new ClassCastException("Long value " + l + " out of Integer range for list " + key);
                return l.intValue();
            }).collect(Collectors.toList());
        } catch (ClassCastException e) {
            return defaultValue;
        }
    }


    public List<Double> getDoubleList(String key) {
        return getList(key, ValueType.DOUBLE, Collections.emptyList());
    }
    public List<Double> getDoubleList(String key, List<Double> defaultValue) {
        return getList(key, ValueType.DOUBLE, defaultValue);
    }

    public List<Boolean> getBooleanList(String key) {
        return getList(key, ValueType.BOOLEAN, Collections.emptyList());
    }
    public List<Boolean> getBooleanList(String key, List<Boolean> defaultValue) {
        return getList(key, ValueType.BOOLEAN, defaultValue);
    }

    public List<Data> getDataList(String key) {
        return getList(key, ValueType.DATA, Collections.emptyList());
    }
    public List<Data> getDataList(String key, List<Data> defaultValue) {
        return getList(key, ValueType.DATA, defaultValue);
    }


    /**
     * Returns the fully validated and potentially default-applied parameters.
     * This should be used by runners after ConfigAccessor instantiation.
     * @return The {@link Data} object holding the effective parameters.
     */
    public Data getProcessedParameters() {
        return this.parameters;
    }
}