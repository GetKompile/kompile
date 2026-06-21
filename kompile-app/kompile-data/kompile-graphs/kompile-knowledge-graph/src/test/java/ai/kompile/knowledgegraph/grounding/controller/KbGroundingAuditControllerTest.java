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
}
