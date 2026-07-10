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

import ai.kompile.graph.reasoning.fol.grounding.VerifyResult;
import ai.kompile.graph.reasoning.synthesis.AnswerSynthesizer.Candidate;
import ai.kompile.graph.reasoning.synthesis.AnswerSynthesizer.CandidateSignal;
import ai.kompile.graph.reasoning.synthesis.AnswerSynthesizer.SynthesizedAnswer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP12b signal mapping + an end-to-end synthesis acceptance test: raw store outputs → calibrated
 * signals → ranked answers where a type-violating or refuted candidate is correctly demoted.
 */
class CandidateSignalMapperTest {

    @Test
    void textSignal_positiveSimilarityGivesBelief() {
        assertTrue(CandidateSignalMapper.textSignal(0.9, 0.5).opinion().expectation() > 0.5);
    }

    @Test
    void typeSignal_allowedHigh_violatesLow_unboundVacuous() {
        assertTrue(CandidateSignalMapper.typeSignal(true, true, false).opinion().expectation() > 0.8);
        assertTrue(CandidateSignalMapper.typeSignal(true, false, true).opinion().expectation() < 0.2);
        assertTrue(CandidateSignalMapper.typeSignal(false, false, false).opinion().isVacuous());
    }

    @Test
    void engineSignal_supportedHigh_refutedLow_unknownVacuous() {
        assertTrue(CandidateSignalMapper.engineSignal("psl", VerifyResult.Status.SUPPORTED, 0.9)
                .opinion().expectation() > 0.7);
        assertTrue(CandidateSignalMapper.engineSignal("psl", VerifyResult.Status.REFUTED, 0.9)
                .opinion().expectation() < 0.3);
        assertTrue(CandidateSignalMapper.engineSignal("psl", VerifyResult.Status.UNKNOWN, 0.0)
                .opinion().isVacuous());
    }

    @Test
    void endToEnd_typeViolatingCandidateRanksBelowConformant() {
        // Both retrieve equally well and are KB-SUPPORTED; only the type differs.
        Candidate conformant = new Candidate("alice", List.of(
                CandidateSignalMapper.textSignal(0.9, 0.5),
                CandidateSignalMapper.typeSignal(true, true, false),
                CandidateSignalMapper.engineSignal("psl", VerifyResult.Status.SUPPORTED, 0.9)));
        Candidate violator = new Candidate("bob", List.of(
                CandidateSignalMapper.textSignal(0.9, 0.5),
                CandidateSignalMapper.typeSignal(true, false, true), // wrong type
                CandidateSignalMapper.engineSignal("psl", VerifyResult.Status.SUPPORTED, 0.9)));

        List<SynthesizedAnswer> ranked = AnswerSynthesizer.synthesize(List.of(violator, conformant));

        assertEquals("alice", ranked.get(0).entityId());
        SynthesizedAnswer bob = ranked.get(1);
        assertTrue(bob.likelihood() < ranked.get(0).likelihood());
        assertTrue(bob.likelihood() < 0.2, "type violation should tank the composite: " + bob.likelihood());
    }

    @Test
    void endToEnd_refutedCandidateIsDemoted() {
        Candidate supported = new Candidate("s", List.of(
                CandidateSignalMapper.textSignal(0.8, 0.4),
                CandidateSignalMapper.engineSignal("psl", VerifyResult.Status.SUPPORTED, 0.85)));
        Candidate refuted = new Candidate("r", List.of(
                CandidateSignalMapper.textSignal(0.8, 0.4),
                CandidateSignalMapper.engineSignal("psl", VerifyResult.Status.REFUTED, 0.85)));

        List<SynthesizedAnswer> ranked = AnswerSynthesizer.synthesize(List.of(refuted, supported));
        assertEquals("s", ranked.get(0).entityId());
        assertTrue(ranked.get(1).likelihood() < ranked.get(0).likelihood());
    }
}
