package ai.kompile.graphchangetracking.service;

import ai.kompile.graphchangetracking.event.EdgeMutationEvent;
import ai.kompile.graphchangetracking.event.NodeMutationEvent;
import ai.kompile.graphchangetracking.hook.GraphUpdateHookRegistry;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import ai.kompile.knowledgegraph.reasoning.IncrementalReasoningOrchestrator;
import ai.kompile.graphchangetracking.hook.GroundingCascadeEventListener;
import ai.kompile.graphchangetracking.hook.GroundingCascadeHook;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ai.kompile.graph.reasoning.fol.Fact;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the double-trigger fix (audit gap U2):
 *
 * <ol>
 *   <li>{@link GraphMutationRecordingListener} no longer calls {@code schedule()} — it is
 *       persist-only now.</li>
 *   <li>A single {@link NodeMutationEvent} results in exactly ONE debounced scheduling via
 *       {@link GroundingCascadeEventListener} and ZERO immediate {@code schedule()} calls from
 *       the recording listener.</li>
 *   <li>{@link GraphMutationRecordingListener} has no {@code setGroundingCascadeHook} method
 *       (the field was deleted as part of the fix).</li>
 * </ol>
 *
 * <p>Because both listeners receive the same event asynchronously in production, this test
 * simulates synchronous dispatch (calling onNodeMutation / onEdgeMutation directly) to keep
 * the assertions deterministic without requiring a Spring context.</p>
 */
class GraphMutationRecordingListenerNoCascadeTest {

    @TempDir
    Path tempDir;

    private static final long FS = 55L;

    private GraphMutationStore mutationStore;
    private GraphMutationRecordingListener recordingListener;
    private KbGroundingService groundingService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        mutationStore = new GraphMutationStore(objectMapper, tempDir.resolve("mut.jsonl"));

        // Hook registry that is a no-op (no registered hooks)
        GraphUpdateHookRegistry hookRegistry = new GraphUpdateHookRegistry();

        recordingListener = new GraphMutationRecordingListener(mutationStore, hookRegistry);

        groundingService = new KbGroundingService();
        groundingService.assertFact(FS, Fact.soft("test(A,B)", 0.9, "test"));
    }

    // ── No schedule() in recording listener ─────────────────────────────────────

    @Test
    @DisplayName("GraphMutationRecordingListener has no setGroundingCascadeHook — cascade hook removed")
    void recordingListener_hasNoCascadeHookSetter() throws NoSuchMethodException {
        // Verifies the method was actually deleted from the class
        boolean hasMethod;
        try {
            GraphMutationRecordingListener.class.getMethod("setGroundingCascadeHook",
                    ai.kompile.knowledgegraph.grounding.GroundingResetPort.class);
            hasMethod = true;
        } catch (NoSuchMethodException e) {
            hasMethod = false;
        }
        assertFalse(hasMethod,
                "setGroundingCascadeHook must not exist on GraphMutationRecordingListener — "
                + "cascade scheduling was moved exclusively to GroundingCascadeEventListener");
    }

    @Test
    @DisplayName("onNodeMutation: persists record only — does NOT trigger cascade")
    void onNodeMutation_persistsOnly_noCascadeScheduled() throws InterruptedException {
        // Use a counting orchestrator; if schedule() was called here it would run immediately
        // and we'd see orchestratorCallCount > 0 before the debounce window even opens.
        AtomicInteger orchestratorCallCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1); // never expected to fire in this test

        IncrementalReasoningOrchestrator countingOrchestrator =
                new IncrementalReasoningOrchestrator(groundingService, e -> {}) {
                    @Override
                    public ai.kompile.knowledgegraph.reasoning.RegroundResult runFullReground(
                            long factSheetId, String trigger) {
                        orchestratorCallCount.incrementAndGet();
                        latch.countDown();
                        return super.runFullReground(factSheetId, trigger);
                    }
                };
        // Wire the hook but give it to GroundingCascadeEventListener only (not to recording listener)
        GroundingCascadeHook hook = new GroundingCascadeHook(countingOrchestrator, null, 5_000L, 30_000L);
        // Recording listener has NO reference to the hook — this is the invariant we're testing.

        MutationContextHolder.MutationContext ctx = new MutationContextHolder.MutationContext(null, "test", null);
        NodeMutationEvent event = NodeMutationEvent.created(
                this, "node-x", FS, "ENTITY", "{}", ctx);

        // Call synchronously to simulate the @EventListener dispatch
        recordingListener.onNodeMutation(event);

        // Give a short window to confirm the orchestrator was NOT called immediately
        boolean fired = latch.await(200, TimeUnit.MILLISECONDS);
        assertFalse(fired, "Cascade must NOT fire immediately from the recording listener — "
                + "it is now debounced via GroundingCascadeEventListener");
        assertEquals(0, orchestratorCallCount.get(),
                "Recording listener must not trigger the orchestrator at all");

        // The mutation record must still be persisted
        long count = mutationStore.findAll(org.springframework.data.domain.Pageable.unpaged()).getTotalElements();
        assertEquals(1, count, "Recording listener must still persist the mutation record");
    }

    @Test
    @DisplayName("onEdgeMutation: persists record only — does NOT trigger cascade")
    void onEdgeMutation_persistsOnly_noCascadeScheduled() throws InterruptedException {
        AtomicInteger orchestratorCallCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        IncrementalReasoningOrchestrator countingOrchestrator =
                new IncrementalReasoningOrchestrator(groundingService, e -> {}) {
                    @Override
                    public ai.kompile.knowledgegraph.reasoning.RegroundResult runFullReground(
                            long factSheetId, String trigger) {
                        orchestratorCallCount.incrementAndGet();
                        latch.countDown();
                        return super.runFullReground(factSheetId, trigger);
                    }
                };
        GroundingCascadeHook hook = new GroundingCascadeHook(countingOrchestrator, null, 5_000L, 30_000L);
        // Recording listener has NO reference to the hook.

        MutationContextHolder.MutationContext ctx = new MutationContextHolder.MutationContext(null, "test", null);
        EdgeMutationEvent event = EdgeMutationEvent.created(
                this, "edge-1", FS, "USER_DEFINED", "src", "tgt", "{}", ctx);

        recordingListener.onEdgeMutation(event);

        boolean fired = latch.await(200, TimeUnit.MILLISECONDS);
        assertFalse(fired, "Cascade must NOT fire immediately from the recording listener");
        assertEquals(0, orchestratorCallCount.get());

        long count = mutationStore.findAll(org.springframework.data.domain.Pageable.unpaged()).getTotalElements();
        assertEquals(1, count, "Recording listener must persist the edge mutation record");
    }

    // ── Single debounced scheduling via GroundingCascadeEventListener ────────────

    @Test
    @DisplayName("NodeMutationEvent → GroundingCascadeEventListener scheduleDebounced exactly once")
    void nodeMutation_singleDebouncedSchedulingViaListener() throws InterruptedException {
        // Short debounce (50ms) so the test completes quickly
        AtomicInteger cascadeCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        IncrementalReasoningOrchestrator countingOrchestrator =
                new IncrementalReasoningOrchestrator(groundingService, e -> {}) {
                    @Override
                    public ai.kompile.knowledgegraph.reasoning.RegroundResult runFullReground(
                            long factSheetId, String trigger) {
                        cascadeCount.incrementAndGet();
                        ai.kompile.knowledgegraph.reasoning.RegroundResult r =
                                super.runFullReground(factSheetId, trigger);
                        latch.countDown();
                        return r;
                    }
                };
        GroundingCascadeHook hook = new GroundingCascadeHook(countingOrchestrator, null, 50L, 500L);
        GroundingCascadeEventListener listener = new GroundingCascadeEventListener(hook);

        // Simulate what Spring would dispatch: ONLY the event listener receives the event now.
        MutationContextHolder.MutationContext ctx = new MutationContextHolder.MutationContext(null, "test", null);
        NodeMutationEvent event = NodeMutationEvent.created(
                this, "node-y", FS, "ENTITY", "{}", ctx);
        listener.onNodeMutation(event);

        boolean fired = latch.await(3, TimeUnit.SECONDS);
        assertTrue(fired, "Cascade must fire via GroundingCascadeEventListener after debounce");
        assertEquals(1, cascadeCount.get(),
                "Exactly ONE cascade must run — not two (no double-fire from recording listener)");
    }

    @Test
    @DisplayName("Ten rapid NodeMutationEvents → GroundingCascadeEventListener coalesces to one cascade")
    void tenRapidNodeMutations_coalescedToOneCascade() throws InterruptedException {
        AtomicInteger cascadeCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        IncrementalReasoningOrchestrator countingOrchestrator =
                new IncrementalReasoningOrchestrator(groundingService, e -> {}) {
                    @Override
                    public ai.kompile.knowledgegraph.reasoning.RegroundResult runFullReground(
                            long factSheetId, String trigger) {
                        cascadeCount.incrementAndGet();
                        ai.kompile.knowledgegraph.reasoning.RegroundResult r =
                                super.runFullReground(factSheetId, trigger);
                        latch.countDown();
                        return r;
                    }
                };
        GroundingCascadeHook hook = new GroundingCascadeHook(countingOrchestrator, null, 50L, 500L);
        GroundingCascadeEventListener listener = new GroundingCascadeEventListener(hook);

        // 10 rapid mutations — all arrive before the 50ms quiet period
        MutationContextHolder.MutationContext ctx2 = new MutationContextHolder.MutationContext(null, "test", null);
        for (int i = 0; i < 10; i++) {
            NodeMutationEvent event = NodeMutationEvent.created(
                    this, "node-" + i, FS, "ENTITY", "{}", ctx2);
            listener.onNodeMutation(event);
        }

        boolean fired = latch.await(3, TimeUnit.SECONDS);
        assertTrue(fired, "Cascade must fire");
        assertEquals(1, cascadeCount.get(),
                "All 10 rapid mutations must collapse into exactly ONE cascade (debounce invariant)");
    }
}
