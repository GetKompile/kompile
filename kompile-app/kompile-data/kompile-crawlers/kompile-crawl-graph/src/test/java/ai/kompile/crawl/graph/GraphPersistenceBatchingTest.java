/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.crawl.graph;

import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class GraphPersistenceBatchingTest {

    private final GraphPersistenceHelper helper = new GraphPersistenceHelper();

    @AfterEach
    void clearProperties() {
        System.clearProperty("kompile.graph.rpc.batch-items");
        System.clearProperty("kompile.graph.rpc.batch-bytes");
    }

    @Test
    void batchesByItemCountBeforeRpcPayloadCanGrowWithoutBound() {
        System.setProperty("kompile.graph.rpc.batch-items", "3");
        List<KnowledgeGraphService.EdgeSpec> specs = edgeSpecs(8, 16);

        List<List<KnowledgeGraphService.EdgeSpec>> batches = helper.boundedBatches(specs);

        assertEquals(List.of(3, 3, 2), batches.stream().map(List::size).toList());
    }

    @Test
    void batchesBySerializedBytesEvenWhenItemLimitIsLarge() {
        System.setProperty("kompile.graph.rpc.batch-items", "1000");
        System.setProperty("kompile.graph.rpc.batch-bytes", "900");
        List<KnowledgeGraphService.EdgeSpec> specs = edgeSpecs(12, 400);

        List<List<KnowledgeGraphService.EdgeSpec>> batches = helper.boundedBatches(specs);

        assertTrue(batches.size() >= 6, "large descriptions must force small byte-bounded batches");
        assertTrue(batches.stream().allMatch(batch -> batch.size() <= 2));
    }

    @Test
    void rejectsSingleOversizedItemBeforeRpcSerialization() {
        System.setProperty("kompile.graph.rpc.batch-items", "100");
        System.setProperty("kompile.graph.rpc.batch-bytes", "512");
        List<KnowledgeGraphService.EdgeSpec> specs = new ArrayList<>(edgeSpecs(2, 16));
        specs.add(1, edgeSpecs(1, 2048).get(0));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> helper.boundedBatches(specs));
        assertTrue(error.getMessage().contains("Single graph RPC item"));
    }

    @Test
    void persistsEdgesThroughBoundedBatchRpcInsteadOfPerEdgeCalls() {
        System.setProperty("kompile.graph.rpc.batch-items", "3");
        KnowledgeGraphService graphService = mock(KnowledgeGraphService.class);
        helper.knowledgeGraphService = graphService;
        when(graphService.createEdgesBatch(anyList())).thenAnswer(invocation ->
                ((List<?>) invocation.getArgument(0)).size());

        int created = helper.createEdgesInBoundedBatches(edgeSpecs(8, 16));

        assertEquals(8, created);
        verify(graphService, times(3)).createEdgesBatch(anyList());
        verify(graphService, never()).createEdgeWithMetadata(
                anyString(), anyString(), any(), anyDouble(), anyString(), anyString(),
                anyString(), any(), any());
    }

    @Test
    void persistsNodeUpdatesThroughCountAndByteBoundedRpcs() {
        System.setProperty("kompile.graph.rpc.batch-items", "3");
        System.setProperty("kompile.graph.rpc.batch-bytes", "1000");
        KnowledgeGraphService graphService = mock(KnowledgeGraphService.class);
        helper.knowledgeGraphService = graphService;
        when(graphService.updateNodesBatch(anyList())).thenAnswer(invocation ->
                ((List<?>) invocation.getArgument(0)).size());
        List<KnowledgeGraphService.NodeUpdate> updates = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            updates.add(new KnowledgeGraphService.NodeUpdate(
                    "node-" + i, null, null, Map.of("payload", "x".repeat(400))));
        }

        int updated = helper.updateNodesInBoundedBatches(updates);

        assertEquals(8, updated);
        verify(graphService, atLeast(4)).updateNodesBatch(anyList());
    }

    private static List<KnowledgeGraphService.EdgeSpec> edgeSpecs(int count, int descriptionLength) {
        String description = "x".repeat(descriptionLength);
        List<KnowledgeGraphService.EdgeSpec> specs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            specs.add(new KnowledgeGraphService.EdgeSpec(
                    "source-" + i, "target-" + i, EdgeType.USER_DEFINED, 0.8,
                    description, "RELATED_TO", "{}", EdgeProvenance.EXTRACTED, 1L));
        }
        return specs;
    }
}
