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
package ai.kompile.knowledgegraph.controller;

import ai.kompile.knowledgegraph.domain.NamedGraph;
import ai.kompile.knowledgegraph.service.NamedGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link NamedGraphController}.
 * Directly instantiates the controller with a mock service and asserts on
 * {@link ResponseEntity} status and body, following the same pattern as
 * {@code SetupStatusControllerTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NamedGraphControllerTest {

    @Mock
    private NamedGraphService namedGraphService;

    private NamedGraphController controller;

    @BeforeEach
    void setUp() {
        controller = new NamedGraphController(namedGraphService);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private NamedGraph buildGraph(String graphId, String name) {
        return NamedGraph.builder()
            .id(1L)
            .graphId(graphId)
            .name(name)
            .nodeCount(0)
            .edgeCount(0)
            .childGraphCount(0)
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // POST /api/graphs/
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testCreateGraph() {
        NamedGraph created = buildGraph("graph-123", "My Graph");
        when(namedGraphService.createGraph(eq("My Graph"), eq("desc"), isNull(), isNull(), eq("taxonomy")))
            .thenReturn(created);

        NamedGraphController.CreateGraphRequest request =
            new NamedGraphController.CreateGraphRequest("My Graph", "desc", null, null, "taxonomy");

        ResponseEntity<NamedGraph> response = controller.createGraph(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("graph-123", response.getBody().getGraphId());
        assertEquals("My Graph", response.getBody().getName());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GET /api/graphs/
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testListRootGraphs() {
        List<NamedGraph> roots = List.of(
            buildGraph("g1", "Root1"),
            buildGraph("g2", "Root2")
        );
        when(namedGraphService.getRootGraphs()).thenReturn(roots);

        ResponseEntity<List<NamedGraph>> response = controller.listGraphs(null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().size());
        verify(namedGraphService).getRootGraphs();
        verify(namedGraphService, never()).searchGraphs(any());
    }

    @Test
    void testListGraphsWithSearch() {
        List<NamedGraph> results = List.of(buildGraph("g3", "Test Graph"));
        when(namedGraphService.searchGraphs("test")).thenReturn(results);

        ResponseEntity<List<NamedGraph>> response = controller.listGraphs("test");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        verify(namedGraphService).searchGraphs("test");
        verify(namedGraphService, never()).getRootGraphs();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GET /api/graphs/{graphId}
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testGetGraph() {
        NamedGraph graph = buildGraph("abc-123", "Found Graph");
        when(namedGraphService.getGraph("abc-123")).thenReturn(Optional.of(graph));

        ResponseEntity<?> response = controller.getGraph("abc-123");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody() instanceof NamedGraph);
        assertEquals("abc-123", ((NamedGraph) response.getBody()).getGraphId());
    }

    @Test
    void testGetGraphNotFound() {
        when(namedGraphService.getGraph("missing-id")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.getGraph("missing-id");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PATCH /api/graphs/{graphId}
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testUpdateGraph() {
        NamedGraph updated = buildGraph("upd-1", "Updated Name");
        updated.setDescription("New desc");
        when(namedGraphService.updateGraph(eq("upd-1"), eq("Updated Name"), eq("New desc"), isNull()))
            .thenReturn(updated);

        NamedGraphController.UpdateGraphRequest request =
            new NamedGraphController.UpdateGraphRequest("Updated Name", "New desc", null);

        ResponseEntity<NamedGraph> response = controller.updateGraph("upd-1", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Updated Name", response.getBody().getName());
        assertEquals("New desc", response.getBody().getDescription());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DELETE /api/graphs/{graphId}
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testDeleteGraph() {
        doNothing().when(namedGraphService).deleteGraph("del-1");

        ResponseEntity<Void> response = controller.deleteGraph("del-1");

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(namedGraphService).deleteGraph("del-1");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GET /api/graphs/{graphId}/children
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testGetChildGraphs() {
        List<NamedGraph> children = List.of(
            buildGraph("c1", "Child1"),
            buildGraph("c2", "Child2")
        );
        when(namedGraphService.getChildGraphs("parent-1")).thenReturn(children);

        ResponseEntity<List<NamedGraph>> response = controller.getChildGraphs("parent-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().size());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GET /api/graphs/{graphId}/hierarchy
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testGetHierarchy() {
        Map<String, Object> hierarchyMap = new LinkedHashMap<>();
        hierarchyMap.put("graphId", "root-1");
        hierarchyMap.put("name", "Root");
        hierarchyMap.put("children", List.of());
        when(namedGraphService.getGraphHierarchy("root-1", 5)).thenReturn(hierarchyMap);

        ResponseEntity<Map<String, Object>> response = controller.getGraphHierarchy("root-1", 5);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("root-1", response.getBody().get("graphId"));
        assertEquals("Root", response.getBody().get("name"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GET /api/graphs/{graphId}/ancestors
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testGetAncestors() {
        List<NamedGraph> ancestors = List.of(
            buildGraph("root", "Root"),
            buildGraph("mid", "Mid")
        );
        when(namedGraphService.getAncestors("leaf-1")).thenReturn(ancestors);

        ResponseEntity<List<NamedGraph>> response = controller.getAncestors("leaf-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().size());
        assertEquals("root", response.getBody().get(0).getGraphId());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // POST /api/graphs/{graphId}/move
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testMoveGraph() {
        NamedGraph moved = buildGraph("child-1", "Child");
        when(namedGraphService.moveGraph("child-1", "new-parent-1")).thenReturn(moved);

        NamedGraphController.MoveGraphRequest request =
            new NamedGraphController.MoveGraphRequest("new-parent-1");

        ResponseEntity<NamedGraph> response = controller.moveGraph("child-1", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("child-1", response.getBody().getGraphId());
        verify(namedGraphService).moveGraph("child-1", "new-parent-1");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // POST /api/graphs/{graphId}/nodes/{nodeId}
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testLinkNodeToGraph() {
        doNothing().when(namedGraphService).linkNodeToGraph("node-1", "graph-1");

        ResponseEntity<Map<String, Object>> response = controller.linkNodeToGraph("graph-1", "node-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("graph-1", response.getBody().get("graphId"));
        assertEquals("node-1", response.getBody().get("nodeId"));
        assertEquals(true, response.getBody().get("linked"));
        verify(namedGraphService).linkNodeToGraph("node-1", "graph-1");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DELETE /api/graphs/{graphId}/nodes/{nodeId}
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testUnlinkNodeFromGraph() {
        doNothing().when(namedGraphService).unlinkNodeFromGraph("node-2", "graph-1");

        ResponseEntity<Map<String, Object>> response = controller.unlinkNodeFromGraph("graph-1", "node-2");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("graph-1", response.getBody().get("graphId"));
        assertEquals("node-2", response.getBody().get("nodeId"));
        assertEquals(false, response.getBody().get("linked"));
        verify(namedGraphService).unlinkNodeFromGraph("node-2", "graph-1");
    }
}
