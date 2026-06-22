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
package ai.kompile.graph.reasoning.sparse;

import ai.kompile.graph.reasoning.confidence.Opinion;

/**
 * Converts sparse-graph observations into {@link Opinion} values that correctly represent the
 * epistemic state for each cell/edge scenario.
 *
 * <h2>Design rationale — why absence maps to uncertainty, not disbelief</h2>
 *
 * <p>In a dense graph (e.g. a complete social network) the absence of an edge between two known
 * nodes is genuine evidence against their connection: we looked at the full structure and did not
 * find it. The correct representation is modest disbelief, not zero disbelief.</p>
 *
 * <p>In a sparse, tabular graph (Excel/CSV) the situation is fundamentally different:</p>
 * <ul>
 *   <li>A missing cell may mean the column is optional for that row type.</li>
 *   <li>A missing cell may mean the extractor did not emit an edge for that cell.</li>
 *   <li>A missing link between two value-nodes may mean they simply were never co-extracted,
 *       not that they are definitively unrelated.</li>
 * </ul>
 *
 * <p>In all these cases the correct epistemic state is <em>uncertainty</em> — the open-world
 * assumption applies. Using the Subjective Logic {@link Opinion} primitive: we want
 * {@code u ≈ 1, b ≈ 0, d ≈ 0}, which is exactly {@link Opinion#vacuous()}.</p>
 *
 * <p>Incorrectly seeding absent sparse edges with disbelief would poison downstream PSL/MEBN
 * inference, causing rules like {@code HasColumn(X,C) → SomeConclusion(X)} to never fire for
 * rows where the edge was simply not extracted.</p>
 *
 * <h2>Beta evidence model</h2>
 *
 * <p>Observed facts use {@link Opinion#fromBetaEvidence(double, double, double, double)} with
 * a non-informative prior strength {@code k = 2.0} (the standard {@code evidencePriorStrength}
 * from {@code KbConfig}). This means partial observations (some cells present, some absent in
 * a multi-source join) accumulate incrementally and correctly: at 10 positive/10 total the belief
 * is high; at 1/20 the belief is low but meaningful.</p>
 *
 * <p>Callers that know the source is purely structural (the column header definitively exists)
 * should prefer {@code Opinion.fromBetaEvidence(sourceTrust, 0.0, 0.5, 0.1)} directly
 * (prior strength = structural {@code W = 0.1}).</p>
 *
 * <h2>No dependencies</h2>
 *
 * <p>This class is pure Java — no Spring, no {@code @Value}, no persistence. All configurable
 * values are either method parameters or documented static defaults.</p>
 */
public final class SparseEvidenceHelper {

    /**
     * Default Beta prior strength {@code k} for observed cells in sparse graphs.
     * Mirrors {@code KbConfig.evidencePriorStrength}.
     * Candidate {@code KbConfig} key: {@code kbSparseObservedPriorStrength}, range [1e-6, 1000.0].
     */
    public static final double DEFAULT_OBSERVED_PRIOR_STRENGTH = 2.0;

    /**
     * Default base rate (prior mean) for sparse-graph opinions.
     * Candidate {@code KbConfig} key: {@code kbSparseAbsenceBaseRate}, range [0.0, 1.0].
     */
    public static final double DEFAULT_BASE_RATE = 0.5;

    private SparseEvidenceHelper() {
        // utility class — no instances
    }

    /**
     * Produce a Subjective Logic opinion for an <em>observed</em> fact in a sparse graph.
     *
     * <p>Uses the Beta-distribution posterior mapping: {@code pos} positive evidence units out of
     * {@code totalObservations} total (negatives = {@code totalObservations - pos}), with a
     * non-informative prior strength of {@link #DEFAULT_OBSERVED_PRIOR_STRENGTH}.</p>
     *
     * <p>Examples with {@code k = 2.0}:</p>
     * <ul>
     *   <li>{@code opinionForObservedFact(10, 10)} → belief ≈ 0.83, u ≈ 0.17 (high belief)</li>
     *   <li>{@code opinionForObservedFact(1, 10)} → belief ≈ 0.08, u ≈ 0.17 (low belief, uncertain)</li>
     * </ul>
     *
     * @param positiveEvidence  number of positive observations (must be ≥ 0 and ≤ totalObservations)
     * @param totalObservations total number of observations (positive + negative; must be ≥ 0)
     * @return an Opinion with belief proportional to the positive fraction
     * @throws IllegalArgumentException if arguments are out of range
     */
    public static Opinion opinionForObservedFact(double positiveEvidence, double totalObservations) {
        if (positiveEvidence < 0)
            throw new IllegalArgumentException("positiveEvidence must be >= 0; got " + positiveEvidence);
        if (totalObservations < 0)
            throw new IllegalArgumentException("totalObservations must be >= 0; got " + totalObservations);
        if (positiveEvidence > totalObservations)
            throw new IllegalArgumentException(
                "positiveEvidence (" + positiveEvidence + ") must be <= totalObservations (" + totalObservations + ")");

        double pos = positiveEvidence;
        double neg = totalObservations - positiveEvidence;
        return Opinion.fromBetaEvidence(pos, neg, DEFAULT_BASE_RATE, DEFAULT_OBSERVED_PRIOR_STRENGTH);
    }

    /**
     * Produce a Subjective Logic opinion for an <em>observed</em> fact using a caller-supplied
     * prior strength and base rate.
     *
     * @param positiveEvidence  positive evidence units (≥ 0)
     * @param totalObservations total observations (≥ positiveEvidence)
     * @param baseRate          prior mean in [0, 1]
     * @param priorStrength     Beta prior strength {@code k} (e.g. 0.1 for structural, 2.0 for inferred)
     * @return computed Opinion
     */
    public static Opinion opinionForObservedFact(double positiveEvidence, double totalObservations,
                                                  double baseRate, double priorStrength) {
        if (positiveEvidence < 0)
            throw new IllegalArgumentException("positiveEvidence must be >= 0; got " + positiveEvidence);
        if (totalObservations < 0)
            throw new IllegalArgumentException("totalObservations must be >= 0; got " + totalObservations);
        if (positiveEvidence > totalObservations)
            throw new IllegalArgumentException(
                "positiveEvidence (" + positiveEvidence + ") must be <= totalObservations (" + totalObservations + ")");

        double pos = positiveEvidence;
        double neg = totalObservations - positiveEvidence;
        return Opinion.fromBetaEvidence(pos, neg, baseRate, priorStrength);
    }

    /**
     * Produce a Subjective Logic opinion for an <em>unobserved</em> entity-pair in a sparse graph.
     *
     * <p>Returns a fully vacuous opinion {@code (b=0, d=0, u=1, a=0.5)}: we have seen <em>nothing</em>
     * about this pair and make no assertion either way. This is the open-world assumption applied to
     * the absent-edge case.</p>
     *
     * <p>Use this when: you know two nodes exist but have found no edge between them, and the graph
     * is sparse (e.g., a CSV-derived bipartite graph). Do NOT use when you have actively checked
     * for the edge and confirmed its absence in a closed-world setting.</p>
     *
     * @return a vacuous Opinion: {@code Opinion.vacuous()}
     */
    public static Opinion opinionForUnobservedPair() {
        return Opinion.vacuous();
    }

    /**
     * Produce a Subjective Logic opinion for an absent edge in a sparse-graph context.
     *
     * <p>Semantically identical to {@link #opinionForUnobservedPair()} — provided as a named
     * alternative to make caller intent explicit: "I know the graph is sparse, and this edge is
     * absent; I am declaring uncertainty, not disbelief."</p>
     *
     * <p>Contrast with a closed-world setting where the correct representation for a confirmed
     * absent link would be {@code Opinion.fromObservedValue(0.0)} (full disbelief). In the
     * open-world / sparse-graph setting, disbelief is never appropriate for mere absence.</p>
     *
     * @return a vacuous Opinion: {@code Opinion.vacuous()}
     */
    public static Opinion opinionForAbsentInSparse() {
        return Opinion.vacuous();
    }

    /**
     * Produce a vacuous opinion with a caller-specified base rate.
     *
     * <p>Use when the domain prior suggests that the proposition has a non-neutral prior probability
     * (e.g., a rare attribute that appears in only 5% of rows: {@code baseRate = 0.05}), but we still
     * have no direct evidence for this particular pair.</p>
     *
     * @param baseRate prior probability that the proposition is true, in [0, 1]
     * @return a vacuous Opinion with the given base rate
     */
    public static Opinion opinionForAbsentInSparse(double baseRate) {
        return Opinion.vacuous(baseRate);
    }
}
