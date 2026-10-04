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
import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.lifecycle.UnifiedGraphReasoningLifecycle;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.query.GraphQueryEngine;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.kompile.knowledgegraph.reasoning.GraphToFactStoreProjector;
import ai.kompile.knowledgegraph.service.BoundedKnowledgeGraphReader;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

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
    private static final List<String> RESERVED_MANAGED_ARTIFACT_PREFIXES = List.of(
            "embeddings/graph-embedding-sidecar.kge2",
            "reasoning/mebn-theory.v1.json",
            "reasoning/mebn-theory.bin",
            "reasoning/mebn-strengths.json",
            "reasoning/fol-psl-program.bin",
            "reasoning/fol-psl-program.v1.json",
            "reasoning/psl-weights.json",
            "reasoning/graph-psl-weights.v1.json",
            "reasoning/consensus-targets.bin",
            "reasoning/consensus-targets.v1.json",
            "reasoning/traces.json",
            "schema/bound-ontology.json",
            "process/",
            "trace:process:");
    /** Candidate ids one query name or phrase may contribute to a bounded neighborhood. */
    private static final int MAX_SEED_CANDIDATES = 5;

    private final KnowledgeGraphService knowledgeGraphService;
    private final ConcurrentHashMap<Long, ReentrantLock> importLocks = new ConcurrentHashMap<>();

    @Autowired(required = false)
    UnifiedGraphAnalysisAssetStore analysisAssets;

    @Autowired(required = false)
    GraphToFactStoreProjector factStoreProjector;

    @Autowired(required = false)
    ApplicationEventPublisher eventPublisher;

    @Autowired(required = false)
    List<UnifiedGraphArtifactContributor> artifactContributors = List.of();

    @Autowired(required = false)
    List<UnifiedGraphArtifactImporter> artifactImporters = List.of();

    @Autowired(required = false)
    List<UnifiedGraphImportTargetValidator> importTargetValidators = List.of();

    @Autowired(required = false)
    UnifiedGraphImportJournal importJournal;

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

    /** One source archive already mapped to its runtime destination. */
    public record ImportScope(UnifiedGraph graph, Long factSheetId) { }

    /** Aggregate result for an all-scope compensating import. */
    public record BatchImportSummary(String transactionId, List<ImportSummary> summaries) {
        public BatchImportSummary {
            summaries = summaries == null ? List.of() : List.copyOf(summaries);
        }
    }

    private record PreparedRelation(
            String sourceEntityId,
            String targetEntityId,
            EdgeType edgeType,
            String relationType,
            double weight,
            String description,
            String restoreMetadata,
            EdgeProvenance provenance) { }

    private record ImportPlan(
            List<GraphEntity> entities,
            List<KnowledgeGraphService.NodeSpec> nodeSpecs,
            List<PreparedRelation> relations) { }

    private static final class PreparedArtifact {
        private final UnifiedGraphArtifactImporter importer;
        private final UnifiedGraphArtifactImporter.PreparedImport transaction;
        private boolean attempted;

        private PreparedArtifact(UnifiedGraphArtifactImporter importer,
                                 UnifiedGraphArtifactImporter.PreparedImport transaction) {
            this.importer = importer;
            this.transaction = transaction;
        }
    }

    private static final class PreparedScope {
        private final UnifiedGraph graph;
        private final Long factSheetId;
        private final ImportPlan incoming;
        private final UnifiedGraph backup;
        private final ImportPlan backupPlan;
        private final List<PreparedArtifact> artifacts;
        private final UnifiedGraphAnalysisAssetStore.AssetState analysisState;
        private boolean attempted;

        private PreparedScope(UnifiedGraph graph,
                              Long factSheetId,
                              ImportPlan incoming,
                              UnifiedGraph backup,
                              ImportPlan backupPlan,
                              List<PreparedArtifact> artifacts,
                              UnifiedGraphAnalysisAssetStore.AssetState analysisState) {
            this.graph = graph;
            this.factSheetId = factSheetId;
            this.incoming = incoming;
            this.backup = backup;
            this.backupPlan = backupPlan;
            this.artifacts = artifacts;
            this.analysisState = analysisState;
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
        return projectGraph(factSheetId, namedGraphId, true, true, true);
    }

    /**
     * Materialize only a bounded neighborhood for exact-id query operations. This path delegates
     * adjacency reads to the live store and never enumerates the complete fact-sheet graph.
     * Stored entity/relation opinions for the materialized ids, and the reasoning-stale flag, are
     * merged by keyed lookup; vector layers, weight maps and model artifacts are excluded, so
     * global/vector/model operations continue to use the full export path.
     */
    public UnifiedGraph exportNeighborhood(
            Long factSheetId, Collection<String> seedIds, int maxDepth, int maxNodes) {
        int edgeLimit = Math.max(1, Math.min(500_000, Math.multiplyExact(
                Math.min(100_000, Math.max(1, maxNodes)), 10)));
        return exportNeighborhood(factSheetId, seedIds, maxDepth, maxNodes,
                GraphQueryEngine.Direction.BOTH, edgeLimit);
    }

    /**
     * Direction-aware bounded materialization used by the query facade. Both node and edge budgets
     * are hard heap bounds; a truncated graph is marked in metadata so absence is never presented as
     * complete evidence.
     */
    public UnifiedGraph exportNeighborhood(
            Long factSheetId,
            Collection<String> seedIds,
            int maxDepth,
            int maxNodes,
            GraphQueryEngine.Direction direction,
            int maxEdges) {
        return exportNeighborhood(factSheetId, seedIds, seedIds, maxDepth, maxNodes, direction, maxEdges);
    }

    /**
     * Bounded materialization with separate point-loaded seeds and traversal roots. Claim/path
     * targets can therefore be resolved without spending the source traversal budget on unrelated
     * target adjacency.
     */
    public UnifiedGraph exportNeighborhood(
            Long factSheetId,
            Collection<String> seedIds,
            Collection<String> expansionSeedIds,
            int maxDepth,
            int maxNodes,
            GraphQueryEngine.Direction direction,
            int maxEdges) {
        int depth = Math.max(0, Math.min(12, maxDepth));
        int nodeLimit = Math.max(1, Math.min(100_000, maxNodes));
        int edgeLimit = Math.max(1, Math.min(500_000, maxEdges));
        GraphQueryEngine.Direction traversalDirection = direction == null
                ? GraphQueryEngine.Direction.BOTH : direction;
        UnifiedGraph graph = new UnifiedGraph()
                .graphId(factSheetId == null ? "global:bounded" : "factsheet_" + factSheetId + ":bounded")
                .factSheetId(factSheetId)
                .meta("boundedNeighborhood", true)
                .meta("maxDepth", depth)
                .meta("maxNodes", nodeLimit)
                .meta("maxEdges", edgeLimit)
                .meta("direction", traversalDirection.name());
        if (knowledgeGraphService == null || seedIds == null || seedIds.isEmpty()) return graph;

        BoundedKnowledgeGraphReader.Neighborhood neighborhood = boundedReader().getNeighborhood(
                factSheetId, seedIds, expansionSeedIds, depth, nodeLimit,
                boundedDirection(traversalDirection), edgeLimit);
        for (GraphNode node : neighborhood.nodes()) addBoundedNode(graph, node);
        for (GraphEdge edge : neighborhood.edges()) addBoundedEdge(graph, edge);
        mergeStoredOpinions(factSheetId, graph);
        graph.meta("truncated", neighborhood.truncated());
        graph.meta("materializedNodes", graph.entityCount());
        graph.meta("materializedEdges", graph.relationCount());
        return graph;
    }

    /**
     * Read only the named portable artifacts of one fact sheet, without enumerating its nodes or
     * edges. Stored analysis-asset artifacts are read first. A contributor runs only when it is an
     * {@link UnifiedGraphArtifactImporter} whose managed prefixes overlap a requested prefix, and
     * what it contributes overrides the stored copy, the same precedence as {@link #export(Long)}.
     * Contributors that declare no prefixes are skipped: nothing short of a full export shows
     * whether they own a requested artifact.
     *
     * @param factSheetId fact sheet to read, or {@code null} for the unscoped graph
     * @param prefixes artifact names or name prefixes to return
     * @return the matching artifacts keyed by name, in name order
     */
    public Map<String, byte[]> exportArtifacts(Long factSheetId, Collection<String> prefixes) {
        List<String> requested = prefixes == null ? List.of() : prefixes.stream()
                .filter(prefix -> prefix != null && !prefix.isBlank())
                .distinct()
                .toList();
        if (requested.isEmpty()) return Map.of();
        Map<String, byte[]> result = new TreeMap<>();
        if (factSheetId != null && analysisAssets != null) {
            analysisAssets.get(factSheetId).ifPresent(stored -> stored.artifacts().forEach((name, data) -> {
                if (startsWithAny(name, requested)) result.put(name, data);
            }));
        }
        for (UnifiedGraphArtifactContributor contributor
                : artifactContributors == null ? List.<UnifiedGraphArtifactContributor>of() : artifactContributors) {
            if (!(contributor instanceof UnifiedGraphArtifactImporter importer)
                    || !managesAnyOf(importer, requested)) continue;
            UnifiedGraph scratch = new UnifiedGraph()
                    .graphId(factSheetId == null ? "global" : "factsheet_" + factSheetId)
                    .factSheetId(factSheetId);
            try {
                contributor.contribute(factSheetId, scratch);
            } catch (RuntimeException e) {
                throw new IllegalStateException("Unified graph artifact contribution failed for "
                        + contributor.getClass().getName(), e);
            }
            scratch.artifacts().forEach((name, data) -> {
                if (startsWithAny(name, requested)) result.put(name, data);
            });
        }
        return Collections.unmodifiableMap(result);
    }

    private static boolean managesAnyOf(UnifiedGraphArtifactImporter importer, List<String> requested) {
        Set<String> managed = importer.managedArtifactPrefixes();
        if (managed == null) return false;
        for (String prefix : managed) {
            if (prefix == null || prefix.isBlank()) continue;
            for (String wanted : requested) {
                if (wanted.startsWith(prefix) || prefix.startsWith(wanted)) return true;
            }
        }
        return false;
    }

    private static boolean startsWithAny(String name, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * Resolve one query input to the stable node ids that seed {@link #exportNeighborhood}. An id
     * that exists in scope is kept as is, without a search. Any other name or phrase costs one
     * search, in the fact sheet or globally when {@code factSheetId} is null (the same scope as the
     * exact-id lookup), and contributes at most {@code MAX_SEED_CANDIDATES} candidate ids; the query
     * engine ranks and traces the final resolution over the materialized neighborhood. Blank input
     * resolves to nothing.
     */
    public List<String> resolveSeedIds(Long factSheetId, String input) {
        if (knowledgeGraphService == null || input == null || input.isBlank()) return List.of();
        String text = input.trim();
        if (nodeInScope(text, factSheetId).isPresent()) return List.of(text);
        List<GraphNode> hits = factSheetId == null
                ? knowledgeGraphService.searchNodes(text, null, MAX_SEED_CANDIDATES)
                : knowledgeGraphService.searchNodesInFactSheet(factSheetId, text, MAX_SEED_CANDIDATES);
        Set<String> ids = new LinkedHashSet<>();
        for (GraphNode hit : hits) {
            if (ids.size() == MAX_SEED_CANDIDATES) break;
            if (hit != null && hit.getNodeId() != null && !hit.getNodeId().isBlank()) ids.add(hit.getNodeId());
        }
        return List.copyOf(ids);
    }

    /**
     * Copy the stored opinions for the ids this bounded graph materialized, plus the reasoning-stale
     * flag that tells the query engine whether to trust them. The stored graph is only read.
     */
    private void mergeStoredOpinions(Long factSheetId, UnifiedGraph graph) {
        if (factSheetId == null || analysisAssets == null) return;
        Optional<UnifiedGraph> stored = analysisAssets.get(factSheetId);
        if (stored.isEmpty()) return;
        UnifiedGraph assets = stored.get();
        for (GraphEntity entity : graph.entities()) {
            Opinion opinion = assets.entityOpinion(entity.id());
            if (opinion != null) graph.putEntityOpinion(entity.id(), opinion);
        }
        for (GraphRelation relation : graph.relations()) {
            Opinion opinion = assets.relationOpinion(relation.id());
            if (opinion != null) graph.putRelationOpinion(relation.id(), opinion);
        }
        Object stale = assets.meta().get(UnifiedGraphReasoningLifecycle.REASONING_STALE_META);
        if (stale != null) graph.meta(UnifiedGraphReasoningLifecycle.REASONING_STALE_META, stale);
    }

    /**
     * Publish the last derivation's MAP posteriors for this fact sheet's edges into the analysis
     * asset store as relation opinions, keyed by the relation ids every export gives those edges.
     * Same rule as the reasoning lifecycle: an opinion is overwritten only where derivation produced
     * a posterior, and every other stored asset (entities, relations, embeddings, opinions, weight
     * maps, artifacts, metadata) carries over. The stored graph is replaced by a copy rather than
     * mutated, because readers hold it without locks, and the swap runs under the fact sheet's import
     * lock so it cannot interleave with an import.
     *
     * @return the number of relation opinions published; 0 leaves the store untouched
     */
    public int publishLearnedRelationOpinions(long factSheetId) {
        if (knowledgeGraphService == null || analysisAssets == null || factStoreProjector == null) return 0;
        List<ReentrantLock> locks = acquireImportLocks(Set.of(factSheetId));
        try {
            Map<String, Opinion> learned = new LinkedHashMap<>();
            for (GraphEdge edge : knowledgeGraphService.getEdgesInFactSheet(factSheetId)) {
                if (edge == null) continue;
                String source = sourceId(edge);
                String target = targetId(edge);
                if (source == null || target == null) continue;
                OptionalDouble posterior = factStoreProjector.learnedPosterior(factSheetId, edge);
                if (posterior.isEmpty() || !Double.isFinite(posterior.getAsDouble())) continue;
                learned.put(relationId(edge, source, target),
                        Opinion.fromSoftTruth(Math.max(0.0, Math.min(1.0, posterior.getAsDouble()))));
            }
            if (learned.isEmpty()) return 0;
            UnifiedGraph published = analysisAssets.snapshot(factSheetId)
                    .map(previous -> {
                        UnifiedGraph copy = UnifiedGraph.of(previous);
                        mergeAnalysisAssets(previous, copy);
                        return copy;
                    })
                    .orElseGet(UnifiedGraph::new);
            published.graphId("factsheet_" + factSheetId).factSheetId(factSheetId);
            learned.forEach(published::putRelationOpinion);
            published.meta(UnifiedGraphReasoningLifecycle.REASONING_STALE_META, false);
            analysisAssets.put(factSheetId, published);
            return learned.size();
        } finally {
            releaseImportLocks(locks);
        }
    }

    private Optional<GraphNode> nodeInScope(String nodeId, Long factSheetId) {
        return boundedReader().getNodeInScope(nodeId, factSheetId);
    }

    /** The store's bounded reads, or point reads over its compatibility API when it has none. */
    private BoundedKnowledgeGraphReader boundedReader() {
        return BoundedKnowledgeGraphReader.of(knowledgeGraphService);
    }

    private static BoundedKnowledgeGraphReader.Direction boundedDirection(
            GraphQueryEngine.Direction direction) {
        return switch (direction) {
            case OUTGOING -> BoundedKnowledgeGraphReader.Direction.OUTGOING;
            case INCOMING -> BoundedKnowledgeGraphReader.Direction.INCOMING;
            case BOTH -> BoundedKnowledgeGraphReader.Direction.BOTH;
        };
    }

    private static String sourceId(GraphEdge edge) {
        return edge.getSourceNodeId() != null ? edge.getSourceNodeId()
                : edge.getSourceNode() == null ? null : edge.getSourceNode().getNodeId();
    }

    private static String targetId(GraphEdge edge) {
        return edge.getTargetNodeId() != null ? edge.getTargetNodeId()
                : edge.getTargetNode() == null ? null : edge.getTargetNode().getNodeId();
    }

    private void addBoundedNode(UnifiedGraph graph, GraphNode node) {
        if (node == null || node.getNodeId() == null || graph.entity(node.getNodeId()).isPresent()) return;
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("kompile.store", nodeStoreState(node));
        String type = firstString(node.getMetadata(), "entity_type", "entityType", "type");
        if (type == null || type.isBlank()) {
            type = node.getNodeType() != null ? node.getNodeType().name() : "ENTITY";
        }
        // Same type memberships as the full export, so a bounded read loses no structural type.
        if (node.getNodeType() != null && !node.getNodeType().name().equalsIgnoreCase(type)) {
            attributes.put("additionalTypes", List.of(node.getNodeType().name()));
        }
        var builder = GraphEntity.builder(node.getNodeId())
                .type(type)
                .label(node.getTitle() != null ? node.getTitle() : node.getNodeId())
                .confidence(node.getConfidence() != null ? node.getConfidence() : 1.0)
                .attributes(attributes);
        if (node.getOccurredAt() != null) builder.timestamp(node.getOccurredAt().toInstant(ZoneOffset.UTC));
        if (node.getKgEmbedding() != null && node.getKgEmbedding().length() > 0) {
            builder.embedding(node.getKgEmbedding().ravel().toDoubleVector());
        }
        graph.addEntity(builder.build());
    }

    private void addBoundedEdge(UnifiedGraph graph, GraphEdge edge) {
        String source = edge.getSourceNodeId();
        String target = edge.getTargetNodeId();
        if (source == null && edge.getSourceNode() != null) source = edge.getSourceNode().getNodeId();
        if (target == null && edge.getTargetNode() != null) target = edge.getTargetNode().getNodeId();
        if (source == null || target == null
                || graph.entity(source).isEmpty() || graph.entity(target).isEmpty()) return;
        String type = relationType(edge);
        double weight = edge.getWeight() != null ? edge.getWeight()
                : edge.getConfidence() != null ? edge.getConfidence() : 1.0;
        var builder = GraphRelation.builder(relationId(edge, source, target), source, target)
                .type(type)
                .weight(weight)
                .confidence(edge.getConfidence() != null ? edge.getConfidence() : weight)
                .directed(!Boolean.TRUE.equals(edge.getBidirectional()))
                .attributes(Map.of("kompile.store", edgeStoreState(edge)));
        if (edge.getOccurredAt() != null) builder.timestamp(edge.getOccurredAt().toInstant(ZoneOffset.UTC));
        if (edge.getKgRelationEmbedding() != null && edge.getKgRelationEmbedding().length() > 0) {
            builder.embedding(edge.getKgRelationEmbedding().ravel().toDoubleVector());
        }
        graph.addRelation(builder.build());
    }

    private UnifiedGraph projectGraph(Long factSheetId,
                                      String namedGraphId,
                                      boolean includeAnalysisAssets,
                                      boolean includeContributedArtifacts,
                                      boolean populateAnalysisCache) {
        UnifiedGraph graph = new UnifiedGraph()
                .graphId(factSheetId == null ? "global" : "factsheet_" + factSheetId)
                .factSheetId(factSheetId);
        if (namedGraphId != null) graph.meta("namedGraphSubset", true);
        if (knowledgeGraphService == null) {
            return graph;
        }

        Optional<UnifiedGraph> previous = Optional.empty();
        if (includeAnalysisAssets && factSheetId != null && analysisAssets != null) {
            previous = populateAnalysisCache
                    ? analysisAssets.get(factSheetId)
                    : analysisAssets.snapshot(factSheetId);
        }

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
            if (node.getNodeType() != null && !node.getNodeType().name().equalsIgnoreCase(type)) {
                attributes.put("additionalTypes", List.of(node.getNodeType().name()));
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
            String type = relationType(edge);
            double weight = edge.getWeight() != null ? edge.getWeight()
                    : (edge.getConfidence() != null ? edge.getConfidence() : 1.0);
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("kompile.store", edgeStoreState(edge));

            var relationBuilder = GraphRelation.builder(relationId(edge, source, target), source, target)
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

        if (includeAnalysisAssets) {
            previous.ifPresent(asset -> mergeAnalysisAssets(asset, graph));
        }
        if (includeContributedArtifacts && artifactContributors != null) {
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
        BatchImportSummary batch = importGraphs(List.of(new ImportScope(graph, requestedFactSheetId)));
        return batch.summaries().get(0);
    }

    public boolean hasDurableImportJournal() {
        return importJournal != null && importJournal.isEnabled();
    }

    /**
     * Prepare every scope before mutation, apply them as one compensation unit, then publish events.
     * This is an explicit saga across heterogeneous stores, not a distributed ACID transaction.
     */
    public BatchImportSummary importGraphs(List<ImportScope> requestedScopes) {
        if (knowledgeGraphService == null) {
            throw new IllegalStateException("KnowledgeGraphService is not available");
        }
        if (requestedScopes == null || requestedScopes.isEmpty()) {
            throw new IllegalArgumentException("At least one unified graph scope is required");
        }

        List<ImportScope> scopes = new ArrayList<>(requestedScopes.size());
        Set<Long> destinationIds = new HashSet<>();
        for (ImportScope requested : requestedScopes) {
            if (requested == null || requested.graph() == null) {
                throw new IllegalArgumentException("graph is required");
            }
            Long factSheetId = requested.factSheetId() != null
                    ? requested.factSheetId() : requested.graph().factSheetId();
            if (factSheetId == null) {
                throw new IllegalArgumentException(
                        "Global unified graph replacement is not supported until atomic global rollback is available");
            }
            if (!destinationIds.add(factSheetId)) {
                throw new IllegalArgumentException("Duplicate unified graph destination: " + factSheetId);
            }
            scopes.add(new ImportScope(requested.graph(), factSheetId));
        }

        List<ReentrantLock> locks = acquireImportLocks(destinationIds);
        String transactionId = "unified-import-" + UUID.randomUUID();
        List<PreparedScope> prepared = new ArrayList<>(scopes.size());
        List<ImportSummary> applied = new ArrayList<>(scopes.size());
        boolean journalStarted = false;
        try {
            if (importJournal != null) importJournal.assertAvailable(destinationIds);
            for (ImportScope scope : scopes) prepared.add(prepareScope(scope));
            if (importJournal != null) {
                importJournal.start(transactionId, prepared.stream()
                        .map(scope -> new UnifiedGraphImportJournal.ScopeArchive(
                                scope.factSheetId, scope.graph, scope.backup))
                        .toList());
                journalStarted = true;
                importJournal.markApplying(transactionId);
            }
            for (PreparedScope scope : prepared) {
                scope.attempted = true;
                applied.add(applyGraph(scope.graph, scope.incoming, scope.factSheetId,
                        false, true, scope.artifacts));
            }

            if (journalStarted) {
                importJournal.complete(transactionId, UnifiedGraphImportJournal.Phase.COMMITTED);
            }

            List<ImportSummary> committed = new ArrayList<>(applied.size());
            for (int i = 0; i < applied.size(); i++) {
                ImportSummary summary = applied.get(i);
                boolean published = publishImportEvent(transactionId, prepared.get(i), summary);
                committed.add(new ImportSummary(summary.nodes(), summary.edges(), summary.embeddings(),
                        summary.atoms(), published));
            }
            return new BatchImportSummary(transactionId, committed);
        } catch (RuntimeException failure) {
            int suppressedBeforeRollback = failure.getSuppressed().length;
            for (int i = prepared.size() - 1; i >= 0; i--) {
                PreparedScope scope = prepared.get(i);
                if (scope.attempted) rollbackScope(scope, failure);
            }
            if (journalStarted) {
                boolean recoveryRequired = failure.getSuppressed().length > suppressedBeforeRollback;
                try {
                    if (recoveryRequired) {
                        importJournal.markRecoveryRequired(transactionId, failure);
                    } else {
                        importJournal.complete(transactionId, UnifiedGraphImportJournal.Phase.ROLLED_BACK);
                    }
                } catch (RuntimeException journalFailure) {
                    failure.addSuppressed(journalFailure);
                    try {
                        importJournal.markRecoveryRequired(transactionId, journalFailure);
                    } catch (RuntimeException recoveryFailure) {
                        failure.addSuppressed(recoveryFailure);
                    }
                }
            }
            throw failure;
        } finally {
            releaseImportLocks(locks);
        }
    }

    /** Replay durable compensating backups for an interrupted import. */
    public BatchImportSummary recoverImport(String transactionId) {
        if (importJournal == null || !importJournal.isEnabled()) {
            throw new IllegalStateException("Graph import recovery journal is not available");
        }
        List<UnifiedGraphImportJournal.ScopeArchive> recovery =
                importJournal.beginRecovery(transactionId);
        try {
            BatchImportSummary restored = importGraphs(recovery.stream()
                    .map(scope -> new ImportScope(scope.backup(), scope.factSheetId()))
                    .toList());
            importJournal.complete(transactionId, UnifiedGraphImportJournal.Phase.ROLLED_BACK);
            return restored;
        } catch (RuntimeException failure) {
            try {
                importJournal.recoveryFailed(transactionId);
            } catch (RuntimeException journalFailure) {
                failure.addSuppressed(journalFailure);
            }
            throw failure;
        }
    }

    private PreparedScope prepareScope(ImportScope scope) {
        ImportPlan incoming = prepareImportPlan(scope.graph());
        validateImportTarget(scope.factSheetId(), scope.graph());
        validateImportArtifacts(scope.factSheetId(), scope.graph());
        UnifiedGraphAnalysisAssetStore.AssetState analysisState =
                UnifiedGraphAnalysisAssetStore.AssetState.absent();
        if (analysisAssets != null) {
            try {
                analysisState = analysisAssets.captureStrict(scope.factSheetId());
            } catch (IOException e) {
                throw new IllegalArgumentException("Could not capture existing unified analysis assets", e);
            }
        }
        UnifiedGraph backup = projectGraph(scope.factSheetId(), null, true, true, false);
        ImportPlan backupPlan = prepareImportPlan(backup);
        List<PreparedArtifact> artifacts =
                prepareArtifactImports(scope.factSheetId(), scope.graph(), backup);
        return new PreparedScope(scope.graph(), scope.factSheetId(), incoming, backup,
                backupPlan, artifacts, analysisState);
    }

    private void rollbackScope(PreparedScope scope, RuntimeException failure) {
        try {
            applyGraph(scope.backup, scope.backupPlan, scope.factSheetId, false, false, List.of());
        } catch (RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
        rollbackArtifacts(scope.artifacts, failure);
        if (analysisAssets != null) {
            try {
                analysisAssets.restoreStrict(scope.factSheetId, scope.analysisState);
            } catch (IOException | RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
        }
    }

    private boolean publishImportEvent(String transactionId, PreparedScope scope, ImportSummary summary) {
        if (eventPublisher == null) return false;
        try {
            eventPublisher.publishEvent(new GraphBuildCompletedEvent(
                    this, transactionId + "-factsheet-" + scope.factSheetId,
                    summary.nodes(), summary.edges(), scope.factSheetId, null));
            return true;
        } catch (RuntimeException eventFailure) {
            log.warn("Unified graph import committed but event publication failed for factSheet={}: {}",
                    scope.factSheetId, eventFailure.getMessage(), eventFailure);
            return false;
        }
    }

    private ImportSummary applyGraph(UnifiedGraph graph,
                                     ImportPlan plan,
                                     Long factSheetId,
                                     boolean publishEvent,
                                     boolean restorePortableAssets,
                                     List<PreparedArtifact> preparedArtifacts) {

        if (factSheetId != null) {
            knowledgeGraphService.deleteByFactSheetId(factSheetId);
        }

        List<GraphNode> createdNodes = knowledgeGraphService.createNodesBatch(plan.nodeSpecs(), factSheetId);
        if (createdNodes == null || createdNodes.size() != plan.entities().size()) {
            throw new IllegalStateException("Unified graph node restore was incomplete: expected "
                    + plan.entities().size() + ", created "
                    + (createdNodes == null ? 0 : createdNodes.size()));
        }
        Map<String, GraphNode> nodeByEntityId = new LinkedHashMap<>();
        for (int i = 0; i < plan.entities().size(); i++) {
            GraphNode created = createdNodes.get(i);
            if (created == null || created.getNodeId() == null || created.getNodeId().isBlank()) {
                throw new IllegalStateException("Unified graph node restore returned an unusable node at index " + i);
            }
            nodeByEntityId.put(plan.entities().get(i).id(), created);
        }

        List<KnowledgeGraphService.EdgeSpec> edgeSpecs = new ArrayList<>(plan.relations().size());
        for (PreparedRelation relation : plan.relations()) {
            GraphNode source = nodeByEntityId.get(relation.sourceEntityId());
            GraphNode target = nodeByEntityId.get(relation.targetEntityId());
            edgeSpecs.add(new KnowledgeGraphService.EdgeSpec(
                    source.getNodeId(), target.getNodeId(), relation.edgeType(),
                    relation.weight(), relation.description(), relation.relationType(),
                    relation.restoreMetadata(), relation.provenance(), factSheetId));
        }
        int edges = knowledgeGraphService.createEdgesBatch(edgeSpecs);
        if (edges != edgeSpecs.size()) {
            throw new IllegalStateException("Unified graph edge restore was incomplete: expected "
                    + edgeSpecs.size() + ", created " + edges);
        }
        knowledgeGraphService.flushPendingNodes();

        int appliedEmbeddings = 0;
        if (restorePortableAssets) {
            for (PreparedArtifact prepared : preparedArtifacts) {
                try {
                    prepared.attempted = true;
                    int applied = prepared.transaction.commit();
                    if (prepared.importer.reportsAppliedEmbeddings()) {
                        appliedEmbeddings += applied;
                    }
                } catch (RuntimeException e) {
                    throw new IllegalStateException("Unified graph artifact restore failed for "
                            + prepared.importer.getClass().getName(), e);
                }
            }
        }

        if (restorePortableAssets && factSheetId != null && analysisAssets != null) {
            graph.factSheetId(factSheetId).graphId("factsheet_" + factSheetId);
            try {
                analysisAssets.replaceStrict(factSheetId, graph);
            } catch (IOException e) {
                throw new IllegalStateException("Could not persist unified analysis assets", e);
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

    private ImportPlan prepareImportPlan(UnifiedGraph graph) {
        java.util.Set<String> entityIds = graph.entities().stream()
                .map(GraphEntity::id).collect(java.util.stream.Collectors.toSet());
        List<GraphEntity> entities = new ArrayList<>(graph.entities());
        if (entityIds.size() != entities.size()) {
            throw new IllegalArgumentException("Unified graph contains duplicate entity IDs");
        }

        List<KnowledgeGraphService.NodeSpec> nodeSpecs = new ArrayList<>(entities.size());
        for (GraphEntity entity : entities) {
            Map<String, Object> store = storeMap(entity.attributes().get("kompile.store"));
            Map<String, Object> metadata = storeMap(store.get("metadata"));
            metadata = metadata.isEmpty() ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
            metadata.remove(KnowledgeGraphService.NODE_RESTORE_STATE_KEY);
            metadata.put(KnowledgeGraphService.NODE_RESTORE_STATE_KEY, new LinkedHashMap<>(store));
            metadata.put("entity_type", entity.type());
            metadata.put("unifiedGraphEntityId", entity.id());
            if (!entity.tags().isEmpty()) metadata.put("tags", List.copyOf(entity.tags()));
            metadata.put("weight", entity.weight());
            metadata.put("confidence", entity.confidence());
            if (entity.timestamp() != null) metadata.put("occurredAt", entity.timestamp().toString());
            NodeLevel level = parseNodeLevel(stringValue(store.get("nodeType"), null));
            String externalId = stringValue(store.get("externalId"), entity.id());
            String description = stringValue(store.get("description"), null);
            nodeSpecs.add(new KnowledgeGraphService.NodeSpec(
                    level, externalId,
                    entity.label() == null || entity.label().isBlank() ? entity.id() : entity.label(),
                    description, metadata));
        }

        List<PreparedRelation> relations = new ArrayList<>();
        for (GraphRelation relation : graph.relations()) {
            if (!entityIds.contains(relation.sourceId()) || !entityIds.contains(relation.targetId())) {
                throw new IllegalArgumentException("Unified graph relation " + relation.id()
                        + " references a missing endpoint");
            }
            Map<String, Object> store = storeMap(relation.attributes().get("kompile.store"));
            EdgeType edgeType = parseEdgeType(stringValue(store.get("edgeType"), relation.type()));
            String relationType = stringValue(store.get("relationType"), relation.type());
            String description = stringValue(store.get("description"),
                    relation.stringAttribute("description"));
            relations.add(new PreparedRelation(
                    relation.sourceId(), relation.targetId(), edgeType, relationType,
                    relation.weight(), description, edgeRestoreMetadata(store),
                    parseEdgeProvenance(store.get("provenanceType"))));
        }
        return new ImportPlan(List.copyOf(entities), List.copyOf(nodeSpecs), List.copyOf(relations));
    }

    private void validateImportTarget(Long factSheetId, UnifiedGraph graph) {
        if (importTargetValidators == null) return;
        for (UnifiedGraphImportTargetValidator validator : importTargetValidators) {
            validator.validateTarget(factSheetId, graph);
        }
    }

    private void validateImportArtifacts(Long factSheetId, UnifiedGraph graph) {
        List<UnifiedGraphArtifactImporter> importers = orderedArtifactImporters();
        validateManagedArtifactOwnership(graph, importers);
        for (UnifiedGraphArtifactImporter importer : importers) {
            try {
                importer.validateArtifacts(factSheetId, graph);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Unified graph artifact preflight failed for "
                        + importer.getClass().getName(), e);
            }
        }
    }

    private static void validateManagedArtifactOwnership(
            UnifiedGraph graph, List<UnifiedGraphArtifactImporter> importers) {
        if (graph == null || graph.artifacts().isEmpty()) return;
        List<String> claimedPrefixes = importers.stream()
                .flatMap(importer -> Optional.ofNullable(importer.managedArtifactPrefixes())
                        .orElse(Set.of()).stream())
                .filter(prefix -> prefix != null && !prefix.isBlank())
                .toList();
        for (String artifact : graph.artifacts().keySet()) {
            boolean reserved = RESERVED_MANAGED_ARTIFACT_PREFIXES.stream()
                    .anyMatch(artifact::startsWith);
            boolean claimed = claimedPrefixes.stream().anyMatch(artifact::startsWith);
            if (reserved && !claimed) {
                throw new IllegalArgumentException(
                        "No managed importer claims reserved .kgraph artifact: " + artifact);
            }
        }
    }

    private List<PreparedArtifact> prepareArtifactImports(
            Long factSheetId, UnifiedGraph incoming, UnifiedGraph previous) {
        List<UnifiedGraphArtifactImporter> importers = orderedArtifactImporters();
        if (importers.isEmpty()) return List.of();
        List<PreparedArtifact> prepared = new ArrayList<>(importers.size());
        for (UnifiedGraphArtifactImporter importer : importers) {
            try {
                if (!importer.supportsExactRollback()) {
                    throw new IllegalStateException("Artifact importer does not support exact rollback");
                }
                UnifiedGraphArtifactImporter.PreparedImport transaction =
                        importer.prepareArtifacts(factSheetId, incoming, previous);
                if (transaction == null) {
                    throw new IllegalStateException("Artifact importer returned a null prepared transaction");
                }
                prepared.add(new PreparedArtifact(importer, transaction));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Unified graph artifact preparation failed for "
                        + importer.getClass().getName(), e);
            }
        }
        return prepared;
    }

    private static void rollbackArtifacts(List<PreparedArtifact> prepared, RuntimeException failure) {
        for (int i = prepared.size() - 1; i >= 0; i--) {
            PreparedArtifact artifact = prepared.get(i);
            if (!artifact.attempted) continue;
            try {
                artifact.transaction.rollback();
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
        }
    }

    private List<UnifiedGraphArtifactImporter> orderedArtifactImporters() {
        if (artifactImporters == null || artifactImporters.isEmpty()) return List.of();
        List<UnifiedGraphArtifactImporter> ordered = artifactImporters.stream()
                .sorted(Comparator.comparingInt(UnifiedGraphArtifactImporter::order)
                        .thenComparing(UnifiedGraphArtifactImporter::participantId))
                .toList();
        Set<String> ids = new HashSet<>();
        for (UnifiedGraphArtifactImporter importer : ordered) {
            String id = importer.participantId();
            if (id == null || id.isBlank() || !ids.add(id)) {
                throw new IllegalArgumentException("Duplicate or blank unified graph import participant: " + id);
            }
        }
        return ordered;
    }

    private List<ReentrantLock> acquireImportLocks(Set<Long> factSheetIds) {
        List<ReentrantLock> locks = factSheetIds.stream().sorted()
                .map(id -> importLocks.computeIfAbsent(id, ignored -> new ReentrantLock()))
                .toList();
        for (ReentrantLock lock : locks) lock.lock();
        return locks;
    }

    private static void releaseImportLocks(List<ReentrantLock> locks) {
        for (int i = locks.size() - 1; i >= 0; i--) locks.get(i).unlock();
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

    /** Semantic relation type when present, else the structural edge type, else empty. */
    private static String relationType(GraphEdge edge) {
        return edge.getRelationType() != null && !edge.getRelationType().isBlank()
                ? edge.getRelationType()
                : edge.getEdgeType() != null ? edge.getEdgeType().name() : "";
    }

    /** The relation id every export gives an edge; published opinions are keyed by it too. */
    private static String relationId(GraphEdge edge, String source, String target) {
        return edge.getEdgeId() != null ? edge.getEdgeId() : stableEdgeId(source, target, relationType(edge));
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
