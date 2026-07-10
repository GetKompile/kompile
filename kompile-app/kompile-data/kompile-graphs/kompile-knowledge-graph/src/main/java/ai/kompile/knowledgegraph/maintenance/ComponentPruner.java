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

import ai.kompile.core.graphrag.maintenance.model.ComponentPrunePolicy;
import ai.kompile.core.graphrag.maintenance.model.MaintenanceTask;
import ai.kompile.core.graphrag.maintenance.model.TaskReport;
import ai.kompile.graph.reasoning.maintenance.ComponentPruningPolicy;
import ai.kompile.graph.reasoning.maintenance.PruneResult;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Prunes tiny disconnected components from the knowledge graph.
 *
 * <p>The pruning <em>decision</em> is delegated to the generic
 * {@link ComponentPruningPolicy} from {@code kompile-graph-maintenance}: a
 * store-agnostic {@link ai.kompile.graph.reasoning.model.ReasoningGraph} is
 * built from the entity nodes and edges in the fact sheet, the policy
 * identifies ids belonging to small components, and then the deletions are
 * applied here via {@link KnowledgeGraphService} (the store-specific step that
 * stays in this module).</p>
 *
 * <p>Operates entirely through {@link KnowledgeGraphService} so it runs against the live
 * {@code @Primary} matrix/vector store (soft-delete sets the {@code _stale} metadata marker via
 * {@link KnowledgeGraphService#pruneNodes}); it no longer reads or writes the JPA tables, which
 * are unpopulated on the live path.</p>
 *
 * <h3>Structure-aware connectivity (TABLE/DOCUMENT anchors)</h3>
 * <p>TABLE and DOCUMENT nodes are <em>structural anchors</em>: they are never themselves pruned
 * but they ARE added to the connectivity graph so that ENTITY (CELL) nodes reachable via
 * CONTAINS or HEADER_OF edges to a TABLE or DOCUMENT are not treated as singletons.
 * Only ENTITY nodes are candidates for the prune decision; structural anchors are excluded
 * from {@code toPrune} regardless of component size.</p>
 *
 * <h3>Metadata-level pin ({@code _pinned:true})</h3>
 * <p>On the live matrix/vector store path the JPA {@code userPinned} column is not populated.
 * The pinning contract uses the {@code _pinned:true} metadata marker written by
 * {@code ContentTypeRouter.persistGraphJson()} for TABLE and CELL nodes at creation time.
 * Both the JPA field ({@code userPinned=true}) and the metadata marker are honoured here.</p>
 */
@Slf4j
@Component
public class ComponentPruner {

    private KnowledgeGraphService knowledgeGraphService;

    @Autowired
    public ComponentPruner(KnowledgeGraphService knowledgeGraphService) {
        this.knowledgeGraphService = knowledgeGraphService;
    }

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected ComponentPruner() {}


    /**
     * Execute component-based pruning for the given fact sheet.
     *
     * @param factSheetId the fact sheet to prune
     * @param policy      size threshold and pinned-node behaviour
     * @param dryRun      when {@code true} no writes are performed
     * @return a {@link TaskReport} summarising the operation
     */
    @Transactional
    public TaskReport execute(Long factSheetId, ComponentPrunePolicy policy, boolean dryRun) {
        Instant start = Instant.now();
        int affected = 0;
        int skipped = 0;
        List<String> warnings = new ArrayList<>();

        // ── 1. Load all active (non-stale) ENTITY nodes — these are candidates for pruning ──
        List<GraphNode> activeEntities = knowledgeGraphService.getNodesByTypeInFactSheet(factSheetId, NodeLevel.ENTITY)
                .stream().filter(n -> !isStale(n)).collect(Collectors.toList());
        int scanned = activeEntities.size();
        log.debug("ComponentPruner: {} active entities for factSheet={}", scanned, factSheetId);

        if (activeEntities.isEmpty()) {
            return new TaskReport(MaintenanceTask.COMPONENT_PRUNE, 0, 0, 0, warnings,
                    Duration.between(start, Instant.now()));
        }

        // ── 2. Build a ReasoningGraph from entities + structural anchors (TABLE/DOCUMENT) ──
        // Structural anchors are added to the graph so ENTITY (CELL) nodes reachable via
        // CONTAINS/HEADER_OF edges to a TABLE or DOCUMENT are not treated as singletons.
        // The anchors are never themselves included in the prune-candidate set.
        MutableReasoningGraph graph = new MutableReasoningGraph();
        for (GraphNode n : activeEntities) {
            graph.addEntity(n.getNodeId(),
                    n.getNodeType() != null ? n.getNodeType().name() : "",
                    n.getTitle() != null ? n.getTitle() : n.getNodeId());
        }

        // Load TABLE and DOCUMENT structural anchors and register them in the graph so
        // CONTAINS / HEADER_OF edges to them make their ENTITY children look connected.
        Set<String> structuralAnchorIds = new HashSet<>();
        for (NodeLevel anchorType : new NodeLevel[]{NodeLevel.TABLE, NodeLevel.DOCUMENT}) {
            try {
                List<GraphNode> anchors = knowledgeGraphService.getNodesByTypeInFactSheet(factSheetId, anchorType);
                for (GraphNode anchor : anchors) {
                    if (!isStale(anchor)) {
                        graph.addEntity(anchor.getNodeId(),
                                anchor.getNodeType() != null ? anchor.getNodeType().name() : "",
                                anchor.getTitle() != null ? anchor.getTitle() : anchor.getNodeId());
                        structuralAnchorIds.add(anchor.getNodeId());
                    }
                }
                log.debug("ComponentPruner: loaded {} {} structural anchor(s) for factSheet={}",
                        anchors.size(), anchorType, factSheetId);
            } catch (Exception e) {
                log.warn("ComponentPruner: could not load {} nodes for factSheet={}: {} — " +
                        "proceeding without them (cells may appear as singletons)",
                        anchorType, factSheetId, e.getMessage());
            }
        }

        // Add edges — now both ENTITY↔ENTITY and ENTITY↔TABLE/DOCUMENT endpoints are in the graph
        List<GraphEdge> activeEdges = knowledgeGraphService.getEdgesInFactSheet(factSheetId);
        for (GraphEdge edge : activeEdges) {
            if (edge.getSourceNode() == null || edge.getTargetNode() == null) continue;
            String src = edge.getSourceNode().getNodeId();
            String tgt = edge.getTargetNode().getNodeId();
            if (graph.containsEntity(src) && graph.containsEntity(tgt)) {
                String relId = edge.getEdgeId() != null ? edge.getEdgeId() : (src + "->" + tgt);
                graph.addRelation(relId, src, tgt,
                        edge.getEdgeType() != null ? edge.getEdgeType().name() : "",
                        edge.getWeight() != null ? edge.getWeight() : 1.0);
            }
        }

        // ── 3. Delegate component DECISION to the generic policy ─────────────────
        ComponentPruningPolicy genericPolicy = new ComponentPruningPolicy(policy.minComponentSize());
        PruneResult decision = genericPolicy.evaluate(graph);
        log.debug("ComponentPruner: {} nodes in small components for factSheet={} (includes anchors in count)",
                decision.entityIds().size(), factSheetId);

        // ── 4. Apply pinned-node and structural-anchor exceptions ────────────────
        List<String> toPrune = new ArrayList<>();

        // Build a fast lookup: nodeId → GraphNode for pin checks
        Map<String, GraphNode> nodeById = new HashMap<>();
        for (GraphNode n : activeEntities) {
            nodeById.put(n.getNodeId(), n);
        }

        for (String entityId : decision.entityIds()) {
            // Never prune structural anchors (TABLE / DOCUMENT) — they are not ENTITY nodes
            // but the policy sees them because they were added to the graph above.
            if (structuralAnchorIds.contains(entityId)) {
                skipped++;
                continue;
            }

            GraphNode node = nodeById.get(entityId);
            if (policy.keepPinned() && node != null && isPinned(node)) {
                skipped++;
                log.debug("ComponentPruner: skipping pinned node {}", entityId);
                continue;
            }
            toPrune.add(entityId);
            affected++;
        }

        // ── 5. Apply soft-mark via store API (never hard-delete) ─────────────────
        knowledgeGraphService.pruneNodes(toPrune, true, null, dryRun);
        if (!dryRun && !toPrune.isEmpty()) {
            log.info("ComponentPruner: marked {} nodes in small components as stale for factSheet={}",
                    toPrune.size(), factSheetId);
        }

        log.info("ComponentPruner (dryRun={}): scanned={}, affected={}, skipped={} for factSheet={}",
                dryRun, scanned, affected, skipped, factSheetId);

        return new TaskReport(
                MaintenanceTask.COMPONENT_PRUNE,
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

    /** A node is stale if its domain flag or its live-store {@code _stale} metadata marker is set. */
    private static boolean isStale(GraphNode n) {
        if (Boolean.TRUE.equals(n.getStale())) {
            return true;
        }
        String metadataJson = n.getMetadataJson();
        if (metadataJson == null || metadataJson.isBlank()) {
            return false;
        }
        try {
            // Quick check without full parse: _stale:true marker
            return metadataJson.contains("\"_stale\"") && metadataJson.contains("true");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * A node is pinned if:
     * <ul>
     *   <li>The JPA {@code userPinned} flag is {@code true} (set by the REST pin endpoint), or</li>
     *   <li>The live-store metadata contains {@code "_pinned":true} (set at creation time by
     *       {@code ContentTypeRouter.persistGraphJson()} for TABLE and CELL nodes so they survive
     *       the component sweep even when embeddings / cross-doc edges are offline).</li>
     * </ul>
     */
    private static boolean isPinned(GraphNode n) {
        if (Boolean.TRUE.equals(n.getUserPinned())) {
            return true;
        }
        String metadataJson = n.getMetadataJson();
        if (metadataJson == null || metadataJson.isBlank()) {
            return false;
        }
        // Quick check without full parse: _pinned:true marker
        return metadataJson.contains("\"_pinned\"") && metadataJson.contains("true");
    }
}
