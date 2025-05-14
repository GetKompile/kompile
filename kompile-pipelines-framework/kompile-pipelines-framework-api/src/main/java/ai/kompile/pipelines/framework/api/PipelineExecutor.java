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

package ai.kompile.pipelines.framework.api;

import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.context.Context;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Executes a {@link Pipeline}.
 * It manages the actual execution of the pipeline steps, handling data flow
 * between them and managing the lifecycle of {@link PipelineStepRunner}s.
 *
 * An executor is typically created from a {@link Pipeline} instance using
 * {@link Pipeline#createExecutor()}. The executor is stateful in the sense
 * that it holds initialized runners, but the {@code exec} methods themselves
 * should be designed to be thread-safe or documented if they have concurrency limitations.
 *
 * It implements {@link AutoCloseable} to ensure that resources held by the
 * pipeline (like initialized runners) can be properly released.
 */
public interface PipelineExecutor extends AutoCloseable {

    /**
     * Synchronously executes the pipeline with the given input data.
     * A default {@link Context} will be created for this execution.
     *
     * @param input The input {@link Data} to the pipeline. Must not be null.
     * @return The output {@link Data} from the pipeline. Should not be null (return {@code Data.empty()} for no output).
     * @throws NullPointerException if input is null.
     * @throws IllegalStateException if the executor or its underlying runners are not properly initialized.
     * @throws Exception if an error occurs during any step of the pipeline execution.
     */
    Data exec(Data input) throws Exception;

    /**
     * Synchronously executes the pipeline with the given input data and a specific {@link Context}.
     * This allows for more control over the execution environment, metrics, and profiling.
     *
     * @param input The input {@link Data} to the pipeline. Must not be null.
     * @param context The {@link Context} for this specific execution. Must not be null.
     * @return The output {@link Data} from the pipeline. Should not be null.
     * @throws NullPointerException if input or context is null.
     * @throws IllegalStateException if the executor or its underlying runners are not properly initialized.
     * @throws Exception if an error occurs during any step of the pipeline execution.
     */
    Data exec(Data input, Context context) throws Exception;


    /**
     * Asynchronously executes the pipeline with the given input data.
     * A default {@link Context} will be created for this execution.
     * The implementation will typically use a thread pool to manage asynchronous tasks.
     *
     * @param input The input {@link Data} to the pipeline. Must not be null.
     * @return A {@link CompletableFuture} that will be completed with the output {@link Data}
     * or completed exceptionally if an error occurs during pipeline execution.
     * @throws NullPointerException if input is null.
     */
    CompletableFuture<Data> execAsync(Data input);

    /**
     * Asynchronously executes the pipeline with the given input data and a specific {@link Context}.
     *
     * @param input The input {@link Data} to the pipeline. Must not be null.
     * @param context The {@link Context} for this specific execution. Must not be null.
     * @return A {@link CompletableFuture} that will be completed with the output {@link Data}
     * or completed exceptionally if an error occurs during pipeline execution.
     * @throws NullPointerException if input or context is null.
     */
    CompletableFuture<Data> execAsync(Data input, Context context);


    /**
     * Asynchronously executes the pipeline and invokes the provided callbacks upon completion or failure.
     * A default {@link Context} will be created for this execution.
     *
     * @param input The input {@link Data} to the pipeline. Must not be null.
     * @param onSuccess A {@link Consumer} that accepts the output {@link Data} upon successful completion. Must not be null.
     * @param onFailure A {@link Consumer} that accepts any {@link Throwable} that occurred during execution. Must not be null.
     * @throws NullPointerException if input, onSuccess, or onFailure is null.
     */
    void execAsync(Data input, Consumer<Data> onSuccess, Consumer<Throwable> onFailure);


    /**
     * Asynchronously executes the pipeline with a given context and invokes the provided callbacks upon completion or failure.
     *
     * @param input The input {@link Data} to the pipeline. Must not be null.
     * @param context The {@link Context} for this specific execution. Must not be null.
     * @param onSuccess A {@link Consumer} that accepts the output {@link Data} upon successful completion. Must not be null.
     * @param onFailure A {@link Consumer} that accepts any {@link Throwable} that occurred during execution. Must not be null.
     * @throws NullPointerException if input, context, onSuccess, or onFailure is null.
     */
    void execAsync(Data input, Context context, Consumer<Data> onSuccess, Consumer<Throwable> onFailure);


    /**
     * Gets the {@link Pipeline} instance that this executor is configured to run.
     * @return The associated {@link Pipeline}.
     */
    Pipeline getPipeline();

    /**
     * Closes the executor and releases any associated resources.
     * This typically involves closing all initialized {@link PipelineStepRunner} instances
     * and shutting down any internal thread pools if used for asynchronous execution.
     * <p>
     * This method should be idempotent (i.e., calling it multiple times should not cause errors).
     * After an executor is closed, subsequent calls to {@code exec} or {@code execAsync}
     * should ideally throw an {@link IllegalStateException}.
     *
     * @throws Exception if an error occurs during closing.
     */
    @Override
    void close() throws Exception;
}