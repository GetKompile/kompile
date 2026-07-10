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
 * Fits a {@link LogisticAnswerScorer} by batch gradient descent on the cross-entropy loss with L2
 * regularization — pure Java, no ND4J/SameDiff, deterministic (zero init). Small enough to run in-process
 * whenever a labeled set is (re)built; the whole point is that "build a small model to score answers"
 * needs neither a training cluster nor a GPU for six features.
 *
 * <p>Training data is {@code (X, y)}: each row of {@code X} is an {@link AnswerFeatures} vector for a
 * (question, candidate) pair, and {@code y[i] ∈ {0,1}} is whether that candidate was the correct answer.
 * Those labels come from a golden QA set (the same one the RAGAS eval needs) or from accepted/rejected
 * answers logged in production.</p>
 */
public final class LogisticRegressionTrainer {

    private LogisticRegressionTrainer() {
    }

    /** Fit with sensible defaults (500 epochs, lr 0.5, L2 1e-4). */
    public static LogisticAnswerScorer fit(double[][] x, double[] y) {
        return fit(x, y, 500, 0.5, 1e-4);
    }

    /**
     * Fit weights + bias minimizing mean cross-entropy + {@code l2}·‖w‖². Returns an untrained scorer
     * when the data is empty/degenerate. Deterministic.
     */
    public static LogisticAnswerScorer fit(double[][] x, double[] y, int epochs, double lr, double l2) {
        if (x == null || y == null || x.length == 0 || x.length != y.length) {
            return LogisticAnswerScorer.untrained(0);
        }
        int n = x.length;
        int dim = x[0].length;
        double[] w = new double[dim];
        double b = 0.0;

        for (int epoch = 0; epoch < epochs; epoch++) {
            double[] gradW = new double[dim];
            double gradB = 0.0;
            for (int i = 0; i < n; i++) {
                double z = b;
                for (int j = 0; j < dim; j++) {
                    z += w[j] * x[i][j];
                }
                double p = 1.0 / (1.0 + Math.exp(-z));
                double err = p - y[i];
                for (int j = 0; j < dim; j++) {
                    gradW[j] += err * x[i][j];
                }
                gradB += err;
            }
            for (int j = 0; j < dim; j++) {
                w[j] -= lr * (gradW[j] / n + l2 * w[j]);
            }
            b -= lr * (gradB / n);
        }
        return new LogisticAnswerScorer(w, b);
    }

    /** Mean cross-entropy of a scorer on labeled data — for reporting / model selection. */
    public static double logLoss(LogisticAnswerScorer scorer, double[][] x, double[] y) {
        if (x == null || x.length == 0) {
            return Double.NaN;
        }
        double sum = 0.0;
        for (int i = 0; i < x.length; i++) {
            double p = clamp(scorer.score(x[i]));
            sum += y[i] > 0.5 ? -Math.log(p) : -Math.log(1.0 - p);
        }
        return sum / x.length;
    }

    private static double clamp(double p) {
        return p < 1e-9 ? 1e-9 : (p > 1.0 - 1e-9 ? 1.0 - 1e-9 : p);
    }
}
