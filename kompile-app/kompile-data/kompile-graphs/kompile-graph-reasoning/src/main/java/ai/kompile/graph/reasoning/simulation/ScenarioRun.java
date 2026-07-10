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

import java.util.List;
import java.util.Objects;

/**
 * A fully generated simulation dataset: the ordered hydration ticks plus the planted ground
 * truth. Produced once per (scenario, seed, params) and immutable thereafter — the runner may
 * apply ticks all at once or stream them, and may re-apply any prefix idempotently.
 *
 * @param scenarioId  {@link ScenarioDescriptor#id()} of the generator
 * @param seed        the seed the dataset was generated from (recorded for reproducibility)
 * @param ticks       hydration increments in application order; see {@link TickBatch} invariant
 * @param groundTruth what the reasoning stack should learn from these observations
 */
public record ScenarioRun(
        String scenarioId,
        long seed,
        List<TickBatch> ticks,
        GroundTruthManifest groundTruth) {

    public ScenarioRun {
        Objects.requireNonNull(scenarioId, "scenarioId must not be null");
        ticks = (ticks == null) ? List.of() : List.copyOf(ticks);
        groundTruth = (groundTruth == null) ? GroundTruthManifest.empty() : groundTruth;
    }

    /** Total nodes across all ticks. */
    public int totalNodes() {
        return ticks.stream().mapToInt(t -> t.nodes().size()).sum();
    }

    /** Total edges across all ticks. */
    public int totalEdges() {
        return ticks.stream().mapToInt(t -> t.edges().size()).sum();
    }
}
