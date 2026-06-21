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

package ai.kompile.process.discovery.mining.causal;

import ai.kompile.graph.reasoning.domain.CausalEdgeType;

/**
 * A statistically-characterised directly-follows dependency between two activities — the bridge from
 * "b was seen after a" to "a plausibly causes b", with the evidence to back the claim.
 *
 * @param from        the predecessor activity
 * @param to          the successor activity
 * @param forward     how often {@code to} directly followed {@code from}
 * @param reverse     how often {@code from} directly followed {@code to}
 * @param dependency  Heuristics-Miner dependency measure {@code (fwd−rev)/(fwd+rev+1)} ∈ (−1,1)
 * @param chiSquare   χ² statistic (1 d.f.) testing "{@code to} follows {@code from} beyond its base rate"
 * @param significant whether χ² exceeds the 0.05 critical value (3.841) — i.e. not mere coincidence
 * @param type        the {@link CausalEdgeType} this resolves to (the same enum the attribution layer uses)
 */
public record CausalDependency(
        String from,
        String to,
        long forward,
        long reverse,
        double dependency,
        double chiSquare,
        boolean significant,
        CausalEdgeType type) {
}
