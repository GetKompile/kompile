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

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.model.SimpleGraphEntity;
import ai.kompile.graph.reasoning.model.SimpleGraphRelation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfidencePruningPolicyTest {

    @Test
    void emptyGraphProducesEmptyResult() {
        PruneResult result = new ConfidencePruningPolicy(0.5).evaluate(new MutableReasoningGraph());
        assertTrue(result.isEmpty());
    }

    @Test
    void entityBelowThresholdIsSelected() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        // SimpleGraphEntity.of(id, type, label, weight) sets confidence = weight
        graph.addEntity(SimpleGraphEntity.of("low", "X", "Low", 0.1));
        graph.addEntity(SimpleGraphEntity.of("high", "X", "High", 0.9));

        PruneResult result = new ConfidencePruningPolicy(0.5).evaluate(graph);

        assertTrue(result.entityIds().contains("low"), "confidence 0.1 < 0.5 should be selected");
        assertFalse(result.entityIds().contains("high"), "confidence 0.9 >= 0.5 should be kept");
    }

    @Test
    void entityAtExactThresholdIsKept() {
        // The threshold is a strict lower bound: confidence >= threshold → keep
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity(SimpleGraphEntity.of("exact", "X", "Exact", 0.5));

        PruneResult result = new ConfidencePruningPolicy(0.5).evaluate(graph);

        assertFalse(result.entityIds().contains("exact"),
                "entity at exactly the threshold should NOT be selected");
    }

    @Test
    void relationBelowThresholdIsSelected() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity("e1", "X", "A");
        graph.addEntity("e2", "X", "B");
        // Build relation with low confidence via builder
        GraphRelation lowConf = GraphRelation.builder("r-low", "e1", "e2")
                .type("REL").weight(0.5).confidence(0.05).directed(true).build();
        GraphRelation highConf = GraphRelation.builder("r-high", "e1", "e2")
                .type("REL").weight(0.5).confidence(0.9).directed(true).build();
        graph.addRelation(lowConf);
        graph.addRelation(highConf);

        PruneResult result = new ConfidencePruningPolicy(0.3, 0.3).evaluate(graph);

        assertTrue(result.relationIds().contains("r-low"), "confidence 0.05 < 0.3 should be selected");
        assertFalse(result.relationIds().contains("r-high"), "confidence 0.9 >= 0.3 should be kept");
    }

    @Test
    void independentEntityAndRelationThresholds() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity(SimpleGraphEntity.of("e1", "X", "E1", 0.4)); // below entity threshold 0.5
        graph.addEntity(SimpleGraphEntity.of("e2", "X", "E2", 0.9));
        GraphRelation rel = GraphRelation.builder("r1", "e1", "e2")
                .type("REL").weight(0.5).confidence(0.25).directed(true).build(); // above relation threshold 0.2
        graph.addRelation(rel);

        PruneResult result = new ConfidencePruningPolicy(0.5, 0.2).evaluate(graph);

        assertTrue(result.entityIds().contains("e1"));
        assertFalse(result.entityIds().contains("e2"));
        assertFalse(result.relationIds().contains("r1"), "confidence 0.25 >= 0.2 → keep");
    }

    @Test
    void reasonStringMentionsConfidence() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity(SimpleGraphEntity.of("e1", "X", "E", 0.1));

        PruneResult result = new ConfidencePruningPolicy(0.5).evaluate(graph);

        String reason = result.entityReason("e1");
        assertNotNull(reason);
        assertTrue(reason.contains("confidence"), "reason should mention 'confidence'");
        assertTrue(reason.contains("threshold"), "reason should mention 'threshold'");
    }
}
