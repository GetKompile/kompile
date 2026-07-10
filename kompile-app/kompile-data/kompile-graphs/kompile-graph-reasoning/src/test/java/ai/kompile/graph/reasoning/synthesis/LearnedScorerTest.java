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

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.synthesis.AnswerSynthesizer.CandidateSignal;
import ai.kompile.graph.reasoning.synthesis.AnswerSynthesizer.SignalGroup;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The small learned scorer: feature extraction, and a logistic model that learns from labeled data
 * (including learning WHICH signal matters).
 */
class LearnedScorerTest {

    private static final double EPS = 1e-9;

    @Test
    void featureVectorEncodesPresencePerGroup() {
        List<CandidateSignal> signals = List.of(
                CandidateSignal.of(SignalGroup.RETRIEVAL, "text", new Opinion(0.6, 0.2, 0.2, 0.5)), // E=0.70
                CandidateSignal.of(SignalGroup.TYPE, "type", new Opinion(0.9, 0.02, 0.08, 0.5)));
        double[] f = AnswerFeatures.extract(signals);

        assertEquals(AnswerFeatures.DIM, f.length);
        assertEquals(0.70, f[0], EPS);   // retrieval_e
        assertEquals(1.0, f[2], EPS);    // retrieval_present
        assertEquals(0.0, f[5], EPS);    // engine absent
        assertEquals(1.0, f[4], EPS);    // engine_u = 1 when absent
        assertEquals(1.0, f[8], EPS);    // type_present
        assertEquals(0.0, f[11], EPS);   // consistency absent
    }

    @Test
    void untrainedScorerIsOneHalf() {
        assertEquals(0.5, LogisticAnswerScorer.untrained(AnswerFeatures.DIM).score(new double[AnswerFeatures.DIM]), EPS);
    }

    @Test
    void learnsWhichSignalDiscriminates() {
        // 20 examples where ONLY the engine (KB-verification) feature [3] tracks correctness.
        int dim = AnswerFeatures.DIM;
        double[][] x = new double[20][dim];
        double[] y = new double[20];
        for (int i = 0; i < 20; i++) {
            for (int j = 0; j < dim; j++) {
                x[i][j] = 0.5; // uninformative baseline
            }
            boolean correct = i < 10;
            x[i][3] = correct ? 0.9 : 0.1; // engine_e discriminates
            y[i] = correct ? 1.0 : 0.0;
        }

        LogisticAnswerScorer model = LogisticRegressionTrainer.fit(x, y);

        // It separates the classes...
        double[] strong = baseline(dim);
        strong[3] = 0.9;
        double[] weak = baseline(dim);
        weak[3] = 0.1;
        assertTrue(model.score(strong) > 0.7, "supported candidate should score high: " + model.score(strong));
        assertTrue(model.score(weak) < 0.3, "unsupported candidate should score low: " + model.score(weak));

        // ...and learned that the engine feature is the one that matters.
        double[] w = model.weights();
        assertTrue(w[3] > Math.abs(w[0]), "engine weight should dominate retrieval: " + w[3] + " vs " + w[0]);

        // ...and fitting reduced the loss vs. the untrained prior.
        double trainedLoss = LogisticRegressionTrainer.logLoss(model, x, y);
        double priorLoss = LogisticRegressionTrainer.logLoss(LogisticAnswerScorer.untrained(dim), x, y);
        assertTrue(trainedLoss < priorLoss, "training should reduce log-loss: " + trainedLoss + " < " + priorLoss);
    }

    private static double[] baseline(int dim) {
        double[] v = new double[dim];
        for (int j = 0; j < dim; j++) {
            v[j] = 0.5;
        }
        return v;
    }
}
