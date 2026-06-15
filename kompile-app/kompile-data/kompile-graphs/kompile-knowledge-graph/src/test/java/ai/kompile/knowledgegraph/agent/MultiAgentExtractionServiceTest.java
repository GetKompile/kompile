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
package ai.kompile.knowledgegraph.agent;

import ai.kompile.core.graphrag.agent.MultiAgentGraphBuilder.GraphMergeStrategy;
import ai.kompile.core.graphrag.agent.MultiAgentGraphBuilder.MergedGraphResult;
import ai.kompile.core.graphrag.agent.RelationExtractionAgent;
import ai.kompile.core.graphrag.agent.RelationExtractionAgent.ExtractionConfig;
import ai.kompile.core.graphrag.agent.RelationExtractionAgent.ExtractionResult;
import ai.kompile.core.graphrag.agent.RelationExtractionAgent.AgentMetrics;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MultiAgentExtractionService}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MultiAgentExtractionServiceTest {

    @Mock
    private KnowledgeGraphService graphService;

    private MultiAgentExtractionService service;

    // Simple stub agent that returns predefined entities
    private static RelationExtractionAgent stubAgent(String id, List<Entity> entities,
                                                      List<Relationship> rels) {
        return new RelationExtractionAgent() {
            @Override public String getId() { return id; }
            @Override public String getDescription() { return "stub-" + id; }
            @Override public Set<String> supportedContentTypes() { return Set.of(); }
            @Override
            public ExtractionResult extract(List<RetrievedDoc> chunks, ExtractionConfig config) {
                Graph g = new Graph();
                g.setEntities(new ArrayList<>(entities));
                g.setRelationships(new ArrayList<>(rels));
                return new ExtractionResult(g,
                        new AgentMetrics(id, 1L, entities.size(), rels.size(),
                                chunks.size(), null, Map.of()));
            }
        };
    }

    private static Entity entity(String id, String title, String type) {
        Entity e = new Entity();
        e.setId(id);
        e.setTitle(title);
        e.setType(type);
        e.setConfidence(0.8);
        return e;
    }

    private static Relationship rel(String src, String tgt, String type) {
        Relationship r = new Relationship();
        r.setSource(src);
        r.setTarget(tgt);
        r.setType(type);
        r.setConfidence(0.8);
        return r;
    }

    @BeforeEach
    void setUp() {
        // Service with no registered agents
        service = new MultiAgentExtractionService(null);
    }

    @Test
    void getAvailableAgentsEmptyWhenNoAgentsRegistered() {
        List<MultiAgentExtractionService.AgentInfo> agents = service.getAvailableAgents();
        assertNotNull(agents);
        assertTrue(agents.isEmpty());
    }

    @Test
    void getAvailableAgentsReturnsRegisteredAgents() {
        RelationExtractionAgent a1 = stubAgent("agent-1", List.of(), List.of());
        RelationExtractionAgent a2 = stubAgent("agent-2", List.of(), List.of());
        service = new MultiAgentExtractionService(List.of(a1, a2));

        List<MultiAgentExtractionService.AgentInfo> agents = service.getAvailableAgents();
        assertEquals(2, agents.size());
        assertTrue(agents.stream().anyMatch(a -> "agent-1".equals(a.id())));
        assertTrue(agents.stream().anyMatch(a -> "agent-2".equals(a.id())));
    }

    @Test
    void runExtractionWithNoAgentsReturnsEmptyGraph() {
        List<RetrievedDoc> chunks = List.of(
                new RetrievedDoc("c1", "Some text.", Map.of()));

        MergedGraphResult result = service.runExtraction(chunks, null, "UNION", null);

        assertNotNull(result);
        assertEquals(0, result.totalEntities());
    }

    @Test
    void runExtractionSelectsAllAgentsWhenNoIdsSpecified() {
        RelationExtractionAgent a1 = stubAgent("a1",
                List.of(entity("e1", "Alice", "PERSON")), List.of());
        RelationExtractionAgent a2 = stubAgent("a2",
                List.of(entity("e2", "Acme", "ORGANIZATION")), List.of());
        service = new MultiAgentExtractionService(List.of(a1, a2));

        List<RetrievedDoc> chunks = List.of(new RetrievedDoc("c1", "Some text.", Map.of()));
        MergedGraphResult result = service.runExtraction(chunks, null, "UNION", null);

        assertTrue(result.totalEntities() >= 2);
    }

    @Test
    void runExtractionSelectsSpecificAgentById() {
        RelationExtractionAgent a1 = stubAgent("a1",
                List.of(entity("e1", "Alice", "PERSON")), List.of());
        RelationExtractionAgent a2 = stubAgent("a2",
                List.of(entity("e2", "Acme", "ORGANIZATION")), List.of());
        service = new MultiAgentExtractionService(List.of(a1, a2));

        List<RetrievedDoc> chunks = List.of(new RetrievedDoc("c1", "Some text.", Map.of()));
        MergedGraphResult result = service.runExtraction(chunks, List.of("a1"), "UNION", null);

        assertEquals(1, result.contributions().size());
        assertTrue(result.contributions().containsKey("a1"));
    }

    @Test
    void runExtractionDefaultsToUnionWhenStrategyUnknown() {
        RelationExtractionAgent a1 = stubAgent("a1",
                List.of(entity("e1", "Alice", "PERSON")), List.of());
        service = new MultiAgentExtractionService(List.of(a1));

        List<RetrievedDoc> chunks = List.of(new RetrievedDoc("c1", "Some text.", Map.of()));
        MergedGraphResult result = service.runExtraction(chunks, null, "INVALID_STRATEGY", null);

        assertNotNull(result);
        assertEquals(GraphMergeStrategy.UNION, result.strategy());
    }

    @Test
    void runExtractionWithNullChunksIsHandled() {
        RelationExtractionAgent a1 = stubAgent("a1", List.of(), List.of());
        service = new MultiAgentExtractionService(List.of(a1));

        MergedGraphResult result = service.runExtraction(null, null, "UNION", null);
        assertNotNull(result);
    }

    @Test
    void runExtractionHandlesNullMergeStrategy() {
        RelationExtractionAgent a1 = stubAgent("a1",
                List.of(entity("e1", "Alice", "PERSON")), List.of());
        service = new MultiAgentExtractionService(List.of(a1));

        List<RetrievedDoc> chunks = List.of(new RetrievedDoc("c1", "Text.", Map.of()));
        MergedGraphResult result = service.runExtraction(chunks, null, null, null);

        assertNotNull(result);
        assertEquals(GraphMergeStrategy.UNION, result.strategy());
    }

    @Test
    void persistToGraphWithNullResultReturnsSummaryWithZeros() {
        MultiAgentExtractionService.PersistenceSummary summary =
                service.persistToGraph(null, graphService, null);

        assertNotNull(summary);
        assertEquals(0, summary.entitiesCreated());
        assertEquals(0, summary.edgesCreated());
    }

    @Test
    void persistToGraphCreatesNodesForEntities() {
        Graph graph = new Graph();
        Entity alice = entity("e1", "Alice", "PERSON");
        graph.setEntities(List.of(alice));
        graph.setRelationships(List.of());

        MergedGraphResult result = buildMergedResult(graph, "UNION");

        GraphNode mockNode = GraphNode.builder()
                .nodeId("entity_e1")
                .build();
        when(graphService.createNode(any(NodeLevel.class), anyString(), anyString(),
                any(), any())).thenReturn(mockNode);

        MultiAgentExtractionService.PersistenceSummary summary =
                service.persistToGraph(result, graphService, null);

        assertEquals(1, summary.entitiesCreated());
        assertEquals(0, summary.edgesCreated());
        verify(graphService).createNode(eq(NodeLevel.ENTITY), anyString(), anyString(), any(), any());
    }

    @Test
    void persistToGraphCreatesEdgesWhenBothEndpointsExist() {
        Entity alice = entity("e1", "Alice", "PERSON");
        Entity acme = entity("e2", "Acme", "ORGANIZATION");
        Relationship r = rel("e1", "e2", "WORKS_AT");
        r.setWeight(0.9);

        Graph graph = new Graph();
        graph.setEntities(List.of(alice, acme));
        graph.setRelationships(List.of(r));

        MergedGraphResult result = buildMergedResult(graph, "UNION");

        // Nodes must be returned from createNode so their IDs can be used for edges
        when(graphService.createNode(any(NodeLevel.class), eq("entity:e1"), anyString(),
                any(), any()))
                .thenReturn(GraphNode.builder().nodeId("node-alice").build());
        when(graphService.createNode(any(NodeLevel.class), eq("entity:e2"), anyString(),
                any(), any()))
                .thenReturn(GraphNode.builder().nodeId("node-acme").build());
        when(graphService.createEdge(eq("node-alice"), eq("node-acme"), any(), any(), any()))
                .thenReturn(mock(ai.kompile.knowledgegraph.domain.GraphEdge.class));

        MultiAgentExtractionService.PersistenceSummary summary =
                service.persistToGraph(result, graphService, null);

        assertEquals(2, summary.entitiesCreated());
        assertEquals(1, summary.edgesCreated());
    }

    @Test
    void persistToGraphSkipsEdgeWhenSourceEntityFailed() {
        Entity alice = entity("e1", "Alice", "PERSON");
        Entity acme = entity("e2", "Acme", "ORGANIZATION");
        Relationship r = rel("e1", "e2", "WORKS_AT");

        Graph graph = new Graph();
        graph.setEntities(List.of(alice, acme));
        graph.setRelationships(List.of(r));

        MergedGraphResult result = buildMergedResult(graph, "UNION");

        // Alice creation fails
        when(graphService.createNode(any(), eq("entity:e1"), anyString(), any(), any()))
                .thenThrow(new RuntimeException("DB error"));
        when(graphService.createNode(any(), eq("entity:e2"), anyString(), any(), any()))
                .thenReturn(GraphNode.builder().nodeId("node-acme").build());

        MultiAgentExtractionService.PersistenceSummary summary =
                service.persistToGraph(result, graphService, null);

        assertEquals(1, summary.entitiesCreated());
        assertEquals(1, summary.entitiesSkipped());
        // Edge should be skipped because alice's node is missing
        assertEquals(0, summary.edgesCreated());
        assertEquals(1, summary.edgesSkipped());
    }

    @Test
    void persistToGraphIncludesFactSheetIdInMetadata() {
        Entity alice = entity("e1", "Alice", "PERSON");
        Graph graph = new Graph();
        graph.setEntities(List.of(alice));
        graph.setRelationships(List.of());

        MergedGraphResult result = buildMergedResult(graph, "UNION");
        when(graphService.createNode(any(), anyString(), anyString(), any(), any()))
                .thenReturn(GraphNode.builder().nodeId("n1").build());

        service.persistToGraph(result, graphService, 42L);

        // Verify the metadata map passed to createNode contains factSheetId
        verify(graphService).createNode(any(), anyString(), anyString(), any(),
                argThat(meta -> meta != null && "42".equals(meta.get("factSheetId"))));
    }

    @Test
    void agentInfoRecordHasCorrectFields() {
        RelationExtractionAgent a = stubAgent("my-agent", List.of(), List.of());
        service = new MultiAgentExtractionService(List.of(a));

        MultiAgentExtractionService.AgentInfo info = service.getAvailableAgents().get(0);
        assertEquals("my-agent", info.id());
        assertEquals("stub-my-agent", info.description());
        assertNotNull(info.supportedContentTypes());
    }

    @Test
    void unknownAgentIdInSelectionIsWarned() {
        RelationExtractionAgent a1 = stubAgent("known-agent", List.of(), List.of());
        service = new MultiAgentExtractionService(List.of(a1));

        List<RetrievedDoc> chunks = List.of(new RetrievedDoc("c1", "Text.", Map.of()));
        // Requesting unknown agent should not crash
        MergedGraphResult result = service.runExtraction(
                chunks, List.of("nonexistent-agent"), "UNION", null);

        assertNotNull(result);
        // No agents selected, so result is empty
        assertEquals(0, result.totalEntities());
    }

    // Helper: build a minimal MergedGraphResult from a graph
    private static MergedGraphResult buildMergedResult(Graph graph, String strategy) {
        RelationExtractionAgent stub = stubAgent("test",
                graph.getEntities(), graph.getRelationships());
        DefaultMultiAgentGraphBuilder builder = new DefaultMultiAgentGraphBuilder();
        List<RetrievedDoc> dummy = List.of(new RetrievedDoc("x", "text", Map.of()));
        return builder.buildGraph(dummy, List.of(stub),
                GraphMergeStrategy.valueOf(strategy), ExtractionConfig.defaults());
    }
}
