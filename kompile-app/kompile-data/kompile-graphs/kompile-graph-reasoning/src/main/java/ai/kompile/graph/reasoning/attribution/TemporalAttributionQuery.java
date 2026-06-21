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

import ai.kompile.graph.reasoning.domain.AttributionQuery;
import ai.kompile.graph.reasoning.domain.CausalEdgeType;
import ai.kompile.graph.reasoning.domain.EvidenceType;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * An {@link AttributionQuery} extended with temporal-attribution parameters: a
 * sliding {@link #getAttributionWindow() attribution window} relative to the target
 * event's timestamp, and a {@link #getDecayConfig() decay configuration} that controls
 * how causal-hop strengths are discounted as a function of the hop's age.
 *
 * <p>If only the base {@link #getTemporalStart()}/{@link #getTemporalEnd()} fields are set,
 * {@link TemporalAttributionService} uses those directly. If {@link #attributionWindow} is
 * also set, it takes precedence and {@code temporalStart} is computed at query-execution time
 * as {@code targetTimestamp − attributionWindow}.</p>
 *
 * <p>Infra-free: no Spring, JPA, or Jackson-databind dependencies.</p>
 */
public class TemporalAttributionQuery extends AttributionQuery {

    /**
     * Sliding window relative to the target event's timestamp. When set, the temporal lower
     * bound used by the service is {@code targetTimestamp − attributionWindow}, overriding any
     * static {@link #getTemporalStart()} value. {@code null} means no sliding window is applied.
     */
    private Duration attributionWindow;

    /**
     * Decay configuration for hop-strength discounting. {@code null} is interpreted as
     * {@link TemporalDecayConfig#none()} (no decay applied).
     */
    private TemporalDecayConfig decayConfig;

    // ─── No-arg constructor (required for builder pattern compatibility) ──────────

    public TemporalAttributionQuery() {
        super();
    }

    // ─── Fluent static builder ────────────────────────────────────────────────────

    /**
     * Begin building a {@code TemporalAttributionQuery} for the given target node.
     *
     * @param targetNodeId the node to explain (never {@code null})
     * @return a mutable builder
     */
    public static Builder forTarget(String targetNodeId) {
        return new Builder(targetNodeId);
    }

    // ─── Accessors ────────────────────────────────────────────────────────────────

    /**
     * The sliding attribution window, or {@code null} for no sliding window.
     *
     * @return the sliding window duration, or {@code null}
     */
    public Duration getAttributionWindow() {
        return attributionWindow;
    }

    /**
     * The decay configuration to apply to surviving hops.
     * Returns {@link TemporalDecayConfig#none()} when not set.
     *
     * @return the decay config (never {@code null} after construction via builder)
     */
    public TemporalDecayConfig getDecayConfig() {
        return decayConfig != null ? decayConfig : TemporalDecayConfig.none();
    }

    /** Package-visible setter used by the builder and by {@link TemporalAttributionService}. */
    void setAttributionWindow(Duration attributionWindow) {
        this.attributionWindow = attributionWindow;
    }

    /** Package-visible setter used by the builder. */
    void setDecayConfig(TemporalDecayConfig decayConfig) {
        this.decayConfig = decayConfig;
    }

    // ─── Builder ──────────────────────────────────────────────────────────────────

    /**
     * Fluent builder for {@link TemporalAttributionQuery}.
     */
    public static final class Builder {

        private final TemporalAttributionQuery query;

        private Builder(String targetNodeId) {
            query = new TemporalAttributionQuery();
            query.setTargetNodeId(targetNodeId);
            // sensible defaults inherited from AttributionQuery via setters
            query.setMaxDepth(5);
            query.setMaxChains(5);
            query.setMinConfidence(0.1);
            query.setUseLlm(false);  // temporal service is infra-free; LLM defaults off
            query.setIncludeCounterfactuals(false);
        }

        /** Natural-language question guiding the explanation. */
        public Builder naturalLanguageQuery(String nlq) {
            query.setNaturalLanguageQuery(nlq);
            return this;
        }

        /** Restrict to a specific fact sheet. */
        public Builder factSheetId(Long id) {
            query.setFactSheetId(id);
            return this;
        }

        /** Maximum traversal depth (default: 5). */
        public Builder maxDepth(int maxDepth) {
            query.setMaxDepth(maxDepth);
            return this;
        }

        /** Maximum number of causal chains to return (default: 5). */
        public Builder maxChains(int maxChains) {
            query.setMaxChains(maxChains);
            return this;
        }

        /** Minimum confidence threshold below which chains are discarded (default: 0.1). */
        public Builder minConfidence(double minConfidence) {
            query.setMinConfidence(minConfidence);
            return this;
        }

        /** Static lower temporal bound. Overridden by {@link #attributionWindow} when both are set. */
        public Builder temporalStart(Instant temporalStart) {
            query.setTemporalStart(temporalStart);
            return this;
        }

        /** Static upper temporal bound. */
        public Builder temporalEnd(Instant temporalEnd) {
            query.setTemporalEnd(temporalEnd);
            return this;
        }

        /**
         * Sliding window relative to the target's timestamp. When set, overrides
         * {@link #temporalStart(Instant)} as the lower window bound.
         */
        public Builder attributionWindow(Duration attributionWindow) {
            query.setAttributionWindow(attributionWindow);
            return this;
        }

        /**
         * Decay configuration for hop-strength discounting.
         * Defaults to {@link TemporalDecayConfig#none()} when not set.
         */
        public Builder decayConfig(TemporalDecayConfig decayConfig) {
            query.setDecayConfig(decayConfig);
            return this;
        }

        /** Restrict traversal to specific causal edge types. */
        public Builder allowedCausalTypes(Set<CausalEdgeType> types) {
            query.setAllowedCausalTypes(types);
            return this;
        }

        /** Require specific evidence types on surviving hops. */
        public Builder requiredEvidenceTypes(Set<EvidenceType> types) {
            query.setRequiredEvidenceTypes(types);
            return this;
        }

        /** Build and return the completed query. */
        public TemporalAttributionQuery build() {
            return query;
        }
    }
}
