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
package ai.kompile.graph.reasoning.argument;

import ai.kompile.graph.reasoning.argument.QbafWeightLearner.LabeledClaim;
import ai.kompile.graph.reasoning.argument.QbafWeightLearner.LearnResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link QbafWeightLearner}: finite-difference learning of per-evidence-kind weights against
 * labeled verdicts, with a holdout gate mirroring the beatsFold pattern.
 */
class QbafWeightLearnerTest {

    private static final List<String> PROV = List.of("p");

    /** A diagnostic "direct-fact" item (PRO when target true, CON when false) at high confidence. */
    private static EvidenceItem directFact(double target) {
        return target >= 0.5
                ? EvidenceItem.pro("direct", 0.9, "direct-fact", PROV)
                : EvidenceItem.con("direct", 0.9, "direct-fact", PROV);
    }

    /** A noise "kge" item: PRO at a constant middling confidence, uncorrelated with the target. */
    private static EvidenceItem kgeNoise() {
        return EvidenceItem.pro("kge", 0.5, "kge", PROV);
    }

    @Test
    @DisplayName("recovery: diagnostic kind outweighs noise kind; train loss drops; holdout beats default")
    void recoversDiagnosticWeight() {
        // 40 claims; target has an odd period (i%3) so the i%4==0 holdout is a MIXED class set.
        List<LabeledClaim> claims = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            double target = (i % 3 == 0) ? 1.0 : 0.0;
            claims.add(new LabeledClaim("c" + i, 0.5,
                    List.of(directFact(target), kgeNoise()), target));
        }

        LearnResult r = new QbafWeightLearner().learn(claims);

        double wDirect = r.weights().get("direct-fact");
        double wKge    = r.weights().get("kge");
        assertTrue(wDirect > wKge,
                "diagnostic direct-fact weight should exceed noise kge weight: direct=" + wDirect + " kge=" + wKge);
        assertTrue(r.trainLossCurveLast() < r.trainLossCurveFirst() + 1e-9,
                "train loss should not increase: first=" + r.trainLossCurveFirst()
                        + " last=" + r.trainLossCurveLast());
        assertTrue(r.beatsDefault(),
                "learned weights should beat default on the mixed holdout: learned="
                        + r.holdoutLossLearned() + " default=" + r.holdoutLossDefault());
        assertEquals(r.weights(), r.effectiveWeights(), "beatsDefault → effectiveWeights == learned");
    }

    @Test
    @DisplayName("anti-overfit: kind diagnostic on train but anti-diagnostic on holdout → beatsDefault false")
    void antiOverfitGate() {
        // Holdout = indices where i%4==0. Make "kge" predict the target on TRAIN but the
        // OPPOSITE on holdout, so fitting kge on train strictly worsens holdout.
        List<LabeledClaim> claims = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            double target = (i % 2 == 0) ? 1.0 : 0.0;
            boolean holdout = (i % 4 == 0);
            // On train: kge PRO when target true (diagnostic). On holdout: PRO when target FALSE (trap).
            boolean proWhenTrue = !holdout;
            boolean pro = (target >= 0.5) == proWhenTrue;
            EvidenceItem kge = pro
                    ? EvidenceItem.pro("kge", 0.9, "kge", PROV)
                    : EvidenceItem.con("kge", 0.9, "kge", PROV);
            claims.add(new LabeledClaim("c" + i, 0.5, List.of(kge), target));
        }

        LearnResult r = new QbafWeightLearner().learn(claims);
        assertFalse(r.beatsDefault(),
                "adversarial holdout must fail the gate: learned=" + r.holdoutLossLearned()
                        + " default=" + r.holdoutLossDefault());
        // effectiveWeights falls back to all-1.0 defaults when the gate fails.
        for (double v : r.effectiveWeights().values()) {
            assertEquals(1.0, v, 1e-12, "gate failed → effective weights default to 1.0");
        }
    }

    @Test
    @DisplayName("weights stay within [W_MIN, W_MAX] after learning")
    void weightsProjected() {
        List<LabeledClaim> claims = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            double target = (i % 3 == 0) ? 1.0 : 0.0;
            claims.add(new LabeledClaim("c" + i, 0.5, List.of(directFact(target)), target));
        }
        // Aggressive learning rate to push weights toward the bounds.
        LearnResult r = new QbafWeightLearner(
                new QbafWeightLearner.LearnerConfig(2.0, 100, 0.25, 0L)).learn(claims);
        for (double v : r.weights().values()) {
            assertTrue(v >= QbafWeightLearner.W_MIN - 1e-9 && v <= QbafWeightLearner.W_MAX + 1e-9,
                    "weight out of bounds: " + v);
        }
    }

    @Test
    @DisplayName("deterministic: identical inputs → identical LearnResult")
    void deterministic() {
        List<LabeledClaim> claims = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            double target = (i % 3 == 0) ? 1.0 : 0.0;
            claims.add(new LabeledClaim("c" + i, 0.5,
                    List.of(directFact(target), kgeNoise()), target));
        }
        LearnResult a = new QbafWeightLearner().learn(claims);
        LearnResult b = new QbafWeightLearner().learn(claims);
        assertEquals(a.weights(), b.weights());
        assertEquals(a.holdoutLossLearned(), b.holdoutLossLearned(), 1e-12);
        assertEquals(a.trainLossCurveLast(), b.trainLossCurveLast(), 1e-12);
        assertEquals(a.beatsDefault(), b.beatsDefault());
    }

    @Test
    @DisplayName("DF-QuAD and QE strategies both produce valid, finite losses")
    void strategyHook() {
        List<LabeledClaim> claims = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            double target = (i % 2 == 0) ? 1.0 : 0.0;
            claims.add(new LabeledClaim("c" + i, 0.5, List.of(directFact(target)), target));
        }
        LearnResult qe = new QbafWeightLearner(
                QbafWeightLearner.LearnerConfig.defaults(),
                QbafWeightLearner.SemanticsStrategy.QE).learn(claims);
        LearnResult df = new QbafWeightLearner(
                QbafWeightLearner.LearnerConfig.defaults(),
                QbafWeightLearner.SemanticsStrategy.DF_QUAD).learn(claims);
        assertTrue(Double.isFinite(qe.holdoutLossLearned()) && Double.isFinite(df.holdoutLossLearned()));
        assertTrue(qe.weights().containsKey("direct-fact") && df.weights().containsKey("direct-fact"));
    }

    @Test
    @DisplayName("empty claim list is rejected")
    void rejectsEmpty() {
        assertThrows(IllegalArgumentException.class, () -> new QbafWeightLearner().learn(List.of()));
    }
}
