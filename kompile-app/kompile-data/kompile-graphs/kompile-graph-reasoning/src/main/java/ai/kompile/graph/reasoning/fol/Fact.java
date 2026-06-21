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
import java.util.Objects;

/**
 * PSL-side analogue of {@link Finding}: a ground atom observation with provenance.
 *
 * <p>A Fact records that a ground PSL atom (e.g. {@code "State(alice)"}) has an observed
 * truth value. Hard facts ({@code hard=true}) are fixed as observed atoms during inference.
 * Soft facts ({@code hard=false}) serve as soft priors that the optimizer can override.</p>
 *
 * @param atomKey   canonical ground atom key, e.g. "State(alice)" or "Knows(alice, bob)"
 * @param value     truth value in [0,1]: 1.0 = definitely true, 0.0 = definitely false
 * @param sourceId  provenance identifier
 * @param timestamp when this fact was asserted
 * @param hard      true = use as fixed observed atom during inference; false = soft prior
 */
public record Fact(
        String atomKey,
        double value,
        String sourceId,
        Instant timestamp,
        boolean hard
) {

    public Fact {
        Objects.requireNonNull(atomKey, "atomKey must not be null");
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        if (value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException("Fact value must be in [0,1], got: " + value);
        }
    }

    /**
     * Create a hard-observed fact (value=1.0, hard=true).
     *
     * @param atomKey  the ground atom key
     * @param sourceId provenance identifier
     * @return a hard Fact
     */
    public static Fact observed(String atomKey, String sourceId) {
        return new Fact(atomKey, 1.0, sourceId, Instant.now(), true);
    }

    /**
     * Create a soft fact with a given truth value (hard=false).
     *
     * @param atomKey  the ground atom key
     * @param value    truth value in [0,1]
     * @param sourceId provenance identifier
     * @return a soft Fact
     */
    public static Fact soft(String atomKey, double value, String sourceId) {
        return new Fact(atomKey, value, sourceId, Instant.now(), false);
    }
}
