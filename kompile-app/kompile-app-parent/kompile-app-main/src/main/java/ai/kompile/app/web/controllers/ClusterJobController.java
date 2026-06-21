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

import ai.kompile.app.services.cluster.ClusterJobRunner;
import ai.kompile.app.services.cluster.ClusterJobRunnerRegistry;
import ai.kompile.app.services.cluster.ClusterJobSubmission;
import ai.kompile.app.services.cluster.CrawlWorkerCapabilityService;
import ai.kompile.app.services.scheduler.ResourceSchedulerConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The worker side of cluster execution. Accepts a delegated job ({@code POST /api/cluster/jobs}), runs it via
 * the matching {@link ClusterJobRunner} on a bounded pool, tracks its status for the orchestrator to poll,
 * and on completion calls back the orchestrator — the distributed-crawl callback when the job carries a
 * {@code sessionId}, otherwise the generic scheduler callback.
 */
@RestController
@RequestMapping("/api/cluster")
public class ClusterJobController {

    private static final Logger log = LoggerFactory.getLogger(ClusterJobController.class);

    private final ClusterJobRunnerRegistry runnerRegistry;
    private final CrawlWorkerCapabilityService capabilityService;
    private final ResourceSchedulerConfigService configService;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private final Map<String, String> jobStatus = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> running = new ConcurrentHashMap<>();
    private ExecutorService workerPool;

    public ClusterJobController(ClusterJobRunnerRegistry runnerRegistry,
                                CrawlWorkerCapabilityService capabilityService,
                                ResourceSchedulerConfigService configService,
                                ObjectMapper objectMapper) {
        this.runnerRegistry = runnerRegistry;
        this.capabilityService = capabilityService;
        this.configService = configService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void start() {
        int max = Math.max(2, configService.getConfiguration().getClusterMaxConcurrentJobs());
        workerPool = Executors.newFixedThreadPool(max, r -> {
            Thread t = new Thread(r, "cluster-job-worker");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    public void stop() {
        if (workerPool != null) {
            workerPool.shutdownNow();
        }
    }

    /** Accept a delegated job and run it asynchronously (worker side). */
    @PostMapping("/jobs")
    public ResponseEntity<Map<String, Object>> acceptJob(
            @RequestBody ClusterJobSubmission job,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (!authorized(auth)) {
            return ResponseEntity.status(401).body(Map.of("ok", false, "error", "unauthorized"));
        }
        Optional<ClusterJobRunner> runner = runnerRegistry.forType(job.jobType());
        if (runner.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "ok", false, "error", "no runner for jobType '" + job.jobType() + "'"));
        }
        jobStatus.put(job.jobId(), "RUNNING");
        Future<?> f = workerPool.submit(() -> runAndCallback(job, runner.get()));
        running.put(job.jobId(), f);
        log.info("Accepted delegated job '{}' (type='{}') from orchestrator {}",
                job.jobId(), job.jobType(), job.callbackBaseUrl());
        return ResponseEntity.accepted().body(Map.of("ok", true, "jobId", job.jobId()));
    }

    private void runAndCallback(ClusterJobSubmission job, ClusterJobRunner runner) {
        capabilityService.activeDelegatedJobs().incrementAndGet();
        boolean success = false;
        String message;
        Map<String, Object> resultData = Map.of();
        try {
            ClusterJobRunner.Result result = runner.run(job);
            success = result.success();
            message = result.message();
            resultData = result.resultData();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            jobStatus.put(job.jobId(), "CANCELLED");
            running.remove(job.jobId());
            capabilityService.activeDelegatedJobs().decrementAndGet();
            log.info("Delegated job '{}' cancelled", job.jobId());
            return;
        } catch (Exception e) {
            message = e.getMessage();
            log.error("Delegated job '{}' failed: {}", job.jobId(), e.getMessage(), e);
        } finally {
            capabilityService.activeDelegatedJobs().decrementAndGet();
        }
        jobStatus.put(job.jobId(), success ? "COMPLETED" : "FAILED");
        running.remove(job.jobId());
        sendCallback(job, success, message, resultData);
    }

    private void sendCallback(ClusterJobSubmission job, boolean success, String message,
                              Map<String, Object> resultData) {
        String base = job.callbackBaseUrl();
        if (base == null || base.isBlank()) {
            log.warn("Job '{}' has no callbackBaseUrl — orchestrator will rely on status polling", job.jobId());
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            String url;
            if (job.meta("sessionId") != null) {
                // Distributed-crawl coordinator callback.
                payload.put("sessionId", job.meta("sessionId"));
                payload.put("workerId", job.meta("workerId") != null ? job.meta("workerId") : job.jobId());
                payload.put("success", success);
                payload.put("message", message);
                payload.put("resultData", resultData);
                url = normalize(base) + "/api/distributed-crawl/callback";
            } else {
                // Generic scheduler callback.
                payload.put("jobId", job.jobId());
                payload.put("success", success);
                payload.put("message", message);
                url = normalize(base) + "/api/scheduler/callback";
            }
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
            String token = configService.getConfiguration().getExternalAuthToken();
            if (token != null && !token.isBlank()) {
                rb.header("Authorization", "Bearer " + token);
            }
            HttpResponse<Void> resp = httpClient.send(rb.build(), HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() / 100 != 2) {
                log.warn("Callback for job '{}' to {} returned HTTP {}", job.jobId(), url, resp.statusCode());
            }
        } catch (Exception e) {
            log.error("Callback for job '{}' failed: {}", job.jobId(), e.getMessage());
        }
    }

    /** Status of a delegated job on this worker (orchestrator polls this). */
    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<Map<String, Object>> jobStatus(@PathVariable String jobId) {
        String s = jobStatus.get(jobId);
        if (s == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("jobId", jobId, "status", s));
    }

    /** Cancel a running delegated job on this worker. */
    @DeleteMapping("/jobs/{jobId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelJob(
            @PathVariable String jobId,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (!authorized(auth)) {
            return ResponseEntity.status(401).body(Map.of("ok", false, "error", "unauthorized"));
        }
        Future<?> f = running.get(jobId);
        boolean cancelled = f != null && f.cancel(true);
        if (cancelled) {
            jobStatus.put(jobId, "CANCELLED");
        }
        return ResponseEntity.ok(Map.of("ok", true, "cancelled", cancelled));
    }

    private boolean authorized(String authHeader) {
        String token = configService.getConfiguration().getExternalAuthToken();
        if (token == null || token.isBlank()) {
            return true;
        }
        return ("Bearer " + token).equals(authHeader);
    }

    private static String normalize(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
