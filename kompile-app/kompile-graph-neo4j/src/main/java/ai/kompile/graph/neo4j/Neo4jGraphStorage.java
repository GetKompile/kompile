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
package ai.kompile.graph.neo4j;

import ai.kompile.knowledgegraph.builder.domain.TripleProposal;
import ai.kompile.knowledgegraph.builder.storage.GraphStorageStrategy;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Neo4j-based graph storage implementation.
 * Stores graph nodes and edges in Neo4j using the Java driver.
 *
 * <p>This class is registered as a bean via {@link Neo4jGraphConfiguration}
 * only when Neo4j is on the classpath and a Driver bean is available.
 */
@Slf4j
public class Neo4jGraphStorage implements GraphStorageStrategy {

    private final Driver neo4jDriver;

    // Cypher query for upserting a node with labels
    private static final String MERGE_NODE_QUERY = """
            MERGE (n:Entity {entityId: $entityId})
            ON CREATE SET
                n.title = $title,
                n.description = $description,
                n.entityType = $entityType,
                n.factSheetId = $factSheetId,
                n.createdAt = datetime()
            ON MATCH SET
                n.title = $title,
                n.description = COALESCE($description, n.description),
                n.updatedAt = datetime()
            WITH n
            CALL apoc.create.addLabels(n, $labels) YIELD node
            RETURN elementId(node) AS elementId, node.entityId AS entityId
            """;

    // Simplified version without APOC for basic Neo4j installations
    private static final String MERGE_NODE_SIMPLE_QUERY = """
            MERGE (n:Entity {entityId: $entityId})
            ON CREATE SET
                n.title = $title,
                n.description = $description,
                n.entityType = $entityType,
                n.factSheetId = $factSheetId,
                n.createdAt = datetime()
            ON MATCH SET
                n.title = $title,
                n.description = COALESCE($description, n.description),
                n.updatedAt = datetime()
            RETURN elementId(n) AS elementId, n.entityId AS entityId
            """;

    // Cypher query for creating a relationship
    private static final String CREATE_RELATIONSHIP_QUERY = """
            MATCH (s:Entity {entityId: $subjectId})
            MATCH (o:Entity {entityId: $objectId})
            MERGE (s)-[r:RELATES_TO {edgeId: $edgeId}]->(o)
            ON CREATE SET
                r.predicateName = $predicateName,
                r.factSheetId = $factSheetId,
                r.sourceChunkId = $sourceChunkId,
                r.sourceDocumentId = $sourceDocumentId,
                r.confidence = $confidence,
                r.createdAt = datetime()
            ON MATCH SET
                r.updatedAt = datetime()
            RETURN elementId(r) AS elementId, r.edgeId AS edgeId
            """;

    public Neo4jGraphStorage(Driver neo4jDriver) {
        this.neo4jDriver = neo4jDriver;
        log.info("Neo4jGraphStorage initialized with driver: {}", neo4jDriver);
    }

    @Override
    public String getStorageType() {
        return "neo4j";
    }

    @Override
    public StorageResult storeProposal(TripleProposal proposal) {
        try (Session session = neo4jDriver.session()) {
            // Generate entity IDs based on name + factSheetId for deduplication
            String subjectEntityId = generateEntityId(proposal.getSubjectName(), proposal.getFactSheetId());
            String objectEntityId = generateEntityId(proposal.getObjectName(), proposal.getFactSheetId());
            String edgeId = UUID.randomUUID().toString();

            // Create/update subject node
            String subjectNodeId = upsertNode(session,
                    subjectEntityId,
                    proposal.getSubjectName(),
                    proposal.getSubjectDescription(),
                    proposal.getSubjectType(),
                    proposal.getFactSheetId());

            // Create/update object node
            String objectNodeId = upsertNode(session,
                    objectEntityId,
                    proposal.getObjectName(),
                    proposal.getObjectDescription(),
                    proposal.getObjectType(),
                    proposal.getFactSheetId());

            // Create relationship
            String relationshipId = createRelationship(session,
                    subjectEntityId,
                    objectEntityId,
                    edgeId,
                    proposal.getPredicateName(),
                    proposal.getFactSheetId(),
                    proposal.getSourceChunkId(),
                    proposal.getSourceDocumentId(),
                    proposal.getConfidence());

            log.debug("Stored proposal {} to Neo4j: subject={}, object={}, edge={}",
                    proposal.getProposalId(), subjectNodeId, objectNodeId, relationshipId);

            return StorageResult.success(subjectNodeId, objectNodeId, relationshipId);

        } catch (Exception e) {
            log.error("Failed to store proposal {} to Neo4j: {}", proposal.getProposalId(), e.getMessage(), e);
            return StorageResult.failure("Neo4j storage error: " + e.getMessage());
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            neo4jDriver.verifyConnectivity();
            return true;
        } catch (Exception e) {
            log.warn("Neo4j is not available: {}", e.getMessage());
            return false;
        }
    }

    private String upsertNode(Session session, String entityId, String title, String description,
                              String entityType, Long factSheetId) {
        Map<String, Object> params = new HashMap<>();
        params.put("entityId", entityId);
        params.put("title", title);
        params.put("description", description);
        params.put("entityType", entityType != null ? entityType : "UNKNOWN");
        params.put("factSheetId", factSheetId);
        params.put("labels", entityType != null ? List.of(entityType) : List.of());

        // Try with APOC first, fall back to simple query
        try {
            Result result = session.run(MERGE_NODE_QUERY, params);
            if (result.hasNext()) {
                return result.next().get("entityId").asString();
            }
        } catch (Exception e) {
            // APOC might not be installed, try simple query
            log.debug("APOC not available, using simple node creation: {}", e.getMessage());
            Result result = session.run(MERGE_NODE_SIMPLE_QUERY, params);
            if (result.hasNext()) {
                return result.next().get("entityId").asString();
            }
        }

        return entityId;
    }

    private String createRelationship(Session session, String subjectId, String objectId,
                                       String edgeId, String predicateName, Long factSheetId,
                                       String sourceChunkId, String sourceDocumentId, Double confidence) {
        Result result = session.run(CREATE_RELATIONSHIP_QUERY, Values.parameters(
                "subjectId", subjectId,
                "objectId", objectId,
                "edgeId", edgeId,
                "predicateName", predicateName,
                "factSheetId", factSheetId,
                "sourceChunkId", sourceChunkId,
                "sourceDocumentId", sourceDocumentId,
                "confidence", confidence != null ? confidence : 0.0
        ));

        if (result.hasNext()) {
            return result.next().get("edgeId").asString();
        }
        return edgeId;
    }

    private String generateEntityId(String name, Long factSheetId) {
        // Generate a deterministic ID based on name and factSheetId for deduplication
        if (name == null) {
            name = "unknown";
        }
        String input = name.toLowerCase().trim() + ":" + factSheetId;
        return UUID.nameUUIDFromBytes(input.getBytes()).toString();
    }
}
