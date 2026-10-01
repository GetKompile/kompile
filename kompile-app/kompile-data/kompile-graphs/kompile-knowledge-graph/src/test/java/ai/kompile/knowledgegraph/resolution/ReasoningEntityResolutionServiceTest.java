/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.knowledgegraph.resolution;

import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReasoningEntityResolutionServiceTest {

    @Test
    void sharedTypedEmailNeighborAnchorsPersonIdentity() {
        MutableReasoningGraph graph = new MutableReasoningGraph()
                .addEntity("person-a", "PERSON", "Alex")
                .addEntity("person-b", "PERSON", "A. Smith")
                .addEntity("email-1", "EMAIL", "alex@example.com")
                .addRelation("email-edge-a", "person-a", "email-1", "HAS_EMAIL", 1.0)
                .addRelation("email-edge-b", "person-b", "email-1", "HAS_EMAIL", 1.0);

        ReasoningEntityResolutionService.Snapshot snapshot = snapshot(graph);
        ReasoningEntityResolutionService.Evidence evidence =
                snapshot.evaluate(node("person-a", "Alex"), node("person-b", "A. Smith"));

        assertEquals(1.0, evidence.exclusiveNeighborIdentity(), 1.0e-9);
        assertTrue(evidence.mergeScore(0.0) >= 0.94,
                "a shared exclusive email relation should anchor person identity");
        assertTrue(evidence.reasons().stream()
                .anyMatch(reason -> reason.startsWith("EXCLUSIVE_IDENTIFIER_NEIGHBOR")));
    }

    @Test
    void emailEntityIsNotTreatedAsThePersonItIdentifies() {
        MutableReasoningGraph graph = new MutableReasoningGraph()
                .addEntity("person-a", "PERSON", "Alex")
                .addEntity("person-b", "PERSON", "A. Smith")
                .addEntity("email-1", "EMAIL", "alex@example.com")
                .addRelation("email-edge-a", "person-a", "email-1", "HAS_EMAIL", 1.0)
                .addRelation("email-edge-b", "person-b", "email-1", "HAS_EMAIL", 1.0);

        ReasoningEntityResolutionService.Evidence evidence = snapshot(graph)
                .evaluate(node("person-a", "Alex"), node("email-1", "alex@example.com"));

        assertEquals(0.0, evidence.exclusiveNeighborIdentity(), 1.0e-9,
                "the identifier endpoint itself is not a duplicate person");
        assertEquals(0.0, evidence.directGraphIdentity(), 1.0e-9);
    }

    @Test
    void pslIdentityCanPromoteAnOtherwiseWeakCandidate() {
        ReasoningEntityResolutionService.Snapshot snapshot =
                new ReasoningEntityResolutionService.Snapshot(
                        null,
                        Map.of(),
                        Map.of(),
                        Map.of("person-a\u0000person-b", 0.91),
                        Map.of(),
                        Map.of(),
                        Map.of());

        ReasoningEntityResolutionService.Evidence evidence =
                snapshot.evaluate(node("person-a", "Alex"), node("person-b", "A. Smith"));

        assertTrue(evidence.mergeScore(0.20) >= 0.98);
        assertTrue(evidence.reasons().stream().anyMatch(reason -> reason.startsWith("PSL_IDENTITY")));
    }

    @Test
    void folOrPslConflictVetoesEvenAnExactLexicalMatch() {
        ReasoningEntityResolutionService.Snapshot snapshot =
                new ReasoningEntityResolutionService.Snapshot(
                        null,
                        Map.of(),
                        Map.of("person-a\u0000person-b", 0.95),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of());

        ReasoningEntityResolutionService.Evidence evidence =
                snapshot.evaluate(node("person-a", "Alex Smith"), node("person-b", "Alex Smith"));

        assertTrue(evidence.vetoMerge());
        assertEquals(0.0, evidence.mergeScore(1.0), 1.0e-9);
    }

    @Test
    void compactionConsumesReasoningConflictBeforeAcceptingExactTitle() {
        KnowledgeGraphService knowledgeGraph = mock(KnowledgeGraphService.class);
        GraphNode left = node("person-a", "Alex Smith");
        GraphNode right = node("person-b", "Alex Smith");
        when(knowledgeGraph.getNodesByTypeInFactSheet(7L, NodeLevel.ENTITY))
                .thenReturn(List.of(left, right));
        doCallRealMethod().when(knowledgeGraph)
                .getEntityNodesInFactSheetPage(anyLong(), anyInt(), anyInt());

        ReasoningEntityResolutionService resolver = mock(ReasoningEntityResolutionService.class);
        when(resolver.snapshot(7L)).thenReturn(
                new ReasoningEntityResolutionService.Snapshot(
                        null,
                        Map.of(),
                        Map.of("person-a\u0000person-b", 0.95),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of()));

        GraphCompactionService compaction = new GraphCompactionService(knowledgeGraph);
        compaction.setReasoningEntityResolutionService(resolver);

        List<GraphCompactionService.MatchCandidate> candidates =
                compaction.previewCandidates(
                        7L,
                        new GraphCompactionService.CompactionConfig(0.85, false, false, 0.88));

        assertTrue(candidates.isEmpty(),
                "a learned identity conflict must veto an otherwise exact-title candidate");
        verify(resolver).snapshot(7L);
    }

    @Test
    void negatedIdentityAtomsAreEvidenceForTheOtherSide() {
        Map<String, Double> positive = new LinkedHashMap<>();
        Map<String, Double> negative = new LinkedHashMap<>();

        ReasoningEntityResolutionService.collectIdentityAtom("~sameAs(person-a, person-b)", 0.9, positive, negative);
        ReasoningEntityResolutionService.collectIdentityAtom("notIdenticalTo(person-a, person-c)", 0.8, positive, negative);
        ReasoningEntityResolutionService.collectIdentityAtom("!differentFrom(person-b, person-c)", 0.7, positive, negative);
        ReasoningEntityResolutionService.collectIdentityAtom("SAME_AS(person-d, person-e)", 0.6, positive, negative);
        ReasoningEntityResolutionService.collectIdentityAtom("derived_sameAs(person-f, person-g)", 0.5, positive, negative);
        ReasoningEntityResolutionService.collectIdentityAtom("worksFor(person-a, acme)", 1.0, positive, negative);

        assertEquals(Map.of(pair("person-a", "person-b"), 0.9, pair("person-a", "person-c"), 0.8), negative,
                "a negated sameAs is an identity conflict, not identity");
        assertEquals(Map.of(pair("person-b", "person-c"), 0.7, pair("person-d", "person-e"), 0.6,
                pair("person-f", "person-g"), 0.5), positive);
    }

    @Test
    void nonLatinRelationTypesKeepDistinctSignatures() {
        MutableReasoningGraph graph = new MutableReasoningGraph()
                .addEntity("person-a", "PERSON", "Aiko")
                .addEntity("person-b", "PERSON", "Aiko S.")
                .addEntity("person-c", "PERSON", "A. Sato")
                .addEntity("org-1", "ORGANIZATION", "Acme")
                .addRelation("r-a", "person-a", "org-1", "所属", 1.0)
                .addRelation("r-b", "person-b", "org-1", "勤務", 1.0)
                .addRelation("r-c", "person-c", "org-1", "所属", 1.0);

        ReasoningEntityResolutionService.Snapshot snapshot = snapshot(graph);

        assertEquals(0.0, snapshot.evaluate(node("person-a", "Aiko"), node("person-b", "Aiko S."))
                .neighborhoodSimilarity(), 1.0e-9, "different relations to one neighbour do not overlap");
        assertEquals(1.0, snapshot.evaluate(node("person-a", "Aiko"), node("person-c", "A. Sato"))
                .neighborhoodSimilarity(), 1.0e-9);
    }

    @Test
    void probabilisticContextAloneCannotInventIdentity() {
        ReasoningEntityResolutionService.Evidence evidence =
                new ReasoningEntityResolutionService.Evidence(
                        0.0, 0.0, 0.0, 0.0,
                        0.0, 0.0, 1.0, 1.0,
                        0.0, List.of("PSL_CONTEXT:1.000", "MEBN_CONTEXT:1.000"));

        assertFalse(evidence.vetoMerge());
        assertEquals(0.0, evidence.mergeScore(0.0), 1.0e-9);
    }

    private static String pair(String left, String right) {
        return left + '\0' + right;
    }

    private static ReasoningEntityResolutionService.Snapshot snapshot(MutableReasoningGraph graph) {
        return new ReasoningEntityResolutionService.Snapshot(
                graph, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    private static GraphNode node(String id, String title) {
        LocalDateTime now = LocalDateTime.now();
        return GraphNode.builder()
                .nodeId(id)
                .externalId(id)
                .nodeType(NodeLevel.ENTITY)
                .title(title)
                .metadataJson("{\"entity_type\":\"PERSON\"}")
                .confidence(0.9)
                .edgeCount(1)
                .childCount(0)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
