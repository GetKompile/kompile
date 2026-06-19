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
package ai.kompile.knowledgegraph.maintenance;

import ai.kompile.core.graphrag.maintenance.model.Contradiction;
import ai.kompile.core.graphrag.maintenance.model.ContradictionResolutionStrategy;
import ai.kompile.core.graphrag.maintenance.model.MaintenanceTask;
import ai.kompile.core.graphrag.maintenance.model.TaskReport;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.repository.GraphEdgeRepository;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rule-based contradiction detector for knowledge graph edges.
 *
 * <p>A contradiction is detected when two or more active edges connect the same
 * source-target node pair but carry <em>different</em> edge types or labels,
 * suggesting conflicting facts about the relationship between those entities.
 * For example: {@code A --WORKS_AT--> B} and {@code A --LEFT--> B} recorded in
 * different source documents would be flagged as a
 * {@link Contradiction.ContradictionType#CONFLICTING_RELATIONSHIP}.</p>
 *
 * <p>Resolution strategies are applied by {@link #resolve}: newer-wins and
 * higher-confidence-wins automate the decision; the other strategies leave the
 * data unchanged and simply report the contradiction.</p>
 */
@Slf4j
@Component
public class ContradictionDetector {

    private  GraphNodeRepository nodeRepository;
    private  GraphEdgeRepository edgeRepository;

    public ContradictionDetector(GraphNodeRepository nodeRepository,
                                 GraphEdgeRepository edgeRepository) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
    }

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected ContradictionDetector() {}


    /**
     * Detect contradictions in the given fact sheet without modifying any data.
     *
     * @param factSheetId the fact sheet to inspect
     * @return list of detected contradictions (may be empty)
     */
    public List<Contradiction> detect(Long factSheetId) {
        List<GraphEdge> allEdges = edgeRepository.findActiveEdges(factSheetId);
        log.debug("ContradictionDetector: inspecting {} active edges for factSheet={}",
                allEdges.size(), factSheetId);

        // Group edges by (sourceNode.id, targetNode.id)
        Map<String, List<GraphEdge>> pairGroups = new HashMap<>();
        for (GraphEdge edge : allEdges) {
            if (edge.getSourceNode() == null || edge.getTargetNode() == null) continue;
            String key = edge.getSourceNode().getId() + ":" + edge.getTargetNode().getId();
            pairGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(edge);
        }

        List<Contradiction> contradictions = new ArrayList<>();
        for (Map.Entry<String, List<GraphEdge>> entry : pairGroups.entrySet()) {
            List<GraphEdge> group = entry.getValue();
            if (group.size() <= 1) continue;

            // Check whether the group contains edges of different types or labels
            boolean hasMultipleTypes = group.stream()
                    .map(e -> e.getEdgeType() != null ? e.getEdgeType().name() : "UNKNOWN")
                    .distinct()
                    .count() > 1;

            if (!hasMultipleTypes) {
                // Same type on all edges — check labels for finer distinctions
                boolean hasMultipleLabels = group.stream()
                        .map(e -> e.getLabel() != null ? e.getLabel().toLowerCase() : "")
                        .distinct()
                        .count() > 1;
                if (!hasMultipleLabels) continue;
            }

            // Found a contradiction; report each conflicting pair
            for (int i = 0; i < group.size() - 1; i++) {
                GraphEdge edgeA = group.get(i);
                GraphEdge edgeB = group.get(i + 1);

                String existingFact = describeEdge(edgeA);
                String newFact = describeEdge(edgeB);
                String srcDocA = edgeA.getProvenance();
                String srcDocB = edgeB.getProvenance();
                String entityIdA = edgeA.getSourceNode().getNodeId();
                String entityIdB = edgeA.getTargetNode().getNodeId();

                Contradiction contradiction = new Contradiction(
                        entityIdA,
                        entityIdB,
                        existingFact,
                        newFact,
                        srcDocA,
                        srcDocB,
                        Contradiction.ContradictionType.CONFLICTING_RELATIONSHIP,
                        Contradiction.Resolution.NEEDS_REVIEW
                );
                contradictions.add(contradiction);
                log.debug("ContradictionDetector: contradiction between {} and {} on pair {}:{}",
                        edgeA.getEdgeId(), edgeB.getEdgeId(), entityIdA, entityIdB);
            }
        }

        log.info("ContradictionDetector: found {} contradictions for factSheet={}", contradictions.size(), factSheetId);
        return contradictions;
    }

    /**
     * Detect and resolve contradictions according to the given strategy.
     *
     * @param factSheetId the fact sheet to process
     * @param strategy    how to resolve detected contradictions
     * @param dryRun      when {@code true} no writes are performed
     * @return a {@link TaskReport} summarising the operation
     */
    @Transactional
    public TaskReport resolve(Long factSheetId, ContradictionResolutionStrategy strategy, boolean dryRun) {
        Instant start = Instant.now();
        LocalDateTime now = LocalDateTime.now();
        int scanned = 0;
        int affected = 0;
        int skipped = 0;
        List<String> warnings = new ArrayList<>();

        List<Contradiction> contradictions = detect(factSheetId);
        scanned = contradictions.size();

        if (strategy == ContradictionResolutionStrategy.KEEP_BOTH
                || strategy == ContradictionResolutionStrategy.FLAG_FOR_REVIEW
                || strategy == ContradictionResolutionStrategy.LLM_JUDGE) {
            log.info("ContradictionDetector: strategy={} — reporting only, no writes", strategy);
            if (strategy == ContradictionResolutionStrategy.LLM_JUDGE) {
                warnings.add("LLM_JUDGE strategy is not automated; contradictions flagged for review.");
            }
            return new TaskReport(
                    MaintenanceTask.CONTRADICTION_DETECT,
                    scanned, 0, scanned, warnings,
                    Duration.between(start, Instant.now())
            );
        }

        // For NEWER_WINS and HIGHER_CONFIDENCE_WINS we need the actual edge objects
        List<GraphEdge> allEdges = edgeRepository.findActiveEdges(factSheetId);
        Map<String, List<GraphEdge>> pairGroups = new HashMap<>();
        for (GraphEdge edge : allEdges) {
            if (edge.getSourceNode() == null || edge.getTargetNode() == null) continue;
            String key = edge.getSourceNode().getId() + ":" + edge.getTargetNode().getId();
            pairGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(edge);
        }

        List<Long> edgeIdsToStale = new ArrayList<>();

        for (List<GraphEdge> group : pairGroups.values()) {
            if (group.size() <= 1) continue;
            boolean hasMultipleTypes = group.stream()
                    .map(e -> e.getEdgeType() != null ? e.getEdgeType().name() : "UNKNOWN")
                    .distinct().count() > 1;
            if (!hasMultipleTypes) continue;

            // Sort: determine the "winner"
            GraphEdge winner;
            if (strategy == ContradictionResolutionStrategy.NEWER_WINS) {
                winner = group.stream()
                        .max(Comparator.comparing(
                                e -> e.getCreatedAt() != null ? e.getCreatedAt() : LocalDateTime.MIN))
                        .orElse(group.get(0));
            } else { // HIGHER_CONFIDENCE_WINS
                winner = group.stream()
                        .max(Comparator.comparingDouble(
                                e -> e.getConfidence() != null ? e.getConfidence() : 0.0))
                        .orElse(group.get(0));
            }

            for (GraphEdge edge : group) {
                if (edge.getId().equals(winner.getId())) continue;
                edgeIdsToStale.add(edge.getId());
                affected++;
            }
        }

        if (!dryRun && !edgeIdsToStale.isEmpty()) {
            edgeRepository.bulkMarkStale(edgeIdsToStale, now);
            log.info("ContradictionDetector: staled {} conflicting edges using strategy={} for factSheet={}",
                    edgeIdsToStale.size(), strategy, factSheetId);
        }

        log.info("ContradictionDetector.resolve (dryRun={}, strategy={}): scanned={}, affected={}, skipped={} for factSheet={}",
                dryRun, strategy, scanned, affected, skipped, factSheetId);

        return new TaskReport(
                MaintenanceTask.CONTRADICTION_DETECT,
                scanned,
                affected,
                skipped,
                warnings,
                Duration.between(start, Instant.now())
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static String describeEdge(GraphEdge edge) {
        String type = edge.getEdgeType() != null ? edge.getEdgeType().name() : "UNKNOWN";
        String label = edge.getLabel() != null ? " (" + edge.getLabel() + ")" : "";
        return type + label;
    }
}
