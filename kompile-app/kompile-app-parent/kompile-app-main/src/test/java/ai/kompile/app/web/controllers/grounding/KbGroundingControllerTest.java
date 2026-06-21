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

import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.fol.grounding.ConjunctiveQueryEngine;
import ai.kompile.graph.reasoning.fol.grounding.VerifyResult;
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
 * Unit tests for {@link KbGroundingController}.
 *
 * <p>Uses direct controller instantiation (no Spring context) with a real
 * {@link KbGroundingService} backed by in-memory stores — identical to how
 * {@code GraphRagControllerTest} is structured.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("KbGroundingController")
class KbGroundingControllerTest {

    private KbGroundingService groundingService;
    private KbGroundingController controller;

    @BeforeEach
    void setUp() {
        groundingService = new KbGroundingService();
        controller = new KbGroundingController(groundingService);
    }

    // ── POST /verify ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /verify")
    class Verify {

        @Test
        @DisplayName("blank atom returns 400")
        void blankAtom_throws() {
            VerifyRequest req = new VerifyRequest("", 1L, null, null, null);
            assertThrows(IllegalArgumentException.class, () -> controller.verify(req));
        }

        @Test
        @DisplayName("unknown atom returns UNKNOWN verdict with confidence 0")
        void unknownAtom_returnsUnknown() {
            VerifyRequest req = new VerifyRequest("isEmployedBy(Alice, Acme)", 42L, null, null, "sess-1");

            ResponseEntity<VerifyResponse> resp = controller.verify(req);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertNotNull(resp.getBody());
            assertEquals("UNKNOWN", resp.getBody().verdict());
            assertEquals(0.0, resp.getBody().confidence());
            assertNotNull(resp.getBody().meta());
            assertEquals("sess-1", resp.getBody().meta().sessionId());
            assertEquals(42L, resp.getBody().meta().factSheetId());
        }

        @Test
        @DisplayName("asserted fact returns SUPPORTED verdict")
        void assertedFact_returnsSupported() {
            // Seed a fact into the service
            Fact fact = Fact.observed("isEmployedBy(Alice, Acme)", "test-source");
            groundingService.assertFact(42L, fact);

            // Also seed an InferredFact so the verifier can find it in the InferredFactStore
            groundingService.seedInferredFacts(42L, List.of(
                    InferredFact.of("isEmployedBy(Alice, Acme)", 0.9,
                            List.of(), List.of(), "test-run", 1L)
            ));

            VerifyRequest req = new VerifyRequest("isEmployedBy(Alice, Acme)", 42L, null, null, null);
            ResponseEntity<VerifyResponse> resp = controller.verify(req);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertNotNull(resp.getBody());
            assertEquals("SUPPORTED", resp.getBody().verdict());
            assertTrue(resp.getBody().confidence() > 0.0);
            assertNotNull(resp.getBody().meta());
            assertEquals(42L, resp.getBody().meta().factSheetId());
        }

        @Test
        @DisplayName("null factSheetId defaults to 0 (global)")
        void nullFactSheetId_usesDefault() {
            VerifyRequest req = new VerifyRequest("foo(X)", null, null, null, null);
            ResponseEntity<VerifyResponse> resp = controller.verify(req);
            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertEquals(0L, resp.getBody().meta().factSheetId());
        }
    }

    // ── POST /query ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /query")
    class Query {

        @Test
        @DisplayName("empty conjuncts returns 400")
        void emptyConjuncts_throws() {
            QueryRequest req = new QueryRequest(List.of(), null, null, 10, 0.0, null);
            assertThrows(IllegalArgumentException.class, () -> controller.query(req));
        }

        @Test
        @DisplayName("maxResults > 1000 returns 400")
        void maxResultsTooLarge_throws() {
            QueryRequest req = new QueryRequest(
                    List.of(new QueryRequest.ConjunctEntry("worksFor", List.of("?X", "Acme"))),
                    null, null, 1001, 0.0, null
            );
            assertThrows(IllegalArgumentException.class, () -> controller.query(req));
        }

        @Test
        @DisplayName("query with no matching facts returns empty bindings")
        void noMatch_returnsEmpty() {
            QueryRequest req = new QueryRequest(
                    List.of(new QueryRequest.ConjunctEntry("worksFor", List.of("?Person", "Acme"))),
                    5L, null, 10, 0.0, null
            );
            ResponseEntity<QueryResponse> resp = controller.query(req);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertNotNull(resp.getBody());
            assertEquals(0, resp.getBody().total());
            assertFalse(resp.getBody().truncated());
            assertNotNull(resp.getBody().meta());
        }
    }

    // ── POST /explain ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /explain")
    class Explain {

        @Test
        @DisplayName("blank atom returns 400")
        void blankAtom_throws() {
            ExplainRequest req = new ExplainRequest("", 1L, 3, null);
            assertThrows(IllegalArgumentException.class, () -> controller.explain(req));
        }

        @Test
        @DisplayName("unknown atom returns UNKNOWN verdict with deterministic summary")
        void unknownAtom_returnsUnknownWithSummary() {
            ExplainRequest req = new ExplainRequest("unknown(X, Y)", 1L, 3, "sess-explain");

            ResponseEntity<ExplainResponse> resp = controller.explain(req);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertNotNull(resp.getBody());
            assertEquals("UNKNOWN", resp.getBody().verdict());
            assertEquals(0.0, resp.getBody().confidence());
            assertNotNull(resp.getBody().summary());
            assertTrue(resp.getBody().summary().contains("not derivable"));
            assertNotNull(resp.getBody().derivation());
            // derivation is a JSON string
            assertTrue(resp.getBody().derivation().startsWith("{") || resp.getBody().derivation().startsWith(" "));
            assertEquals("sess-explain", resp.getBody().meta().sessionId());
        }
    }

    // ── POST /assert ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /assert")
    class Assert {

        @Test
        @DisplayName("blank atom returns 400")
        void blankAtom_throws() {
            AssertRequest req = new AssertRequest("", 1.0, 1L, null, null, null);
            assertThrows(IllegalArgumentException.class, () -> controller.assertFact(req));
        }

        @Test
        @DisplayName("null factSheetId returns 400")
        void nullFactSheetId_throws() {
            AssertRequest req = new AssertRequest("foo(X)", 1.0, null, null, null, null);
            assertThrows(IllegalArgumentException.class, () -> controller.assertFact(req));
        }

        @Test
        @DisplayName("value out of range returns 400")
        void valueOutOfRange_throws() {
            AssertRequest req = new AssertRequest("foo(X)", 1.5, 1L, null, null, null);
            assertThrows(IllegalArgumentException.class, () -> controller.assertFact(req));
        }

        @Test
        @DisplayName("valid assert returns ASSERTED status and positive version")
        void validAssert_returnsAsserted() {
            AssertRequest req = new AssertRequest(
                    "isEmployedBy(Alice, Acme)", 1.0, 42L,
                    "agent-sess-001", "test-run", null
            );
            ResponseEntity<AssertResponse> resp = controller.assertFact(req);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertNotNull(resp.getBody());
            assertEquals("ASSERTED", resp.getBody().status());
            assertTrue(resp.getBody().version() > 0);
            assertTrue(resp.getBody().cascadeTriggered());
            assertNotNull(resp.getBody().meta());
            assertEquals(42L, resp.getBody().meta().factSheetId());
            assertEquals("agent-sess-001", resp.getBody().meta().sessionId());
        }

        @Test
        @DisplayName("assert with expectedVersion conflict returns CONFLICT_QUEUED")
        void conflictVersion_returnsConflictQueued() {
            // First assert to bump version to 1
            groundingService.assertFact(10L,
                    Fact.observed("foo(X)", "src1"));

            // Attempt assert with old expectedVersion=0 (should conflict since version is now 1)
            AssertRequest req = new AssertRequest(
                    "bar(Y)", 1.0, 10L, null, null, 0L
            );
            ResponseEntity<AssertResponse> resp = controller.assertFact(req);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertNotNull(resp.getBody());
            assertEquals("CONFLICT_QUEUED", resp.getBody().status());
            assertFalse(resp.getBody().cascadeTriggered());
        }

        @Test
        @DisplayName("assert value=0.0 returns RETRACTED status")
        void zeroValue_returnsRetracted() {
            AssertRequest req = new AssertRequest("foo(X)", 0.0, 42L, null, null, null);
            ResponseEntity<AssertResponse> resp = controller.assertFact(req);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertEquals("RETRACTED", resp.getBody().status());
        }
    }

    // ── POST /subscribe (stub) ────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /subscribe")
    class Subscribe {

        @Test
        @DisplayName("returns 501 Not Implemented")
        void returnsNotImplemented() {
            ResponseEntity<SubscribeResponse> resp = controller.subscribe(null);
            assertEquals(HttpStatus.NOT_IMPLEMENTED, resp.getStatusCode());
            assertNotNull(resp.getBody());
            assertTrue(resp.getBody().message().contains("Phase 2"));
        }
    }
}
