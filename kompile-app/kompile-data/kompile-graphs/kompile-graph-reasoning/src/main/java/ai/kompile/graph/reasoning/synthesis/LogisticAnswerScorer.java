/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.graph.reasoning.synthesis;

/**
 * A <b>small learned scorer</b> over {@link AnswerFeatures}: logistic regression that maps a candidate's
 * signal-feature vector to a calibrated P(correct) ∈ (0,1). This is the ML alternative to the fixed
 * subjective-logic fold — instead of hand-coded {@code ⊕/consensus/⊙} operators, the weights are fit to
 * labeled data ({@link LogisticRegressionTrainer}), so the model learns how much each signal is worth
 * (and the sigmoid output is already a probability — no separate calibration step).
 *
 * <p>Logistic regression is deliberate for a first model: it needs few labels, can't overfit six
 * features, and the weights are directly inspectable ("KB verification is worth 3× retrieval"). A small
 * MLP is a drop-in upgrade behind the same {@link #score} contract once there is enough labeled data.
 * Immutable + record-shaped so a trained model serializes to JSON and reloads.</p>
 */
public record LogisticAnswerScorer(double[] weights, double bias) {

    public LogisticAnswerScorer {
        weights = weights == null ? new double[0] : weights.clone();
    }

    /** An untrained scorer (all weights 0 → constant 0.5); a safe no-op prior before training. */
    public static LogisticAnswerScorer untrained(int dim) {
        return new LogisticAnswerScorer(new double[dim], 0.0);
    }

    @Override
    public double[] weights() {
        return weights.clone();
    }

    /** Calibrated P(correct) for a feature vector = σ(w·x + b). Extra/short dims are ignored/zero-padded. */
    public double score(double[] features) {
        double z = bias;
        int n = Math.min(features == null ? 0 : features.length, weights.length);
        for (int i = 0; i < n; i++) {
            z += weights[i] * features[i];
        }
        return 1.0 / (1.0 + Math.exp(-z));
    }
}
