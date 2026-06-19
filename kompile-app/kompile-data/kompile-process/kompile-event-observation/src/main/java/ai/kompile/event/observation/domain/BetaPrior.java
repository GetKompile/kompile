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
package ai.kompile.event.observation.domain;

/**
 * A Beta-Binomial empirical prior for a single event.
 *
 * <p>The probability that an event occurs is modelled as a Beta({@code alpha}, {@code beta})
 * posterior over observation counts. {@link #mean()} = {@code alpha / (alpha + beta)} is the
 * empirical probability, and {@link #evidenceStrength()} = {@code alpha + beta} measures how much
 * real evidence backs it. Pure, side-effect free, fully unit-testable — no Spring, no persistence.</p>
 *
 * <p>To keep priors <em>up to date over time</em>, {@link #decayToward} exponentially forgets old
 * evidence by reverting toward the configured prior pseudo-counts, so recent crawls dominate.</p>
 */
public final class BetaPrior {

    private double alpha;
    private double beta;

    public BetaPrior(double alpha, double beta) {
        if (alpha <= 0.0 || beta <= 0.0 || !Double.isFinite(alpha) || !Double.isFinite(beta)) {
            throw new IllegalArgumentException("alpha and beta must be positive and finite: a=" + alpha + ", b=" + beta);
        }
        this.alpha = alpha;
        this.beta = beta;
    }

    public static BetaPrior uniform() {
        return new BetaPrior(1.0, 1.0);
    }

    public static BetaPrior of(double alpha, double beta) {
        return new BetaPrior(alpha, beta);
    }

    public double alpha() {
        return alpha;
    }

    public double beta() {
        return beta;
    }

    /** Posterior mean P(event) = alpha / (alpha + beta), in [0,1]. */
    public double mean() {
        return alpha / (alpha + beta);
    }

    /** Total pseudo-count evidence behind the estimate (alpha + beta). */
    public double evidenceStrength() {
        return alpha + beta;
    }

    /** Variance of the Beta distribution. */
    public double variance() {
        double s = alpha + beta;
        return (alpha * beta) / (s * s * (s + 1.0));
    }

    /**
     * Apply a Binomial observation: {@code occurrences} successes out of {@code opportunities}
     * trials. Failures ({@code opportunities - occurrences}) are floored at zero so a caller that
     * only knows the success count (opportunities == occurrences) simply adds to alpha.
     */
    public void update(long occurrences, long opportunities) {
        if (occurrences < 0L || opportunities < 0L) {
            throw new IllegalArgumentException("counts must be non-negative");
        }
        long failures = Math.max(0L, opportunities - occurrences);
        alpha += occurrences;
        beta += failures;
    }

    /**
     * Exponentially forget past evidence by reverting toward the prior pseudo-counts.
     *
     * @param floorAlpha the configured prior alpha (the value alpha relaxes back to)
     * @param floorBeta  the configured prior beta
     * @param factor     in (0,1]: 1 = no decay; →0 = full revert to the prior
     */
    public void decayToward(double floorAlpha, double floorBeta, double factor) {
        double f = clamp(factor, 0.0, 1.0);
        alpha = floorAlpha + (alpha - floorAlpha) * f;
        beta = floorBeta + (beta - floorBeta) * f;
        if (alpha < floorAlpha) {
            alpha = floorAlpha;
        }
        if (beta < floorBeta) {
            beta = floorBeta;
        }
    }

    /** Approximate (normal) ~95% credible interval for the mean, clamped to [0,1]. */
    public double[] credibleInterval95() {
        double sd = Math.sqrt(variance());
        double m = mean();
        return new double[]{clamp(m - 1.96 * sd, 0.0, 1.0), clamp(m + 1.96 * sd, 0.0, 1.0)};
    }

    /**
     * Exponential decay factor for an elapsed interval given a half-life (both in the same time
     * unit). {@code 0.5^(elapsed / halfLife)}. Returns 1.0 (no decay) for non-positive inputs.
     */
    public static double decayFactor(double elapsed, double halfLife) {
        if (halfLife <= 0.0 || elapsed <= 0.0) {
            return 1.0;
        }
        return Math.pow(0.5, elapsed / halfLife);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override
    public String toString() {
        return "Beta(" + alpha + ", " + beta + ")=" + mean();
    }
}
