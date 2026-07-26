/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.project;

import ai.kompile.project.archive.ProjectArchiveExportOptions;
import ai.kompile.project.archive.ProjectArchiveInspection;
import ai.kompile.project.archive.ProjectArchiveResult;
import ai.kompile.project.archive.ProjectArchiveSemanticMetadata;
import ai.kompile.project.archive.ProjectArchiveService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Coordinates portable knowledge-base snapshots as observable maintenance jobs.
 *
 * <p>Export first flushes runtime catalogs and graph state into the project tree. Import always
 * publishes into a new sibling directory and never activates providers, schedulers, or graphs.</p>
 */
@Service
public class ProjectPortabilityJobService {

    private static final Logger log = LoggerFactory.getLogger(ProjectPortabilityJobService.class);
    private static final int MAX_RETAINED_JOBS = 50;
    private static final int JOURNAL_SCHEMA_VERSION = 1;
    private static final String STATE_DIRECTORY = ".kompile/cache/portability";
    private static final DateTimeFormatter ARCHIVE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final ProjectBackendService backend;
    private final Executor executor;
    private final ProjectArchiveService archives;
    private final ObjectMapper objectMapper;
    private final Map<String, MutableJob> jobs = new ConcurrentHashMap<>();
    private final Object persistenceMonitor = new Object();
    private volatile Path persistenceRoot;
    private volatile boolean journalLoaded;

    @Autowired
    public ProjectPortabilityJobService(
            ProjectBackendService backend,
            @Qualifier("taskExecutor") Executor executor,
            ObjectMapper objectMapper) {
        this(backend, executor, new ProjectArchiveService(), objectMapper);
    }

    ProjectPortabilityJobService(
            ProjectBackendService backend,
            Executor executor,
            ProjectArchiveService archives) {
        this(backend, executor, archives, new ObjectMapper().findAndRegisterModules());
    }

    ProjectPortabilityJobService(
            ProjectBackendService backend,
            Executor executor,
            ProjectArchiveService archives,
            ObjectMapper objectMapper) {
        this.backend = backend;
        this.executor = executor;
        this.archives = archives;
        this.objectMapper = objectMapper;
    }

    public PortableJob startExport() {
        MutableJob job = create(Operation.EXPORT);
        executor.execute(() -> runExport(job));
        return job.snapshot();
    }

    /**
     * Starts a staged import and takes ownership of the temporary uploaded archive.
     */
    public PortableJob startImport(Path uploadedArchive, String targetName) {
        MutableJob job = create(Operation.IMPORT);
        executor.execute(() -> runImport(job, uploadedArchive, targetName));
        return job.snapshot();
    }

    public PortableArchive inspect(Path archive) throws IOException {
        return summary(archives.inspectProject(archive));
    }

    public List<PortableJob> listJobs() {
        ensureJournalLoaded();
        return jobs.values().stream()
                .map(MutableJob::snapshot)
                .sorted(Comparator.comparing(PortableJob::createdAt).reversed())
                .limit(MAX_RETAINED_JOBS)
                .toList();
    }

    public PortableJob getJob(String id) {
        return requireJob(id).snapshot();
    }

    public Path importedPath(String id) {
        PortableJob job = requireJob(id).snapshot();
        if (job.operation() != Operation.IMPORT || job.status() != Status.COMPLETED
                || job.importedPath() == null) {
            throw new IllegalStateException("Portable import is not ready for restoration inspection");
        }
        Path imported = Path.of(job.importedPath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(imported)) {
            throw new IllegalStateException("Imported project directory is no longer available");
        }
        return imported;
    }

    public Path downloadPath(String id) {
        MutableJob job = requireJob(id);
        Path artifact = job.downloadPath();
        if (artifact == null || !Files.isRegularFile(artifact)) {
            throw new IllegalStateException("Portable archive is not ready for download");
        }
        return artifact;
    }

    private MutableJob create(Operation operation) {
        ensureJournalLoaded();
        pruneFinishedJobs();
        MutableJob job = new MutableJob(UUID.randomUUID().toString(), operation, Instant.now());
        jobs.put(job.id, job);
        persistJournal();
        return job;
    }

    private void pruneFinishedJobs() {
        int removeCount = jobs.size() - MAX_RETAINED_JOBS + 1;
        if (removeCount <= 0) return;
        jobs.values().stream()
                .filter(MutableJob::terminal)
                .sorted(Comparator.comparing(MutableJob::createdAt))
                .limit(removeCount)
                .forEach(candidate -> {
                    if (jobs.remove(candidate.id, candidate)) {
                        candidate.cleanupArtifact();
                    }
                });
    }

    private MutableJob requireJob(String id) {
        ensureJournalLoaded();
        MutableJob job = jobs.get(id);
        if (job == null) throw new IllegalArgumentException("Unknown portability job: " + id);
        return job;
    }

    private void runExport(MutableJob job) {
        Path output = null;
        try {
            job.running("PREPARING_PORTABLE_STATE", 10);
            persistJournal();
            Path root = backend.preparePortableKnowledgeBase();
            bindPersistenceRoot(root);

            job.stage("PACKAGING_PROJECT", 55);
            persistJournal();
            Path artifactDirectory = stateDirectory().resolve("artifacts");
            Files.createDirectories(artifactDirectory);
            String base = safeSlug(root.getFileName() == null ? "kompile-project"
                    : root.getFileName().toString());
            String artifactName = base + "-" + ARCHIVE_TIMESTAMP.format(Instant.now())
                    + ".kproject";
            output = artifactDirectory.resolve(job.id + ".kproject");
            ProjectArchiveResult result = archives.exportProject(
                    root, output, new ProjectArchiveExportOptions(true, false));

            job.stage("VERIFYING_ARCHIVE", 88);
            persistJournal();
            PortableArchive inspection = summary(archives.inspectProject(result.path()));
            job.completeExport(
                    inspection,
                    artifactName,
                    Files.size(result.path()),
                    result.path());
        } catch (Exception failure) {
            cleanupFailedExport(output, null);
            job.fail(failure);
        } finally {
            persistJournal();
        }
    }

    private void runImport(MutableJob job, Path uploadedArchive, String requestedTargetName) {
        try {
            job.running("INSPECTING_ARCHIVE", 10);
            persistJournal();
            ProjectArchiveInspection inspection = archives.inspectProject(uploadedArchive);
            PortableArchive summary = summary(inspection);

            job.stage("PREFLIGHTING_DESTINATION", 30);
            persistJournal();
            Path target = importTarget(summary.name(), requestedTargetName);
            archives.preflightImport(uploadedArchive, target);

            job.stage("VERIFYING_AND_EXTRACTING", 55);
            persistJournal();
            ProjectArchiveResult imported = archives.importProject(uploadedArchive, target);
            List<String> warnings = new ArrayList<>(summary.warnings());
            warnings.add("Imported project is staged and inactive; open it explicitly to restore runtime state.");
            job.completeImport(
                    new PortableArchive(
                            summary.formatVersion(), summary.projectId(), summary.name(),
                            summary.createdAt(), summary.defaultGraph(), summary.semantic(),
                            summary.entryCount(), imported.totalBytes(), List.copyOf(warnings)),
                    imported.path().toString());
        } catch (Exception failure) {
            job.fail(failure);
        } finally {
            cleanupUpload(uploadedArchive);
            persistJournal();
        }
    }

    private void ensureJournalLoaded() {
        Path root = currentProjectRootOrNull();
        if (root == null) return;
        bindPersistenceRoot(root);
    }

    private void bindPersistenceRoot(Path root) {
        if (root == null) return;
        Path normalized = root.toAbsolutePath().normalize();
        if (journalLoaded && normalized.equals(persistenceRoot)) return;
        synchronized (persistenceMonitor) {
            if (journalLoaded && normalized.equals(persistenceRoot)) return;
            List<MutableJob> unboundJobs = persistenceRoot == null
                    ? List.copyOf(jobs.values()) : List.of();
            persistenceRoot = normalized;
            jobs.clear();
            boolean recoveredInterruptedJob = false;
            Path journal = stateDirectory().resolve("jobs.json");
            if (Files.isRegularFile(journal)) {
                try {
                    JobJournal saved = objectMapper.readValue(journal.toFile(), JobJournal.class);
                    if (saved.jobs() != null) {
                        for (PersistedJob persisted : saved.jobs()) {
                            MutableJob restored = MutableJob.restore(
                                    persisted.job(),
                                    persisted.artifactFile() == null ? null
                                            : stateDirectory().resolve("artifacts")
                                                    .resolve(persisted.artifactFile()));
                            if (!restored.terminal()) {
                                restored.interrupt();
                                recoveredInterruptedJob = true;
                            }
                            jobs.put(restored.id, restored);
                        }
                    }
                } catch (Exception failure) {
                    log.warn("Unable to restore portability job journal {}", journal, failure);
                }
            }
            unboundJobs.forEach(job -> jobs.putIfAbsent(job.id, job));
            journalLoaded = true;
            pruneFinishedJobs();
            if (recoveredInterruptedJob) persistJournal();
        }
    }

    private Path currentProjectRootOrNull() {
        try {
            return backend.currentProjectRoot();
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private Path stateDirectory() {
        Path root = persistenceRoot;
        if (root == null) {
            throw new IllegalStateException("No current project is available for portability state");
        }
        return root.resolve(STATE_DIRECTORY);
    }

    private void persistJournal() {
        if (!journalLoaded || persistenceRoot == null) return;
        synchronized (persistenceMonitor) {
            Path directory = stateDirectory();
            Path journal = directory.resolve("jobs.json");
            Path temporary = directory.resolve("jobs.json.tmp");
            try {
                Files.createDirectories(directory);
                List<PersistedJob> saved = jobs.values().stream()
                        .sorted(Comparator.comparing(MutableJob::createdAt))
                        .map(job -> {
                            Path artifact = job.downloadPath();
                            return new PersistedJob(
                                    job.snapshot(),
                                    artifact == null ? null : artifact.getFileName().toString());
                        })
                        .toList();
                objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValue(temporary.toFile(), new JobJournal(JOURNAL_SCHEMA_VERSION, saved));
                try {
                    Files.move(temporary, journal, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, journal, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception failure) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Preserve the primary persistence failure.
                }
                log.warn("Unable to persist portability job journal {}", journal, failure);
            }
        }
    }

    private Path importTarget(String archiveName, String requestedTargetName) throws IOException {
        Path current = backend.currentProjectRoot();
        Path parent = current.getParent();
        if (parent == null) throw new IOException("Current project does not have a workspace parent");

        if (requestedTargetName != null && !requestedTargetName.isBlank()) {
            String candidate = requestedTargetName.trim();
            if (!candidate.matches("[A-Za-z0-9._-]{1,128}") || candidate.equals(".")
                    || candidate.equals("..")) {
                throw new IOException("Import target name may contain only letters, digits, '.', '_' and '-'");
            }
            return parent.resolve(candidate).toAbsolutePath().normalize();
        }

        String base = safeSlug(archiveName) + "-imported";
        Path candidate = parent.resolve(base).toAbsolutePath().normalize();
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = parent.resolve(base + "-" + suffix++).toAbsolutePath().normalize();
        }
        return candidate;
    }

    private PortableArchive summary(ProjectArchiveInspection inspection) {
        var manifest = inspection.manifest();
        return new PortableArchive(
                manifest.formatVersion(),
                manifest.projectId(),
                manifest.name(),
                manifest.createdAt(),
                manifest.defaultGraph(),
                manifest.semantic(),
                manifest.entries().size(),
                inspection.declaredTotalBytes(),
                inspection.warnings());
    }

    private static String safeSlug(String value) {
        String slug = value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.isBlank() ? "kompile-project" : slug;
    }

    private static void cleanupFailedExport(Path output, Path directory) {
        try {
            if (output != null) Files.deleteIfExists(output);
            if (directory != null) Files.deleteIfExists(directory);
        } catch (IOException ignored) {
            // The job already carries the primary failure.
        }
    }

    private static void cleanupUpload(Path upload) {
        if (upload == null) return;
        try {
            Files.deleteIfExists(upload);
            Path parent = upload.getParent();
            if (parent != null && parent.getFileName() != null
                    && parent.getFileName().toString().startsWith("kompile-portable-upload-")) {
                Files.deleteIfExists(parent);
            }
        } catch (IOException ignored) {
            // Temporary upload cleanup is best-effort.
        }
    }

    public enum Operation { EXPORT, IMPORT }

    public enum Status {
        QUEUED, RUNNING, COMPLETED, FAILED;

        public boolean terminal() {
            return this == COMPLETED || this == FAILED;
        }
    }

    public record PortableArchive(
            int formatVersion,
            String projectId,
            String name,
            Instant createdAt,
            String defaultGraph,
            ProjectArchiveSemanticMetadata semantic,
            int entryCount,
            long totalBytes,
            List<String> warnings) {

        public PortableArchive {
            semantic = semantic == null ? ProjectArchiveSemanticMetadata.empty() : semantic;
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    public record PortableJob(
            String id,
            Operation operation,
            Status status,
            String stage,
            int progress,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt,
            PortableArchive archive,
            String artifactName,
            Long artifactSize,
            boolean downloadReady,
            String importedPath,
            List<String> warnings,
            String error) {

        public PortableJob {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    private record JobJournal(int schemaVersion, List<PersistedJob> jobs) {
    }

    private record PersistedJob(PortableJob job, String artifactFile) {
    }

    private static final class MutableJob {
        private final String id;
        private final Operation operation;
        private final Instant createdAt;
        private Status status = Status.QUEUED;
        private String stage = "QUEUED";
        private int progress;
        private Instant startedAt;
        private Instant completedAt;
        private PortableArchive archive;
        private String artifactName;
        private Long artifactSize;
        private Path artifactPath;
        private String importedPath;
        private List<String> warnings = List.of();
        private String error;

        private MutableJob(String id, Operation operation, Instant createdAt) {
            this.id = id;
            this.operation = operation;
            this.createdAt = createdAt;
        }

        static MutableJob restore(PortableJob snapshot, Path artifactPath) {
            MutableJob restored = new MutableJob(
                    snapshot.id(), snapshot.operation(), snapshot.createdAt());
            restored.status = snapshot.status();
            restored.stage = snapshot.stage();
            restored.progress = snapshot.progress();
            restored.startedAt = snapshot.startedAt();
            restored.completedAt = snapshot.completedAt();
            restored.archive = snapshot.archive();
            restored.artifactName = snapshot.artifactName();
            restored.artifactSize = snapshot.artifactSize();
            restored.artifactPath = artifactPath != null && Files.isRegularFile(artifactPath)
                    ? artifactPath
                    : null;
            restored.importedPath = snapshot.importedPath();
            restored.warnings = snapshot.warnings();
            restored.error = snapshot.error();
            return restored;
        }

        synchronized void running(String nextStage, int nextProgress) {
            status = Status.RUNNING;
            startedAt = Instant.now();
            stage = nextStage;
            progress = nextProgress;
        }

        synchronized void stage(String nextStage, int nextProgress) {
            stage = nextStage;
            progress = nextProgress;
        }

        synchronized void completeExport(
                PortableArchive portableArchive,
                String name,
                long size,
                Path path) {
            archive = portableArchive;
            warnings = portableArchive.warnings();
            artifactName = name;
            artifactSize = size;
            artifactPath = path;
            finish("READY_TO_DOWNLOAD");
        }

        synchronized void completeImport(PortableArchive portableArchive, String path) {
            archive = portableArchive;
            warnings = portableArchive.warnings();
            importedPath = path;
            finish("READY_TO_OPEN");
        }

        synchronized void fail(Exception failure) {
            status = Status.FAILED;
            stage = "FAILED";
            completedAt = Instant.now();
            error = failure.getMessage() == null ? failure.getClass().getSimpleName()
                    : failure.getMessage();
        }

        synchronized void interrupt() {
            status = Status.FAILED;
            stage = "INTERRUPTED";
            completedAt = Instant.now();
            error = "Application restarted before the portability job completed.";
        }

        synchronized Path downloadPath() {
            return status == Status.COMPLETED && operation == Operation.EXPORT ? artifactPath : null;
        }

        synchronized boolean terminal() {
            return status.terminal();
        }

        Instant createdAt() {
            return createdAt;
        }

        synchronized void cleanupArtifact() {
            Path path = artifactPath;
            artifactPath = null;
            if (path == null) return;
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // Expired download cleanup is best-effort.
            }
        }

        synchronized PortableJob snapshot() {
            return new PortableJob(
                    id, operation, status, stage, progress, createdAt, startedAt, completedAt,
                    archive, artifactName, artifactSize,
                    status == Status.COMPLETED && operation == Operation.EXPORT
                            && artifactPath != null,
                    importedPath, warnings, error);
        }

        private void finish(String terminalStage) {
            status = Status.COMPLETED;
            stage = terminalStage;
            progress = 100;
            completedAt = Instant.now();
        }
    }
}
