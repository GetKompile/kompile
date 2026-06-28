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
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Executes TTL-based expiry sweeps against a knowledge graph fact sheet.
 *
 * <p>Behaviour is governed by the {@link TtlPolicy}: expired nodes can be soft-deleted
 * (marked stale) or hard-deleted immediately. High-confidence items can be exempt via
 * {@code policy.minConfidenceToKeep()}. Previously soft-deleted items that have passed
 * the grace period are hard-deleted.</p>
 *
 * <p>Expiry is determined by the {@code _validUntil} metadata field on each node
 * (epoch-millisecond string). Nodes without this field are considered permanent and
 * are never expired by this sweep. This field should be set at node creation time when
 * a TTL is known (e.g. for ephemeral research nodes).</p>
 *
 * <p>The expiry <em>decision</em> for nodes is delegated to the generic
 * {@link StalenessPruningPolicy} from {@code kompile-graph-maintenance}: the nodes'
 * {@code _validUntil} fields are projected as {@link java.time.Instant} timestamps
 * in a {@link MutableReasoningGraph}, and the policy is evaluated with
 * {@code Instant.now()} as the cutoff. Soft/hard-delete operations route through
 * the {@link KnowledgeGraphService} seam so they work on the live @Primary
 * matrix/vector store.</p>
 *
 * <p><strong>Edge TTL:</strong> The matrix store does not expose a per-edge validUntil field,
 * so edge TTL expiry is not performed in this sweep. Edges connected to hard-deleted nodes
 * are implicitly cleaned up when those nodes are removed.</p>
 */
@Slf4j
@Component
public class TtlSweepExecutor {

    /** Default grace period in days before stale items are permanently deleted. */
    private static final int DEFAULT_GRACE_DAYS = 7;

    /** Metadata key for node TTL: epoch-millisecond string after which the node expires. */
    static final String META_VALID_UNTIL = "_validUntil";

    private KnowledgeGraphService knowledgeGraphService;

    @Autowired
    public TtlSweepExecutor(KnowledgeGraphService knowledgeGraphService) {
        this.knowledgeGraphService = knowledgeGraphService;
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
    public TaskReport execute(Long factSheetId, TtlPolicy policy, boolean dryRun) {
        Instant start = Instant.now();
        Instant now = Instant.now();
        int scanned = 0;
        int affected = 0;
        int skipped = 0;
        List<String> warnings = new ArrayList<>();

        // ── 1. Find expired nodes via _validUntil metadata ──────────────────────
        List<GraphNode> allNodes = knowledgeGraphService.getNodesInFactSheet(factSheetId);
        List<GraphNode> expiredNodes = new ArrayList<>();
        for (GraphNode node : allNodes) {
            Instant validUntil = extractValidUntil(node);
            if (validUntil != null && validUntil.isBefore(now)) {
                expiredNodes.add(node);
            }
        }
        scanned += allNodes.size();
        log.debug("TTL sweep: {} expired nodes found for factSheet={}", expiredNodes.size(), factSheetId);

        // Build a minimal ReasoningGraph projecting validUntil as timestamp for policy evaluation
        MutableReasoningGraph nodeGraph = buildNodeGraph(expiredNodes, now);
        PruneResult nodeDecision = new StalenessPruningPolicy(now).evaluate(nodeGraph);

        List<String> nodeIdsToSoftDelete = new ArrayList<>();
        List<String> nodeIdsToHardDelete = new ArrayList<>();

        for (GraphNode node : expiredNodes) {
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
                    if (!dryRun) nodeIdsToSoftDelete.add(node.getNodeId());
                    affected++;
                }
                case HARD_DELETE -> {
                    if (!dryRun) nodeIdsToHardDelete.add(node.getNodeId());
                    affected++;
                }
                default -> warnings.add("Unknown TtlAction for node " + node.getNodeId());
            }
        }

        if (!dryRun) {
            // Soft-delete: mark stale via pruneNodes (which sets _stale + _staleAt metadata)
            if (!nodeIdsToSoftDelete.isEmpty()) {
                knowledgeGraphService.pruneNodes(nodeIdsToSoftDelete, true, null, false);
            }
            // Hard-delete immediately (no grace, remove at once)
            if (!nodeIdsToHardDelete.isEmpty()) {
                knowledgeGraphService.pruneNodes(nodeIdsToHardDelete, false, Duration.ZERO, false);
            }
        }

        // ── 2. Hard-delete previously stale items past grace period ──────────────
        if (!dryRun) {
            Duration grace = Duration.ofDays(DEFAULT_GRACE_DAYS);
            knowledgeGraphService.hardDeleteStaleNodes(factSheetId, grace);
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

    /** Extract the {@code _validUntil} epoch-millis from node metadata, or {@code null} if absent. */
    private static Instant extractValidUntil(GraphNode node) {
        if (node == null) return null;
        // Prefer the POJO field if populated (JPA path); fall back to metadata for matrix nodes.
        if (node.getValidUntil() != null) {
            return node.getValidUntil().toInstant(java.time.ZoneOffset.UTC);
        }
        Map<String, Object> meta = node.getMetadata();
        if (meta == null) return null;
        Object val = meta.get(META_VALID_UNTIL);
        if (val == null) return null;
        try {
            return Instant.ofEpochMilli(Long.parseLong(val.toString()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Build a minimal ReasoningGraph from the given expired nodes, projecting
     * {@code validUntil} as each entity's timestamp, so {@link StalenessPruningPolicy}
     * can evaluate whether each entity's "expiry timestamp" is before the supplied cutoff.
     */
    private static MutableReasoningGraph buildNodeGraph(List<GraphNode> nodes, Instant cutoff) {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        for (GraphNode n : nodes) {
            Instant ts = extractValidUntil(n);
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
