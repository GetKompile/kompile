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

package ai.kompile.pipelines.framework.api;

import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.context.Context;

/**
 * Defines the execution logic for a {@link StepConfig} in a pipeline.
 * Each unique type of pipeline step will have a corresponding PipelineStepRunner implementation.
 *
 * Runners are responsible for:
 * 1. Initialization: Setting up based on the provided {@link StepConfig} and {@link Context}.
 * This includes loading models, allocating resources, and validating parameters.
 * 2. Execution: Processing an input {@link Data} object and producing an output {@link Data} object.
 * This is where the core transformation or action of the step occurs.
 * 3. Cleanup: Releasing any resources when the runner is no longer needed.
 *
 * Implementations of this interface should be thread-safe if they are intended to be used
 * concurrently by multiple pipeline executions, or the {@link PipelineExecutor} must ensure
 * that a runner instance is only used by one thread at a time for a given execution.
 * The lifecycle (init, exec, close) is managed by the {@link PipelineExecutor}.
 */
public interface PipelineStepRunner extends AutoCloseable {

    /**
     * Initializes the runner with its specific configuration and the pipeline context.
     * This method is called once by the {@link PipelineExecutor} before any calls to
     * {@link #exec(Data, Context)} for this runner instance.
     * <p>
     * Implementations should perform all necessary setup here, such as:
     * <ul>
     * <li>Loading models or other resources from URIs specified in {@code stepConfig}.</li>
     * <li>Validating parameters from {@code stepConfig.getParameters()} against an expected schema.</li>
     * <li>Allocating any necessary buffers or state.</li>
     * <li>Registering metrics or profiler events if applicable, using the provided {@code context}.</li>
     * </ul>
     *
     * @param stepConfig The configuration for this specific step instance, providing access to
     * parameters via {@link StepConfig#getParameters()}.
     * @param context The pipeline execution context, providing access to shared resources,
     * metrics, profilers, the original pipeline input, and execution ID.
     * The context passed here might be a root context for the pipeline or a
     * child context specific to this step's initialization phase.
     * @throws Exception if initialization fails (e.g., model loading error, invalid configuration,
     * resource allocation failure). A descriptive exception should be thrown.
     */
    void init(StepConfig stepConfig, Context context) throws Exception;

    /**
     * Executes the primary logic of this pipeline step.
     * It takes an input {@link Data} object (which could be the output of a previous step
     * or the initial input to the pipeline) and returns an output {@link Data} object.
     * <p>
     * The {@code context} provided to this method is specific to the current execution of this step
     * and can be used for fine-grained metrics, profiling, or accessing execution-scoped data.
     * It may be a child of the context passed during {@code init}.
     * <p>
     * This method may be called multiple times with different inputs if the runner instance is reused
     * across multiple pipeline executions (behavior determined by the {@link PipelineExecutor}).
     *
     * @param input The input {@link Data} object from the previous step or pipeline input.
     * The runner should expect specific keys in this Data object based on its contract.
     * @param context The pipeline execution context for this specific execution of the step.
     * @return The output {@link Data} object to be passed to the next step or as the pipeline's final output.
     * This should not be null; return {@code Data.empty()} if there's no meaningful output.
     * @throws Exception if an error occurs during the execution of the step's logic.
     * A descriptive exception should be thrown.
     */
    Data exec(Data input, Context context) throws Exception;

    /**
     * Checks if this runner has been successfully initialized.
     * The {@link PipelineExecutor} should only call {@link #exec(Data, Context)} if this returns true.
     *
     * @return {@code true} if {@link #init(StepConfig, Context)} has completed successfully, {@code false} otherwise.
     */
    boolean isInitialized();

    /**
     * Cleans up any resources held by this runner (e.g., closing files, releasing native memory,
     * shutting down thread pools). This method is called by the {@link PipelineExecutor} when
     * the pipeline is shut down or the runner instance is being discarded.
     * <p>
     * Overrides {@link AutoCloseable#close()} to allow usage in try-with-resources statements
     * for managing the lifecycle of runners.
     * <p>
     * Implementations should be idempotent (i.e., calling close multiple times should not cause errors).
     *
     * @throws Exception if an error occurs during cleanup.
     */
    @Override
    void close() throws Exception;
}