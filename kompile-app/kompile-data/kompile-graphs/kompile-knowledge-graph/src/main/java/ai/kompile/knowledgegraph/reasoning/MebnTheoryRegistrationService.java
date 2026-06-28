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
package ai.kompile.knowledgegraph.reasoning;

import ai.kompile.graph.reasoning.mebn.MFrag;
import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.mebn.MTheoryValidator;
import ai.kompile.graph.reasoning.mebn.RelationalMTheoryBuilder;
import ai.kompile.graph.reasoning.mebn.RelationalMTheoryBuilder.RelationDescriptor;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a simple {@link MTheory} from live graph data for a given fact sheet and registers it
 * with the {@link IncrementalReasoningOrchestrator} so the next grounding cascade will run
 * MEBN weight learning.
 *
 * <p>This service fills the production gap where {@link IncrementalReasoningOrchestrator#registerMTheory}
 * had no callers. It is designed to be called:</p>
 * <ul>
 *   <li>After graph enrichment completes for a fact sheet</li>
 *   <li>On demand via a REST endpoint or admin trigger</li>
 * </ul>
 *
 * <p>The MTheory built here is typed and relational: a foundational {@code EntityRelevance}
 * MFrag plus one {@link MFrag} per distinct edge type (up to {@link #MAX_EDGE_TYPES}). Each
 * edge-type MFrag has a binary relationship RV typed by the <em>dominant</em> source and target
 * {@link ai.kompile.knowledgegraph.domain.NodeLevel} observed for that edge type, conditioned on
 * {@code isRelevant(SourceType)} (an INPUT with parent-edge activation probability = mean observed
 * edge weight), and gated by four context constraints (IsA(src, SourceType), IsA(tgt, TargetType),
 * notEqual, edgeExists) so SSBN grounding stays bounded by actual edges rather than the N²
 * node-pair product. This seeds the MEBN weight learner with per-type typed structure derived from
 * real graph topology.</p>
 */
@Service
@Slf4j
public class MebnTheoryRegistrationService {

    /**
     * Maximum number of distinct edge types to include in the auto-built MTheory.
     * Caps MFrag count to avoid O(|edgeTypes|^2) MEBN grounding explosion for graphs
     * with many exotic edge types.
     */
    static final int MAX_EDGE_TYPES = 20;

    private final IncrementalReasoningOrchestrator orchestrator;

    @Autowired(required = false)
    private KnowledgeGraphService knowledgeGraphService;

    @Autowired(required = false)
    private GraphToFactStoreProjector graphToFactStoreProjector;

    public MebnTheoryRegistrationService(IncrementalReasoningOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * Build and register an {@link MTheory} for the given fact sheet.
     *
     * <p>Steps:</p>
     * <ol>
     *   <li>Fetch all edges in the fact sheet via {@link KnowledgeGraphService#getEdgesInFactSheet}.</li>
     *   <li>Collect up to {@link #MAX_EDGE_TYPES} distinct edge type labels.</li>
     *   <li>Map each edge type to its dominant source/target {@link ai.kompile.knowledgegraph.domain.NodeLevel}
     *       and per-type entity-id sets.</li>
     *   <li>Call {@link #buildEnrichedMTheory} which delegates pure MEBN construction to
     *       {@link RelationalMTheoryBuilder}.</li>
     *   <li>Validate the resulting MTheory and log any violations (does not abort a valid theory).</li>
     *   <li>Build a {@link MutableReasoningGraph} and call
     *       {@link IncrementalReasoningOrchestrator#registerMTheory}.</li>
     * </ol>
     *
     * @param factSheetId the fact sheet to build and register an MTheory for
     * @return the number of {@link MFrag}s registered (0 if the service could not run)
     */
    public int registerMTheoryForFactSheet(long factSheetId) {
        if (knowledgeGraphService == null) {
            log.warn("[MebnTheoryRegistrationService] KnowledgeGraphService not wired — "
                    + "cannot auto-register MTheory for factSheet={}", factSheetId);
            return 0;
        }

        List<GraphEdge> edges = knowledgeGraphService.getEdgesInFactSheet(factSheetId);
        if (edges == null || edges.isEmpty()) {
            log.info("[MebnTheoryRegistrationService] No edges found for factSheet={} — "
                    + "skipping MTheory registration", factSheetId);
            return 0;
        }

        // ── Collect distinct edge-type labels (up to MAX_EDGE_TYPES) ─────────────────
        Set<String> edgeTypeLabels = new LinkedHashSet<>();
        for (GraphEdge edge : edges) {
            if (edgeTypeLabels.size() >= MAX_EDGE_TYPES) break;
            String label = edgeTypeLabel(edge);
            if (label != null && !label.isBlank()) {
                edgeTypeLabels.add(label);
            }
        }

        if (edgeTypeLabels.isEmpty()) {
            log.info("[MebnTheoryRegistrationService] No usable edge types for factSheet={} — "
                    + "skipping MTheory registration", factSheetId);
            return 0;
        }

        // ── Build a typed, relational MTheory from the graph topology ─────────────────
        MTheory mTheory = buildEnrichedMTheory(factSheetId, edges, edgeTypeLabels);

        // ── Validate and log any structural violations — never abort a healthy theory ──
        MTheoryValidator.ValidationResult validation = MTheoryValidator.validate(mTheory);
        if (!validation.isValid()) {
            log.warn("[MebnTheoryRegistrationService] MTheory for factSheet={} has {} violation(s): {}",
                    factSheetId, validation.violations().size(), validation.violations());
        }

        // ── Build ReasoningGraph from the edge list ───────────────────────────────────
        MutableReasoningGraph reasoningGraph = new MutableReasoningGraph();
        for (GraphEdge edge : edges) {
            GraphNode src = edge.getSourceNode();
            GraphNode tgt = edge.getTargetNode();
            if (src == null || tgt == null) continue;

            String srcId = src.getNodeId();
            String srcType = src.getNodeType() != null ? src.getNodeType().name() : "NODE";
            String srcLabel = src.getTitle() != null ? src.getTitle() : srcId;
            reasoningGraph.addEntity(srcId, srcType, srcLabel);

            String tgtId = tgt.getNodeId();
            String tgtType = tgt.getNodeType() != null ? tgt.getNodeType().name() : "NODE";
            String tgtLabel = tgt.getTitle() != null ? tgt.getTitle() : tgtId;
            reasoningGraph.addEntity(tgtId, tgtType, tgtLabel);

            String relId = edge.getEdgeId() != null ? edge.getEdgeId() : srcId + "->" + tgtId;
            String relType = edgeTypeLabel(edge);
            double weight = edge.getWeight() != null ? edge.getWeight() : 0.5;
            reasoningGraph.addRelation(relId, srcId, tgtId, relType != null ? relType : "UNKNOWN", weight);
        }

        // ── Register with the orchestrator ────────────────────────────────────────────
        orchestrator.registerMTheory(factSheetId, mTheory, reasoningGraph);

        int mFragCount = mTheory.getMFrags().size();
        log.info("[MebnTheoryRegistrationService] Registered MTheory for factSheet={}: "
                + "{} MFrag(s) from {} edge(s) (edgeTypes: {})",
                factSheetId, mFragCount, edges.size(), edgeTypeLabels);
        return mFragCount;
    }

    /**
     * Build a typed, relational {@link MTheory} from graph edges — package-private + static so it
     * can be unit-tested without Spring or a live graph.
     *
     * <p>Infra mapping (graph topology → pure MEBN inputs):</p>
     * <ol>
     *   <li>For each edge type: aggregate the mean observed weight as the
     *       <em>activation probability</em> — P(relation = TRUE | isRelevant(src) = TRUE,
     *       edgeExists). See {@link RelationDescriptor} for full semantics.</li>
     *   <li>For each edge type: derive the dominant source and target
     *       {@link ai.kompile.knowledgegraph.domain.NodeLevel} type names by taking the mode of
     *       each endpoint's node type across all edges of that label.</li>
     *   <li>Collect per-type entity-id sets from the endpoints of matching edges.</li>
     *   <li>Build one {@link RelationDescriptor} per edge type and delegate pure MEBN
     *       construction to {@link RelationalMTheoryBuilder#build}.</li>
     * </ol>
     *
     * @param factSheetId    fact sheet id (used only as the theory name suffix)
     * @param edges          the fact sheet's edges (source of node ids, types, and weights)
     * @param edgeTypeLabels the distinct edge-type labels to build fragments for (already capped)
     * @return the typed, relational MTheory
     */
    static MTheory buildEnrichedMTheory(long factSheetId, List<GraphEdge> edges, Set<String> edgeTypeLabels) {

        // Per edge-type label: accumulate weight (sum, count) and endpoint-type occurrence counts.
        Map<String, double[]> weightAccum = new HashMap<>();            // label → {sum, count}
        Map<String, Map<String, Long>> srcTypeCounts = new HashMap<>(); // label → {typeName → count}
        Map<String, Map<String, Long>> tgtTypeCounts = new HashMap<>(); // label → {typeName → count}
        // Per NodeLevel type name: set of node IDs observed on that side.
        Map<String, Set<String>> entityIdsByType = new LinkedHashMap<>();

        for (GraphEdge edge : edges) {
            String label = edgeTypeLabel(edge);
            if (label == null || !edgeTypeLabels.contains(label)) continue;
            GraphNode src = edge.getSourceNode();
            GraphNode tgt = edge.getTargetNode();
            if (src == null || tgt == null) continue;
            String srcId = src.getNodeId();
            String tgtId = tgt.getNodeId();
            if (srcId == null || tgtId == null) continue;

            String srcType = nodeTypeName(src);
            String tgtType = nodeTypeName(tgt);

            entityIdsByType.computeIfAbsent(srcType, k -> new LinkedHashSet<>()).add(srcId);
            entityIdsByType.computeIfAbsent(tgtType, k -> new LinkedHashSet<>()).add(tgtId);

            double w = edge.getWeight() != null ? edge.getWeight() : 0.5;
            double[] acc = weightAccum.computeIfAbsent(label, k -> new double[2]);
            acc[0] += w;
            acc[1] += 1;

            srcTypeCounts.computeIfAbsent(label, k -> new HashMap<>()).merge(srcType, 1L, Long::sum);
            tgtTypeCounts.computeIfAbsent(label, k -> new HashMap<>()).merge(tgtType, 1L, Long::sum);
        }

        // Build one RelationDescriptor per edge-type label; preserve discovery order.
        List<RelationDescriptor> descriptors = new ArrayList<>();
        for (String label : edgeTypeLabels) {
            double[] acc = weightAccum.get(label);
            // Mean observed edge weight = activation-probability estimate for this relation.
            double activationProbability = (acc != null && acc[1] > 0) ? acc[0] / acc[1] : 0.5;

            // Dominant endpoint type = the NodeLevel type name seen most often on each side.
            String dominantSrc = dominantType(srcTypeCounts.getOrDefault(label, Map.of()));
            String dominantTgt = dominantType(tgtTypeCounts.getOrDefault(label, Map.of()));

            Set<String> srcIds = entityIdsByType.getOrDefault(dominantSrc, Set.of());
            Set<String> tgtIds = entityIdsByType.getOrDefault(dominantTgt, Set.of());

            descriptors.add(new RelationDescriptor(
                    sanitizeName(label), dominantSrc, dominantTgt,
                    activationProbability, srcIds, tgtIds));
        }

        // Pure MEBN theory construction lives in the agnostic graph-reasoning library.
        return RelationalMTheoryBuilder.build("auto-factSheet-" + factSheetId, descriptors);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Derive a human-readable label for an edge's type. Prefers the semantic
     * {@link GraphEdge#getRelationType()} when set; falls back to the structural
     * {@link GraphEdge#getEdgeType()} enum name.
     */
    private static String edgeTypeLabel(GraphEdge edge) {
        if (edge.getRelationType() != null && !edge.getRelationType().isBlank()) {
            return edge.getRelationType();
        }
        if (edge.getEdgeType() != null) {
            return edge.getEdgeType().name();
        }
        return null;
    }

    /**
     * Return the {@link ai.kompile.knowledgegraph.domain.NodeLevel} name of a node,
     * or {@code "NODE"} when unset.
     */
    private static String nodeTypeName(GraphNode node) {
        return node.getNodeType() != null ? node.getNodeType().name() : "NODE";
    }

    /**
     * The type name with the highest occurrence count among {@code typeCounts};
     * falls back to {@code "NODE"} when the map is empty.
     */
    private static String dominantType(Map<String, Long> typeCounts) {
        return typeCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("NODE");
    }

    /**
     * Sanitize a raw edge-type string into a valid Java-identifier-like name
     * usable as an MFrag / RV name (no spaces, no special chars).
     */
    private static String sanitizeName(String raw) {
        return raw.replaceAll("[^A-Za-z0-9_]", "_");
    }
}
