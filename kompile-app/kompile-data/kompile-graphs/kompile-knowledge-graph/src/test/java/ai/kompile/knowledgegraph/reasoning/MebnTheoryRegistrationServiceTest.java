/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.knowledgegraph.reasoning;

import ai.kompile.graph.reasoning.mebn.EntityType;
import ai.kompile.graph.reasoning.mebn.MFrag;
import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.mebn.RandomVariable;
import ai.kompile.graph.reasoning.mebn.RelationalMTheoryBuilder;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.knowledgegraph.staging.ModelTrainedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Infra-level tests for {@link MebnTheoryRegistrationService#buildEnrichedMTheory}.
 *
 * <p>This class validates the graph → pure-MEBN-inputs mapping only:
 * node-id collection, per-edge-type mean-weight aggregation (activation probability),
 * dominant source/target {@link NodeLevel} type derivation, per-type entity-id sets, and
 * edge-type → sanitized relation name. The resulting {@link MTheory} is built by the agnostic
 * {@link RelationalMTheoryBuilder}; that builder's correctness (typed RVs, IsA contexts,
 * learning-input boundedness) is covered separately in
 * {@code RelationalMTheoryBuilderTest} in the graph-reasoning library.</p>
 */
class MebnTheoryRegistrationServiceTest {

    // ─────────────────────────────────────────────────────────────────────────────
    // Builders
    // ─────────────────────────────────────────────────────────────────────────────

    private GraphNode node(String id, NodeLevel level) {
        return GraphNode.builder()
                .nodeId(id)
                .nodeType(level)
                .externalId(id)
                .title(id)
                .build();
    }

    private GraphNode entityNode(String id) {
        return node(id, NodeLevel.ENTITY);
    }

    private GraphEdge edge(String id, GraphNode s, GraphNode t, EdgeType type, double w) {
        return GraphEdge.builder()
                .edgeId(id)
                .sourceNode(s)
                .targetNode(t)
                .edgeType(type)
                .weight(w)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Mean-weight aggregation (activation probability)
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void mapsEdgesToMeanWeightedRelationFragments() {
        // SHARED_ENTITY appears twice (0.8, 0.6) → mean 0.7; CITATION once (0.9).
        GraphNode a = entityNode("a"), b = entityNode("b"), c = entityNode("c");
        List<GraphEdge> edges = List.of(
                edge("e1", a, b, EdgeType.SHARED_ENTITY, 0.8),
                edge("e2", a, c, EdgeType.SHARED_ENTITY, 0.6),
                edge("e3", b, c, EdgeType.CITATION, 0.9));
        Set<String> types = new LinkedHashSet<>(List.of("SHARED_ENTITY", "CITATION"));

        MTheory theory = MebnTheoryRegistrationService.buildEnrichedMTheory(1L, edges, types);

        assertNotNull(theory.getMFrag("EntityRelevance"), "foundational relevance fragment");
        assertNotNull(theory.getMFrag("SHARED_ENTITY"), "relation fragment for SHARED_ENTITY");
        assertNotNull(theory.getMFrag("CITATION"), "relation fragment for CITATION");

        // Mean edge weight = activation probability for each relation.
        assertEquals(0.7,
                theory.getMFrag("SHARED_ENTITY").getEdgeStrength("isRelevant", "SHARED_ENTITY"),
                1e-9, "mean(0.8, 0.6) = 0.7 for SHARED_ENTITY");
        assertEquals(0.9,
                theory.getMFrag("CITATION").getEdgeStrength("isRelevant", "CITATION"),
                1e-9, "single edge weight 0.9 for CITATION");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Name sanitization
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void sanitizesRelationNamesFromRelationType() {
        GraphNode a = entityNode("a"), b = entityNode("b");
        GraphEdge e = GraphEdge.builder()
                .edgeId("e1").sourceNode(a).targetNode(b)
                .edgeType(EdgeType.USER_DEFINED).relationType("relates-to/x").weight(0.5)
                .build();
        Set<String> types = new LinkedHashSet<>(List.of("relates-to/x"));

        MTheory theory = MebnTheoryRegistrationService.buildEnrichedMTheory(1L, List.of(e), types);

        assertNotNull(theory.getMFrag("relates_to_x"),
                "non-identifier chars must be replaced with underscores");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Dominant-type derivation
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void dominantSourceTypeIsTheMostFrequentlyObservedEndpointType() {
        // SHARED_ENTITY edges: 3 src=ENTITY, 1 src=DOCUMENT → dominant src = ENTITY.
        GraphNode entityA = node("ea", NodeLevel.ENTITY);
        GraphNode entityB = node("eb", NodeLevel.ENTITY);
        GraphNode entityC = node("ec", NodeLevel.ENTITY);
        GraphNode docD    = node("dd", NodeLevel.DOCUMENT);
        GraphNode target  = node("tgt", NodeLevel.ENTITY);

        List<GraphEdge> edges = List.of(
                edge("e1", entityA, target, EdgeType.SHARED_ENTITY, 0.5),
                edge("e2", entityB, target, EdgeType.SHARED_ENTITY, 0.5),
                edge("e3", entityC, target, EdgeType.SHARED_ENTITY, 0.5),
                edge("e4", docD,    target, EdgeType.SHARED_ENTITY, 0.5)); // minority
        Set<String> types = new LinkedHashSet<>(List.of("SHARED_ENTITY"));

        MTheory theory = MebnTheoryRegistrationService.buildEnrichedMTheory(1L, edges, types);

        MFrag frag = theory.getMFrag("SHARED_ENTITY");
        assertNotNull(frag);
        RandomVariable resident = frag.getResidentNodes().get(0);
        // Dominant source type = ENTITY (3 occurrences) — first argument of binary RV.
        assertEquals("ENTITY", resident.getArgumentTypes().get(0).getTypeName(),
                "dominant source type (mode) must be ENTITY");
    }

    @Test
    void dominantTargetTypeIsTheMostFrequentlyObservedTargetEndpointType() {
        // CITATION edges: 1 tgt=DOCUMENT, 2 tgt=ENTITY → dominant tgt = ENTITY.
        GraphNode src    = node("s", NodeLevel.ENTITY);
        GraphNode docTgt = node("d", NodeLevel.DOCUMENT);
        GraphNode entA   = node("ea", NodeLevel.ENTITY);
        GraphNode entB   = node("eb", NodeLevel.ENTITY);

        List<GraphEdge> edges = List.of(
                edge("e1", src, docTgt, EdgeType.CITATION, 0.5),
                edge("e2", src, entA,   EdgeType.CITATION, 0.5),
                edge("e3", src, entB,   EdgeType.CITATION, 0.5));
        Set<String> types = new LinkedHashSet<>(List.of("CITATION"));

        MTheory theory = MebnTheoryRegistrationService.buildEnrichedMTheory(1L, edges, types);

        MFrag frag = theory.getMFrag("CITATION");
        assertNotNull(frag);
        RandomVariable resident = frag.getResidentNodes().get(0);
        assertEquals("ENTITY", resident.getArgumentTypes().get(1).getTypeName(),
                "dominant target type (mode) must be ENTITY");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Per-type entity-id sets
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void perTypeEntityIdsPopulatedInDominantTypeEntityType() {
        GraphNode ea = node("ea", NodeLevel.ENTITY);
        GraphNode eb = node("eb", NodeLevel.ENTITY);
        GraphNode oc = node("oc", NodeLevel.ENTITY);

        List<GraphEdge> edges = List.of(
                edge("e1", ea, oc, EdgeType.SHARED_ENTITY, 0.8),
                edge("e2", eb, oc, EdgeType.SHARED_ENTITY, 0.6));
        Set<String> types = new LinkedHashSet<>(List.of("SHARED_ENTITY"));

        MTheory theory = MebnTheoryRegistrationService.buildEnrichedMTheory(1L, edges, types);

        // Dominant type for both endpoints is ENTITY.
        EntityType entityType = theory.getEntityType("ENTITY");
        assertNotNull(entityType, "ENTITY EntityType must be registered");

        Set<String> entityIds = entityType.getEntityIds();
        assertTrue(entityIds.contains("ea"), "source node id ea must be in ENTITY type");
        assertTrue(entityIds.contains("eb"), "source node id eb must be in ENTITY type");
        assertTrue(entityIds.contains("oc"), "target node id oc must be in ENTITY type");
    }

    @Test
    void allNodesEntityTypeContainsUnionOfAllEndpointIds() {
        GraphNode ea = entityNode("ea");
        GraphNode eb = entityNode("eb");
        GraphNode ec = entityNode("ec");

        List<GraphEdge> edges = List.of(
                edge("e1", ea, eb, EdgeType.SHARED_ENTITY, 0.5),
                edge("e2", eb, ec, EdgeType.CITATION, 0.5));
        Set<String> types = new LinkedHashSet<>(List.of("SHARED_ENTITY", "CITATION"));

        MTheory theory = MebnTheoryRegistrationService.buildEnrichedMTheory(1L, edges, types);

        EntityType allNodes = theory.getEntityType(RelationalMTheoryBuilder.ALL_NODES_TYPE);
        assertNotNull(allNodes, "AllNodes supertype must always be present");
        assertTrue(allNodes.getEntityIds().containsAll(List.of("ea", "eb", "ec")),
                "AllNodes must contain every node id from all edge endpoints");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Typed RV structure in mapped MFrag
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void mappedRelationFragHasTypedBinaryResidentRv() {
        GraphNode a = entityNode("a"), b = entityNode("b");
        List<GraphEdge> edges = List.of(edge("e1", a, b, EdgeType.SHARED_ENTITY, 0.7));
        Set<String> types = new LinkedHashSet<>(List.of("SHARED_ENTITY"));

        MTheory theory = MebnTheoryRegistrationService.buildEnrichedMTheory(1L, edges, types);

        MFrag frag = theory.getMFrag("SHARED_ENTITY");
        assertNotNull(frag);
        RandomVariable resident = frag.getResidentNodes().get(0);

        assertEquals(2, resident.getArity(), "resident RV must be binary");
        assertEquals(RandomVariable.NodeRole.RESIDENT, resident.getRole());
        // Both endpoints are ENTITY nodes → both arg types must be ENTITY.
        assertEquals("ENTITY", resident.getArgumentTypes().get(0).getTypeName());
        assertEquals("ENTITY", resident.getArgumentTypes().get(1).getTypeName());
    }

    @Test
    void mappedRelationFragHasFourContextConstraints() {
        // IsA(src,SrcType) + IsA(tgt,TgtType) + notEqual + edgeExists = 4 constraints.
        GraphNode a = entityNode("a"), b = entityNode("b");
        List<GraphEdge> edges = List.of(edge("e1", a, b, EdgeType.CITATION, 0.9));
        Set<String> types = new LinkedHashSet<>(List.of("CITATION"));

        MTheory theory = MebnTheoryRegistrationService.buildEnrichedMTheory(1L, edges, types);

        MFrag frag = theory.getMFrag("CITATION");
        assertNotNull(frag);
        assertEquals(4, frag.getContextConstraints().size(),
                "IsA(src) + IsA(tgt) + notEqual + edgeExists");
    }

    @Test
    void isAContextConstraintsPresentForBothArgPositions() {
        GraphNode a = entityNode("a"), b = entityNode("b");
        List<GraphEdge> edges = List.of(edge("e1", a, b, EdgeType.SHARED_ENTITY, 0.5));
        Set<String> types = new LinkedHashSet<>(List.of("SHARED_ENTITY"));

        MTheory theory = MebnTheoryRegistrationService.buildEnrichedMTheory(1L, edges, types);

        MFrag frag = theory.getMFrag("SHARED_ENTITY");
        assertNotNull(frag);

        // Dominant source = ENTITY → arg-var = ENTITY_0; target = ENTITY → arg-var = ENTITY_1.
        boolean hasSrcIsA = frag.getContextConstraints().stream()
                .anyMatch(c -> c.describe().contains("hasType(ENTITY_0, ENTITY)"));
        boolean hasTgtIsA = frag.getContextConstraints().stream()
                .anyMatch(c -> c.describe().contains("hasType(ENTITY_1, ENTITY)"));

        assertTrue(hasSrcIsA, "IsA(arg0, ENTITY) context constraint must be present");
        assertTrue(hasTgtIsA, "IsA(arg1, ENTITY) context constraint must be present");
    }

    @Test
    void kgeCompletionRefreshesBackendNeutralReasoningGraph() {
        IncrementalReasoningOrchestrator orchestrator = mock(IncrementalReasoningOrchestrator.class);
        KnowledgeGraphService graphService = mock(KnowledgeGraphService.class);
        MebnTheoryRegistrationService service = new MebnTheoryRegistrationService(orchestrator);
        ReflectionTestUtils.setField(service, "knowledgeGraphService", graphService);

        INDArray nodeEmbedding = mock(INDArray.class);
        when(nodeEmbedding.toDoubleVector()).thenReturn(new double[]{0.2, 0.4, 0.6});
        GraphNode source = GraphNode.builder()
                .nodeId("source")
                .externalId("source")
                .title("Source")
                .nodeType(NodeLevel.ENTITY)
                .confidence(0.9)
                .kgEmbedding(nodeEmbedding)
                .build();
        GraphNode target = entityNode("target");
        GraphEdge relation = GraphEdge.builder()
                .edgeId("relation-1")
                .sourceNode(source)
                .targetNode(target)
                .edgeType(EdgeType.USER_DEFINED)
                .relationType("supports")
                .weight(0.8)
                .confidence(0.9)
                .build();

        when(graphService.getNodesInFactSheet(42L)).thenReturn(List.of(source, target));
        when(graphService.getEdgesInFactSheet(42L)).thenReturn(List.of(relation));

        service.refreshReasoningGraphAfterKge(
                new ModelTrainedEvent(this, "kge", 42L, Path.of("target", "kge.bin")));

        ArgumentCaptor<ReasoningGraph> graphCaptor = ArgumentCaptor.forClass(ReasoningGraph.class);
        verify(orchestrator).registerReasoningGraph(eq(42L), graphCaptor.capture());
        ReasoningGraph registered = graphCaptor.getValue();
        assertEquals(2, registered.entityCount());
        assertEquals(1, registered.relationCount());
        assertArrayEquals(new double[]{0.2, 0.4, 0.6},
                registered.entity("source").orElseThrow().embedding(), 1.0e-9);
        assertEquals("supports", registered.relations().iterator().next().type());
    }

    @Test
    void perTypeEntityTypeIsSubtypeOfAllNodes() {
        GraphNode a = entityNode("a"), b = entityNode("b");
        List<GraphEdge> edges = List.of(edge("e1", a, b, EdgeType.SHARED_ENTITY, 0.5));
        Set<String> types = new LinkedHashSet<>(List.of("SHARED_ENTITY"));

        MTheory theory = MebnTheoryRegistrationService.buildEnrichedMTheory(1L, edges, types);

        EntityType allNodes = theory.getEntityType(RelationalMTheoryBuilder.ALL_NODES_TYPE);
        EntityType entityType = theory.getEntityType("ENTITY");

        assertNotNull(allNodes);
        assertNotNull(entityType);
        assertSame(allNodes, entityType.getSuperType(),
                "ENTITY must be a subtype of AllNodes for isA-polymorphic SSBN lookup");
    }
}
