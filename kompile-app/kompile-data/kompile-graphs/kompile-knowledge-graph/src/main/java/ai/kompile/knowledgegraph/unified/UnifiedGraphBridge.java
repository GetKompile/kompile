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
package ai.kompile.knowledgegraph.unified;

import ai.kompile.core.graphbuilder.GraphBuildCompletedEvent;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.kompile.knowledgegraph.reasoning.GraphToFactStoreProjector;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Bridges the live fact-sheet graph (matrix/vector store) to the infrastructure-free
 * {@link UnifiedGraph} used by the reasoning library.
 *
 * <p>{@link #export(Long)} projects a fact sheet's entity-level nodes and their non-stale edges
 * into a {@code UnifiedGraph}: node metadata becomes entity attributes (so type memberships —
 * {@code entity_type}, {@code owlInferredTypes} — resolve through the standard
 * {@link GraphEntity#typeMemberships()} conventions), and the semantic {@code relationType} is
 * preferred over the structural edge enum so ontology-typed relations survive the projection.</p>
 *
 * <p>The result is reasoning-ready: consumers hand it directly to claim dossiers, explain
 * orchestration, verification, and the graph query facade. The projection is a snapshot; callers
 * should not mutate the store through it.</p>
 */
@Service
public class UnifiedGraphBridge {

    private static final Logger log = LoggerFactory.getLogger(UnifiedGraphBridge.class);
    private static final ObjectMapper ARCHIVE_MAPPER = new ObjectMapper();

    private final KnowledgeGraphService knowledgeGraphService;

    @Autowired(required = false)
    private UnifiedGraphAnalysisAssetStore analysisAssets;

    @Autowired(required = false)
    private GraphToFactStoreProjector factStoreProjector;

    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;

    @Autowired(required = false)
    private List<UnifiedGraphArtifactContributor> artifactContributors = List.of();

    @Autowired(required = false)
    private List<UnifiedGraphArtifactImporter> artifactImporters = List.of();

    @Autowired
    public UnifiedGraphBridge(KnowledgeGraphService knowledgeGraphService) {
        this.knowledgeGraphService = knowledgeGraphService;
    }

    /** Result returned by unified graph imports and snapshot restores. */
    public record ImportSummary(
            int nodes,
            int edges,
            int embeddings,
            int atoms,
            boolean graphBuildEventPublished) {

        /** Backward-compatible constructor used by existing callers and tests. */
        public ImportSummary(int nodes, int edges, int embeddings, int atoms) {
            this(nodes, edges, embeddings, atoms, false);
        }
    }

    /**
     * Export a fact-sheet graph, or the complete graph when {@code factSheetId} is null.
     * This nullable signature is the shared contract used by graph export and snapshots; primitive
     * caller values are boxed automatically, avoiding ambiguous mock/caller overloads.
     */
    public UnifiedGraph export(Long factSheetId) {
        return export(factSheetId, null);
    }

    /** Export a fact-sheet graph restricted to one named graph when requested. */
    public UnifiedGraph export(Long factSheetId, String namedGraphId) {
        UnifiedGraph graph = new UnifiedGraph()
                .graphId(factSheetId == null ? "global" : "factsheet_" + factSheetId)
                .factSheetId(factSheetId);
        if (knowledgeGraphService == null) {
            return graph;
        }

        Optional<UnifiedGraph> previous = factSheetId != null && analysisAssets != null
                ? analysisAssets.get(factSheetId)
                : Optional.empty();

        List<GraphNode> nodes = nodesForScope(factSheetId);
        if (namedGraphId != null) {
            nodes = nodes.stream().filter(node -> belongsToNamedGraph(node, namedGraphId)).toList();
        }
        for (GraphNode node : nodes) {
            if (node == null || node.getNodeId() == null) {
                continue;
            }
            Map<String, Object> attributes = new LinkedHashMap<>();
            Map<String, Object> store = nodeStoreState(node);
            attributes.put("kompile.store", store);
            String type = firstString(node.getMetadata(), "entity_type", "entityType", "type");
            if (type == null || type.isBlank()) {
                type = node.getNodeType() != null ? node.getNodeType().name() : "ENTITY";
            }
            var builder = GraphEntity.builder(node.getNodeId())
                    .type(type)
                    .label(node.getTitle() != null ? node.getTitle() : node.getNodeId())
                    .confidence(node.getConfidence() != null ? node.getConfidence() : 1.0)
                    .attributes(attributes);
            if (node.getOccurredAt() != null) {
                builder.timestamp(node.getOccurredAt().toInstant(ZoneOffset.UTC));
            }
            if (node.getKgEmbedding() != null && node.getKgEmbedding().length() > 0) {
                builder.embedding(node.getKgEmbedding().ravel().toDoubleVector());
            } else {
                previous.flatMap(asset -> asset.entity(node.getNodeId()))
                        .filter(GraphEntity::hasEmbedding)
                        .ifPresent(entity -> builder.embedding(entity.embedding()));
            }
            graph.addEntity(builder.build());
        }

        int edges = 0;
        for (GraphEdge edge : edgesForScope(factSheetId, nodes)) {
            if (edge == null) {
                continue;
            }
            String source = edge.getSourceNode() != null
                    ? edge.getSourceNode().getNodeId() : edge.getSourceNodeId();
            String target = edge.getTargetNode() != null
                    ? edge.getTargetNode().getNodeId() : edge.getTargetNodeId();
            if (source == null || target == null) {
                continue;
            }
            if (namedGraphId != null
                    && (graph.entity(source).isEmpty() || graph.entity(target).isEmpty())) {
                continue;
            }
            if (graph.entity(source).isEmpty()) {
                graph.addEntity(GraphEntity.builder(source).type("MISSING_ENDPOINT").label(source).build());
            }
            if (graph.entity(target).isEmpty()) {
                graph.addEntity(GraphEntity.builder(target).type("MISSING_ENDPOINT").label(target).build());
            }
            String type = edge.getRelationType() != null && !edge.getRelationType().isBlank()
                    ? edge.getRelationType()
                    : (edge.getEdgeType() != null ? edge.getEdgeType().name() : "");
            double weight = edge.getWeight() != null ? edge.getWeight()
                    : (edge.getConfidence() != null ? edge.getConfidence() : 1.0);
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("kompile.store", edgeStoreState(edge));

            var relationBuilder = GraphRelation.builder(
                            edge.getEdgeId() != null ? edge.getEdgeId()
                                    : stableEdgeId(source, target, type),
                            source, target)
                    .type(type)
                    .weight(weight)
                    .confidence(edge.getConfidence() != null ? edge.getConfidence() : weight)
                    .directed(!Boolean.TRUE.equals(edge.getBidirectional()))
                    .attributes(attributes);
            if (edge.getOccurredAt() != null) {
                relationBuilder.timestamp(edge.getOccurredAt().toInstant(ZoneOffset.UTC));
            }
            if (edge.getKgRelationEmbedding() != null && edge.getKgRelationEmbedding().length() > 0) {
                relationBuilder.embedding(edge.getKgRelationEmbedding().ravel().toDoubleVector());
            } else if (edge.getEdgeId() != null) {
                previous.flatMap(asset -> asset.relations().stream()
                                .filter(relation -> edge.getEdgeId().equals(relation.id()))
                                .findFirst())
                        .filter(GraphRelation::hasEmbedding)
                        .ifPresent(relation -> relationBuilder.embedding(relation.embedding()));
            }
            graph.addRelation(relationBuilder.build());
            edges++;
        }

        previous.ifPresent(asset -> mergeAnalysisAssets(asset, graph));
        if (artifactContributors != null) {
            for (UnifiedGraphArtifactContributor contributor : artifactContributors) {
                try {
                    contributor.contribute(factSheetId, graph);
                } catch (RuntimeException e) {
                    throw new IllegalStateException("Unified graph artifact contribution failed for "
                            + contributor.getClass().getName(), e);
                }
            }
        }
        log.debug("UnifiedGraphBridge.export factSheet={}: {} entities, {} relations",
                factSheetId, graph.entityCount(), edges);
        return graph;
    }

    /** Export a graph directly to a portable {@code .kgraph} file. */
    public void exportToFile(Path file, Long factSheetId) throws IOException {
        export(factSheetId).save(file);
    }

    /** Export a graph as portable {@code .kgraph} bytes. */
    public byte[] exportBytes(Long factSheetId) throws IOException {
        return exportBytes(factSheetId, null);
    }

    /** Export a named-graph subset as portable {@code .kgraph} bytes. */
    public byte[] exportBytes(Long factSheetId, String namedGraphId) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        export(factSheetId, namedGraphId).save(out);
        return out.toByteArray();
    }

    /** Import a portable {@code .kgraph} file, replacing the selected fact-sheet scope. */
    public ImportSummary importFromFile(Path file, Long factSheetId) throws IOException {
        return importGraph(UnifiedGraph.load(file), factSheetId);
    }

    /** Import portable {@code .kgraph} bytes, replacing the selected fact-sheet scope. */
    public ImportSummary importBytes(byte[] bytes, Long factSheetId) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("A non-empty .kgraph payload is required");
        }
        return importGraph(UnifiedGraph.load(new ByteArrayInputStream(bytes)), factSheetId);
    }

    /**
     * Restore a unified graph into the live store. Domain-owned artifacts are restored through
     * extension points so this module does not depend on process or agent implementations.
     */
    public ImportSummary importGraph(UnifiedGraph graph, Long requestedFactSheetId) {
        if (graph == null) {
            throw new IllegalArgumentException("graph is required");
        }
        Long factSheetId = requestedFactSheetId != null ? requestedFactSheetId : graph.factSheetId();
        if (knowledgeGraphService == null) {
            throw new IllegalStateException("KnowledgeGraphService is not available");
        }

        validateImportGraph(graph);
        UnifiedGraph backup = factSheetId == null ? null : export(factSheetId);
        try {
            return applyGraph(graph, factSheetId, true);
        } catch (RuntimeException failure) {
            if (backup != null) {
                try {
                    applyGraph(backup, factSheetId, false);
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
    }

    private ImportSummary applyGraph(UnifiedGraph graph, Long factSheetId, boolean publishEvent) {

        if (factSheetId != null) {
            knowledgeGraphService.deleteByFactSheetId(factSheetId);
        }

        List<GraphEntity> entities = new ArrayList<>(graph.entities());
        List<KnowledgeGraphService.NodeSpec> nodeSpecs = new ArrayList<>(entities.size());
        for (GraphEntity entity : entities) {
            Map<String, Object> store = storeMap(entity.attributes().get("kompile.store"));
            Map<String, Object> metadata = storeMap(store.get("metadata"));
            if (metadata.isEmpty()) {
                metadata = new LinkedHashMap<>();
            } else {
                metadata = new LinkedHashMap<>(metadata);
            }
            metadata.remove(KnowledgeGraphService.NODE_RESTORE_STATE_KEY);
            metadata.put(KnowledgeGraphService.NODE_RESTORE_STATE_KEY, new LinkedHashMap<>(store));
            metadata.put("entity_type", entity.type());
            metadata.put("unifiedGraphEntityId", entity.id());
            if (!entity.tags().isEmpty()) metadata.put("tags", List.copyOf(entity.tags()));
            metadata.put("weight", entity.weight());
            metadata.put("confidence", entity.confidence());
            if (entity.timestamp() != null) metadata.put("occurredAt", entity.timestamp().toString());
            String nodeType = stringValue(store.get("nodeType"), null);
            NodeLevel level = parseNodeLevel(nodeType);
            String externalId = stringValue(store.get("externalId"), entity.id());
            String description = stringValue(store.get("description"), null);
            nodeSpecs.add(new KnowledgeGraphService.NodeSpec(
                    level, externalId,
                    entity.label() == null || entity.label().isBlank() ? entity.id() : entity.label(),
                    description, metadata));
        }

        List<GraphNode> createdNodes = knowledgeGraphService.createNodesBatch(nodeSpecs, factSheetId);
        if (createdNodes.size() != entities.size()) {
            throw new IllegalStateException("Unified graph node restore was incomplete: expected "
                    + entities.size() + ", created " + createdNodes.size());
        }
        Map<String, GraphNode> nodeByEntityId = new LinkedHashMap<>();
        for (int i = 0; i < entities.size() && i < createdNodes.size(); i++) {
            GraphNode created = createdNodes.get(i);
            if (created != null) nodeByEntityId.put(entities.get(i).id(), created);
        }

        List<KnowledgeGraphService.EdgeSpec> edgeSpecs = new ArrayList<>();
        for (GraphRelation relation : graph.relations()) {
            GraphNode source = nodeByEntityId.get(relation.sourceId());
            GraphNode target = nodeByEntityId.get(relation.targetId());
            if (source == null || target == null || source.getNodeId() == null || target.getNodeId() == null) {
                continue;
            }
            Map<String, Object> store = storeMap(relation.attributes().get("kompile.store"));
            EdgeType edgeType = parseEdgeType(stringValue(store.get("edgeType"), relation.type()));
            String relationType = stringValue(store.get("relationType"), relation.type());
            String description = stringValue(store.get("description"),
                    relation.stringAttribute("description"));
            String label = stringValue(store.get("label"), relationType);
            edgeSpecs.add(new KnowledgeGraphService.EdgeSpec(
                    source.getNodeId(), target.getNodeId(), edgeType,
                    relation.weight(), description, label,
                    edgeRestoreMetadata(store),
                    parseEdgeProvenance(store.get("provenanceType")), factSheetId));
        }
        int edges = knowledgeGraphService.createEdgesBatch(edgeSpecs);
        if (edges != edgeSpecs.size()) {
            throw new IllegalStateException("Unified graph edge restore was incomplete: expected "
                    + edgeSpecs.size() + ", created " + edges);
        }
        knowledgeGraphService.flushPendingNodes();

        if (factSheetId != null && analysisAssets != null) {
            graph.factSheetId(factSheetId).graphId("factsheet_" + factSheetId);
            analysisAssets.put(factSheetId, graph);
        }

        int appliedEmbeddings = 0;
        if (artifactImporters != null) {
            for (UnifiedGraphArtifactImporter importer : artifactImporters) {
                try {
                    int applied = importer.importArtifacts(factSheetId, graph);
                    if (importer.reportsAppliedEmbeddings()) {
                        appliedEmbeddings += applied;
                    }
                } catch (RuntimeException e) {
                    throw new IllegalStateException("Unified graph artifact restore failed for "
                            + importer.getClass().getName(), e);
                }
            }
        }

        int atoms = factSheetId != null && factStoreProjector != null
                ? factStoreProjector.project(factSheetId) : 0;
        boolean published = false;
        if (publishEvent && eventPublisher != null) {
            eventPublisher.publishEvent(new GraphBuildCompletedEvent(
                    this, "unified-import-" + UUID.randomUUID(),
                    createdNodes.size(), edges, factSheetId, null));
            published = true;
        }
        return new ImportSummary(createdNodes.size(), edges, appliedEmbeddings, atoms, published);
    }

    private static void validateImportGraph(UnifiedGraph graph) {
        java.util.Set<String> entityIds = graph.entities().stream()
                .map(GraphEntity::id).collect(java.util.stream.Collectors.toSet());
        for (GraphRelation relation : graph.relations()) {
            if (!entityIds.contains(relation.sourceId()) || !entityIds.contains(relation.targetId())) {
                throw new IllegalArgumentException("Unified graph relation " + relation.id()
                        + " references a missing endpoint");
            }
        }
    }

    /** Summary used by hybrid graph tools without exposing the asset-store implementation. */
    public UnifiedGraphAnalysisAssetStore.AssetSummary analysisAssetSummary(long factSheetId) {
        return analysisAssets != null
                ? analysisAssets.summary(factSheetId)
                : new UnifiedGraphAnalysisAssetStore.AssetSummary(
                        "factsheet_" + factSheetId, 0, 0, 0, 0, 0, false);
    }

    private static Map<String, Object> nodeStoreState(GraphNode node) {
        Map<String, Object> store = new LinkedHashMap<>();
        store.put("id", node.getId());
        store.put("nodeId", node.getNodeId());
        store.put("nodeType", enumName(node.getNodeType()));
        store.put("externalId", node.getExternalId());
        store.put("title", node.getTitle());
        store.put("description", node.getDescription());
        store.put("contentPreview", node.getContentPreview());
        store.put("parentId", node.getParentId());
        store.put("children", node.getChildren() == null ? List.of()
                : node.getChildren().stream().map(GraphNode::getNodeId).toList());
        store.put("sourceNodeId", node.getSourceNode() == null ? null : node.getSourceNode().getNodeId());
        store.put("metadataJson", node.getMetadataJson());
        store.put("metadata", publicMetadata(node.getMetadata(),
                KnowledgeGraphService.NODE_RESTORE_STATE_KEY));
        store.put("vectorId", node.getVectorId());
        store.put("sourceType", node.getSourceType());
        store.put("pathOrUrl", node.getPathOrUrl());
        store.put("childCount", node.getChildCount());
        store.put("edgeCount", node.getEdgeCount());
        store.put("factSheetId", node.getFactSheetId());
        store.put("confidence", node.getConfidence());
        store.put("namedGraphId", node.getNamedGraphId());
        store.put("stale", node.getStale());
        store.put("staleAt", temporal(node.getStaleAt()));
        store.put("userPinned", node.getUserPinned());
        store.put("validUntil", temporal(node.getValidUntil()));
        store.put("lastVerifiedAt", temporal(node.getLastVerifiedAt()));
        store.put("observedAt", temporal(node.getObservedAt()));
        store.put("occurredAt", temporal(node.getOccurredAt()));
        store.put("kgEmbedding", node.getKgEmbedding() == null ? null
                : node.getKgEmbedding().ravel().toDoubleVector());
        store.put("kgEmbeddingAlgorithm", enumName(node.getKgEmbeddingAlgorithm()));
        store.put("kgEmbeddingVersion", node.getKgEmbeddingVersion());
        store.put("kgEmbeddingUpdatedAt", temporal(node.getKgEmbeddingUpdatedAt()));
        store.put("createdAt", temporal(node.getCreatedAt()));
        store.put("updatedAt", temporal(node.getUpdatedAt()));
        return store;
    }

    private static Map<String, Object> edgeStoreState(GraphEdge edge) {
        Map<String, Object> store = new LinkedHashMap<>();
        store.put("id", edge.getId());
        store.put("edgeId", edge.getEdgeId());
        store.put("sourceNodeId", edge.getSourceNodeId());
        store.put("targetNodeId", edge.getTargetNodeId());
        store.put("edgeType", enumName(edge.getEdgeType()));
        store.put("relationType", edge.getRelationType());
        store.put("weight", edge.getWeight());
        store.put("description", edge.getDescription());
        store.put("label", edge.getLabel());
        store.put("sharedEntitiesJson", edge.getSharedEntitiesJson());
        store.put("similarityScore", edge.getSimilarityScore());
        store.put("bidirectional", edge.getBidirectional());
        store.put("metadataJson", edge.getMetadataJson());
        store.put("metadata", publicMetadata(edge.getMetadata(),
                KnowledgeGraphService.EDGE_RESTORE_STATE_KEY));
        store.put("createdAt", temporal(edge.getCreatedAt()));
        store.put("computedAt", temporal(edge.getComputedAt()));
        store.put("factSheetId", edge.getFactSheetId());
        store.put("confidence", edge.getConfidence());
        store.put("provenance", edge.getProvenance());
        store.put("provenanceType", enumName(edge.getProvenanceType()));
        store.put("kgRelationEmbedding", edge.getKgRelationEmbedding() == null ? null
                : edge.getKgRelationEmbedding().ravel().toDoubleVector());
        store.put("kgEmbeddingAlgorithm", enumName(edge.getKgEmbeddingAlgorithm()));
        store.put("kgEmbeddingVersion", edge.getKgEmbeddingVersion());
        store.put("stale", edge.getStale());
        store.put("staleAt", temporal(edge.getStaleAt()));
        store.put("userPinned", edge.getUserPinned());
        store.put("validUntil", temporal(edge.getValidUntil()));
        store.put("lastVerifiedAt", temporal(edge.getLastVerifiedAt()));
        store.put("observedAt", temporal(edge.getObservedAt()));
        store.put("occurredAt", temporal(edge.getOccurredAt()));
        return store;
    }

    private static Map<String, Object> publicMetadata(Map<String, Object> metadata, String restoreKey) {
        if (metadata == null || metadata.isEmpty()) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>(metadata);
        result.remove(restoreKey);
        return result;
    }

    private static String edgeRestoreMetadata(Map<String, Object> store) {
        Map<String, Object> metadata = storeMap(store.get("metadata"));
        metadata.remove(KnowledgeGraphService.EDGE_RESTORE_STATE_KEY);
        metadata.put(KnowledgeGraphService.EDGE_RESTORE_STATE_KEY, new LinkedHashMap<>(store));

        Map<String, Object> payload = new LinkedHashMap<>();
        copyPresent(store, payload, "confidence", "provenance", "occurredAt", "bidirectional",
                "label", "sharedEntitiesJson", "similarityScore", "description", "provenanceType");
        payload.put("metadata", metadata);
        try {
            return ARCHIVE_MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to encode archived edge state", e);
        }
    }

    private static void copyPresent(Map<String, Object> source, Map<String, Object> target,
                                    String... keys) {
        for (String key : keys) {
            if (source.containsKey(key) && source.get(key) != null) {
                target.put(key, source.get(key));
            }
        }
    }

    private static EdgeProvenance parseEdgeProvenance(Object value) {
        if (value == null) return null;
        try {
            return EdgeProvenance.valueOf(String.valueOf(value).trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static String temporal(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String stableEdgeId(String source, String target, String type) {
        return "edge-" + Integer.toUnsignedString(
                java.util.Objects.hash(source, target, type), 36);
    }

    private List<GraphNode> nodesForScope(Long factSheetId) {
        if (factSheetId == null) {
            return knowledgeGraphService.getAllNodes(Integer.MAX_VALUE);
        }
        List<GraphNode> nodes = new ArrayList<>();
        for (NodeLevel level : NodeLevel.values()) {
            nodes.addAll(knowledgeGraphService.getNodesByTypeInFactSheet(factSheetId, level));
        }
        return nodes;
    }

    private static boolean belongsToNamedGraph(GraphNode node, String namedGraphId) {
        if (node == null || namedGraphId == null) return false;
        if (namedGraphId.equals(node.getNamedGraphId())) return true;
        Map<String, Object> metadata = node.getMetadata();
        if (metadata == null) return false;
        Object value = metadata.get("namedGraphId");
        if (value == null) value = metadata.get("named_graph_id");
        return namedGraphId.equals(String.valueOf(value));
    }

    private Collection<GraphEdge> edgesForScope(Long factSheetId, List<GraphNode> nodes) {
        if (factSheetId != null) {
            return knowledgeGraphService.getEdgesInFactSheet(factSheetId);
        }
        Map<String, GraphEdge> edges = new LinkedHashMap<>();
        for (GraphNode node : nodes) {
            if (node == null || node.getNodeId() == null) continue;
            for (GraphEdge edge : knowledgeGraphService.getEdgesForNode(node.getNodeId())) {
                if (edge == null) continue;
                String key = edge.getEdgeId() != null ? edge.getEdgeId()
                        : String.valueOf(edge.getSourceNodeId()) + "->" + edge.getTargetNodeId();
                edges.putIfAbsent(key, edge);
            }
        }
        return edges.values();
    }

    private static void mergeAnalysisAssets(UnifiedGraph source, UnifiedGraph target) {
        source.vectorLayers().values().forEach(target::putVectorLayer);
        source.entityOpinions().forEach(target::putEntityOpinion);
        source.relationOpinions().forEach(target::putRelationOpinion);
        source.weightMaps().forEach(target::putWeightMap);
        source.artifacts().forEach(target::putArtifact);
        source.meta().forEach((key, value) -> {
            if (!target.meta().containsKey(key)) target.meta(key, value);
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> storeMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return new LinkedHashMap<>();
    }

    private static EdgeType parseEdgeType(Object value) {
        if (value != null) {
            try {
                return EdgeType.valueOf(String.valueOf(value).trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // Interop relations may carry ontology labels rather than structural enum names.
            }
        }
        return EdgeType.USER_DEFINED;
    }

    private static NodeLevel parseNodeLevel(Object value) {
        if (value != null) {
            try {
                return NodeLevel.valueOf(String.valueOf(value).trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // Imported reasoning graphs commonly use domain types rather than storage levels.
            }
        }
        return NodeLevel.ENTITY;
    }

    private static String stringValue(Object value, String fallback) {
        if (value == null) return fallback;
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    private static String firstString(Map<String, Object> attributes, String... keys) {
        for (String key : keys) {
            Object value = attributes.get(key);
            if (value instanceof String text && !text.isBlank()) {
                return text.trim();
            }
        }
        return null;
    }
}
