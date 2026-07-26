/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.crawl.graph.partition;

import ai.kompile.core.graphrag.partition.ChunkCandidate;
import ai.kompile.core.graphrag.partition.DiscoveryChannel;
import ai.kompile.core.graphrag.partition.DiscoveryChannelProvider;
import ai.kompile.core.graphrag.partition.EntityPartition;
import ai.kompile.core.graphrag.partition.PartitionMember;
import ai.kompile.core.graphrag.partition.PartitionSubjects;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Finds evidence by walking out from the subject over the graph it already produced.
 *
 * <p>This is the {@link DiscoveryChannel#STRUCTURED_RELATIONSHIP} channel. It is stronger than
 * embedding search because the link it follows was asserted by an extractor rather than inferred
 * from proximity, and weaker than an identifier match because the further out the walk goes the
 * more the relation is about something adjacent to the subject rather than the subject itself.
 * That gradient is expressed as per-hop decay, not as a cut-off, so the manifest records
 * how far out a chunk was found instead of pretending distance did not exist.</p>
 *
 * <p>The walk widens with the round: round one takes one hop, round two takes two, up to
 * {@code maxHops}. Once the neighbourhood is exhausted every proposal is a repeat of one the
 * partition already holds, those are suppressed via {@link PartitionMember#absorbs}, and the
 * coordinator sees an empty round — which is what {@code frontierExhausted} is supposed to mean.</p>
 *
 * <p>All endpoint resolution is served from a single node index built once per round. Reading
 * nodes edge-by-edge would turn a two-hop walk into one store round-trip per edge, and the graph
 * store is the expensive side of this channel.</p>
 *
 * <p>A partition may be about several subjects — that is the point of grouping — and the walk then
 * starts from all of them at once over one shared index and one shared visited set. That is
 * cheaper than walking each subject separately <em>and</em> more correct: the chunk that two
 * grouped subjects both reach is read once, which is the saving grouping exists to make. Which
 * subject each chunk was reached from is carried along the frontier so the recorded reason still
 * names it. The membership comes from {@link PartitionSubjects}, never from the key alone: a
 * group id names no node in any graph.</p>
 */
public final class GraphNeighbourhoodDiscoveryProvider implements DiscoveryChannelProvider {

    private static final Logger log =
            LoggerFactory.getLogger(GraphNeighbourhoodDiscoveryProvider.class);

    private static final DiscoveryChannel CHANNEL = DiscoveryChannel.STRUCTURED_RELATIONSHIP;

    /** Two hops reaches "the other party to a contract the subject signed" and stops there. */
    public static final int DEFAULT_MAX_HOPS = 2;

    /**
     * Confidence for chunks the subject entity itself was extracted from.
     *
     * <p>High but not certain: the extractor believed this text was about the subject, which is
     * good evidence and not the same thing as an identifier match.</p>
     */
    public static final double DEFAULT_BASE_CONFIDENCE = 0.85;

    /**
     * Per-hop multiplier. With the default base this puts hop one at 0.64 and hop two at 0.48,
     * which straddles the default policy's 0.5 promotion threshold on purpose: a directly related
     * chunk is scheduled, a chunk two relations away is recorded and deferred rather than dropped.
     */
    public static final double DEFAULT_HOP_DECAY = 0.75;

    /**
     * Seeds per subject beyond which the walk does not widen; the overflow is logged rather than
     * silently ignored. A group gets this budget for each of its subjects, so grouping several
     * subjects into one partition never narrows what any one of them would have reached alone.
     */
    private static final int MAX_SEEDS = 25;

    private final KnowledgeGraphService graph;
    private final Function<EntityPartition, Long> factSheetResolver;
    private final Function<EntityPartition, List<String>> subjectResolver;
    private final int maxHops;
    private final double baseConfidence;
    private final double hopDecay;

    public GraphNeighbourhoodDiscoveryProvider(KnowledgeGraphService graph) {
        this(graph, PartitionFactSheets.fromPinOrSnapshot(), PartitionSubjects.fromPinOrKey(),
                DEFAULT_MAX_HOPS, DEFAULT_BASE_CONFIDENCE, DEFAULT_HOP_DECAY);
    }

    public GraphNeighbourhoodDiscoveryProvider(KnowledgeGraphService graph,
                                               Function<EntityPartition, Long> factSheetResolver) {
        this(graph, factSheetResolver, PartitionSubjects.fromPinOrKey(), DEFAULT_MAX_HOPS,
                DEFAULT_BASE_CONFIDENCE, DEFAULT_HOP_DECAY);
    }

    public GraphNeighbourhoodDiscoveryProvider(KnowledgeGraphService graph,
                                               Function<EntityPartition, Long> factSheetResolver,
                                               int maxHops, double baseConfidence,
                                               double hopDecay) {
        this(graph, factSheetResolver, PartitionSubjects.fromPinOrKey(), maxHops, baseConfidence,
                hopDecay);
    }

    public GraphNeighbourhoodDiscoveryProvider(KnowledgeGraphService graph,
                                               Function<EntityPartition, Long> factSheetResolver,
                                               Function<EntityPartition, List<String>> subjects,
                                               int maxHops, double baseConfidence,
                                               double hopDecay) {
        this.graph = Objects.requireNonNull(graph, "a graph channel needs a knowledge graph");
        this.factSheetResolver = factSheetResolver == null
                ? PartitionFactSheets.fromPinOrSnapshot() : factSheetResolver;
        this.subjectResolver = subjects == null ? PartitionSubjects.fromPinOrKey() : subjects;
        this.maxHops = Math.max(1, maxHops);
        this.baseConfidence = clamp(baseConfidence);
        this.hopDecay = Math.max(0.0, Math.min(1.0, hopDecay));
    }

    @Override
    public DiscoveryChannel channel() {
        return CHANNEL;
    }

    @Override
    public List<ChunkCandidate> discover(EntityPartition partition, int round, int limit) {
        if (partition == null) {
            return List.of();
        }
        List<String> subjects = subjectResolver.apply(partition);
        if (subjects == null || subjects.isEmpty()) {
            return List.of();
        }
        Long factSheetId = factSheetResolver.apply(partition);
        if (factSheetId == null) {
            // Refusing to guess: see PartitionFactSheets#resolve. An unscoped walk would attribute
            // another graph's evidence to this partition.
            log.debug("Partition {} names no fact sheet; the structured-relationship channel has "
                    + "nothing to walk", partition.id());
            return List.of();
        }
        int cap = Math.max(1, limit);

        Map<String, GraphNode> byId = indexNodes(factSheetId);
        List<Step> seeds = seedNodes(subjects, factSheetId, byId);
        if (seeds.isEmpty()) {
            log.debug("No subject of partition {} ({}) has a node in fact sheet {}; nothing to "
                    + "walk from", partition.id(), subjects, factSheetId);
            return List.of();
        }

        Map<String, ChunkCandidate> best = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();
        List<Step> frontier = new ArrayList<>();
        for (Step seed : seeds) {
            if (visited.add(seed.node().getNodeId())) {
                frontier.add(seed);
                // Hop zero: the text the subject entity was itself extracted from. The strongest
                // structured evidence there is, and the reason a partition can cite a graph at all.
                offer(best, partition, GraphProvenanceChunks.chunkIdsOf(seed.node()),
                        GraphProvenanceChunks.documentIdOf(seed.node()), baseConfidence,
                        "extracted the subject entity", cap);
            }
        }

        int hops = Math.min(maxHops, Math.max(1, round));
        for (int hop = 1; hop <= hops; hop++) {
            double confidence = confidenceAt(hop);
            List<Step> next = new ArrayList<>();
            for (Step step : frontier) {
                GraphNode node = step.node();
                for (GraphEdge edge : edgesOf(node.getNodeId(), factSheetId)) {
                    if (edge == null || Boolean.TRUE.equals(edge.getStale())) {
                        continue;
                    }
                    // An extractor that was unsure of the relation produces weaker evidence than
                    // one that was certain, and the store already records which it was.
                    double weighted = confidence * edgeWeight(edge);
                    String reason = reasonFor(hop, step.origin(), edge);
                    offer(best, partition, GraphProvenanceChunks.chunkIdsOf(edge),
                            GraphProvenanceChunks.documentIdOf(edge), weighted, reason, cap);

                    String otherId = otherEndpoint(edge, node.getNodeId());
                    if (otherId == null || !visited.add(otherId)) {
                        continue;
                    }
                    GraphNode other = byId.get(otherId);
                    if (other == null || Boolean.TRUE.equals(other.getStale())) {
                        // A hollow or tombstoned endpoint carries no provenance to cite and no
                        // edges worth walking; the edge's own chunks were already offered above.
                        continue;
                    }
                    offer(best, partition, GraphProvenanceChunks.chunkIdsOf(other),
                            GraphProvenanceChunks.documentIdOf(other), weighted, reason, cap);
                    next.add(new Step(other, step.origin()));
                }
            }
            frontier = next;
            if (frontier.isEmpty()) {
                break;
            }
        }

        List<ChunkCandidate> ranked = new ArrayList<>(best.values());
        ranked.sort(Comparator.comparingDouble(ChunkCandidate::confidence).reversed());
        return ranked.size() <= cap ? ranked : new ArrayList<>(ranked.subList(0, cap));
    }

    /** Confidence at a given hop distance; hop zero is the subject's own extraction. */
    double confidenceAt(int hop) {
        return clamp(baseConfidence * Math.pow(hopDecay, Math.max(0, hop)));
    }

    private void offer(Map<String, ChunkCandidate> best, EntityPartition partition,
                       List<String> chunkIds, String documentId, double confidence, String reason,
                       int cap) {
        for (String chunkId : chunkIds) {
            if (chunkId == null || chunkId.isBlank()) {
                continue;
            }
            if (best.size() >= cap && !best.containsKey(chunkId)) {
                continue;
            }
            ChunkCandidate candidate = ChunkCandidate.of(chunkId, CHANNEL, confidence, reason)
                    .inDocument(documentId);
            PartitionMember existing = partition.member(chunkId).orElse(null);
            if (existing != null && existing.absorbs(candidate)) {
                // Already held on these exact terms. Re-proposing it would be counted as an
                // upgrade and the walk would never read as exhausted.
                continue;
            }
            best.merge(chunkId, candidate,
                    (kept, incoming) -> kept.confidence() >= incoming.confidence() ? kept : incoming);
        }
    }

    private Map<String, GraphNode> indexNodes(Long factSheetId) {
        List<GraphNode> nodes = graph.getNodesInFactSheet(factSheetId);
        if (nodes == null || nodes.isEmpty()) {
            return Map.of();
        }
        Map<String, GraphNode> byId = new LinkedHashMap<>(Math.max(16, nodes.size() * 2));
        for (GraphNode node : nodes) {
            if (node != null && node.getNodeId() != null) {
                byId.putIfAbsent(node.getNodeId(), node);
            }
        }
        return byId;
    }

    /**
     * A node in the walk, remembering which subject it was reached from.
     *
     * <p>A group partition walks out from several subjects at once, and the reason recorded against
     * a chunk has to name the one it actually came from. Carrying the origin along the frontier is
     * what keeps "two hops from Acme" true when Beta was also in the same partition.</p>
     */
    private record Step(GraphNode node, String origin) {
    }

    private List<Step> seedNodes(List<String> subjects, Long factSheetId,
                                 Map<String, GraphNode> byId) {
        Map<String, Step> seeds = new LinkedHashMap<>();
        Map<String, String> needles = new LinkedHashMap<>();
        for (String subject : subjects) {
            if (subject == null || subject.isBlank()) {
                continue;
            }
            GraphNode byNodeId = byId.get(subject);
            if (byNodeId != null) {
                seeds.putIfAbsent(byNodeId.getNodeId(), new Step(byNodeId, subject));
            }
            graph.getNodeByExternalIdInFactSheet(subject, NodeLevel.ENTITY, factSheetId)
                    .filter(node -> node.getNodeId() != null)
                    .ifPresent(node -> seeds.putIfAbsent(node.getNodeId(),
                            new Step(node, subject)));
            needles.putIfAbsent(subject.trim().toLowerCase(Locale.ROOT), subject);
        }
        if (needles.isEmpty()) {
            return List.of();
        }

        // One pass over the index for the whole membership: the index is already in memory, so
        // matching aliases over it costs nothing extra and catches the common case of a subject
        // named by title rather than by canonical id — but a group of a dozen subjects must not
        // walk the index a dozen times to do it.
        int seedBudget = MAX_SEEDS * needles.size();
        boolean truncated = false;
        for (GraphNode node : byId.values()) {
            if (seeds.size() >= seedBudget) {
                truncated = true;
                break;
            }
            String origin = originOf(node, needles);
            if (origin != null) {
                seeds.putIfAbsent(node.getNodeId(), new Step(node, origin));
            }
        }
        if (truncated) {
            log.info("Subjects {} matched at least {} nodes in fact sheet {}; the walk starts from "
                    + "the first {} — the rest were not expanded", subjects, seedBudget,
                    factSheetId, seedBudget);
        }
        List<Step> live = new ArrayList<>(seeds.size());
        for (Step seed : seeds.values()) {
            if (!Boolean.TRUE.equals(seed.node().getStale())) {
                live.add(seed);
            }
        }
        return live;
    }

    /** The subject a node answers to, or {@code null} when it answers to none of them. */
    private static String originOf(GraphNode node, Map<String, String> needles) {
        if (node == null || node.getNodeId() == null) {
            return null;
        }
        String external = folded(node.getExternalId());
        String origin = external == null ? null : needles.get(external);
        if (origin != null) {
            return origin;
        }
        String title = folded(node.getTitle());
        return title == null ? null : needles.get(title);
    }

    private static String folded(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private List<GraphEdge> edgesOf(String nodeId, Long factSheetId) {
        List<GraphEdge> edges = graph.getEdgesForNodeInFactSheet(nodeId, factSheetId);
        return edges == null ? List.of() : edges;
    }

    /** The far end of an edge relative to {@code nodeId}, or {@code null} if it has no far end. */
    static String otherEndpoint(GraphEdge edge, String nodeId) {
        String source = edge.getSourceNodeId();
        String target = edge.getTargetNodeId();
        if (nodeId == null) {
            return null;
        }
        if (nodeId.equals(source)) {
            return target;
        }
        if (nodeId.equals(target)) {
            return source;
        }
        // The edge was returned for this node but names neither end as it — an inconsistency in
        // the store, not something to guess our way through.
        return null;
    }

    private static double edgeWeight(GraphEdge edge) {
        Double confidence = edge.getConfidence();
        if (confidence == null || confidence.isNaN() || confidence <= 0.0) {
            return 1.0;
        }
        return Math.min(1.0, confidence);
    }

    private static String reasonFor(int hop, String subject, GraphEdge edge) {
        String relation = edge.getRelationType();
        if (relation == null || relation.isBlank()) {
            relation = edge.getEdgeType() == null ? "related to" : edge.getEdgeType().name();
        }
        return hop + (hop == 1 ? " hop from " : " hops from ") + subject + " via " + relation;
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
