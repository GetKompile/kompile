/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.graph.reasoning.domain;

import ai.kompile.graph.reasoning.mebn.EntityType;
import ai.kompile.graph.reasoning.mebn.MFrag;
import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.mebn.RandomVariable;
import ai.kompile.graph.reasoning.mebn.logic.Constraints;
import ai.kompile.graph.reasoning.mebn.logic.LogicalConstraint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MTheoryStructureTest {

    @Test
    void describesEachFragmentsVariablesContextsAndEdges() {
        EntityType nodes = new EntityType("AllNodes");
        nodes.addEntity("n1");
        RandomVariable relevant = RandomVariable.unary("isRelevant", nodes, RandomVariable.NodeRole.INPUT);
        RandomVariable active = RandomVariable.unary("isActive", nodes, RandomVariable.NodeRole.RESIDENT);
        LogicalConstraint exists = Constraints.entityExists("AllNodes_0");
        MFrag frag = new MFrag("Activity")
                .addResidentNode(active)
                .addInputNode(relevant)
                .addContextConstraint(exists)
                .addParentEdge("isRelevant", "isActive", 0.7);
        frag.setLocalDistribution((rv, strengths) -> new double[]{0.5, 0.5});
        MTheory theory = new MTheory("kg");
        theory.addEntityType(nodes);
        theory.addMFrag(frag);

        MTheoryStructure structure = MTheoryStructure.of(theory);

        assertEquals("kg", structure.name());
        MTheoryStructure.Fragment fragment = structure.fragments().get(0);
        assertEquals("Activity", fragment.name());
        assertEquals(List.of(new MTheoryStructure.Variable(
                "isActive", active.toString(), active.getStates(), "RESIDENT")), fragment.residentNodes());
        assertEquals("INPUT", fragment.inputNodes().get(0).role());
        assertEquals(List.of(exists.toString()), fragment.contexts());
        assertEquals(List.of(new MTheoryStructure.Edge("isRelevant", "isActive", 0.7)), fragment.edges());
    }

    @Test
    void anEdgeKeyWithoutAnArrowKeepsItsParent() {
        assertEquals(new MTheoryStructure.Edge("orphan", "", 0.2), MTheoryStructure.Edge.parse("orphan", 0.2));
        assertEquals(new MTheoryStructure.Edge("a", "b", 0.4), MTheoryStructure.Edge.parse(" a -> b ", 0.4));
    }
}
