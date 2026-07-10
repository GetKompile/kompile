/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.app.services.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure claim-JSON parser for the LLM-backed answer-claim extractor: tolerant of prose/fences,
 * accepts string or object arrays, and keeps only atom-shaped, de-duplicated, capped entries.
 */
class LlmAnswerClaimExtractorTest {

    @Test
    void parsesPlainJsonArrayOfAtomStrings() {
        List<String> atoms = LlmAnswerClaimExtractor.parseAtomKeys(
                "[\"works_at(alice, acme)\", \"headquartered_in(acme, seattle)\"]", 10);
        assertEquals(List.of("works_at(alice, acme)", "headquartered_in(acme, seattle)"), atoms);
    }

    @Test
    void extractsArrayEmbeddedInProseAndCodeFence() {
        String resp = "Sure! Here are the claims:\n```json\n[\"ceo_of(bob, acme)\"]\n```\nHope that helps.";
        assertEquals(List.of("ceo_of(bob, acme)"), LlmAnswerClaimExtractor.parseAtomKeys(resp, 10));
    }

    @Test
    void acceptsArrayOfObjectsWithAtomField() {
        List<String> atoms = LlmAnswerClaimExtractor.parseAtomKeys(
                "[{\"atom\":\"located_in(acme, wa)\"},{\"atom\":\"founded(acme, 1999)\"}]", 10);
        assertEquals(List.of("located_in(acme, wa)", "founded(acme, 1999)"), atoms);
    }

    @Test
    void dropsNonAtomShapedEntries_andDeduplicates() {
        List<String> atoms = LlmAnswerClaimExtractor.parseAtomKeys(
                "[\"works_at(alice, acme)\", \"just a sentence\", \"works_at(alice, acme)\", \"\"]", 10);
        assertEquals(List.of("works_at(alice, acme)"), atoms);
    }

    @Test
    void capsAtMax() {
        List<String> atoms = LlmAnswerClaimExtractor.parseAtomKeys(
                "[\"a(1)\",\"b(2)\",\"c(3)\",\"d(4)\"]", 2);
        assertEquals(2, atoms.size());
    }

    @Test
    void noArrayOrMalformed_returnsEmpty() {
        assertTrue(LlmAnswerClaimExtractor.parseAtomKeys("no json here", 10).isEmpty());
        assertTrue(LlmAnswerClaimExtractor.parseAtomKeys("[ not valid json ", 10).isEmpty());
        assertTrue(LlmAnswerClaimExtractor.parseAtomKeys(null, 10).isEmpty());
        assertTrue(LlmAnswerClaimExtractor.parseAtomKeys("[\"x(1)\"]", 0).isEmpty());
    }
}
