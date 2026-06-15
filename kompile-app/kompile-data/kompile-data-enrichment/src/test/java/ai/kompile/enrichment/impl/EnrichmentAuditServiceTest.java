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
package ai.kompile.enrichment.impl;

import ai.kompile.enrichment.domain.AuditSummary;
import ai.kompile.enrichment.domain.EnrichmentAuditEntry;
import ai.kompile.enrichment.domain.RevertResult;
import ai.kompile.enrichment.repository.EnrichmentAuditRepository;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EnrichmentAuditServiceTest {

    @Mock
    private EnrichmentAuditRepository auditRepository;

    @Mock
    private KnowledgeGraphService knowledgeGraphService;

    private EnrichmentAuditService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        service = new EnrichmentAuditService(auditRepository, objectMapper);
        // Inject optional knowledgeGraphService via reflection (field is @Autowired(required=false))
        Field kgField = EnrichmentAuditService.class.getDeclaredField("knowledgeGraphService");
        kgField.setAccessible(true);
        kgField.set(service, knowledgeGraphService);
    }

    // ─── logAction ──────────────────────────────────────────────────────────────

    @Test
    void logActionCreatesEntry() {
        EnrichmentAuditEntry saved = EnrichmentAuditEntry.builder()
                .id(1L)
                .auditId("some-uuid")
                .factSheetId(10L)
                .enrichmentJobId("job-1")
                .phase("CLEAN")
                .action("CHUNK_DEDUP")
                .targetNodeId("node-abc")
                .targetType("SNIPPET")
                .description("Removed duplicate chunk")
                .build();

        when(auditRepository.save(any(EnrichmentAuditEntry.class))).thenReturn(saved);

        EnrichmentAuditEntry result = service.logAction(
                10L, "job-1", "CLEAN", "CHUNK_DEDUP",
                "node-abc", "SNIPPET",
                null, null, "Removed duplicate chunk");

        ArgumentCaptor<EnrichmentAuditEntry> captor = ArgumentCaptor.forClass(EnrichmentAuditEntry.class);
        verify(auditRepository).save(captor.capture());

        EnrichmentAuditEntry captured = captor.getValue();
        assertNotNull(captured.getAuditId(), "auditId must be set before save");
        assertEquals(10L, captured.getFactSheetId());
        assertEquals("job-1", captured.getEnrichmentJobId());
        assertEquals("CLEAN", captured.getPhase());
        assertEquals("CHUNK_DEDUP", captured.getAction());
        assertEquals("node-abc", captured.getTargetNodeId());
        assertEquals("SNIPPET", captured.getTargetType());
        assertEquals("Removed duplicate chunk", captured.getDescription());

        // Returned value is whatever the repo returns
        assertEquals("some-uuid", result.getAuditId());
    }

    // ─── getAuditLog routing ─────────────────────────────────────────────────

    @Test
    void getAuditLogByFactSheetOnly() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<EnrichmentAuditEntry> page = new PageImpl<>(List.of());
        when(auditRepository.findByFactSheetIdOrderByCreatedAtDesc(eq(5L), eq(pageable))).thenReturn(page);

        Page<EnrichmentAuditEntry> result = service.getAuditLog(5L, null, null, pageable);

        assertEquals(page, result);
        verify(auditRepository).findByFactSheetIdOrderByCreatedAtDesc(5L, pageable);
        verify(auditRepository, never()).findByFactSheetIdAndEnrichmentJobIdOrderByCreatedAtDesc(any(), any(), any());
        verify(auditRepository, never()).findByFactSheetIdAndEnrichmentJobIdAndPhaseOrderByCreatedAtDesc(any(), any(), any(), any());
    }

    @Test
    void getAuditLogByJobAndPhase() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<EnrichmentAuditEntry> page = new PageImpl<>(List.of());
        when(auditRepository.findByFactSheetIdAndEnrichmentJobIdAndPhaseOrderByCreatedAtDesc(
                eq(5L), eq("job-1"), eq("CLEAN"), eq(pageable))).thenReturn(page);

        Page<EnrichmentAuditEntry> result = service.getAuditLog(5L, "job-1", "CLEAN", pageable);

        assertEquals(page, result);
        verify(auditRepository).findByFactSheetIdAndEnrichmentJobIdAndPhaseOrderByCreatedAtDesc(
                5L, "job-1", "CLEAN", pageable);
        verify(auditRepository, never()).findByFactSheetIdOrderByCreatedAtDesc(any(), any());
        verify(auditRepository, never()).findByFactSheetIdAndEnrichmentJobIdOrderByCreatedAtDesc(any(), any(), any());
    }

    @Test
    void getAuditLogByJobOnly() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<EnrichmentAuditEntry> page = new PageImpl<>(List.of());
        when(auditRepository.findByFactSheetIdAndEnrichmentJobIdOrderByCreatedAtDesc(
                eq(7L), eq("job-2"), eq(pageable))).thenReturn(page);

        Page<EnrichmentAuditEntry> result = service.getAuditLog(7L, "job-2", null, pageable);

        assertEquals(page, result);
        verify(auditRepository).findByFactSheetIdAndEnrichmentJobIdOrderByCreatedAtDesc(7L, "job-2", pageable);
        verify(auditRepository, never()).findByFactSheetIdOrderByCreatedAtDesc(any(), any());
        verify(auditRepository, never()).findByFactSheetIdAndEnrichmentJobIdAndPhaseOrderByCreatedAtDesc(any(), any(), any(), any());
    }

    // ─── getJobSummary ───────────────────────────────────────────────────────

    @Test
    void getJobSummaryAggregates() {
        String jobId = "job-summary";
        List<Object[]> actionRows = List.of(
                new Object[]{"CHUNK_DEDUP", 5L},
                new Object[]{"PRUNE_ENTITY", 3L}
        );
        when(auditRepository.countByActionForJob(jobId)).thenReturn(actionRows);
        when(auditRepository.findDistinctPhasesByJobId(jobId)).thenReturn(List.of("CLEAN", "ORGANIZE"));
        when(auditRepository.countByEnrichmentJobIdAndRevertedTrue(jobId)).thenReturn(1L);

        AuditSummary summary = service.getJobSummary(jobId);

        assertEquals(8L, summary.getTotalActions());
        assertEquals(2, summary.getActionCounts().size());
        assertEquals(5L, summary.getActionCounts().get("CHUNK_DEDUP"));
        assertEquals(3L, summary.getActionCounts().get("PRUNE_ENTITY"));
        assertEquals(1L, summary.getRevertedCount());
        assertEquals(List.of("CLEAN", "ORGANIZE"), summary.getPhases());
    }

    // ─── revertAction ────────────────────────────────────────────────────────

    @Test
    void revertActionNotFound() {
        String auditId = "missing-audit-id";
        when(auditRepository.findByAuditId(auditId)).thenReturn(Optional.empty());

        RevertResult result = service.revertAction(auditId);

        assertNotNull(result.getFailedRevertIds());
        assertTrue(result.getFailedRevertIds().contains(auditId));
        verify(auditRepository, never()).save(any());
    }

    @Test
    void revertActionAlreadyReverted() {
        String auditId = "already-reverted";
        EnrichmentAuditEntry entry = EnrichmentAuditEntry.builder()
                .auditId(auditId)
                .factSheetId(1L)
                .reverted(true)
                .build();
        when(auditRepository.findByAuditId(auditId)).thenReturn(Optional.of(entry));

        RevertResult result = service.revertAction(auditId);

        assertNotNull(result.getWarnings());
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("Already reverted")));
        verify(auditRepository, never()).save(any());
    }

    @Test
    void revertActionDeletedNodeRecreated() throws Exception {
        String auditId = "deleted-node-audit";
        String beforeJson = objectMapper.writeValueAsString(
                java.util.Map.of(
                        "nodeType", "ENTITY",
                        "externalId", "ext-node-1",
                        "title", "Test Entity",
                        "description", "A node that was deleted",
                        "metadataJson", ""
                )
        );

        EnrichmentAuditEntry entry = EnrichmentAuditEntry.builder()
                .auditId(auditId)
                .factSheetId(2L)
                .targetNodeId("node-xyz")
                .targetType("GRAPH_NODE")
                .beforeSnapshot(beforeJson)
                .afterSnapshot(null)   // null afterSnapshot means the node was deleted
                .reverted(false)
                .build();

        when(auditRepository.findByAuditId(auditId)).thenReturn(Optional.of(entry));
        when(auditRepository.save(any(EnrichmentAuditEntry.class))).thenReturn(entry);

        RevertResult result = service.revertAction(auditId);

        verify(knowledgeGraphService).createNode(
                eq(NodeLevel.ENTITY),
                eq("ext-node-1"),
                eq("Test Entity"),
                eq("A node that was deleted"),
                any());

        assertEquals(1, result.getNodesRestored());
        assertEquals(1, result.getActionsReverted());
        assertTrue(result.getFailedRevertIds().isEmpty());

        // Verify entry was marked as reverted
        ArgumentCaptor<EnrichmentAuditEntry> captor = ArgumentCaptor.forClass(EnrichmentAuditEntry.class);
        verify(auditRepository).save(captor.capture());
        assertTrue(captor.getValue().isReverted());
        assertEquals("user", captor.getValue().getRevertedBy());
        assertNotNull(captor.getValue().getRevertedAt());
    }

    @Test
    void revertPhaseRevertsMultipleEntries() {
        String jobId = "job-phase-revert";
        String phase = "CLEAN";

        // Three unreversed entries for the phase
        EnrichmentAuditEntry e1 = EnrichmentAuditEntry.builder()
                .auditId("audit-1").factSheetId(3L).reverted(false)
                .targetType("SNIPPET").build();
        EnrichmentAuditEntry e2 = EnrichmentAuditEntry.builder()
                .auditId("audit-2").factSheetId(3L).reverted(false)
                .targetType("SNIPPET").build();
        EnrichmentAuditEntry e3 = EnrichmentAuditEntry.builder()
                .auditId("audit-3").factSheetId(3L).reverted(false)
                .targetType("SNIPPET").build();

        when(auditRepository.findByEnrichmentJobIdAndPhaseAndRevertedFalseOrderByCreatedAtDesc(jobId, phase))
                .thenReturn(List.of(e1, e2, e3));

        // Each individual revertAction call must find its entry
        when(auditRepository.findByAuditId("audit-1")).thenReturn(Optional.of(e1));
        when(auditRepository.findByAuditId("audit-2")).thenReturn(Optional.of(e2));
        when(auditRepository.findByAuditId("audit-3")).thenReturn(Optional.of(e3));
        when(auditRepository.save(any(EnrichmentAuditEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        RevertResult result = service.revertPhase(jobId, phase);

        assertEquals(3, result.getActionsReverted());
        assertTrue(result.getFailedRevertIds().isEmpty());
    }
}
