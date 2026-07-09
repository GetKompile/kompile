/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.confidence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Subjective Logic opinion: first-class epistemic primitive.
 * b + d + u = 1.0 (simplex constraint, epsilon-tolerant).
 * expectation() = belief + baseRate * uncertainty.
 * projectStatus() → VerifyStatusProjection (SUPPORTED/REFUTED/UNKNOWN)
 * projectBand() → StrengthBand
 */
public record Opinion(double belief, double disbelief, double uncertainty, double baseRate)
        implements java.io.Serializable {

    private static final long serialVersionUID = 1L;

    private static final double SIMPLEX_EPSILON = 1e-9;

    public Opinion {
        if (belief < 0 || disbelief < 0 || uncertainty < 0)
            throw new IllegalArgumentException("All opinion components must be >= 0; got b=" + belief + " d=" + disbelief + " u=" + uncertainty);
        double sum = belief + disbelief + uncertainty;
        if (Math.abs(sum - 1.0) > SIMPLEX_EPSILON)
            throw new IllegalArgumentException("belief + disbelief + uncertainty must equal 1.0; got " + sum);
        if (baseRate < 0 || baseRate > 1)
            throw new IllegalArgumentException("baseRate must be in [0,1]; got " + baseRate);
    }

    /** Projected probability: belief + baseRate * uncertainty. */
    public double expectation() { return belief + baseRate * uncertainty; }

    /** How much of the simplex is undecided. */
    public double ignorance() { return uncertainty; }

    /** True when no evidence has been accumulated (u ~= 1.0). */
    public boolean isVacuous() { return Math.abs(uncertainty - 1.0) < SIMPLEX_EPSILON; }

    /** True when both belief and disbelief exceed the given threshold. */
    public boolean isConflicted(double threshold) {
        return belief > threshold && disbelief > threshold;
    }

    // ─── Factories ────────────────────────────────────────────────────────────

    public static Opinion vacuous() { return vacuous(0.5); }

    public static Opinion vacuous(double baseRate) {
        return new Opinion(0.0, 0.0, 1.0, baseRate);
    }

    /**
     * From PSL soft-truth. supportCount rules fired → uncertainty = 1/(supportCount+1).
     */
    public static Opinion fromSoftTruth(double softTruth, long evidenceCount) {
        // evidenceCount drives uncertainty down
        double u = 1.0 / (evidenceCount + 1.0);
        double spread = 1.0 - u;
        double b = clamp(softTruth * spread);
        double d = clamp((1.0 - softTruth) * spread);
        // renormalize to ensure sum=1 exactly
        return ofNormalized(b, d, u, 0.5);
    }

    /**
     * From a scalar soft-truth using a default evidence count of 10 → uncertainty {@code u = 1/11 ≈ 0.091}.
     * This means "treat as well-supported but not certain"; it is <b>not</b> zero-uncertainty. Callers
     * that have a real support/evidence count should use {@link #fromSoftTruth(double, long)} instead —
     * the fixed k=10 here is an assumption, and WP7 replaces the call sites where it matters with a
     * moment-matched evidence count. WP1e (Javadoc-only).
     */
    public static Opinion fromSoftTruth(double softTruth) {
        return fromSoftTruth(softTruth, 10); // default evidence count; see fromSoftTruth(double, long)
    }

    /**
     * From an observed hard fact (value in [0,1]; uncertainty=0).
     */
    public static Opinion fromObserved() {
        return new Opinion(1.0, 0.0, 0.0, 1.0);
    }

    /**
     * From an observed scalar value (clamps b+d to 1 with u=0).
     */
    public static Opinion fromObservedValue(double value) {
        double b = clamp(value);
        double d = clamp(1.0 - value);
        // force u=0 by renormalizing
        double total = b + d;
        if (total < 1e-12) return new Opinion(0.5, 0.5, 0.0, value);
        return new Opinion(b / total, d / total, 0.0, clamp(value));
    }

    /**
     * From positive/negative evidence counts using Beta-distribution mapping.
     * k = prior strength (default 2 for non-informative).
     * b = pos/(pos+neg+k), d = neg/(pos+neg+k), u = k/(pos+neg+k)
     */
    public static Opinion fromBetaEvidence(double pos, double neg) {
        return fromBetaEvidence(pos, neg, 0.5, 2.0);
    }

    public static Opinion fromBetaEvidence(double pos, double neg, double baseRate, double k) {
        double total = pos + neg + k;
        if (total < 1e-12) {
            // No evidence and no prior strength (pos+neg+k == 0) — avoid a 0/0 NaN opinion; the
            // honest answer is total ignorance. WP1b.
            return vacuous(baseRate);
        }
        return new Opinion(pos / total, neg / total, k / total, baseRate);
    }

    /**
     * From a Bayesian posterior (MEBN output). Information gain drives uncertainty down.
     */
    public static Opinion fromBayesianPosterior(double posterior, double baseRate) {
        double infGain = Math.abs(posterior - baseRate);
        double u = Math.max(0.0, 1.0 - 2.0 * infGain);
        double spread = 1.0 - u;
        return ofNormalized(posterior * spread, (1.0 - posterior) * spread, u, baseRate);
    }

    /**
     * From an embedding calibrated score with configurable uncertainty floor.
     */
    public static Opinion fromEmbeddingScore(double calibratedScore, double embeddingUncertainty) {
        double spread = 1.0 - embeddingUncertainty;
        return ofNormalized(calibratedScore * spread, (1.0 - calibratedScore) * spread, embeddingUncertainty, 0.5);
    }

    // ─── Fusion operators ────────────────────────────────────────────────────

    /**
     * Cumulative fusion: combine two <em>independent</em> opinions (Jøsang §12.2, CBF).
     * Returns a lower-uncertainty opinion when sources agree; conflicted when they disagree.
     * <p>NOT idempotent: fusing x with itself lowers uncertainty (treats x as two independent
     * pieces of evidence). Use {@link #averagingFuse} for correlated/same-evidence sources.</p>
     */
    public Opinion cumulativeFuse(Opinion other) {
        double ua = this.uncertainty;
        double ub = other.uncertainty;
        double denom = ua + ub - ua * ub;
        if (denom < 1e-12) {
            // Both certain (dogmatic pair) — component mean
            return new Opinion(
                clamp((this.belief + other.belief) / 2),
                clamp((this.disbelief + other.disbelief) / 2),
                0.0,
                clamp((this.baseRate + other.baseRate) / 2)
            );
        }
        double b = clamp((this.belief * ub + other.belief * ua) / denom);
        double d = clamp((this.disbelief * ub + other.disbelief * ua) / denom);
        double u = clamp(ua * ub / denom);
        double denomA = 2.0 - ua - ub;
        double a = denomA < 1e-12 ? clamp((this.baseRate + other.baseRate) / 2)
                                  : clamp((this.baseRate * ub + other.baseRate * ua) / denomA);
        return ofNormalized(b, d, u, a);
    }

    /** Static varargs entry point for cumulativeFuse of many independent sources. */
    public static Opinion cumulativeFuse(Opinion... opinions) {
        if (opinions == null || opinions.length == 0) return vacuous();
        Opinion acc = opinions[0];
        for (int i = 1; i < opinions.length; i++) acc = acc.cumulativeFuse(opinions[i]);
        return acc;
    }

    // ─── E7: Jøsang Averaging Belief Fusion (ABF) ───────────────────────────

    /**
     * Jøsang Averaging Belief Fusion (ABF) — Jøsang §12.3.
     * <p>Use for <em>correlated / same-evidence</em> sources: multiple observers watching the
     * same event, or modalities that read the same fact store.  Key property:
     * <strong>idempotent</strong> — {@code x.averagingFuse(x) == x}.</p>
     *
     * <p>Formula (two sources A and B):
     * <pre>
     *   b  = (b_A·u_B + b_B·u_A) / (u_A + u_B)
     *   d  = (d_A·u_B + d_B·u_A) / (u_A + u_B)
     *   u  = 2·u_A·u_B            / (u_A + u_B)
     *   a  = (a_A + a_B) / 2
     * </pre>
     * Dogmatic-pair fallback (u_A = u_B = 0): component mean (idempotent holds trivially).
     * </p>
     */
    public Opinion averagingFuse(Opinion other) {
        double ua = this.uncertainty;
        double ub = other.uncertainty;
        double denom = ua + ub;
        if (denom < 1e-12) {
            // Both dogmatic — component mean (preserves idempotence)
            return ofNormalized(
                (this.belief + other.belief) / 2,
                (this.disbelief + other.disbelief) / 2,
                0.0,
                (this.baseRate + other.baseRate) / 2
            );
        }
        double b = (this.belief * ub + other.belief * ua) / denom;
        double d = (this.disbelief * ub + other.disbelief * ua) / denom;
        double u = 2.0 * ua * ub / denom;
        double a = (this.baseRate + other.baseRate) / 2.0;
        return ofNormalized(b, d, u, a);
    }

    /**
     * N-ary Averaging Belief Fusion — left-fold of {@link #averagingFuse(Opinion)}.
     * Empty → vacuous. Idempotent: all-identical inputs return that same opinion.
     */
    public static Opinion averagingFuse(List<Opinion> opinions) {
        if (opinions == null || opinions.isEmpty()) return vacuous();
        Opinion acc = opinions.get(0);
        for (int i = 1; i < opinions.size(); i++) acc = acc.averagingFuse(opinions.get(i));
        return acc;
    }

    /**
     * Varargs overload for {@link #averagingFuse(List)}.
     */
    public static Opinion averagingFuse(Opinion... opinions) {
        if (opinions == null || opinions.length == 0) return vacuous();
        return averagingFuse(List.of(opinions));
    }

    /**
     * @deprecated Use {@link #averagingFuse(Opinion)} for correlated/same-evidence sources
     *             (Jøsang ABF, idempotent) or {@link #cumulativeFuse(Opinion)} for independent
     *             sources (Jøsang CBF, uncertainty-reducing).  This method was a plain component
     *             mean; it is now a backward-compatible alias for {@code averagingFuse}.
     */
    @Deprecated
    public Opinion averageFuse(Opinion other) {
        return averagingFuse(other);
    }

    /**
     * @deprecated Use {@link #averagingFuse(Opinion...)} instead.
     */
    @Deprecated
    public static Opinion averageFuse(Opinion... opinions) {
        return averagingFuse(opinions);
    }

    // ─── E7: Jøsang Weighted Belief Fusion (WBF) ────────────────────────────

    /**
     * Jøsang Weighted Belief Fusion (WBF) — Jøsang §12.4.
     * <p>For sources with <em>different reliability weights</em>: each source i contributes
     * proportionally to its certainty (1 − u_i).  Vacuous inputs (u = 1) are fully ignored.
     * If ALL inputs are vacuous, returns a vacuous opinion whose base rate is the simple mean
     * of the inputs' base rates.</p>
     *
     * <pre>
     *   w_i = 1 − u_i
     *   W   = Σ w_i
     *   b   = Σ(w_i · b_i) / W
     *   d   = Σ(w_i · d_i) / W
     *   u   derived from b+d+u=1 (or 1/N if W=0)
     *   a   = Σ(w_i · a_i) / W  (or simple mean if W=0)
     * </pre>
     */
    public static Opinion weightedFuse(List<Opinion> opinions) {
        if (opinions == null || opinions.isEmpty()) return vacuous();
        if (opinions.size() == 1) return opinions.get(0);

        double totalWeight = 0, sumB = 0, sumD = 0, sumA = 0;
        for (Opinion o : opinions) {
            double w = 1.0 - o.uncertainty; // certainty weight
            totalWeight += w;
            sumB += w * o.belief;
            sumD += w * o.disbelief;
            sumA += w * o.baseRate;
        }
        if (totalWeight < 1e-12) {
            // All vacuous — return vacuous with mean base rate
            double meanA = opinions.stream().mapToDouble(Opinion::baseRate).average().orElse(0.5);
            return vacuous(clamp(meanA));
        }
        double b = clamp(sumB / totalWeight);
        double d = clamp(sumD / totalWeight);
        double u = clamp(1.0 - b - d);
        double a = clamp(sumA / totalWeight);
        return ofNormalized(b, d, u, a);
    }

    // ─── E7: Consensus & Compromise (CCF) ───────────────────────────────────

    /**
     * Consensus: certainty-weighted centroid (Jøsang §12.5).
     * <p>Use for N sources of varying reliability when there is potential conflict between them.
     * Idempotent: {@code consensus(x, x) == x}.
     * <strong>Contrast with {@link #averagingFuse}</strong>: consensus uses (1−u) weights and
     * leaves uncertainty at {@code 1 − b − d} (can be high when sources strongly disagree);
     * ABF uses uncertainty-mass weights and drives u toward the harmonic-mean of inputs.</p>
     */
    public static Opinion consensus(List<Opinion> opinions) {
        if (opinions == null || opinions.isEmpty()) return vacuous();
        if (opinions.size() == 1) return opinions.get(0);
        double totalWeight = 0, sumB = 0, sumD = 0, sumA = 0;
        for (Opinion o : opinions) {
            double w = 1.0 - o.uncertainty;
            totalWeight += w;
            sumB += w * o.belief;
            sumD += w * o.disbelief;
            sumA += w * o.baseRate;
        }
        if (totalWeight < 1e-12) return vacuous();
        double b = clamp(sumB / totalWeight);
        double d = clamp(sumD / totalWeight);
        double u = clamp(1.0 - b - d);
        double a = clamp(sumA / totalWeight);
        return ofNormalized(b, d, u, a);
    }

    public static Opinion consensus(Collection<Opinion> opinions) {
        return consensus(opinions instanceof List ? (List<Opinion>) opinions : List.copyOf(opinions));
    }

    // ─── Jøsang CCF: Consensus & Compromise Fusion ──────────────────────────

    /**
     * Jøsang Binomial Consensus &amp; Compromise Fusion (CCF) — Jøsang 2016 §12.6.
     *
     * <p>Unlike {@link #consensus(List)} (certainty-weighted centroid), CCF first
     * extracts the <em>consensus</em> component shared by all sources (min belief and
     * min disbelief), then distributes the per-source <em>residuals</em> as a
     * compromise component weighted by each source's uncertainty mass.  This means:
     * <ul>
     *   <li>Unanimous agreement → result ≈ that opinion (idempotent).</li>
     *   <li>High pairwise conflict (b≈1 vs d≈1) → residuals cancel; u grows; expectation
     *       stays near the base rate (avoids Zadeh-style minority certainty).</li>
     *   <li>Vacuous source (u=1) → does NOT sharpen the other opinions (vacuous mass
     *       "dilutes" the compromise toward the consensus floor).</li>
     * </ul>
     *
     * <h3>Algorithm (binomial N-source)</h3>
     * <pre>
     *   b_cons = min_i(b_i)
     *   d_cons = min_i(d_i)
     *   u_sum  = Σ_i max(u_i, ε)          // ε=1e-9 prevents all-dogmatic collapse
     *   b_comp = Σ_i (b_i − b_cons) · u_i / u_sum
     *   d_comp = Σ_i (d_i − d_cons) · u_i / u_sum
     *   b_fused = clamp(b_cons + b_comp)
     *   d_fused = clamp(d_cons + d_comp)
     *   u_fused = clamp(1 − b_fused − d_fused)
     *   a_fused = certainty-weighted mean of base rates
     * </pre>
     *
     * <p><strong>Properties:</strong>
     * <ol>
     *   <li>Idempotent: {@code ccFuse([x, x]) ≈ x}</li>
     *   <li>Commutative: {@code ccFuse([a,b]) ≈ ccFuse([b,a])}</li>
     *   <li>Unanimous: all same → result ≈ that opinion</li>
     *   <li>High conflict: NOT dogmatic; E near base rate, |b−d| small</li>
     *   <li>Vacuous: fusing with a vacuous opinion does not sharpen (u non-decreasing)</li>
     * </ol>
     *
     * @param opinions source opinions; must be non-null and non-empty
     * @return the CCF-fused opinion
     */
    public static Opinion ccFuse(List<Opinion> opinions) {
        if (opinions == null || opinions.isEmpty()) return vacuous();
        if (opinions.size() == 1) return opinions.get(0);

        // Phase 1 — consensus: shared minimum masses
        double bCons = opinions.stream().mapToDouble(Opinion::belief).min().getAsDouble();
        double dCons = opinions.stream().mapToDouble(Opinion::disbelief).min().getAsDouble();

        // Phase 2 — compromise: residuals weighted by each source's uncertainty
        // Use epsilon floor so all-dogmatic inputs still distribute residuals rather than
        // producing 0/0; the epsilon is small enough not to affect non-degenerate cases.
        final double EPS = 1e-9;
        double uSum = opinions.stream()
                .mapToDouble(o -> Math.max(o.uncertainty(), EPS))
                .sum();

        double bComp = opinions.stream()
                .mapToDouble(o -> (o.belief() - bCons) * Math.max(o.uncertainty(), EPS))
                .sum() / uSum;

        double dComp = opinions.stream()
                .mapToDouble(o -> (o.disbelief() - dCons) * Math.max(o.uncertainty(), EPS))
                .sum() / uSum;

        double bFused = clamp(bCons + bComp);
        double dFused = clamp(dCons + dComp);
        double uFused = clamp(1.0 - bFused - dFused);

        // Base rate: certainty-weighted average (vacuous sources contribute nothing)
        double certSum = opinions.stream().mapToDouble(o -> 1.0 - o.uncertainty()).sum();
        double aFused;
        if (certSum < 1e-15) {
            aFused = opinions.stream().mapToDouble(Opinion::baseRate).average().orElse(0.5);
        } else {
            aFused = opinions.stream()
                    .mapToDouble(o -> (1.0 - o.uncertainty()) * o.baseRate())
                    .sum() / certSum;
        }

        return ofNormalized(bFused, dFused, uFused, aFused);
    }

    // ─── E7: FusionMode router ───────────────────────────────────────────────

    /**
     * Fusion mode selector.
     *
     * <table>
     * <caption>Selection guide (Jøsang 2016)</caption>
     * <tr><th>Mode</th><th>When to use</th><th>Routes to</th></tr>
     * <tr><td>CUMULATIVE</td>
     *     <td>Sources represent <em>independent</em> evidence channels (separate documents,
     *         non-overlapping knowledge bases).  Uncertainty drops with each agreeing
     *         source — NOT idempotent.</td>
     *     <td>{@link #cumulativeFuse(Opinion...)}</td></tr>
     * <tr><td>AVERAGING</td>
     *     <td>Multiple observers of the <em>same</em> evidence (e.g. PSL and MEBN both
     *         reading the same fact store, or two sensors watching one event).
     *         Idempotent; prevents spurious confidence inflation.</td>
     *     <td>{@link #averagingFuse(List)}</td></tr>
     * <tr><td>WEIGHTED</td>
     *     <td>Sources have differing reliability (vacuous inputs ignored, certainty-weighted
     *         blend of the rest).</td>
     *     <td>{@link #weightedFuse(List)}</td></tr>
     * <tr><td>CONSENSUS_COMPROMISE</td>
     *     <td>Deep conflict between sources where neither can be discarded.  Extracts a
     *         consensus floor (min b, min d) and distributes residuals as an
     *         uncertainty-weighted compromise.  Idempotent; high-conflict pairs converge
     *         toward the base rate without collapsing to dogmatic certainty.</td>
     *     <td>{@link #ccFuse(List)}</td></tr>
     * </table>
     */
    public enum FusionMode {
        /** Independent evidence channels — uncertainty-reducing, NOT idempotent. */
        CUMULATIVE,
        /** Same-evidence observers — idempotent, Jøsang ABF. */
        AVERAGING,
        /** Reliability-weighted blend; vacuous sources ignored. */
        WEIGHTED,
        /**
         * Conflicting sources — routes to the true Jøsang Consensus &amp; Compromise Fusion
         * (CCF) via {@link #ccFuse(List)}.  Idempotent; avoids Zadeh-style minority certainty.
         */
        CONSENSUS_COMPROMISE
    }

    /**
     * Fuse a list of opinions using the specified {@link FusionMode}.
     *
     * @param mode     the fusion operator to apply
     * @param opinions the opinions to fuse; must be non-null, but may be empty
     * @return the fused opinion (vacuous if the list is empty)
     */
    public static Opinion fuse(FusionMode mode, List<Opinion> opinions) {
        if (opinions == null || opinions.isEmpty()) return vacuous();
        return switch (mode) {
            case CUMULATIVE           -> cumulativeFuse(opinions.toArray(new Opinion[0]));
            case AVERAGING            -> averagingFuse(opinions);
            case WEIGHTED             -> weightedFuse(opinions);
            case CONSENSUS_COMPROMISE -> ccFuse(opinions);
        };
    }

    // ─── E7: Uncertainty-maximized factory ──────────────────────────────────

    /**
     * Uncertainty-maximized opinion for an externally supplied probability {@code p} and base
     * rate {@code a} — Jøsang §4.5.
     *
     * <p>The uncertainty-maximized opinion is the one with the highest possible uncertainty
     * whose projected probability equals {@code p}.  It lies on a simplex edge (either b=0
     * or d=0) and is the principled way to import a point probability without fabricating
     * evidence mass.</p>
     *
     * <pre>
     *   if p ≥ a:  d = 0,  u = (1−p)/(1−a),  b = 1−u
     *   if p &lt; a:  b = 0,  u = p/a,           d = 1−u
     * </pre>
     *
     * <p>Verify: E = b + a·u = (1−u) + a·u = 1 − u(1−a) = 1 − (1−p) = p  ✓ (for p≥a case).
     * Degenerate cases a=0 and a=1 are clamped to avoid division by zero.</p>
     *
     * @param p        projected probability in [0,1]
     * @param baseRate prior base rate in [0,1]
     */
    public static Opinion uncertaintyMaximized(double p, double baseRate) {
        double pp = clamp(p);
        // Clamp a away from 0 and 1 to avoid division by zero; tiny epsilon is fine
        double a = Math.max(1e-9, Math.min(1.0 - 1e-9, baseRate));
        if (pp >= a) {
            // d = 0 edge
            double u = (1.0 - pp) / (1.0 - a);
            double b = 1.0 - u;
            return ofNormalized(b, 0.0, u, clamp(baseRate));
        } else {
            // b = 0 edge
            double u = pp / a;
            double d = 1.0 - u;
            return ofNormalized(0.0, d, u, clamp(baseRate));
        }
    }

    // ─── E7: withBaseRate copy + cumulativeFuseCanonical ────────────────────

    /**
     * Return a copy of this opinion with a different base rate (all other components unchanged).
     * <p>Use this to canonicalize opinions to a common prior before cumulative fusion — the
     * fix for the non-monotone-expectation gotcha (#13): when sources have different base
     * rates, fusing them shifts the combined prior term {@code a·u} in ways that can lower
     * {@code E = b + a·u} even when both sources are positive.  A common base rate removes
     * this artefact.</p>
     */
    public Opinion withBaseRate(double a) {
        return ofNormalized(this.belief, this.disbelief, this.uncertainty, clamp(a));
    }

    /**
     * Cumulative fusion with base-rate canonicalization (fix #13).
     * <p>All opinions are first rebased to {@code commonBaseRate} via {@link #withBaseRate(double)},
     * then fused with {@link #cumulativeFuse(Opinion...)}.  This restores expectation-monotonicity
     * for agreeing positive sources: when all sources have the same base rate, E(fused) ≥ min(E_i)
     * and u(fused) &lt; min(u_i).</p>
     *
     * @param opinions       opinions to fuse; empty → vacuous(commonBaseRate)
     * @param commonBaseRate the shared prior to apply before fusion
     */
    public static Opinion cumulativeFuseCanonical(List<Opinion> opinions, double commonBaseRate) {
        if (opinions == null || opinions.isEmpty()) return vacuous(clamp(commonBaseRate));
        List<Opinion> rebased = new ArrayList<>(opinions.size());
        for (Opinion o : opinions) rebased.add(o.withBaseRate(commonBaseRate));
        return cumulativeFuse(rebased.toArray(new Opinion[0]));
    }

    // ─── E7: Comultiplication (OR) ───────────────────────────────────────────

    /**
     * Binomial comultiplication — logical OR of two INDEPENDENT propositions (Jøsang §7.4).
     *
     * <p>This is the dual of {@link #conjoin}: while conjoin models AND (multiplication),
     * comultiplication models OR (co-multiplication / co-product).  Key property:
     * {@code E(x.comultiply(y)) ≈ E(x) + E(y) − E(x)·E(y)} (exact for dogmatic inputs;
     * approximately true in general, derived from the Beta evidence mapping).</p>
     *
     * <p>Jøsang formula (SL §7.4, via the duality b̄=d, d̄=b on complement):
     * <pre>
     *   b_y = bx + by − bx·by
     *   d_y = dx·dy
     *   u_y = ux·uy + (bx·uy + ux·by) · (ax+ay−ax·ay) / ((1 − ax·ay) * baseRate_y)
     *                                                        [distributes uncertainty upward]
     *   a_y = ax + ay − ax·ay
     * </pre>
     * For simplicity and numerical stability we derive comultiply via the complement identity:
     * {@code x OR y = ¬(¬x AND ¬y)} — apply complement, conjoin, complement back.
     * This ensures E(x∨y) = E(x)+E(y)−E(x)E(y) holds exactly (since conjoin preserves
     * E(x⊙y)=E(x)E(y) and complement maps E→1−E).</p>
     */
    public Opinion comultiply(Opinion other) {
        // x OR y = ¬(¬x ⊙ ¬y)
        return this.complement().conjoin(other.complement()).complement();
    }

    /**
     * Left-fold comultiplication over many independent propositions; empty → vacuous.
     */
    public static Opinion comultiplyAll(Opinion... opinions) {
        if (opinions == null || opinions.length == 0) return vacuous();
        Opinion acc = opinions[0];
        for (int i = 1; i < opinions.length; i++) acc = acc.comultiply(opinions[i]);
        return acc;
    }

    // ─── E7: Opinion deduction ───────────────────────────────────────────────

    /**
     * Binomial deduction (Jøsang §7.2): conditional propagation along a directed edge.
     *
     * <p>Given an antecedent opinion ω_x and conditional opinions ω_{y|x} and ω_{y|x̄}
     * (the consequent given the antecedent is true or false respectively), derives ω_y.</p>
     *
     * <p><strong>Dogmatic-conditional exact case</strong> (u_{y|x} = u_{y|x̄} = 0):
     * <pre>
     *   P(y) = P(y|x)·E(x) + P(y|x̄)·(1−E(x))
     *   a_y  = P(y|x)·a_x  + P(y|x̄)·(1−a_x)
     *   u_y  = 0   (conditionals are certain → marginal is certain)
     * </pre>
     * Returns {@code fromObservedValue(P(y))} with the appropriate base rate.</p>
     *
     * <p><strong>General case (approximation)</strong>: the full Jøsang apex construction
     * finds the uncertainty-maximized opinion consistent with the projected probability.
     * We compute P(y) exactly as above (using E(x) for P(x)) then apply
     * {@link #uncertaintyMaximized(double, double)} with a_y computed from the dogmatic blend,
     * scaled by the antecedent uncertainty to reflect residual ignorance.  This approximation
     * is exact at the dogmatic extremes (u_x→0 and u_x→1) and conservative in between
     * (u_y ≥ the apex u_y).</p>
     *
     * @param antecedent        ω_x — opinion about the antecedent proposition
     * @param consequentIfTrue  ω_{y|x} — conditional opinion when x is true
     * @param consequentIfFalse ω_{y|x̄} — conditional opinion when x is false
     */
    public static Opinion deduce(Opinion antecedent,
                                 Opinion consequentIfTrue,
                                 Opinion consequentIfFalse) {
        double Ex  = antecedent.expectation();
        double Eyx = consequentIfTrue.expectation();
        double Eyn = consequentIfFalse.expectation();

        // Total probability: P(y) = P(y|x)·E(x) + P(y|x̄)·(1−E(x))
        double Py = Eyx * Ex + Eyn * (1.0 - Ex);

        // Base rate a_y = P(y|x)·a_x + P(y|x̄)·(1−a_x)
        double ax = antecedent.baseRate();
        double ay = Eyx * ax + Eyn * (1.0 - ax);
        ay = clamp(ay);

        boolean dogmaticConditionals =
                consequentIfTrue.uncertainty() < 1e-9 && consequentIfFalse.uncertainty() < 1e-9;

        if (dogmaticConditionals) {
            // Exact case: conditionals are certain → marginal is certain
            double by = clamp(Py);
            double dy = clamp(1.0 - Py);
            return ofNormalized(by, dy, 0.0, ay);
        }

        // General case: uncertainty-maximized approximation
        // Antecedent uncertainty contributes residual ignorance to the consequent.
        // When antecedent is vacuous (ux=1), the consequent uncertainty is also maximized.
        // We derive an uncertainty-maximized opinion at P(y) and then blend its uncertainty
        // toward a floor of antecedent.uncertainty (conservative: can't be MORE certain
        // about y than we are about x, all else equal).
        Opinion umOp = uncertaintyMaximized(Py, ay);
        double uFloor = antecedent.uncertainty()
                * Math.abs(Eyx - Eyn); // scales: if conditionals agree, little added uncertainty
        double uy = Math.max(umOp.uncertainty(), Math.min(uFloor, 1.0));
        // Derive b, d from P(y) = b + ay·u with the resolved uy
        // If p >= ay: d=0, b=P(y)-ay*uy  clamped
        // If p <  ay: b=0, d=(ay*uy-p)/(1-ay) ... simplify via uncertaintyMaximized
        if (uy > umOp.uncertainty() + 1e-12) {
            // uy was raised by the floor — recompute consistent opinion preserving E(y)=Py
            double by, dy;
            if (Py >= ay) {
                // P(y) = b + ay*uy → b = P(y) - ay*uy, d = 0
                by = clamp(Py - ay * uy);
                dy = 0.0;
            } else {
                // P(y) = ay*uy → if uy > P(y)/ay we lower uy to P(y)/ay
                uy = clamp(Py / Math.max(ay, 1e-9));
                by = 0.0;
                dy = clamp(1.0 - uy);
            }
            return ofNormalized(by, dy, uy, ay);
        }
        return umOp;
    }

    // ─── WP4: Opinion algebra (conjunction · discount · negation · conflict) ─────
    // All pure; all preserve the simplex via ofNormalized. Jøsang, Subjective Logic (2016).

    /**
     * Binomial multiplication (Jøsang §7.1) — conjunction of two INDEPENDENT propositions (logical
     * AND). Expectation is exactly multiplicative: {@code E(x.conjoin(y)) = E(x)·E(y)}.
     */
    public Opinion conjoin(Opinion o) {
        double ax = this.baseRate, ay = o.baseRate;
        double denom = 1.0 - ax * ay;
        if (denom < 1e-12) {
            // Degenerate a≈1 both sides: product base rate is 1; residual mass goes to uncertainty.
            double b2 = this.belief * o.belief;
            double d2 = this.disbelief + o.disbelief - this.disbelief * o.disbelief;
            return ofNormalized(b2, d2, Math.max(0.0, 1.0 - b2 - d2), 1.0);
        }
        double b2 = this.belief * o.belief
                + ((1 - ax) * ay * this.belief * o.uncertainty
                   + ax * (1 - ay) * this.uncertainty * o.belief) / denom;
        double d2 = this.disbelief + o.disbelief - this.disbelief * o.disbelief;
        double u2 = this.uncertainty * o.uncertainty
                + ((1 - ay) * this.belief * o.uncertainty
                   + (1 - ax) * this.uncertainty * o.belief) / denom;
        return ofNormalized(b2, d2, u2, ax * ay);
    }

    /** Left-fold conjunction of many independent propositions; empty → vacuous. */
    public static Opinion conjoinAll(Opinion... opinions) {
        if (opinions == null || opinions.length == 0) return vacuous();
        Opinion acc = opinions[0];
        for (int i = 1; i < opinions.length; i++) acc = acc.conjoin(opinions[i]);
        return acc;
    }

    /**
     * Trust discounting (Jøsang §14.3): scale belief/disbelief by a scalar dogmatic trust
     * {@code t ∈ [0,1]}; the discounted mass becomes uncertainty. {@code discount(1)} is identity,
     * {@code discount(0)} is vacuous with the same base rate.
     */
    public Opinion discount(double t) {
        double tt = clamp(t);
        return ofNormalized(tt * this.belief, tt * this.disbelief,
                1.0 - tt * (this.belief + this.disbelief), this.baseRate);
    }

    /** Negation (Jøsang §4): swap belief/disbelief and complement the base rate. */
    public Opinion complement() {
        return new Opinion(this.disbelief, this.belief, this.uncertainty, clamp(1.0 - this.baseRate));
    }

    /**
     * Binomial degree of conflict with another opinion on the SAME proposition: {@code b₁d₂ + d₁b₂}
     * ∈ [0,1]. Symmetric; 0 for vacuous pairs; → 1 for dogmatic-opposed opinions.
     */
    public double conflict(Opinion o) {
        return this.belief * o.disbelief + this.disbelief * o.belief;
    }

    // ─── Trichotomy projection ──────────────────────────────────────────────

    public enum VerifyStatusProjection { SUPPORTED, REFUTED, UNKNOWN }

    /**
     * Project to VerifyStatusProjection.
     * UNKNOWN when uncertainty >= unknownThreshold.
     * SUPPORTED when expectation >= supportThreshold.
     * REFUTED when expectation < refuteThreshold.
     */
    public VerifyStatusProjection projectStatus(double supportThreshold, double refuteThreshold, double unknownThreshold) {
        if (uncertainty >= unknownThreshold) return VerifyStatusProjection.UNKNOWN;
        double e = expectation();
        if (e >= supportThreshold) return VerifyStatusProjection.SUPPORTED;
        if (e < refuteThreshold) return VerifyStatusProjection.REFUTED;
        return VerifyStatusProjection.UNKNOWN;
    }

    /** Convenience with defaults: support>=0.5, refute<0.3, unknown>=0.6 */
    public VerifyStatusProjection projectStatus() {
        return projectStatus(0.5, 0.3, 0.6);
    }

    /** Project to a StrengthBand using both expectation and uncertainty. */
    public StrengthBand projectBand() {
        double e = expectation();
        if (e >= 0.85 && uncertainty < 0.15) return StrengthBand.ESTABLISHED;
        if (e >= 0.70 && uncertainty < 0.30) return StrengthBand.HIGH;
        if (e >= 0.40 && uncertainty < 0.60) return StrengthBand.PROBABLE;
        if (e >= 0.10) return StrengthBand.SPECULATIVE;
        return StrengthBand.SUPPRESSED;
    }

    // ─── JSON ────────────────────────────────────────────────────────────────

    public String toJson() {
        return String.format("{\"belief\":%.9f,\"disbelief\":%.9f,\"uncertainty\":%.9f,\"baseRate\":%.9f}",
            belief, disbelief, uncertainty, baseRate);
    }

    public static Opinion fromJson(String json) {
        double b = parseField(json, "belief");
        double d = parseField(json, "disbelief");
        double u = parseField(json, "uncertainty");
        double a = parseField(json, "baseRate");
        return new Opinion(b, d, u, a);
    }

    private static double parseField(String json, String field) {
        String key = "\"" + field + "\":";
        int idx = json.indexOf(key);
        if (idx < 0) throw new IllegalArgumentException("Missing field: " + field + " in " + json);
        int start = idx + key.length();
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
        return Double.parseDouble(json.substring(start, end).trim());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static double clamp(double v) { return Math.min(1.0, Math.max(0.0, v)); }

    /** Normalize b,d,u so they sum to 1.0; keeps baseRate as-is. */
    private static Opinion ofNormalized(double b, double d, double u, double baseRate) {
        b = clamp(b); d = clamp(d); u = clamp(u);
        double sum = b + d + u;
        if (sum < 1e-12) return vacuous(clamp(baseRate));
        return new Opinion(b / sum, d / sum, u / sum, clamp(baseRate));
    }
}
