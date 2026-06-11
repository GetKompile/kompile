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

import ai.kompile.app.config.BackupProperties;
import ai.kompile.app.config.BackupProperties.BackupFormat;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;

/**
 * Service for periodic backup of H2 databases and Lucene indexes.
 * <p>
 * Supports both compressed (tar.gz) and directory copy formats.
 * Automatically cleans up backups older than the configured retention period.
 * </p>
 *
 * <h3>Features:</h3>
 * <ul>
 *   <li>Scheduled backups at configurable intervals (default: every 6 hours)</li>
 *   <li>Manual backup trigger via {@link #triggerBackup()}</li>
 *   <li>Automatic cleanup of old backups</li>
 *   <li>Concurrent backup protection</li>
 *   <li>Handles Lucene write.lock files gracefully</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "kompile.backup.enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class BackupService {

    private static final DateTimeFormatter BACKUP_TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final BackupProperties properties;

    // Track if backup is in progress to prevent concurrent runs
    private final AtomicBoolean backupInProgress = new AtomicBoolean(false);

    // Track last backup time and result
    private volatile Instant lastBackupTime;
    private volatile BackupResult lastBackupResult;

    @PostConstruct
    public void init() {
        log.info("=== Backup Service Initialized ===");
        log.info("Backup path: {}", properties.getBackupPath());
        log.info("Retention: {} days", properties.getRetentionDays());
        log.info("Format: {}", properties.getFormat());
        log.info("Schedule: every {} hours", properties.getFixedRateMs() / 3600000.0);
        log.info("Include databases: {}", properties.isIncludeDatabase());
        log.info("Include indexes: {}", properties.isIncludeIndexes());

        // Ensure backup directory exists
        try {
            Files.createDirectories(Paths.get(properties.getBackupPath()));
        } catch (IOException e) {
            log.error("Failed to create backup directory: {}", properties.getBackupPath(), e);
        }
    }

    /**
     * Scheduled backup task running at configured interval.
     */
    @Scheduled(fixedRateString = "${kompile.backup.fixedRateMs:21600000}", initialDelayString = "${kompile.backup.initialDelayMs:60000}")
    public void scheduledBackup() {
        log.info("Starting scheduled backup...");
        performBackup();
    }

    /**
     * Manually trigger a backup.
     *
     * @return BackupResult with details of the operation
     */
    public BackupResult triggerBackup() {
        log.info("Manual backup triggered");
        return performBackup();
    }

    /**
     * Performs the backup operation.
     */
    private synchronized BackupResult performBackup() {
        if (!backupInProgress.compareAndSet(false, true)) {
            log.warn("Backup already in progress, skipping");
            return new BackupResult(false, "Backup already in progress", null, 0, 0, 0, List.of());
        }

        Instant startTime = Instant.now();
        String timestamp = LocalDateTime.now().format(BACKUP_TIMESTAMP_FORMAT);
        String backupName = "backup-" + timestamp;
        Path backupDir = Paths.get(properties.getBackupPath(), backupName);

        List<String> errors = new ArrayList<>();
        int filesBackedUp = 0;
        long totalSize = 0;

        try {
            Files.createDirectories(backupDir);
            log.info("Created backup directory: {}", backupDir);

            // Backup H2 databases
            if (properties.isIncludeDatabase()) {
                // Main orchestrator DB
                BackupStats stats = backupH2Database(
                    properties.getOrchestratorDbPath(),
                    backupDir.resolve("orchestrator-db")
                );
                filesBackedUp += stats.fileCount();
                totalSize += stats.totalBytes();
                if (stats.error() != null) errors.add("Orchestrator DB: " + stats.error());

                // Chat history DB
                stats = backupH2Database(
                    properties.getChatHistoryDbPath(),
                    backupDir.resolve("chat-history-db")
                );
                filesBackedUp += stats.fileCount();
                totalSize += stats.totalBytes();
                if (stats.error() != null) errors.add("Chat History DB: " + stats.error());
            }

            // Backup Lucene indexes
            if (properties.isIncludeIndexes()) {
                // Vector index
                BackupStats stats = backupLuceneIndex(
                    properties.getVectorIndexPath(),
                    backupDir.resolve("vector-index")
                );
                filesBackedUp += stats.fileCount();
                totalSize += stats.totalBytes();
                if (stats.error() != null) errors.add("Vector Index: " + stats.error());

                // Text index
                stats = backupLuceneIndex(
                    properties.getTextIndexPath(),
                    backupDir.resolve("text-index")
                );
                filesBackedUp += stats.fileCount();
                totalSize += stats.totalBytes();
                if (stats.error() != null) errors.add("Text Index: " + stats.error());
            }

            // Compress if configured
            Path finalBackupPath = backupDir;
            if (properties.getFormat() == BackupFormat.COMPRESSED && filesBackedUp > 0) {
                Path tarGzPath = Paths.get(properties.getBackupPath(), backupName + ".tar.gz");
                compressDirectory(backupDir, tarGzPath);
                // Delete uncompressed directory after compression
                deleteDirectory(backupDir);
                finalBackupPath = tarGzPath;
                totalSize = Files.size(tarGzPath);
                log.info("Compressed backup to: {} ({} MB)", tarGzPath, totalSize / (1024 * 1024));
            } else if (filesBackedUp == 0) {
                // No files backed up, remove empty directory
                deleteDirectory(backupDir);
                log.warn("No files were backed up");
            }

            // Cleanup old backups
            int deletedCount = cleanupOldBackups();
            if (deletedCount > 0) {
                log.info("Cleaned up {} old backups", deletedCount);
            }

            Duration duration = Duration.between(startTime, Instant.now());
            lastBackupTime = Instant.now();

            BackupResult result = new BackupResult(
                errors.isEmpty(),
                errors.isEmpty() ? "Backup completed successfully" :
                    "Backup completed with errors: " + String.join(", ", errors),
                filesBackedUp > 0 ? finalBackupPath.toString() : null,
                filesBackedUp,
                totalSize,
                duration.toMillis(),
                errors
            );
            lastBackupResult = result;

            log.info("Backup completed: {} files, {} MB, {} ms",
                filesBackedUp, totalSize / (1024 * 1024), duration.toMillis());
            return result;

        } catch (Exception e) {
            log.error("Backup failed", e);
            BackupResult result = new BackupResult(false, "Backup failed: " + e.getMessage(),
                null, 0, 0, 0, List.of(e.getMessage()));
            lastBackupResult = result;
            return result;
        } finally {
            backupInProgress.set(false);
        }
    }

    /**
     * Backup H2 database files.
     * Uses file copy approach which is safe when database is open with proper settings.
     */
    private BackupStats backupH2Database(String dbPath, Path targetDir) {
        try {
            Path dbBasePath = Paths.get(dbPath);
            Path parentDir = dbBasePath.getParent();
            String baseName = dbBasePath.getFileName().toString();

            if (parentDir == null || !Files.exists(parentDir)) {
                log.debug("Database directory not found, skipping: {}", dbPath);
                return new BackupStats(0, 0, null);
            }

            Files.createDirectories(targetDir);

            int fileCount = 0;
            long totalBytes = 0;

            // Find all related database files (.mv.db, .trace.db, etc.)
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(parentDir, baseName + ".*")) {
                for (Path file : stream) {
                    if (Files.isRegularFile(file)) {
                        Path target = targetDir.resolve(file.getFileName());
                        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                        fileCount++;
                        totalBytes += Files.size(file);
                        log.debug("Backed up database file: {}", file.getFileName());
                    }
                }
            }

            if (fileCount > 0) {
                log.info("Backed up H2 database: {} ({} files, {} KB)",
                    baseName, fileCount, totalBytes / 1024);
            }

            return new BackupStats(fileCount, totalBytes, null);

        } catch (Exception e) {
            log.error("Error backing up H2 database: {}", dbPath, e);
            return new BackupStats(0, 0, e.getMessage());
        }
    }

    /**
     * Backup Lucene index directory.
     * Handles write.lock files by skipping them (they are recreated on startup).
     */
    private BackupStats backupLuceneIndex(String indexPath, Path targetDir) {
        try {
            Path sourcePath = Paths.get(indexPath);

            if (!Files.exists(sourcePath)) {
                log.debug("Index directory not found, skipping: {}", indexPath);
                return new BackupStats(0, 0, null);
            }

            Files.createDirectories(targetDir);

            int[] fileCount = {0};
            long[] totalBytes = {0};

            Files.walkFileTree(sourcePath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String fileName = file.getFileName().toString();

                    // Skip write.lock files - they are transient and recreated on startup
                    if ("write.lock".equals(fileName)) {
                        log.debug("Skipping lock file: {}", file);
                        return FileVisitResult.CONTINUE;
                    }

                    Path relativePath = sourcePath.relativize(file);
                    Path targetFile = targetDir.resolve(relativePath);
                    Files.createDirectories(targetFile.getParent());
                    Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
                    fileCount[0]++;
                    totalBytes[0] += attrs.size();

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.warn("Failed to backup file: {}", file, exc);
                    return FileVisitResult.CONTINUE;
                }
            });

            if (fileCount[0] > 0) {
                log.info("Backed up Lucene index: {} ({} files, {} KB)",
                    sourcePath.getFileName(), fileCount[0], totalBytes[0] / 1024);
            }

            return new BackupStats(fileCount[0], totalBytes[0], null);

        } catch (Exception e) {
            log.error("Error backing up Lucene index: {}", indexPath, e);
            return new BackupStats(0, 0, e.getMessage());
        }
    }

    /**
     * Compress a directory to tar.gz format.
     */
    private void compressDirectory(Path sourceDir, Path targetFile) throws IOException {
        try (OutputStream fos = Files.newOutputStream(targetFile);
             GZIPOutputStream gzos = new GZIPOutputStream(fos);
             TarArchiveOutputStream taos = new TarArchiveOutputStream(gzos)) {

            taos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            taos.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);

            Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path relativePath = sourceDir.relativize(file);
                    TarArchiveEntry entry = new TarArchiveEntry(file.toFile(), relativePath.toString());
                    taos.putArchiveEntry(entry);
                    Files.copy(file, taos);
                    taos.closeArchiveEntry();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(sourceDir)) {
                        Path relativePath = sourceDir.relativize(dir);
                        TarArchiveEntry entry = new TarArchiveEntry(dir.toFile(), relativePath.toString() + "/");
                        taos.putArchiveEntry(entry);
                        taos.closeArchiveEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    /**
     * Delete a directory recursively.
     */
    private void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;

        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Cleanup backups older than retention period.
     *
     * @return Number of backups deleted
     */
    public int cleanupOldBackups() {
        Path backupPath = Paths.get(properties.getBackupPath());
        if (!Files.exists(backupPath)) {
            return 0;
        }

        Instant cutoff = Instant.now().minus(properties.getRetentionDays(), ChronoUnit.DAYS);
        int deletedCount = 0;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(backupPath, "backup-*")) {
            for (Path backup : stream) {
                BasicFileAttributes attrs = Files.readAttributes(backup, BasicFileAttributes.class);
                if (attrs.creationTime().toInstant().isBefore(cutoff)) {
                    try {
                        if (Files.isDirectory(backup)) {
                            deleteDirectory(backup);
                        } else {
                            Files.deleteIfExists(backup);
                        }
                        deletedCount++;
                        log.debug("Deleted old backup: {}", backup.getFileName());
                    } catch (IOException e) {
                        log.warn("Failed to delete old backup: {}", backup, e);
                    }
                }
            }
        } catch (IOException e) {
            log.error("Error during backup cleanup", e);
        }

        return deletedCount;
    }

    // ========== Status Methods ==========

    /**
     * Get the current status of the backup service.
     */
    public BackupStatus getStatus() {
        return new BackupStatus(
            properties.isEnabled(),
            backupInProgress.get(),
            lastBackupTime,
            lastBackupResult,
            properties.getBackupPath(),
            properties.getRetentionDays(),
            properties.getFormat().name(),
            properties.getFixedRateMs()
        );
    }

    /**
     * List all available backups.
     */
    public List<BackupInfo> listBackups() {
        List<BackupInfo> backups = new ArrayList<>();
        Path backupPath = Paths.get(properties.getBackupPath());

        if (!Files.exists(backupPath)) {
            return backups;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(backupPath, "backup-*")) {
            for (Path backup : stream) {
                BasicFileAttributes attrs = Files.readAttributes(backup, BasicFileAttributes.class);
                long size = Files.isDirectory(backup) ?
                    calculateDirectorySize(backup) : Files.size(backup);

                backups.add(new BackupInfo(
                    backup.getFileName().toString(),
                    backup.toString(),
                    attrs.creationTime().toInstant(),
                    size,
                    Files.isDirectory(backup) ? "DIRECTORY" : "COMPRESSED"
                ));
            }
        } catch (IOException e) {
            log.error("Error listing backups", e);
        }

        // Sort by creation time, newest first
        backups.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
        return backups;
    }

    private long calculateDirectorySize(Path dir) throws IOException {
        long[] size = {0};
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                size[0] += attrs.size();
                return FileVisitResult.CONTINUE;
            }
        });
        return size[0];
    }

    // ========== Restore Methods ==========

    /**
     * Restore from a backup by name.
     *
     * @param backupName The name of the backup (e.g., "backup-20251220-143000" or "backup-20251220-143000.tar.gz")
     * @return RestoreResult with details of the operation
     */
    public RestoreResult restoreBackup(String backupName) {
        if (backupInProgress.get()) {
            return new RestoreResult(false, "Cannot restore while backup is in progress", 0, List.of());
        }

        Instant startTime = Instant.now();
        List<String> errors = new ArrayList<>();

        try {
            Path backupFile = getBackupFile(backupName);
            if (backupFile == null) {
                return new RestoreResult(false, "Backup not found: " + backupName, 0, List.of("Backup file does not exist"));
            }

            log.info("Starting restore from backup: {}", backupFile);

            Path tempExtractDir = null;
            Path sourceDir;

            // If compressed, extract to temp directory first
            if (backupFile.toString().endsWith(".tar.gz")) {
                tempExtractDir = Files.createTempDirectory("kompile-restore-");
                extractTarGz(backupFile, tempExtractDir);
                sourceDir = tempExtractDir;
                log.info("Extracted backup to temp directory: {}", tempExtractDir);
            } else {
                sourceDir = backupFile;
            }

            try {
                // Restore H2 databases
                if (properties.isIncludeDatabase()) {
                    // Restore orchestrator DB
                    Path orchestratorBackup = sourceDir.resolve("orchestrator-db");
                    if (Files.exists(orchestratorBackup)) {
                        String error = restoreH2Database(orchestratorBackup, properties.getOrchestratorDbPath());
                        if (error != null) errors.add("Orchestrator DB: " + error);
                    }

                    // Restore chat history DB
                    Path chatHistoryBackup = sourceDir.resolve("chat-history-db");
                    if (Files.exists(chatHistoryBackup)) {
                        String error = restoreH2Database(chatHistoryBackup, properties.getChatHistoryDbPath());
                        if (error != null) errors.add("Chat History DB: " + error);
                    }
                }

                // Restore Lucene indexes
                if (properties.isIncludeIndexes()) {
                    // Restore vector index
                    Path vectorIndexBackup = sourceDir.resolve("vector-index");
                    if (Files.exists(vectorIndexBackup)) {
                        String error = restoreLuceneIndex(vectorIndexBackup, properties.getVectorIndexPath());
                        if (error != null) errors.add("Vector Index: " + error);
                    }

                    // Restore text index
                    Path textIndexBackup = sourceDir.resolve("text-index");
                    if (Files.exists(textIndexBackup)) {
                        String error = restoreLuceneIndex(textIndexBackup, properties.getTextIndexPath());
                        if (error != null) errors.add("Text Index: " + error);
                    }
                }
            } finally {
                // Clean up temp directory
                if (tempExtractDir != null && Files.exists(tempExtractDir)) {
                    deleteDirectory(tempExtractDir);
                }
            }

            Duration duration = Duration.between(startTime, Instant.now());
            String message = errors.isEmpty() ?
                "Restore completed successfully" :
                "Restore completed with errors";

            log.info("Restore completed in {} ms with {} errors", duration.toMillis(), errors.size());

            return new RestoreResult(errors.isEmpty(), message, duration.toMillis(), errors);

        } catch (Exception e) {
            log.error("Restore failed", e);
            return new RestoreResult(false, "Restore failed: " + e.getMessage(), 0, List.of(e.getMessage()));
        }
    }

    /**
     * Restore H2 database files from backup.
     */
    private String restoreH2Database(Path sourceDir, String targetPath) {
        try {
            Path targetBase = Paths.get(targetPath);
            Path targetDir = targetBase.getParent();
            String baseName = targetBase.getFileName().toString();

            if (targetDir == null) {
                return "Invalid target path";
            }

            Files.createDirectories(targetDir);

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(sourceDir)) {
                for (Path file : stream) {
                    if (Files.isRegularFile(file)) {
                        Path target = targetDir.resolve(file.getFileName());
                        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                        log.debug("Restored database file: {}", file.getFileName());
                    }
                }
            }

            log.info("Restored H2 database: {}", baseName);
            return null;

        } catch (Exception e) {
            log.error("Error restoring H2 database to: {}", targetPath, e);
            return e.getMessage();
        }
    }

    /**
     * Restore Lucene index from backup.
     */
    private String restoreLuceneIndex(Path sourceDir, String targetPath) {
        try {
            Path targetDir = Paths.get(targetPath);

            // Clear existing index (except write.lock)
            if (Files.exists(targetDir)) {
                Files.walkFileTree(targetDir, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (!"write.lock".equals(file.getFileName().toString())) {
                            Files.deleteIfExists(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }

            Files.createDirectories(targetDir);

            // Copy files from backup
            Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path relativePath = sourceDir.relativize(file);
                    Path targetFile = targetDir.resolve(relativePath);
                    Files.createDirectories(targetFile.getParent());
                    Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Path relativePath = sourceDir.relativize(dir);
                    Path targetSubDir = targetDir.resolve(relativePath);
                    Files.createDirectories(targetSubDir);
                    return FileVisitResult.CONTINUE;
                }
            });

            log.info("Restored Lucene index: {}", targetDir.getFileName());
            return null;

        } catch (Exception e) {
            log.error("Error restoring Lucene index to: {}", targetPath, e);
            return e.getMessage();
        }
    }

    /**
     * Extract a tar.gz file to a directory.
     */
    private void extractTarGz(Path tarGzFile, Path targetDir) throws IOException {
        try (InputStream fis = Files.newInputStream(tarGzFile);
             GZIPInputStream gzis = new GZIPInputStream(fis);
             TarArchiveInputStream tais = new TarArchiveInputStream(gzis)) {

            TarArchiveEntry entry;
            while ((entry = tais.getNextTarEntry()) != null) {
                Path targetPath = targetDir.resolve(entry.getName());

                // Security check: prevent path traversal
                if (!targetPath.normalize().startsWith(targetDir.normalize())) {
                    throw new IOException("Invalid tar entry: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.createDirectories(targetPath.getParent());
                    Files.copy(tais, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * Get the path to a backup file by name.
     *
     * @param backupName The backup name (with or without .tar.gz extension)
     * @return Path to the backup file/directory, or null if not found
     */
    public Path getBackupFile(String backupName) {
        Path backupPath = Paths.get(properties.getBackupPath());

        // Try with the given name
        Path path = backupPath.resolve(backupName);
        if (Files.exists(path)) {
            return path;
        }

        // Try with .tar.gz extension if not present
        if (!backupName.endsWith(".tar.gz")) {
            path = backupPath.resolve(backupName + ".tar.gz");
            if (Files.exists(path)) {
                return path;
            }
        }

        // Try without .tar.gz extension if present
        if (backupName.endsWith(".tar.gz")) {
            path = backupPath.resolve(backupName.substring(0, backupName.length() - 7));
            if (Files.exists(path)) {
                return path;
            }
        }

        return null;
    }

    // ========== Records ==========

    /**
     * Result of a restore operation.
     */
    public record RestoreResult(
        boolean success,
        String message,
        long durationMs,
        List<String> errors
    ) {}

    /**
     * Result of a backup operation.
     */
    public record BackupResult(
        boolean success,
        String message,
        String backupPath,
        int fileCount,
        long totalBytes,
        long durationMs,
        List<String> errors
    ) {}

    /**
     * Statistics for a single backup component.
     */
    public record BackupStats(int fileCount, long totalBytes, String error) {}

    /**
     * Current status of the backup service.
     */
    public record BackupStatus(
        boolean enabled,
        boolean inProgress,
        Instant lastBackupTime,
        BackupResult lastResult,
        String backupPath,
        int retentionDays,
        String format,
        long intervalMs
    ) {}

    /**
     * Information about a single backup.
     */
    public record BackupInfo(
        String name,
        String path,
        Instant createdAt,
        long sizeBytes,
        String format
    ) {}
}
