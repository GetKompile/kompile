package ai.kompile.compute.graph.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * A stored computation artifact produced by a node execution.
 * Artifacts capture the outputs and state from each node run,
 * enabling replay, auditing, and downstream consumption.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComputeArtifact {

    private String id;

    /**
     * ID of the graph execution run that produced this artifact.
     */
    private String executionId;

    /**
     * ID of the node that produced this artifact.
     */
    private String nodeId;

    /**
     * Name of this artifact (e.g., "model_scores", "transformed_data").
     */
    private String name;

    /**
     * MIME type of the artifact content.
     */
    private String contentType;

    /**
     * The actual output data produced by the node.
     * For simple values, stored directly. For large blobs, may be a reference URI.
     */
    @Builder.Default
    private Map<String, Object> data = new HashMap<>();

    /**
     * Optional binary content (serialized model, file output, etc.)
     */
    private byte[] binaryContent;

    /**
     * URI reference if artifact is stored externally (e.g., S3, filesystem).
     */
    private String storageUri;

    /**
     * Size in bytes of the artifact content.
     */
    private long sizeBytes;

    @Builder.Default
    private Instant createdAt = Instant.now();

    /**
     * Metadata about how this artifact was produced.
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
}
