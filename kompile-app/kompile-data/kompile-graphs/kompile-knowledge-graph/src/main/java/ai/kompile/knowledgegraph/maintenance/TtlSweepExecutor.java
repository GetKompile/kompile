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

import ai.kompile.core.graphrag.maintenance.model.MaintenanceTask;
import ai.kompile.core.graphrag.maintenance.model.TaskReport;
import ai.kompile.core.graphrag.maintenance.model.TtlPolicy;
import ai.kompile.graph.reasoning.maintenance.PruneResult;
import ai.kompile.graph.reasoning.maintenance.StalenessPruningPolicy;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.model.SimpleGraphEntity;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.repository.GraphEdgeRepository;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Executes TTL-based expiry sweeps against a knowledge graph fact sheet.
 *
 * <p>Behaviour is governed by the {@link TtlPolicy}: expired nodes and edges
 * can be soft-deleted (marked stale) or hard-deleted immediately.  High-confidence
 * items can be exempt via {@code policy.minConfidenceToKeep()}.
 * Previously soft-deleted items that have passed the grace period are hard-deleted.</p>
 *
 * <p>The expiry <em>decision</em> for nodes is now delegated to the generic
 * {@link StalenessPruningPolicy} from {@code kompile-graph-maintenance}: the
 * JPA nodes' {@code validUntil} fields are projected as {@link java.time.Instant}
 * timestamps in a {@link ai.kompile.graph.reasoning.model.ReasoningGraph}, and
 * the policy is evaluated with {@code Instant.now()} as the cutoff (so the
 * caller supplies the time, keeping the policy pure). Deletions are applied here
 * via the JPA repositories — the live-store migration remains an open task.</p>
 *
 * <p><strong>Live-store limitation:</strong> this executor still reads JPA ({@code GraphNodeRepository}),
 * which is unpopulated on the live {@code @Primary} matrix/vector store, so TTL expiry finds nothing
 * there. A live-store migration is blocked because {@code MatrixGraphNode} has no {@code validUntil}
 * expiry field to scan — that field must be added (and populated at node creation) before TTL expiry
 * can run against the live graph. The grace-period purge of already-stale nodes is, however, available
 * on the live store via {@link ai.kompile.knowledgegraph.service.KnowledgeGraphService#hardDeleteStaleNodes}.</p>
 */
@Slf4j
@Component
public class TtlSweepExecutor {

    /** Default grace period in days before stale items are permanently deleted. */
    private static final int DEFAULT_GRACE_DAYS = 7;

    private  GraphNodeRepository nodeRepository;
    private  GraphEdgeRepository edgeRepository;

    @Autowired
    public TtlSweepExecutor(GraphNodeRepository nodeRepository,
                            GraphEdgeRepository edgeRepository) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
    }

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected TtlSweepExecutor() {}


    /**
     * Run a TTL sweep over the given fact sheet.
     *
     * @param factSheetId the fact sheet to sweep
     * @param policy      the TTL policy to apply
     * @param dryRun      when {@code true} no writes are performed
     * @return a {@link TaskReport} summarising the sweep
     */
    @Transactional
    public TaskReport execute(Long factSheetId, TtlPolicy policy, boolean dryRun) {
        Instant start = Instant.now();
        LocalDateTime now = LocalDateTime.now();
        // The cutoff for StalenessPruningPolicy: treat validUntil < now as expired
        Instant cutoff = now.toInstant(ZoneOffset.UTC);
        int scanned = 0;
        int affected = 0;
        int skipped = 0;
        List<String> warnings = new ArrayList<>();

        // ── 1. Find expired nodes via StalenessPruningPolicy ────────────────────
        List<GraphNode> expiredNodes = nodeRepository.findExpiredNodes(factSheetId, now);
        scanned += expiredNodes.size();
        log.debug("TTL sweep: {} expired nodes found for factSheet={}", expiredNodes.size(), factSheetId);

        // Build a minimal ReasoningGraph projecting validUntil as timestamp
        MutableReasoningGraph nodeGraph = buildNodeGraph(expiredNodes);
        // The policy uses the cutoff = now; validUntil < now means stale
        PruneResult nodeDecision = new StalenessPruningPolicy(cutoff).evaluate(nodeGraph);

        List<Long> nodeIdsToMark = new ArrayList<>();
        for (GraphNode node : expiredNodes) {
            // Filter by policy decision: only act if the generic policy selected this node
            if (!nodeDecision.entityIds().contains(node.getNodeId())) {
                skipped++;
                continue;
            }
            Double confidence = node.getConfidence();
            if (policy.minConfidenceToKeep() != null
                    && confidence != null
                    && confidence >= policy.minConfidenceToKeep()) {
                skipped++;
                log.debug("Skipping high-confidence expired node {} (confidence={})", node.getNodeId(), confidence);
                continue;
            }

            switch (policy.action()) {
                case SOFT_DELETE, MARK_STALE -> {
                    if (!dryRun) {
                        nodeIdsToMark.add(node.getId());
                    }
                    affected++;
                }
                case HARD_DELETE -> {
                    if (!dryRun) {
                        nodeRepository.delete(node);
                    }
                    affected++;
                }
                default -> warnings.add("Unknown TtlAction for node " + node.getNodeId());
            }
        }

        if (!dryRun && !nodeIdsToMark.isEmpty()) {
            nodeRepository.bulkMarkStale(nodeIdsToMark, now);
        }

        // ── 2. Find expired edges ────────────────────────────────────────────────
        List<GraphEdge> expiredEdges = edgeRepository.findExpiredEdges(factSheetId, now);
        scanned += expiredEdges.size();
        log.debug("TTL sweep: {} expired edges found for factSheet={}", expiredEdges.size(), factSheetId);

        List<Long> edgeIdsToMark = new ArrayList<>();
        for (GraphEdge edge : expiredEdges) {
            Double confidence = edge.getConfidence();
            if (policy.minConfidenceToKeep() != null
                    && confidence != null
                    && confidence >= policy.minConfidenceToKeep()) {
                skipped++;
                log.debug("Skipping high-confidence expired edge {} (confidence={})", edge.getEdgeId(), confidence);
                continue;
            }

            switch (policy.action()) {
                case SOFT_DELETE, MARK_STALE -> {
                    if (!dryRun) {
                        edgeIdsToMark.add(edge.getId());
                    }
                    affected++;
                }
                case HARD_DELETE -> {
                    if (!dryRun) {
                        edgeRepository.delete(edge);
                    }
                    affected++;
                }
                default -> warnings.add("Unknown TtlAction for edge " + edge.getEdgeId());
            }
        }

        if (!dryRun && !edgeIdsToMark.isEmpty()) {
            edgeRepository.bulkMarkStale(edgeIdsToMark, now);
        }

        // ── 3. Hard-delete previously stale items past grace period ──────────────
        LocalDateTime graceCutoff = now.minusDays(DEFAULT_GRACE_DAYS);
        if (!dryRun) {
            int hardDeletedNodes = nodeRepository.hardDeleteStaleNodes(factSheetId, graceCutoff);
            int hardDeletedEdges = edgeRepository.hardDeleteStaleEdges(factSheetId, graceCutoff);
            if (hardDeletedNodes > 0 || hardDeletedEdges > 0) {
                log.info("TTL sweep: hard-deleted {} nodes and {} edges past grace period for factSheet={}",
                        hardDeletedNodes, hardDeletedEdges, factSheetId);
            }
        }

        log.info("TTL sweep (dryRun={}): scanned={}, affected={}, skipped={} for factSheet={}",
                dryRun, scanned, affected, skipped, factSheetId);

        return new TaskReport(
                MaintenanceTask.TTL_SWEEP,
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

    /**
     * Build a minimal ReasoningGraph from the given expired nodes, projecting
     * {@code validUntil} as each entity's {@link ai.kompile.graph.reasoning.model.GraphEntity#timestamp()}.
     * This lets {@link StalenessPruningPolicy} evaluate whether each entity's
     * "expiry timestamp" is before the supplied cutoff.
     */
    private static MutableReasoningGraph buildNodeGraph(List<GraphNode> nodes) {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        for (GraphNode n : nodes) {
            Instant ts = n.getValidUntil() != null ? n.getValidUntil().toInstant(ZoneOffset.UTC) : null;
            graph.addEntity(new SimpleGraphEntity(
                    n.getNodeId(),
                    n.getNodeType() != null ? n.getNodeType().name() : "",
                    n.getTitle() != null ? n.getTitle() : n.getNodeId(),
                    n.getConfidence() != null ? n.getConfidence() : 1.0,
                    n.getConfidence() != null ? n.getConfidence() : 1.0,
                    Set.of(),
                    null,
                    ts,
                    Map.of()
            ));
        }
        return graph;
    }
}
