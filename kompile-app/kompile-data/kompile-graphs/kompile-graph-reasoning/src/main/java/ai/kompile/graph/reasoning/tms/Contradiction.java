/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.tms;

import ai.kompile.graph.reasoning.psl.GroundRule;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a detected constraint violation in an HL-MRF inference result.
 *
 * @param groundRule              the violated ground rule
 * @param currentValues           the truth assignment at the time of detection
 * @param distanceToSatisfaction  how far the constraint is from being satisfied (0 = satisfied)
 * @param violatingAtomKeys       atom keys that are contributing to the violation
 */
public record Contradiction(
        GroundRule groundRule,
        Map<String, Double> currentValues,
        double distanceToSatisfaction,
        List<String> violatingAtomKeys
) {

    public Contradiction {
        Objects.requireNonNull(groundRule, "groundRule must not be null");
        Objects.requireNonNull(currentValues, "currentValues must not be null");
        violatingAtomKeys = (violatingAtomKeys == null) ? List.of() : List.copyOf(violatingAtomKeys);
    }

    @Override
    public String toString() {
        return String.format("Contradiction{rule='%s', dist=%.4f, violating=%s}",
                groundRule.display(), distanceToSatisfaction, violatingAtomKeys);
    }
}
