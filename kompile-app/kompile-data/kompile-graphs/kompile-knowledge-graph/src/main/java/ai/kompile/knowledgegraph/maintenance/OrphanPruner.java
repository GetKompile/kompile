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

import ai.kompile.core.graphrag.maintenance.model.GraphPruneResult;
import ai.kompile.core.graphrag.maintenance.model.MaintenanceTask;
import ai.kompile.core.graphrag.maintenance.model.OrphanScanResult;
import ai.kompile.core.graphrag.maintenance.model.TaskReport;
import ai.kompile.graph.reasoning.maintenance.OrphanPruningPolicy;
import ai.kompile.graph.reasoning.maintenance.PruneResult;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Finds and removes orphan entity nodes — ENTITY-level nodes that have zero
 * edges in the knowledge graph (neither as source nor as target).
 *
 * <p>Orphans are soft-deleted first; a subsequent sweep (or the grace-period
 * hard-delete path in {@link TtlSweepExecutor}) will permanently remove them
 * once the grace period has passed.</p>
 *
 * <p>The pruning <em>decision</em> is now delegated to the generic
 * {@link OrphanPruningPolicy} from {@code kompile-graph-maintenance}: a
 * store-agnostic {@link ai.kompile.graph.reasoning.model.ReasoningGraph} is
 * built from the fact sheet's entities and edges, the policy identifies the
 * orphan ids, and then the deletions are applied here via
 * {@link KnowledgeGraphService} (the store-specific step that stays in this
 * module).</p>
 */
@Slf4j
@Component
public class OrphanPruner {

    /** Default grace period in days before previously-stale orphans are hard-deleted. */
    private static final int DEFAULT_GRACE_DAYS = 7;

    private final KnowledgeGraphService knowledgeGraphService;

    @Autowired
    public OrphanPruner(KnowledgeGraphService knowledgeGraphService) {
        this.knowledgeGraphService = knowledgeGraphService;
    }

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected OrphanPruner() {
        this.knowledgeGraphService = null;
    }

    /**
     * Scan for orphan entity nodes without persisting any changes.
     *
     * @param factSheetId the fact sheet to scan
     * @return a summary of orphan entities found
     */
    public OrphanScanResult scan(Long factSheetId) {
        PruneResult decision = evaluatePolicy(factSheetId);
        List<String> orphanNodeIds = new ArrayList<>(decision.entityIds());
        long totalActive = knowledgeGraphService.countActiveNodes(factSheetId);

        Map<String, String> orphanDetails = new LinkedHashMap<>();
        for (String nodeId : orphanNodeIds) {
            knowledgeGraphService.getNode(nodeId).ifPresent(node ->
                    orphanDetails.put(nodeId,
                            "title=" + node.getTitle() + ", confidence=" + node.getConfidence()));
        }

        log.info("Orphan scan: found {}/{} orphan entities for factSheet={}",
                orphanNodeIds.size(), totalActive, factSheetId);

        return new OrphanScanResult(orphanNodeIds, (int) totalActive, orphanNodeIds.size(), orphanDetails);
    }

    /**
     * Execute orphan pruning for the given fact sheet.
     *
     * <p>Step 1 — soft-deletes newly discovered orphans (marks them stale)
     * via {@link OrphanPruningPolicy}.
     * Step 2 — hard-deletes orphans that were previously marked stale and have
     * exceeded the specified grace period.</p>
     *
     * @param factSheetId the fact sheet to prune
     * @param gracePeriod how long a stale orphan is retained before hard deletion
     * @param dryRun      when {@code true} no writes are performed
     * @return a {@link TaskReport} summarising the operation
     */
    public TaskReport execute(Long factSheetId, Duration gracePeriod, boolean dryRun) {
        Instant start = Instant.now();
        List<String> warnings = new ArrayList<>();

        // ── Step 1: Delegate orphan DECISION to generic policy ───────────────────
        PruneResult decision = evaluatePolicy(factSheetId);
        // Filter to ENTITY-level nodes only: evaluatePolicy now adds DOCUMENT/TABLE structural
        // anchors to prevent ENTITY nodes connected via CONTAINS edges from being orphaned.
        // Those anchor node IDs carry the "document_" / "table_" prefix and must NOT be pruned
        // here (they are structural, not content orphans, and are handled by SnapshotManager).
        List<String> orphanNodeIds = decision.entityIds().stream()
                .filter(id -> id != null && id.startsWith(NodeLevel.ENTITY.name().toLowerCase() + "_"))
                .collect(Collectors.toList());
        // P3 diagnostic: log at INFO so the node-count change is attributable in the crawl log.
        // Previously at DEBUG, making it invisible during production crawls and masking large drops.
        log.info("OrphanPruner: {} orphan ENTITY nodes found for factSheet={} (these will be soft-deleted; "
                        + "previously-stale nodes past grace period will be hard-deleted in step 2)",
                orphanNodeIds.size(), factSheetId);

        // ── Step 1b: Apply deletions via store API (KG-specific, stays here) ─────
        GraphPruneResult softDeleteResult = knowledgeGraphService.pruneNodes(
                orphanNodeIds,
                /* softDelete= */ true,
                gracePeriod,
                dryRun);

        if (!dryRun && softDeleteResult.affectedCount() > 0) {
            log.info("OrphanPruner: marked {} orphan entities as stale for factSheet={}",
                    softDeleteResult.affectedCount(), factSheetId);
        }

        // ── Step 2: Hard-delete previously stale orphans past grace period ────────
        Duration effectiveGrace = gracePeriod != null
                ? gracePeriod
                : Duration.ofDays(DEFAULT_GRACE_DAYS);

        GraphPruneResult hardDeleteResult = GraphPruneResult.empty(dryRun);
        if (!dryRun) {
            hardDeleteResult = knowledgeGraphService.hardDeleteStaleNodes(factSheetId, effectiveGrace);
            if (hardDeleteResult.hardDeleted() > 0) {
                log.info("OrphanPruner: hard-deleted {} stale nodes past grace period ({} days) for factSheet={}",
                        hardDeleteResult.hardDeleted(), effectiveGrace.toDays(), factSheetId);
            }
        }

        int scanned = orphanNodeIds.size();
        int affected = softDeleteResult.affectedCount();
        int skipped = 0;

        log.info("OrphanPruner (dryRun={}): scanned={}, affected={}, skipped={} for factSheet={}",
                dryRun, scanned, affected, skipped, factSheetId);

        return new TaskReport(
                MaintenanceTask.ORPHAN_CLEANUP,
                scanned,
                affected,
                skipped,
                warnings,
                Duration.between(start, Instant.now())
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build a {@link ai.kompile.graph.reasoning.model.ReasoningGraph} from the entity nodes and
     * edges in the given fact sheet, then delegate the orphan decision to
     * {@link OrphanPruningPolicy}.
     */
    private PruneResult evaluatePolicy(Long factSheetId) {
        MutableReasoningGraph graph = new MutableReasoningGraph();

        // Load all active ENTITY nodes
        knowledgeGraphService.getNodesByTypeInFactSheet(factSheetId, NodeLevel.ENTITY).stream()
                .filter(n -> !Boolean.TRUE.equals(n.getStale()))
                .forEach(n -> graph.addEntity(n.getNodeId(), nodeType(n), nodeLabel(n)));

        // Load DOCUMENT and TABLE structural anchors so that ENTITY nodes connected to them
        // via CONTAINS or HIERARCHICAL edges are NOT treated as orphans.
        // Without this, every ENTITY node that has only CONTAINS edges from a parent DOCUMENT
        // (but no entity-to-entity edges yet) is incorrectly classified as an orphan and
        // soft-deleted — causing the node count to plummet during early/cold-start crawls where
        // cross-document shared-entity edges have not yet been computed.
        for (NodeLevel anchorLevel : List.of(NodeLevel.DOCUMENT, NodeLevel.TABLE)) {
            knowledgeGraphService.getNodesByTypeInFactSheet(factSheetId, anchorLevel).stream()
                    .filter(n -> !Boolean.TRUE.equals(n.getStale()))
                    .forEach(n -> graph.addEntity(n.getNodeId(), nodeType(n), nodeLabel(n)));
        }

        // Load edges (those whose endpoints are entity OR structural-anchor nodes we loaded)
        for (GraphEdge edge : knowledgeGraphService.getEdgesInFactSheet(factSheetId)) {
            if (edge.getSourceNode() == null || edge.getTargetNode() == null) continue;
            String src = edge.getSourceNode().getNodeId();
            String tgt = edge.getTargetNode().getNodeId();
            if (graph.containsEntity(src) && graph.containsEntity(tgt)) {
                graph.addRelation(edge.getEdgeId() != null ? edge.getEdgeId() : (src + "->" + tgt),
                        src, tgt,
                        edge.getEdgeType() != null ? edge.getEdgeType().name() : "",
                        edge.getWeight() != null ? edge.getWeight() : 1.0);
            }
        }

        return new OrphanPruningPolicy().evaluate(graph);
    }

    private static String nodeType(GraphNode n) {
        return n.getNodeType() != null ? n.getNodeType().name() : "";
    }

    private static String nodeLabel(GraphNode n) {
        return n.getTitle() != null ? n.getTitle() : n.getNodeId();
    }
}
