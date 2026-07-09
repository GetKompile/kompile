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
package ai.kompile.graph.reasoning.confidence.ds;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.confidence.Opinion.FusionMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link HighConflictFusion}: SL-vs-PCR5 routing facade. Verifies the low-conflict path stays
 * on the subjective-logic fallback and the high-conflict path triggers PCR5 with a non-dogmatic,
 * open-world-signalled result (no Zadeh-style minority certainty).
 */
class HighConflictFusionTest {

    private static void assertSimplex(Opinion o) {
        assertTrue(o.belief() >= -1e-9 && o.disbelief() >= -1e-9 && o.uncertainty() >= -1e-9,
                "components non-negative: " + o);
        assertEquals(1.0, o.belief() + o.disbelief() + o.uncertainty(), 1e-9, "simplex sum: " + o);
    }

    @Test
    @DisplayName("single source returns as-is, no PCR5")
    void singleSource() {
        Opinion x = new Opinion(0.6, 0.2, 0.2, 0.5);
        HighConflictFusion.Result r = HighConflictFusion.fuse(List.of(x), 0.5, FusionMode.CUMULATIVE);
        assertEquals(x, r.fused());
        assertFalse(r.pcr5Applied());
        assertEquals(0.0, r.maxPairwiseK(), 1e-12);
    }

    @Test
    @DisplayName("low-conflict agreeing sources route to the SL fallback (pcr5Applied=false)")
    void lowConflictUsesFallback() {
        // Two agreeing, well-supported opinions: low pairwise K → SL path.
        Opinion a = new Opinion(0.8, 0.1, 0.1, 0.5);
        Opinion b = new Opinion(0.75, 0.1, 0.15, 0.5);
        HighConflictFusion.Result r = HighConflictFusion.fuse(List.of(a, b), 0.5, FusionMode.AVERAGING);
        assertFalse(r.pcr5Applied(), "agreeing sources must stay on the SL fallback");
        assertEquals(0.0, r.emptyMass(), 1e-12, "no open-world mass on the SL path");
        assertTrue(r.maxPairwiseK() < 0.5, "K should be below threshold: " + r.maxPairwiseK());
        assertSimplex(r.fused());
        // Averaging fusion of two ~0.78 opinions stays near 0.78.
        assertEquals(r.fused(), Opinion.fuse(FusionMode.AVERAGING, List.of(a, b)));
    }

    @Test
    @DisplayName("near-dogmatic contradictory sources trigger PCR5, non-dogmatic result, K>0.5")
    void highConflictTriggersPcr5() {
        // Zadeh-style: one source near-certain TRUE, the other near-certain FALSE.
        Opinion proT = new Opinion(0.9, 0.05, 0.05, 0.5);
        Opinion proF = new Opinion(0.05, 0.9, 0.05, 0.5);
        HighConflictFusion.Result r = HighConflictFusion.fuse(List.of(proT, proF), 0.5, FusionMode.CUMULATIVE);

        assertTrue(r.pcr5Applied(), "high conflict must route to PCR5");
        assertTrue(r.maxPairwiseK() > 0.5, "conflict K should exceed threshold: " + r.maxPairwiseK());
        assertTrue(r.emptyMass() > 0.5, "TBM open-world empty mass should be large: " + r.emptyMass());
        assertSimplex(r.fused());

        // The fused opinion must NOT collapse to a one-sided dogmatic verdict: by symmetry
        // belief ≈ disbelief and the expectation sits near the base rate.
        assertEquals(r.fused().belief(), r.fused().disbelief(), 0.05,
                "symmetric conflict → belief ≈ disbelief, got " + r.fused());
        assertEquals(0.5, r.fused().expectation(), 0.1,
                "expectation should stay near the base rate under symmetric conflict: " + r.fused());
    }

    @Test
    @DisplayName("empty / null input is rejected")
    void rejectsEmpty() {
        assertThrows(IllegalArgumentException.class,
                () -> HighConflictFusion.fuse(List.of(), 0.5, FusionMode.CUMULATIVE));
        assertThrows(IllegalArgumentException.class,
                () -> HighConflictFusion.fuse(null, 0.5, FusionMode.CUMULATIVE));
    }
}
