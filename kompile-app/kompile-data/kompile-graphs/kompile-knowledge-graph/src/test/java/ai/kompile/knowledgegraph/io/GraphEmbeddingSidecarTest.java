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
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.embedding.util.INDArrayConverter;
import ai.kompile.knowledgegraph.repository.GraphEdgeRepository;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests {@link GraphEmbeddingSidecar} on both embedding families:
 * <ul>
 *   <li><b>JPA structural KGE</b> (node + relation) binary framing, using a mocked
 *       {@link INDArrayConverter} so no live ND4J backend is required.</li>
 *   <li><b>Live-store (matrix/vector) node vectors</b> — the path that makes the vector store's
 *       embeddings travel on clone — using a real converter + {@link KnowledgeGraphService}
 *       seam and asserting round-trip fidelity + re-application to the @Primary store.</li>
 * </ul>
 */
class GraphEmbeddingSidecarTest {

    private final GraphNodeRepository nodeRepo = mock(GraphNodeRepository.class);
    private final GraphEdgeRepository edgeRepo = mock(GraphEdgeRepository.class);
    private final INDArrayConverter converter = mock(INDArrayConverter.class);
    private final GraphEmbeddingSidecar sidecar = new GraphEmbeddingSidecar(nodeRepo, edgeRepo, converter);

    // ── JPA structural-KGE path (mocked converter, no backend) ──────────────────────────────

    @Test
    void exportThenImport_roundtripsNodeKeysAlgorithmAndVersion() {
        INDArray vec = mock(INDArray.class);
        byte[] embBytes = {9, 8, 7, 6};
        KGEmbeddingAlgorithm algo = KGEmbeddingAlgorithm.values()[0];

        java.time.Instant updatedAt = java.time.Instant.ofEpochMilli(1_700_000_000_000L);
        GraphNode source = GraphNode.builder()
                .nodeId("n1").externalId("e1").nodeType(NodeLevel.ENTITY).title("Acme")
                .factSheetId(42L).kgEmbedding(vec).kgEmbeddingAlgorithm(algo).kgEmbeddingVersion(7L)
                .kgEmbeddingUpdatedAt(updatedAt)
                .build();
        when(nodeRepo.findByFactSheetIdAndKgEmbeddingNotNull(42L)).thenReturn(List.of(source));
        when(edgeRepo.findByFactSheetIdAndKgRelationEmbeddingNotNull(42L)).thenReturn(List.of());
        when(converter.convertToDatabaseColumn(vec)).thenReturn(embBytes);

        byte[] data = sidecar.export(42L);
        assertNotNull(data);

        GraphNode target = GraphNode.builder()
                .nodeId("n1-new").externalId("e1").nodeType(NodeLevel.ENTITY).title("Acme")
                .factSheetId(42L).build();
        INDArray restored = mock(INDArray.class);
        when(nodeRepo.findByExternalIdAndNodeTypeAndFactSheetId("e1", NodeLevel.ENTITY, 42L))
                .thenReturn(Optional.of(target));
        when(converter.convertToEntityAttribute(embBytes)).thenReturn(restored);

        int applied = sidecar.importInto(42L, data);

        assertEquals(1, applied);
        assertSame(restored, target.getKgEmbedding());
        assertEquals(algo, target.getKgEmbeddingAlgorithm());
        assertEquals(Long.valueOf(7L), target.getKgEmbeddingVersion());
        assertEquals(updatedAt, target.getKgEmbeddingUpdatedAt(), "[H-5] kgEmbeddingUpdatedAt must round-trip");
        verify(nodeRepo).save(target);
    }

    @Test
    void relationEmbeddings_roundtripByType_appliedToAllEdgesOfType() {
        EdgeType type = EdgeType.values()[0];
        INDArray relVec = mock(INDArray.class);
        byte[] relBytes = {5, 5, 5};

        GraphEdge sourceEdge = GraphEdge.builder()
                .edgeId("ed1").edgeType(type).weight(1.0).factSheetId(9L)
                .kgRelationEmbedding(relVec).kgEmbeddingVersion(3L).build();
        when(nodeRepo.findByFactSheetIdAndKgEmbeddingNotNull(9L)).thenReturn(List.of());
        when(edgeRepo.findByFactSheetIdAndKgRelationEmbeddingNotNull(9L)).thenReturn(List.of(sourceEdge));
        when(converter.convertToDatabaseColumn(relVec)).thenReturn(relBytes);

        byte[] data = sidecar.export(9L);
        assertNotNull(data);

        GraphEdge e1 = GraphEdge.builder().edgeId("x1").edgeType(type).weight(1.0).factSheetId(9L).build();
        GraphEdge e2 = GraphEdge.builder().edgeId("x2").edgeType(type).weight(1.0).factSheetId(9L).build();
        INDArray restored = mock(INDArray.class);
        when(edgeRepo.findByFactSheetIdAndEdgeType(9L, type)).thenReturn(List.of(e1, e2));
        when(converter.convertToEntityAttribute(relBytes)).thenReturn(restored);

        int applied = sidecar.importInto(9L, data);

        assertEquals(2, applied, "relation embedding applied to every edge of the type");
        assertSame(restored, e1.getKgRelationEmbedding());
        assertSame(restored, e2.getKgRelationEmbedding());
        assertEquals(Long.valueOf(3L), e1.getKgEmbeddingVersion());
        verify(edgeRepo).save(e1);
        verify(edgeRepo).save(e2);
    }

    @Test
    void export_noEmbeddings_returnsNull() {
        when(nodeRepo.findByFactSheetIdAndKgEmbeddingNotNull(1L)).thenReturn(List.of());
        when(edgeRepo.findByFactSheetIdAndKgRelationEmbeddingNotNull(1L)).thenReturn(List.of());
        assertNull(sidecar.export(1L));
    }

    @Test
    void importInto_missingNode_skipsGracefully() {
        INDArray vec = mock(INDArray.class);
        GraphNode source = GraphNode.builder()
                .nodeId("n1").externalId("gone").nodeType(NodeLevel.ENTITY).title("X")
                .factSheetId(5L).kgEmbedding(vec).build();
        when(nodeRepo.findByFactSheetIdAndKgEmbeddingNotNull(5L)).thenReturn(List.of(source));
        when(edgeRepo.findByFactSheetIdAndKgRelationEmbeddingNotNull(5L)).thenReturn(List.of());
        when(converter.convertToDatabaseColumn(vec)).thenReturn(new byte[]{1});
        byte[] data = sidecar.export(5L);

        when(nodeRepo.findByExternalIdAndNodeTypeAndFactSheetId(eq("gone"), any(), eq(5L)))
                .thenReturn(Optional.empty());

        assertEquals(0, sidecar.importInto(5L, data));
        verify(nodeRepo, never()).save(any());
    }

    // ── Live-store (matrix/vector) path — real converter, real backend ──────────────────────

    @Test
    void liveStoreNodeEmbeddingsRoundTripAndReApplyToTheVectorStore() {
        GraphNodeRepository nodeRepo2 = mock(GraphNodeRepository.class);
        GraphEdgeRepository edgeRepo2 = mock(GraphEdgeRepository.class);
        KnowledgeGraphService graphService = mock(KnowledgeGraphService.class);
        GraphEmbeddingSidecar sc =
                new GraphEmbeddingSidecar(nodeRepo2, edgeRepo2, new INDArrayConverter(), graphService);

        // No JPA structural KGE — only the live (matrix/vector) store holds vectors.
        when(nodeRepo2.findByFactSheetIdAndKgEmbeddingNotNull(1L)).thenReturn(List.of());
        when(edgeRepo2.findByFactSheetIdAndKgRelationEmbeddingNotNull(1L)).thenReturn(List.of());

        INDArray vec = Nd4j.create(new float[]{0.1f, 0.2f, 0.3f, 0.4f}, new long[]{4});
        when(graphService.exportNodeEmbeddings(1L)).thenReturn(Map.of("node-uuid-1", vec));
        GraphNode original = GraphNode.builder()
                .nodeId("node-uuid-1").externalId("ext-1").nodeType(NodeLevel.ENTITY).build();
        when(graphService.getNodesInFactSheet(1L)).thenReturn(List.of(original));

        byte[] bytes = sc.export(1L);
        assertNotNull(bytes, "live-store embeddings must produce a sidecar");

        // On import the node UUID has regenerated; the sidecar re-resolves by (type, externalId).
        GraphNode rehydrated = GraphNode.builder()
                .nodeId("node-uuid-NEW").externalId("ext-1").nodeType(NodeLevel.ENTITY).build();
        when(graphService.getNodeByExternalIdInFactSheet("ext-1", NodeLevel.ENTITY, 1L))
                .thenReturn(Optional.of(rehydrated));
        when(graphService.applyNodeEmbeddings(any())).thenReturn(1);

        int applied = sc.importInto(1L, bytes);
        assertEquals(1, applied);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, INDArray>> captor = ArgumentCaptor.forClass(Map.class);
        verify(graphService).applyNodeEmbeddings(captor.capture());
        Map<String, INDArray> applyArg = captor.getValue();
        assertTrue(applyArg.containsKey("node-uuid-NEW"), "embedding must be keyed by the regenerated nodeId");
        assertArrayEquals(vec.toFloatVector(), applyArg.get("node-uuid-NEW").toFloatVector(), 1e-6f,
                "vector must survive the serialize/deserialize round-trip");
    }

    @Test
    void export_liveStoreEmpty_andNoJpa_returnsNull() {
        KnowledgeGraphService graphService = mock(KnowledgeGraphService.class);
        GraphEmbeddingSidecar sc =
                new GraphEmbeddingSidecar(nodeRepo, edgeRepo, converter, graphService);
        when(nodeRepo.findByFactSheetIdAndKgEmbeddingNotNull(1L)).thenReturn(List.of());
        when(edgeRepo.findByFactSheetIdAndKgRelationEmbeddingNotNull(1L)).thenReturn(List.of());
        when(graphService.exportNodeEmbeddings(1L)).thenReturn(Map.of());

        assertNull(sc.export(1L));
    }

    @Test
    void legacyKge1FileWithoutLiveSectionStillReads() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bos)) {
            out.writeInt(0x4B474531); // KGE1 magic
            out.writeInt(0);          // JPA node count
            out.writeInt(0);          // edge-type count
            // no live section in the legacy format
        }

        // 3-arg sidecar (no live store wired) must read a legacy file without error.
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
            out.writeLong(-1L);       // no version  (NOTE: no updatedAt slot in KGE1)
            out.writeInt(embBytes.length);
            out.write(embBytes);
            out.writeInt(0);          // 0 edge types
        }

        GraphNode target = GraphNode.builder()
                .nodeId("n1").externalId("e1").nodeType(NodeLevel.ENTITY).build();
        INDArray restored = mock(INDArray.class);
        when(nodeRepo.findByExternalIdAndNodeTypeAndFactSheetId("e1", NodeLevel.ENTITY, 5L))
                .thenReturn(Optional.of(target));
        when(converter.convertToEntityAttribute(embBytes)).thenReturn(restored);

        int applied = sidecar.importInto(5L, bos.toByteArray());

        assertEquals(1, applied);
        assertSame(restored, target.getKgEmbedding());
        assertNull(target.getKgEmbeddingUpdatedAt(), "KGE1 carries no timestamp; it must stay null");
    }
}
