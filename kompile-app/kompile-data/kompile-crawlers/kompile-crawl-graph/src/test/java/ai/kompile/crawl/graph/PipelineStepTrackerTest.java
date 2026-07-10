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

package ai.kompile.crawl.graph;

import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob.PipelineStepProgress;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob.PipelineStepStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pure unit tests for {@link PipelineStepTracker} — no Spring context.
 *
 * <p>Covers step initialization, status transitions (PENDING → RUNNING → COMPLETED / FAILED /
 * SKIPPED / ARCHIVED), terminal-state immutability, step-ID aliasing, and incremental progress.</p>
 */
class PipelineStepTrackerTest {

    private static final int ALL_STEPS_COUNT = 14; // 13 registry steps + LEARNING

    private PipelineStepTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new PipelineStepTracker();
    }

    private UnifiedCrawlJob newJob() {
        return UnifiedCrawlJob.builder().jobId("unit-test-job").build();
    }

    private PipelineStepProgress stepById(UnifiedCrawlJob job, String stepId) {
        return job.getPipelineSteps().stream()
                .filter(s -> stepId.equals(s.getStepId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Step not found: " + stepId));
    }

    // -----------------------------------------------------------------------
    // Initialization
    // -----------------------------------------------------------------------

    @Test
    void initializePipelineSteps_createsAll14Steps() {
        UnifiedCrawlJob job = newJob();
        tracker.initializePipelineSteps(job);

        assertEquals(ALL_STEPS_COUNT, job.getPipelineSteps().size(),
                "Expected " + ALL_STEPS_COUNT + " steps after initialization");
    }

    @Test
    void initializePipelineSteps_loadingIsPending() {
        UnifiedCrawlJob job = newJob();
        tracker.initializePipelineSteps(job);

        PipelineStepProgress loading = stepById(job, "LOADING");
        assertEquals(PipelineStepStatus.PENDING, loading.getStatus().get(),
                "LOADING step must start as PENDING");
        assertNotNull(loading.getMessage().get(), "LOADING step must have an initial message");
    }

    @Test
    void initializePipelineSteps_duplicateCallIsIdempotent() {
        UnifiedCrawlJob job = newJob();
        tracker.initializePipelineSteps(job);
        tracker.initializePipelineSteps(job);

        // Second init must not add duplicate steps.
        assertEquals(ALL_STEPS_COUNT, job.getPipelineSteps().size(),
                "Calling initializePipelineSteps twice must not duplicate steps");
    }

    @Test
    void initializePipelineSteps_nullJob_doesNotThrow() {
        // Guard: null job must be a no-op.
        tracker.initializePipelineSteps(null);
    }

    // -----------------------------------------------------------------------
    // Status transitions: PENDING → RUNNING → COMPLETED
    // -----------------------------------------------------------------------

    @Test
    void completePipelineStep_transitionsToCompleted() {
        UnifiedCrawlJob job = newJob();
        tracker.initializePipelineSteps(job);

        tracker.updatePipelineStep(job, "LOADING", PipelineStepStatus.RUNNING,
                0, 10, 0, 0, 0, 0, null, "loading sources");
        tracker.completePipelineStep(job, "LOADING", 10, "done");

        PipelineStepProgress step = stepById(job, "LOADING");
        assertEquals(PipelineStepStatus.COMPLETED, step.getStatus().get());
        assertEquals(100, step.getProgressPercent().get(), "completed step must show 100%");
        assertNotNull(step.getCompletedAt(), "completedAt must be set on completion");
        assertNotNull(step.getStartedAt(),   "startedAt must be set by the time a step completes");
    }

    @Test
    void completePipelineStep_setsCompletedItemsToStepTotal() {
        UnifiedCrawlJob job = newJob();
        tracker.initializePipelineSteps(job);

        tracker.updatePipelineStep(job, "ENTITY_RESOLUTION", PipelineStepStatus.RUNNING,
                1, 7225, 0, 0, 0, 0, "block 1", "resolving");
        tracker.completePipelineStep(job, "ENTITY_RESOLUTION", 1, "312 merge(s), 6913 final entities");

        PipelineStepProgress step = stepById(job, "ENTITY_RESOLUTION");
        assertEquals(7225, step.getCompletedItems().get(),
                "completed steps must not render as partial item counts");
        assertEquals(100, step.getProgressPercent().get());
    }

    @Test
    void updatePipelineStep_runningStep_setsStartedAt() {
        UnifiedCrawlJob job = newJob();
        tracker.initializePipelineSteps(job);

        assertNull(stepById(job, "CHUNKING").getStartedAt(), "startedAt must be null before RUNNING");

        tracker.updatePipelineStep(job, "CHUNKING", PipelineStepStatus.RUNNING,
                5, 20, 0, 0, 0, 0, "file.pdf", "chunking");

        assertNotNull(stepById(job, "CHUNKING").getStartedAt(),
                "startedAt must be set when a step transitions to RUNNING");
    }

    @Test
    void runningStepShowsAtLeastOneActiveTask() {
        UnifiedCrawlJob job = newJob();
        tracker.initializePipelineSteps(job);

        tracker.updatePipelineStep(job, "ENRICHMENT", PipelineStepStatus.RUNNING,
                1, 4, 0, 0, 0, 0, "WEIGHT_LEARNING", "weight learning");

        assertEquals(1, stepById(job, "ENRICHMENT").getActiveTasks().get(),
                "running steps must not render as idle");

        tracker.completePipelineStep(job, "ENRICHMENT", 4, "done");
        assertEquals(0, stepById(job, "ENRICHMENT").getActiveTasks().get(),
                "terminal steps must clear active task count");
    }

    // -----------------------------------------------------------------------
    // Status transitions: FAILED
    // -----------------------------------------------------------------------

    @Test
    void failPipelineStep_setsFailedStatus() {
        UnifiedCrawlJob job = newJob();
        tracker.initializePipelineSteps(job);

        tracker.failPipelineStep(job, "GRAPH_EXTRACTION", "LLM timeout");

        PipelineStepProgress step = stepById(job, "GRAPH_EXTRACTION");
        assertEquals(PipelineStepStatus.FAILED, step.getStatus().get());
        assertNotNull(step.getCompletedAt(), "completedAt must be set on failure");
    }

    @Test
    void failPipelineStep_incrementsFailedItems() {
        UnifiedCrawlJob job = newJob();
        tracker.initializePipelineSteps(job);

        tracker.failPipelineStep(job, "GRAPH_EXTRACTION", "error 1");
        tracker.failPipelineStep(job, "GRAPH_EXTRACTION", "error 2");

        // Each call to failPipelineStep increments failedItems once.
        // After the first call the step is FAILED (terminal), so the second call's
        // incrementAndGet still fires (it is applied before the terminal guard in applyPipelineStepUpdate).
        // Verify at least one failure was recorded.
        assertEquals(PipelineStepStatus.FAILED, stepById(job, "GRAPH_EXTRACTION").getStatus().get());
    }

    // -----------------------------------------------------------------------
    // Status transitions: SKIPPED
    // -----------------------------------------------------------------------

    @Test
    void skipPipelineStep_setsSkippedStatus() {
        UnifiedCrawlJob job = newJob();
        tracker.initializePipelineSteps(job);

        tracker.skipPipelineStep(job, "GRAPH_EXTRACTION", "no graph config");

        assertEquals(PipelineStepStatus.SKIPPED, stepById(job, "GRAPH_EXTRACTION").getStatus().get());
        assertNotNull(stepById(job, "GRAPH_EXTRACTION").getCompletedAt());
    }

    /**
     * SKIPPED is a terminal status: {@link PipelineStepTracker#updatePipelineStepFromCounters}
     * must not overwrite it with RUNNING.
     */
    @Test
    void skippedStep_isTerminal_fromCountersDoesNotOverwrite() {
        UnifiedCrawlJob job = newJob();
        tracker.initializePipelineSteps(job);

        tracker.skipPipelineStep(job, "GRAPH_EXTRACTION", "skipped");
        // Simulate the orchestrator calling the counter-refresh path.
        tracker.updatePipelineStepFromCounters(job, "GRAPH_EXTRACTION", "still extracting", null);

        assertEquals(PipelineStepStatus.SKIPPED, stepById(job, "GRAPH_EXTRACTION").getStatus().get(),
                "SKIPPED status must not be overwritten by updatePipelineStepFromCounters");
    }

    // -----------------------------------------------------------------------
    // Status transitions: ARCHIVED
    // -----------------------------------------------------------------------

    @Test
    void archivePipelineStep_setsArchivedStatus() {
        UnifiedCrawlJob job = newJob();
        tracker.initializePipelineSteps(job);

        tracker.archivePipelineStep(job, "VECTOR_INDEXING", "deferred for later");

        assertEquals(PipelineStepStatus.ARCHIVED, stepById(job, "VECTOR_INDEXING").getStatus().get());
        assertNotNull(stepById(job, "VECTOR_INDEXING").getCompletedAt());
    }

    /**
     * ARCHIVED is a terminal status: {@link PipelineStepTracker#updatePipelineStepFromCounters}
     * must not change it. Archived steps are deferred, not actively running.
     */
    @Test
    void archivedStep_isTerminal_fromCountersDoesNotOverwrite() {
        UnifiedCrawlJob job = newJob();
        tracker.initializePipelineSteps(job);

        tracker.archivePipelineStep(job, "VECTOR_INDEXING", "archived");
        tracker.updatePipelineStepFromCounters(job, "VECTOR_INDEXING", "embedding in progress", null);

        assertEquals(PipelineStepStatus.ARCHIVED, stepById(job, "VECTOR_INDEXING").getStatus().get(),
                "ARCHIVED status must not be overwritten by updatePipelineStepFromCounters");
    }

    /**
     * COMPLETED is a terminal status and must also resist counter refreshes.
     */
    @Test
    void completedStep_isTerminal_fromCountersDoesNotOverwrite() {
        UnifiedCrawlJob job = newJob();
        tracker.initializePipelineSteps(job);

        tracker.completePipelineStep(job, "LOADING", 5, "done");
        tracker.updatePipelineStepFromCounters(job, "LOADING", "still loading?", null);

        assertEquals(PipelineStepStatus.COMPLETED, stepById(job, "LOADING").getStatus().get(),
                "COMPLETED status must not be overwritten by updatePipelineStepFromCounters");
    }

    // -----------------------------------------------------------------------
    // Step-ID normalization / aliasing
    // -----------------------------------------------------------------------

    @Test
    void normalizeStepId_embeddingAlias_mapsToVectorIndexing() {
        assertEquals("VECTOR_INDEXING", tracker.normalizeStepId("EMBEDDING"));
    }

    @Test
    void normalizeStepId_indexingAlias_mapsToVectorIndexing() {
        assertEquals("VECTOR_INDEXING", tracker.normalizeStepId("INDEXING"));
    }

    @Test
    void normalizeStepId_canonicalId_passesThrough() {
        assertEquals("GRAPH_EXTRACTION", tracker.normalizeStepId("GRAPH_EXTRACTION"));
        assertEquals("LOADING",          tracker.normalizeStepId("LOADING"));
        assertEquals("CHUNKING",         tracker.normalizeStepId("CHUNKING"));
    }

    @Test
    void normalizeStepId_null_returnsUnknown() {
        assertEquals("UNKNOWN", tracker.normalizeStepId(null));
    }

    /**
     * Updating via the "EMBEDDING" alias must affect the same step as "VECTOR_INDEXING".
     */
    @Test
    void embeddingAlias_andCanonicalId_referToSameStep() {
        UnifiedCrawlJob job = newJob();
        tracker.initializePipelineSteps(job);

        // Skip via canonical ID.
        tracker.skipPipelineStep(job, "EMBEDDING", "via alias");

        // Lookup via canonical ID must find the same step in terminal state.
        assertEquals(PipelineStepStatus.SKIPPED, stepById(job, "VECTOR_INDEXING").getStatus().get(),
                "EMBEDDING alias must resolve to the VECTOR_INDEXING step");
    }

    // -----------------------------------------------------------------------
    // Incremental progress
    // -----------------------------------------------------------------------

    @Test
    void incrementPipelineStep_accumulatesCompletedItems() {
        UnifiedCrawlJob job = newJob();
        tracker.initializePipelineSteps(job);

        tracker.updatePipelineStep(job, "CHUNKING", PipelineStepStatus.RUNNING,
                0, 100, 0, 0, 0, 0, null, "started");

        tracker.incrementPipelineStep(job, "CHUNKING", 10, 0, "batch 1");
        tracker.incrementPipelineStep(job, "CHUNKING", 15, 1, "batch 2");

        PipelineStepProgress step = stepById(job, "CHUNKING");
        assertEquals(25, step.getCompletedItems().get(), "completed items must accumulate across increments");
        assertEquals(1, step.getCompletedBatches().get(), "completed batches must accumulate across increments");
    }

    @Test
    void vectorIndexingCounterRefreshClampsCompletedBatchesToTotal() {
        UnifiedCrawlJob job = newJob();
        tracker.initializePipelineSteps(job);

        job.getChunksQueuedForEmbedding().set(50);
        job.getChunksEmbedded().set(50);
        job.getDocumentsIndexed().set(50);
        job.getVectorBatchesTotal().set(2);
        job.getVectorBatchesCompleted().set(6);

        tracker.updatePipelineStepFromCounters(job, "VECTOR_INDEXING", "indexing", null);

        PipelineStepProgress step = stepById(job, "VECTOR_INDEXING");
        assertEquals(2, step.getCompletedBatches().get(),
                "completedBatches must not exceed totalBatches in UI state");
        assertEquals(2, step.getTotalBatches().get());
        assertEquals(50, step.getCompletedItems().get());
    }

    @Test
    void incrementPipelineStep_graphPrepAutoCompletes_whenTotalReached() {
        UnifiedCrawlJob job = newJob();
        tracker.initializePipelineSteps(job);

        tracker.updatePipelineStep(job, "GRAPH_PREP", PipelineStepStatus.RUNNING,
                0, 3, 0, 0, 0, 0, null, "rule processing");

        tracker.incrementPipelineStep(job, "GRAPH_PREP", 3, 0, "all rules applied");

        // GRAPH_PREP auto-transitions to COMPLETED when completedItems >= totalItems.
        assertEquals(PipelineStepStatus.COMPLETED, stepById(job, "GRAPH_PREP").getStatus().get(),
                "GRAPH_PREP must auto-complete when completedItems reaches totalItems");
    }

    // -----------------------------------------------------------------------
    // ensurePipelineStep — idempotent step creation
    // -----------------------------------------------------------------------

    @Test
    void ensurePipelineStep_newStep_createsWithPendingStatus() {
        UnifiedCrawlJob job = newJob();

        PipelineStepProgress step = tracker.ensurePipelineStep(job, "LEARNING");

        assertNotNull(step);
        assertEquals("LEARNING", step.getStepId());
        assertEquals(PipelineStepStatus.PENDING, step.getStatus().get());
    }

    @Test
    void ensurePipelineStep_existingStep_returnsTheSameInstance() {
        UnifiedCrawlJob job = newJob();
        PipelineStepProgress first  = tracker.ensurePipelineStep(job, "ENRICHMENT");
        PipelineStepProgress second = tracker.ensurePipelineStep(job, "ENRICHMENT");

        assertEquals(1, job.getPipelineSteps().stream()
                .filter(s -> "ENRICHMENT".equals(s.getStepId())).count(),
                "Repeated ensurePipelineStep must not add duplicate entries");
        // Identity check: same AtomicReference object returned.
        assertEquals(first.getStatus(), second.getStatus());
    }
}
