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
import ai.kompile.graph.reasoning.simulation.PatternRecoveryScorer.FamilyScore;
import ai.kompile.graph.reasoning.simulation.PatternRecoveryScorer.RecoveredEdge;
import ai.kompile.graph.reasoning.simulation.PatternRecoveryScorer.RecoveredState;
import ai.kompile.graph.reasoning.simulation.PatternRecoveryScorer.ScoreReport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternRecoveryScorerTest {

    private static GroundTruthManifest edgeTruth(List<ExpectedEdge> expected) {
        return new GroundTruthManifest(expected, List.of(), List.of(),
                List.of(), List.of(), List.of(), Set.of());
    }

    @Test
    void perfectRecoveryScoresOne() {
        GroundTruthManifest truth = edgeTruth(List.of(
                new ExpectedEdge("p1", "BASED_IN", "c1", "R1"),
                new ExpectedEdge("p2", "BASED_IN", "c2", "R1")));
        RecoveredState recovered = new RecoveredState(List.of(
                new RecoveredEdge("p1", "BASED_IN", "c1", 0.9),
                new RecoveredEdge("p2", "BASED_IN", "c2", 0.8)),
                List.of(), Map.of());

        ScoreReport report = PatternRecoveryScorer.score(truth, recovered);
        FamilyScore f = report.families().get(0);
        assertEquals(PatternRecoveryScorer.FAMILY_INFERRED_EDGES, f.family());
        assertEquals(1.0, f.precision(), 1e-9);
        assertEquals(1.0, f.recall(), 1e-9);
        assertEquals(1.0, f.f1(), 1e-9);
        assertEquals(1.0, report.macroF1(), 1e-9);
        assertTrue(report.hallucinatedEdges().isEmpty());
        // Perfectly correct predictions at ~0.85 confidence: ECE = |1 - meanConf| per bucket share.
        assertTrue(report.ece() > 0.0 && report.ece() < 0.25);
    }

    @Test
    void partialRecoveryAndHallucination() {
        GroundTruthManifest truth = edgeTruth(List.of(
                new ExpectedEdge("p1", "BASED_IN", "c1", "R1"),
                new ExpectedEdge("p2", "BASED_IN", "c2", "R1")));
        RecoveredState recovered = new RecoveredState(List.of(
                new RecoveredEdge("p1", "BASED_IN", "c1", 0.9),
                new RecoveredEdge("p9", "BASED_IN", "c9", 0.7)),   // hallucinated
                List.of(), Map.of());

        ScoreReport report = PatternRecoveryScorer.score(truth, recovered);
        FamilyScore f = report.families().get(0);
        assertEquals(0.5, f.precision(), 1e-9);
        assertEquals(0.5, f.recall(), 1e-9);
        assertEquals(1, report.hallucinatedEdges().size());
        assertEquals("p9", report.hallucinatedEdges().get(0).sourceKey());
    }

    @Test
    void symmetricTruthMatchesEitherDirection() {
        GroundTruthManifest truth = edgeTruth(List.of(
                new ExpectedEdge("a", "PARTNERS_WITH", "b", "sym", true)));
        RecoveredState recovered = new RecoveredState(List.of(
                new RecoveredEdge("b", "PARTNERS_WITH", "a", 0.8)),
                List.of(), Map.of());

        FamilyScore f = PatternRecoveryScorer.score(truth, recovered).families().get(0);
        assertEquals(1, f.truePositives());
        assertEquals(1.0, f.f1(), 1e-9);
    }

    @Test
    void relationMatchingSurvivesCaseAndUnderscoreDrift() {
        GroundTruthManifest truth = edgeTruth(List.of(
                new ExpectedEdge("p1", "BASED_IN", "c1", "R1")));
        RecoveredState recovered = new RecoveredState(List.of(
                new RecoveredEdge("P1", "basedIn", "C1", 0.9)),    // predicate-name round-trip drift
                List.of(), Map.of());

        assertEquals(1, PatternRecoveryScorer.score(truth, recovered).families().get(0).truePositives());
    }

    @Test
    void resolutionsScorePairwise() {
        GroundTruthManifest truth = new GroundTruthManifest(List.of(),
                List.of(Set.of("a", "b", "c")),      // 3 truth pairs: ab ac bc
                List.of(), List.of(), List.of(), List.of(), Set.of());
        RecoveredState recovered = new RecoveredState(List.of(),
                List.of(Set.of("a", "b"), Set.of("x", "y")),   // 1 correct pair + 1 false merge
                Map.of());

        FamilyScore f = PatternRecoveryScorer.score(truth, recovered).families().get(0);
        assertEquals(PatternRecoveryScorer.FAMILY_RESOLUTIONS, f.family());
        assertEquals(3, f.truthCount());
        assertEquals(2, f.recoveredCount());
        assertEquals(1, f.truePositives());
        assertEquals(0.5, f.precision(), 1e-9);
        assertEquals(1.0 / 3.0, f.recall(), 1e-9);
    }

    @Test
    void communitiesScoreOnlyOverPlantedUniverse() {
        GroundTruthManifest truth = new GroundTruthManifest(List.of(), List.of(),
                List.of(Set.of("p1", "p2"), Set.of("p3", "p4")),
                List.of(), List.of(), List.of(), Set.of());
        RecoveredState recovered = new RecoveredState(List.of(), List.of(), Map.of(
                "p1", "A", "p2", "A",          // correct pair
                "p3", "A",                     // wrong community for p3 → pairs p1p3, p2p3 are FPs
                "p4", "B",
                "city9", "A"));                // outside universe: ignored

        FamilyScore f = PatternRecoveryScorer.score(truth, recovered).families().get(0);
        assertEquals(2, f.truthCount());       // p1~p2, p3~p4
        assertEquals(3, f.recoveredCount());   // p1~p2, p1~p3, p2~p3
        assertEquals(1, f.truePositives());
    }

    @Test
    void forbiddenCausalLinksAreCountedAsViolations() {
        GroundTruthManifest truth = new GroundTruthManifest(List.of(), List.of(), List.of(),
                List.of(new ExpectedEdge("rc", "TRIGGERS", "x", "true cause")),
                List.of(new ExpectedEdge("x", "TRIGGERS", "y", "confounded", true)),
                List.of(), Set.of());
        RecoveredState recovered = new RecoveredState(List.of(
                new RecoveredEdge("rc", "TRIGGERS", "x", 0.9),
                new RecoveredEdge("y", "TRIGGERS", "x", 0.6)),     // reverse of forbidden, symmetric
                List.of(), Map.of());

        ScoreReport report = PatternRecoveryScorer.score(truth, recovered);
        assertEquals(1, report.forbiddenViolations());
        FamilyScore causal = report.families().stream()
                .filter(f -> f.family().equals(PatternRecoveryScorer.FAMILY_CAUSAL_LINKS))
                .findFirst().orElseThrow();
        assertEquals(1, causal.truePositives());
    }

    @Test
    void plantedNoiseEchoesAndUnanticipatedRelationsAreNotPenalized() {
        GroundTruthManifest truth = new GroundTruthManifest(
                List.of(new ExpectedEdge("p1", "BASED_IN", "c1", "R1")),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                Set.of(GroundTruthManifest.edgeKey("p7", "BASED_IN", "c7")));
        RecoveredState recovered = new RecoveredState(List.of(
                new RecoveredEdge("p1", "BASED_IN", "c1", 0.9),
                new RecoveredEdge("p7", "BASED_IN", "c7", 0.6),    // echo of planted noise: ignored
                new RecoveredEdge("t1", "PART_OF", "o1", 0.8)),    // unanticipated family
                List.of(), Map.of());

        ScoreReport report = PatternRecoveryScorer.score(truth, recovered);
        FamilyScore f = report.families().get(0);
        assertEquals(1.0, f.precision(), 1e-9, "noise echo must not count as FP");
        assertTrue(report.hallucinatedEdges().isEmpty());
        assertEquals(1, report.unanticipatedEdges().size());
        assertEquals("PART_OF", report.unanticipatedEdges().get(0).relationType());
    }

    @Test
    void emptyTruthProducesNoFamilies() {
        ScoreReport report = PatternRecoveryScorer.score(GroundTruthManifest.empty(),
                RecoveredState.empty());
        assertTrue(report.families().isEmpty());
        assertEquals(0.0, report.macroF1(), 1e-9);
    }
}
