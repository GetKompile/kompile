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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ranker's feature vector: fixed order matching {@link SuggestionFeatures#NAMES}, every value
 * in [0,1], graceful zeros on missing data — training rows (recorded at accept/dismiss time) and
 * scoring rows (fresh mining) must be byte-comparable.
 */
class SuggestionFeaturesTest {

    @Test
    void missingData_yieldsZeroVectorOfDeclaredArity() {
        double[] x = SuggestionFeatures.extract(null);
        assertEquals(SuggestionFeatures.NAMES.size(), x.length);
        for (double v : x) {
            assertEquals(0.0, v, 1e-9);
        }
        double[] empty = SuggestionFeatures.extract(ProcessSuggestion.builder().build());
        assertEquals(SuggestionFeatures.NAMES.size(), empty.length);
    }

    @Test
    void richSuggestion_fillsTheExpectedSlots_allInUnitRange() {
        ProcessSuggestion.SuggestedStep withRole = ProcessSuggestion.SuggestedStep.builder()
                .name("Claim Intake").stepType("AUTO").build();
        withRole.setRoleBinding("CLAIMS_AGENT");
        ProcessSuggestion.SuggestedStep withoutRole = ProcessSuggestion.SuggestedStep.builder()
                .name("Claim Payout").stepType("AUTO").build();

        ProcessSuggestion s = ProcessSuggestion.builder()
                .rawConformanceScore(0.72)
                .confidence(0.61)
                .description("Discovered by the Inductive Miner from 5 case(s) over the knowledge graph")
                .phases(List.of(ProcessSuggestion.SuggestedPhase.builder()
                        .name("Phase 1").steps(List.of(withRole, withoutRole)).build()))
                .structuredEvidence(new ArrayList<>(List.of(
                        ProcessSuggestion.StructuredEvidence.builder()
                                .type("ENTAILED").description("a").score(0.9).build(),
                        ProcessSuggestion.StructuredEvidence.builder()
                                .type("ENTAILED").description("b").score(0.7).build(),
                        ProcessSuggestion.StructuredEvidence.builder()
                                .type("CONTRADICTION").description("c").build(),
                        ProcessSuggestion.StructuredEvidence.builder()
                                .type("CAUSAL").description("d").score(0.5).build())))
                .bayesianPosteriors(Map.of("a", 0.4, "b", 0.6))
                .build();

        double[] x = SuggestionFeatures.extract(s);
        assertEquals(0.72, x[0], 1e-9, "rawConformance");
        assertEquals(0.61, x[1], 1e-9, "fusedConfidence");
        assertEquals(0.8, x[2], 1e-9, "entailedMeanPosterior = mean(0.9, 0.7)");
        assertEquals(0.2, x[3], 1e-9, "entailedCount01 = 2/10");
        assertEquals(0.25, x[4], 1e-9, "contradictionShare = 1/4");
        assertEquals(0.5, x[5], 1e-9, "causalMeanDependency");
        assertEquals(0.5, x[6], 1e-9, "bayesianMeanPosterior = mean(0.4, 0.6)");
        assertEquals(2.0 / 12.0, x[7], 1e-9, "stepCount01");
        assertEquals(0.5, x[8], 1e-9, "caseCount01 = 5/10");
        assertEquals(0.5, x[9], 1e-9, "roleCoverage = 1/2");
        for (double v : x) {
            assertTrue(v >= 0.0 && v <= 1.0, "all features in [0,1]");
        }
    }
}
