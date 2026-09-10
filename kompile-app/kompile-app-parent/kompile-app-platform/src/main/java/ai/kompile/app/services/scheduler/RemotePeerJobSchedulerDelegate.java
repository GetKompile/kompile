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

package ai.kompile.app.services.scheduler;

import ai.kompile.app.config.ResourceSchedulerConfig;
import ai.kompile.app.services.ResourceGovernor;
import ai.kompile.app.services.cluster.ClusterJobSubmission;
import ai.kompile.app.services.cluster.CrawlWorkerRegistry;
import ai.kompile.app.services.cluster.WorkerCapabilities;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * External-scheduler delegate (mode {@code cluster}) that runs jobs on a remote-peer {@code CrawlWorker} over
 * HTTP. Auto-discovered by {@link ResourceAwareJobScheduler} when {@code externalSchedulerMode=cluster}; also
 * usable by {@code DistributedCrawlCoordinator} to scatter crawl partitions across peers.
 *
 * <p>{@link #submitJob} picks the best-fit live worker from the {@link CrawlWorkerRegistry} (capability +
 * load aware), POSTs the serialisable descriptor to that peer's {@code /api/cluster/jobs}, and returns the
 * peer's base URL as the {@code externalId} so status/cancel can reach it. The worker runs the job and calls
 * back the orchestrator ({@code /api/scheduler/callback} or {@code /api/distributed-crawl/callback}).</p>
 */
@Component
public class RemotePeerJobSchedulerDelegate implements ExternalJobSchedulerDelegate {

    private static final Logger log = LoggerFactory.getLogger(RemotePeerJobSchedulerDelegate.class);

    private final CrawlWorkerRegistry registry;
    private final ResourceSchedulerConfigService configService;
    private final ObjectMapper objectMapper;

    /** Optional: pressure-gates failover so peers take scheduler work only when the local host is saturated. */
    @Autowired(required = false)
    private ResourceGovernor governor;

    @Value("${server.port:8080}")
    private int serverPort;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public RemotePeerJobSchedulerDelegate(CrawlWorkerRegistry registry,
                                          ResourceSchedulerConfigService configService,
                                          ObjectMapper objectMapper) {
        this.registry = registry;
        this.configService = configService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getMode() {
        return "cluster";
    }

    @Override
    public CompletableFuture<ExternalJobRef> submitJob(
            String jobId, String jobType, String description,
            JobResourceProfile resourceProfile, Map<String, Object> metadata) {
        return CompletableFuture.supplyAsync(() -> {
            boolean requiresGpu = resourceProfile != null && resourceProfile.requiresGpu();
            // A coordinator-pinned target (targetWorkerBaseUrl) wins; otherwise pick the best-fit live worker.
            String pinned = metadata != null && metadata.get("targetWorkerBaseUrl") != null
                    ? metadata.get("targetWorkerBaseUrl").toString() : null;
            String workerBaseUrl;
            String workerLabel;
            if (pinned != null && !pinned.isBlank()) {
                workerBaseUrl = normalize(pinned);
                workerLabel = pinned;
            } else {
                Optional<WorkerCapabilities> peerOpt =
                        registry.selectWorker(jobType, requiresGpu, System.currentTimeMillis());
                if (peerOpt.isEmpty()) {
                    log.warn("No capable cluster worker for job '{}' (type='{}', requiresGpu={})",
                            jobId, jobType, requiresGpu);
                    return new ExternalJobRef(jobId, "FAILED",
                            "no capable cluster worker for jobType '" + jobType + "'");
                }
                workerBaseUrl = normalize(peerOpt.get().baseUrl());
                workerLabel = peerOpt.get().workerId();
            }
            try {
                ResourceSchedulerConfig cfg = configService.getConfiguration();
                ClusterJobSubmission sub = new ClusterJobSubmission(
                        jobId, jobType, description,
                        resourceProfile != null ? resourceProfile.serviceType() : jobType,
                        requiresGpu, metadata, orchestratorBaseUrl(cfg));
                String url = workerBaseUrl + "/api/cluster/jobs";
                HttpRequest.Builder rb = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(sub)));
                addAuth(rb, cfg);
                HttpResponse<String> resp = httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 == 2) {
                    log.info("Delegated job '{}' ({}) to CrawlWorker '{}'", jobId, jobType, workerLabel);
                    // externalId = worker base URL so cancel/status can reach the worker that took it.
                    return new ExternalJobRef(workerBaseUrl, "SUBMITTED", "accepted by " + workerLabel);
                }
                return new ExternalJobRef(jobId, "FAILED",
                        "worker " + workerLabel + " returned HTTP " + resp.statusCode() + ": " + resp.body());
            } catch (Exception e) {
                log.error("Failed to delegate job '{}' to worker '{}': {}", jobId, workerLabel, e.getMessage());
                return new ExternalJobRef(jobId, "FAILED",
                        "submit to " + workerLabel + " failed: " + e.getMessage());
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> cancelJob(String jobId, String externalRef) {
        return CompletableFuture.supplyAsync(() -> {
            if (externalRef == null || externalRef.isBlank()) {
                return false;
            }
            try {
                HttpRequest.Builder rb = HttpRequest.newBuilder()
                        .uri(URI.create(normalize(externalRef) + "/api/cluster/jobs/" + jobId + "/cancel"))
                        .timeout(Duration.ofSeconds(15))
                        .DELETE();
                addAuth(rb, configService.getConfiguration());
                HttpResponse<Void> resp = httpClient.send(rb.build(), HttpResponse.BodyHandlers.discarding());
                return resp.statusCode() / 100 == 2;
            } catch (Exception e) {
                log.warn("Cancel of job '{}' on {} failed: {}", jobId, externalRef, e.getMessage());
                return false;
            }
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<ExternalJobStatus> getJobStatus(String jobId, String externalRef) {
        return CompletableFuture.supplyAsync(() -> {
            if (externalRef == null || externalRef.isBlank()) {
                return new ExternalJobStatus(jobId, "UNKNOWN", "no externalRef", Map.of());
            }
            try {
                HttpRequest.Builder rb = HttpRequest.newBuilder()
                        .uri(URI.create(normalize(externalRef) + "/api/cluster/jobs/" + jobId))
                        .timeout(Duration.ofSeconds(15))
                        .GET();
                addAuth(rb, configService.getConfiguration());
                HttpResponse<String> resp = httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 404) {
                    return new ExternalJobStatus(jobId, "UNKNOWN", "not tracked by worker", Map.of());
                }
                if (resp.statusCode() / 100 != 2) {
                    return new ExternalJobStatus(jobId, "UNKNOWN", "HTTP " + resp.statusCode(), Map.of());
                }
                Map<String, Object> m = objectMapper.readValue(resp.body(), Map.class);
                return new ExternalJobStatus(jobId,
                        String.valueOf(m.getOrDefault("status", "RUNNING")),
                        String.valueOf(m.getOrDefault("message", "")), m);
            } catch (Exception e) {
                return new ExternalJobStatus(jobId, "UNKNOWN", e.getMessage(), Map.of());
            }
        });
    }

    @Override
    public boolean isAvailable() {
        ResourceSchedulerConfig cfg = configService.getConfiguration();
        if (!cfg.isClusterOrchestrator() || registry.size(System.currentTimeMillis()) == 0) {
            return false;
        }
        // Failover mode: offer to take scheduler work only once the local host is saturated — the 3rd tier
        // after local GPU → local CPU. 'always' mode (or no governor to consult) offers whenever a peer exists.
        // Note: the DistributedCrawlCoordinator calls submitJob directly (bypassing isAvailable), so explicit
        // distributed crawls always scatter regardless of this gate.
        if (cfg.isClusterFailoverMode() && governor != null && !governor.isLocalSaturated()) {
            return false;
        }
        return true;
    }

    /** This orchestrator's externally-reachable base URL, for workers to call back. */
    private String orchestratorBaseUrl(ResourceSchedulerConfig cfg) {
        if (cfg.getClusterAdvertiseBaseUrl() != null && !cfg.getClusterAdvertiseBaseUrl().isBlank()) {
            return normalize(cfg.getClusterAdvertiseBaseUrl());
        }
        String host;
        try {
            host = InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            host = "127.0.0.1";
        }
        return "http://" + host + ":" + serverPort;
    }

    private void addAuth(HttpRequest.Builder rb, ResourceSchedulerConfig cfg) {
        String token = cfg.getExternalAuthToken();
        if (token != null && !token.isBlank()) {
            rb.header("Authorization", "Bearer " + token);
        }
    }

    private static String normalize(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
