/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.learning;

import ai.kompile.graph.reasoning.psl.PslRule;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The result of a weight learning run.
 *
 * @param learnedRules    the updated PSL rules with learned weights
 * @param epochsRun       number of epochs actually run
 * @param converged       true if the learning converged before the epoch cap
 * @param finalLoss       sum of |distGT - distPred| across all ground rules after learning
 * @param weightHistory   rule display string -> final weight (for inspection)
 */
public record WeightLearningResult(
        List<PslRule> learnedRules,
        int epochsRun,
        boolean converged,
        double finalLoss,
        Map<String, Double> weightHistory
) {

    public WeightLearningResult {
        Objects.requireNonNull(learnedRules, "learnedRules must not be null");
        learnedRules = List.copyOf(learnedRules);
        weightHistory = (weightHistory == null) ? Map.of() : Map.copyOf(weightHistory);
    }
}
