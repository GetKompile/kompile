/*
 *  Copyright 2025 Kompile Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 */

// Location: kompile-pipelines-framework/kompile-pipelines-framework-api/src/main/java/ai/kompile/pipelines/framework/api/configschema/
package ai.kompile.pipelines.framework.api.configschema;

import ai.kompile.pipelines.framework.api.Configuration;
import ai.kompile.pipelines.framework.api.data.ValueType;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;
import lombok.Singular;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Getter
@Builder
public class ParameterSchema implements Configuration {
    private static final long serialVersionUID = 1L;

    private final String name;
    private final String description;
    private final ValueType type;
    private final boolean required;
    private final Object defaultValue;
    @Singular
    private final List<String> allowedValues;
    private final ValueType listElementType; // For type = LIST
    private final String nestedSchemaRef;   // For type = DATA, refers to another StepSchema ID or FQN

    /**
     * If type is {@link ValueType#OBJECT} or {@link ValueType#LIST} with listElementType {@link ValueType#OBJECT},
     * or type is {@link ValueType#DATA} and nestedSchemaRef is not used,
     * this can specify the fully qualified class name of the expected object or map.
     * For lists of complex objects, this would be the FQN of the element type.
     * This provides more specific type information beyond ValueType.OBJECT.
     */
    private final String subTypeClassName; // Added for feature parity with how factories were using it

    @JsonCreator
    public ParameterSchema(
            @JsonProperty(value = "name", required = true) String name,
            @JsonProperty("description") String description,
            @JsonProperty(value = "type", required = true) ValueType type,
            @JsonProperty("required") Boolean required,
            @JsonProperty("defaultValue") Object defaultValue,
            @JsonProperty("allowedValues") List<String> allowedValues,
            @JsonProperty("listElementType") ValueType listElementType,
            @JsonProperty("nestedSchemaRef") String nestedSchemaRef,
            @JsonProperty("subTypeClassName") String subTypeClassName) { // Added new field

        this.name = Objects.requireNonNull(name, "ParameterSchema 'name' cannot be null.");
        this.description = description;
        this.type = Objects.requireNonNull(type, "ParameterSchema 'type' cannot be null for parameter: " + name);
        this.required = (required != null) ? required : false;
        this.defaultValue = defaultValue;
        this.allowedValues = (allowedValues != null) ? Collections.unmodifiableList(new ArrayList<>(allowedValues)) : Collections.emptyList();
        this.listElementType = listElementType;
        this.nestedSchemaRef = nestedSchemaRef;
        this.subTypeClassName = subTypeClassName; // Initialize new field

        // Validations
        if (this.type == ValueType.LIST && this.listElementType == null) {
            throw new IllegalArgumentException("ParameterSchema 'listElementType' must be specified when 'type' is LIST for parameter: " + name);
        }
        if (this.type != ValueType.LIST && this.listElementType != null) {
            throw new IllegalArgumentException("ParameterSchema 'listElementType' should only be specified when 'type' is LIST for parameter: " + name);
        }
        if (this.type != ValueType.DATA && this.nestedSchemaRef != null) {
            throw new IllegalArgumentException("ParameterSchema 'nestedSchemaRef' should only be specified when 'type' is DATA for parameter: " + name);
        }



    }

    @SuppressWarnings("unchecked")
    public <T> T getDefaultTypedValue() {
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
                Objects.equals(nestedSchemaRef, that.nestedSchemaRef) &&
                Objects.equals(subTypeClassName, that.subTypeClassName); // Added
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, description, type, required, defaultValue, allowedValues, listElementType, nestedSchemaRef, subTypeClassName); // Added
    }

    @Override
    public String toString() {
        return "ParameterSchema{" +
                "name='" + name + '\'' +
                ", type=" + type +
                (type == ValueType.LIST && listElementType != null ? "<" + listElementType + (subTypeClassName != null && listElementType == ValueType.OBJECT ? ":" + subTypeClassName : "") + ">" : "") +
                (type == ValueType.OBJECT && subTypeClassName != null ? " (" + subTypeClassName + ")" : "") +
                ", required=" + required +
                (defaultValue != null ? ", defaultValue=" + defaultValue : "") +
                (description != null ? ", description='" + description + '\'' : "") +
                (!allowedValues.isEmpty() ? ", allowedValues=" + allowedValues : "") +
                (nestedSchemaRef != null ? ", nestedSchemaRef='" + nestedSchemaRef + '\'' : "") +
                (subTypeClassName != null && type != ValueType.LIST ? ", subTypeClassName='" + subTypeClassName + '\'' : "") + // Avoid double print for list
                '}';
    }
}