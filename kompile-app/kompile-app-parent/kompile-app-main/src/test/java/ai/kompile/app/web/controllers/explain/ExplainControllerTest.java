/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.app.web.controllers.explain;

import ai.kompile.event.attribution.service.EventAttributionService;
import ai.kompile.event.attribution.service.PslReasoningService;
import ai.kompile.event.attribution.service.BayesianNetworkService;
import ai.kompile.graph.reasoning.domain.AttributionResult;
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.fol.grounding.VerifyResult;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ExplainController} and its {@link ExplainOrchestrator}.
 *
 * <p>Uses direct controller instantiation (no Spring context) with a real
 * {@link KbGroundingService} backed by in-memory stores. The
 * {@link KnowledgeGraphService} store is mocked.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExplainController")
class ExplainControllerTest {

    @Mock
    private KnowledgeGraphService kgService;

    @Mock
    private EventAttributionService attributionService;

    @Mock
    private PslReasoningService pslService;

    @Mock
    private BayesianNetworkService bayesianService;

    private KbGroundingService groundingService;
    private ExplainOrchestrator orchestrator;
    private ExplainController controller;

    @BeforeEach
    void setUp() {
        groundingService = new KbGroundingService();
        // Default: empty attribution result (no chains) — overridden per causal tests where needed.
        // lenient() prevents UnnecessaryStubbingException in non-causal tests.
        lenient().when(attributionService.explain(any())).thenReturn(
                AttributionResult.builder()
                        .targetNodeId("default")
                        .computedAt(Instant.now())
                        .build());
        orchestrator = new ExplainOrchestrator(groundingService, kgService, attributionService, pslService, bayesianService);
        controller = new ExplainController(orchestrator, groundingService);
    }

    // ── Validation ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Input validation")
    class Validation {

        @Test
        @DisplayName("blank target throws IllegalArgumentException")
        void blankTarget_throws() {
            ExplainRequest req = new ExplainRequest("", 1L, 3, null, null);
            assertThrows(IllegalArgumentException.class, () -> controller.explain(req));
        }

        @Test
        @DisplayName("null target throws IllegalArgumentException")
        void nullTarget_throws() {
            ExplainRequest req = new ExplainRequest(null, 1L, 3, null, null);
            assertThrows(IllegalArgumentException.class, () -> controller.explain(req));
        }
    }

    // ── GROUNDING route ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("AUTO-route: atom key → GROUNDING")
    class GroundingRoute {

        @Test
        @DisplayName("unknown atom routes to GROUNDING, returns UNKNOWN verdict")
        void atomKey_unknownAtom_returnsUnknown() {
            ExplainRequest req = new ExplainRequest(
                    "isEmployedBy(Alice, Acme)", 42L, 3, null, "sess-1");

            ResponseEntity<ExplainResponse> resp = controller.explain(req);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertNotNull(resp.getBody());
            assertEquals("GROUNDING", resp.getBody().inferenceMode());
            assertEquals("UNKNOWN", resp.getBody().verdict());
            assertEquals(0.0, resp.getBody().confidence());
            assertNotNull(resp.getBody().naturalLanguageSummary());
            assertTrue(resp.getBody().naturalLanguageSummary().contains("not derivable"));
            assertNotNull(resp.getBody().trail());
            assertEquals("isEmployedBy(Alice, Acme)", resp.getBody().trail().targetId());
        }

        @Test
        @DisplayName("seeded atom routes to GROUNDING, returns SUPPORTED verdict")
        void atomKey_seededAtom_returnsSupported() {
            // Seed an inferred fact so the verifier finds it
            groundingService.seedInferredFacts(42L, List.of(
                    InferredFact.of("worksAt(Bob, Corp)", 0.85,
                            List.of(), List.of(), "run-001", 1L)
            ));

            ExplainRequest req = new ExplainRequest(
                    "worksAt(Bob, Corp)", 42L, 3, null, null);

            ResponseEntity<ExplainResponse> resp = controller.explain(req);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertNotNull(resp.getBody());
            assertEquals("GROUNDING", resp.getBody().inferenceMode());
            assertEquals("SUPPORTED", resp.getBody().verdict());
            assertTrue(resp.getBody().confidence() > 0.0);
            assertNotNull(resp.getBody().derivationTreeJson());
            assertTrue(resp.getBody().derivationTreeJson().contains("worksAt"));
        }

        @Test
        @DisplayName("null factSheetId defaults to 0 (global)")
        void nullFactSheetId_usesGlobal() {
            ExplainRequest req = new ExplainRequest(
                    "foo(X)", null, 0, null, null);

            ResponseEntity<ExplainResponse> resp = controller.explain(req);
            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertEquals("GROUNDING", resp.getBody().inferenceMode());
        }

        @Test
        @DisplayName("explicit mode=GROUNDING overrides entity-id auto-detection")
        void explicitGroundingMode_overridesAutoDetect() {
            // Target has no parentheses — would auto-route to HYBRID — but mode=GROUNDING forces it
            ExplainRequest req = new ExplainRequest(
                    "someEntityId", 1L, 3, "GROUNDING", null);

            ResponseEntity<ExplainResponse> resp = controller.explain(req);
            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertEquals("GROUNDING", resp.getBody().inferenceMode());
        }
    }

    // ── CAUSAL route ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("AUTO-route: causal: prefix → CAUSAL")
    class CausalRoute {

        @Test
        @DisplayName("causal: prefix routes to CAUSAL, returns populated trail (not a stub)")
        void causalPrefix_returnsCausalTrail_populated() {
            // Attribution service returns a non-empty result — trail must NOT be the old stub
            when(attributionService.explain(any())).thenReturn(
                    AttributionResult.builder()
                            .targetNodeId("PaymentFailure")
                            .targetTitle("Payment Failure")
                            .computedAt(Instant.now())
                            .build());

            ExplainRequest req = new ExplainRequest(
                    "causal:PaymentFailure", 1L, 0, null, null);

            ResponseEntity<ExplainResponse> resp = controller.explain(req);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertNotNull(resp.getBody());
            assertEquals("CAUSAL", resp.getBody().inferenceMode());
            // Target id must be the bare "PaymentFailure" (causal: prefix stripped)
            assertNotNull(resp.getBody().trail());
            assertEquals("PaymentFailure", resp.getBody().trail().targetId());
            // Natural language summary must mention the target — must NOT contain the old banner text
            assertNotNull(resp.getBody().naturalLanguageSummary());
            assertTrue(resp.getBody().naturalLanguageSummary().contains("PaymentFailure"),
                    "Summary should contain the target name");
            assertFalse(resp.getBody().naturalLanguageSummary().contains("dedicated attribution endpoints"),
                    "Stub banner text must be gone");
        }

        @Test
        @DisplayName("causal: target with no chains returns 0.0 confidence and descriptive summary")
        void causalPrefix_noChains_zeroConfidence() {
            // Default mock already returns empty AttributionResult (set up in @BeforeEach)
            ExplainRequest req = new ExplainRequest(
                    "causal:UnknownEvent", 1L, 0, null, null);

            ResponseEntity<ExplainResponse> resp = controller.explain(req);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertEquals("CAUSAL", resp.getBody().inferenceMode());
            assertEquals(0.0, resp.getBody().confidence(), 1e-9);
            assertTrue(resp.getBody().naturalLanguageSummary().contains("UnknownEvent"));
        }

        @Test
        @DisplayName("explicit mode=CAUSAL overrides auto-detection")
        void explicitCausalMode() {
            ExplainRequest req = new ExplainRequest(
                    "someTarget", 1L, 0, "CAUSAL", null);

            ResponseEntity<ExplainResponse> resp = controller.explain(req);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertEquals("CAUSAL", resp.getBody().inferenceMode());
        }
    }

    // ── HYBRID route ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("AUTO-route: entity id → HYBRID")
    class HybridRoute {

        @Test
        @DisplayName("bare entity id routes to HYBRID")
        void bareEntityId_routesToHybrid() {
            // No parentheses, no causal: prefix → HYBRID
            ExplainRequest req = new ExplainRequest(
                    "entity-node-123", 1L, 0, null, null);

            ResponseEntity<ExplainResponse> resp = controller.explain(req);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertNotNull(resp.getBody());
            assertEquals("HYBRID", resp.getBody().inferenceMode());
            // Score is 0.0 when the entity is not in an empty KG subgraph
            assertEquals(0.0, resp.getBody().confidence());
            assertNull(resp.getBody().verdict()); // no verdict in HYBRID mode
        }
    }

    // ── Response structure ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Response structure")
    class ResponseStructure {

        @Test
        @DisplayName("trail is always non-null")
        void trail_isAlwaysNonNull() {
            ExplainRequest req = new ExplainRequest("foo(X)", 1L, 0, null, null);
            ResponseEntity<ExplainResponse> resp = controller.explain(req);
            assertNotNull(resp.getBody());
            assertNotNull(resp.getBody().trail());
        }

        @Test
        @DisplayName("computedAt is always set")
        void computedAt_isSet() {
            ExplainRequest req = new ExplainRequest("bar(Y)", 1L, 0, null, null);
            ResponseEntity<ExplainResponse> resp = controller.explain(req);
            assertNotNull(resp.getBody());
            assertNotNull(resp.getBody().computedAt());
        }
    }
}
