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

import java.util.Map;

/**
 * A deterministic synthetic-dataset generator for the graph simulator: given a seed and
 * parameters, produces observed graph ticks plus a {@link GroundTruthManifest} of the patterns
 * the reasoning stack should recover from them.
 *
 * <p>Contract:</p>
 * <ul>
 *   <li><b>Deterministic</b> — the same (seed, params) MUST produce an identical
 *       {@link ScenarioRun}. No wall-clock, no unseeded randomness; temporal scenarios derive
 *       timestamps from a fixed base instant.</li>
 *   <li><b>Infra-free</b> — implementations know nothing about stores, Spring, or HTTP; they emit
 *       {@link ScenarioNode}/{@link ScenarioEdge} in scenario-key space only.</li>
 *   <li><b>Self-describing</b> — {@link #describe()} is constant and cheap; UIs render parameter
 *       forms from it.</li>
 *   <li><b>Honest holdout</b> — anything listed in
 *       {@link GroundTruthManifest#expectedInferredEdges()} must NOT appear as an observed edge in
 *       any tick (that is what makes recovery meaningful). Generators plant supporting instances
 *       so rule mining / closure reasoning has evidence to learn from.</li>
 * </ul>
 */
public interface GraphScenario {

    /** Static identity, documentation, and parameter schema for this generator. */
    ScenarioDescriptor describe();

    /**
     * Generate the full dataset for one simulation run.
     *
     * @param seed   randomness seed; equal seeds yield byte-identical runs
     * @param params values for (a subset of) {@link ScenarioDescriptor#params()}; missing keys
     *               fall back to defaults, out-of-range values are clamped
     * @return the generated ticks + ground truth
     */
    ScenarioRun generate(long seed, Map<String, Object> params);
}
