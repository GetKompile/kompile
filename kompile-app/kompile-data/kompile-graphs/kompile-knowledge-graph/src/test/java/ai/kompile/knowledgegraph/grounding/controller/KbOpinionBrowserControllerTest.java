/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.knowledgegraph.grounding.controller;

import ai.kompile.graph.reasoning.confidence.StrengthBand;
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.knowledgegraph.persistence.dual.InferredFactRow;
import ai.kompile.knowledgegraph.persistence.dual.InferredFactRowRepository;
import ai.kompile.knowledgegraph.reasoning.FactPromotionTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link KbOpinionBrowserController}, covering:
 * <ul>
 *   <li>Service-unavailable guard (no FactPromotionTracker)</li>
 *   <li>basisType extraction from provenanceJson (D4)</li>
 *   <li>basisType server-side filter param (D4)</li>
 *   <li>q text filter covering subject/predicate in atomKey (D5)</li>
 *   <li>{@code extractStringValue} edge cases</li>
 * </ul>
 *
 * <p>Uses Mockito to avoid a full Spring context.  {@link FactPromotionTracker} and
 * {@link InferredFactRowRepository} are mocked; opinion math is exercised via
 * the real {@link Opinion} factory methods.</p>
 */
@DisplayName("KbOpinionBrowserController")
class KbOpinionBrowserControllerTest {

    private FactPromotionTracker tracker;
    private InferredFactRowRepository repo;
    private KbOpinionBrowserController controller;

    // ── Helper factory ────────────────────────────────────────────────────────

    private static InferredFact fact(String atomKey, double confidence) {
        return new InferredFact(atomKey, confidence, confidence,
                List.of(), List.of(), "run-test", 1L, Instant.now());
    }

    private static InferredFactRow rowWithBasisType(String basisType) {
        InferredFactRow row = new InferredFactRow();
        row.setFactSheetId(1L);
        row.setAtomKey("dummy");
        row.setVersion(1L);
        row.setValue(0.7);
        row.setConfidence(0.7);
        row.setRunId("run-1");
        row.setProvenanceJson("{\"atomKey\":\"dummy\",\"_basisType\":\"" + basisType + "\"}");
        row.setInferredAt(Instant.now());
        return row;
    }

    @BeforeEach
    void setUp() {
        tracker = mock(FactPromotionTracker.class);
        repo    = mock(InferredFactRowRepository.class);
        controller = new KbOpinionBrowserController(tracker, repo);

        // Default behaviours
        when(tracker.getLastBand(anyLong(), anyString())).thenReturn(StrengthBand.PROBABLE);
        when(tracker.getPromotionStatus(anyLong(), anyString())).thenReturn("NONE");
        when(tracker.getCorroborationCount(anyLong(), anyString())).thenReturn(0);
        when(tracker.bandCounts(anyLong())).thenReturn(Map.of(StrengthBand.PROBABLE, 1));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Guard: no FactPromotionTracker
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Service unavailable")
    class ServiceUnavailable {

        @Test
        @DisplayName("returns 503 when FactPromotionTracker is null")
        void returns503WhenNoTracker() {
            KbOpinionBrowserController noTracker = new KbOpinionBrowserController(null, repo);
            ResponseEntity<?> resp = noTracker.getOpinions(1L, null, null, null, null, null, 100);
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
        }

        @Test
        @DisplayName("band-summary returns 503 when FactPromotionTracker is null")
        void bandSummaryReturns503WhenNoTracker() {
            KbOpinionBrowserController noTracker = new KbOpinionBrowserController(null, repo);
            ResponseEntity<?> resp = noTracker.getBandSummary(1L);
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Basic opinions endpoint
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /opinions")
    class GetOpinions {

        @Test
        @DisplayName("returns 200 with empty list when no facts exist")
        void emptyFactSheetReturnsEmptyList() {
            for (StrengthBand b : StrengthBand.values()) {
                when(tracker.factsByTierDurable(eq(42L), eq(b))).thenReturn(List.of());
            }
            ResponseEntity<?> resp = controller.getOpinions(42L, null, null, null, null, null, 200);
            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertInstanceOf(List.class, resp.getBody());
            assertTrue(((List<?>) resp.getBody()).isEmpty());
        }

        @Test
        @DisplayName("returns 400 for unknown tier name")
        void unknownTierReturns400() {
            ResponseEntity<?> resp = controller.getOpinions(1L, "BOGUS_TIER", null, null, null, null, 200);
            assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        }

        @Test
        @DisplayName("returns one row per fact with expected atomKey")
        void returnsOneRowPerFact() {
            when(tracker.factsByTierDurable(eq(1L), eq(StrengthBand.PROBABLE)))
                    .thenReturn(List.of(fact("worksAt(alice, acme)", 0.7)));
            for (StrengthBand b : StrengthBand.values()) {
                if (b != StrengthBand.PROBABLE) {
                    when(tracker.factsByTierDurable(eq(1L), eq(b))).thenReturn(List.of());
                }
            }
            when(repo.findTopByFactSheetIdAndAtomKeyOrderByVersionDesc(eq(1L), eq("worksAt(alice, acme)")))
                    .thenReturn(Optional.empty());

            ResponseEntity<?> resp = controller.getOpinions(1L, null, null, null, null, null, 200);
            assertEquals(HttpStatus.OK, resp.getStatusCode());
            @SuppressWarnings("unchecked")
            List<KbOpinionBrowserController.FactOpinionRow> rows =
                    (List<KbOpinionBrowserController.FactOpinionRow>) resp.getBody();
            assertNotNull(rows);
            assertEquals(1, rows.size());
            assertEquals("worksAt(alice, acme)", rows.get(0).atomKey());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. D4: basisType extraction
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("D4: basisType extraction and filter")
    class D4BasisType {

        @Test
        @DisplayName("basisType defaults to LLM_EXTRACTION when no DB row found")
        void basisTypeDefaultsToLlmExtractionWhenNoRow() {
            when(tracker.factsByTierDurable(eq(1L), eq(StrengthBand.PROBABLE)))
                    .thenReturn(List.of(fact("x:fact", 0.5)));
            for (StrengthBand b : StrengthBand.values()) {
                if (b != StrengthBand.PROBABLE) {
                    when(tracker.factsByTierDurable(eq(1L), eq(b))).thenReturn(List.of());
                }
            }
            when(repo.findTopByFactSheetIdAndAtomKeyOrderByVersionDesc(anyLong(), anyString()))
                    .thenReturn(Optional.empty());

            ResponseEntity<?> resp = controller.getOpinions(1L, null, null, null, null, null, 200);
            @SuppressWarnings("unchecked")
            List<KbOpinionBrowserController.FactOpinionRow> rows =
                    (List<KbOpinionBrowserController.FactOpinionRow>) resp.getBody();
            assertNotNull(rows);
            assertEquals(1, rows.size());
            assertEquals("LLM_EXTRACTION", rows.get(0).basisType());
        }

        @Test
        @DisplayName("basisType is read from provenanceJson _basisType key")
        void basisTypeReadFromProvenanceJson() {
            when(tracker.factsByTierDurable(eq(1L), eq(StrengthBand.PROBABLE)))
                    .thenReturn(List.of(fact("x:fact", 0.5)));
            for (StrengthBand b : StrengthBand.values()) {
                if (b != StrengthBand.PROBABLE) {
                    when(tracker.factsByTierDurable(eq(1L), eq(b))).thenReturn(List.of());
                }
            }
            InferredFactRow row = rowWithBasisType("STRUCTURAL");
            when(repo.findTopByFactSheetIdAndAtomKeyOrderByVersionDesc(anyLong(), anyString()))
                    .thenReturn(Optional.of(row));

            ResponseEntity<?> resp = controller.getOpinions(1L, null, null, null, null, null, 200);
            @SuppressWarnings("unchecked")
            List<KbOpinionBrowserController.FactOpinionRow> rows =
                    (List<KbOpinionBrowserController.FactOpinionRow>) resp.getBody();
            assertNotNull(rows);
            assertEquals(1, rows.size());
            assertEquals("STRUCTURAL", rows.get(0).basisType());
        }

        @Test
        @DisplayName("basisType PSL_INFERENCE is read correctly")
        void basisTypePslInferenceReadCorrectly() {
            when(tracker.factsByTierDurable(eq(1L), eq(StrengthBand.HIGH)))
                    .thenReturn(List.of(fact("psl:fact", 0.8)));
            for (StrengthBand b : StrengthBand.values()) {
                if (b != StrengthBand.HIGH) {
                    when(tracker.factsByTierDurable(eq(1L), eq(b))).thenReturn(List.of());
                }
            }
            InferredFactRow row = rowWithBasisType("PSL_INFERENCE");
            when(repo.findTopByFactSheetIdAndAtomKeyOrderByVersionDesc(anyLong(), anyString()))
                    .thenReturn(Optional.of(row));

            ResponseEntity<?> resp = controller.getOpinions(1L, null, null, null, null, null, 200);
            @SuppressWarnings("unchecked")
            List<KbOpinionBrowserController.FactOpinionRow> rows =
                    (List<KbOpinionBrowserController.FactOpinionRow>) resp.getBody();
            assertNotNull(rows);
            assertEquals("PSL_INFERENCE", rows.get(0).basisType());
        }

        @Test
        @DisplayName("basisType filter excludes non-matching rows")
        void basisTypeFilterExcludesNonMatching() {
            // Two facts with different basisTypes in the same band
            when(tracker.factsByTierDurable(eq(1L), eq(StrengthBand.PROBABLE)))
                    .thenReturn(List.of(
                            fact("structural:fact", 0.9),
                            fact("llm:fact", 0.7)));
            for (StrengthBand b : StrengthBand.values()) {
                if (b != StrengthBand.PROBABLE) {
                    when(tracker.factsByTierDurable(eq(1L), eq(b))).thenReturn(List.of());
                }
            }
            InferredFactRow structRow = rowWithBasisType("STRUCTURAL");
            InferredFactRow llmRow   = rowWithBasisType("LLM_EXTRACTION");
            when(repo.findTopByFactSheetIdAndAtomKeyOrderByVersionDesc(eq(1L), eq("structural:fact")))
                    .thenReturn(Optional.of(structRow));
            when(repo.findTopByFactSheetIdAndAtomKeyOrderByVersionDesc(eq(1L), eq("llm:fact")))
                    .thenReturn(Optional.of(llmRow));

            // Filter by STRUCTURAL only
            ResponseEntity<?> resp = controller.getOpinions(1L, null, null, null, null, "STRUCTURAL", 200);
            @SuppressWarnings("unchecked")
            List<KbOpinionBrowserController.FactOpinionRow> rows =
                    (List<KbOpinionBrowserController.FactOpinionRow>) resp.getBody();
            assertNotNull(rows);
            assertEquals(1, rows.size());
            assertEquals("structural:fact", rows.get(0).atomKey());
        }

        @Test
        @DisplayName("basisType filter null = all types pass")
        void basisTypeFilterNullPassesAll() {
            when(tracker.factsByTierDurable(eq(1L), eq(StrengthBand.PROBABLE)))
                    .thenReturn(List.of(
                            fact("structural:fact", 0.9),
                            fact("llm:fact", 0.7)));
            for (StrengthBand b : StrengthBand.values()) {
                if (b != StrengthBand.PROBABLE) {
                    when(tracker.factsByTierDurable(eq(1L), eq(b))).thenReturn(List.of());
                }
            }
            when(repo.findTopByFactSheetIdAndAtomKeyOrderByVersionDesc(eq(1L), eq("structural:fact")))
                    .thenReturn(Optional.of(rowWithBasisType("STRUCTURAL")));
            when(repo.findTopByFactSheetIdAndAtomKeyOrderByVersionDesc(eq(1L), eq("llm:fact")))
                    .thenReturn(Optional.of(rowWithBasisType("LLM_EXTRACTION")));

            ResponseEntity<?> resp = controller.getOpinions(1L, null, null, null, null, null, 200);
            @SuppressWarnings("unchecked")
            List<KbOpinionBrowserController.FactOpinionRow> rows =
                    (List<KbOpinionBrowserController.FactOpinionRow>) resp.getBody();
            assertNotNull(rows);
            assertEquals(2, rows.size());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. D5: Corpus-level text search (q param)
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("D5: Corpus text search (q param)")
    class D5CorpusSearch {

        @Test
        @DisplayName("q param matches on subject within atomKey")
        void qMatchesSubject() {
            when(tracker.factsByTierDurable(eq(1L), eq(StrengthBand.PROBABLE)))
                    .thenReturn(List.of(
                            fact("worksAt(alice, acme)", 0.8),
                            fact("worksAt(bob, globex)", 0.7)));
            for (StrengthBand b : StrengthBand.values()) {
                if (b != StrengthBand.PROBABLE) {
                    when(tracker.factsByTierDurable(eq(1L), eq(b))).thenReturn(List.of());
                }
            }
            when(repo.findTopByFactSheetIdAndAtomKeyOrderByVersionDesc(anyLong(), anyString()))
                    .thenReturn(Optional.empty());

            ResponseEntity<?> resp = controller.getOpinions(1L, null, "alice", null, null, null, 200);
            @SuppressWarnings("unchecked")
            List<KbOpinionBrowserController.FactOpinionRow> rows =
                    (List<KbOpinionBrowserController.FactOpinionRow>) resp.getBody();
            assertNotNull(rows);
            assertEquals(1, rows.size());
            assertTrue(rows.get(0).atomKey().contains("alice"));
        }

        @Test
        @DisplayName("q param matches on predicate within atomKey")
        void qMatchesPredicate() {
            when(tracker.factsByTierDurable(eq(1L), eq(StrengthBand.PROBABLE)))
                    .thenReturn(List.of(
                            fact("worksAt(alice, acme)", 0.8),
                            fact("likes(alice, pizza)", 0.5)));
            for (StrengthBand b : StrengthBand.values()) {
                if (b != StrengthBand.PROBABLE) {
                    when(tracker.factsByTierDurable(eq(1L), eq(b))).thenReturn(List.of());
                }
            }
            when(repo.findTopByFactSheetIdAndAtomKeyOrderByVersionDesc(anyLong(), anyString()))
                    .thenReturn(Optional.empty());

            ResponseEntity<?> resp = controller.getOpinions(1L, null, "likes", null, null, null, 200);
            @SuppressWarnings("unchecked")
            List<KbOpinionBrowserController.FactOpinionRow> rows =
                    (List<KbOpinionBrowserController.FactOpinionRow>) resp.getBody();
            assertNotNull(rows);
            assertEquals(1, rows.size());
            assertTrue(rows.get(0).atomKey().startsWith("likes"));
        }

        @Test
        @DisplayName("q null returns all rows")
        void qNullReturnsAll() {
            when(tracker.factsByTierDurable(eq(1L), eq(StrengthBand.PROBABLE)))
                    .thenReturn(List.of(
                            fact("worksAt(alice, acme)", 0.8),
                            fact("likes(alice, pizza)", 0.5)));
            for (StrengthBand b : StrengthBand.values()) {
                if (b != StrengthBand.PROBABLE) {
                    when(tracker.factsByTierDurable(eq(1L), eq(b))).thenReturn(List.of());
                }
            }
            when(repo.findTopByFactSheetIdAndAtomKeyOrderByVersionDesc(anyLong(), anyString()))
                    .thenReturn(Optional.empty());

            ResponseEntity<?> resp = controller.getOpinions(1L, null, null, null, null, null, 200);
            @SuppressWarnings("unchecked")
            List<KbOpinionBrowserController.FactOpinionRow> rows =
                    (List<KbOpinionBrowserController.FactOpinionRow>) resp.getBody();
            assertNotNull(rows);
            assertEquals(2, rows.size());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. extractStringValue unit tests
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("extractStringValue helper")
    class ExtractStringValue {

        @Test
        @DisplayName("returns value for present simple string key")
        void returnsValueForPresentKey() {
            String json = "{\"_basisType\":\"STRUCTURAL\",\"other\":\"x\"}";
            String result = KbOpinionBrowserController.extractStringValue(json, "\"_basisType\"");
            assertEquals("STRUCTURAL", result);
        }

        @Test
        @DisplayName("returns null for absent key")
        void returnsNullForAbsentKey() {
            String json = "{\"other\":\"value\"}";
            String result = KbOpinionBrowserController.extractStringValue(json, "\"_basisType\"");
            assertNull(result);
        }

        @Test
        @DisplayName("returns null for null json")
        void returnsNullForNullJson() {
            assertNull(KbOpinionBrowserController.extractStringValue(null, "\"_basisType\""));
        }

        @Test
        @DisplayName("returns null for empty json")
        void returnsNullForEmptyJson() {
            assertNull(KbOpinionBrowserController.extractStringValue("", "\"_basisType\""));
        }

        @Test
        @DisplayName("handles json with extra whitespace around value")
        void handlesWhitespaceAroundValue() {
            String json = "{\"_basisType\" :  \"PSL_INFERENCE\" , \"x\":1}";
            String result = KbOpinionBrowserController.extractStringValue(json, "\"_basisType\"");
            assertEquals("PSL_INFERENCE", result);
        }

        @Test
        @DisplayName("handles escaped backslash in value")
        void handlesEscapedBackslash() {
            String json = "{\"_basisType\":\"LLM_EXTRACTION\"}";
            assertEquals("LLM_EXTRACTION",
                    KbOpinionBrowserController.extractStringValue(json, "\"_basisType\""));
        }

        @Test
        @DisplayName("returns null when value is not a string (number)")
        void returnsNullWhenValueIsNumber() {
            String json = "{\"_basisType\":42}";
            assertNull(KbOpinionBrowserController.extractStringValue(json, "\"_basisType\""));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. FactOpinionRow record includes basisType field
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("FactOpinionRow record includes basisType field")
    void factOpinionRowHasBasisTypeField() {
        KbOpinionBrowserController.FactOpinionRow row = new KbOpinionBrowserController.FactOpinionRow(
                "test:atom", 0.5, "PROBABLE", "NONE", 0,
                0.6, 0.2, 0.2, 0.7, 0.5,
                "PSL_INFERENCE");
        assertEquals("test:atom", row.atomKey());
        assertEquals("PSL_INFERENCE", row.basisType());
    }
}
