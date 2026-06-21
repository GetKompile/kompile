/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.fol;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A first-class finding (observation / evidence assertion) that can be applied to
 * a MEBN Bayesian network or PSL program during inference.
 *
 * <p>A finding is either:
 * <ul>
 *   <li><b>Hard</b> ({@code stateIndex >= 0}): the random variable is clamped to the given
 *       discrete state. Example: "alice IS active" (stateIndex=1).</li>
 *   <li><b>Soft / virtual evidence</b> ({@code stateIndex < 0}): the random variable is
 *       updated via a likelihood ratio vector (Jeffrey's rule). Example: "we believe alice is
 *       active with ratio 3:1".</li>
 * </ul>
 *
 * @param rvName         random variable name, e.g. "isActive"
 * @param entityArgs     entity IDs that ground the variable, e.g. ["alice"]
 * @param stateIndex     hard clamp: state index (0=FALSE, 1=TRUE); -1 means soft (use likelihood)
 * @param likelihood     per-state likelihood ratios (virtual evidence); null for hard clamp;
 *                       for soft findings must have length >= 2
 * @param sourceId       provenance: crawl run ID, channel ID, user assertion identifier
 * @param timestamp      when this finding was asserted
 * @param inferenceRunId nullable; set after inference to link back to an inference run
 */
public record Finding(
        String rvName,
        List<String> entityArgs,
        int stateIndex,
        double[] likelihood,
        String sourceId,
        Instant timestamp,
        String inferenceRunId
) {

    public Finding {
        Objects.requireNonNull(rvName, "rvName must not be null");
        Objects.requireNonNull(entityArgs, "entityArgs must not be null");
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        entityArgs = List.copyOf(entityArgs);

        // Validate soft finding
        if (stateIndex < 0 && likelihood != null && likelihood.length < 2) {
            throw new IllegalArgumentException(
                    "Soft finding likelihood must have at least 2 entries, got: " + likelihood.length);
        }
        // Validate hard finding
        if (stateIndex >= 0 && stateIndex > 1000) {
            throw new IllegalArgumentException(
                    "Hard finding stateIndex must be a valid state index, got: " + stateIndex);
        }
    }

    /**
     * Create a hard-clamped finding.
     *
     * @param rvName       random variable name
     * @param entityArgs   entity IDs grounding the variable
     * @param stateIndex   which discrete state to clamp to (0=FALSE, 1=TRUE)
     * @param sourceId     provenance identifier
     * @return a hard Finding
     */
    public static Finding hard(String rvName, List<String> entityArgs, int stateIndex, String sourceId) {
        if (stateIndex < 0) throw new IllegalArgumentException("Hard finding requires stateIndex >= 0");
        return new Finding(rvName, entityArgs, stateIndex, null, sourceId, Instant.now(), null);
    }

    /**
     * Create a soft (virtual evidence) finding.
     *
     * @param rvName     random variable name
     * @param entityArgs entity IDs grounding the variable
     * @param likelihood per-state likelihood ratios (length >= 2)
     * @param sourceId   provenance identifier
     * @return a soft Finding
     */
    public static Finding soft(String rvName, List<String> entityArgs, double[] likelihood, String sourceId) {
        Objects.requireNonNull(likelihood, "likelihood must not be null for soft finding");
        if (likelihood.length < 2) {
            throw new IllegalArgumentException("Soft finding likelihood must have at least 2 entries");
        }
        return new Finding(rvName, entityArgs, -1, likelihood.clone(), sourceId, Instant.now(), null);
    }

    /** @return true if this is a hard-clamped finding (stateIndex >= 0) */
    public boolean isHard() {
        return stateIndex >= 0;
    }

    /** @return true if this is a soft/virtual evidence finding */
    public boolean isSoft() {
        return stateIndex < 0 && likelihood != null;
    }

    /**
     * The grounded key of this finding, in the format used by BN node names:
     * {@code rvName(entityArg1,entityArg2,...)}
     *
     * @return grounded key string
     */
    public String groundedKey() {
        return rvName + "(" + String.join(",", entityArgs) + ")";
    }
}
