package ai.kompile.cli.main.chat.harness.eval;

import ai.kompile.cli.main.chat.harness.TaskOutcome;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@TemporaryUserHome
class EvalResultStoreTest {

    @TempDir
    Path tempDir;

    private EvalResultStore store;

    @BeforeEach
    void setUp() {
        store = new EvalResultStore(tempDir.resolve("eval-results.json"));
        store.load();
    }

    // ========================================================================
    // Basic CRUD
    // ========================================================================

    @Test
    void emptyStoreHasZeroSize() {
        assertEquals(0, store.size());
        assertTrue(store.getSuiteNames().isEmpty());
    }

    @Test
    void recordAndRetrieve() {
        EvalRunResult run = makeRunResult("Suite A", true, 3, 3);
        store.record(run);

        assertEquals(1, store.size());
        assertTrue(store.getSuiteNames().contains("Suite A"));

        EvalRunResult latest = store.getLatestRun("Suite A");
        assertNotNull(latest);
        assertEquals("Suite A", latest.getSuiteName());
        assertTrue(latest.isSuitePassed());
    }

    @Test
    void multipleRunsForSameSuite() {
        store.record(makeRunResult("Suite A", true, 5, 5));
        store.record(makeRunResult("Suite A", false, 3, 5));
        store.record(makeRunResult("Suite A", true, 4, 5));

        assertEquals(3, store.size());

        // Latest should be the last one recorded
        EvalRunResult latest = store.getLatestRun("Suite A");
        assertNotNull(latest);
        assertTrue(latest.isSuitePassed());
        assertEquals(4, latest.getPassedCases());
    }

    @Test
    void multipleSuites() {
        store.record(makeRunResult("Suite A", true, 5, 5));
        store.record(makeRunResult("Suite B", false, 2, 5));

        assertEquals(2, store.size());

        List<String> names = store.getSuiteNames();
        assertTrue(names.contains("Suite A"));
        assertTrue(names.contains("Suite B"));

        assertTrue(store.getLatestRun("Suite A").isSuitePassed());
        assertFalse(store.getLatestRun("Suite B").isSuitePassed());
    }

    // ========================================================================
    // Filtering and queries
    // ========================================================================

    @Test
    void getRecentRuns_filtersBySuite() {
        store.record(makeRunResult("Suite A", true, 5, 5));
        store.record(makeRunResult("Suite B", true, 3, 3));
        store.record(makeRunResult("Suite A", false, 2, 5));

        List<EvalRunResult> suiteARuns = store.getRecentRuns("Suite A", 10);
        assertEquals(2, suiteARuns.size());

        List<EvalRunResult> suiteBRuns = store.getRecentRuns("Suite B", 10);
        assertEquals(1, suiteBRuns.size());
    }

    @Test
    void getRecentRuns_respectsLimit() {
        for (int i = 0; i < 20; i++) {
            store.record(makeRunResult("Suite A", true, 5, 5));
        }

        List<EvalRunResult> limited = store.getRecentRuns(null, 5);
        assertEquals(5, limited.size());
    }

    @Test
    void getRecentRuns_allSuites() {
        store.record(makeRunResult("A", true, 5, 5));
        store.record(makeRunResult("B", true, 3, 3));
        store.record(makeRunResult("C", true, 2, 2));

        List<EvalRunResult> all = store.getRecentRuns(null, 10);
        assertEquals(3, all.size());
    }

    @Test
    void getLatestRun_nonExistentSuite() {
        store.record(makeRunResult("Suite A", true, 5, 5));
        assertNull(store.getLatestRun("Nonexistent"));
    }

    // ========================================================================
    // Trend analysis
    // ========================================================================

    @Test
    void passRateTrend() {
        store.record(makeRunResult("Suite A", true, 5, 5));    // 1.0
        store.record(makeRunResult("Suite A", false, 3, 5));   // 0.6
        store.record(makeRunResult("Suite A", true, 4, 5));    // 0.8

        double[] trend = store.getPassRateTrend("Suite A", 10);
        assertEquals(3, trend.length);
        assertEquals(1.0, trend[0], 0.001);
        assertEquals(0.6, trend[1], 0.001);
        assertEquals(0.8, trend[2], 0.001);
    }

    @Test
    void passRateTrend_truncated() {
        for (int i = 0; i < 20; i++) {
            store.record(makeRunResult("Suite A", true, 5, 5));
        }

        double[] trend = store.getPassRateTrend("Suite A", 5);
        assertEquals(5, trend.length);
    }

    @Test
    void passRateTrend_nonExistentSuite() {
        double[] trend = store.getPassRateTrend("Nonexistent", 10);
        assertEquals(0, trend.length);
    }

    // ========================================================================
    // Persistence
    // ========================================================================

    @Test
    void saveAndReload() {
        store.record(makeRunResult("Suite A", true, 5, 5));
        store.record(makeRunResult("Suite B", false, 2, 5));

        // Create new store pointing at same file
        EvalResultStore reloaded = new EvalResultStore(tempDir.resolve("eval-results.json"));
        reloaded.load();

        assertEquals(2, reloaded.size());
        assertTrue(reloaded.getSuiteNames().contains("Suite A"));
        assertTrue(reloaded.getSuiteNames().contains("Suite B"));
        assertTrue(reloaded.getLatestRun("Suite A").isSuitePassed());
        assertFalse(reloaded.getLatestRun("Suite B").isSuitePassed());
    }

    @Test
    void defaultStoreFollowsTheCurrentHome() {
        Path first = tempDir.resolve("first-home");
        Path second = tempDir.resolve("second-home");
        String original = System.getProperty("user.home");
        try {
            // The class can first load while an earlier home is current.
            System.setProperty("user.home", first.toString());
            new EvalResultStore().record(makeRunResult("First", true, 1, 1));

            System.setProperty("user.home", second.toString());
            EvalResultStore current = new EvalResultStore();
            current.load();
            assertEquals(0, current.size(), "the second home has no runs yet");
            current.record(makeRunResult("Second", true, 1, 1));

            Path firstFile = first.resolve(".kompile").resolve("eval-results.json");
            Path secondFile = second.resolve(".kompile").resolve("eval-results.json");
            assertTrue(Files.exists(firstFile));
            assertTrue(Files.exists(secondFile));
            EvalResultStore reread = new EvalResultStore(secondFile);
            reread.load();
            assertEquals(List.of("Second"), reread.getSuiteNames());
        } finally {
            if (original == null) System.clearProperty("user.home");
            else System.setProperty("user.home", original);
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private EvalRunResult makeRunResult(String suiteName, boolean passed,
                                         int passedCases, int totalCases) {
        EvalRunResult run = new EvalRunResult();
        run.setRunId("run-" + System.nanoTime());
        run.setSuiteName(suiteName);
        run.setStartTime(Instant.now().minusSeconds(10));
        run.setEndTime(Instant.now());

        List<EvalCaseResult> results = new java.util.ArrayList<>();
        for (int i = 0; i < totalCases; i++) {
            EvalCaseResult cr = new EvalCaseResult();
            cr.setCaseId("case-" + i);
            cr.setOutcome(i < passedCases ? TaskOutcome.COMPLETED : TaskOutcome.FAILED);
            cr.setOutcomeReason(cr.getOutcome().name());
            cr.setAssertionResults(List.of());
            results.add(cr);
        }
        run.setCaseResults(results);
        run.computeAggregates(0.8);
        return run;
    }
}
