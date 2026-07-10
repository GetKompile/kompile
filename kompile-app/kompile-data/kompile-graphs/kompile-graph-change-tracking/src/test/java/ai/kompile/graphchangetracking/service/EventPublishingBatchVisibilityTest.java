package ai.kompile.graphchangetracking.service;

import ai.kompile.graphchangetracking.domain.GraphMutationRecord;
import ai.kompile.graphchangetracking.event.GraphBatchMutationEvent;
import ai.kompile.knowledgegraph.domain.*;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests verifying that batch write methods in {@link EventPublishingKnowledgeGraphService}
 * produce per-item mutation records AND a single coalesced {@link GraphBatchMutationEvent}.
 */
@ExtendWith(MockitoExtension.class)
class EventPublishingBatchVisibilityTest {

    @TempDir
    Path tempDir;

    @Mock private KnowledgeGraphService delegate;

    /** Real event publisher that captures all published events for assertion. */
    private final List<Object> publishedEvents = new CopyOnWriteArrayList<>();
    private final ApplicationEventPublisher trackingPublisher = event -> publishedEvents.add(event);

    private EventPublishingKnowledgeGraphService service;
    private GraphMutationStore mutationStore;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        publishedEvents.clear();
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // GraphMutationStore using TempDir
        mutationStore = new GraphMutationStore(objectMapper, tempDir.resolve("graph-mutations.jsonl"));

        MutationContextHolder contextHolder = new MutationContextHolder();

        service = new EventPublishingKnowledgeGraphService(
                delegate, trackingPublisher, objectMapper, contextHolder);
        service.setMutationStore(mutationStore);
    }

    private List<GraphBatchMutationEvent> batchEvents() {
        return publishedEvents.stream()
                .filter(e -> e instanceof GraphBatchMutationEvent)
                .map(e -> (GraphBatchMutationEvent) e)
                .toList();
    }

    // ─── createNodesBatch ────────────────────────────────────────────────────────

    @Test
    @DisplayName("createNodesBatch: records one mutation entry per node + one coalesced event")
    void createNodesBatch_recordsPerItemAndOneEvent() {
        GraphNode n1 = makeNode("n1", "Node1", 7L);
        GraphNode n2 = makeNode("n2", "Node2", 7L);
        when(delegate.createNodesBatch(anyList(), eq(7L))).thenReturn(List.of(n1, n2));

        service.createNodesBatch(List.of(
                new KnowledgeGraphService.NodeSpec(NodeLevel.ENTITY, "ext1", "Node1", null, Map.of()),
                new KnowledgeGraphService.NodeSpec(NodeLevel.ENTITY, "ext2", "Node2", null, Map.of())
        ), 7L);

        // Exactly one coalesced batch event published.
        List<GraphBatchMutationEvent> events = batchEvents();
        assertEquals(1, events.size(), "Expected exactly 1 GraphBatchMutationEvent");
        GraphBatchMutationEvent batchEvent = events.get(0);
        assertEquals("NODES_CREATED", batchEvent.getBatchType());
        assertEquals(7L, batchEvent.getFactSheetId());
        assertEquals(2, batchEvent.getItemCount());

        // Two per-item mutation records in the store
        List<GraphMutationRecord> records = mutationStore
                .findByFactSheetIdOrderByOccurredAtDesc(7L, org.springframework.data.domain.Pageable.unpaged())
                .getContent();
        assertEquals(2, records.size(), "Expected 2 mutation records for 2 batch nodes");
        assertTrue(records.stream().allMatch(r -> "NODE_CREATED".equals(r.getMutationType())));
        assertTrue(records.stream().allMatch(r -> "NODE".equals(r.getEntityKind())));
    }

    @Test
    @DisplayName("createNodesBatch: empty result does NOT publish event or save records")
    void createNodesBatch_emptyResult_noEventOrRecords() {
        when(delegate.createNodesBatch(anyList(), eq(5L))).thenReturn(List.of());

        service.createNodesBatch(List.of(), 5L);

        assertTrue(batchEvents().isEmpty(), "No batch event should be published for empty result");
        assertEquals(0, mutationStore.findAll(org.springframework.data.domain.Pageable.unpaged())
                .getTotalElements());
    }

    @Test
    @DisplayName("createNodesBatch: event/log failure does NOT break the mutation result")
    void createNodesBatch_eventFailure_doesNotBreakMutation() {
        GraphNode n1 = makeNode("n1", "N1", 3L);
        when(delegate.createNodesBatch(anyList(), eq(3L))).thenReturn(List.of(n1));
        // Use a publisher that throws to simulate "event bus down"
        EventPublishingKnowledgeGraphService throwingService = new EventPublishingKnowledgeGraphService(
                delegate, e -> { throw new RuntimeException("event bus down"); },
                objectMapper, new MutationContextHolder());

        // Should NOT throw — failures in event publishing are caught and logged
        assertDoesNotThrow(() -> throwingService.createNodesBatch(
                List.of(new KnowledgeGraphService.NodeSpec(NodeLevel.ENTITY, null, "N1", null, Map.of())),
                3L));
    }

    // ─── createEdgesBatch ────────────────────────────────────────────────────────

    @Test
    @DisplayName("createEdgesBatch: records per-edge entries + one coalesced event")
    void createEdgesBatch_recordsPerItemAndOneEvent() {
        when(delegate.createEdgesBatch(anyList())).thenReturn(2);

        List<KnowledgeGraphService.EdgeSpec> specs = List.of(
                new KnowledgeGraphService.EdgeSpec("a", "b", EdgeType.USER_DEFINED, 1.0,
                        null, "REL", null, null, 7L),
                new KnowledgeGraphService.EdgeSpec("c", "d", EdgeType.CITATION, 0.8,
                        null, null, null, null, 7L)
        );

        service.createEdgesBatch(specs);

        // One coalesced batch event
        List<GraphBatchMutationEvent> events = batchEvents();
        assertEquals(1, events.size(), "Expected exactly 1 GraphBatchMutationEvent");
        GraphBatchMutationEvent batchEvent = events.get(0);
        assertEquals("EDGES_CREATED", batchEvent.getBatchType());
        assertEquals(7L, batchEvent.getFactSheetId());
        assertEquals(2, batchEvent.getItemCount());

        // Two per-item mutation records
        List<GraphMutationRecord> records = mutationStore
                .findByFactSheetIdOrderByOccurredAtDesc(7L, org.springframework.data.domain.Pageable.unpaged())
                .getContent();
        assertEquals(2, records.size());
        assertTrue(records.stream().allMatch(r -> "EDGE_CREATED".equals(r.getMutationType())));
        assertTrue(records.stream().allMatch(r -> "EDGE".equals(r.getEntityKind())));
    }

    @Test
    @DisplayName("createEdgesBatch: zero rows created does NOT publish event")
    void createEdgesBatch_zeroCreated_noEvent() {
        when(delegate.createEdgesBatch(anyList())).thenReturn(0);

        service.createEdgesBatch(List.of(
                new KnowledgeGraphService.EdgeSpec("a", "b", EdgeType.USER_DEFINED, 1.0,
                        null, null, null, null, null)
        ));

        assertTrue(batchEvents().isEmpty(), "No batch event should be published when 0 rows created");
    }

    // ─── updateNodesBatch ────────────────────────────────────────────────────────

    @Test
    @DisplayName("updateNodesBatch: records per-item entries + one coalesced event")
    void updateNodesBatch_recordsAndEvent() {
        when(delegate.updateNodesBatch(anyList())).thenReturn(2);

        service.updateNodesBatch(List.of(
                new KnowledgeGraphService.NodeUpdate("n1", "Title1", "Desc1", Map.of()),
                new KnowledgeGraphService.NodeUpdate("n2", "Title2", "Desc2", Map.of())
        ));

        List<GraphBatchMutationEvent> events = batchEvents();
        assertEquals(1, events.size(), "Expected exactly 1 GraphBatchMutationEvent");
        GraphBatchMutationEvent batchEvent = events.get(0);
        assertEquals("NODES_UPDATED", batchEvent.getBatchType());
        assertNull(batchEvent.getFactSheetId()); // updateNodesBatch doesn't carry factSheetId

        // Two per-item records
        List<GraphMutationRecord> records = new ArrayList<>(
                mutationStore.findByEntityKindAndEntityIdOrderByOccurredAtDesc("NODE", "n1"));
        records.addAll(mutationStore.findByEntityKindAndEntityIdOrderByOccurredAtDesc("NODE", "n2"));
        assertEquals(2, records.size());
        assertTrue(records.stream().allMatch(r -> "NODE_UPDATED".equals(r.getMutationType())));
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private static GraphNode makeNode(String id, String title, Long factSheetId) {
        GraphNode n = new GraphNode();
        n.setNodeId(id);
        n.setTitle(title);
        n.setNodeType(NodeLevel.ENTITY);
        n.setFactSheetId(factSheetId);
        return n;
    }
}
