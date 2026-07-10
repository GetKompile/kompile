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

package ai.kompile.app.services.placement;

import ai.kompile.app.services.placement.ChurnCostModel.ChurnDecision;
import ai.kompile.app.services.placement.ChurnCostModel.ChurnRequest;
import ai.kompile.app.services.placement.ChurnCostModel.Tuning;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChurnCostModelTest {

    private final ChurnCostModel model = new ChurnCostModel();

    @Test
    void churnsWhenAvoidedContentionBeatsSpinUp() {
        // contention 10s, spin-up 1s, k=1.5 → 10000 > 1500, plenty of remaining work.
        ChurnDecision d = model.decide(new ChurnRequest(10_000, 1_000, 30_000), Tuning.defaults());
        assertTrue(d.churn(), d.rationale());
        assertTrue(d.netBenefitMs() > 0);
    }

    @Test
    void holdsWhenSpinUpDominates() {
        // contention 1s, spin-up 5s, k=1.5 → 1000 ≤ 7500.
        ChurnDecision d = model.decide(new ChurnRequest(1_000, 5_000, 60_000), Tuning.defaults());
        assertFalse(d.churn(), d.rationale());
        assertTrue(d.netBenefitMs() < 0);
    }

    @Test
    void holdsWhenNotEnoughRemainingWorkToAmortizeSpinUp() {
        // Contention would be worth churning, BUT the job finishes (2s) before the 5s spin-up amortizes.
        ChurnDecision d = model.decide(new ChurnRequest(10_000, 5_000, 2_000), Tuning.defaults());
        assertFalse(d.churn(), d.rationale());
    }

    @Test
    void higherFactorMakesChurnHarder() {
        ChurnRequest req = new ChurnRequest(3_000, 1_000, 30_000);
        assertTrue(model.decide(req, new Tuning(1.5)).churn());   // 3000 > 1500
        assertFalse(model.decide(req, new Tuning(4.0)).churn());  // 3000 ≤ 4000
    }
}
