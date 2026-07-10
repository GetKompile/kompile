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
package ai.kompile.graphchangetracking.hook;

import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graphchangetracking.event.GraphChangesetCompletedEvent;
import ai.kompile.knowledgegraph.grounding.AgentFactAssertedEvent;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import ai.kompile.knowledgegraph.reasoning.IncrementalReasoningOrchestrator;
import ai.kompile.knowledgegraph.reasoning.RegroundResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link GroundingCascadeHook}.
 *
 * <p>Instantiates the hook and its dependencies directly (no Spring context).
 * Uses a real {@link KbGroundingService} and {@link IncrementalReasoningOrchestrator}
 * so we verify the wiring without mocks.</p>
 */
class GroundingCascadeHookTest {

    private static final long FS = 77L;

    private KbGroundingService groundingService;
    private IncrementalReasoningOrchestrator orchestrator;
    private GroundingCascadeHook hook;

    @BeforeEach
    void setUp() {
        groundingService = new KbGroundingService();
        orchestrator = new IncrementalReasoningOrchestrator(groundingService, e -> {});
        hook = new GroundingCascadeHook(orchestrator);
    }

    @Test
    @DisplayName("onChangesetCompleted: null factSheetId → no cascade scheduled (no exception)")
    void onChangesetCompleted_nullFactSheetId_noException() {
        GraphChangesetCompletedEvent event = new GraphChangesetCompletedEvent(
                this, "cs-001", 3, 0, 0, 2, 0, null);
        // Should not throw; the event listener logs and returns (delegates to the hook)
        new GroundingCascadeEventListener(hook).onChangesetCompleted(event);
    }

    @Test
    @DisplayName("onChangesetCompleted: valid factSheetId → cascade executes")
    void onChangesetCompleted_validFactSheet_cascadeRuns() throws InterruptedException {
        // Pre-assert a fact so the cascade has something to work with
        groundingService.assertFact(FS, Fact.observed("mentions(Email, AcmeCorp)", "crawl-1"));

        AtomicInteger callCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        // Override the orchestrator to count calls and signal the latch
        IncrementalReasoningOrchestrator countingOrchestrator =
                new IncrementalReasoningOrchestrator(groundingService, e -> {}) {
                    @Override
                    public RegroundResult runFullReground(long factSheetId, String trigger) {
                        callCount.incrementAndGet();
                        RegroundResult result = super.runFullReground(factSheetId, trigger);
                        latch.countDown();
                        return result;
                    }
                };
        GroundingCascadeHook countingHook = new GroundingCascadeHook(countingOrchestrator);

        GraphChangesetCompletedEvent event = new GraphChangesetCompletedEvent(
                this, "cs-002", 4, 0, 0, 3, 0, FS);
        countingHook.schedule(FS, "test-trigger");

        boolean completed = latch.await(5, TimeUnit.SECONDS);
        assertTrue(completed, "Cascade must complete within 5 seconds");
        assertEquals(1, callCount.get(), "Cascade must be called exactly once");
    }

    @Test
    @DisplayName("onAgentFactAsserted: schedules cascade for correct factSheetId")
    void onAgentFactAsserted_schedulesForCorrectFactSheet() throws InterruptedException {
        groundingService.assertFact(FS, Fact.soft("trusts(Alice, Bob)", 0.8, "agent"));

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger runForFactSheet = new AtomicInteger(-1);

        IncrementalReasoningOrchestrator trackingOrchestrator =
                new IncrementalReasoningOrchestrator(groundingService, e -> {}) {
                    @Override
                    public RegroundResult runFullReground(long factSheetId, String trigger) {
                        runForFactSheet.set((int) factSheetId);
                        RegroundResult r = super.runFullReground(factSheetId, trigger);
                        latch.countDown();
                        return r;
                    }
                };
        GroundingCascadeHook trackingHook = new GroundingCascadeHook(trackingOrchestrator);

        AgentFactAssertedEvent event = new AgentFactAssertedEvent(
                this, FS, "trusts(Alice, Bob)", 0.8, "sess-abc");
        new GroundingCascadeEventListener(trackingHook).onAgentFactAsserted(event);

        boolean done = latch.await(5, TimeUnit.SECONDS);
        assertTrue(done, "Agent-assert cascade must complete within 5 seconds");
        assertEquals((int) FS, runForFactSheet.get(),
                "Cascade must run for the correct factSheetId");
    }
}
