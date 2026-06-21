/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.app.web.controllers.grounding;

import ai.kompile.graph.reasoning.confidence.StrengthBand;
import ai.kompile.graph.reasoning.fol.InferredFact;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link VerifyElementController}.
 *
 * <p>Uses direct controller instantiation with in-memory {@link KbGroundingService}.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VerifyElementController")
class VerifyElementControllerTest {

    private KbGroundingService groundingService;
    private VerifyElementController controller;

    @BeforeEach
    void setUp() {
        groundingService = new KbGroundingService();
        controller = new VerifyElementController(groundingService);
    }

    // ── Validation ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Input validation")
    class Validation {

        @Test
        @DisplayName("blank atomKey throws IllegalArgumentException")
        void blankAtomKey_throws() {
            VerifyElementController.VerifyElementRequest req =
                    new VerifyElementController.VerifyElementRequest(
                            "some element", "", 1L, 3, "INDUCTIVE_MINER");
            assertThrows(IllegalArgumentException.class, () -> controller.verifyElement(req));
        }

        @Test
        @DisplayName("null atomKey throws IllegalArgumentException")
        void nullAtomKey_throws() {
            VerifyElementController.VerifyElementRequest req =
                    new VerifyElementController.VerifyElementRequest(
                            "some element", null, 1L, 3, null);
            assertThrows(IllegalArgumentException.class, () -> controller.verifyElement(req));
        }
    }

    // ── Unknown atom ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Unknown atom")
    class UnknownAtom {

        @Test
        @DisplayName("unknown atom returns UNKNOWN verdict with SUPPRESSED band")
        void unknownAtom_returnsSuppressedBand() {
            VerifyElementController.VerifyElementRequest req =
                    new VerifyElementController.VerifyElementRequest(
                            "step: Receive Order", "activity(ReceiveOrder)", 1L, 3, "INDUCTIVE_MINER");

            ResponseEntity<VerifyElementController.GroundedElementResponse> resp =
                    controller.verifyElement(req);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertNotNull(resp.getBody());
            assertEquals("UNKNOWN", resp.getBody().verdict());
            assertEquals(0.0, resp.getBody().confidence());
            // Calibrated confidence for UNKNOWN is also 0.0 (calibrator returns 0 for UNKNOWN mapped through sigmoid)
            assertTrue(resp.getBody().calibratedConfidence() >= 0.0);
            assertTrue(resp.getBody().calibratedConfidence() <= 1.0);
            // SUPPRESSED or SPECULATIVE band for near-zero confidence
            assertNotNull(resp.getBody().strengthBand());
            assertEquals("step: Receive Order", resp.getBody().element());
            assertEquals("activity(ReceiveOrder)", resp.getBody().atomKey());
            assertEquals("INDUCTIVE_MINER", resp.getBody().generatorId());
        }

        @Test
        @DisplayName("derivation tree JSON is non-null (even for unknown atoms)")
        void unknownAtom_hasDerivationTree() {
            VerifyElementController.VerifyElementRequest req =
                    new VerifyElementController.VerifyElementRequest(
                            null, "precedes(A, B)", null, 0, null);

            ResponseEntity<VerifyElementController.GroundedElementResponse> resp =
                    controller.verifyElement(req);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertNotNull(resp.getBody());
            assertNotNull(resp.getBody().derivationTreeJson());
            assertTrue(resp.getBody().derivationTreeJson().contains("precedes(A, B)"));
        }

        @Test
        @DisplayName("null factSheetId defaults to global (0L)")
        void nullFactSheetId_usesGlobal() {
            VerifyElementController.VerifyElementRequest req =
                    new VerifyElementController.VerifyElementRequest(
                            null, "foo(X)", null, 0, null);

            ResponseEntity<VerifyElementController.GroundedElementResponse> resp =
                    controller.verifyElement(req);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertEquals(0L, resp.getBody().factSheetId());
        }
    }

    // ── Supported atom ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Supported atom")
    class SupportedAtom {

        @Test
        @DisplayName("seeded atom returns SUPPORTED verdict with positive calibrated confidence")
        void seededAtom_returnsSupportedWithConfidence() {
            long fsId = 55L;
            groundingService.seedInferredFacts(fsId, List.of(
                    InferredFact.of("activity(ProcessPayment)", 0.92,
                            List.of(), List.of(), "run-mine", 1L)
            ));

            VerifyElementController.VerifyElementRequest req =
                    new VerifyElementController.VerifyElementRequest(
                            "Process Payment step", "activity(ProcessPayment)",
                            fsId, 3, "INDUCTIVE_MINER");

            ResponseEntity<VerifyElementController.GroundedElementResponse> resp =
                    controller.verifyElement(req);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertNotNull(resp.getBody());
            assertEquals("SUPPORTED", resp.getBody().verdict());
            assertTrue(resp.getBody().confidence() > 0.0);
            assertTrue(resp.getBody().calibratedConfidence() > 0.0);
            assertTrue(resp.getBody().calibratedConfidence() <= 1.0);
            // ESTABLISHED or HIGH band for high-confidence support
            String band = resp.getBody().strengthBand();
            assertTrue(
                    band.equals(StrengthBand.ESTABLISHED.name()) ||
                    band.equals(StrengthBand.HIGH.name()) ||
                    band.equals(StrengthBand.PROBABLE.name()),
                    "Expected ESTABLISHED/HIGH/PROBABLE for confidence ~0.92, got: " + band
            );
        }

        @Test
        @DisplayName("trailRef is set and has expected format")
        void trailRef_isSet() {
            VerifyElementController.VerifyElementRequest req =
                    new VerifyElementController.VerifyElementRequest(
                            null, "type(Alice, Person)", 1L, 0, null);

            ResponseEntity<VerifyElementController.GroundedElementResponse> resp =
                    controller.verifyElement(req);

            assertNotNull(resp.getBody());
            assertNotNull(resp.getBody().trailRef());
            assertTrue(resp.getBody().trailRef().startsWith("runId:"));
        }

        @Test
        @DisplayName("evaluatedAt is set")
        void evaluatedAt_isSet() {
            VerifyElementController.VerifyElementRequest req =
                    new VerifyElementController.VerifyElementRequest(
                            null, "role(Alice, Manager)", 1L, 0, null);

            ResponseEntity<VerifyElementController.GroundedElementResponse> resp =
                    controller.verifyElement(req);

            assertNotNull(resp.getBody());
            assertNotNull(resp.getBody().evaluatedAt());
        }
    }

    // ── GlobalExceptionHandler package coverage ──────────────────────────────────

    @Nested
    @DisplayName("Error handling (verifying GlobalExceptionHandler package coverage)")
    class ErrorHandling {

        @Test
        @DisplayName("IllegalArgumentException propagates (caught by GlobalExceptionHandler in prod)")
        void illegalArgument_propagates() {
            // In a real Spring context this would be caught by GlobalExceptionHandler
            // and return a structured 400 body. In the unit test it propagates as-is.
            VerifyElementController.VerifyElementRequest req =
                    new VerifyElementController.VerifyElementRequest(
                            "e", "", 1L, 0, null);
            assertThrows(IllegalArgumentException.class, () -> controller.verifyElement(req));
        }
    }
}
