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

package ai.kompile.knowledgegraph.embedding.service;

import ai.kompile.core.kgembedding.KGEmbeddingAlgorithm;
import ai.kompile.core.kgembedding.KGEmbeddingConfig;
import ai.kompile.knowledgegraph.embedding.domain.KGEmbeddingJob;
import ai.kompile.knowledgegraph.embedding.domain.KGEmbeddingJob.JobStatus;
import ai.kompile.knowledgegraph.embedding.repository.KGEmbeddingJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for KGEmbeddingJobService.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KGEmbeddingJobServiceTest {

    @Mock
    private KGEmbeddingJobRepository jobRepository;

    @Mock
    private KGEmbeddingStorageService storageService;

    private KGEmbeddingJobService service;

    private static final Long FACT_SHEET_ID = 42L;

    private static final KGEmbeddingConfig FAST_CONFIG = KGEmbeddingConfig.builder()
            .embeddingDim(10)
            .epochs(1)
            .learningRate(0.01)
            .batchSize(8)
            .margin(1.0)
            .negativeSamples(2)
            .build();

    @BeforeEach
    void setUp() {
        // No SimpMessagingTemplate needed — pass null (it is @Autowired(required = false))
        service = new KGEmbeddingJobService(jobRepository, storageService, null);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // startTraining
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void startTraining_whenNoRunningJob_createsAndSavesJob() {
        when(jobRepository.hasRunningJob(FACT_SHEET_ID)).thenReturn(false);
        KGEmbeddingJob savedJob = buildJob(FACT_SHEET_ID, JobStatus.PENDING);
        when(jobRepository.save(any())).thenReturn(savedJob);

        KGEmbeddingJob result = service.startTraining(FACT_SHEET_ID, KGEmbeddingAlgorithm.TRANSE, FAST_CONFIG);

        assertNotNull(result);
        verify(jobRepository).save(any(KGEmbeddingJob.class));
    }

    @Test
    void startTraining_whenRunningJobExists_throwsIllegalStateException() {
        when(jobRepository.hasRunningJob(FACT_SHEET_ID)).thenReturn(true);

        assertThrows(IllegalStateException.class,
                () -> service.startTraining(FACT_SHEET_ID, KGEmbeddingAlgorithm.TRANSE, FAST_CONFIG));

        verify(jobRepository, never()).save(any());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // cancelJob
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void cancelJob_forNonExistentJob_returnsFalse() {
        String jobId = UUID.randomUUID().toString();
        when(jobRepository.findByJobId(jobId)).thenReturn(Optional.empty());

        boolean cancelled = service.cancelJob(jobId);
        assertFalse(cancelled);
    }

    @Test
    void cancelJob_forPendingJob_updatesToCancelled_returnsTrue() {
        String jobId = UUID.randomUUID().toString();
        KGEmbeddingJob job = buildJob(FACT_SHEET_ID, JobStatus.PENDING);
        job.setJobId(jobId);

        when(jobRepository.findByJobId(jobId)).thenReturn(Optional.of(job));
        when(jobRepository.save(any())).thenReturn(job);

        boolean cancelled = service.cancelJob(jobId);
        assertTrue(cancelled);
        assertEquals(JobStatus.CANCELLED, job.getStatus());
        verify(jobRepository).save(job);
    }

    @Test
    void cancelJob_forRunningJob_updatesToCancelled_returnsTrue() {
        String jobId = UUID.randomUUID().toString();
        KGEmbeddingJob job = buildJob(FACT_SHEET_ID, JobStatus.RUNNING);
        job.setJobId(jobId);

        when(jobRepository.findByJobId(jobId)).thenReturn(Optional.of(job));
        when(jobRepository.save(any())).thenReturn(job);

        boolean cancelled = service.cancelJob(jobId);
        assertTrue(cancelled);
        assertEquals(JobStatus.CANCELLED, job.getStatus());
    }

    @Test
    void cancelJob_forCompletedJob_returnsFalse() {
        String jobId = UUID.randomUUID().toString();
        KGEmbeddingJob job = buildJob(FACT_SHEET_ID, JobStatus.COMPLETED);
        job.setJobId(jobId);

        when(jobRepository.findByJobId(jobId)).thenReturn(Optional.of(job));

        boolean cancelled = service.cancelJob(jobId);
        assertFalse(cancelled);
        verify(jobRepository, never()).save(any());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getJob
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void getJob_forExistingId_returnsJob() {
        String jobId = "test-job-id";
        KGEmbeddingJob job = buildJob(FACT_SHEET_ID, JobStatus.RUNNING);
        job.setJobId(jobId);

        when(jobRepository.findByJobId(jobId)).thenReturn(Optional.of(job));

        Optional<KGEmbeddingJob> found = service.getJob(jobId);
        assertTrue(found.isPresent());
        assertEquals(jobId, found.get().getJobId());
    }

    @Test
    void getJob_forNonExistentId_returnsEmpty() {
        when(jobRepository.findByJobId("nonexistent")).thenReturn(Optional.empty());

        Optional<KGEmbeddingJob> found = service.getJob("nonexistent");
        assertTrue(found.isEmpty());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getJobs
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void getJobs_returnsPageOfJobs() {
        PageRequest pageable = PageRequest.of(0, 20);
        KGEmbeddingJob job = buildJob(FACT_SHEET_ID, JobStatus.COMPLETED);
        Page<KGEmbeddingJob> page = new PageImpl<>(List.of(job));

        when(jobRepository.findByFactSheetIdOrderByCreatedAtDesc(FACT_SHEET_ID, pageable))
                .thenReturn(page);

        Page<KGEmbeddingJob> result = service.getJobs(FACT_SHEET_ID, pageable);
        assertEquals(1, result.getTotalElements());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getMostRecentCompletedJob
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void getMostRecentCompletedJob_withCompletedJob_returnsJob() {
        KGEmbeddingJob job = buildJob(FACT_SHEET_ID, JobStatus.COMPLETED);
        when(jobRepository.findMostRecentCompletedJob(eq(FACT_SHEET_ID), any()))
                .thenReturn(List.of(job));

        Optional<KGEmbeddingJob> result = service.getMostRecentCompletedJob(FACT_SHEET_ID);
        assertTrue(result.isPresent());
    }

    @Test
    void getMostRecentCompletedJob_withNoCompletedJob_returnsEmpty() {
        when(jobRepository.findMostRecentCompletedJob(eq(FACT_SHEET_ID), any()))
                .thenReturn(Collections.emptyList());

        Optional<KGEmbeddingJob> result = service.getMostRecentCompletedJob(FACT_SHEET_ID);
        assertTrue(result.isEmpty());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // hasRunningJob
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void hasRunningJob_whenRunning_returnsTrue() {
        when(jobRepository.hasRunningJob(FACT_SHEET_ID)).thenReturn(true);
        assertTrue(service.hasRunningJob(FACT_SHEET_ID));
    }

    @Test
    void hasRunningJob_whenNotRunning_returnsFalse() {
        when(jobRepository.hasRunningJob(FACT_SHEET_ID)).thenReturn(false);
        assertFalse(service.hasRunningJob(FACT_SHEET_ID));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // KGEmbeddingJob entity tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void kgEmbeddingJob_getProgressPercent_whenNoEpochs_returnsZero() {
        KGEmbeddingJob job = buildJob(FACT_SHEET_ID, JobStatus.RUNNING);
        job.setEpochs(0);
        job.setCurrentEpoch(null);
        assertEquals(0.0, job.getProgressPercent());
    }

    @Test
    void kgEmbeddingJob_getProgressPercent_calculatesCorrectly() {
        KGEmbeddingJob job = buildJob(FACT_SHEET_ID, JobStatus.RUNNING);
        job.setEpochs(100);
        job.setCurrentEpoch(50);
        assertEquals(50.0, job.getProgressPercent(), 1e-5);
    }

    @Test
    void kgEmbeddingJob_getDurationMs_whenNotStarted_returnsNull() {
        KGEmbeddingJob job = buildJob(FACT_SHEET_ID, JobStatus.PENDING);
        job.setStartedAt(null);
        assertNull(job.getDurationMs());
    }

    @Test
    void kgEmbeddingJob_getDurationMs_whenStartedAndCompleted_returnsPositive() {
        KGEmbeddingJob job = buildJob(FACT_SHEET_ID, JobStatus.COMPLETED);
        job.setStartedAt(Instant.now().minusSeconds(5));
        job.setCompletedAt(Instant.now());

        Long duration = job.getDurationMs();
        assertNotNull(duration);
        assertTrue(duration >= 0);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private KGEmbeddingJob buildJob(Long factSheetId, JobStatus status) {
        return KGEmbeddingJob.builder()
                .jobId(UUID.randomUUID().toString())
                .factSheetId(factSheetId)
                .algorithm(KGEmbeddingAlgorithm.TRANSE)
                .status(status)
                .embeddingDim(100)
                .epochs(100)
                .learningRate(0.01)
                .batchSize(1024)
                .margin(1.0)
                .negativeSamples(10)
                .createdAt(Instant.now())
                .build();
    }
}
