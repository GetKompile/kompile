/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.matrix.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents a node in a matrix-based graph.
 * Each node has a unique ID, a matrix index, and associated metadata.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MatrixGraphNode {

    /**
     * Unique string identifier for the node (used for external reference).
     */
    private String nodeId;

    /**
     * Matrix row/column index for this node in the adjacency matrix.
     */
    private int matrixIndex;

    /**
     * Node type/label (e.g., "PERSON", "DOCUMENT", "CONCEPT").
     */
    private String nodeType;

    /**
     * Display title for the node.
     */
    private String title;

    /**
     * Optional description of the node.
     */
    private String description;

    /**
     * Reference to embedding vector ID in the vector store.
     */
    private String embeddingId;

    /**
     * Additional metadata as key-value pairs.
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    /**
     * The fact sheet this node belongs to (for multi-tenant scoping).
     */
    private Long factSheetId;

    /**
     * Timestamp when the node was created (epoch millis).
     */
    private long createdAt;

    /**
     * Timestamp when the node was last updated (epoch millis).
     */
    private long updatedAt;

    /**
     * Helper to add metadata.
     */
    public MatrixGraphNode withMetadata(String key, Object value) {
        if (this.metadata == null) {
            this.metadata = new HashMap<>();
        }
        this.metadata.put(key, value);
        return this;
    }
}
