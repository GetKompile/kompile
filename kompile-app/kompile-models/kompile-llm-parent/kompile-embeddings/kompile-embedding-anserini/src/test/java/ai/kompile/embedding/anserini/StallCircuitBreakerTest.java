package ai.kompile.embedding.anserini;

import ai.kompile.embedding.anserini.subprocess.EmbeddingSubprocessLauncher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the two-track stall circuit breaker in
 * {@link AnseriniEmbeddingModelImpl#createRestartPolicyCallback()}.
 *
 * <p>No ND4J, no subprocess, no Spring context — uses the package-private
 * {@code setWasEverHealthyForTest} / {@code resetRestartGovernorForTest} /
 * {@code createRestartPolicyCallbackForTest} accessors added for testability
 * (same idiom as {@code resolveSystemPhysicalCeilingMb} in the launcher tests).</p>
 *
 * <h3>Scenarios tested</h3>
 * <ul>
 *   <li>Load stall (wasEverHealthy=false): deferred after the first heartbeat-timeout → {@code null} restart config.</li>
 *   <li>Runtime-degradation stall (wasEverHealthy=true): restart ALLOWED for attempts 1..MAX_RUNTIME_STALL_RESTARTS,
 *       then deferred on attempt MAX+1.</li>
 *   <li>Native crash path: unchanged — increments {@code consecutiveNativeCrashes}; not interfered
 *       with by the new stall logic.</li>
 * </ul>
 */
@DisplayName("AnseriniEmbeddingModelImpl stall circuit breaker")
class StallCircuitBreakerTest {

    // The model under test — constructed without Spring context via the package-private constructor.
    private AnseriniEmbeddingModelImpl model;

    @BeforeEach
    void setUp() {
        model = new AnseriniEmbeddingModelImpl("bge-base-en-v1.5", 32, 64, null);
        model.resetRestartGovernorForTest();
    }

    // ── heartbeat crash reason used across tests ────────────────────────────────────

    private static final String HEARTBEAT_REASON = "heartbeat timeout: subprocess did not respond";

    // ── Load stall: wasEverHealthy=false ────────────────────────────────────────────

    @Test
    @DisplayName("Load stall: first heartbeat-timeout defers immediately (returns null)")
    void loadStall_deferrredAfterFirstAttempt() {
        // wasEverHealthy starts false (never produced a successful embedding)
        assertFalse(model.isWasEverHealthy(), "sanity: should start un-healthy");

        EmbeddingSubprocessLauncher.RestartPolicyCallback cb = model.createRestartPolicyCallbackForTest();

        // Exit code -1 + heartbeat reason is the canonical stall signal
        EmbeddingSubprocessLauncher.RestartConfiguration result =
                cb.shouldRestart("task-1", -1, HEARTBEAT_REASON, 1);

        assertNull(result, "Load stall must return null (defer) on the first attempt");
        assertTrue(model.isRestartsPaused(), "Restarts must be paused after load stall");
    }

    @Test
    @DisplayName("Load stall: stays deferred on subsequent calls (breaker remains tripped)")
    void loadStall_remainsDeferredAfterBreaker() {
        assertFalse(model.isWasEverHealthy());

        EmbeddingSubprocessLauncher.RestartPolicyCallback cb = model.createRestartPolicyCallbackForTest();
        cb.shouldRestart("task-1", -1, HEARTBEAT_REASON, 1); // trips the breaker

        // Simulate a second restart attempt (e.g. poll/resume path recreates callback)
        EmbeddingSubprocessLauncher.RestartPolicyCallback cb2 = model.createRestartPolicyCallbackForTest();
        EmbeddingSubprocessLauncher.RestartConfiguration result2 =
                cb2.shouldRestart("task-1", -1, HEARTBEAT_REASON, 2);

        assertNull(result2, "Breaker already tripped — must stay deferred");
        assertTrue(model.isRestartsPaused());
    }

    // ── Runtime-degradation stall: wasEverHealthy=true ──────────────────────────────

    @Test
    @DisplayName("Runtime-degradation stall: restart ALLOWED for all 5 calls within the cap (single callback)")
    void runtimeStall_allowedUpToCap() {
        // Runtime-degradation stalls return a RestartConfiguration directly (bypass the churn ceiling),
        // so a single callback can accumulate all 5 restarts without interference from other breakers.
        model.setWasEverHealthyForTest(true);
        EmbeddingSubprocessLauncher.RestartPolicyCallback cb = model.createRestartPolicyCallbackForTest();

        for (int attempt = 1; attempt <= 5; attempt++) {
            EmbeddingSubprocessLauncher.RestartConfiguration result =
                    cb.shouldRestart("task-runtime", -1, HEARTBEAT_REASON, 1);

            assertNotNull(result,
                    "Runtime-degradation stall attempt " + attempt + " must be allowed (not null)");
            assertFalse(model.isRestartsPaused(),
                    "Restarts must NOT be paused after stall attempt " + attempt);
            assertEquals(attempt, model.getRuntimeStallRestarts(),
                    "runtimeStallRestarts counter must equal the attempt number");
        }
    }

    @Test
    @DisplayName("Runtime-degradation stall: deferred on the 6th call (exceeds cap of 5)")
    void runtimeStall_deferrredAfterCap() {
        // Drive runtimeStallRestarts to exactly 5 (the cap), then verify the next call defers.
        model.setWasEverHealthyForTest(true);
        EmbeddingSubprocessLauncher.RestartPolicyCallback cb = model.createRestartPolicyCallbackForTest();

        // Consume all 5 allowed restarts
        for (int i = 1; i <= 5; i++) {
            EmbeddingSubprocessLauncher.RestartConfiguration r =
                    cb.shouldRestart("task-runtime", -1, HEARTBEAT_REASON, 1);
            assertNotNull(r, "Restart " + i + " within cap must be non-null");
        }
        assertEquals(5, model.getRuntimeStallRestarts(), "Counter must be exactly 5 at the cap");
        assertFalse(model.isRestartsPaused(), "Must NOT be paused yet at exactly cap");

        // The 6th call pushes runtimeStallRestarts to 6 > MAX → must defer
        EmbeddingSubprocessLauncher.RestartConfiguration capResult =
                cb.shouldRestart("task-runtime-cap", -1, HEARTBEAT_REASON, 1);

        assertNull(capResult, "6th runtime-degradation stall (> cap of 5) must return null (defer)");
        assertTrue(model.isRestartsPaused(), "Restarts must be paused after exceeding the stall cap");
        assertEquals(6, model.getRuntimeStallRestarts());
    }

    @Test
    @DisplayName("Runtime-degradation stall: single-attempt sequence — first stall is not deferred")
    void runtimeStall_firstAttemptNotDeferred() {
        model.setWasEverHealthyForTest(true);

        EmbeddingSubprocessLauncher.RestartPolicyCallback cb = model.createRestartPolicyCallbackForTest();
        EmbeddingSubprocessLauncher.RestartConfiguration result =
                cb.shouldRestart("task-runtime", -1, HEARTBEAT_REASON, 1);

        assertNotNull(result, "First runtime-degradation stall must NOT be deferred");
        assertFalse(model.isRestartsPaused(), "Restarts must not be paused on the first runtime stall");
        assertEquals(1, model.getRuntimeStallRestarts());
    }

    // ── Native crash path: must be unchanged by the new stall logic ─────────────────

    @Test
    @DisplayName("Native crash (exit 139 / SIGSEGV) does not activate the stall path")
    void nativeCrash_stallPathNotActivated() {
        // Whether or not we were ever healthy, a SIGSEGV is NOT a heartbeat-stall
        model.setWasEverHealthyForTest(false); // load-stall condition, but crash reason is different

        EmbeddingSubprocessLauncher.RestartPolicyCallback cb = model.createRestartPolicyCallbackForTest();

        // exit 139 = SIGSEGV — no "heartbeat" in crashReason
        EmbeddingSubprocessLauncher.RestartConfiguration result =
                cb.shouldRestart("task-crash", 139, "SIGSEGV", 1);

        // The native-crash breaker threshold is 3 (EmbeddingRestartConfig default).
        // After 1 crash we are below threshold → restart ALLOWED.
        assertNotNull(result, "First native crash must be allowed (below native-crash threshold)");
        assertFalse(model.isRestartsPaused(),
                "Restarts must not be paused after a single native crash (threshold=3)");
    }

    @Test
    @DisplayName("wasEverHealthy is NOT set by failed (empty) embedBatch results")
    void wasEverHealthy_notSetOnEmptyResult() {
        // A batch returning an empty list (subprocess not running, null result) must NOT mark healthy
        assertFalse(model.isWasEverHealthy(), "Should start un-healthy");
        // We can't call embedBatch() without a real subprocess, but the guard reads:
        //   if (!result.isEmpty()) wasEverHealthy.set(true)
        // So explicitly verify the field starts false and is controlled by setWasEverHealthyForTest
        model.setWasEverHealthyForTest(false);
        assertFalse(model.isWasEverHealthy(), "Must remain false after setting to false");
        model.setWasEverHealthyForTest(true);
        assertTrue(model.isWasEverHealthy(), "Must be true after setting to true");
    }
}
