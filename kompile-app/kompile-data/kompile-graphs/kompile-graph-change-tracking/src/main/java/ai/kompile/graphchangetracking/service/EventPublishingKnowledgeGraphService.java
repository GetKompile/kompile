package ai.kompile.graphchangetracking.service;

import ai.kompile.core.graphrag.maintenance.model.GraphPruneResult;
import ai.kompile.graphchangetracking.event.EdgeMutationEvent;
import ai.kompile.graphchangetracking.event.NodeMutationEvent;
import ai.kompile.knowledgegraph.domain.*;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Primary
@ConditionalOnBean(name = "knowledgeGraphDelegate")
@Slf4j
public class EventPublishingKnowledgeGraphService implements KnowledgeGraphService {

    private final KnowledgeGraphService delegate;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final MutationContextHolder contextHolder;

    public EventPublishingKnowledgeGraphService(
            @Qualifier("knowledgeGraphDelegate") KnowledgeGraphService delegate,
            ApplicationEventPublisher eventPublisher,
            ObjectMapper objectMapper,
            MutationContextHolder contextHolder) {
        this.delegate = delegate;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.contextHolder = contextHolder;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NODE MUTATIONS — publish events after delegation
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public GraphNode createOrUpdateSourceNode(String externalId, String title, String sourceType,
                                               String pathOrUrl, Map<String, Object> metadata) {
        Optional<GraphNode> existing = delegate.getNodeByExternalId(externalId, NodeLevel.SOURCE);
        String snapshotBefore = existing.map(this::toJson).orElse(null);

        GraphNode result = delegate.createOrUpdateSourceNode(externalId, title, sourceType, pathOrUrl, metadata);

        String mutationType = existing.isPresent() ? "NODE_UPDATED" : "NODE_CREATED";
        if ("NODE_CREATED".equals(mutationType)) {
            eventPublisher.publishEvent(NodeMutationEvent.created(this, result.getNodeId(),
                    result.getFactSheetId(), nodeTypeStr(result), toJson(result), contextHolder.current()));
        } else {
            eventPublisher.publishEvent(NodeMutationEvent.updated(this, result.getNodeId(),
                    result.getFactSheetId(), nodeTypeStr(result), snapshotBefore, toJson(result), contextHolder.current()));
        }
        return result;
    }

    @Override
    public GraphNode createDocumentNode(GraphNode sourceNode, String docId, String title,
                                         Map<String, Object> metadata) {
        GraphNode result = delegate.createDocumentNode(sourceNode, docId, title, metadata);
        eventPublisher.publishEvent(NodeMutationEvent.created(this, result.getNodeId(),
                result.getFactSheetId(), nodeTypeStr(result), toJson(result), contextHolder.current()));
        return result;
    }

    @Override
    public GraphNode createSnippetNode(GraphNode documentNode, String snippetId, String content, int chunkIndex) {
        GraphNode result = delegate.createSnippetNode(documentNode, snippetId, content, chunkIndex);
        eventPublisher.publishEvent(NodeMutationEvent.created(this, result.getNodeId(),
                result.getFactSheetId(), nodeTypeStr(result), toJson(result), contextHolder.current()));
        return result;
    }

    @Override
    public GraphNode createSnippetNode(GraphNode documentNode, String snippetId, String content,
                                        int chunkIndex, Map<String, Object> metadata) {
        GraphNode result = delegate.createSnippetNode(documentNode, snippetId, content, chunkIndex, metadata);
        eventPublisher.publishEvent(NodeMutationEvent.created(this, result.getNodeId(),
                result.getFactSheetId(), nodeTypeStr(result), toJson(result), contextHolder.current()));
        return result;
    }

    @Override
    public GraphNode createNode(NodeLevel nodeType, String externalId, String title,
                                 String description, Map<String, Object> metadata) {
        GraphNode result = delegate.createNode(nodeType, externalId, title, description, metadata);
        eventPublisher.publishEvent(NodeMutationEvent.created(this, result.getNodeId(),
                result.getFactSheetId(), nodeTypeStr(result), toJson(result), contextHolder.current()));
        return result;
    }

    @Override
    public GraphNode createNode(NodeLevel nodeType, String externalId, String title,
                                 String description, Map<String, Object> metadata, Long factSheetId) {
        GraphNode result = delegate.createNode(nodeType, externalId, title, description, metadata, factSheetId);
        eventPublisher.publishEvent(NodeMutationEvent.created(this, result.getNodeId(),
                result.getFactSheetId(), nodeTypeStr(result), toJson(result), contextHolder.current()));
        return result;
    }

    @Override
    public GraphNode updateNode(String nodeId, String title, String description, Map<String, Object> metadata) {
        Optional<GraphNode> before = delegate.getNode(nodeId);
        String snapshotBefore = before.map(this::toJson).orElse(null);

        GraphNode result = delegate.updateNode(nodeId, title, description, metadata);
        eventPublisher.publishEvent(NodeMutationEvent.updated(this, result.getNodeId(),
                result.getFactSheetId(), nodeTypeStr(result), snapshotBefore, toJson(result), contextHolder.current()));
        return result;
    }

    @Override
    public void deleteNode(String nodeId) {
        Optional<GraphNode> before = delegate.getNode(nodeId);
        String snapshotBefore = before.map(this::toJson).orElse(null);
        Long factSheetId = before.map(GraphNode::getFactSheetId).orElse(null);
        String nodeType = before.map(this::nodeTypeStr).orElse("UNKNOWN");

        delegate.deleteNode(nodeId);
        eventPublisher.publishEvent(NodeMutationEvent.deleted(this, nodeId, factSheetId,
                nodeType, snapshotBefore, contextHolder.current()));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EDGE MUTATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public GraphEdge createEdge(String sourceNodeId, String targetNodeId, EdgeType edgeType,
                                 Double weight, String description) {
        GraphEdge result = delegate.createEdge(sourceNodeId, targetNodeId, edgeType, weight, description);
        eventPublisher.publishEvent(EdgeMutationEvent.created(this, result.getEdgeId(),
                result.getFactSheetId(), edgeType.name(), sourceNodeId, targetNodeId,
                toJson(result), contextHolder.current()));
        return result;
    }

    @Override
    public GraphEdge createEdgeWithMetadata(String sourceNodeId, String targetNodeId,
                                             EdgeType edgeType, Double weight,
                                             String label, String description,
                                             String metaJson, EdgeProvenance provenance,
                                             Long factSheetId) {
        GraphEdge result = delegate.createEdgeWithMetadata(sourceNodeId, targetNodeId,
                edgeType, weight, label, description, metaJson, provenance, factSheetId);
        eventPublisher.publishEvent(EdgeMutationEvent.created(this, result.getEdgeId(),
                result.getFactSheetId(), edgeType.name(), sourceNodeId, targetNodeId,
                toJson(result), contextHolder.current()));
        return result;
    }

    @Override
    public GraphEdge updateEdge(String edgeId, Double weight, String description) {
        Optional<GraphEdge> before = delegate.getEdge(edgeId);
        String snapshotBefore = before.map(this::toJson).orElse(null);

        GraphEdge result = delegate.updateEdge(edgeId, weight, description);
        String sourceNodeId = result.getSourceNode() != null ? result.getSourceNode().getNodeId() : null;
        String targetNodeId = result.getTargetNode() != null ? result.getTargetNode().getNodeId() : null;
        eventPublisher.publishEvent(EdgeMutationEvent.updated(this, result.getEdgeId(),
                result.getFactSheetId(), result.getEdgeType().name(), sourceNodeId, targetNodeId,
                snapshotBefore, toJson(result), contextHolder.current()));
        return result;
    }

    @Override
    public void deleteEdge(String edgeId) {
        Optional<GraphEdge> before = delegate.getEdge(edgeId);
        String snapshotBefore = before.map(this::toJson).orElse(null);
        Long factSheetId = before.map(GraphEdge::getFactSheetId).orElse(null);
        String edgeType = before.map(e -> e.getEdgeType().name()).orElse("UNKNOWN");
        String sourceNodeId = before.map(e -> e.getSourceNode() != null ? e.getSourceNode().getNodeId() : null).orElse(null);
        String targetNodeId = before.map(e -> e.getTargetNode() != null ? e.getTargetNode().getNodeId() : null).orElse(null);

        delegate.deleteEdge(edgeId);
        eventPublisher.publishEvent(EdgeMutationEvent.deleted(this, edgeId, factSheetId,
                edgeType, sourceNodeId, targetNodeId, snapshotBefore, contextHolder.current()));
    }

    @Override
    public void deleteEdgesBulk(List<String> edgeIds) {
        if (edgeIds != null) {
            edgeIds.forEach(this::deleteEdge);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // READ-ONLY METHODS — pure delegation
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public Optional<GraphNode> getNode(String nodeId) {
        return delegate.getNode(nodeId);
    }

    @Override
    public Optional<GraphNode> getNodeByExternalId(String externalId, NodeLevel nodeType) {
        return delegate.getNodeByExternalId(externalId, nodeType);
    }

    @Override
    public Optional<GraphNode> getNodeByExternalId(String externalId, NodeLevel nodeType, Long factSheetId) {
        return delegate.getNodeByExternalId(externalId, nodeType, factSheetId);
    }

    @Override
    public List<GraphNode> getChildren(String parentNodeId) {
        return delegate.getChildren(parentNodeId);
    }

    @Override
    public List<GraphNode> getAllSources() {
        return delegate.getAllSources();
    }

    @Override
    public List<GraphNode> getNodesByType(NodeLevel type, int limit) {
        return delegate.getNodesByType(type, limit);
    }

    @Override
    public List<GraphNode> searchNodes(String query, NodeLevel type, int limit) {
        return delegate.searchNodes(query, type, limit);
    }

    @Override
    public Optional<GraphEdge> getEdge(String edgeId) {
        return delegate.getEdge(edgeId);
    }

    @Override
    public List<GraphEdge> getEdgesForNode(String nodeId) {
        return delegate.getEdgesForNode(nodeId);
    }

    @Override
    public List<GraphEdge> getEdgesByType(String nodeId, EdgeType edgeType) {
        return delegate.getEdgesByType(nodeId, edgeType);
    }

    @Override
    public boolean edgeExists(String sourceNodeId, String targetNodeId) {
        return delegate.edgeExists(sourceNodeId, targetNodeId);
    }

    @Override
    public boolean edgeExists(String sourceNodeId, String targetNodeId, EdgeType edgeType, String label, Long factSheetId) {
        return delegate.edgeExists(sourceNodeId, targetNodeId, edgeType, label, factSheetId);
    }

    @Override
    public List<GraphEdge> searchEdges(String query, EdgeType edgeType, int limit) {
        return delegate.searchEdges(query, edgeType, limit);
    }

    @Override
    public List<GraphNode> getConnectedNodes(String nodeId, int depth) {
        return delegate.getConnectedNodes(nodeId, depth);
    }

    @Override
    public List<GraphNode> findRelatedNodes(String nodeId, int maxResults) {
        return delegate.findRelatedNodes(nodeId, maxResults);
    }

    @Override
    public List<GraphNode> findShortestPath(String fromNodeId, String toNodeId, int maxDepth) {
        return delegate.findShortestPath(fromNodeId, toNodeId, maxDepth);
    }

    @Override
    public Map<String, Double> computeNodeRelevance(String queryNodeId, List<String> candidateNodeIds) {
        return delegate.computeNodeRelevance(queryNodeId, candidateNodeIds);
    }

    @Override
    public Map<String, Object> getGraphStatistics() {
        return delegate.getGraphStatistics();
    }

    @Override
    public Map<String, Object> getVisualizationData(String rootNodeId, int depth, int maxNodes) {
        return delegate.getVisualizationData(rootNodeId, depth, maxNodes);
    }

    @Override
    public void flushPendingNodes() {
        delegate.flushPendingNodes();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FACT-SHEET-SCOPED NODE QUERIES — pure delegation
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public List<GraphNode> getNodesByTypeInFactSheet(Long factSheetId, NodeLevel type) {
        return delegate.getNodesByTypeInFactSheet(factSheetId, type);
    }

    @Override
    public List<GraphNode> getNodesInFactSheet(Long factSheetId) {
        return delegate.getNodesInFactSheet(factSheetId);
    }

    @Override
    public List<GraphNode> getSourcesInFactSheet(Long factSheetId) {
        return delegate.getSourcesInFactSheet(factSheetId);
    }

    @Override
    public Optional<GraphNode> getNodeByExternalIdInFactSheet(String externalId, NodeLevel type, Long factSheetId) {
        return delegate.getNodeByExternalIdInFactSheet(externalId, type, factSheetId);
    }

    @Override
    public List<GraphNode> searchNodesInFactSheet(Long factSheetId, String query, int limit) {
        return delegate.searchNodesInFactSheet(factSheetId, query, limit);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BATCH NODE OPERATIONS — pure delegation
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public List<GraphNode> getNodesByIds(List<String> nodeIds) {
        return delegate.getNodesByIds(nodeIds);
    }

    @Override
    public List<GraphNode> getNodesByType(NodeLevel type) {
        return delegate.getNodesByType(type);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FACT-SHEET-SCOPED EDGE QUERIES — pure delegation
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public List<GraphEdge> getEdgesForNodeInFactSheet(String nodeId, Long factSheetId) {
        return delegate.getEdgesForNodeInFactSheet(nodeId, factSheetId);
    }

    @Override
    public boolean edgeExistsInFactSheet(String sourceNodeId, String targetNodeId, Long factSheetId) {
        return delegate.edgeExistsInFactSheet(sourceNodeId, targetNodeId, factSheetId);
    }

    @Override
    public List<GraphEdge> getEdgesInFactSheet(Long factSheetId) {
        return delegate.getEdgesInFactSheet(factSheetId);
    }

    @Override
    public List<GraphEdge> getEdgesByTypeInFactSheet(Long factSheetId, EdgeType edgeType) {
        return delegate.getEdgesByTypeInFactSheet(factSheetId, edgeType);
    }

    @Override
    public GraphEdge findEdgeBetweenNodes(String sourceNodeId, String targetNodeId) {
        return delegate.findEdgeBetweenNodes(sourceNodeId, targetNodeId);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ENTITY MENTION OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public List<EntityMention> getEntityMentionsForNode(GraphNode node) {
        return delegate.getEntityMentionsForNode(node);
    }

    @Override
    public List<EntityMention> getEntityMentionsForNode(String nodeId) {
        return delegate.getEntityMentionsForNode(nodeId);
    }

    @Override
    public Optional<EntityMention> findEntityMention(GraphNode node, String entityName) {
        return delegate.findEntityMention(node, entityName);
    }

    @Override
    public Optional<EntityMention> findEntityMentionInFactSheet(GraphNode node, String entityName,
                                                                  Long factSheetId) {
        return delegate.findEntityMentionInFactSheet(node, entityName, factSheetId);
    }

    @Override
    public EntityMention saveEntityMention(EntityMention mention) {
        return delegate.saveEntityMention(mention);
    }

    @Override
    public List<Object[]> findNodePairsWithSharedEntities(int minShared) {
        return delegate.findNodePairsWithSharedEntities(minShared);
    }

    @Override
    public List<Object[]> findNodePairsWithSharedEntitiesInFactSheet(Long factSheetId, int minShared) {
        return delegate.findNodePairsWithSharedEntitiesInFactSheet(factSheetId, minShared);
    }

    @Override
    public List<String> getEntityNamesForNode(String nodeId) {
        return delegate.getEntityNamesForNode(nodeId);
    }

    @Override
    public List<GraphNode> getNodesWithEntity(String entityName) {
        return delegate.getNodesWithEntity(entityName);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // COUNT / STATISTICS — pure delegation
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public long countNodesByType(NodeLevel type) {
        return delegate.countNodesByType(type);
    }

    @Override
    public long countNodesByTypeInFactSheet(Long factSheetId, NodeLevel type) {
        return delegate.countNodesByTypeInFactSheet(factSheetId, type);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MAINTENANCE
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public void deleteByFactSheetId(Long factSheetId) {
        delegate.deleteByFactSheetId(factSheetId);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRUNING / MAINTENANCE — pure delegation (read-only, no events published)
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public List<String> findOrphanNodeIds(Long factSheetId) {
        return delegate.findOrphanNodeIds(factSheetId);
    }

    @Override
    public List<String> findLowConfidenceNodeIds(Long factSheetId, double minConfidence) {
        return delegate.findLowConfidenceNodeIds(factSheetId, minConfidence);
    }

    @Override
    public List<String> findLowConfidenceEdgeIds(Long factSheetId, double minConfidence) {
        return delegate.findLowConfidenceEdgeIds(factSheetId, minConfidence);
    }

    @Override
    public long countActiveNodes(Long factSheetId) {
        return delegate.countActiveNodes(factSheetId);
    }

    @Override
    public List<String> findActiveEdgeIds(Long factSheetId) {
        return delegate.findActiveEdgeIds(factSheetId);
    }

    @Override
    public GraphPruneResult pruneNodes(Collection<String> nodeIds,
                                       boolean softDelete,
                                       Duration grace,
                                       boolean dryRun) {
        return delegate.pruneNodes(nodeIds, softDelete, grace, dryRun);
    }

    @Override
    public GraphPruneResult pruneEdges(Collection<String> edgeIds,
                                       boolean softDelete,
                                       boolean dryRun) {
        return delegate.pruneEdges(edgeIds, softDelete, dryRun);
    }

    @Override
    public GraphPruneResult hardDeleteStaleNodes(Long factSheetId, Duration grace) {
        return delegate.hardDeleteStaleNodes(factSheetId, grace);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize graph entity to JSON", e);
            return null;
        }
    }

    private String nodeTypeStr(GraphNode node) {
        return node.getNodeType() != null ? node.getNodeType().name() : "UNKNOWN";
    }
}
