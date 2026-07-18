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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private static final DateTimeFormatter ARCHIVE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final ProjectBackendService backend;
    private final Executor executor;
    private final ProjectArchiveService archives;
    private final Map<String, MutableJob> jobs = new ConcurrentHashMap<>();

    @Autowired
    public ProjectPortabilityJobService(
            ProjectBackendService backend,
            @Qualifier("taskExecutor") Executor executor) {
        this(backend, executor, new ProjectArchiveService());
    }

    ProjectPortabilityJobService(
            ProjectBackendService backend,
            Executor executor,
            ProjectArchiveService archives) {
        this.backend = backend;
        this.executor = executor;
        this.archives = archives;
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
        return jobs.values().stream()
                .map(MutableJob::snapshot)
                .sorted(Comparator.comparing(PortableJob::createdAt).reversed())
                .limit(50)
                .toList();
    }

    public PortableJob getJob(String id) {
        return requireJob(id).snapshot();
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
        MutableJob job = new MutableJob(UUID.randomUUID().toString(), operation);
        jobs.put(job.id, job);
        return job;
    }

    private MutableJob requireJob(String id) {
        MutableJob job = jobs.get(id);
        if (job == null) throw new IllegalArgumentException("Unknown portability job: " + id);
        return job;
    }

    private void runExport(MutableJob job) {
        Path exportDirectory = null;
        Path output = null;
        try {
            job.running("PREPARING_PORTABLE_STATE", 10);
            Path root = backend.preparePortableKnowledgeBase();

            job.stage("PACKAGING_PROJECT", 55);
            exportDirectory = Files.createTempDirectory("kompile-portable-export-");
            String base = safeSlug(root.getFileName() == null ? "kompile-project"
                    : root.getFileName().toString());
            output = exportDirectory.resolve(base + "-" + ARCHIVE_TIMESTAMP.format(Instant.now())
                    + ".kproject");
            ProjectArchiveResult result = archives.exportProject(
                    root, output, new ProjectArchiveExportOptions(true, false));

            job.stage("VERIFYING_ARCHIVE", 88);
            PortableArchive inspection = summary(archives.inspectProject(result.path()));
            job.completeExport(
                    inspection,
                    result.path().getFileName().toString(),
                    Files.size(result.path()),
                    result.path());
        } catch (Exception failure) {
            cleanupFailedExport(output, exportDirectory);
            job.fail(failure);
        }
    }

    private void runImport(MutableJob job, Path uploadedArchive, String requestedTargetName) {
        try {
            job.running("INSPECTING_ARCHIVE", 10);
            ProjectArchiveInspection inspection = archives.inspectProject(uploadedArchive);
            PortableArchive summary = summary(inspection);

            job.stage("PREFLIGHTING_DESTINATION", 30);
            Path target = importTarget(summary.name(), requestedTargetName);
            archives.preflightImport(uploadedArchive, target);

            job.stage("VERIFYING_AND_EXTRACTING", 55);
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

    private static final class MutableJob {
        private final String id;
        private final Operation operation;
        private final Instant createdAt = Instant.now();
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

        private MutableJob(String id, Operation operation) {
            this.id = id;
            this.operation = operation;
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

        synchronized Path downloadPath() {
            return status == Status.COMPLETED && operation == Operation.EXPORT ? artifactPath : null;
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
