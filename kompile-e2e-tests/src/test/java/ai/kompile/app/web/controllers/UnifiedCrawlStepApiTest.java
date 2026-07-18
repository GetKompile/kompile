/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.web.controllers;

import ai.kompile.core.crawl.graph.UnifiedCrawlService;
import ai.kompile.core.crawl.graph.archive.CrawlStepArchiveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract of the per-step crawl API — the endpoints a step-level testing workflow drives:
 * the step catalog, resumable-job listing, per-step archive, and per-step re-run. Standalone
 * MockMvc over a mocked {@link UnifiedCrawlService}; optional collaborators (job history,
 * hydration orchestrator) are absent, exercising the degraded-mode branches the way a minimal
 * deployment would.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Unified crawl per-step API")
class UnifiedCrawlStepApiTest {

    @Mock
    private UnifiedCrawlService unifiedCrawlService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new UnifiedCrawlController(unifiedCrawlService))
                .build();
    }

    @Test
    @DisplayName("GET /steps returns the pipeline step catalog with flags")
    void stepsCatalogListsPipelineSteps() throws Exception {
        mockMvc.perform(get("/api/unified-crawl/steps"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='GRAPH_EXTRACTION')].archivable").value(hasItem(true)))
                .andExpect(jsonPath("$[?(@.id=='LOADING')].foundational").value(hasItem(true)))
                .andExpect(jsonPath("$[*].id").value(hasItem("CHUNKING")))
                .andExpect(jsonPath("$[*].id").value(hasItem("ENTITY_RESOLUTION")))
                .andExpect(jsonPath("$[*].id").value(hasItem("VECTOR_INDEXING")));
    }

    @Test
    @DisplayName("GET /jobs/resumable maps archived-job summaries")
    void resumableJobsAreListed() throws Exception {
        when(unifiedCrawlService.listResumableCrawlJobs()).thenReturn(List.of(
                new CrawlStepArchiveService.ResumableCrawlJob(
                        "job-1", "procurement crawl", 7L,
                        List.of("VECTOR_INDEXING", "GRAPH_EXTRACTION"),
                        "2026-07-16T10:00:00Z", "/tmp/archive/job-1")));

        mockMvc.perform(get("/api/unified-crawl/jobs/resumable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].jobId").value("job-1"))
                .andExpect(jsonPath("$[0].factSheetId").value(7))
                .andExpect(jsonPath("$[0].archivedSteps").value(hasItem("GRAPH_EXTRACTION")));
    }

    @Test
    @DisplayName("POST archive returns the archive directory for an archivable step")
    void archiveStepReturnsArchiveDir() throws Exception {
        when(unifiedCrawlService.archiveStep("job-1", "VECTOR_INDEXING"))
                .thenReturn("/tmp/archive/job-1/VECTOR_INDEXING");

        mockMvc.perform(post("/api/unified-crawl/jobs/job-1/steps/VECTOR_INDEXING/archive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archiveDir").value("/tmp/archive/job-1/VECTOR_INDEXING"))
                .andExpect(jsonPath("$.stepId").value("VECTOR_INDEXING"));
    }

    @Test
    @DisplayName("POST archive is a 400 when there is nothing to archive")
    void archiveStepRejectsWhenNothingToArchive() throws Exception {
        when(unifiedCrawlService.archiveStep("job-1", "CHUNKING")).thenReturn(null);

        mockMvc.perform(post("/api/unified-crawl/jobs/job-1/steps/CHUNKING/archive"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST run resumes an archived step and reports items processed")
    void runStepResumesFromArchive() throws Exception {
        when(unifiedCrawlService.resumeArchivedStep("job-1", "GRAPH_EXTRACTION")).thenReturn(7);

        mockMvc.perform(post("/api/unified-crawl/jobs/job-1/steps/GRAPH_EXTRACTION/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemsProcessed").value(7))
                .andExpect(jsonPath("$.jobId").value("job-1"));
    }

    @Test
    @DisplayName("POST run for a reasoning step without the orchestrator is a clear 422")
    void runReasoningStepWithoutOrchestratorIsUnprocessable() throws Exception {
        when(unifiedCrawlService.resumeArchivedStep("job-1", "ENRICHMENT")).thenReturn(-1);

        mockMvc.perform(post("/api/unified-crawl/jobs/job-1/steps/ENRICHMENT/run"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST run for a step with no archive and no standalone path is a 422")
    void runUnresumableStepIsUnprocessable() throws Exception {
        when(unifiedCrawlService.resumeArchivedStep("job-1", "CHUNKING")).thenReturn(-1);

        mockMvc.perform(post("/api/unified-crawl/jobs/job-1/steps/CHUNKING/run"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").exists());
    }
}
