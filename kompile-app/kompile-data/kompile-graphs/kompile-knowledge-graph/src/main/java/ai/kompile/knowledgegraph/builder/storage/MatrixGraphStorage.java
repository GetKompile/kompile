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
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Graph storage strategy that routes proposal acceptance to the live {@code @Primary}
 * matrix/vector store through the store-agnostic {@link KnowledgeGraphService} seam.
 *
 * <p>This replaces the dead {@link JpaGraphStorage} path on the live matrix path: JPA
 * repositories are {@code null} for nodes/edges in the matrix store, so writing accepted
 * proposals through the JPA repository is a no-op that silently loses data. This
 * implementation mirrors {@link JpaGraphStorage}'s logic but delegates every read/write
 * to the {@link KnowledgeGraphService} interface, which is backed by
 * {@code MatrixKnowledgeGraphService} (marked {@code @Primary}) in the live deployment.</p>
 *
 * <p>Storage type key: {@code "matrix"}. Registered automatically by
 * {@link GraphStorageRegistry} (which collects all {@code GraphStorageStrategy} beans).
 * Selected by default when {@code kompile.graph-builder.storage} is unset — see
 * {@link GraphStorageRegistry} where the default was changed from {@code "jpa"} to
 * {@code "matrix"}.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MatrixGraphStorage implements GraphStorageStrategy {

    /**
     * The {@code @Primary} live store. Injected as the matrix/vector implementation at runtime
     * (MatrixKnowledgeGraphService). Never null — Spring wires the @Primary bean.
     */
    private final KnowledgeGraphService graphService;

    @Override
    public String getStorageType() {
        return "matrix";
    }

    @Override
    public StorageResult storeProposal(TripleProposal proposal) {
        try {
            // Find or create subject node in the live store.
            GraphNode subjectNode = findOrCreateEntityNode(
                    proposal.getFactSheetId(),
                    proposal.getSubjectName(),
                    proposal.getSubjectType(),
                    proposal.getSubjectDescription()
            );

            // Find or create object node in the live store.
            GraphNode objectNode = findOrCreateEntityNode(
                    proposal.getFactSheetId(),
                    proposal.getObjectName(),
                    proposal.getObjectType(),
                    proposal.getObjectDescription()
            );

            // Create edge between the two nodes.
            GraphEdge edge = createEdgeForProposal(subjectNode, objectNode, proposal);

            log.debug("Stored proposal {} to matrix store: subject={}, object={}, edge={}",
                    proposal.getProposalId(), subjectNode.getNodeId(), objectNode.getNodeId(), edge.getEdgeId());

            return StorageResult.success(subjectNode.getNodeId(), objectNode.getNodeId(), edge.getEdgeId());

        } catch (Exception e) {
            log.error("Failed to store proposal {} to matrix store", proposal.getProposalId(), e);
            return StorageResult.failure("Matrix storage failed: " + e.getMessage());
        }
    }

    @Override
    public boolean isAvailable() {
        // Fast non-loading check: the graphService bean being non-null is sufficient evidence
        // that the matrix store is wired and available.  The previous implementation called
        // graphService.getGraphStatistics(), which transitively triggered a full vector-store
        // scan to load the default graph (~13 seconds on a 330k-doc index) — inside
        // GraphStorageRegistry.init() @PostConstruct, which blocked the Spring context refresh
        // and therefore the Tomcat startup on the [main] thread.
        return graphService != null;
    }

    /**
     * Find an existing ENTITY node by (externalId, factSheetId) or create a new one via the
     * store-agnostic seam. The externalId convention mirrors {@link JpaGraphStorage} so that
     * re-accepting a proposal for the same entity is idempotent.
     */
    private GraphNode findOrCreateEntityNode(Long factSheetId, String name, String type, String description) {
        String externalId = "entity_" + name.toLowerCase().replaceAll("[^a-z0-9]", "_");

        // Try to find the existing node in the live store first (idempotent accept).
        Optional<GraphNode> existing = graphService.getNodeByExternalIdInFactSheet(
                externalId, NodeLevel.ENTITY, factSheetId);
        if (existing.isPresent()) {
            return existing.get();
        }

        // Build metadata with entityType, mirroring the JPA path.
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("entityType", type != null ? type : "UNKNOWN");

        return graphService.createNode(
                NodeLevel.ENTITY,
                externalId,
                name,
                description,
                metadata,
                factSheetId
        );
    }

    /**
     * Create an edge between two nodes, using the store-agnostic createEdge seam. Uses
     * {@link EdgeType#USER_DEFINED} to match the JPA path convention for accepted proposals.
     */
    private GraphEdge createEdgeForProposal(GraphNode source, GraphNode target, TripleProposal proposal) {
        // Prefer createEdgeWithMetadata when we have a label and provenance context.
        // Fall back to the 5-arg createEdge for simplicity — the matrix store handles both.
        return graphService.createEdge(
                source.getNodeId(),
                target.getNodeId(),
                EdgeType.USER_DEFINED,
                proposal.getPredicateName(), // relationType / label
                proposal.getConfidence(),
                proposal.getPredicateName() + " relationship"
        );
    }
}
