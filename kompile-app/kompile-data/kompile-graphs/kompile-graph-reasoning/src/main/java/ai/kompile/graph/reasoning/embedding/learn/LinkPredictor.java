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
package ai.kompile.graph.reasoning.embedding.learn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Link prediction over a trained RotatE model: given a head entity and relation type (or tail
 * entity and relation type), score every candidate entity as the missing tail (or head) using
 * the RotatE distance function and return the {@code topK} most plausible completions.
 *
 * <h3>RotatE scoring recap</h3>
 * <p>The RotatE distance {@code d(h, r, t)} is the L2 norm of the difference between the
 * rotated head and the tail in complex space — <b>lower distance = more plausible</b>.
 * The {@link ScoredPrediction#distance()} values returned by this class follow the same
 * convention: the list is sorted <em>ascending</em> by distance so that index 0 is the
 * most-plausible prediction.</p>
 *
 * <h3>Usage</h3>
 * <pre>
 *   TrainedRotatE model = new RotatELearner(cfg).train(graph, cfg);
 *   LinkPredictor lp = new LinkPredictor(model);
 *   List&lt;ScoredPrediction&gt; tails = lp.predictTails("Alice", "KNOWS", 5);
 * </pre>
 *
 * @see RotatELearner
 * @see RotatELearner.TrainedRotatE
 */
public final class LinkPredictor {

    private final RotatELearner.TrainedRotatE model;

    /**
     * Construct a predictor backed by the given trained RotatE model.
     *
     * @param model a fully-trained {@link RotatELearner.TrainedRotatE}; must not be null
     */
    public LinkPredictor(RotatELearner.TrainedRotatE model) {
        if (model == null) {
            throw new IllegalArgumentException("model must not be null");
        }
        this.model = model;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Score every known entity as the tail of the triple {@code (headId, relationType, ?)}.
     *
     * <p>Returns the {@code topK} entities with the lowest RotatE distance (most plausible),
     * sorted ascending by distance. If {@code topK} is less than 1 or greater than the total
     * number of entities all entities are returned.</p>
     *
     * @param headId       the head entity id; must be known to the model
     * @param relationType the relation type string; must be known to the model
     * @param topK         maximum number of results to return (≤ 0 means "all")
     * @return predictions sorted ascending by {@link ScoredPrediction#distance()} (lower = more plausible)
     * @throws IllegalArgumentException if {@code headId} or {@code relationType} is unknown
     */
    public List<ScoredPrediction> predictTails(String headId, String relationType, int topK) {
        validateEntity(headId);
        validateRelation(relationType);

        List<ScoredPrediction> results = new ArrayList<>(model.numEntities());
        for (String candidateId : model.entityIds()) {
            double dist = model.score(headId, relationType, candidateId);
            if (Double.isNaN(dist)) continue;
            results.add(new ScoredPrediction(candidateId, dist));
        }
        return selectTopK(results, topK);
    }

    /**
     * Score every known entity as the head of the triple {@code (?, relationType, tailId)}.
     *
     * <p>Symmetric to {@link #predictTails}: each candidate entity is scored as the head, and
     * the {@code topK} with the lowest distance are returned, sorted ascending.</p>
     *
     * @param relationType the relation type string; must be known to the model
     * @param tailId       the tail entity id; must be known to the model
     * @param topK         maximum number of results to return (≤ 0 means "all")
     * @return predictions sorted ascending by {@link ScoredPrediction#distance()} (lower = more plausible)
     * @throws IllegalArgumentException if {@code relationType} or {@code tailId} is unknown
     */
    public List<ScoredPrediction> predictHeads(String relationType, String tailId, int topK) {
        validateRelation(relationType);
        validateEntity(tailId);

        List<ScoredPrediction> results = new ArrayList<>(model.numEntities());
        for (String candidateId : model.entityIds()) {
            double dist = model.score(candidateId, relationType, tailId);
            if (Double.isNaN(dist)) continue;
            results.add(new ScoredPrediction(candidateId, dist));
        }
        return selectTopK(results, topK);
    }

    /**
     * Passthrough: return the RotatE distance for a fully-specified triple.
     *
     * <p>Lower distance = more plausible. Returns {@link Double#NaN} if any id is unknown.</p>
     *
     * @param headId       head entity id
     * @param relationType relation type string
     * @param tailId       tail entity id
     * @return non-negative RotatE distance, or {@link Double#NaN} if any id is not in the model
     */
    public double scoreTriple(String headId, String relationType, String tailId) {
        return model.score(headId, relationType, tailId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Result type
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * A candidate entity paired with its RotatE distance score.
     *
     * <p>Lists of {@code ScoredPrediction} returned by this class are sorted
     * <em>ascending</em> by {@link #distance()} — index 0 is the most-plausible prediction.
     * Lower distance means more plausible under RotatE semantics (the model minimises
     * {@code d(h ∘ r, t)} for true triples during training).</p>
     *
     * @param entityId the candidate entity id
     * @param distance non-negative RotatE L2 distance; lower = more plausible
     */
    public record ScoredPrediction(String entityId, double distance) {
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static List<ScoredPrediction> selectTopK(List<ScoredPrediction> all, int topK) {
        all.sort(Comparator.comparingDouble(ScoredPrediction::distance));
        if (topK > 0 && all.size() > topK) {
            return new ArrayList<>(all.subList(0, topK));
        }
        return all;
    }

    private void validateEntity(String entityId) {
        if (!model.entityIds().contains(entityId)) {
            throw new IllegalArgumentException(
                    "Unknown entity id '" + entityId + "'. Known entities: " + model.entityIds());
        }
    }

    private void validateRelation(String relationType) {
        if (!model.relTypes().contains(relationType)) {
            throw new IllegalArgumentException(
                    "Unknown relation type '" + relationType + "'. Known types: " + model.relTypes());
        }
    }
}
