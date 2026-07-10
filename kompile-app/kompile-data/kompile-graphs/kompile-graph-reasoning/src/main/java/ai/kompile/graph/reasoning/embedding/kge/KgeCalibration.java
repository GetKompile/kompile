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
package ai.kompile.graph.reasoning.embedding.kge;

/**
 * WP2c — calibrates a raw RotatE link-prediction distance {@code d} into a plausibility probability
 * with the temperature-scaled sigmoid {@code σ((γ − d) / T)}. This matches RotatE's own training
 * objective ({@code logσ(γ − d)} for positives), so the calibrated score is a proper probability rather
 * than the ad-hoc {@code 1/(1+d)} — the difference between a trustworthy ω_kge signal and a heuristic.
 *
 * <ul>
 *   <li>{@code γ} (gamma) — the distance at which plausibility crosses 0.5 (RotatE's margin).</li>
 *   <li>{@code T} (temperature) — sharpness: small T → near-step decision, large T → soft.</li>
 * </ul>
 *
 * <p>{@link #fit(double[], boolean[])} performs temperature scaling: it picks {@code (γ, T)} that
 * minimize the negative log-likelihood of observed link labels (true edges = positive, corrupted =
 * negative), so the reported probabilities track empirical correctness. Pure + deterministic.</p>
 */
public final class KgeCalibration {

    private static final double EPS = 1e-9;

    private final double gamma;
    private final double temperature;

    public KgeCalibration(double gamma, double temperature) {
        if (Double.isNaN(gamma) || Double.isNaN(temperature) || !(temperature > 0.0)) {
            throw new IllegalArgumentException("temperature must be > 0 and gamma finite");
        }
        this.gamma = gamma;
        this.temperature = temperature;
    }

    /** Uncalibrated-but-sane default: γ=1, T=1 (used when there is no data to fit). */
    public static KgeCalibration defaults() {
        return new KgeCalibration(1.0, 1.0);
    }

    /** Fix the 0.5 crossover at {@code gamma} with unit temperature. */
    public static KgeCalibration ofMargin(double gamma) {
        return new KgeCalibration(gamma, 1.0);
    }

    public double gamma() {
        return gamma;
    }

    public double temperature() {
        return temperature;
    }

    /** Calibrated plausibility {@code σ((γ − d)/T) ∈ (0,1)}; strictly decreasing in {@code distance}. */
    public double calibrate(double distance) {
        if (Double.isNaN(distance)) {
            return 0.0;
        }
        return sigmoid((gamma - distance) / temperature);
    }

    /**
     * Fit {@code (γ, T)} by temperature scaling: a deterministic grid search minimizing mean negative
     * log-likelihood of the labels. Returns {@link #defaults()} when the data is too small / degenerate
     * (fewer than 2 points, only one class, or no spread) — never throws.
     */
    public static KgeCalibration fit(double[] distances, boolean[] positive) {
        if (distances == null || positive == null
                || distances.length != positive.length || distances.length < 2) {
            return defaults();
        }
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        boolean hasPos = false;
        boolean hasNeg = false;
        for (int i = 0; i < distances.length; i++) {
            double d = distances[i];
            if (Double.isNaN(d)) {
                continue;
            }
            min = Math.min(min, d);
            max = Math.max(max, d);
            hasPos |= positive[i];
            hasNeg |= !positive[i];
        }
        if (!hasPos || !hasNeg || !(max > min)) {
            return defaults();
        }

        final int gammaSteps = 40;
        final double[] temps = {0.1, 0.25, 0.5, 1.0, 2.0, 4.0, 8.0};
        double bestGamma = (min + max) / 2.0;
        double bestT = 1.0;
        double bestNll = Double.POSITIVE_INFINITY;
        for (int g = 0; g <= gammaSteps; g++) {
            double candidateGamma = min + (max - min) * g / gammaSteps;
            for (double t : temps) {
                double nll = meanNll(distances, positive, candidateGamma, t);
                if (nll < bestNll) {
                    bestNll = nll;
                    bestGamma = candidateGamma;
                    bestT = t;
                }
            }
        }
        return new KgeCalibration(bestGamma, bestT);
    }

    private static double meanNll(double[] distances, boolean[] positive, double gamma, double t) {
        double sum = 0.0;
        int n = 0;
        for (int i = 0; i < distances.length; i++) {
            double d = distances[i];
            if (Double.isNaN(d)) {
                continue;
            }
            double p = clampProb(sigmoid((gamma - d) / t));
            sum += positive[i] ? -Math.log(p) : -Math.log(1.0 - p);
            n++;
        }
        return n == 0 ? Double.POSITIVE_INFINITY : sum / n;
    }

    private static double sigmoid(double z) {
        return 1.0 / (1.0 + Math.exp(-z));
    }

    private static double clampProb(double p) {
        return p < EPS ? EPS : (p > 1.0 - EPS ? 1.0 - EPS : p);
    }
}
