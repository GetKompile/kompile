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
package ai.kompile.knowledgegraph.impl;

import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NamedGraph;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import ai.kompile.knowledgegraph.repository.NamedGraphRepository;
import ai.kompile.knowledgegraph.service.NamedGraphService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Implementation of {@link NamedGraphService}.
 * Manages hierarchical named knowledge graphs, supporting tree navigation,
 * node membership, and recursive deletion.
 */
@Service
@Slf4j
public class NamedGraphServiceImpl implements NamedGraphService {

    private final NamedGraphRepository namedGraphRepository;
    private final GraphNodeRepository graphNodeRepository;

    @Autowired
    public NamedGraphServiceImpl(NamedGraphRepository namedGraphRepository,
                                 GraphNodeRepository graphNodeRepository) {
        this.namedGraphRepository = namedGraphRepository;
        this.graphNodeRepository = graphNodeRepository;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CRUD OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public NamedGraph createGraph(String name, String description, String parentGraphId,
                                  Long factSheetId, String ontologyType) {
        NamedGraph parent = null;
        if (parentGraphId != null && !parentGraphId.isBlank()) {
            parent = namedGraphRepository.findByGraphId(parentGraphId)
                .orElseThrow(() -> new IllegalArgumentException(
                    "Parent graph not found: " + parentGraphId));
        }

        NamedGraph graph = NamedGraph.builder()
            .name(name)
            .description(description)
            .parentGraph(parent)
            .factSheetId(factSheetId)
            .ontologyType(ontologyType)
            .build();

        NamedGraph saved = namedGraphRepository.save(graph);

        // Update parent's cached child count
        if (parent != null) {
            parent.incrementChildGraphCount();
            namedGraphRepository.save(parent);
        }

        log.debug("Created named graph '{}' with graphId={}", name, saved.getGraphId());
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<NamedGraph> getGraph(String graphId) {
        return namedGraphRepository.findByGraphId(graphId);
    }

    @Override
    @Transactional
    public NamedGraph updateGraph(String graphId, String name, String description, String metadataJson) {
        NamedGraph graph = requireGraph(graphId);

        if (name != null && !name.isBlank()) {
            graph.setName(name);
        }
        if (description != null) {
            graph.setDescription(description);
        }
        if (metadataJson != null) {
            graph.setMetadataJson(metadataJson);
        }

        return namedGraphRepository.save(graph);
    }

    @Override
    @Transactional
    public void deleteGraph(String graphId) {
        NamedGraph graph = requireGraph(graphId);
        NamedGraph parent = graph.getParentGraph();

        // Recursively delete all descendants first (depth-first)
        deleteDescendants(graph);

        namedGraphRepository.delete(graph);

        // Update parent's cached child count
        if (parent != null) {
            parent.decrementChildGraphCount();
            namedGraphRepository.save(parent);
        }

        log.debug("Deleted named graph graphId={}", graphId);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HIERARCHY NAVIGATION
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public List<NamedGraph> getRootGraphs() {
        return namedGraphRepository.findByParentGraphIsNull();
    }

    @Override
    @Transactional(readOnly = true)
    public List<NamedGraph> getChildGraphs(String parentGraphId) {
        NamedGraph parent = requireGraph(parentGraphId);
        return namedGraphRepository.findByParentGraph(parent);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getGraphHierarchy(String graphId, int maxDepth) {
        NamedGraph graph = requireGraph(graphId);
        return buildHierarchyMap(graph, maxDepth, 0);
    }

    @Override
    @Transactional(readOnly = true)
    public List<NamedGraph> getAncestors(String graphId) {
        NamedGraph graph = requireGraph(graphId);
        LinkedList<NamedGraph> ancestors = new LinkedList<>();

        NamedGraph current = graph.getParentGraph();
        while (current != null) {
            // Re-fetch to ensure the lazy parent is available
            final NamedGraph fetched = namedGraphRepository.findByGraphId(current.getGraphId())
                .orElse(null);
            if (fetched == null) break;
            ancestors.addFirst(fetched);
            current = fetched.getParentGraph();
        }

        return ancestors;
    }

    @Override
    @Transactional
    public NamedGraph moveGraph(String graphId, String newParentGraphId) {
        NamedGraph graph = requireGraph(graphId);
        NamedGraph oldParent = graph.getParentGraph();

        // Guard against cycles: the new parent must not be a descendant of this graph
        if (newParentGraphId != null) {
            if (graphId.equals(newParentGraphId)) {
                throw new IllegalArgumentException("A graph cannot be its own parent.");
            }
            List<String> descendantIds = collectDescendantIds(graph);
            if (descendantIds.contains(newParentGraphId)) {
                throw new IllegalArgumentException(
                    "Cannot move graph to one of its own descendants.");
            }
        }

        // Decrement old parent's child count
        if (oldParent != null) {
            oldParent.decrementChildGraphCount();
            namedGraphRepository.save(oldParent);
        }

        // Set new parent
        if (newParentGraphId == null || newParentGraphId.isBlank()) {
            graph.setParentGraph(null);
        } else {
            NamedGraph newParent = namedGraphRepository.findByGraphId(newParentGraphId)
                .orElseThrow(() -> new IllegalArgumentException(
                    "New parent graph not found: " + newParentGraphId));
            graph.setParentGraph(newParent);
            newParent.incrementChildGraphCount();
            namedGraphRepository.save(newParent);
        }

        return namedGraphRepository.save(graph);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SEARCH
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public List<NamedGraph> searchGraphs(String query) {
        if (query == null || query.isBlank()) {
            return namedGraphRepository.findByParentGraphIsNull();
        }
        return namedGraphRepository.findByNameContainingIgnoreCase(query);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NODE MEMBERSHIP
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public void linkNodeToGraph(String nodeId, String graphId) {
        GraphNode node = graphNodeRepository.findByNodeId(nodeId)
            .orElseThrow(() -> new IllegalArgumentException("Node not found: " + nodeId));
        NamedGraph graph = requireGraph(graphId);

        String previousGraphId = node.getNamedGraphId();
        node.setNamedGraphId(graphId);
        graphNodeRepository.save(node);

        // Increment count on new graph, decrement on old if different
        graph.incrementNodeCount();
        namedGraphRepository.save(graph);

        if (previousGraphId != null && !previousGraphId.equals(graphId)) {
            namedGraphRepository.findByGraphId(previousGraphId).ifPresent(prev -> {
                prev.decrementNodeCount();
                namedGraphRepository.save(prev);
            });
        }

        log.debug("Linked node {} to graph {}", nodeId, graphId);
    }

    @Override
    @Transactional
    public void unlinkNodeFromGraph(String nodeId, String graphId) {
        GraphNode node = graphNodeRepository.findByNodeId(nodeId)
            .orElseThrow(() -> new IllegalArgumentException("Node not found: " + nodeId));

        if (!graphId.equals(node.getNamedGraphId())) {
            throw new IllegalArgumentException(
                "Node " + nodeId + " is not linked to graph " + graphId);
        }

        node.setNamedGraphId(null);
        graphNodeRepository.save(node);

        NamedGraph graph = requireGraph(graphId);
        graph.decrementNodeCount();
        namedGraphRepository.save(graph);

        log.debug("Unlinked node {} from graph {}", nodeId, graphId);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATISTICS
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getGraphStatistics(String graphId) {
        NamedGraph graph = requireGraph(graphId);

        int depth = computeDepth(graph);
        long descendantCount = countDescendants(graph);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("graphId", graph.getGraphId());
        stats.put("name", graph.getName());
        stats.put("nodeCount", graph.getNodeCount());
        stats.put("edgeCount", graph.getEdgeCount());
        stats.put("childGraphCount", graph.getChildGraphCount());
        stats.put("descendantCount", descendantCount);
        stats.put("depth", depth);
        stats.put("isRoot", graph.getParentGraph() == null);
        stats.put("ontologyType", graph.getOntologyType());
        stats.put("factSheetId", graph.getFactSheetId());
        stats.put("createdAt", graph.getCreatedAt());
        stats.put("updatedAt", graph.getUpdatedAt());
        return stats;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private NamedGraph requireGraph(String graphId) {
        return namedGraphRepository.findByGraphId(graphId)
            .orElseThrow(() -> new IllegalArgumentException("Named graph not found: " + graphId));
    }

    /**
     * Recursively build a nested hierarchy map up to maxDepth.
     */
    private Map<String, Object> buildHierarchyMap(NamedGraph graph, int maxDepth, int currentDepth) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("graphId", graph.getGraphId());
        node.put("name", graph.getName());
        node.put("description", graph.getDescription());
        node.put("ontologyType", graph.getOntologyType());
        node.put("nodeCount", graph.getNodeCount());
        node.put("edgeCount", graph.getEdgeCount());
        node.put("childGraphCount", graph.getChildGraphCount());
        node.put("factSheetId", graph.getFactSheetId());
        node.put("createdAt", graph.getCreatedAt());
        node.put("updatedAt", graph.getUpdatedAt());

        if (currentDepth < maxDepth) {
            List<NamedGraph> children = namedGraphRepository.findByParentGraph(graph);
            List<Map<String, Object>> childMaps = new ArrayList<>();
            for (NamedGraph child : children) {
                childMaps.add(buildHierarchyMap(child, maxDepth, currentDepth + 1));
            }
            node.put("children", childMaps);
        } else {
            node.put("children", Collections.emptyList());
        }

        return node;
    }

    /**
     * Recursively delete all descendant graphs.
     */
    private void deleteDescendants(NamedGraph graph) {
        List<NamedGraph> children = namedGraphRepository.findByParentGraph(graph);
        for (NamedGraph child : children) {
            deleteDescendants(child);
            namedGraphRepository.delete(child);
        }
    }

    /**
     * Collect all descendant graphIds (for cycle detection during reparenting).
     */
    private List<String> collectDescendantIds(NamedGraph graph) {
        List<String> ids = new ArrayList<>();
        collectDescendantIdsRecursive(graph, ids);
        return ids;
    }

    private void collectDescendantIdsRecursive(NamedGraph graph, List<String> ids) {
        List<NamedGraph> children = namedGraphRepository.findByParentGraph(graph);
        for (NamedGraph child : children) {
            ids.add(child.getGraphId());
            collectDescendantIdsRecursive(child, ids);
        }
    }

    /**
     * Compute the depth of the graph in the hierarchy (root = 0).
     */
    private int computeDepth(NamedGraph graph) {
        int depth = 0;
        NamedGraph current = graph.getParentGraph();
        while (current != null) {
            depth++;
            NamedGraph fetched = namedGraphRepository.findByGraphId(current.getGraphId())
                .orElse(null);
            current = fetched != null ? fetched.getParentGraph() : null;
        }
        return depth;
    }

    /**
     * Count all descendants of a graph.
     */
    private long countDescendants(NamedGraph graph) {
        long count = 0;
        List<NamedGraph> children = namedGraphRepository.findByParentGraph(graph);
        for (NamedGraph child : children) {
            count += 1 + countDescendants(child);
        }
        return count;
    }
}
