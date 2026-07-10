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

import ai.kompile.graph.reasoning.embedding.learn.LinkPredictor;
import ai.kompile.graph.reasoning.embedding.learn.RotatELearner;

import java.util.Objects;
import java.util.Set;

/**
 * {@link KgeTripleScorer} backed by a trained {@link LinkPredictor} (RotatE model).
 *
 * <h3>Distance → plausibility calibration</h3>
 * <p>RotatE produces a non-negative L2 distance {@code d(h, r, t)} where lower = more
 * plausible.  This implementation calibrates to a {@code [0, 1]} plausibility score
 * via the Platt-style formula:</p>
 * <pre>
 *   score = 1.0 / (1.0 + distance)
 * </pre>
 * <p>This maps {@code distance=0 → score=1.0} (perfect plausibility) and
 * {@code distance→∞ → score→0.0} (impossible).  For typical RotatE distances
 * in the range {@code [0, 10]} this places the midpoint at {@code distance≈1}.</p>
 *
 * <p>For a proper probability, pass a fitted {@link KgeCalibration} to the two-arg constructor —
 * {@link #calibrate(double)} then uses the temperature-scaled sigmoid {@code σ((γ−d)/T)} (WP2c) that
 * matches RotatE's training objective. Or extend this class and override {@link #calibrate(double)}
 * for fully custom behavior.</p>
 *
 * <h3>Unknown identifiers</h3>
 * <p>If any of the three identifiers is unknown to the underlying model,
 * {@link LinkPredictor#scoreTriple} returns {@link Double#NaN}.  This class maps
 * {@code NaN} to {@code 0.0} so the PSL / OpinionStore paths get a safe sentinel.</p>
 *
 * @see LinkPredictor
 * @see KgeTripleScorer
 */
public class LinkPredictorKgeScorer implements KgeTripleScorer {

    private final LinkPredictor predictor;
    private final Set<String>   entityIds;
    private final Set<String>   relTypes;
    private final KgeCalibration calibration; // nullable → legacy 1/(1+d)

    /**
     * Construct a scorer backed by the given trained RotatE model.
     *
     * @param model a fully-trained {@link RotatELearner.TrainedRotatE}; must not be null
     */
    public LinkPredictorKgeScorer(RotatELearner.TrainedRotatE model) {
        this(model, null);
    }

    /**
     * Construct a scorer with an explicit {@link KgeCalibration} (WP2c). When {@code calibration} is
     * non-null, {@link #calibrate(double)} uses the temperature-scaled sigmoid {@code σ((γ−d)/T)} —
     * matching RotatE's training objective — instead of the legacy {@code 1/(1+d)} heuristic, so the
     * returned plausibility is a proper probability. Fit one with {@link KgeCalibration#fit}.
     *
     * @param model       a fully-trained {@link RotatELearner.TrainedRotatE}; must not be null
     * @param calibration the distance→probability calibration, or {@code null} for legacy behavior
     */
    public LinkPredictorKgeScorer(RotatELearner.TrainedRotatE model, KgeCalibration calibration) {
        Objects.requireNonNull(model, "model must not be null");
        this.predictor = new LinkPredictor(model);
        this.entityIds  = Set.copyOf(model.entityIds());
        this.relTypes   = Set.copyOf(model.relTypes());
        this.calibration = calibration;
    }

    @Override
    public double scoreTriple(String headId, String relationType, String tailId) {
        if (!knows(headId, relationType, tailId)) {
            return 0.0;
        }
        double distance = predictor.scoreTriple(headId, relationType, tailId);
        if (Double.isNaN(distance)) {
            return 0.0;
        }
        return calibrate(distance);
    }

    @Override
    public boolean knows(String headId, String relationType, String tailId) {
        return entityIds.contains(headId)
                && relTypes.contains(relationType)
                && entityIds.contains(tailId);
    }

    /**
     * Calibrate a raw RotatE distance to a {@code [0, 1]} plausibility score.
     *
     * <p>Default: {@code 1 / (1 + distance)}.  Override to supply domain-specific
     * calibration (e.g. a temperature-scaled sigmoid or min-max normalization).</p>
     *
     * @param distance non-negative RotatE L2 distance
     * @return plausibility in {@code [0, 1]}
     */
    protected double calibrate(double distance) {
        return calibration != null ? calibration.calibrate(distance) : 1.0 / (1.0 + distance);
    }
}
