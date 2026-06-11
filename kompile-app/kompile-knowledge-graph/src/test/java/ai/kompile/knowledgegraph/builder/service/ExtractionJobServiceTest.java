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

package ai.kompile.knowledgegraph.builder.service;

import ai.kompile.core.graphbuilder.BuildProgress;
import ai.kompile.core.graphbuilder.ProposedTriple;
import ai.kompile.knowledgegraph.builder.domain.ExtractionJob;
import ai.kompile.knowledgegraph.builder.domain.ExtractionJob.JobStatus;
import ai.kompile.knowledgegraph.builder.domain.ExtractionLogRecord;
import ai.kompile.knowledgegraph.builder.domain.TripleProposal;
import ai.kompile.knowledgegraph.builder.domain.TripleProposal.ProposalStatus;
import ai.kompile.knowledgegraph.builder.repository.ExtractionJobRepository;
import ai.kompile.knowledgegraph.builder.repository.ExtractionLogRepository;
import ai.kompile.knowledgegraph.builder.repository.TripleProposalRepository;
import ai.kompile.knowledgegraph.builder.storage.GraphStorageRegistry;
import ai.kompile.knowledgegraph.builder.storage.GraphStorageStrategy;
import ai.kompile.knowledgegraph.builder.storage.GraphStorageStrategy.StorageResult;
import ai.kompile.knowledgegraph.repository.GraphEdgeRepository;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ExtractionJobService} — job lifecycle (create, start, complete,
 * fail, cancel), proposal CRUD, accept/reject flow, progress callbacks, and statistics.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class ExtractionJobServiceTest {

    @Mock private ExtractionJobRepository jobRepository;
    @Mock private TripleProposalRepository proposalRepository;
    @Mock private ExtractionLogRepository logRepository;
    @Mock private GraphNodeRepository nodeRepository;
    @Mock private GraphEdgeRepository edgeRepository;
    @Mock private GraphStorageRegistry storageRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ExtractionJobService service;

    @BeforeEach
    void setUp() {
        service = new ExtractionJobService(
                jobRepository, proposalRepository, logRepository,
                nodeRepository, edgeRepository, storageRegistry, objectMapper);
    }

    private ExtractionJob pendingJob(String jobId, Long factSheetId) {
        ExtractionJob job = ExtractionJob.builder()
                .factSheetId(factSheetId)
                .builderType("llm")
                .status(JobStatus.PENDING)
                .build();
        // Simulate what @PrePersist would set
        try {
            var f = ExtractionJob.class.getDeclaredField("jobId");
            f.setAccessible(true);
            f.set(job, jobId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return job;
    }

    private ExtractionJob runningJob(String jobId, Long factSheetId) {
        ExtractionJob job = pendingJob(jobId, factSheetId);
        job.start();
        job.setTotalChunks(10);
        return job;
    }

    private TripleProposal pendingProposal(String proposalId, ExtractionJob job) {
        TripleProposal proposal = TripleProposal.builder()
                .job(job)
                .factSheetId(job.getFactSheetId())
                .subjectName("Apple")
                .subjectType("ORG")
                .predicateName("COMPETES_WITH")
                .objectName("Google")
                .objectType("ORG")
                .confidence(0.9)
                .status(ProposalStatus.PENDING)
                .build();
        try {
            var f = TripleProposal.class.getDeclaredField("proposalId");
            f.setAccessible(true);
            f.set(proposal, proposalId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return proposal;
    }

    // ─── Job Lifecycle ─────────────────────────────────────────────────

    @Test
    void createJob_savesWithPendingStatus() {
        when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExtractionJob job = service.createJob(1L, "llm", null);

        assertEquals(JobStatus.PENDING, job.getStatus());
        assertEquals(1L, job.getFactSheetId());
        assertEquals("llm", job.getBuilderType());
        verify(jobRepository).save(any());
    }

    @Test
    void getJob_delegatesToRepository() {
        ExtractionJob job = pendingJob("job-1", 1L);
        when(jobRepository.findByJobId("job-1")).thenReturn(Optional.of(job));

        Optional<ExtractionJob> result = service.getJob("job-1");

        assertTrue(result.isPresent());
        assertEquals("job-1", result.get().getJobId());
    }

    @Test
    void startJob_transitionsToRunning() {
        ExtractionJob job = pendingJob("job-1", 1L);
        when(jobRepository.findByJobId("job-1")).thenReturn(Optional.of(job));
        when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExtractionJob started = service.startJob("job-1", 50);

        assertEquals(JobStatus.RUNNING, started.getStatus());
        assertEquals(50, started.getTotalChunks());
        assertNotNull(started.getStartedAt());
    }

    @Test
    void startJob_unknownJob_throws() {
        when(jobRepository.findByJobId("bad")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.startJob("bad", 10));
    }

    @Test
    void completeJob_transitionsToCompleted() {
        ExtractionJob job = runningJob("job-1", 1L);
        when(jobRepository.findByJobId("job-1")).thenReturn(Optional.of(job));
        when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExtractionJob completed = service.completeJob("job-1");

        assertEquals(JobStatus.COMPLETED, completed.getStatus());
        assertNotNull(completed.getCompletedAt());
    }

    @Test
    void completeJob_withProposalCount_setsCount() {
        ExtractionJob job = runningJob("job-1", 1L);
        when(jobRepository.findByJobId("job-1")).thenReturn(Optional.of(job));
        when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExtractionJob completed = service.completeJob("job-1", 42);

        assertEquals(JobStatus.COMPLETED, completed.getStatus());
        assertEquals(42, completed.getProposalsCreated());
    }

    @Test
    void failJob_transitionsToFailed() {
        ExtractionJob job = runningJob("job-1", 1L);
        when(jobRepository.findByJobId("job-1")).thenReturn(Optional.of(job));
        when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExtractionJob failed = service.failJob("job-1", "LLM timeout");

        assertEquals(JobStatus.FAILED, failed.getStatus());
        assertEquals("LLM timeout", failed.getErrorMessage());
    }

    @Test
    void cancelJob_transitionsToCancelled() {
        ExtractionJob job = runningJob("job-1", 1L);
        when(jobRepository.findByJobId("job-1")).thenReturn(Optional.of(job));
        when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExtractionJob cancelled = service.cancelJob("job-1");

        assertEquals(JobStatus.CANCELLED, cancelled.getStatus());
    }

    @Test
    void cancelJob_terminalJob_throws() {
        ExtractionJob job = runningJob("job-1", 1L);
        job.complete(); // now COMPLETED (terminal)
        when(jobRepository.findByJobId("job-1")).thenReturn(Optional.of(job));

        assertThrows(IllegalStateException.class, () -> service.cancelJob("job-1"));
    }

    // ─── Progress Callbacks ────────────────────────────────────────────

    @Test
    void completeJob_firesProgressCallback() {
        ExtractionJob job = runningJob("job-1", 1L);
        job.setProposalsCreated(5);
        when(jobRepository.findByJobId("job-1")).thenReturn(Optional.of(job));
        when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AtomicReference<BuildProgress> received = new AtomicReference<>();
        service.registerProgressCallback("job-1", received::set);

        service.completeJob("job-1");

        assertNotNull(received.get());
    }

    @Test
    void failJob_firesProgressCallback() {
        ExtractionJob job = runningJob("job-1", 1L);
        when(jobRepository.findByJobId("job-1")).thenReturn(Optional.of(job));
        when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AtomicReference<BuildProgress> received = new AtomicReference<>();
        service.registerProgressCallback("job-1", received::set);

        service.failJob("job-1", "error");

        assertNotNull(received.get());
    }

    @Test
    void cancelJob_removesProgressCallback() {
        ExtractionJob job = runningJob("job-1", 1L);
        when(jobRepository.findByJobId("job-1")).thenReturn(Optional.of(job));
        when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AtomicReference<BuildProgress> received = new AtomicReference<>();
        service.registerProgressCallback("job-1", received::set);

        service.cancelJob("job-1");
        // Callback should have been removed, so completing should NOT fire it again
        assertNull(received.get());
    }

    // ─── hasRunningJob ─────────────────────────────────────────────────

    @Test
    void hasRunningJob_delegatesToRepo() {
        when(jobRepository.hasRunningJob(1L)).thenReturn(true);
        assertTrue(service.hasRunningJob(1L));

        when(jobRepository.hasRunningJob(2L)).thenReturn(false);
        assertFalse(service.hasRunningJob(2L));
    }

    // ─── Proposal Creation ─────────────────────────────────────────────

    @Test
    void createProposals_buildsAndSaves() {
        ExtractionJob job = runningJob("job-1", 1L);
        ProposedTriple triple = new ProposedTriple(
                "Apple", "ORG", "COMPETES_WITH", "Google", "ORG",
                0.9, "chunk-1", "doc-1", "In the mobile market...", null);

        when(proposalRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        List<TripleProposal> proposals = service.createProposals(job, List.of(triple));

        assertEquals(1, proposals.size());
        assertEquals("Apple", proposals.get(0).getSubjectName());
        assertEquals(ProposalStatus.PENDING, proposals.get(0).getStatus());
    }

    @Test
    void createProposals_serializesMetadata() {
        ExtractionJob job = runningJob("job-1", 1L);
        ProposedTriple triple = new ProposedTriple(
                "A", "T", "R", "B", "T",
                0.8, null, null, null, Map.of("key", "value"));

        when(proposalRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        List<TripleProposal> proposals = service.createProposals(job, List.of(triple));

        assertNotNull(proposals.get(0).getMetadataJson());
        assertTrue(proposals.get(0).getMetadataJson().contains("key"));
    }

    @Test
    void createProposalsFromTriples_nullTriples_returnsZero() {
        assertEquals(0, service.createProposalsFromTriples("job-1", 1L, null));
    }

    @Test
    void createProposalsFromTriples_emptyTriples_returnsZero() {
        assertEquals(0, service.createProposalsFromTriples("job-1", 1L, List.of()));
    }

    // ─── Accept/Reject Flow ────────────────────────────────────────────

    @Test
    void acceptProposal_updatesStatusAndStoresGraph() {
        ExtractionJob job = runningJob("job-1", 1L);
        job.setProposalsAccepted(0);
        TripleProposal proposal = pendingProposal("p-1", job);
        when(proposalRepository.findByProposalId("p-1")).thenReturn(Optional.of(proposal));
        when(proposalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        GraphStorageStrategy mockStorage = mock(GraphStorageStrategy.class);
        when(mockStorage.getStorageType()).thenReturn("jpa");
        when(mockStorage.storeProposal(any())).thenReturn(
                new StorageResult("sn1", "on1", "e1", true, null));
        when(storageRegistry.getStrategyWithFallback(isNull())).thenReturn(mockStorage);

        TripleProposal accepted = service.acceptProposal("p-1", "reviewer");

        assertEquals(ProposalStatus.ACCEPTED, accepted.getStatus());
        assertEquals("sn1", accepted.getSubjectNodeId());
        assertEquals("on1", accepted.getObjectNodeId());
        assertEquals("e1", accepted.getEdgeId());
        assertEquals(1, job.getProposalsAccepted());
    }

    @Test
    void acceptProposal_notPending_throws() {
        ExtractionJob job = runningJob("job-1", 1L);
        TripleProposal proposal = pendingProposal("p-1", job);
        proposal.accept("someone"); // already accepted
        when(proposalRepository.findByProposalId("p-1")).thenReturn(Optional.of(proposal));

        assertThrows(IllegalStateException.class,
                () -> service.acceptProposal("p-1", "reviewer"));
    }

    @Test
    void rejectProposal_updatesStatusAndReason() {
        ExtractionJob job = runningJob("job-1", 1L);
        job.setProposalsRejected(0);
        TripleProposal proposal = pendingProposal("p-1", job);
        when(proposalRepository.findByProposalId("p-1")).thenReturn(Optional.of(proposal));
        when(proposalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TripleProposal rejected = service.rejectProposal("p-1", "reviewer", "Low quality");

        assertEquals(ProposalStatus.REJECTED, rejected.getStatus());
        assertEquals("Low quality", rejected.getRejectionReason());
        assertEquals(1, job.getProposalsRejected());
    }

    @Test
    void rejectProposal_notPending_throws() {
        ExtractionJob job = runningJob("job-1", 1L);
        TripleProposal proposal = pendingProposal("p-1", job);
        proposal.reject("someone", "reason"); // already rejected
        when(proposalRepository.findByProposalId("p-1")).thenReturn(Optional.of(proposal));

        assertThrows(IllegalStateException.class,
                () -> service.rejectProposal("p-1", "reviewer", "reason"));
    }

    // ─── Bulk Operations ───────────────────────────────────────────────

    @Test
    void bulkRejectProposals_delegatesToRepo() {
        when(proposalRepository.bulkReject(anyList(), any(), any(), any())).thenReturn(3);

        int result = service.bulkRejectProposals(List.of("p1", "p2", "p3"), "admin", "bad");

        assertEquals(3, result);
    }

    // ─── Storage Info ──────────────────────────────────────────────────

    @Test
    void getAvailableStorageTypes_delegatesToRegistry() {
        when(storageRegistry.getAvailableStorageTypes()).thenReturn(List.of("jpa", "neo4j"));

        List<String> types = service.getAvailableStorageTypes();

        assertEquals(2, types.size());
        assertTrue(types.contains("jpa"));
    }

    @Test
    void getDefaultStorageType_delegatesToRegistry() {
        when(storageRegistry.getDefaultStorageType()).thenReturn("jpa");
        assertEquals("jpa", service.getDefaultStorageType());
    }

    // ─── Log Management ────────────────────────────────────────────────

    @Test
    void saveLog_delegatesToRepo() {
        ExtractionLogRecord log = new ExtractionLogRecord();
        when(logRepository.save(any())).thenReturn(log);

        ExtractionLogRecord saved = service.saveLog(log);
        assertNotNull(saved);
    }

    // ─── Statistics ────────────────────────────────────────────────────

    @Test
    void getJobStatistics_aggregatesFromRepo() {
        when(logRepository.countByJobId("job-1")).thenReturn(100L);
        when(logRepository.countSuccessfulByJobId("job-1")).thenReturn(95L);
        when(logRepository.countFailedByJobId("job-1")).thenReturn(5L);
        when(logRepository.getAverageLatencyByJobId("job-1")).thenReturn(150.0);
        when(logRepository.getTotalTokensByJobId("job-1")).thenReturn(50000L);
        when(logRepository.getTotalEntitiesByJobId("job-1")).thenReturn(200L);
        when(logRepository.getTotalRelationshipsByJobId("job-1")).thenReturn(80L);

        Map<String, Object> stats = service.getJobStatistics("job-1");

        assertEquals(100L, stats.get("totalLogs"));
        assertEquals(95L, stats.get("successfulLogs"));
        assertEquals(5L, stats.get("failedLogs"));
        assertEquals(150.0, stats.get("averageLatencyMs"));
        assertEquals(50000L, stats.get("totalTokens"));
        assertEquals(200L, stats.get("totalEntities"));
        assertEquals(80L, stats.get("totalRelationships"));
    }

    // ─── Cleanup ───────────────────────────────────────────────────────

    @Test
    void cleanupOldJobs_delegatesToRepo() {
        when(jobRepository.deleteOldCompletedJobs(any())).thenReturn(5);

        int deleted = service.cleanupOldJobs(30);

        assertEquals(5, deleted);
        verify(jobRepository).deleteOldCompletedJobs(any());
    }
}
