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

// Location: kompile-pipelines-framework/kompile-pipelines-framework-runtime/src/main/java/ai/kompile/pipelines/framework/runtime/pipeline/graph/
package ai.kompile.pipelines.framework.runtime.pipeline.graph;

import ai.kompile.pipelines.framework.api.Pipeline;
import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.PipelineStepRunnerFactory;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.context.Context;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.data.DataFactory;
import ai.kompile.pipelines.framework.api.data.PipelineDataConstants;
import ai.kompile.pipelines.framework.api.data.ValueType;
import ai.kompile.pipelines.framework.api.llm.LLMStepConfig;
import ai.kompile.pipelines.framework.core.context.DefaultContext;
import ai.kompile.pipelines.framework.core.context.NoOpMetrics;
import ai.kompile.pipelines.framework.core.context.NoOpProfiler;
import ai.kompile.pipelines.framework.runtime.pipeline.BasePipelineExecutor;
import ai.kompile.pipelines.framework.runtime.tooling.PipelineToolCallOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class GraphPipelineExecutor extends BasePipelineExecutor {

    public static final String PIPELINE_INPUT_ID = "@PIPELINE_INPUT@";

    private final GraphPipeline graphPipeline;
    private final Map<String, PipelineStepRunner> runnersMapLocal = new HashMap<>();
    private final PipelineToolCallOrchestrator toolOrchestrator;
    private final int maxToolCallingDepthConfig;
    private final DataFactory dataFactoryInstance;
    private final Supplier<Context> defaultContextFactoryInternal;

    private final Map<String, PausedGraphExecutionState> pausedExecutions = new ConcurrentHashMap<>();

    private enum StepRunState { PENDING, RUNNABLE, RUNNING, COMPLETED, FAILED, PAUSED_FOR_TOOLS }

    private static class PausedGraphExecutionState {
        final Map<String, Data> completedStepOutputsAtPause;
        final Map<String, StepRunState> stepStatesAtPause;
        final String pausedByLlmStepId;
        final Data toolCallRequestPayloadFromLlm;
        final Context originalTurnContext;

        PausedGraphExecutionState(Map<String, Data> outputs, Map<String, StepRunState> states,
                                  String pausedByLlmStepId, Data toolCallRequestPayloadFromLlm, Context context) {
            this.completedStepOutputsAtPause = new HashMap<>(outputs);
            this.stepStatesAtPause = new HashMap<>(states);
            this.pausedByLlmStepId = pausedByLlmStepId;
            this.toolCallRequestPayloadFromLlm = toolCallRequestPayloadFromLlm.dup();
            this.originalTurnContext = context;
        }
    }

    public GraphPipelineExecutor(
            GraphPipeline pipeline,
            boolean ownRunners,
            DataFactory dataFactory,
            ExecutorService executorService,
            Supplier<Context> defaultContextFactory
    ) throws Exception {
        super(pipeline, false);
        int maxToolCallingDepthConfig1;
        this.graphPipeline = Objects.requireNonNull(pipeline, "GraphPipeline cannot be null");
        this.dataFactoryInstance = (dataFactory != null) ? dataFactory : Data.Factory.get();

        this.defaultContextFactoryInternal = (defaultContextFactory != null) ? defaultContextFactory :
                () -> new DefaultContext(this.dataFactoryInstance.empty(),
                        "graph-exec-" + System.nanoTime(),
                        "graph-ctx-" + System.nanoTime(),
                        null, NoOpMetrics.INSTANCE, NoOpProfiler.INSTANCE);

        String maxDepthStr = System.getProperty("kompile.pipelines.toolcalling.maxDepth", "5");
        try {
            maxToolCallingDepthConfig1 = Integer.parseInt(maxDepthStr);
        } catch (NumberFormatException e) {
            maxToolCallingDepthConfig1 = 5;
        }

        this.maxToolCallingDepthConfig = maxToolCallingDepthConfig1;
        this.toolOrchestrator = new PipelineToolCallOrchestrator(
                this.graphPipeline,
                super.runnerFactories,
                new ObjectMapper(),
                this.maxToolCallingDepthConfig
        );
        initializeGraphRunners(this.defaultContextFactoryInternal.get());
    }

    public GraphPipelineExecutor(GraphPipeline pipeline, boolean ownRunners) throws Exception {
        this(pipeline, ownRunners, Data.Factory.get(), null,
                () -> new DefaultContext(Data.Factory.get().empty(), "graph-exec-" + System.nanoTime(), "graph-ctx-" + System.nanoTime(), null, NoOpMetrics.INSTANCE, NoOpProfiler.INSTANCE));
    }

    private void initializeGraphRunners(Context rootInitContext) throws Exception {
        if (graphPipeline.getGraphNodes() == null) {
            return;
        }
        Context baseInitContext = rootInitContext.child("graph-runners-init-" + graphPipeline.id());

        for (Map.Entry<String, GraphNodeConfig> entry : this.graphPipeline.getGraphNodes().entrySet()) {
            String stepId = entry.getKey();
            GraphNodeConfig nodeConfig = entry.getValue();

            if (nodeConfig instanceof StandardGraphNodeConfig) {
                StandardGraphNodeConfig standardNodeConfig = (StandardGraphNodeConfig) nodeConfig;
                StepConfig stepConfig = standardNodeConfig.getStepConfig();

                if (stepConfig == null || stepConfig.runnerClassName() == null || stepConfig.runnerClassName().trim().isEmpty()) {
                    continue;
                }

                PipelineStepRunnerFactory factory = super.runnerFactories.get(stepConfig.runnerClassName());
                if (factory == null) {
                    throw new IllegalStateException("No PipelineStepRunnerFactory found for runner class: " + stepConfig.runnerClassName() + " for graph step " + stepId);
                }
                PipelineStepRunner runner = factory.create();
                try {
                    String runnerInitContextName = (stepConfig.runnerClassName() != null ? stepConfig.runnerClassName() : stepId) + "-init:" + runner.getClass().getSimpleName();
                    runner.init(stepConfig, baseInitContext.child(runnerInitContextName));
                    this.runnersMapLocal.put(stepId, runner);
                } catch (Exception e) {
                    throw new Exception("Failed to initialize runner for graph step '" + stepId + "' with runner type '" + stepConfig.runnerClassName() + "'", e);
                }
            }
        }
    }

    @Override
    public void ensureRunnersInitialized() throws Exception {
        if (this.runnersMapLocal.isEmpty() && this.graphPipeline.getGraphNodes() != null && !this.graphPipeline.getGraphNodes().isEmpty()) {
            initializeGraphRunners(this.defaultContextFactoryInternal.get());
        }
    }

    @Override
    public Data exec(Data input, Context context) throws Exception {
        super.checkIfClosed();
        Objects.requireNonNull(input, "Input Data cannot be null.");
        Objects.requireNonNull(context, "Context cannot be null.");
        ensureRunnersInitialized();

        String executionId = context.executionId().orElseThrow(() ->
                new IllegalArgumentException("Context must provide an executionId for graph executions."));

        if (pausedExecutions.containsKey(executionId)) {
            throw new IllegalStateException("Execution with ID '" + executionId + "' is already paused. Use resume() to continue.");
        }

        Map<String, Data> completedStepOutputs = new HashMap<>();
        completedStepOutputs.put(PIPELINE_INPUT_ID, input.dup());

        Map<String, StepRunState> stepStates = new HashMap<>();
        for (String stepId : graphPipeline.getGraphNodes().keySet()) {
            stepStates.put(stepId, StepRunState.PENDING);
        }
        stepStates.put(PIPELINE_INPUT_ID, StepRunState.COMPLETED);

        return processGraph(executionId, context, stepStates, completedStepOutputs, null, null);
    }

    @Override
    public Data resume(Context context, Data toolResponsesFromExternal) throws Exception {
        super.checkIfClosed();
        Objects.requireNonNull(context, "Context cannot be null for resume.");
        Objects.requireNonNull(toolResponsesFromExternal, "Tool responses data cannot be null for resume.");
        ensureRunnersInitialized();

        String executionId = context.executionId().orElseThrow(() ->
                new IllegalArgumentException("Context must provide an executionId to resume an execution."));

        PausedGraphExecutionState pausedState = pausedExecutions.remove(executionId);
        if (pausedState == null) {
            throw new IllegalStateException("No paused execution found for ID '" + executionId + "'. Cannot resume.");
        }

        String llmStepToResumeId = pausedState.pausedByLlmStepId;

        Map<String, StepRunState> stepStates = new HashMap<>(pausedState.stepStatesAtPause);
        Map<String, Data> completedStepOutputs = new HashMap<>(pausedState.completedStepOutputsAtPause);

        stepStates.put(llmStepToResumeId, StepRunState.PENDING);

        return processGraph(executionId, context, stepStates, completedStepOutputs, llmStepToResumeId, toolResponsesFromExternal);
    }

    private Data processGraph(String executionId, Context currentPipelineContext,
                              Map<String, StepRunState> stepStates,
                              Map<String, Data> completedStepOutputs,
                              String explicitlyResumingLlmStepId,
                              Data toolResponsesForLlmStep
    ) throws Exception {

        boolean graphChangedStateInIteration;
        int iterations = 0;
        int maxIterations = graphPipeline.getGraphNodes().size() * 3 + 20;

        do {
            iterations++;
            if(iterations > maxIterations) {
                throw new IllegalStateException("Graph execution exceeded max iterations for ID " + executionId + ". Potential deadlock or cycle. Current states: " + stepStates);
            }

            graphChangedStateInIteration = false;
            Set<String> currentRunnableSteps = new HashSet<>();

            for (String stepId : graphPipeline.getGraphNodes().keySet()) {
                if (stepStates.get(stepId) == StepRunState.PENDING && areDependenciesMet(stepId, completedStepOutputs, stepStates)) {
                    GraphNodeConfig nodeConfig = graphPipeline.getGraphNodes().get(stepId);
                    if (nodeConfig instanceof StandardGraphNodeConfig && runnersMapLocal.containsKey(stepId)) {
                        currentRunnableSteps.add(stepId);
                    } else if (nodeConfig.getGraphStepType() == GraphStepType.COMBINE_FN) { // Corrected from COMBINE
                        CombineNodeConfig combineConfig = (CombineNodeConfig) nodeConfig;
                        Data combinedData = Data.empty();
                        for(String inputNodeId : combineConfig.getInputs()){
                            String effectiveInputNodeId = inputNodeId.equals(graphPipeline.getInputNodeName()) ? PIPELINE_INPUT_ID : inputNodeId;
                            Data inputData = completedStepOutputs.get(effectiveInputNodeId);
                            if(inputData != null) combinedData.merge(inputData);
                        }
                        // The CombineNodeConfig in the uploaded file does not have a getCombineFn() that returns a Java Function.
                        // It has getCombineFunctionClassName() and getCombineFunctionParams().
                        // Executing this requires instantiating and calling the CombineFn.
                        // This logic is complex and assumed to be handled by a dedicated runner or different mechanism
                        // if COMBINE_FN nodes are to be executed directly by the GraphPipelineExecutor without a runner.
                        // For this iteration, we'll assume a CombineFn node would be wrapped in a StandardGraphNodeConfig
                        // with a specific runner if it needs active execution beyond simple merging.
                        // If it's just data merging, the current logic is a simplified merge.
                        // If a custom CombineFn class needs to be invoked:
                        if (combineConfig.getCombineFunctionClassName() != null && !combineConfig.getCombineFunctionClassName().isEmpty()) {
                            try {
                                Class<?> fnClass = Class.forName(combineConfig.getCombineFunctionClassName());
                                CombineFn combineFnInstance = (CombineFn) fnClass.getDeclaredConstructor().newInstance();
                                // CombineFn might need its own init with combineConfig.getCombineFunctionParams()
                                // This is a simplified invocation.
                                combinedData = combineFnInstance.combine(combinedData, combineConfig.getCombineFunctionParams());
                            } catch (Exception e) {
                                throw new RuntimeException("Failed to execute CombineFn for node " + stepId, e);
                            }
                        }
                        completedStepOutputs.put(stepId, combinedData.dup());
                        stepStates.put(stepId, StepRunState.COMPLETED);
                        graphChangedStateInIteration = true;
                    }
                }
            }

            if (currentRunnableSteps.isEmpty() && !anyStepRunningOrPaused(stepStates) && !anyStepPendingWithMetDependencies(stepStates, completedStepOutputs)) {
                break;
            }

            for (String stepIdToRun : new ArrayList<>(currentRunnableSteps)) {
                if (stepStates.get(stepIdToRun) != StepRunState.PENDING) continue;

                StandardGraphNodeConfig standardNodeToRun = (StandardGraphNodeConfig) graphPipeline.getGraphNodes().get(stepIdToRun);
                StepConfig currentStepConfig = standardNodeToRun.getStepConfig();
                PipelineStepRunner runner = runnersMapLocal.get(stepIdToRun);

                stepStates.put(stepIdToRun, StepRunState.RUNNING);
                graphChangedStateInIteration = true;
                String stepContextName = (currentStepConfig.runnerClassName() != null ? currentStepConfig.runnerClassName() : stepIdToRun);
                Context stepContext = currentPipelineContext.child(stepContextName + "-turn-" + iterations + "-" + System.nanoTime());

                Data stepInput = prepareStepInput(standardNodeToRun, completedStepOutputs, executionId);

                if (stepIdToRun.equals(explicitlyResumingLlmStepId) && toolResponsesForLlmStep != null && currentStepConfig instanceof LLMStepConfig) {
                    LLMStepConfig llmConfig = (LLMStepConfig) currentStepConfig;
                    if(toolResponsesForLlmStep.has(llmConfig.getToolCallResponseInputName())){
                        stepInput.put(llmConfig.getToolCallResponseInputName(),
                                Optional.ofNullable(toolResponsesForLlmStep.get(llmConfig.getToolCallResponseInputName())));
                    }
                }

                Data stepOutput;
                String profilerEventName = "GraphPipelineExecutor.step:" + stepContextName; // Using stepContextName for profiler
                currentPipelineContext.profiler().startEvent(profilerEventName);
                try {
                    if (currentStepConfig instanceof LLMStepConfig && runner.isInitialized()) {
                        LLMStepConfig llmConfig = (LLMStepConfig) currentStepConfig;
                        stepOutput = toolOrchestrator.executeLLMStepWithInternalToolLoop(
                                runner, llmConfig, stepInput, stepContext);
                    } else {
                        stepOutput = runner.exec(stepInput, stepContext);
                    }
                    Objects.requireNonNull(stepOutput, "Step " + stepIdToRun + " returned null data.");

                    if (stepOutput.getBoolean(PipelineDataConstants.IS_TOOL_CALL_REQUEST_FLAG_KEY, false) &&
                            currentStepConfig instanceof LLMStepConfig) {
                        stepStates.put(stepIdToRun, StepRunState.PAUSED_FOR_TOOLS);
                        Map<String, Data> outputsAtPause = new HashMap<>(completedStepOutputs);
                        pausedExecutions.put(executionId, new PausedGraphExecutionState(
                                outputsAtPause, new HashMap<>(stepStates), stepIdToRun,
                                stepOutput.dup(), currentPipelineContext));
                        currentPipelineContext.profiler().stopEvent(); // Stop event before returning
                        return stepOutput;
                    } else {
                        completedStepOutputs.put(stepIdToRun, stepOutput.dup());
                        stepStates.put(stepIdToRun, StepRunState.COMPLETED);
                    }
                } catch (Exception e) {
                    stepStates.put(stepIdToRun, StepRunState.FAILED);
                    // Ensure profiler event is stopped on error
                    currentPipelineContext.profiler().stopEvent();
                    throw new RuntimeException("Error in step " + stepIdToRun + " during execution ID " + executionId, e);
                } finally {
                    // Ensure profiler event is stopped if not returned early
                    if (stepStates.get(stepIdToRun) != StepRunState.PAUSED_FOR_TOOLS) {
                        currentPipelineContext.profiler().stopEvent();
                    }
                }
            }

            if (explicitlyResumingLlmStepId != null &&
                    (stepStates.get(explicitlyResumingLlmStepId) == StepRunState.COMPLETED ||
                            stepStates.get(explicitlyResumingLlmStepId) == StepRunState.FAILED ||
                            stepStates.get(explicitlyResumingLlmStepId) == StepRunState.PAUSED_FOR_TOOLS)) {
                explicitlyResumingLlmStepId = null;
                toolResponsesForLlmStep = null;
            }

        } while (graphChangedStateInIteration && !allFinalStepsCompleted(stepStates, completedStepOutputs));

        if (anyStepPaused(stepStates)) {
            for(Map.Entry<String, StepRunState> entry : stepStates.entrySet()){
                if(entry.getValue() == StepRunState.PAUSED_FOR_TOOLS){
                    PausedGraphExecutionState pausedState = pausedExecutions.get(executionId);
                    if(pausedState != null && entry.getKey().equals(pausedState.pausedByLlmStepId)){
                        return pausedState.toolCallRequestPayloadFromLlm;
                    }
                }
            }
            throw new IllegalStateException("Execution ID '" + executionId + "': Graph processing loop ended with paused steps in an inconsistent state.");
        }

        pausedExecutions.remove(executionId);
        return aggregateFinalOutput(completedStepOutputs, stepStates);
    }

    private boolean anyStepPendingWithMetDependencies(Map<String, StepRunState> stepStates, Map<String, Data> completedStepOutputs) {
        for (String stepId : graphPipeline.getGraphNodes().keySet()) {
            if (stepStates.get(stepId) == StepRunState.PENDING && areDependenciesMet(stepId, completedStepOutputs, stepStates)) {
                return true;
            }
        }
        return false;
    }

    private Data prepareStepInput(StandardGraphNodeConfig nodeConfig, Map<String, Data> completedStepOutputs, String executionId) {
        Data stepInput = Data.empty(); // Use static factory method
        List<String> inputNodeIds = nodeConfig.getInputs();

        if (inputNodeIds == null || inputNodeIds.isEmpty()) {
            return stepInput;
        }

        StepConfig currentStepConfig = nodeConfig.getStepConfig();
        Map<String, String> inputDataBindings = new HashMap<>();
        Data paramsData = currentStepConfig.getParameters();

        if (paramsData != null && paramsData.has("inputDataBindings")) {
            if (paramsData.type("inputDataBindings") == ValueType.DATA) {
                Data bindingsData = paramsData.getData("inputDataBindings");
                if (bindingsData != null) {
                    for (String targetSlotKey : bindingsData.keySet()) {
                        if (bindingsData.type(targetSlotKey) == ValueType.STRING) {
                            inputDataBindings.put(targetSlotKey, bindingsData.getString(targetSlotKey));
                        }
                    }
                }
            }
        }

        if (inputDataBindings.isEmpty()) {
            if (inputNodeIds.size() == 1) {
                String sourceNodeId = inputNodeIds.get(0);
                String effectiveSourceNodeId = sourceNodeId.equals(graphPipeline.getInputNodeName()) ? PIPELINE_INPUT_ID : sourceNodeId;
                Data sourceData = completedStepOutputs.get(effectiveSourceNodeId);
                if (sourceData != null) {
                    return sourceData.dup();
                }
            }
            return stepInput;
        }

        for (Map.Entry<String, String> mapping : inputDataBindings.entrySet()) {
            String targetInputSlotName = mapping.getKey();
            String sourceRef = mapping.getValue();

            String sourceStepIdOrSpecialKey = sourceRef.contains(".") ? sourceRef.substring(0, sourceRef.indexOf('.')) : sourceRef;
            String sourceOutputKeyInSourceData = sourceRef.contains(".") ? sourceRef.substring(sourceRef.indexOf('.') + 1) : null;

            if (sourceStepIdOrSpecialKey.equals(graphPipeline.getInputNodeName())) {
                sourceStepIdOrSpecialKey = PIPELINE_INPUT_ID;
            }
            Data sourceDataBlock = completedStepOutputs.get(sourceStepIdOrSpecialKey);

            if (sourceDataBlock == null) continue;

            if (sourceOutputKeyInSourceData != null && !sourceOutputKeyInSourceData.isEmpty()) {
                if (sourceDataBlock.has(sourceOutputKeyInSourceData)) {
                    stepInput.put(targetInputSlotName, Optional.ofNullable(sourceDataBlock.get(sourceOutputKeyInSourceData)));
                }
            } else {
                stepInput.put(targetInputSlotName, sourceDataBlock.dup());
            }
        }
        return stepInput;
    }

    private boolean areDependenciesMet(String stepId, Map<String, Data> completedStepOutputs, Map<String, StepRunState> stepStates) {
        GraphNodeConfig nodeConfig = graphPipeline.getGraphNodes().get(stepId);
        if (nodeConfig == null) return false;
        List<String> predecessorNodeNames = nodeConfig.getInputs();
        if (predecessorNodeNames == null || predecessorNodeNames.isEmpty()) return true;

        for (String depNodeName : predecessorNodeNames) {
            String effectiveDepName = depNodeName.equals(graphPipeline.getInputNodeName()) ? PIPELINE_INPUT_ID : depNodeName;

            if (!PIPELINE_INPUT_ID.equals(effectiveDepName) && stepStates.get(effectiveDepName) != StepRunState.COMPLETED) {
                return false;
            } else if (PIPELINE_INPUT_ID.equals(effectiveDepName) && !completedStepOutputs.containsKey(PIPELINE_INPUT_ID)) {
                return false;
            }
        }
        return true;
    }

    private boolean anyStepRunningOrPaused(Map<String, StepRunState> states) {
        return states.values().stream().anyMatch(s -> s == StepRunState.RUNNING || s == StepRunState.PAUSED_FOR_TOOLS);
    }
    private boolean anyStepPaused(Map<String, StepRunState> states) {
        return states.values().stream().anyMatch(s -> s == StepRunState.PAUSED_FOR_TOOLS);
    }

    private boolean allFinalStepsCompleted(Map<String, StepRunState> stepStates, Map<String, Data> completedStepOutputs) {
        String outputNodeName = graphPipeline.getOutputNodeName(); // Singular based on GraphPipeline.java
        if (outputNodeName == null || outputNodeName.trim().isEmpty()) {
            // If no specific output node, graph is "complete" when no more steps can run and none are paused.
            return !anyStepRunningOrPaused(stepStates) && !anyStepPendingWithMetDependencies(stepStates, completedStepOutputs);
        }
        // If specific output node, it must be completed.
        return stepStates.get(outputNodeName) == StepRunState.COMPLETED;
    }

    private Data aggregateFinalOutput(Map<String, Data> completedStepOutputs, Map<String, StepRunState> stepStates) {
        String outputNodeName = graphPipeline.getOutputNodeName(); // Singular
        Data finalOutput = Data.empty(); // Corrected

        if (outputNodeName == null || outputNodeName.trim().isEmpty()) {
            // No specific output node, merge all completed, non-PIPELINE_INPUT_ID outputs
            for (Map.Entry<String, Data> entry : completedStepOutputs.entrySet()) {
                if (!PIPELINE_INPUT_ID.equals(entry.getKey()) &&
                        stepStates.containsKey(entry.getKey()) &&
                        stepStates.get(entry.getKey()) == StepRunState.COMPLETED) {
                    finalOutput.merge(entry.getValue());
                }
            }
            return finalOutput;
        }

        // Specific output node defined
        Data stepOutput = completedStepOutputs.get(outputNodeName);
        if (stepOutput != null && stepStates.get(outputNodeName) == StepRunState.COMPLETED) {
            finalOutput.merge(stepOutput); // Merge to handle if it's a Data object itself
        }
        return finalOutput;
    }

    @Override
    public CompletableFuture<Data> resumeAsync(Context context, Data toolResponses) {
        super.checkIfClosed();
        Objects.requireNonNull(context, "Context cannot be null for async resume.");
        Objects.requireNonNull(toolResponses, "Tool responses data cannot be null for async resume.");
        String executionId = context.executionId().orElseThrow(() ->
                new IllegalArgumentException("Context must provide an executionId for async resume."));

        return CompletableFuture.supplyAsync(() -> {
            try {
                ensureRunnersInitialized();
                return resume(context, toolResponses);
            } catch (Exception e) {
                if (e instanceof RuntimeException) throw (RuntimeException) e;
                throw new RuntimeException("Async resume failed for " + executionId, e);
            }
        }, super.asyncExecutorService); // Use asyncExecutorService from BasePipelineExecutor
    }

    @Override
    public void resumeAsync(Context context, Data toolResponses, Consumer<Data> onSuccess, Consumer<Throwable> onFailure) {
        super.checkIfClosed();
        Objects.requireNonNull(context, "Context cannot be null.");
        Objects.requireNonNull(toolResponses, "Tool responses cannot be null.");
        Objects.requireNonNull(onSuccess, "onSuccess callback cannot be null.");
        Objects.requireNonNull(onFailure, "onFailure callback cannot be null.");

        resumeAsync(context, toolResponses)
                .thenAccept(result -> {
                    try { onSuccess.accept(result); }
                    catch (Exception e) { onFailure.accept(e); }
                })
                .exceptionally(err -> {
                    Throwable cause = err.getCause() != null ? err.getCause() : err;
                    onFailure.accept(cause);
                    return null;
                });
    }

    @Override
    public Iterator<Data> execStream(Data initialInput) throws Exception {
        Context streamRootContext = this.defaultContextFactoryInternal.get();
        return execStream(initialInput, streamRootContext);
    }

    @Override
    public Iterator<Data> execStream(Data initialInput, Context context) throws Exception {
        super.checkIfClosed();
        Objects.requireNonNull(initialInput, "Initial input data cannot be null for execStream.");
        Objects.requireNonNull(context, "Context cannot be null for execStream.");
        ensureRunnersInitialized();

        Data finalResult = this.exec(initialInput, context);
        return Collections.singletonList(finalResult).iterator();
    }

    @Override
    public Iterator<Data> execStream(Pipeline pipelineToStream, Data initialInput, Context context) throws Exception {
        if (this.graphPipeline != pipelineToStream && !Objects.equals(this.graphPipeline.id(), pipelineToStream.id())) {
            throw new IllegalArgumentException("GraphPipelineExecutor is bound to a specific pipeline instance.");
        }
        return execStream(initialInput, context);
    }

    @Override
    public void close() throws Exception {
        if (super.closed) return; // Use closed from BasePipelineExecutor

        // Call super.close() first to handle BasePipelineExecutor's responsibilities (like asyncExecutorService)
        // BasePipelineExecutor.close() also handles closing runners in its `this.runners` list,
        // which is empty for GraphPipelineExecutor as it uses runnersMapLocal.
        super.close();

        List<Exception> closeExceptions = new ArrayList<>();
        // GraphPipelineExecutor is responsible for runnersMapLocal.
        // The `ownRunners` flag in BasePipelineExecutor applies to its `runners` list.
        // We assume GraphPipelineExecutor always "owns" the runners in runnersMapLocal.
        for (Map.Entry<String, PipelineStepRunner> entry : runnersMapLocal.entrySet()) {
            try {
                if (entry.getValue().isInitialized()) {
                    entry.getValue().close();
                }
            } catch (Exception e) {
                closeExceptions.add(e);
            }
        }
        runnersMapLocal.clear();
        pausedExecutions.clear();

        if (!closeExceptions.isEmpty()) {
            RuntimeException overallEx = new RuntimeException("Errors occurred while closing graph runners for pipeline '" + graphPipeline.id() + "'. See suppressed exceptions.");
            closeExceptions.forEach(overallEx::addSuppressed);
            throw overallEx;
        }
    }
}
