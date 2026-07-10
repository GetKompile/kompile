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
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrgNetworkScenarioTest {

    private final OrgNetworkScenario scenario = new OrgNetworkScenario();

    @Test
    void closureTruthIsEntailedButNeverObserved() {
        ScenarioRun run = scenario.generate(5L, Map.of());

        // Index the observed PART_OF edges: child -> parent.
        Map<String, String> partOf = new HashMap<>();
        Set<String> observedKeys = new HashSet<>();
        for (TickBatch tick : run.ticks()) {
            for (ScenarioEdge e : tick.edges()) {
                observedKeys.add(GroundTruthManifest.edgeKey(e.sourceKey(), e.relationType(), e.targetKey()));
                if ("PART_OF".equals(e.relationType())) {
                    partOf.put(e.sourceKey(), e.targetKey());
                }
            }
        }

        assertFalse(run.groundTruth().expectedInferredEdges().isEmpty());
        for (ExpectedEdge closure : run.groundTruth().expectedInferredEdges()) {
            // Never observed directly...
            String key = GroundTruthManifest.edgeKey(
                    closure.sourceKey(), closure.relationType(), closure.targetKey());
            assertFalse(observedKeys.contains(key), "closure edge was observed: " + key);
            // ...but entailed by a 2-hop observed PART_OF chain.
            String mid = partOf.get(closure.sourceKey());
            assertTrue(mid != null && closure.targetKey().equals(partOf.get(mid)),
                    "closure edge not entailed by observed chain: " + key);
        }
    }

    @Test
    void communitiesMatchTeamMembership() {
        ScenarioRun run = scenario.generate(9L, Map.of("orgs", 2, "deptsPerOrg", 2,
                "teamsPerDept", 2, "peoplePerTeam", 4));

        // Members per team from observed MEMBER_OF edges.
        Map<String, Set<String>> membersByTeam = new HashMap<>();
        for (TickBatch tick : run.ticks()) {
            for (ScenarioEdge e : tick.edges()) {
                if ("MEMBER_OF".equals(e.relationType())) {
                    membersByTeam.computeIfAbsent(e.targetKey(), k -> new HashSet<>()).add(e.sourceKey());
                }
            }
        }
        assertEquals(membersByTeam.size(), run.groundTruth().communities().size(),
                "one planted community per team");
        for (Set<String> community : run.groundTruth().communities()) {
            assertTrue(membersByTeam.containsValue(community),
                    "planted community does not equal a team's member set: " + community);
            assertEquals(4, community.size());
        }
    }

    @Test
    void deterministicPerSeed() {
        assertEquals(scenario.generate(21L, Map.of()), scenario.generate(21L, Map.of()));
    }
}
