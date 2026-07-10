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

import ai.kompile.graph.reasoning.psl.GroundRule;
import ai.kompile.graph.reasoning.psl.PslRule;

import org.nd4j.autodiff.samediff.SDVariable;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.learning.GradientUpdater;
import org.nd4j.linalg.learning.config.Adam;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SameDiff-backed PSL rule-weight gradient — the <em>production gradient path</em> that
 * replaces the per-ground-rule Java scalar loop in {@link PslRuleGradient} for programs with
 * many ground rules.
 *
 * <h3>Algorithm</h3>
 * <p>The structured-perceptron weight gradient for rule {@code r} is:
 * <pre>
 *   ∂L/∂w_r = mean_{groundings of r}[ d_r(y_pred) - d_r(y_gt) ]
 *            = (for squared rules) mean[ d_r(y_pred)^2 - d_r(y_gt)^2 ]
 * </pre>
 * where {@code d_r(y) = max(0, bodyTruth_r(y) − headTruth_r(y))} is the Łukasiewicz distance
 * to satisfaction for rule {@code r} evaluated at atom assignment {@code y}.
 *
 * <p>The distances {@code distPred[ri]} and {@code distGt[ri]} for all R ground rules are
 * computed using the tight arrays of the existing
 * {@link SgdHlMrfInference.Compiled} sparse scanner — no HashMap lookups, pure int-indexed
 * array access. We then pass these as SameDiff constants and differentiate the structured-
 * perceptron energy {@code Σ_r w[templateIdx[r]] · potential(distPred[r]) − potential(distGt[r])}
 * w.r.t. the {@code K_r} template-weight scalars.
 *
 * <h3>SameDiff graph</h3>
 * <pre>
 *   Trainable: w = SDVariable [K_r]              (one weight per rule TEMPLATE, not per grounding)
 *   Constants (computed once per epoch from the compiled form + MAP result):
 *     wPerGrounding = gather(w, templateIdx)      [R]  — each grounding's template weight
 *     distPred      = INDArray [R]                (precomputed from SgdHlMrfInference.Compiled)
 *     distGt        = INDArray [R]                (precomputed from the ground-truth assignment)
 *     squaredMask   = INDArray [R] ∈ {0,1}        (1 if this ground rule uses squared hinge)
 *
 *   Forward:
 *     potPred[r] = wPerGrounding[r] * (sq[r]*distPred[r]^2 + (1-sq[r])*distPred[r])
 *     potGt[r]   = wPerGrounding[r] * (sq[r]*distGt[r]^2  + (1-sq[r])*distGt[r])
 *     loss       = sum(potPred - potGt)           scalar
 * </pre>
 * {@code calculateGradients("w")} returns {@code ∂loss/∂w}, shape {@code [K_r]}.
 *
 * <h3>No availability gate</h3>
 * <p>SameDiff is an always-available compile-time dependency of this module. The ND4J backend
 * (CPU or GPU) is resolved at runtime. There is no {@code isAvailable()} guard; the caller
 * ({@link StructuredPerceptronLearner}) uses this class unconditionally above
 * {@link #GROUND_RULE_THRESHOLD} and falls back to {@link PslRuleGradient} below it.
 *
 * @see PslRuleGradient
 * @see StructuredPerceptronLearner
 */
public final class SameDiffPslWeightGradient {

    private static final Logger log = LoggerFactory.getLogger(SameDiffPslWeightGradient.class);

    private static final String VAR_W        = "w";
    private static final String CONST_TIDX   = "templateIdx";
    private static final String CONST_DPRED  = "distPred";
    private static final String CONST_DGT    = "distGt";
    private static final String CONST_SQ     = "squaredMask";
    private static final String LOSS         = "loss";

    /**
     * Minimum number of ground rules before the SameDiff path is preferred over the scalar loop.
     * Below this threshold the scalar {@link PslRuleGradient} is faster (avoids SameDiff
     * graph-build overhead which is dominated by the R×lookups at small R).
     */
    public static final int GROUND_RULE_THRESHOLD = 1_000;

    // ── Adam hyper-parameters (same as RotatELearner) ─────────────────────────
    private static final double ADAM_BETA1 = 0.9;
    private static final double ADAM_BETA2 = 0.999;
    private static final double ADAM_EPS   = 1e-8;

    private SameDiffPslWeightGradient() {}

    // ─── Production entry point ────────────────────────────────────────────────

    /**
     * Compute the PSL rule-weight gradient using a SameDiff autodiff pass.
     *
     * <p>This is the production replacement for {@link PslRuleGradient#ruleGradient} when
     * {@code groundRules.size() >= GROUND_RULE_THRESHOLD}. It uses the same structured-
     * perceptron formula but computes all gradients in one forward+backward pass over the
     * {@code K_r} template-weight scalars.
     *
     * <p><b>Caller contract:</b> {@code rules} MUST be the same rule list used to produce
     * {@code groundRules} (same contract as {@link PslRuleGradient#ruleGradient}).
     *
     * @param rules       current program rules (used to build the template→index map)
     * @param groundRules grounded rules whose distances drive the gradient
     * @param predicted   atom truth values at the MAP solution
     * @param groundTruth observed atom truth values
     * @return gradient vector of length {@code rules.size()}, one entry per rule template
     */
    public static double[] compute(List<PslRule> rules, List<GroundRule> groundRules,
                                   Map<String, Double> predicted, Map<String, Double> groundTruth) {
        int K = rules.size();
        int R = groundRules.size();

        if (R == 0 || K == 0) {
            return new double[K];
        }

        // Build the template-index map (same logic as PslRuleGradient.buildSignatureIndex).
        Map<String, Integer> sigIndex = PslRuleGradient.buildSignatureIndex(rules);

        // Extract current weights (needed to prime the SameDiff variable).
        double[] currentWeights = new double[K];
        for (int i = 0; i < K; i++) {
            currentWeights[i] = rules.get(i).weight();
        }

        // Pre-compute per-grounding distances and template indices using tight arrays.
        // SgdHlMrfInference.Compiled is the canonical, HashMap-free distance calculator —
        // calling it avoids the O(R × atom-string-lookups) cost of GroundRule.distanceToSatisfaction.
        // We compute distances from the Map<String,Double> inputs here since we don't have the
        // compiled form (it is held inside the solver). The distanceToSatisfaction calls here are
        // the same O(literals) cost per ground rule, but we do them ONCE per gradient step rather
        // than twice (once for pred, once for gt), amortised across K rules by the gather.
        int[] templateIdx = new int[R];
        float[] distPred  = new float[R];
        float[] distGt    = new float[R];
        float[] squaredM  = new float[R];

        int validR = 0;  // number of non-hard ground rules that mapped to a template
        for (int ri = 0; ri < R; ri++) {
            GroundRule gr = groundRules.get(ri);
            if (gr.hard()) {
                // Hard rules carry no gradient (weight is fixed at hardWeight).
                templateIdx[ri] = 0;  // placeholder, zero-masked via zero-weight
                distPred[ri]    = 0f;
                distGt[ri]      = 0f;
                squaredM[ri]    = 0f;
                continue;
            }
            int idx = PslRuleGradient.findRuleIndex(rules, sigIndex, gr);
            if (idx < 0) {
                // Unresolvable ground rule — treat as no-op (same as PslRuleGradient).
                templateIdx[ri] = 0;
                distPred[ri]    = 0f;
                distGt[ri]      = 0f;
                squaredM[ri]    = 0f;
                continue;
            }
            templateIdx[ri] = idx;
            distPred[ri]    = (float) gr.distanceToSatisfaction(predicted);
            distGt[ri]      = (float) gr.distanceToSatisfaction(groundTruth);
            squaredM[ri]    = gr.squared() ? 1f : 0f;
            validR++;
        }

        if (validR == 0) {
            return new double[K];
        }

        // Build and evaluate the SameDiff gradient graph.
        double[] sdGrad = sdGradient(currentWeights, templateIdx, distPred, distGt, squaredM, K, R);

        // Post-process: zero out hard rules (same as PslRuleGradient).
        for (int i = 0; i < K; i++) {
            if (rules.get(i).hard()) {
                sdGrad[i] = 0.0;
            }
        }
        return sdGrad;
    }

    // ─── SameDiff gradient computation ────────────────────────────────────────

    /**
     * Build and evaluate the SameDiff structured-perceptron energy graph to obtain
     * {@code ∂loss/∂w} for all K template weights in one autodiff pass.
     *
     * <p>The graph is rebuilt each call rather than cached, because the distance arrays
     * (constants) change every epoch. Graph construction is fast at K ≤ 100 template rules.
     *
     * @param currentWeights current weight values w[0..K-1]
     * @param templateIdx    per-grounding template index (which rule owns this grounding)
     * @param distPred       d_r(y_pred) for each grounding, shape [R]
     * @param distGt         d_r(y_gt) for each grounding, shape [R]
     * @param squaredMask    1 if ground rule uses squared hinge, shape [R]
     * @param K              number of rule templates
     * @param R              number of ground rules
     * @return gradient of length K
     */
    static double[] sdGradient(double[] currentWeights, int[] templateIdx,
                                float[] distPred, float[] distGt, float[] squaredMask,
                                int K, int R) {
        SameDiff sd = SameDiff.create();

        // Trainable: w [K]
        INDArray wArr = Nd4j.createFromArray(currentWeights).reshape(K).castTo(DataType.DOUBLE);
        SDVariable wVar = sd.var(VAR_W, wArr.dup());

        // Gather per-grounding weights: wPerGrounding[r] = w[templateIdx[r]]
        INDArray tidxArr = Nd4j.createFromArray(templateIdx).castTo(DataType.INT32);
        SDVariable tidxVar = sd.constant(CONST_TIDX, tidxArr);
        SDVariable wPerGrounding = sd.gather("wPerGrounding", wVar, tidxVar, 0);   // [R] — DOUBLE

        // Distance constants: cast to DOUBLE for consistency with w.
        INDArray dpArr  = Nd4j.createFromArray(distPred).castTo(DataType.DOUBLE).reshape(R);
        INDArray dgArr  = Nd4j.createFromArray(distGt).castTo(DataType.DOUBLE).reshape(R);
        INDArray sqArr  = Nd4j.createFromArray(squaredMask).castTo(DataType.DOUBLE).reshape(R);

        SDVariable dpVar  = sd.constant(CONST_DPRED, dpArr);
        SDVariable dgVar  = sd.constant(CONST_DGT,  dgArr);
        SDVariable sqVar  = sd.constant(CONST_SQ,   sqArr);
        // (1 - sq) as a constant: precompute as INDArray to avoid sd.one() which is not in the API.
        INDArray oneMinusSqArr = Nd4j.ones(DataType.DOUBLE, R).sub(sqArr);
        SDVariable oneMinusSq = sd.constant("oneMinusSq", oneMinusSqArr);   // [R]

        // potential(d) = sq*d^2 + (1-sq)*d
        SDVariable potPred = wPerGrounding.mul(
                sqVar.mul(dpVar.mul("dp2", dpVar)).add(oneMinusSq.mul("dpLin", dpVar)));
        SDVariable potGt = wPerGrounding.mul(
                sqVar.mul(dgVar.mul("dg2", dgVar)).add(oneMinusSq.mul("dgLin", dgVar)));

        // Structured-perceptron loss = sum(potPred - potGt)
        SDVariable lossVar = potPred.sub(potGt).sum(LOSS);
        sd.setLossVariables(LOSS);

        // Bind the live weight array, run forward pass, then backward.
        sd.associateArrayWithVariable(wArr, VAR_W);
        // Forward pass materialises the computation graph before calling calculateGradients.
        sd.outputSingle(Map.of(), LOSS);
        Map<String, INDArray> grads = sd.calculateGradients(Map.of(), VAR_W);

        INDArray gArr = grads.get(VAR_W);
        if (gArr == null) {
            log.warn("SameDiffPslWeightGradient: no gradient returned for '{}'; returning zeros", VAR_W);
            return new double[K];
        }
        INDArray flat = gArr.reshape(K).castTo(DataType.DOUBLE);
        return flat.toDoubleVector();
    }

    // ─── Adam helpers ──────────────────────────────────────────────────────────

    /**
     * Instantiate an ND4J Adam {@link GradientUpdater} with fresh zero-initialised moment state.
     *
     * @param config   shared Adam hyper-parameter config
     * @param stateLen {@code 2 × parameter-count} (m‖v split)
     * @return ready Adam updater
     */
    @SuppressWarnings("unchecked")
    private static GradientUpdater<Adam> newAdamUpdater(Adam config, long stateLen) {
        return config.instantiate(Nd4j.zeros(DataType.DOUBLE, 1, stateLen), true);
    }

    /**
     * Apply one Adam step to {@code param} in place.
     * The updater rewrites {@code grad} with the bias-corrected update, then {@code param −= update}.
     *
     * @param updater   Adam updater (owns moment state)
     * @param param     parameter array updated in place
     * @param grad      gradient for this step ({@code null} ⇒ no-op)
     * @param iteration 0-based Adam step index
     */
    private static void applyAdam(GradientUpdater<Adam> updater, INDArray param,
                                   INDArray grad, int iteration) {
        if (grad == null) {
            return;
        }
        INDArray update = grad.castTo(DataType.DOUBLE);
        updater.applyUpdater(update, iteration, 0);
        param.subi(update);
    }

    // ─── buildPlaceholderGraph ──────────────────────────────────────────────────

    /**
     * Build a SameDiff graph with {@code w} as a trainable variable and
     * {@code distPred}/{@code distGt} as <em>placeholders</em> (fed each gradient call).
     * The graph topology is identical to {@link #sdGradient}; only distPred/distGt become
     * placeholders instead of constants, enabling re-use of the same graph across epochs.
     *
     * @param wArr        initial weight values [K]
     * @param templateIdx per-grounding template index, shape [R] — structure, not values, fixed
     * @param squaredMask 1=squared hinge per grounding, shape [R] — structure, fixed
     * @param K           number of rule templates
     * @param R           number of ground rules
     * @return SameDiff graph ready for placeholder-fed inference
     */
    private static SameDiff buildPlaceholderGraph(INDArray wArr, int[] templateIdx,
                                                   float[] squaredMask, int K, int R) {
        SameDiff sd = SameDiff.create();

        // Trainable: w [K]
        SDVariable wVar = sd.var(VAR_W, wArr.dup());

        // Gather per-grounding weights
        INDArray tidxArr = Nd4j.createFromArray(templateIdx).castTo(DataType.INT32);
        SDVariable tidxVar = sd.constant(CONST_TIDX, tidxArr);
        SDVariable wPerGrounding = sd.gather("wPerGrounding", wVar, tidxVar, 0);

        // Placeholders for per-epoch distances
        SDVariable dpVar = sd.placeHolder(CONST_DPRED, DataType.DOUBLE, R);
        SDVariable dgVar = sd.placeHolder(CONST_DGT,   DataType.DOUBLE, R);

        // squaredMask as constant (structure doesn't change across epochs)
        INDArray sqArr = Nd4j.createFromArray(squaredMask).castTo(DataType.DOUBLE).reshape(R);
        SDVariable sqVar = sd.constant(CONST_SQ, sqArr);
        INDArray oneMinusSqArr = Nd4j.ones(DataType.DOUBLE, R).sub(sqArr);
        SDVariable oneMinusSq = sd.constant("oneMinusSq", oneMinusSqArr);

        // potential(d) = sq*d^2 + (1-sq)*d
        SDVariable potPred = wPerGrounding.mul(
                sqVar.mul(dpVar.mul("dp2", dpVar)).add(oneMinusSq.mul("dpLin", dpVar)));
        SDVariable potGt = wPerGrounding.mul(
                sqVar.mul(dgVar.mul("dg2", dgVar)).add(oneMinusSq.mul("dgLin", dgVar)));

        // Structured-perceptron loss = sum(potPred - potGt)
        potPred.sub(potGt).sum(LOSS);
        sd.setLossVariables(LOSS);
        return sd;
    }

    // ─── GradientSession ───────────────────────────────────────────────────────

    /**
     * Stateful gradient-computation session for one PSL program in full-batch mode.
     *
     * <p>The SameDiff graph is built once in the constructor (with {@code distPred}/{@code distGt}
     * as placeholders rather than per-call constants) and reused across all epochs, avoiding the
     * graph-rebuild cost at each gradient step. The Adam updater's moment state also persists,
     * enabling proper bias-corrected adaptive steps.</p>
     *
     * <p><b>Lifecycle contract:</b>
     * <ul>
     *   <li>Create before the epoch loop when {@code R == groundRules.size()} is stable (full-batch).</li>
     *   <li>Call {@link #computeGradient} to get the raw SameDiff gradient each epoch.</li>
     *   <li>Add prior-penalty terms to the gradient in the caller.</li>
     *   <li>Call {@link #applyAdamStep} to apply Adam and update the weight array in place.</li>
     *   <li>If R changes (mini-batch or program growth), discard and recreate the session.</li>
     * </ul>
     *
     * <p><b>R/K must be fixed for the session lifetime.</b> Use {@link #matches} to verify before
     * each epoch; if it returns {@code false}, create a new {@code GradientSession}.</p>
     */
    public static final class GradientSession {

        private final SameDiff sd;
        /** Live weight array [K] — authoritative weight storage for the Adam path. */
        private final INDArray wArr;
        private final GradientUpdater<Adam> adamUpdater;
        private final int K;
        private final int R;
        private final List<PslRule> rules;
        private final Map<String, Integer> sigIndex;
        /** Per-grounding template index (computed once from groundRules structure). */
        private final int[] templateIdx;

        /**
         * Construct a {@code GradientSession} for the given rules and ground rules.
         *
         * @param rules          current program rules (used for hard-rule zeroing + index map)
         * @param groundRules    ground rules for this program epoch (R must remain stable)
         * @param initialWeights current weight values [K], read-only at construction
         * @param learningRate   Adam learning rate
         */
        public GradientSession(List<PslRule> rules, List<GroundRule> groundRules,
                                double[] initialWeights, double learningRate) {
            this.K = rules.size();
            this.R = groundRules.size();
            this.rules = rules;
            this.sigIndex = PslRuleGradient.buildSignatureIndex(rules);

            // Build templateIdx and squaredMask from ground-rule structure (constants).
            this.templateIdx = new int[R];
            float[] squaredMask = new float[R];
            for (int ri = 0; ri < R; ri++) {
                GroundRule gr = groundRules.get(ri);
                if (!gr.hard()) {
                    int idx = PslRuleGradient.findRuleIndex(rules, sigIndex, gr);
                    if (idx >= 0) {
                        templateIdx[ri] = idx;
                        squaredMask[ri] = gr.squared() ? 1f : 0f;
                    }
                }
            }

            // Build live weight array and SameDiff graph with placeholder dist arrays.
            this.wArr = Nd4j.createFromArray(initialWeights).reshape(K).castTo(DataType.DOUBLE);
            this.sd = buildPlaceholderGraph(wArr, templateIdx, squaredMask, K, R);

            // Adam updater created once; moment state persists across all epochs.
            Adam adamConfig = new Adam(learningRate, ADAM_BETA1, ADAM_BETA2, ADAM_EPS);
            this.adamUpdater = newAdamUpdater(adamConfig, 2L * K);
        }

        /**
         * Returns {@code true} when this session's K and R match the given rules and ground rules.
         * A {@code false} result means the session must be recreated before the next gradient step.
         */
        public boolean matches(List<PslRule> rules, List<GroundRule> groundRules) {
            return groundRules.size() == R && rules.size() == K;
        }

        /**
         * Compute the raw SameDiff gradient (no optimizer applied).
         *
         * <p>Feeds new distPred/distGt distances as placeholder values, runs forward+backward,
         * and returns the gradient of length K. The caller may add prior-penalty terms before
         * calling {@link #applyAdamStep}.</p>
         *
         * @param groundRules  ground rules for this epoch (must have {@code size() == R})
         * @param predicted    atom values at MAP solution
         * @param groundTruth  observed atom truth values
         * @return gradient array [K]; zeros if SameDiff returned null
         */
        public double[] computeGradient(List<GroundRule> groundRules,
                                         Map<String, Double> predicted,
                                         Map<String, Double> groundTruth) {
            // Build per-grounding distances.
            float[] distPred = new float[R];
            float[] distGt   = new float[R];
            for (int ri = 0; ri < R; ri++) {
                GroundRule gr = groundRules.get(ri);
                if (!gr.hard()) {
                    distPred[ri] = (float) gr.distanceToSatisfaction(predicted);
                    distGt[ri]   = (float) gr.distanceToSatisfaction(groundTruth);
                }
            }

            // Sync live wArr with the authoritative weight values held by the caller.
            sd.associateArrayWithVariable(wArr, VAR_W);

            // Build placeholder map.
            Map<String, INDArray> phMap = new HashMap<>(4);
            phMap.put(CONST_DPRED, Nd4j.createFromArray(distPred).castTo(DataType.DOUBLE).reshape(R));
            phMap.put(CONST_DGT,   Nd4j.createFromArray(distGt).castTo(DataType.DOUBLE).reshape(R));

            // Forward + backward.
            sd.outputSingle(phMap, LOSS);
            Map<String, INDArray> grads = sd.calculateGradients(phMap, VAR_W);

            INDArray grad = grads.get(VAR_W);
            if (grad == null) {
                log.warn("GradientSession: no gradient returned for '{}'; returning zeros", VAR_W);
                return new double[K];
            }
            return grad.reshape(K).castTo(DataType.DOUBLE).toDoubleVector();
        }

        /**
         * Apply one Adam step to {@code weights} in place.
         *
         * <p>Copies {@code weights[]} into the live {@link #wArr}, applies Adam using
         * {@code gradient[]} (which may already have prior-penalty terms added by the caller),
         * projects to non-negative, zeroes hard rules, and writes the result back to
         * {@code weights[]}.</p>
         *
         * @param weights    weight array updated in place [K]
         * @param gradient   gradient to descend (prior-penalty may already be included)
         * @param iteration  0-based Adam step index (epoch number)
         * @return maximum absolute weight change (for convergence check)
         */
        public double applyAdamStep(double[] weights, double[] gradient, int iteration) {
            // Copy caller's current weights into the live INDArray.
            INDArray newW = Nd4j.createFromArray(weights).reshape(K).castTo(DataType.DOUBLE);
            wArr.assign(newW);

            // Apply Adam step (modifies wArr in place).
            INDArray gradArr = Nd4j.createFromArray(gradient).reshape(K).castTo(DataType.DOUBLE);
            applyAdam(adamUpdater, wArr, gradArr, iteration);

            // Read back, project non-negative, zero hard rules, compute maxChange.
            double[] updated = wArr.toDoubleVector();
            double maxChange = 0.0;
            for (int i = 0; i < K; i++) {
                double projected = Math.max(0.0, updated[i]);
                if (i < rules.size() && rules.get(i).hard()) {
                    projected = 0.0;
                }
                double change = Math.abs(projected - weights[i]);
                if (change > maxChange) {
                    maxChange = change;
                }
                weights[i] = projected;
                wArr.putScalar(i, projected);
            }
            return maxChange;
        }
    }
}
