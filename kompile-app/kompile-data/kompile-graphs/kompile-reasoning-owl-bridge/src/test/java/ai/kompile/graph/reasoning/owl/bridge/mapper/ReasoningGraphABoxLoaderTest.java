/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.owl.bridge.mapper;

import ai.kompile.graph.reasoning.mebn.type.owl.OwlIri;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReasoningGraphABoxLoaderTest {

    @Test
    void load_writesClassAssertionForEveryTypeMembership() throws Exception {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity(GraphEntity.builder("acct-1")
                .type("Account")
                .label("Account 1")
                .attribute("additionalTypes", List.of("Customer"))
                .attribute("ontology.typeCandidates", List.of(
                        java.util.Map.of("type", "AuditableEntity", "source", "owl-rl", "confidence", 1.0d)))
                .build());

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(OwlIri.ontologyIri("ABoxLoaderTest")));
        ReasoningGraphABoxLoader.load(graph, ontology, manager);

        OWLDataFactory df = manager.getOWLDataFactory();
        OWLNamedIndividual individual = df.getOWLNamedIndividual(IRI.create(OwlIri.indIri("acct-1")));
        assertClassAssertion(ontology, df.getOWLClass(IRI.create(OwlIri.classIri("Account"))), individual);
        assertClassAssertion(ontology, df.getOWLClass(IRI.create(OwlIri.classIri("Customer"))), individual);
        assertClassAssertion(ontology, df.getOWLClass(IRI.create(OwlIri.classIri("AuditableEntity"))), individual);
    }

    @Test
    void extractAbox_preservesMultipleClassAssertionsAsTypeMemberships() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create(OwlIri.ontologyIri("ABoxExtractTest")));
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLNamedIndividual individual = df.getOWLNamedIndividual(IRI.create(OwlIri.indIri("acct-1")));
        manager.addAxiom(ontology, df.getOWLClassAssertionAxiom(
                df.getOWLClass(IRI.create(OwlIri.classIri("Account"))), individual));
        manager.addAxiom(ontology, df.getOWLClassAssertionAxiom(
                df.getOWLClass(IRI.create(OwlIri.classIri("Customer"))), individual));

        MutableReasoningGraph graph = ReasoningGraphABoxLoader.extractAbox(ontology);
        GraphEntity entity = graph.entity("acct-1").orElseThrow();

        assertTrue(Set.of("Account", "Customer").contains(entity.type()));
        assertEquals(Set.of("Account", "Customer"), Set.copyOf(new ArrayList<>(entity.typeMemberships())));
    }

    private static void assertClassAssertion(OWLOntology ontology,
                                             OWLClass owlClass,
                                             OWLNamedIndividual individual) {
        assertTrue(ontology.containsAxiom(
                        ontology.getOWLOntologyManager().getOWLDataFactory()
                                .getOWLClassAssertionAxiom(owlClass, individual)),
                "Expected class assertion " + owlClass + "(" + individual + ")");
    }
}
