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

import ai.kompile.app.config.ResourceSchedulerConfig;
import ai.kompile.app.services.scheduler.ResourceSchedulerConfigService;
import ai.kompile.crawl.graph.ClusterBackendHealth;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * App-main implementation of the {@link ClusterBackendHealth} SPI (distributed-crawl hardening Phase 4) — wired
 * into the crawl-graph {@code CrawlLlmDispatcher} so a flaky/rate-limited shared LLM backend trips ONCE for the
 * cluster instead of once per worker.
 *
 * <p>Role-aware: the <strong>orchestrator</strong> holds the authoritative per-backend breaker and answers
 * {@link #isOpen} from it; a <strong>worker</strong> reports its local failures to the orchestrator (off the
 * happy path, on a daemon thread) and answers {@link #isOpen} from the open-set the orchestrator returns on each
 * report. Everything is gated by {@code clusterSharedBackendBreakerEnabled} (default off → no-op) and fail-open:
 * if the orchestrator is unreachable, {@link #isOpen} simply returns the last-known (or empty) set.</p>
 */
@Component
public class ClusterBackendHealthAdapter implements ClusterBackendHealth {

    private static final Logger log = LoggerFactory.getLogger(ClusterBackendHealthAdapter.class);

    private final ResourceSchedulerConfigService configService;
    private final ObjectMapper objectMapper;

    /** Orchestrator-authoritative per-backend breakers. */
    private final Map<String, ClusterBreaker> breakers = new ConcurrentHashMap<>();
    /** Worker-side cache of the cluster open-set, refreshed from report responses. */
    private volatile Set<String> cachedOpen = Set.of();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService reporter = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cluster-backend-health");
        t.setDaemon(true);
        return t;
    });

    public ClusterBackendHealthAdapter(ResourceSchedulerConfigService configService, ObjectMapper objectMapper) {
        this.configService = configService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean isOpen(String backendId) {
        ResourceSchedulerConfig cfg = cfg();
        if (cfg == null || !cfg.isClusterSharedBackendBreakerEnabled() || backendId == null) {
            return false; // feature off → advisory says "available", behavior unchanged
        }
        if (cfg.isClusterOrchestrator()) {
            ClusterBreaker b = breakers.get(backendId);
            return b != null && b.isOpen();
        }
        return cachedOpen.contains(backendId);
    }

    @Override
    public void record(String backendId, Event event) {
        ResourceSchedulerConfig cfg = cfg();
        if (cfg == null || !cfg.isClusterSharedBackendBreakerEnabled() || backendId == null) {
            return;
        }
        if (cfg.isClusterOrchestrator()) {
            breaker(backendId, cfg).recordFailure(); // local node is authoritative
        } else {
            reportToOrchestrator(backendId, event, cfg);
        }
    }

    /** Orchestrator-side hook (called by the controller): apply a worker-reported event, return the open-set. */
    public Set<String> applyRemoteEvent(String backendId, Event event) {
        ResourceSchedulerConfig cfg = cfg();
        if (cfg != null && cfg.isClusterSharedBackendBreakerEnabled() && backendId != null) {
            breaker(backendId, cfg).recordFailure();
        }
        return openBackends();
    }

    /** Backends currently tripped cluster-wide (orchestrator authoritative view). */
    public Set<String> openBackends() {
        Set<String> open = new HashSet<>();
        for (Map.Entry<String, ClusterBreaker> e : breakers.entrySet()) {
            if (e.getValue().isOpen()) {
                open.add(e.getKey());
            }
        }
        return open;
    }

    private ClusterBreaker breaker(String backendId, ResourceSchedulerConfig cfg) {
        return breakers.computeIfAbsent(backendId, id ->
                new ClusterBreaker(cfg.getClusterBackendFailureThreshold(), cfg.getClusterBackendCooldownSeconds()));
    }

    private void reportToOrchestrator(String backendId, Event event, ResourceSchedulerConfig cfg) {
        String base = cfg.getClusterOrchestratorUrl();
        if (base == null || base.isBlank()) {
            return;
        }
        reporter.submit(() -> {
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("backendId", backendId);
                payload.put("event", event.name());
                HttpRequest.Builder rb = HttpRequest.newBuilder()
                        .uri(URI.create(normalize(base) + "/api/distributed-crawl/backend-health"))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
                String token = cfg.getExternalAuthToken();
                if (token != null && !token.isBlank()) {
                    rb.header("Authorization", "Bearer " + token);
                }
                HttpResponse<String> resp = httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 == 2) {
                    cachedOpen = parseOpenBackends(resp.body());
                }
            } catch (Exception e) {
                log.debug("Cluster backend-health report for '{}' failed (fail-open): {}", backendId, e.getMessage());
            }
        });
    }

    private Set<String> parseOpenBackends(String body) {
        try {
            JsonNode arr = objectMapper.readTree(body).get("openBackends");
            if (arr != null && arr.isArray()) {
                Set<String> open = new HashSet<>();
                arr.forEach(n -> open.add(n.asText()));
                return open;
            }
        } catch (Exception ignored) {
            // fail-open: keep the previous view
        }
        return cachedOpen;
    }

    private ResourceSchedulerConfig cfg() {
        return configService != null ? configService.getConfiguration() : null;
    }

    private static String normalize(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @PreDestroy
    void shutdown() {
        reporter.shutdownNow();
    }

    /**
     * Cooldown-based cluster breaker: opens after {@code threshold} aggregate failures, then half-opens (resets)
     * once {@code cooldown} has elapsed. Package-private for tests.
     */
    static final class ClusterBreaker {
        private final int threshold;
        private final long cooldownMs;
        private int failures;
        private long openedAtMs;

        ClusterBreaker(int threshold, int cooldownSeconds) {
            this.threshold = Math.max(1, threshold);
            this.cooldownMs = Math.max(1, cooldownSeconds) * 1000L;
        }

        synchronized void recordFailure() {
            failures++;
            if (failures >= threshold) {
                openedAtMs = System.currentTimeMillis();
            }
        }

        synchronized boolean isOpen() {
            if (openedAtMs == 0) {
                return false;
            }
            if (System.currentTimeMillis() - openedAtMs >= cooldownMs) {
                openedAtMs = 0;
                failures = 0;
                return false; // cooldown elapsed → half-open (reset)
            }
            return true;
        }
    }
}
