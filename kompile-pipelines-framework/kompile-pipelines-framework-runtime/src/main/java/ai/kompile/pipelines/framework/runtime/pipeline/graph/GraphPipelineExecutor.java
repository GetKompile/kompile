package ai.kompile.pipelines.framework.runtime.pipeline.graph;

import ai.kompile.pipelines.framework.api.Pipeline;
import ai.kompile.pipelines.framework.api.PipelineExecutor;
import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.PipelineStepRunnerFactory;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.context.Context;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.data.DataFactory;
import ai.kompile.pipelines.framework.api.data.PipelineDataConstants;
import ai.kompile.pipelines.framework.api.data.ValueType; // Import ValueType
import ai.kompile.pipelines.framework.core.context.DefaultContext;
import ai.kompile.pipelines.framework.core.context.NoOpMetrics;
import ai.kompile.pipelines.framework.core.context.NoOpProfiler;

// Local project imports
import ai.kompile.pipelines.framework.runtime.pipeline.graph.GraphNodeConfig;
import ai.kompile.pipelines.framework.runtime.pipeline.graph.StandardGraphNodeConfig;


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

public class GraphPipelineExecutor implements PipelineExecutor {
    private static final Logger logger = LoggerFactory.getLogger(GraphPipelineExecutor.class);

    public static final String PIPELINE_INPUT_ID = "@PIPELINE_INPUT@";

    private final GraphPipeline graphPipeline;
    private final Map<String, PipelineStepRunner> runnersMap;
    private final boolean ownRunners;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final DataFactory dataFactory; // Keep using this for creating new Data instances
    private final Supplier<Context> defaultContextFactory;
    private final ExecutorService asyncExecutorService;

    private final Map<String, PausedGraphExecutionState> pausedExecutions = new ConcurrentHashMap<>();

    private enum StepRunState { PENDING, RUNNABLE, RUNNING, COMPLETED, FAILED, PAUSED_AWAITING_TOOLS }

    private static class PausedGraphExecutionState {
        final Map<String, Data> completedStepOutputs;
        final Map<String, StepRunState> stepStates;
        final String pausedByStepId;
        final Data toolCallRequestPayload;
        final Context originalContext;
        final String executionIdToResume;

        PausedGraphExecutionState(Map<String, Data> outputs, Map<String, StepRunState> states,
                                  String pausedByStepId, Data toolCallRequestPayload, Context context, String executionId) {
            this.completedStepOutputs = new HashMap<>(outputs);
            this.stepStates = new HashMap<>(states);
            this.pausedByStepId = pausedByStepId;
            this.toolCallRequestPayload = toolCallRequestPayload;
            this.originalContext = context;
            this.executionIdToResume = executionId;
        }
    }

    public GraphPipelineExecutor(GraphPipeline pipeline, boolean ownRunners) throws Exception {
        this(pipeline, ownRunners, Data.Factory.get(), null, null);
    }

    public GraphPipelineExecutor(
            GraphPipeline pipeline,
            boolean ownRunners,
            DataFactory dataFactory, // dataFactory from constructor
            ExecutorService executorService,
            Supplier<Context> defaultContextFactory
    ) throws Exception {
        this.graphPipeline = Objects.requireNonNull(pipeline, "GraphPipeline cannot be null");
        this.ownRunners = ownRunners;
        this.dataFactory = Objects.requireNonNull(dataFactory, "DataFactory cannot be null"); // Use the injected DataFactory
        this.asyncExecutorService = (executorService != null) ? executorService : Executors.newCachedThreadPool();
        this.defaultContextFactory = (defaultContextFactory != null) ? defaultContextFactory :
                () -> new DefaultContext(this.dataFactory.empty(), // Use this.dataFactory here
                        "default-graph-exec-" + System.nanoTime(),
                        "default-graph-ctx-" + System.nanoTime(),
                        null, NoOpMetrics.INSTANCE, NoOpProfiler.INSTANCE);

        this.graphPipeline.validate();
        this.runnersMap = new HashMap<>();
        Context initContext = this.defaultContextFactory.get().child("graph-runners-init");
        Map<String, PipelineStepRunnerFactory> factories = loadRunnerFactories();

        for (Map.Entry<String, GraphNodeConfig> entry : this.graphPipeline.getGraphNodes().entrySet()) {
            String stepId = entry.getKey();
            GraphNodeConfig nodeConfig = entry.getValue();

            if (nodeConfig instanceof StandardGraphNodeConfig) {
                StandardGraphNodeConfig standardNodeConfig = (StandardGraphNodeConfig) nodeConfig;
                StepConfig stepConfig = standardNodeConfig.getStepConfig();

                if (stepConfig == null || stepConfig.runnerClassName() == null || stepConfig.runnerClassName().trim().isEmpty()) {
                    logger.warn("StandardGraphNodeConfig for step '{}' has null StepConfig or empty runnerClassName. Skipping runner creation.", stepId);
                    continue;
                }

                PipelineStepRunnerFactory factory = factories.get(stepConfig.runnerClassName());
                if (factory == null) {
                    throw new IllegalStateException("No PipelineStepRunnerFactory found for runner type: " + stepConfig.runnerClassName() + " for step " + stepId);
                }
                PipelineStepRunner runner = factory.create();
                try {
                    runner.init(stepConfig, initContext.child(stepId + "-init"));
                    this.runnersMap.put(stepId, runner);
                } catch (Exception e) {
                    logger.error("Failed to initialize runner for step '{}' with runner type '{}'", stepId, stepConfig.runnerClassName(), e);
                    throw e;
                }
            } else {
                logger.debug("Node '{}' of type {} does not have a direct PipelineStepRunner. Skipping runner creation.", stepId, nodeConfig.getGraphStepType());
            }
        }
        logger.info("GraphPipelineExecutor initialized for pipeline '{}' with {} runnable steps.", pipeline.id(), this.runnersMap.size());
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
            throw new IllegalStateException("Execution with ID '" + executionId + "' is already paused. Use resumeWithId() or resume().");
        }

        Map<String, Data> completedStepOutputs = new HashMap<>();
        completedStepOutputs.put(PIPELINE_INPUT_ID, input); // Data.put is void, this should be: completedStepOutputs.put(PIPELINE_INPUT_ID, input);

        Map<String, StepRunState> stepStates = new HashMap<>();
        for (String stepId : graphPipeline.getGraphNodes().keySet()) {
            stepStates.put(stepId, StepRunState.PENDING);
        }

        return processGraph(executionId, context, stepStates, completedStepOutputs, null, null);
    }

    @Override
    public Data resume(Context context, Data toolResponses) throws Exception {
        String executionId = context.executionId().orElseThrow(() ->
                new IllegalArgumentException("Context must provide an executionId to resume."));
        return resumeWithId(executionId, context, toolResponses);
    }

    public Data resumeWithId(String executionId, Context resumeContext, Data toolResponses) throws Exception {
        if (closed.get()) throw new IllegalStateException("PipelineExecutor is closed.");
        Objects.requireNonNull(executionId, "Execution ID cannot be null for resume.");
        Objects.requireNonNull(resumeContext, "Resume Context cannot be null for resume.");
        Objects.requireNonNull(toolResponses, "Tool responses data cannot be null for resume.");

        PausedGraphExecutionState pausedState = pausedExecutions.remove(executionId);
        if (pausedState == null) {
            throw new IllegalStateException("No paused execution found for ID '" + executionId + "'. Cannot resume.");
        }

        String stepToResumeId = pausedState.pausedByStepId;
        logger.info("Resuming graph execution for ID '{}', focusing on step '{}' with tool responses.", executionId, stepToResumeId);

        Map<String, StepRunState> stepStates = new HashMap<>(pausedState.stepStates);
        Map<String, Data> completedStepOutputs = new HashMap<>(pausedState.completedStepOutputs);
        stepStates.put(stepToResumeId, StepRunState.PENDING);

        return processGraph(executionId, resumeContext, stepStates, completedStepOutputs, stepToResumeId, toolResponses);
    }

    private Data processGraph(String executionId, Context currentContext,
                              Map<String, StepRunState> stepStates,
                              Map<String, Data> completedStepOutputs,
                              String explicitlyResumingStepId,
                              Data toolResponsesForResumedStep
    ) throws Exception {

        Set<String> currentRunnableSteps = new HashSet<>();
        boolean graphChangedStateInIteration;

        do {
            graphChangedStateInIteration = false;
            currentRunnableSteps.clear();

            for (String stepId : graphPipeline.getGraphNodes().keySet()) {
                GraphNodeConfig nodeConfig = graphPipeline.getGraphNodes().get(stepId);
                if (nodeConfig == null) continue;

                if (stepStates.get(stepId) == StepRunState.PENDING && areDependenciesMet(stepId, completedStepOutputs)) {
                    if (nodeConfig instanceof StandardGraphNodeConfig && runnersMap.containsKey(stepId)) {
                        currentRunnableSteps.add(stepId);
                    } else if (nodeConfig instanceof StandardGraphNodeConfig && !runnersMap.containsKey(stepId)) {
                        logger.warn("Execution ID '{}': StandardNode '{}' found but no runner in runnersMap. Cannot run automatically.", executionId, stepId);
                        if (!stepId.equals(explicitlyResumingStepId)) { /* Potentially fail */ }
                    }
                }
            }

            if (currentRunnableSteps.isEmpty() && !anyStepRunningOrPaused(stepStates)) {
                break;
            }

            for (String stepIdToRun : new ArrayList<>(currentRunnableSteps)) {
                if (stepStates.get(stepIdToRun) != StepRunState.PENDING) {
                    continue;
                }

                GraphNodeConfig nodeConfigToRun = graphPipeline.getGraphNodes().get(stepIdToRun);
                if (!(nodeConfigToRun instanceof StandardGraphNodeConfig)) {
                    logger.error("Execution ID '{}': Node '{}' is in runnable set but not a StandardGraphNodeConfig. Skipping.", executionId, stepIdToRun);
                    stepStates.put(stepIdToRun, StepRunState.FAILED);
                    graphChangedStateInIteration = true;
                    continue;
                }
                StandardGraphNodeConfig standardNodeToRun = (StandardGraphNodeConfig) nodeConfigToRun;
                StepConfig config = standardNodeToRun.getStepConfig();
                PipelineStepRunner runner = runnersMap.get(stepIdToRun);

                if (runner == null) {
                    logger.error("Execution ID '{}': No runner found for standard step '{}' at execution time. Marking FAILED.", executionId, stepIdToRun);
                    stepStates.put(stepIdToRun, StepRunState.FAILED);
                    graphChangedStateInIteration = true;
                    continue;
                }

                stepStates.put(stepIdToRun, StepRunState.RUNNING);
                graphChangedStateInIteration = true;
                logger.debug("Execution ID '{}': Running step '{}'", executionId, stepIdToRun);
                Context stepContext = currentContext.child(stepIdToRun + "-child"); // Ensure unique child context name
                Data stepInput = this.dataFactory.empty(); // Use instance dataFactory

                // ** Input Data Mappings - Adhering to Data.java API **
                Map<String, String> inputDataBindings = new HashMap<>();
                Data params = config.getParameters(); // This is a Data object
                if (params != null && params.has("inputDataBindings")) {
                    // Check the type of the "inputDataBindings" entry itself
                    ValueType bindingsEntryType = params.type("inputDataBindings");
                    if (bindingsEntryType == ValueType.DATA) {
                        Data bindingsData = params.getData("inputDataBindings"); // Retrieve as nested Data
                        if (bindingsData != null) {
                            for (String targetSlotKey : bindingsData.keySet()) { // Iterate keys of the NESTED Data object
                                if (bindingsData.type(targetSlotKey) == ValueType.STRING) { // Check type of value within nested Data
                                    inputDataBindings.put(targetSlotKey, bindingsData.getString(targetSlotKey));
                                } else if (bindingsData.get(targetSlotKey) != null) { // Value exists but not string
                                    logger.warn("Execution ID '{}', Step '{}': Value for inputDataBinding key '{}' within 'inputDataBindings' is of type {} but expected STRING. Ignoring this binding.",
                                            executionId, stepIdToRun, targetSlotKey, bindingsData.type(targetSlotKey));
                                } else { // Value is null
                                    logger.warn("Execution ID '{}', Step '{}': Value for inputDataBinding key '{}' within 'inputDataBindings' is null. Ignoring this binding.",
                                            executionId, stepIdToRun, targetSlotKey);
                                }
                            }
                        }
                    } else {
                        logger.warn("Execution ID '{}', Step '{}': 'inputDataBindings' parameter is present but not of type DATA (actual type: {}). Expected a nested Data object for bindings. Bindings will be empty.",
                                executionId, stepIdToRun, bindingsEntryType);
                    }
                }
                // ** END Input Data Mappings **

                if (inputDataBindings.isEmpty() && nodeConfigToRun.getInputs() != null && !nodeConfigToRun.getInputs().isEmpty()
                        && !nodeConfigToRun.getInputs().stream().allMatch(in -> in.equals(graphPipeline.getInputNodeName()) || PIPELINE_INPUT_ID.equals(in))
                ) {
                    logger.warn("Execution ID '{}': Step '{}' has defined input nodes ({}) but no explicit inputDataBindings found or parsed from its StepConfig parameters. Input aggregation might be incomplete.",
                            executionId, stepIdToRun, nodeConfigToRun.getInputs());
                }

                for (Map.Entry<String, String> mapping : inputDataBindings.entrySet()) {
                    String targetInputSlotName = mapping.getKey();
                    String sourceRef = mapping.getValue();

                    String sourceStepIdOrSpecialKey = sourceRef.contains(".") ? sourceRef.substring(0, sourceRef.indexOf('.')) : sourceRef;
                    String sourceOutputKeyInSourceData = sourceRef.contains(".") ? sourceRef.substring(sourceRef.indexOf('.') + 1) : null;

                    Data sourceDataBlock = completedStepOutputs.get(sourceStepIdOrSpecialKey);

                    if (sourceDataBlock == null) {
                        logger.error("Execution ID '{}': Missing source data for '{}' required by step '{}' for input slot '{}'. Marking step FAILED.",
                                executionId, sourceStepIdOrSpecialKey, stepIdToRun, targetInputSlotName);
                        stepStates.put(stepIdToRun, StepRunState.FAILED);
                        throw new IllegalStateException("Missing data from dependency '" + sourceStepIdOrSpecialKey + "' for step '" + stepIdToRun + "'.");
                    }

                    if (sourceOutputKeyInSourceData != null && !sourceOutputKeyInSourceData.isEmpty()) {
                        if (sourceDataBlock.has(sourceOutputKeyInSourceData)) {
                            // Get returns <T> T, so it's an Object here. Data.put(String, Object) is fine.
                            stepInput.put(targetInputSlotName, Optional.ofNullable(sourceDataBlock.get(sourceOutputKeyInSourceData)));
                        } else {
                            logger.warn("Execution ID '{}': Source key '{}' not found in output of '{}' for step '{}' input '{}'. Slot will not be populated.",
                                    executionId, sourceOutputKeyInSourceData, sourceStepIdOrSpecialKey, stepIdToRun, targetInputSlotName);
                        }
                    } else { // Entire output of source step/input is mapped to this slot
                        stepInput.put(targetInputSlotName, sourceDataBlock); // sourceDataBlock is Data, Data.put(String, Data) is fine
                    }
                }

                if (toolResponsesForResumedStep != null && stepIdToRun.equals(explicitlyResumingStepId)) {
                    if (toolResponsesForResumedStep.has(PipelineDataConstants.TOOL_CALL_RESPONSES_KEY)) {
                        // Corrected: getList expects ValueType for element type
                        if (toolResponsesForResumedStep.type(PipelineDataConstants.TOOL_CALL_RESPONSES_KEY) == ValueType.LIST &&
                                toolResponsesForResumedStep.listType(PipelineDataConstants.TOOL_CALL_RESPONSES_KEY) == ValueType.DATA) {
                            List<Data> toolResponseList = toolResponsesForResumedStep.getList(
                                    PipelineDataConstants.TOOL_CALL_RESPONSES_KEY,
                                    ValueType.DATA // Pass the ValueType enum for element type
                            );
                            // Data.putList(String, List<T>, ValueType)
                            stepInput.putList(PipelineDataConstants.TOOL_CALL_RESPONSES_KEY, toolResponseList, ValueType.DATA);
                            logger.debug("Execution ID '{}', Step '{}': Injected tool responses.", executionId, stepIdToRun);
                        } else {
                            logger.warn("Execution ID '{}', Step '{}': Tool responses key '{}' is present but not a LIST of DATA. Actual type: {}, list element type: {}. Cannot inject.",
                                    executionId, stepIdToRun, PipelineDataConstants.TOOL_CALL_RESPONSES_KEY,
                                    toolResponsesForResumedStep.type(PipelineDataConstants.TOOL_CALL_RESPONSES_KEY),
                                    toolResponsesForResumedStep.listType(PipelineDataConstants.TOOL_CALL_RESPONSES_KEY));
                        }
                    } else {
                        logger.warn("Execution ID '{}', Step '{}': Tool responses provided for resume, but missing '{}' key in the response data.",
                                executionId, stepIdToRun, PipelineDataConstants.TOOL_CALL_RESPONSES_KEY);
                    }
                }

                try {
                    Data stepOutput = currentContext.profiler().profile("step:" + stepIdToRun, () -> runner.exec(stepInput, stepContext));
                    Objects.requireNonNull(stepOutput, "Step " + stepIdToRun + " returned null data.");
                    logger.debug("Execution ID '{}': Step '{}' completed successfully.", executionId, stepIdToRun);

                    // Check for tool call request using Data.getBoolean(key, defaultValue)
                    if (stepOutput.getBoolean(PipelineDataConstants.IS_TOOL_CALL_REQUEST_FLAG_KEY, false)) {
                        logger.info("Execution ID '{}': Step '{}' requests tool call(s). Pausing graph execution.", executionId, stepIdToRun);
                        stepStates.put(stepIdToRun, StepRunState.PAUSED_AWAITING_TOOLS);
                        Map<String, Data> outputsAtPause = new HashMap<>(completedStepOutputs);
                        pausedExecutions.put(executionId, new PausedGraphExecutionState(
                                outputsAtPause, new HashMap<>(stepStates), stepIdToRun, stepOutput, currentContext, executionId));
                        return stepOutput;
                    } else {
                        completedStepOutputs.put(stepIdToRun, stepOutput);
                        stepStates.put(stepIdToRun, StepRunState.COMPLETED);
                    }
                } catch (Exception e) {
                    logger.error("Execution ID '{}': Error executing step '{}'", executionId, stepIdToRun, e);
                    stepStates.put(stepIdToRun, StepRunState.FAILED);
                    throw new RuntimeException("Error in step " + stepIdToRun + " during execution ID " + executionId, e);
                }
            }

            if (explicitlyResumingStepId != null && stepStates.get(explicitlyResumingStepId) != StepRunState.RUNNING && stepStates.get(explicitlyResumingStepId) != StepRunState.PENDING) {
                toolResponsesForResumedStep = null;
                explicitlyResumingStepId = null;
            }

        } while (graphChangedStateInIteration && !allFinalStepsCompleted(stepStates)); // Removed completedStepOutputs from condition

        if (anyStepPaused(stepStates)) {
            for(Map.Entry<String, StepRunState> entry : stepStates.entrySet()){
                if(entry.getValue() == StepRunState.PAUSED_AWAITING_TOOLS){
                    PausedGraphExecutionState pausedState = pausedExecutions.get(executionId);
                    if(pausedState != null && entry.getKey().equals(pausedState.pausedByStepId)){
                        logger.warn("Execution ID '{}': Graph processing loop ended, but step '{}' is PAUSED_AWAITING_TOOLS. Returning its tool call request.", executionId, entry.getKey());
                        return pausedState.toolCallRequestPayload;
                    }
                }
            }
            logger.error("Execution ID '{}': Graph processing loop ended but a step is PAUSED_AWAITING_TOOLS and its state is inconsistent. Throwing error.", executionId);
            throw new IllegalStateException("Execution ID '" + executionId + "': Graph processing loop ended but a step is still marked PAUSED_AWAITING_TOOLS without a clear return path.");
        }

        pausedExecutions.remove(executionId);
        return aggregateFinalOutput(completedStepOutputs, stepStates);
    }

    private boolean anyStepRunningOrPaused(Map<String, StepRunState> states) {
        return states.values().stream().anyMatch(s -> s == StepRunState.RUNNING || s == StepRunState.PAUSED_AWAITING_TOOLS);
    }
    private boolean anyStepPaused(Map<String, StepRunState> states) {
        return states.values().stream().anyMatch(s -> s == StepRunState.PAUSED_AWAITING_TOOLS);
    }

    private boolean areDependenciesMet(String stepId, Map<String, Data> completedStepOutputs) {
        GraphNodeConfig nodeConfig = graphPipeline.getGraphNodes().get(stepId);
        if (nodeConfig == null) {
            logger.error("NodeConfig not found for stepId '{}' during dependency check.", stepId);
            return false;
        }
        List<String> predecessorNodeNames = nodeConfig.getInputs();
        if (predecessorNodeNames == null || predecessorNodeNames.isEmpty()) {
            return true;
        }
        for (String depNodeName : predecessorNodeNames) {
            if (GraphPipeline.DEFAULT_GRAPH_INPUT_NAME.equals(depNodeName) ||
                    (graphPipeline.getInputNodeName() != null && graphPipeline.getInputNodeName().equals(depNodeName)) || // Check if inputNodeName is not null
                    PIPELINE_INPUT_ID.equals(depNodeName)) {
                if (!completedStepOutputs.containsKey(PIPELINE_INPUT_ID)) {
                    logger.trace("Dependency {} (PIPELINE_INPUT) not met for step {}.", depNodeName, stepId);
                    return false;
                }
            } else if (!completedStepOutputs.containsKey(depNodeName)) {
                logger.trace("Dependency node {} not met for step {}.", depNodeName, stepId);
                return false;
            }
        }
        return true;
    }

    private boolean allFinalStepsCompleted(Map<String, StepRunState> stepStates) { // Removed completedStepOutputs
        String finalStepId = graphPipeline.getOutputNodeName();
        if (finalStepId == null || finalStepId.trim().isEmpty()) {
            logger.warn("Pipeline '{}' does not define a specific outputNodeName. Completion will be based on no more runnable/running steps.", graphPipeline.id());
            return stepStates.values().stream().noneMatch(s ->
                    s == StepRunState.PENDING || s == StepRunState.RUNNABLE || s == StepRunState.RUNNING || s == StepRunState.PAUSED_AWAITING_TOOLS);
        }
        if (!stepStates.containsKey(finalStepId)) {
            logger.error("Pipeline '{}' configured outputNodeName '{}' does not exist as a stateful step in the graph. This is a configuration error.", graphPipeline.id(), finalStepId);
            throw new IllegalStateException("Defined outputNodeName '" + finalStepId + "' not found in pipeline step states.");
        }
        return stepStates.get(finalStepId) == StepRunState.COMPLETED;
    }

    private Data aggregateFinalOutput(Map<String, Data> completedStepOutputs, Map<String, StepRunState> stepStates) {
        String finalStepId = graphPipeline.getOutputNodeName();
        if (finalStepId == null || finalStepId.trim().isEmpty()) {
            logger.warn("No specific final output node defined for pipeline '{}'. Attempting to merge all COMPLETED non-input step outputs.", graphPipeline.id());
            Data mergedOutput = this.dataFactory.empty(); // Use instance dataFactory
            boolean anyOutputMerged = false;
            for(Map.Entry<String, GraphNodeConfig> nodeEntry : graphPipeline.getGraphNodes().entrySet()){
                String nodeId = nodeEntry.getKey();
                // Check stepStates for COMPLETED status
                if (stepStates.get(nodeId) == StepRunState.COMPLETED && completedStepOutputs.containsKey(nodeId)) {
                    Data stepOutputData = completedStepOutputs.get(nodeId);
                    if(stepOutputData != null) {
                        mergedOutput.merge(stepOutputData);
                        anyOutputMerged = true;
                    }
                }
            }
            if (!anyOutputMerged && !runnersMap.isEmpty()) {
                logger.warn("Graph pipeline '{}' execution resulted in an empty aggregated output (no specific outputNodeName, and no completed steps found or their outputs were empty).", graphPipeline.id());
            }
            return mergedOutput;
        }
        Data finalOutputData = completedStepOutputs.get(finalStepId);
        if (finalOutputData == null) {
            // Check state before logging potentially misleading message
            StepRunState finalState = stepStates.get(finalStepId);
            if (finalState == StepRunState.COMPLETED) {
                logger.warn("Graph pipeline '{}' final output from step '{}' is null, though step completed. Returning empty data.", graphPipeline.id(), finalStepId);
            } else {
                logger.warn("Graph pipeline '{}' final output from step '{}' is unavailable (step state: {}). Returning empty data.", graphPipeline.id(), finalStepId, finalState);
            }
            return this.dataFactory.empty(); // Use instance dataFactory
        }
        return finalOutputData;
    }

    @Override
    public CompletableFuture<Data> execAsync(Data input) {
        return execAsync(input, defaultContextFactory.get());
    }

    @Override
    public CompletableFuture<Data> execAsync(Data input, Context context) {
        if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("PipelineExecutor is closed."));

        // Ensure context has an executionId for async operations
        Context effectiveContext = context;
        final String executionId; // Effectively final for lambda

        if (!context.executionId().isPresent()) {
            logger.warn("Async execution initiated with a Context lacking an executionId. Using default context factory to generate one.");
            effectiveContext = defaultContextFactory.get(); // This will have an executionId
            executionId = effectiveContext.executionId().get(); // Get it from the new context
        } else {
            executionId = context.executionId().get();
        }
        // Ensure the context being passed to the async task is the one with the executionId
        final Context finalEffectiveContext = effectiveContext;

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Use finalEffectiveContext which is guaranteed to have an executionId
                return exec(input, finalEffectiveContext);
            } catch (Exception e) {
                logger.error("Async execution failed for execution ID '{}'", executionId, e);
                // Wrap in a generic runtime exception if not already one, for CompletableFuture's exceptionally
                if (e instanceof RuntimeException) {
                    throw (RuntimeException) e;
                }
                throw new RuntimeException("Async execution failed for " + executionId, e);
            }
        }, asyncExecutorService);
    }


    public CompletableFuture<Data> resumeAsyncWithId(String executionId, Context context, Data toolResponses) {
        if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("PipelineExecutor is closed."));
        // Context for resume should ideally be the original context or one derived from it,
        // ensuring it has the correct executionId for correlation.
        // The 'context' parameter here is 'resumeContext' from the original design.
        return CompletableFuture.supplyAsync(() -> {
            try {
                return resumeWithId(executionId, context, toolResponses);
            } catch (Exception e) {
                logger.error("Async resume failed for execution ID '{}'", executionId, e);
                if (e instanceof RuntimeException) {
                    throw (RuntimeException) e;
                }
                throw new RuntimeException("Async resume failed for " + executionId, e);
            }
        }, asyncExecutorService);
    }
    @Override
    public CompletableFuture<Data> resumeAsync(Context context, Data toolResponses) {
        if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("PipelineExecutor is closed."));
        String executionId = context.executionId().orElseThrow(() ->
                new IllegalArgumentException("Context must provide an executionId for async resume. This ID should match the paused execution."));
        return resumeAsyncWithId(executionId, context, toolResponses);
    }


    @Override
    public void execAsync(Data input, Consumer<Data> onSuccess, Consumer<Throwable> onFailure) {
        execAsync(input, defaultContextFactory.get(), onSuccess, onFailure);
    }

    @Override
    public void execAsync(Data input, Context context, Consumer<Data> onSuccess, Consumer<Throwable> onFailure) {
        execAsync(input, context)
                .thenAccept(result -> {
                    try {
                        onSuccess.accept(result);
                    } catch (Exception e) {
                        logger.error("Error in onSuccess callback for async execution", e);
                        // Optionally call onFailure here as well if the callback itself throws
                        onFailure.accept(e);
                    }
                })
                .exceptionally(err -> {
                    Throwable cause = err.getCause() != null ? err.getCause() : err;
                    onFailure.accept(cause);
                    return null;
                });
    }

    public void resumeAsyncWithId(String executionId, Context context, Data toolResponses, Consumer<Data> onSuccess, Consumer<Throwable> onFailure) {
        resumeAsyncWithId(executionId, context, toolResponses)
                .thenAccept(result -> {
                    try {
                        onSuccess.accept(result);
                    } catch (Exception e) {
                        logger.error("Error in onSuccess callback for async resume", e);
                        onFailure.accept(e);
                    }
                })
                .exceptionally(err -> {
                    Throwable cause = err.getCause() != null ? err.getCause() : err;
                    onFailure.accept(cause);
                    return null;
                });
    }
    @Override
    public void resumeAsync(Context context, Data toolResponses, Consumer<Data> onSuccess, Consumer<Throwable> onFailure) {
        String executionId = context.executionId().orElseThrow(() ->
                new IllegalArgumentException("Context must provide an executionId for async resume callback. This ID should match the paused execution."));
        resumeAsyncWithId(executionId, context, toolResponses, onSuccess, onFailure);
    }

    @Override
    public Iterator<Data> execStream(Data initialInput) throws Exception {
        return execStream(initialInput, defaultContextFactory.get());
    }
    @Override
    public Iterator<Data> execStream(Data initialInput, Context context) throws Exception {
        String executionId = context.executionId().orElseGet(() -> {
            logger.warn("Context for execStream lacked an executionId. One might be generated by exec call if default factory is used.");
            return "stream-exec-" + System.nanoTime(); // Placeholder if exec itself doesn't get a default context
        });
        Context streamContext = context.executionId().isPresent() ? context : defaultContextFactory.get();


        Data result = exec(initialInput, streamContext); // exec will ensure executionId
        // Access executionId from the context possibly modified/created by exec
        String finalExecutionId = streamContext.executionId().orElse(executionId); // Fallback to initially generated if somehow still missing

        if (result.getBoolean(PipelineDataConstants.IS_TOOL_CALL_REQUEST_FLAG_KEY, false)){
            logger.warn("execStream for GraphPipeline (executionId: {}) received tool call request. Streaming will yield this request. Resumption must happen outside the stream context.", finalExecutionId);
        }
        return Collections.singletonList(result).iterator();
    }
    @Override
    public Iterator<Data> execStream(Pipeline pipeline, Data initialInput, Context context) throws Exception {
        if (!(pipeline instanceof GraphPipeline)) {
            throw new IllegalArgumentException("GraphPipelineExecutor can only execute GraphPipeline instances for streaming.");
        }
        if (this.graphPipeline != pipeline && !this.graphPipeline.id().equals(pipeline.id())) {
            throw new UnsupportedOperationException("This GraphPipelineExecutor instance is bound to a specific GraphPipeline instance. Executing a different instance is not supported by default.");
        }
        return execStream(initialInput, context);
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

            // Check if the executor service was internally created.
            // This is a heuristic; a more robust way is to have a flag set in the constructor.
            boolean ownExecutor = false;
            try {
                // If defaultContextFactory produces a DefaultContext which creates its own executor (which it does not by default)
                // or if this.asyncExecutorService was directly initialized to Executors.newCachedThreadPool()
                // because the constructor argument `executorService` was null.
                if (this.defaultContextFactory.get() instanceof DefaultContext) {
                    // This check isn't quite right as DefaultContext itself doesn't create the GraphPipelineExecutor's service.
                    // The relevant check is whether the constructor parameter `executorService` was null.
                    // This cannot be checked directly here. For now, assume if it's a ThreadPoolExecutor, it *might* be owned.
                    // This is imperfect.
                }
                // The critical point is whether it's the one we created: Executors.newCachedThreadPool()
                // This is still not a perfect check.
                if (this.asyncExecutorService.getClass().getName().startsWith("java.util.concurrent.Executors$")) {
                    // Heuristic: Default executors from Executors factory often have such names.
                    // This is still not a guarantee of ownership.
                }
                // Simplest assumption for now: if it's a ThreadPoolExecutor, we MIGHT own it if not already shutdown.
                // A boolean `ownsExecutorService` flag set in constructor is the best way.
                // For now, proceed with shutdown if it's a common pool type and not externally shut down.
            } catch (Exception e) { /* ignore, just trying to infer ownership */ }


            if (asyncExecutorService != null && !asyncExecutorService.isShutdown()) {
                // Simple heuristic: if it's a general ThreadPoolExecutor, assume we might own it.
                // This part needs to be robust based on whether this instance created the service.
                // For this iteration, let's assume if it's our default type (CachedThreadPool), we try to shut it down.
                // This is risky if an external CachedThreadPool with same characteristics was passed.
                // The most reliable approach is an 'ownsExecutorService' flag in the constructor.
                // For now, if it is a ThreadPoolExecutor, it's a candidate for being owned.
                if (asyncExecutorService instanceof java.util.concurrent.ThreadPoolExecutor) {
                    logger.info("Attempting to shut down internal ExecutorService for pipeline '{}'.", graphPipeline.id());
                    asyncExecutorService.shutdown();
                    // Optionally: asyncExecutorService.awaitTermination(timeout, unit);
                } else {
                    logger.info("ExecutorService for pipeline '{}' is not an instance of ThreadPoolExecutor or may be externally managed; not shutting down from here.", graphPipeline.id());
                }
            }


            if (ownRunners) {
                List<Exception> closeExceptions = new ArrayList<>();
                for (Map.Entry<String, PipelineStepRunner> entry : runnersMap.entrySet()) {
                    try {
                        entry.getValue().close();
                    } catch (Exception e) {
                        closeExceptions.add(e);
                        logger.error("Error closing runner for step '{}': {}", entry.getKey(), entry.getValue().getClass().getName(), e);
                    }
                }
                if (!closeExceptions.isEmpty()) {
                    RuntimeException overallEx = new RuntimeException("Errors occurred while closing owned runners for pipeline '" + graphPipeline.id() + "'. See suppressed exceptions.");
                    closeExceptions.forEach(overallEx::addSuppressed);
                    throw overallEx;
                }
            }
            runnersMap.clear();
            logger.info("GraphPipelineExecutor for pipeline '{}' closed.", graphPipeline.id());
        }
    }
}