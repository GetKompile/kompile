/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.psl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Sampling-based marginal inference for HL-MRF PSL programs.
 *
 * <h3>Algorithm: Perturb-and-MAP</h3>
 * <p>The canonical approach (Papandreou & Yuille 2011; confirmed in Bach et al. 2017 §4.3)
 * for approximate marginals on a continuous HL-MRF is to draw Gumbel perturbations on the
 * energy and solve MAP each time, then average the MAP solutions across draws. Each sample
 * is produced by:
 * <ol>
 *   <li>Perturbing the atom-level energy by adding Gumbel noise scaled by the temperature
 *       parameter {@code τ} (default 0.1); this transforms the MAP distribution into a
 *       richer distribution over assignments.</li>
 *   <li>Solving MAP on the perturbed program ({@link HlMrfMapInference#solve}).</li>
 *   <li>Recording the MAP solution as one sample.</li>
 * </ol>
 * After {@code numSamples} draws, the empirical mean and variance of each target atom's
 * truth value are collected into {@link AtomMarginal} objects.</p>
 *
 * <h3>Burn-in / warm-up</h3>
 * <p>The first {@code burnIn} samples are discarded to allow the sampling distribution to
 * stabilise before statistics are collected (analogous to MCMC burn-in).</p>
 *
 * <h3>Relation to MAP</h3>
 * <p>When temperature {@code τ → 0}, every perturbed MAP solution converges to the true MAP
 * solution, and the marginal mean approaches the MAP point estimate. At moderate τ (0.1–0.5)
 * the marginals reflect genuine uncertainty in the HL-MRF distribution.</p>
 *
 * <h3>Dependencies</h3>
 * <p>Purely plain-Java; no ND4J needed at marginal inference time (although the underlying
 * {@link HlMrfMapInference} may dispatch to ND4J for large programs).</p>
 */
public final class PslMarginalInference {

    /** Default number of perturb-and-MAP samples. */
    public static final int DEFAULT_SAMPLES = 50;

    /** Default burn-in samples (discarded before statistics are collected). */
    public static final int DEFAULT_BURN_IN = 10;

    /**
     * Default Gumbel-perturbation temperature. Smaller values concentrate samples near
     * the MAP mode; larger values explore more of the HL-MRF distribution.
     */
    public static final double DEFAULT_TEMPERATURE = 0.1;

    private PslMarginalInference() {}

    // ─── Result ──────────────────────────────────────────────────────────────────

    /**
     * Marginal inference outcome.
     *
     * @param marginals    per-atom marginal statistics, keyed by atom key
     * @param mapValues    the un-perturbed MAP solution (baseline point estimate)
     * @param samplesUsed  number of samples that contributed to the statistics
     */
    public record Result(Map<String, AtomMarginal> marginals,
                         Map<String, Double> mapValues,
                         int samplesUsed,
                         List<Map<String, Double>> rawSamples) {

        /**
         * Back-compat constructor without raw samples (empty list). Existing 3-arg call sites keep
         * working; the perturb-and-MAP sampler uses the 4-arg form so the per-sample MAP assignments
         * (after burn-in) are available for sample-based info-gain / BALD on the PSL path.
         */
        public Result(Map<String, AtomMarginal> marginals, Map<String, Double> mapValues, int samplesUsed) {
            this(marginals, mapValues, samplesUsed, List.of());
        }

        /**
         * Convenience accessor for a single atom's marginal.
         *
         * @param atomKey the atom key
         * @return the marginal, or a zero-variance marginal at the MAP value if not found
         */
        public AtomMarginal get(String atomKey) {
            AtomMarginal m = marginals.get(atomKey);
            if (m != null) return m;
            double mapVal = mapValues.getOrDefault(atomKey, 0.0);
            return AtomMarginal.deterministic(atomKey, mapVal);
        }
    }

    // ─── Entry points ─────────────────────────────────────────────────────────────

    /**
     * Run marginal inference with default parameters.
     *
     * @param program the PSL program to run marginal inference on
     * @return marginal inference result
     */
    public static Result solve(PslProgram program) {
        return solve(program, DEFAULT_SAMPLES, DEFAULT_BURN_IN, DEFAULT_TEMPERATURE,
                new Random(42));
    }

    /**
     * Run marginal inference with explicit parameters.
     *
     * @param program    the PSL program
     * @param numSamples total number of perturb-and-MAP draws (including burn-in)
     * @param burnIn     number of initial samples to discard
     * @param temperature Gumbel perturbation scale; larger = more uncertainty
     * @param rng        random number generator for reproducibility
     * @return marginal inference result
     */
    public static Result solve(PslProgram program, int numSamples, int burnIn,
                               double temperature, Random rng) {
        if (numSamples <= burnIn) {
            throw new IllegalArgumentException(
                    "numSamples (" + numSamples + ") must be > burnIn (" + burnIn + ")");
        }

        // 1. Run un-perturbed MAP to get the baseline point estimate.
        HlMrfMapInference.Result mapResult = HlMrfMapInference.solve(program);
        Map<String, Double> mapValues = mapResult.values();

        List<String> targets = program.targetKeys();
        if (targets.isEmpty()) {
            Map<String, AtomMarginal> det = new LinkedHashMap<>();
            for (String key : program.observedKeys()) {
                det.put(key, AtomMarginal.deterministic(key, program.value(key)));
            }
            return new Result(Collections.unmodifiableMap(det), mapValues, 0);
        }

        // 2. Running accumulators: sum and sum-of-squares of per-sample truth values.
        Map<String, double[]> sums = new LinkedHashMap<>();  // [sum, sumSq]
        for (String t : targets) sums.put(t, new double[2]);

        int kept = 0;
        // Retain the per-sample target assignments (after burn-in) so PSL information-gain / BALD
        // can read the joint sample distribution rather than just per-atom mean/variance.
        List<Map<String, Double>> rawSamples = new ArrayList<>();

        for (int s = 0; s < numSamples; s++) {
            // 3. Build a perturbed program: add Gumbel noise to each target atom's prior.
            PslProgram perturbed = buildPerturbedProgram(program, targets, temperature, rng);

            // 4. Solve MAP on the perturbed program.
            HlMrfMapInference.Result perturbedResult = HlMrfMapInference.solve(perturbed);
            Map<String, Double> sample = perturbedResult.values();

            // 5. Accumulate statistics (skip burn-in samples).
            if (s >= burnIn) {
                Map<String, Double> record = new LinkedHashMap<>();
                for (String t : targets) {
                    double v = sample.getOrDefault(t, 0.0);
                    double[] acc = sums.get(t);
                    acc[0] += v;
                    acc[1] += v * v;
                    record.put(t, v);
                }
                rawSamples.add(record);
                kept++;
            }
        }

        // 6. Compute per-atom mean and variance.
        Map<String, AtomMarginal> marginals = new LinkedHashMap<>();
        for (String t : targets) {
            double[] acc = sums.get(t);
            double mean = acc[0] / kept;
            double variance = Math.max(0.0, acc[1] / kept - mean * mean);
            marginals.put(t, new AtomMarginal(t, mean, variance, kept));
        }

        // Also include observed atoms as deterministic marginals.
        for (String key : program.observedKeys()) {
            marginals.put(key, AtomMarginal.deterministic(key, program.value(key)));
        }

        return new Result(Collections.unmodifiableMap(marginals), mapValues, kept,
                Collections.unmodifiableList(rawSamples));
    }

    // ─── Internal helpers ────────────────────────────────────────────────────────

    /**
     * Build a copy of {@code program} with Gumbel-perturbation priors added for each
     * target atom. The perturbation is implemented as a soft "prior" ground rule that
     * biases the target's optimum by a small random amount.
     *
     * <p>Concretely, for each target atom {@code t} we add a soft rule
     * {@code -> t  ^2} with a weight sampled from a Gumbel distribution scaled by
     * {@code temperature}. Positive Gumbel noise pushes {@code t} toward 1; negative
     * noise pushes it toward 0 (the squared hinge is asymmetric, so we encode the sign
     * via separate observed-atom perturbation priors).</p>
     */
    private static PslProgram buildPerturbedProgram(PslProgram original,
                                                     List<String> targets,
                                                     double temperature,
                                                     Random rng) {
        PslProgram perturbed = new PslProgram();

        // Copy all rules.
        for (PslRule rule : original.rules()) {
            perturbed.addRule(rule);
        }

        // Copy all atoms at their current values.
        for (String key : original.atomKeys()) {
            PslAtom atom = PslAtom.parse(key);
            if (original.isObserved(key)) {
                perturbed.observe(atom, original.value(key));
            } else {
                perturbed.target(atom);
            }
        }

        // Add Gumbel perturbation rules for each target atom.
        for (String key : targets) {
            double gumbel = sampleGumbel(rng) * temperature;
            // To bias toward 1.0 (positive gumbel), add a weak positive prior rule.
            // To bias toward 0.0 (negative gumbel), add a weak negative prior rule (~atom -> ).
            // We achieve this by adding a soft rule with weight = |gumbel| and a negated head
            // depending on the sign.
            double absGumbel = Math.abs(gumbel);
            if (absGumbel < 1e-9) continue;

            List<PslAtom> body = List.of();
            PslAtom targetAtom = PslAtom.parse(key);
            boolean negHead = gumbel < 0.0; // negative gumbel → bias to 0 → penalise truth
            PslAtom headAtom = targetAtom.withNegated(negHead);
            List<PslAtom> head = List.of(headAtom);
            PslRule perturbRule = PslRule.weighted(absGumbel, true, body, head);
            perturbed.addRule(perturbRule);
        }

        return perturbed;
    }

    /**
     * Sample from the standard Gumbel distribution: {@code -log(-log(U))} where U ~ Uniform(0,1).
     */
    private static double sampleGumbel(Random rng) {
        double u = rng.nextDouble();
        if (u < 1e-15) u = 1e-15;
        if (u > 1.0 - 1e-15) u = 1.0 - 1e-15;
        return -Math.log(-Math.log(u));
    }
}
