/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.app.project;

import ai.kompile.app.facts.domain.FactSheet;
import ai.kompile.app.facts.service.FactSheetService;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.knowledgegraph.unified.UnifiedGraphBridge;
import ai.kompile.project.KompileProjectFactSheet;
import ai.kompile.project.KompileProjectStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProjectGraphPortabilityServiceTest {

    private static final String RESEARCH_PORTABLE_ID = "0b6f3c1e-2a4d-4f5e-9c7b-1d2e3f4a5b6c";
    private static final String FINANCE_PORTABLE_ID = "7c1d9e2f-3b4a-4c5d-8e6f-a1b2c3d4e5f6";
    private static final String DELETED_PORTABLE_ID = "e4f5a6b7-c8d9-4e0f-a1b2-c3d4e5f6a7b8";

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
        service.factSheetService = factSheetService;
        service.knowledgeGraphService = knowledgeGraphService;
        service.unifiedGraphBridge = unifiedGraphBridge;
        // Every sheet below already carries a portable identity, so the backfill hands it back unchanged.
        when(factSheetService.ensurePortableId(any(FactSheet.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static FactSheet sheet(long id, String portableId, String name) {
        return FactSheet.builder().id(id).portableId(portableId).name(name).build();
    }

    /** Publish the fact-sheet catalog, as ProjectBackendService.commit() does before exporting graphs. */
    private void publishCatalog(FactSheet... sheets) {
        new KompileProjectStore().writeFactSheetCatalog(projectRoot, Arrays.stream(sheets)
                .map(sheet -> KompileProjectFactSheet.builder()
                        .id(sheet.getId())
                        .portableId(sheet.getPortableId())
                        .name(sheet.getName())
                        .build())
                .toList());
    }

    private static UnifiedGraphBridge.BatchImportSummary oneImportedScope() {
        return new UnifiedGraphBridge.BatchImportSummary(
                "tx-1", List.of(new UnifiedGraphBridge.ImportSummary(1, 0, 0, 0)));
    }

    @Test
    void exportAllGraphs_writesPortableScopes_andRemovesLegacyArtifacts() throws Exception {
        FactSheet research = sheet(1, RESEARCH_PORTABLE_ID, "Research");
        FactSheet finance = sheet(2, FINANCE_PORTABLE_ID, "Finance");
        when(factSheetService.getAllSheets()).thenReturn(List.of(research, finance));
        publishCatalog(research, finance);
        when(unifiedGraphBridge.export(1L)).thenReturn(
                new UnifiedGraph().factSheetId(1L).addEntity("e1", "ENTITY", "Entity 1"));
        when(unifiedGraphBridge.export(2L)).thenReturn(new UnifiedGraph().factSheetId(2L));
        when(unifiedGraphBridge.export(isNull())).thenReturn(
                new UnifiedGraph().graphId("project").addEntity("e1", "ENTITY", "Entity 1"));

        Path dir = Files.createDirectories(projectRoot.resolve("data/graph"));
        Files.writeString(dir.resolve("factsheet-1.json"), "{}");
        Files.writeString(dir.resolve("global.json"), "{}");
        Files.writeString(dir.resolve("named-graphs.json"), "{}");
        Files.createDirectories(dir.resolve("embeddings"));
        Files.write(dir.resolve("embeddings/factsheet-1.bin"), new byte[]{1});

        service.exportAllGraphs(projectRoot);

        Path researchScope = dir.resolve("factsheet-" + RESEARCH_PORTABLE_ID + ".kgraph");
        assertTrue(Files.exists(researchScope));
        assertFalse(Files.exists(dir.resolve("factsheet-1.kgraph")),
                "scopes are named by portable identity, not by the source database id");
        assertFalse(Files.exists(dir.resolve("factsheet-" + FINANCE_PORTABLE_ID + ".kgraph")),
                "an empty scope publishes no archive");
        assertTrue(Files.exists(dir.resolve("project.kgraph")));
        assertFalse(Files.exists(dir.resolve("factsheet-1.json")));
        assertFalse(Files.exists(dir.resolve("global.json")));
        assertFalse(Files.exists(dir.resolve("named-graphs.json")));
        assertFalse(Files.exists(dir.resolve("embeddings")));
        assertEquals(1, UnifiedGraph.load(dir.resolve("project.kgraph")).entities().size());

        Map<?, ?> sourceScope = (Map<?, ?>) UnifiedGraph.load(researchScope).meta()
                .get(ProjectGraphDestinationMapper.SOURCE_SCOPE_META);
        assertEquals("FACT_SHEET", sourceScope.get("kind"));
        assertEquals(RESEARCH_PORTABLE_ID, sourceScope.get("portableId"));
        assertEquals(1L, ((Number) sourceScope.get("legacyFactSheetId")).longValue());
        assertEquals("Research", sourceScope.get("name"));
    }

    @Test
    void exportAllGraphs_removesScopesThatNoLongerExist() throws Exception {
        FactSheet research = sheet(1, RESEARCH_PORTABLE_ID, "Research");
        when(factSheetService.getAllSheets()).thenReturn(List.of(research));
        publishCatalog(research);
        when(unifiedGraphBridge.export(1L)).thenReturn(new UnifiedGraph().factSheetId(1L)
                .addEntity("e1", "ENTITY", "one"));
        when(unifiedGraphBridge.export(isNull())).thenReturn(new UnifiedGraph().graphId("project"));
        Path dir = Files.createDirectories(projectRoot.resolve("data/graph"));
        new UnifiedGraph().factSheetId(9L).addEntity("old", "ENTITY", "old")
                .save(dir.resolve("factsheet-" + DELETED_PORTABLE_ID + ".kgraph"));
        new UnifiedGraph().factSheetId(9L).addEntity("old", "ENTITY", "old")
                .save(dir.resolve("factsheet-9.kgraph"));

        service.exportAllGraphs(projectRoot);

        assertFalse(Files.exists(dir.resolve("factsheet-" + DELETED_PORTABLE_ID + ".kgraph")));
        assertFalse(Files.exists(dir.resolve("factsheet-9.kgraph")),
                "a scope named by a legacy database id is stale too");
        assertTrue(Files.exists(dir.resolve("factsheet-" + RESEARCH_PORTABLE_ID + ".kgraph")));
    }

    @Test
    void exportAllGraphs_propagatesScopeFailure() throws Exception {
        FactSheet research = sheet(1, RESEARCH_PORTABLE_ID, "Research");
        when(factSheetService.getAllSheets()).thenReturn(List.of(research));
        publishCatalog(research);
        when(unifiedGraphBridge.export(1L)).thenThrow(new IllegalStateException("export failed"));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> service.exportAllGraphs(projectRoot));

        assertTrue(failure.getMessage().contains("native project graphs"));
        assertEquals("export failed", failure.getCause().getMessage());
        assertFalse(Files.exists(projectRoot.resolve("data/graph/project.kgraph")));
    }

    @Test
    void exportAllGraphs_refusesScopesMissingFromThePortableCatalog() {
        FactSheet research = sheet(1, RESEARCH_PORTABLE_ID, "Research");
        when(factSheetService.getAllSheets()).thenReturn(List.of(research));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> service.exportAllGraphs(projectRoot));

        assertTrue(failure.getCause().getMessage().contains("Publish the portable fact-sheet catalog"));
        verify(unifiedGraphBridge, never()).export(any());
    }

    @Test
    void importAllGraphs_skipsWhenEveryScopeIsPopulated() throws Exception {
        Path dir = Files.createDirectories(projectRoot.resolve("data/graph"));
        new UnifiedGraph().factSheetId(1L).addEntity("e1", "ENTITY", "one")
                .save(dir.resolve("factsheet-1.kgraph"));
        when(knowledgeGraphService.getNodesInFactSheet(1L)).thenReturn(List.of(mock(GraphNode.class)));

        service.importAllGraphs(projectRoot);

        verify(unifiedGraphBridge, never()).importGraphs(anyList());
    }

    @Test
    void importAllGraphs_importsOnlyTheScopesThatAreStillEmpty() throws Exception {
        Path dir = Files.createDirectories(projectRoot.resolve("data/graph"));
        new UnifiedGraph().factSheetId(1L).addEntity("e1", "ENTITY", "one")
                .save(dir.resolve("factsheet-1.kgraph"));
        new UnifiedGraph().factSheetId(2L).addEntity("e2", "ENTITY", "two")
                .save(dir.resolve("factsheet-2.kgraph"));
        when(knowledgeGraphService.getNodesInFactSheet(1L)).thenReturn(List.of(mock(GraphNode.class)));
        when(unifiedGraphBridge.importGraphs(anyList())).thenReturn(oneImportedScope());

        service.importAllGraphs(projectRoot);

        verify(unifiedGraphBridge).importGraphs(argThat(scopes -> scopes.size() == 1
                && Long.valueOf(2L).equals(scopes.get(0).factSheetId())));
    }

    @Test
    void importAllGraphs_preflightsEveryNativeScopeBeforeMutation() throws Exception {
        Path dir = Files.createDirectories(projectRoot.resolve("data/graph"));
        new UnifiedGraph().factSheetId(1L).addEntity("e1", "ENTITY", "one")
                .save(dir.resolve("factsheet-1.kgraph"));
        Files.writeString(dir.resolve("factsheet-2.kgraph"), "not a graph");

        assertThrows(IllegalStateException.class, () -> service.importAllGraphs(projectRoot));

        verify(unifiedGraphBridge, never()).importGraphs(anyList());
    }

    @Test
    void importAllGraphs_importsLegacyIdScopesWithoutADestinationMapper() throws Exception {
        Path dir = Files.createDirectories(projectRoot.resolve("data/graph"));
        new UnifiedGraph().factSheetId(7L).addEntity("e7", "ENTITY", "Entity 7")
                .save(dir.resolve("factsheet-7.kgraph"));
        when(unifiedGraphBridge.importGraphs(anyList())).thenReturn(oneImportedScope());

        service.importAllGraphs(projectRoot);

        verify(unifiedGraphBridge).importGraphs(argThat(scopes -> scopes.size() == 1
                && Long.valueOf(7L).equals(scopes.get(0).factSheetId())
                && scopes.get(0).graph().entities().size() == 1));
    }

    @Test
    void importAllGraphs_refusesAnUnscopedProjectArchive() throws Exception {
        Path dir = Files.createDirectories(projectRoot.resolve("data/graph"));
        new UnifiedGraph().graphId("project").addEntity("e", "ENTITY", "Entity")
                .save(dir.resolve("project.kgraph"));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> service.importAllGraphs(projectRoot));

        assertTrue(failure.getCause().getMessage().contains("Unscoped project.kgraph"));
        verify(unifiedGraphBridge, never()).importGraphs(anyList());
    }

    @Test
    void exportedScopeRehydratesIntoTheRuntimeSheetWithTheSamePortableIdentity() throws Exception {
        FactSheet research = sheet(1, RESEARCH_PORTABLE_ID, "Research");
        when(factSheetService.getAllSheets()).thenReturn(List.of(research));
        publishCatalog(research);
        when(unifiedGraphBridge.export(1L)).thenReturn(
                new UnifiedGraph().factSheetId(1L).addEntity("e1", "ENTITY", "Entity 1"));
        when(unifiedGraphBridge.export(isNull())).thenReturn(
                new UnifiedGraph().graphId("project").addEntity("e1", "ENTITY", "Entity 1"));
        service.exportAllGraphs(projectRoot);

        // A clone restores the sheet under a new database id; the portable identity is what binds the scope.
        FactSheet restored = sheet(11, RESEARCH_PORTABLE_ID, "Research");
        when(factSheetService.getSheetByPortableId(RESEARCH_PORTABLE_ID)).thenReturn(Optional.of(restored));
        when(factSheetService.getSheetByName("Research")).thenReturn(Optional.of(restored));
        when(unifiedGraphBridge.importGraphs(anyList())).thenReturn(oneImportedScope());
        service.destinationMapper = new ProjectGraphDestinationMapper(factSheetService);

        service.importAllGraphs(projectRoot);

        verify(unifiedGraphBridge).importGraphs(argThat(scopes -> scopes.size() == 1
                && Long.valueOf(11L).equals(scopes.get(0).factSheetId())
                && scopes.get(0).graph().entities().size() == 1));
    }
}
