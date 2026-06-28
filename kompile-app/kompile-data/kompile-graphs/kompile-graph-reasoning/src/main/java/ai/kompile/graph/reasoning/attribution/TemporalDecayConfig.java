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
package ai.kompile.graph.reasoning.attribution;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Configuration for the time-decay function applied to causal-hop strengths in
 * {@link TemporalAttributionService}.
 *
 * <p>Three functions are supported:
 * <ul>
 *   <li>{@link DecayFunction#NONE} — no decay; all weights stay at {@code 1.0}. Safe default
 *       for callers that have not yet decided on a decay policy.</li>
 *   <li>{@link DecayFunction#EXPONENTIAL} — weight halves every {@code halfLife}. Mirrors the
 *       pattern used in the event-attribution {@code TemporalChainExtractor} (1-hour half-life
 *       for incident attribution).</li>
 *   <li>{@link DecayFunction#LINEAR} — weight falls linearly from {@code 1.0} (age=0) to
 *       {@code 0.0} (age≥decayWindow); useful when a hard time-horizon is natural.</li>
 * </ul>
 *
 * <p>The {@code weight(Duration age)} method maps a non-negative {@link Duration} to {@code [0,1]}.
 * Negative ages (effect before cause) are treated as zero age — the precedence constraint enforced
 * by {@link TemporalAttributionService} should have already pruned such hops.</p>
 *
 * <p>Infra-free: no Spring, no JPA, no Jackson-databind. Pure {@code java.time}.</p>
 */
public final class TemporalDecayConfig {

    /**
     * The mathematical function used to compute the decay weight.
     */
    public enum DecayFunction {
        /** No decay — weight is always {@code 1.0}. */
        NONE,
        /** Exponential half-life decay: {@code weight = 0.5^(age/halfLife)}. */
        EXPONENTIAL,
        /** Linear decay to zero at the window boundary: {@code weight = max(0, 1 − age/window)}. */
        LINEAR
    }

    private final DecayFunction function;
    /** Half-life for {@link DecayFunction#EXPONENTIAL}; null for other functions. */
    private final Duration halfLife;
    /** Full-zero boundary for {@link DecayFunction#LINEAR}; null for other functions. */
    private final Duration decayWindow;

    private TemporalDecayConfig(DecayFunction function, Duration halfLife, Duration decayWindow) {
        this.function    = Objects.requireNonNull(function, "function");
        this.halfLife    = halfLife;
        this.decayWindow = decayWindow;
    }

    // ─── Factories ───────────────────────────────────────────────────────────────

    /**
     * No decay: every hop's contribution weight is {@code 1.0} regardless of age.
     * This is the recommended default when callers do not have a specific decay policy.
     *
     * @return a {@code NONE} decay config
     */
    public static TemporalDecayConfig none() {
        return new TemporalDecayConfig(DecayFunction.NONE, null, null);
    }

    /**
     * Exponential decay: weight halves every {@code halfLife}.
     *
     * <pre>
     *   weight = 0.5 ^ (age / halfLife)
     * </pre>
     *
     * @param halfLife the duration after which the weight is halved (never {@code null}, must be
     *                 positive)
     * @return an {@code EXPONENTIAL} decay config
     * @throws IllegalArgumentException if {@code halfLife} is not positive
     */
    public static TemporalDecayConfig exponential(Duration halfLife) {
        Objects.requireNonNull(halfLife, "halfLife");
        if (halfLife.isNegative() || halfLife.isZero()) {
            throw new IllegalArgumentException("halfLife must be positive: " + halfLife);
        }
        return new TemporalDecayConfig(DecayFunction.EXPONENTIAL, halfLife, null);
    }

    /**
     * Linear decay: weight falls linearly from {@code 1.0} at age=0 to {@code 0.0} at
     * age≥{@code decayWindow}, and stays at {@code 0.0} beyond that.
     *
     * <pre>
     *   weight = max(0.0,  1.0 − age / decayWindow)
     * </pre>
     *
     * @param decayWindow the duration at which weight reaches zero (never {@code null}, must be
     *                    positive)
     * @return a {@code LINEAR} decay config
     * @throws IllegalArgumentException if {@code decayWindow} is not positive
     */
    public static TemporalDecayConfig linear(Duration decayWindow) {
        Objects.requireNonNull(decayWindow, "decayWindow");
        if (decayWindow.isNegative() || decayWindow.isZero()) {
            throw new IllegalArgumentException("decayWindow must be positive: " + decayWindow);
        }
        return new TemporalDecayConfig(DecayFunction.LINEAR, null, decayWindow);
    }

    // ─── Core computation ────────────────────────────────────────────────────────

    /**
     * Compute the decay weight for a hop of the given {@code age} (effect time − cause time).
     *
     * <p>Negative or zero ages are clamped to zero (treated as "instantaneous") before the decay
     * function is applied, so the result is always in {@code [0, 1]}.</p>
     *
     * @param age the non-negative age of the hop (never {@code null})
     * @return the decay weight in {@code [0.0, 1.0]}
     */
    public double weight(Duration age) {
        Objects.requireNonNull(age, "age");
        if (function == DecayFunction.NONE) {
            return 1.0;
        }
        // Clamp negative ages (shouldn't occur after precedence filter, but be defensive)
        double ageMillis = Math.max(0.0, (double) age.toMillis());

        if (function == DecayFunction.EXPONENTIAL) {
            double halfLifeMillis = (double) halfLife.toMillis();
            // 0.5^(age/halfLife) = exp(-ln(2) * age/halfLife)
            return Math.pow(0.5, ageMillis / halfLifeMillis);
        }

        if (function == DecayFunction.LINEAR) {
            double windowMillis = (double) decayWindow.toMillis();
            return Math.max(0.0, 1.0 - ageMillis / windowMillis);
        }

        return 1.0; // unreachable but safe
    }

    /**
     * Convenience: compute the decay weight from two {@link Instant}s.
     *
     * <p>If either instant is {@code null}, returns {@code 1.0} (unknown age → no decay applied).
     * This preserves the design contract from the spec: unknown-age hops are kept but not
     * penalised.</p>
     *
     * @param causeTime  when the cause occurred (may be {@code null})
     * @param effectTime when the effect occurred (may be {@code null})
     * @return the decay weight in {@code [0.0, 1.0]}
     */
    public double weight(Instant causeTime, Instant effectTime) {
        if (causeTime == null || effectTime == null) {
            return 1.0;
        }
        // age = effect − cause; negative if cause is after effect (shouldn't pass precedence filter)
        Duration age = Duration.between(causeTime, effectTime);
        return weight(age);
    }

    /**
     * Prior weight for "temporal unknown" scenarios: {@code P = 0.5 + 0.5·exp(−γ·Δt)}.
     *
     * <p>Unlike the hop-strength {@link #weight(Instant, Instant)} which returns {@code 1.0} when
     * timestamps are null (preserving "unknown age → no penalty"), this method returns {@code 0.5}
     * for null timestamps — signalling maximum uncertainty rather than "no penalty".  This is the
     * correct semantics for the {@link ai.kompile.graph.reasoning.prior.PriorProvider} cascade:
     * an entity whose temporal context is completely unknown gets the uninformative prior.</p>
     *
     * <p>γ is derived from the configured half-life for {@link DecayFunction#EXPONENTIAL}; for
     * {@link DecayFunction#NONE} and {@link DecayFunction#LINEAR} a default of ln(2)/3600
     * (≈1-hour half-life) is used.</p>
     *
     * @param referenceTime when the event/entity was last known (null = unknown → returns 0.5)
     * @param now           the current instant (null = unknown → returns 0.5)
     * @return prior in {@code [0.5, 1.0]}; {@code 1.0} when age=0, decaying toward {@code 0.5}
     */
    public double decayPrior(Instant referenceTime, Instant now) {
        if (referenceTime == null || now == null) return 0.5;
        double deltaT = Math.max(0.0, ChronoUnit.SECONDS.between(referenceTime, now));
        return 0.5 + 0.5 * Math.exp(-deriveGamma() * deltaT);
    }

    /**
     * Derives the temporal decay rate γ in s⁻¹.
     * For {@link DecayFunction#EXPONENTIAL}: {@code γ = ln(2) / halfLife_in_seconds}.
     * Otherwise uses the default (1-hour half-life: ln(2)/3600).
     */
    private double deriveGamma() {
        if (function == DecayFunction.EXPONENTIAL && halfLife != null && halfLife.toSeconds() > 0) {
            return Math.log(2.0) / halfLife.toSeconds();
        }
        return Math.log(2.0) / 3600.0;
    }

    // ─── Accessors ───────────────────────────────────────────────────────────────

    /** The decay function in use. */
    public DecayFunction getFunction() { return function; }

    /** Half-life for {@link DecayFunction#EXPONENTIAL}, or {@code null} otherwise. */
    public Duration getHalfLife() { return halfLife; }

    /** Full-zero window for {@link DecayFunction#LINEAR}, or {@code null} otherwise. */
    public Duration getDecayWindow() { return decayWindow; }

    @Override
    public String toString() {
        return switch (function) {
            case NONE        -> "TemporalDecayConfig{NONE}";
            case EXPONENTIAL -> "TemporalDecayConfig{EXPONENTIAL, halfLife=" + halfLife + "}";
            case LINEAR      -> "TemporalDecayConfig{LINEAR, window=" + decayWindow + "}";
        };
    }
}
