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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link NamedGraphServiceImpl}, covering CRUD, hierarchy navigation,
 * node membership, cycle detection, and statistics.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NamedGraphServiceImplTest {

    @Mock
    private NamedGraphRepository namedGraphRepository;

    @Mock
    private GraphNodeRepository graphNodeRepository;

    private NamedGraphServiceImpl service;

    /**
     * In-memory store so findByGraphId reflects whatever was saved.
     */
    private final Map<String, NamedGraph> savedGraphs = new ConcurrentHashMap<>();

    /**
     * In-memory store for nodes.
     */
    private final Map<String, GraphNode> savedNodes = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        service = new NamedGraphServiceImpl(namedGraphRepository, graphNodeRepository);
        savedGraphs.clear();
        savedNodes.clear();

        // save() → assign a DB id, generate graphId via @PrePersist logic, store in map
        when(namedGraphRepository.save(any(NamedGraph.class))).thenAnswer(inv -> {
            NamedGraph g = inv.getArgument(0);
            if (g.getId() == null) {
                g.setId((long) (Math.random() * 100_000));
            }
            if (g.getGraphId() == null) {
                g.setGraphId(UUID.randomUUID().toString());
            }
            if (g.getCreatedAt() == null) {
                g.setCreatedAt(LocalDateTime.now());
            }
            if (g.getUpdatedAt() == null) {
                g.setUpdatedAt(LocalDateTime.now());
            }
            // default counts
            if (g.getNodeCount() == null) g.setNodeCount(0);
            if (g.getEdgeCount() == null) g.setEdgeCount(0);
            if (g.getChildGraphCount() == null) g.setChildGraphCount(0);
            savedGraphs.put(g.getGraphId(), g);
            return g;
        });

        // findByGraphId → look up in map
        when(namedGraphRepository.findByGraphId(anyString())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            return Optional.ofNullable(savedGraphs.get(id));
        });

        // findByParentGraphIsNull → all graphs where parentGraph == null
        when(namedGraphRepository.findByParentGraphIsNull()).thenAnswer(inv ->
            savedGraphs.values().stream()
                .filter(g -> g.getParentGraph() == null)
                .toList()
        );

        // findByParentGraph → all graphs whose parentGraph reference equals the given parent
        when(namedGraphRepository.findByParentGraph(any(NamedGraph.class))).thenAnswer(inv -> {
            NamedGraph parent = inv.getArgument(0);
            return savedGraphs.values().stream()
                .filter(g -> g.getParentGraph() != null
                        && g.getParentGraph().getGraphId().equals(parent.getGraphId()))
                .toList();
        });

        // delete → remove from map
        doAnswer(inv -> {
            NamedGraph g = inv.getArgument(0);
            savedGraphs.remove(g.getGraphId());
            return null;
        }).when(namedGraphRepository).delete(any(NamedGraph.class));

        // findByNameContainingIgnoreCase → substring match on name
        when(namedGraphRepository.findByNameContainingIgnoreCase(anyString())).thenAnswer(inv -> {
            String q = ((String) inv.getArgument(0)).toLowerCase();
            return savedGraphs.values().stream()
                .filter(g -> g.getName() != null && g.getName().toLowerCase().contains(q))
                .toList();
        });

        // node repository
        when(graphNodeRepository.findByNodeId(anyString())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            return Optional.ofNullable(savedNodes.get(id));
        });
        when(graphNodeRepository.save(any(GraphNode.class))).thenAnswer(inv -> {
            GraphNode n = inv.getArgument(0);
            if (n.getNodeId() != null) savedNodes.put(n.getNodeId(), n);
            return n;
        });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CREATE
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testCreateRootGraph() {
        NamedGraph result = service.createGraph("Root", null, null, null, null);

        assertNotNull(result);
        assertNotNull(result.getGraphId());
        assertEquals("Root", result.getName());
        assertNull(result.getParentGraph());
        assertNotNull(result.getCreatedAt());
        assertNotNull(result.getUpdatedAt());
        verify(namedGraphRepository, atLeastOnce()).save(any(NamedGraph.class));
    }

    @Test
    void testCreateChildGraph() {
        NamedGraph parent = service.createGraph("Parent", null, null, null, null);
        String parentId = parent.getGraphId();

        NamedGraph child = service.createGraph("Child", null, parentId, null, null);

        assertNotNull(child);
        assertEquals("Child", child.getName());
        assertNotNull(child.getParentGraph());
        assertEquals(parentId, child.getParentGraph().getGraphId());

        // parent's childGraphCount should have been incremented
        assertEquals(1, parent.getChildGraphCount());
    }

    @Test
    void testCreateGraphWithAllFields() {
        NamedGraph result = service.createGraph(
            "Full Graph", "A detailed description", null, 42L, "domain_ontology");

        assertEquals("Full Graph", result.getName());
        assertEquals("A detailed description", result.getDescription());
        assertNull(result.getParentGraph());
        assertEquals(42L, result.getFactSheetId());
        assertEquals("domain_ontology", result.getOntologyType());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // READ
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testGetGraphByGraphId() {
        NamedGraph saved = service.createGraph("Known", null, null, null, null);
        String graphId = saved.getGraphId();

        Optional<NamedGraph> result = service.getGraph(graphId);

        assertTrue(result.isPresent());
        assertEquals(graphId, result.get().getGraphId());
        assertEquals("Known", result.get().getName());
    }

    @Test
    void testGetGraphNotFound() {
        Optional<NamedGraph> result = service.getGraph("nonexistent-uuid");

        assertFalse(result.isPresent());
    }

    @Test
    void testGetRootGraphs() {
        NamedGraph root1 = service.createGraph("Root1", null, null, null, null);
        NamedGraph root2 = service.createGraph("Root2", null, null, null, null);
        // child of root1 — should NOT appear in root list
        service.createGraph("Child", null, root1.getGraphId(), null, null);

        List<NamedGraph> roots = service.getRootGraphs();

        assertEquals(2, roots.size());
        assertTrue(roots.stream().anyMatch(g -> g.getGraphId().equals(root1.getGraphId())));
        assertTrue(roots.stream().anyMatch(g -> g.getGraphId().equals(root2.getGraphId())));
    }

    @Test
    void testGetChildGraphs() {
        NamedGraph parent = service.createGraph("Parent", null, null, null, null);
        NamedGraph child1 = service.createGraph("Child1", null, parent.getGraphId(), null, null);
        NamedGraph child2 = service.createGraph("Child2", null, parent.getGraphId(), null, null);

        List<NamedGraph> children = service.getChildGraphs(parent.getGraphId());

        assertEquals(2, children.size());
        assertTrue(children.stream().anyMatch(g -> g.getGraphId().equals(child1.getGraphId())));
        assertTrue(children.stream().anyMatch(g -> g.getGraphId().equals(child2.getGraphId())));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HIERARCHY
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testGetGraphHierarchy() {
        NamedGraph root = service.createGraph("Root", "root desc", null, null, null);
        NamedGraph child = service.createGraph("Child", null, root.getGraphId(), null, null);
        NamedGraph grandchild = service.createGraph("Grandchild", null, child.getGraphId(), null, null);

        Map<String, Object> hierarchy = service.getGraphHierarchy(root.getGraphId(), 3);

        assertEquals(root.getGraphId(), hierarchy.get("graphId"));
        assertEquals("Root", hierarchy.get("name"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) hierarchy.get("children");
        assertEquals(1, children.size());
        assertEquals("Child", children.get(0).get("name"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> grandchildren = (List<Map<String, Object>>) children.get(0).get("children");
        assertEquals(1, grandchildren.size());
        assertEquals("Grandchild", grandchildren.get(0).get("name"));
    }

    @Test
    void testGetGraphHierarchyRespectsMaxDepth() {
        NamedGraph root = service.createGraph("Root", null, null, null, null);
        NamedGraph child = service.createGraph("Child", null, root.getGraphId(), null, null);
        service.createGraph("Grandchild", null, child.getGraphId(), null, null);

        // maxDepth=1 should include direct children but not grandchildren
        Map<String, Object> hierarchy = service.getGraphHierarchy(root.getGraphId(), 1);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) hierarchy.get("children");
        assertEquals(1, children.size());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> grandchildren = (List<Map<String, Object>>) children.get(0).get("children");
        assertTrue(grandchildren.isEmpty());
    }

    @Test
    void testGetAncestors() {
        NamedGraph root = service.createGraph("Root", null, null, null, null);
        NamedGraph mid = service.createGraph("Mid", null, root.getGraphId(), null, null);
        NamedGraph leaf = service.createGraph("Leaf", null, mid.getGraphId(), null, null);

        List<NamedGraph> ancestors = service.getAncestors(leaf.getGraphId());

        // Should return [root, mid] — root-first, excluding leaf itself
        assertEquals(2, ancestors.size());
        assertEquals(root.getGraphId(), ancestors.get(0).getGraphId());
        assertEquals(mid.getGraphId(), ancestors.get(1).getGraphId());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UPDATE
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testUpdateGraph() {
        NamedGraph original = service.createGraph("Original Name", "Old desc", null, null, null);
        String graphId = original.getGraphId();

        NamedGraph updated = service.updateGraph(graphId, "New Name", "New desc", "{\"key\":\"val\"}");

        assertEquals("New Name", updated.getName());
        assertEquals("New desc", updated.getDescription());
        assertEquals("{\"key\":\"val\"}", updated.getMetadataJson());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DELETE
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testDeleteRootGraph() {
        NamedGraph graph = service.createGraph("ToDelete", null, null, null, null);
        String graphId = graph.getGraphId();

        service.deleteGraph(graphId);

        assertFalse(service.getGraph(graphId).isPresent());
        verify(namedGraphRepository).delete(argThat(g -> g.getGraphId().equals(graphId)));
    }

    @Test
    void testDeleteGraphCascadesChildren() {
        NamedGraph parent = service.createGraph("Parent", null, null, null, null);
        NamedGraph child1 = service.createGraph("Child1", null, parent.getGraphId(), null, null);
        NamedGraph child2 = service.createGraph("Child2", null, parent.getGraphId(), null, null);

        service.deleteGraph(parent.getGraphId());

        assertFalse(service.getGraph(parent.getGraphId()).isPresent());
        // children should also have been deleted from the saved map
        assertFalse(savedGraphs.containsKey(child1.getGraphId()));
        assertFalse(savedGraphs.containsKey(child2.getGraphId()));
    }

    @Test
    void testDeleteGraphDecrementsParentCount() {
        NamedGraph parent = service.createGraph("Parent", null, null, null, null);
        NamedGraph child = service.createGraph("Child", null, parent.getGraphId(), null, null);

        // After creating the child the parent count is 1
        assertEquals(1, parent.getChildGraphCount());

        service.deleteGraph(child.getGraphId());

        // After deleting the child the parent count should be decremented back to 0
        assertEquals(0, parent.getChildGraphCount());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MOVE
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testMoveGraphToNewParent() {
        NamedGraph parentA = service.createGraph("ParentA", null, null, null, null);
        NamedGraph parentB = service.createGraph("ParentB", null, null, null, null);
        NamedGraph child = service.createGraph("Child", null, parentA.getGraphId(), null, null);

        assertEquals(1, parentA.getChildGraphCount());
        assertEquals(0, parentB.getChildGraphCount());

        service.moveGraph(child.getGraphId(), parentB.getGraphId());

        assertEquals(0, parentA.getChildGraphCount());
        assertEquals(1, parentB.getChildGraphCount());
        assertEquals(parentB.getGraphId(), child.getParentGraph().getGraphId());
    }

    @Test
    void testMoveGraphToRoot() {
        NamedGraph parent = service.createGraph("Parent", null, null, null, null);
        NamedGraph child = service.createGraph("Child", null, parent.getGraphId(), null, null);

        service.moveGraph(child.getGraphId(), null);

        assertNull(child.getParentGraph());
        assertEquals(0, parent.getChildGraphCount());
    }

    @Test
    void testMoveGraphDetectsCycle() {
        NamedGraph root = service.createGraph("Root", null, null, null, null);
        NamedGraph child = service.createGraph("Child", null, root.getGraphId(), null, null);

        // Trying to move root under its own child is a cycle
        assertThrows(IllegalArgumentException.class, () ->
            service.moveGraph(root.getGraphId(), child.getGraphId()));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SEARCH
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testSearchGraphs() {
        service.createGraph("Alpha Graph", null, null, null, null);
        service.createGraph("Beta Graph", null, null, null, null);
        service.createGraph("Gamma Unrelated", null, null, null, null);

        List<NamedGraph> results = service.searchGraphs("graph");

        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(g -> g.getName().toLowerCase().contains("graph")));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NODE MEMBERSHIP
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testLinkNodeToGraph() {
        NamedGraph graph = service.createGraph("MyGraph", null, null, null, null);
        GraphNode node = GraphNode.builder()
            .nodeId("node-1")
            .build();
        savedNodes.put("node-1", node);

        service.linkNodeToGraph("node-1", graph.getGraphId());

        assertEquals(graph.getGraphId(), node.getNamedGraphId());
        assertEquals(1, graph.getNodeCount());
    }

    @Test
    void testUnlinkNodeFromGraph() {
        NamedGraph graph = service.createGraph("MyGraph", null, null, null, null);
        GraphNode node = GraphNode.builder()
            .nodeId("node-2")
            .build();
        node.setNamedGraphId(graph.getGraphId());
        savedNodes.put("node-2", node);

        // Manually set the graph's nodeCount to 1 to simulate a linked state
        graph.setNodeCount(1);

        service.unlinkNodeFromGraph("node-2", graph.getGraphId());

        assertNull(node.getNamedGraphId());
        assertEquals(0, graph.getNodeCount());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATISTICS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testGetGraphStatistics() {
        NamedGraph graph = service.createGraph("StatsGraph", "desc", null, 7L, "taxonomy");
        graph.setNodeCount(15);
        graph.setEdgeCount(30);
        graph.setChildGraphCount(3);

        Map<String, Object> stats = service.getGraphStatistics(graph.getGraphId());

        assertEquals(graph.getGraphId(), stats.get("graphId"));
        assertEquals("StatsGraph", stats.get("name"));
        assertEquals(15, stats.get("nodeCount"));
        assertEquals(30, stats.get("edgeCount"));
        assertEquals(3, stats.get("childGraphCount"));
        assertTrue((Boolean) stats.get("isRoot"));
        assertEquals("taxonomy", stats.get("ontologyType"));
        assertEquals(7L, stats.get("factSheetId"));
    }
}
