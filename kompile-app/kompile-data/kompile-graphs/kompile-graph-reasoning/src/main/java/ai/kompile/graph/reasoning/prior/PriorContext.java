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
package ai.kompile.graph.reasoning.prior;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable context bag passed to {@link PriorProvider} for a single prior-resolution request.
 *
 * <p>Carry only what is cheaply available at the call site — all fields are optional.
 * Build via {@link #builder()} or use {@link #EMPTY} when no context is at hand.</p>
 *
 * <p>Infra-free: no Spring, no JPA, no Jackson-databind.</p>
 */
public final class PriorContext {

    /**
     * Default temporal decay rate γ used when the context carries no explicit override.
     * Derived from a 1-hour half-life: γ = ln(2) / 3600 ≈ 1.925 × 10⁻⁴ s⁻¹.
     */
    public static final double DEFAULT_GAMMA = Math.log(2.0) / 3600.0;

    /** Entity/edge type label (e.g. {@code "PERSON"}, {@code "CAUSE"}).  Used for type-frequency tier. */
    private final String entityType;
    /** Dense entity embedding in {@code double[]}, or {@code null} when unavailable. */
    private final double[] embedding;
    /** When the event/entity occurred, or {@code null}. */
    private final Instant occurredAt;
    /** When the fact was last verified/observed, or {@code null}. */
    private final Instant lastVerifiedAt;
    /**
     * Temporal decay rate γ (s⁻¹) for the prior formula {@code P = 0.5 + 0.5·exp(−γ·Δt)}.
     * Defaults to {@link #DEFAULT_GAMMA}.
     */
    private final double gamma;
    /**
     * Observed type-frequency counts keyed by entity type string.
     * Enables the EmpiricalPriorBlend tier (d) when non-null and non-empty.
     */
    private final Map<String, Long> typeFrequencies;
    /**
     * WP19 — the entity's PageRank percentile in {@code [0,1]} (fraction of nodes it outranks), or
     * {@code null} when no topology stats have been computed for this epoch. Feeds the topology-prior
     * tier; a hub (high percentile) is a stronger a-priori bet than a leaf. Percentile (rank-normalised)
     * not raw PageRank so the prior is scale-free across graphs of different size/density.
     */
    private final Double pageRankPercentile;

    private PriorContext(Builder b) {
        this.entityType         = b.entityType;
        this.embedding          = b.embedding;
        this.occurredAt         = b.occurredAt;
        this.lastVerifiedAt     = b.lastVerifiedAt;
        this.gamma              = b.gamma;
        this.typeFrequencies    = b.typeFrequencies;
        this.pageRankPercentile = b.pageRankPercentile;
    }

    /** Entity type label, or {@code null}. */
    public String entityType()                  { return entityType; }
    /** Dense embedding vector, or {@code null} when unavailable. */
    public double[] embedding()                 { return embedding; }
    /** Occurrence timestamp, or {@code null}. */
    public Instant occurredAt()                 { return occurredAt; }
    /** Last-verified timestamp, or {@code null}. */
    public Instant lastVerifiedAt()             { return lastVerifiedAt; }
    /** Temporal decay rate γ (s⁻¹). Always positive. */
    public double gamma()                       { return gamma; }
    /** Type-frequency map for empirical-prior tier, or {@code null}. */
    public Map<String, Long> typeFrequencies()  { return typeFrequencies; }
    /** PageRank percentile in {@code [0,1]} for the WP19 topology tier, or {@code null} when absent. */
    public Double pageRankPercentile()          { return pageRankPercentile; }

    /** Start a fluent builder. */
    public static Builder builder() { return new Builder(); }

    /** Pre-built empty context: all fields null / default gamma. */
    public static final PriorContext EMPTY = builder().build();

    // ─── Builder ─────────────────────────────────────────────────────────────

    public static final class Builder {
        private String entityType;
        private double[] embedding;
        private Instant occurredAt;
        private Instant lastVerifiedAt;
        private double gamma = DEFAULT_GAMMA;
        private Map<String, Long> typeFrequencies;
        private Double pageRankPercentile;

        private Builder() {}

        public Builder entityType(String t)                 { this.entityType = t;         return this; }
        public Builder embedding(double[] e)                { this.embedding = e;          return this; }
        public Builder occurredAt(Instant i)                { this.occurredAt = i;         return this; }
        public Builder lastVerifiedAt(Instant i)            { this.lastVerifiedAt = i;     return this; }
        public Builder gamma(double g)                      { this.gamma = g;              return this; }
        public Builder typeFrequencies(Map<String, Long> m) { this.typeFrequencies = m;    return this; }
        public Builder pageRankPercentile(Double p)         { this.pageRankPercentile = p; return this; }

        public PriorContext build() { return new PriorContext(this); }
    }
}
