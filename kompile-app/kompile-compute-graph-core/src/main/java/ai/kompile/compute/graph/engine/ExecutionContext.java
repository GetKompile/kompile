package ai.kompile.compute.graph.engine;

import ai.kompile.compute.graph.model.ComputeArtifact;
import ai.kompile.compute.graph.model.ComputeGraph;
import ai.kompile.compute.graph.store.ArtifactStore;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Context available to node executors during graph execution.
 * Provides access to the graph definition, artifact storage,
 * global parameters, and inter-node state.
 */
public class ExecutionContext {

    private final String executionId;
    private final ComputeGraph graph;
    private final ArtifactStore artifactStore;
    private final Map<String, Object> globalState;
    private final Map<String, Map<String, Object>> nodeOutputs;

    public ExecutionContext(String executionId, ComputeGraph graph, ArtifactStore artifactStore) {
        this.executionId = executionId;
        this.graph = graph;
        this.artifactStore = artifactStore;
        this.globalState = new ConcurrentHashMap<>(graph.getGlobalParameters());
        this.nodeOutputs = new ConcurrentHashMap<>();
    }

    public String getExecutionId() {
        return executionId;
    }

    public ComputeGraph getGraph() {
        return graph;
    }

    public ArtifactStore getArtifactStore() {
        return artifactStore;
    }

    /**
     * Get global state (shared across all nodes in this execution).
     */
    public Map<String, Object> getGlobalState() {
        return globalState;
    }

    /**
     * Store outputs produced by a node.
     */
    public void recordNodeOutputs(String nodeId, Map<String, Object> outputs) {
        nodeOutputs.put(nodeId, outputs);
    }

    /**
     * Get outputs produced by a previously executed node.
     */
    public Map<String, Object> getNodeOutputs(String nodeId) {
        return nodeOutputs.getOrDefault(nodeId, Map.of());
    }

    /**
     * Store an artifact produced during execution.
     */
    public void storeArtifact(ComputeArtifact artifact) {
        if (artifactStore != null) {
            artifactStore.store(artifact);
        }
    }

    /**
     * Put a value in global state (visible to all subsequent nodes).
     */
    public void putGlobal(String key, Object value) {
        globalState.put(key, value);
    }

    /**
     * Get a value from global state.
     */
    public Object getGlobal(String key) {
        return globalState.get(key);
    }
}
