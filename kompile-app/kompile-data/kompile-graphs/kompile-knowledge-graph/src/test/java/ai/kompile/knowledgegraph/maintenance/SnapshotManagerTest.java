/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.knowledgegraph.maintenance;

import ai.kompile.core.graphrag.maintenance.model.GraphSnapshot;
import ai.kompile.knowledgegraph.io.GraphIOService;
import ai.kompile.knowledgegraph.io.model.ExportResult;
import ai.kompile.knowledgegraph.io.model.ImportResult;
import ai.kompile.knowledgegraph.repository.GraphEdgeRepository;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests that snapshots carry a restorable full dump and that restore clears + re-imports it.
 */
class SnapshotManagerTest {

    private final GraphNodeRepository nodeRepo = mock(GraphNodeRepository.class);
    private final GraphEdgeRepository edgeRepo = mock(GraphEdgeRepository.class);
    private final GraphIOService graphIOService = mock(GraphIOService.class);
    private final KnowledgeGraphService knowledgeGraphService = mock(KnowledgeGraphService.class);
    private SnapshotManager manager;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        manager = new SnapshotManager(nodeRepo, edgeRepo, new ObjectMapper(), graphIOService, knowledgeGraphService);
        // Point snapshot storage at the temp dir (the @Value dataDir field).
        Field f = SnapshotManager.class.getDeclaredField("dataDir");
        f.setAccessible(true);
        f.set(manager, tempDir.toString());
        when(nodeRepo.findActiveEntities(anyLong())).thenReturn(List.of());
        when(nodeRepo.countActiveNodes(anyLong())).thenReturn(0L);
        when(edgeRepo.countActiveEdges(anyLong())).thenReturn(0L);
    }

    private ExportResult dump() {
        return new ExportResult("json", 2, 1,
                "{\"nodes\":[],\"edges\":[]}".getBytes(StandardCharsets.UTF_8), "application/json", "graph.json");
    }

    @Test
    void createSnapshot_writesManifestAndFullDump() throws Exception {
        when(graphIOService.exportGraph("json", 42L)).thenReturn(dump());

        GraphSnapshot snap = manager.createSnapshot(42L, "test");

        Path dir = tempDir.resolve("data/graph/snapshots/42");
        assertTrue(Files.exists(dir.resolve(snap.snapshotId() + ".json")), "manifest written");
        assertTrue(Files.exists(dir.resolve(snap.snapshotId() + ".graph.json")), "full dump written");
        assertEquals("json-full", snap.exportFormat());
    }

    @Test
    void restoreSnapshot_clearsFactSheetAndReimportsDump() throws Exception {
        when(graphIOService.exportGraph("json", 42L)).thenReturn(dump());
        when(graphIOService.importGraph(eq("json"), any(), isNull()))
                .thenReturn(new ImportResult("json", 2, 0, 1, 0, List.of()));
        GraphSnapshot snap = manager.createSnapshot(42L, "before change");

        manager.restoreSnapshot(snap.snapshotId());

        verify(knowledgeGraphService).deleteByFactSheetId(42L);
        verify(graphIOService).importGraph(eq("json"), any(), isNull());
    }

    @Test
    void restoreSnapshot_unknownId_throws() {
        assertThrows(IllegalArgumentException.class, () -> manager.restoreSnapshot("does-not-exist"));
    }
}
