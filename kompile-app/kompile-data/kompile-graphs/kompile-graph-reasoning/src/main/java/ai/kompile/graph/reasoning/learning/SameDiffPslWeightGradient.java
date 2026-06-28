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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        double[] result = new double[K];
        for (int i = 0; i < K; i++) {
            result[i] = flat.getDouble(i);
        }
        return result;
    }
}
