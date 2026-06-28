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
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.io.GraphEmbeddingSidecar;
import ai.kompile.knowledgegraph.io.GraphIOService;
import ai.kompile.knowledgegraph.io.model.ExportResult;
import ai.kompile.knowledgegraph.io.model.ImportResult;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests that snapshots carry a restorable full dump and that restore clears + re-imports it.
 */
class SnapshotManagerTest {

    private final GraphIOService graphIOService = mock(GraphIOService.class);
    private final KnowledgeGraphService knowledgeGraphService = mock(KnowledgeGraphService.class);
    private SnapshotManager manager;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        manager = new SnapshotManager(new ObjectMapper(), graphIOService, knowledgeGraphService);
        // Point snapshot storage at the temp dir (the @Value dataDir field).
        Field f = SnapshotManager.class.getDeclaredField("dataDir");
        f.setAccessible(true);
        f.set(manager, tempDir.toString());
        // H-6 fix: SnapshotManager now routes through KnowledgeGraphService (the @Primary live store)
        // rather than JPA repos (which are empty on the matrix path). Stub the service methods.
        when(knowledgeGraphService.getNodesInFactSheet(anyLong())).thenReturn(List.of());
        when(knowledgeGraphService.getEdgesInFactSheet(anyLong())).thenReturn(List.of());
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

    /**
     * H-6: createSnapshot must enumerate node IDs from the @Primary live store (via
     * KnowledgeGraphService.getNodesInFactSheet) — NOT from JPA repos which are always empty
     * on the matrix/vector path. The manifest nodeIds list and entityCount must reflect the
     * nodes returned by the service, not JPA.
     */
    @Test
    void createSnapshot_h6_usesLiveStoreNotJpa() throws Exception {
        // Arrange: service returns 2 live nodes; JPA repos remain un-stubbed (would throw if called)
        GraphNode n1 = GraphNode.builder().nodeId("node-1").build();
        GraphNode n2 = GraphNode.builder().nodeId("node-2").build();
        GraphEdge e1 = GraphEdge.builder().edgeId("edge-1").build();
        when(knowledgeGraphService.getNodesInFactSheet(99L)).thenReturn(List.of(n1, n2));
        when(knowledgeGraphService.getEdgesInFactSheet(99L)).thenReturn(List.of(e1));
        when(graphIOService.exportGraph("json", 99L)).thenReturn(
                new ExportResult("json", 2, 1,
                        "{\"nodes\":[],\"edges\":[]}".getBytes(StandardCharsets.UTF_8),
                        "application/json", "graph.json"));

        GraphSnapshot snap = manager.createSnapshot(99L, "h6-test");

        // Entity/edge counts reflect live store
        assertEquals(2, snap.entityCount(), "node count should come from live store");
        assertEquals(1, snap.relationshipCount(), "edge count should come from live store");

        // Manifest nodeIds list should contain the live node IDs
        Path manifestFile = tempDir.resolve("data/graph/snapshots/99/" + snap.snapshotId() + ".json");
        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = new ObjectMapper().readValue(manifestFile.toFile(), Map.class);
        @SuppressWarnings("unchecked")
        List<String> nodeIds = (List<String>) manifest.get("nodeIds");
        assertTrue(nodeIds.contains("node-1"), "manifest must list node-1 from live store");
        assertTrue(nodeIds.contains("node-2"), "manifest must list node-2 from live store");

        // JPA repos are no longer wired — the seam is the only data source (H-6 complete).
    }

    /**
     * [M-9] When an embedding sidecar is wired, createSnapshot must persist the KG embeddings next
     * to the structure dump, and restoreSnapshot must reattach them (instead of dropping them).
     */
    @Test
    void snapshot_persistsAndReattachesEmbeddings_m9() throws Exception {
        GraphEmbeddingSidecar sidecar = mock(GraphEmbeddingSidecar.class);
        Field sf = SnapshotManager.class.getDeclaredField("embeddingSidecar");
        sf.setAccessible(true);
        sf.set(manager, sidecar);

        byte[] embBytes = {1, 2, 3, 4};
        when(graphIOService.exportGraph("json", 42L)).thenReturn(dump());
        when(sidecar.export(42L)).thenReturn(embBytes);
        when(graphIOService.importGraph(eq("json"), any(), isNull()))
                .thenReturn(new ImportResult("json", 2, 0, 1, 0, List.of()));
        when(sidecar.importInto(eq(42L), any())).thenReturn(3);

        GraphSnapshot snap = manager.createSnapshot(42L, "with embeddings");

        Path embFile = tempDir.resolve("data/graph/snapshots/42/" + snap.snapshotId() + ".embeddings.bin");
        assertTrue(Files.exists(embFile), "[M-9] snapshot must persist the embedding sidecar");
        assertArrayEquals(embBytes, Files.readAllBytes(embFile));

        manager.restoreSnapshot(snap.snapshotId());

        verify(sidecar).importInto(eq(42L), any());
    }
}
