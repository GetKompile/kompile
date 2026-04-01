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
package ai.kompile.knowledgegraph.builder.storage;

import ai.kompile.knowledgegraph.builder.domain.TripleProposal;

import java.util.Optional;

/**
 * Strategy interface for storing accepted proposals to a graph backend.
 *
 * <p>Implementations exist for:
 * <ul>
 *   <li><b>jpa</b> - Local JPA/JDBC storage (H2, PostgreSQL)</li>
 *   <li><b>neo4j</b> - Neo4j graph database</li>
 * </ul>
 */
public interface GraphStorageStrategy {

    /**
     * Get the storage type identifier.
     */
    String getStorageType();

    /**
     * Store an accepted proposal as nodes and edges.
     *
     * @param proposal the accepted proposal
     * @return the result containing created node and edge IDs
     */
    StorageResult storeProposal(TripleProposal proposal);

    /**
     * Check if this storage is available and configured.
     */
    boolean isAvailable();

    /**
     * Result of storing a proposal.
     */
    record StorageResult(
            String subjectNodeId,
            String objectNodeId,
            String edgeId,
            boolean success,
            String errorMessage
    ) {
        public static StorageResult success(String subjectNodeId, String objectNodeId, String edgeId) {
            return new StorageResult(subjectNodeId, objectNodeId, edgeId, true, null);
        }

        public static StorageResult failure(String errorMessage) {
            return new StorageResult(null, null, null, false, errorMessage);
        }
    }
}
