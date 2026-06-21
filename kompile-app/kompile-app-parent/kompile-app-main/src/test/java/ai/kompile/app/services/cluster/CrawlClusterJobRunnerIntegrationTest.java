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

package ai.kompile.app.services.cluster;

import ai.kompile.app.ingest.domain.JobLogEntry;
import ai.kompile.app.ingest.service.JobLogService;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration test for the WORKER side of a distributed crawl: {@link CrawlClusterJobRunner} runs a delegated
 * partition (local crawl mocked) and must stream live progress to the coordinator over real HTTP. A real
 * {@link HttpServer} stands in for the coordinator's {@code /api/distributed-crawl/progress} endpoint and
 * captures what the worker actually sends on the wire.
 */
class CrawlClusterJobRunnerIntegrationTest {

    private HttpServer server;
    private final List<String> progressBodies = new CopyOnWriteArrayList<>();
    private final List<String> transcriptBodies = new CopyOnWriteArrayList<>();
    private int port;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/distributed-crawl/progress", captureInto(progressBodies));
        server.createContext("/api/distributed-crawl/transcripts", captureInto(transcriptBodies));
        server.start();
        port = server.getAddress().getPort();
    }

    private static com.sun.net.httpserver.HttpHandler captureInto(List<String> sink) {
        return exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                sink.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] resp = "{\"acknowledged\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        };
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private UnifiedCrawlJob jobWithStatus(UnifiedCrawlJob.Status status) {
        UnifiedCrawlJob job = UnifiedCrawlJob.builder().jobId("local-1").build();
        job.getStatus().set(status);
        job.getEntitiesExtracted().set(5);
        return job;
    }

    private CrawlClusterJobRunner runnerWith(UnifiedCrawlService svc) {
        CrawlClusterJobRunner runner = new CrawlClusterJobRunner();
        ReflectionTestUtils.setField(runner, "unifiedCrawlService", svc);
        ReflectionTestUtils.setField(runner, "objectMapper", new ObjectMapper().findAndRegisterModules());
        return runner;
    }

    @Test
    void workerStreamsProgressToCoordinatorOverHttp() throws Exception {
        UnifiedCrawlService svc = mock(UnifiedCrawlService.class);
        when(svc.startJob(any())).thenReturn(jobWithStatus(UnifiedCrawlJob.Status.RUNNING));
        when(svc.getJob("local-1"))
                .thenReturn(Optional.of(jobWithStatus(UnifiedCrawlJob.Status.RUNNING)))   // still running
                .thenReturn(Optional.of(jobWithStatus(UnifiedCrawlJob.Status.COMPLETED))); // then done

        ClusterJobRunner.Result result = runnerWith(svc).run(new ClusterJobSubmission(
                "job-1", "crawl", "test", "crawl", false,
                Map.of("sessionId", "sess-1", "workerId", "sess-1-worker-0", "crawlRequestJson", "{}"),
                "http://127.0.0.1:" + port));

        assertTrue(result.success());
        assertFalse(progressBodies.isEmpty(), "worker should POST at least one progress update");
        String body = progressBodies.get(0);
        assertTrue(body.contains("\"sessionId\":\"sess-1\""), body);
        assertTrue(body.contains("\"workerId\":\"sess-1-worker-0\""), body);
        assertTrue(body.contains("\"snapshot\""), body);
        assertTrue(body.contains("\"entitiesExtracted\":5"), body); // the live snapshot rode along
    }

    @Test
    void workerForwardsNewTranscriptsToCoordinator() throws Exception {
        UnifiedCrawlService svc = mock(UnifiedCrawlService.class);
        when(svc.startJob(any())).thenReturn(jobWithStatus(UnifiedCrawlJob.Status.RUNNING));
        when(svc.getJob("local-1"))
                .thenReturn(Optional.of(jobWithStatus(UnifiedCrawlJob.Status.RUNNING)))
                .thenReturn(Optional.of(jobWithStatus(UnifiedCrawlJob.Status.COMPLETED)));

        JobLogService jls = mock(JobLogService.class);
        when(jls.isEnabled()).thenReturn(true);
        when(jls.getLogsForTaskBySource("crawl-local-1", JobLogEntry.LogSource.LLM_TRANSCRIPT))
                .thenReturn(List.of(JobLogEntry.builder()
                        .sequenceNumber(1L).timestamp(Instant.now())
                        .level(JobLogEntry.LogLevel.INFO).source(JobLogEntry.LogSource.LLM_TRANSCRIPT)
                        .message("PROMPT/RESPONSE A").build()));

        CrawlClusterJobRunner runner = runnerWith(svc);
        ReflectionTestUtils.setField(runner, "jobLogService", jls);

        runner.run(new ClusterJobSubmission(
                "job-3", "crawl", "test", "crawl", false,
                Map.of("sessionId", "sess-1", "workerId", "sess-1-worker-0", "crawlRequestJson", "{}"),
                "http://127.0.0.1:" + port));

        assertFalse(transcriptBodies.isEmpty(), "worker should forward LLM transcripts to the coordinator");
        String body = transcriptBodies.get(0);
        assertTrue(body.contains("\"sessionId\":\"sess-1\""), body);
        assertTrue(body.contains("\"entries\""), body);
        assertTrue(body.contains("PROMPT/RESPONSE A"), body);
    }

    @Test
    void noSessionIdMeansNoDistributedProgress() throws Exception {
        UnifiedCrawlService svc = mock(UnifiedCrawlService.class);
        when(svc.startJob(any())).thenReturn(jobWithStatus(UnifiedCrawlJob.Status.RUNNING));
        when(svc.getJob("local-1")).thenReturn(Optional.of(jobWithStatus(UnifiedCrawlJob.Status.COMPLETED)));

        runnerWith(svc).run(new ClusterJobSubmission(
                "job-2", "crawl", "test", "crawl", false,
                Map.of("crawlRequestJson", "{}"), // no sessionId → standalone cluster job
                "http://127.0.0.1:" + port));

        assertTrue(progressBodies.isEmpty(), "a non-distributed cluster job must not POST distributed progress");
    }
}
