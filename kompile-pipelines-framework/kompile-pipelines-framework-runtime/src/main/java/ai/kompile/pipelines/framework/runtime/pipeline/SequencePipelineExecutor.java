// Предполагаемый путь: upload/kompile-pipelines-framework-runtime/src/main/java/ai/kompile/pipelines/framework/runtime/pipeline/SequencePipelineExecutor.java
package ai.kompile.pipelines.framework.runtime.pipeline;

import ai.kompile.pipelines.framework.api.Pipeline;
import ai.kompile.pipelines.framework.api.PipelineExecutor;
import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.PipelineStepRunnerFactory;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.configschema.StepSchemaProvider; // For runner initialization if needed
import ai.kompile.pipelines.framework.api.context.Context;
import ai.kompile.pipelines.framework.api.context.Metrics;
import ai.kompile.pipelines.framework.api.context.Profiler;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.data.DataFactory;
// Import the new data constants
import ai.kompile.pipelines.framework.api.data.PipelineDataConstants;
import ai.kompile.pipelines.framework.api.data.ValueType;
import ai.kompile.pipelines.framework.core.context.DefaultContext; // Assuming DefaultContext exists
import ai.kompile.pipelines.framework.core.context.NoOpMetrics;
import ai.kompile.pipelines.framework.core.context.NoOpProfiler; // Assuming NoOpProfiler

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Executes a {@link SequencePipeline} by running its steps in the defined order.
 * This executor supports pausable and resumable executions for pipelines involving tool calls.
 */
public class SequencePipelineExecutor implements PipelineExecutor {

    private static final Logger logger = LoggerFactory.getLogger(SequencePipelineExecutor.class);

    private final Pipeline pipeline;
    private final List<PipelineStepRunner> runners;
    private final boolean ownRunners; // If true, this executor is responsible for closing runners
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final DataFactory dataFactory;
    private final Supplier<Context> defaultContextFactory; // To create default contexts

    // For managing state of paused executions
    private final Map<String, PausedExecutionState> pausedExecutions = new ConcurrentHashMap<>();

    // For async operations
    private final ExecutorService asyncExecutorService;


    /**
     * Represents the state of a paused pipeline execution.
     */
    private static class PausedExecutionState {
        final int nextStepIndex;
        Data currentData; // The data object as it was when the pipeline paused
        final Context originalContext; // The context of the execution

        PausedExecutionState(int nextStepIndex, Data currentData, Context originalContext) {
            this.nextStepIndex = nextStepIndex;
            this.currentData = currentData;
            this.originalContext = originalContext;
        }
    }

    public SequencePipelineExecutor(Pipeline pipeline, boolean ownRunners) throws Exception {
        this(pipeline, ownRunners, Data.Factory.get(), null, null);
    }

    public SequencePipelineExecutor(
            Pipeline pipeline,
            boolean ownRunners,
            DataFactory dataFactory,
            ExecutorService executorService,
            Supplier<Context> defaultContextFactory) throws Exception {
        this.pipeline = Objects.requireNonNull(pipeline, "Pipeline cannot be null");
        this.ownRunners = ownRunners;
        this.dataFactory = Objects.requireNonNull(dataFactory, "DataFactory cannot be null");
        this.asyncExecutorService = (executorService != null) ? executorService : Executors.newCachedThreadPool(); // Default executor service
        this.defaultContextFactory = (defaultContextFactory != null) ? defaultContextFactory :
                () -> new DefaultContext(this.dataFactory.empty(), "default-exec-" + System.nanoTime(), "default-ctx-" + System.nanoTime(), null, NoOpMetrics.INSTANCE, NoOpProfiler.INSTANCE);


        // Validate pipeline first
        this.pipeline.validate();

        // Initialize runners
        List<StepConfig> steps = pipeline.getSteps();
        if (steps == null || steps.isEmpty()) {
            this.runners = Collections.emptyList();
            logger.warn("Pipeline '{}' has no steps defined.", pipeline.id());
        } else {
            this.runners = new ArrayList<>(steps.size());
            // Assuming context for init is a basic one or provided by a specific factory
            Context initContext = this.defaultContextFactory.get().child("runners-init");
            Map<String, PipelineStepRunnerFactory> factories = loadRunnerFactories();
            StepSchemaProvider schemaProvider = loadSchemaProvider().orElse(null); // Optional

            for (StepConfig stepConfig : steps) {
                if (stepConfig.runnerClassName() == null || stepConfig.runnerClassName().trim().isEmpty()) {
                    throw new IllegalStateException("StepConfig runnerClassName cannot be null or empty for step in pipeline: " + pipeline.id());
                }
                PipelineStepRunnerFactory factory = factories.get(stepConfig.runnerClassName());
                if (factory == null) {
                    throw new IllegalStateException("No PipelineStepRunnerFactory found for runner type: " + stepConfig.runnerClassName());
                }
                PipelineStepRunner runner = factory.create();
                // TODO: Optionally, validate stepConfig.getParameters() against schema from schemaProvider if available
                runner.init(stepConfig, initContext.child(runner.getClass().getSimpleName() + "-init"));
                this.runners.add(runner);
            }
        }
        logger.info("SequencePipelineExecutor initialized for pipeline '{}' with {} steps.", pipeline.id(), this.runners.size());
    }

    private Map<String, PipelineStepRunnerFactory> loadRunnerFactories() {
        Map<String, PipelineStepRunnerFactory> factories = new ConcurrentHashMap<>();
        ServiceLoader<PipelineStepRunnerFactory> loader = ServiceLoader.load(PipelineStepRunnerFactory.class);
        for (PipelineStepRunnerFactory factory : loader) {
            factories.put(factory.getRunnerType(), factory);
        }
        if (factories.isEmpty()) {
            logger.warn("No PipelineStepRunnerFactory implementations found via ServiceLoader.");
        }
        return factories;
    }

    private Optional<StepSchemaProvider> loadSchemaProvider() {
        return ServiceLoader.load(StepSchemaProvider.class).findFirst();
    }


    @Override
    public Data exec(Data input) throws Exception {
        return exec(input, defaultContextFactory.get());
    }

    @Override
    public Data exec(Data input, Context context) throws Exception {
        if (closed.get()) {
            throw new IllegalStateException("PipelineExecutor is closed.");
        }
        Objects.requireNonNull(input, "Input data cannot be null.");
        Objects.requireNonNull(context, "Context cannot be null.");

        String executionId = context.executionId().orElseThrow(() ->
                new IllegalArgumentException("Context must provide an executionId for resumable executions."));

        // Check if this execution ID is already paused. If so, it's an error to call exec again.
        // Or, if the design allows overwriting/restarting, this logic would change.
        // For now, assume exec is for new executions or non-pausing ones.
        if (pausedExecutions.containsKey(executionId)) {
            throw new IllegalStateException("Execution with ID '" + executionId + "' is already paused. Use resume() to continue.");
        }
        // If this is a fresh execution for this ID, or if previous completed/closed, it's fine.

        return processPipeline(input, context, 0, executionId);
    }

    @Override
    public Data resume(Context context, Data toolResponses) throws Exception {
        if (closed.get()) {
            throw new IllegalStateException("PipelineExecutor is closed.");
        }
        Objects.requireNonNull(context, "Context cannot be null for resume.");
        Objects.requireNonNull(toolResponses, "Tool responses data cannot be null for resume.");

        String executionId = context.executionId().orElseThrow(() ->
                new IllegalArgumentException("Context must provide an executionId to resume an execution."));

        PausedExecutionState pausedState = pausedExecutions.remove(executionId);
        if (pausedState == null) {
            throw new IllegalStateException("No paused execution found for ID '" + executionId + "'. Cannot resume.");
        }

        // Merge toolResponses into the pausedData or prepare it as the new input
        // For simplicity, let's assume toolResponses are added to the currentData.
        // A more sophisticated merge or specific input mapping might be needed.
        Data currentData = pausedState.currentData.dup(); // Work on a copy
        currentData.put(PipelineDataConstants.TOOL_CALL_RESPONSES_KEY,
                toolResponses.getList(PipelineDataConstants.TOOL_CALL_RESPONSES_KEY, ValueType.DATA));


        logger.info("Resuming pipeline execution for ID '{}' from step index {}.", executionId, pausedState.nextStepIndex);
        // Use the new context for resumed execution, but originalInput might be from pausedState.originalContext
        Context resumedContext = context; // Or a child context: context.child("resume-" + pausedState.nextStepIndex);

        return processPipeline(currentData, resumedContext, pausedState.nextStepIndex, executionId);
    }

    private Data processPipeline(Data currentInputData, Context currentContext, int startIndex, String executionId) throws Exception {
        Data stepInput = currentInputData;
        Profiler profiler = currentContext.profiler(); // Get profiler from the current context

        for (int i = startIndex; i < runners.size(); i++) {
            PipelineStepRunner runner = runners.get(i);
            String stepName = runner.getClass().getSimpleName() + "[" + i + "]"; // Example step name
            Context stepContext = currentContext.child(stepName); // Create child context for the step

            try {
                logger.debug("Executing step {}: {} with input: {}", i, stepName, stepInput.toJson()); // Be careful with logging full data
                Data finalStepInput = stepInput;
                stepInput = profiler.profile("step:" + stepName, () -> runner.exec(finalStepInput, stepContext));
                logger.debug("Step {}: {} output: {}", i, stepName, stepInput.toJson());

                Objects.requireNonNull(stepInput, "Step " + stepName + " returned null data.");

                // Check for tool call request
                if (stepInput.getBoolean(PipelineDataConstants.IS_TOOL_CALL_REQUEST_FLAG_KEY, false)) {
                    logger.info("Pipeline execution ID '{}' paused at step index {} due to tool call request.", executionId, i);
                    // Save state for resumption
                    // The 'currentData' saved should be the 'stepInput' which contains the tool call requests.
                    pausedExecutions.put(executionId, new PausedExecutionState(i + 1, stepInput, currentContext /* or original context of this turn */));
                    return stepInput; // Return the data containing tool call requests
                }

            } catch (Exception e) {
                logger.error("Error executing step {}: {} in pipeline '{}'", i, stepName, pipeline.id(), e);
                // TODO: Add more sophisticated error handling (e.g., pipeline failure strategy)
                throw new RuntimeException("Error in step " + stepName + " of pipeline " + pipeline.id(), e);
            }
        }
        // If loop completes, it's a final result for this execution turn
        pausedExecutions.remove(executionId); // Clean up if execution completed without pause
        return stepInput;
    }


    // --- Asynchronous Implementations ---

    @Override
    public CompletableFuture<Data> execAsync(Data input) {
        return execAsync(input, defaultContextFactory.get());
    }

    @Override
    public CompletableFuture<Data> execAsync(Data input, Context context) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("PipelineExecutor is closed."));
        }
        Objects.requireNonNull(input, "Input data cannot be null.");
        Objects.requireNonNull(context, "Context cannot be null.");
        if (!context.executionId().isPresent()){
            return CompletableFuture.failedFuture(new IllegalArgumentException("Context must provide an executionId for resumable async executions."));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                return exec(input, context); // Delegate to synchronous version, which now handles pausing
            } catch (Exception e) {
                logger.error("Async execution failed for pipeline '{}', execution ID '{}'", pipeline.id(), context.executionId().get(), e);
                throw new RuntimeException(e);
            }
        }, asyncExecutorService);
    }

    @Override
    public CompletableFuture<Data> resumeAsync(Context context, Data toolResponses) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("PipelineExecutor is closed."));
        }
        Objects.requireNonNull(context, "Context cannot be null for resume.");
        Objects.requireNonNull(toolResponses, "Tool responses data cannot be null for resume.");
        if (!context.executionId().isPresent()){
            return CompletableFuture.failedFuture(new IllegalArgumentException("Context must provide an executionId to resume an async execution."));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return resume(context, toolResponses); // Delegate to synchronous version
            } catch (Exception e) {
                logger.error("Async resume failed for pipeline '{}', execution ID '{}'", pipeline.id(), context.executionId().get(), e);
                throw new RuntimeException(e);
            }
        }, asyncExecutorService);
    }


    @Override
    public void execAsync(Data input, Consumer<Data> onSuccess, Consumer<Throwable> onFailure) {
        execAsync(input, defaultContextFactory.get(), onSuccess, onFailure);
    }

    @Override
    public void execAsync(Data input, Context context, Consumer<Data> onSuccess, Consumer<Throwable> onFailure) {
        CompletableFuture<Data> future = execAsync(input, context);
        future.thenAccept(onSuccess).exceptionally(ex -> {
            onFailure.accept(ex);
            return null;
        });
    }

    @Override
    public void resumeAsync(Context context, Data toolResponses, Consumer<Data> onSuccess, Consumer<Throwable> onFailure) {
        CompletableFuture<Data> future = resumeAsync(context, toolResponses);
        future.thenAccept(onSuccess).exceptionally(ex -> {
            onFailure.accept(ex);
            return null;
        });
    }

    // --- Streaming ---
    // execStream is harder to adapt directly for request-response tool calls with external suspension.
    // It would imply the Iterator itself needs to become interactive or the stream processing logic
    // needs to handle yielding control and resuming.
    // For now, let's assume execStream yields Data objects, and if one of them is a tool call request,
    // the consumer of the stream has to manage the pause/resume cycle externally, possibly
    // by stopping iteration and then starting a new stream or using exec/resume for the interactive part.
    // A fully reactive (e.g., Project Reactor Flux) approach might be better for complex streaming + tool calls.

    @Override
    public Iterator<Data> execStream(Data initialInput) throws Exception {
        return execStream(initialInput, defaultContextFactory.get());
    }

    @Override
    public Iterator<Data> execStream(Data initialInput, Context context) throws Exception {
        return execStream(this.pipeline, initialInput, context);
    }

    @Override
    public Iterator<Data> execStream(Pipeline pipelineToStream, Data initialInput, Context context) throws Exception {
        if (closed.get()) {
            throw new IllegalStateException("PipelineExecutor is closed.");
        }
        // Basic iterator implementation for a sequence pipeline.
        // This simple version doesn't inherently support pausing and resuming FOR TOOL CALLS
        // in the middle of the stream *internally*. If a step in the stream outputs a tool call request,
        // the stream will yield that Data object. The consumer of the iterator would have to handle it.
        // This does NOT use the `pausedExecutions` map.
        // A more advanced streaming executor for tool calls would be needed.

        List<PipelineStepRunner> streamRunners = this.runners; // Use runners of this executor instance
        if (pipelineToStream != this.pipeline) {
            logger.warn("Executing stream with a different pipeline instance than this executor was created for. This is unusual for stateful executors.");
            // For simplicity, this example will use the current executor's runners.
            // A proper implementation might need to create/manage runners for the passed pipeline.
        }


        return new Iterator<Data>() {
            private Data currentData = initialInput;
            private int currentStep = 0;
            private Data nextOutput = null;
            private boolean streamFinished = false;

            @Override
            public boolean hasNext() {
                if (streamFinished) return false;
                if (nextOutput != null) return true;

                // Try to compute the next output
                try {
                    // This loop is to find the next actual data output or tool request if the pipeline supports it.
                    // If a step internally iterates and yields multiple Data, this simple iterator won't capture that.
                    // This iterator assumes each step produces one Data output to pass to the next.
                    if (currentStep < streamRunners.size()) {
                        PipelineStepRunner runner = streamRunners.get(currentStep);
                        String stepName = runner.getClass().getSimpleName() + "[" + currentStep + "]";
                        Context stepContext = context.child("stream-" + stepName);
                        currentData = runner.exec(currentData, stepContext); // Execute step
                        nextOutput = currentData; // The output of the step is the next item
                        currentStep++;
                        return true;
                    } else {
                        streamFinished = true;
                        return false;
                    }
                } catch (Exception e) {
                    streamFinished = true;
                    logger.error("Error during execStream", e);
                    throw new RuntimeException("Error processing stream", e);
                }
            }

            @Override
            public Data next() {
                if (!hasNext()) {
                    throw new java.util.NoSuchElementException();
                }
                Data toReturn = nextOutput;
                nextOutput = null; // Consume it
                return toReturn;
            }
        };
    }


    @Override
    public Pipeline getPipeline() {
        return pipeline;
    }

    @Override
    public void close() throws Exception {
        if (closed.compareAndSet(false, true)) {
            logger.info("Closing SequencePipelineExecutor for pipeline '{}'...", pipeline.id());
            // Clear any paused states
            pausedExecutions.clear();

            // Shut down internal executor service if it was created by this instance
            // (More complex logic needed if executorService is shared)
            if (asyncExecutorService != null && !asyncExecutorService.isShutdown()) {
                asyncExecutorService.shutdown();
            }

            if (ownRunners) {
                List<Exception> closeExceptions = new ArrayList<>();
                for (PipelineStepRunner runner : runners) {
                    try {
                        runner.close();
                    } catch (Exception e) {
                        logger.error("Error closing runner: " + runner.getClass().getName(), e);
                        closeExceptions.add(e);
                    }
                }
                if (!closeExceptions.isEmpty()) {
                    // Composite exception or log them all
                    throw new Exception("One or more errors occurred while closing runners: " +
                            closeExceptions.stream().map(Throwable::getMessage).collect(Collectors.joining(", ")));
                }
            }
            logger.info("SequencePipelineExecutor for pipeline '{}' closed.", pipeline.id());
        }
    }
}