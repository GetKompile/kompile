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

// Updated Interface: PipelineStepRunnerFactory.java
// Purpose: To be a factory for PipelineStepRunner instances and also provide their schema.
// Location: kompile-pipelines-framework/kompile-pipelines-framework-api/src/main/java/ai/kompile/pipelines/framework/api/
package ai.kompile.pipelines.framework.api;

import ai.kompile.pipelines.framework.api.configschema.StepSchema;
import java.util.ServiceLoader; // For Javadoc reference

/**
 * A factory responsible for creating instances of {@link PipelineStepRunner} and providing
 * the schema for their configuration.
 * <p>
 * Implementations of this factory typically correspond to a specific type of pipeline step
 * (e.g., a {@code PythonRunnerFactory} for Python steps, a {@code DL4JLanguageModelStepRunnerFactory}
 * for DL4J LLM steps). Each factory knows how to create an instance of its
 * associated runner and describe its configuration parameters.
 * <p>
 * The pipeline runtime (specifically, implementations of {@link PipelineExecutor} and potentially
 * configuration UIs or validation tools) uses these factories to:
 * <ol>
 * <li>Instantiate the appropriate runners based on a step's configured type.</li>
 * <li>Retrieve the schema to understand how to configure the step, validate configurations,
 * or generate documentation/UI for the step.</li>
 * </ol>
 * <p>
 * Factories are typically discovered using Java's {@link ServiceLoader} mechanism.
 * To make a factory discoverable, its fully qualified class name should be listed in a file named
 * {@code META-INF/services/ai.kompile.pipelines.framework.api.PipelineStepRunnerFactory}
 * within the JAR that provides the factory implementation.
 */
public interface PipelineStepRunnerFactory {

    /**
     * Returns the symbolic type name for the kind of pipeline step this factory handles.
     * This type name (e.g., "PYTHON", "DL4J_LANGUAGE_MODEL") is used in pipeline definitions
     * to specify what kind of step to create. It should be unique among all registered factories.
     *
     * @return A non-null, non-empty unique string identifying the step type.
     */
    String stepTypeName();

    /**
     * Returns the fully qualified class name of the {@link PipelineStepRunner}
     * that this factory creates. This name might be used by the pipeline runtime for
     * logging, reflection, or matching against a {@link StepConfig#runnerClassName()} if
     * that field is used for explicit runner binding (though {@link #stepTypeName()} is primary for lookup).
     * <p>
     * The returned string must exactly match the class name of the runner instance
     * returned by {@link #create()}.
     *
     * @return The fully qualified class name of the {@link PipelineStepRunner} this factory produces.
     * Should not be null or empty.
     */
    String getRunnerType(); // This was the original method.

    /**
     * Creates a new, uninitialized instance of the {@link PipelineStepRunner}.
     * <p>
     * The created runner will subsequently have its
     * {@link PipelineStepRunner#init(StepConfig, ai.kompile.pipelines.framework.api.context.Context)}
     * method called by the pipeline runtime before it is used for execution.
     *
     * @return A new, uninitialized {@link PipelineStepRunner} instance.
     * Should not return null.
     * @throws RuntimeException if an instance cannot be created for some reason
     * (though typically, configuration or resource loading issues are handled in the init phase).
     */
    PipelineStepRunner create();

    /**
     * Returns the {@link StepSchema} that describes the configuration parameters,
     * inputs, and outputs for the {@link PipelineStepRunner} created by this factory.
     * <p>
     * This schema is used for configuration validation, UI generation, documentation,
     * and other tooling. The schema should accurately reflect the parameters expected
     * by the runner's {@link StepConfig} (often an instance of {@link ai.kompile.pipelines.framework.api.llm.LLMStepConfig}
     * or {@link ai.kompile.pipelines.framework.core.config.GenericStepConfig}).
     *
     * @return The {@link StepSchema} for the runner type produced by this factory. Should not be null.
     */
    StepSchema getSchema();
}