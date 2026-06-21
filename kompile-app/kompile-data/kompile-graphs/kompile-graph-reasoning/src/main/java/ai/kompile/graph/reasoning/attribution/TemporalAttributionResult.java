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

import ai.kompile.graph.reasoning.domain.AttributionChain;
import ai.kompile.graph.reasoning.domain.AttributionQuery;
import ai.kompile.graph.reasoning.domain.AttributionResult;
import ai.kompile.graph.reasoning.domain.CounterfactualResult;
import ai.kompile.graph.reasoning.model.TemporalInterval;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An {@link AttributionResult} enriched with temporal-attribution metadata:
 *
 * <ul>
 *   <li>{@link #getQueryInterval()} — the effective temporal window that was applied to scope
 *       the graph before traversal (derived from query's {@code temporalStart/End} or
 *       {@code attributionWindow}).</li>
 *   <li>{@link #getDecayConfig()} — the {@link TemporalDecayConfig} that was used to discount
 *       hop strengths during traversal.</li>
 *   <li>{@link #getTemporalInfluenceScores()} — like {@code influenceScores} in the base class
 *       but populated after decay has been applied, so they reflect the time-discounted
 *       contribution of each cause node.</li>
 *   <li>{@link #getPrunedHopCount()} — how many hops were discarded by the precedence filter
 *       (cause timestamp was NOT before effect timestamp).</li>
 *   <li>{@link #getInconsistentChainCount()} — how many chains were demoted or dropped because
 *       {@link AttributionChain#isTemporallyConsistent()} returned {@code false}.</li>
 * </ul>
 *
 * <p>Infra-free: no Spring, JPA, or Jackson-databind dependencies. Use the nested
 * {@link Builder} to construct instances.</p>
 */
public final class TemporalAttributionResult extends AttributionResult {

    /**
     * The effective temporal window applied to scope the graph before traversal.
     * {@code null} when no temporal bounding was requested or applicable.
     */
    private final TemporalInterval queryInterval;

    /**
     * The decay configuration that was applied during traversal.
     */
    private final TemporalDecayConfig decayConfig;

    /**
     * Influence scores after temporal decay has been applied.
     * Keys are node IDs; values are the decay-weighted influence (0..1).
     */
    private final Map<String, Double> temporalInfluenceScores;

    /**
     * Number of candidate causal hops that were pruned because the cause did NOT temporally
     * precede the effect (precedence constraint, spec §5a).
     */
    private final int prunedHopCount;

    /**
     * Number of assembled chains that were demoted to
     * {@link ai.kompile.graph.reasoning.domain.AttributionConfidence#INSUFFICIENT} or dropped
     * because {@link AttributionChain#isTemporallyConsistent()} returned {@code false}.
     */
    private final int inconsistentChainCount;

    // ─── Constructor (package-private — use Builder) ──────────────────────────────

    private TemporalAttributionResult(
            AttributionQuery query,
            String targetNodeId,
            String targetTitle,
            List<AttributionChain> chains,
            String synthesizedExplanation,
            Map<String, Double> influenceScores,
            List<CounterfactualResult> counterfactuals,
            List<String> deadEnds,
            Instant computedAt,
            long computationTimeMs,
            int nodesVisited,
            int edgesExamined,
            boolean llmUsed,
            TemporalInterval queryInterval,
            TemporalDecayConfig decayConfig,
            Map<String, Double> temporalInfluenceScores,
            int prunedHopCount,
            int inconsistentChainCount) {
        // Populate the base class fields via setters
        setQuery(query);
        setTargetNodeId(targetNodeId);
        setTargetTitle(targetTitle);
        setChains(chains != null ? chains : new ArrayList<>());
        setSynthesizedExplanation(synthesizedExplanation);
        setInfluenceScores(influenceScores != null ? influenceScores : Map.of());
        setCounterfactuals(counterfactuals != null ? counterfactuals : new ArrayList<>());
        setDeadEnds(deadEnds != null ? deadEnds : new ArrayList<>());
        setComputedAt(computedAt);
        setComputationTimeMs(computationTimeMs);
        setNodesVisited(nodesVisited);
        setEdgesExamined(edgesExamined);
        setLlmUsed(llmUsed);

        this.queryInterval            = queryInterval;
        this.decayConfig              = decayConfig != null ? decayConfig : TemporalDecayConfig.none();
        this.temporalInfluenceScores  = temporalInfluenceScores != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(temporalInfluenceScores))
                : Map.of();
        this.prunedHopCount           = prunedHopCount;
        this.inconsistentChainCount   = inconsistentChainCount;
    }

    // ─── Temporal-specific accessors ──────────────────────────────────────────────

    /**
     * The effective temporal window that scoped the graph before traversal,
     * or {@code null} when no window was applied.
     *
     * @return the query interval, or {@code null}
     */
    public TemporalInterval getQueryInterval() {
        return queryInterval;
    }

    /**
     * The decay configuration used during traversal.
     * Never {@code null} (at minimum {@link TemporalDecayConfig#none()}).
     *
     * @return the decay config
     */
    public TemporalDecayConfig getDecayConfig() {
        return decayConfig;
    }

    /**
     * Influence scores after temporal decay: the same nodes as the base
     * {@link #getInfluenceScores()} but with each score multiplied by the decay weight for
     * that node's age relative to the target.
     *
     * @return an unmodifiable map of node-id → decay-weighted influence score
     */
    public Map<String, Double> getTemporalInfluenceScores() {
        return temporalInfluenceScores;
    }

    /**
     * Number of candidate hops pruned by the precedence constraint (§5a): the cause's
     * timestamp was not strictly before the effect's timestamp.
     *
     * @return the pruned hop count (≥ 0)
     */
    public int getPrunedHopCount() {
        return prunedHopCount;
    }

    /**
     * Number of chains dropped or demoted because at least one hop violated temporal ordering
     * ({@link AttributionChain#isTemporallyConsistent()} returned {@code false}).
     *
     * @return the inconsistent-chain count (≥ 0)
     */
    public int getInconsistentChainCount() {
        return inconsistentChainCount;
    }

    // ─── Builder ──────────────────────────────────────────────────────────────────

    /**
     * Fluent builder for {@link TemporalAttributionResult}.
     */
    public static final class Builder {

        private AttributionQuery query;
        private String targetNodeId;
        private String targetTitle;
        private List<AttributionChain> chains = new ArrayList<>();
        private String synthesizedExplanation;
        private Map<String, Double> influenceScores = new LinkedHashMap<>();
        private List<CounterfactualResult> counterfactuals = new ArrayList<>();
        private List<String> deadEnds = new ArrayList<>();
        private Instant computedAt;
        private long computationTimeMs;
        private int nodesVisited;
        private int edgesExamined;
        private boolean llmUsed;
        // Temporal-specific
        private TemporalInterval queryInterval;
        private TemporalDecayConfig decayConfig;
        private Map<String, Double> temporalInfluenceScores = new LinkedHashMap<>();
        private int prunedHopCount;
        private int inconsistentChainCount;

        public Builder query(AttributionQuery query)               { this.query = query; return this; }
        public Builder targetNodeId(String targetNodeId)           { this.targetNodeId = targetNodeId; return this; }
        public Builder targetTitle(String targetTitle)             { this.targetTitle = targetTitle; return this; }
        public Builder chains(List<AttributionChain> chains)       { this.chains = chains; return this; }
        public Builder addChain(AttributionChain chain)            { this.chains.add(chain); return this; }
        public Builder synthesizedExplanation(String explanation)  { this.synthesizedExplanation = explanation; return this; }
        public Builder influenceScores(Map<String, Double> scores) { this.influenceScores = scores; return this; }
        public Builder counterfactuals(List<CounterfactualResult> cf) { this.counterfactuals = cf; return this; }
        public Builder deadEnds(List<String> deadEnds)             { this.deadEnds = deadEnds; return this; }
        public Builder computedAt(Instant computedAt)              { this.computedAt = computedAt; return this; }
        public Builder computationTimeMs(long ms)                  { this.computationTimeMs = ms; return this; }
        public Builder nodesVisited(int nodesVisited)              { this.nodesVisited = nodesVisited; return this; }
        public Builder edgesExamined(int edgesExamined)            { this.edgesExamined = edgesExamined; return this; }
        public Builder llmUsed(boolean llmUsed)                    { this.llmUsed = llmUsed; return this; }

        // Temporal-specific
        public Builder queryInterval(TemporalInterval queryInterval) {
            this.queryInterval = queryInterval; return this;
        }
        public Builder decayConfig(TemporalDecayConfig decayConfig) {
            this.decayConfig = decayConfig; return this;
        }
        public Builder temporalInfluenceScores(Map<String, Double> scores) {
            this.temporalInfluenceScores = scores; return this;
        }
        public Builder temporalInfluenceScore(String nodeId, double score) {
            this.temporalInfluenceScores.put(nodeId, score); return this;
        }
        public Builder prunedHopCount(int prunedHopCount) {
            this.prunedHopCount = prunedHopCount; return this;
        }
        public Builder inconsistentChainCount(int inconsistentChainCount) {
            this.inconsistentChainCount = inconsistentChainCount; return this;
        }

        /** Construct and return the {@link TemporalAttributionResult}. */
        public TemporalAttributionResult build() {
            return new TemporalAttributionResult(
                    query, targetNodeId, targetTitle, chains, synthesizedExplanation,
                    influenceScores, counterfactuals, deadEnds,
                    computedAt != null ? computedAt : Instant.now(),
                    computationTimeMs, nodesVisited, edgesExamined, llmUsed,
                    queryInterval, decayConfig, temporalInfluenceScores,
                    prunedHopCount, inconsistentChainCount);
        }
    }
}
