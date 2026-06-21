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

import ai.kompile.graph.reasoning.psl.PslProgram;
import ai.kompile.graph.reasoning.psl.PslRule;

import java.util.List;
import java.util.Map;

/**
 * Interface for PSL rule weight learning from labeled observations.
 *
 * <p>Implementations adjust rule weights to minimize the discrepancy between inferred
 * atom values and ground-truth observations.</p>
 */
public interface WeightLearner {

    /**
     * Learn rule weights from labeled observations.
     *
     * @param program     the PSL program (rules + atoms); rules will have weights updated
     * @param groundTruth map from atom key to ground-truth value [0,1]
     * @param maxEpochs   maximum learning iterations
     * @return list of updated PslRule (same rules with learned weights)
     */
    List<PslRule> learn(PslProgram program, Map<String, Double> groundTruth, int maxEpochs);
}
