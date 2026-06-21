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

/**
 * Health control-loop setpoints for the {@link PruneCompactOrchestrator}.
 *
 * <p>Values are calibrated for production graphs of a few thousand to tens of thousands of nodes.
 * The hysteresis gap between {@code thetaPruneDefault} and {@code thetaDerive} prevents
 * oscillation between prune-mode and grow-mode.</p>
 *
 * <p>Configurable via {@code graph-extraction-config.json} (hot-reloaded at runtime).</p>
 */
public record HealthSetpoints(
        /** Orphan rate below which the control loop exits aggressive mode (hysteresis low). */
        double orphanLo,
        /** Orphan rate above which the control loop enters aggressive mode. */
        double orphanHi,
        /** Low-confidence-node rate below which the control loop exits aggressive mode. */
        double noiseLo,
        /** Low-confidence-node rate above which the control loop enters aggressive mode. */
        double noiseHi,
        /** Density below which the graph is considered too sparse to prune further. */
        double densityLo,
        /** Conformance score below which the graph is considered non-conformant. */
        double conformanceLo,
        /** Connected-component count above which the component-sweep stage activates. */
        int componentHi,
        /** Default confidence threshold for edge pruning (used in normal mode). */
        double thetaPruneDefault,
        /** Aggressive confidence threshold for edge pruning (used in aggressive mode). */
        double thetaPruneAggressive,
        /** Confidence threshold at which new edges are derived/materialized. */
        double thetaDerive,
        /** Max entity-pair merges per compaction run in normal mode. */
        int maxMergePairsDefault,
        /** Max entity-pair merges per compaction run in aggressive mode. */
        int maxMergePairsAggressive
) {

    /**
     * Production-calibrated defaults.
     *
     * <p>The hysteresis gap: {@code thetaPruneDefault} (0.45) &lt; {@code thetaDerive} (0.50)
     * ensures the prune threshold never exceeds the derive threshold, preventing ping-pong.</p>
     */
    public static HealthSetpoints defaults() {
        return new HealthSetpoints(
                0.05,   // orphanLo   — exit aggressive mode when orphan rate drops below 5%
                0.20,   // orphanHi   — enter aggressive mode when orphan rate exceeds 20%
                0.15,   // noiseLo    — exit aggressive mode when noise rate drops below 15%
                0.30,   // noiseHi    — enter aggressive mode when noise rate exceeds 30%
                0.001,  // densityLo  — graph too sparse; skip pruning
                0.6,    // conformanceLo
                10,     // componentHi
                0.45,   // thetaPruneDefault   (= thetaDerive − 0.05 hysteresis gap)
                0.55,   // thetaPruneAggressive
                0.50,   // thetaDerive
                500,    // maxMergePairsDefault
                2000    // maxMergePairsAggressive
        );
    }
}
