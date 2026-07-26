/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.project;

import ai.kompile.project.archive.ProjectArchiveService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectPortabilityJobServiceTest {

    @TempDir
    Path temp;

    @Test
    void exportFlushesPortableStateAndProducesDownloadableVersionTwoArchive() throws Exception {
        Path root = project("current");
        Files.createDirectories(root.resolve("data/graph"));
        Files.writeString(root.resolve("data/graph/project.kgraph"), "graph");

        ProjectBackendService backend = mock(ProjectBackendService.class);
        when(backend.preparePortableKnowledgeBase()).thenReturn(root);
        when(backend.currentProjectRoot()).thenReturn(root);
        ProjectPortabilityJobService service = new ProjectPortabilityJobService(
                backend, Runnable::run, new ProjectArchiveService());

        ProjectPortabilityJobService.PortableJob job = service.startExport();

        assertEquals(ProjectPortabilityJobService.Status.COMPLETED, job.status());
        assertEquals("READY_TO_DOWNLOAD", job.stage());
        assertEquals(100, job.progress());
        assertTrue(job.downloadReady());
        assertEquals(ProjectArchiveService.FORMAT_VERSION, job.archive().formatVersion());
        assertTrue(job.archive().semantic().portableAssets().contains("KNOWLEDGE_GRAPH"));
        assertTrue(Files.isRegularFile(service.downloadPath(job.id())));
        verify(backend).preparePortableKnowledgeBase();
    }

    @Test
    void importPreflightsAndPublishesANewInactiveSiblingProject() throws Exception {
        Path current = project("current-import-host");
        Path source = project("portable-source");
        Files.writeString(source.resolve("README.md"), "portable");
        Path upload = temp.resolve("portable-source.kproject");
        ProjectArchiveService archives = new ProjectArchiveService();
        archives.exportProject(source, upload);

        ProjectBackendService backend = mock(ProjectBackendService.class);
        when(backend.currentProjectRoot()).thenReturn(current);
        ProjectPortabilityJobService service =
                new ProjectPortabilityJobService(backend, Runnable::run, archives);

        ProjectPortabilityJobService.PortableJob job = service.startImport(upload, null);

        assertEquals(ProjectPortabilityJobService.Status.COMPLETED, job.status());
        assertEquals("READY_TO_OPEN", job.stage());
        assertNotNull(job.importedPath());
        Path imported = Path.of(job.importedPath());
        assertEquals("portable", Files.readString(imported.resolve("README.md")));
        assertFalse(Files.exists(upload), "the service owns and removes the temporary upload");
        assertTrue(job.warnings().stream().anyMatch(value -> value.contains("inactive")));
    }

    @Test
    void importFailureIsVisibleAndNeverOverwritesAnExistingTarget() throws Exception {
        Path current = project("current-failure-host");
        Path source = project("failure-source");
        Path upload = temp.resolve("failure-source.kproject");
        ProjectArchiveService archives = new ProjectArchiveService();
        archives.exportProject(source, upload);
        Path existing = Files.createDirectory(temp.resolve("reserved-target"));
        Files.writeString(existing.resolve("sentinel"), "keep");

        ProjectBackendService backend = mock(ProjectBackendService.class);
        when(backend.currentProjectRoot()).thenReturn(current);
        ProjectPortabilityJobService service =
                new ProjectPortabilityJobService(backend, Runnable::run, archives);

        ProjectPortabilityJobService.PortableJob job =
                service.startImport(upload, "reserved-target");

        assertEquals(ProjectPortabilityJobService.Status.FAILED, job.status());
        assertTrue(job.error().contains("already exists"));
        assertEquals("keep", Files.readString(existing.resolve("sentinel")));
        assertFalse(Files.exists(upload));
    }

    @Test
    void finishedJobHistoryIsBounded() {
        ProjectBackendService backend = mock(ProjectBackendService.class);
        ProjectPortabilityJobService service = new ProjectPortabilityJobService(
                backend, Runnable::run, new ProjectArchiveService());

        String firstJobId = null;
        for (int i = 0; i < 60; i++) {
            ProjectPortabilityJobService.PortableJob job =
                    service.startImport(temp.resolve("missing-" + i + ".kproject"), null);
            if (firstJobId == null) firstJobId = job.id();
            assertEquals(ProjectPortabilityJobService.Status.FAILED, job.status());
        }

        assertEquals(50, service.listJobs().size());
        String expiredJobId = firstJobId;
        assertThrows(IllegalArgumentException.class, () -> service.getJob(expiredJobId));
    }

    @Test
    void completedExportsAndDownloadsSurviveServiceRestart() throws Exception {
        Path root = project("durable-export");
        ProjectBackendService backend = mock(ProjectBackendService.class);
        when(backend.currentProjectRoot()).thenReturn(root);
        when(backend.preparePortableKnowledgeBase()).thenReturn(root);

        ProjectPortabilityJobService first = new ProjectPortabilityJobService(
                backend, Runnable::run, new ProjectArchiveService());
        ProjectPortabilityJobService.PortableJob exported = first.startExport();
        Path artifact = first.downloadPath(exported.id());

        ProjectPortabilityJobService restarted = new ProjectPortabilityJobService(
                backend, Runnable::run, new ProjectArchiveService());

        assertEquals(ProjectPortabilityJobService.Status.COMPLETED,
                restarted.getJob(exported.id()).status());
        assertEquals(artifact, restarted.downloadPath(exported.id()));
        assertTrue(Files.isRegularFile(artifact));
    }

    @Test
    void missingDurableArtifactIsNotAdvertisedAfterRestart() throws Exception {
        Path root = project("missing-durable-export");
        ProjectBackendService backend = mock(ProjectBackendService.class);
        when(backend.currentProjectRoot()).thenReturn(root);
        when(backend.preparePortableKnowledgeBase()).thenReturn(root);

        ProjectPortabilityJobService first = new ProjectPortabilityJobService(
                backend, Runnable::run, new ProjectArchiveService());
        ProjectPortabilityJobService.PortableJob exported = first.startExport();
        Files.delete(first.downloadPath(exported.id()));

        ProjectPortabilityJobService restarted = new ProjectPortabilityJobService(
                backend, Runnable::run, new ProjectArchiveService());
        ProjectPortabilityJobService.PortableJob recovered = restarted.getJob(exported.id());

        assertEquals(ProjectPortabilityJobService.Status.COMPLETED, recovered.status());
        assertFalse(recovered.downloadReady());
        assertThrows(IllegalStateException.class, () -> restarted.downloadPath(exported.id()));
    }

    @Test
    void unfinishedJobsAreRecoveredAsInterruptedAfterRestart() throws Exception {
        Path root = project("interrupted-export");
        ProjectBackendService backend = mock(ProjectBackendService.class);
        when(backend.currentProjectRoot()).thenReturn(root);
        AtomicReference<Runnable> queued = new AtomicReference<>();
        Executor holdingExecutor = queued::set;

        ProjectPortabilityJobService first = new ProjectPortabilityJobService(
                backend, holdingExecutor, new ProjectArchiveService());
        ProjectPortabilityJobService.PortableJob started = first.startExport();
        assertNotNull(queued.get());
        assertEquals(ProjectPortabilityJobService.Status.QUEUED, started.status());

        ProjectPortabilityJobService restarted = new ProjectPortabilityJobService(
                backend, Runnable::run, new ProjectArchiveService());
        ProjectPortabilityJobService.PortableJob recovered = restarted.getJob(started.id());

        assertEquals(ProjectPortabilityJobService.Status.FAILED, recovered.status());
        assertEquals("INTERRUPTED", recovered.stage());
        assertTrue(recovered.error().contains("restarted"));
    }

    private Path project(String name) throws Exception {
        Path root = Files.createDirectory(temp.resolve(name));
        Files.writeString(root.resolve("kompile.project.json"),
                "{\"schemaVersion\":1,\"projectId\":\"id-" + name
                        + "\",\"name\":\"" + name + "\"}");
        return root;
    }
}
