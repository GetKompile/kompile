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

import ai.kompile.core.graphrag.maintenance.GraphMaintenanceService;
import ai.kompile.core.graphrag.maintenance.model.Contradiction;
import ai.kompile.core.graphrag.partition.ChunkCandidate;
import ai.kompile.core.graphrag.partition.DiscoveryChannel;
import ai.kompile.core.graphrag.partition.DiscoveryChannelProvider;
import ai.kompile.core.graphrag.partition.EntityPartition;
import ai.kompile.core.graphrag.partition.PartitionMember;
import ai.kompile.core.graphrag.partition.PartitionSubjects;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Surfaces the chunks behind facts that disagree about the subject.
 *
 * <p>This is the {@link DiscoveryChannel#CONTRADICTION} channel, and it is last on purpose: a
 * contradiction is only interpretable once there is an established prior to contradict. Its job
 * is not to decide which side is right — {@code resolveContradictions} does that — but to make
 * sure both sides' text is <em>in</em> the partition, so whatever reads the partition later can
 * see the conflict instead of inheriting whichever side happened to be retrieved.</p>
 *
 * <p>It runs on the first round only. Detection is a scan of the whole fact sheet, and the
 * conflicts it finds do not change because a later round admitted more chunks; paying for that
 * scan every round would buy nothing and would keep the frontier from ever reading as
 * exhausted.</p>
 *
 * <p>A grouped partition is about several subjects, which it reads from {@link PartitionSubjects}
 * rather than from its key, and a conflict touching any one of them is admitted. That is the case
 * grouping is <em>for</em>: two subjects that disagree about the same fact are most useful in the
 * same partition, where the disagreement is visible.</p>
 */
public final class ContradictionDiscoveryProvider implements DiscoveryChannelProvider {

    private static final Logger log = LoggerFactory.getLogger(ContradictionDiscoveryProvider.class);

    private static final DiscoveryChannel CHANNEL = DiscoveryChannel.CONTRADICTION;

    /**
     * Confidence used when the detector reports no severity.
     *
     * <p>Above the default policy's exclusion floor and just above its promotion threshold: a
     * chunk on the wrong side of a conflict is still evidence about the subject, and dropping it
     * is exactly how a partition ends up confidently one-sided.</p>
     */
    public static final double DEFAULT_CONFIDENCE = 0.55;

    private final GraphMaintenanceService maintenance;
    private final KnowledgeGraphService graph;
    private final Function<EntityPartition, Long> factSheetResolver;
    private final Function<EntityPartition, List<String>> subjectResolver;
    private final double baseConfidence;

    public ContradictionDiscoveryProvider(GraphMaintenanceService maintenance,
                                          KnowledgeGraphService graph) {
        this(maintenance, graph, PartitionFactSheets.fromPinOrSnapshot(),
                PartitionSubjects.fromPinOrKey(), DEFAULT_CONFIDENCE);
    }

    public ContradictionDiscoveryProvider(GraphMaintenanceService maintenance,
                                          KnowledgeGraphService graph,
                                          Function<EntityPartition, Long> factSheetResolver) {
        this(maintenance, graph, factSheetResolver, PartitionSubjects.fromPinOrKey(),
                DEFAULT_CONFIDENCE);
    }

    public ContradictionDiscoveryProvider(GraphMaintenanceService maintenance,
                                          KnowledgeGraphService graph,
                                          Function<EntityPartition, Long> factSheetResolver,
                                          double baseConfidence) {
        this(maintenance, graph, factSheetResolver, PartitionSubjects.fromPinOrKey(),
                baseConfidence);
    }

    public ContradictionDiscoveryProvider(GraphMaintenanceService maintenance,
                                          KnowledgeGraphService graph,
                                          Function<EntityPartition, Long> factSheetResolver,
                                          Function<EntityPartition, List<String>> subjects,
                                          double baseConfidence) {
        this.maintenance = Objects.requireNonNull(maintenance,
                "a contradiction channel needs a maintenance service");
        this.graph = Objects.requireNonNull(graph, "a contradiction channel needs a graph");
        this.factSheetResolver = factSheetResolver == null
                ? PartitionFactSheets.fromPinOrSnapshot() : factSheetResolver;
        this.subjectResolver = subjects == null ? PartitionSubjects.fromPinOrKey() : subjects;
        this.baseConfidence = clamp(baseConfidence, DEFAULT_CONFIDENCE);
    }

    @Override
    public DiscoveryChannel channel() {
        return CHANNEL;
    }

    @Override
    public List<ChunkCandidate> discover(EntityPartition partition, int round, int limit) {
        if (partition == null || round > 1) {
            return List.of();
        }
        List<String> subjects = subjectResolver.apply(partition);
        if (subjects == null || subjects.isEmpty()) {
            return List.of();
        }
        Long factSheetId = factSheetResolver.apply(partition);
        if (factSheetId == null) {
            log.debug("Partition {} names no fact sheet; the contradiction channel has nothing to "
                    + "scan", partition.id());
            return List.of();
        }

        List<Contradiction> detected = maintenance.detectContradictions(factSheetId);
        if (detected == null || detected.isEmpty()) {
            return List.of();
        }
        List<Contradiction> aboutSubject = new ArrayList<>();
        for (Contradiction contradiction : detected) {
            if (contradiction != null && isAbout(contradiction, subjects)) {
                aboutSubject.add(contradiction);
            }
        }
        if (aboutSubject.isEmpty()) {
            return List.of();
        }

        // Built once, and only once something needs it: resolving each conflicting edge id through
        // the store separately would be one round-trip per conflict.
        Map<String, GraphEdge> edgesById = indexEdges(factSheetId);
        int cap = Math.max(1, limit);
        Map<String, ChunkCandidate> best = new LinkedHashMap<>();
        int uncitable = 0;

        for (Contradiction contradiction : aboutSubject) {
            String reason = reasonFor(contradiction);
            double confidence = confidenceOf(contradiction);
            boolean cited = false;
            for (String edgeId : edgeIdsOf(contradiction)) {
                GraphEdge edge = edgesById.get(edgeId);
                if (edge == null) {
                    continue;
                }
                List<String> chunkIds = GraphProvenanceChunks.chunkIdsOf(edge);
                if (chunkIds.isEmpty()) {
                    continue;
                }
                String documentId = GraphProvenanceChunks.documentIdOf(edge);
                if (documentId == null) {
                    documentId = firstNonBlank(contradiction.sourceDocNew(),
                            contradiction.sourceDocExisting());
                }
                cited |= offer(best, partition, chunkIds, documentId, confidence, reason, cap);
            }
            if (!cited) {
                uncitable++;
            }
        }
        if (uncitable > 0) {
            // Said out loud: a conflict whose edges recorded no chunk cannot be put in front of a
            // reader, and the partition would otherwise look like it had considered it.
            log.info("{} of {} contradiction(s) about {} in fact sheet {} name no chunk provenance "
                            + "and could not be cited into the partition", uncitable,
                    aboutSubject.size(), subjects, factSheetId);
        }

        List<ChunkCandidate> ranked = new ArrayList<>(best.values());
        ranked.sort(Comparator.comparingDouble(ChunkCandidate::confidence).reversed());
        return ranked.size() <= cap ? ranked : new ArrayList<>(ranked.subList(0, cap));
    }

    /**
     * True when a contradiction is about {@code subject}.
     *
     * <p>Both spellings are accepted because the detector reports whichever the graph gave it: a
     * node id follows the {@code <type>_<externalId>} convention, while a partition is usually
     * keyed on the external id alone. Matching only one would make the channel silently empty for
     * half the callers.</p>
     */
    static boolean isAbout(Contradiction contradiction, String subject) {
        return matches(contradiction.entityIdA(), subject)
                || matches(contradiction.entityIdB(), subject);
    }

    /**
     * True when a contradiction is about any subject the partition reads.
     *
     * <p>Any, not all: a conflict involving one member of a group is a conflict that group has to
     * see. Requiring it to name every member would hide exactly the disagreements grouping brought
     * into the same partition to be compared.</p>
     */
    static boolean isAbout(Contradiction contradiction, Collection<String> subjects) {
        for (String subject : subjects) {
            if (isAbout(contradiction, subject)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String entityId, String subject) {
        if (entityId == null || subject == null) {
            return false;
        }
        String a = entityId.trim().toLowerCase(Locale.ROOT);
        String b = subject.trim().toLowerCase(Locale.ROOT);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        return a.equals(b) || a.endsWith("_" + b) || b.endsWith("_" + a);
    }

    /** Conflicting edges first, then the ones proposed as stale; duplicates collapse. */
    static List<String> edgeIdsOf(Contradiction contradiction) {
        Set<String> ids = new LinkedHashSet<>();
        for (String id : contradiction.conflictingEdgeIds()) {
            addId(ids, id);
        }
        for (String id : contradiction.candidateStaleEdgeIds()) {
            addId(ids, id);
        }
        return List.copyOf(ids);
    }

    private static void addId(Set<String> ids, String id) {
        if (id != null && !id.isBlank()) {
            ids.add(id.trim());
        }
    }

    private boolean offer(Map<String, ChunkCandidate> best, EntityPartition partition,
                          List<String> chunkIds, String documentId, double confidence,
                          String reason, int cap) {
        boolean any = false;
        for (String chunkId : chunkIds) {
            if (chunkId == null || chunkId.isBlank()) {
                continue;
            }
            any = true;
            if (best.size() >= cap && !best.containsKey(chunkId)) {
                continue;
            }
            ChunkCandidate candidate = ChunkCandidate.of(chunkId, CHANNEL, confidence, reason)
                    .inDocument(documentId);
            PartitionMember existing = partition.member(chunkId).orElse(null);
            if (existing != null && existing.absorbs(candidate)) {
                continue;
            }
            best.merge(chunkId, candidate,
                    (kept, incoming) -> kept.confidence() >= incoming.confidence() ? kept : incoming);
        }
        return any;
    }

    private Map<String, GraphEdge> indexEdges(Long factSheetId) {
        List<GraphEdge> edges = graph.getEdgesInFactSheet(factSheetId);
        if (edges == null || edges.isEmpty()) {
            return Map.of();
        }
        Map<String, GraphEdge> byId = new LinkedHashMap<>(Math.max(16, edges.size() * 2));
        for (GraphEdge edge : edges) {
            if (edge != null && edge.getEdgeId() != null) {
                byId.putIfAbsent(edge.getEdgeId(), edge);
            }
        }
        return byId;
    }

    /** Severity when the detector measured one; otherwise the channel's own default. */
    double confidenceOf(Contradiction contradiction) {
        Double severity = contradiction.severity();
        if (severity == null || severity.isNaN() || severity <= 0.0) {
            return baseConfidence;
        }
        return Math.min(1.0, severity);
    }

    private static String reasonFor(Contradiction contradiction) {
        StringBuilder reason = new StringBuilder("contradicts admitted evidence");
        String predicate = contradiction.predicate();
        if (predicate != null && !predicate.isBlank()) {
            reason.append(" on ").append(predicate.trim());
        } else if (contradiction.type() != null) {
            reason.append(" (").append(contradiction.type().name()).append(')');
        }
        String id = contradiction.contradictionId();
        if (id != null && !id.isBlank()) {
            reason.append(" [").append(id.trim()).append(']');
        }
        return reason.toString();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null || second.isBlank() ? null : second.trim();
    }

    private static double clamp(double value, double fallback) {
        if (Double.isNaN(value) || value <= 0.0 || value > 1.0) {
            return fallback;
        }
        return value;
    }
}
