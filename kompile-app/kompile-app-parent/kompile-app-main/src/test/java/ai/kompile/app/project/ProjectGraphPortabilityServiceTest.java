/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.app.project;

import ai.kompile.app.facts.domain.FactSheet;
import ai.kompile.app.facts.service.FactSheetService;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.knowledgegraph.unified.UnifiedGraphBridge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProjectGraphPortabilityServiceTest {

    private FactSheetService factSheetService;
    private KnowledgeGraphService knowledgeGraphService;
    private UnifiedGraphBridge unifiedGraphBridge;
    private ProjectGraphPortabilityService service;

    @TempDir
    Path projectRoot;

    @BeforeEach
    void setUp() {
        factSheetService = mock(FactSheetService.class);
        knowledgeGraphService = mock(KnowledgeGraphService.class);
        unifiedGraphBridge = mock(UnifiedGraphBridge.class);
        service = new ProjectGraphPortabilityService();
        ReflectionTestUtils.setField(service, "factSheetService", factSheetService);
        ReflectionTestUtils.setField(service, "knowledgeGraphService", knowledgeGraphService);
        ReflectionTestUtils.setField(service, "unifiedGraphBridge", unifiedGraphBridge);
    }

    private FactSheet sheet(long id) {
        FactSheet fs = mock(FactSheet.class);
        when(fs.getId()).thenReturn(id);
        return fs;
    }

    @Test
    void exportAllGraphs_writesNativeScopes_andRemovesLegacyArtifacts() throws Exception {
        FactSheet firstSheet = sheet(1);
        FactSheet secondSheet = sheet(2);
        when(factSheetService.getAllSheets()).thenReturn(List.of(firstSheet, secondSheet));
        when(unifiedGraphBridge.export(1L)).thenReturn(
                new UnifiedGraph().factSheetId(1L).addEntity("e1", "ENTITY", "Entity 1"));
        when(unifiedGraphBridge.export(2L)).thenReturn(new UnifiedGraph().factSheetId(2L));
        when(unifiedGraphBridge.export(isNull())).thenReturn(
                new UnifiedGraph().graphId("project").addEntity("e1", "ENTITY", "Entity 1"));

        Path dir = Files.createDirectories(projectRoot.resolve("data/graph"));
        Files.writeString(dir.resolve("factsheet-1.json"), "{}");
        Files.writeString(dir.resolve("global.json"), "{}");
        Files.createDirectories(dir.resolve("embeddings"));
        Files.write(dir.resolve("embeddings/factsheet-1.bin"), new byte[]{1});

        service.exportAllGraphs(projectRoot);

        assertTrue(Files.exists(dir.resolve("factsheet-1.kgraph")));
        assertFalse(Files.exists(dir.resolve("factsheet-2.kgraph")));
        assertTrue(Files.exists(dir.resolve("project.kgraph")));
        assertFalse(Files.exists(dir.resolve("factsheet-1.json")));
        assertFalse(Files.exists(dir.resolve("global.json")));
        assertFalse(Files.exists(dir.resolve("embeddings")));
        assertEquals(1, UnifiedGraph.load(dir.resolve("project.kgraph")).entities().size());
    }

    @Test
    void exportAllGraphs_removesScopesThatNoLongerExist() throws Exception {
        FactSheet firstSheet = sheet(1);
        when(factSheetService.getAllSheets()).thenReturn(List.of(firstSheet));
        when(unifiedGraphBridge.export(1L)).thenReturn(new UnifiedGraph().factSheetId(1L)
                .addEntity("e1", "ENTITY", "one"));
        when(unifiedGraphBridge.export(isNull())).thenReturn(new UnifiedGraph().graphId("project"));
        Path dir = Files.createDirectories(projectRoot.resolve("data/graph"));
        new UnifiedGraph().factSheetId(9L).addEntity("old", "ENTITY", "old")
                .save(dir.resolve("factsheet-9.kgraph"));

        service.exportAllGraphs(projectRoot);

        assertFalse(Files.exists(dir.resolve("factsheet-9.kgraph")));
        assertTrue(Files.exists(dir.resolve("factsheet-1.kgraph")));
    }

    @Test
    void exportAllGraphs_propagatesScopeFailure() throws Exception {
        FactSheet firstSheet = sheet(1);
        when(factSheetService.getAllSheets()).thenReturn(List.of(firstSheet));
        when(unifiedGraphBridge.export(1L)).thenThrow(new IllegalStateException("export failed"));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> service.exportAllGraphs(projectRoot));

        assertTrue(failure.getMessage().contains("native project graphs"));
    }

    @Test
    void importAllGraphs_skipsWhenGraphAlreadyPopulated() throws Exception {
        Path dir = Files.createDirectories(projectRoot.resolve("data/graph"));
        new UnifiedGraph().factSheetId(1L).addEntity("e1", "ENTITY", "one")
                .save(dir.resolve("factsheet-1.kgraph"));
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 5));

        service.importAllGraphs(projectRoot);

        verify(unifiedGraphBridge, never()).importGraph(any(), any());
    }

    @Test
    void importAllGraphs_preflightsEveryNativeScopeBeforeMutation() throws Exception {
        Path dir = Files.createDirectories(projectRoot.resolve("data/graph"));
        new UnifiedGraph().factSheetId(1L).addEntity("e1", "ENTITY", "one")
                .save(dir.resolve("factsheet-1.kgraph"));
        Files.writeString(dir.resolve("factsheet-2.kgraph"), "not a graph");
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 0));

        assertThrows(IllegalStateException.class, () -> service.importAllGraphs(projectRoot));

        verify(unifiedGraphBridge, never()).importGraph(any(), any());
    }

    @Test
    void importAllGraphs_importsScopedNativeArchives() throws Exception {
        Path dir = Files.createDirectories(projectRoot.resolve("data/graph"));
        new UnifiedGraph().factSheetId(7L).addEntity("e7", "ENTITY", "Entity 7")
                .save(dir.resolve("factsheet-7.kgraph"));
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 0));
        when(unifiedGraphBridge.importGraph(any(UnifiedGraph.class), eq(7L)))
                .thenReturn(new UnifiedGraphBridge.ImportSummary(1, 0, 0, 0));

        service.importAllGraphs(projectRoot);

        verify(unifiedGraphBridge).importGraph(any(UnifiedGraph.class), eq(7L));
    }

    @Test
    void importAllGraphs_usesCompleteProjectArchiveWhenNoScopesExist() throws Exception {
        Path dir = Files.createDirectories(projectRoot.resolve("data/graph"));
        new UnifiedGraph().graphId("project").addEntity("e", "ENTITY", "Entity")
                .save(dir.resolve("project.kgraph"));
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 0));
        when(unifiedGraphBridge.importGraph(any(UnifiedGraph.class), isNull()))
                .thenReturn(new UnifiedGraphBridge.ImportSummary(1, 0, 0, 0));

        service.importAllGraphs(projectRoot);

        verify(unifiedGraphBridge).importGraph(any(UnifiedGraph.class), isNull());
    }
}
