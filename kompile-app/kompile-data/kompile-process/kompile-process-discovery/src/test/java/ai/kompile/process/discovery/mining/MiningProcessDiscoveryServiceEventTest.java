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

package ai.kompile.process.discovery.mining;

import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.knowledgegraph.staging.ModelTrainedEvent;
import ai.kompile.process.discovery.mining.log.Event;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;
import ai.kompile.process.discovery.mining.rules.MinedRulePersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Verifies that {@link MiningProcessDiscoveryService} publishes a {@link ModelTrainedEvent} after
 * a successful persist of mined PSL causal rules, and that no event is published on failure paths.
 */
@ExtendWith(MockitoExtension.class)
class MiningProcessDiscoveryServiceEventTest {

    @Mock
    private KnowledgeGraphService graph;

    @Mock
    private ApplicationEventPublisher publisher;

    private MiningProcessDiscoveryService svc;

    @BeforeEach
    void setUp() {
        svc = new MiningProcessDiscoveryService(graph);
    }

    /**
     * Happy path: strong sequence log → causal arcs → rules persisted → ModelTrainedEvent published
     * with type="psl", baseModelId="psl-mined", and an artifact path that exists and is non-empty.
     */
    @Test
    void publishesModelTrainedEventAfterSuccessfulPersist(@TempDir Path dataDir) throws Exception {
        // Build 5 traces of approve→notify→close as a stub graph (strong enough for CAUSES arcs)
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        LocalDateTime base = LocalDateTime.of(2024, 1, 1, 8, 0);
        for (int i = 0; i < 5; i++) {
            GraphNode approve = node("a" + i, "Approve", base.plusMinutes(i * 10));
            GraphNode notify  = node("b" + i, "Notify",  base.plusMinutes(i * 10 + 1));
            GraphNode close   = node("c" + i, "Close",   base.plusMinutes(i * 10 + 2));
            nodes.addAll(List.of(approve, notify, close));
            edges.add(edge("a" + i + "->b" + i, approve, notify));
            edges.add(edge("b" + i + "->c" + i, notify,  close));
        }
        when(graph.getNodesInFactSheet(42L)).thenReturn(nodes);
        when(graph.getEdgesInFactSheet(42L)).thenReturn(edges);
        when(graph.getNode(anyString())).thenReturn(Optional.empty());

        // Real persist service → writes the actual artifact file
        MinedRulePersistenceService realPersist = new MinedRulePersistenceService();
        ReflectionTestUtils.setField(realPersist, "dataDir", dataDir.toString());

        svc.setRulePersistenceService(realPersist);
        ReflectionTestUtils.setField(svc, "dataDir", dataDir.toString());
        svc.setEventPublisher(publisher);

        svc.discoverForFactSheet(42L, 0.0, null);

        // Capture and verify the published event
        ArgumentCaptor<ModelTrainedEvent> captor = ArgumentCaptor.forClass(ModelTrainedEvent.class);
        verify(publisher).publishEvent(captor.capture());
        ModelTrainedEvent evt = captor.getValue();

        assertEquals("psl", evt.getModelType(), "model type must be 'psl'");
        assertEquals(42L, evt.getFactSheetId(), "factSheetId must match");
        assertEquals("psl-mined", evt.getBaseModelId(), "baseModelId must be 'psl-mined'");
        assertNotNull(evt.getArtifactPath(), "artifactPath must not be null");
        assertTrue(Files.exists(evt.getArtifactPath()), "artifact file must exist on disk");
        assertTrue(Files.size(evt.getArtifactPath()) > 0, "artifact file must be non-empty");
    }

    /**
     * No-events path: empty graph → no event log → discoverForFactSheet returns null before
     * reaching the persist block → no ModelTrainedEvent published.
     */
    @Test
    void doesNotPublishWhenEventLogIsEmpty() {
        when(graph.getNodesInFactSheet(99L)).thenReturn(List.of());
        when(graph.getEdgesInFactSheet(99L)).thenReturn(List.of());
        svc.setEventPublisher(publisher);

        assertNull(svc.discoverForFactSheet(99L, 0.0, null),
                "must return null when graph has no events");
        verify(publisher, never()).publishEvent(any());
    }

    /**
     * Persist-skipped path: persist service configured with no dataDir → returns PersistResult.empty
     * (persisted=false) → publish block guarded by result.persisted() → no event published.
     */
    @Test
    void doesNotPublishWhenPersistIsSkipped(@TempDir Path dataDir) throws Exception {
        // Provide the same strong-sequence graph so we reach the persist block
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        LocalDateTime base = LocalDateTime.of(2024, 1, 1, 9, 0);
        for (int i = 0; i < 5; i++) {
            GraphNode approve = node("x" + i, "Approve", base.plusMinutes(i * 10));
            GraphNode notify  = node("y" + i, "Notify",  base.plusMinutes(i * 10 + 1));
            GraphNode close   = node("z" + i, "Close",   base.plusMinutes(i * 10 + 2));
            nodes.addAll(List.of(approve, notify, close));
            edges.add(edge("x" + i + "->y" + i, approve, notify));
            edges.add(edge("y" + i + "->z" + i, notify,  close));
        }
        when(graph.getNodesInFactSheet(7L)).thenReturn(nodes);
        when(graph.getEdgesInFactSheet(7L)).thenReturn(edges);
        when(graph.getNode(anyString())).thenReturn(Optional.empty());

        // Persist service with null dataDir → always returns PersistResult.empty (persisted=false)
        MinedRulePersistenceService noPersist = new MinedRulePersistenceService();
        ReflectionTestUtils.setField(noPersist, "dataDir", null);

        svc.setRulePersistenceService(noPersist);
        // svc.dataDir left null → same guard would block publish even if persisted were true
        svc.setEventPublisher(publisher);

        svc.discoverForFactSheet(7L, 0.0, null);

        verify(publisher, never()).publishEvent(any(ModelTrainedEvent.class));
    }

    @Test
    void buildsDeterministicSemanticContextsFromEventAttributes() {
        EventLog log = new EventLog(List.of(new Trace("case-1", List.of(
                new Event("case-1", "Approve", null, "event-1", Map.of(
                        "relationType", "APPROVED_BY",
                        "sourceType", "FORECAST",
                        "sourceNodeId", "node-123",
                        "confidence", 0.9)),
                new Event("case-1", "Publish", null, "event-2", Map.of(
                        "relationType", "PUBLISHES",
                        "targetType", "REPORT"))))));

        List<ActivityEmbedder.ActivityContext> contexts =
                MiningProcessDiscoveryService.activityEmbeddingContexts(log);

        assertEquals(List.of("Approve", "Publish"),
                contexts.stream().map(ActivityEmbedder.ActivityContext::label).toList());
        assertTrue(contexts.get(0).contexts().get(0).contains("relationType=APPROVED_BY"));
        assertTrue(contexts.get(0).contexts().get(0).contains("sourceType=FORECAST"));
        assertFalse(contexts.get(0).contexts().get(0).contains("node-123"));
        assertFalse(contexts.get(0).contexts().get(0).contains("confidence"));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static GraphNode node(String id, String entityType, LocalDateTime time) {
        return GraphNode.builder()
                .nodeId(id)
                .nodeType(NodeLevel.ENTITY)
                .occurredAt(time)
                .metadataJson("{\"entity_type\":\"" + entityType + "\"}")
                .build();
    }

    private static GraphEdge edge(String edgeId, GraphNode src, GraphNode tgt) {
        return GraphEdge.builder()
                .edgeId(edgeId)
                .sourceNodeId(src.getNodeId())
                .targetNodeId(tgt.getNodeId())
                .sourceNode(src)
                .targetNode(tgt)
                .build();
    }
}
