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
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.NodeLevel;
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
        for (GraphNode node : nodes) {
            if (node == null || node.getNodeId() == null) {
                continue;
            }
            Map<String, Object> attributes = new LinkedHashMap<>();
            if (node.getMetadata() != null) {
                node.getMetadata().forEach((key, value) -> {
                    if (key != null && value != null) {
                        attributes.put(key, value);
                    }
                });
            }
            attributes.putIfAbsent("_kompileNodeLevel",
                    node.getNodeType() == null ? NodeLevel.ENTITY.name() : node.getNodeType().name());
            if (node.getExternalId() != null) {
                attributes.putIfAbsent("_kompileExternalId", node.getExternalId());
            }
            if (node.getDescription() != null) {
                attributes.putIfAbsent("description", node.getDescription());
            }
            String type = firstString(attributes, "entity_type", "entityType", "type");
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
            if (edge == null || Boolean.TRUE.equals(edge.getStale())) {
                continue;
            }
            String source = edge.getSourceNode() != null
                    ? edge.getSourceNode().getNodeId() : edge.getSourceNodeId();
            String target = edge.getTargetNode() != null
                    ? edge.getTargetNode().getNodeId() : edge.getTargetNodeId();
            if (source == null || target == null
                    || graph.entity(source).isEmpty() || graph.entity(target).isEmpty()) {
                continue;
            }
            String type = edge.getRelationType() != null && !edge.getRelationType().isBlank()
                    ? edge.getRelationType()
                    : (edge.getEdgeType() != null ? edge.getEdgeType().name() : "");
            double weight = edge.getWeight() != null ? edge.getWeight()
                    : (edge.getConfidence() != null ? edge.getConfidence() : 1.0);
            Map<String, Object> attributes = new LinkedHashMap<>();
            if (edge.getDescription() != null) attributes.put("description", edge.getDescription());
            if (edge.getLabel() != null) attributes.put("label", edge.getLabel());
            if (edge.getEdgeType() != null) attributes.put("edgeType", edge.getEdgeType().name());
            if (edge.getMetadataJson() != null) attributes.put("metadataJson", edge.getMetadataJson());
            if (edge.getProvenance() != null) attributes.put("provenance", edge.getProvenance());

            var relationBuilder = GraphRelation.builder(
                            edge.getEdgeId() != null ? edge.getEdgeId() : UUID.randomUUID().toString(),
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
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        export(factSheetId).save(out);
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

        if (factSheetId != null) {
            knowledgeGraphService.deleteByFactSheetId(factSheetId);
        }

        List<GraphEntity> entities = new ArrayList<>(graph.entities());
        List<KnowledgeGraphService.NodeSpec> nodeSpecs = new ArrayList<>(entities.size());
        for (GraphEntity entity : entities) {
            Map<String, Object> metadata = new LinkedHashMap<>(entity.attributes());
            metadata.put("entity_type", entity.type());
            metadata.put("unifiedGraphEntityId", entity.id());
            if (!entity.tags().isEmpty()) metadata.put("tags", List.copyOf(entity.tags()));
            metadata.put("weight", entity.weight());
            metadata.put("confidence", entity.confidence());
            if (entity.timestamp() != null) metadata.put("occurredAt", entity.timestamp().toString());
            NodeLevel level = parseNodeLevel(metadata.get("_kompileNodeLevel"));
            String externalId = stringValue(metadata.get("_kompileExternalId"), entity.id());
            String description = stringValue(metadata.get("description"), null);
            nodeSpecs.add(new KnowledgeGraphService.NodeSpec(
                    level, externalId,
                    entity.label() == null || entity.label().isBlank() ? entity.id() : entity.label(),
                    description, metadata));
        }

        List<GraphNode> createdNodes = knowledgeGraphService.createNodesBatch(nodeSpecs, factSheetId);
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
            edgeSpecs.add(new KnowledgeGraphService.EdgeSpec(
                    source.getNodeId(), target.getNodeId(), EdgeType.USER_DEFINED,
                    relation.weight(), relation.stringAttribute("description"), relation.type(),
                    null, null, factSheetId));
        }
        int edges = knowledgeGraphService.createEdgesBatch(edgeSpecs);
        knowledgeGraphService.flushPendingNodes();

        if (factSheetId != null && analysisAssets != null) {
            graph.factSheetId(factSheetId).graphId("factsheet_" + factSheetId);
            analysisAssets.put(factSheetId, graph);
        }

        if (artifactImporters != null) {
            for (UnifiedGraphArtifactImporter importer : artifactImporters) {
                try {
                    importer.importArtifacts(factSheetId, graph);
                } catch (RuntimeException e) {
                    throw new IllegalStateException("Unified graph artifact restore failed for "
                            + importer.getClass().getName(), e);
                }
            }
        }

        int atoms = factSheetId != null && factStoreProjector != null
                ? factStoreProjector.project(factSheetId) : 0;
        boolean published = false;
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new GraphBuildCompletedEvent(
                    this, "unified-import-" + UUID.randomUUID(),
                    createdNodes.size(), edges, factSheetId, null));
            published = true;
        }
        return new ImportSummary(createdNodes.size(), edges, embeddingCount(graph), atoms, published);
    }

    /** Summary used by hybrid graph tools without exposing the asset-store implementation. */
    public UnifiedGraphAnalysisAssetStore.AssetSummary analysisAssetSummary(long factSheetId) {
        return analysisAssets != null
                ? analysisAssets.summary(factSheetId)
                : new UnifiedGraphAnalysisAssetStore.AssetSummary(
                        "factsheet_" + factSheetId, 0, 0, 0, 0, 0, false);
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

    private static int embeddingCount(UnifiedGraph graph) {
        int count = 0;
        for (GraphEntity entity : graph.entities()) if (entity.hasEmbedding()) count++;
        for (GraphRelation relation : graph.relations()) if (relation.hasEmbedding()) count++;
        count += graph.vectorLayers().values().stream().mapToInt(layer -> layer.size()).sum();
        return count;
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
