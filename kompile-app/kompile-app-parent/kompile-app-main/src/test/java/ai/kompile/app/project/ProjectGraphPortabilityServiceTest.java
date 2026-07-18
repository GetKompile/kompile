/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.app.project;

import ai.kompile.app.facts.domain.FactSheet;
import ai.kompile.app.facts.service.FactSheetService;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.io.GraphEmbeddingSidecar;
import ai.kompile.knowledgegraph.io.GraphIOService;
import ai.kompile.knowledgegraph.io.model.ExportResult;
import ai.kompile.knowledgegraph.io.model.ImportResult;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.knowledgegraph.unified.UnifiedGraphBridge;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ProjectGraphPortabilityService} — export layout (structure JSON +
 * embedding sidecars + named-graph registry), the empty-graph rehydrate guard, and
 * cross-file merge-before-import.
 */
class ProjectGraphPortabilityServiceTest {

    private GraphIOService graphIOService;
    private FactSheetService factSheetService;
    private KnowledgeGraphService knowledgeGraphService;
    private GraphEmbeddingSidecar embeddingSidecar;
    private UnifiedGraphBridge unifiedGraphBridge;
    private ProjectGraphPortabilityService service;

    @TempDir
    Path projectRoot;

    @BeforeEach
    void setUp() {
        graphIOService = mock(GraphIOService.class);
        factSheetService = mock(FactSheetService.class);
        knowledgeGraphService = mock(KnowledgeGraphService.class);
        embeddingSidecar = mock(GraphEmbeddingSidecar.class);
        unifiedGraphBridge = mock(UnifiedGraphBridge.class);
        service = new ProjectGraphPortabilityService();
        ReflectionTestUtils.setField(service, "graphIOService", graphIOService);
        ReflectionTestUtils.setField(service, "factSheetService", factSheetService);
        ReflectionTestUtils.setField(service, "knowledgeGraphService", knowledgeGraphService);
        ReflectionTestUtils.setField(service, "embeddingSidecar", embeddingSidecar);
        ReflectionTestUtils.setField(service, "unifiedGraphBridge", unifiedGraphBridge);
        ReflectionTestUtils.setField(service, "mapper", new ObjectMapper());
    }

    private FactSheet sheet(long id) {
        FactSheet fs = mock(FactSheet.class);
        when(fs.getId()).thenReturn(id);
        return fs;
    }

    private ExportResult export(int nodes, int edges) {
        return new ExportResult("json", nodes, edges,
                "{\"nodes\":[],\"edges\":[]}".getBytes(StandardCharsets.UTF_8),
                "application/json", "graph.json");
    }

    @Test
    void exportAllGraphs_writesStructureAndEmbeddings_skipsEmptyScopes() throws Exception {
        FactSheet s1 = sheet(1);
        FactSheet s2 = sheet(2);
        when(factSheetService.getAllSheets()).thenReturn(List.of(s1, s2));
        when(graphIOService.exportGraph("json", 1L)).thenReturn(export(2, 1));
        when(graphIOService.exportGraph("json", 2L)).thenReturn(export(0, 0));
        when(graphIOService.exportGlobalGraph("json")).thenReturn(export(1, 0));
        when(embeddingSidecar.export(1L)).thenReturn(new byte[]{1, 2, 3});
        when(unifiedGraphBridge.export(1L)).thenReturn(
                new UnifiedGraph().factSheetId(1L).addEntity("e1", "ENTITY", "Entity 1"));
        when(unifiedGraphBridge.export(2L)).thenReturn(new UnifiedGraph().factSheetId(2L));
        when(unifiedGraphBridge.export(isNull())).thenReturn(
                new UnifiedGraph().graphId("project").addEntity("e1", "ENTITY", "Entity 1"));

        service.exportAllGraphs(projectRoot);

        Path dir = projectRoot.resolve("data/graph");
        assertTrue(Files.exists(dir.resolve("factsheet-1.json")), "non-empty fact sheet should be written");
        assertFalse(Files.exists(dir.resolve("factsheet-2.json")), "empty fact sheet should be skipped");
        assertTrue(Files.exists(dir.resolve("global.json")), "global bucket should be written");
        assertTrue(Files.exists(dir.resolve("factsheet-1.kgraph")), "rich fact-sheet graph should be written");
        assertFalse(Files.exists(dir.resolve("factsheet-2.kgraph")), "empty rich graph should be skipped");
        assertTrue(Files.exists(dir.resolve("project.kgraph")), "complete local-chat graph should be written");
        assertEquals(1, UnifiedGraph.load(dir.resolve("project.kgraph")).entities().size());
        assertTrue(Files.exists(dir.resolve("embeddings/factsheet-1.bin")), "embedding sidecar should be written");
        assertFalse(Files.exists(dir.resolve("embeddings/factsheet-2.bin")), "no sidecar for embedding-less sheet");
        assertFalse(Files.exists(dir.resolve("named-graphs.json")), "obsolete named-graph registry must not be written");
    }

    @Test
    void exportAllGraphs_propagatesAnyScopeFailure() throws Exception {
        FactSheet s1 = sheet(1);
        when(factSheetService.getAllSheets()).thenReturn(List.of(s1));
        when(graphIOService.exportGraph("json", 1L)).thenThrow(new IllegalStateException("export failed"));

        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> service.exportAllGraphs(projectRoot));

        assertTrue(failure.getMessage().contains("portable project graphs"));
        verify(unifiedGraphBridge, never()).export(any());
    }

    @Test
    void importAllGraphs_skipsWhenGraphAlreadyPopulated() throws Exception {
        Path dir = Files.createDirectories(projectRoot.resolve("data/graph"));
        Files.writeString(dir.resolve("factsheet-1.json"), "{\"nodes\":[],\"edges\":[]}");
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 5));

        service.importAllGraphs(projectRoot);

        verify(graphIOService, never()).importGraph(anyString(), any(), any());
        verify(embeddingSidecar, never()).importInto(anyLong(), any());
    }

    @Test
    void importAllGraphs_mergesStructure_thenEmbeddings_whenEmpty() throws Exception {
        Path dir = Files.createDirectories(projectRoot.resolve("data/graph"));
        Files.writeString(dir.resolve("factsheet-1.json"),
                "{\"nodes\":[{\"externalId\":\"e1\",\"title\":\"A\",\"nodeType\":\"ENTITY\",\"factSheetId\":1}],\"edges\":[]}");
        Files.writeString(dir.resolve("global.json"),
                "{\"nodes\":[{\"externalId\":\"g1\",\"title\":\"G\",\"nodeType\":\"ENTITY\"}],\"edges\":[]}");
        Files.createDirectories(dir.resolve("embeddings"));
        Files.write(dir.resolve("embeddings/factsheet-1.bin"), new byte[]{1, 2, 3, 4});
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 0));
        when(graphIOService.importGraph(eq("json"), any(), isNull()))
                .thenReturn(new ImportResult("json", 2, 0, 0, 0, List.of()));

        service.importAllGraphs(projectRoot);

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(graphIOService).importGraph(eq("json"), payload.capture(), isNull());
        String merged = new String(payload.getValue(), StandardCharsets.UTF_8);
        assertTrue(merged.contains("e1"), "merged payload should carry the fact-sheet node");
        assertTrue(merged.contains("g1"), "merged payload should carry the global node");
        verify(embeddingSidecar).importInto(eq(1L), any());
    }

    @Test
    void importAllGraphs_prefersScopedKgraph_whenEmpty() throws Exception {
        Path dir = Files.createDirectories(projectRoot.resolve("data/graph"));
        Path scoped = dir.resolve("factsheet-7.kgraph");
        new UnifiedGraph().factSheetId(7L)
                .addEntity("e7", "ENTITY", "Entity 7")
                .save(scoped);
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 0));
        when(unifiedGraphBridge.importGraph(any(UnifiedGraph.class), eq(7L)))
                .thenReturn(new UnifiedGraphBridge.ImportSummary(1, 0, 0, 0));

        service.importAllGraphs(projectRoot);

        verify(unifiedGraphBridge).importGraph(any(UnifiedGraph.class), eq(7L));
        verify(graphIOService, never()).importGraph(eq("json"), any(), isNull());
        verify(embeddingSidecar, never()).importInto(anyLong(), any());
    }

    @Test
    void importAllGraphs_preflightsEveryScopedGraphBeforeMutation() throws Exception {
        Path dir = Files.createDirectories(projectRoot.resolve("data/graph"));
        new UnifiedGraph().factSheetId(1L).addEntity("e1", "ENTITY", "one")
                .save(dir.resolve("factsheet-1.kgraph"));
        Files.writeString(dir.resolve("factsheet-2.kgraph"), "not a graph");
        when(knowledgeGraphService.getGraphStatistics()).thenReturn(Map.of("totalNodes", 0));

        assertThrows(IllegalStateException.class, () -> service.importAllGraphs(projectRoot));

        verify(unifiedGraphBridge, never()).importGraph(any(), any());
        verify(graphIOService, never()).importGraph(anyString(), any(), any());
    }
}
