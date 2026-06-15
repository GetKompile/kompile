/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.knowledgegraph.controller;

import ai.kompile.knowledgegraph.service.FactSheetGraphService;
import ai.kompile.knowledgegraph.service.FactSheetGraphService.*;
import ai.kompile.knowledgegraph.service.SourceLinkingService;
import ai.kompile.knowledgegraph.service.SourceLinkingService.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link FactSheetGraphController} — graph building, visualization,
 * concepts, search, and source linking endpoints.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class FactSheetGraphControllerTest {

    @Mock private FactSheetGraphService factSheetGraphService;
    @Mock private SourceLinkingService sourceLinkingService;

    private FactSheetGraphController controller;

    @BeforeEach
    void setUp() {
        controller = new FactSheetGraphController(factSheetGraphService, sourceLinkingService);
    }

    private GraphBuildStatus stubBuildStatus(String jobId, String status) {
        return new GraphBuildStatus(jobId, "Test Sheet", 1L, status,
                10, 5, 20, 15, 50, null, System.currentTimeMillis(), null, Map.of());
    }

    // ─── buildGraph ─────────────────────────────────────────────────

    @Test
    void buildGraph_withConfig_delegatesToService() {
        GraphBuildStatus expected = stubBuildStatus("j1", "RUNNING");
        when(factSheetGraphService.buildGraphFromIndex(eq(1L), any(GraphBuildConfig.class)))
                .thenReturn(expected);

        ResponseEntity<GraphBuildStatus> response = controller.buildGraph(1L, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("j1", response.getBody().jobId());
        assertEquals("RUNNING", response.getBody().status());
    }

    @Test
    void buildGraph_nullRequest_usesDefaults() {
        when(factSheetGraphService.buildGraphFromIndex(eq(1L), any()))
                .thenReturn(stubBuildStatus("j2", "PENDING"));

        ResponseEntity<GraphBuildStatus> response = controller.buildGraph(1L, null);
        assertEquals(200, response.getStatusCode().value());
        verify(factSheetGraphService).buildGraphFromIndex(eq(1L), any(GraphBuildConfig.class));
    }

    // ─── getBuildStatus ─────────────────────────────────────────────

    @Test
    void getBuildStatus_found_returnsOk() {
        when(factSheetGraphService.getBuildStatus("j1")).thenReturn(stubBuildStatus("j1", "COMPLETED"));

        ResponseEntity<GraphBuildStatus> response = controller.getBuildStatus(1L, "j1");
        assertEquals(200, response.getStatusCode().value());
        assertEquals("COMPLETED", response.getBody().status());
    }

    @Test
    void getBuildStatus_notFound_returns404() {
        when(factSheetGraphService.getBuildStatus("missing")).thenReturn(null);

        ResponseEntity<GraphBuildStatus> response = controller.getBuildStatus(1L, "missing");
        assertEquals(404, response.getStatusCode().value());
    }

    // ─── cancelBuild ────────────────────────────────────────────────

    @Test
    void cancelBuild_success_returnsCancelled() {
        when(factSheetGraphService.cancelBuild("j1")).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.cancelBuild(1L, "j1");
        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, response.getBody().get("cancelled"));
        assertEquals("j1", response.getBody().get("jobId"));
    }

    @Test
    void cancelBuild_notCancellable_returnsFalse() {
        when(factSheetGraphService.cancelBuild("j2")).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = controller.cancelBuild(1L, "j2");
        assertEquals(false, response.getBody().get("cancelled"));
    }

    // ─── getRunningJobs ─────────────────────────────────────────────

    @Test
    void getRunningJobs_returnsRunningList() {
        when(factSheetGraphService.getRunningJobs())
                .thenReturn(List.of(stubBuildStatus("j1", "RUNNING")));

        ResponseEntity<List<GraphBuildStatus>> response = controller.getRunningJobs(1L);
        assertEquals(1, response.getBody().size());
        assertEquals("RUNNING", response.getBody().get(0).status());
    }

    // ─── getVisualization ───────────────────────────────────────────

    @Test
    void getVisualization_returnsData() {
        GraphVisualizationData data = new GraphVisualizationData(
                List.of(Map.of("id", "n1")), List.of(Map.of("source", "n1")), Map.of("nodeCount", 1));
        when(factSheetGraphService.getVisualizationData(1L, 500, 1000)).thenReturn(data);

        ResponseEntity<GraphVisualizationData> response = controller.getVisualization(1L, 500, 1000);
        assertEquals(1, response.getBody().nodes().size());
        assertEquals(1, response.getBody().edges().size());
    }

    // ─── getStatistics ──────────────────────────────────────────────

    @Test
    void getStatistics_returnsMap() {
        when(factSheetGraphService.getGraphStatistics(1L))
                .thenReturn(Map.of("nodeCount", 100, "edgeCount", 200));

        ResponseEntity<Map<String, Object>> response = controller.getStatistics(1L);
        assertEquals(100, response.getBody().get("nodeCount"));
    }

    // ─── clearGraph ─────────────────────────────────────────────────

    @Test
    void clearGraph_returnsDeletedCount() {
        when(factSheetGraphService.clearGraph(1L)).thenReturn(50);

        ResponseEntity<Map<String, Object>> response = controller.clearGraph(1L);
        assertEquals(50, response.getBody().get("entitiesDeleted"));
        assertEquals(1L, response.getBody().get("factSheetId"));
    }

    // ─── getTopConcepts ─────────────────────────────────────────────

    @Test
    void getTopConcepts_delegatesToService() {
        when(factSheetGraphService.getTopConcepts(1L, 20))
                .thenReturn(List.of(Map.of("name", "ML", "count", 10)));

        ResponseEntity<List<Map<String, Object>>> response = controller.getTopConcepts(1L, 20);
        assertEquals(1, response.getBody().size());
        assertEquals("ML", response.getBody().get(0).get("name"));
    }

    // ─── rebuildConceptEdges ────────────────────────────────────────

    @Test
    void rebuildConceptEdges_returnsCount() {
        when(factSheetGraphService.rebuildConceptEdges(1L, 2)).thenReturn(15);

        ResponseEntity<Map<String, Object>> response = controller.rebuildConceptEdges(1L, 2);
        assertEquals(15, response.getBody().get("edgesCreated"));
    }

    // ─── searchNodes ────────────────────────────────────────────────

    @Test
    void searchNodes_delegatesToService() {
        when(factSheetGraphService.searchNodes(1L, "machine learning", 20))
                .thenReturn(List.of(Map.of("title", "ML")));

        ResponseEntity<List<Map<String, Object>>> response = controller.searchNodes(1L, "machine learning", 20);
        assertEquals(1, response.getBody().size());
    }

    // ─── getRelatedDocuments ────────────────────────────────────────

    @Test
    void getRelatedDocuments_delegatesToService() {
        when(factSheetGraphService.getRelatedDocuments(1L, "doc1", 2, 10))
                .thenReturn(List.of(Map.of("documentNodeId", "doc2")));

        ResponseEntity<List<Map<String, Object>>> response =
                controller.getRelatedDocuments(1L, "doc1", 2, 10);
        assertEquals(1, response.getBody().size());
    }

    // ─── linkSources ────────────────────────────────────────────────

    @Test
    void linkSources_nullRequest_usesDefaults() {
        LinkingResult result = new LinkingResult(5, 3, 2, 1, List.of(), Map.of());
        when(sourceLinkingService.linkAllSources(eq(1L), any(LinkingConfig.class)))
                .thenReturn(result);

        ResponseEntity<LinkingResult> response = controller.linkSources(1L, null);
        assertEquals(3, response.getBody().linksCreated());
    }

    // ─── getSourceLinks ─────────────────────────────────────────────

    @Test
    void getSourceLinks_delegatesToService() {
        SourceLink link = new SourceLink("s1", "Source 1", "s2", "Source 2",
                "SHARED_CONCEPTS", 0.8, List.of("ML"), "shared concepts");
        when(sourceLinkingService.getSourceLinks(1L)).thenReturn(List.of(link));

        ResponseEntity<List<SourceLink>> response = controller.getSourceLinks(1L);
        assertEquals(1, response.getBody().size());
        assertEquals("s1", response.getBody().get(0).sourceId1());
    }

    // ─── getLinksForSource ──────────────────────────────────────────

    @Test
    void getLinksForSource_delegatesToService() {
        when(sourceLinkingService.getLinksForSource(1L, "s1")).thenReturn(List.of());

        ResponseEntity<List<SourceLink>> response = controller.getLinksForSource(1L, "s1");
        assertTrue(response.getBody().isEmpty());
    }

    // ─── createManualLink ───────────────────────────────────────────

    @Test
    void createManualLink_withStrength_usesProvidedStrength() {
        SourceLink link = new SourceLink("s1", "S1", "s2", "S2",
                "MANUAL", 0.9, List.of(), "manual");
        when(sourceLinkingService.createManualLink(1L, "s1", "s2", "test link", 0.9))
                .thenReturn(link);

        ResponseEntity<SourceLink> response = controller.createManualLink(1L,
                new FactSheetGraphController.CreateLinkRequest("s1", "s2", "test link", 0.9));
        assertEquals("MANUAL", response.getBody().linkType());
    }

    @Test
    void createManualLink_nullStrength_defaultsToHalf() {
        SourceLink link = new SourceLink("s1", "S1", "s2", "S2",
                "MANUAL", 0.5, List.of(), "manual");
        when(sourceLinkingService.createManualLink(1L, "s1", "s2", "link", 0.5))
                .thenReturn(link);

        ResponseEntity<SourceLink> response = controller.createManualLink(1L,
                new FactSheetGraphController.CreateLinkRequest("s1", "s2", "link", null));
        assertEquals(0.5, response.getBody().strength());
    }

    // ─── removeLink ─────────────────────────────────────────────────

    @Test
    void removeLink_success_returnsTrue() {
        when(sourceLinkingService.removeLink(1L, "s1", "s2")).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.removeLink(1L, "s1", "s2");
        assertEquals(true, response.getBody().get("removed"));
        assertEquals("s1", response.getBody().get("sourceNodeId1"));
        assertEquals("s2", response.getBody().get("sourceNodeId2"));
    }

    @Test
    void removeLink_notFound_returnsFalse() {
        when(sourceLinkingService.removeLink(1L, "s1", "s2")).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = controller.removeLink(1L, "s1", "s2");
        assertEquals(false, response.getBody().get("removed"));
    }

    // ─── getConnectivitySummary ─────────────────────────────────────

    @Test
    void getConnectivitySummary_delegatesToService() {
        when(sourceLinkingService.getConnectivitySummary(1L))
                .thenReturn(Map.of("totalSources", 10, "linkedSources", 8));

        ResponseEntity<Map<String, Object>> response = controller.getConnectivitySummary(1L);
        assertEquals(10, response.getBody().get("totalSources"));
    }

    // ─── findIsolatedSources ────────────────────────────────────────

    @Test
    void findIsolatedSources_delegatesToService() {
        when(sourceLinkingService.findIsolatedSources(1L)).thenReturn(List.of("s3", "s4"));

        ResponseEntity<List<String>> response = controller.findIsolatedSources(1L);
        assertEquals(2, response.getBody().size());
    }

    // ─── findMostConnectedSources ───────────────────────────────────

    @Test
    void findMostConnectedSources_delegatesToService() {
        when(sourceLinkingService.findMostConnectedSources(1L, 10))
                .thenReturn(List.of(Map.of("sourceId", "s1", "linkCount", 5)));

        ResponseEntity<List<Map<String, Object>>> response =
                controller.findMostConnectedSources(1L, 10);
        assertEquals(1, response.getBody().size());
    }
}
