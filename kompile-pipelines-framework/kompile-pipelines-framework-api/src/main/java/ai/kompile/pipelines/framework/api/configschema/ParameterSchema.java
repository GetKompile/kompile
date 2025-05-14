/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.pipelines.framework.api.configschema;

import ai.kompile.pipelines.framework.api.Configuration;
import ai.kompile.pipelines.framework.api.data.ValueType;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;
import lombok.Singular; // For @Singular with collections in builder

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Defines the schema for a single parameter within a {@link ai.kompile.pipelines.framework.api.StepConfig}.
 * This class is used to describe expected parameters for a {@link ai.kompile.pipelines.framework.api.PipelineStepRunner},
 * enabling validation, documentation, default value application, and tooling support (like CLI help text).
 * <p>
 * Instances of this class are typically part of a {@link StepSchema}.
 */
@Getter
@Builder
public class ParameterSchema implements Configuration { // Implements Configuration for serialization
    private static final long serialVersionUID = 1L;

    /**
     * The name of the parameter (key used in the parameters {@link ai.kompile.pipelines.framework.api.data.Data} object).
     * This should ideally match a public static final String constant defined by the runner.
     */
    private final String name;

    /**
     * A human-readable description of the parameter, its purpose, and usage.
     */
    private final String description;

    /**
     * The expected {@link ValueType} of the parameter.
     */
    private final ValueType type;

    /**
     * Indicates if this parameter is mandatory. Defaults to {@code false}.
     */
    private final boolean required;

    /**
     * The default value for this parameter if it's not provided by the user and {@code required} is false.
     * Stored as {@link Object}; the actual type should be compatible with the specified {@code type}
     * and {@code listElementType}. Type conversion or casting will be handled by the configuration accessor.
     */
    private final Object defaultValue;

    /**
     * For parameters of type {@link ValueType#STRING}, this optional list specifies
     * the set of allowed string values (enum-like behavior).
     */
    @Singular // Lombok builder will generate 'allowedValue(String)' and 'allowedValues(List<String>)'
    private final List<String> allowedValues;

    /**
     * If {@code type} is {@link ValueType#LIST}, this specifies the expected
     * {@link ValueType} of the elements within that list.
     * Must be null if {@code type} is not {@link ValueType#LIST}.
     */
    private final ValueType listElementType;

    /**
     * If {@code type} is {@link ValueType#DATA}, this optional field can provide a reference
     * (e.g., a fully qualified name or path) to another {@link StepSchema} definition
     * that describes the expected structure of the nested {@link ai.kompile.pipelines.framework.api.data.Data} object.
     */
    private final String nestedSchemaRef;

    @JsonCreator
    public ParameterSchema(
            @JsonProperty(value = "name", required = true) String name,
            @JsonProperty("description") String description,
            @JsonProperty(value = "type", required = true) ValueType type,
            @JsonProperty("required") Boolean required, // Use Boolean to allow null -> map to false
            @JsonProperty("defaultValue") Object defaultValue,
            @JsonProperty("allowedValues") List<String> allowedValues,
            @JsonProperty("listElementType") ValueType listElementType,
            @JsonProperty("nestedSchemaRef") String nestedSchemaRef) {

        this.name = Objects.requireNonNull(name, "ParameterSchema 'name' cannot be null.");
        this.description = description; // Description can be null
        this.type = Objects.requireNonNull(type, "ParameterSchema 'type' cannot be null for parameter: " + name);
        this.required = (required != null) ? required : false;
        this.defaultValue = defaultValue; // Default value can be null
        this.allowedValues = (allowedValues != null) ? Collections.unmodifiableList(new ArrayList<>(allowedValues)) : Collections.emptyList();
        this.listElementType = listElementType;
        this.nestedSchemaRef = nestedSchemaRef;

        // Validation for listElementType
        if (this.type == ValueType.LIST && this.listElementType == null) {
            throw new IllegalArgumentException("ParameterSchema 'listElementType' must be specified when 'type' is LIST for parameter: " + name);
        }
        if (this.type != ValueType.LIST && this.listElementType != null) {
            throw new IllegalArgumentException("ParameterSchema 'listElementType' should only be specified when 'type' is LIST for parameter: " + name);
        }
        // Validation for nestedSchemaRef
        if (this.type != ValueType.DATA && this.nestedSchemaRef != null) {
            throw new IllegalArgumentException("ParameterSchema 'nestedSchemaRef' should only be specified when 'type' is DATA for parameter: " + name);
        }
    }

    /**
     * Helper to get the default value, potentially performing type casting if necessary.
     * This is a simple version; a more robust one might involve the {@link ValueType}
     * for safer casting or conversion.
     *
     * @param <T> The expected type of the default value.
     * @return The default value cast to T, or null.
     * @throws ClassCastException if the defaultValue cannot be cast to T.
     */
    @SuppressWarnings("unchecked")
    public <T> T getDefaultTypedValue() {
        // Basic casting. A more sophisticated system might use the 'type' and 'listElementType'
        // to perform more robust conversion, e.g., ensuring a List default matches List<CorrectType>.
        // For now, this relies on the schema definition being correct.
        return (T) defaultValue;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ParameterSchema that = (ParameterSchema) o;
        return required == that.required &&
                name.equals(that.name) &&
                Objects.equals(description, that.description) &&
                type == that.type &&
                Objects.equals(defaultValue, that.defaultValue) &&
                Objects.equals(allowedValues, that.allowedValues) &&
                listElementType == that.listElementType &&
                Objects.equals(nestedSchemaRef, that.nestedSchemaRef);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, description, type, required, defaultValue, allowedValues, listElementType, nestedSchemaRef);
    }

    @Override
    public String toString() {
        return "ParameterSchema{" +
                "name='" + name + '\'' +
                ", type=" + type +
                (type == ValueType.LIST && listElementType != null ? "<" + listElementType + ">" : "") +
                ", required=" + required +
                (defaultValue != null ? ", defaultValue=" + defaultValue : "") +
                (description != null ? ", description='" + description + '\'' : "") +
                (!allowedValues.isEmpty() ? ", allowedValues=" + allowedValues : "") +
                (nestedSchemaRef != null ? ", nestedSchemaRef='" + nestedSchemaRef + '\'' : "") +
                '}';
    }
}