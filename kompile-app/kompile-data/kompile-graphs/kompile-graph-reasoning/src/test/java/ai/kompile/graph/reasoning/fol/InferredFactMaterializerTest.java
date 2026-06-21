/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.fol;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the infra-free {@link InferredFactMaterializer}: it writes inferred facts into a generic
 * {@link MutableReasoningGraph} as INFERRED relations / attributes, with no store dependency.
 */
class InferredFactMaterializerTest {

    @Test
    void materialize_binaryAtom_addsInferredRelationWithSoftTruth() {
        MutableReasoningGraph g = new MutableReasoningGraph();
        InferredFact fact = InferredFact.of("Causes(a, b)", 0.8, List.of(), List.of("r1"), "run-1", 1L);

        var result = InferredFactMaterializer.materialize(List.of(fact), g);

        assertEquals(1, result.relationsAdded());
        assertEquals(0, result.skipped());
        assertTrue(g.entity("a").isPresent(), "endpoint a auto-created");
        assertTrue(g.entity("b").isPresent(), "endpoint b auto-created");

        List<GraphRelation> rels = g.outgoing("a");
        assertEquals(1, rels.size());
        GraphRelation rel = rels.get(0);
        assertEquals("Causes", rel.type());
        assertEquals("b", rel.targetId());
        assertEquals(0.8, rel.weight(), 1e-9, "soft-truth value carried as relation weight");
        assertTrue(rel.tags().contains(InferredFactMaterializer.INFERRED_TAG));
    }

    @Test
    void materialize_unaryAtom_setsInferredAttributeWithoutClobberingExisting() {
        MutableReasoningGraph g = new MutableReasoningGraph();
        g.addEntity(GraphEntity.builder("alice").type("PERSON").label("Alice").attribute("existing", 1).build());
        InferredFact fact = InferredFact.of("Fraudulent(alice)", 0.9, List.of(), List.of(), "run-2", 1L);

        var result = InferredFactMaterializer.materialize(List.of(fact), g);

        assertEquals(1, result.attributesSet());
        GraphEntity e = g.entity("alice").orElseThrow();
        assertEquals("PERSON", e.type(), "existing type preserved on upsert");
        assertEquals(1, ((Number) e.attributes().get("existing")).intValue(), "existing attribute preserved");
        assertEquals(0.9, ((Number) e.attributes().get("inferred.Fraudulent")).doubleValue(), 1e-9);
        assertTrue(e.tags().contains(InferredFactMaterializer.INFERRED_TAG));
    }

    @Test
    void parseAtom_handlesBinaryUnaryAndNonAtoms() {
        assertEquals("Causes", InferredFactMaterializer.parseAtom("Causes(a, b)").predicate());
        assertEquals(List.of("a", "b"), InferredFactMaterializer.parseAtom("Causes(a, b)").args());
        assertEquals(List.of("alice"), InferredFactMaterializer.parseAtom("State(alice)").args());
        assertNull(InferredFactMaterializer.parseAtom("not-an-atom"), "bare text is not an atom");
        assertNull(InferredFactMaterializer.parseAtom(null));
    }
}
