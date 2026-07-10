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

/**
 * Pure, side-effect-free model for the "should we churn?" decision — suspend a low-priority tenant so
 * a high-priority workload can run solo on a contended device, then resume it. Churn is only worth it
 * when the device contention we would avoid over the remaining work outweighs {@code k ×} the
 * suspend+resume (spin-up) latency we would pay to do it. Deterministic given its inputs, so the whole
 * decision surface is unit-testable without live subprocesses (mirrors {@link PlacementPolicy}).
 */
public final class ChurnCostModel {

    /**
     * @param contentionPenaltyMs estimated wall-clock cost of continued contention if we do NOT churn
     *                            (e.g. the high-priority job runs this much slower while co-tenanted)
     * @param spinUpLatencyMs     cost to suspend the tenant and later resume/spin it back up
     * @param remainingWorkMs     how long the high-priority workload still has to run (the horizon over
     *                            which the avoided contention accrues)
     */
    public record ChurnRequest(long contentionPenaltyMs, long spinUpLatencyMs, long remainingWorkMs) {}

    /** @param churnBenefitFactor {@code k}: how many spin-ups the avoided contention must beat (≥1). */
    public record Tuning(double churnBenefitFactor) {
        public static Tuning defaults() {
            return new Tuning(1.5);
        }
    }

    /**
     * @param churn        whether to suspend-solo-resume
     * @param netBenefitMs avoided contention minus the churn cost (positive ⇒ churn pays off)
     * @param rationale    human-readable explanation
     */
    public record ChurnDecision(boolean churn, double netBenefitMs, String rationale) {}

    /**
     * Decide whether churning is cheaper than tolerating the contention. Churn iff the avoided
     * contention exceeds {@code k ×} spin-up cost AND there is enough remaining work to amortize the
     * spin-up (no point suspending a tenant for a job that finishes before the resume completes).
     */
    public ChurnDecision decide(ChurnRequest req, Tuning tuning) {
        double avoidedContention = Math.max(0L, req.contentionPenaltyMs());
        double churnCost = tuning.churnBenefitFactor() * Math.max(0L, req.spinUpLatencyMs());
        double net = avoidedContention - churnCost;
        boolean worthByCost = avoidedContention > churnCost;
        boolean enoughHorizon = req.remainingWorkMs() > req.spinUpLatencyMs();
        boolean churn = worthByCost && enoughHorizon;

        String rationale;
        if (!enoughHorizon) {
            rationale = String.format(
                    "hold: remaining work %dms ≤ spin-up %dms — job finishes before resume amortizes",
                    req.remainingWorkMs(), req.spinUpLatencyMs());
        } else if (churn) {
            rationale = String.format(
                    "churn: avoided contention %dms > %.2f× spin-up %dms (net +%.0fms)",
                    req.contentionPenaltyMs(), tuning.churnBenefitFactor(), req.spinUpLatencyMs(), net);
        } else {
            rationale = String.format(
                    "hold: avoided contention %dms ≤ %.2f× spin-up %dms (net %.0fms)",
                    req.contentionPenaltyMs(), tuning.churnBenefitFactor(), req.spinUpLatencyMs(), net);
        }
        return new ChurnDecision(churn, net, rationale);
    }
}
