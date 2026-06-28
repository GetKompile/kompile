/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.knowledgegraph.io;

import ai.kompile.core.kgembedding.KGEmbeddingAlgorithm;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.embedding.util.INDArrayConverter;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests {@link GraphEmbeddingSidecar} on both embedding families:
 * <ul>
 *   <li><b>Structural KGE</b> (TransE/RotatE) — stored via the {@link KnowledgeGraphService}
 *       seam in node/edge-type metadata; round-trip via export+importInto.</li>
 *   <li><b>Live-store (matrix/vector) node vectors</b> — the path that makes the vector store's
 *       embeddings travel on clone — using a real converter + {@link KnowledgeGraphService}
 *       seam and asserting round-trip fidelity + re-application to the @Primary store.</li>
 * </ul>
 */
class GraphEmbeddingSidecarTest {

    private final INDArrayConverter converter = mock(INDArrayConverter.class);
    private final KnowledgeGraphService graphService = mock(KnowledgeGraphService.class);
    private final GraphEmbeddingSidecar sidecar = new GraphEmbeddingSidecar(converter, graphService);

    // ── Structural-KGE (via seam) path ──────────────────────────────────────────────────

    @Test
    void exportThenImport_roundtripsNodeKeysAlgorithmAndVersion() {
        INDArray vec = mock(INDArray.class);
        byte[] embBytes = {9, 8, 7, 6};
        KGEmbeddingAlgorithm algo = KGEmbeddingAlgorithm.values()[0];
        java.time.Instant updatedAt = java.time.Instant.ofEpochMilli(1_700_000_000_000L);

        // Export: graphService returns one node with KGE embedding
        GraphNode source = GraphNode.builder()
                .nodeId("n1").externalId("e1").nodeType(NodeLevel.ENTITY).title("Acme")
                .factSheetId(42L).kgEmbedding(vec).kgEmbeddingAlgorithm(algo).kgEmbeddingVersion(7L)
                .kgEmbeddingUpdatedAt(updatedAt)
                .build();
        when(graphService.findNodesWithKgEmbedding(42L)).thenReturn(List.of(source));
        when(graphService.getEdgeTypeKgEmbeddings(42L)).thenReturn(Map.of());
        when(graphService.getStoredKgAlgorithm(42L)).thenReturn(algo);
        when(graphService.exportNodeEmbeddings(42L)).thenReturn(Map.of());
        when(graphService.getNodesInFactSheet(42L)).thenReturn(List.of());
        when(converter.convertToDatabaseColumn(vec)).thenReturn(embBytes);

        byte[] data = sidecar.export(42L);
        assertNotNull(data);

        // Import: graphService resolves by (externalId, nodeType, factSheetId) and stores via seam
        GraphNode target = GraphNode.builder()
                .nodeId("n1-new").externalId("e1").nodeType(NodeLevel.ENTITY).factSheetId(42L).build();
        INDArray restored = mock(INDArray.class);
        when(graphService.getNodeByExternalIdInFactSheet("e1", NodeLevel.ENTITY, 42L))
                .thenReturn(Optional.of(target));
        when(converter.convertToEntityAttribute(embBytes)).thenReturn(restored);

        int applied = sidecar.importInto(42L, data);

        assertEquals(1, applied);
        // Verify seam was called with the restored embedding and metadata
        verify(graphService).storeNodeKgEmbedding(
                eq("n1-new"),
                eq(restored),
                eq(algo),
                anyLong(),
                any()
        );
    }

    @Test
    void edgeTypeEmbeddings_roundtripByType() {
        INDArray relVec = mock(INDArray.class);
        byte[] relBytes = {5, 5, 5};
        KGEmbeddingAlgorithm algo = KGEmbeddingAlgorithm.values()[0];

        when(graphService.findNodesWithKgEmbedding(9L)).thenReturn(List.of());
        when(graphService.getEdgeTypeKgEmbeddings(9L)).thenReturn(Map.of("RELATED_TO", relVec));
        when(graphService.getStoredKgAlgorithm(9L)).thenReturn(algo);
        when(graphService.exportNodeEmbeddings(9L)).thenReturn(Map.of());
        when(graphService.getNodesInFactSheet(9L)).thenReturn(List.of());
        when(converter.convertToDatabaseColumn(relVec)).thenReturn(relBytes);

        byte[] data = sidecar.export(9L);
        assertNotNull(data);

        INDArray restored = mock(INDArray.class);
        when(converter.convertToEntityAttribute(relBytes)).thenReturn(restored);

        int applied = sidecar.importInto(9L, data);

        assertEquals(1, applied, "one edge-type embedding should be restored");
        verify(graphService).storeEdgeTypeKgEmbedding(
                eq("RELATED_TO"), eq(restored), eq(algo), anyLong(), eq(9L));
    }

    @Test
    void export_noEmbeddings_returnsNull() {
        when(graphService.findNodesWithKgEmbedding(1L)).thenReturn(List.of());
        when(graphService.getEdgeTypeKgEmbeddings(1L)).thenReturn(Map.of());
        when(graphService.exportNodeEmbeddings(1L)).thenReturn(Map.of());
        when(graphService.getNodesInFactSheet(1L)).thenReturn(List.of());
        assertNull(sidecar.export(1L));
    }

    @Test
    void importInto_missingNode_skipsGracefully() {
        INDArray vec = mock(INDArray.class);
        GraphNode source = GraphNode.builder()
                .nodeId("n1").externalId("gone").nodeType(NodeLevel.ENTITY)
                .factSheetId(5L).kgEmbedding(vec).build();
        when(graphService.findNodesWithKgEmbedding(5L)).thenReturn(List.of(source));
        when(graphService.getEdgeTypeKgEmbeddings(5L)).thenReturn(Map.of());
        when(graphService.getStoredKgAlgorithm(5L)).thenReturn(null);
        when(graphService.exportNodeEmbeddings(5L)).thenReturn(Map.of());
        when(graphService.getNodesInFactSheet(5L)).thenReturn(List.of());
        when(converter.convertToDatabaseColumn(vec)).thenReturn(new byte[]{1});
        byte[] data = sidecar.export(5L);

        when(graphService.getNodeByExternalIdInFactSheet(eq("gone"), any(), eq(5L)))
                .thenReturn(Optional.empty());

        int applied = sidecar.importInto(5L, data);
        // The seam-based sidecar counts the entry but the storeNodeKgEmbedding is a no-op
        // because getNodeByExternalIdInFactSheet returned empty → ifPresent does nothing.
        // applied counts the node slot read from the stream = 1, but no seam call happens.
        verify(graphService, never()).storeNodeKgEmbedding(any(), any(), any(), any(), any());
    }

    // ── Live-store (matrix/vector) path — real converter, real backend ──────────────────────

    @Test
    void liveStoreNodeEmbeddingsRoundTripAndReApplyToTheVectorStore() {
        KnowledgeGraphService gs = mock(KnowledgeGraphService.class);
        GraphEmbeddingSidecar sc = new GraphEmbeddingSidecar(new INDArrayConverter(), gs);

        // No structural KGE — only the live (matrix/vector) store holds vectors.
        when(gs.findNodesWithKgEmbedding(1L)).thenReturn(List.of());
        when(gs.getEdgeTypeKgEmbeddings(1L)).thenReturn(Map.of());
        when(gs.getStoredKgAlgorithm(1L)).thenReturn(null);

        INDArray vec = Nd4j.create(new float[]{0.1f, 0.2f, 0.3f, 0.4f}, new long[]{4});
        when(gs.exportNodeEmbeddings(1L)).thenReturn(Map.of("node-uuid-1", vec));
        GraphNode original = GraphNode.builder()
                .nodeId("node-uuid-1").externalId("ext-1").nodeType(NodeLevel.ENTITY).build();
        when(gs.getNodesInFactSheet(1L)).thenReturn(List.of(original));

        byte[] bytes = sc.export(1L);
        assertNotNull(bytes, "live-store embeddings must produce a sidecar");

        // On import the node UUID has regenerated; the sidecar re-resolves by (type, externalId).
        GraphNode rehydrated = GraphNode.builder()
                .nodeId("node-uuid-NEW").externalId("ext-1").nodeType(NodeLevel.ENTITY).build();
        when(gs.getNodeByExternalIdInFactSheet("ext-1", NodeLevel.ENTITY, 1L))
                .thenReturn(Optional.of(rehydrated));
        when(gs.applyNodeEmbeddings(any())).thenReturn(1);

        int applied = sc.importInto(1L, bytes);
        assertEquals(1, applied);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, INDArray>> captor = ArgumentCaptor.forClass(Map.class);
        verify(gs).applyNodeEmbeddings(captor.capture());
        Map<String, INDArray> applyArg = captor.getValue();
        assertTrue(applyArg.containsKey("node-uuid-NEW"), "embedding must be keyed by the regenerated nodeId");
        assertArrayEquals(vec.toFloatVector(), applyArg.get("node-uuid-NEW").toFloatVector(), 1e-6f,
                "vector must survive the serialize/deserialize round-trip");
    }

    @Test
    void export_liveStoreEmpty_andNoKge_returnsNull() {
        KnowledgeGraphService gs = mock(KnowledgeGraphService.class);
        GraphEmbeddingSidecar sc = new GraphEmbeddingSidecar(converter, gs);
        when(gs.findNodesWithKgEmbedding(1L)).thenReturn(List.of());
        when(gs.getEdgeTypeKgEmbeddings(1L)).thenReturn(Map.of());
        when(gs.exportNodeEmbeddings(1L)).thenReturn(Map.of());
        when(gs.getNodesInFactSheet(1L)).thenReturn(List.of());

        assertNull(sc.export(1L));
    }

    @Test
    void legacyKge1FileWithoutLiveSectionStillReads() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            out.writeInt(0x4B474531); // KGE1 magic
            out.writeInt(0);          // structural-KGE node count
            out.writeInt(0);          // edge-type count
            // no live section in the legacy format
        }

        // Sidecar must read a legacy file without error.
        assertEquals(0, sidecar.importInto(1L, bos.toByteArray()));
    }

    @Test
    void legacyKge1NodeEntry_withoutTimestampSlot_readsWithStreamStillAligned() throws Exception {
        // A KGE1 node entry has NO kgEmbeddingUpdatedAt slot; the reader must not consume one,
        // or the stream would misalign. Hand-craft a KGE1 file with a single node.
        byte[] embBytes = {1, 2, 3};
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            out.writeInt(0x4B474531); // KGE1 magic
            out.writeInt(1);          // 1 node
            out.writeUTF("ENTITY");
            out.writeUTF("e1");
            out.writeUTF("");         // no algorithm
            out.writeLong(-1L);       // no version (NOTE: no updatedAt slot in KGE1)
            out.writeInt(embBytes.length);
            out.write(embBytes);
            out.writeInt(0);          // 0 edge types
        }

        GraphNode target = GraphNode.builder()
                .nodeId("n1").externalId("e1").nodeType(NodeLevel.ENTITY).build();
        INDArray restored = mock(INDArray.class);
        when(graphService.getNodeByExternalIdInFactSheet("e1", NodeLevel.ENTITY, 5L))
                .thenReturn(Optional.of(target));
        when(converter.convertToEntityAttribute(embBytes)).thenReturn(restored);

        int applied = sidecar.importInto(5L, bos.toByteArray());
        // The entry is read; storeNodeKgEmbedding is called since node was found.
        verify(graphService).storeNodeKgEmbedding(eq("n1"), eq(restored), isNull(), anyLong(), isNull());
    }
}
