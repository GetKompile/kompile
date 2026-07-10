package ai.kompile.embedding.anserini;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the request-timeout → lazy-forced-restart path in
 * {@link AnseriniEmbeddingModelImpl}.
 *
 * <p>No ND4J, no subprocess, no Spring context.  Uses the package-private
 * test seams ({@code isForceRestartArmed}, {@code getConsecutiveRequestTimeouts},
 * {@code setForceRestartArmedForTest}, {@code triggerForceRestartCheckForTest}, etc.)
 * added alongside the feature.</p>
 *
 * <h3>Scenarios tested</h3>
 * <ol>
 *   <li>Counter advances on each simulated timeout; at threshold the flag is armed;
 *       below threshold the flag stays clear.</li>
 *   <li>A success between timeouts resets the counter — the flag is never armed.</li>
 *   <li>When {@code restartsPaused} is set the arming guard prevents the flag from being set.</li>
 *   <li>{@code triggerForceRestartCheckForTest()} returns {@code true}, clears the flag, and
 *       resets the counter when all guards pass.</li>
 *   <li>{@code triggerForceRestartCheckForTest()} returns {@code false} when the flag is clear.</li>
 *   <li>The hard cap (MAX_REQUEST_TIMEOUT_RESTARTS = 5) eventually trips {@code restartsPaused}.</li>
 *   <li>The general churn ceiling trips {@code restartsPaused} when {@code consecutiveFailures}
 *       reaches the threshold.</li>
 * </ol>
 */
@DisplayName("AnseriniEmbeddingModelImpl request-timeout forced-restart")
class RequestTimeoutRestartTest {

    // Default threshold used by getMaxConsecutiveRequestTimeouts() when no -D override is set.
    private static final int THRESHOLD = 2;

    private AnseriniEmbeddingModelImpl model;

    @BeforeEach
    void setUp() {
        model = new AnseriniEmbeddingModelImpl("bge-base-en-v1.5", 32, 64, null);
        model.resetRestartGovernorForTest();
        System.clearProperty("kompile.embedding.maxConsecutiveRequestTimeouts");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────────

    /**
     * Simulate what the production TimeoutException catch block does:
     * increment counter and arm the flag when {@code n >= threshold} and guards pass.
     */
    private void simulateTimeout() {
        int n = model.getConsecutiveRequestTimeouts() + 1;
        model.setConsecutiveRequestTimeoutsForTest(n);
        if (n >= THRESHOLD && !model.isRestartsPaused()) {
            model.setForceRestartArmedForTest(true);
        }
    }

    /** Simulate what the production success path does: reset the counter. */
    private void simulateSuccess() {
        model.setConsecutiveRequestTimeoutsForTest(0);
    }

    // ── (a) Threshold timeouts arm the flag ──────────────────────────────────────────────────

    @Test
    @DisplayName("Counter advances on each timeout; flag armed at exactly threshold")
    void counterAdvancesAndFlagArmedAtThreshold() {
        assertFalse(model.isForceRestartArmed(), "Flag must start clear");

        for (int i = 1; i < THRESHOLD; i++) {
            simulateTimeout();
            assertFalse(model.isForceRestartArmed(),
                    "Flag must NOT be armed at count=" + i + " (threshold=" + THRESHOLD + ")");
            assertEquals(i, model.getConsecutiveRequestTimeouts());
        }

        // The threshold-th timeout must arm the flag.
        simulateTimeout();
        assertEquals(THRESHOLD, model.getConsecutiveRequestTimeouts());
        assertTrue(model.isForceRestartArmed(),
                "Flag must be armed when counter reaches threshold=" + THRESHOLD);
    }

    @Test
    @DisplayName("Single timeout (threshold=2) does NOT arm the flag")
    void singleTimeoutBelowThresholdLeavesFlag() {
        simulateTimeout();
        assertEquals(1, model.getConsecutiveRequestTimeouts());
        assertFalse(model.isForceRestartArmed(), "One timeout (< threshold) must leave flag clear");
    }

    // ── (b) Success between timeouts resets counter ──────────────────────────────────────────

    @Test
    @DisplayName("Success between timeouts resets counter — flag is never armed")
    void successBetweenTimeoutsResetsCounter() {
        simulateTimeout();
        assertEquals(1, model.getConsecutiveRequestTimeouts());

        simulateSuccess();
        assertEquals(0, model.getConsecutiveRequestTimeouts(), "Counter must reset after success");
        assertFalse(model.isForceRestartArmed(), "Flag must stay clear after success");

        // One more timeout after the reset: counter goes to 1, still below threshold.
        simulateTimeout();
        assertEquals(1, model.getConsecutiveRequestTimeouts());
        assertFalse(model.isForceRestartArmed(),
                "Flag must remain clear — single timeout after success is below threshold");
    }

    @Test
    @DisplayName("Two timeouts, then success, then two more timeouts — flag arms only on the second pair")
    void twoTimeoutsSuccessTwoMoreArms() {
        simulateTimeout();
        simulateTimeout();   // arms flag
        assertTrue(model.isForceRestartArmed(), "Flag must be armed after 2 timeouts");

        simulateSuccess();   // reset counter
        model.setForceRestartArmedForTest(false); // clear flag as ensureInitialized() would
        assertFalse(model.isForceRestartArmed());
        assertEquals(0, model.getConsecutiveRequestTimeouts());

        simulateTimeout();
        assertFalse(model.isForceRestartArmed(), "Flag still clear after 1 timeout");
        simulateTimeout();
        assertTrue(model.isForceRestartArmed(), "Flag re-armed after a second pair of timeouts");
    }

    // ── (c) restartsPaused suppresses arming ─────────────────────────────────────────────────

    @Test
    @DisplayName("When restartsPaused arming guard prevents the flag being set")
    void restartsPausedSuppressesArming() {
        model.setRestartsPausedForTest(true);
        assertTrue(model.isRestartsPaused(), "Pre-condition: restartsPaused must be true");

        simulateTimeout();
        simulateTimeout(); // would be at threshold, but guard blocks arming

        assertEquals(THRESHOLD, model.getConsecutiveRequestTimeouts());
        assertFalse(model.isForceRestartArmed(),
                "Flag must NOT be armed when restartsPaused=true, even at threshold");
    }

    @Test
    @DisplayName("When restartsPaused triggerForceRestartCheckForTest returns false even if flag armed")
    void restartsPausedSuppressesTriggerSeam() {
        // Arm the flag first (bypassing the guard, as if it was set before pause was tripped)
        model.setForceRestartArmedForTest(true);
        model.setRestartsPausedForTest(true);

        boolean proceeded = model.triggerForceRestartCheckForTest();
        assertFalse(proceeded, "Seam must return false when restartsPaused=true");
        // Flag must remain set (it was not consumed)
        assertTrue(model.isForceRestartArmed(),
                "Flag must stay armed — restart was suppressed by restartsPaused");
    }

    // ── (d) triggerForceRestartCheckForTest with flag armed ──────────────────────────────────

    @Test
    @DisplayName("triggerForceRestartCheckForTest returns true, clears flag, resets counter")
    void triggerSeamProceedsAndResetsState() {
        model.setForceRestartArmedForTest(true);
        model.setConsecutiveRequestTimeoutsForTest(5);

        assertTrue(model.isForceRestartArmed(), "Pre-condition: flag must be armed");
        assertEquals(5, model.getConsecutiveRequestTimeouts(), "Pre-condition: counter=5");

        boolean proceeded = model.triggerForceRestartCheckForTest();

        assertTrue(proceeded, "Seam must return true when flag armed and all guards pass");
        assertFalse(model.isForceRestartArmed(), "Flag must be cleared after trigger");
        assertEquals(0, model.getConsecutiveRequestTimeouts(),
                "Counter must be reset to 0 after forced-restart trigger");
        assertEquals(1, model.getRequestTimeoutForcedRestarts(),
                "requestTimeoutForcedRestarts must be incremented to 1");
        assertFalse(model.isRestartsPaused(),
                "restartsPaused must NOT be set after the first forced restart (below cap)");
    }

    @Test
    @DisplayName("triggerForceRestartCheckForTest returns false when flag is clear")
    void triggerSeamReturnsFalseWhenFlagClear() {
        assertFalse(model.isForceRestartArmed(), "Pre-condition: flag must be clear");
        boolean proceeded = model.triggerForceRestartCheckForTest();
        assertFalse(proceeded, "Seam must return false when forceRestartBeforeNextEmbed is not armed");
        assertEquals(0, model.getRequestTimeoutForcedRestarts(),
                "Restart counter must not be incremented when flag was not set");
    }

    // ── (e) Hard cap trips restartsPaused after MAX_REQUEST_TIMEOUT_RESTARTS ────────────────

    @Test
    @DisplayName("Hard cap: forced restarts beyond 5 trip restartsPaused (restart succeeds each time, resetting consecutiveFailures)")
    void hardCapTripsPausedAfterMaxRestarts() {
        // This scenario: each forced restart leads to a SUCCESSFUL model load (consecutiveFailures
        // resets to 0 on success), but the model keeps timing out.  Without MAX_REQUEST_TIMEOUT_RESTARTS
        // this would loop forever.  With it, the 6th attempt trips the hard cap.
        for (int i = 1; i <= 5; i++) {
            model.setForceRestartArmedForTest(true);
            boolean proceeded = model.triggerForceRestartCheckForTest();
            assertTrue(proceeded, "Forced restart #" + i + " within cap must proceed");
            assertFalse(model.isRestartsPaused(), "Must NOT be paused after restart #" + i);
            assertEquals(i, model.getRequestTimeoutForcedRestarts());
            // Simulate a successful model load between restarts: production code resets
            // consecutiveFailures to 0 at lines 457-458 of the init success path.
            model.setConsecutiveFailuresForTest(0);
        }

        // The 6th trigger must trip the hard cap (restartCount=6 > MAX=5)
        model.setForceRestartArmedForTest(true);
        boolean proceeded6 = model.triggerForceRestartCheckForTest();
        assertFalse(proceeded6, "6th forced restart (> cap of 5) must be suppressed");
        assertTrue(model.isRestartsPaused(),
                "restartsPaused must be tripped after exceeding MAX_REQUEST_TIMEOUT_RESTARTS=5");
        assertEquals(6, model.getRequestTimeoutForcedRestarts());
    }

    // ── (f) General churn ceiling also bounds forced restarts ────────────────────────────────

    @Test
    @DisplayName("General churn ceiling trips restartsPaused when consecutiveFailures reaches cap")
    void generalChurnCeilingTripsPaused() {
        // nativeCrashThreshold default=3; cap = max(2,3)=3.
        // Pre-load to 2 so the seam increments it to 3 and hits the ceiling.
        model.setConsecutiveFailuresForTest(2);
        model.setForceRestartArmedForTest(true);

        boolean proceeded = model.triggerForceRestartCheckForTest();
        assertFalse(proceeded, "Forced restart must be suppressed when general churn ceiling is hit");
        assertTrue(model.isRestartsPaused(),
                "restartsPaused must be tripped when consecutiveFailures reaches the cap");
    }
}
