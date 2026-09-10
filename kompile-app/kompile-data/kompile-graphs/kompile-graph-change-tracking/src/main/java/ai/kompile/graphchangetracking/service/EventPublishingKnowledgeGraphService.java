package ai.kompile.graphchangetracking.service;

import ai.kompile.core.graphrag.maintenance.model.GraphPruneResult;
import ai.kompile.graphchangetracking.domain.GraphMutationRecord;
import ai.kompile.graphchangetracking.event.EdgeMutationEvent;
import ai.kompile.graphchangetracking.event.GraphBatchMutationEvent;
import ai.kompile.graphchangetracking.event.NodeMutationEvent;
import ai.kompile.knowledgegraph.domain.*;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.nd4j.linalg.api.ndarray.INDArray;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Primary;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@Primary
@ConditionalOnBean(name = "knowledgeGraphDelegate")
@Slf4j
public class EventPublishingKnowledgeGraphService implements KnowledgeGraphService {

    private final KnowledgeGraphService delegate;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final MutationContextHolder contextHolder;

    /**
     * Optional — mutation store for recording batch-write entries that bypass the per-item
     * event path. Injected when present (i.e. kompile-graph-change-tracking is on the classpath
     * and both beans are in the same Spring context). Null-safe everywhere.
     */
    @Nullable
    private GraphMutationStore mutationStore;

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

    /** Setter injection so the store is optional (avoids circular-bean issues in thin test contexts). */
    @Autowired(required = false)
    public void setMutationStore(GraphMutationStore mutationStore) {
        this.mutationStore = mutationStore;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NODE EMBEDDINGS — pure delegation (read/index; no graph mutation to publish)
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public Map<String, INDArray> exportNodeEmbeddings(Long factSheetId) {
        return delegate.exportNodeEmbeddings(factSheetId);
    }

    @Override
    public int applyNodeEmbeddings(Map<String, INDArray> embeddingsByNodeId) {
        return delegate.applyNodeEmbeddings(embeddingsByNodeId);
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
    public List<GraphNode> createNodesBatch(List<NodeSpec> specs, Long factSheetId) {
        List<GraphNode> result = delegate.createNodesBatch(specs, factSheetId);
        try {
            if (result != null && !result.isEmpty()) {
                recordBatchNodes(result, "NODE_CREATED", factSheetId);
                publishBatch("NODES_CREATED", factSheetId, result.size());
            }
        } catch (Exception e) {
            log.warn("EventPublishingKnowledgeGraphService: batch event/log failed for createNodesBatch factSheet={}: {}",
                    factSheetId, e.getMessage());
        }
        return result;
    }

    @Override
    public List<GraphNode> createSnippetNodesBatch(List<SnippetSpec> specs) {
        List<GraphNode> result = delegate.createSnippetNodesBatch(specs);
        try {
            if (result != null && !result.isEmpty()) {
                // Snippet nodes may span multiple fact sheets; derive factSheetId from first node
                Long factSheetId = result.get(0).getFactSheetId();
                recordBatchNodes(result, "NODE_CREATED", factSheetId);
                publishBatch("SNIPPET_NODES_CREATED", factSheetId, result.size());
            }
        } catch (Exception e) {
            log.warn("EventPublishingKnowledgeGraphService: batch event/log failed for createSnippetNodesBatch: {}",
                    e.getMessage());
        }
        return result;
    }

    @Override
    public int updateNodesBatch(List<NodeUpdate> updates) {
        int result = delegate.updateNodesBatch(updates);
        try {
            if (result > 0 && updates != null && !updates.isEmpty()) {
                // NodeUpdate doesn't carry factSheetId; emit a batch event without factSheet scoping
                publishBatch("NODES_UPDATED", null, result);
                if (mutationStore != null) {
                    MutationContextHolder.MutationContext ctx = contextHolder.current();
                    for (NodeUpdate u : updates) {
                        if (u == null) continue;
                        GraphMutationRecord rec = GraphMutationRecord.builder()
                                .mutationType("NODE_UPDATED")
                                .entityKind("NODE")
                                .entityId(u.nodeId())
                                .factSheetId(null)
                                .changesetId(ctx.changesetId())
                                .triggerSource(ctx.triggerSource())
                                .actorId(ctx.actorId())
                                .build();
                        try { mutationStore.save(rec); } catch (Exception ex) {
                            log.warn("mutationStore.save failed for nodeId {}: {}", u.nodeId(), ex.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("EventPublishingKnowledgeGraphService: batch event/log failed for updateNodesBatch: {}",
                    e.getMessage());
        }
        return result;
    }

    @Override
    public int updateNodeKgeMetadataBatch(List<NodeMetadataUpdate> updates) {
        int result = delegate.updateNodeKgeMetadataBatch(updates);
        try {
            if (result > 0) {
                publishBatch("NODES_METADATA_UPDATED", null, result);
            }
        } catch (Exception e) {
            log.warn("EventPublishingKnowledgeGraphService: batch event/log failed for updateNodeKgeMetadataBatch: {}",
                    e.getMessage());
        }
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
    public int createEdgesBatch(List<EdgeSpec> specs) {
        int result = delegate.createEdgesBatch(specs);
        try {
            if (result > 0 && specs != null && !specs.isEmpty()) {
                // Derive factSheetId from the first spec (most batches are scoped to one fact sheet)
                Long factSheetId = specs.stream()
                        .filter(s -> s != null && s.factSheetId() != null)
                        .map(EdgeSpec::factSheetId)
                        .findFirst()
                        .orElse(null);
                recordBatchEdges(specs, result, "EDGE_CREATED", factSheetId);
                publishBatch("EDGES_CREATED", factSheetId, result);
            }
        } catch (Exception e) {
            log.warn("EventPublishingKnowledgeGraphService: batch event/log failed for createEdgesBatch factSheet=<derived>: {}",
                    e.getMessage());
        }
        return result;
    }

    @Override
    public int updateEdgeMetadataBatch(List<EdgeMetadataUpdate> updates) {
        int result = delegate.updateEdgeMetadataBatch(updates);
        try {
            if (result > 0) {
                publishBatch("EDGES_METADATA_UPDATED", null, result);
            }
        } catch (Exception e) {
            log.warn("EventPublishingKnowledgeGraphService: batch event/log failed for updateEdgeMetadataBatch: {}",
                    e.getMessage());
        }
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
    public List<GraphNode> getNodesByExternalIds(List<ExternalNodeLookup> lookups) {
        return delegate.getNodesByExternalIds(lookups);
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
    public GraphPage<GraphNode> getNodesInFactSheetPage(Long factSheetId, int cursor, int pageSize) {
        return delegate.getNodesInFactSheetPage(factSheetId, cursor, pageSize);
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
    public GraphPage<GraphEdge> getEdgesInFactSheetPage(Long factSheetId, int cursor, int pageSize) {
        return delegate.getEdgesInFactSheetPage(factSheetId, cursor, pageSize);
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

    /**
     * Delete every node and edge belonging to {@code factSheetId}.
     *
     * <p>Per-item enumeration before deletion is intentionally skipped here — the matrix store
     * can contain millions of nodes and materialising them just for audit would be prohibitively
     * expensive. Instead a single consolidated {@link GraphBatchMutationEvent} is published so
     * {@link ai.kompile.graphchangetracking.hook.GroundingCascadeEventListener} can schedule a
     * re-ground (which will be a no-op because the fact sheet is gone, but it also clears the
     * stale flag). A summary {@link GraphMutationRecord} with type {@code FACT_SHEET_DELETED}
     * is written to the mutation store for audit trail purposes.</p>
     */
    @Override
    public void deleteByFactSheetId(Long factSheetId) {
        delegate.deleteByFactSheetId(factSheetId);
        try {
            MutationContextHolder.MutationContext ctx = contextHolder.current();
            // Write a consolidated audit record for the entire fact-sheet deletion.
            if (mutationStore != null) {
                GraphMutationRecord rec = GraphMutationRecord.builder()
                        .mutationType("FACT_SHEET_DELETED")
                        .entityKind("FACT_SHEET")
                        .entityId(String.valueOf(factSheetId))
                        .factSheetId(factSheetId)
                        .changesetId(ctx.changesetId())
                        .triggerSource(ctx.triggerSource())
                        .actorId(ctx.actorId())
                        .build();
                mutationStore.save(rec);
            }
            // Publish a batch event so cascade scheduling and stale-marking fire automatically.
            publishBatch("FACT_SHEET_DELETED", factSheetId, 1);
            log.info("EventPublishingKnowledgeGraphService: published FACT_SHEET_DELETED event "
                    + "for factSheet={} — FactStore phantom atoms will be cleared by cascade",
                    factSheetId);
        } catch (Exception e) {
            log.warn("EventPublishingKnowledgeGraphService: event/log failed for deleteByFactSheetId "
                    + "factSheet={}: {}", factSheetId, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRUNING / MAINTENANCE — emits events so cascade scheduling fires correctly
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public List<String> findOrphanNodeIds(Long factSheetId) {
        return delegate.findOrphanNodeIds(factSheetId);
    }

    @Override
    public List<String> findOrphanNodeIds(Long factSheetId, Set<NodeLevel> levels) {
        return delegate.findOrphanNodeIds(factSheetId, levels);
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

    /**
     * Prune (soft-delete or hard-delete) a collection of nodes.
     *
     * <p>After delegating, if the operation was not a dry-run and actually affected items, one
     * {@link GraphBatchMutationEvent} is published per fact sheet found in the affected IDs so
     * the grounding cascade can re-project the affected graphs. Per-item
     * {@link GraphMutationRecord}s are written for itemised audit; when {@code affectedIds} is
     * empty (bulk hard-delete path) a single summary record is written instead.</p>
     */
    @Override
    public GraphPruneResult pruneNodes(Collection<String> nodeIds,
                                       boolean softDelete,
                                       Duration grace,
                                       boolean dryRun) {
        GraphPruneResult result = delegate.pruneNodes(nodeIds, softDelete, grace, dryRun);
        if (!dryRun && result.affectedCount() > 0) {
            try {
                String mutType = softDelete ? "NODE_SOFT_DELETED" : "NODE_HARD_DELETED";
                recordAndPublishPruneResult(result, mutType, "NODE", null);
            } catch (Exception e) {
                log.warn("EventPublishingKnowledgeGraphService: event/log failed for pruneNodes: {}",
                        e.getMessage());
            }
        }
        return result;
    }

    @Override
    public GraphPruneResult pruneNodes(Collection<String> nodeIds,
                                       boolean softDelete,
                                       Duration grace,
                                       boolean dryRun,
                                       Long factSheetId) {
        GraphPruneResult result = delegate.pruneNodes(
                nodeIds, softDelete, grace, dryRun, factSheetId);
        if (!dryRun && result.affectedCount() > 0) {
            try {
                String mutType = softDelete ? "NODE_SOFT_DELETED" : "NODE_HARD_DELETED";
                recordAndPublishPruneResult(result, mutType, "NODE", factSheetId);
            } catch (Exception e) {
                log.warn("EventPublishingKnowledgeGraphService: event/log failed for scoped pruneNodes: {}",
                        e.getMessage());
            }
        }
        return result;
    }

    /**
     * Prune (soft-delete or hard-delete) a collection of edges.
     *
     * <p>Same event/audit contract as {@link #pruneNodes}.</p>
     */
    @Override
    public GraphPruneResult pruneEdges(Collection<String> edgeIds,
                                       boolean softDelete,
                                       boolean dryRun) {
        GraphPruneResult result = delegate.pruneEdges(edgeIds, softDelete, dryRun);
        if (!dryRun && result.affectedCount() > 0) {
            try {
                String mutType = softDelete ? "EDGE_SOFT_DELETED" : "EDGE_HARD_DELETED";
                recordAndPublishPruneResult(result, mutType, "EDGE", null);
            } catch (Exception e) {
                log.warn("EventPublishingKnowledgeGraphService: event/log failed for pruneEdges: {}",
                        e.getMessage());
            }
        }
        return result;
    }

    /**
     * Hard-delete nodes whose stale flag has aged past {@code grace} for the given fact sheet.
     *
     * <p>A {@link GraphBatchMutationEvent} is published so the cascade reschedules re-grounding
     * and the stale flag is refreshed. Because {@code hardDeleteStaleNodes} returns only a count
     * (no per-item IDs when using the bulk-delete path), per-item records are not written; instead
     * a single {@code NODES_HARD_DELETED_BULK} summary record is written to the mutation store.</p>
     */
    @Override
    public GraphPruneResult hardDeleteStaleNodes(Long factSheetId, Duration grace) {
        GraphPruneResult result = delegate.hardDeleteStaleNodes(factSheetId, grace);
        if (result.hardDeleted() > 0) {
            try {
                recordAndPublishPruneResult(result, "NODES_HARD_DELETED_BULK", "NODE", factSheetId);
                log.info("EventPublishingKnowledgeGraphService: hardDeleteStaleNodes factSheet={} "
                        + "deleted={} — cascade scheduled", factSheetId, result.hardDeleted());
            } catch (Exception e) {
                log.warn("EventPublishingKnowledgeGraphService: event/log failed for hardDeleteStaleNodes "
                        + "factSheet={}: {}", factSheetId, e.getMessage());
            }
        }
        return result;
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

    /** Publish a single coalesced batch event — NEVER throws. */
    private void publishBatch(String batchType, Long factSheetId, int count) {
        try {
            MutationContextHolder.MutationContext ctx = contextHolder.current();
            eventPublisher.publishEvent(new GraphBatchMutationEvent(
                    this, batchType, factSheetId, count, ctx.changesetId(), ctx.triggerSource()));
        } catch (Exception e) {
            log.warn("Failed to publish GraphBatchMutationEvent type={} factSheet={}: {}",
                    batchType, factSheetId, e.getMessage());
        }
    }

    /** Record one mutation entry per node into GraphMutationStore — NEVER throws. */
    private void recordBatchNodes(List<GraphNode> nodes, String mutationType, Long defaultFactSheetId) {
        if (mutationStore == null || nodes == null) return;
        MutationContextHolder.MutationContext ctx = contextHolder.current();
        for (GraphNode node : nodes) {
            if (node == null) continue;
            try {
                Long fsId = node.getFactSheetId() != null ? node.getFactSheetId() : defaultFactSheetId;
                GraphMutationRecord rec = GraphMutationRecord.builder()
                        .mutationType(mutationType)
                        .entityKind("NODE")
                        .entityId(node.getNodeId())
                        .factSheetId(fsId)
                        .changesetId(ctx.changesetId())
                        .triggerSource(ctx.triggerSource())
                        .actorId(ctx.actorId())
                        .snapshotAfter(toJson(node))
                        .build();
                mutationStore.save(rec);
            } catch (Exception e) {
                log.warn("recordBatchNodes: failed for nodeId {}: {}", node.getNodeId(), e.getMessage());
            }
        }
    }

    /**
     * Write per-item (or summary) mutation records and publish a coalesced batch event for a
     * prune/maintenance operation — NEVER throws.
     *
     * <p>When {@code result.affectedIds()} is non-empty, one {@link GraphMutationRecord} is
     * written per ID. When {@code affectedIds} is empty (bulk-delete path, only a count is
     * available), a single summary record is written using {@code "bulk"} as the entity ID.
     * A single {@link GraphBatchMutationEvent} is published for the {@code factSheetId} (which
     * may be supplied directly, or derived from the mutationType tag when the IDs give no
     * fact-sheet context).</p>
     *
     * @param result         prune result from the delegate
     * @param mutationType   audit label, e.g. {@code "NODE_SOFT_DELETED"} or {@code "NODES_HARD_DELETED_BULK"}
     * @param entityKind     {@code "NODE"} or {@code "EDGE"}
     * @param factSheetId    explicit fact-sheet ID, or {@code null} to derive from context
     */
    private void recordAndPublishPruneResult(GraphPruneResult result,
                                              String mutationType,
                                              String entityKind,
                                              Long factSheetId) {
        MutationContextHolder.MutationContext ctx = contextHolder.current();
        if (mutationStore != null) {
            List<String> ids = result.affectedIds();
            if (ids != null && !ids.isEmpty()) {
                for (String id : ids) {
                    try {
                        GraphMutationRecord rec = GraphMutationRecord.builder()
                                .mutationType(mutationType)
                                .entityKind(entityKind)
                                .entityId(id)
                                .factSheetId(factSheetId)
                                .changesetId(ctx.changesetId())
                                .triggerSource(ctx.triggerSource())
                                .actorId(ctx.actorId())
                                .build();
                        mutationStore.save(rec);
                    } catch (Exception ex) {
                        log.warn("recordAndPublishPruneResult: failed for id {}: {}", id, ex.getMessage());
                    }
                }
            } else {
                // Bulk path — only a count is available; write a single summary record.
                try {
                    int count = result.affectedCount() > 0 ? result.affectedCount() : result.hardDeleted();
                    GraphMutationRecord rec = GraphMutationRecord.builder()
                            .mutationType(mutationType)
                            .entityKind(entityKind)
                            .entityId("bulk:" + count)
                            .factSheetId(factSheetId)
                            .changesetId(ctx.changesetId())
                            .triggerSource(ctx.triggerSource())
                            .actorId(ctx.actorId())
                            .build();
                    mutationStore.save(rec);
                } catch (Exception ex) {
                    log.warn("recordAndPublishPruneResult: bulk summary record failed: {}", ex.getMessage());
                }
            }
        }
        // Publish the batch event so GroundingCascadeEventListener schedules re-grounding.
        int itemCount = result.affectedCount() > 0 ? result.affectedCount() : result.hardDeleted();
        publishBatch(mutationType, factSheetId, itemCount);
    }

    /** Record one mutation entry per edge spec into GraphMutationStore — NEVER throws. */
    private void recordBatchEdges(List<EdgeSpec> specs, int createdCount, String mutationType, Long defaultFactSheetId) {
        if (mutationStore == null || specs == null) return;
        MutationContextHolder.MutationContext ctx = contextHolder.current();
        int recorded = 0;
        for (EdgeSpec spec : specs) {
            if (spec == null || recorded >= createdCount) break;
            try {
                Long fsId = spec.factSheetId() != null ? spec.factSheetId() : defaultFactSheetId;
                GraphMutationRecord rec = GraphMutationRecord.builder()
                        .mutationType(mutationType)
                        .entityKind("EDGE")
                        .entityId(spec.sourceNodeId() + "->" + spec.targetNodeId())
                        .factSheetId(fsId)
                        .changesetId(ctx.changesetId())
                        .triggerSource(ctx.triggerSource())
                        .actorId(ctx.actorId())
                        .build();
                mutationStore.save(rec);
                recorded++;
            } catch (Exception e) {
                log.warn("recordBatchEdges: failed for spec {}->{}: {}",
                        spec.sourceNodeId(), spec.targetNodeId(), e.getMessage());
            }
        }
    }
}
