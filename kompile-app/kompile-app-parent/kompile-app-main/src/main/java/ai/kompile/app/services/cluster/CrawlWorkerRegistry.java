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

import ai.kompile.app.services.scheduler.ResourceSchedulerConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The orchestrator's live view of the CrawlWorker cluster — the place where "capabilities are known and
 * shared". Workers POST their {@link WorkerCapabilities} here (register + heartbeat); the registry keeps the
 * latest per worker, evicts those that go silent past the configured timeout, and selects a best-fit worker
 * for a job. Lock-free reads via a {@link ConcurrentHashMap}; callers pass {@code nowEpochMs} so the
 * timeout/selection logic is deterministically testable.
 */
@Service
public class CrawlWorkerRegistry {

    private static final Logger log = LoggerFactory.getLogger(CrawlWorkerRegistry.class);

    @Autowired
    private ResourceSchedulerConfigService configService;

    private final Map<String, Registration> workers = new ConcurrentHashMap<>();

    /** A worker's latest advertised capabilities and when the orchestrator last heard from it. */
    public record Registration(WorkerCapabilities capabilities, long lastSeenEpochMs) {
    }

    /** Record a worker register/heartbeat; returns the stored registration. */
    public Registration registerOrHeartbeat(WorkerCapabilities caps, long nowEpochMs) {
        if (caps == null || caps.workerId() == null || caps.workerId().isBlank()) {
            throw new IllegalArgumentException("worker capabilities must include a workerId");
        }
        Registration reg = new Registration(caps, nowEpochMs);
        Registration prior = workers.put(caps.workerId(), reg);
        if (prior == null) {
            log.info("CrawlWorker '{}' joined cluster — backends={}, jobTypes={}, {}/{} slots free, baseUrl={}",
                    caps.workerId(), caps.backends(), caps.supportedJobTypes(),
                    caps.freeSlots(), caps.maxConcurrentJobs(), caps.baseUrl());
        }
        return reg;
    }

    /** Graceful deregister (a worker leaving the cluster). */
    public boolean deregister(String workerId) {
        boolean removed = workers.remove(workerId) != null;
        if (removed) {
            log.info("CrawlWorker '{}' left cluster", workerId);
        }
        return removed;
    }

    /** Live workers — those heard from within the configured timeout. */
    public List<WorkerCapabilities> liveWorkers(long nowEpochMs) {
        evictStale(nowEpochMs);
        List<WorkerCapabilities> out = new ArrayList<>(workers.size());
        for (Registration r : workers.values()) {
            out.add(r.capabilities());
        }
        return out;
    }

    /**
     * Best live worker for a job: it must be able to run the type (supported + GPU if required + a free slot +
     * accepting work); among those, prefer the most free slots, breaking ties toward the lowest CPU load.
     * Empty when no peer qualifies (caller then keeps the work local).
     */
    public Optional<WorkerCapabilities> selectWorker(String jobType, boolean requiresGpu, long nowEpochMs) {
        evictStale(nowEpochMs);
        return workers.values().stream()
                .map(Registration::capabilities)
                .filter(c -> c.canRun(jobType, requiresGpu))
                .max(Comparator.comparingInt(WorkerCapabilities::freeSlots)
                        .thenComparing(Comparator.comparingDouble(WorkerCapabilities::cpuLoad).reversed()));
    }

    /** A specific live worker by id (used by lifecycle proxy endpoints). */
    public Optional<WorkerCapabilities> find(String workerId, long nowEpochMs) {
        evictStale(nowEpochMs);
        Registration r = workers.get(workerId);
        return r == null ? Optional.empty() : Optional.of(r.capabilities());
    }

    /** Count of live workers. */
    public int size(long nowEpochMs) {
        evictStale(nowEpochMs);
        return workers.size();
    }

    private void evictStale(long nowEpochMs) {
        long timeoutMs = Math.max(5, configService.getConfiguration().getClusterWorkerTimeoutSeconds()) * 1000L;
        workers.entrySet().removeIf(e -> {
            boolean stale = nowEpochMs - e.getValue().lastSeenEpochMs() > timeoutMs;
            if (stale) {
                log.info("CrawlWorker '{}' evicted from cluster (no heartbeat for >{}s)",
                        e.getKey(), timeoutMs / 1000);
            }
            return stale;
        });
    }
}
