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

package ai.kompile.knowledgegraph.builder.controller;

import ai.kompile.core.graphbuilder.*;
import ai.kompile.knowledgegraph.builder.domain.ExtractionJob;
import ai.kompile.knowledgegraph.builder.domain.ExtractionLogRecord;
import ai.kompile.knowledgegraph.builder.domain.TripleProposal;
import ai.kompile.knowledgegraph.builder.service.ExtractionJobService;
import ai.kompile.knowledgegraph.builder.service.GraphBuilderRegistry;
import ai.kompile.knowledgegraph.builder.storage.GraphStorageRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link KnowledgeGraphBuilderController} — builder discovery,
 * job management, proposal CRUD, bulk operations, and manual proposals.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class KnowledgeGraphBuilderControllerTest {

    @Mock private GraphBuilderRegistry builderRegistry;
    @Mock private ExtractionJobService jobService;

    private KnowledgeGraphBuilderController controller;

    @BeforeEach
    void setUp() {
        controller = new KnowledgeGraphBuilderController(builderRegistry, jobService);
    }

    private ExtractionJob stubJob(String jobId, Long factSheetId) {
        ExtractionJob job = new ExtractionJob();
        job.setJobId(jobId);
        job.setFactSheetId(factSheetId);
        job.setBuilderType("llm");
        job.setStatus(ExtractionJob.JobStatus.PENDING);
        job.setTotalChunks(10);
        job.setProcessedChunks(0);
        job.setProposalsCreated(0);
        job.setProposalsAccepted(0);
        job.setProposalsRejected(0);
        job.setCreatedAt(LocalDateTime.now());
        return job;
    }

    private TripleProposal stubProposal(String proposalId, Long factSheetId) {
        TripleProposal p = new TripleProposal();
        p.setProposalId(proposalId);
        p.setFactSheetId(factSheetId);
        p.setSubjectName("Alice");
        p.setSubjectType("PERSON");
        p.setPredicateName("WORKS_AT");
        p.setObjectName("Acme");
        p.setObjectType("ORGANIZATION");
        p.setConfidence(0.9);
        p.setStatus(TripleProposal.ProposalStatus.PENDING);
        p.setCreatedAt(LocalDateTime.now());
        return p;
    }

    // ─── Builder Discovery ──────────────────────────────────────────

    @Test
    void listBuilders_returnsOk() {
        GraphBuilderInfo info = new GraphBuilderInfo("llm-1", "LLM Builder", "Desc",
                GraphBuilderType.LLM, true);
        when(builderRegistry.getBuilderInfos()).thenReturn(List.of(info));

        ResponseEntity<List<GraphBuilderInfo>> response = controller.listBuilders();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        assertEquals("llm-1", response.getBody().get(0).id());
    }

    @Test
    void getBuilder_found_returnsOk() {
        GraphBuilderInfo info = new GraphBuilderInfo("llm-1", "LLM Builder", "Desc",
                GraphBuilderType.LLM, true);
        when(builderRegistry.getBuilderInfo("llm-1")).thenReturn(Optional.of(info));

        ResponseEntity<GraphBuilderInfo> response = controller.getBuilder("llm-1");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("LLM Builder", response.getBody().displayName());
    }

    @Test
    void getBuilder_notFound_returns404() {
        when(builderRegistry.getBuilderInfo("missing")).thenReturn(Optional.empty());

        ResponseEntity<GraphBuilderInfo> response = controller.getBuilder("missing");
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    // ─── Storage Types ──────────────────────────────────────────────

    @Test
    void getStorageTypes_returnsOk() {
        when(jobService.getStorageInfo()).thenReturn(List.of(
                new GraphStorageRegistry.StorageInfo("jpa", true, true)));
        when(jobService.getAvailableStorageTypes()).thenReturn(List.of("jpa"));
        when(jobService.getDefaultStorageType()).thenReturn("jpa");

        ResponseEntity<Map<String, Object>> response = controller.getStorageTypes();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("jpa", response.getBody().get("defaultType"));
    }

    // ─── Job Management ─────────────────────────────────────────────

    @Test
    void startJob_success() {
        when(builderRegistry.hasBuilder("llm")).thenReturn(true);
        when(jobService.hasRunningJob(1L)).thenReturn(false);
        ExtractionJob job = stubJob("job-1", 1L);
        when(jobService.createJob(eq(1L), eq("llm"), any())).thenReturn(job);

        var request = new KnowledgeGraphBuilderController.StartJobRequest(1L, "llm", null, null);
        var response = controller.startJob(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("job-1", response.getBody().jobId());
    }

    @Test
    void startJob_unknownBuilder_returns400() {
        when(builderRegistry.hasBuilder("unknown")).thenReturn(false);
        when(builderRegistry.getBuilderByTypeString("unknown")).thenReturn(Optional.empty());

        var request = new KnowledgeGraphBuilderController.StartJobRequest(1L, "unknown", null, null);
        var response = controller.startJob(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody().errorMessage());
    }

    @Test
    void startJob_existingRunningJob_returns400() {
        when(builderRegistry.hasBuilder("llm")).thenReturn(true);
        when(jobService.hasRunningJob(1L)).thenReturn(true);

        var request = new KnowledgeGraphBuilderController.StartJobRequest(1L, "llm", null, null);
        var response = controller.startJob(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void getJobStatus_found_returnsOk() {
        ExtractionJob job = stubJob("job-1", 1L);
        when(jobService.getJob("job-1")).thenReturn(Optional.of(job));

        var response = controller.getJobStatus("job-1");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("job-1", response.getBody().jobId());
    }

    @Test
    void getJobStatus_notFound_returns404() {
        when(jobService.getJob("missing")).thenReturn(Optional.empty());

        var response = controller.getJobStatus("missing");
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void getJobs_returnsPaginatedResults() {
        ExtractionJob job = stubJob("job-1", 1L);
        Page<ExtractionJob> page = new PageImpl<>(List.of(job));
        when(jobService.getJobsForFactSheet(eq(1L), any(Pageable.class))).thenReturn(page);

        var response = controller.getJobs(1L, 0, 20);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().getTotalElements());
    }

    @Test
    void cancelJob_success() {
        ExtractionJob job = stubJob("job-1", 1L);
        job.setStatus(ExtractionJob.JobStatus.CANCELLED);
        when(jobService.cancelJob("job-1")).thenReturn(job);

        var response = controller.cancelJob("job-1");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(true, response.getBody().get("cancelled"));
    }

    @Test
    void cancelJob_notFound_returns404() {
        when(jobService.cancelJob("missing")).thenThrow(new IllegalArgumentException("Not found"));

        var response = controller.cancelJob("missing");
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void cancelJob_invalidState_returns400() {
        when(jobService.cancelJob("job-done")).thenThrow(new IllegalStateException("Already completed"));

        var response = controller.cancelJob("job-done");
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(false, response.getBody().get("cancelled"));
    }

    @Test
    void getJobStatistics_found_returnsOk() {
        when(jobService.getJob("job-1")).thenReturn(Optional.of(stubJob("job-1", 1L)));
        when(jobService.getJobStatistics("job-1")).thenReturn(Map.of("totalProposals", 5));

        var response = controller.getJobStatistics("job-1");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(5, response.getBody().get("totalProposals"));
    }

    @Test
    void getJobStatistics_notFound_returns404() {
        when(jobService.getJob("missing")).thenReturn(Optional.empty());

        var response = controller.getJobStatistics("missing");
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    // ─── Extraction Logs ────────────────────────────────────────────

    @Test
    void getJobLogs_returnsPage() {
        ExtractionLogRecord log = ExtractionLogRecord.builder()
                .chunkId("c-1")
                .promptText("prompt")
                .success(true)
                .createdAt(LocalDateTime.now())
                .build();
        Page<ExtractionLogRecord> page = new PageImpl<>(List.of(log));
        when(jobService.getLogsForJob(eq("job-1"), any(Pageable.class))).thenReturn(page);

        var response = controller.getJobLogs("job-1", 0, 50);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().getTotalElements());
    }

    @Test
    void getLogForChunk_found_returnsOk() {
        ExtractionLogRecord log = ExtractionLogRecord.builder()
                .chunkId("c-1")
                .promptText("prompt")
                .success(true)
                .createdAt(LocalDateTime.now())
                .build();
        when(jobService.getLogForChunk("job-1", "c-1")).thenReturn(Optional.of(log));

        var response = controller.getLogForChunk("job-1", "c-1");
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void getLogForChunk_notFound_returns404() {
        when(jobService.getLogForChunk("job-1", "missing")).thenReturn(Optional.empty());

        var response = controller.getLogForChunk("job-1", "missing");
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    // ─── Proposals ──────────────────────────────────────────────────

    @Test
    void getProposals_byJobId_returnsPage() {
        TripleProposal p = stubProposal("p-1", 1L);
        Page<TripleProposal> page = new PageImpl<>(List.of(p));
        when(jobService.getProposalsForJob(eq("job-1"), any(Pageable.class))).thenReturn(page);

        var response = controller.getProposals("job-1", null, null, null, 0, 50);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().getTotalElements());
    }

    @Test
    void getProposals_byFactSheetId_returnsPage() {
        TripleProposal p = stubProposal("p-1", 1L);
        Page<TripleProposal> page = new PageImpl<>(List.of(p));
        when(jobService.getProposalsForFactSheet(eq(1L), any(Pageable.class))).thenReturn(page);

        var response = controller.getProposals(null, 1L, null, null, 0, 50);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void getProposals_byFactSheetAndStatus_returnsPage() {
        Page<TripleProposal> page = new PageImpl<>(List.of());
        when(jobService.getProposalsByStatus(eq(1L), eq(TripleProposal.ProposalStatus.PENDING), any(Pageable.class)))
                .thenReturn(page);

        var response = controller.getProposals(null, 1L, "PENDING", null, 0, 50);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void getProposals_withQuery_searchesProposals() {
        Page<TripleProposal> page = new PageImpl<>(List.of());
        when(jobService.searchProposals(eq(1L), eq("Alice"), any(Pageable.class))).thenReturn(page);

        var response = controller.getProposals(null, 1L, null, "Alice", 0, 50);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(jobService).searchProposals(eq(1L), eq("Alice"), any());
    }

    @Test
    void getProposals_noParams_returns400() {
        var response = controller.getProposals(null, null, null, null, 0, 50);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void getProposal_found_returnsOk() {
        TripleProposal p = stubProposal("p-1", 1L);
        when(jobService.getProposal("p-1")).thenReturn(Optional.of(p));

        var response = controller.getProposal("p-1");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Alice", response.getBody().subjectName());
    }

    @Test
    void getProposal_notFound_returns404() {
        when(jobService.getProposal("missing")).thenReturn(Optional.empty());

        var response = controller.getProposal("missing");
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    // ─── Accept/Reject Proposals ────────────────────────────────────

    @Test
    void acceptProposal_success() {
        TripleProposal p = stubProposal("p-1", 1L);
        p.setSubjectNodeId("n-1");
        p.setObjectNodeId("n-2");
        p.setEdgeId("e-1");
        when(jobService.acceptProposal("p-1", "user", "jpa")).thenReturn(p);

        var response = controller.acceptProposal("p-1", "user", "jpa");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(true, response.getBody().get("accepted"));
        assertEquals("n-1", response.getBody().get("subjectNodeId"));
    }

    @Test
    void acceptProposal_notFound_returns404() {
        when(jobService.acceptProposal("missing", "user", null))
                .thenThrow(new IllegalArgumentException("Not found"));

        var response = controller.acceptProposal("missing", "user", null);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void acceptProposal_invalidState_returns400() {
        when(jobService.acceptProposal("p-done", "user", null))
                .thenThrow(new IllegalStateException("Already accepted"));

        var response = controller.acceptProposal("p-done", "user", null);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(false, response.getBody().get("accepted"));
    }

    @Test
    void rejectProposal_success() {
        TripleProposal p = stubProposal("p-1", 1L);
        when(jobService.rejectProposal("p-1", "user", "Low quality")).thenReturn(p);

        var request = new KnowledgeGraphBuilderController.RejectRequest("Low quality", "user");
        var response = controller.rejectProposal("p-1", request);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(true, response.getBody().get("rejected"));
    }

    @Test
    void rejectProposal_nullBody_usesDefaults() {
        TripleProposal p = stubProposal("p-1", 1L);
        when(jobService.rejectProposal("p-1", "user", null)).thenReturn(p);

        var response = controller.rejectProposal("p-1", null);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void rejectProposal_notFound_returns404() {
        when(jobService.rejectProposal("missing", "user", null))
                .thenThrow(new IllegalArgumentException("Not found"));

        var response = controller.rejectProposal("missing", null);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    // ─── Bulk Operations ────────────────────────────────────────────

    @Test
    void bulkAccept_returnsAcceptedCount() {
        when(jobService.bulkAcceptProposals(anyList(), eq("user"), eq("jpa"))).thenReturn(3);
        when(jobService.getDefaultStorageType()).thenReturn("jpa");

        var request = new KnowledgeGraphBuilderController.BulkActionRequest(
                List.of("p-1", "p-2", "p-3"), "user", "jpa");
        var response = controller.bulkAccept(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(3, response.getBody().get("accepted"));
        assertEquals(3, response.getBody().get("total"));
        assertEquals(0, response.getBody().get("failed"));
    }

    @Test
    void bulkAccept_partialFailure_reportsFailed() {
        when(jobService.bulkAcceptProposals(anyList(), eq("user"), eq("jpa"))).thenReturn(1);
        when(jobService.getDefaultStorageType()).thenReturn("jpa");

        var request = new KnowledgeGraphBuilderController.BulkActionRequest(
                List.of("p-1", "p-2"), "user", "jpa");
        var response = controller.bulkAccept(request);

        assertEquals(1, response.getBody().get("accepted"));
        assertEquals(1, response.getBody().get("failed"));
    }

    @Test
    void bulkReject_returnsRejectedCount() {
        when(jobService.bulkRejectProposals(anyList(), eq("user"), eq("Bad quality"))).thenReturn(2);

        var request = new KnowledgeGraphBuilderController.BulkRejectRequest(
                List.of("p-1", "p-2"), "user", "Bad quality");
        var response = controller.bulkReject(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(2, response.getBody().get("rejected"));
    }

    // ─── Manual Proposal ────────────────────────────────────────────

    @Test
    void createManualProposal_success() {
        TripleProposal p = stubProposal("p-manual", 1L);
        when(jobService.createManualProposal(eq(1L), eq("Alice"), eq("PERSON"),
                eq("WORKS_AT"), eq("Acme"), eq("ORG"), eq("Employment"), eq(false)))
                .thenReturn(p);

        var request = new KnowledgeGraphBuilderController.ManualProposalRequest(
                1L, "Alice", "PERSON", "WORKS_AT", "Acme", "ORG", "Employment", false);
        var response = controller.createManualProposal(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("p-manual", response.getBody().proposalId());
    }

    @Test
    void createManualProposal_autoAcceptNull_treatsFalse() {
        TripleProposal p = stubProposal("p-manual", 1L);
        when(jobService.createManualProposal(anyLong(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), eq(false)))
                .thenReturn(p);

        var request = new KnowledgeGraphBuilderController.ManualProposalRequest(
                1L, "A", "B", "C", "D", "E", "F", null);
        var response = controller.createManualProposal(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    // ─── Response Record Mapping ────────────────────────────────────

    @Test
    void extractionJobResponse_from_mapsAllFields() {
        ExtractionJob job = stubJob("job-1", 1L);
        job.setStatus(ExtractionJob.JobStatus.RUNNING);
        job.setStartedAt(LocalDateTime.of(2025, 6, 1, 10, 0));
        job.setTotalChunks(100);
        job.setProcessedChunks(50);

        var response = KnowledgeGraphBuilderController.ExtractionJobResponse.from(job);

        assertEquals("job-1", response.jobId());
        assertEquals(1L, response.factSheetId());
        assertEquals("llm", response.builderType());
        assertEquals("RUNNING", response.status());
        assertEquals(100, response.totalChunks());
        assertEquals(50, response.processedChunks());
    }

    @Test
    void extractionJobResponse_errorConstructor() {
        var response = new KnowledgeGraphBuilderController.ExtractionJobResponse(null, "Error msg");
        assertNull(response.jobId());
        assertEquals("Error msg", response.errorMessage());
    }

    @Test
    void tripleProposalResponse_from_mapsAllFields() {
        TripleProposal p = stubProposal("p-1", 1L);
        p.setSourceChunkId("chunk-1");
        p.setSourceContext("Alice works at Acme");

        var response = KnowledgeGraphBuilderController.TripleProposalResponse.from(p);

        assertEquals("p-1", response.proposalId());
        assertEquals("Alice", response.subjectName());
        assertEquals("PERSON", response.subjectType());
        assertEquals("WORKS_AT", response.predicateName());
        assertEquals("Acme", response.objectName());
        assertEquals(0.9, response.confidence());
        assertEquals("PENDING", response.status());
    }

    @Test
    void extractionLogResponse_from_mapsFields() {
        ExtractionLogRecord log = ExtractionLogRecord.builder()
                .id(1L)
                .chunkId("c-1")
                .documentId("doc-1")
                .promptText("prompt")
                .responseText("response")
                .inputText("input")
                .entitiesCount(3)
                .relationshipsCount(2)
                .modelProvider("openai")
                .modelName("gpt-4")
                .latencyMs(500L)
                .success(true)
                .createdAt(LocalDateTime.of(2025, 6, 1, 10, 0))
                .build();

        var response = KnowledgeGraphBuilderController.ExtractionLogResponse.from(log);

        assertEquals(1L, response.id());
        assertEquals("c-1", response.chunkId());
        assertEquals("openai", response.modelProvider());
        assertEquals(3, response.entitiesCount());
        assertTrue(response.success());
    }
}
