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

// Existing file: upload/kompile-pipelines-framework-api/src/main/java/ai/kompile/pipelines/framework/api/PipelineExecutor.java
// Additions/Modifications are marked

package ai.kompile.pipelines.framework.api;

import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.context.Context;
// Import the new data constants
import ai.kompile.pipelines.framework.api.data.PipelineDataConstants;


import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Executes a {@link Pipeline}.
 * It manages the actual execution of the pipeline steps, handling data flow
 * between them and managing the lifecycle of {@link PipelineStepRunner}s.
 *
 * An executor is typically created from a {@link Pipeline} instance using
 * {@link Pipeline#createExecutor()}. The executor is stateful in the sense
 * that it holds initialized runners. For executions involving tool calls,
 * the executor may pause and resume, managed via the {@link Context#executionId()}.
 *
 * It implements {@link AutoCloseable} to ensure that resources held by the
 * pipeline (like initialized runners) can be properly released.
 */
public interface PipelineExecutor extends AutoCloseable {

    /**
     * Synchronously executes the pipeline with the given input data.
     * A default {@link Context} will be created for this execution.
     * <p>
     * If the pipeline supports tool calls, the returned {@link Data} object may indicate
     * that tool calls are pending (see {@link PipelineDataConstants#IS_TOOL_CALL_REQUEST_FLAG_KEY}).
     * In such cases, the execution is considered paused, and {@link #resume(Context, Data)}
     * should be called with tool responses to continue.
     *
     * @param input The input {@link Data} to the pipeline. Must not be null.
     * @return The output {@link Data} from the pipeline. This could be a final result
     * or an intermediate result indicating pending tool calls. Should not be null.
     * @throws NullPointerException if input is null.
     * @throws IllegalStateException if the executor or its underlying runners are not properly initialized or if the executor is closed.
     * @throws Exception if an error occurs during any step of the pipeline execution.
     */
    Data exec(Data input) throws Exception;

    /**
     * Synchronously executes the pipeline with the given input data and a specific {@link Context}.
     * This allows for more control over the execution environment, metrics, and profiling.
     * The {@link Context#executionId()} is crucial for managing resumable (tool-calling) executions.
     * <p>
     * If the pipeline supports tool calls, the returned {@link Data} object may indicate
     * that tool calls are pending (see {@link PipelineDataConstants#IS_TOOL_CALL_REQUEST_FLAG_KEY}).
     * In such cases, the execution is considered paused, and {@link #resume(Context, Data)}
     * should be called with tool responses to continue using the same {@code executionId} in the context.
     *
     * @param input The input {@link Data} to the pipeline. Must not be null.
     * @param context The {@link Context} for this specific execution. Must not be null.
     * The {@link Context#executionId()} should be set for executions that might involve tool calls.
     * @return The output {@link Data} from the pipeline. This could be a final result
     * or an intermediate result indicating pending tool calls. Should not be null.
     * @throws NullPointerException if input or context is null.
     * @throws IllegalStateException if the executor or its underlying runners are not properly initialized or if the executor is closed,
     * or if trying to start a new execution with an ID that is already paused.
     * @throws Exception if an error occurs during any step of the pipeline execution.
     */
    Data exec(Data input, Context context) throws Exception;

    /**
     * NEW METHOD:
     * Resumes a previously paused pipeline execution with the responses from executed tools.
     * The pipeline execution must have been paused by a prior call to an {@code exec} method
     * that returned a {@link Data} object indicating pending tool calls.
     * The {@link Context} must contain the same {@code executionId} that was used during the
     * initial {@code exec} call that led to the pause.
     *
     * @param context The {@link Context} for this resumption. Its {@link Context#executionId()}
     * must match the ID of the paused execution. This context will be used
     * for the continued execution.
     * @param toolResponses A {@link Data} object containing the tool responses, typically structured
     * under the key {@link PipelineDataConstants#TOOL_CALL_RESPONSES_KEY}.
     * Must not be null.
     * @return The next output {@link Data} from the pipeline. This could be a final result
     * or another intermediate result indicating further pending tool calls. Should not be null.
     * @throws NullPointerException if context or toolResponses is null.
     * @throws IllegalStateException if no execution is paused for the given {@code executionId},
     * if the executor is closed, or if the execution was not in a state expecting tool responses.
     * @throws IllegalArgumentException if {@link Context#executionId()} is not present.
     * @throws Exception if an error occurs during the resumed pipeline execution.
     */
    Data resume(Context context, Data toolResponses) throws Exception;


    // --- Asynchronous methods would also need similar considerations ---
    // For brevity, we'll focus on the synchronous `exec` and `resume`.
    // `execAsync` would return a CompletableFuture<Data>, where Data could signal tool calls.
    // A `resumeAsync(Context, Data)` would then be needed.

    /**
     * Asynchronously executes the pipeline with the given input data.
     * A default {@link Context} will be created. The returned {@link Data} (via CompletableFuture)
     * may indicate pending tool calls.
     *
     * @param input The input {@link Data} to the pipeline. Must not be null.
     * @return A {@link CompletableFuture} that will be completed with the output {@link Data}
     * or completed exceptionally. The output {@link Data} may signal pending tool calls.
     * @throws NullPointerException if input is null.
     * @throws IllegalStateException if the executor is closed.
     */
    CompletableFuture<Data> execAsync(Data input);

    /**
     * Asynchronously executes the pipeline with the given input data and a specific {@link Context}.
     * The returned {@link Data} (via CompletableFuture) may indicate pending tool calls.
     * The {@link Context#executionId()} is crucial.
     *
     * @param input The input {@link Data} to the pipeline. Must not be null.
     * @param context The {@link Context} for this specific execution. Must not be null.
     * @return A {@link CompletableFuture} that will be completed with the output {@link Data}
     * or completed exceptionally. The output {@link Data} may signal pending tool calls.
     * @throws NullPointerException if input or context is null.
     * @throws IllegalStateException if the executor is closed.
     */
    CompletableFuture<Data> execAsync(Data input, Context context);

    /**
     * NEW METHOD (Conceptual for async resume):
     * Asynchronously resumes a previously paused pipeline execution.
     *
     * @param context The {@link Context} for this resumption.
     * @param toolResponses A {@link Data} object containing the tool responses.
     * @return A {@link CompletableFuture} that will be completed with the next output {@link Data}
     * or completed exceptionally. The output {@link Data} may signal further tool calls.
     */
    CompletableFuture<Data> resumeAsync(Context context, Data toolResponses);


    /**
     * Asynchronously executes the pipeline and invokes the provided callbacks upon completion or failure.
     * The {@link Data} provided to {@code onSuccess} may indicate pending tool calls.
     *
     * @param input The input {@link Data} to the pipeline. Must not be null.
     * @param onSuccess A {@link Consumer} that accepts the output {@link Data}. Must not be null.
     * @param onFailure A {@link Consumer} that accepts any {@link Throwable}. Must not be null.
     * @throws NullPointerException if input, onSuccess, or onFailure is null.
     * @throws IllegalStateException if the executor is closed.
     */
    void execAsync(Data input, Consumer<Data> onSuccess, Consumer<Throwable> onFailure);


    /**
     * Asynchronously executes the pipeline with a given context and invokes the provided callbacks.
     * The {@link Data} provided to {@code onSuccess} may indicate pending tool calls.
     *
     * @param input The input {@link Data} to the pipeline. Must not be null.
     * @param context The {@link Context} for this specific execution. Must not be null.
     * @param onSuccess A {@link Consumer} that accepts the output {@link Data}. Must not be null.
     * @param onFailure A {@link Consumer} that accepts any {@link Throwable}. Must not be null.
     * @throws NullPointerException if input, context, onSuccess, or onFailure is null.
     * @throws IllegalStateException if the executor is closed.
     */
    void execAsync(Data input, Context context, Consumer<Data> onSuccess, Consumer<Throwable> onFailure);

    /**
     * NEW METHOD (Conceptual for async resume with callbacks):
     * Asynchronously resumes a previously paused pipeline execution and invokes callbacks.
     *
     * @param context The {@link Context} for this resumption.
     * @param toolResponses A {@link Data} object containing the tool responses.
     * @param onSuccess A {@link Consumer} that accepts the next output {@link Data}.
     * @param onFailure A {@link Consumer} that accepts any {@link Throwable}.
     */
    void resumeAsync(Context context, Data toolResponses, Consumer<Data> onSuccess, Consumer<Throwable> onFailure);


    // execStream might be complex to adapt for request-response tool calls,
    // as Iterator is pull-based. It might be better suited for pipelines that
    // internally loop and yield multiple results/tool_requests in sequence.
    // For now, we'll leave execStream as is, assuming it's for non-interactive streaming.
    // Or, it could be re-imagined where the Data objects it yields can be tool call requests.
    // The caller would then have to somehow inject responses back into the "stream's source",
    // which is non-standard for Iterator. This would likely need a different reactive streams API.

    Iterator<Data> execStream(Data initialInput) throws Exception;
    Iterator<Data> execStream(Data initialInput, Context context) throws Exception;
    Iterator<Data> execStream(Pipeline pipeline, Data initialInput, Context context) throws Exception;


    /**
     * Gets the {@link Pipeline} instance that this executor is configured to run.
     * @return The associated {@link Pipeline}.
     */
    Pipeline getPipeline();

    /**
     * Closes the executor and releases any associated resources.
     * This typically involves closing all initialized {@link PipelineStepRunner} instances,
     * cleaning up any stored states for paused executions,
     * and shutting down any internal thread pools if used for asynchronous execution.
     * <p>
     * This method should be idempotent.
     * After an executor is closed, subsequent calls to exec or resume methods
     * should ideally throw an {@link IllegalStateException}.
     *
     * @throws Exception if an error occurs during closing.
     */
    @Override
    void close() throws Exception;
}