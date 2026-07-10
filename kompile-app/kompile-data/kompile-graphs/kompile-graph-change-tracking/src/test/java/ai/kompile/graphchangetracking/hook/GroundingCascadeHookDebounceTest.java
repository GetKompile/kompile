package ai.kompile.graphchangetracking.hook;

import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.knowledgegraph.grounding.KbGroundingService;
import ai.kompile.knowledgegraph.reasoning.IncrementalReasoningOrchestrator;
import ai.kompile.knowledgegraph.reasoning.RegroundResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GroundingCascadeHook debounce and max-wait behavior.
 *
 * <p>Uses very short debounce windows (50ms) so tests complete quickly.</p>
 */
class GroundingCascadeHookDebounceTest {

    private static final long FS = 99L;
    private static final long DEBOUNCE_MS = 50L;
    private static final long MAX_WAIT_MS = 200L;

    private KbGroundingService groundingService;
    private IncrementalReasoningOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        groundingService = new KbGroundingService();
        groundingService.assertFact(FS, Fact.soft("test(A,B)", 0.9, "test"));
        orchestrator = new IncrementalReasoningOrchestrator(groundingService, e -> {});
    }

    /**
     * Build a counting orchestrator that intercepts the 2-param variant
     * (the one actually called by the hook's submitCascadeTask).
     */
    private IncrementalReasoningOrchestrator countingOrchestrator(
            AtomicInteger count, CountDownLatch latch) {
        return new IncrementalReasoningOrchestrator(groundingService, e -> {}) {
            @Override
            public RegroundResult runFullReground(long factSheetId, String trigger) {
                count.incrementAndGet();
                RegroundResult r = super.runFullReground(factSheetId, trigger);
                latch.countDown();
                return r;
            }
        };
    }

    // ── Debounce fires after quiet period ────────────────────────────────────────

    @Test
    @DisplayName("scheduleDebounced: fires cascade after quiet period elapses")
    void debounce_firesAfterQuietPeriod() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger cascadeCount = new AtomicInteger(0);

        GroundingCascadeHook hook = new GroundingCascadeHook(
                countingOrchestrator(cascadeCount, latch), null, DEBOUNCE_MS, MAX_WAIT_MS);

        hook.scheduleDebounced(FS, "test", "cascade");

        boolean fired = latch.await(3, TimeUnit.SECONDS);
        assertTrue(fired, "Cascade must fire after quiet period");
        assertEquals(1, cascadeCount.get(), "Exactly one cascade must run");
    }

    @Test
    @DisplayName("scheduleDebounced: multiple rapid calls collapse into a single cascade")
    void debounce_rapidCallsCoalesceToSingleCascade() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger cascadeCount = new AtomicInteger(0);

        GroundingCascadeHook hook = new GroundingCascadeHook(
                countingOrchestrator(cascadeCount, latch), null, DEBOUNCE_MS, MAX_WAIT_MS);

        // 10 rapid calls — all arrive within the debounce window
        for (int i = 0; i < 10; i++) {
            hook.scheduleDebounced(FS, "batch:" + i, "cascade");
        }

        boolean fired = latch.await(3, TimeUnit.SECONDS);
        assertTrue(fired, "Cascade must fire after quiet period");
        assertEquals(1, cascadeCount.get(), "All 10 rapid calls must collapse into ONE cascade");
    }

    @Test
    @DisplayName("scheduleDebounced: max-wait cap forces cascade under continuous mutations")
    void debounce_maxWaitCapForcesCascade() throws InterruptedException {
        // longDebounce ensures quiet period would never naturally elapse during the test.
        // shortMaxWait forces a cascade after 80ms regardless.
        long shortMaxWait = 80L;
        long longDebounce = 500L;

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger cascadeCount = new AtomicInteger(0);

        GroundingCascadeHook hook = new GroundingCascadeHook(
                countingOrchestrator(cascadeCount, latch), null, longDebounce, shortMaxWait);

        // First call starts the debounce timer and records debounceStartAt
        hook.scheduleDebounced(FS, "flood:0", "cascade");
        // Keep touching lastMutationAt so quiet period never elapses
        for (int i = 1; i <= 5; i++) {
            Thread.sleep(20);
            hook.scheduleDebounced(FS, "flood:" + i, "cascade");
        }
        // By now ~100ms have passed. max-wait (80ms) must have triggered the cascade.

        boolean fired = latch.await(3, TimeUnit.SECONDS);
        assertTrue(fired, "max-wait cap must force the cascade to fire");
        assertEquals(1, cascadeCount.get(), "Exactly one cascade must run after max-wait");
    }

    @Test
    @DisplayName("scheduleDebounced: stale flag is marked immediately on first call")
    void debounce_marksStaleImmediately() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger count = new AtomicInteger(0);

        GroundingCascadeHook hook = new GroundingCascadeHook(
                countingOrchestrator(count, latch), null, DEBOUNCE_MS, MAX_WAIT_MS);
        hook.setKbGroundingService(groundingService);

        assertFalse(groundingService.isStale(FS), "KB must not be stale before first debounce call");

        hook.scheduleDebounced(FS, "test", "cascade");

        // stale must be set synchronously (before the debounce timer fires)
        assertTrue(groundingService.isStale(FS),
                "KB must be marked stale immediately after scheduleDebounced()");

        // Wait for cascade to complete and clear stale
        assertTrue(latch.await(3, TimeUnit.SECONDS), "Cascade must complete");
        assertFalse(groundingService.isStale(FS),
                "KB must not be stale after cascade completes successfully");
    }

    @Test
    @DisplayName("scheduleDebounced: new calls after first cascade can start a second cascade")
    void debounce_secondWindowStartsAfterFirstCascadeCompletes() throws InterruptedException {
        CountDownLatch secondWave = new CountDownLatch(2);  // counts both cascade runs
        AtomicInteger cascadeCount = new AtomicInteger(0);

        IncrementalReasoningOrchestrator counter = countingOrchestrator(cascadeCount, secondWave);
        GroundingCascadeHook hook = new GroundingCascadeHook(counter, null, DEBOUNCE_MS, MAX_WAIT_MS);

        // First wave — wait for the cascade to be submitted
        CountDownLatch firstSubmitted = new CountDownLatch(1);
        // Use a wrapper that signals when schedule() is called (i.e., debounce timer fired)
        GroundingCascadeHook observingHook = new GroundingCascadeHook(counter, null, DEBOUNCE_MS, MAX_WAIT_MS) {
            @Override
            public void schedule(long factSheetId, String logLabel, String trigger) {
                super.schedule(factSheetId, logLabel, trigger);
                firstSubmitted.countDown();
            }
        };

        observingHook.scheduleDebounced(FS, "wave1", "cascade");
        assertTrue(firstSubmitted.await(3, TimeUnit.SECONDS), "First cascade must be submitted");

        // Wait for the running gate to clear
        Thread.sleep(200);

        // Second wave — should start a fresh debounce window and fire a second cascade
        observingHook.scheduleDebounced(FS, "wave2", "cascade");
        assertTrue(secondWave.await(3, TimeUnit.SECONDS), "Second cascade must fire");

        assertEquals(2, cascadeCount.get(), "Exactly two cascades must have run");
    }
}
