/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.claims;

import java.util.Objects;

/**
 * Evidence from a single mined/explicit rule that fires for the evaluated claim.
 *
 * @param ruleName    identifier of the firing rule (e.g. "worksAt-locatedIn-basedIn")
 * @param ruleDisplay human-readable rule description (e.g. antecedent → consequent string)
 * @param confidence  rule confidence in {@code [0, 1]}:
 *                    {@code rule.weight()} clamped to [0,1], or 0.9 for hard/unweighted rules
 */
public record RuleEvidence(String ruleName, String ruleDisplay, double confidence) {

    public RuleEvidence {
        Objects.requireNonNull(ruleName,    "ruleName");
        Objects.requireNonNull(ruleDisplay, "ruleDisplay");
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0,1], got " + confidence);
        }
    }
}
