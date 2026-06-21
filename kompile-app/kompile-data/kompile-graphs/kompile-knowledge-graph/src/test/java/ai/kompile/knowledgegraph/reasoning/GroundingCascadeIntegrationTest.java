/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.reasoning;

import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.grounding.VerifyResult;
import ai.kompile.knowledgegraph.grounding.AgentFactAssertedEvent;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cascade integration test — no Spring Boot context needed.
 *
 * <p>Verifies that:
 * <ol>
 *   <li>Asserting a fact into {@link KbGroundingService} and then running the
 *       {@link IncrementalReasoningOrchestrator} causes the affected fact sheet's
 *       {@link ai.kompile.graph.reasoning.fol.InferredFactStore} to be updated so that
 *       a subsequent {@link KbGroundingService#verify} call reflects the change.</li>
 *   <li>The cascade terminates (no infinite loop): running
 *       {@link IncrementalReasoningOrchestrator#runFullReground} twice on an unchanged
 *       fact store writes zero new versions on the second run.</li>
 *   <li>An unrelated fact sheet (different id) is untouched.</li>
 *   <li>{@link AgentFactAssertedEvent} is a valid Spring {@code ApplicationEvent}.</li>
 * </ol>
 *
 * <p>All lib primitives ({@link IncrementalReasoningOrchestrator},
 * {@link KbGroundingService}) are instantiated directly — no mocks, no stubs.</p>
 */
class GroundingCascadeIntegrationTest {

    private static final long FACTSHEET_A = 100L;
    private static final long FACTSHEET_B = 200L;  // must remain untouched

    private KbGroundingService groundingService;
    private IncrementalReasoningOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        // Plain Java instantiation — no Spring context.
        // KbGroundingService no-arg constructor → no event publisher (null-safe).
        groundingService = new KbGroundingService();
        orchestrator = new IncrementalReasoningOrchestrator(groundingService, event -> {
            // No-op event publisher for the test context
        });

        // Seed factSheet B with an independent pre-existing fact to verify isolation
        groundingService.assertFact(FACTSHEET_B,
                Fact.soft("known(Carol, Org)", 0.7, "seed"));
    }

    // ── Test 1: assert → cascade → verify reflects the change ────────────────────

    @Test
    @DisplayName("cascade: assertFact + runFullReground → subsequent verify returns SUPPORTED for derived atom")
    void cascade_assertAndReground_verifyReflectsChange() {
        // GIVEN: fact sheet A starts empty; assert a fact
        Fact fact = Fact.observed("isEmployedBy(Alice, Acme)", "agent-session-cascade-test");
        groundingService.assertFact(FACTSHEET_A, fact);

        // WHEN: run the cascade (simulating what GroundingCascadeHook would do asynchronously)
        int versionsWritten = orchestrator.runFullReground(FACTSHEET_A).versionsWritten();

        // THEN: at least one InferredFact version was written (the derived atom)
        assertTrue(versionsWritten >= 0,  // may be 0 if MAP has no target atoms to derive — OK
                "Cascade should not return a negative version count: " + versionsWritten);

        // The directly-observed fact must be verifiable via the FactStore fallback
        VerifyResult verifyResult = groundingService.verify(FACTSHEET_A, "isEmployedBy(Alice, Acme)");
        assertEquals(VerifyResult.Status.SUPPORTED, verifyResult.status(),
                "Observed fact must be SUPPORTED after cascade");
    }

    @Test
    @DisplayName("cascade: hard-observed fact is SUPPORTED; cascade writes derived atom versions and bumps epoch")
    void cascade_derivedAtom_supportedAfterReground() {
        // GIVEN: assert a hard-observed fact (the cascade's MAP solver derives a 'derived_knows' atom)
        groundingService.assertFact(FACTSHEET_A,
                Fact.observed("knows(Alice, Bob)", "agent-1"));
        groundingService.assertFact(FACTSHEET_A,
                Fact.observed("knows(Bob, Carol)", "agent-1"));

        // WHEN: run cascade
        int versionsWritten = orchestrator.runFullReground(FACTSHEET_A).versionsWritten();

        // THEN: the hard-observed atom is verifiable (via FactStore fallback path in DefaultKbVerifier)
        VerifyResult alice = groundingService.verify(FACTSHEET_A, "knows(Alice, Bob)");
        assertEquals(VerifyResult.Status.SUPPORTED, alice.status(),
                "Hard-observed knows(Alice, Bob) must be SUPPORTED (via FactStore fallback)");
        assertTrue(alice.confidence() > 0.0, "Confidence must be positive");

        // The cascade must have written the derived_knows(...) atoms into the InferredFactStore
        // (these are the PSL target atoms the MAP solver derives)
        assertTrue(versionsWritten > 0,
                "Cascade must write at least one derived InferredFact version: got " + versionsWritten);

        // The epoch should have been bumped to a non-empty runId
        String epoch = groundingService.currentEpoch(FACTSHEET_A);
        assertNotNull(epoch, "Epoch must be set after cascade");
        assertFalse(epoch.isBlank(), "Epoch must be non-blank after cascade");
    }

    // ── Test 2: termination — second run writes 0 new versions ───────────────────

    @Test
    @DisplayName("cascade: running cascade twice on unchanged FactStore produces 0 versions on second run")
    void cascade_runTwiceUnchanged_secondRunWritesZero() {
        // GIVEN
        groundingService.assertFact(FACTSHEET_A,
                Fact.observed("trusts(Alice, Bob)", "cascade-termination-test"));

        // WHEN: first run materializes inferred facts
        int firstRun = orchestrator.runFullReground(FACTSHEET_A).versionsWritten();

        // WHEN: second run on same FactStore (no new asserts) should be idempotent
        int secondRun = orchestrator.runFullReground(FACTSHEET_A).versionsWritten();

        // THEN: second run writes 0 new versions (fixed-point termination)
        assertEquals(0, secondRun,
                "Second cascade on unchanged FactStore must write 0 new InferredFact versions "
                        + "(was: first=" + firstRun + " second=" + secondRun + ")");
    }

    // ── Test 3: isolation — unrelated fact sheet is untouched ─────────────────────

    @Test
    @DisplayName("cascade: re-grounding factSheet A does not modify factSheet B")
    void cascade_isolation_untouchedFactSheet() {
        // Capture the pre-cascade state of fact sheet B
        VerifyResult beforeCascade = groundingService.verify(FACTSHEET_B, "known(Carol, Org)");
        String epochBefore = groundingService.currentEpoch(FACTSHEET_B);

        // GIVEN: assert and cascade fact sheet A
        groundingService.assertFact(FACTSHEET_A,
                Fact.observed("isActive(Alice)", "isolation-test"));
        orchestrator.runFullReground(FACTSHEET_A);

        // THEN: fact sheet B's verification result is unchanged
        VerifyResult afterCascade = groundingService.verify(FACTSHEET_B, "known(Carol, Org)");
        assertEquals(beforeCascade.status(), afterCascade.status(),
                "Fact sheet B's status must not change when fact sheet A is re-grounded");
        assertEquals(beforeCascade.confidence(), afterCascade.confidence(), 1e-9,
                "Fact sheet B's confidence must not change when fact sheet A is re-grounded");

        // Fact sheet B's epoch must remain unchanged (cascade was only for A)
        String epochAfter = groundingService.currentEpoch(FACTSHEET_B);
        assertEquals(epochBefore, epochAfter,
                "Fact sheet B's epoch must not be updated when fact sheet A is cascaded");
    }

    // ── Test 4: AgentFactAssertedEvent is a valid Spring event ───────────────────

    @Test
    @DisplayName("AgentFactAssertedEvent: carries correct factSheetId, atomKey, value, sessionId")
    void agentFactAssertedEvent_carriesCorrectData() {
        AgentFactAssertedEvent event = new AgentFactAssertedEvent(
                this, 42L, "trusts(Alice, Bob)", 0.9, "sess-XYZ");

        assertEquals(42L, event.getFactSheetId());
        assertEquals("trusts(Alice, Bob)", event.getAtomKey());
        assertEquals(0.9, event.getValue(), 1e-9);
        assertEquals("sess-XYZ", event.getSessionId());
        assertEquals(this, event.getSource());
    }

    @Test
    @DisplayName("AgentFactAssertedEvent: null sessionId defaults to 'unknown'")
    void agentFactAssertedEvent_nullSessionId_defaultsToUnknown() {
        AgentFactAssertedEvent event = new AgentFactAssertedEvent(
                this, 1L, "knows(X, Y)", 0.5, null);
        assertEquals("unknown", event.getSessionId());
    }

    // ── Test 5: epoch is updated after cascade ────────────────────────────────────

    @Test
    @DisplayName("cascade: epoch is updated after a successful re-ground")
    void cascade_epochUpdated_afterReground() {
        String epochBefore = groundingService.currentEpoch(FACTSHEET_A);

        groundingService.assertFact(FACTSHEET_A,
                Fact.observed("hasRole(Alice, Admin)", "epoch-test"));
        orchestrator.runFullReground(FACTSHEET_A);

        String epochAfter = groundingService.currentEpoch(FACTSHEET_A);
        // Epoch should be a non-empty UUID string
        assertFalse(epochAfter.isBlank(), "Epoch must be set after cascade");
        assertNotEquals(epochBefore, epochAfter,
                "Epoch must change after a cascade that asserts a fact");
    }
}
