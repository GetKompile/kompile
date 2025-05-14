package ai.kompile.pipelines.framework.runtime.pipeline.graph;

import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.PipelineStepRunnerFactory;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.context.Context;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.core.context.DefaultContext;
import ai.kompile.pipelines.framework.runtime.pipeline.BasePipelineExecutor;
import ai.kompile.pipelines.framework.runtime.pipeline.graph.*;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Executes a {@link GraphPipeline}.
 * This executor manages the directed acyclic graph (DAG) of steps,
 * handling data flow and specialized graph nodes like MERGE, SWITCH, ANY, and COMBINE_FN.
 * <p>
 * The current execution model processes nodes in a topological order.
 * For nodes that can run in parallel (independent branches), this implementation
 * will execute them sequentially based on their order in the ready queue.
 * True parallelism would require submitting tasks to the asyncExecutorService and managing futures.
 */

public class GraphPipelineExecutor extends BasePipelineExecutor {

    private final GraphPipeline graphPipeline;
    // Adjacency list: nodeName -> list of its direct successor nodeNames
    private final Map<String, List<String>> successorsMap;
    // Predecessor list: nodeName -> list of its direct predecessor nodeNames (actual graph nodes, not the pipeline input string)
    private final Map<String, List<String>> predecessorsMap;

    private final Map<String, PipelineStepRunner> nodeRunners;
    private final Map<String, SwitchFn> switchFunctions;
    private final Map<String, CombineFn> combineFunctions;

    // To store which successors a SWITCH node has selected for the current execution
    private final ThreadLocal<Map<String, List<String>>> currentExecutionSwitchSelections = ThreadLocal.withInitial(HashMap::new);


    public GraphPipelineExecutor(GraphPipeline pipeline, boolean initializeRunners) throws Exception {
        super(pipeline, false); // Base initializes common fields; we handle specific init
        this.graphPipeline = Objects.requireNonNull(pipeline, "GraphPipeline cannot be null.");
        this.successorsMap = new ConcurrentHashMap<>();
        this.predecessorsMap = new ConcurrentHashMap<>();
        this.nodeRunners = new ConcurrentHashMap<>();
        this.switchFunctions = new ConcurrentHashMap<>();
        this.combineFunctions = new ConcurrentHashMap<>();

        parseAndValidateGraphStructure();

        if (initializeRunners) {
            initializeGraphNodeRunnersAndFunctions();
        }
    }

    private void parseAndValidateGraphStructure() {
        Map<String, GraphNodeConfig> nodes = graphPipeline.getGraphNodes();

        if (nodes.isEmpty()) {
            if (!GraphPipeline.DEFAULT_GRAPH_INPUT_NAME.equals(graphPipeline.getOutputNodeName())) {
                throw new IllegalStateException("Graph pipeline " + graphPipeline.id() + " has no nodes, but output node '" +
                        graphPipeline.getOutputNodeName() + "' is not the default pipeline input name ('" +
                        GraphPipeline.DEFAULT_GRAPH_INPUT_NAME + "'). An empty graph must output its input.");
            }
            return;
        }

        // Initialize maps
        nodes.keySet().forEach(nodeName -> {
            successorsMap.put(nodeName, new ArrayList<>());
            predecessorsMap.put(nodeName, new ArrayList<>());
        });

        // Build successor and predecessor maps
        for (Map.Entry<String, GraphNodeConfig> entry : nodes.entrySet()) {
            String nodeName = entry.getKey();
            GraphNodeConfig nodeConfig = entry.getValue();

            if (nodeConfig.getInputs() == null) {
                throw new IllegalStateException(String.format("Node '%s' in pipeline '%s' has a null inputs list.", nodeName, graphPipeline.id()));
            }

            for (String inputSourceName : nodeConfig.getInputs()) {
                if (inputSourceName == null || inputSourceName.trim().isEmpty()) {
                    throw new IllegalStateException(String.format("Node '%s' in pipeline '%s' has a null or empty input source name.", nodeName, graphPipeline.id()));
                }

                if (!inputSourceName.equals(graphPipeline.getInputNodeName())) { // If it's a regular node
                    if (!nodes.containsKey(inputSourceName)) {
                        throw new IllegalStateException(String.format("Node '%s' in pipeline '%s' declares input from undefined node: '%s'.", nodeName, graphPipeline.id(), inputSourceName));
                    }
                    successorsMap.get(inputSourceName).add(nodeName);
                    predecessorsMap.get(nodeName).add(inputSourceName);
                }
                // No need to add to predecessorsMap for PIPELINE_INPUT here, as in-degree calculation will handle it by checking nodeConfig.getInputs()
            }
        }

        // Validate output node existence
        if (!graphPipeline.getOutputNodeName().equals(GraphPipeline.DEFAULT_GRAPH_INPUT_NAME) &&
                !nodes.containsKey(graphPipeline.getOutputNodeName())) {
            throw new IllegalStateException(String.format("Output node '%s' is not defined in the graph nodes for pipeline: %s",
                    graphPipeline.getOutputNodeName(), graphPipeline.id()));
        }

        // Basic cycle detection using Kahn's algorithm preparation (will be done fully in exec)
        // is implicitly part of the validation during topological sort.
        // A more explicit cycle check during parsing can be added if needed.
        detectCycles(); // Perform an explicit cycle check during parsing
    }


    private void initializeGraphNodeRunnersAndFunctions() throws Exception {
        // Use a shared root context for the initialization phase of all runners/functions.
        // The originalInput for this init context is empty as it's not an execution context.
        Context initRootContext = new DefaultContext(Data.empty(), graphPipeline.id() + "-init", "graph-init", null, null, null);

        for (Map.Entry<String, GraphNodeConfig> entry : graphPipeline.getGraphNodes().entrySet()) {
            String nodeName = entry.getKey();
            GraphNodeConfig nodeConfig = entry.getValue();
            Context nodeInitContext = initRootContext.child("init.node." + nodeName);


            if (nodeConfig instanceof StandardGraphNodeConfig) {
                StandardGraphNodeConfig stdConfig = (StandardGraphNodeConfig) nodeConfig;
                StepConfig actualStepConfig = stdConfig.getStepConfig();
                String runnerClassName = actualStepConfig.runnerClassName();
                PipelineStepRunnerFactory factory = runnerFactories.get(runnerClassName);
                if (factory == null) {
                    throw new IllegalStateException(String.format(
                            "No PipelineStepRunnerFactory found for runner class '%s' of STANDARD node '%s'. Ensure factory is registered.",
                            runnerClassName, nodeName));
                }
                PipelineStepRunner runner = factory.create();
                runner.init(actualStepConfig, nodeInitContext); // Pass node-specific init context
                nodeRunners.put(nodeName, runner);
            } else if (nodeConfig instanceof SwitchNodeConfig) {
                SwitchNodeConfig switchConfig = (SwitchNodeConfig) nodeConfig;
                try {
                    Class<?> switchFnClass = Class.forName(switchConfig.getSwitchFunctionClassName());
                    SwitchFn switchFn = (SwitchFn) switchFnClass.getDeclaredConstructor().newInstance();
                    // TODO: Add an init(Data params, Context context) method to SwitchFn if they can be stateful or configurable
                    // if (switchFn instanceof InitializableSwitchFn) {
                    //    ((InitializableSwitchFn) switchFn).init(switchConfig.getSwitchFunctionParams(), nodeInitContext);
                    // }
                    switchFunctions.put(nodeName, switchFn);
                } catch (ReflectiveOperationException e) {
                    throw new Exception(String.format("Failed to instantiate SwitchFn '%s' for node '%s'",
                            switchConfig.getSwitchFunctionClassName(), nodeName), e);
                }
            } else if (nodeConfig instanceof CombineNodeConfig) {
                CombineNodeConfig combineConfig = (CombineNodeConfig) nodeConfig;
                try {
                    Class<?> combineFnClass = Class.forName(combineConfig.getCombineFunctionClassName());
                    CombineFn combineFn = (CombineFn) combineFnClass.getDeclaredConstructor().newInstance();
                    // TODO: Add an init(Data params, Context context) method to CombineFn
                    // if (combineFn instanceof InitializableCombineFn) {
                    //    ((InitializableCombineFn) combineFn).init(combineConfig.getCombineFunctionParams(), nodeInitContext);
                    // }
                    combineFunctions.put(nodeName, combineFn);
                } catch (ReflectiveOperationException e) {
                    throw new Exception(String.format("Failed to instantiate CombineFn '%s' for node '%s'",
                            combineConfig.getCombineFunctionClassName(), nodeName), e);
                }
            }
            // MERGE and ANY nodes are typically handled by the executor's logic directly.
        }
    }

    /**
     * Ensures runners and functions are initialized if they weren't at construction.
     * This method is idempotent.
     * @throws Exception if initialization fails.
     */
    public void ensureRunnersInitialized() throws Exception {
        boolean needsInit = graphPipeline.getGraphNodes().values().stream().anyMatch(gnc ->
                (gnc.getGraphStepType() == GraphStepType.STANDARD && !nodeRunners.containsKey(gnc.getName())) ||
                        (gnc.getGraphStepType() == GraphStepType.SWITCH && !switchFunctions.containsKey(gnc.getName())) ||
                        (gnc.getGraphStepType() == GraphStepType.COMBINE_FN && !combineFunctions.containsKey(gnc.getName()))
        );

        if (needsInit && !this.closed) { // Only init if not closed
            initializeGraphNodeRunnersAndFunctions();
        }
    }


    @Override
    public Data exec(Data initialInput, Context rootContext) throws Exception {
        checkIfClosed();
        Objects.requireNonNull(initialInput, "Initial input Data cannot be null for graph exec.");
        Objects.requireNonNull(rootContext, "Root context cannot be null for graph exec.");

        ensureRunnersInitialized(); // Make sure everything is ready


        if (GraphPipeline.DEFAULT_GRAPH_INPUT_NAME.equals(graphPipeline.getOutputNodeName())) {
            return initialInput;
        }
        if (graphPipeline.getGraphNodes().isEmpty()) {
            return Data.empty();
        }

        Map<String, Data> results = new ConcurrentHashMap<>(); // Stores output of each executed node
        Map<String, Integer> currentInDegree = new HashMap<>();
        Queue<String> readyToExecuteQueue = new LinkedList<>(); // Nodes ready to execute

        // Initialize in-degrees based on graph structure (not pipeline_input string)
        for (String nodeName : graphPipeline.getGraphNodes().keySet()) {
            currentInDegree.put(nodeName, predecessorsMap.getOrDefault(nodeName, Collections.emptyList())
                    .stream()
                    .filter(pred -> !pred.equals(graphPipeline.getInputNodeName())) // Count only actual node predecessors
                    .collect(Collectors.toList()).size());

            if (currentInDegree.get(nodeName) == 0) {
                // Node only depends on pipeline input or has no graph predecessors.
                // Further check: it must have graphPipeline.getInputNodeName() as an input if it has inputs.
                boolean dependsOnlyOnPipelineInput = graphPipeline.getGraphNodes().get(nodeName).getInputs().isEmpty() ||
                        graphPipeline.getGraphNodes().get(nodeName).getInputs().stream()
                                .allMatch(in -> in.equals(graphPipeline.getInputNodeName()));
                if(dependsOnlyOnPipelineInput || predecessorsMap.getOrDefault(nodeName, Collections.emptyList()).isEmpty()){
                    readyToExecuteQueue.add(nodeName);
                }
            }
        }

        if (readyToExecuteQueue.isEmpty() && !graphPipeline.getGraphNodes().isEmpty()) {
            throw new IllegalStateException("Graph has no nodes ready for initial execution (all have graph predecessors). Possible cycle or misconfiguration in pipeline: " + graphPipeline.id());
        }


        int executedCount = 0;
        currentExecutionSwitchSelections.set(new HashMap<>()); // Reset for this execution

        // ExecutorService for parallel execution of ready nodes
        // Using the one from BasePipelineExecutor, ensure it's suitable (e.g., cached or fixed pool)
        List<Future<NodeExecutionResult>> futures = new ArrayList<>();


        while (executedCount < graphPipeline.getGraphNodes().size()) {
            List<String> currentReadyBatch = new ArrayList<>();
            while(!readyToExecuteQueue.isEmpty()){
                currentReadyBatch.add(readyToExecuteQueue.poll());
            }

            if (currentReadyBatch.isEmpty() && executedCount < graphPipeline.getGraphNodes().size()) {
                Set<String> allNodes = new HashSet<>(graphPipeline.getGraphNodes().keySet());
                results.keySet().forEach(allNodes::remove); // Remove executed nodes
                throw new IllegalStateException("Cycle detected or graph disconnected in pipeline: " + graphPipeline.id() +
                        ". Not all nodes could be scheduled. Unprocessed nodes: " + allNodes);
            }
            if(currentReadyBatch.isEmpty()) break; // All processed

            for (String nodeName : currentReadyBatch) {
                GraphNodeConfig nodeConfig = graphPipeline.getGraphNodes().get(nodeName);
                Context stepContext = rootContext.child("node." + nodeName);

                // Prepare inputs for this node. This needs to be robust.
                Data nodeInput = prepareNodeInputForExecution(nodeName, nodeConfig, initialInput, results);

                Callable<NodeExecutionResult> task = () -> {
                    String eventName = String.format("node.%s.%s.exec", nodeName, nodeConfig.getGraphStepType());
                    Data outputData;
                    try {
                        outputData = rootContext.profiler().profile(eventName, () ->
                                executeSingleNodeLogic(nodeName, nodeConfig, nodeInput, stepContext, initialInput, results)
                        );
                        rootContext.metrics().counter("pipeline.graph.node.executions.total", "Total node executions",
                                        "pipeline_id", graphPipeline.id(), "node_name", nodeName, "node_type", nodeConfig.getGraphStepType().name())
                                .increment();
                    } catch (Exception e) {
                        rootContext.metrics().counter("pipeline.graph.node.errors.total", "Total node execution errors",
                                        "pipeline_id", graphPipeline.id(), "node_name", nodeName, "node_type", nodeConfig.getGraphStepType().name())
                                .increment();
                        throw e; // Re-throw to be caught by Future.get()
                    }
                    if (outputData == null) {
                        throw new IllegalStateException("Node '" + nodeName + "' returned null Data.");
                    }
                    return new NodeExecutionResult(nodeName, outputData);
                };
                futures.add(asyncExecutorService.submit(task));
            }

            // Process completed futures from this batch before preparing next batch
            for (Future<NodeExecutionResult> future : futures) {
                try {
                    NodeExecutionResult result = future.get(); // This blocks until this task is done
                    String executedNodeName = result.getNodeName();
                    results.put(executedNodeName, result.getOutputData());
                    executedCount++;

                    List<String> nodeSuccessors = successorsMap.getOrDefault(executedNodeName, Collections.emptyList());

                    // Special handling for SWITCH node outputs
                    GraphNodeConfig executedNodeConfig = graphPipeline.getGraphNodes().get(executedNodeName);
                    if (executedNodeConfig.getGraphStepType() == GraphStepType.SWITCH) {
                        List<String> selectedSwitchOutputs = currentExecutionSwitchSelections.get().get(executedNodeName);
                        if (selectedSwitchOutputs != null) {
                            nodeSuccessors = selectedSwitchOutputs.stream()
                                    .filter(successorsMap.getOrDefault(executedNodeName, Collections.emptyList())::contains) // Ensure selected outputs are valid successors
                                    .collect(Collectors.toList());
                        } else {
                            // Keep nodeSuccessors as all defined successors
                        }
                    }


                    for (String successorName : nodeSuccessors) {
                        currentInDegree.compute(successorName, (k, v) -> {
                            if (v == null) { // Should not happen if graph is parsed correctly
                                return 0; // Avoid NPE, but indicates a problem
                            }
                            int newDegree = v - 1;
                            if (newDegree == 0) {
                                readyToExecuteQueue.add(successorName);
                            }
                            return newDegree;
                        });
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    // Cancel remaining futures
                    futures.forEach(f -> f.cancel(true));
                    throw e;
                } catch (ExecutionException e) {
                    futures.forEach(f -> f.cancel(true));
                    throw (Exception) e.getCause();
                }
            }
            futures.clear(); // Clear for the next batch of ready nodes
        }
        currentExecutionSwitchSelections.remove(); // Clean up ThreadLocal

        if (executedCount != graphPipeline.getGraphNodes().size()) {
            Set<String> allGraphNodeNames = new HashSet<>(graphPipeline.getGraphNodes().keySet());
            results.keySet().forEach(allGraphNodeNames::remove); // Nodes that did not produce a result
            throw new IllegalStateException("Cycle detected or graph disconnected in pipeline: " + graphPipeline.id() + ". Not all nodes were executed.");
        }

        Data finalOutput = results.get(graphPipeline.getOutputNodeName());
        if (finalOutput == null) {
            throw new IllegalStateException("Output node '" + graphPipeline.getOutputNodeName() + "' did not produce a result.");
        }
        return finalOutput;
    }

    private Data prepareNodeInputForExecution(String nodeName, GraphNodeConfig nodeConfig, Data pipelineInitialInput, Map<String, Data> intermediateResults) {
        List<String> inputSourceNames = nodeConfig.getInputs();

        // If the node explicitly takes the pipeline input.
        if (inputSourceNames.size() == 1 && inputSourceNames.get(0).equals(graphPipeline.getInputNodeName())) {
            return pipelineInitialInput.dup(); // Provide a copy
        }

        // For nodes that are supposed to handle their multiple inputs directly (MERGE, COMBINE_FN, ANY)
        // they will look up their inputs from intermediateResults or pipelineInitialInput
        // within their executeSingleNodeLogic. So, we can pass a placeholder or the first input if single.
        if (nodeConfig.getGraphStepType() == GraphStepType.MERGE ||
                nodeConfig.getGraphStepType() == GraphStepType.COMBINE_FN ||
                nodeConfig.getGraphStepType() == GraphStepType.ANY) {
            // These nodes will aggregate inputs themselves. We pass an empty Data or a signal.
            // For simplicity now, let's pass an empty one, they should use intermediateResults.
            return Data.empty();
        }

        // For STANDARD and SWITCH nodes, they typically expect a single Data input object.
        // If they list multiple inputs, it's an error unless the first one is the intended one or an implicit merge is assumed (not good).
        // The graph validation should ensure STANDARD/SWITCH nodes have one logical input source after any MERGEs.
        if (inputSourceNames.isEmpty()) { // Node depends only on pipeline input (implicit) or no inputs
            if (predecessorsMap.getOrDefault(nodeName, Collections.emptyList()).stream().allMatch(p -> p.equals(graphPipeline.getInputNodeName()))) {
                return pipelineInitialInput.dup();
            }
            return Data.empty();
        }

        // Assuming single input for STANDARD and SWITCH after graph validation/construction.
        String sourceName = inputSourceNames.get(0); // Should only be one graph node input here after MERGEs
        Data sourceData = sourceName.equals(graphPipeline.getInputNodeName()) ?
                pipelineInitialInput :
                intermediateResults.get(sourceName);

        if (sourceData == null) {
            throw new IllegalStateException(String.format(
                    "Input data from source '%s' not found for node '%s' in pipeline '%s'. This indicates a flaw in execution order or graph structure.",
                    sourceName, nodeName, graphPipeline.id()));
        }
        return sourceData.dup(); // Return a copy
    }


    private Data executeSingleNodeLogic(String nodeName, GraphNodeConfig nodeConfig, Data nodeInput, Context nodeContext,
                                        Data pipelineInitialInput, Map<String, Data> intermediateResults) throws Exception {
        switch (nodeConfig.getGraphStepType()) {
            case STANDARD:
                PipelineStepRunner runner = nodeRunners.get(nodeName);
                if (runner == null || !runner.isInitialized()) {
                    throw new IllegalStateException("Runner for STANDARD node '" + nodeName + "' not found or not initialized.");
                }
                return runner.exec(nodeInput, nodeContext);
            case MERGE:
                MergeNodeConfig mergeConfig = (MergeNodeConfig) nodeConfig;
                Data mergedData = Data.empty();
                for (String inputSourceName : mergeConfig.getInputs()) {
                    Data dataToMerge = inputSourceName.equals(graphPipeline.getInputNodeName()) ?
                            pipelineInitialInput.dup() : // Use a copy of pipeline input
                            intermediateResults.get(inputSourceName).dup(); // Use a copy of intermediate result
                    if (dataToMerge == null) {
                        throw new IllegalStateException("Missing input '" + inputSourceName + "' for MERGE node '" + nodeName + "'.");
                    }
                    mergedData.merge(dataToMerge);
                }
                return mergedData;
            case SWITCH:
                SwitchNodeConfig switchConfig = (SwitchNodeConfig) nodeConfig;
                SwitchFn switchFn = switchFunctions.get(nodeName);
                if (switchFn == null) {
                    throw new IllegalStateException("SwitchFn for SWITCH node '" + nodeName + "' not found.");
                }
                // SwitchFn receives the single resolved input to the switch node.
                List<String> selectedOutputs = switchFn.selectOutput(nodeInput,
                        // Provide actual configured successor names for this switch node
                        successorsMap.getOrDefault(nodeName, Collections.emptyList()),
                        switchConfig.getSwitchFunctionParams());
                currentExecutionSwitchSelections.get().put(nodeName, selectedOutputs != null ? selectedOutputs : Collections.emptyList());
                return nodeInput.dup(); // Switch node passes its input data through to selected branches.
            case ANY:
                AnyNodeConfig anyConfig = (AnyNodeConfig) nodeConfig;
                // In Kahn's, ANY executes when all inputs are "available".
                // It should take the first *meaningful* data from its inputs.
                for (String inputSourceName : anyConfig.getInputs()) {
                    Data dataToPass = inputSourceName.equals(graphPipeline.getInputNodeName()) ?
                            pipelineInitialInput :
                            intermediateResults.get(inputSourceName);
                    if(dataToPass != null && !dataToPass.isEmpty()){ // Define what "meaningful" means.
                        return dataToPass.dup();
                    }
                }
                return Data.empty();
            case COMBINE_FN:
                CombineNodeConfig combineConfig = (CombineNodeConfig) nodeConfig;
                CombineFn combineFn = combineFunctions.get(nodeName);
                if (combineFn == null) {
                    throw new IllegalStateException("CombineFn for COMBINE_FN node '" + nodeName + "' not found.");
                }
                Map<String, Data> combineInputs = new HashMap<>();
                for (String inputSourceName : combineConfig.getInputs()) {
                    Data dataToCombine = inputSourceName.equals(graphPipeline.getInputNodeName()) ?
                            pipelineInitialInput : // Pass reference, CombineFn should dup if it modifies
                            intermediateResults.get(inputSourceName);
                    if (dataToCombine == null) {
                        throw new IllegalStateException("Missing input '" + inputSourceName + "' for COMBINE_FN node '" + nodeName + "'.");
                    }
                    combineInputs.put(inputSourceName, dataToCombine);
                }
                return combineFn.combine(combineInputs, combineConfig.getCombineFunctionParams());
            default:
                throw new IllegalStateException("Unsupported GraphStepType: " + nodeConfig.getGraphStepType() + " for node '" + nodeName + "'.");
        }
    }

    private void detectCycles() {
        Map<String, VisitState> visitStates = new HashMap<>();
        for (String node : graphPipeline.getGraphNodes().keySet()) {
            visitStates.put(node, VisitState.UNVISITED);
        }

        for (String node : graphPipeline.getGraphNodes().keySet()) {
            if (visitStates.get(node) == VisitState.UNVISITED) {
                if (hasCycleDfs(node, visitStates, new HashSet<>())) {
                    throw new IllegalStateException("Cycle detected in the graph pipeline: " + graphPipeline.id());
                }
            }
        }
    }

    private boolean hasCycleDfs(String node, Map<String, VisitState> visitStates, Set<String> recursionStack) {
        visitStates.put(node, VisitState.VISITING);
        recursionStack.add(node);

        for (String neighbor : successorsMap.getOrDefault(node, Collections.emptyList())) {
            if (!graphPipeline.getGraphNodes().containsKey(neighbor)) continue; // Should be caught by parseAndValidate

            if (recursionStack.contains(neighbor)) {
                return true; // Cycle detected
            }
            if (visitStates.get(neighbor) == VisitState.UNVISITED) {
                if (hasCycleDfs(neighbor, visitStates, recursionStack)) {
                    return true;
                }
            }
        }

        recursionStack.remove(node);
        visitStates.put(node, VisitState.VISITED);
        return false;
    }

    private enum VisitState {UNVISITED, VISITING, VISITED}

    private static class NodeExecutionResult {
        private final String nodeName;
        private final Data outputData;

        public NodeExecutionResult(String nodeName, Data outputData) {
            this.nodeName = nodeName;
            this.outputData = outputData;
        }
        public String getNodeName() { return nodeName; }
        public Data getOutputData() { return outputData; }
    }


    @Override
    public void close() throws Exception {
        if (closed) {
            return;
        }
        Exception firstException = null;

        for (PipelineStepRunner runner : nodeRunners.values()) {
            try {
                if (runner.isInitialized()) {
                    runner.close();
                }
            } catch (Exception e) {
                if (firstException == null) firstException = e; else firstException.addSuppressed(e);
            }
        }
        nodeRunners.clear();
        switchFunctions.clear(); // Assuming SwitchFn and CombineFn don't need explicit close, or add AutoCloseable
        combineFunctions.clear();

        // Call super.close() to shutdown asyncExecutorService and set closed flag
        try {
            super.close();
        } catch (Exception e) {
            if (firstException == null) firstException = e; else firstException.addSuppressed(e);
        }

        if (firstException != null) throw firstException;
    }
}