/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.project;

import ai.kompile.app.facts.domain.FactSheet;
import ai.kompile.app.facts.repository.FactRepository;
import ai.kompile.app.facts.repository.FactSheetRepository;
import ai.kompile.app.facts.service.FactSheetService;
import ai.kompile.app.sync.domain.NoteSyncConnection;
import ai.kompile.app.sync.repository.NoteSyncConnectionRepository;
import ai.kompile.project.KompileProjectFactSheet;
import ai.kompile.project.KompileProjectInitRequest;
import ai.kompile.project.KompileProjectNoteSyncConnection;
import ai.kompile.project.KompileProjectStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProjectRestorationServiceTest {

    @TempDir
    Path temp;

    private Path root;
    private ProjectRestorationService service;
    private final List<FactSheet> runtimeSheets = new ArrayList<>();
    private final List<NoteSyncConnection> runtimeConnections = new ArrayList<>();

    @BeforeEach
    void setUp() {
        root = temp.resolve("portable");
        KompileProjectStore store = new KompileProjectStore();
        store.init(root, new KompileProjectInitRequest());
        store.writeFactSheetCatalog(root, List.of(KompileProjectFactSheet.builder()
                .name("Research")
                .description("Portable research")
                .embeddingModel("sentence-transformer")
                .embeddingModelSource("registry")
                .rerankingEnabled(true)
                .rerankerType("cross-encoder")
                .enableGraphBuilding(true)
                .build()));
        store.writeNoteSyncCatalog(root, List.of(KompileProjectNoteSyncConnection.builder()
                .bindingId("source-stable")
                .provider("OBSIDIAN")
                .factSheetName("Research")
                .externalScope("vault")
                .direction("BIDIRECTIONAL")
                .enabled(true)
                .obsidianApiUrl("https://localhost:27124")
                .authMode("OBSIDIAN_REST_TOKEN")
                .credentialBinding("OBSIDIAN_TOKEN")
                .build()));

        FactSheetRepository sheetRepository = mock(FactSheetRepository.class);
        FactRepository factRepository = mock(FactRepository.class);
        when(sheetRepository.findAllByOrderByNameAsc())
                .thenAnswer(ignored -> new ArrayList<>(runtimeSheets));
        when(sheetRepository.existsByName(any()))
                .thenAnswer(invocation -> runtimeSheets.stream()
                        .anyMatch(sheet -> invocation.getArgument(0).equals(sheet.getName())));
        when(sheetRepository.save(any(FactSheet.class))).thenAnswer(invocation -> {
            FactSheet sheet = invocation.getArgument(0);
            if (sheet.getId() == null) sheet.setId((long) runtimeSheets.size() + 1);
            if (!runtimeSheets.contains(sheet)) runtimeSheets.add(sheet);
            return sheet;
        });
        FactSheetService factSheetService =
                new FactSheetService(sheetRepository, factRepository, null);

        NoteSyncConnectionRepository connectionRepository =
                mock(NoteSyncConnectionRepository.class);
        when(connectionRepository.findAll())
                .thenAnswer(ignored -> new ArrayList<>(runtimeConnections));
        when(connectionRepository.save(any(NoteSyncConnection.class))).thenAnswer(invocation -> {
            NoteSyncConnection connection = invocation.getArgument(0);
            if (connection.getId() == null) {
                connection.setId((long) runtimeConnections.size() + 100);
            }
            runtimeConnections.add(connection);
            return connection;
        });

        ProjectBackendService backend = mock(ProjectBackendService.class);
        when(backend.currentProjectRoot()).thenReturn(root);
        service = new ProjectRestorationService(
                backend, factSheetService, connectionRepository, store);
    }

    @Test
    void dryRunPlansCatalogRestorationWithoutChangingRuntime() {
        ProjectRestorationService.RestorationResult result = service.restoreCurrent(true);

        assertTrue(result.dryRun());
        assertFalse(result.applied());
        assertEquals(1, result.plannedFactSheets());
        assertEquals(1, result.plannedSourceConnections());
        assertTrue(runtimeSheets.isEmpty());
        assertTrue(runtimeConnections.isEmpty());
    }

    @Test
    void restoreIsIdempotentAndCreatesDisabledSecretFreeSources() {
        ProjectRestorationService.RestorationResult result = service.restoreCurrent(false);

        assertTrue(result.applied());
        assertEquals(1, result.restoredFactSheets());
        assertEquals(1, result.restoredSourceConnections());
        assertEquals(1, runtimeSheets.size());
        assertFalse(runtimeSheets.get(0).getIsActive());

        NoteSyncConnection source = runtimeConnections.get(0);
        assertFalse(source.getEnabled());
        assertFalse(source.getRemoteSyncEnabled());
        assertFalse(source.getAutoCommit());
        assertNull(source.getObsidianTokenEncrypted());
        assertEquals("MISSING", source.getAuthStatus());
        assertEquals("source-stable", result.readiness().items().stream()
                .filter(item -> item.kind().equals("SOURCE_CONNECTION"))
                .findFirst().orElseThrow().id());

        ProjectRestorationService.RestorationResult second = service.restoreCurrent(false);
        assertEquals(0, second.plannedFactSheets());
        assertEquals(0, second.plannedSourceConnections());
        assertEquals(1, runtimeSheets.size());
        assertEquals(1, runtimeConnections.size());
    }

    @Test
    void stagedProjectRequiresRestartAndDoesNotInspectGlobalRuntime() {
        Path active = temp.resolve("active");
        new KompileProjectStore().init(active, new KompileProjectInitRequest());
        ProjectBackendService differentBackend = mock(ProjectBackendService.class);
        when(differentBackend.currentProjectRoot()).thenReturn(active);
        ProjectRestorationService staged = new ProjectRestorationService(
                differentBackend,
                mock(FactSheetService.class),
                mock(NoteSyncConnectionRepository.class),
                new KompileProjectStore());

        ProjectRestorationService.RestorationReadiness readiness =
                staged.readinessForStaged(root);

        assertFalse(readiness.active());
        assertFalse(readiness.ready());
        assertEquals("RESTART_REQUIRED", readiness.activationMode());
        assertTrue(readiness.activationInstruction().contains(root.toString()));
        assertEquals(0, readiness.inventory().runtimeFactSheets());
    }
}
