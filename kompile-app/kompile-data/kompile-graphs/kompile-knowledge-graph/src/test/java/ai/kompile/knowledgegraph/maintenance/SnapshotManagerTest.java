/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.knowledgegraph.maintenance;

import ai.kompile.core.graphrag.maintenance.model.GraphSnapshot;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.io.GraphIOService;
import ai.kompile.knowledgegraph.io.model.ImportResult;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.knowledgegraph.unified.UnifiedGraphBridge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies snapshots use one native, property-complete .kgraph archive.
 */
class SnapshotManagerTest {

    private final UnifiedGraphBridge bridge = mock(UnifiedGraphBridge.class);
    private SnapshotManager manager;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        manager = new SnapshotManager(bridge);
        Field dataDir = SnapshotManager.class.getDeclaredField("dataDir");
        dataDir.setAccessible(true);
        dataDir.set(manager, tempDir.toString());
    }

    private UnifiedGraph graph() {
        return new UnifiedGraph()
                .graphId("factsheet_42")
                .factSheetId(42L)
                .addEntity("node-1", "Person", "Alice")
                .addEntity("node-2", "Document", "Source")
                .addRelation("edge-1", "node-1", "node-2", "MENTIONS", 0.8);
    }

    @Test
    void createSnapshot_writesSingleNativeArchive() throws Exception {
        when(bridge.export(42L)).thenReturn(graph());

        GraphSnapshot snapshot = manager.createSnapshot(42L, "before maintenance");

        Path directory = tempDir.resolve("graph-snapshots/factsheet-42");
        assertTrue(Files.exists(directory.resolve(snapshot.snapshotId() + ".kgraph")));
        assertEquals("kgraph", snapshot.exportFormat());
        assertEquals(2, snapshot.entityCount());
        assertEquals(1, snapshot.relationshipCount());
        assertEquals("before maintenance", snapshot.reason());
        assertEquals(1, directory.toFile().listFiles().length);
        assertTrue(Files.list(directory).noneMatch(path -> path.toString().endsWith(".json")));
    }

    @Test
    void listSnapshots_readsNativeMetadata_newestFirst() {
        when(bridge.export(42L)).thenReturn(graph());

        GraphSnapshot first = manager.createSnapshot(42L, "first");
        GraphSnapshot second = manager.createSnapshot(42L, "second");

        List<GraphSnapshot> snapshots = manager.listSnapshots(42L);

        assertEquals(2, snapshots.size());
        assertEquals("kgraph", snapshots.get(0).exportFormat());
        assertTrue(snapshots.stream().map(GraphSnapshot::snapshotId)
                .toList().containsAll(List.of(first.snapshotId(), second.snapshotId())));
    }

    @Test
    void restoreSnapshot_reimportsNativeArchive() {
        UnifiedGraph source = graph();
        when(bridge.export(42L)).thenReturn(source);
        when(bridge.importGraph(any(UnifiedGraph.class), eq(42L)))
                .thenReturn(new UnifiedGraphBridge.ImportSummary(2, 1, 0, 0, false));

        GraphSnapshot snapshot = manager.createSnapshot(42L, "restore me");
        GraphSnapshot restored = manager.restoreSnapshot(snapshot.snapshotId());

        assertEquals(snapshot.snapshotId(), restored.snapshotId());
        assertEquals("kgraph", restored.exportFormat());
        verify(bridge).importGraph(any(UnifiedGraph.class), eq(42L));
    }

    @Test
    void restoreSnapshot_unknownId_throws() {
        assertThrows(IllegalArgumentException.class, () -> manager.restoreSnapshot("does-not-exist"));
    }

    @Test
    void legacyTripletRemainsDiscoverableAndMigratesAlongsideOriginals() throws Exception {
        GraphIOService graphIOService = mock(GraphIOService.class);
        KnowledgeGraphService graphService = mock(KnowledgeGraphService.class);
        setField("graphIOService", graphIOService);
        setField("knowledgeGraphService", graphService);
        when(bridge.export(42L)).thenReturn(graph());
        when(graphIOService.importGraph(eq("json"), any(byte[].class), isNull(), eq(42L)))
                .thenReturn(new ImportResult("json", 2, 0, 1, 0, List.of()));

        Path directory = tempDir.resolve("data/graph/snapshots/42");
        Files.createDirectories(directory);
        String snapshotId = "legacy-snapshot";
        Files.writeString(directory.resolve(snapshotId + ".json"), """
                {"snapshotId":"legacy-snapshot","factSheetId":42,
                 "createdAt":"2026-08-01T00:00:00Z","reason":"legacy",
                 "entityCount":2,"relationshipCount":1,"communityCount":0,
                 "exportFormat":"json-full"}
                """);
        Files.writeString(directory.resolve(snapshotId + ".graph.json"), "{}");

        assertEquals(List.of(snapshotId), manager.listSnapshots(42L).stream()
                .map(GraphSnapshot::snapshotId).toList());

        GraphSnapshot restored = manager.restoreSnapshot(snapshotId);

        assertEquals("kgraph", restored.exportFormat());
        assertTrue(Files.isRegularFile(directory.resolve(snapshotId + ".json")));
        assertTrue(Files.isRegularFile(directory.resolve(snapshotId + ".graph.json")));
        assertTrue(Files.isRegularFile(directory.resolve(snapshotId + ".kgraph")));
        verify(graphService).deleteByFactSheetId(42L);
        verify(graphIOService).importGraph(eq("json"), any(byte[].class), isNull(), eq(42L));
    }

    private void setField(String name, Object value) throws Exception {
        Field field = SnapshotManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(manager, value);
    }
}
