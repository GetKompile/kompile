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

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Persists job scheduler history to JSON files for monitoring, debugging,
 * and historical analysis.
 *
 * <p>Listens for {@link JobSchedulerEvent} and records completed, failed,
 * and cancelled jobs. Supports querying by type, status, and date range.</p>
 *
 * <p>Storage: {@code ~/.kompile/logs/scheduler/} with one JSON file per job.</p>
 */
@Service
public class JobSchedulerHistoryService {

    private static final Logger log = LoggerFactory.getLogger(JobSchedulerHistoryService.class);
    private static final String HISTORY_DIR = "logs/scheduler";

    private final ObjectMapper objectMapper;
    private final Path historyDir;
    private final ResourceSchedulerConfigService configService;

    // In-memory cache of recent history (bounded)
    private final ConcurrentLinkedDeque<JobHistoryEntry> recentHistory = new ConcurrentLinkedDeque<>();
    private static final int MAX_RECENT_CACHE = 500;
    private final ReentrantReadWriteLock historyLock = new ReentrantReadWriteLock();

    /**
     * A persisted record of a completed/failed/cancelled job.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JobHistoryEntry(
            @JsonProperty("jobId") String jobId,
            @JsonProperty("jobType") String jobType,
            @JsonProperty("description") String description,
            @JsonProperty("state") String state,
            @JsonProperty("priority") int priority,
            @JsonProperty("requiresGpu") boolean requiresGpu,
            @JsonProperty("peakGpuMemoryMb") long peakGpuMemoryMb,
            @JsonProperty("queuedAt") String queuedAt,
            @JsonProperty("startedAt") String startedAt,
            @JsonProperty("completedAt") String completedAt,
            @JsonProperty("durationMs") long durationMs,
            @JsonProperty("waitMs") long waitMs,
            @JsonProperty("error") String error,
            @JsonProperty("cancelReason") String cancelReason,
            @JsonProperty("blockedReason") String blockedReason,
            @JsonProperty("phases") List<PhaseHistoryEntry> phases
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PhaseHistoryEntry(
            @JsonProperty("phaseName") String phaseName,
            @JsonProperty("timestamp") String timestamp,
            @JsonProperty("requiresGpu") boolean requiresGpu
    ) {}

    // Track active phase transitions for building history (thread-safe — accessed from event listener + scheduler threads)
    private final Map<String, List<PhaseHistoryEntry>> activePhaseHistory = new ConcurrentHashMap<>();

    // Track jobs that were recorded via recordFromJob() (rich data) to prevent
    // the sparse event-driven recordJobCompletion() from overwriting them.
    // Bounded: evicted during cleanupOldHistory() to prevent unbounded growth.
    private final Set<String> fullyRecordedIds = ConcurrentHashMap.newKeySet();
    private static final int MAX_FULLY_RECORDED_IDS = 5000;

    public JobSchedulerHistoryService(ResourceSchedulerConfigService configService) {
        this.configService = configService;
        this.objectMapper = JsonUtils.newStandardMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.historyDir = KompileHome.dataDir().toPath().resolve(HISTORY_DIR);
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(historyDir);
            log.info("Job scheduler history directory: {}", historyDir);
            loadRecentHistory();
        } catch (IOException e) {
            log.warn("Could not create scheduler history directory: {}", e.getMessage());
        }
    }

    // ==================== Event Listeners ====================

    @EventListener
    public void onJobSchedulerEvent(JobSchedulerEvent event) {
        switch (event.getEventType()) {
            case JOB_PHASE_TRANSITION -> recordPhaseTransition(event);
            case JOB_COMPLETED -> recordJobCompletion(event, "COMPLETED");
            case JOB_FAILED -> recordJobCompletion(event, "FAILED");
            case JOB_CANCELLED -> recordJobCompletion(event, "CANCELLED");
            default -> {} // JOB_QUEUED, JOB_DISPATCHED, etc. — not persisted
        }
    }

    private void recordPhaseTransition(JobSchedulerEvent event) {
        if (event.getJobId() == null) return;
        activePhaseHistory.computeIfAbsent(event.getJobId(), k -> Collections.synchronizedList(new ArrayList<>()))
                .add(new PhaseHistoryEntry(
                        event.getCurrentPhase(),
                        Instant.now().toString(),
                        Boolean.TRUE.equals(event.getData().get("requiresGpu"))
                ));
    }

    private void recordJobCompletion(JobSchedulerEvent event, String state) {
        if (event.getJobId() == null) return;

        // Skip if already recorded via recordFromJob() (rich direct record)
        if (fullyRecordedIds.contains(event.getJobId())) {
            log.debug("Skipping sparse event record for job '{}' — already fully recorded", event.getJobId());
            return;
        }

        List<PhaseHistoryEntry> phases = activePhaseHistory.remove(event.getJobId());
        long durationMs = event.getData().containsKey("durationMs")
                ? ((Number) event.getData().get("durationMs")).longValue() : 0;
        String error = event.getData().containsKey("error")
                ? String.valueOf(event.getData().get("error")) : null;
        String cancelReason = event.getData().containsKey("cancelReason")
                ? String.valueOf(event.getData().get("cancelReason")) : null;
        String description = event.getData().containsKey("description")
                ? String.valueOf(event.getData().get("description")) : null;

        JobHistoryEntry entry = new JobHistoryEntry(
                event.getJobId(),
                event.getJobType(),
                description,
                state,
                0, false, 0, // minimal info from event
                null, null,
                Instant.now().toString(),
                durationMs, 0,
                error,
                cancelReason,
                null, // blockedReason not available from event
                phases != null ? phases : List.of()
        );

        persistEntry(entry);

        // Add to in-memory cache
        recentHistory.addFirst(entry);
        while (recentHistory.size() > MAX_RECENT_CACHE) {
            recentHistory.removeLast();
        }
    }

    /**
     * Record a completed job from the full ScheduledJob object (richer than events).
     */
    public void recordFromJob(ScheduledJob job) {
        // Mark as fully recorded so the sparse event listener skips this job
        fullyRecordedIds.add(job.getJobId());
        List<PhaseHistoryEntry> phases = activePhaseHistory.remove(job.getJobId());

        long durationMs = 0;
        long waitMs = 0;
        if (job.getStartedAt() != null) {
            Instant end = job.getCompletedAt() != null ? job.getCompletedAt() : Instant.now();
            durationMs = java.time.Duration.between(job.getStartedAt(), end).toMillis();
            waitMs = java.time.Duration.between(job.getQueuedAt(), job.getStartedAt()).toMillis();
        }

        String error = job.getErrorMessage(); // Direct field set before recordFromJob is called
        String cancelReason = job.getCancelReason(); // Direct field set by cancelJob()

        JobHistoryEntry entry = new JobHistoryEntry(
                job.getJobId(),
                job.getJobType(),
                job.getDescription(),
                job.getState().name(),
                job.getPriority(),
                job.getResourceProfile().requiresGpu(),
                job.getResourceProfile().peakGpuMemoryBytes() / (1024L * 1024L),
                job.getQueuedAt().toString(),
                job.getStartedAt() != null ? job.getStartedAt().toString() : null,
                job.getCompletedAt() != null ? job.getCompletedAt().toString() : null,
                durationMs,
                waitMs,
                error,
                cancelReason,
                job.getBlockedReason(),
                phases != null ? phases : List.of()
        );

        persistEntry(entry);

        recentHistory.addFirst(entry);
        while (recentHistory.size() > MAX_RECENT_CACHE) {
            recentHistory.removeLast();
        }

        log.debug("Recorded job history: id='{}', type='{}', state='{}', duration={}ms",
                entry.jobId(), entry.jobType(), entry.state(), entry.durationMs());
    }

    // ==================== Query API ====================

    /**
     * Get recent job history (from in-memory cache).
     */
    public List<JobHistoryEntry> getRecentHistory(int limit) {
        return recentHistory.stream().limit(limit).collect(Collectors.toList());
    }

    /**
     * Query job history by type.
     */
    public List<JobHistoryEntry> getHistoryByType(String jobType, int limit) {
        return recentHistory.stream()
                .filter(e -> jobType.equals(e.jobType()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * Query job history by state.
     */
    public List<JobHistoryEntry> getHistoryByState(String state, int limit) {
        return recentHistory.stream()
                .filter(e -> state.equals(e.state()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * Get aggregated stats.
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();

        Map<String, Long> byType = recentHistory.stream()
                .collect(Collectors.groupingBy(JobHistoryEntry::jobType, Collectors.counting()));
        Map<String, Long> byState = recentHistory.stream()
                .collect(Collectors.groupingBy(JobHistoryEntry::state, Collectors.counting()));

        // Average duration by type
        Map<String, Double> avgDurationByType = recentHistory.stream()
                .filter(e -> "COMPLETED".equals(e.state()) && e.durationMs() > 0)
                .collect(Collectors.groupingBy(
                        JobHistoryEntry::jobType,
                        Collectors.averagingLong(JobHistoryEntry::durationMs)));

        // Average wait time by type
        Map<String, Double> avgWaitByType = recentHistory.stream()
                .filter(e -> e.waitMs() > 0)
                .collect(Collectors.groupingBy(
                        JobHistoryEntry::jobType,
                        Collectors.averagingLong(JobHistoryEntry::waitMs)));

        stats.put("totalEntries", recentHistory.size());
        stats.put("countByType", byType);
        stats.put("countByState", byState);
        stats.put("avgDurationMsByType", avgDurationByType);
        stats.put("avgWaitMsByType", avgWaitByType);
        return stats;
    }

    // ==================== Persistence ====================

    private void persistEntry(JobHistoryEntry entry) {
        try {
            // Use full jobId to prevent filename collisions when multiple jobs complete in the same second
            String timestamp = entry.completedAt() != null
                    ? entry.completedAt().replace(":", "-").substring(0, 19)
                    : Instant.now().toString().replace(":", "-").substring(0, 19);
            String safeJobId = entry.jobId().replaceAll("[^a-zA-Z0-9_-]", "_");
            String filename = String.format("%s_%s_%s.json", timestamp, entry.jobType(), safeJobId);
            Path file = historyDir.resolve(filename);
            objectMapper.writeValue(file.toFile(), entry);
        } catch (Exception e) {
            log.debug("Failed to persist job history entry '{}': {}", entry.jobId(), e.getMessage());
        }
    }

    private void loadRecentHistory() {
        try {
            if (!Files.exists(historyDir)) return;

            try (Stream<Path> files = Files.list(historyDir)) {
                files.filter(p -> p.toString().endsWith(".json"))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString(), Comparator.reverseOrder()))
                        .limit(MAX_RECENT_CACHE)
                        .forEach(file -> {
                            try {
                                JobHistoryEntry entry = objectMapper.readValue(
                                        file.toFile(), JobHistoryEntry.class);
                                recentHistory.addLast(entry);
                            } catch (Exception e) {
                                log.debug("Failed to load history file {}: {}", file.getFileName(), e.getMessage());
                            }
                        });
            }

            log.info("Loaded {} recent job history entries", recentHistory.size());
        } catch (Exception e) {
            log.warn("Failed to load job history: {}", e.getMessage());
        }
    }

    /**
     * Clean up old history files based on retention policy.
     * Also evicts stale fullyRecordedIds and leaked activePhaseHistory entries.
     */
    @Scheduled(cron = "0 0 3 * * ?") // 3 AM daily
    public void cleanupOldHistory() {
        int retentionDays = configService.getConfiguration().getHistoryRetentionDays();
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int deleted = 0;

        try {
            if (!Files.exists(historyDir)) return;

            try (Stream<Path> files = Files.list(historyDir)) {
                List<Path> oldFiles = files
                        .filter(p -> p.toString().endsWith(".json"))
                        .filter(p -> {
                            try {
                                return Files.getLastModifiedTime(p).toInstant().isBefore(cutoff);
                            } catch (IOException e) {
                                return false;
                            }
                        })
                        .collect(Collectors.toList());

                for (Path file : oldFiles) {
                    try {
                        Files.deleteIfExists(file);
                        deleted++;
                    } catch (IOException e) {
                        log.debug("Failed to delete old history file: {}", file.getFileName());
                    }
                }
            }

            if (deleted > 0) {
                log.info("Cleaned up {} old scheduler history files (retention: {} days)",
                        deleted, retentionDays);
            }
        } catch (Exception e) {
            log.warn("Error during scheduler history cleanup: {}", e.getMessage());
        }

        // Evict fullyRecordedIds if it exceeds the bound — all entries are stale after history is persisted
        if (fullyRecordedIds.size() > MAX_FULLY_RECORDED_IDS) {
            int before = fullyRecordedIds.size();
            fullyRecordedIds.clear();
            log.info("Evicted fullyRecordedIds: {} entries cleared", before);
        }

        // Sweep activePhaseHistory for leaked entries (jobs that crashed without completing)
        if (!activePhaseHistory.isEmpty()) {
            int stale = activePhaseHistory.size();
            activePhaseHistory.clear();
            if (stale > 0) {
                log.info("Swept {} stale activePhaseHistory entries (leaked from crashed/killed jobs)", stale);
            }
        }
    }
}
