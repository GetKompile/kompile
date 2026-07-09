/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.tms.inconsistency;

import java.util.List;
import java.util.Objects;

/**
 * Structured inconsistency assessment for a specific claim atom in its neighbourhood.
 *
 * <p>This is the payload attached by {@link ClaimNeighborhoodInconsistency#assess} to
 * {@code UNKNOWN} or contested verify verdicts. It bundles all measures from
 * {@link InconsistencyMeasures} into a single report for convenient serialisation and
 * downstream consumption (e.g., {@code ask_graph_verify} response enrichment).</p>
 *
 * @param claimAtom              the original claim atom key as supplied to
 *                               {@link ClaimNeighborhoodInconsistency#assess}
 * @param bCount                 number of atoms in the neighbourhood marked {@link Mark#B}
 *                               (paraconsistently contested)
 * @param contension             I_c-inspired footprint ∈ [0,1]: fraction of strongly-evidenced
 *                               atoms in the neighbourhood that are B; 0 = no conflict, 1 = all contested
 * @param blameOfClaim           number of conflict pairs that directly involve the claim atom's
 *                               canonical form; 0 if the claim itself is not a conflict participant
 * @param conflictPairDescriptions human-readable description of each conflict pair found in the
 *                               neighbourhood (at most one per distinct pair)
 * @param minRepair              greedy minimum-repair set: canonical atom keys whose retraction
 *                               would restore consistency (ln(n)-approximate minimum hitting set)
 */
public record InconsistencyReport(
        String claimAtom,
        int bCount,
        double contension,
        int blameOfClaim,
        List<String> conflictPairDescriptions,
        List<String> minRepair
) {

    public InconsistencyReport {
        Objects.requireNonNull(claimAtom, "claimAtom must not be null");
        if (bCount < 0) throw new IllegalArgumentException("bCount must be non-negative");
        if (contension < 0.0 || contension > 1.0 || Double.isNaN(contension)) {
            throw new IllegalArgumentException("contension must be in [0,1], got: " + contension);
        }
        if (blameOfClaim < 0) throw new IllegalArgumentException("blameOfClaim must be non-negative");
        conflictPairDescriptions = conflictPairDescriptions == null ? List.of() : List.copyOf(conflictPairDescriptions);
        minRepair = minRepair == null ? List.of() : List.copyOf(minRepair);
    }

    /**
     * Convenience: true if the neighbourhood has any detected inconsistency (bCount &gt; 0).
     */
    public boolean hasInconsistency() {
        return bCount > 0;
    }

    /**
     * Convenience: true if the claim atom itself participates in at least one conflict.
     */
    public boolean claimIsContested() {
        return blameOfClaim > 0;
    }

    @Override
    public String toString() {
        return "InconsistencyReport{claim='" + claimAtom + "', bCount=" + bCount
                + ", contension=" + String.format("%.3f", contension)
                + ", blame=" + blameOfClaim
                + ", pairs=" + conflictPairDescriptions.size()
                + ", repairSize=" + minRepair.size() + "}";
    }
}
