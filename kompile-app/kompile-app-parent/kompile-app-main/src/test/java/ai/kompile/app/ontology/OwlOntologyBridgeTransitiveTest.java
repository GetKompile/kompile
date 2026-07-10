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

import ai.kompile.graph.reasoning.mebn.type.owl.OwlClass;
import ai.kompile.graph.reasoning.mebn.type.owl.OwlObjectProperty;
import ai.kompile.graph.reasoning.mebn.type.owl.OwlOntology;
import ai.kompile.process.ontology.EntityTypeDefinition;
import ai.kompile.process.ontology.OntologySchema;
import ai.kompile.process.ontology.RelationshipTypeDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * The relation-resolution statistics recorded on a definition's {@code relationResolution}
     * metadata must surface as OWL property axioms: the symmetric flag → owl:SymmetricProperty
     * (prp-symp materializes reverse edges), and a mean fan-out/fan-in of EXACTLY 1 →
     * owl:Functional/InverseFunctionalProperty. Thresholded near-1 averages must NOT (prp-fp is
     * a sameAs hint — a lax axiom would merge distinct individuals).
     */
    @Test
    void relationResolutionMetadata_becomesSymmetricAndFunctionalAxioms() {
        OntologySchema schema = OntologySchema.builder()
                .name("resolved")
                .relationshipTypes(List.of(
                        RelationshipTypeDefinition.builder()
                                .type("collaboratesWith").sourceEntityType("Person").targetEntityType("Person")
                                .metadata(java.util.Map.of("relationResolution", java.util.Map.of(
                                        "symmetric", true,
                                        "avgOutDegree", 2.5,
                                        "avgInDegree", 2.5)))
                                .build(),
                        RelationshipTypeDefinition.builder()
                                .type("hasBadge").sourceEntityType("Person").targetEntityType("Badge")
                                .metadata(java.util.Map.of("relationResolution", java.util.Map.of(
                                        "symmetric", false,
                                        "avgOutDegree", 1.0,   // every person exactly one badge
                                        "avgInDegree", 1.0)))  // every badge exactly one person
                                .build(),
                        RelationshipTypeDefinition.builder()
                                .type("mentions").sourceEntityType("Email").targetEntityType("Topic")
                                .metadata(java.util.Map.of("relationResolution", java.util.Map.of(
                                        "symmetric", false,
                                        "avgOutDegree", 1.2,   // near-1 threshold average: NOT functional
                                        "avgInDegree", 4.0)))
                                .build()))
                .build();

        OwlOntology tbox = new OwlOntologyBridge().toOwlOntology(schema);

        OwlObjectProperty collaborates = property(tbox, "collaboratesWith");
        assertTrue(collaborates.isSymmetric(), "symmetric flag must become owl:SymmetricProperty");

        OwlObjectProperty hasBadge = property(tbox, "hasBadge");
        assertTrue(hasBadge.isFunctional(), "exact fan-out 1 must become owl:FunctionalProperty");
        assertTrue(hasBadge.isInverseFunctional(), "exact fan-in 1 must become owl:InverseFunctionalProperty");
        assertTrue(!hasBadge.isSymmetric(), "symmetric=false must not set the axiom");

        OwlObjectProperty mentions = property(tbox, "mentions");
        assertTrue(!mentions.isFunctional(), "avg fan-out 1.2 is not the strict signal — no functional axiom");
        assertTrue(!mentions.isInverseFunctional(), "fan-in 4.0 is plainly not inverse-functional");
    }

    private static OwlObjectProperty property(OwlOntology tbox, String localName) {
        return tbox.objectProperties().values().stream()
                .filter(p -> localName.equals(p.localName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(localName + " property missing from TBox"));
    }

    /**
     * An entity type's {@code parentType} (is-a) must map to {@code rdfs:subClassOf} so the OWL-RL
     * compiler can emit the {@code cax-sco} type-propagation rule (Dog ⊑ Animal) that classifies
     * subtype instances up the is-a chain during PSL grounding.
     */
    @Test
    void parentType_becomesSubClassOf_forIsAReasoning() {
        OntologySchema schema = OntologySchema.builder()
                .name("zoo")
                .entityTypes(List.of(
                        EntityTypeDefinition.builder().name("Animal").build(),
                        EntityTypeDefinition.builder().name("Dog").parentType("Animal").build()))
                .build();

        OwlOntology tbox = new OwlOntologyBridge().toOwlOntology(schema);

        OwlClass dog = tbox.classes().values().stream()
                .filter(c -> "Dog".equals(c.localName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Dog class missing from TBox"));
        assertTrue(dog.subClassOfIris().stream().anyMatch(iri -> iri.contains("Animal")),
                "Dog.parentType=Animal should map to rdfs:subClassOf so cax-sco classifies Dogs as Animals");
    }
}
