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

import ai.kompile.graph.reasoning.confidence.InMemoryOpinionStore;
import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.confidence.OpinionStore;
import ai.kompile.graph.reasoning.prior.CascadePriorProvider;

import java.util.Collection;
import java.util.Objects;

/**
 * Bridge that writes KGE plausibility scores into an {@link OpinionStore} as
 * {@link Opinion#fromEmbeddingScore} opinions, making them available as MEBN root priors
 * via {@link CascadePriorProvider}.
 *
 * <h3>How MEBN consumes these opinions</h3>
 * <p>{@link CascadePriorProvider} applies a six-tier waterfall when computing a prior:</p>
 * <ol>
 *   <li>Hard findings</li>
 *   <li><b>OpinionStore expectation</b> ← KGE scores land here</li>
 *   <li>Embedding geometric prior (L2 norm of entity embedding)</li>
 *   <li>Type-frequency shrinkage</li>
 *   <li>Temporal decay</li>
 *   <li>Uniform 0.5 fallback</li>
 * </ol>
 * <p>Writing a non-vacuous {@link Opinion} into the store via this bridge causes the
 * cascade to resolve at tier (b) for the corresponding atom key.  <b>No changes to
 * MEBN or SSBNGenerator internals are needed.</b></p>
 *
 * <h3>Atom key convention</h3>
 * <p>The key written to the store must match the key used when calling
 * {@link CascadePriorProvider#priorFor(String, ai.kompile.graph.reasoning.prior.PriorContext)}
 * or {@link CascadePriorProvider#strengthFor(String, String, ai.kompile.graph.reasoning.prior.PriorContext)}
 * in your MEBN code.  Use the {@link TripleKeyFormatter} parameter to supply a formatter
 * consistent with your calling convention; the default is
 * {@code headId + "->" + relationType + "->" + tailId}.</p>
 *
 * <h3>Uncertainty floor</h3>
 * <p>KGE scores are derived from geometric distances in a learned embedding space and
 * therefore carry inherent uncertainty.  The {@code embeddingUncertainty} parameter
 * (default {@link #DEFAULT_UNCERTAINTY}) controls how much simplex mass is allocated to
 * the uncertainty component of the resulting {@link Opinion}.  A value of {@code 0.25}
 * means 25% of the opinion is "I don't know", regardless of the scored plausibility.</p>
 *
 * @see KgeTripleScorer
 * @see Opinion#fromEmbeddingScore(double, double)
 * @see CascadePriorProvider
 */
public final class KgeOpinionStoreBridge {

    /** Default embedding uncertainty floor: 25% of simplex mass is uncertain. */
    public static final double DEFAULT_UNCERTAINTY = 0.25;

    /** Default atom key format: {@code headId->relationType->tailId}. */
    public static final TripleKeyFormatter DEFAULT_KEY_FORMAT =
            (head, rel, tail) -> head + "->" + rel + "->" + tail;

    private KgeOpinionStoreBridge() {}

    /**
     * Functional interface for computing an atom key from a (head, relationType, tail) triple.
     * The returned key must match the key used when calling
     * {@link CascadePriorProvider#priorFor} or {@link CascadePriorProvider#strengthFor}.
     */
    @FunctionalInterface
    public interface TripleKeyFormatter {
        String format(String headId, String relationType, String tailId);
    }

    // ─── Main entry points ────────────────────────────────────────────────────

    /**
     * Score all {@code (head, relationType, tail)} combinations from the given entity and
     * relation sets and write each qualifying triple as an {@link Opinion} into {@code store}.
     *
     * <p>Uses {@link #DEFAULT_UNCERTAINTY} and {@link #DEFAULT_KEY_FORMAT}.</p>
     *
     * @param store         the opinion store to populate
     * @param scorer        the KGE scorer
     * @param entityIds     entity id candidates (forms the Cartesian head × tail product)
     * @param relationTypes relation type strings to enumerate
     * @param threshold     minimum plausibility to write into the store (triples below are skipped)
     * @return the number of opinions written
     */
    public static int populateFrom(OpinionStore store,
                                   KgeTripleScorer scorer,
                                   Collection<String> entityIds,
                                   Collection<String> relationTypes,
                                   double threshold) {
        return populateFrom(store, scorer, entityIds, relationTypes, threshold,
                DEFAULT_UNCERTAINTY, DEFAULT_KEY_FORMAT);
    }

    /**
     * Full-control overload: score candidate triples, apply a configurable uncertainty floor
     * and atom-key format, and write opinions into {@code store}.
     *
     * @param store                the opinion store to populate
     * @param scorer               the KGE scorer
     * @param entityIds            entity id candidates
     * @param relationTypes        relation type strings
     * @param threshold            minimum plausibility to write (triples below are skipped)
     * @param embeddingUncertainty uncertainty floor in {@code [0, 1)} for
     *                             {@link Opinion#fromEmbeddingScore(double, double)}
     * @param keyFormatter         maps {@code (headId, relationType, tailId)} to an atom key
     * @return the number of opinions written
     * @throws IllegalArgumentException if {@code threshold} or {@code embeddingUncertainty}
     *                                  is outside {@code [0, 1]}
     */
    public static int populateFrom(OpinionStore store,
                                   KgeTripleScorer scorer,
                                   Collection<String> entityIds,
                                   Collection<String> relationTypes,
                                   double threshold,
                                   double embeddingUncertainty,
                                   TripleKeyFormatter keyFormatter) {
        Objects.requireNonNull(store, "store must not be null");
        Objects.requireNonNull(scorer, "scorer must not be null");
        Objects.requireNonNull(entityIds, "entityIds must not be null");
        Objects.requireNonNull(relationTypes, "relationTypes must not be null");
        Objects.requireNonNull(keyFormatter, "keyFormatter must not be null");
        if (threshold < 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException("threshold must be in [0, 1], got " + threshold);
        }
        if (embeddingUncertainty < 0.0 || embeddingUncertainty >= 1.0) {
            throw new IllegalArgumentException(
                    "embeddingUncertainty must be in [0, 1), got " + embeddingUncertainty);
        }

        int written = 0;
        for (String head : entityIds) {
            for (String rel : relationTypes) {
                for (String tail : entityIds) {
                    if (!scorer.knows(head, rel, tail)) continue;
                    double score = scorer.scoreTriple(head, rel, tail);
                    if (score < threshold) continue;
                    Opinion opinion = Opinion.fromEmbeddingScore(score, embeddingUncertainty);
                    store.put(keyFormatter.format(head, rel, tail), opinion);
                    written++;
                }
            }
        }
        return written;
    }

    /**
     * Convenience factory: create a fresh {@link InMemoryOpinionStore}, populate it from
     * the given scorer, and return it.  Useful when no existing store is available.
     *
     * @param scorer        the KGE scorer
     * @param entityIds     entity id candidates
     * @param relationTypes relation type strings
     * @param threshold     minimum plausibility threshold
     * @return a populated {@link InMemoryOpinionStore}
     */
    public static OpinionStore buildStore(KgeTripleScorer scorer,
                                          Collection<String> entityIds,
                                          Collection<String> relationTypes,
                                          double threshold) {
        InMemoryOpinionStore store = new InMemoryOpinionStore();
        populateFrom(store, scorer, entityIds, relationTypes, threshold);
        return store;
    }
}
