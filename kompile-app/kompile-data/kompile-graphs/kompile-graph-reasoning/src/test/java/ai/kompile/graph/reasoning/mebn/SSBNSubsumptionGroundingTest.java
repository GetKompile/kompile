/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.mebn;

import ai.kompile.graph.reasoning.bayesian.BayesianNetwork;
import ai.kompile.graph.reasoning.bayesian.BayesianNode;
import ai.kompile.graph.reasoning.mebn.logic.KnowledgeBase;
import ai.kompile.graph.reasoning.mebn.type.TypeHierarchy;
import ai.kompile.graph.reasoning.mebn.type.TypeRegistry;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration check for is-a (subsumption) navigation in SSBN grounding.
 *
 * <p>An RV declared over a SUPERTYPE must ground over the instances of its declared SUBTYPES —
 * but only when a {@link TypeHierarchy} is wired into the {@link SSBNGenerator}. Without one,
 * grounding stays exact-type (the original behaviour), so this also pins backward compatibility.</p>
 */
class SSBNSubsumptionGroundingTest {

    private static final KnowledgeBase EMPTY_KB = new KnowledgeBase() {
        @Override public boolean entityExists(String id)                            { return false; }
        @Override public boolean edgeExists(String s, String t)                     { return false; }
        @Override public boolean edgeExistsOfType(String s, String t, String type) { return false; }
        @Override public Optional<String> getEntityType(String id)                  { return Optional.empty(); }
        @Override public Optional<String> getMetadata(String id, String key)        { return Optional.empty(); }
        @Override public Optional<Double> getEdgeWeight(String s, String t)         { return Optional.empty(); }
        @Override public Set<String> getEntitiesOfType(String type)                 { return Set.of(); }
        @Override public Set<String> getConnectedEntities(String id)                { return Set.of(); }
        @Override public boolean shareProperty(String id1, String id2, String key)  { return false; }
    };

    /** Graph with two Employee instances; nothing is typed exactly "Person". */
    private MutableReasoningGraph employeeGraph() {
        return new MutableReasoningGraph()
                .addEntity("alice", "Employee", "Alice")
                .addEntity("bob",   "Employee", "Bob");
    }

    /** Single-MFrag theory: resident unary RV isRelevant(Person) — Person is the supertype. */
    private MTheory personRelevanceTheory() {
        EntityType person = new EntityType("Person");          // supertype, no direct instances
        RandomVariable isRelevant =
                RandomVariable.unary("isRelevant", person, RandomVariable.NodeRole.RESIDENT);
        MFrag frag = new MFrag("PersonRelevance");
        frag.addResidentNode(isRelevant);
        MTheory theory = new MTheory("subsumptionTest");
        theory.addMFrag(frag);
        return theory;
    }

    @Test
    void rvOverSupertype_groundsSubtypeInstances_onlyWithTypeHierarchy() {
        MutableReasoningGraph graph = employeeGraph();
        MTheory theory = personRelevanceTheory();

        // WITHOUT a hierarchy: "Person" has no direct instances → zero groundings.
        BayesianNetwork without = new SSBNGenerator(theory, EMPTY_KB).generate();
        assertEquals(0, without.size(),
                "Without a TypeHierarchy, an RV over 'Person' should ground nothing (no exact 'Person' instances)");

        // WITH a hierarchy declaring Employee isA Person: the RV over Person grounds the Employees.
        TypeHierarchy hierarchy = new TypeRegistry()
                .declare("Person")
                .declare("Employee")
                .subtype("Employee", "Person")
                .buildFor(graph);

        BayesianNetwork with = new SSBNGenerator(theory, EMPTY_KB)
                .typeHierarchy(hierarchy)
                .generate();

        assertEquals(2, with.size(),
                "With Employee isA Person, the RV over 'Person' should ground both Employee instances");

        String groundedNames = with.getNodes().stream()
                .map(BayesianNode::getVariableName)
                .collect(Collectors.joining(","));
        assertTrue(groundedNames.contains("alice") && groundedNames.contains("bob"),
                "Both Employee instances should be grounded under the Person RV; got: " + groundedNames);
    }

    @Test
    void exactTypeGrounding_stillWorks_withoutHierarchy() {
        // Backward compatibility: an RV over the concrete type still grounds its instances with no
        // TypeHierarchy at all.
        MutableReasoningGraph graph = employeeGraph();
        EntityType employee = EntityType.fromGraph(graph, "Employee"); // exact members: alice, bob
        RandomVariable isRelevant =
                RandomVariable.unary("isRelevant", employee, RandomVariable.NodeRole.RESIDENT);
        MFrag frag = new MFrag("EmployeeRelevance");
        frag.addResidentNode(isRelevant);
        MTheory theory = new MTheory("exactTest");
        theory.addMFrag(frag);

        BayesianNetwork net = new SSBNGenerator(theory, EMPTY_KB).generate();
        assertEquals(2, net.size(),
                "Exact-type grounding must still produce one node per Employee instance (backward compatible)");
    }
}
