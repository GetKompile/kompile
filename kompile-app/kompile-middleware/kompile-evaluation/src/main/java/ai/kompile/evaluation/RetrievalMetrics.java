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
package ai.kompile.evaluation;

import java.util.List;
import java.util.Set;

/**
 * Retrieval-quality metrics (recall@k, precision@k, MRR, hit@k, nDCG@k) over ranked retrieval results
 * against a set of relevant (golden) ids. This is the retrieval side of grounded-RAG evaluation
 * (lit-gap rec 5) that the answer-quality evaluators (faithfulness / relevancy / …) don't cover — the
 * measurement needed to answer "is graph retrieval actually better than dense?" in-house rather than
 * trusting benchmark claims (64% of which the literature review refuted).
 *
 * <p>Pure functions over {@code (rankedRetrievedIds, relevantIds)} — no Spring, fully unit-testable.
 * A query with an empty relevant set contributes 0 (callers should exclude such queries from a corpus
 * average if that is not the intended semantics).</p>
 */
public final class RetrievalMetrics {

    private RetrievalMetrics() {
    }

    /** One query's ranked retrieval + its golden relevant ids. */
    public record RankedResult(List<String> retrieved, Set<String> relevant) {
    }

    /** Fraction of relevant ids appearing in the top-{@code k} retrieved. 0 when there are no relevant ids. */
    public static double recallAtK(List<String> retrieved, Set<String> relevant, int k) {
        if (relevant == null || relevant.isEmpty()) {
            return 0.0;
        }
        return (double) relevantHits(retrieved, relevant, k) / relevant.size();
    }

    /** Fraction of the top-{@code k} retrieved that are relevant. 0 when {@code k <= 0}. */
    public static double precisionAtK(List<String> retrieved, Set<String> relevant, int k) {
        if (k <= 0 || retrieved == null || retrieved.isEmpty() || relevant == null || relevant.isEmpty()) {
            return 0.0;
        }
        return (double) relevantHits(retrieved, relevant, k) / k;
    }

    /** 1.0 if any relevant id is in the top-{@code k}, else 0.0. */
    public static double hitAtK(List<String> retrieved, Set<String> relevant, int k) {
        return relevantHits(retrieved, relevant, k) > 0 ? 1.0 : 0.0;
    }

    /** Reciprocal rank of the FIRST relevant id (1-indexed); 0 if none retrieved. */
    public static double reciprocalRank(List<String> retrieved, Set<String> relevant) {
        if (retrieved == null || relevant == null || relevant.isEmpty()) {
            return 0.0;
        }
        for (int i = 0; i < retrieved.size(); i++) {
            if (relevant.contains(retrieved.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /** Binary-relevance normalized discounted cumulative gain at {@code k}. */
    public static double ndcgAtK(List<String> retrieved, Set<String> relevant, int k) {
        if (k <= 0 || retrieved == null || relevant == null || relevant.isEmpty()) {
            return 0.0;
        }
        int limit = Math.min(k, retrieved.size());
        double dcg = 0.0;
        for (int i = 0; i < limit; i++) {
            if (relevant.contains(retrieved.get(i))) {
                dcg += 1.0 / log2(i + 2); // position i (0-based) → rank i+1 → discount log2(rank+1)
            }
        }
        double idcg = 0.0;
        int ideal = Math.min(k, relevant.size());
        for (int i = 0; i < ideal; i++) {
            idcg += 1.0 / log2(i + 2);
        }
        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }

    // ── Corpus aggregates ─────────────────────────────────────────────────────────

    /** Mean reciprocal rank across queries. Empty input → 0. */
    public static double meanReciprocalRank(List<RankedResult> results) {
        return mean(results, r -> reciprocalRank(r.retrieved(), r.relevant()));
    }

    /** Mean recall@k across queries. */
    public static double meanRecallAtK(List<RankedResult> results, int k) {
        return mean(results, r -> recallAtK(r.retrieved(), r.relevant(), k));
    }

    /** Mean nDCG@k across queries. */
    public static double meanNdcgAtK(List<RankedResult> results, int k) {
        return mean(results, r -> ndcgAtK(r.retrieved(), r.relevant(), k));
    }

    // ── Internals ───────────────────────────────────────────────────────────────

    private static int relevantHits(List<String> retrieved, Set<String> relevant, int k) {
        if (k <= 0 || retrieved == null || relevant == null || relevant.isEmpty()) {
            return 0;
        }
        int limit = Math.min(k, retrieved.size());
        int hits = 0;
        for (int i = 0; i < limit; i++) {
            if (relevant.contains(retrieved.get(i))) {
                hits++;
            }
        }
        return hits;
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2.0);
    }

    private static double mean(List<RankedResult> results, java.util.function.ToDoubleFunction<RankedResult> f) {
        if (results == null || results.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (RankedResult r : results) {
            sum += f.applyAsDouble(r);
        }
        return sum / results.size();
    }
}
