/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.app.project;

import ai.kompile.app.facts.domain.FactSheet;
import ai.kompile.app.facts.service.FactSheetService;
import ai.kompile.codeindexer.domain.CodeProject;
import ai.kompile.codeindexer.domain.CodeProjectRepository;
import ai.kompile.codeindexer.service.CodebaseIndexer;
import ai.kompile.project.KompileCodingProject;
import ai.kompile.project.KompileProjectFactSheet;
import ai.kompile.project.KompileProjectInitRequest;
import ai.kompile.project.KompileProjectStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectBackendServiceTest {

    private static final String PORTABLE_ID = "ff9209ba-60c4-40ec-a78f-e4145041a740";

    @TempDir
    Path tempDir;

    @Test
    void changedFactSheetBindingReprojectsExistingCodeGraph() {
        CodeProjectRepository repository = mock(CodeProjectRepository.class);
        CodebaseIndexer indexer = mock(CodebaseIndexer.class);
        CodeProject indexedProject = CodeProject.builder()
                .projectId("code-app")
                .name("Application")
                .build();
        when(repository.findByProjectId("code-app")).thenReturn(Optional.of(indexedProject));
        when(repository.save(indexedProject)).thenReturn(indexedProject);

        ProjectBackendService service = projectService(repository, indexer);
        KompileCodingProject project = registerProject(service, null);
        clearInvocations(indexer);

        TransactionSynchronizationManager.initSynchronization();
        try {
            ProjectResponse response = service.bindCodingProjectFactSheet(project.getId(), 42L);

            assertEquals(42L, response.manifest().getCodingProjects().get(0).getFactSheetId());
            assertEquals(42L, indexedProject.getFactSheetId());
            verify(indexer, never()).pruneProjectGraph(eq("code-app"), isNull());
            verify(indexer, never()).indexDirectoryAsync("code-app", tempDir.toString(), true);
            assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(synchronization -> synchronization.afterCommit());

            verify(indexer).pruneProjectGraph(eq("code-app"), isNull());
            verify(indexer).indexDirectoryAsync("code-app", tempDir.toString(), true);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void unchangedFactSheetBindingDoesNotRebuildCodeGraph() {
        CodeProjectRepository repository = mock(CodeProjectRepository.class);
        CodebaseIndexer indexer = mock(CodebaseIndexer.class);
        CodeProject indexedProject = CodeProject.builder()
                .projectId("code-app")
                .name("Application")
                .factSheetId(42L)
                .build();
        when(repository.findByProjectId("code-app")).thenReturn(Optional.of(indexedProject));
        when(repository.save(indexedProject)).thenReturn(indexedProject);

        ProjectBackendService service = projectService(repository, indexer);
        KompileCodingProject project = registerProject(service, 42L);
        clearInvocations(indexer);

        service.bindCodingProjectFactSheet(project.getId(), 42L);

        verify(indexer, never()).pruneProjectGraph(eq("code-app"), eq(42L));
        verify(indexer, never()).indexDirectoryAsync("code-app", tempDir.toString(), true);
    }

    @Test
    void crawlBindingAwaitsStrictSynchronousProjection() {
        CodeProjectRepository repository = mock(CodeProjectRepository.class);
        CodebaseIndexer indexer = mock(CodebaseIndexer.class);
        CodeProject indexedProject = CodeProject.builder()
                .projectId("code-app").name("Application").factSheetId(7L).build();
        when(repository.findByProjectId("code-app")).thenReturn(Optional.of(indexedProject));
        when(repository.save(indexedProject)).thenReturn(indexedProject);
        when(indexer.projectDirectoryToFactSheet("code-app", tempDir.toString(), 42L, true))
                .thenReturn(successfulStatus());
        ProjectBackendService service = projectService(repository, indexer);
        KompileCodingProject project = registerProject(service, 7L);
        clearInvocations(indexer);

        ProjectResponse response = service.bindCodingProjectFactSheetAndProject(project.getId(), 42L);

        assertEquals(42L, response.manifest().getCodingProjects().get(0).getFactSheetId());
        verify(repository).flush();
        verify(indexer).pruneProjectGraph("code-app", 7L);
        verify(indexer).projectDirectoryToFactSheet("code-app", tempDir.toString(), 42L, true);
        verify(indexer, never()).indexDirectoryAsync("code-app", tempDir.toString(), true);
    }

    @Test
    void failedCrawlProjectionRestoresPreviousBindingAndGraph() {
        CodeProjectRepository repository = mock(CodeProjectRepository.class);
        CodebaseIndexer indexer = mock(CodebaseIndexer.class);
        CodeProject indexedProject = CodeProject.builder()
                .projectId("code-app").name("Application").factSheetId(7L).build();
        when(repository.findByProjectId("code-app")).thenReturn(Optional.of(indexedProject));
        when(repository.save(indexedProject)).thenReturn(indexedProject);
        when(indexer.projectDirectoryToFactSheet("code-app", tempDir.toString(), 42L, true))
                .thenThrow(new IllegalStateException("projection failed"));
        ProjectBackendService service = projectService(repository, indexer);
        KompileCodingProject project = registerProject(service, 7L);
        clearInvocations(indexer);

        assertThrows(IllegalStateException.class,
                () -> service.bindCodingProjectFactSheetAndProject(project.getId(), 42L));

        assertEquals(7L, service.getManifest().getCodingProjects().get(0).getFactSheetId());
        assertEquals(7L, indexedProject.getFactSheetId());
        verify(indexer, never()).pruneProjectGraph("code-app", 7L);
    }

    @Test
    void exportProjectCatalogsPersistsStableFactSheetIdentity() {
        ProjectBackendService service = projectService(
                mock(CodeProjectRepository.class), mock(CodebaseIndexer.class));
        FactSheetService factSheets = mock(FactSheetService.class);
        FactSheet sheet = FactSheet.builder().id(42L).portableId(PORTABLE_ID)
                .name("Research").isActive(false).build();
        when(factSheets.getAllSheets()).thenReturn(List.of(sheet));
        when(factSheets.ensurePortableId(sheet)).thenReturn(sheet);
        ReflectionTestUtils.setField(service, "factSheetService", factSheets);

        service.exportProjectCatalogs(tempDir);

        List<KompileProjectFactSheet> portable = new KompileProjectStore().listFactSheets(tempDir);
        assertEquals(1, portable.size());
        assertEquals(PORTABLE_ID, portable.get(0).getPortableId());
        assertEquals(42L, portable.get(0).getId());
    }

    private ProjectBackendService projectService(CodeProjectRepository repository,
                                                 CodebaseIndexer indexer) {
        ProjectBackendService service = new ProjectBackendService(repository, indexer);
        ReflectionTestUtils.setField(service, "configuredRoot", tempDir.toString());
        KompileProjectInitRequest init = new KompileProjectInitRequest();
        init.setName("graph-project");
        service.init(init);
        return service;
    }

    private KompileCodingProject registerProject(ProjectBackendService service, Long factSheetId) {
        KompileCodingProject project = new KompileCodingProject();
        project.setId("app");
        project.setCodeProjectId("code-app");
        project.setName("Application");
        project.setRootPath(tempDir.toString());
        project.setFactSheetId(factSheetId);
        service.registerCodingProject(project);
        return project;
    }

    private static CodebaseIndexer.IndexingStatus successfulStatus() {
        return new CodebaseIndexer.IndexingStatus("code-app", "/tmp", Instant.now(),
                new AtomicInteger(), new AtomicInteger(), new AtomicInteger(),
                new AtomicInteger(), new AtomicInteger(), new AtomicInteger(),
                new AtomicBoolean(true), null, false);
    }
}
