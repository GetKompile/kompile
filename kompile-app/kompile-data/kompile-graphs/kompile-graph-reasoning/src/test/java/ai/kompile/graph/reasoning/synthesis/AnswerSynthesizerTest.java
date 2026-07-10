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
import ai.kompile.graph.reasoning.synthesis.AnswerSynthesizer.Candidate;
import ai.kompile.graph.reasoning.synthesis.AnswerSynthesizer.CandidateSignal;
import ai.kompile.graph.reasoning.synthesis.AnswerSynthesizer.SignalGroup;
import ai.kompile.graph.reasoning.synthesis.AnswerSynthesizer.SynthesizedAnswer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP12a AnswerSynthesizer: signals fuse per dependence structure (retrieval ⊕, engines consensus,
 * groups ⊙) into a ranked answer whose likelihood is reproducible from its operator-tree trace.
 */
class AnswerSynthesizerTest {

    private static final double EPS = 1e-9;
    private static final Opinion X = new Opinion(0.6, 0.2, 0.2, 0.5); // E = 0.70
    private static final Opinion Y = new Opinion(0.5, 0.3, 0.2, 0.4); // E = 0.58

    @Test
    void groupsAreConjoinedAtRoot() {
        Candidate c = new Candidate("alice", List.of(
                CandidateSignal.of(SignalGroup.RETRIEVAL, "text", X),
                CandidateSignal.of(SignalGroup.ENGINE, "psl", X)));
        SynthesizedAnswer a = AnswerSynthesizer.synthesize(List.of(c)).get(0);
        // one retrieval group (E=0.70) ⊙ one engine group (E=0.70) → 0.49
        assertEquals(0.70 * 0.70, a.likelihood(), EPS);
    }

    @Test
    void ranksByLikelihoodDescending() {
        Candidate strong = new Candidate("a", List.of(CandidateSignal.of(SignalGroup.RETRIEVAL, "text", X)));
        Candidate weak = new Candidate("b", List.of(CandidateSignal.of(SignalGroup.RETRIEVAL, "text", Y)));
        List<SynthesizedAnswer> ranked = AnswerSynthesizer.synthesize(List.of(weak, strong));
        assertEquals("a", ranked.get(0).entityId());
        assertEquals("b", ranked.get(1).entityId());
    }

    @Test
    void correlatedEnginesTakeConsensus_noFalseBoost() {
        // PSL & MEBN agree on X; consensus of X,X = X → likelihood stays E(X), not inflated.
        Candidate c = new Candidate("c", List.of(
                CandidateSignal.of(SignalGroup.ENGINE, "psl", X),
                CandidateSignal.of(SignalGroup.ENGINE, "mebn", X)));
        assertEquals(0.70, AnswerSynthesizer.synthesize(List.of(c)).get(0).likelihood(), EPS);
    }

    @Test
    void independentRetrievalSignalsFuse_lowerUncertainty() {
        Candidate c = new Candidate("d", List.of(
                CandidateSignal.of(SignalGroup.RETRIEVAL, "text", X),
                CandidateSignal.of(SignalGroup.RETRIEVAL, "kge", X)));
        Opinion fused = AnswerSynthesizer.synthesize(List.of(c)).get(0).opinion();
        assertTrue(fused.uncertainty() < X.uncertainty(), "independent agreement should reduce u: " + fused);
    }

    @Test
    void likelihoodIsReproducibleFromTheTrace() {
        Candidate c = new Candidate("alice", List.of(
                CandidateSignal.of(SignalGroup.RETRIEVAL, "text", X),
                CandidateSignal.of(SignalGroup.TYPE, "type", Y)));
        SynthesizedAnswer a = AnswerSynthesizer.synthesize(List.of(c)).get(0);
        // The acceptance property: re-evaluating the returned trace yields the same likelihood.
        assertEquals(a.likelihood(), a.trace().value().expectation(), EPS);
        assertEquals(0.70 * 0.58, a.likelihood(), EPS);
    }

    @Test
    void candidateWithNoSignalsIsVacuous() {
        SynthesizedAnswer a = AnswerSynthesizer.synthesize(List.of(new Candidate("e", List.of()))).get(0);
        assertTrue(a.opinion().isVacuous());
    }
}
