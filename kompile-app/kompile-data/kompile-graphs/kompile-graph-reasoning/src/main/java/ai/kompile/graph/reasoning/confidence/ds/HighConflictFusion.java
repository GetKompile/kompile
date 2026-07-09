/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.confidence.ds;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.confidence.Opinion.FusionMode;

import java.util.List;

/**
 * Facade: routes multi-source fusion between Subjective Logic (low conflict)
 * and PCR5 Dempster-Shafer (high conflict), with TBM open-world signaling.
 *
 * <h2>Rationale</h2>
 * Zadeh's paradox: Dempster's rule can yield counterintuitive results under
 * high conflict (e.g., two nearly-dogmatic contradictory sources produce an
 * artificially certain result in the "winning" direction).  PCR5 (Smarandanche
 * &amp; Dezert) resolves this by proportionally redistributing conflict mass back
 * to the conflicting hypotheses rather than normalizing it away.  The TBM
 * (Smets) open-world signal ({@code m(∅) > 0}) further quantifies how much
 * the sources may be talking past each other.
 *
 * <h2>Routing logic</h2>
 * <ol>
 *   <li>Compute max pairwise conflict K over all pairs O(n²).</li>
 *   <li>If {@code maxK < conflictThreshold}: apply the SL {@code fallbackMode}
 *       ({@link FusionMode#AVERAGING} or {@link FusionMode#CUMULATIVE}).
 *       {@code pcr5Applied = false, emptyMass = 0}.</li>
 *   <li>If {@code maxK ≥ conflictThreshold}: convert all opinions to BBAs,
 *       fold pairwise with {@link Combination#pcr5} (sequential in list order),
 *       also fold with {@link Combination#tbmConjunctive} to extract the
 *       open-world empty mass, then convert the PCR5 result back to an Opinion.
 *       {@code pcr5Applied = true}.</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * Use SL fallback ({@link FusionMode#AVERAGING} or {@link FusionMode#CUMULATIVE})
 * when sources are generally consistent.  Use this facade when sources come from
 * different (potentially incompatible) information channels where high pairwise
 * conflict is expected.
 */
public final class HighConflictFusion {

    private HighConflictFusion() {}

    /**
     * Result of a {@link #fuse} call.
     *
     * @param fused          the combined Opinion
     * @param maxPairwiseK   the maximum pairwise conflict K found among all source pairs
     * @param pcr5Applied    {@code true} if PCR5 was used; {@code false} if the SL fallback ran
     * @param emptyMass      the open-world empty-set mass from TBM conjunctive combination;
     *                       0.0 when PCR5 was not applied
     */
    public record Result(
            Opinion fused,
            double maxPairwiseK,
            boolean pcr5Applied,
            double emptyMass) {}

    /**
     * Fuse a list of opinions, routing to PCR5 when pairwise conflict exceeds the threshold.
     *
     * @param opinions          source opinions (must be non-null, non-empty)
     * @param conflictThreshold pairwise K at or above which PCR5 is used (typical: 0.5)
     * @param fallbackMode      SL fusion mode to use when below threshold
     *                          (should be {@link FusionMode#AVERAGING} or
     *                          {@link FusionMode#CUMULATIVE})
     * @return a {@link Result} containing the fused opinion and diagnostics
     */
    public static Result fuse(
            List<Opinion> opinions,
            double conflictThreshold,
            FusionMode fallbackMode) {

        if (opinions == null || opinions.isEmpty())
            throw new IllegalArgumentException("opinions must be non-null and non-empty");

        // Single-source: trivially return as-is
        if (opinions.size() == 1) {
            Opinion single = opinions.get(0);
            return new Result(single, 0.0, false, 0.0);
        }

        // Step 1: compute max pairwise conflict K
        List<MassFunction> bbas = opinions.stream()
                .map(MassFunction::fromOpinion)
                .toList();

        double maxK = 0.0;
        for (int i = 0; i < bbas.size(); i++) {
            for (int j = i + 1; j < bbas.size(); j++) {
                double k = Combination.conflictK(bbas.get(i), bbas.get(j));
                if (k > maxK) maxK = k;
            }
        }

        // Step 2: route
        if (maxK < conflictThreshold) {
            // Low conflict: use SL fallback
            Opinion sl = Opinion.fuse(fallbackMode, opinions);
            return new Result(sl, maxK, false, 0.0);
        }

        // High conflict: PCR5 pairwise fold + TBM for emptyMass
        MassFunction pcr5Result = bbas.get(0);
        MassFunction tbmResult  = bbas.get(0);
        for (int i = 1; i < bbas.size(); i++) {
            pcr5Result = Combination.pcr5(pcr5Result, bbas.get(i));
            tbmResult  = Combination.tbmConjunctive(tbmResult, bbas.get(i));
        }

        // Extract emptyMass from TBM result
        double emptyMass = tbmResult.mEmpty();

        // Determine the mean base rate from the source opinions (certainty-weighted)
        double certSum = opinions.stream().mapToDouble(o -> 1.0 - o.uncertainty()).sum();
        double baseRate;
        if (certSum < 1e-15) {
            baseRate = opinions.stream().mapToDouble(Opinion::baseRate).average().orElse(0.5);
        } else {
            baseRate = opinions.stream()
                    .mapToDouble(o -> (1.0 - o.uncertainty()) * o.baseRate())
                    .sum() / certSum;
        }
        baseRate = Math.min(1.0, Math.max(0.0, baseRate));

        Opinion fused = pcr5Result.toOpinion(baseRate);
        return new Result(fused, maxK, true, emptyMass);
    }
}
