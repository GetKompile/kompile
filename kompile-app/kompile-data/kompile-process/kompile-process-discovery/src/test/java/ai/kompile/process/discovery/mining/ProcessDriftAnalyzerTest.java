/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.process.discovery.mining;

import ai.kompile.process.discovery.ProcessSuggestion;
import ai.kompile.process.discovery.ProcessSuggestion.SuggestedPhase;
import ai.kompile.process.discovery.ProcessSuggestion.SuggestedStep;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the generation diff: step add/remove, dependsOn rewires, performer changes,
 * routing-condition changes, and the confidence-delta floor.
 */
class ProcessDriftAnalyzerTest {

    private static ProcessSuggestion generation(double confidence, SuggestedStep... steps) {
        return ProcessSuggestion.builder()
                .id("g")
                .confidence(confidence)
                .phases(List.of(SuggestedPhase.builder().name("p").steps(List.of(steps)).build()))
                .build();
    }

    private static SuggestedStep step(String name, List<String> dependsOn, String role, String roleSource,
                                      String conditionLabel) {
        return SuggestedStep.builder()
                .name(name).stepType("AUTO")
                .dependsOn(new java.util.ArrayList<>(dependsOn))
                .roleBinding(role).roleSource(roleSource)
                .conditionLabel(conditionLabel)
                .build();
    }

    @Test
    void reportsStructuralFlowPeopleAndRoutingChanges() {
        ProcessSuggestion before = generation(0.75,
                step("Request", List.of(), "alice", "OBSERVED", null),
                step("Approval", List.of("Request"), "bob", "OBSERVED", null),
                step("Invoice", List.of("Approval"), null, null,
                        "Choice: Invoice branch — observed in 5 of 8 cases"));
        ProcessSuggestion after = generation(0.72,
                step("Request", List.of(), "alice", "OBSERVED", null),
                step("Approval", List.of("Request"), "carol", "OBSERVED", null),
                step("Manager Review", List.of("Approval"), null, null, null),
                step("Invoice", List.of("Manager Review"), null, null,
                        "Choice: Invoice branch — observed in 3 of 8 cases"));

        List<String> drift = ProcessDriftAnalyzer.diff(before, after);

        assertTrue(drift.contains("step added: 'Manager Review'"), String.valueOf(drift));
        assertTrue(drift.stream().anyMatch(d ->
                        d.startsWith("'Invoice' now waits for 'Manager Review'") && d.contains("was 'Approval'")),
                "dependsOn rewires must be reported: " + drift);
        assertTrue(drift.stream().anyMatch(d -> d.equals("performer of 'Approval': bob → carol")),
                "performer changes must be reported: " + drift);
        assertTrue(drift.stream().anyMatch(d -> d.startsWith("routing of 'Invoice' changed")
                        && d.contains("3 of 8")),
                "branch-share shifts ride the routing line: " + drift);
        assertTrue(drift.stream().noneMatch(d -> d.startsWith("confidence")),
                "a 0.03 confidence wiggle is noise, not drift: " + drift);
    }

    @Test
    void identicalGenerations_yieldNoDrift_andUnassignedNeverFlaps() {
        ProcessSuggestion a = generation(0.8,
                step("Request", List.of(), null, null, null),
                step("Close", List.of("Request"), "UNASSIGNED", null, null));
        ProcessSuggestion b = generation(0.8,
                step("Request", List.of(), "UNASSIGNED", null, null),
                step("Close", List.of("Request"), null, null, null));

        assertEquals(List.of(), ProcessDriftAnalyzer.diff(a, b),
                "UNASSIGNED and null are the same no-binding state");
    }

    @Test
    void confidenceDelta_reportsOnlyAboveTheFloor() {
        ProcessSuggestion before = generation(0.80, step("A", List.of(), null, null, null));
        ProcessSuggestion after = generation(0.55, step("A", List.of(), null, null, null));

        List<String> drift = ProcessDriftAnalyzer.diff(before, after);

        assertEquals(1, drift.size());
        assertTrue(drift.get(0).startsWith("confidence fell 0.80 → 0.55"), String.valueOf(drift));
    }
}
