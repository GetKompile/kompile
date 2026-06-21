/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.psl;

/**
 * Per-ground-rule satisfaction detail at the MAP solution.
 *
 * <p>Produced by {@link HlMrfMapInference.Result#groundRuleResults()} after a solve completes;
 * the values are computed purely post-hoc from the final atom assignment — no ADMM internals are
 * touched. This is the data the PSL "why" reasoning trail needs to explain which rules were
 * violated, by how much, and what they contributed to the total energy.</p>
 *
 * @param rule                  the ground rule whose satisfaction is reported
 * @param distanceToSatisfaction Łukasiewicz distance d ∈ [0, 1]; 0 means fully satisfied
 * @param weightedPotential     w · d^p as used in the MAP energy (0 when satisfied)
 * @param satisfied             {@code true} when {@code distanceToSatisfaction ≤}
 *                              {@link HlMrfMapInference#HARD_VIOLATION_TOLERANCE}
 */
public record GroundRuleResult(
        GroundRule rule,
        double distanceToSatisfaction,
        double weightedPotential,
        boolean satisfied) {

    /**
     * Human-readable summary: {@code rule + " | d=" + distance + " | pot=" + potential}.
     */
    @Override
    public String toString() {
        return rule.display()
                + " | d=" + String.format("%.4f", distanceToSatisfaction)
                + " | pot=" + String.format("%.4f", weightedPotential)
                + (satisfied ? " [OK]" : " [VIOLATED]");
    }
}
