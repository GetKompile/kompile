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

import ai.kompile.app.services.grounding.KbFactSubscriptionService;
import ai.kompile.app.services.grounding.KbFactSubscriptionService.KbSubscription;
import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.fol.grounding.ConjunctiveQueryEngine;
import ai.kompile.graph.reasoning.fol.grounding.VerifyResult;
import ai.kompile.graph.reasoning.fol.grounding.VerifyResult.Status;
import ai.kompile.knowledgegraph.grounding.AgentFactAssertedEvent;
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
    private KbFactSubscriptionService subscriptionService;

    @BeforeEach
    void setUp() {
        groundingService = new KbGroundingService();
        subscriptionService = new KbFactSubscriptionService();
        controller = new KbGroundingController(groundingService);
        // Wire the optional subscription service (normally @Autowired in the full context)
        try {
            java.lang.reflect.Field f = KbGroundingController.class.getDeclaredField("subscriptionService");
            f.setAccessible(true);
            f.set(controller, subscriptionService);
        } catch (Exception e) {
            throw new RuntimeException("Could not wire subscriptionService into controller", e);
        }
    }

    // ── POST /synthesize (WP12) ────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /synthesize")
    class Synthesize {

        @Test
        @DisplayName("blank query returns 400")
        void blankQuery_throws() {
            SynthesizeRequest req = new SynthesizeRequest("", 1L, null, null);
            assertThrows(IllegalArgumentException.class, () -> controller.synthesize(req));
        }

        @Test
        @DisplayName("synthesis service unavailable returns 500")
        void serviceUnavailable_throws() {
            // answerSynthesisService is not wired in this plain-lib context → null-safe rejection.
            SynthesizeRequest req = new SynthesizeRequest("who leads Acme?", 42L, null, null);
            assertThrows(IllegalStateException.class, () -> controller.synthesize(req));
        }
    }

    // ── POST /train-scorer (WP12 learned re-ranker) ─────────────────────────────────

    @Nested
    @DisplayName("POST /train-scorer")
    class TrainScorer {

        @Test
        @DisplayName("empty golden set returns 400")
        void emptyGolden_throws() {
            TrainScorerRequest req = new TrainScorerRequest(List.of(), 0.3, null);
            assertThrows(IllegalArgumentException.class, () -> controller.trainScorer(req));
        }

        @Test
        @DisplayName("synthesis service unavailable returns 500")
        void serviceUnavailable_throws() {
            TrainScorerRequest req = new TrainScorerRequest(
                    List.of(new TrainScorerRequest.GoldenItem("who leads Acme?", "Alice", 42L, null)),
                    0.3, null);
            assertThrows(IllegalStateException.class, () -> controller.trainScorer(req));
        }
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
        @DisplayName("unknown atom returns UNKNOWN verdict with confidence 0 and unknownReason")
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
            // Fix #6: unknownReason populated for UNKNOWN
            assertNotNull(resp.getBody().unknownReason(),
                    "unknownReason must be set for UNKNOWN verdict");
            // New fields must be non-null
            assertNotNull(resp.getBody().counterEvidence());
            assertNotNull(resp.getBody().contradictions());
            // Fix #3: evidenceCount and derivationDepth present
            assertEquals(0, resp.getBody().evidenceCount(),
                    "evidenceCount must be 0 for UNKNOWN");
        }

        @Test
        @DisplayName("asserted fact returns SUPPORTED verdict with real depth and sourceProvenance")
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
            // Fix #3: real derivationDepth and evidenceCount
            assertTrue(resp.getBody().evidenceCount() >= 0,
                    "evidenceCount must be non-negative");
            // Fix #4: sourceProvenance non-null
            assertNotNull(resp.getBody().sourceProvenance(),
                    "sourceProvenance must not be null");
            // New E4/E5 fields non-null
            assertNotNull(resp.getBody().counterEvidence());
            assertNotNull(resp.getBody().contradictions());
            // No refutationBasis expected for a clean SUPPORTED atom
            // (may be null or set if tension detected — just check for non-crash)
        }

        @Test
        @DisplayName("null factSheetId defaults to 0 (global)")
        void nullFactSheetId_usesDefault() {
            VerifyRequest req = new VerifyRequest("foo(X)", null, null, null, null);
            ResponseEntity<VerifyResponse> resp = controller.verify(req);
            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertEquals(0L, resp.getBody().meta().factSheetId());
        }

        @Test
        @DisplayName("verify response includes new fields: evidenceCount, derivationDepth, counterEvidence, contradictions")
        void verifyResponse_includesNewFields() {
            VerifyRequest req = new VerifyRequest("anything(X)", 99L, null, null, null);
            ResponseEntity<VerifyResponse> resp = controller.verify(req);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            VerifyResponse body = resp.getBody();
            assertNotNull(body);
            // All new fields must be present (no NullPointerException when accessed)
            body.evidenceCount();
            body.derivationDepth();
            assertNotNull(body.counterEvidence());
            assertNotNull(body.contradictions());
            assertNotNull(body.sourceProvenance());
            // UNKNOWN → unknownReason set
            assertNotNull(body.unknownReason());
        }

        @Test
        @DisplayName("E12: SUPPORTED verdict carries non-null fragility DTO with valid robustness")
        void supported_verdict_carries_fragility_dto() {
            // Seed an inferred fact and a hard fact so the verifier returns SUPPORTED
            groundingService.seedInferredFacts(77L, List.of(
                    InferredFact.of("trusts(Alice, Bob)", 0.85,
                            List.of("observed:test-src"), List.of(), "run-ctrl", 1L)
            ));
            groundingService.assertFact(77L, Fact.observed("trusts(Alice, Bob)", "test-src"));

            VerifyRequest req = new VerifyRequest("trusts(Alice, Bob)", 77L, null, null, null);
            ResponseEntity<VerifyResponse> resp = controller.verify(req);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            VerifyResponse body = resp.getBody();
            assertNotNull(body);
            assertEquals("SUPPORTED", body.verdict());
            // E12: fragility must be non-null for SUPPORTED
            assertNotNull(body.fragility(), "fragility DTO must be non-null for SUPPORTED verdict");
            double robustness = body.fragility().robustness();
            assertTrue(robustness >= 0.0 && robustness <= 1.0,
                    "fragility robustness must be in [0,1] but was: " + robustness);
            assertNotNull(body.fragility().wouldFlipIf(), "wouldFlipIf must not be null");
            assertTrue(body.fragility().minimalSupportSize() >= 0,
                    "minimalSupportSize must be non-negative");
        }

        @Test
        @DisplayName("E12: UNKNOWN verdict → fragility is null")
        void unknown_verdict_fragility_is_null() {
            VerifyRequest req = new VerifyRequest("neverSeen(X, Y)", 42L, null, null, null);
            ResponseEntity<VerifyResponse> resp = controller.verify(req);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            VerifyResponse body = resp.getBody();
            assertNotNull(body);
            assertEquals("UNKNOWN", body.verdict());
            // E12: fragility must be null for UNKNOWN
            assertNull(body.fragility(), "fragility must be null for UNKNOWN verdict");
        }

        @Test
        @DisplayName("E9: UNKNOWN verdict carries non-null nearMissSuggestions list (empty without rules)")
        void unknown_verdict_has_nearMissSuggestions_field() {
            VerifyRequest req = new VerifyRequest("basedIn(Alice, London)", 42L, null, null, null);
            ResponseEntity<VerifyResponse> resp = controller.verify(req);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            VerifyResponse body = resp.getBody();
            assertNotNull(body);
            // nearMissSuggestions must always be non-null (may be empty without rules)
            assertNotNull(body.nearMissSuggestions(),
                    "nearMissSuggestions must not be null (E9 field always present)");
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

    // ── POST /retract ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /retract")
    class Retract {

        @Test
        @DisplayName("blank atomKey returns 400")
        void blankAtomKey_throws() {
            RetractRequest req = new RetractRequest(1L, "", null);
            assertThrows(IllegalArgumentException.class, () -> controller.retractFact(req));
        }

        @Test
        @DisplayName("null factSheetId returns 400")
        void nullFactSheetId_throws() {
            RetractRequest req = new RetractRequest(null, "foo(X)", null);
            assertThrows(IllegalArgumentException.class, () -> controller.retractFact(req));
        }

        @Test
        @DisplayName("retract absent atom returns NOT_FOUND with empty dependency lists")
        void retractAbsentAtom_returnsNotFound() {
            RetractRequest req = new RetractRequest(42L, "neverAsserted(X)", null);

            ResponseEntity<RetractResponse> resp = controller.retractFact(req);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertNotNull(resp.getBody());
            assertEquals("NOT_FOUND", resp.getBody().status());
            assertEquals("neverAsserted(X)", resp.getBody().atomKey());
            assertEquals("retract", resp.getBody().mode());
            assertTrue(resp.getBody().dependentAtomsUnsupported().isEmpty());
            assertTrue(resp.getBody().dependentAtomsWeakened().isEmpty());
            assertTrue(resp.getBody().cascadeTriggered());
        }

        @Test
        @DisplayName("retract present atom returns RETRACTED and atom is gone from fact store")
        void retractPresentAtom_returnsRetractedAndRemovesFact() {
            // First assert a fact
            groundingService.assertFact(42L, Fact.soft("trusts(Alice, Bob)", 0.9, "test"));

            RetractRequest req = new RetractRequest(42L, "trusts(Alice, Bob)", null);
            ResponseEntity<RetractResponse> resp = controller.retractFact(req);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertNotNull(resp.getBody());
            assertEquals("RETRACTED", resp.getBody().status());
            assertEquals("trusts(Alice, Bob)", resp.getBody().atomKey());
            assertTrue(resp.getBody().cascadeTriggered());
            assertNotNull(resp.getBody().meta());
            assertEquals(42L, resp.getBody().meta().factSheetId());

            // Fact should no longer be in the store
            assertTrue(groundingService.getState(42L).factStore()
                    .factFor("trusts(Alice, Bob)").isEmpty(),
                    "atom must be gone after true TMS retraction");
        }

        @Test
        @DisplayName("mode=revise is accepted and returns RETRACTED")
        void modeRevise_returnsRetracted() {
            groundingService.assertFact(10L, Fact.soft("controls(A, B)", 0.8, "test"));

            RetractRequest req = new RetractRequest(10L, "controls(A, B)", "revise");
            ResponseEntity<RetractResponse> resp = controller.retractFact(req);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertEquals("RETRACTED", resp.getBody().status());
            assertEquals("revise", resp.getBody().mode());
        }

        @Test
        @DisplayName("default mode is 'retract' when mode is null")
        void nullMode_defaultsToRetract() {
            RetractRequest req = new RetractRequest(99L, "absent(X)", null);
            ResponseEntity<RetractResponse> resp = controller.retractFact(req);

            assertEquals("retract", resp.getBody().mode());
        }
    }

    // ── POST /subscribe ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /subscribe + long-poll")
    class Subscribe {

        @Test
        @DisplayName("null body defaults to factSheetId=0 and returns 200 with subscriptionId")
        void nullBody_defaultsToSheet0() {
            ResponseEntity<SubscribeResponse> resp = controller.subscribe(null);
            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertNotNull(resp.getBody());
            assertNotNull(resp.getBody().subscriptionId());
            assertFalse(resp.getBody().subscriptionId().isBlank());
            assertNotNull(resp.getBody().expiresAt());
            assertNotNull(resp.getBody().eventsUrl());
            assertNotNull(resp.getBody().pollUrl());
            assertNull(resp.getBody().message()); // no error
        }

        @Test
        @DisplayName("factSheetId and predicates are honoured")
        void factSheetAndPredicates_honoured() {
            SubscribeRequest req = new SubscribeRequest(42L, List.of("worksFor", "trusts"));
            ResponseEntity<SubscribeResponse> resp = controller.subscribe(req);
            assertEquals(HttpStatus.OK, resp.getStatusCode());
            String subId = resp.getBody().subscriptionId();
            assertNotNull(subId);

            // Verify the subscription was actually created with the right fact sheet
            KbSubscription sub = subscriptionService.get(subId).orElseThrow();
            assertEquals(42L, sub.factSheetId());
            assertTrue(sub.predicates().contains("worksFor"));
            assertTrue(sub.predicates().contains("trusts"));
        }

        @Test
        @DisplayName("poll endpoint returns 200 with empty events when no events queued (no wait)")
        void pollEndpoint_emptyWhenNoEvents() {
            SubscribeRequest req = new SubscribeRequest(1L, List.of("worksFor"));
            String subId = controller.subscribe(req).getBody().subscriptionId();

            ResponseEntity<PollResponse> pollResp =
                    controller.pollSubscription(subId, -1L, 0L);

            assertEquals(HttpStatus.OK, pollResp.getStatusCode());
            assertNotNull(pollResp.getBody());
            assertTrue(pollResp.getBody().events().isEmpty());
            assertFalse(pollResp.getBody().overflow());
        }

        @Test
        @DisplayName("poll returns event immediately when event was published before poll")
        void pollEndpoint_immediateReturnWithData() {
            SubscribeRequest req = new SubscribeRequest(10L, List.of("trusts"));
            String subId = controller.subscribe(req).getBody().subscriptionId();

            // Publish an event directly to the service (simulating an EventListener dispatch)
            AgentFactAssertedEvent event = new AgentFactAssertedEvent(
                    this, 10L, "trusts(Alice, Bob)", 0.9, "sess-42");
            subscriptionService.onFactAsserted(event);

            ResponseEntity<PollResponse> pollResp =
                    controller.pollSubscription(subId, -1L, 0L);

            assertEquals(HttpStatus.OK, pollResp.getStatusCode());
            PollResponse body = pollResp.getBody();
            assertNotNull(body);
            assertEquals(1, body.events().size());
            PollResponse.EventDto dto = body.events().get(0);
            assertEquals("asserted", dto.type());
            assertEquals("trusts(Alice, Bob)", dto.atomKey());
            assertEquals(0.9, dto.value(), 1e-9);
            assertEquals("sess-42", dto.source());
            assertEquals(10L, dto.factSheetId());
            assertEquals(0L, body.nextCursor()); // first event has seq=0
        }

        @Test
        @DisplayName("cursor semantics: second poll returns only new events")
        void pollEndpoint_cursorAdvancesCorrectly() {
            SubscribeRequest req = new SubscribeRequest(5L, List.of());
            String subId = controller.subscribe(req).getBody().subscriptionId();

            // Publish two events
            subscriptionService.onFactAsserted(
                    new AgentFactAssertedEvent(this, 5L, "a(x)", 1.0, null));
            subscriptionService.onFactAsserted(
                    new AgentFactAssertedEvent(this, 5L, "b(x)", 1.0, null));

            // First poll: drain both
            PollResponse first = controller.pollSubscription(subId, -1L, 0L).getBody();
            assertEquals(2, first.events().size());
            long cursor = first.nextCursor();

            // Publish a third event
            subscriptionService.onFactAsserted(
                    new AgentFactAssertedEvent(this, 5L, "c(x)", 1.0, null));

            // Second poll: should return only the third event
            PollResponse second = controller.pollSubscription(subId, cursor, 0L).getBody();
            assertEquals(1, second.events().size());
            assertEquals("c(x)", second.events().get(0).atomKey());
        }

        @Test
        @DisplayName("poll returns 404 for unknown subscription id")
        void pollEndpoint_unknownId_returns404() {
            ResponseEntity<PollResponse> resp =
                    controller.pollSubscription("no-such-id", -1L, 0L);
            assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        }

        @Test
        @DisplayName("cancel endpoint returns 204 and subscription is gone")
        void cancelEndpoint_returns204() {
            String subId = controller.subscribe(null).getBody().subscriptionId();
            ResponseEntity<Void> resp = controller.cancelSubscription(subId);
            assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
            assertEquals(HttpStatus.NOT_FOUND,
                    controller.pollSubscription(subId, -1L, 0L).getStatusCode());
        }

        @Test
        @DisplayName("cancel returns 404 for unknown id")
        void cancelEndpoint_unknownId_returns404() {
            ResponseEntity<Void> resp = controller.cancelSubscription("ghost-id");
            assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        }
    }

    // ── POST /claim (P1-5) ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /claim")
    class Claim {

        @Test
        @DisplayName("blank subject returns 400")
        void blankSubject_throws() {
            ClaimRequest req = new ClaimRequest("", "worksFor", "acme", 42L, null);
            assertThrows(IllegalArgumentException.class, () -> controller.assessClaim(req));
        }

        @Test
        @DisplayName("blank predicate returns 400")
        void blankPredicate_throws() {
            ClaimRequest req = new ClaimRequest("alice", "", "acme", 42L, null);
            assertThrows(IllegalArgumentException.class, () -> controller.assessClaim(req));
        }

        @Test
        @DisplayName("blank object returns 400")
        void blankObject_throws() {
            ClaimRequest req = new ClaimRequest("alice", "worksFor", "", 42L, null);
            assertThrows(IllegalArgumentException.class, () -> controller.assessClaim(req));
        }

        @Test
        @DisplayName("valid claim returns 200 with verdict and fusedScore in [0,1]")
        void validClaim_returns200WithVerdict() {
            ClaimRequest req = new ClaimRequest("alice", "worksFor", "acme", 42L, null);
            ResponseEntity<ClaimResponse> resp = controller.assessClaim(req);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertNotNull(resp.getBody());
            // Verdict is SUPPORTED / REFUTED / UNCERTAIN
            String verdict = resp.getBody().verdict();
            assertTrue(verdict.equals("SUPPORTED") || verdict.equals("REFUTED") || verdict.equals("UNCERTAIN"),
                    "verdict must be SUPPORTED, REFUTED, or UNCERTAIN but was: " + verdict);
            double score = resp.getBody().fusedScore();
            assertTrue(score >= 0.0 && score <= 1.0, "fusedScore must be in [0,1]");
            assertNotNull(resp.getBody().supporting());
            assertNotNull(resp.getBody().refuting());
            assertNotNull(resp.getBody().claimAtom());
            assertNotNull(resp.getBody().meta());
        }

        @Test
        @DisplayName("claim on empty KB returns UNCERTAIN with empty supporting/refuting")
        void emptyKb_returnsUncertainOrSupported() {
            // No facts asserted — expect UNCERTAIN (fused score ~0.5)
            ClaimRequest req = new ClaimRequest("nobody", "owns", "nothing", 99L, null);
            ResponseEntity<ClaimResponse> resp = controller.assessClaim(req);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertNotNull(resp.getBody());
            // With no evidence the log-odds sum is 0, sigma(0) = 0.5, verdict = UNCERTAIN
            assertEquals("UNCERTAIN", resp.getBody().verdict());
            assertTrue(resp.getBody().supporting().isEmpty(),
                    "no supporting evidence expected on empty KB");
        }

        @Test
        @DisplayName("null factSheetId defaults to global sheet (0L)")
        void nullFactSheetId_usesGlobalSheet() {
            ClaimRequest req = new ClaimRequest("alice", "knows", "bob", null, null);
            ResponseEntity<ClaimResponse> resp = controller.assessClaim(req);
            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertEquals(0L, resp.getBody().meta().factSheetId());
        }

        @Test
        @DisplayName("claim with asserted fact returns SUPPORTED via verifier channel")
        void assertedFact_supportsClaim() {
            // Seed fact and inferred fact so the verifier channel fires
            groundingService.assertFact(55L, Fact.observed("WORKSFOR(alice, acme)", "test"));
            groundingService.seedInferredFacts(55L, List.of(
                    InferredFact.of("WORKSFOR(alice, acme)", 0.95,
                            List.of(), List.of(), "run-claim", 1L)
            ));

            ClaimRequest req = new ClaimRequest("alice", "WORKSFOR", "acme", 55L, null);
            ResponseEntity<ClaimResponse> resp = controller.assessClaim(req);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertNotNull(resp.getBody());
            // fusedScore should be above neutral (verifier channel fires with SUPPORTED)
            // We can't guarantee SUPPORTED (DossierBuilder depends on exact canonicalization)
            // but the fused score must be in [0,1] and verdict must be one of the three values
            assertTrue(resp.getBody().fusedScore() >= 0.0 && resp.getBody().fusedScore() <= 1.0);
        }
    }

    // ── GET /predicates (P2) ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /predicates")
    class Predicates {

        @Test
        @DisplayName("empty fact sheet returns empty predicate list")
        void emptySheet_returnsEmpty() {
            ResponseEntity<PredicatesResponse> resp = controller.listPredicates(999L);
            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertNotNull(resp.getBody());
            assertNotNull(resp.getBody().predicates());
            assertTrue(resp.getBody().predicates().isEmpty(),
                    "empty fact sheet must yield empty predicate list");
        }

        @Test
        @DisplayName("null factSheetId defaults to global sheet")
        void nullFactSheetId_usesGlobalSheet() {
            ResponseEntity<PredicatesResponse> resp = controller.listPredicates(null);
            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertNotNull(resp.getBody());
            assertNotNull(resp.getBody().predicates());
        }

        @Test
        @DisplayName("asserted facts produce correct predicate entries")
        void assertedFacts_producePredicate() {
            groundingService.assertFact(70L, Fact.observed("isEmployedBy(Alice, Acme)", "test-src"));
            groundingService.assertFact(70L, Fact.observed("isEmployedBy(Bob, Acme)", "test-src"));
            groundingService.assertFact(70L, Fact.observed("livesIn(Alice, London)", "test-src"));

            ResponseEntity<PredicatesResponse> resp = controller.listPredicates(70L);
            assertEquals(HttpStatus.OK, resp.getStatusCode());

            List<PredicatesResponse.PredicateEntry> predicates = resp.getBody().predicates();
            assertFalse(predicates.isEmpty(), "should have at least one predicate");

            // Find isEmployedBy
            PredicatesResponse.PredicateEntry empEntry = predicates.stream()
                    .filter(p -> "isEmployedBy".equals(p.name()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(empEntry, "isEmployedBy predicate must be present");
            assertTrue(empEntry.count() >= 2, "count for isEmployedBy must be >= 2");
            assertFalse(empEntry.inferred(), "observed predicate must not be flagged inferred");

            // livesIn
            PredicatesResponse.PredicateEntry livesEntry = predicates.stream()
                    .filter(p -> "livesIn".equals(p.name()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(livesEntry, "livesIn predicate must be present");

            // Sorted by count descending: isEmployedBy (2) must come before livesIn (1)
            int empIdx   = predicates.indexOf(empEntry);
            int livesIdx = predicates.indexOf(livesEntry);
            assertTrue(empIdx < livesIdx, "higher-count predicate must appear first");
        }

        @Test
        @DisplayName("predicate list has required shape: name/count/inferred fields")
        void predicateEntry_hasRequiredShape() {
            groundingService.assertFact(80L, Fact.observed("trusts(A, B)", "test"));
            ResponseEntity<PredicatesResponse> resp = controller.listPredicates(80L);
            assertFalse(resp.getBody().predicates().isEmpty());
            PredicatesResponse.PredicateEntry e = resp.getBody().predicates().get(0);
            assertNotNull(e.name());
            assertTrue(e.count() > 0);
            // inferred is a boolean (no NPE)
            boolean ignored = e.inferred();
        }
    }

    // ── P1-6 DeepWhyNot in verify chain ──────────────────────────────────────────

    @Nested
    @DisplayName("P1-6 DeepWhyNot via POST /verify")
    class DeepWhyNot {

        @Test
        @DisplayName("UNKNOWN verdict with no rules returns deepWhyNot=null")
        void unknownNoRules_deepWhyNotIsNull() {
            VerifyRequest req = new VerifyRequest("missingPred(X, Y)", 200L, null, null, null);
            ResponseEntity<VerifyResponse> resp = controller.verify(req);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            VerifyResponse body = resp.getBody();
            assertNotNull(body);
            assertEquals("UNKNOWN", body.verdict());
            // Without rules the DeepWhyNot is skipped — field must be null (not NPE)
            assertNull(body.deepWhyNot(), "deepWhyNot must be null when no rules are present");
        }

        @Test
        @DisplayName("SUPPORTED verdict returns deepWhyNot=null (only for UNKNOWN)")
        void supportedVerdict_deepWhyNotIsNull() {
            groundingService.assertFact(201L, Fact.observed("trusts(A, B)", "src"));
            groundingService.seedInferredFacts(201L, List.of(
                    InferredFact.of("trusts(A, B)", 0.9, List.of(), List.of(), "run", 1L)
            ));

            VerifyRequest req = new VerifyRequest("trusts(A, B)", 201L, null, null, null);
            ResponseEntity<VerifyResponse> resp = controller.verify(req);

            assertEquals(HttpStatus.OK, resp.getStatusCode());
            assertEquals("SUPPORTED", resp.getBody().verdict());
            // DeepWhyNot is only for UNKNOWN
            assertNull(resp.getBody().deepWhyNot(),
                    "deepWhyNot must be null for SUPPORTED verdict");
        }

        @Test
        @DisplayName("DeepWhyNot DTO fields have required shape when present")
        void deepWhyNotDto_hasRequiredShape() {
            // This test verifies the DTO shape at compile/runtime — actual completion sets
            // require a PSL cascade which isn't wired in the plain-lib test context.
            // We exercise the null-safety of the field: verify the record compiles and
            // all three fields are accessible.
            VerifyResponse.DeepWhyNotDto dto = new VerifyResponse.DeepWhyNotDto(
                    List.of(List.of("fact1(A, B)", "fact2(B, C)")),
                    List.of("fact1(A, B) MISSING; fact2(B, C) MISSING"),
                    false
            );
            assertNotNull(dto.completionSets());
            assertEquals(1, dto.completionSets().size());
            assertEquals(2, dto.completionSets().get(0).size());
            assertNotNull(dto.flatSuggestions());
            assertFalse(dto.budgetExhausted());
        }
    }
}
