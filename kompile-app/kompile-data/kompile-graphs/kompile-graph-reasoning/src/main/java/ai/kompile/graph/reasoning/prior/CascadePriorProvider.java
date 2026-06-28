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

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.confidence.OpinionStore;
import ai.kompile.graph.reasoning.embedding.Embeddings;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;

/**
 * Full-cascade {@link PriorProvider} resolving informative priors via the six-tier waterfall:
 *
 * <ol>
 *   <li><b>Hard finding/evidence</b> — {@code 1.0} (TRUE) or {@code 0.0} (FALSE) from the
 *       findings map supplied at construction time.</li>
 *   <li><b>{@link OpinionStore} expectation</b> — when a non-vacuous opinion is held for
 *       the {@code rvKey}.</li>
 *   <li><b>Embedding geometric prior</b> — when the context carries a non-null embedding, the
 *       L2 norm is mapped to a prior via the function {@code norm / (norm + 1.0)}, then wrapped
 *       in {@link Opinion#fromEmbeddingScore(double, double)} with uncertainty floor 0.3.</li>
 *   <li><b>EmpiricalPriorBlend / type-frequency shrinkage</b> — Laplace-smoothed count prior
 *       {@code (typeCount + 1) / (totalCount + 2)} when
 *       {@link PriorContext#typeFrequencies()} is non-null and the entity type is recognised.</li>
 *   <li><b>Temporal decay</b> — {@code P = 0.5 + 0.5·exp(−γ·Δt)} computed from the most
 *       recent reference time ({@link PriorContext#lastVerifiedAt()} preferred over
 *       {@link PriorContext#occurredAt()}).  Fires only when at least one timestamp is present.</li>
 *   <li><b>Uniform 0.5</b> — true last resort (maximum uncertainty).</li>
 * </ol>
 *
 * <p>Infra-free: no Spring annotations, no JPA, no Jackson.</p>
 */
public final class CascadePriorProvider implements PriorProvider {

    /**
     * Embedding uncertainty floor: 30% of the simplex mass is held as uncertainty when
     * the prior is derived from an embedding rather than direct evidence.
     */
    private static final double EMBEDDING_UNCERTAINTY = 0.3;

    /**
     * Hard findings map.  Key = rvKey; value = {@code 1.0} (TRUE) or {@code 0.0} (FALSE).
     * May be {@code null} (no findings tier).
     */
    private final Map<String, Double> findings;

    /**
     * Opinion store for tier (b).  Required; wrap with
     * {@link ai.kompile.graph.reasoning.confidence.InMemoryOpinionStore} if no persistent store is needed.
     */
    private final OpinionStore opinionStore;

    /**
     * Construct with an opinion store and no findings.
     *
     * @param opinionStore the opinion store used for tier (b); never {@code null}
     */
    public CascadePriorProvider(OpinionStore opinionStore) {
        this(opinionStore, null);
    }

    /**
     * Construct with an opinion store and an optional hard-findings map.
     *
     * @param opinionStore the opinion store used for tier (b); never {@code null}
     * @param findings     optional map of grounded variable keys to {@code 1.0} / {@code 0.0}
     *                     hard evidence values (tier a); may be {@code null}
     */
    public CascadePriorProvider(OpinionStore opinionStore, Map<String, Double> findings) {
        this.opinionStore = Objects.requireNonNull(opinionStore, "opinionStore");
        this.findings     = findings;
    }

    // ─── PriorProvider ───────────────────────────────────────────────────────

    @Override
    public double priorFor(String rvKey, PriorContext ctx) {
        Objects.requireNonNull(ctx, "ctx");

        // (a) Hard finding
        if (findings != null) {
            Double found = findings.get(rvKey);
            if (found != null) return found;
        }

        // (b) OpinionStore: non-vacuous opinion
        if (opinionStore.has(rvKey)) {
            Opinion op = opinionStore.get(rvKey);
            if (!op.isVacuous()) {
                return op.expectation();
            }
        }

        // (c) Embedding geometric prior
        double embPrior = embeddingPrior(ctx.embedding());
        if (embPrior >= 0.0) return embPrior;

        // (d) EmpiricalPriorBlend — type-frequency shrinkage
        double typeFreqPrior = typeFrequencyPrior(ctx);
        if (typeFreqPrior >= 0.0) return typeFreqPrior;

        // (e) Temporal decay: P = 0.5 + 0.5 * exp(-gamma * deltaT)
        double temporalPrior = temporalDecayPrior(ctx);
        if (temporalPrior >= 0.0) return temporalPrior;

        // (f) True last resort
        return 0.5;
    }

    @Override
    public double strengthFor(String parentRv, String childRv, PriorContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        String edgeKey = parentRv + "->" + childRv;

        // (a) Hard finding for edge key
        if (findings != null) {
            Double found = findings.get(edgeKey);
            if (found != null) return found;
        }

        // (b) OpinionStore for edge key
        if (opinionStore.has(edgeKey)) {
            Opinion op = opinionStore.get(edgeKey);
            if (!op.isVacuous()) {
                return op.expectation();
            }
        }

        // (c) Embedding geometric prior from parent context embedding
        double embPrior = embeddingPrior(ctx.embedding());
        if (embPrior >= 0.0) return embPrior;

        // (d) Type-frequency shrinkage
        double typeFreqPrior = typeFrequencyPrior(ctx);
        if (typeFreqPrior >= 0.0) return typeFreqPrior;

        // (e) Temporal decay
        double temporalPrior = temporalDecayPrior(ctx);
        if (temporalPrior >= 0.0) return temporalPrior;

        // (f) Uniform fallback
        return 0.5;
    }

    // ─── Tier implementations ─────────────────────────────────────────────────

    /**
     * Tier (c): embedding geometric prior.
     *
     * <p>Maps the L2 norm of the embedding to a calibrated prior via
     * {@code score = norm / (norm + 1.0)} (a Platt-style sigmoid that maps
     * {@code (0, ∞) → (0, 1)}), then wraps it in
     * {@link Opinion#fromEmbeddingScore(double, double)} with the fixed uncertainty floor.</p>
     *
     * @param embedding the entity embedding, or {@code null}
     * @return the expectation of the calibrated opinion, or {@code -1.0} as a "skip" sentinel
     */
    static double embeddingPrior(double[] embedding) {
        if (embedding == null || embedding.length == 0) return -1.0;
        double norm = Embeddings.magnitude(embedding);
        if (norm < 1e-10) return -1.0; // zero-magnitude vector carries no information
        double score = norm / (norm + 1.0);
        return Opinion.fromEmbeddingScore(score, EMBEDDING_UNCERTAINTY).expectation();
    }

    /**
     * Tier (d): Laplace-smoothed type-frequency prior (EmpiricalPriorBlend).
     *
     * <p>Prior = {@code (typeCount + 1) / (totalCount + 2)}.  Returns a negative sentinel
     * when the frequency map is absent or the entity type is not recognised.</p>
     */
    static double typeFrequencyPrior(PriorContext ctx) {
        Map<String, Long> freqs = ctx.typeFrequencies();
        if (freqs == null || freqs.isEmpty() || ctx.entityType() == null) return -1.0;
        Long count = freqs.get(ctx.entityType());
        if (count == null) return -1.0;
        long total = freqs.values().stream().mapToLong(Long::longValue).sum();
        if (total <= 0) return -1.0;
        return (count + 1.0) / (total + 2.0);
    }

    /**
     * Tier (e): temporal decay prior.
     *
     * <p>Formula: {@code P = 0.5 + 0.5 * exp(−γ · Δt)} where Δt is the elapsed seconds
     * from the most recent reference time to now.  Uses {@link PriorContext#lastVerifiedAt()}
     * preferentially over {@link PriorContext#occurredAt()}.</p>
     *
     * <p>Returns a negative sentinel when no timestamp is available (both are null), so the
     * cascade falls through to the uniform 0.5 in tier (f).</p>
     */
    static double temporalDecayPrior(PriorContext ctx) {
        Instant ref = ctx.lastVerifiedAt() != null ? ctx.lastVerifiedAt() : ctx.occurredAt();
        if (ref == null) return -1.0; // no temporal information — fall through to tier (f)
        double deltaT  = Math.max(0.0, ChronoUnit.SECONDS.between(ref, Instant.now()));
        double gamma   = ctx.gamma() > 0 ? ctx.gamma() : PriorContext.DEFAULT_GAMMA;
        return 0.5 + 0.5 * Math.exp(-gamma * deltaT);
    }
}
