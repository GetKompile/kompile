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

package ai.kompile.app.services.crawl;

import ai.kompile.app.ingest.domain.IndexingJobHistory;
import ai.kompile.app.ingest.domain.IndexingJobHistory.JobStatus;
import ai.kompile.app.ingest.service.IndexingJobHistoryService;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.core.crawl.graph.archive.ArchiveManifest;
import ai.kompile.core.crawl.graph.archive.CrawlStepArchiveService;
import ai.kompile.crawl.graph.CrawlStepPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Per-step crawl resumability guarantees not covered by the existing
 * {@link CrawlStepArchiveServiceImplTest} and {@link IndexingJobHistoryResumeTest}.
 *
 * <ul>
 *   <li><b>Non-destructive prune</b> — {@code markStepCompleted} drains the step from
 *       {@code archivedSteps} but preserves its entry in the manifest {@code steps} map
 *       (mark, not delete).</li>
 *   <li><b>Multi-step partial completion</b> — the {@code resumable} flag is cleared only
 *       after the last archived step is completed, not after each one.</li>
 *   <li><b>Explicit {@code resumable} flag wins</b> — {@code IndexingJobHistory.isResumable()}
 *       returns {@code true} for any status when the flag is explicitly set {@code true}.</li>
 *   <li><b>Composition: archive snapshot → resume step plan</b> — the archive service's
 *       remaining-step list, fed into {@link CrawlStepPlan}, runs only the failed step and its
 *       transitive dependencies, never re-running already-completed steps.</li>
 * </ul>
 */
@DisplayName("Crawl per-step resumability")
class CrawlResumabilityTest {

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    // ---- helpers ----

    private static UnifiedCrawlJob job(String jobId) {
        UnifiedCrawlRequest req = UnifiedCrawlRequest.builder()
                .name("Resume Test")
                .factSheetId(42L)
                .build();
        return UnifiedCrawlJob.builder().jobId(jobId).request(req).build();
    }

    private static List<Document> docs(int n) {
        List<Document> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new Document("doc-" + i, "text " + i, Map.of()));
        }
        return out;
    }

    // =========================================================================
    // 1. Non-destructive prune
    // =========================================================================

    @Nested
    @DisplayName("Non-destructive prune")
    class NonDestructivePrune {

        /**
         * {@code markStepCompleted} removes a step from {@code archivedSteps} but does NOT
         * delete its entry from the manifest {@code steps} map. The historical record is
         * preserved (mark, not delete) so lineage and chunk counts remain readable.
         */
        @Test
        @DisplayName("completed step is drained from archivedSteps but its steps-map entry is kept")
        void completedStepRemainsInStepsMapAfterDrainFromArchivedList(@TempDir Path stateDir) throws Exception {
            IndexingJobHistoryService histSvc = mock(IndexingJobHistoryService.class);
            CrawlStepArchiveServiceImpl svc =
                    new CrawlStepArchiveServiceImpl(MAPPER, histSvc, stateDir.toString());

            UnifiedCrawlJob j = job("prune-test");
            svc.archive(j, "VECTOR_INDEXING", docs(5), Map.of("enabled", true));
            svc.archive(j, "GRAPH_EXTRACTION", docs(3), Map.of("enabled", true));

            // Both steps visible before any completion.
            CrawlStepArchiveService.ArchivedJobSnapshot before = svc.loadSnapshot("prune-test");
            assertTrue(before.archivedSteps().contains("VECTOR_INDEXING"));
            assertTrue(before.archivedSteps().contains("GRAPH_EXTRACTION"));

            // Complete VECTOR_INDEXING only.
            svc.markStepCompleted("prune-test", "VECTOR_INDEXING");

            // archivedSteps list loses VECTOR_INDEXING.
            CrawlStepArchiveService.ArchivedJobSnapshot after = svc.loadSnapshot("prune-test");
            assertFalse(after.archivedSteps().contains("VECTOR_INDEXING"),
                    "completed step must be removed from archivedSteps");
            assertTrue(after.archivedSteps().contains("GRAPH_EXTRACTION"),
                    "remaining step stays in archivedSteps");

            // Read the raw manifest to confirm the steps map is untouched (non-destructive).
            Path manifestFile = stateDir.resolve("checkpoints")
                    .resolve("crawl-prune-test")
                    .resolve("manifest.json");
            ArchiveManifest manifest = MAPPER.readValue(manifestFile.toFile(), ArchiveManifest.class);

            assertTrue(manifest.getSteps().containsKey("VECTOR_INDEXING"),
                    "Completed step entry must stay in manifest.steps (mark, not delete)");
            assertTrue(manifest.getSteps().containsKey("GRAPH_EXTRACTION"),
                    "Remaining step entry must also be in manifest.steps");

            // The chunk count in the kept entry must match what was written.
            assertEquals(5, manifest.getSteps().get("VECTOR_INDEXING").getChunkCount(),
                    "Chunk count for completed step must be preserved in the steps map");
        }
    }

    // =========================================================================
    // 2. Multi-step partial completion
    // =========================================================================

    @Nested
    @DisplayName("Multi-step partial completion")
    class MultiStepPartialCompletion {

        /**
         * When three steps are archived, completing the first two must NOT clear the resumable
         * flag. Only after the last archived step completes should {@code markJobResumable(false)}
         * be called. This ensures the job stays visible as resumable until all archived work is done.
         */
        @Test
        @DisplayName("resumable flag is cleared only after the last archived step completes")
        void resumableFlagClearedOnlyAfterLastStep(@TempDir Path stateDir) {
            IndexingJobHistoryService histSvc = mock(IndexingJobHistoryService.class);
            CrawlStepArchiveServiceImpl svc =
                    new CrawlStepArchiveServiceImpl(MAPPER, histSvc, stateDir.toString());

            UnifiedCrawlJob j = job("multi-step");
            svc.archive(j, "GRAPH_EXTRACTION", docs(4), Map.of());
            svc.archive(j, "ENTITY_RESOLUTION", docs(2), Map.of());
            svc.archive(j, "VECTOR_INDEXING", docs(6), Map.of());

            // Complete first two — must not yet clear the resumable flag.
            svc.markStepCompleted("multi-step", "GRAPH_EXTRACTION");
            svc.markStepCompleted("multi-step", "ENTITY_RESOLUTION");
            verify(histSvc, never()).markJobResumable(anyString(), eq(false));

            // The remaining step is still in the archived list.
            CrawlStepArchiveService.ArchivedJobSnapshot snap = svc.loadSnapshot("multi-step");
            assertEquals(List.of("VECTOR_INDEXING"), snap.archivedSteps(),
                    "only the last pending step should remain");

            // Complete the final step — NOW the flag should be cleared exactly once.
            svc.markStepCompleted("multi-step", "VECTOR_INDEXING");
            verify(histSvc, times(1)).markJobResumable(eq("crawl-multi-step"), eq(false));

            // Snapshot reflects an empty archived list.
            assertTrue(svc.loadSnapshot("multi-step").archivedSteps().isEmpty(),
                    "all archived steps consumed: list must be empty");
        }

        /**
         * Completing a step that was never archived is a no-op and must not corrupt the manifest
         * or trigger any history service calls.
         */
        @Test
        @DisplayName("completing an unknown step is a no-op")
        void completingUnknownStepIsNoop(@TempDir Path stateDir) {
            IndexingJobHistoryService histSvc = mock(IndexingJobHistoryService.class);
            CrawlStepArchiveServiceImpl svc =
                    new CrawlStepArchiveServiceImpl(MAPPER, histSvc, stateDir.toString());

            UnifiedCrawlJob j = job("noop-job");
            svc.archive(j, "VECTOR_INDEXING", docs(2), Map.of());

            // markStepCompleted for a step that was never archived.
            svc.markStepCompleted("noop-job", "GRAPH_EXTRACTION");

            // VECTOR_INDEXING must still be archived.
            CrawlStepArchiveService.ArchivedJobSnapshot snap = svc.loadSnapshot("noop-job");
            assertTrue(snap.archivedSteps().contains("VECTOR_INDEXING"),
                    "unrelated archived step must survive a no-op completion");

            // The job must NOT have been marked non-resumable.
            verify(histSvc, never()).markJobResumable(anyString(), eq(false));
        }
    }

    // =========================================================================
    // 3. Explicit resumable flag on IndexingJobHistory
    // =========================================================================

    @Nested
    @DisplayName("Explicit resumable flag")
    class ExplicitResumableFlag {

        /**
         * {@code resumable=true} makes {@code isResumable()} return {@code true} for every
         * possible job status — including statuses that would otherwise never qualify
         * (RUNNING, COMPLETED, QUEUED). This is the flag set by the archive service when
         * a crawl step is archived mid-run.
         */
        @Test
        @DisplayName("resumable=true wins for every JobStatus")
        void explicitFlagWinsForEveryStatus() {
            for (JobStatus status : JobStatus.values()) {
                IndexingJobHistory h = IndexingJobHistory.builder()
                        .taskId("flag-" + status)
                        .fileName("doc.pdf")
                        .status(status)
                        // No checkpointPath: the explicit flag must be sufficient on its own.
                        .resumable(true)
                        .startTime(Instant.now())
                        .build();
                assertTrue(h.isResumable(),
                        "resumable=true must win for status=" + status);
            }
        }

        /**
         * Without the explicit flag, RUNNING, COMPLETED, and QUEUED must not be resumable
         * even when a checkpointPath is present.  (Terminal statuses FAILED / CANCELLED /
         * MEMORY_KILLED / PAUSED with a checkpoint are resumable — that is tested in
         * {@link IndexingJobHistoryResumeTest}.)
         */
        @Test
        @DisplayName("without explicit flag, non-terminal statuses are not resumable")
        void withoutFlagNonTerminalStatusesAreNotResumable() {
            for (JobStatus status : List.of(JobStatus.RUNNING, JobStatus.COMPLETED, JobStatus.QUEUED)) {
                IndexingJobHistory h = IndexingJobHistory.builder()
                        .taskId("noflag-" + status)
                        .fileName("doc.pdf")
                        .status(status)
                        .checkpointPath("/tmp/cp")
                        .resumable(false)
                        .startTime(Instant.now())
                        .build();
                assertFalse(h.isResumable(),
                        "status=" + status + " must not be resumable without the explicit flag");
            }
        }
    }

    // =========================================================================
    // 4. Composition: archive snapshot → CrawlStepPlan
    // =========================================================================

    @Nested
    @DisplayName("Resume step plan derived from archive snapshot")
    class ResumeStepPlan {

        /**
         * Scenario: graph steps (GRAPH_EXTRACTION, ENTITY_RESOLUTION, EDGE_COMPUTATION)
         * completed on the first run; VECTOR_INDEXING failed and was archived.
         *
         * A resume request derived from the archive snapshot must RUN VECTOR_INDEXING
         * (and its transitive dependency CHUNKING) while SKIPPING all graph steps.
         * This proves per-step resume: the embedding failure does not force LLM
         * extraction to repeat.
         */
        @Test
        @DisplayName("VECTOR_INDEXING failed: resume skips completed graph steps")
        void vectorIndexingFailed_resumeSkipsGraphSteps(@TempDir Path stateDir) {
            IndexingJobHistoryService histSvc = mock(IndexingJobHistoryService.class);
            CrawlStepArchiveServiceImpl svc =
                    new CrawlStepArchiveServiceImpl(MAPPER, histSvc, stateDir.toString());

            // Archive only VECTOR_INDEXING (graph steps completed = not archived).
            UnifiedCrawlJob j = job("resume-vi");
            svc.archive(j, "VECTOR_INDEXING", docs(10), Map.of("enabled", true));

            // Read the remaining steps from the snapshot — this is what a resume controller does.
            List<String> remaining = svc.loadSnapshot("resume-vi").archivedSteps();
            assertEquals(List.of("VECTOR_INDEXING"), remaining);

            // Build the resume request from the snapshot.
            // strictSteps=true: skip the mandatory graph spine — graph steps already completed.
            UnifiedCrawlRequest resumeReq = UnifiedCrawlRequest.builder()
                    .enabledSteps(new ArrayList<>(remaining))
                    .strictSteps(true)
                    .build();
            CrawlStepPlan plan = CrawlStepPlan.from(resumeReq);

            // The failed step must run.
            assertEquals(CrawlStepPlan.Action.RUN, plan.forStep("VECTOR_INDEXING"),
                    "failed step must RUN on resume");
            // CHUNKING is the only hard dependency of VECTOR_INDEXING.
            assertEquals(CrawlStepPlan.Action.RUN, plan.forStep("CHUNKING"),
                    "hard dep of VECTOR_INDEXING must RUN");

            // Already-completed graph steps must NOT be scheduled to re-run.
            assertEquals(CrawlStepPlan.Action.SKIP, plan.forStep("GRAPH_EXTRACTION"),
                    "completed GRAPH_EXTRACTION must be SKIP");
            assertEquals(CrawlStepPlan.Action.SKIP, plan.forStep("ENTITY_RESOLUTION"),
                    "completed ENTITY_RESOLUTION must be SKIP");
            assertEquals(CrawlStepPlan.Action.SKIP, plan.forStep("EDGE_COMPUTATION"),
                    "completed EDGE_COMPUTATION must be SKIP");
            assertEquals(CrawlStepPlan.Action.SKIP, plan.forStep("ENRICHMENT"),
                    "ENRICHMENT (dep of completed steps) must be SKIP");
        }

        /**
         * Scenario: GRAPH_EXTRACTION completed; ENTITY_RESOLUTION failed and was archived.
         *
         * A resume request for ENTITY_RESOLUTION must also include GRAPH_EXTRACTION (its hard
         * dependency) in the RUN set.  VECTOR_INDEXING and EDGE_COMPUTATION — which are not
         * dependencies of ENTITY_RESOLUTION, nor explicitly selected — must be SKIP.
         */
        @Test
        @DisplayName("ENTITY_RESOLUTION failed: resume includes its dep GRAPH_EXTRACTION, skips VECTOR_INDEXING")
        void entityResolutionFailed_resumeIncludesDepsSkipsIndependentSteps(@TempDir Path stateDir) {
            IndexingJobHistoryService histSvc = mock(IndexingJobHistoryService.class);
            CrawlStepArchiveServiceImpl svc =
                    new CrawlStepArchiveServiceImpl(MAPPER, histSvc, stateDir.toString());

            // Only ENTITY_RESOLUTION is archived (GRAPH_EXTRACTION completed).
            UnifiedCrawlJob j = job("resume-er");
            svc.archive(j, "ENTITY_RESOLUTION", docs(7), Map.of());

            List<String> remaining = svc.loadSnapshot("resume-er").archivedSteps();
            assertEquals(List.of("ENTITY_RESOLUTION"), remaining);

            // strictSteps=true: only run the failed step + its transitive deps, nothing beyond.
            UnifiedCrawlRequest resumeReq = UnifiedCrawlRequest.builder()
                    .enabledSteps(new ArrayList<>(remaining))
                    .strictSteps(true)
                    .build();
            CrawlStepPlan plan = CrawlStepPlan.from(resumeReq);

            // The failed step runs.
            assertEquals(CrawlStepPlan.Action.RUN, plan.forStep("ENTITY_RESOLUTION"),
                    "failed step must RUN on resume");
            // GRAPH_EXTRACTION is a hard dep — plan must pull it into the RUN set.
            assertEquals(CrawlStepPlan.Action.RUN, plan.forStep("GRAPH_EXTRACTION"),
                    "hard dep GRAPH_EXTRACTION of ENTITY_RESOLUTION must RUN");

            // Independent steps not in the failed step's dependency closure must be SKIP.
            assertEquals(CrawlStepPlan.Action.SKIP, plan.forStep("VECTOR_INDEXING"),
                    "VECTOR_INDEXING is independent; must be SKIP");
            assertEquals(CrawlStepPlan.Action.SKIP, plan.forStep("EDGE_COMPUTATION"),
                    "EDGE_COMPUTATION depends on ENTITY_RESOLUTION but is not selected; must be SKIP");
            assertEquals(CrawlStepPlan.Action.SKIP, plan.forStep("ENRICHMENT"),
                    "ENRICHMENT must be SKIP (its deps are not all in plan)");
        }

        /**
         * When both VECTOR_INDEXING and ENTITY_RESOLUTION failed and appear in the archive,
         * a resume request for both must produce a merged plan: VECTOR_INDEXING path and
         * ENTITY_RESOLUTION path both run, EDGE_COMPUTATION and ENRICHMENT remain SKIP
         * because neither was selected.
         */
        @Test
        @DisplayName("two independent paths failed: resume plan merges both closures")
        void twoIndependentPathsFailed_planMergesBothClosures(@TempDir Path stateDir) {
            IndexingJobHistoryService histSvc = mock(IndexingJobHistoryService.class);
            CrawlStepArchiveServiceImpl svc =
                    new CrawlStepArchiveServiceImpl(MAPPER, histSvc, stateDir.toString());

            UnifiedCrawlJob j = job("resume-both");
            svc.archive(j, "VECTOR_INDEXING", docs(8), Map.of());
            svc.archive(j, "ENTITY_RESOLUTION", docs(5), Map.of());

            List<String> remaining = svc.loadSnapshot("resume-both").archivedSteps();
            assertTrue(remaining.contains("VECTOR_INDEXING"));
            assertTrue(remaining.contains("ENTITY_RESOLUTION"));

            // strictSteps=true: each failed step runs with just its dep closure; no mandatory spine.
            UnifiedCrawlRequest resumeReq = UnifiedCrawlRequest.builder()
                    .enabledSteps(new ArrayList<>(remaining))
                    .strictSteps(true)
                    .build();
            CrawlStepPlan plan = CrawlStepPlan.from(resumeReq);

            // Both failed steps run.
            assertEquals(CrawlStepPlan.Action.RUN, plan.forStep("VECTOR_INDEXING"),
                    "VECTOR_INDEXING must RUN");
            assertEquals(CrawlStepPlan.Action.RUN, plan.forStep("ENTITY_RESOLUTION"),
                    "ENTITY_RESOLUTION must RUN");
            // Shared dep of both paths.
            assertEquals(CrawlStepPlan.Action.RUN, plan.forStep("CHUNKING"),
                    "CHUNKING (shared dep) must RUN");
            // GRAPH_EXTRACTION — dep of ENTITY_RESOLUTION path — must also run.
            assertEquals(CrawlStepPlan.Action.RUN, plan.forStep("GRAPH_EXTRACTION"),
                    "GRAPH_EXTRACTION (dep of ENTITY_RESOLUTION) must RUN");

            // Steps NOT in either closure and not explicitly selected must be SKIP.
            assertEquals(CrawlStepPlan.Action.SKIP, plan.forStep("EDGE_COMPUTATION"),
                    "EDGE_COMPUTATION not selected; must be SKIP");
            assertEquals(CrawlStepPlan.Action.SKIP, plan.forStep("ENRICHMENT"),
                    "ENRICHMENT not selected; must be SKIP");
        }
    }
}
