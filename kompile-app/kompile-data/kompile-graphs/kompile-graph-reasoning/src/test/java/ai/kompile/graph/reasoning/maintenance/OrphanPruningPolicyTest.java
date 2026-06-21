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

class OrphanPruningPolicyTest {

    private final OrphanPruningPolicy policy = new OrphanPruningPolicy();

    @Test
    void emptyGraphProducesEmptyResult() {
        PruneResult result = policy.evaluate(new MutableReasoningGraph());
        assertTrue(result.isEmpty());
    }

    @Test
    void singleIsolatedEntityIsOrphan() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity("e1", "PERSON", "Alice");

        PruneResult result = policy.evaluate(graph);

        assertTrue(result.entityIds().contains("e1"), "isolated entity must be selected");
        assertTrue(result.relationIds().isEmpty());
    }

    @Test
    void connectedEntitiesAreNotOrphans() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity("e1", "PERSON", "Alice");
        graph.addEntity("e2", "PERSON", "Bob");
        graph.addRelation("r1", "e1", "e2", "KNOWS", 1.0);

        PruneResult result = policy.evaluate(graph);

        assertFalse(result.entityIds().contains("e1"), "e1 has a relation — not orphan");
        assertFalse(result.entityIds().contains("e2"), "e2 has a relation — not orphan");
    }

    @Test
    void mixedGraphSelectsOnlyOrphans() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity("connected1", "X", "C1");
        graph.addEntity("connected2", "X", "C2");
        graph.addEntity("orphan1", "X", "O1");
        graph.addEntity("orphan2", "X", "O2");
        graph.addRelation("r1", "connected1", "connected2", "REL", 0.8);

        PruneResult result = policy.evaluate(graph);

        assertFalse(result.entityIds().contains("connected1"));
        assertFalse(result.entityIds().contains("connected2"));
        assertTrue(result.entityIds().contains("orphan1"));
        assertTrue(result.entityIds().contains("orphan2"));
        assertEquals(2, result.entityIds().size());
    }

    @Test
    void reasonStringMentionsOrphan() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity("e1", "PERSON", "Alice");

        PruneResult result = policy.evaluate(graph);

        String reason = result.entityReason("e1");
        assertNotNull(reason);
        assertTrue(reason.contains("orphan"), "reason should mention 'orphan'");
    }
}
