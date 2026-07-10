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
package ai.kompile.graph.reasoning.synthesis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Trains a {@link LogisticAnswerScorer} from labeled {@link TrainingExample}s and evaluates it on a
 * <b>held-out set of queries</b> — the "ship only if it beats the fold" gate for the learned scorer.
 * Splitting by whole queries (never a candidate) prevents leakage, and the held-out MRR is compared to a
 * <b>retrieval-only baseline</b> (rank by the ω_text feature alone) so we can prove the model adds value
 * beyond retrieval before deploying it. Pure + deterministic — no infra, no RNG.
 */
public final class AnswerScorerTrainingHarness {

    private AnswerScorerTrainingHarness() {
    }

    /** Trained model + held-out metrics. */
    public record TrainingReport(
            LogisticAnswerScorer model,
            int trainRows,
            int heldOutQueries,
            double trainLogLoss,
            double heldOutLogLoss,
            double heldOutAccuracy,
            double learnedMrr,
            double retrievalBaselineMrr,
            double algebraicFoldMrr,
            double learnedRecallAt1,
            double learnedRecallAt3,
            double learnedNdcgAt3,
            List<String> featureNames,
            double[] weights,
            double bias) {

        /** True iff the learned scorer out-ranks the retrieval-only signal on held-out queries. */
        public boolean beatsBaseline() {
            return learnedMrr > retrievalBaselineMrr;
        }

        /**
         * The deploy gate: the learned scorer is at least as good as the hand-coded algebraic fold on
         * held-out queries. Only ship a trained model when this holds (otherwise the fold is better).
         */
        public boolean beatsFold() {
            return learnedMrr >= algebraicFoldMrr;
        }
    }

    /**
     * Fit on ~{@code (1 - heldOutFraction)} of the queries and evaluate on the rest. Deterministic split
     * (every k-th query, k = round(1/heldOutFraction)). Falls back to training on everything (held-out
     * metrics NaN) when there are too few queries to split.
     */
    public static TrainingReport train(List<TrainingExample> examples, double heldOutFraction) {
        LinkedHashMap<String, List<TrainingExample>> byQuery = new LinkedHashMap<>();
        for (TrainingExample ex : examples) {
            byQuery.computeIfAbsent(ex.query(), q -> new ArrayList<>()).add(ex);
        }
        List<String> queries = new ArrayList<>(byQuery.keySet());
        int everyK = Math.max(2, (int) Math.round(heldOutFraction > 0.0 ? 1.0 / heldOutFraction : 5.0));

        List<TrainingExample> train = new ArrayList<>();
        List<List<TrainingExample>> heldOut = new ArrayList<>();
        for (int i = 0; i < queries.size(); i++) {
            List<TrainingExample> group = byQuery.get(queries.get(i));
            if (queries.size() > 1 && i % everyK == 0) {
                heldOut.add(group);
            } else {
                train.addAll(group);
            }
        }
        if (train.isEmpty()) { // degenerate (1 query) → train on all, no held-out eval
            train.addAll(examples);
            heldOut.clear();
        }

        double[][] x = new double[train.size()][];
        double[] y = new double[train.size()];
        for (int i = 0; i < train.size(); i++) {
            x[i] = train.get(i).features();
            y[i] = train.get(i).label();
        }
        LogisticAnswerScorer model = LogisticRegressionTrainer.fit(x, y);
        double trainLoss = LogisticRegressionTrainer.logLoss(model, x, y);

        double sumRr = 0.0;
        double sumBaseRr = 0.0;
        double sumFoldRr = 0.0;
        double sumLoss = 0.0;
        double sumR1 = 0.0;
        double sumR3 = 0.0;
        double sumNdcg3 = 0.0;
        int correct = 0;
        int rows = 0;
        for (List<TrainingExample> group : heldOut) {
            ToDoubleFunction<TrainingExample> learned = ex -> model.score(ex.features());
            sumRr += reciprocalRank(group, learned);
            sumBaseRr += reciprocalRank(group, ex -> ex.features().length > 0 ? ex.features()[0] : 0.0);
            sumFoldRr += reciprocalRank(group, ex -> algebraicFoldScore(ex.features()));
            sumR1 += hitAtK(group, learned, 1);
            sumR3 += hitAtK(group, learned, 3);
            sumNdcg3 += ndcgAtK(group, learned, 3);
            for (TrainingExample ex : group) {
                double p = model.score(ex.features());
                if ((p >= 0.5) == ex.correct()) {
                    correct++;
                }
                sumLoss += ex.correct() ? -Math.log(clamp(p)) : -Math.log(1.0 - clamp(p));
                rows++;
            }
        }
        int hq = heldOut.size();
        return new TrainingReport(
                model,
                train.size(),
                hq,
                trainLoss,
                rows > 0 ? sumLoss / rows : Double.NaN,
                rows > 0 ? (double) correct / rows : Double.NaN,
                hq > 0 ? sumRr / hq : Double.NaN,
                hq > 0 ? sumBaseRr / hq : Double.NaN,
                hq > 0 ? sumFoldRr / hq : Double.NaN,
                hq > 0 ? sumR1 / hq : Double.NaN,
                hq > 0 ? sumR3 / hq : Double.NaN,
                hq > 0 ? sumNdcg3 / hq : Double.NaN,
                AnswerFeatures.NAMES,
                model.weights(),
                model.bias());
    }

    private static List<TrainingExample> sortByScoreDesc(List<TrainingExample> group,
                                                         ToDoubleFunction<TrainingExample> scorer) {
        List<TrainingExample> sorted = new ArrayList<>(group);
        sorted.sort((a, b) -> Double.compare(scorer.applyAsDouble(b), scorer.applyAsDouble(a)));
        return sorted;
    }

    /** Reciprocal rank of the first correct candidate when the group is ranked descending by {@code scorer}. */
    private static double reciprocalRank(List<TrainingExample> group, ToDoubleFunction<TrainingExample> scorer) {
        List<TrainingExample> sorted = sortByScoreDesc(group, scorer);
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).correct()) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /** recall@k for a single-positive query: 1 if a correct candidate lands in the top-k by score. */
    private static double hitAtK(List<TrainingExample> group, ToDoubleFunction<TrainingExample> scorer, int k) {
        List<TrainingExample> sorted = sortByScoreDesc(group, scorer);
        int limit = Math.min(k, sorted.size());
        for (int i = 0; i < limit; i++) {
            if (sorted.get(i).correct()) {
                return 1.0;
            }
        }
        return 0.0;
    }

    /** Binary nDCG@k for a single-positive query (IDCG=1): 1/log2(rank+1) if the correct one is in top-k. */
    private static double ndcgAtK(List<TrainingExample> group, ToDoubleFunction<TrainingExample> scorer, int k) {
        List<TrainingExample> sorted = sortByScoreDesc(group, scorer);
        int limit = Math.min(k, sorted.size());
        for (int i = 0; i < limit; i++) {
            if (sorted.get(i).correct()) {
                return 1.0 / (Math.log(i + 2) / Math.log(2));
            }
        }
        return 0.0;
    }

    /**
     * The algebraic fold's ranking score from a feature vector — the product of the present groups'
     * expectations, mirroring the ⊙ conjunction {@code E = ∏ E_group}. This is the hand-coded baseline
     * the learned scorer must beat ({@code beatsFold}) to be worth deploying. Exact for single-signal
     * groups; a close proxy when a group fuses multiple signals.
     */
    private static double algebraicFoldScore(double[] f) {
        int[][] groups = {{0, 2}, {3, 5}, {6, 8}, {9, 11}}; // {expectation index, present-flag index}
        double e = 1.0;
        boolean any = false;
        for (int[] g : groups) {
            if (f.length > g[1] && f[g[1]] > 0.5) { // group present
                e *= f[g[0]];
                any = true;
            }
        }
        return any ? e : 0.0;
    }

    private static double clamp(double p) {
        return p < 1e-9 ? 1e-9 : (p > 1.0 - 1e-9 ? 1.0 - 1e-9 : p);
    }
}
