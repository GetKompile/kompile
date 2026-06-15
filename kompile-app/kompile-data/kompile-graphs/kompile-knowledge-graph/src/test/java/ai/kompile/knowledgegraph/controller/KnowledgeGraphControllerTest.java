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

import ai.kompile.knowledgegraph.domain.*;
import ai.kompile.knowledgegraph.service.*;
import ai.kompile.knowledgegraph.service.GraphBuildingService.BuildConfig;
import ai.kompile.knowledgegraph.service.GraphBuildingService.BuildStatus;
import ai.kompile.knowledgegraph.service.SourceLinkingService.LinkingConfig;
import ai.kompile.knowledgegraph.service.SourceLinkingService.LinkingResult;
import ai.kompile.knowledgegraph.service.SourceLinkingService.SourceLink;
import ai.kompile.knowledgegraph.service.SourceLinkingService.TermLinkingResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link KnowledgeGraphController}.
 *
 * Directly instantiates the controller with mocked dependencies and asserts on
 * {@link ResponseEntity} status and body. No Spring context is loaded.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KnowledgeGraphControllerTest {

    @Mock
    private KnowledgeGraphService graphService;

    @Mock
    private SourceWeightingService weightingService;

    @Mock
    private GraphBuildingService graphBuildingService;

    @Mock
    private SourceLinkingService sourceLinkingService;

    // nodeRepository, graphDataPatchService, and ObjectMapper removed:
    // KnowledgeGraphController constructor only takes 4 args.

    private KnowledgeGraphController controller;

    // ─── Shared fixtures ──────────────────────────────────────────────────────

    private static final String NODE_ID  = "node-uuid-1";
    private static final String EDGE_ID  = "edge-uuid-1";
    private static final String JOB_ID   = "job-uuid-1";
    private static final Long   FS_ID    = 42L;

    @BeforeEach
    void setUp() {
        controller = new KnowledgeGraphController(
                graphService,
                weightingService,
                graphBuildingService,
                sourceLinkingService);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private GraphNode makeNode(String nodeId, NodeLevel level) {
        GraphNode node = new GraphNode();
        node.setNodeId(nodeId);
        node.setNodeType(level);
        node.setExternalId("ext-" + nodeId);
        node.setTitle("Title " + nodeId);
        return node;
    }

    private GraphEdge makeEdge(String edgeId, EdgeType type) {
        GraphEdge edge = new GraphEdge();
        edge.setEdgeId(edgeId);
        edge.setEdgeType(type);
        edge.setWeight(0.8);
        return edge;
    }

    private BuildStatus makeBuildStatus(String jobId) {
        return new BuildStatus(jobId, "COMPLETED", 1, 1, 2, 2, 5, 3, null,
                System.currentTimeMillis(), System.currentTimeMillis());
    }

    private SourceLink makeSourceLink() {
        return new SourceLink("src1", "Source One", "src2", "Source Two",
                "SHARED_CONCEPTS", 0.9, List.of("AI"), "shared concept link");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NODE ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    // --- listNodes ---

    @Test
    void listNodes_withQuery_delegatesToSearchNodes() {
        GraphNode node = makeNode(NODE_ID, NodeLevel.SOURCE);
        when(graphService.searchNodes(eq("ai"), isNull(), eq(50))).thenReturn(List.of(node));

        ResponseEntity<?> resp = controller.listNodes(null, "ai", 50);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        List<?> body = (List<?>) resp.getBody();
        assertEquals(1, body.size());
        assertEquals(NODE_ID, ((GraphNode) body.get(0)).getNodeId());
        verify(graphService).searchNodes("ai", null, 50);
    }

    @Test
    void listNodes_withQueryAndType_parsesNodeLevel() {
        GraphNode node = makeNode(NODE_ID, NodeLevel.ENTITY);
        when(graphService.searchNodes(eq("ml"), eq(NodeLevel.ENTITY), eq(20)))
                .thenReturn(List.of(node));

        ResponseEntity<?> resp = controller.listNodes("entity", "ml", 20);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(graphService).searchNodes("ml", NodeLevel.ENTITY, 20);
    }

    @Test
    void listNodes_withTypeOnly_callsSearchNodesWithEmptyQuery() {
        GraphNode node = makeNode(NODE_ID, NodeLevel.DOCUMENT);
        when(graphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                .thenReturn(List.of(node));

        ResponseEntity<?> resp = controller.listNodes("document", null, 50);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(graphService).searchNodes("", NodeLevel.DOCUMENT, 50);
    }

    @Test
    void listNodes_noParams_callsGetAllSources() {
        when(graphService.getAllSources()).thenReturn(List.of(makeNode(NODE_ID, NodeLevel.SOURCE)));

        ResponseEntity<?> resp = controller.listNodes(null, null, 50);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(graphService).getAllSources();
    }

    // --- getNode ---

    @Test
    void getNode_found_returns200() {
        when(graphService.getNode(NODE_ID)).thenReturn(Optional.of(makeNode(NODE_ID, NodeLevel.SOURCE)));

        ResponseEntity<?> resp = controller.getNode(NODE_ID);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    @Test
    void getNode_missing_returns404() {
        when(graphService.getNode(NODE_ID)).thenReturn(Optional.empty());

        ResponseEntity<?> resp = controller.getNode(NODE_ID);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    // --- getNodeChildren ---

    @Test
    void getNodeChildren_delegatesToGetChildren() {
        List<GraphNode> children = List.of(makeNode("child-1", NodeLevel.DOCUMENT));
        when(graphService.getChildren(NODE_ID)).thenReturn(children);

        ResponseEntity<?> resp = controller.getNodeChildren(NODE_ID);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(1, ((List<?>) resp.getBody()).size());
        verify(graphService).getChildren(NODE_ID);
    }

    // --- getConnectedNodes ---

    @Test
    void getConnectedNodes_delegatesToService() {
        List<GraphNode> connected = List.of(makeNode("cn-1", NodeLevel.ENTITY));
        when(graphService.getConnectedNodes(NODE_ID, 3)).thenReturn(connected);

        ResponseEntity<?> resp = controller.getConnectedNodes(NODE_ID, 3);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(graphService).getConnectedNodes(NODE_ID, 3);
    }

    // --- getRelatedNodes ---

    @Test
    void getRelatedNodes_delegatesToFindRelatedNodes() {
        List<GraphNode> related = List.of(makeNode("rn-1", NodeLevel.SNIPPET));
        when(graphService.findRelatedNodes(NODE_ID, 5)).thenReturn(related);

        ResponseEntity<?> resp = controller.getRelatedNodes(NODE_ID, 5);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(graphService).findRelatedNodes(NODE_ID, 5);
    }

    // --- createNode ---

    @Test
    void createNode_delegatesToGraphService() {
        GraphNode created = makeNode(NODE_ID, NodeLevel.ENTITY);
        when(graphService.createNode(eq(NodeLevel.ENTITY), anyString(), anyString(),
                anyString(), any())).thenReturn(created);

        KnowledgeGraphController.CreateNodeRequest req =
                new KnowledgeGraphController.CreateNodeRequest(
                        "entity", "ext-1", "My Entity", "a description",
                        Map.of("k", "v"));

        ResponseEntity<GraphNode> resp = controller.createNode(req);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(NODE_ID, resp.getBody().getNodeId());
        verify(graphService).createNode(NodeLevel.ENTITY, "ext-1", "My Entity",
                "a description", Map.of("k", "v"));
    }

    // --- updateNode ---

    @Test
    void updateNode_delegatesToGraphService() {
        GraphNode updated = makeNode(NODE_ID, NodeLevel.SOURCE);
        when(graphService.updateNode(eq(NODE_ID), anyString(), anyString(), any()))
                .thenReturn(updated);

        KnowledgeGraphController.UpdateNodeRequest req =
                new KnowledgeGraphController.UpdateNodeRequest("New Title", "New Desc", Map.of());

        ResponseEntity<GraphNode> resp = controller.updateNode(NODE_ID, req);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(graphService).updateNode(NODE_ID, "New Title", "New Desc", Map.of());
    }

    // patchNodeMetadata test removed: controller does not expose patchNodeMetadata endpoint.

    // --- deleteNode ---

    @Test
    void deleteNode_returns204NoContent() {
        doNothing().when(graphService).deleteNode(NODE_ID);

        ResponseEntity<Void> resp = controller.deleteNode(NODE_ID);

        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
        verify(graphService).deleteNode(NODE_ID);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EDGE ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    // --- listEdges ---

    @Test
    void listEdges_withNodeIdAndType_callsGetEdgesByType() {
        GraphEdge edge = makeEdge(EDGE_ID, EdgeType.HIERARCHICAL);
        when(graphService.getEdgesByType(NODE_ID, EdgeType.HIERARCHICAL)).thenReturn(List.of(edge));

        ResponseEntity<?> resp = controller.listEdges(NODE_ID, "HIERARCHICAL", null, 100);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(graphService).getEdgesByType(NODE_ID, EdgeType.HIERARCHICAL);
    }

    @Test
    void listEdges_withNodeIdOnly_callsGetEdgesForNode() {
        GraphEdge edge = makeEdge(EDGE_ID, EdgeType.CONTAINS);
        when(graphService.getEdgesForNode(NODE_ID)).thenReturn(List.of(edge));

        ResponseEntity<?> resp = controller.listEdges(NODE_ID, null, null, 100);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(graphService).getEdgesForNode(NODE_ID);
    }

    @Test
    void listEdges_withQuery_callsSearchEdges() {
        GraphEdge edge = makeEdge(EDGE_ID, EdgeType.EMBEDDING_SIMILARITY);
        when(graphService.searchEdges(eq("similar"), isNull(), eq(50))).thenReturn(List.of(edge));

        ResponseEntity<?> resp = controller.listEdges(null, null, "similar", 50);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(graphService).searchEdges("similar", null, 50);
    }

    @Test
    void listEdges_noParams_returnsEmpty() {
        ResponseEntity<?> resp = controller.listEdges(null, null, null, 100);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(((List<?>) resp.getBody()).isEmpty());
        verifyNoInteractions(graphService);
    }

    // --- searchEdges ---

    @Test
    void searchEdges_withType_callsServiceWithParsedType() {
        GraphEdge edge = makeEdge(EDGE_ID, EdgeType.CITATION);
        when(graphService.searchEdges("cite", EdgeType.CITATION, 25)).thenReturn(List.of(edge));

        ResponseEntity<List<GraphEdge>> resp = controller.searchEdges("cite", "CITATION", 25);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(graphService).searchEdges("cite", EdgeType.CITATION, 25);
    }

    @Test
    void searchEdges_withoutType_passesNullType() {
        when(graphService.searchEdges(eq("anything"), isNull(), eq(50))).thenReturn(List.of());

        ResponseEntity<List<GraphEdge>> resp = controller.searchEdges("anything", null, 50);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(graphService).searchEdges("anything", null, 50);
    }

    // --- getEdge ---

    @Test
    void getEdge_found_returns200() {
        when(graphService.getEdge(EDGE_ID))
                .thenReturn(Optional.of(makeEdge(EDGE_ID, EdgeType.SHARED_ENTITY)));

        ResponseEntity<?> resp = controller.getEdge(EDGE_ID);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    @Test
    void getEdge_notFound_returns404() {
        when(graphService.getEdge(EDGE_ID)).thenReturn(Optional.empty());

        ResponseEntity<?> resp = controller.getEdge(EDGE_ID);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    // --- createEdge ---

    @Test
    void createEdge_delegatesToGraphService() {
        GraphEdge created = makeEdge(EDGE_ID, EdgeType.CROSS_SOURCE);
        when(graphService.createEdge("src-1", "tgt-1", EdgeType.CROSS_SOURCE, 0.9, "cross link"))
                .thenReturn(created);

        KnowledgeGraphController.CreateEdgeRequest req =
                new KnowledgeGraphController.CreateEdgeRequest(
                        "src-1", "tgt-1", "CROSS_SOURCE", 0.9, "cross link");

        ResponseEntity<GraphEdge> resp = controller.createEdge(req);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(graphService).createEdge("src-1", "tgt-1", EdgeType.CROSS_SOURCE, 0.9, "cross link");
    }

    // --- updateEdge ---

    @Test
    void updateEdge_delegatesToGraphService() {
        GraphEdge updated = makeEdge(EDGE_ID, EdgeType.USER_DEFINED);
        when(graphService.updateEdge(EDGE_ID, 0.5, "updated desc")).thenReturn(updated);

        KnowledgeGraphController.UpdateEdgeRequest req =
                new KnowledgeGraphController.UpdateEdgeRequest(0.5, "updated desc");

        ResponseEntity<GraphEdge> resp = controller.updateEdge(EDGE_ID, req);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(graphService).updateEdge(EDGE_ID, 0.5, "updated desc");
    }

    // --- deleteEdge ---

    @Test
    void deleteEdge_returns204NoContent() {
        doNothing().when(graphService).deleteEdge(EDGE_ID);

        ResponseEntity<Void> resp = controller.deleteEdge(EDGE_ID);

        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
        verify(graphService).deleteEdge(EDGE_ID);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SOURCE WEIGHT ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    // --- listWeights ---

    @Test
    void listWeights_withSourceId_delegatesToService() {
        SourceWeight sw = new SourceWeight();
        when(weightingService.getAllWeightsForSource("src-1")).thenReturn(List.of(sw));

        ResponseEntity<List<SourceWeight>> resp = controller.listWeights("src-1");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(1, resp.getBody().size());
        verify(weightingService).getAllWeightsForSource("src-1");
    }

    @Test
    void listWeights_withoutSourceId_returnsEmpty() {
        ResponseEntity<List<SourceWeight>> resp = controller.listWeights(null);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody().isEmpty());
        verifyNoInteractions(weightingService);
    }

    // --- getWeight ---

    @Test
    void getWeight_delegatesToService() {
        SourceWeight sw = new SourceWeight();
        when(weightingService.getSourceWeight("src-1", "ml")).thenReturn(sw);

        ResponseEntity<SourceWeight> resp = controller.getWeight("src-1", "ml");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(weightingService).getSourceWeight("src-1", "ml");
    }

    // --- setWeight ---

    @Test
    void setWeight_delegatesToService() {
        SourceWeight sw = new SourceWeight();
        when(weightingService.setSourceWeight("src-1", 1.5, "ml", "user1")).thenReturn(sw);

        KnowledgeGraphController.SetWeightRequest req =
                new KnowledgeGraphController.SetWeightRequest("src-1", 1.5, "ml", "user1");

        ResponseEntity<SourceWeight> resp = controller.setWeight(req);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(weightingService).setSourceWeight("src-1", 1.5, "ml", "user1");
    }

    // --- removeWeight ---

    @Test
    void removeWeight_returns204NoContent() {
        doNothing().when(weightingService).removeWeight("src-1", "ml", "user1");

        ResponseEntity<Void> resp = controller.removeWeight("src-1", "ml", "user1");

        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
        verify(weightingService).removeWeight("src-1", "ml", "user1");
    }

    // --- previewWeightedSearch ---

    @Test
    void previewWeightedSearch_delegatesToService() {
        Map<String, Object> preview = Map.of("results", List.of());
        when(weightingService.previewWeightedSearch("query", 5)).thenReturn(preview);

        KnowledgeGraphController.WeightedSearchRequest req =
                new KnowledgeGraphController.WeightedSearchRequest("query", 5);

        ResponseEntity<Map<String, Object>> resp = controller.previewWeightedSearch(req);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(weightingService).previewWeightedSearch("query", 5);
    }

    // --- submitFeedback ---

    @Test
    void submitFeedback_delegatesToService() {
        doNothing().when(weightingService).updateQualityScore("src-1", true);

        KnowledgeGraphController.FeedbackRequest req =
                new KnowledgeGraphController.FeedbackRequest("src-1", true);

        ResponseEntity<Void> resp = controller.submitFeedback(req);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(weightingService).updateQualityScore("src-1", true);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TOPIC ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void getTopics_delegatesToService() {
        when(weightingService.getTopics()).thenReturn(List.of("ml", "finance"));

        ResponseEntity<List<String>> resp = controller.getTopics();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(2, resp.getBody().size());
        verify(weightingService).getTopics();
    }

    @Test
    void assignTopic_delegatesToService() {
        doNothing().when(weightingService).assignTopic("src-1", "finance");

        KnowledgeGraphController.AssignTopicRequest req =
                new KnowledgeGraphController.AssignTopicRequest("finance");

        ResponseEntity<Void> resp = controller.assignTopic("src-1", req);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(weightingService).assignTopic("src-1", "finance");
    }

    @Test
    void getSourcesForTopic_delegatesToService() {
        when(weightingService.getSourcesForTopic("ml")).thenReturn(List.of("src-1", "src-2"));

        ResponseEntity<List<String>> resp = controller.getSourcesForTopic("ml");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(2, resp.getBody().size());
        verify(weightingService).getSourcesForTopic("ml");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATISTICS & VISUALIZATION ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void getStatistics_delegatesToService() {
        Map<String, Object> stats = Map.of("nodeCount", 100);
        when(graphService.getGraphStatistics()).thenReturn(stats);

        ResponseEntity<Map<String, Object>> resp = controller.getStatistics();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(100, resp.getBody().get("nodeCount"));
        verify(graphService).getGraphStatistics();
    }

    @Test
    void getVisualizationData_delegatesToService() {
        Map<String, Object> viz = Map.of("nodes", List.of(), "edges", List.of());
        when(graphService.getVisualizationData("root-1", 2, 50)).thenReturn(viz);

        ResponseEntity<Map<String, Object>> resp = controller.getVisualizationData("root-1", 2, 50, null, null);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(graphService).getVisualizationData("root-1", 2, 50);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRAPH BUILDING ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    // --- buildGraphFromAllSources ---

    @Test
    void buildGraphFromAllSources_withRequest_usesToConfigFromRequest() {
        BuildStatus status = makeBuildStatus(JOB_ID);
        when(graphBuildingService.buildGraphFromAllSources(any(BuildConfig.class))).thenReturn(status);

        KnowledgeGraphController.BuildGraphRequest req =
                new KnowledgeGraphController.BuildGraphRequest(
                        0.7, 2, true, false, 0.8,
                        List.of("PERSON"), 50, false);

        ResponseEntity<BuildStatus> resp = controller.buildGraphFromAllSources(req);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(JOB_ID, resp.getBody().jobId());
        verify(graphBuildingService).buildGraphFromAllSources(argThat(cfg ->
                cfg.minEntityConfidence() == 0.7 && cfg.minSharedEntitiesForEdge() == 2));
    }

    @Test
    void buildGraphFromAllSources_withNullRequest_usesDefaults() {
        BuildStatus status = makeBuildStatus(JOB_ID);
        when(graphBuildingService.buildGraphFromAllSources(any(BuildConfig.class))).thenReturn(status);

        ResponseEntity<BuildStatus> resp = controller.buildGraphFromAllSources(null);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(graphBuildingService).buildGraphFromAllSources(argThat(cfg ->
                cfg.minEntityConfidence() == 0.6));
    }

    // --- buildGraphFromSources ---

    @Test
    void buildGraphFromSources_delegatesToService() {
        BuildStatus status = makeBuildStatus(JOB_ID);
        List<String> srcIds = List.of("src-1", "src-2");
        when(graphBuildingService.buildGraphFromSources(eq(srcIds), any(BuildConfig.class)))
                .thenReturn(status);

        KnowledgeGraphController.BuildFromSourcesRequest req =
                new KnowledgeGraphController.BuildFromSourcesRequest(srcIds, null);

        ResponseEntity<BuildStatus> resp = controller.buildGraphFromSources(req);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(graphBuildingService).buildGraphFromSources(eq(srcIds), any(BuildConfig.class));
    }

    // --- getBuildStatus ---

    @Test
    void getBuildStatus_found_returns200() {
        BuildStatus status = makeBuildStatus(JOB_ID);
        when(graphBuildingService.getBuildStatus(JOB_ID)).thenReturn(status);

        ResponseEntity<BuildStatus> resp = controller.getBuildStatus(JOB_ID);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(JOB_ID, resp.getBody().jobId());
    }

    @Test
    void getBuildStatus_notFound_returns404() {
        when(graphBuildingService.getBuildStatus(JOB_ID)).thenReturn(null);

        ResponseEntity<BuildStatus> resp = controller.getBuildStatus(JOB_ID);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    // --- cancelBuild ---

    @Test
    void cancelBuild_returnsCancelledStatus() {
        when(graphBuildingService.cancelBuild(JOB_ID)).thenReturn(true);

        ResponseEntity<Map<String, Object>> resp = controller.cancelBuild(JOB_ID);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(JOB_ID, resp.getBody().get("jobId"));
        assertEquals(true, resp.getBody().get("cancelled"));
    }

    // --- getRunningJobs ---

    @Test
    void getRunningJobs_delegatesToService() {
        when(graphBuildingService.getRunningJobs()).thenReturn(List.of(makeBuildStatus(JOB_ID)));

        ResponseEntity<List<BuildStatus>> resp = controller.getRunningJobs();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(1, resp.getBody().size());
        verify(graphBuildingService).getRunningJobs();
    }

    // --- getBuildStatistics ---

    @Test
    void getBuildStatistics_delegatesToService() {
        Map<String, Object> buildStats = Map.of("totalBuilds", 5);
        when(graphBuildingService.getBuildStatistics()).thenReturn(buildStats);

        ResponseEntity<Map<String, Object>> resp = controller.getBuildStatistics();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(5, resp.getBody().get("totalBuilds"));
        verify(graphBuildingService).getBuildStatistics();
    }

    // --- createSharedEntityEdges ---

    @Test
    void createSharedEntityEdges_returnsEdgeCount() {
        when(graphBuildingService.createSharedEntityEdges(2)).thenReturn(7);

        ResponseEntity<Map<String, Object>> resp = controller.createSharedEntityEdges(2);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(7, resp.getBody().get("edgesCreated"));
        assertEquals(2, resp.getBody().get("minSharedEntities"));
    }

    // --- clearGraph ---

    @Test
    void clearGraph_returnsSuccessMessage() {
        doNothing().when(graphBuildingService).clearGraph();

        ResponseEntity<Map<String, Object>> resp = controller.clearGraph();

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("Graph cleared successfully", resp.getBody().get("message"));
        verify(graphBuildingService).clearGraph();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SOURCE LINKING ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    // --- linkSourcesBySharedConcepts ---

    @Test
    void linkSourcesBySharedConcepts_withNullConfig_usesDefaults() {
        LinkingResult result = new LinkingResult(2, 1, 1, 0, List.of(makeSourceLink()), Map.of());
        when(sourceLinkingService.linkSourcesBySharedConcepts(eq(FS_ID), any(LinkingConfig.class)))
                .thenReturn(result);

        ResponseEntity<LinkingResult> resp = controller.linkSourcesBySharedConcepts(FS_ID, null);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(sourceLinkingService).linkSourcesBySharedConcepts(eq(FS_ID),
                argThat(cfg -> cfg.minSharedConcepts() == 3));
    }

    @Test
    void linkSourcesBySharedConcepts_withConfig_usesProvidedConfig() {
        LinkingResult result = new LinkingResult(3, 2, 2, 0, List.of(), Map.of());
        when(sourceLinkingService.linkSourcesBySharedConcepts(eq(FS_ID), any(LinkingConfig.class)))
                .thenReturn(result);

        KnowledgeGraphController.LinkingConfigRequest config =
                new KnowledgeGraphController.LinkingConfigRequest(5, 0.8, 0.3, true, true, true, true);

        ResponseEntity<LinkingResult> resp = controller.linkSourcesBySharedConcepts(FS_ID, config);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(sourceLinkingService).linkSourcesBySharedConcepts(eq(FS_ID),
                argThat(cfg -> cfg.minSharedConcepts() == 5 && cfg.minSimilarity() == 0.8));
    }

    // --- linkSourcesByEmbeddingSimilarity ---

    @Test
    void linkSourcesByEmbeddingSimilarity_delegatesToService() {
        LinkingResult result = new LinkingResult(2, 1, 0, 1, List.of(), Map.of());
        when(sourceLinkingService.linkSourcesByEmbeddingSimilarity(eq(FS_ID), any(LinkingConfig.class)))
                .thenReturn(result);

        ResponseEntity<LinkingResult> resp =
                controller.linkSourcesByEmbeddingSimilarity(FS_ID, null);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(sourceLinkingService).linkSourcesByEmbeddingSimilarity(eq(FS_ID), any(LinkingConfig.class));
    }

    // --- linkAllSources ---

    @Test
    void linkAllSources_delegatesToService() {
        LinkingResult result = new LinkingResult(4, 3, 2, 1, List.of(), Map.of());
        when(sourceLinkingService.linkAllSources(eq(FS_ID), any(LinkingConfig.class)))
                .thenReturn(result);

        ResponseEntity<LinkingResult> resp = controller.linkAllSources(FS_ID, null);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(sourceLinkingService).linkAllSources(eq(FS_ID), any(LinkingConfig.class));
    }

    // --- getSourceLinks ---

    @Test
    void getSourceLinks_delegatesToService() {
        when(sourceLinkingService.getSourceLinks(FS_ID)).thenReturn(List.of(makeSourceLink()));

        ResponseEntity<List<SourceLink>> resp = controller.getSourceLinks(FS_ID);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(1, resp.getBody().size());
        verify(sourceLinkingService).getSourceLinks(FS_ID);
    }

    // --- getLinksForSource ---

    @Test
    void getLinksForSource_delegatesToService() {
        when(sourceLinkingService.getLinksForSource(FS_ID, "src-1"))
                .thenReturn(List.of(makeSourceLink()));

        ResponseEntity<List<SourceLink>> resp = controller.getLinksForSource("src-1", FS_ID);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(sourceLinkingService).getLinksForSource(FS_ID, "src-1");
    }

    // --- createManualLink ---

    @Test
    void createManualLink_withStrength_passesStrengthToService() {
        SourceLink link = makeSourceLink();
        when(sourceLinkingService.createManualLink(FS_ID, "src-1", "src-2", "desc", 0.85))
                .thenReturn(link);

        KnowledgeGraphController.ManualLinkRequest req =
                new KnowledgeGraphController.ManualLinkRequest(FS_ID, "src-1", "src-2", "desc", 0.85);

        ResponseEntity<SourceLink> resp = controller.createManualLink(req);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(sourceLinkingService).createManualLink(FS_ID, "src-1", "src-2", "desc", 0.85);
    }

    @Test
    void createManualLink_withNullStrength_usesDefaultStrength() {
        SourceLink link = makeSourceLink();
        when(sourceLinkingService.createManualLink(FS_ID, "src-1", "src-2", "desc", 0.7))
                .thenReturn(link);

        KnowledgeGraphController.ManualLinkRequest req =
                new KnowledgeGraphController.ManualLinkRequest(FS_ID, "src-1", "src-2", "desc", null);

        ResponseEntity<SourceLink> resp = controller.createManualLink(req);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(sourceLinkingService).createManualLink(FS_ID, "src-1", "src-2", "desc", 0.7);
    }

    // --- removeLink ---

    @Test
    void removeLink_returnsRemovedStatus() {
        when(sourceLinkingService.removeLink(FS_ID, "src-1", "src-2")).thenReturn(true);

        ResponseEntity<Map<String, Object>> resp = controller.removeLink(FS_ID, "src-1", "src-2");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(true, resp.getBody().get("removed"));
        assertEquals("src-1", resp.getBody().get("sourceNodeId1"));
        assertEquals("src-2", resp.getBody().get("sourceNodeId2"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TERM LINKING ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    // --- linkNodesByTerm ---

    @Test
    void linkNodesByTerm_delegatesToService() {
        TermLinkingResult result = new TermLinkingResult("AI", 3, 2, List.of("n1", "n2"), "ok");
        when(sourceLinkingService.linkNodesByTerm("AI", FS_ID, EdgeType.SHARED_ENTITY, 0.7))
                .thenReturn(result);

        KnowledgeGraphController.LinkByTermRequest req =
                new KnowledgeGraphController.LinkByTermRequest("AI", FS_ID, "SHARED_ENTITY", 0.7);

        ResponseEntity<TermLinkingResult> resp = controller.linkNodesByTerm(req);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("AI", resp.getBody().term());
        verify(sourceLinkingService).linkNodesByTerm("AI", FS_ID, EdgeType.SHARED_ENTITY, 0.7);
    }

    // --- linkNodesByTerms ---

    @Test
    void linkNodesByTerms_delegatesToService() {
        TermLinkingResult r1 = new TermLinkingResult("AI", 2, 1, List.of("n1"), "ok");
        TermLinkingResult r2 = new TermLinkingResult("ML", 2, 1, List.of("n2"), "ok");
        when(sourceLinkingService.linkNodesByTerms(any(), eq(FS_ID), isNull(), isNull()))
                .thenReturn(List.of(r1, r2));

        KnowledgeGraphController.LinkByTermsRequest req =
                new KnowledgeGraphController.LinkByTermsRequest(
                        List.of("AI", "ML"), FS_ID, null, null);

        ResponseEntity<List<TermLinkingResult>> resp = controller.linkNodesByTerms(req);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(2, resp.getBody().size());
        verify(sourceLinkingService).linkNodesByTerms(List.of("AI", "ML"), FS_ID, null, null);
    }

    // --- createTermBasedRelation ---

    @Test
    void createTermBasedRelation_withWeightAndBidirectional_passesValues() {
        SourceLink link = makeSourceLink();
        when(sourceLinkingService.createTermBasedRelation("n1", "n2", "relates", "desc", 0.9, true))
                .thenReturn(link);

        KnowledgeGraphController.TermRelationRequest req =
                new KnowledgeGraphController.TermRelationRequest("n1", "n2", "relates", "desc", 0.9, true);

        ResponseEntity<SourceLink> resp = controller.createTermBasedRelation(req);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(sourceLinkingService).createTermBasedRelation("n1", "n2", "relates", "desc", 0.9, true);
    }

    @Test
    void createTermBasedRelation_withNullWeightAndBidirectional_usesDefaults() {
        SourceLink link = makeSourceLink();
        when(sourceLinkingService.createTermBasedRelation("n1", "n2", "relates", null, 0.7, true))
                .thenReturn(link);

        KnowledgeGraphController.TermRelationRequest req =
                new KnowledgeGraphController.TermRelationRequest("n1", "n2", "relates", null, null, null);

        ResponseEntity<SourceLink> resp = controller.createTermBasedRelation(req);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(sourceLinkingService).createTermBasedRelation("n1", "n2", "relates", null, 0.7, true);
    }

    // --- findNodesWithTerm ---

    @Test
    void findNodesWithTerm_delegatesToService() {
        when(sourceLinkingService.findNodesWithTerm("AI", FS_ID, 10)).thenReturn(List.of("n1", "n2"));

        ResponseEntity<List<String>> resp = controller.findNodesWithTerm("AI", FS_ID, 10);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(2, resp.getBody().size());
        verify(sourceLinkingService).findNodesWithTerm("AI", FS_ID, 10);
    }

    // --- getAllTerms ---

    @Test
    void getAllTerms_delegatesToService() {
        List<Map<String, Object>> terms = List.of(Map.of("term", "AI", "count", 5));
        when(sourceLinkingService.getAllTerms(FS_ID, 100)).thenReturn(terms);

        ResponseEntity<List<Map<String, Object>>> resp = controller.getAllTerms(FS_ID, 100);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(1, resp.getBody().size());
        verify(sourceLinkingService).getAllTerms(FS_ID, 100);
    }

    // --- getSharedTerms ---

    @Test
    void getSharedTerms_delegatesToService() {
        when(sourceLinkingService.getSharedTerms("n1", "n2", FS_ID)).thenReturn(List.of("AI", "ML"));

        ResponseEntity<List<String>> resp = controller.getSharedTerms("n1", "n2", FS_ID);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(2, resp.getBody().size());
        verify(sourceLinkingService).getSharedTerms("n1", "n2", FS_ID);
    }

    // getSourceChunks tests removed: controller does not expose getSourceChunks endpoint.
}
