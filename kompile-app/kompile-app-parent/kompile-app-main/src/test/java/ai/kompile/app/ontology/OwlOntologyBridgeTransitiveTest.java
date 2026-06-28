/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.app.ontology;

import ai.kompile.graph.reasoning.mebn.type.owl.OwlObjectProperty;
import ai.kompile.graph.reasoning.mebn.type.owl.OwlOntology;
import ai.kompile.process.ontology.OntologySchema;
import ai.kompile.process.ontology.RelationshipTypeDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that a transitive {@link RelationshipTypeDefinition} (has-a / part-of) is mapped to an
 * {@code owl:TransitiveProperty} by {@link OwlOntologyBridge}, which is what lets the OWL-RL reasoner
 * compute the relation's transitive closure (and emit transitive PSL rules) for has-a navigation.
 */
class OwlOntologyBridgeTransitiveTest {

    @Test
    void transitiveRelationship_becomesTransitiveOwlProperty() {
        OntologySchema schema = OntologySchema.builder()
                .name("composition")
                .relationshipTypes(List.of(
                        RelationshipTypeDefinition.builder()
                                .type("partOf").sourceEntityType("Component").targetEntityType("Assembly")
                                .transitive(true).build(),
                        RelationshipTypeDefinition.builder()
                                .type("approvedBy").sourceEntityType("Document").targetEntityType("Person")
                                .build()))
                .build();

        OwlOntology tbox = new OwlOntologyBridge().toOwlOntology(schema);

        assertEquals(2, tbox.objectProperties().size(),
                "both relationships should map to object properties");
        long transitive = tbox.objectProperties().values().stream()
                .filter(OwlObjectProperty::isTransitive)
                .count();
        assertEquals(1, transitive,
                "only the partOf relationship should be marked owl:TransitiveProperty");
    }
}
