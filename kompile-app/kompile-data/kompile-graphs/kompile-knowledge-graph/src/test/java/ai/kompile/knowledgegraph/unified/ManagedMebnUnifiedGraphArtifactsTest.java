/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.knowledgegraph.unified;

import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.mebn.RelationalMTheoryBuilder;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.persistence.MebnWeightPersistenceAdapter;
import ai.kompile.knowledgegraph.reasoning.IncrementalReasoningOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ManagedMebnUnifiedGraphArtifactsTest {

    @Test
    void exportsAndImportsConstraintCompleteTheory() throws Exception {
        IncrementalReasoningOrchestrator sourceOrchestrator = mock(IncrementalReasoningOrchestrator.class);
        MebnWeightPersistenceAdapter sourceWeights = mock(MebnWeightPersistenceAdapter.class);
        MTheory theory = theory();
        when(sourceOrchestrator.registeredMTheory(42L)).thenReturn(Optional.of(theory));
        ManagedMebnUnifiedGraphArtifacts exporter = artifacts(sourceOrchestrator, sourceWeights);
        UnifiedGraph graph = graph();

        exporter.contribute(42L, graph);

        assertNotNull(graph.artifactText(ManagedMebnUnifiedGraphArtifacts.THEORY_ARTIFACT));
        verifyNoInteractions(sourceWeights);

        IncrementalReasoningOrchestrator targetOrchestrator = mock(IncrementalReasoningOrchestrator.class);
        MebnWeightPersistenceAdapter targetWeights = mock(MebnWeightPersistenceAdapter.class);
        ManagedMebnUnifiedGraphArtifacts importer = artifacts(targetOrchestrator, targetWeights);
        assertEquals(1, importer.importArtifacts(99L, graph));
        verify(targetWeights).persist(org.mockito.ArgumentMatchers.eq(99L),
                org.mockito.ArgumentMatchers.any(MTheory.class));
        verify(targetOrchestrator).registerMTheory(org.mockito.ArgumentMatchers.eq(99L),
                org.mockito.ArgumentMatchers.any(MTheory.class), org.mockito.ArgumentMatchers.same(graph));
    }

    @Test
    void missingArtifactClearsStaleTheory() {
        IncrementalReasoningOrchestrator orchestrator = mock(IncrementalReasoningOrchestrator.class);
        MebnWeightPersistenceAdapter weights = mock(MebnWeightPersistenceAdapter.class);
        ManagedMebnUnifiedGraphArtifacts importer = artifacts(orchestrator, weights);
        UnifiedGraph imported = graph();

        assertEquals(0, importer.importArtifacts(42L, imported));

        verify(orchestrator).unregisterMTheory(42L);
        verify(orchestrator).registerReasoningGraph(42L, imported);
        try {
            verify(weights).clearTheoryArtifacts(42L);
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    @Test
    void coarseManagedNodeTypeMatchesSemanticExportType() {
        IncrementalReasoningOrchestrator orchestrator = mock(IncrementalReasoningOrchestrator.class);
        MTheory coarse = RelationalMTheoryBuilder.build("portable", List.of(
                new RelationalMTheoryBuilder.RelationDescriptor(
                        "supports", "ENTITY", "DOCUMENT", 0.8,
                        List.of("alice"), List.of("doc-1"))));
        when(orchestrator.registeredMTheory(42L)).thenReturn(Optional.of(coarse));
        UnifiedGraph graph = new UnifiedGraph()
                .addEntity(GraphEntity.builder("alice").type("PERSON").label("Alice")
                        .attribute("kompile.store", Map.of("nodeType", "ENTITY")).build())
                .addEntity(GraphEntity.builder("doc-1").type("DOCUMENT").label("Doc").build());

        artifacts(orchestrator, mock(MebnWeightPersistenceAdapter.class)).contribute(42L, graph);

        assertNotNull(graph.artifactText(ManagedMebnUnifiedGraphArtifacts.THEORY_ARTIFACT));
    }

    @Test
    void preflightRejectsMalformedTheoryWithoutMutatingManagedState() {
        IncrementalReasoningOrchestrator orchestrator = mock(IncrementalReasoningOrchestrator.class);
        MebnWeightPersistenceAdapter weights = mock(MebnWeightPersistenceAdapter.class);
        ManagedMebnUnifiedGraphArtifacts importer = artifacts(orchestrator, weights);
        UnifiedGraph graph = graph().putArtifactText(
                ManagedMebnUnifiedGraphArtifacts.THEORY_ARTIFACT, "{\"version\":999}");

        assertThrows(IllegalArgumentException.class,
                () -> importer.validateArtifacts(42L, graph));
        verifyNoInteractions(orchestrator, weights);
    }

    private static ManagedMebnUnifiedGraphArtifacts artifacts(
            IncrementalReasoningOrchestrator orchestrator,
            MebnWeightPersistenceAdapter weights) {
        ManagedMebnUnifiedGraphArtifacts artifacts = new ManagedMebnUnifiedGraphArtifacts();
        ReflectionTestUtils.setField(artifacts, "orchestrator", orchestrator);
        ReflectionTestUtils.setField(artifacts, "weights", weights);
        return artifacts;
    }

    private static MTheory theory() {
        return RelationalMTheoryBuilder.build("portable", List.of(
                new RelationalMTheoryBuilder.RelationDescriptor(
                        "supports", "PERSON", "DOCUMENT", 0.8,
                        List.of("alice"), List.of("doc-1"))));
    }

    private static UnifiedGraph graph() {
        return new UnifiedGraph()
                .addEntity(GraphEntity.builder("alice").type("PERSON").label("Alice").build())
                .addEntity(GraphEntity.builder("doc-1").type("DOCUMENT").label("Doc").build());
    }
}
