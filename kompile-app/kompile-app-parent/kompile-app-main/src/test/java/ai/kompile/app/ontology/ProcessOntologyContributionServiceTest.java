/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package ai.kompile.app.ontology;

import ai.kompile.knowledgegraph.staging.ModelTrainedEvent;
import ai.kompile.process.ontology.Cardinality;
import ai.kompile.process.ontology.OntologySchema;
import ai.kompile.process.ontology.RelationshipTypeDefinition;
import ai.kompile.process.service.ProcessEngineService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The miner's ontology contribution: PRECEDES (transitive) + DIRECTLY_FOLLOWS join the governing
 * ontology exactly once, via the same update-and-rebind path post-OWL type induction uses.
 */
@ExtendWith(MockitoExtension.class)
class ProcessOntologyContributionServiceTest {

    private static final long FS = 42L;

    @Mock
    private GraphOntologyBindingService bindingService;

    @Mock
    private ProcessEngineService processEngineService;

    @InjectMocks
    private ProcessOntologyContributionService service;

    private static OntologySchema schema(List<RelationshipTypeDefinition> relationships) {
        return OntologySchema.builder()
                .id("ont-1")
                .version(1)
                .relationshipTypes(relationships)
                .build();
    }

    @Test
    void contributesBothRelations_updatesAndRebinds() {
        when(bindingService.resolveActiveOntology(FS)).thenReturn(Optional.of(schema(new ArrayList<>())));
        OntologySchema updated = schema(List.of());
        updated.setVersion(2);
        when(processEngineService.updateOntology(eq("ont-1"), any())).thenReturn(updated);

        assertTrue(service.contributeProcessRelations(FS));

        ArgumentCaptor<OntologySchema> captor = ArgumentCaptor.forClass(OntologySchema.class);
        verify(processEngineService).updateOntology(eq("ont-1"), captor.capture());
        List<RelationshipTypeDefinition> written = captor.getValue().getRelationshipTypes();

        RelationshipTypeDefinition precedes = written.stream()
                .filter(r -> "PRECEDES".equals(r.getType())).findFirst().orElseThrow();
        assertTrue(precedes.isTransitive(),
                "PRECEDES must be transitive so OWL-RL computes the precedence closure");
        RelationshipTypeDefinition df = written.stream()
                .filter(r -> "DIRECTLY_FOLLOWS".equals(r.getType())).findFirst().orElseThrow();
        assertFalse(df.isTransitive(), "instance adjacency must NOT be transitive");

        verify(bindingService).bindOntology(FS, "ont-1", 2);
    }

    @Test
    void alreadyContributed_isIdempotentNoOp() {
        List<RelationshipTypeDefinition> existing = List.of(
                RelationshipTypeDefinition.builder().type("PRECEDES").transitive(true)
                        .cardinality(Cardinality.MANY_TO_MANY).build(),
                RelationshipTypeDefinition.builder().type("DIRECTLY_FOLLOWS")
                        .cardinality(Cardinality.MANY_TO_MANY).build());
        when(bindingService.resolveActiveOntology(FS)).thenReturn(Optional.of(schema(existing)));

        assertFalse(service.contributeProcessRelations(FS));
        verify(processEngineService, never()).updateOntology(any(), any());
    }

    @Test
    void noOntologyResolvable_fallsBackToAutoProvision_thenSkipsWhenStillAbsent() {
        when(bindingService.resolveActiveOntology(FS)).thenReturn(Optional.empty());
        when(bindingService.autoProvisionStructuralOntology(FS)).thenReturn(Optional.empty());

        assertFalse(service.contributeProcessRelations(FS));
        verify(bindingService).autoProvisionStructuralOntology(FS);
        verify(processEngineService, never()).updateOntology(any(), any());
    }

    @Test
    void listenerIgnoresNonMinedModelEvents() {
        service.onProcessMined(new ModelTrainedEvent(this, "psl", FS, Path.of("/tmp/x"), "psl-cascade"));
        verifyNoInteractions(bindingService, processEngineService);
    }

    @Test
    void listenerContributesOnMinedEvents() {
        when(bindingService.resolveActiveOntology(anyLong())).thenReturn(Optional.of(schema(new ArrayList<>())));
        OntologySchema updated = schema(List.of());
        updated.setVersion(3);
        when(processEngineService.updateOntology(any(), any())).thenReturn(updated);

        service.onProcessMined(new ModelTrainedEvent(this, "psl", FS, Path.of("/tmp/x"), "psl-mined"));

        verify(processEngineService).updateOntology(eq("ont-1"), any());
        verify(bindingService).bindOntology(FS, "ont-1", 3);
    }
}
