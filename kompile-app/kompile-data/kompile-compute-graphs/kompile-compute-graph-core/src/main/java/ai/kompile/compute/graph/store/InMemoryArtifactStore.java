package ai.kompile.compute.graph.store;

import ai.kompile.compute.graph.model.ComputeArtifact;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Simple in-memory artifact store for development and testing.
 * Not suitable for production use with large artifacts.
 */
public class InMemoryArtifactStore implements ArtifactStore {

    private final Map<String, ComputeArtifact> store = new ConcurrentHashMap<>();

    @Override
    public void store(ComputeArtifact artifact) {
        store.put(artifact.getId(), artifact);
    }

    @Override
    public Optional<ComputeArtifact> get(String artifactId) {
        return Optional.ofNullable(store.get(artifactId));
    }

    @Override
    public List<ComputeArtifact> getByExecutionId(String executionId) {
        return store.values().stream()
                .filter(a -> executionId.equals(a.getExecutionId()))
                .collect(Collectors.toList());
    }

    @Override
    public List<ComputeArtifact> getByNodeId(String nodeId) {
        return store.values().stream()
                .filter(a -> nodeId.equals(a.getNodeId()))
                .collect(Collectors.toList());
    }

    @Override
    public List<ComputeArtifact> getByExecutionAndNode(String executionId, String nodeId) {
        return store.values().stream()
                .filter(a -> executionId.equals(a.getExecutionId()) && nodeId.equals(a.getNodeId()))
                .collect(Collectors.toList());
    }

    @Override
    public void delete(String artifactId) {
        store.remove(artifactId);
    }

    @Override
    public void deleteByExecutionId(String executionId) {
        store.entrySet().removeIf(e -> executionId.equals(e.getValue().getExecutionId()));
    }
}
