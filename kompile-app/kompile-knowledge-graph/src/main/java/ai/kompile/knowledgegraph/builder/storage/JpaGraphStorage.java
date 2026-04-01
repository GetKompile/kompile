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
import ai.kompile.knowledgegraph.repository.GraphEdgeRepository;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * JPA-based graph storage implementation.
 * Stores graph nodes and edges in the local database using Spring Data JPA.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JpaGraphStorage implements GraphStorageStrategy {

    private final GraphNodeRepository nodeRepository;
    private final GraphEdgeRepository edgeRepository;

    @Override
    public String getStorageType() {
        return "jpa";
    }

    @Override
    public StorageResult storeProposal(TripleProposal proposal) {
        try {
            // Create or find subject node
            GraphNode subjectNode = findOrCreateEntityNode(
                    proposal.getFactSheetId(),
                    proposal.getSubjectName(),
                    proposal.getSubjectType(),
                    proposal.getSubjectDescription()
            );

            // Create or find object node
            GraphNode objectNode = findOrCreateEntityNode(
                    proposal.getFactSheetId(),
                    proposal.getObjectName(),
                    proposal.getObjectType(),
                    proposal.getObjectDescription()
            );

            // Create edge
            GraphEdge edge = createEdgeForProposal(subjectNode, objectNode, proposal);

            log.debug("Stored proposal {} to JPA: subject={}, object={}, edge={}",
                    proposal.getProposalId(), subjectNode.getNodeId(), objectNode.getNodeId(), edge.getEdgeId());

            return StorageResult.success(subjectNode.getNodeId(), objectNode.getNodeId(), edge.getEdgeId());

        } catch (Exception e) {
            log.error("Failed to store proposal {} to JPA", proposal.getProposalId(), e);
            return StorageResult.failure("JPA storage failed: " + e.getMessage());
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            // Simple health check - try to count nodes
            nodeRepository.count();
            return true;
        } catch (Exception e) {
            log.warn("JPA graph storage is not available: {}", e.getMessage());
            return false;
        }
    }

    private GraphNode findOrCreateEntityNode(Long factSheetId, String name, String type, String description) {
        // Generate external ID from name and type
        String externalId = "entity_" + name.toLowerCase().replaceAll("[^a-z0-9]", "_");

        Optional<GraphNode> existing = nodeRepository.findByExternalIdAndNodeTypeAndFactSheetId(
                externalId, NodeLevel.ENTITY, factSheetId);

        if (existing.isPresent()) {
            return existing.get();
        }

        // Create new entity node
        GraphNode node = GraphNode.builder()
                .nodeType(NodeLevel.ENTITY)
                .externalId(externalId)
                .title(name)
                .description(description)
                .factSheetId(factSheetId)
                .metadataJson("{\"entityType\": \"" + (type != null ? type : "UNKNOWN") + "\"}")
                .build();

        return nodeRepository.save(node);
    }

    private GraphEdge createEdgeForProposal(GraphNode source, GraphNode target, TripleProposal proposal) {
        GraphEdge edge = GraphEdge.builder()
                .sourceNode(source)
                .targetNode(target)
                .edgeType(EdgeType.USER_DEFINED)
                .label(proposal.getPredicateName())
                .description(proposal.getPredicateName() + " relationship")
                .weight(proposal.getConfidence())
                .factSheetId(proposal.getFactSheetId())
                .bidirectional(false)
                .build();

        GraphEdge saved = edgeRepository.save(edge);

        // Update edge counts
        source.incrementEdgeCount();
        target.incrementEdgeCount();
        nodeRepository.save(source);
        nodeRepository.save(target);

        return saved;
    }
}
