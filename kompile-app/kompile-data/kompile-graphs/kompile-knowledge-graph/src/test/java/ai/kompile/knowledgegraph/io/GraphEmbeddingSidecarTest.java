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
import org.junit.jupiter.api.Test;
import org.nd4j.linalg.api.ndarray.INDArray;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests {@link GraphEmbeddingSidecar} binary framing for node and relation embeddings
 * using a mocked {@link INDArrayConverter}, so no live ND4J backend is required.
 */
class GraphEmbeddingSidecarTest {

    private final GraphNodeRepository nodeRepo = mock(GraphNodeRepository.class);
    private final GraphEdgeRepository edgeRepo = mock(GraphEdgeRepository.class);
    private final INDArrayConverter converter = mock(INDArrayConverter.class);
    private final GraphEmbeddingSidecar sidecar = new GraphEmbeddingSidecar(nodeRepo, edgeRepo, converter);

    @Test
    void exportThenImport_roundtripsNodeKeysAlgorithmAndVersion() {
        INDArray vec = mock(INDArray.class);
        byte[] embBytes = {9, 8, 7, 6};
        KGEmbeddingAlgorithm algo = KGEmbeddingAlgorithm.values()[0];

        GraphNode source = GraphNode.builder()
                .nodeId("n1").externalId("e1").nodeType(NodeLevel.ENTITY).title("Acme")
                .factSheetId(42L).kgEmbedding(vec).kgEmbeddingAlgorithm(algo).kgEmbeddingVersion(7L)
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
}
