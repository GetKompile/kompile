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
package ai.kompile.graph.reasoning.pruning;

import ai.kompile.graph.reasoning.confidence.StrengthBand;

/**
 * Immutable policy record that drives {@link OpinionPruner}.
 *
 * <p>All thresholds operate on Subjective-Logic {@link ai.kompile.graph.reasoning.confidence.Opinion}
 * dimensions rather than on a raw scalar confidence, so the decision is epistemically richer:
 * an edge with high belief but also high uncertainty can still be pruned if its projected
 * expectation is too low or its uncertainty is too high.</p>
 *
 * <h3>Decision rules (applied in order, first match wins)</h3>
 * <ol>
 *   <li>If {@code pruneSuppressedBand} and {@code opinion.projectBand() == SUPPRESSED} → PRUNE</li>
 *   <li>If {@code opinion.belief() < minBelief} → PRUNE</li>
 *   <li>If {@code opinion.uncertainty() > maxUncertainty} → PRUNE</li>
 *   <li>If {@code opinion.expectation() < minExpectation} → PRUNE</li>
 *   <li>Otherwise → KEEP</li>
 * </ol>
 *
 * <h3>Documented defaults (for KbConfig wiring)</h3>
 * <ul>
 *   <li>{@code minBelief}          = 0.10  (range 0.0–1.0)</li>
 *   <li>{@code maxUncertainty}     = 0.80  (range 0.0–1.0)</li>
 *   <li>{@code minExpectation}     = 0.15  (range 0.0–1.0)</li>
 *   <li>{@code pruneSuppressedBand}= true  (prune SUPPRESSED-band edges by default)</li>
 * </ul>
 *
 * @param minBelief           edges whose {@code belief < minBelief} are pruned
 *                            (default 0.10; range [0.0, 1.0])
 * @param maxUncertainty      edges whose {@code uncertainty > maxUncertainty} are pruned
 *                            (default 0.80; range [0.0, 1.0])
 * @param minExpectation      edges whose projected expectation {@code < minExpectation} are pruned
 *                            (default 0.15; range [0.0, 1.0])
 * @param pruneSuppressedBand when {@code true} (default), edges projecting to
 *                            {@link StrengthBand#SUPPRESSED} are always pruned regardless of
 *                            the numeric thresholds
 */
public record PrunePolicy(
        double minBelief,
        double maxUncertainty,
        double minExpectation,
        boolean pruneSuppressedBand
) {

    /**
     * Validate thresholds at construction time.
     */
    public PrunePolicy {
        if (minBelief < 0.0 || minBelief > 1.0)
            throw new IllegalArgumentException("minBelief must be in [0, 1]; got " + minBelief);
        if (maxUncertainty < 0.0 || maxUncertainty > 1.0)
            throw new IllegalArgumentException("maxUncertainty must be in [0, 1]; got " + maxUncertainty);
        if (minExpectation < 0.0 || minExpectation > 1.0)
            throw new IllegalArgumentException("minExpectation must be in [0, 1]; got " + minExpectation);
    }

    /**
     * Production-calibrated defaults.
     *
     * <ul>
     *   <li>minBelief=0.10 — prune edges that are actively disbelieved or near-vacuous</li>
     *   <li>maxUncertainty=0.80 — prune edges with no evidence base yet</li>
     *   <li>minExpectation=0.15 — prune edges with extremely low projected probability</li>
     *   <li>pruneSuppressedBand=true — always prune SUPPRESSED-band edges</li>
     * </ul>
     */
    public static PrunePolicy defaults() {
        return new PrunePolicy(0.10, 0.80, 0.15, true);
    }

    /**
     * Aggressive variant for use in high-bloat mode (tighter thresholds).
     *
     * <ul>
     *   <li>minBelief=0.20</li>
     *   <li>maxUncertainty=0.65</li>
     *   <li>minExpectation=0.25</li>
     *   <li>pruneSuppressedBand=true</li>
     * </ul>
     */
    public static PrunePolicy aggressive() {
        return new PrunePolicy(0.20, 0.65, 0.25, true);
    }

    /**
     * Conservative variant that only prunes clearly suppressed/disbelieved edges.
     *
     * <ul>
     *   <li>minBelief=0.05</li>
     *   <li>maxUncertainty=0.95</li>
     *   <li>minExpectation=0.08</li>
     *   <li>pruneSuppressedBand=true</li>
     * </ul>
     */
    public static PrunePolicy conservative() {
        return new PrunePolicy(0.05, 0.95, 0.08, true);
    }
}
