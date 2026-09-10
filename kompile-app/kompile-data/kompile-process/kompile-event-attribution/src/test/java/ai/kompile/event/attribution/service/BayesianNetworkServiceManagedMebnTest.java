/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.event.attribution.service;

import ai.kompile.graph.reasoning.domain.BayesianInferenceResult;
import ai.kompile.graph.reasoning.mebn.MFrag;
import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.mebn.RelationalMTheoryBuilder;
import ai.kompile.graph.reasoning.mebn.type.TypeHierarchy;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.knowledgegraph.unified.ManagedMebnUnifiedGraphArtifacts;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BayesianNetworkServiceManagedMebnTest {

    @Test
    void factSheetQueryUsesBoundedPortableManagedTheoryWithoutMutatingSource() {
        KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
        MTheory theory = RelationalMTheoryBuilder.build("managed", List.of(
                new RelationalMTheoryBuilder.RelationDescriptor(
                        "supports", "PERSON", "DOCUMENT", 0.81,
                        List.of("seed"), List.of("neighbor", "outside"))));
        ManagedMebnUnifiedGraphArtifacts provider = mock(ManagedMebnUnifiedGraphArtifacts.class);
        when(provider.theoryForFactSheet(42L)).thenReturn(Optional.of(theory));
        GraphNode seed = GraphNode.builder().nodeId("seed").nodeType(NodeLevel.ENTITY).title("Seed").build();
        GraphNode neighbor = GraphNode.builder().nodeId("neighbor").nodeType(NodeLevel.ENTITY)
                .title("Neighbor").build();
        GraphNode outside = GraphNode.builder().nodeId("outside").nodeType(NodeLevel.ENTITY)
                .title("Outside").build();
        GraphEdge edge = GraphEdge.builder().sourceNode(seed).targetNode(neighbor)
                .edgeType(EdgeType.USER_DEFINED).weight(1.0).build();
        GraphEdge outsideEdge = GraphEdge.builder().sourceNode(neighbor).targetNode(outside)
                .edgeType(EdgeType.USER_DEFINED).weight(1.0).build();
        when(graph.getEdgesForNodeInFactSheet("seed", 42L)).thenReturn(List.of(edge));
        when(graph.getEdgesForNodeInFactSheet("neighbor", 42L)).thenReturn(List.of(edge, outsideEdge));
        AtomicReference<MTheory> used = new AtomicReference<>();
        AtomicReference<Long> inferenceScope = new AtomicReference<>();
        BayesianNetworkService service = new BayesianNetworkService(graph) {
            @Override
            protected BayesianInferenceResult queryWithMTheory(
                    MTheory selected, Map<String, Integer> evidence, TypeHierarchy hierarchy,
                    Long factSheetId) {
                used.set(selected);
                inferenceScope.set(factSheetId);
                return BayesianInferenceResult.builder().computedAt(Instant.now()).build();
            }
        };
        service.setManagedMebn(provider);

        service.queryMebnFromKg(List.of("seed"), Map.of(), 1, 3, null, 42L);

        assertNotSame(theory, used.get());
        assertEquals(Set.of("seed"), used.get().getEntityType("PERSON").getEntityIds());
        assertEquals(Set.of("neighbor"), used.get().getEntityType("DOCUMENT").getEntityIds());
        assertEquals(42L, inferenceScope.get());
        MFrag relation = used.get().getMFrag("supports");
        assertEquals(0.81, relation.getEdgeStrength("isRelevant", "supports"), 1e-12);
        assertEquals(Set.of("neighbor", "outside"),
                theory.getEntityType("DOCUMENT").getEntityIds());

        service.queryMebnFromKg(List.of("seed"), Map.of(), 5, 1, null, 42L);
        assertEquals(Set.of("seed"), used.get().getEntityType("PERSON").getEntityIds());
        assertEquals(Set.of(), used.get().getEntityType("DOCUMENT").getEntityIds());
    }
}
