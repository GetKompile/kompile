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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service that periodically cleans up old leak report files to prevent disk space issues.
 *
 * Configuration properties:
 * - kompile.lifecycle.cleanup.enabled: Enable/disable cleanup (default: true)
 * - kompile.lifecycle.cleanup.directory: Directory to clean (default: ./leak_reports)
 * - kompile.lifecycle.cleanup.max-age-days: Max age of files to keep (default: 7)
 * - kompile.lifecycle.cleanup.max-files: Max number of files to keep (default: 100)
 * - kompile.lifecycle.cleanup.cron: Cron expression for cleanup schedule (default: daily at 2am)
 */
@Service
@ConditionalOnProperty(
    name = "kompile.lifecycle.cleanup.enabled",
    havingValue = "true",
    matchIfMissing = true  // Enabled by default
)
public class LeakReportCleanupService {

    private static final Logger logger = LoggerFactory.getLogger(LeakReportCleanupService.class);

    @Value("${kompile.lifecycle.cleanup.directory:./leak_reports}")
    private String cleanupDirectory;

    @Value("${kompile.lifecycle.cleanup.max-age-days:7}")
    private int maxAgeDays;

    @Value("${kompile.lifecycle.cleanup.max-files:100}")
    private int maxFiles;

    private volatile boolean cleanupEnabled = true;

    @PostConstruct
    public void init() {
        logger.info("=== Leak Report Cleanup Service Initialized ===");
        logger.info("Cleanup directory: {}", cleanupDirectory);
        logger.info("Max file age: {} days", maxAgeDays);
        logger.info("Max files to keep: {}", maxFiles);
        logger.info("Cleanup runs daily at 2:00 AM");
    }

    /**
     * Scheduled cleanup task that runs daily at 2:00 AM.
     * Can be customized via kompile.lifecycle.cleanup.cron property.
     */
    @Scheduled(cron = "${kompile.lifecycle.cleanup.cron:0 0 2 * * ?}")
    public void cleanupOldReports() {
        if (!cleanupEnabled) {
            logger.debug("Cleanup is disabled, skipping");
            return;
        }

        logger.info("Starting scheduled leak report cleanup");

        try {
            Path reportDir = Paths.get(cleanupDirectory);

            // Check if directory exists
            if (!Files.exists(reportDir)) {
                logger.debug("Leak report directory does not exist yet: {}", cleanupDirectory);
                return;
            }

            if (!Files.isDirectory(reportDir)) {
                logger.warn("Cleanup path is not a directory: {}", cleanupDirectory);
                return;
            }

            CleanupResult result = performCleanup(reportDir);

            if (result.deletedCount > 0) {
                logger.info("Cleanup completed: deleted {} files, freed {} bytes",
                           result.deletedCount, result.freedBytes);
            } else {
                logger.debug("Cleanup completed: no files needed to be deleted");
            }

        } catch (Exception e) {
            logger.error("Error during leak report cleanup", e);
        }
    }

    /**
     * Performs the actual cleanup operation.
     *
     * @param reportDir The directory to clean
     * @return CleanupResult with statistics
     */
    private CleanupResult performCleanup(Path reportDir) throws IOException {
        AtomicInteger deletedCount = new AtomicInteger(0);
        AtomicLong freedBytes = new AtomicLong(0);

        // Calculate cutoff time for old files
        Instant cutoffTime = Instant.now().minus(maxAgeDays, ChronoUnit.DAYS);

        // Collect all report files with their metadata
        List<FileInfo> files = new ArrayList<>();

        Files.walkFileTree(reportDir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                // Only process regular files, not directories
                if (attrs.isRegularFile()) {
                    // Consider files ending with .txt, .log, or specific patterns
                    String fileName = file.getFileName().toString();
                    if (isReportFile(fileName)) {
                        files.add(new FileInfo(
                            file,
                            attrs.lastModifiedTime().toInstant(),
                            attrs.size()
                        ));
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                logger.warn("Failed to visit file: {}", file, exc);
                return FileVisitResult.CONTINUE;
            }
        });

        logger.debug("Found {} report files in {}", files.size(), reportDir);

        // Delete files older than cutoff time
        for (FileInfo fileInfo : files) {
            if (fileInfo.lastModified.isBefore(cutoffTime)) {
                if (deleteFile(fileInfo)) {
                    deletedCount.incrementAndGet();
                    freedBytes.addAndGet(fileInfo.size);
                }
            }
        }

        // If we still have too many files, delete the oldest ones
        if (files.size() - deletedCount.get() > maxFiles) {
            // Sort by last modified time (oldest first)
            files.sort((a, b) -> a.lastModified.compareTo(b.lastModified));

            int toDelete = files.size() - deletedCount.get() - maxFiles;
            int deleted = 0;

            for (FileInfo fileInfo : files) {
                if (deleted >= toDelete) {
                    break;
                }

                // Skip files already deleted
                if (!Files.exists(fileInfo.path)) {
                    continue;
                }

                if (deleteFile(fileInfo)) {
                    deletedCount.incrementAndGet();
                    freedBytes.addAndGet(fileInfo.size);
                    deleted++;
                }
            }

            logger.debug("Deleted {} additional files to stay under max file limit of {}",
                        deleted, maxFiles);
        }

        return new CleanupResult(deletedCount.get(), freedBytes.get());
    }

    /**
     * Determines if a file is a leak report file based on naming patterns.
     */
    private boolean isReportFile(String fileName) {
        return fileName.endsWith(".txt") ||
               fileName.endsWith(".log") ||
               fileName.contains("leak_report") ||
               fileName.contains("comprehensive_leak") ||
               fileName.contains("snapshot");
    }

    /**
     * Safely deletes a file and logs the result.
     */
    private boolean deleteFile(FileInfo fileInfo) {
        try {
            Files.delete(fileInfo.path);
            logger.debug("Deleted old report file: {} (age: {} days, size: {} bytes)",
                        fileInfo.path.getFileName(),
                        ChronoUnit.DAYS.between(fileInfo.lastModified, Instant.now()),
                        fileInfo.size);
            return true;
        } catch (IOException e) {
            logger.warn("Failed to delete file: {}", fileInfo.path, e);
            return false;
        }
    }

    /**
     * Manually trigger cleanup (useful for testing or manual operations).
     */
    public CleanupResult triggerCleanup() {
        logger.info("Manual cleanup triggered");

        try {
            Path reportDir = Paths.get(cleanupDirectory);

            if (!Files.exists(reportDir)) {
                logger.info("Leak report directory does not exist: {}", cleanupDirectory);
                return new CleanupResult(0, 0);
            }

            return performCleanup(reportDir);

        } catch (Exception e) {
            logger.error("Error during manual cleanup", e);
            return new CleanupResult(0, 0);
        }
    }

    /**
     * Enable or disable the cleanup service at runtime.
     */
    public void setCleanupEnabled(boolean enabled) {
        this.cleanupEnabled = enabled;
        logger.info("Leak report cleanup service {}", enabled ? "ENABLED" : "DISABLED");
    }

    /**
     * Get current cleanup configuration.
     */
    public CleanupConfig getConfig() {
        return new CleanupConfig(
            cleanupEnabled,
            cleanupDirectory,
            maxAgeDays,
            maxFiles
        );
    }

    /**
     * Update cleanup configuration at runtime.
     */
    public void updateConfig(Integer maxAgeDays, Integer maxFiles) {
        if (maxAgeDays != null && maxAgeDays > 0) {
            this.maxAgeDays = maxAgeDays;
            logger.info("Updated max age to {} days", maxAgeDays);
        }

        if (maxFiles != null && maxFiles > 0) {
            this.maxFiles = maxFiles;
            logger.info("Updated max files to {}", maxFiles);
        }
    }

    // ========================================================================
    // Helper Classes
    // ========================================================================

    private static class FileInfo {
        final Path path;
        final Instant lastModified;
        final long size;

        FileInfo(Path path, Instant lastModified, long size) {
            this.path = path;
            this.lastModified = lastModified;
            this.size = size;
        }
    }

    public static class CleanupResult {
        public final int deletedCount;
        public final long freedBytes;

        public CleanupResult(int deletedCount, long freedBytes) {
            this.deletedCount = deletedCount;
            this.freedBytes = freedBytes;
        }
    }

    public static class CleanupConfig {
        public final boolean enabled;
        public final String directory;
        public final int maxAgeDays;
        public final int maxFiles;

        public CleanupConfig(boolean enabled, String directory, int maxAgeDays, int maxFiles) {
            this.enabled = enabled;
            this.directory = directory;
            this.maxAgeDays = maxAgeDays;
            this.maxFiles = maxFiles;
        }
    }
}
