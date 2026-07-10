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
import ai.kompile.app.services.scheduler.ResourceSchedulerConfigService;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.core.crawl.graph.UnifiedCrawlService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs a delegated {@code crawl} partition on this worker: deserialises {@code metadata.crawlRequestJson}
 * (the per-partition {@link UnifiedCrawlRequest} the {@code DistributedCrawlCoordinator} produced) and runs
 * it through the worker's own local crawl pipeline, polling until the local job reaches a terminal status.
 * Present only when a {@link UnifiedCrawlService} bean exists, so a node without the crawl pipeline simply
 * doesn't advertise {@code crawl} as a runnable type.
 */
@Component
public class CrawlClusterJobRunner implements ClusterJobRunner {

    private static final Logger log = LoggerFactory.getLogger(CrawlClusterJobRunner.class);
    private static final long POLL_INTERVAL_MS = 2_000L;
    /** Min gap between progress reports to the coordinator (every other poll tick). */
    private static final long PROGRESS_EMIT_INTERVAL_MS = 4_000L;

    @Autowired(required = false)
    private UnifiedCrawlService unifiedCrawlService;

    @Autowired
    private ObjectMapper objectMapper;

    /** Optional — used only to attach the cluster auth token to progress reports. */
    @Autowired(required = false)
    private ResourceSchedulerConfigService configService;

    /** Optional — reads this worker's local LLM transcripts so they can be forwarded to the coordinator. */
    @Autowired(required = false)
    private JobLogService jobLogService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public String jobType() {
        return "crawl";
    }

    @Override
    public Result run(ClusterJobSubmission job) throws Exception {
        if (unifiedCrawlService == null) {
            return Result.fail("no UnifiedCrawlService on this worker — cannot run crawl partitions");
        }
        String json = job.meta("crawlRequestJson");
        if (json == null || json.isBlank()) {
            return Result.fail("crawl job '" + job.jobId() + "' missing metadata.crawlRequestJson");
        }

        UnifiedCrawlRequest request = objectMapper.readValue(json, UnifiedCrawlRequest.class);
        UnifiedCrawlJob crawlJob = unifiedCrawlService.startJob(request);
        String localJobId = crawlJob.getJobId();
        log.info("CrawlWorker running delegated crawl '{}' as local crawl job '{}'", job.jobId(), localJobId);

        UnifiedCrawlJob.Status status = crawlJob.getStatus().get();
        boolean distributed = job.meta("sessionId") != null;
        long lastProgressEmitMs = 0L;
        long lastTranscriptSeq = -1L;
        while (!isTerminal(status)) {
            Thread.sleep(POLL_INTERVAL_MS);
            Optional<UnifiedCrawlJob> current = unifiedCrawlService.getJob(localJobId);
            status = current.map(j -> j.getStatus().get()).orElse(status);
            // Stream live progress + new LLM transcripts to the coordinator (distributed partitions, throttled).
            long now = System.currentTimeMillis();
            if (distributed && now - lastProgressEmitMs >= PROGRESS_EMIT_INTERVAL_MS) {
                lastProgressEmitMs = now;
                current.ifPresent(j -> sendProgressToCoordinator(job, j));
                lastTranscriptSeq = forwardNewTranscripts(job, localJobId, lastTranscriptSeq);
            }
        }
        // Final flush so transcripts from the last batch reach the coordinator before the job ends.
        if (distributed) {
            forwardNewTranscripts(job, localJobId, lastTranscriptSeq);
        }

        boolean success = status == UnifiedCrawlJob.Status.COMPLETED
                || status == UnifiedCrawlJob.Status.COMPLETED_PENDING_EMBEDDING
                || status == UnifiedCrawlJob.Status.COMPLETED_PENDING_GRAPH;
        log.info("Delegated crawl '{}' (local '{}') finished: status={}", job.jobId(), localJobId, status);
        return new Result(success, "crawl finished with status " + status,
                Map.of("localJobId", localJobId, "status", status.name()));
    }

    /**
     * Fire-and-forget POST of this worker's live {@link UnifiedCrawlJob.ProgressSnapshot} to the
     * coordinator's {@code /api/distributed-crawl/progress}, so a distributed crawl renders as one live job.
     * Never throws — a failed report must not interrupt the crawl.
     */
    private void sendProgressToCoordinator(ClusterJobSubmission job, UnifiedCrawlJob crawlJob) {
        String base = job.callbackBaseUrl();
        if (base == null || base.isBlank()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", job.meta("sessionId"));
        payload.put("workerId", job.meta("workerId") != null ? job.meta("workerId") : job.jobId());
        payload.put("snapshot", crawlJob.toProgressSnapshot());
        postJson(normalize(base) + "/api/distributed-crawl/progress", payload, "progress", job.jobId());
    }

    /**
     * Read this worker's local LLM transcripts (stored under {@code crawl-<localJobId>}) newer than
     * {@code lastSeq} and forward them to the coordinator's {@code /api/distributed-crawl/transcripts}, so the
     * unified monitor's transcript viewer works for distributed crawls. Returns the new high-water sequence.
     */
    private long forwardNewTranscripts(ClusterJobSubmission job, String localJobId, long lastSeq) {
        if (jobLogService == null || !jobLogService.isEnabled()) {
            return lastSeq;
        }
        String base = job.callbackBaseUrl();
        if (base == null || base.isBlank()) {
            return lastSeq;
        }
        try {
            List<JobLogEntry> all = jobLogService.getLogsForTaskBySource(
                    "crawl-" + localJobId, JobLogEntry.LogSource.LLM_TRANSCRIPT);
            List<Map<String, Object>> fresh = new ArrayList<>();
            long max = lastSeq;
            for (JobLogEntry e : all) {
                if (e.getSequenceNumber() > lastSeq) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("timestamp", e.getTimestamp() != null ? e.getTimestamp().toString() : null);
                    m.put("level", e.getLevel() != null ? e.getLevel().name() : "INFO");
                    m.put("message", e.getMessage());
                    fresh.add(m);
                    max = Math.max(max, e.getSequenceNumber());
                }
            }
            if (fresh.isEmpty()) {
                return lastSeq;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sessionId", job.meta("sessionId"));
            payload.put("workerId", job.meta("workerId") != null ? job.meta("workerId") : job.jobId());
            payload.put("entries", fresh);
            postJson(normalize(base) + "/api/distributed-crawl/transcripts", payload, "transcripts", job.jobId());
            return max;
        } catch (Exception e) {
            log.debug("Transcript forward for distributed job '{}' failed: {}", job.jobId(), e.getMessage());
            return lastSeq;
        }
    }

    /** Fire-and-forget authenticated JSON POST; never throws (a failed report must not interrupt the crawl). */
    private void postJson(String url, Object payload, String what, String jobId) {
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
            String token = configService != null && configService.getConfiguration() != null
                    ? configService.getConfiguration().getExternalAuthToken() : null;
            if (token != null && !token.isBlank()) {
                rb.header("Authorization", "Bearer " + token);
            }
            httpClient.send(rb.build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.debug("{} report for distributed job '{}' failed: {}", what, jobId, e.getMessage());
        }
    }

    private static String normalize(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static boolean isTerminal(UnifiedCrawlJob.Status s) {
        return switch (s) {
            case COMPLETED, COMPLETED_PENDING_EMBEDDING, COMPLETED_PENDING_GRAPH, FAILED, CANCELLED -> true;
            default -> false;
        };
    }
}
