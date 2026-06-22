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

import ai.kompile.knowledgegraph.audit.FactAuditEvent;
import ai.kompile.knowledgegraph.audit.PinGuard;
import ai.kompile.knowledgegraph.audit.PinRecord;
import ai.kompile.knowledgegraph.grounding.KbCorrectionService;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link KbGroundingAuditController}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>GET /api/kb-grounding/{factSheetId}/audit is reachable and returns HTTP 200 + a list</li>
 *   <li>POST /api/kb-grounding/{factSheetId}/corrections records a correction and creates
 *       an audit event</li>
 *   <li>DELETE /api/kb-grounding/{factSheetId}/corrections/{atomKey} reverts the pin</li>
 *   <li>GET /api/kb-grounding/{factSheetId}/corrections returns active pins</li>
 * </ul>
 *
 * <p>Uses a real {@link KbCorrectionService} backed by real in-memory stores —
 * no Spring context, no mocks, proving the endpoints compile and the service wiring is correct.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("KbGroundingAuditController")
class KbGroundingAuditControllerTest {

    private KbGroundingAuditController controller;
    private KbCorrectionService correctionService;

    @BeforeEach
    void setUp() {
        KbGroundingService groundingService = new KbGroundingService();
        // PinGuard() no-arg ctor uses in-memory pin stores (suitable for unit tests)
        correctionService = new KbCorrectionService(groundingService, new PinGuard(), null);
        controller = new KbGroundingAuditController(correctionService);
    }

    // ── GET /audit ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /audit")
    class GetAudit {

        @Test
        @DisplayName("empty fact sheet returns 200 with empty list")
        void emptyFactSheet_returns200EmptyList() {
            ResponseEntity<List<FactAuditEvent>> resp = controller.getAuditTrail(42L, null, null, 200);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertNotNull(resp.getBody());
            assertTrue(resp.getBody().isEmpty(), "Audit trail should be empty before any corrections");
        }

        @Test
        @DisplayName("after correction, audit trail contains at least one CORRECTED event")
        void afterCorrection_auditContainsCorrectedEvent() {
            // Submit a correction first
            KbGroundingAuditController.CorrectionRequest req =
                    new KbGroundingAuditController.CorrectionRequest(
                            "revenue(Q1)", 0.9, "analyst review", false, "HUMAN:alice", "sess-1");
            controller.postCorrection(42L, req);

            ResponseEntity<List<FactAuditEvent>> resp = controller.getAuditTrail(42L, null, null, 200);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertNotNull(resp.getBody());
            assertFalse(resp.getBody().isEmpty(), "Audit trail should contain at least one event after correction");
            assertTrue(resp.getBody().stream()
                    .anyMatch(ev -> "CORRECTED".equals(ev.eventType())),
                    "Audit trail should contain a CORRECTED event");
        }

        @Test
        @DisplayName("atomKey filter scopes events to that atom")
        void atomKeyFilter_scopesResults() {
            // Two corrections on different atoms
            controller.postCorrection(10L, new KbGroundingAuditController.CorrectionRequest(
                    "alpha(x)", 0.7, null, false, "HUMAN:bob", null));
            controller.postCorrection(10L, new KbGroundingAuditController.CorrectionRequest(
                    "beta(y)", 0.5, null, false, "HUMAN:bob", null));

            ResponseEntity<List<FactAuditEvent>> resp =
                    controller.getAuditTrail(10L, "alpha(x)", null, 200);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertTrue(resp.getBody().stream()
                    .allMatch(ev -> "alpha(x)".equals(ev.atomKey())),
                    "All returned events should be for alpha(x)");
        }
    }

    // ── POST /corrections ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /corrections")
    class PostCorrections {

        @Test
        @DisplayName("valid correction returns 200 with CorrectionResult")
        void validCorrection_returns200() {
            KbGroundingAuditController.CorrectionRequest req =
                    new KbGroundingAuditController.CorrectionRequest(
                            "isActive(Node42)", 1.0, "confirmed active", false, "HUMAN:alice", null);

            ResponseEntity<KbCorrectionService.CorrectionResult> resp =
                    controller.postCorrection(1L, req);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertNotNull(resp.getBody());
        }

        @Test
        @DisplayName("blank atomKey returns 400")
        void blankAtomKey_returns400() {
            KbGroundingAuditController.CorrectionRequest req =
                    new KbGroundingAuditController.CorrectionRequest(
                            "  ", 0.5, null, false, null, null);

            ResponseEntity<KbCorrectionService.CorrectionResult> resp =
                    controller.postCorrection(1L, req);

            assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        }
    }

    // ── DELETE /corrections/{atomKey} ────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /corrections/{atomKey}")
    class DeleteCorrection {

        @Test
        @DisplayName("revert returns 200 with status=reverted")
        void revert_returns200() {
            // Pin it first
            controller.postCorrection(5L, new KbGroundingAuditController.CorrectionRequest(
                    "foo(bar)", 0.8, null, false, "HUMAN:carol", null));

            ResponseEntity<Map<String, String>> resp =
                    controller.revertPin(5L, "foo(bar)", "HUMAN:carol");

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertNotNull(resp.getBody());
            assertEquals("reverted", resp.getBody().get("status"));
            assertEquals("foo(bar)", resp.getBody().get("atomKey"));
        }
    }

    // ── GET /corrections ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /corrections")
    class GetCorrections {

        @Test
        @DisplayName("returns 200 with empty list when no pins")
        void noCorrections_returnsEmptyList() {
            ResponseEntity<List<PinRecord>> resp = controller.getActivePins(99L);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertNotNull(resp.getBody());
        }

        @Test
        @DisplayName("after correction, active pins list is non-empty")
        void afterCorrection_pinsNonEmpty() {
            controller.postCorrection(7L, new KbGroundingAuditController.CorrectionRequest(
                    "pinned(atom)", 0.95, "pinning this", false, "HUMAN:dave", "sess-2"));

            ResponseEntity<List<PinRecord>> resp = controller.getActivePins(7L);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertFalse(resp.getBody().isEmpty(), "Should have at least one active pin");
        }
    }

    // ── GET /facts — temporal filter (D7) + FactTierRow shape ────────────────────

    @Nested
    @DisplayName("GET /facts — temporal filter and FactTierRow fields")
    class GetFacts {

        @Test
        @DisplayName("FactTierRow has validFrom and validTo fields")
        void factTierRow_hasTemporalFields() throws Exception {
            // Verify the record components exist with the expected names
            Class<KbGroundingAuditController.FactTierRow> cls =
                    KbGroundingAuditController.FactTierRow.class;
            // Record components: atomKey, confidence, band, promotionStatus, corroborationCount, validFrom, validTo
            assertNotNull(cls.getDeclaredMethod("validFrom"), "validFrom accessor must exist");
            assertNotNull(cls.getDeclaredMethod("validTo"),   "validTo accessor must exist");
        }

        @Test
        @DisplayName("FactTierRow can be constructed with null temporal fields")
        void factTierRow_nullableTemporalFields() {
            KbGroundingAuditController.FactTierRow row =
                    new KbGroundingAuditController.FactTierRow(
                            "revenue(Q1)", 0.8, "HIGH", "NONE", 2, null, null);
            assertNull(row.validFrom(),  "validFrom should accept null");
            assertNull(row.validTo(),    "validTo should accept null");
            assertEquals("revenue(Q1)", row.atomKey());
            assertEquals(0.8, row.confidence(), 0.001);
        }

        @Test
        @DisplayName("FactTierRow captures validFrom and validTo when supplied")
        void factTierRow_temporalFieldsRoundTrip() {
            long from = 1_700_000_000_000L;
            long to   = 1_700_100_000_000L;
            KbGroundingAuditController.FactTierRow row =
                    new KbGroundingAuditController.FactTierRow(
                            "active(Node7)", 0.95, "ESTABLISHED", "PROMOTED", 5, from, to);
            assertEquals(from, row.validFrom());
            assertEquals(to,   row.validTo());
        }

        @Test
        @DisplayName("matchesTemporalFilter: no filter accepts all rows")
        void temporalFilter_noFilter_acceptsAll() throws Exception {
            Method m = KbGroundingAuditController.class.getDeclaredMethod(
                    "matchesTemporalFilter", Long.class, Long.class, Long.class, Long.class, boolean.class);
            m.setAccessible(true);

            // (rowFrom, rowTo, filterFrom, filterTo, excludeUndated)
            assertTrue((Boolean) m.invoke(null, null,  null, null, null, false), "nulls + no filter");
            assertTrue((Boolean) m.invoke(null, 1000L, 2000L, null, null, false), "dated row + no filter");
        }

        @Test
        @DisplayName("matchesTemporalFilter: null temporal row passes unless excludeUndated=true")
        void temporalFilter_undatedRow_behavior() throws Exception {
            Method m = KbGroundingAuditController.class.getDeclaredMethod(
                    "matchesTemporalFilter", Long.class, Long.class, Long.class, Long.class, boolean.class);
            m.setAccessible(true);

            long from = 1_000L;
            long to   = 2_000L;
            // Undated row with filter active
            assertTrue((Boolean) m.invoke(null, null, null, from, to, false),
                    "Undated row should pass when excludeUndated=false");
            assertFalse((Boolean) m.invoke(null, null, null, from, to, true),
                    "Undated row should be excluded when excludeUndated=true");
        }

        @Test
        @DisplayName("matchesTemporalFilter: row starts after filter end → excluded")
        void temporalFilter_rowAfterFilterEnd_excluded() throws Exception {
            Method m = KbGroundingAuditController.class.getDeclaredMethod(
                    "matchesTemporalFilter", Long.class, Long.class, Long.class, Long.class, boolean.class);
            m.setAccessible(true);

            // row: [5000, 6000], filter: [null, 3000] — row starts after filter ends
            assertFalse((Boolean) m.invoke(null, 5000L, 6000L, null, 3000L, false));
        }

        @Test
        @DisplayName("matchesTemporalFilter: row ends before filter start → excluded")
        void temporalFilter_rowBeforeFilterStart_excluded() throws Exception {
            Method m = KbGroundingAuditController.class.getDeclaredMethod(
                    "matchesTemporalFilter", Long.class, Long.class, Long.class, Long.class, boolean.class);
            m.setAccessible(true);

            // row: [1000, 2000], filter: [3000, null] — row ends before filter starts
            assertFalse((Boolean) m.invoke(null, 1000L, 2000L, 3000L, null, false));
        }

        @Test
        @DisplayName("matchesTemporalFilter: overlapping windows pass")
        void temporalFilter_overlappingWindows_pass() throws Exception {
            Method m = KbGroundingAuditController.class.getDeclaredMethod(
                    "matchesTemporalFilter", Long.class, Long.class, Long.class, Long.class, boolean.class);
            m.setAccessible(true);

            // row: [1000, 4000], filter: [2000, 5000] — overlap [2000, 4000]
            assertTrue((Boolean) m.invoke(null, 1000L, 4000L, 2000L, 5000L, false));
        }

        @Test
        @DisplayName("matchesTemporalFilter: unbounded validTo (null) overlaps any filter")
        void temporalFilter_unboundedValidTo_alwaysOverlaps() throws Exception {
            Method m = KbGroundingAuditController.class.getDeclaredMethod(
                    "matchesTemporalFilter", Long.class, Long.class, Long.class, Long.class, boolean.class);
            m.setAccessible(true);

            // row validTo=null (still valid), filter ends at 9999 — should pass
            assertTrue((Boolean) m.invoke(null, 1000L, null, 5000L, 9999L, false),
                    "row with null validTo (unbounded) should overlap any filter that starts after validFrom");
        }

        @Test
        @DisplayName("extractValidTo: returns null when provenanceJson has no _validTo key")
        void extractValidTo_missingKey_returnsNull() throws Exception {
            Method m = KbGroundingAuditController.class.getDeclaredMethod(
                    "extractValidTo", ai.kompile.knowledgegraph.persistence.dual.InferredFactRow.class);
            m.setAccessible(true);

            // null row
            assertNull(m.invoke(null, (Object) null));

            // Row with no _validTo in JSON
            ai.kompile.knowledgegraph.persistence.dual.InferredFactRow row =
                    new ai.kompile.knowledgegraph.persistence.dual.InferredFactRow();
            row.setProvenanceJson("{\"atomKey\":\"foo\",\"value\":0.9}");
            assertNull(m.invoke(null, row));
        }

        @Test
        @DisplayName("extractValidTo: parses _validTo epoch millis from provenanceJson")
        void extractValidTo_parsesEpochMillis() throws Exception {
            Method m = KbGroundingAuditController.class.getDeclaredMethod(
                    "extractValidTo", ai.kompile.knowledgegraph.persistence.dual.InferredFactRow.class);
            m.setAccessible(true);

            ai.kompile.knowledgegraph.persistence.dual.InferredFactRow row =
                    new ai.kompile.knowledgegraph.persistence.dual.InferredFactRow();
            row.setProvenanceJson("{\"atomKey\":\"foo\",\"_validTo\": 1700100000000}");
            assertEquals(1700100000000L, m.invoke(null, row));
        }

        @Test
        @DisplayName("GET /facts returns SERVICE_UNAVAILABLE when promotionTracker is null")
        void getFactsByTier_noTracker_returns503() {
            // Controller with no tracker (tests and minimal contexts)
            ResponseEntity<?> resp = controller.getFactsByTier(1L, null, null, null, false);
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
        }

        @Test
        @DisplayName("GET /facts returns 400 for unknown tier value")
        void getFactsByTier_unknownTier_returns400() {
            // controller built with no tracker; 400 comes from band parsing before tracker check
            // Use a fresh controller that does have a tracker but receives bad tier
            // Since tracker-less controller returns 503, test the validation path via a dedicated
            // instance wired with a tracker
            var tracker = new ai.kompile.knowledgegraph.reasoning.FactPromotionTracker();
            var ctrl    = new KbGroundingAuditController(correctionService, tracker, null);
            ResponseEntity<?> resp = ctrl.getFactsByTier(1L, "BANANA", null, null, false);
            assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        }
    }
}
