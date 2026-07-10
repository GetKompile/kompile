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

package ai.kompile.process.discovery.mining.convert;

import ai.kompile.process.discovery.ProcessSuggestion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic narration: business-sounding names from the flow's business endpoints, and a
 * coherent multi-sentence description that mentions every step and reads dependency/caveat facts
 * from the mined structure only.
 */
class ProcessNarratorTest {

    private static ProcessSuggestion.SuggestedStep step(String name, List<String> dependsOn, String role) {
        ProcessSuggestion.SuggestedStep s = ProcessSuggestion.SuggestedStep.builder()
                .name(name).stepType("AUTO").description("Discovered activity \"" + name + "\"")
                .build();
        if (dependsOn != null) {
            s.setDependsOn(new ArrayList<>(dependsOn));
        }
        s.setRoleBinding(role);
        return s;
    }

    private static ProcessSuggestion suggestion(ProcessSuggestion.SuggestedStep... steps) {
        return ProcessSuggestion.builder()
                .name("raw")
                .description("Discovered by the Inductive Miner from 4 case(s) over the knowledge graph")
                .confidence(0.62)
                .phases(List.of(ProcessSuggestion.SuggestedPhase.builder()
                        .name("Phase 1").steps(List.of(steps)).build()))
                .structuredEvidence(new ArrayList<>(List.of(
                        ProcessSuggestion.StructuredEvidence.builder()
                                .type("ENTAILED")
                                .description("Claim Intake ⊨ precedes Claim Payout (posterior 0.91)")
                                .build(),
                        ProcessSuggestion.StructuredEvidence.builder()
                                .type("CONTRADICTION")
                                .description("precedes(Remediation, Security Alert) temporally refuted (+0/-3)")
                                .build())))
                .build();
    }

    @Test
    void businessName_fromBusinessEndpoints_skippingCarriers() {
        ProcessSuggestion s = suggestion(
                step("Email Message", null, null),
                step("Claim Intake", null, null),
                step("Email Message", null, null),
                step("Claim Payout", null, null));
        assertEquals("Claim Intake → Claim Payout process", ProcessNarrator.businessName(s),
                "carriers must never be name endpoints");

        ProcessSuggestion carriersOnly = suggestion(step("Email Message", null, null));
        assertEquals("Email Message process", ProcessNarrator.businessName(carriersOnly),
                "carriers-only flows fall back to the carrier endpoints");
    }

    @Test
    void narrate_isCoherent_mentionsEverything_andOnlyGivenFacts() {
        ProcessSuggestion s = suggestion(
                step("Claim Intake", null, "CLAIMS_AGENT"),
                step("Coverage Review", List.of("Claim Intake"), null),
                step("Claim Payout", List.of("Coverage Review", "Claim Intake"), null));

        String narrative = ProcessNarrator.narrate(s);

        assertTrue(narrative.contains("4 cases"), narrative);
        assertTrue(narrative.contains("62% confidence"), narrative);
        assertTrue(narrative.contains("Claim Intake → Coverage Review → Claim Payout"),
                "the flow reads in mined order: " + narrative);
        assertTrue(narrative.contains("Claim Payout does not start until Coverage Review and Claim Intake are complete."),
                "non-trivial dependencies are spelled out: " + narrative);
        assertTrue(narrative.contains("Roles involved: CLAIMS_AGENT"), narrative);
        assertTrue(narrative.contains("the reasoner entailed"), narrative);
        assertTrue(narrative.contains("Caveats:"), narrative);
    }
}
