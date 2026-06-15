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

import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.data.DataFactory;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.EqualsAndHashCode;
// import lombok.Getter; // We will implement getters explicitly to satisfy the interface clearly
import lombok.NonNull;
import lombok.ToString;

import java.util.Objects;

/**
 * A generic, concrete implementation of {@link StepConfig}.
 * It holds a {@code runnerClassName} to identify the {@link ai.kompile.pipelines.framework.api.PipelineStepRunner}
 * and a {@link Data} object to store all arbitrary configuration parameters for that runner.
 * This class is designed to be easily serializable to/from JSON/YAML.
 *
 * The {@code @class} property for JSON/YAML will be automatically handled by Jackson
 * due to {@code @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)} on the {@link StepConfig} interface.
 */
@EqualsAndHashCode // Lombok will use runnerClassName and parameters
@ToString // Lombok will use runnerClassName and parameters
@Builder
public class GenericStepConfig implements StepConfig {
    private static final long serialVersionUID = 1L; // From Configuration interface

    private final String runnerClassName;
    private final Data parameters;

    /**
     * Constructor primarily for Jackson deserialization and programmatic creation.
     *
     * @param runnerClassName The fully qualified class name of the PipelineStepRunner. Must not be null.
     * @param parameters A {@link Data} object containing the parameters for the step.
     * If null during deserialization or construction, an empty {@link Data} object will be instantiated.
     */
    @JsonCreator
    public GenericStepConfig(
            @NonNull @JsonProperty(value = "runnerClassName", required = true) String runnerClassName,
            @JsonProperty("parameters") Data parameters) {
        this.runnerClassName = runnerClassName;
        // Ensure parameters is never null after construction.
        // Data.Factory.get().empty() ensures we use the service-loaded Data implementation.
        this.parameters = (parameters != null) ? parameters : Data.empty();
    }

    /**
     * Convenience constructor to create an empty configuration for a given runner.
     * Parameters can be added subsequently using the {@link #put(String, Object)} method
     * or by directly manipulating the {@link #getParameters()} object.
     *
     * @param runnerClassName The fully qualified class name of the PipelineStepRunner. Must not be null.
     */
    public GenericStepConfig(@NonNull String runnerClassName) {
        this(runnerClassName, Data.empty());
    }

    @Override
    public String type() {
        return runnerClassName;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @JsonProperty("runnerClassName") // Ensure it's part of serialization contract if not inferred by field name
    public String runnerClassName() {
        return this.runnerClassName;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @JsonProperty("parameters") // Ensure it's part of serialization contract if not inferred by field name
    public Data getParameters() {
        return this.parameters;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> T get(String key) {
        return parameters.get(key);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> T get(String key, T defaultValue) {
        return parameters.get(key, defaultValue);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public StepConfig put(String key, Object value) {
        parameters.put(key, value);
        return this;
    }
}