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
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.repository.GraphEdgeRepository;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Prunes tiny disconnected components from the knowledge graph.
 *
 * <p>Algorithm:</p>
 * <ol>
 *   <li>Load all active entity nodes and non-stale edges for the fact sheet.</li>
 *   <li>Build an in-memory adjacency map over entity-node UUIDs.</li>
 *   <li>Find all connected components via breadth-first search.</li>
 *   <li>Soft-delete the nodes in any component smaller than
 *       {@link ComponentPrunePolicy#minComponentSize()}, unless a pinned node is
 *       present and {@link ComponentPrunePolicy#keepPinned()} is {@code true}.</li>
 * </ol>
 */
@Slf4j
@Component
public class ComponentPruner {

    private  GraphNodeRepository nodeRepository;
    private  GraphEdgeRepository edgeRepository;

    public ComponentPruner(GraphNodeRepository nodeRepository,
                           GraphEdgeRepository edgeRepository) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
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
        LocalDateTime now = LocalDateTime.now();
        int scanned = 0;
        int affected = 0;
        int skipped = 0;
        List<String> warnings = new ArrayList<>();

        // ── 1. Load all active entities ──────────────────────────────────────────
        List<GraphNode> activeEntities = nodeRepository.findActiveEntities(factSheetId);
        scanned = activeEntities.size();
        log.debug("ComponentPruner: {} active entities for factSheet={}", scanned, factSheetId);

        if (activeEntities.isEmpty()) {
            return new TaskReport(MaintenanceTask.COMPONENT_PRUNE, 0, 0, 0, warnings,
                    Duration.between(start, Instant.now()));
        }

        // Index nodeId → GraphNode for fast lookup
        Map<String, GraphNode> nodeById = new HashMap<>();
        for (GraphNode n : activeEntities) {
            nodeById.put(n.getNodeId(), n);
        }

        // ── 2. Build adjacency map ───────────────────────────────────────────────
        Map<String, Set<String>> adjacency = new HashMap<>();
        for (String id : nodeById.keySet()) {
            adjacency.put(id, new HashSet<>());
        }

        List<GraphEdge> activeEdges = edgeRepository.findActiveEdges(factSheetId);
        for (GraphEdge edge : activeEdges) {
            if (edge.getSourceNode() == null || edge.getTargetNode() == null) continue;
            String src = edge.getSourceNode().getNodeId();
            String tgt = edge.getTargetNode().getNodeId();
            // Only include edges where both endpoints are active entities we loaded
            if (adjacency.containsKey(src) && adjacency.containsKey(tgt)) {
                adjacency.get(src).add(tgt);
                adjacency.get(tgt).add(src);
            }
        }

        // ── 3. Find connected components via BFS ─────────────────────────────────
        Set<String> visited = new HashSet<>();
        List<Set<String>> components = new ArrayList<>();

        for (String startId : nodeById.keySet()) {
            if (visited.contains(startId)) continue;
            Set<String> component = new HashSet<>();
            Queue<String> queue = new ArrayDeque<>();
            queue.add(startId);
            visited.add(startId);
            while (!queue.isEmpty()) {
                String current = queue.poll();
                component.add(current);
                for (String neighbour : adjacency.getOrDefault(current, Set.of())) {
                    if (!visited.contains(neighbour)) {
                        visited.add(neighbour);
                        queue.add(neighbour);
                    }
                }
            }
            components.add(component);
        }

        log.debug("ComponentPruner: found {} components for factSheet={}", components.size(), factSheetId);

        // ── 4. Soft-delete nodes in small components ─────────────────────────────
        List<Long> toMark = new ArrayList<>();

        for (Set<String> component : components) {
            if (component.size() >= policy.minComponentSize()) continue;

            // Check pinned nodes
            if (policy.keepPinned()) {
                boolean hasPinned = component.stream()
                        .map(nodeById::get)
                        .anyMatch(n -> n != null && Boolean.TRUE.equals(n.getUserPinned()));
                if (hasPinned) {
                    skipped += component.size();
                    log.debug("ComponentPruner: skipping small component of size {} containing pinned node",
                            component.size());
                    continue;
                }
            }

            for (String nodeId : component) {
                GraphNode node = nodeById.get(nodeId);
                if (node != null) {
                    toMark.add(node.getId());
                    affected++;
                }
            }
        }

        if (!dryRun && !toMark.isEmpty()) {
            nodeRepository.bulkMarkStale(toMark, now);
            log.info("ComponentPruner: marked {} nodes in small components as stale for factSheet={}",
                    toMark.size(), factSheetId);
        }

        log.info("ComponentPruner (dryRun={}): scanned={}, affected={}, skipped={}, components={} for factSheet={}",
                dryRun, scanned, affected, skipped, components.size(), factSheetId);

        return new TaskReport(
                MaintenanceTask.COMPONENT_PRUNE,
                scanned,
                affected,
                skipped,
                warnings,
                Duration.between(start, Instant.now())
        );
    }
}
