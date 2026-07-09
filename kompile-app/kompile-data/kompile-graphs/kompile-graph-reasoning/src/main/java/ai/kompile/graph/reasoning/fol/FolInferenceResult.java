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
package ai.kompile.graph.reasoning.fol;

import ai.kompile.graph.reasoning.psl.HlMrfMapInference;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Result of a {@link FolInferenceService} inference pass over a
 * {@link ai.kompile.graph.reasoning.model.ReasoningGraph}.
 *
 * <p>Contains the inferred soft-truth likelihoods for every entity in the graph
 * (the MAP optimum of the HL-MRF energy defined by the weighted {@link FolRuleSet}),
 * together with diagnostic metadata from the underlying PSL solver.</p>
 *
 * <ul>
 *   <li>{@link #entityLikelihoods()} — the main output: entity id → soft truth in [0,1].</li>
 *   <li>{@link #pslResult()} — access to the raw solver result for deep diagnostics.</li>
 *   <li>{@link #ruleSetName()}, {@link #graphEntityCount()}, {@link #graphRelationCount()} — provenance.</li>
 * </ul>
 */
public final class FolInferenceResult {

    private final Map<String, Double> entityLikelihoods;
    private final HlMrfMapInference.Result pslResult;
    private final String ruleSetName;
    private final int graphEntityCount;
    private final int graphRelationCount;
    private final Instant computedAt;
    private final long computationTimeMs;
    private final boolean groundingTruncated;
    private final int pairsConsidered;

    FolInferenceResult(Map<String, Double> entityLikelihoods,
                       HlMrfMapInference.Result pslResult,
                       String ruleSetName,
                       int graphEntityCount,
                       int graphRelationCount,
                       long computationTimeMs,
                       boolean groundingTruncated,
                       int pairsConsidered) {
        this.entityLikelihoods = Collections.unmodifiableMap(new LinkedHashMap<>(entityLikelihoods));
        this.pslResult = pslResult;
        this.ruleSetName = ruleSetName;
        this.graphEntityCount = graphEntityCount;
        this.graphRelationCount = graphRelationCount;
        this.computedAt = Instant.now();
        this.computationTimeMs = computationTimeMs;
        this.groundingTruncated = groundingTruncated;
        this.pairsConsidered = pairsConsidered;
    }

    /**
     * Inferred soft-truth likelihood for each entity in the graph.
     * Values are in {@code [0, 1]}; higher = more likely to be "active" / relevant
     * given the rule set.
     */
    public Map<String, Double> entityLikelihoods() { return entityLikelihoods; }

    /** Soft truth for a specific entity, defaulting to {@code 0.0} if not in the result. */
    public double likelihoodOf(String entityId) {
        return entityLikelihoods.getOrDefault(entityId, 0.0);
    }

    /** The raw PSL/HL-MRF solver result (for diagnostics and rule-violation analysis). */
    public HlMrfMapInference.Result pslResult() { return pslResult; }

    /** Name of the {@link FolRuleSet} that was applied. */
    public String ruleSetName() { return ruleSetName; }

    /** Number of entities in the graph at inference time. */
    public int graphEntityCount() { return graphEntityCount; }

    /** Number of relations in the graph at inference time. */
    public int graphRelationCount() { return graphRelationCount; }

    /** When this result was computed. */
    public Instant computedAt() { return computedAt; }

    /** Wall-clock milliseconds elapsed during inference. */
    public long computationTimeMs() { return computationTimeMs; }

    /** Whether the PSL solver converged before the iteration cap. */
    public boolean converged() { return pslResult != null && pslResult.converged(); }

    /**
     * Whether the entity-pair grounding was truncated by the {@code maxPairsPerRule} cap.
     *
     * <p>When {@code true}, at least one FOL rule was grounded against fewer entity pairs than
     * exist in the graph.  The inference result is still valid but may miss groundings for
     * entity pairs beyond the cap.  See {@link #pairsConsidered()} for the actual count.</p>
     *
     * @return {@code true} if grounding was cut short by the pair cap
     */
    public boolean groundingTruncated() { return groundingTruncated; }

    /**
     * Total number of entity pairs that were considered for grounding across all rules in this
     * inference pass.  When {@link #groundingTruncated()} is {@code true} this equals the cap
     * that was applied; otherwise it equals the full {@code entityCount²} (or the number of
     * typed-scoped pairs when a rule's {@link FolRule#entityTypeScope()} is set).
     *
     * @return total pairs grounded
     */
    public int pairsConsidered() { return pairsConsidered; }

    @Override
    public String toString() {
        return "FolInferenceResult{ruleSet=" + ruleSetName
                + ", entities=" + graphEntityCount
                + ", converged=" + converged()
                + ", truncated=" + groundingTruncated
                + ", pairs=" + pairsConsidered
                + ", ms=" + computationTimeMs + "}";
    }
}
