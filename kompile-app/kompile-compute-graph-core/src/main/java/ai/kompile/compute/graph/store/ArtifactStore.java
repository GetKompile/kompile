package ai.kompile.compute.graph.store;

import ai.kompile.compute.graph.model.ComputeArtifact;

import java.util.List;
import java.util.Optional;

/**
 * Interface for storing and retrieving computation artifacts.
 * Implementations may use filesystem, database, or object storage.
 */
public interface ArtifactStore {

    /**
     * Store an artifact.
     */
    void store(ComputeArtifact artifact);

    /**
     * Retrieve an artifact by ID.
     */
    Optional<ComputeArtifact> get(String artifactId);

    /**
     * Retrieve all artifacts for a given execution run.
     */
    List<ComputeArtifact> getByExecutionId(String executionId);

    /**
     * Retrieve all artifacts produced by a specific node across all executions.
     */
    List<ComputeArtifact> getByNodeId(String nodeId);

    /**
     * Retrieve artifacts for a specific node within a specific execution.
     */
    List<ComputeArtifact> getByExecutionAndNode(String executionId, String nodeId);

    /**
     * Delete an artifact by ID.
     */
    void delete(String artifactId);

    /**
     * Delete all artifacts for an execution.
     */
    void deleteByExecutionId(String executionId);
}
