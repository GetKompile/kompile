/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.subgraph;

import ai.kompile.graph.reasoning.community.CommunityAssignment;
import ai.kompile.graph.reasoning.community.LabelPropagationDetector;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.model.SimpleGraphEntity;
import ai.kompile.graph.reasoning.model.SimpleGraphRelation;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the bridge that materializes communities as subgraph views
 * ({@link CommunityViewMaterializer}).
 */
class CommunityViewMaterializerTest {

    private final CommunityViewMaterializer cvm = new CommunityViewMaterializer();

    /** Path a-b-c-d: two natural communities {a,b} and {c,d} joined by the b-c bridge edge. */
    private MutableReasoningGraph twoCommunityGraph() {
        MutableReasoningGraph g = new MutableReasoningGraph();
        for (String id : new String[]{"a", "b", "c", "d"}) {
            g.addEntity(SimpleGraphEntity.of(id, "T", "Node " + id));
        }
        g.addRelation(SimpleGraphRelation.directed("r-ab", "a", "b", "REL", 1.0));
        g.addRelation(SimpleGraphRelation.directed("r-cd", "c", "d", "REL", 1.0));
        g.addRelation(SimpleGraphRelation.directed("r-bc", "b", "c", "REL", 1.0)); // bridge
        return g;
    }

    private CommunityAssignment twoCommunities() {
        return new CommunityAssignment(Map.of("a", 0, "b", 0, "c", 1, "d", 1), 0.4);
    }

    @Test
    void materializeAll_inducedSubgraphPerCommunity() {
        MutableReasoningGraph g = twoCommunityGraph();
        Map<Integer, SubgraphView> views = cvm.materializeAll(g, twoCommunities());

        assertEquals(2, views.size());

        SubgraphView v0 = views.get(0);
        assertNotNull(v0);
        // radius 0 → induced subgraph: community 0 = {a,b} + only the a-b edge (bridge b-c excluded)
        assertTrue(v0.graph().containsEntity("a"));
        assertTrue(v0.graph().containsEntity("b"));
        assertFalse(v0.graph().containsEntity("c"));
        assertEquals(2, v0.graph().entityCount());
        assertEquals(1, v0.graph().relations().size());

        SubgraphView v1 = views.get(1);
        assertNotNull(v1);
        assertTrue(v1.graph().containsEntity("c"));
        assertTrue(v1.graph().containsEntity("d"));
        assertEquals(2, v1.graph().entityCount());
    }

    @Test
    void materializeSingleCommunityById() {
        MutableReasoningGraph g = twoCommunityGraph();
        SubgraphView v = cvm.materialize(g, twoCommunities(), 1, 0);
        assertTrue(v.graph().containsEntity("c"));
        assertTrue(v.graph().containsEntity("d"));
        assertFalse(v.graph().containsEntity("a"));
        assertEquals(2, v.graph().entityCount());
    }

    @Test
    void radiusOneIncludesBoundaryHalo() {
        MutableReasoningGraph g = twoCommunityGraph();
        // community 0 = {a,b}; radius 1 pulls in c across the b-c bridge
        Map<Integer, SubgraphView> views = cvm.materializeAll(g, twoCommunities(), 1, 0);
        assertTrue(views.get(0).graph().containsEntity("c"),
                "radius 1 should include the one-hop boundary node c");
    }

    @Test
    void endToEnd_detectorThenMaterialize_inducedViewsPartitionNodes() {
        MutableReasoningGraph g = twoCommunityGraph();
        CommunityAssignment ca = new LabelPropagationDetector().detect(g, 42L);

        Map<Integer, SubgraphView> views = cvm.materializeAll(g, ca);

        // One view per detected community
        assertEquals(ca.communityCount(), views.size());
        // Induced (radius 0) views partition the nodes: every node appears in exactly one view
        int totalNodes = views.values().stream().mapToInt(v -> v.graph().entityCount()).sum();
        assertEquals(g.entityCount(), totalNodes);
    }
}
