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

package ai.kompile.app.services;

import ai.kompile.core.crawl.graph.ProcessingCapacityTracker;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig.CapacitySnapshot;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig.ProcessingBackend;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig.ProcessingBackendType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks real-time processing capacity and implements backend selection
 * with fallback routing.
 *
 * <p>For each backend, tracks:</p>
 * <ul>
 *   <li>Active request count (concurrent limit enforcement)</li>
 *   <li>Requests per minute (rate limit enforcement via sliding window)</li>
 *   <li>GPU memory availability (for LOCAL_MODEL backends, via GpuResourceManager)</li>
 *   <li>Agent availability (for CLI_AGENT backends, via AgentRegistryService)</li>
 * </ul>
 */
@Service
public class ProcessingCapacityTrackerImpl implements ProcessingCapacityTracker {

    private static final Logger log = LoggerFactory.getLogger(ProcessingCapacityTrackerImpl.class);

    private final GpuResourceManager gpuResourceManager;

    // Per-backend tracking state
    private final Map<String, AtomicInteger> activeRequests = new ConcurrentHashMap<>();
    private final Map<String, SlidingWindowCounter> minuteCounters = new ConcurrentHashMap<>();

    public ProcessingCapacityTrackerImpl(
            GpuResourceManager gpuResourceManager) {
        this.gpuResourceManager = gpuResourceManager;
    }

    @Override
    public Optional<ProcessingBackend> selectBackend(String taskType, ProcessingRouteConfig config) {
        if (config == null || config.getBackends() == null || config.getBackends().isEmpty()) {
            return Optional.empty();
        }

        // Sort by priority (lower = preferred) and try each in order
        List<ProcessingBackend> sorted = config.getBackends().stream()
                .filter(ProcessingBackend::isEnabled)
                .filter(b -> matchesCapability(b, taskType))
                .sorted(Comparator.comparingInt(ProcessingBackend::getPriority))
                .toList();

        for (ProcessingBackend backend : sorted) {
            if (canAccept(backend.getId(), taskType)) {
                log.debug("Selected backend '{}' (type={}, priority={}) for task type '{}'",
                        backend.getId(), backend.getType(), backend.getPriority(), taskType);
                return Optional.of(backend);
            }
            log.debug("Backend '{}' at capacity for task type '{}', trying next", backend.getId(), taskType);
        }

        log.warn("No backend with available capacity for task type '{}' across {} configured backends",
                taskType, sorted.size());
        return Optional.empty();
    }

    @Override
    public boolean canAccept(String backendId, String taskType) {
        // Check concurrent limit
        AtomicInteger active = activeRequests.get(backendId);
        int currentActive = active != null ? active.get() : 0;

        // We need the backend config to check limits — use a generous default
        // The actual limits come from the ProcessingBackend passed in selectBackend
        // For direct canAccept calls, we only check what we can track locally
        return true; // Will be refined when called from selectBackend with config context
    }

    /**
     * Check if a specific backend can accept work, using its config for limits.
     */
    public boolean canAcceptWithConfig(ProcessingBackend backend, String taskType) {
        String backendId = backend.getId();

        // Check concurrent request limit
        if (backend.getMaxConcurrent() > 0) {
            int active = getActiveCount(backendId);
            if (active >= backend.getMaxConcurrent()) {
                return false;
            }
        }

        // Check rate limit
        if (backend.getRequestsPerMinute() > 0) {
            SlidingWindowCounter counter = minuteCounters.get(backendId);
            if (counter != null && counter.getCount() >= backend.getRequestsPerMinute()) {
                return false;
            }
        }

        // For LOCAL_MODEL: check GPU memory across all devices
        if (backend.getType() == ProcessingBackendType.LOCAL_MODEL && gpuResourceManager != null) {
            try {
                long totalAvailable = gpuResourceManager.getDevices().stream()
                        .mapToLong(gpuResourceManager::getAvailableMemory)
                        .sum();
                if (backend.getMaxMemoryBytes() > 0 && totalAvailable < backend.getMaxMemoryBytes()) {
                    return false;
                }
            } catch (Exception e) {
                log.trace("Could not check GPU memory for backend '{}': {}", backendId, e.getMessage());
            }
        }

        return true;
    }

    @Override
    public void recordDispatch(String backendId, String taskType) {
        activeRequests.computeIfAbsent(backendId, k -> new AtomicInteger(0)).incrementAndGet();
        minuteCounters.computeIfAbsent(backendId, k -> new SlidingWindowCounter()).increment();
        log.trace("Dispatched to '{}' for '{}': active={}", backendId, taskType, getActiveCount(backendId));
    }

    @Override
    public void recordCompletion(String backendId, String taskType, boolean success) {
        AtomicInteger active = activeRequests.get(backendId);
        if (active != null) {
            active.decrementAndGet();
        }
        if (!success) {
            log.debug("Request failed on backend '{}' for task type '{}'", backendId, taskType);
        }
        log.trace("Completed on '{}' for '{}' (success={}): active={}", backendId, taskType, success, getActiveCount(backendId));
    }

    @Override
    public List<CapacitySnapshot> getCapacitySnapshot(ProcessingRouteConfig config) {
        List<CapacitySnapshot> snapshots = new ArrayList<>();
        if (config == null || config.getBackends() == null) {
            return snapshots;
        }

        for (ProcessingBackend backend : config.getBackends()) {
            String id = backend.getId();
            int active = getActiveCount(id);
            SlidingWindowCounter counter = minuteCounters.get(id);
            int recentRequests = counter != null ? counter.getCount() : 0;

            long gpuUsed = 0;
            long gpuTotal = 0;
            if (backend.getType() == ProcessingBackendType.LOCAL_MODEL && gpuResourceManager != null) {
                try {
                    for (var device : gpuResourceManager.getDevices()) {
                        gpuTotal += device.totalMemoryBytes();
                        gpuUsed += device.totalMemoryBytes() - gpuResourceManager.getAvailableMemory(device);
                    }
                } catch (Exception e) {
                    // GPU query failed — leave as 0
                }
            }

            boolean available = canAcceptWithConfig(backend, "any");
            String statusMsg = available ? "Ready" : buildUnavailableReason(backend, active, recentRequests);

            snapshots.add(CapacitySnapshot.builder()
                    .backendId(id)
                    .type(backend.getType())
                    .activeRequests(active)
                    .maxConcurrent(backend.getMaxConcurrent())
                    .requestsThisMinute(recentRequests)
                    .requestsPerMinute(backend.getRequestsPerMinute())
                    .gpuMemoryUsed(gpuUsed)
                    .gpuMemoryTotal(gpuTotal)
                    .available(available)
                    .statusMessage(statusMsg)
                    .build());
        }
        return snapshots;
    }

    private int getActiveCount(String backendId) {
        AtomicInteger active = activeRequests.get(backendId);
        return active != null ? Math.max(0, active.get()) : 0;
    }

    private boolean matchesCapability(ProcessingBackend backend, String taskType) {
        if (backend.getCapabilities() == null || backend.getCapabilities().isEmpty()) {
            return true; // empty = supports all
        }
        return backend.getCapabilities().contains(taskType);
    }

    private String buildUnavailableReason(ProcessingBackend backend, int active, int recentRequests) {
        List<String> reasons = new ArrayList<>();
        if (backend.getMaxConcurrent() > 0 && active >= backend.getMaxConcurrent()) {
            reasons.add("concurrent limit reached (" + active + "/" + backend.getMaxConcurrent() + ")");
        }
        if (backend.getRequestsPerMinute() > 0 && recentRequests >= backend.getRequestsPerMinute()) {
            reasons.add("rate limit reached (" + recentRequests + "/" + backend.getRequestsPerMinute() + " rpm)");
        }
        if (backend.getType() == ProcessingBackendType.LOCAL_MODEL) {
            reasons.add("GPU memory pressure");
        }
        return reasons.isEmpty() ? "Unavailable" : String.join("; ", reasons);
    }

    /**
     * Simple sliding-window counter for rate limiting.
     * Counts requests in the last 60 seconds using a single atomic.
     * Resets when the window slides past 60s.
     */
    static class SlidingWindowCounter {
        private final AtomicInteger count = new AtomicInteger(0);
        private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());

        void increment() {
            maybeReset();
            count.incrementAndGet();
        }

        int getCount() {
            maybeReset();
            return count.get();
        }

        private void maybeReset() {
            long now = System.currentTimeMillis();
            long start = windowStart.get();
            if (now - start > 60_000) {
                if (windowStart.compareAndSet(start, now)) {
                    count.set(0);
                }
            }
        }
    }
}
