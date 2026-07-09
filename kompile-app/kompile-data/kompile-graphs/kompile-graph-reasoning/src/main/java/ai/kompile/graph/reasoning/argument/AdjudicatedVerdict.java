/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.argument;

import ai.kompile.graph.reasoning.explain.ReasoningTrace;

import java.util.Map;
import java.util.Objects;

/**
 * The result of adjudicating a claim via {@link ClaimAdjudicator}.
 *
 * <p>Captures:
 * <ul>
 *   <li>{@link #status} — SUPPORTED / REFUTED / UNKNOWN, derived from the claim's final
 *       DF-QuAD strength against the configured thresholds.</li>
 *   <li>{@link #strength} — the claim argument's final DF-QuAD strength in [0, 1].</li>
 *   <li>{@link #argumentStrengths} — per-argument final strengths (by argument id).</li>
 *   <li>{@link #trace} — a {@link ReasoningTrace} whose root is an INFERENCE step for the
 *       claim and whose premises are one step per evidence item.</li>
 * </ul>
 */
public record AdjudicatedVerdict(
        Status status,
        double strength,
        Map<String, Double> argumentStrengths,
        ReasoningTrace trace
) {

    /** The three possible adjudication outcomes. */
    public enum Status { SUPPORTED, REFUTED, UNKNOWN }

    public AdjudicatedVerdict {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(argumentStrengths, "argumentStrengths");
        Objects.requireNonNull(trace, "trace");
        if (strength < 0.0 || strength > 1.0) {
            throw new IllegalArgumentException("strength must be in [0,1], got " + strength);
        }
    }
}
