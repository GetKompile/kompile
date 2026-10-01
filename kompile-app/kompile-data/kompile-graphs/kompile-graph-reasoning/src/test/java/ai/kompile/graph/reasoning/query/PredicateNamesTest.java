/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.reasoning.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PredicateNamesTest {

    @Test
    void caseCamelHumpsAndPunctuationAllReadAsOneSpelling() {
        for (String spelling : List.of("worksFor", "WorksFor", "works_for", "WORKS_FOR", "Works-For",
                "works for", "works__for", "  worksFor  ", "works.for")) {
            assertEquals("WORKS_FOR", PredicateNames.canonical(spelling), spelling);
        }
    }

    @Test
    void acronymAndDigitRunsEndBeforeTheNextCapitalisedWord() {
        assertEquals("HTTP_SERVER", PredicateNames.canonical("HTTPServer"));
        assertEquals("IPV4_ADDRESS", PredicateNames.canonical("ipv4Address"));
        assertEquals("HAS_CEO", PredicateNames.canonical("hasCEO"));
        assertEquals("ABC2X", PredicateNames.canonical("ABC2x"));
    }

    @Test
    void edgeSeparatorsAreKeptSoMarkersNeverCollapseIntoThePlainPredicate() {
        assertEquals("_X", PredicateNames.canonical("~X"));
        assertEquals("X_", PredicateNames.canonical("X~"));
        assertEquals("NOT_WORKS_FOR", PredicateNames.canonical("NOT_worksFor"));
        assertFalse(PredicateNames.same("~worksFor", "worksFor"));
        assertFalse(PredicateNames.same("NOT_ABLE", "NOTABLE"));
    }

    @Test
    void nonLatinVocabulariesStayDistinct() {
        assertEquals("工作于", PredicateNames.canonical("工作于"));
        assertEquals("TRABAJA_PARA", PredicateNames.canonical("trabajaPara"));
        assertEquals("CAFÉ_OWNER", PredicateNames.canonical("caféOwner"));
        assertFalse(PredicateNames.same("工作于", "工作在"));
    }

    @Test
    void canonicalIsIdempotentAndNullSafe() {
        for (String spelling : List.of("worksFor", "HTTPServer", "ipv4Address", "ABC2x", "~X", "X~",
                "NOT_worksFor", "工作于", "caféOwner", "straße", "aBC", "ABc", "a1Bc")) {
            String once = PredicateNames.canonical(spelling);
            assertEquals(once, PredicateNames.canonical(once), spelling);
        }
        assertEquals("", PredicateNames.canonical(null));
        assertEquals("", PredicateNames.canonical("   "));
        assertTrue(PredicateNames.same("worksFor", "WORKS_FOR"));
    }

    @Test
    void keyIgnoresTheSeparatorsBetweenWords() {
        for (String spelling : List.of("worksFor", "WORKS_FOR", "WORKSFOR", "worksfor", "works_for",
                "Works-For", "works for", "  WorksFor ")) {
            assertEquals("WORKSFOR", PredicateNames.key(spelling), spelling);
        }
        assertTrue(PredicateNames.same("WORKSFOR", "worksFor"));
        assertTrue(PredicateNames.same("worksfor", "works_for"));
        assertTrue(PredicateNames.same("HTTPServer", "httpserver"));
        assertFalse(PredicateNames.same("工作于", "工作在"));
        assertEquals("", PredicateNames.key(null));
        assertEquals("", PredicateNames.key(" "));
    }

    @Test
    void keyKeepsEdgeMarkersAndTheNegationPrefix() {
        assertEquals("NOT_WORKSFOR", PredicateNames.key("NOT_worksFor"));
        assertTrue(PredicateNames.same("NOT_WORKSFOR", "NOT_worksFor"));
        assertTrue(PredicateNames.same("notWorksFor", "NOT_WORKS_FOR"));
        assertFalse(PredicateNames.same("NOT_worksFor", "worksFor"));
        assertFalse(PredicateNames.same("NOT_ABLE", "NOTABLE"));
        assertEquals("_WORKSFOR", PredicateNames.key("~worksFor"));
        assertEquals("WORKSFOR_", PredicateNames.key("worksFor~"));
        assertEquals("_NOT_X", PredicateNames.key("~NOT_X"));
        assertEquals("NOT_", PredicateNames.key("NOT_"));
        assertFalse(PredicateNames.same("~worksFor", "worksFor"));
    }

    @Test
    void keyIsIdempotent() {
        for (String spelling : List.of("worksFor", "NOT_worksFor", "~X", "X~", "~NOT_X", "NOT_",
                "HTTPServer", "ipv4Address", "ABC2x", "工作于", "caféOwner", "straße", "a1Bc")) {
            String once = PredicateNames.key(spelling);
            assertEquals(once, PredicateNames.key(once), spelling);
        }
    }

    @Test
    void suggestsTheClosestKnownPredicatesOncePerKey() {
        List<String> known = List.of("EMPLOYED_BY", "works_at", "WORKS_AT", "WORKS_WITH", "LOCATED_IN");

        assertEquals(List.of("WORKS_AT", "WORKS_WITH"), PredicateNames.suggestions("worksFor", known, 3));
        assertEquals(List.of("WORKS_AT"), PredicateNames.suggestions("worksFor", known, 1));
        assertEquals(List.of("WORKS_FAR", "WORKS_FIR"),
                PredicateNames.suggestions("worksFor", List.of("WORKS_FIR", "WORKS_FAR"), 3));
        assertEquals(List.of("WORKSAT"),
                PredicateNames.suggestions("worksFor", List.of("WORKS_AT", "worksAt", "WORKSAT"), 3));
    }

    @Test
    void suggestsNothingWhenNoKnownPredicateIsClose() {
        assertEquals(List.of(), PredicateNames.suggestions("worksFor", List.of("LOCATED_IN"), 3));
        assertEquals(List.of(), PredicateNames.suggestions("", List.of("WORKS_AT"), 3));
        assertEquals(List.of(), PredicateNames.suggestions(null, List.of("WORKS_AT"), 3));
        assertEquals(List.of(), PredicateNames.suggestions("worksFor", null, 3));
        assertEquals(List.of(), PredicateNames.suggestions("worksFor", List.of("WORKS_AT"), 0));
    }
}
