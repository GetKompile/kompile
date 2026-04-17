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

import ai.kompile.app.ingest.domain.IngestEvent;
import ai.kompile.app.ingest.domain.IndexingJobHistory;
import ai.kompile.app.ingest.domain.IndexingJobHistory.FailureReason;
import ai.kompile.app.ingest.domain.IndexingJobHistory.JobStatus;
import ai.kompile.app.ingest.domain.JobLogEntry;
import ai.kompile.app.ingest.service.IndexingJobHistoryService;
import ai.kompile.app.ingest.service.JobLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for IndexingJobHistoryController.
 * Uses standalone MockMvc setup to avoid Spring context loading issues.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IndexingJobHistoryController Tests")
class IndexingJobHistoryControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private IndexingJobHistoryService historyService;
    private JobLogService jobLogService;
    private IndexingJobHistoryController controller;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.registerModule(new org.springframework.data.web.config.SpringDataJacksonConfiguration.PageModule());
        objectMapper.configure(com.fasterxml.jackson.databind.SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setObjectMapper(objectMapper);

        historyService = mock(IndexingJobHistoryService.class);
        jobLogService = mock(JobLogService.class);
        controller = new IndexingJobHistoryController(historyService, jobLogService);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(converter)
                .build();
    }

    @Nested
    @DisplayName("GET /api/indexing/history")
    class GetAllJobsTests {

        @Test
        @DisplayName("Should return paginated jobs")
        void shouldReturnPaginatedJobs() throws Exception {
            IndexingJobHistory job = new IndexingJobHistory();
            job.setTaskId("task-1");
            job.setStatus(JobStatus.COMPLETED);
            Page<IndexingJobHistory> page = new PageImpl<>(new java.util.ArrayList<>(List.of(job)));

            when(historyService.getAllJobs(0, 20)).thenReturn(page);

            mockMvc.perform(get("/api/indexing/history")
                            .param("page", "0")
                            .param("size", "20"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(1)));
            verify(historyService).getAllJobs(0, 20);
        }
    }

    @Nested
    @DisplayName("GET /api/indexing/history/{taskId}")
    class GetJobByTaskIdTests {

        @Test
        @DisplayName("Should return job when found")
        void shouldReturnJobWhenFound() throws Exception {
            IndexingJobHistory job = mock(IndexingJobHistory.class);
            when(job.getTaskId()).thenReturn("task-abc");
            when(job.getStatus()).thenReturn(JobStatus.COMPLETED);

            when(historyService.getJob("task-abc")).thenReturn(Optional.of(job));

            mockMvc.perform(get("/api/indexing/history/task-abc"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Should return 404 when not found")
        void shouldReturn404WhenNotFound() throws Exception {
            when(historyService.getJob("nonexistent")).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/indexing/history/nonexistent"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("GET /api/indexing/history/status/{status}")
    class GetJobsByStatusTests {

        @Test
        @DisplayName("Should return jobs by status without hours filter")
        void shouldReturnJobsByStatusWithoutHours() throws Exception {
            IndexingJobHistory job = mock(IndexingJobHistory.class);
            when(historyService.getJobsByStatus(JobStatus.COMPLETED)).thenReturn(List.of(job));

            mockMvc.perform(get("/api/indexing/history/status/COMPLETED"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));
        }

        @Test
        @DisplayName("Should return jobs by status with hours filter")
        void shouldReturnJobsByStatusWithHours() throws Exception {
            IndexingJobHistory job = mock(IndexingJobHistory.class);
            when(historyService.getJobsByStatus(JobStatus.FAILED, 12)).thenReturn(List.of(job));

            mockMvc.perform(get("/api/indexing/history/status/FAILED")
                            .param("hours", "12"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));
        }
    }

    @Nested
    @DisplayName("GET /api/indexing/history/status/{status}/page")
    class GetJobsByStatusPagedTests {

        @Test
        @DisplayName("Should return paged jobs by status")
        void shouldReturnPagedJobsByStatus() throws Exception {
            IndexingJobHistory job = IndexingJobHistory.builder()
                    .taskId("task-paged")
                    .status(JobStatus.RUNNING)
                    .build();
            Page<IndexingJobHistory> page = new PageImpl<>(List.of(job));

            when(historyService.getJobsByStatus(JobStatus.RUNNING, 0, 20)).thenReturn(page);

            mockMvc.perform(get("/api/indexing/history/status/RUNNING/page")
                            .param("page", "0")
                            .param("size", "20"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(1)));
        }
    }

    @Nested
    @DisplayName("GET /api/indexing/history/recent")
    class GetRecentJobsTests {

        @Test
        @DisplayName("Should return recent jobs")
        void shouldReturnRecentJobs() throws Exception {
            IndexingJobHistory job = mock(IndexingJobHistory.class);
            when(historyService.getRecentJobs(24)).thenReturn(List.of(job));

            mockMvc.perform(get("/api/indexing/history/recent"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));
        }
    }

    @Nested
    @DisplayName("GET /api/indexing/history/recent/page")
    class GetRecentJobsPagedTests {

        @Test
        @DisplayName("Should return paged recent jobs")
        void shouldReturnPagedRecentJobs() throws Exception {
            IndexingJobHistory job = IndexingJobHistory.builder()
                    .taskId("task-recent")
                    .status(JobStatus.COMPLETED)
                    .build();
            Page<IndexingJobHistory> page = new PageImpl<>(List.of(job));

            when(historyService.getRecentJobs(24, 0, 20)).thenReturn(page);

            mockMvc.perform(get("/api/indexing/history/recent/page")
                            .param("hours", "24")
                            .param("page", "0")
                            .param("size", "20"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(1)));
        }
    }

    @Nested
    @DisplayName("GET /api/indexing/history/range")
    class GetJobsInRangeTests {

        @Test
        @DisplayName("Should return jobs in time range")
        void shouldReturnJobsInRange() throws Exception {
            IndexingJobHistory job = mock(IndexingJobHistory.class);
            when(historyService.getJobsBetween(any(Instant.class), any(Instant.class)))
                    .thenReturn(List.of(job));

            mockMvc.perform(get("/api/indexing/history/range")
                            .param("start", "2025-01-01T00:00:00Z")
                            .param("end", "2025-01-02T00:00:00Z"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));
        }
    }

    @Nested
    @DisplayName("GET /api/indexing/history/failed")
    class GetFailedJobsTests {

        @Test
        @DisplayName("Should return failed jobs")
        void shouldReturnFailedJobs() throws Exception {
            IndexingJobHistory job = mock(IndexingJobHistory.class);
            when(historyService.getFailedJobs()).thenReturn(List.of(job));

            mockMvc.perform(get("/api/indexing/history/failed"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));
        }
    }

    @Nested
    @DisplayName("GET /api/indexing/history/failed/{reason}")
    class GetJobsByFailureReasonTests {

        @Test
        @DisplayName("Should return jobs by failure reason")
        void shouldReturnJobsByFailureReason() throws Exception {
            IndexingJobHistory job = mock(IndexingJobHistory.class);
            when(historyService.getJobsByFailureReason(FailureReason.OUT_OF_MEMORY)).thenReturn(List.of(job));

            mockMvc.perform(get("/api/indexing/history/failed/OUT_OF_MEMORY"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));
        }
    }

    @Nested
    @DisplayName("GET /api/indexing/history/active")
    class GetActiveJobsTests {

        @Test
        @DisplayName("Should return active jobs")
        void shouldReturnActiveJobs() throws Exception {
            IndexingJobHistory job = mock(IndexingJobHistory.class);
            when(historyService.getActiveJobs()).thenReturn(List.of(job));

            mockMvc.perform(get("/api/indexing/history/active"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));
        }
    }

    @Nested
    @DisplayName("GET /api/indexing/history/latest")
    class GetLatestJobsTests {

        @Test
        @DisplayName("Should return latest jobs with default limit")
        void shouldReturnLatestJobsWithDefaultLimit() throws Exception {
            IndexingJobHistory job = mock(IndexingJobHistory.class);
            when(historyService.getMostRecentJobs(10)).thenReturn(List.of(job));

            mockMvc.perform(get("/api/indexing/history/latest"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));
        }
    }

    @Nested
    @DisplayName("GET /api/indexing/history/search")
    class SearchByFileNameTests {

        @Test
        @DisplayName("Should search jobs by file name")
        void shouldSearchByFileName() throws Exception {
            IndexingJobHistory job = mock(IndexingJobHistory.class);
            when(historyService.searchByFileName("report.pdf")).thenReturn(List.of(job));

            mockMvc.perform(get("/api/indexing/history/search")
                            .param("fileName", "report.pdf"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));
        }
    }

    @Nested
    @DisplayName("GET /api/indexing/history/high-memory")
    class GetHighMemoryJobsTests {

        @Test
        @DisplayName("Should return high memory jobs")
        void shouldReturnHighMemoryJobs() throws Exception {
            IndexingJobHistory job = mock(IndexingJobHistory.class);
            when(historyService.getHighMemoryJobs(80.0)).thenReturn(List.of(job));

            mockMvc.perform(get("/api/indexing/history/high-memory")
                            .param("threshold", "80"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));
        }
    }

    @Nested
    @DisplayName("GET /api/indexing/history/long-running")
    class GetLongRunningJobsTests {

        @Test
        @DisplayName("Should return long running jobs")
        void shouldReturnLongRunningJobs() throws Exception {
            IndexingJobHistory job = mock(IndexingJobHistory.class);
            when(historyService.getLongRunningJobs(60000L)).thenReturn(List.of(job));

            mockMvc.perform(get("/api/indexing/history/long-running")
                            .param("thresholdMs", "60000"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));
        }
    }

    @Nested
    @DisplayName("GET /api/indexing/history/statistics")
    class GetStatisticsTests {

        @Test
        @DisplayName("Should return job statistics")
        void shouldReturnJobStatistics() throws Exception {
            Map<String, Object> stats = Map.of("totalJobs", 50, "completedJobs", 45);
            when(historyService.getJobStatistics(24)).thenReturn(stats);

            mockMvc.perform(get("/api/indexing/history/statistics"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalJobs", is(50)))
                    .andExpect(jsonPath("$.completedJobs", is(45)));
        }
    }

    @Nested
    @DisplayName("GET /api/indexing/history/statistics/failure-rate")
    class GetFailureRateTests {

        @Test
        @DisplayName("Should return failure rate")
        void shouldReturnFailureRate() throws Exception {
            when(historyService.getFailureRate(24)).thenReturn(5.5);

            mockMvc.perform(get("/api/indexing/history/statistics/failure-rate"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.failureRatePercent", is(5.5)))
                    .andExpect(jsonPath("$.periodHours", is(24)));
        }
    }

    @Nested
    @DisplayName("DELETE /api/indexing/history/{taskId}")
    class DeleteJobTests {

        @Test
        @DisplayName("Should delete job successfully")
        void shouldDeleteJobSuccessfully() throws Exception {
            when(historyService.deleteJob("task-del")).thenReturn(true);

            mockMvc.perform(delete("/api/indexing/history/task-del"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.deleted", is(true)))
                    .andExpect(jsonPath("$.taskId", is("task-del")));
        }

        @Test
        @DisplayName("Should return 404 when job not found for deletion")
        void shouldReturn404WhenJobNotFound() throws Exception {
            when(historyService.deleteJob("nonexistent")).thenReturn(false);

            mockMvc.perform(delete("/api/indexing/history/nonexistent"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("POST /api/indexing/history/cleanup")
    class ForceCleanupTests {

        @Test
        @DisplayName("Should cleanup old jobs")
        void shouldCleanupOldJobs() throws Exception {
            when(historyService.forceCleanup(30)).thenReturn(10);

            mockMvc.perform(post("/api/indexing/history/cleanup")
                            .param("olderThanDays", "30"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.deleted", is(10)))
                    .andExpect(jsonPath("$.olderThanDays", is(30)));
        }
    }

    @Nested
    @DisplayName("GET /api/indexing/history/{taskId}/summary")
    class GetJobSummaryTests {

        @Test
        @DisplayName("Should return job summary when found")
        void shouldReturnJobSummaryWhenFound() throws Exception {
            IndexingJobHistory job = mock(IndexingJobHistory.class);
            when(job.getTaskId()).thenReturn("task-sum");
            when(job.getFileName()).thenReturn("report.pdf");
            when(job.getStatus()).thenReturn(JobStatus.COMPLETED);
            when(job.getProgressPercent()).thenReturn(100);
            when(job.getStartTime()).thenReturn(Instant.parse("2025-01-01T00:00:00Z"));
            when(job.getEndTime()).thenReturn(Instant.parse("2025-01-01T00:05:00Z"));
            when(job.getTotalDurationMs()).thenReturn(300000L);
            when(job.getLastPhase()).thenReturn(IngestEvent.IngestPhase.INDEXING);
            when(job.getFailedPhase()).thenReturn(null);
            when(job.getFailureReason()).thenReturn(FailureReason.NONE);
            when(job.getErrorMessage()).thenReturn(null);
            when(job.getLoaderUsed()).thenReturn("pdf");
            when(job.getChunkerUsed()).thenReturn("recursive");
            when(job.getEmbeddingModelUsed()).thenReturn("bge-base-en-v1.5");
            when(job.getChunkSize()).thenReturn(500);
            when(job.getChunkOverlap()).thenReturn(50);
            when(job.getEmbeddingBatchSize()).thenReturn(32);
            when(job.getWorkerThreads()).thenReturn(4);
            when(job.getDocumentsLoaded()).thenReturn(10);
            when(job.getChunksCreated()).thenReturn(100);
            when(job.getChunksEmbedded()).thenReturn(100);
            when(job.getDocumentsIndexed()).thenReturn(100);
            when(job.getLoadingDurationMs()).thenReturn(1000L);
            when(job.getConversionDurationMs()).thenReturn(null);
            when(job.getChunkingDurationMs()).thenReturn(2000L);
            when(job.getEmbeddingDurationMs()).thenReturn(5000L);
            when(job.getIndexingDurationMs()).thenReturn(3000L);
            when(job.getJavaVersion()).thenReturn("17.0.1");
            when(job.getOsInfo()).thenReturn("Linux");
            when(job.getAvailableProcessors()).thenReturn(8);
            when(job.getMaxHeapMemoryBytes()).thenReturn(8589934592L);
            when(job.getNd4jBackend()).thenReturn("CPU");
            when(job.getNd4jEnvironmentJson()).thenReturn(null);
            when(job.getMemoryUsagePercentAtStart()).thenReturn(20.0);
            when(job.getMemoryUsagePercentAtEnd()).thenReturn(45.0);
            when(job.getPeakMemoryUsagePercent()).thenReturn(60.0);

            when(historyService.getJob("task-sum")).thenReturn(Optional.of(job));

            mockMvc.perform(get("/api/indexing/history/task-sum/summary"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.taskId", is("task-sum")))
                    .andExpect(jsonPath("$.fileName", is("report.pdf")))
                    .andExpect(jsonPath("$.status", is("COMPLETED")))
                    .andExpect(jsonPath("$.parameters.loaderUsed", is("pdf")))
                    .andExpect(jsonPath("$.results.documentsLoaded", is(10)));
        }

        @Test
        @DisplayName("Should return 404 when job not found for summary")
        void shouldReturn404WhenJobNotFoundForSummary() throws Exception {
            when(historyService.getJob("nonexistent")).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/indexing/history/nonexistent/summary"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("Embedding Log Endpoints")
    class EmbeddingLogTests {

        @Test
        @DisplayName("GET /embedding/{modelId}/logs should return disabled when jobLogService is null")
        void shouldReturnDisabledWhenJobLogServiceNull() throws Exception {
            controller = new IndexingJobHistoryController(historyService, null);

            MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
            converter.setObjectMapper(objectMapper);

            mockMvc = MockMvcBuilders.standaloneSetup(controller)
                    .setMessageConverters(converter)
                    .build();

            mockMvc.perform(get("/api/indexing/history/embedding/bge-base/logs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled", is(false)));
        }

        @Test
        @DisplayName("DELETE /embedding/{modelId}/logs should return disabled when jobLogService is null")
        void shouldReturnDisabledOnDeleteWhenJobLogServiceNull() throws Exception {
            controller = new IndexingJobHistoryController(historyService, null);

            MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
            converter.setObjectMapper(objectMapper);

            mockMvc = MockMvcBuilders.standaloneSetup(controller)
                    .setMessageConverters(converter)
                    .build();

            mockMvc.perform(delete("/api/indexing/history/embedding/bge-base/logs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled", is(false)));
        }
    }
}
