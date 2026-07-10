/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.knowledgegraph.reasoning;

import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.Test;
import org.nd4j.linalg.api.ndarray.INDArray;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeGraphReasoningAdapterTest {

    @Test
    void graphFromPreservesEmbeddingsSemanticTypesAndParallelRelations() {
        INDArray nodeVector = vector(0.1, 0.2, 0.3);
        INDArray firstRelationVector = vector(0.4, 0.5, 0.6);
        INDArray secondRelationVector = vector(0.7, 0.8, 0.9);

        GraphNode source = node("source", nodeVector);
        GraphNode target = node("target", null);
        GraphEdge first = edge(
                "edge-1", source, target, "submitted_to", firstRelationVector, false);
        GraphEdge second = edge(
                "edge-2", source, target, "approved_by", secondRelationVector, true);

        ReasoningGraph graph = new KnowledgeGraphReasoningAdapter(mock(KnowledgeGraphService.class))
                .maxNodes(10)
                .minEdgeWeight(0.0)
                .preserveParallelRelations(true)
                .graphFrom(List.of(source, target), List.of(first, second));

        assertEquals(2, graph.entityCount());
        assertArrayEquals(new double[]{0.1, 0.2, 0.3},
                graph.entity("source").orElseThrow().embedding(), 1.0e-9);

        Map<String, GraphRelation> relations = graph.relations().stream()
                .collect(Collectors.toMap(GraphRelation::id, Function.identity()));
        assertEquals(3, relations.size());
        assertEquals("submitted_to", relations.get("edge-1").type());
        assertArrayEquals(new double[]{0.4, 0.5, 0.6},
                relations.get("edge-1").embedding(), 1.0e-9);
        assertEquals("approved_by", relations.get("edge-2").type());
        assertArrayEquals(new double[]{0.7, 0.8, 0.9},
                relations.get("edge-2").embedding(), 1.0e-9);
        assertEquals("approved_by", relations.get("edge-2:reverse").type());
        assertEquals("target", relations.get("edge-2:reverse").sourceId());
        assertEquals("source", relations.get("edge-2:reverse").targetId());
    }

    private static GraphNode node(String id, INDArray embedding) {
        return GraphNode.builder()
                .nodeId(id)
                .externalId(id)
                .title(id)
                .nodeType(NodeLevel.ENTITY)
                .confidence(0.9)
                .kgEmbedding(embedding)
                .build();
    }

    private static GraphEdge edge(String id, GraphNode source, GraphNode target,
                                  String relationType, INDArray embedding,
                                  boolean bidirectional) {
        return GraphEdge.builder()
                .edgeId(id)
                .sourceNode(source)
                .targetNode(target)
                .edgeType(EdgeType.USER_DEFINED)
                .relationType(relationType)
                .weight(0.8)
                .confidence(0.9)
                .bidirectional(bidirectional)
                .kgRelationEmbedding(embedding)
                .build();
    }

    private static INDArray vector(double... values) {
        INDArray vector = mock(INDArray.class);
        when(vector.toDoubleVector()).thenReturn(values);
        return vector;
    }
}
