/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.tool.graph;

import ai.kompile.graph.algorithms.service.GraphAlgorithmService;
import ai.kompile.knowledgegraph.reasoning.GraphToFactStoreProjector;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.knowledgegraph.unified.GraphSnapshotService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the snapshot @Tool methods added to {@link GraphMutationTool}.
 */
@ExtendWith(MockitoExtension.class)
class GraphMutationToolSnapshotTest {

    @Mock KnowledgeGraphService graphService;
    @Mock GraphAlgorithmService algorithmService;
    @Mock GraphToFactStoreProjector factStoreProjector;
    @Mock GraphSnapshotService snapshotService;

    private GraphMutationTool tool;

    @BeforeEach
    void setUp() {
        tool = new GraphMutationTool(graphService, algorithmService, factStoreProjector, null);
        tool.setSnapshotService(snapshotService);
    }

    // ─── graph_snapshot_create ────────────────────────────────────────────────

    @Test
    void snapshotCreate_missingFactSheetId_returnsError() {
        var result = tool.snapshotCreate(new GraphMutationTool.SnapshotCreateInput(null, null));
        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("factSheetId"));
    }

    @Test
    void snapshotCreate_featureDisabled_returnsError() {
        when(snapshotService.isEnabled()).thenReturn(false);
        var result = tool.snapshotCreate(new GraphMutationTool.SnapshotCreateInput(1L, null));
        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("disabled"));
    }

    @Test
    void snapshotCreate_success_returnsMeta() throws IOException {
        when(snapshotService.isEnabled()).thenReturn(true);
        var meta = new GraphSnapshotService.SnapshotMetadata(
                "20260709T120000Z_my-label.kgraph", 1L, "my label", Instant.now(), 2048L);
        when(snapshotService.createSnapshot(1L, "my-label")).thenReturn(meta);

        var result = tool.snapshotCreate(new GraphMutationTool.SnapshotCreateInput(1L, "my-label"));
        assertFalse(result.containsKey("error"));
        assertEquals("20260709T120000Z_my-label.kgraph", result.get("snapshotId"));
        assertEquals(1L, result.get("factSheetId"));
        assertEquals(2048L, result.get("sizeBytes"));
    }

    @Test
    void snapshotCreate_ioException_returnsError() throws IOException {
        when(snapshotService.isEnabled()).thenReturn(true);
        when(snapshotService.createSnapshot(anyLong(), any())).thenThrow(new IOException("disk full"));

        var result = tool.snapshotCreate(new GraphMutationTool.SnapshotCreateInput(1L, null));
        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("disk full"));
    }

    @Test
    void snapshotCreate_nullSnapshotService_returnsError() {
        tool.setSnapshotService(null);
        var result = tool.snapshotCreate(new GraphMutationTool.SnapshotCreateInput(1L, null));
        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("not available"));
    }

    // ─── graph_snapshot_list ─────────────────────────────────────────────────

    @Test
    void snapshotList_missingFactSheetId_returnsError() {
        var result = tool.snapshotList(new GraphMutationTool.SnapshotInput(null, null));
        assertTrue(result.containsKey("error"));
    }

    @Test
    void snapshotList_success_returnsItems() throws IOException {
        when(snapshotService.isEnabled()).thenReturn(true);
        var metas = List.of(
                new GraphSnapshotService.SnapshotMetadata("20260709T120000Z_b.kgraph", 2L, "b", Instant.now(), 1024L),
                new GraphSnapshotService.SnapshotMetadata("20260709T110000Z_a.kgraph", 2L, "a", Instant.now(), 512L));
        when(snapshotService.listSnapshots(2L)).thenReturn(metas);

        var result = tool.snapshotList(new GraphMutationTool.SnapshotInput(2L, null));
        assertFalse(result.containsKey("error"));
        assertEquals(2, result.get("count"));
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) result.get("snapshots");
        assertEquals(2, items.size());
        assertEquals("20260709T120000Z_b.kgraph", items.get(0).get("snapshotId"));
    }

    // ─── graph_snapshot_restore ──────────────────────────────────────────────

    @Test
    void snapshotRestore_missingFactSheetId_returnsError() {
        var result = tool.snapshotRestore(new GraphMutationTool.SnapshotInput(null, "snap.kgraph"));
        assertTrue(result.containsKey("error"));
    }

    @Test
    void snapshotRestore_missingSnapshotId_returnsError() {
        var result = tool.snapshotRestore(new GraphMutationTool.SnapshotInput(1L, ""));
        assertTrue(result.containsKey("error"));
    }

    @Test
    void snapshotRestore_success_returnsImportSummary() throws IOException {
        when(snapshotService.isEnabled()).thenReturn(true);
        var importSummary = new ai.kompile.knowledgegraph.unified.UnifiedGraphBridge.ImportSummary(
                10, 5, 10, 20);
        var restoreResult = new GraphSnapshotService.RestoreResult(
                1L, "snap.kgraph", "pre-restore.kgraph", importSummary);
        when(snapshotService.restoreSnapshot(1L, "snap.kgraph")).thenReturn(restoreResult);

        var result = tool.snapshotRestore(new GraphMutationTool.SnapshotInput(1L, "snap.kgraph"));
        assertFalse(result.containsKey("error"));
        assertEquals(1L, result.get("factSheetId"));
        assertEquals("snap.kgraph", result.get("restoredSnapshotId"));
        assertEquals("pre-restore.kgraph", result.get("preRestoreSnapshotId"));
        assertEquals(10, result.get("nodes"));
        assertEquals(20, result.get("atoms"));
    }

    @Test
    void snapshotRestore_illegalArgument_returnsError() throws IOException {
        when(snapshotService.isEnabled()).thenReturn(true);
        when(snapshotService.restoreSnapshot(anyLong(), anyString()))
                .thenThrow(new IllegalArgumentException("Snapshot not found"));

        var result = tool.snapshotRestore(new GraphMutationTool.SnapshotInput(1L, "missing.kgraph"));
        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("Snapshot not found"));
    }

    // ─── graph_snapshot_delete ───────────────────────────────────────────────

    @Test
    void snapshotDelete_missingFactSheetId_returnsError() {
        var result = tool.snapshotDelete(new GraphMutationTool.SnapshotInput(null, "snap.kgraph"));
        assertTrue(result.containsKey("error"));
    }

    @Test
    void snapshotDelete_notFound_returnsError() throws IOException {
        when(snapshotService.isEnabled()).thenReturn(true);
        when(snapshotService.deleteSnapshot(1L, "missing.kgraph")).thenReturn(false);

        var result = tool.snapshotDelete(new GraphMutationTool.SnapshotInput(1L, "missing.kgraph"));
        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("not found"));
    }

    @Test
    void snapshotDelete_success_returnsDeleted() throws IOException {
        when(snapshotService.isEnabled()).thenReturn(true);
        when(snapshotService.deleteSnapshot(1L, "snap.kgraph")).thenReturn(true);

        var result = tool.snapshotDelete(new GraphMutationTool.SnapshotInput(1L, "snap.kgraph"));
        assertFalse(result.containsKey("error"));
        assertEquals(true, result.get("deleted"));
        assertEquals("snap.kgraph", result.get("snapshotId"));
    }
}
