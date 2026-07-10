package ai.kompile.graphchangetracking.service;

import ai.kompile.core.graphrag.maintenance.model.GraphPruneResult;
import ai.kompile.graphchangetracking.domain.GraphMutationRecord;
import ai.kompile.graphchangetracking.event.GraphBatchMutationEvent;
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
import org.springframework.data.domain.Pageable;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies that bulk-delete / maintenance methods in
 * {@link EventPublishingKnowledgeGraphService} publish {@link GraphBatchMutationEvent}s and
 * record {@link GraphMutationRecord}s so the grounding cascade fires correctly (audit gap U1).
 *
 * <p>Uses Mockito for the delegate so the test is fully unit-level. A real
 * {@link GraphMutationStore} backed by a TempDir is used to assert record persistence.</p>
 */
@ExtendWith(MockitoExtension.class)
class BulkDeleteEventPublishingTest {

    @TempDir
    Path tempDir;

    @Mock
    private KnowledgeGraphService delegate;

    private final List<Object> publishedEvents = new CopyOnWriteArrayList<>();
    private final ApplicationEventPublisher trackingPublisher = event -> publishedEvents.add(event);

    private EventPublishingKnowledgeGraphService service;
    private GraphMutationStore mutationStore;

    @BeforeEach
    void setUp() {
        publishedEvents.clear();
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        mutationStore = new GraphMutationStore(objectMapper, tempDir.resolve("mutations.jsonl"));
        MutationContextHolder contextHolder = new MutationContextHolder();

        service = new EventPublishingKnowledgeGraphService(
                delegate, trackingPublisher, objectMapper, contextHolder);
        service.setMutationStore(mutationStore);
    }

    // ─── helpers ────────────────────────────────────────────────────────────────

    private List<GraphBatchMutationEvent> batchEvents() {
        return publishedEvents.stream()
                .filter(e -> e instanceof GraphBatchMutationEvent)
                .map(e -> (GraphBatchMutationEvent) e)
                .toList();
    }

    private List<GraphMutationRecord> recordsForFactSheet(Long fsId) {
        return mutationStore
                .findByFactSheetIdOrderByOccurredAtDesc(fsId, Pageable.unpaged())
                .getContent();
    }

    // ─── deleteByFactSheetId ─────────────────────────────────────────────────────

    @Test
    @DisplayName("deleteByFactSheetId: publishes FACT_SHEET_DELETED batch event")
    void deleteByFactSheetId_publishesBatchEvent() {
        doNothing().when(delegate).deleteByFactSheetId(42L);

        service.deleteByFactSheetId(42L);

        List<GraphBatchMutationEvent> events = batchEvents();
        assertEquals(1, events.size(), "Expected exactly 1 GraphBatchMutationEvent");
        GraphBatchMutationEvent evt = events.get(0);
        assertEquals("FACT_SHEET_DELETED", evt.getBatchType());
        assertEquals(42L, evt.getFactSheetId());
        assertEquals(1, evt.getItemCount());
    }

    @Test
    @DisplayName("deleteByFactSheetId: writes a FACT_SHEET_DELETED mutation record")
    void deleteByFactSheetId_writesMutationRecord() {
        doNothing().when(delegate).deleteByFactSheetId(42L);

        service.deleteByFactSheetId(42L);

        List<GraphMutationRecord> records = recordsForFactSheet(42L);
        assertEquals(1, records.size(), "Expected 1 mutation record for fact-sheet deletion");
        GraphMutationRecord rec = records.get(0);
        assertEquals("FACT_SHEET_DELETED", rec.getMutationType());
        assertEquals("FACT_SHEET", rec.getEntityKind());
        assertEquals("42", rec.getEntityId());
        assertEquals(42L, rec.getFactSheetId());
    }

    @Test
    @DisplayName("deleteByFactSheetId: delegate exception still propagates (no silent swallow)")
    void deleteByFactSheetId_delegateExceptionPropagates() {
        doThrow(new RuntimeException("store failure")).when(delegate).deleteByFactSheetId(99L);

        assertThrows(RuntimeException.class, () -> service.deleteByFactSheetId(99L),
                "Delegate exceptions must not be swallowed");
    }

    // ─── pruneNodes ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("pruneNodes (soft-delete, with IDs): publishes NODE_SOFT_DELETED event and per-item records")
    void pruneNodes_softDelete_publishesEventAndRecords() {
        GraphPruneResult pruneResult = GraphPruneResult.ofSoftDelete(List.of("n1", "n2"), false);
        when(delegate.pruneNodes(anyCollection(), eq(true), any(), eq(false)))
                .thenReturn(pruneResult);

        service.pruneNodes(List.of("n1", "n2"), true, Duration.ofMinutes(5), false);

        List<GraphBatchMutationEvent> events = batchEvents();
        assertEquals(1, events.size(), "Expected 1 batch event for pruneNodes");
        GraphBatchMutationEvent evt = events.get(0);
        assertEquals("NODE_SOFT_DELETED", evt.getBatchType());
        assertEquals(2, evt.getItemCount());

        // Two per-item mutation records (factSheetId is null since prune doesn't carry one)
        List<GraphMutationRecord> allRecords = mutationStore.findAll(Pageable.unpaged()).getContent();
        assertEquals(2, allRecords.size(), "Expected 2 per-item mutation records");
        assertTrue(allRecords.stream().allMatch(r -> "NODE_SOFT_DELETED".equals(r.getMutationType())));
        assertTrue(allRecords.stream().allMatch(r -> "NODE".equals(r.getEntityKind())));
        assertTrue(allRecords.stream().map(GraphMutationRecord::getEntityId).toList().containsAll(List.of("n1", "n2")));
    }

    @Test
    @DisplayName("pruneNodes (hard-delete, bulk path, no IDs): publishes event and writes summary record")
    void pruneNodes_hardDelete_bulkPath_publishesSummaryRecord() {
        GraphPruneResult pruneResult = GraphPruneResult.ofHardDelete(50, false);
        when(delegate.pruneNodes(anyCollection(), eq(false), any(), eq(false)))
                .thenReturn(pruneResult);

        service.pruneNodes(List.of(), false, null, false);

        List<GraphBatchMutationEvent> events = batchEvents();
        assertEquals(1, events.size());
        assertEquals("NODE_HARD_DELETED", events.get(0).getBatchType());
        assertEquals(50, events.get(0).getItemCount());

        // Summary record written because affectedIds is empty
        List<GraphMutationRecord> allRecords = mutationStore.findAll(Pageable.unpaged()).getContent();
        assertEquals(1, allRecords.size(), "Expected 1 summary record for bulk-delete path");
        assertTrue(allRecords.get(0).getEntityId().startsWith("bulk:"));
    }

    @Test
    @DisplayName("pruneNodes (dry-run): no event or records published")
    void pruneNodes_dryRun_noEventOrRecords() {
        GraphPruneResult dryResult = GraphPruneResult.ofSoftDelete(List.of("n1"), true);
        when(delegate.pruneNodes(anyCollection(), anyBoolean(), any(), eq(true)))
                .thenReturn(dryResult);

        service.pruneNodes(List.of("n1"), true, Duration.ZERO, true);

        assertTrue(batchEvents().isEmpty(), "No event for dry-run pruneNodes");
        assertEquals(0, mutationStore.findAll(Pageable.unpaged()).getTotalElements(),
                "No records for dry-run pruneNodes");
    }

    @Test
    @DisplayName("pruneNodes (nothing affected): no event published")
    void pruneNodes_nothingAffected_noEvent() {
        when(delegate.pruneNodes(anyCollection(), anyBoolean(), any(), eq(false)))
                .thenReturn(GraphPruneResult.empty(false));

        service.pruneNodes(List.of(), false, null, false);

        assertTrue(batchEvents().isEmpty());
    }

    // ─── pruneEdges ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("pruneEdges (soft-delete): publishes EDGE_SOFT_DELETED event and per-item records")
    void pruneEdges_softDelete_publishesEventAndRecords() {
        GraphPruneResult pruneResult = GraphPruneResult.ofSoftDelete(List.of("e1", "e2", "e3"), false);
        when(delegate.pruneEdges(anyCollection(), eq(true), eq(false))).thenReturn(pruneResult);

        service.pruneEdges(List.of("e1", "e2", "e3"), true, false);

        List<GraphBatchMutationEvent> events = batchEvents();
        assertEquals(1, events.size());
        assertEquals("EDGE_SOFT_DELETED", events.get(0).getBatchType());
        assertEquals(3, events.get(0).getItemCount());

        List<GraphMutationRecord> allRecords = mutationStore.findAll(Pageable.unpaged()).getContent();
        assertEquals(3, allRecords.size());
        assertTrue(allRecords.stream().allMatch(r -> "EDGE".equals(r.getEntityKind())));
    }

    @Test
    @DisplayName("pruneEdges (dry-run): no event or records")
    void pruneEdges_dryRun_noEventOrRecords() {
        when(delegate.pruneEdges(anyCollection(), anyBoolean(), eq(true)))
                .thenReturn(GraphPruneResult.ofSoftDelete(List.of("e1"), true));

        service.pruneEdges(List.of("e1"), true, true);

        assertTrue(batchEvents().isEmpty());
        assertEquals(0, mutationStore.findAll(Pageable.unpaged()).getTotalElements());
    }

    // ─── hardDeleteStaleNodes ───────────────────────────────────────────────────

    @Test
    @DisplayName("hardDeleteStaleNodes: publishes NODES_HARD_DELETED_BULK event + summary record")
    void hardDeleteStaleNodes_publishesEventAndSummaryRecord() {
        GraphPruneResult result = GraphPruneResult.ofHardDelete(7, false);
        when(delegate.hardDeleteStaleNodes(eq(55L), any())).thenReturn(result);

        service.hardDeleteStaleNodes(55L, Duration.ofHours(24));

        List<GraphBatchMutationEvent> events = batchEvents();
        assertEquals(1, events.size());
        assertEquals("NODES_HARD_DELETED_BULK", events.get(0).getBatchType());
        assertEquals(55L, events.get(0).getFactSheetId());
        assertEquals(7, events.get(0).getItemCount());

        List<GraphMutationRecord> records = recordsForFactSheet(55L);
        assertEquals(1, records.size(), "Expected 1 summary record");
        assertEquals("NODES_HARD_DELETED_BULK", records.get(0).getMutationType());
        assertEquals(55L, records.get(0).getFactSheetId());
    }

    @Test
    @DisplayName("hardDeleteStaleNodes: no event when nothing deleted")
    void hardDeleteStaleNodes_nothingDeleted_noEvent() {
        when(delegate.hardDeleteStaleNodes(eq(55L), any()))
                .thenReturn(GraphPruneResult.empty(false));

        service.hardDeleteStaleNodes(55L, Duration.ofHours(1));

        assertTrue(batchEvents().isEmpty());
        assertEquals(0, mutationStore.findAll(Pageable.unpaged()).getTotalElements());
    }

    // ─── deleteEdgesBulk loops through deleteEdge ────────────────────────────────

    @Test
    @DisplayName("deleteEdgesBulk: delegates to deleteEdge for each id (per-item events fire)")
    void deleteEdgesBulk_delegatesPerItem() {
        // getEdge returns empty (no pre-snapshot) — delegate.deleteEdge still called
        when(delegate.getEdge(any())).thenReturn(java.util.Optional.empty());
        doNothing().when(delegate).deleteEdge(anyString());

        service.deleteEdgesBulk(List.of("e1", "e2"));

        // deleteEdge on the delegate is called once per id
        verify(delegate, times(1)).deleteEdge("e1");
        verify(delegate, times(1)).deleteEdge("e2");
    }
}
