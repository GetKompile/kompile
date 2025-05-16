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
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;
import lombok.Singular;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Getter
@Builder
public class StepSchema implements Configuration {
    private static final long serialVersionUID = 1L;

    /**
     * The symbolic name/type of the step, matching PipelineStepRunnerFactory.stepTypeName().
     * E.g., "DL4J_LANGUAGE_MODEL".
     */
    private final String name; // Added for feature parity with factory usage

    /**
     * The fully qualified class name of the {@link ai.kompile.pipelines.framework.api.PipelineStepRunner}
     * to which this schema applies.
     */
    private final String runnerClassName;

    /**
     * A human-readable description of the pipeline step itself (what it does).
     */
    private final String description;

    /**
     * The fully qualified class name of the {@link ai.kompile.pipelines.framework.api.StepConfig}
     * implementation this schema describes (e.g., LLMStepConfig.class.getName()).
     */
    private final String configClass; // Added for feature parity

    /**
     * An immutable list of {@link ParameterSchema} objects, each describing an
     * expected configuration parameter for this step.
     */
    @Singular("parameter")
    private final List<ParameterSchema> parameters;

    /**
     * An immutable list of {@link ParameterSchema} objects, each describing an
     * expected input Data key for this step's exec() method.
     */
    @Singular("input")
    private final List<ParameterSchema> inputs; // Added for feature parity

    /**
     * An immutable list of {@link ParameterSchema} objects, each describing an
     * potential output Data key from this step's exec() method.
     */
    @Singular("output")
    private final List<ParameterSchema> outputs; // Added for feature parity

    @JsonCreator
    public StepSchema(
            @JsonProperty(value = "name", required = true) String name, // Added
            @JsonProperty(value = "runnerClassName", required = true) String runnerClassName,
            @JsonProperty("description") String description,
            @JsonProperty("configClass") String configClass, // Added
            @JsonProperty("parameters") List<ParameterSchema> parameters,
            @JsonProperty("inputs") List<ParameterSchema> inputs, // Added
            @JsonProperty("outputs") List<ParameterSchema> outputs) { // Added
        this.name = Objects.requireNonNull(name, "StepSchema 'name' (symbolic type) cannot be null.");
        this.runnerClassName = Objects.requireNonNull(runnerClassName, "StepSchema 'runnerClassName' cannot be null.");
        this.description = description;
        this.configClass = configClass; // Can be null if not strictly needed by all tools using StepSchema
        this.parameters = (parameters != null) ? Collections.unmodifiableList(new ArrayList<>(parameters)) : Collections.emptyList();
        this.inputs = (inputs != null) ? Collections.unmodifiableList(new ArrayList<>(inputs)) : Collections.emptyList();
        this.outputs = (outputs != null) ? Collections.unmodifiableList(new ArrayList<>(outputs)) : Collections.emptyList();
    }

    public Optional<ParameterSchema> getParameterSchema(String parameterName) {
        if (parameterName == null) {
            return Optional.empty();
        }
        return parameters.stream()
                .filter(p -> parameterName.equals(p.getName()))
                .findFirst();
    }
    public Optional<ParameterSchema> getInputSchema(String inputName) {
        if (inputName == null) {
            return Optional.empty();
        }
        return inputs.stream()
                .filter(p -> inputName.equals(p.getName()))
                .findFirst();
    }

    public Optional<ParameterSchema> getOutputSchema(String outputName) {
        if (outputName == null) {
            return Optional.empty();
        }
        return outputs.stream()
                .filter(p -> outputName.equals(p.getName()))
                .findFirst();
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StepSchema that = (StepSchema) o;
        return Objects.equals(name, that.name) && // Added
                runnerClassName.equals(that.runnerClassName) &&
                Objects.equals(description, that.description) &&
                Objects.equals(configClass, that.configClass) && // Added
                Objects.equals(parameters, that.parameters) &&
                Objects.equals(inputs, that.inputs) && // Added
                Objects.equals(outputs, that.outputs); // Added
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, runnerClassName, description, configClass, parameters, inputs, outputs); // Added
    }

    @Override
    public String toString() {
        return "StepSchema{" +
                "name='" + name + '\'' + // Added
                ", runnerClassName='" + runnerClassName + '\'' +
                (description != null ? ", description='" + description + '\'' : "") +
                (configClass != null ? ", configClass='" + configClass + '\'' : "") + // Added
                ", parameters=" + parameters +
                ", inputs=" + inputs + // Added
                ", outputs=" + outputs + // Added
                '}';
    }
}