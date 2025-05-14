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
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;
import lombok.Singular; // For Lombok builder with lists

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Defines the schema for a {@link ai.kompile.pipelines.framework.api.PipelineStepRunner}'s
 * configuration parameters. This schema is typically deserialized from a JSON/YAML resource file
 * associated with the runner, providing a structured way to define expected parameters,
 * their types, descriptions, and validation rules (like 'required' or 'allowedValues').
 * <p>
 * The {@link SchemaRegistry} (in framework-core) is responsible for loading these schemas.
 */
@Getter
@Builder
public class StepSchema implements Configuration { // Implements Configuration for serialization
    private static final long serialVersionUID = 1L;

    /**
     * The fully qualified class name of the {@link ai.kompile.pipelines.framework.api.PipelineStepRunner}
     * to which this schema applies. This should match the {@code runnerClassName}
     * field in a {@link ai.kompile.pipelines.framework.api.StepConfig}.
     */
    private final String runnerClassName;

    /**
     * A human-readable description of the pipeline step itself (what it does).
     */
    private final String description;

    /**
     * An immutable list of {@link ParameterSchema} objects, each describing an
     * expected parameter for this step.
     */
    @Singular // For Lombok builder: .parameter(param1).parameter(param2) and .parameters(listOfParams)
    private final List<ParameterSchema> parameters;

    @JsonCreator
    public StepSchema(
            @JsonProperty(value = "runnerClassName", required = true) String runnerClassName,
            @JsonProperty("description") String description,
            @JsonProperty("parameters") List<ParameterSchema> parameters) {
        this.runnerClassName = Objects.requireNonNull(runnerClassName, "StepSchema 'runnerClassName' cannot be null.");
        this.description = description; // Description can be null
        this.parameters = (parameters != null) ? Collections.unmodifiableList(new ArrayList<>(parameters)) : Collections.emptyList();
    }

    /**
     * Retrieves the schema definition for a specific parameter by its name.
     *
     * @param parameterName The name of the parameter.
     * @return An {@link Optional} containing the {@link ParameterSchema} if found,
     * otherwise an empty Optional.
     */
    public Optional<ParameterSchema> getParameterSchema(String parameterName) {
        if (parameterName == null) {
            return Optional.empty();
        }
        return parameters.stream()
                .filter(p -> parameterName.equals(p.getName()))
                .findFirst();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StepSchema that = (StepSchema) o;
        return runnerClassName.equals(that.runnerClassName) &&
                Objects.equals(description, that.description) &&
                Objects.equals(parameters, that.parameters); // List.equals checks order and content
    }

    @Override
    public int hashCode() {
        return Objects.hash(runnerClassName, description, parameters);
    }

    @Override
    public String toString() {
        return "StepSchema{" +
                "runnerClassName='" + runnerClassName + '\'' +
                (description != null ? ", description='" + description + '\'' : "") +
                ", parameters=" + parameters +
                '}';
    }
}