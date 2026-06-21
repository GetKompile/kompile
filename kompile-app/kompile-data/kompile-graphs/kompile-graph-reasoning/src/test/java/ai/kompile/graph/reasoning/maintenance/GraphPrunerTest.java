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
import ai.kompile.graph.reasoning.model.SimpleGraphEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

class GraphPrunerTest {

    @Test
    void noPoliciesProducesEmptyResult() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity("e1", "X", "E");

        PruneResult result = GraphPruner.builder().build().evaluate(graph);

        assertTrue(result.isEmpty(), "no policies → nothing selected");
    }

    @Test
    void orphanPolicyAloneSelectsIsolatedNodes() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity("orphan", "X", "O");
        graph.addEntity("connected1", "X", "C1");
        graph.addEntity("connected2", "X", "C2");
        graph.addRelation("r", "connected1", "connected2", "REL", 1.0);

        PruneResult result = GraphPruner.builder().orphan().build().evaluate(graph);

        assertTrue(result.entityIds().contains("orphan"));
        assertFalse(result.entityIds().contains("connected1"));
        assertFalse(result.entityIds().contains("connected2"));
    }

    @Test
    void confidenceAndOrphanMergeTogether() {
        // e-low: low confidence but connected → selected by confidence, not orphan
        // e-orphan: high confidence but isolated → selected by orphan, not confidence
        // e-both: low confidence AND isolated → selected by both (merged once)
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity(SimpleGraphEntity.of("e-low", "X", "Low", 0.1));  // low conf
        graph.addEntity(SimpleGraphEntity.of("e-anchor", "X", "Anchor", 0.9));
        graph.addEntity(SimpleGraphEntity.of("e-orphan", "X", "Orphan", 0.9)); // orphan only
        graph.addEntity(SimpleGraphEntity.of("e-both", "X", "Both", 0.05));   // low + orphan
        graph.addRelation("r", "e-low", "e-anchor", "REL", 1.0);

        PruneResult result = GraphPruner.builder().orphan().confidence(0.3).build().evaluate(graph);

        assertTrue(result.entityIds().contains("e-low"),
                "e-low: low confidence → selected by confidence policy");
        assertFalse(result.entityIds().contains("e-anchor"),
                "e-anchor: high confidence, connected → kept");
        assertTrue(result.entityIds().contains("e-orphan"),
                "e-orphan: isolated → selected by orphan policy");
        assertTrue(result.entityIds().contains("e-both"),
                "e-both: low + orphan → selected by both, appears once");
        // e-both appears exactly once in the merged result
        assertEquals(3, result.entityIds().size());
    }

    @Test
    void stalenessAndComponentMerge() {
        Instant cutoff = Instant.parse("2025-01-01T00:00:00Z");
        Instant oldTimestamp = cutoff.minus(10, ChronoUnit.DAYS);

        MutableReasoningGraph graph = new MutableReasoningGraph();
        // Small component (size 1) AND stale
        graph.addEntity(new SimpleGraphEntity("old-singleton", "X", "OS", 1.0, 1.0,
                java.util.Set.of(), null, oldTimestamp, java.util.Map.of()));
        // Large connected component, fresh
        graph.addEntity(SimpleGraphEntity.of("a", "X", "A"));
        graph.addEntity(SimpleGraphEntity.of("b", "X", "B"));
        graph.addEntity(SimpleGraphEntity.of("c", "X", "C"));
        graph.addRelation("r1", "a", "b", "REL", 1.0);
        graph.addRelation("r2", "b", "c", "REL", 1.0);

        PruneResult result = GraphPruner.builder()
                .component(2)
                .staleness(cutoff)
                .build()
                .evaluate(graph);

        assertTrue(result.entityIds().contains("old-singleton"),
                "singleton selected by component AND staleness — appears once");
        assertFalse(result.entityIds().contains("a"));
        assertFalse(result.entityIds().contains("b"));
        assertFalse(result.entityIds().contains("c"));
        assertEquals(1, result.entityIds().size(),
                "old-singleton appears only once despite two policies selecting it");
    }
}
