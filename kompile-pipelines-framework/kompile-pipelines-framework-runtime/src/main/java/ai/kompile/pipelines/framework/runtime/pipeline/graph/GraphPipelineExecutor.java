// Suggested Path: upload/kompile-pipelines-framework-runtime/src/main/java/ai/kompile/pipelines/framework/runtime/pipeline/GraphPipelineExecutor.java
package ai.kompile.pipelines.framework.runtime.pipeline.graph;

import ai.kompile.pipelines.framework.api.Pipeline; // Should be GraphPipeline
import ai.kompile.pipelines.framework.api.PipelineExecutor;
import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.PipelineStepRunnerFactory;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.configschema.StepSchemaProvider;
import ai.kompile.pipelines.framework.api.context.Context;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.data.DataFactory;
import ai.kompile.pipelines.framework.api.data.PipelineDataConstants;
import ai.kompile.pipelines.framework.api.data.PipelineToolCallRequest; // Assuming this POJO exists
import ai.kompile.pipelines.framework.api.data.ValueType;
import ai.kompile.pipelines.framework.core.context.DefaultContext;
import ai.kompile.pipelines.framework.core.context.NoOpMetrics;
import ai.kompile.pipelines.framework.core.context.NoOpProfiler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Executes a GraphPipeline, managing dependencies and data flow between steps.
 * Supports pausable and resumable executions for tool calls.
 *
 * Assumptions:
 * - A GraphPipeline interface/class exists and provides graph structure.
 * - StepConfig includes a unique 'id' and dependency information.
 */
public class GraphPipelineExecutor implements PipelineExecutor {
    private static final Logger logger = LoggerFactory.getLogger(GraphPipelineExecutor.class);

    // Special Step ID for initial pipeline input
    public static final String PIPELINE_INPUT_ID = "@PIPELINE_INPUT@";

    private final GraphPipeline graphPipeline; // Assuming a GraphPipeline type
    private final Map<String, PipelineStepRunner> runnersMap; // stepId -> runner
    private final boolean ownRunners;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final DataFactory dataFactory;
    private final Supplier<Context> defaultContextFactory;
    private final ExecutorService asyncExecutorService;

    // For managing state of paused graph executions
    private final Map<String, PausedGraphExecutionState> pausedExecutions = new ConcurrentHashMap<>();

    private enum StepRunState { PENDING, RUNNABLE, RUNNING, COMPLETED, FAILED, PAUSED_AWAITING_TOOLS }

    /**
     * Represents the overall state of a paused graph execution.
     */
    private static class PausedGraphExecutionState {
        final Map<String, Data> completedStepOutputs; // Outputs of steps already completed before pause
        final Map<String, StepRunState> stepStates;   // State of all steps at the moment of pause
        final String pausedByStepId;                 // The ID of the step that triggered the pause
        final Data toolCallRequestPayload;           // The Data object containing the tool call requests
        final Context originalContext;               // Context when execution paused

        PausedGraphExecutionState(Map<String, Data> outputs, Map<String, StepRunState> states,
                                  String pausedByStepId, Data toolCallRequestPayload, Context context) {
            this.completedStepOutputs = new HashMap<>(outputs); // Defensive copy
            this.stepStates = new HashMap<>(states);           // Defensive copy
            this.pausedByStepId = pausedByStepId;
            this.toolCallRequestPayload = toolCallRequestPayload;
            this.originalContext = context;
        }
    }


    public GraphPipelineExecutor(GraphPipeline pipeline, boolean ownRunners) throws Exception {
        this(pipeline, ownRunners, Data.Factory.get(), null, null);
    }

    public GraphPipelineExecutor(
            GraphPipeline pipeline,
            boolean ownRunners,
            DataFactory dataFactory,
            ExecutorService executorService,
            Supplier<Context> defaultContextFactory
    ) throws Exception {
        this.graphPipeline = Objects.requireNonNull(pipeline, "GraphPipeline cannot be null");
        this.ownRunners = ownRunners;
        this.dataFactory = Objects.requireNonNull(dataFactory, "DataFactory cannot be null");
        this.asyncExecutorService = (executorService != null) ? executorService : Executors.newCachedThreadPool();
        this.defaultContextFactory = (defaultContextFactory != null) ? defaultContextFactory :
                () -> new DefaultContext(this.dataFactory.empty(), "default-graph-exec-" + System.nanoTime(), "default-graph-ctx-" + System.nanoTime(), null, NoOpMetrics.INSTANCE, NoOpProfiler.INSTANCE);

        this.graphPipeline.validate();
        this.runnersMap = new HashMap<>();
        Context initContext = this.defaultContextFactory.get().child("graph-runners-init");
        Map<String, PipelineStepRunnerFactory> factories = loadRunnerFactories();

        for (StepConfig stepConfig : this.graphPipeline.getStepsAsMap().values()) {
            String stepId = graphPipeline.getStepId(stepConfig); // Assumes GraphPipeline can provide a stable ID for a StepConfig
            if (stepId == null) throw new IllegalStateException("StepConfig must have a resolvable ID in a GraphPipeline.");

            PipelineStepRunnerFactory factory = factories.get(stepConfig.runnerClassName());
            if (factory == null) {
                throw new IllegalStateException("No PipelineStepRunnerFactory found for runner type: " + stepConfig.runnerClassName());
            }
            PipelineStepRunner runner = factory.create();
            runner.init(stepConfig, initContext.child(stepId + "-init"));
            this.runnersMap.put(stepId, runner);
        }
        logger.info("GraphPipelineExecutor initialized for pipeline '{}' with {} steps.", pipeline.id(), this.runnersMap.size());
    }

    private Map<String, PipelineStepRunnerFactory> loadRunnerFactories() {
        Map<String, PipelineStepRunnerFactory> factories = new ConcurrentHashMap<>();
        ServiceLoader.load(PipelineStepRunnerFactory.class).forEach(factory -> factories.put(factory.getRunnerType(), factory));
        return factories;
    }

    @Override
    public Data exec(Data input) throws Exception {
        return exec(input, defaultContextFactory.get());
    }

    @Override
    public Data exec(Data input, Context context) throws Exception {
        if (closed.get()) throw new IllegalStateException("PipelineExecutor is closed.");
        Objects.requireNonNull(input, "Input Data cannot be null.");
        Objects.requireNonNull(context, "Context cannot be null.");
        String executionId = context.executionId().orElseThrow(() ->
                new IllegalArgumentException("Context must provide an executionId for graph executions."));

        if (pausedExecutions.containsKey(executionId)) {
            throw new IllegalStateException("Execution with ID '" + executionId + "' is already paused. Use resume().");
        }

        Map<String, Data> completedStepOutputs = new HashMap<>();
        completedStepOutputs.put(PIPELINE_INPUT_ID, input); // Initial pipeline input

        Map<String, StepRunState> stepStates = new HashMap<>();
        for (String stepId : graphPipeline.getStepsAsMap().keySet()) {
            stepStates.put(stepId, StepRunState.PENDING);
        }

        return processGraph(executionId, context, stepStates, completedStepOutputs, null);
    }

    @Override
    public Data resume(Context context, Data toolResponses) throws Exception {
        if (closed.get()) throw new IllegalStateException("PipelineExecutor is closed.");
        Objects.requireNonNull(context, "Context cannot be null for resume.");
        Objects.requireNonNull(toolResponses, "Tool responses data cannot be null for resume.");
        String executionId = context.executionId().orElseThrow(() ->
                new IllegalArgumentException("Context must provide an executionId to resume."));

        PausedGraphExecutionState pausedState = pausedExecutions.remove(executionId);
        if (pausedState == null) {
            throw new IllegalStateException("No paused execution found for ID '" + executionId + "'.");
        }

        // The step that paused needs to receive the tool responses.
        // The 'toolResponses' Data object should be merged or passed to the paused step.
        String stepToResumeId = pausedState.pausedByStepId;

        // We will effectively re-run the step that paused, but now with tool_responses in its input.
        // The `pausedState.completedStepOutputs` already contains outputs from *other* completed steps.
        // The input for the `stepToResumeId` needs to be reconstructed, now including `toolResponses`.

        logger.info("Resuming graph execution for ID '{}', focusing on step '{}' with tool responses.", executionId, stepToResumeId);

        // Mark the previously paused step as PENDING or RUNNABLE again to re-evaluate it with new inputs.
        // The actual input construction for this step will happen inside processGraph.
        // We need to ensure processGraph knows to feed toolResponses to this specific step.
        pausedState.stepStates.put(stepToResumeId, StepRunState.PENDING); // Reset state to re-process

        // The `toolResponses` Data object (which contains a list of PipelineToolCallResponse)
        // will be passed to `processGraph` and injected into the input of the step that paused.
        return processGraph(executionId, context, pausedState.stepStates, pausedState.completedStepOutputs, toolResponses);
    }


    private Data processGraph(String executionId, Context currentContext,
                              Map<String, StepRunState> stepStates,
                              Map<String, Data> completedStepOutputs,
                              Data toolResponsesForResumedStep // Null if not a resume, or if resume is for a different step
    ) throws Exception {

        Set<String> runnableSteps = new HashSet<>();
        boolean graphChangedState;

        do {
            graphChangedState = false;
            // Identify runnable steps
            runnableSteps.clear();
            for (String stepId : graphPipeline.getStepsAsMap().keySet()) {
                if (stepStates.get(stepId) == StepRunState.PENDING && areDependenciesMet(stepId, completedStepOutputs)) {
                    runnableSteps.add(stepId);
                }
            }

            if (runnableSteps.isEmpty() && !anyStepRunningOrPaused(stepStates)) {
                // No more runnable steps, and nothing is currently running or paused that could make others runnable.
                // This means the graph execution for this "turn" is complete or stuck.
                break;
            }

            // Execute runnable steps (simplified: sequential execution of ready steps for now)
            // A true graph executor might run these in parallel if independent.
            for (String stepIdToRun : new ArrayList<>(runnableSteps)) { // Copy to allow modification of stepStates
                if (stepStates.get(stepIdToRun) != StepRunState.PENDING) continue; // Already processed in this iteration by a parallel branch

                stepStates.put(stepIdToRun, StepRunState.RUNNING);
                graphChangedState = true;
                logger.debug("Execution ID '{}': Running step '{}'", executionId, stepIdToRun);

                PipelineStepRunner runner = runnersMap.get(stepIdToRun);
                StepConfig config = graphPipeline.getStepsAsMap().get(stepIdToRun);
                Context stepContext = currentContext.child("step-" + stepIdToRun);

                // Prepare input for this step
                Data stepInput = dataFactory.empty();
                // 1. Aggregate inputs from predecessors
                Map<String, String> inputMappings = graphPipeline.getInputDataMappings(stepIdToRun, config);
                for (Map.Entry<String, String> mapping : inputMappings.entrySet()) {
                    String targetInputSlotName = mapping.getKey();
                    String sourceRef = mapping.getValue(); // e.g., "prevStepId.outputSlot" or "@PIPELINE_INPUT@.someKey"

                    String sourceStepId = sourceRef.contains(".") ? sourceRef.substring(0, sourceRef.indexOf('.')) : sourceRef;
                    String sourceOutputKey = sourceRef.contains(".") ? sourceRef.substring(sourceRef.indexOf('.') + 1) : null;

                    Data sourceData = completedStepOutputs.get(sourceStepId);
                    if (sourceData == null) {
                        throw new IllegalStateException("Execution ID '" + executionId + "': Missing output from dependency '" + sourceStepId + "' for step '" + stepIdToRun + "'");
                    }

                    if (sourceOutputKey != null) { // Specific key from source Data
                        if (sourceData.has(sourceOutputKey)) {
                            stepInput.put(targetInputSlotName, Optional.ofNullable(sourceData.get(sourceOutputKey)));
                        } else {
                            logger.warn("Execution ID '{}': Source key '{}' not found in output of step '{}' for step '{}' input '{}'. Skipping.",
                                    executionId, sourceOutputKey, sourceStepId, stepIdToRun, targetInputSlotName);
                        }
                    } else { // Entire output of source step
                        stepInput.merge(sourceData); // Or put under targetInputSlotName if that's the convention
                    }
                }

                // 2. If this is the step being resumed, inject tool responses
                // The `toolResponsesForResumedStep` Data object contains the list of `PipelineToolCallResponse`
                if (toolResponsesForResumedStep != null && stepIdToRun.equals( // Check if this is the step that was paused
                        pausedExecutions.get(executionId) != null ? pausedExecutions.get(executionId).pausedByStepId : null // Check if still in map before removing during resume
                        // This check needs to be more robust; pausedByStepId should be passed to processGraph if resuming a specific step
                )) {
                    if (toolResponsesForResumedStep.has(PipelineDataConstants.TOOL_CALL_RESPONSES_KEY)) {
                        stepInput.put(PipelineDataConstants.TOOL_CALL_RESPONSES_KEY,
                                toolResponsesForResumedStep.getList(PipelineDataConstants.TOOL_CALL_RESPONSES_KEY, ValueType.DATA));
                    }
                    toolResponsesForResumedStep = null; // Consume it for this step
                }


                try {
                    Data stepOutput = currentContext.profiler().profile("step:" + stepIdToRun, () -> runner.exec(stepInput, stepContext));
                    Objects.requireNonNull(stepOutput, "Step " + stepIdToRun + " returned null data.");
                    logger.debug("Execution ID '{}': Step '{}' completed.", executionId, stepIdToRun);

                    if (stepOutput.getBoolean(PipelineDataConstants.IS_TOOL_CALL_REQUEST_FLAG_KEY, false)) {
                        logger.info("Execution ID '{}': Step '{}' requests tool call(s). Pausing graph execution.", executionId, stepIdToRun);
                        stepStates.put(stepIdToRun, StepRunState.PAUSED_AWAITING_TOOLS);
                        // Save the entire graph state
                        pausedExecutions.put(executionId, new PausedGraphExecutionState(
                                new HashMap<>(completedStepOutputs), // outputs so far, *excluding* the current step's tool request
                                new HashMap<>(stepStates),
                                stepIdToRun,
                                stepOutput, // This is the data containing tool call requests
                                currentContext
                        ));
                        return stepOutput; // Return to caller (e.g., KompilePipelineLanguageModelImpl)
                    } else {
                        completedStepOutputs.put(stepIdToRun, stepOutput);
                        stepStates.put(stepIdToRun, StepRunState.COMPLETED);
                    }
                } catch (Exception e) {
                    logger.error("Execution ID '{}': Error executing step '{}'", executionId, stepIdToRun, e);
                    stepStates.put(stepIdToRun, StepRunState.FAILED);
                    // Propagate or handle error based on pipeline's error strategy
                    throw new RuntimeException("Error in step " + stepIdToRun, e);
                }
            } // End for each runnable step

        } while (graphChangedState && !allFinalStepsCompleted(stepStates, completedStepOutputs)); // Loop if state changed and not all final outputs are ready

        // If loop finishes, and no step is PAUSED, then execution for this "turn" is done.
        if (anyStepPaused(stepStates)) {
            // This should not happen if pause returns immediately. Defensive.
            throw new IllegalStateException("Execution ID '" + executionId + "': Graph processing loop ended but a step is still paused.");
        }

        pausedExecutions.remove(executionId); // Clean up if successfully completed this turn without a pause
        return aggregateFinalOutput(completedStepOutputs);
    }

    private boolean anyStepRunningOrPaused(Map<String, StepRunState> states) {
        return states.values().stream().anyMatch(s -> s == StepRunState.RUNNING || s == StepRunState.PAUSED_AWAITING_TOOLS);
    }
    private boolean anyStepPaused(Map<String, StepRunState> states) {
        return states.values().stream().anyMatch(s -> s == StepRunState.PAUSED_AWAITING_TOOLS);
    }


    private boolean areDependenciesMet(String stepId, Map<String, Data> completedStepOutputs) {
        Set<String> predecessorStepIds = graphPipeline.getPredecessorStepIds(stepId);
        if (predecessorStepIds.isEmpty() && !graphPipeline.getInitialStepIds().contains(stepId)) { // True initial step needs pipeline input
            return completedStepOutputs.containsKey(PIPELINE_INPUT_ID);
        }
        for (String depId : predecessorStepIds) {
            if (!completedStepOutputs.containsKey(depId)) {
                return false;
            }
        }
        return true;
    }

    private boolean allFinalStepsCompleted(Map<String, StepRunState> stepStates, Map<String, Data> completedStepOutputs) {
        List<String> finalStepIds = graphPipeline.getFinalOutputContributingStepIds();
        if (finalStepIds.isEmpty()) { // If no specific final steps, graph is done when no more PENDING/RUNNABLE
            return stepStates.values().stream().noneMatch(s -> s == StepRunState.PENDING || s == StepRunState.RUNNABLE || s == StepRunState.RUNNING);
        }
        for (String stepId : finalStepIds) {
            if (stepStates.get(stepId) != StepRunState.COMPLETED) {
                return false;
            }
        }
        return true;
    }

    private Data aggregateFinalOutput(Map<String, Data> completedStepOutputs) {
        // Aggregation logic depends on how GraphPipeline defines its final output.
        // For example, merge outputs of all steps marked as "final output contributors".
        Data finalOutput = dataFactory.empty();
        List<String> finalStepIds = graphPipeline.getFinalOutputContributingStepIds();

        if (finalStepIds.isEmpty()) { // No specific final steps, maybe take output of last completed step(s) or all leaves
            // This logic needs to be clearly defined by the GraphPipeline contract
            // For now, merge all available outputs if no specific final steps.
            logger.warn("No specific final output steps defined for pipeline '{}'. Merging all completed step outputs.", graphPipeline.id());
            for(Data outputData : completedStepOutputs.values()){
                if(outputData != completedStepOutputs.get(PIPELINE_INPUT_ID)) { // Don't merge back the initial input
                    finalOutput.merge(outputData);
                }
            }
        } else {
            for (String stepId : finalStepIds) {
                Data stepOutputData = completedStepOutputs.get(stepId);
                if (stepOutputData != null) {
                    // Option 1: Merge all final outputs
                    // finalOutput.merge(stepOutputData);
                    // Option 2: Put each final output under its stepId key
                    finalOutput.put(stepId, stepOutputData);
                } else {
                    logger.warn("Expected final output from step '{}' not found in completed outputs for pipeline '{}'.", stepId, graphPipeline.id());
                }
            }
        }
        if (finalOutput.isEmpty() && !runnersMap.isEmpty()) {
            logger.warn("Graph pipeline '{}' execution resulted in an empty aggregated output.", graphPipeline.id());
        }
        return finalOutput;
    }

    // --- Async resume and other methods ---
    @Override
    public CompletableFuture<Data> execAsync(Data input) {
        return execAsync(input, defaultContextFactory.get());
    }

    @Override
    public CompletableFuture<Data> execAsync(Data input, Context context) {
        if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("PipelineExecutor is closed."));
        return CompletableFuture.supplyAsync(() -> {
            try {
                return exec(input, context);
            } catch (Exception e) { throw new RuntimeException(e); }
        }, asyncExecutorService);
    }

    @Override
    public CompletableFuture<Data> resumeAsync(Context context, Data toolResponses) {
        if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("PipelineExecutor is closed."));
        return CompletableFuture.supplyAsync(() -> {
            try {
                return resume(context, toolResponses);
            } catch (Exception e) { throw new RuntimeException(e); }
        }, asyncExecutorService);
    }
    @Override
    public void execAsync(Data input, Consumer<Data> onSuccess, Consumer<Throwable> onFailure) {
        execAsync(input, defaultContextFactory.get(), onSuccess, onFailure);
    }

    @Override
    public void execAsync(Data input, Context context, Consumer<Data> onSuccess, Consumer<Throwable> onFailure) {
        execAsync(input, context).thenAccept(onSuccess).exceptionally(err -> {onFailure.accept(err); return null;});
    }
    @Override
    public void resumeAsync(Context context, Data toolResponses, Consumer<Data> onSuccess, Consumer<Throwable> onFailure) {
        resumeAsync(context, toolResponses).thenAccept(onSuccess).exceptionally(err -> {onFailure.accept(err); return null;});
    }

    // execStream for Graph is non-trivial and depends heavily on graph structure and desired stream semantics.
    // A simple implementation might perform a full graph execution and return its result(s) as a single-element stream
    // if the graph doesn't naturally lend itself to streaming multiple distinct Data outputs over time.
    // If tool calls are involved, making execStream resumable is even more complex.
    @Override
    public Iterator<Data> execStream(Data initialInput) throws Exception {
        return execStream(initialInput, defaultContextFactory.get());
    }
    @Override
    public Iterator<Data> execStream(Data initialInput, Context context) throws Exception {
        // For a graph, a simple stream might be a single execution result.
        // Or, if the graph has multiple designated output steps, stream them one by one after full execution.
        // This version will just execute and return final result as a single item stream for simplicity.
        // It does NOT support pausing/resuming for tool calls within the stream itself in this basic form.
        Data result = exec(initialInput, context);
        if (result.getBoolean(PipelineDataConstants.IS_TOOL_CALL_REQUEST_FLAG_KEY, false)){
            logger.warn("execStream for GraphPipeline received tool call request. Streaming will yield this request. Resumption must happen outside the stream context for this executionId.");
        }
        return Collections.singletonList(result).iterator();
    }
    @Override
    public Iterator<Data> execStream(Pipeline pipeline, Data initialInput, Context context) throws Exception {
        if (!(pipeline instanceof GraphPipeline)) {
            throw new IllegalArgumentException("GraphPipelineExecutor can only execute GraphPipeline instances for streaming.");
        }
        if (this.graphPipeline != pipeline) {
            // This executor is tied to its initially configured graphPipeline and runners.
            // Executing a different pipeline instance here would require re-initialization or different design.
            throw new UnsupportedOperationException("This GraphPipelineExecutor instance is bound to a specific GraphPipeline.");
        }
        return execStream(initialInput, context); // Delegate to the instance's pipeline
    }


    @Override
    public Pipeline getPipeline() {
        return graphPipeline;
    }

    @Override
    public void close() throws Exception {
        if (closed.compareAndSet(false, true)) {
            logger.info("Closing GraphPipelineExecutor for pipeline '{}'...", graphPipeline.id());
            pausedExecutions.clear();
            if (asyncExecutorService != null && !asyncExecutorService.isShutdown()) { // Assuming we own it if we created it
                asyncExecutorService.shutdown();
            }
            if (ownRunners) {
                List<Exception> closeExceptions = new ArrayList<>();
                for (PipelineStepRunner runner : runnersMap.values()) {
                    try {
                        runner.close();
                    } catch (Exception e) {
                        closeExceptions.add(e);
                        logger.error("Error closing runner " + runner.getClass().getName(), e);
                    }
                }
                if (!closeExceptions.isEmpty()) {
                    throw new RuntimeException("Errors closing runners: " + closeExceptions);
                }
            }
            logger.info("GraphPipelineExecutor for pipeline '{}' closed.", graphPipeline.id());
        }
    }


}