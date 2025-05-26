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

package ai.kompile.pipelines.framework.api;

import ai.kompile.pipelines.framework.api.data.Data; // Though not directly used in method signatures here, conceptually relevant.
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * Represents a data processing pipeline, which is a defined sequence or graph of executable steps.
 * Each step in the pipeline is represented by a {@link StepConfig}.
 *
 * A Pipeline is essentially a configuration that, when executed by a {@link PipelineExecutor},
 * takes an input {@link Data} object, processes it through its configured steps,
 * and produces an output {@link Data} object.
 *
 * This interface itself implements {@link Configuration}, meaning a Pipeline definition
 * can be serialized to/from JSON/YAML and can be part of larger configurations.
 * Different types of pipelines (e.g., {@code SequencePipeline}, {@code GraphPipeline})
 * will implement this interface.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
public interface Pipeline extends Configuration {

    /**
     * Returns an immutable list of {@link StepConfig} objects that define the steps of this pipeline.
     * The order of steps is significant for sequential pipelines. For graph-based pipelines,
     * this list might represent all configured nodes, and their connectivity is defined elsewhere
     * (typically within the concrete Pipeline implementation's structure, often parsed from the StepConfig list itself).
     *
     * @return A non-null, possibly empty, list of step configurations.
     */
    @JsonProperty("steps")
    List<StepConfig> getSteps();

    /**
     * Creates and initializes an executor for this pipeline. The executor is responsible for
     * managing the lifecycle of {@link PipelineStepRunner}s (instantiating them from {@link StepConfig}
     * and calling their init methods) and running the pipeline's data flow.
     * <p>
     * Each call to this method may return a new {@link PipelineExecutor} instance,
     * or a cached one if the pipeline implementation supports it and it's appropriate.
     * The returned executor should be ready for use (i.e., its internal runners should be initialized).
     *
     * @return A {@link PipelineExecutor} instance for this pipeline.
     * @throws IllegalStateException if the pipeline configuration is invalid and an executor cannot be created.
     * @throws Exception if any other error occurs during executor creation or initialization of its steps
     * (e.g., failure to load models, resource allocation issues for runners).
     */
    PipelineExecutor createExecutor() throws Exception;

    /**
     * Validates the pipeline configuration.
     * This method should be called after a Pipeline object is constructed or deserialized
     * to ensure its internal consistency and the validity of its {@link StepConfig} list.
     * <p>
     * Validation checks may include:
     * <ul>
     * <li>Ensuring all {@link StepConfig} entries have a valid {@code runnerClassName}.</li>
     * <li>Checking if runners specified by {@code runnerClassName} can be found/loaded.</li>
     * <li>For graph pipelines, verifying graph connectivity (e.g., no orphaned steps, valid inputs/outputs).</li>
     * <li>Potentially, basic schema validation for step parameters if schemas are available at this stage.</li>
     * </ul>
     *
     * @throws IllegalStateException if the pipeline configuration is found to be invalid.
     * The exception message should provide details about the validation failure.
     */
    void validate() throws IllegalStateException;

    /**
     * Returns a unique identifier for this pipeline instance, if one has been assigned.
     * This ID can be used for logging, tracking, or managing multiple pipeline instances.
     *
     * @return A string ID, or null if no ID is explicitly set for this pipeline.
     * Implementations might generate a default ID if not provided.
     */
    @JsonProperty("id")
    String id();
}