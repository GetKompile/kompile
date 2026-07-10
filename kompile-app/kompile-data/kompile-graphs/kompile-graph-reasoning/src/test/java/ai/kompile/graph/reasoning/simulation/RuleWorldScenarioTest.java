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
package ai.kompile.graph.reasoning.simulation;

import ai.kompile.graph.reasoning.simulation.GroundTruthManifest.ExpectedEdge;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleWorldScenarioTest {

    private final RuleWorldScenario scenario = new RuleWorldScenario();

    @Test
    void sameSeedIsByteIdentical() {
        ScenarioRun a = scenario.generate(42L, Map.of());
        ScenarioRun b = scenario.generate(42L, Map.of());
        assertEquals(a, b, "generation must be deterministic per (seed, params)");
        assertNotEquals(a, scenario.generate(43L, Map.of()), "different seeds should differ");
    }

    @Test
    void heldOutHeadsAreNeverObserved() {
        ScenarioRun run = scenario.generate(7L, Map.of("people", 60, "noiseRate", 0.1));
        Set<String> observed = observedTripleKeys(run);
        assertFalse(run.groundTruth().expectedInferredEdges().isEmpty(), "should hold out some heads");
        for (ExpectedEdge e : run.groundTruth().expectedInferredEdges()) {
            String key = GroundTruthManifest.edgeKey(e.sourceKey(), e.relationType(), e.targetKey());
            assertFalse(observed.contains(key), "held-out head leaked into observations: " + key);
        }
    }

    @Test
    void ticksNeverReferenceUnintroducedNodesAndPairsAreUnique() {
        ScenarioRun run = scenario.generate(11L, Map.of("ticks", 5));
        assertEquals(5, run.ticks().size());
        Set<String> introduced = new HashSet<>();
        Set<String> pairs = new HashSet<>();
        Map<String, Integer> keyCount = new HashMap<>();
        for (TickBatch tick : run.ticks()) {
            for (ScenarioNode n : tick.nodes()) {
                introduced.add(n.key());
                keyCount.merge(n.key(), 1, Integer::sum);
            }
            for (ScenarioEdge e : tick.edges()) {
                assertTrue(introduced.contains(e.sourceKey()), "dangling source " + e.sourceKey());
                assertTrue(introduced.contains(e.targetKey()), "dangling target " + e.targetKey());
                assertTrue(pairs.add(e.sourceKey() + "|" + e.targetKey()),
                        "duplicate ordered pair (store keeps one edge per pair): "
                                + e.sourceKey() + "->" + e.targetKey());
            }
        }
        keyCount.forEach((k, c) -> assertEquals(1, c, "node introduced twice: " + k));
    }

    @Test
    void noiseIsTrackedAndNeverCoincidesWithTruth() {
        ScenarioRun run = scenario.generate(13L, Map.of("noiseRate", 0.2));
        Set<String> truthKeys = new HashSet<>();
        for (ExpectedEdge e : run.groundTruth().expectedInferredEdges()) {
            truthKeys.add(GroundTruthManifest.edgeKey(e.sourceKey(), e.relationType(), e.targetKey()));
        }
        int noiseEdges = 0;
        for (TickBatch tick : run.ticks()) {
            for (ScenarioEdge e : tick.edges()) {
                if (e.noise()) {
                    noiseEdges++;
                    String key = GroundTruthManifest.edgeKey(e.sourceKey(), e.relationType(), e.targetKey());
                    assertTrue(run.groundTruth().corruptedEdgeKeys().contains(key),
                            "noise edge missing from corruptedEdgeKeys: " + key);
                    assertFalse(truthKeys.contains(key), "noise edge equals a held-out head: " + key);
                }
            }
        }
        assertEquals(run.groundTruth().corruptedEdgeKeys().size(), noiseEdges,
                "every corrupted key corresponds to exactly one planted noise edge");
        assertTrue(noiseEdges > 0, "noiseRate=0.2 should plant noise");
    }

    @Test
    void namesAreUniqueAcrossAllNodes() {
        ScenarioRun run = scenario.generate(3L, Map.of("people", 120, "orgs", 20, "cities", 20));
        Set<String> names = new HashSet<>();
        for (TickBatch tick : run.ticks()) {
            for (ScenarioNode n : tick.nodes()) {
                assertTrue(names.add(n.name()), "duplicate entity name (atoms resolve by name): " + n.name());
            }
        }
        assertEquals(run.totalNodes(), names.size());
    }

    @Test
    void manifestDeclaresGeneratingRules() {
        ScenarioRun run = scenario.generate(1L, Map.of());
        assertEquals(3, run.groundTruth().generatingRules().size());
    }

    private static Set<String> observedTripleKeys(ScenarioRun run) {
        Set<String> keys = new HashSet<>();
        for (TickBatch tick : run.ticks()) {
            for (ScenarioEdge e : tick.edges()) {
                keys.add(GroundTruthManifest.edgeKey(e.sourceKey(), e.relationType(), e.targetKey()));
            }
        }
        return keys;
    }
}
