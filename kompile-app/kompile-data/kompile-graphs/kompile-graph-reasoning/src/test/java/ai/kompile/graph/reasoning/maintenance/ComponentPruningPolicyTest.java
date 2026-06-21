/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.graph.reasoning.maintenance;

import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ComponentPruningPolicyTest {

    @Test
    void emptyGraphProducesEmptyResult() {
        PruneResult result = new ComponentPruningPolicy(2).evaluate(new MutableReasoningGraph());
        assertTrue(result.isEmpty());
    }

    @Test
    void singletonIsolatedEntityIsSelectedWhenMinSizeIsTwo() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity("singleton", "X", "S");

        PruneResult result = new ComponentPruningPolicy(2).evaluate(graph);

        assertTrue(result.entityIds().contains("singleton"),
                "singleton (component size=1) < minSize=2 → selected");
    }

    @Test
    void singletonIsKeptWhenMinSizeIsOne() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity("s", "X", "S");

        PruneResult result = new ComponentPruningPolicy(1).evaluate(graph);

        assertFalse(result.entityIds().contains("s"),
                "minSize=1 means nothing is ever too small");
    }

    @Test
    void pairIsKeptWhenMinSizeIsTwo() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity("a", "X", "A");
        graph.addEntity("b", "X", "B");
        graph.addRelation("r", "a", "b", "REL", 1.0);

        PruneResult result = new ComponentPruningPolicy(2).evaluate(graph);

        assertFalse(result.entityIds().contains("a"), "component size=2 >= minSize=2 → keep");
        assertFalse(result.entityIds().contains("b"));
    }

    @Test
    void pairIsSelectedWhenMinSizeIsThree() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity("a", "X", "A");
        graph.addEntity("b", "X", "B");
        graph.addRelation("r", "a", "b", "REL", 1.0);

        PruneResult result = new ComponentPruningPolicy(3).evaluate(graph);

        assertTrue(result.entityIds().contains("a"), "component size=2 < minSize=3 → selected");
        assertTrue(result.entityIds().contains("b"));
    }

    @Test
    void largeAndSmallComponentMixed() {
        // Large component: a-b-c (size 3)
        // Small component: x-y (size 2)
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity("a", "X", "A");
        graph.addEntity("b", "X", "B");
        graph.addEntity("c", "X", "C");
        graph.addEntity("x", "X", "X");
        graph.addEntity("y", "X", "Y");
        graph.addRelation("r1", "a", "b", "REL", 1.0);
        graph.addRelation("r2", "b", "c", "REL", 1.0);
        graph.addRelation("r3", "x", "y", "REL", 1.0);

        PruneResult result = new ComponentPruningPolicy(3).evaluate(graph);

        // Large component (size 3) is kept
        assertFalse(result.entityIds().contains("a"));
        assertFalse(result.entityIds().contains("b"));
        assertFalse(result.entityIds().contains("c"));
        // Small component (size 2) is selected
        assertTrue(result.entityIds().contains("x"));
        assertTrue(result.entityIds().contains("y"));
        assertEquals(2, result.entityIds().size());
    }

    @Test
    void undirectedConnectivityIsEnforced() {
        // Directed edge b→a: b should still be reachable from a for component detection
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity("a", "X", "A");
        graph.addEntity("b", "X", "B");
        // relation goes b→a (target=a), so outgoing(a) is empty but incoming(a) has r1
        graph.addRelation("r1", "b", "a", "REL", 1.0);

        // With undirected component detection, a and b should be in the same component
        PruneResult result = new ComponentPruningPolicy(3).evaluate(graph);

        // Both in component of size 2, which is < 3
        assertTrue(result.entityIds().contains("a"));
        assertTrue(result.entityIds().contains("b"));
    }

    @Test
    void invalidMinSizeThrows() {
        assertThrows(IllegalArgumentException.class, () -> new ComponentPruningPolicy(0));
    }
}
