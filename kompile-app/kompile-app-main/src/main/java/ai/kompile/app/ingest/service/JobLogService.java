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

package ai.kompile.app.ingest.service;

import ai.kompile.app.config.PrimaryDataSourceConfig;
import ai.kompile.app.ingest.domain.JobLogEntry;
import ai.kompile.app.ingest.domain.JobLogEntry.LogLevel;
import ai.kompile.app.ingest.domain.JobLogEntry.LogSource;
import ai.kompile.app.ingest.repository.JobLogRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Service for managing job log entries.
 *
 * Provides:
 * - Log entry persistence for indexing jobs
 * - Query methods for log retrieval and filtering
 * - Automatic cleanup based on retention policy
 * - Per-job and total entry limits
 * - Log statistics and monitoring
 */
@Service
public class JobLogService {

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected JobLogService() {}


    private static final Logger logger = LoggerFactory.getLogger(JobLogService.class);

    private JobLogRepository repository;

    // Track sequence numbers per task for log ordering
    private final Map<String, AtomicLong> sequenceCounters = new ConcurrentHashMap<>();

    @Value("${kompile.ingest.job-log.enabled:true}")
    private boolean enabled;

    @Value("${kompile.ingest.job-log.retention-days:7}")
    private int retentionDays;

    @Value("${kompile.ingest.job-log.max-entries-per-job:10000}")
    private int maxEntriesPerJob;

    @Value("${kompile.ingest.job-log.max-total-entries:500000}")
    private long maxTotalEntries;

    @Value("${kompile.ingest.job-log.archive-enabled:true}")
    private boolean archiveEnabled;

    @Value("${kompile.ingest.job-log.archive-path:${user.home}/.kompile/log-archives}")
    private String archivePath;

    @Value("${kompile.ingest.job-log.archive-on-cleanup:true}")
    private boolean archiveOnCleanup;

    private static final DateTimeFormatter ARCHIVE_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault());

    @Autowired
    public JobLogService(@Autowired(required = false) JobLogRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void init() {
        if (repository == null) {
            logger.warn("JobLogRepository not available - job logging disabled");
            enabled = false;
        } else {
            logger.info("JobLogService initialized: retention={}d, maxPerJob={}, maxTotal={}, enabled={}",
                    retentionDays, maxEntriesPerJob, maxTotalEntries, enabled);
        }
    }

    /**
     * Check if job logging is enabled.
     */
    public boolean isEnabled() {
        return enabled && repository != null;
    }

    /**
     * Get current configuration.
     */
    public Map<String, Object> getConfiguration() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("enabled", enabled);
        config.put("retentionDays", retentionDays);
        config.put("maxEntriesPerJob", maxEntriesPerJob);
        config.put("maxTotalEntries", maxTotalEntries);
        config.put("archiveEnabled", archiveEnabled);
        config.put("archivePath", archivePath);
        config.put("archiveOnCleanup", archiveOnCleanup);
        return config;
    }

    /**
     * Update configuration at runtime.
     */
    public void updateConfiguration(Boolean enabled, Integer retentionDays,
                                    Integer maxEntriesPerJob, Long maxTotalEntries) {
        updateConfiguration(enabled, retentionDays, maxEntriesPerJob, maxTotalEntries, null, null, null);
    }

    /**
     * Update configuration at runtime including archive settings.
     */
    public void updateConfiguration(Boolean enabled, Integer retentionDays,
                                    Integer maxEntriesPerJob, Long maxTotalEntries,
                                    Boolean archiveEnabled, String archivePath,
                                    Boolean archiveOnCleanup) {
        if (enabled != null) {
            this.enabled = enabled;
        }
        if (retentionDays != null && retentionDays > 0) {
            this.retentionDays = retentionDays;
        }
        if (maxEntriesPerJob != null && maxEntriesPerJob > 0) {
            this.maxEntriesPerJob = maxEntriesPerJob;
        }
        if (maxTotalEntries != null && maxTotalEntries > 0) {
            this.maxTotalEntries = maxTotalEntries;
        }
        if (archiveEnabled != null) {
            this.archiveEnabled = archiveEnabled;
        }
        if (archivePath != null && !archivePath.isBlank()) {
            this.archivePath = archivePath;
        }
        if (archiveOnCleanup != null) {
            this.archiveOnCleanup = archiveOnCleanup;
        }
        logger.info("JobLogService configuration updated: enabled={}, retention={}d, maxPerJob={}, maxTotal={}, archiveEnabled={}, archiveOnCleanup={}",
                this.enabled, this.retentionDays, this.maxEntriesPerJob, this.maxTotalEntries,
                this.archiveEnabled, this.archiveOnCleanup);
    }

    // ========== Log Entry Methods ==========

    /**
     * Log an entry from standard output.
     */
    @Transactional(transactionManager = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER)
    public void logStdout(String taskId, String message) {
        if (!isEnabled()) return;

        long seq = getNextSequence(taskId);
        JobLogEntry entry = JobLogEntry.stdout(taskId, message, seq);
        saveEntry(entry);
    }

    /**
     * Log an entry from standard error.
     */
    @Transactional(transactionManager = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER)
    public void logStderr(String taskId, String message) {
        if (!isEnabled()) return;

        long seq = getNextSequence(taskId);
        JobLogEntry entry = JobLogEntry.stderr(taskId, message, seq);
        saveEntry(entry);
    }

    /**
     * Log a system message.
     */
    @Transactional(transactionManager = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER)
    public void logSystem(String taskId, LogLevel level, String message) {
        if (!isEnabled()) return;

        long seq = getNextSequence(taskId);
        JobLogEntry entry = JobLogEntry.system(taskId, level, message, seq);
        saveEntry(entry);
    }

    /**
     * Log an application-level entry.
     */
    @Transactional(transactionManager = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER)
    public void logApplication(String taskId, LogLevel level, String message,
                               String loggerName, String threadName) {
        if (!isEnabled()) return;

        long seq = getNextSequence(taskId);
        JobLogEntry entry = JobLogEntry.application(taskId, level, message, loggerName, threadName, seq);
        saveEntry(entry);
    }

    /**
     * Log an error with exception details.
     */
    @Transactional(transactionManager = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER)
    public void logError(String taskId, String message, Throwable exception,
                         String loggerName, String threadName) {
        if (!isEnabled()) return;

        long seq = getNextSequence(taskId);
        JobLogEntry entry = JobLogEntry.error(taskId, message, exception, loggerName, threadName, seq);
        saveEntry(entry);
    }

    /**
     * Generic log entry method.
     */
    @Transactional(transactionManager = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER)
    public void logEntry(String taskId, LogLevel level, LogSource source, String message,
                         String loggerName, String threadName) {
        if (!isEnabled()) return;

        long seq = getNextSequence(taskId);
        JobLogEntry entry = JobLogEntry.builder()
                .taskId(taskId)
                .timestamp(Instant.now())
                .level(level)
                .source(source)
                .message(message)
                .loggerName(loggerName)
                .threadName(threadName)
                .sequenceNumber(seq)
                .build();
        saveEntry(entry);
    }

    /**
     * Batch log multiple entries for efficiency.
     */
    @Transactional(transactionManager = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER)
    public void logBatch(String taskId, List<JobLogEntry> entries) {
        if (!isEnabled() || entries == null || entries.isEmpty()) return;

        AtomicLong seqCounter = sequenceCounters.computeIfAbsent(taskId, k -> {
            Long maxSeq = repository.findMaxSequenceNumber(taskId);
            return new AtomicLong(maxSeq != null ? maxSeq : 0);
        });

        for (JobLogEntry entry : entries) {
            entry.setTaskId(taskId);
            entry.setSequenceNumber(seqCounter.incrementAndGet());
            if (entry.getTimestamp() == null) {
                entry.setTimestamp(Instant.now());
            }
        }

        repository.saveAll(entries);

        // Check and enforce per-job limit
        enforceMaxEntriesForTask(taskId);
    }

    private void saveEntry(JobLogEntry entry) {
        repository.save(entry);
        // Check and enforce per-job limit periodically (every 100 entries)
        if (entry.getSequenceNumber() % 100 == 0) {
            enforceMaxEntriesForTask(entry.getTaskId());
        }
    }

    private long getNextSequence(String taskId) {
        AtomicLong counter = sequenceCounters.computeIfAbsent(taskId, k -> {
            Long maxSeq = repository.findMaxSequenceNumber(taskId);
            return new AtomicLong(maxSeq != null ? maxSeq : 0);
        });
        return counter.incrementAndGet();
    }

    // ========== Query Methods ==========

    /**
     * Get all log entries for a task.
     */
    @Transactional(transactionManager = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public List<JobLogEntry> getLogsForTask(String taskId) {
        if (!isEnabled()) return List.of();
        return repository.findByTaskIdOrderBySequenceNumberAsc(taskId);
    }

    /**
     * Get log entries for a task with pagination.
     */
    @Transactional(transactionManager = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public Page<JobLogEntry> getLogsForTask(String taskId, int page, int size) {
        if (!isEnabled()) return Page.empty();
        return repository.findByTaskIdOrderBySequenceNumberAsc(taskId, PageRequest.of(page, size));
    }

    /**
     * Get log entries filtered by level.
     */
    @Transactional(transactionManager = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public List<JobLogEntry> getLogsForTask(String taskId, LogLevel level) {
        if (!isEnabled()) return List.of();
        return repository.findByTaskIdAndLevelOrderBySequenceNumberAsc(taskId, level);
    }

    /**
     * Get log entries filtered by multiple levels.
     */
    @Transactional(transactionManager = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public List<JobLogEntry> getLogsForTask(String taskId, List<LogLevel> levels) {
        if (!isEnabled()) return List.of();
        return repository.findByTaskIdAndLevelsOrderBySequenceNumberAsc(taskId, levels);
    }

    /**
     * Get log entries filtered by multiple levels with pagination.
     */
    @Transactional(transactionManager = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public Page<JobLogEntry> getLogsForTask(String taskId, List<LogLevel> levels, int page, int size) {
        if (!isEnabled()) return Page.empty();
        return repository.findByTaskIdAndLevelsOrderBySequenceNumberAsc(taskId, levels, PageRequest.of(page, size));
    }

    /**
     * Get log entries filtered by a single level with pagination.
     */
    @Transactional(transactionManager = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public Page<JobLogEntry> getLogsForTask(String taskId, LogLevel level, int page, int size) {
        if (!isEnabled()) return Page.empty();
        return repository.findByTaskIdAndLevelOrderBySequenceNumberAsc(taskId, level, PageRequest.of(page, size));
    }

    /**
     * Get log entries filtered by source.
     */
    @Transactional(transactionManager = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public List<JobLogEntry> getLogsForTaskBySource(String taskId, LogSource source) {
        if (!isEnabled()) return List.of();
        return repository.findByTaskIdAndSourceOrderBySequenceNumberAsc(taskId, source);
    }

    /**
     * Get all log entries by source type with a limit.
     * Used for retrieving all embedding logs across all models.
     */
    @Transactional(transactionManager = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public List<JobLogEntry> getLogsBySource(LogSource source, int limit) {
        if (!isEnabled()) return List.of();
        return repository.findBySourceOrderByTimestampDesc(source, PageRequest.of(0, limit));
    }

    /**
     * Get log entries for task IDs matching a prefix with a specific source.
     */
    @Transactional(transactionManager = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public List<JobLogEntry> getLogsForTaskPrefixBySource(String taskIdPrefix, LogSource source, int limit) {
        if (!isEnabled()) return List.of();
        return repository.findByTaskIdPrefixAndSource(taskIdPrefix, source, PageRequest.of(0, limit));
    }

    /**
     * Get distinct task IDs with a specific source.
     */
    @Transactional(transactionManager = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public List<String> getTaskIdsBySource(LogSource source) {
        if (!isEnabled()) return List.of();
        return repository.findDistinctTaskIdsBySource(source);
    }

    /**
     * Get the last N log entries for a task (tail).
     */
    @Transactional(transactionManager = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public List<JobLogEntry> tailLogs(String taskId, int lines) {
        if (!isEnabled()) return List.of();
        List<JobLogEntry> results = repository.findLastNByTaskId(taskId, PageRequest.of(0, lines));
        // Reverse to get chronological order
        Collections.reverse(results);
        return results;
    }

    /**
     * Search logs by message content.
     */
    @Transactional(transactionManager = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public List<JobLogEntry> searchLogs(String taskId, String searchText) {
        if (!isEnabled()) return List.of();
        return repository.searchByMessage(taskId, searchText);
    }

    /**
     * Search logs by message content with pagination.
     */
    @Transactional(transactionManager = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public Page<JobLogEntry> searchLogs(String taskId, String searchText, int page, int size) {
        if (!isEnabled()) return Page.empty();
        return repository.searchByMessage(taskId, searchText, PageRequest.of(page, size));
    }

    /**
     * Search logs by message content filtered by multiple levels with pagination.
     * This is the most common case: user has level filters AND enters a search term.
     */
    @Transactional(transactionManager = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public Page<JobLogEntry> searchLogsWithLevels(String taskId, String searchText, List<LogLevel> levels, int page, int size) {
        if (!isEnabled()) return Page.empty();
        return repository.searchByMessageWithLevels(taskId, searchText, levels, PageRequest.of(page, size));
    }

    /**
     * Search logs by message content filtered by a single level with pagination.
     */
    @Transactional(transactionManager = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public Page<JobLogEntry> searchLogsWithLevel(String taskId, String searchText, LogLevel level, int page, int size) {
        if (!isEnabled()) return Page.empty();
        return repository.searchByMessageWithLevel(taskId, searchText, level, PageRequest.of(page, size));
    }

    /**
     * Get log entries with errors (have stack traces).
     */
    @Transactional(transactionManager = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public List<JobLogEntry> getErrorsWithStackTrace(String taskId) {
        if (!isEnabled()) return List.of();
        return repository.findErrorsWithStackTrace(taskId);
    }

    /**
     * Get logs in a time range.
     */
    @Transactional(transactionManager = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public List<JobLogEntry> getLogsInTimeRange(String taskId, Instant start, Instant end) {
        if (!isEnabled()) return List.of();
        return repository.findByTaskIdAndTimestampBetweenOrderBySequenceNumberAsc(taskId, start, end);
    }

    /**
     * Get log count for a task.
     */
    @Transactional(transactionManager = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public long getLogCount(String taskId) {
        if (!isEnabled()) return 0;
        return repository.countByTaskId(taskId);
    }

    /**
     * Get log counts by level for a task.
     */
    @Transactional(transactionManager = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public Map<LogLevel, Long> getLogCountsByLevel(String taskId) {
        if (!isEnabled()) return Map.of();

        List<Object[]> results = repository.getLogCountsByLevel(taskId);
        Map<LogLevel, Long> counts = new EnumMap<>(LogLevel.class);
        for (Object[] row : results) {
            counts.put((LogLevel) row[0], (Long) row[1]);
        }
        return counts;
    }

    /**
     * Format all logs for a task as downloadable text.
     */
    @Transactional(transactionManager = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public String formatLogsForDownload(String taskId) {
        if (!isEnabled()) return "";

        List<JobLogEntry> logs = repository.findByTaskIdOrderBySequenceNumberAsc(taskId);
        StringBuilder sb = new StringBuilder();
        sb.append("=== Job Logs for Task: ").append(taskId).append(" ===\n");
        sb.append("Generated: ").append(Instant.now()).append("\n");
        sb.append("Total entries: ").append(logs.size()).append("\n");
        sb.append("=".repeat(60)).append("\n\n");

        for (JobLogEntry log : logs) {
            sb.append(log.format()).append("\n");
        }

        return sb.toString();
    }

    // ========== Statistics Methods ==========

    /**
     * Get log statistics.
     */
    @Transactional(transactionManager = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public Map<String, Object> getStatistics() {
        if (!isEnabled()) {
            return Map.of("enabled", false);
        }

        long totalLogs = repository.countTotalLogs();
        List<String> taskIds = repository.findDistinctTaskIds();
        List<Object[]> logsByTask = repository.getLogCountsByTaskId();

        Map<String, Long> topTasks = new LinkedHashMap<>();
        for (Object[] row : logsByTask.subList(0, Math.min(10, logsByTask.size()))) {
            topTasks.put((String) row[0], (Long) row[1]);
        }

        return Map.of(
                "enabled", true,
                "totalLogEntries", totalLogs,
                "totalTasksWithLogs", taskIds.size(),
                "topTasksByLogCount", topTasks,
                "retentionDays", retentionDays,
                "maxEntriesPerJob", maxEntriesPerJob,
                "maxTotalEntries", maxTotalEntries,
                "utilizationPercent", totalLogs > 0 ? (double) totalLogs / maxTotalEntries * 100 : 0
        );
    }

    /**
     * Get status info for monitoring.
     */
    @Transactional(transactionManager = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public Map<String, Object> getStatus() {
        if (!isEnabled()) {
            return Map.of(
                    "enabled", false,
                    "reason", repository == null ? "Repository not available" : "Disabled by configuration"
            );
        }

        long totalLogs = repository.countTotalLogs();
        return Map.of(
                "enabled", true,
                "totalLogEntries", totalLogs,
                "maxTotalEntries", maxTotalEntries,
                "utilizationPercent", totalLogs > 0 ? (double) totalLogs / maxTotalEntries * 100 : 0,
                "retentionDays", retentionDays,
                "activeSequenceTrackers", sequenceCounters.size()
        );
    }

    // ========== Cleanup Methods ==========

    /**
     * Delete all logs for a specific task.
     * Called when job history is deleted (cascade delete).
     */
    @Transactional(transactionManager = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER)
    public void deleteLogsForTask(String taskId) {
        if (!isEnabled()) return;

        repository.deleteByTaskId(taskId);
        sequenceCounters.remove(taskId);
        logger.info("Deleted all logs for task: {}", taskId);
    }

    /**
     * Enforce max entries per job limit.
     */
    @Transactional(transactionManager = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER)
    public void enforceMaxEntriesForTask(String taskId) {
        if (!isEnabled()) return;

        long count = repository.countByTaskId(taskId);
        if (count <= maxEntriesPerJob) {
            return;
        }

        // Find the sequence number to keep (keep the newest maxEntriesPerJob entries)
        List<Long> seqNumbers = repository.findSequenceNumbersDescending(
                taskId, PageRequest.of(0, maxEntriesPerJob));

        if (!seqNumbers.isEmpty()) {
            long minSequenceToKeep = seqNumbers.get(seqNumbers.size() - 1);
            int deleted = repository.deleteOldestForTask(taskId, minSequenceToKeep);
            if (deleted > 0) {
                logger.debug("Trimmed {} old log entries for task {} (exceeded max {})",
                        deleted, taskId, maxEntriesPerJob);
            }
        }
    }

    /**
     * Scheduled cleanup of old logs based on retention policy.
     * Runs daily at 3:30 AM.
     */
    @Scheduled(cron = "${kompile.ingest.job-log.cleanup-cron:0 30 3 * * ?}")
    @Transactional(transactionManager = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER)
    public void cleanupOldLogs() {
        if (!isEnabled()) return;

        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));

        try {
            // Archive before cleanup if enabled
            if (archiveEnabled && archiveOnCleanup) {
                try {
                    int archived = archiveOldLogs(cutoff);
                    if (archived > 0) {
                        logger.info("Archived {} log entries before cleanup", archived);
                    }
                } catch (IOException e) {
                    logger.error("Failed to archive logs before cleanup: {}", e.getMessage(), e);
                    // Continue with cleanup even if archive fails
                }
            }

            long countBefore = repository.countTotalLogs();
            int deleted = repository.deleteLogsOlderThan(cutoff);
            long countAfter = repository.countTotalLogs();

            if (deleted > 0) {
                logger.info("Job log cleanup: deleted {} entries older than {} days (before: {}, after: {})",
                        deleted, retentionDays, countBefore, countAfter);
            }

            // Also enforce total entry limit
            enforceTotalEntriesLimit();

            // Clean up sequence counters for tasks that no longer have logs
            cleanupSequenceCounters();

        } catch (Exception e) {
            logger.error("Failed to cleanup job logs: {}", e.getMessage(), e);
        }
    }

    /**
     * Enforce total entries limit by removing oldest task logs.
     */
    @Transactional(transactionManager = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER)
    public void enforceTotalEntriesLimit() {
        if (!isEnabled()) return;

        long totalCount = repository.countTotalLogs();
        if (totalCount <= maxTotalEntries) {
            return;
        }

        long toDelete = totalCount - maxTotalEntries;
        logger.warn("Total log entries ({}) exceeds limit ({}). Removing {} entries from oldest tasks.",
                totalCount, maxTotalEntries, toDelete);

        // Get oldest tasks and delete their logs until we're under the limit
        List<String> oldestTasks = repository.findOldestTaskIds(PageRequest.of(0, 100));
        long deleted = 0;

        for (String taskId : oldestTasks) {
            if (deleted >= toDelete) break;

            long taskLogCount = repository.countByTaskId(taskId);
            repository.deleteByTaskId(taskId);
            sequenceCounters.remove(taskId);
            deleted += taskLogCount;

            logger.debug("Deleted {} logs for task {} during total limit enforcement", taskLogCount, taskId);
        }

        logger.info("Total limit enforcement complete: deleted {} entries", deleted);
    }

    /**
     * Manually trigger cleanup.
     *
     * @param hoursToKeep Number of hours of logs to retain
     * @return Number of entries deleted
     */
    @Transactional(transactionManager = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER)
    public int forceCleanup(int hoursToKeep) {
        if (!isEnabled()) return 0;

        Instant cutoff = Instant.now().minus(Duration.ofHours(hoursToKeep));
        int deleted = repository.deleteLogsOlderThan(cutoff);
        logger.info("Manual job log cleanup: deleted {} entries older than {} hours", deleted, hoursToKeep);

        cleanupSequenceCounters();
        return deleted;
    }

    /**
     * Clean up sequence counters for tasks that no longer have logs.
     */
    private void cleanupSequenceCounters() {
        Set<String> tasksWithLogs = new HashSet<>(repository.findDistinctTaskIds());
        Set<String> trackedTasks = new HashSet<>(sequenceCounters.keySet());

        for (String taskId : trackedTasks) {
            if (!tasksWithLogs.contains(taskId)) {
                sequenceCounters.remove(taskId);
            }
        }
    }

    // ========== Archive Methods ==========

    /**
     * Archive logs for a specific task to a compressed file.
     *
     * @param taskId The task ID to archive
     * @return Path to the created archive file, or null if no logs found
     */
    @Transactional(transactionManager = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public Path archiveLogsForTask(String taskId) throws IOException {
        if (!isEnabled()) return null;

        List<JobLogEntry> logs = repository.findByTaskIdOrderBySequenceNumberAsc(taskId);
        if (logs.isEmpty()) {
            return null;
        }

        Path archiveDir = ensureArchiveDirectory();
        String timestamp = ARCHIVE_DATE_FORMAT.format(Instant.now());
        String fileName = String.format("task_%s_%s.log.gz", sanitizeFileName(taskId), timestamp);
        Path archiveFile = archiveDir.resolve(fileName);

        try (GZIPOutputStream gzos = new GZIPOutputStream(Files.newOutputStream(archiveFile))) {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Job Logs Archive for Task: ").append(taskId).append(" ===\n");
            sb.append("Archived: ").append(Instant.now()).append("\n");
            sb.append("Total entries: ").append(logs.size()).append("\n");
            sb.append("=".repeat(60)).append("\n\n");

            for (JobLogEntry log : logs) {
                sb.append(log.format()).append("\n");
            }

            gzos.write(sb.toString().getBytes());
        }

        logger.info("Archived {} log entries for task {} to {}", logs.size(), taskId, archiveFile);
        return archiveFile;
    }

    /**
     * Archive all logs older than the specified cutoff.
     *
     * @param cutoff Archive logs older than this timestamp
     * @return Number of entries archived
     */
    @Transactional(transactionManager = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public int archiveOldLogs(Instant cutoff) throws IOException {
        if (!isEnabled()) return 0;

        // Get distinct task IDs with old logs
        List<String> taskIds = repository.findDistinctTaskIds();
        int totalArchived = 0;

        Path archiveDir = ensureArchiveDirectory();
        String timestamp = ARCHIVE_DATE_FORMAT.format(Instant.now());
        String fileName = String.format("logs_before_%s_archived_%s.log.gz",
                ARCHIVE_DATE_FORMAT.format(cutoff), timestamp);
        Path archiveFile = archiveDir.resolve(fileName);

        try (GZIPOutputStream gzos = new GZIPOutputStream(Files.newOutputStream(archiveFile))) {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Job Logs Archive ===\n");
            sb.append("Archived: ").append(Instant.now()).append("\n");
            sb.append("Cutoff: ").append(cutoff).append("\n");
            sb.append("=".repeat(60)).append("\n\n");

            for (String taskId : taskIds) {
                List<JobLogEntry> oldLogs = repository.findByTaskIdAndTimestampBetweenOrderBySequenceNumberAsc(
                        taskId, Instant.EPOCH, cutoff);
                if (!oldLogs.isEmpty()) {
                    sb.append("\n--- Task: ").append(taskId).append(" (").append(oldLogs.size()).append(" entries) ---\n");
                    for (JobLogEntry log : oldLogs) {
                        sb.append(log.format()).append("\n");
                    }
                    totalArchived += oldLogs.size();
                }
            }

            gzos.write(sb.toString().getBytes());
        }

        if (totalArchived == 0) {
            // Delete empty archive
            Files.deleteIfExists(archiveFile);
            return 0;
        }

        logger.info("Archived {} total log entries to {}", totalArchived, archiveFile);
        return totalArchived;
    }

    /**
     * Create a full archive of all current logs.
     *
     * @return Path to the created archive file
     */
    @Transactional(transactionManager = PrimaryDataSourceConfig.INGEST_EVENT_TRANSACTION_MANAGER, readOnly = true)
    public Path createFullArchive() throws IOException {
        if (!isEnabled()) return null;

        Path archiveDir = ensureArchiveDirectory();
        String timestamp = ARCHIVE_DATE_FORMAT.format(Instant.now());
        String fileName = String.format("full_archive_%s.log.gz", timestamp);
        Path archiveFile = archiveDir.resolve(fileName);

        List<String> taskIds = repository.findDistinctTaskIds();
        long totalEntries = 0;

        try (GZIPOutputStream gzos = new GZIPOutputStream(Files.newOutputStream(archiveFile))) {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Full Job Logs Archive ===\n");
            sb.append("Archived: ").append(Instant.now()).append("\n");
            sb.append("Tasks: ").append(taskIds.size()).append("\n");
            sb.append("=".repeat(60)).append("\n\n");

            for (String taskId : taskIds) {
                List<JobLogEntry> logs = repository.findByTaskIdOrderBySequenceNumberAsc(taskId);
                if (!logs.isEmpty()) {
                    sb.append("\n--- Task: ").append(taskId).append(" (").append(logs.size()).append(" entries) ---\n");
                    for (JobLogEntry log : logs) {
                        sb.append(log.format()).append("\n");
                    }
                    totalEntries += logs.size();
                }
            }

            gzos.write(sb.toString().getBytes());
        }

        logger.info("Created full archive with {} entries from {} tasks: {}",
                totalEntries, taskIds.size(), archiveFile);
        return archiveFile;
    }

    /**
     * List available archive files.
     */
    public List<Map<String, Object>> listArchives() throws IOException {
        Path archiveDir = Paths.get(archivePath);
        if (!Files.exists(archiveDir)) {
            return List.of();
        }

        List<Map<String, Object>> archives = new ArrayList<>();
        try (Stream<Path> files = Files.list(archiveDir)) {
            files.filter(p -> p.toString().endsWith(".log.gz"))
                    .sorted((a, b) -> {
                        try {
                            return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .forEach(path -> {
                        try {
                            Map<String, Object> info = new LinkedHashMap<>();
                            info.put("name", path.getFileName().toString());
                            info.put("path", path.toString());
                            info.put("size", Files.size(path));
                            info.put("created", Files.getLastModifiedTime(path).toInstant().toString());
                            archives.add(info);
                        } catch (IOException e) {
                            logger.warn("Failed to get info for archive: {}", path, e);
                        }
                    });
        }
        return archives;
    }

    /**
     * Delete an archive file.
     */
    public boolean deleteArchive(String fileName) throws IOException {
        Path archiveDir = Paths.get(archivePath);
        Path archiveFile = archiveDir.resolve(sanitizeFileName(fileName));

        if (!archiveFile.startsWith(archiveDir)) {
            throw new SecurityException("Invalid archive path");
        }

        if (Files.exists(archiveFile)) {
            Files.delete(archiveFile);
            logger.info("Deleted archive: {}", archiveFile);
            return true;
        }
        return false;
    }

    /**
     * Get the path to an archive file for download.
     */
    public Path getArchiveFile(String fileName) {
        Path archiveDir = Paths.get(archivePath);
        Path archiveFile = archiveDir.resolve(sanitizeFileName(fileName));

        if (!archiveFile.startsWith(archiveDir)) {
            throw new SecurityException("Invalid archive path");
        }

        return Files.exists(archiveFile) ? archiveFile : null;
    }

    private Path ensureArchiveDirectory() throws IOException {
        Path archiveDir = Paths.get(archivePath);
        if (!Files.exists(archiveDir)) {
            Files.createDirectories(archiveDir);
            logger.info("Created archive directory: {}", archiveDir);
        }
        return archiveDir;
    }

    private String sanitizeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    public boolean isArchiveEnabled() {
        return archiveEnabled;
    }

    public String getArchivePath() {
        return archivePath;
    }

    public boolean isArchiveOnCleanup() {
        return archiveOnCleanup;
    }

    /**
     * Read logs from archives for a specific task.
     * Searches all archive files and returns logs that belong to the specified task.
     *
     * @param taskId The task ID to find logs for
     * @return List of archived log entries for the task, or empty if none found
     */
    public List<ArchivedLogEntry> readLogsFromArchive(String taskId) throws IOException {
        Path archiveDir = Paths.get(archivePath);
        if (!Files.exists(archiveDir)) {
            return List.of();
        }

        List<ArchivedLogEntry> allLogs = new ArrayList<>();
        String sanitizedTaskId = taskId;

        // First check for task-specific archive files
        try (Stream<Path> files = Files.list(archiveDir)) {
            List<Path> archiveFiles = files
                    .filter(p -> p.toString().endsWith(".log.gz"))
                    .sorted((a, b) -> {
                        try {
                            // Sort by modification time descending (newest first)
                            return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .collect(Collectors.toList());

            for (Path archiveFile : archiveFiles) {
                String fileName = archiveFile.getFileName().toString();

                // Check if this is a task-specific archive
                boolean isTaskSpecific = fileName.startsWith("task_" + sanitizeFileName(sanitizedTaskId) + "_");

                try {
                    List<ArchivedLogEntry> logsFromFile = readLogsFromArchiveFile(archiveFile, taskId, isTaskSpecific);
                    allLogs.addAll(logsFromFile);

                    // If we found a task-specific archive, that's likely the most complete one
                    if (isTaskSpecific && !logsFromFile.isEmpty()) {
                        logger.debug("Found {} logs for task {} in task-specific archive: {}",
                                logsFromFile.size(), taskId, fileName);
                        break;
                    }
                } catch (IOException e) {
                    logger.warn("Failed to read archive file {}: {}", archiveFile, e.getMessage());
                }
            }
        }

        logger.debug("Found {} total archived logs for task {}", allLogs.size(), taskId);
        return allLogs;
    }

    /**
     * Read logs from a specific archive file, optionally filtering by task ID.
     */
    private List<ArchivedLogEntry> readLogsFromArchiveFile(Path archiveFile, String taskId, boolean isTaskSpecific) throws IOException {
        List<ArchivedLogEntry> logs = new ArrayList<>();

        try (GZIPInputStream gzis = new GZIPInputStream(Files.newInputStream(archiveFile));
             BufferedReader reader = new BufferedReader(new InputStreamReader(gzis))) {

            String line;
            String currentTaskSection = null;
            long sequenceNumber = 0;

            while ((line = reader.readLine()) != null) {
                // Detect task section headers like "--- Task: task-123 (100 entries) ---"
                if (line.startsWith("--- Task: ")) {
                    int endIndex = line.indexOf(" (");
                    if (endIndex > 10) {
                        currentTaskSection = line.substring(10, endIndex);
                    }
                    continue;
                }

                // Skip header lines
                if (line.startsWith("===") || line.startsWith("Archived:") ||
                        line.startsWith("Cutoff:") || line.startsWith("Total entries:") ||
                        line.startsWith("Tasks:") || line.startsWith("Generated:") ||
                        line.trim().isEmpty() || line.startsWith("=")) {
                    continue;
                }

                // For task-specific archives, include all log lines
                // For full/partial archives, only include lines when in the right task section
                boolean includeLog = isTaskSpecific ||
                        (currentTaskSection != null && currentTaskSection.equals(taskId));

                if (includeLog) {
                    ArchivedLogEntry entry = parseLogLine(line, taskId, ++sequenceNumber);
                    if (entry != null) {
                        logs.add(entry);
                    }
                }
            }
        }

        return logs;
    }

    /**
     * Parse a log line from an archive file.
     * Expected format: "2024-01-15T10:30:00.123Z [INFO] [STDOUT] Message here"
     */
    private ArchivedLogEntry parseLogLine(String line, String taskId, long sequenceNumber) {
        try {
            // Try to parse the standard format: timestamp [LEVEL] [SOURCE] message
            int firstBracket = line.indexOf('[');
            if (firstBracket < 0) {
                // Plain message without metadata
                return new ArchivedLogEntry(taskId, Instant.now().toString(), "INFO", "APPLICATION", line, sequenceNumber);
            }

            String timestamp = line.substring(0, firstBracket).trim();
            String rest = line.substring(firstBracket);

            // Extract level
            int levelEnd = rest.indexOf(']');
            String level = levelEnd > 1 ? rest.substring(1, levelEnd) : "INFO";

            // Extract source
            String afterLevel = rest.substring(levelEnd + 1).trim();
            String source = "APPLICATION";
            String message = afterLevel;

            if (afterLevel.startsWith("[")) {
                int sourceEnd = afterLevel.indexOf(']');
                if (sourceEnd > 1) {
                    source = afterLevel.substring(1, sourceEnd);
                    message = afterLevel.substring(sourceEnd + 1).trim();
                }
            }

            return new ArchivedLogEntry(taskId, timestamp, level, source, message, sequenceNumber);
        } catch (Exception e) {
            // If parsing fails, treat the whole line as a message
            return new ArchivedLogEntry(taskId, Instant.now().toString(), "INFO", "APPLICATION", line, sequenceNumber);
        }
    }

    /**
     * Check if there are any archives that may contain logs for a task.
     */
    public boolean hasArchivedLogsForTask(String taskId) {
        Path archiveDir = Paths.get(archivePath);
        if (!Files.exists(archiveDir)) {
            return false;
        }

        try (Stream<Path> files = Files.list(archiveDir)) {
            String sanitized = sanitizeFileName(taskId);
            return files.anyMatch(p -> {
                String name = p.getFileName().toString();
                // Check for task-specific archive or full/partial archives that might contain the task
                return name.endsWith(".log.gz") &&
                        (name.startsWith("task_" + sanitized + "_") ||
                                name.startsWith("full_archive_") ||
                                name.startsWith("logs_before_"));
            });
        } catch (IOException e) {
            logger.warn("Failed to check for archived logs: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Archived log entry DTO.
     */
    public static class ArchivedLogEntry {
        private String taskId;
        private String timestamp;
        private String level;
        private String source;
        private String message;
        private long sequenceNumber;

        public ArchivedLogEntry(String taskId, String timestamp, String level, String source, String message, long sequenceNumber) {
            this.taskId = taskId;
            this.timestamp = timestamp;
            this.level = level;
            this.source = source;
            this.message = message;
            this.sequenceNumber = sequenceNumber;
        }

        public String getTaskId() { return taskId; }
        public String getTimestamp() { return timestamp; }
        public String getLevel() { return level; }
        public String getSource() { return source; }
        public String getMessage() { return message; }
        public long getSequenceNumber() { return sequenceNumber; }
    }

    // ========== Getters for Configuration ==========

    public int getRetentionDays() {
        return retentionDays;
    }

    public int getMaxEntriesPerJob() {
        return maxEntriesPerJob;
    }

    public long getMaxTotalEntries() {
        return maxTotalEntries;
    }
}
