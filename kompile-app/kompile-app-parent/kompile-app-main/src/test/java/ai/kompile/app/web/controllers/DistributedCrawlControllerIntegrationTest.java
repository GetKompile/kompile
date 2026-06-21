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

package ai.kompile.app.web.controllers;

import ai.kompile.app.config.ResourceSchedulerConfig;
import ai.kompile.app.ingest.domain.JobLogEntry;
import ai.kompile.app.ingest.service.JobLogService;
import ai.kompile.app.services.crawl.DistributedCrawlAggregator;
import ai.kompile.app.services.crawl.DistributedCrawlCoordinator;
import ai.kompile.app.services.scheduler.ExternalJobSchedulerDelegate;
import ai.kompile.app.services.scheduler.JobResourceProfile;
import ai.kompile.app.services.scheduler.ResourceSchedulerConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer integration test for the distributed-crawl coordinator endpoints (standalone MockMvc over a
 * real coordinator + aggregator): start → worker progress report → aggregated snapshot. Exercises JSON
 * (de)serialization of the {@code ProgressSnapshot} body and the controller↔coordinator↔aggregator wiring.
 */
class DistributedCrawlControllerIntegrationTest {

    private final ObjectMapper om = new ObjectMapper().findAndRegisterModules();
    private DistributedCrawlCoordinator coordinator;
    private JobLogService jobLogService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        coordinator = new DistributedCrawlCoordinator(List.of(new LocalDelegate()), null, om);
        ReflectionTestUtils.setField(coordinator, "aggregator", new DistributedCrawlAggregator());
        ReflectionTestUtils.setField(coordinator, "eventPublisher", mock(ApplicationEventPublisher.class));

        ResourceSchedulerConfigService cfgService = mock(ResourceSchedulerConfigService.class);
        when(cfgService.getConfiguration()).thenReturn(new ResourceSchedulerConfig()); // blank token → open

        jobLogService = mock(JobLogService.class);
        when(jobLogService.isEnabled()).thenReturn(true);

        DistributedCrawlController controller = new DistributedCrawlController(
                coordinator, new DistributedCrawlAggregator(), cfgService, jobLogService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    private String startSession() throws Exception {
        Map<String, Object> request = Map.of(
                "name", "mockmvc dist",
                "sources", List.of(Map.of("label", "A", "pathOrUrl", "a")),
                "distribution", Map.of("partitionStrategy", "PER_SOURCE"));
        MvcResult res = mockMvc.perform(post("/api/distributed-crawl/start")
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();
        return om.readTree(res.getResponse().getContentAsString()).get("sessionId").asText();
    }

    @Test
    void startThenProgressThenAggregate() throws Exception {
        // 1. Start a distributed crawl.
        Map<String, Object> request = Map.of(
                "name", "mockmvc dist",
                "sources", List.of(Map.of("label", "A", "pathOrUrl", "a")),
                "distribution", Map.of("partitionStrategy", "PER_SOURCE"));
        MvcResult res = mockMvc.perform(post("/api/distributed-crawl/start")
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();
        String sid = om.readTree(res.getResponse().getContentAsString()).get("sessionId").asText();
        assertNotNull(sid);
        String wid = coordinator.getSession(sid).orElseThrow().getWorkers().keySet().iterator().next();

        // 2. A worker reports progress (a partial ProgressSnapshot as JSON).
        Map<String, Object> progress = Map.of("sessionId", sid, "workerId", wid,
                "snapshot", Map.of("entitiesExtracted", 7, "progressPercent", 40, "chunksCreated", 25));
        mockMvc.perform(post("/api/distributed-crawl/progress")
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(progress)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acknowledged").value(true));

        // 3. The aggregate snapshot reflects the worker's report (one job, merged counters).
        mockMvc.perform(get("/api/distributed-crawl/sessions/" + sid).param("aggregate", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("distributed-" + sid))
                .andExpect(jsonPath("$.entitiesExtracted").value(7))
                .andExpect(jsonPath("$.chunksCreated").value(25));

        // 4. The non-aggregate view is the lightweight session summary.
        mockMvc.perform(get("/api/distributed-crawl/sessions/" + sid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sid))
                .andExpect(jsonPath("$.totalWorkers").value(1));
    }

    @Test
    void progressForUnknownSessionIsAcknowledgedButIgnored() throws Exception {
        Map<String, Object> progress = Map.of("sessionId", "nope", "workerId", "w",
                "snapshot", Map.of("entitiesExtracted", 1));
        mockMvc.perform(post("/api/distributed-crawl/progress")
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(progress)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acknowledged").value(true));
        assertTrue(coordinator.getSession("nope").isEmpty());
    }

    @Test
    void missingDistributionConfigIsRejected() throws Exception {
        Map<String, Object> request = Map.of(
                "name", "no dist",
                "sources", List.of(Map.of("label", "A", "pathOrUrl", "a")));
        mockMvc.perform(post("/api/distributed-crawl/start")
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void workerTranscriptsAreStoredUnderTheSessionTaskId() throws Exception {
        String sid = startSession();
        String wid = coordinator.getSession(sid).orElseThrow().getWorkers().keySet().iterator().next();

        Map<String, Object> body = Map.of("sessionId", sid, "workerId", wid, "entries", List.of(
                Map.of("timestamp", "2026-06-21T10:00:00Z", "level", "INFO", "message", "PROMPT/RESPONSE 1"),
                Map.of("level", "WARN", "message", "failed call")));
        mockMvc.perform(post("/api/distributed-crawl/transcripts")
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stored").value(2));

        // Stored under crawl-distributed-<sid>, source LLM_TRANSCRIPT, worker-prefixed message, thread=workerId.
        verify(jobLogService, times(2)).logEntry(
                eq("crawl-distributed-" + sid), any(), eq(JobLogEntry.LogSource.LLM_TRANSCRIPT),
                contains("[W0]"), any(), eq(wid));
    }

    @Test
    void transcriptsForUnknownSessionStoreNothing() throws Exception {
        Map<String, Object> body = Map.of("sessionId", "nope", "workerId", "w",
                "entries", List.of(Map.of("level", "INFO", "message", "x")));
        mockMvc.perform(post("/api/distributed-crawl/transcripts")
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stored").value(0));
        verify(jobLogService, times(0)).logEntry(any(), any(), any(), any(), any(), any());
    }

    /** Minimal in-test external delegate that "accepts" every submission. */
    static class LocalDelegate implements ExternalJobSchedulerDelegate {
        @Override
        public String getMode() {
            return "mock";
        }

        @Override
        public CompletableFuture<ExternalJobRef> submitJob(String jobId, String jobType, String description,
                                                           JobResourceProfile resourceProfile,
                                                           Map<String, Object> metadata) {
            return CompletableFuture.completedFuture(new ExternalJobRef("ext-" + jobId, "SUBMITTED", "ok"));
        }

        @Override
        public CompletableFuture<Boolean> cancelJob(String jobId, String externalRef) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<ExternalJobStatus> getJobStatus(String jobId, String externalRef) {
            return CompletableFuture.completedFuture(new ExternalJobStatus(externalRef, "RUNNING", "", Map.of()));
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }
}
