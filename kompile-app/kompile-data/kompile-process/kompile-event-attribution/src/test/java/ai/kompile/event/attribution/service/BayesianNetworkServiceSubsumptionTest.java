/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.event.attribution.service;

import ai.kompile.graph.reasoning.domain.BayesianInferenceResult;
import ai.kompile.graph.reasoning.mebn.EntityType;
import ai.kompile.graph.reasoning.mebn.MFrag;
import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.mebn.RandomVariable;
import ai.kompile.graph.reasoning.mebn.type.TypeHierarchy;
import ai.kompile.graph.reasoning.mebn.type.TypeRegistry;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Verifies the live MEBN query path ({@link BayesianNetworkService#queryWithMTheory}) forwards a
 * {@link TypeHierarchy} into SSBN grounding: an RV over a supertype grounds its subtype instances
 * at query time when a hierarchy is supplied, and stays exact-type without one.
 */
class BayesianNetworkServiceSubsumptionTest {

    private MTheory personRelevanceTheory() {
        EntityType person = new EntityType("Person"); // supertype, no direct instances
        RandomVariable isRelevant =
                RandomVariable.unary("isRelevant", person, RandomVariable.NodeRole.RESIDENT);
        MFrag frag = new MFrag("PersonRelevance");
        frag.addResidentNode(isRelevant);
        MTheory theory = new MTheory("subsumptionTest");
        theory.addMFrag(frag);
        return theory;
    }

    @Test
    void queryWithMTheory_groundsSubtypeInstances_whenTypeHierarchyPassed() {
        BayesianNetworkService service =
                new BayesianNetworkService(mock(KnowledgeGraphService.class));
        MTheory theory = personRelevanceTheory();

        // No hierarchy → "Person" has no exact instances → empty result (exact-type, unchanged).
        BayesianInferenceResult without = service.queryWithMTheory(theory, Map.of());
        assertTrue(without.getPosteriors().isEmpty(),
                "Without a TypeHierarchy, an RV over 'Person' should ground nothing");

        // Hierarchy: Employee isA Person, with two Employee instances.
        MutableReasoningGraph graph = new MutableReasoningGraph()
                .addEntity("alice", "Employee", "Alice")
                .addEntity("bob",   "Employee", "Bob");
        TypeHierarchy hierarchy = new TypeRegistry()
                .declare("Person").declare("Employee").subtype("Employee", "Person")
                .buildFor(graph);

        BayesianInferenceResult with = service.queryWithMTheory(theory, Map.of(), hierarchy);
        assertEquals(2, with.getPosteriors().size(),
                "With Employee isA Person, the RV over 'Person' should ground both Employee instances");
        String keys = String.join(",", with.getPosteriors().keySet());
        assertTrue(keys.contains("alice") && keys.contains("bob"),
                "Posteriors should cover both Employee instances; got: " + keys);
    }
}
