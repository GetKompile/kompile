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

package ai.kompile.core.crawl.graph;

import ai.kompile.core.crawl.graph.ProcessingRouteConfig.ProcessingBackend;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig.ProcessingBackendType;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob.BackendRoutingStats;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dynamic workload balancer that routes processing tasks across heterogeneous
 * backends (LOCAL_MODEL, CLI_AGENT, API_AGENT) with rerouting on overload.
 *
 * <p>Implements a weighted scoring algorithm inspired by HERMES load-based routing:</p>
 * <ul>
 *   <li><b>Capacity score</b>: (maxConcurrent - active) / maxConcurrent</li>
 *   <li><b>Latency score</b>: 1.0 / (1.0 + emaLatency/1000)</li>
 *   <li><b>Cost score</b>: inverse priority (prefer cheaper backends)</li>
 *   <li><b>Health penalty</b>: consecutive failures degrade score exponentially</li>
 * </ul>
 *
 * <p>The balancer also implements continuous batching admission: new work items
 * can be accepted mid-batch if a backend has remaining capacity, rather than
 * waiting for the full batch to complete.</p>
 *
 * <p>Rerouting triggers when:</p>
 * <ul>
 *   <li>A backend's active count reaches maxConcurrent</li>
 *   <li>A backend's error rate exceeds the health threshold</li>
 *   <li>A backend's latency EMA exceeds the latency ceiling</li>
 * </ul>
 */
public class WorkloadBalancer {

    /**
     * Tracks live state for a single backend.
     */
    private static class BackendState {
        final String backendId;
        final ProcessingBackendType type;
        final int maxConcurrent;
        final int priority;
        final AtomicInteger activeRequests = new AtomicInteger(0);
        final AtomicLong requestsDispatched = new AtomicLong(0);
        final AtomicLong requestsCompleted = new AtomicLong(0);
        final AtomicLong requestsFailed = new AtomicLong(0);
        final AtomicLong requestsRerouted = new AtomicLong(0);
        final AtomicLong inputTokens = new AtomicLong(0);
        final AtomicLong outputTokens = new AtomicLong(0);
        final AtomicLong costCentsX100 = new AtomicLong(0);
        final AtomicLong emaLatencyMsX100 = new AtomicLong(0);
        final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        volatile boolean healthy = true;
        volatile String unhealthyReason = null;

        // Rate limiting
        final AtomicInteger requestsThisMinute = new AtomicInteger(0);
        final int requestsPerMinute;
        volatile long currentMinuteStart = System.currentTimeMillis();

        // Cooldown tracking
        volatile long cooldownUntilMs = 0;
        final AtomicInteger cooldownCount = new AtomicInteger(0);

        BackendState(ProcessingBackend backend) {
            this.backendId = backend.getId();
            this.type = backend.getType();
            this.maxConcurrent = backend.getMaxConcurrent() > 0 ? backend.getMaxConcurrent() : Integer.MAX_VALUE;
            this.priority = backend.getPriority();
            this.requestsPerMinute = backend.getRequestsPerMinute();
        }

        boolean isInCooldown() {
            return cooldownUntilMs > 0 && System.currentTimeMillis() < cooldownUntilMs;
        }
    }

    private final Map<String, BackendState> backends = new ConcurrentHashMap<>();
    private final double emaAlpha;
    private final int healthFailureThreshold;
    private final long latencyCeilingMs;
    private final long cooldownBaseMs;
    private final double cooldownMultiplier;

    /**
     * Create a workload balancer for the given backend configuration.
     *
     * @param config   the processing route config with backend definitions
     * @param emaAlpha EMA smoothing factor for latency tracking (0.0 - 1.0)
     * @param healthFailureThreshold consecutive failures before marking unhealthy
     * @param latencyCeilingMs       latency above which backend score is penalized
     */
    public WorkloadBalancer(ProcessingRouteConfig config, double emaAlpha,
                            int healthFailureThreshold, long latencyCeilingMs) {
        this(config, emaAlpha, healthFailureThreshold, latencyCeilingMs, 10_000L, 2.0);
    }

    /**
     * Create a workload balancer with explicit cooldown parameters.
     */
    public WorkloadBalancer(ProcessingRouteConfig config, double emaAlpha,
                            int healthFailureThreshold, long latencyCeilingMs,
                            long cooldownBaseMs, double cooldownMultiplier) {
        this.emaAlpha = emaAlpha;
        this.healthFailureThreshold = healthFailureThreshold;
        this.latencyCeilingMs = latencyCeilingMs;
        this.cooldownBaseMs = cooldownBaseMs;
        this.cooldownMultiplier = cooldownMultiplier;
        if (config != null && config.getBackends() != null) {
            for (ProcessingBackend backend : config.getBackends()) {
                if (backend.isEnabled()) {
                    backends.put(backend.getId(), new BackendState(backend));
                }
            }
        }
    }

    /**
     * Select the best backend for the given task type using weighted scoring.
     * Returns empty if no backend has capacity.
     *
     * @param taskType "vlm", "llm", or "embedding"
     * @return the selected backend ID, or empty
     */
    public Optional<String> selectBackend(String taskType) {
        return backends.values().stream()
                .filter(bs -> bs.healthy)
                .filter(bs -> canAccept(bs))
                .max(Comparator.comparingDouble(bs -> scoreBackend(bs)))
                .map(bs -> bs.backendId);
    }

    /**
     * Try to select a backend; if the preferred one is full, reroute to the next best.
     * Records reroute events on the job.
     *
     * @param taskType    the task type
     * @param preferredId preferred backend ID (may be null)
     * @param job         the crawl job for recording reroute events
     * @return the selected backend ID, or empty if all exhausted
     */
    public Optional<String> selectWithReroute(String taskType, String preferredId,
                                              UnifiedCrawlJob job) {
        // Try preferred first
        if (preferredId != null) {
            BackendState preferred = backends.get(preferredId);
            if (preferred != null && preferred.healthy && canAccept(preferred)) {
                return Optional.of(preferredId);
            }
        }

        // Score all available backends
        Optional<String> selected = selectBackend(taskType);
        if (selected.isPresent() && preferredId != null
                && !selected.get().equals(preferredId)) {
            // This is a reroute
            BackendState from = backends.get(preferredId);
            if (from != null) {
                from.requestsRerouted.incrementAndGet();
            }
            String reason = preferredId != null ?
                    deriveRerouteReason(backends.get(preferredId)) : "no_preferred";
            job.recordRerouteEvent(preferredId, selected.get(), taskType, reason, 1);
        }

        if (selected.isEmpty() && job != null) {
            job.getDroppedItems().incrementAndGet();
        }

        return selected;
    }

    /**
     * Record that a request was dispatched to a backend.
     */
    public void recordDispatch(String backendId) {
        BackendState bs = backends.get(backendId);
        if (bs == null) return;
        bs.activeRequests.incrementAndGet();
        bs.requestsDispatched.incrementAndGet();
    }

    /**
     * Record that a request completed on a backend.
     *
     * @param backendId   the backend
     * @param success     whether it succeeded
     * @param latencyMs   wall-clock latency
     * @param inTokens    input tokens consumed (0 if not applicable)
     * @param outTokens   output tokens consumed
     * @param costCentsX100 cost in cents * 100
     */
    public void recordCompletion(String backendId, boolean success, long latencyMs,
                                 long inTokens, long outTokens, long costCentsX100) {
        BackendState bs = backends.get(backendId);
        if (bs == null) return;

        bs.activeRequests.decrementAndGet();
        bs.requestsCompleted.incrementAndGet();

        if (success) {
            bs.consecutiveFailures.set(0);
            if (!bs.healthy) {
                bs.healthy = true;
                bs.unhealthyReason = null;
            }
        } else {
            bs.requestsFailed.incrementAndGet();
            int failures = bs.consecutiveFailures.incrementAndGet();
            if (failures >= healthFailureThreshold) {
                bs.healthy = false;
                bs.unhealthyReason = "consecutive_failures:" + failures;
                // Set exponential cooldown: base * multiplier^count
                int count = bs.cooldownCount.incrementAndGet();
                long cooldownMs = (long)(cooldownBaseMs * Math.pow(cooldownMultiplier, count - 1));
                bs.cooldownUntilMs = System.currentTimeMillis() + cooldownMs;
            }
        }

        // Update EMA latency
        if (latencyMs > 0) {
            long latX100 = latencyMs * 100L;
            long prev = bs.emaLatencyMsX100.get();
            long newEma = prev == 0 ? latX100
                    : (long)(emaAlpha * latX100 + (1.0 - emaAlpha) * prev);
            bs.emaLatencyMsX100.set(newEma);
        }

        // Token accounting
        bs.inputTokens.addAndGet(inTokens);
        bs.outputTokens.addAndGet(outTokens);
        bs.costCentsX100.addAndGet(costCentsX100);
    }

    /**
     * Reset the per-minute rate counter (call this on a 1-minute timer).
     */
    public void resetMinuteCounters() {
        long now = System.currentTimeMillis();
        for (BackendState bs : backends.values()) {
            bs.requestsThisMinute.set(0);
            bs.currentMinuteStart = now;
        }
    }

    /**
     * Mark a backend as unhealthy (e.g., from external health check).
     */
    public void markUnhealthy(String backendId, String reason) {
        BackendState bs = backends.get(backendId);
        if (bs != null) {
            bs.healthy = false;
            bs.unhealthyReason = reason;
        }
    }

    /**
     * Mark a backend as healthy again.
     */
    public void markHealthy(String backendId) {
        BackendState bs = backends.get(backendId);
        if (bs != null) {
            bs.healthy = true;
            bs.unhealthyReason = null;
            bs.consecutiveFailures.set(0);
            bs.cooldownUntilMs = 0;
            bs.cooldownCount.set(0);
        }
    }

    /**
     * Publish aggregated stats to a {@link UnifiedCrawlJob} for UI visibility.
     */
    public void publishStats(UnifiedCrawlJob job) {
        long totalIn = 0, totalOut = 0, totalCost = 0;

        for (BackendState bs : backends.values()) {
            BackendRoutingStats stats = BackendRoutingStats.builder()
                    .backendId(bs.backendId)
                    .backendType(bs.type.name())
                    .requestsDispatched(bs.requestsDispatched.get())
                    .requestsCompleted(bs.requestsCompleted.get())
                    .requestsFailed(bs.requestsFailed.get())
                    .requestsRerouted(bs.requestsRerouted.get())
                    .inputTokens(bs.inputTokens.get())
                    .outputTokens(bs.outputTokens.get())
                    .estimatedCostCentsX100(bs.costCentsX100.get())
                    .emaLatencyMsX100(bs.emaLatencyMsX100.get())
                    .activeRequests(bs.activeRequests.get())
                    .maxConcurrent(bs.maxConcurrent == Integer.MAX_VALUE ? 0 : bs.maxConcurrent)
                    .healthy(bs.healthy)
                    .unhealthyReason(bs.unhealthyReason)
                    .build();
            job.getBackendStats().put(bs.backendId, stats);

            totalIn += bs.inputTokens.get();
            totalOut += bs.outputTokens.get();
            totalCost += bs.costCentsX100.get();
        }

        job.getTotalInputTokens().set(totalIn);
        job.getTotalOutputTokens().set(totalOut);
        job.getEstimatedCostCentsX100().set(totalCost);
        job.getBackendsCoolingDown().set(getBackendsCoolingDown());
    }

    /**
     * Check all backends in cooldown: if cooldown has expired, tentatively
     * mark the backend healthy so it can receive a probe request.
     * Call this periodically (e.g., on a timer alongside {@link #resetMinuteCounters()}).
     *
     * @return number of backends recovered from cooldown
     */
    public int tryCooldownRecovery() {
        int recovered = 0;
        long now = System.currentTimeMillis();
        for (BackendState bs : backends.values()) {
            if (!bs.healthy && bs.cooldownUntilMs > 0 && now >= bs.cooldownUntilMs) {
                bs.healthy = true;
                bs.unhealthyReason = null;
                bs.consecutiveFailures.set(0);
                bs.cooldownUntilMs = 0;
                // Don't reset cooldownCount — next failure will use a longer cooldown
                recovered++;
            }
        }
        return recovered;
    }

    /**
     * Get the number of backends currently in cooldown.
     */
    public int getBackendsCoolingDown() {
        int count = 0;
        long now = System.currentTimeMillis();
        for (BackendState bs : backends.values()) {
            if (!bs.healthy && bs.cooldownUntilMs > 0 && now < bs.cooldownUntilMs) {
                count++;
            }
        }
        return count;
    }

    // ---- Internal scoring ----

    private boolean canAccept(BackendState bs) {
        // Check cooldown
        if (bs.isInCooldown()) {
            return false;
        }
        // Check concurrency limit
        if (bs.activeRequests.get() >= bs.maxConcurrent) {
            return false;
        }
        // Check rate limit
        if (bs.requestsPerMinute > 0) {
            long now = System.currentTimeMillis();
            if (now - bs.currentMinuteStart >= 60_000) {
                bs.requestsThisMinute.set(0);
                bs.currentMinuteStart = now;
            }
            if (bs.requestsThisMinute.get() >= bs.requestsPerMinute) {
                return false;
            }
        }
        return true;
    }

    private double scoreBackend(BackendState bs) {
        // Capacity score: how much headroom (0.0 - 1.0)
        double capacityScore = bs.maxConcurrent == Integer.MAX_VALUE ? 1.0
                : (double)(bs.maxConcurrent - bs.activeRequests.get()) / bs.maxConcurrent;

        // Latency score: prefer faster backends (0.0 - 1.0)
        double latencyMs = bs.emaLatencyMsX100.get() / 100.0;
        double latencyScore = 1.0 / (1.0 + latencyMs / 1000.0);

        // Cost score: lower priority number = preferred = higher score
        double costScore = 1.0 / (1.0 + bs.priority / 10.0);

        // Health penalty: exponential decay on failures
        int failures = bs.consecutiveFailures.get();
        double healthPenalty = failures > 0 ? Math.pow(0.5, failures) : 1.0;

        // Latency ceiling penalty
        double ceilingPenalty = latencyMs > latencyCeilingMs ? 0.3 : 1.0;

        return (capacityScore * 0.4 + latencyScore * 0.3 + costScore * 0.3)
                * healthPenalty * ceilingPenalty;
    }

    private String deriveRerouteReason(BackendState from) {
        if (from == null) return "backend_unavailable";
        if (!from.healthy) return "unhealthy:" + from.unhealthyReason;
        if (from.activeRequests.get() >= from.maxConcurrent) return "at_capacity";
        if (from.requestsPerMinute > 0
                && from.requestsThisMinute.get() >= from.requestsPerMinute) {
            return "rate_limited";
        }
        return "lower_score";
    }

    /**
     * Get a snapshot of all backend states for the capacity endpoint.
     */
    public List<ProcessingRouteConfig.CapacitySnapshot> getCapacitySnapshots() {
        return backends.values().stream()
                .map(bs -> ProcessingRouteConfig.CapacitySnapshot.builder()
                        .backendId(bs.backendId)
                        .type(bs.type)
                        .activeRequests(bs.activeRequests.get())
                        .maxConcurrent(bs.maxConcurrent == Integer.MAX_VALUE ? 0 : bs.maxConcurrent)
                        .requestsThisMinute(bs.requestsThisMinute.get())
                        .requestsPerMinute(bs.requestsPerMinute)
                        .available(bs.healthy && canAccept(bs))
                        .statusMessage(bs.healthy ? "ok" : bs.unhealthyReason)
                        .build())
                .toList();
    }
}
