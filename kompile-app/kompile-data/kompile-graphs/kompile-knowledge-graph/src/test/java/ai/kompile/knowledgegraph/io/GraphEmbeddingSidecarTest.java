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
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import org.junit.jupiter.api.Test;
import org.nd4j.linalg.api.ndarray.INDArray;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests {@link GraphEmbeddingSidecar} binary framing (keys/algorithm/version + the
 * embedding byte payload) using a mocked {@link INDArrayConverter}, so no live ND4J
 * backend is required.
 */
class GraphEmbeddingSidecarTest {

    @Test
    void exportThenImport_roundtripsKeysAlgorithmAndVersion() {
        GraphNodeRepository repo = mock(GraphNodeRepository.class);
        INDArrayConverter converter = mock(INDArrayConverter.class);
        GraphEmbeddingSidecar sidecar = new GraphEmbeddingSidecar(repo, converter);

        INDArray vec = mock(INDArray.class);
        byte[] embBytes = {9, 8, 7, 6};
        KGEmbeddingAlgorithm algo = KGEmbeddingAlgorithm.values()[0];

        GraphNode source = GraphNode.builder()
                .nodeId("n1").externalId("e1").nodeType(NodeLevel.ENTITY).title("Acme")
                .factSheetId(42L).kgEmbedding(vec).kgEmbeddingAlgorithm(algo).kgEmbeddingVersion(7L)
                .build();
        when(repo.findByFactSheetIdAndKgEmbeddingNotNull(42L)).thenReturn(List.of(source));
        when(converter.convertToDatabaseColumn(vec)).thenReturn(embBytes);

        byte[] data = sidecar.export(42L);
        assertNotNull(data);

        // A freshly-rehydrated node with the same identity but no embedding yet.
        GraphNode target = GraphNode.builder()
                .nodeId("n1-new").externalId("e1").nodeType(NodeLevel.ENTITY).title("Acme")
                .factSheetId(42L).build();
        INDArray restored = mock(INDArray.class);
        when(repo.findByExternalIdAndNodeTypeAndFactSheetId("e1", NodeLevel.ENTITY, 42L))
                .thenReturn(Optional.of(target));
        when(converter.convertToEntityAttribute(embBytes)).thenReturn(restored);

        int applied = sidecar.importInto(42L, data);

        assertEquals(1, applied);
        assertSame(restored, target.getKgEmbedding());
        assertEquals(algo, target.getKgEmbeddingAlgorithm());
        assertEquals(Long.valueOf(7L), target.getKgEmbeddingVersion());
        verify(repo).save(target);
    }

    @Test
    void export_noEmbeddings_returnsNull() {
        GraphNodeRepository repo = mock(GraphNodeRepository.class);
        when(repo.findByFactSheetIdAndKgEmbeddingNotNull(1L)).thenReturn(List.of());
        GraphEmbeddingSidecar sidecar = new GraphEmbeddingSidecar(repo, mock(INDArrayConverter.class));
        assertNull(sidecar.export(1L));
    }

    @Test
    void importInto_missingNode_skipsGracefully() {
        GraphNodeRepository repo = mock(GraphNodeRepository.class);
        INDArrayConverter converter = mock(INDArrayConverter.class);
        GraphEmbeddingSidecar sidecar = new GraphEmbeddingSidecar(repo, converter);

        INDArray vec = mock(INDArray.class);
        GraphNode source = GraphNode.builder()
                .nodeId("n1").externalId("gone").nodeType(NodeLevel.ENTITY).title("X")
                .factSheetId(5L).kgEmbedding(vec).build();
        when(repo.findByFactSheetIdAndKgEmbeddingNotNull(5L)).thenReturn(List.of(source));
        when(converter.convertToDatabaseColumn(vec)).thenReturn(new byte[]{1});
        byte[] data = sidecar.export(5L);

        when(repo.findByExternalIdAndNodeTypeAndFactSheetId(eq("gone"), any(), eq(5L)))
                .thenReturn(Optional.empty());

        assertEquals(0, sidecar.importInto(5L, data));
        verify(repo, never()).save(any());
    }
}
