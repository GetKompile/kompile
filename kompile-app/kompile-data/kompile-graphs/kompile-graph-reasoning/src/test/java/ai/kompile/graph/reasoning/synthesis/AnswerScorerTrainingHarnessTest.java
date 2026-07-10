/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.synthesis;

import ai.kompile.graph.reasoning.synthesis.AnswerScorerTrainingHarness.TrainingReport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The training harness fits the scorer and validates it on held-out queries, gating on beating the
 * retrieval-only baseline — the ML-scoring idea proven end-to-end (train → held-out → gate).
 */
class AnswerScorerTrainingHarnessTest {

    /** Feature vector with a given retrieval (idx 0) and engine (idx 3) value; the rest neutral. */
    private static double[] feat(double retrieval, double engine) {
        double[] f = new double[AnswerFeatures.DIM];
        for (int i = 0; i < f.length; i++) {
            f[i] = 0.5;
        }
        f[0] = retrieval;
        f[2] = 1.0;   // retrieval group present
        f[3] = engine;
        f[5] = 1.0;   // engine group present
        f[8] = 0.0;   // type absent
        f[11] = 0.0;  // consistency absent
        return f;
    }

    @Test
    void trainsAndBeatsRetrievalOnlyBaseline() {
        // Retrieval is MISLEADING here (correct answer has lower ω_text); the KB-verification signal
        // (engine, idx 3) is what actually discriminates. A retrieval-only ranker fails; the learned
        // model should discover the engine signal and win on held-out queries.
        List<TrainingExample> examples = new ArrayList<>();
        for (int q = 0; q < 6; q++) {
            String query = "q" + q;
            examples.add(new TrainingExample(query, "correct", feat(0.4, 0.9), 1));
            examples.add(new TrainingExample(query, "wrongA", feat(0.6, 0.1), 0));
            examples.add(new TrainingExample(query, "wrongB", feat(0.6, 0.1), 0));
        }

        TrainingReport report = AnswerScorerTrainingHarness.train(examples, 0.34);

        assertTrue(report.heldOutQueries() >= 1, "should hold out at least one query");
        assertTrue(report.beatsBaseline(),
                "learned MRR " + report.learnedMrr() + " should beat baseline " + report.retrievalBaselineMrr());
        assertTrue(report.learnedMrr() > 0.8,
                "learned should rank the correct answer near the top: " + report.learnedMrr());
        assertTrue(report.retrievalBaselineMrr() < 0.6,
                "retrieval-only baseline is misled here: " + report.retrievalBaselineMrr());
        assertTrue(report.weights()[3] > report.weights()[0],
                "engine weight should exceed the (misleading) retrieval weight");
        // Enriched held-out metrics: the correct answer is ranked #1, so recall@1 and nDCG@3 are high.
        assertTrue(report.learnedRecallAt1() > 0.8, "recall@1 should be high: " + report.learnedRecallAt1());
        assertTrue(report.learnedRecallAt3() > 0.99, "recall@3 with 3 candidates must be 1.0: " + report.learnedRecallAt3());
        assertTrue(report.learnedNdcgAt3() > 0.8, "nDCG@3 should be high: " + report.learnedNdcgAt3());
        // Deploy gate: the learned scorer is at least as good as the algebraic fold on held-out queries.
        assertTrue(report.beatsFold(), "learned should match/beat the fold: "
                + report.learnedMrr() + " vs " + report.algebraicFoldMrr());
    }
}
