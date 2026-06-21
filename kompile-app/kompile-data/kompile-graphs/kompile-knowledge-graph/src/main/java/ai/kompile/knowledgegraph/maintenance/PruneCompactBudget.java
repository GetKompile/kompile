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
package ai.kompile.knowledgegraph.maintenance;

import ai.kompile.core.graphrag.maintenance.model.GraphHealthSnapshot;

/**
 * Budget record computed from a {@link GraphHealthSnapshot} and {@link HealthSetpoints}.
 *
 * <p>Controls which prune/compact stages run in {@link PruneCompactOrchestrator}. The budget
 * is recomputed on every run, but the {@code aggressivePruneMode} flag is sticky —
 * once entered (when {@code orphanRate > orphanHi} or {@code noiseRate > noiseHi}), the
 * mode persists until the graph has fully recovered (both rates below the low-side setpoints),
 * preventing oscillation.</p>
 */
public record PruneCompactBudget(
        /** Whether entity compaction + RESOLVES_TO rematerialization should run (P2). */
        boolean runCompaction,
        /** Whether orphan GC should run (P4). */
        boolean runOrphanGc,
        /** Whether the component sweep should run (P5). */
        boolean runComponentSweep,
        /** Confidence threshold for edge pruning (varies by mode). */
        double confidencePruneThreshold,
        /** Max entity-pair merges allowed in this run. */
        int maxMergePairs,
        /** Whether the graph is too sparse and should prefer grow/add over prune. */
        boolean growBiased,
        /** Whether the orchestrator is in aggressive-prune mode (sticky hysteresis). */
        boolean aggressivePruneMode
) {

    /**
     * Compute a budget from the current health snapshot and setpoints.
     *
     * @param health              the current health snapshot
     * @param sp                  the configured setpoints
     * @param currentAggressiveMode whether the orchestrator is currently in aggressive mode
     * @return the budget for this run
     */
    public static PruneCompactBudget from(GraphHealthSnapshot health, HealthSetpoints sp,
                                           boolean currentAggressiveMode) {
        double orphanRate = health.orphanRate();
        double noiseRate = health.lowConfidenceNodeCount() / (double) Math.max(1, health.nodeCount());
        double conformance = health.conformanceScore() != null ? health.conformanceScore() : 1.0;

        // Hysteresis: enter aggressive mode when graph is bloated; exit only when fully recovered
        boolean tooBloated = orphanRate > sp.orphanHi() || noiseRate > sp.noiseHi();
        boolean recoveredFromBloat = orphanRate < sp.orphanLo() && noiseRate < sp.noiseLo();
        boolean newAggressiveMode = currentAggressiveMode
                ? !recoveredFromBloat  // sticky: exit only when both metrics are below lo setpoints
                : tooBloated;          // enter when either metric exceeds hi setpoint

        boolean tooSparse = health.density() < sp.densityLo() || conformance < sp.conformanceLo();

        double threshold = newAggressiveMode ? sp.thetaPruneAggressive() : sp.thetaPruneDefault();
        int maxPairs = newAggressiveMode ? sp.maxMergePairsAggressive() : sp.maxMergePairsDefault();

        return new PruneCompactBudget(
                orphanRate > sp.orphanLo() || noiseRate > sp.noiseLo(),
                orphanRate > sp.orphanLo(),
                health.connectedComponentCount() > sp.componentHi(),
                threshold,
                maxPairs,
                tooSparse && !tooBloated,
                newAggressiveMode
        );
    }

    /**
     * Budget that skips all prune/compact stages — used when the health snapshot is unavailable.
     */
    public static PruneCompactBudget skipAll() {
        return new PruneCompactBudget(false, false, false,
                HealthSetpoints.defaults().thetaPruneDefault(), 0, false, false);
    }
}
