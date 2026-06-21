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
package ai.kompile.graph.reasoning.learning;

import ai.kompile.graph.reasoning.fol.MebnInferenceService;
import ai.kompile.graph.reasoning.mebn.MFrag;
import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link MebnWeightSerializer}: verifies that MEBN learned edge strengths round-trip
 * faithfully through {@link MebnWeightSerializer#strengthsToJson} and
 * {@link MebnWeightSerializer#applyStrengths}.
 */
class MebnWeightSerializerTest {

    private ReasoningGraph graph;

    @BeforeEach
    void buildGraph() {
        MutableReasoningGraph g = new MutableReasoningGraph();
        g.addEntity(GraphEntity.builder("alice").type("Person").label("Alice").build());
        g.addEntity(GraphEntity.builder("bob").type("Person").label("Bob").build());
        g.addEntity(GraphEntity.builder("carol").type("Person").label("Carol").build());
        graph = g;
    }

    /** Helper: read the strength of the {@code parent→child} edge across all MFrags. */
    private static double edgeStrength(MTheory theory, String parent, String child) {
        String key = parent + "->" + child;
        for (MFrag m : theory.getMFrags()) {
            if (m.getEdgeStrengths().containsKey(key)) {
                return m.getEdgeStrength(parent, child);
            }
        }
        return Double.NaN;
    }

    @Test
    @DisplayName("strengthsToJson / applyStrengths round-trips a learned edge strength exactly")
    void strengthsRoundTrip() {
        MTheory theory = MebnInferenceService.buildCausalTheory(graph, "Person", "cause", "effect", 0.3);

        // Simulate learning by setting a known strength directly
        double learnedStrength = 0.77;
        for (MFrag m : theory.getMFrags()) {
            if (m.getEdgeStrengths().containsKey("cause->effect")) {
                m.setEdgeStrength("cause", "effect", learnedStrength);
            }
        }

        // Serialize
        String json = MebnWeightSerializer.strengthsToJson(theory);
        assertFalse(json.equals("{}"), "JSON should not be empty for a theory with edges");
        assertTrue(json.contains("cause->effect"), "JSON should contain the edge key");

        // Restore onto a fresh identical theory
        MTheory fresh = MebnInferenceService.buildCausalTheory(graph, "Person", "cause", "effect", 0.3);
        MebnWeightSerializer.applyStrengths(fresh, json);

        double restored = edgeStrength(fresh, "cause", "effect");
        assertEquals(learnedStrength, restored, 1e-9,
                "The edge strength should round-trip exactly through JSON serialization");
    }

    @Test
    @DisplayName("strengthsToJson produces valid JSON parseable by applyStrengths")
    void strengthsToJson_producesParseableJson() {
        MTheory theory = MebnInferenceService.buildCausalTheory(graph, "Person", "cause", "effect", 0.55);

        String json = MebnWeightSerializer.strengthsToJson(theory);

        // Parse it back via the package-visible helper
        Map<String, Double> parsed = MebnWeightSerializer.parseStrengths(json);
        assertFalse(parsed.isEmpty(), "Parsed map should contain at least one entry");

        // Every value must be a finite double in a sensible range
        for (Map.Entry<String, Double> entry : parsed.entrySet()) {
            assertTrue(Double.isFinite(entry.getValue()),
                    "Parsed strength for '" + entry.getKey() + "' must be finite");
        }
    }

    @Test
    @DisplayName("applyStrengths with MebnWeightLearner — learned value round-trips after actual learning")
    void applyStrengths_afterLearning_edgeStrengthRoundTrips() {
        MTheory theory = MebnInferenceService.buildCausalTheory(graph, "Person", "cause", "effect", 0.1);
        MebnInferenceService svc = new MebnInferenceService();

        // Observe all effect RVs as active and learn
        Map<String, Double> observations = new java.util.LinkedHashMap<>();
        svc.infer(graph, theory, Map.of()).keySet().stream()
                .filter(v -> v.startsWith("effect"))
                .forEach(v -> observations.put(v, 1.0));
        assertFalse(observations.isEmpty(), "effect RVs should be present in posteriors");

        new MebnWeightLearner(0.5, 1e-3).learn(theory, graph, observations, 40);
        double learnedStrength = edgeStrength(theory, "cause", "effect");

        // Serialize, then re-apply onto a fresh theory and verify
        String json = MebnWeightSerializer.strengthsToJson(theory);
        MTheory fresh = MebnInferenceService.buildCausalTheory(graph, "Person", "cause", "effect", 0.1);
        MebnWeightSerializer.applyStrengths(fresh, json);

        double restored = edgeStrength(fresh, "cause", "effect");
        assertEquals(learnedStrength, restored, 1e-9,
                "Learned edge strength must survive the strengthsToJson / applyStrengths round-trip");
    }

    @Test
    @DisplayName("applyStrengths on empty JSON leaves the theory unchanged")
    void applyStrengths_emptyJson_noChange() {
        MTheory theory = MebnInferenceService.buildCausalTheory(graph, "Person", "cause", "effect", 0.42);

        MebnWeightSerializer.applyStrengths(theory, "{}");

        // The initial strength should be unchanged
        assertEquals(0.42, edgeStrength(theory, "cause", "effect"), 1e-9,
                "Empty JSON should not change any edge strength");
    }
}
