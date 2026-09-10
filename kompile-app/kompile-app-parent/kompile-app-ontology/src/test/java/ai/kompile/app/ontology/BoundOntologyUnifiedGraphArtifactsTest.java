/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.app.ontology;

import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.process.ontology.OntologySchema;
import ai.kompile.process.service.ProcessEngineService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class BoundOntologyUnifiedGraphArtifactsTest {

    @Test
    void exactSchemaRoundTripsAndBindsDestinationFactSheet() {
        GraphOntologyBindingService sourceBindings = mock(GraphOntologyBindingService.class);
        OntologySchema schema = OntologySchema.builder()
                .id("finance").name("Finance").version(4)
                .createdAt(Instant.parse("2025-01-01T00:00:00Z"))
                .updatedAt(Instant.parse("2025-02-01T00:00:00Z"))
                .entityTypes(List.of()).relationshipTypes(List.of()).globalRules(List.of())
                .metadata(Map.of("domain", "finance")).build();
        when(sourceBindings.resolveActiveOntology(42L)).thenReturn(Optional.of(schema));
        BoundOntologyUnifiedGraphArtifacts exporter = new BoundOntologyUnifiedGraphArtifacts(
                sourceBindings, mock(ProcessEngineService.class));
        UnifiedGraph graph = new UnifiedGraph().factSheetId(42L);

        exporter.contribute(42L, graph);

        assertNotNull(graph.artifactText(BoundOntologyUnifiedGraphArtifacts.ARTIFACT));
        ProcessEngineService targetEngine = mock(ProcessEngineService.class);
        GraphOntologyBindingService targetBindings = mock(GraphOntologyBindingService.class);
        BoundOntologyUnifiedGraphArtifacts importer = new BoundOntologyUnifiedGraphArtifacts(
                targetBindings, targetEngine);

        assertEquals(1, importer.importArtifacts(99L, graph));
        var order = inOrder(targetEngine, targetBindings);
        order.verify(targetEngine).restoreOntologySchema(
                org.mockito.ArgumentMatchers.argThat(restored ->
                        "finance".equals(restored.getId()) && restored.getVersion() == 4));
        order.verify(targetBindings).bindOntology(99L, "finance", 4);
    }

    @Test
    void unboundScopeWritesExplicitEmptyEnvelope() {
        GraphOntologyBindingService bindings = mock(GraphOntologyBindingService.class);
        when(bindings.resolveActiveOntology(42L)).thenReturn(Optional.empty());
        BoundOntologyUnifiedGraphArtifacts artifacts = new BoundOntologyUnifiedGraphArtifacts(
                bindings, mock(ProcessEngineService.class));
        UnifiedGraph graph = new UnifiedGraph();

        artifacts.contribute(42L, graph);

        assertEquals(0, artifacts.importArtifacts(42L, graph));
    }

    @Test
    void preflightRejectsUnsafeOntologyIdentityBeforeRestoringOrBinding() {
        ProcessEngineService engine = mock(ProcessEngineService.class);
        GraphOntologyBindingService bindings = mock(GraphOntologyBindingService.class);
        BoundOntologyUnifiedGraphArtifacts artifacts =
                new BoundOntologyUnifiedGraphArtifacts(bindings, engine);
        UnifiedGraph graph = new UnifiedGraph().putArtifactText(
                BoundOntologyUnifiedGraphArtifacts.ARTIFACT,
                """
                {"format":"kompile-bound-ontology","formatVersion":1,
                 "ontology":{"id":"../escape","name":"Unsafe","version":1}}
                """);

        assertThrows(IllegalArgumentException.class,
                () -> artifacts.validateArtifacts(42L, graph));
        verifyNoInteractions(engine, bindings);
    }

    @Test
    void preparedImportRemovesNewOntologyAndBindingOnRollback() {
        OntologySchema schema = OntologySchema.builder()
                .id("portable").name("Portable").version(4)
                .entityTypes(List.of()).relationshipTypes(List.of()).globalRules(List.of()).build();
        GraphOntologyBindingService source = mock(GraphOntologyBindingService.class);
        when(source.resolveActiveOntology(42L)).thenReturn(Optional.of(schema));
        UnifiedGraph incoming = new UnifiedGraph();
        new BoundOntologyUnifiedGraphArtifacts(source, mock(ProcessEngineService.class))
                .contribute(42L, incoming);
        ProcessEngineService engine = mock(ProcessEngineService.class);
        when(engine.supportsOntologySnapshotRemoval()).thenReturn(true);
        GraphOntologyBindingService bindings = mock(GraphOntologyBindingService.class);
        when(bindings.resolveActiveOntology(99L)).thenReturn(Optional.empty());
        BoundOntologyUnifiedGraphArtifacts artifacts =
                new BoundOntologyUnifiedGraphArtifacts(bindings, engine);

        var prepared = artifacts.prepareArtifacts(99L, incoming, new UnifiedGraph());
        assertEquals(1, prepared.commit());
        prepared.rollback();
        prepared.rollback();

        verify(bindings).bindOntology(99L, "portable", 4);
        verify(bindings, times(1)).unbindOntology(99L);
        verify(engine, times(1)).removeOntologySchemaSnapshot("portable", 4);
    }
}
