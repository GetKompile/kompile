package ai.kompile.cli.main.chat.harness.eval;

import ai.kompile.cli.main.chat.harness.TaskOutcome;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EvalRunResultTest {

    // ========================================================================
    // Aggregate computation
    // ========================================================================

    @Test
    void computeAggregates_allPassed() {
        EvalRunResult runResult = new EvalRunResult();
        runResult.setRunId("test-run");
        runResult.setSuiteName("Test");
        runResult.setStartTime(Instant.now().minusSeconds(10));
        runResult.setEndTime(Instant.now());

        List<EvalCaseResult> results = List.of(
                makeResult("c1", TaskOutcome.COMPLETED),
                makeResult("c2", TaskOutcome.COMPLETED),
                makeResult("c3", TaskOutcome.COMPLETED)
        );
        runResult.setCaseResults(results);
        runResult.computeAggregates(0.8);

        assertEquals(3, runResult.getTotalCases());
        assertEquals(3, runResult.getPassedCases());
        assertEquals(0, runResult.getFailedCases());
        assertEquals(1.0, runResult.getPassRate(), 0.001);
        assertTrue(runResult.isSuitePassed());
    }

    @Test
    void computeAggregates_belowPassRate() {
        EvalRunResult runResult = new EvalRunResult();
        runResult.setStartTime(Instant.now().minusSeconds(5));
        runResult.setEndTime(Instant.now());

        List<EvalCaseResult> results = List.of(
                makeResult("c1", TaskOutcome.COMPLETED),
                makeResult("c2", TaskOutcome.FAILED),
                makeResult("c3", TaskOutcome.FAILED),
                makeResult("c4", TaskOutcome.ESCAPED)
        );
        runResult.setCaseResults(results);
        runResult.computeAggregates(0.8);

        assertEquals(4, runResult.getTotalCases());
        assertEquals(1, runResult.getPassedCases());
        assertEquals(3, runResult.getFailedCases());
        assertEquals(0.25, runResult.getPassRate(), 0.001);
        assertFalse(runResult.isSuitePassed());
        assertEquals(0.8, runResult.getRequiredPassRate(), 0.001);
    }

    @Test
    void computeAggregates_exactlyAtPassRate() {
        EvalRunResult runResult = new EvalRunResult();
        runResult.setStartTime(Instant.now().minusSeconds(5));
        runResult.setEndTime(Instant.now());

        List<EvalCaseResult> results = List.of(
                makeResult("c1", TaskOutcome.COMPLETED),
                makeResult("c2", TaskOutcome.COMPLETED),
                makeResult("c3", TaskOutcome.COMPLETED),
                makeResult("c4", TaskOutcome.COMPLETED),
                makeResult("c5", TaskOutcome.FAILED)
        );
        runResult.setCaseResults(results);
        runResult.computeAggregates(0.8);

        assertEquals(0.8, runResult.getPassRate(), 0.001);
        assertTrue(runResult.isSuitePassed());
    }

    @Test
    void computeAggregates_outcomeCounts() {
        EvalRunResult runResult = new EvalRunResult();
        runResult.setStartTime(Instant.now().minusSeconds(5));
        runResult.setEndTime(Instant.now());

        List<EvalCaseResult> results = List.of(
                makeResult("c1", TaskOutcome.COMPLETED),
                makeResult("c2", TaskOutcome.COMPLETED),
                makeResult("c3", TaskOutcome.FAILED),
                makeResult("c4", TaskOutcome.ESCAPED),
                makeResult("c5", TaskOutcome.TIMED_OUT)
        );
        runResult.setCaseResults(results);
        runResult.computeAggregates(0.5);

        assertEquals(2, runResult.getOutcomeCounts().get("COMPLETED").intValue());
        assertEquals(1, runResult.getOutcomeCounts().get("FAILED").intValue());
        assertEquals(1, runResult.getOutcomeCounts().get("ESCAPED").intValue());
        assertEquals(1, runResult.getOutcomeCounts().get("TIMED_OUT").intValue());
    }

    @Test
    void computeAggregates_averageJudgeScores() {
        EvalRunResult runResult = new EvalRunResult();
        runResult.setStartTime(Instant.now().minusSeconds(5));
        runResult.setEndTime(Instant.now());

        EvalCaseResult r1 = makeResult("c1", TaskOutcome.COMPLETED);
        r1.setJudgeCorrectness(4.0f);
        r1.setJudgeCompleteness(5.0f);
        r1.setCompositeScore(4.5f);

        EvalCaseResult r2 = makeResult("c2", TaskOutcome.COMPLETED);
        r2.setJudgeCorrectness(3.0f);
        r2.setJudgeCompleteness(3.0f);
        r2.setCompositeScore(3.0f);

        runResult.setCaseResults(List.of(r1, r2));
        runResult.computeAggregates(0.5);

        assertEquals(3.5f, runResult.getAvgJudgeCorrectness(), 0.01);
        assertEquals(4.0f, runResult.getAvgJudgeCompleteness(), 0.01);
        assertEquals(3.75f, runResult.getAvgCompositeScore(), 0.01);
    }

    @Test
    void computeAggregates_durationMs() {
        EvalRunResult runResult = new EvalRunResult();
        Instant start = Instant.now().minusSeconds(30);
        runResult.setStartTime(start);
        runResult.setEndTime(start.plusMillis(30_000));

        EvalCaseResult r1 = makeResult("c1", TaskOutcome.COMPLETED);
        r1.setExecutionTimeMs(15_000);
        EvalCaseResult r2 = makeResult("c2", TaskOutcome.COMPLETED);
        r2.setExecutionTimeMs(10_000);

        runResult.setCaseResults(List.of(r1, r2));
        runResult.computeAggregates(0.5);

        assertEquals(30_000, runResult.getTotalDurationMs());
    }

    @Test
    void generateSummary_containsKeyInfo() {
        EvalRunResult runResult = new EvalRunResult();
        runResult.setSuiteName("My Suite");
        runResult.setStartTime(Instant.now().minusSeconds(5));
        runResult.setEndTime(Instant.now());

        runResult.setCaseResults(List.of(
                makeResult("c1", TaskOutcome.COMPLETED),
                makeResult("c2", TaskOutcome.FAILED)
        ));
        runResult.computeAggregates(0.8);

        String summary = runResult.generateSummary();
        assertNotNull(summary);
        assertTrue(summary.contains("My Suite"));
        assertTrue(summary.contains("1/2"));
    }

    // ========================================================================
    // Empty cases
    // ========================================================================

    @Test
    void computeAggregates_emptyCaseList() {
        EvalRunResult runResult = new EvalRunResult();
        runResult.setStartTime(Instant.now());
        runResult.setEndTime(Instant.now());
        runResult.setCaseResults(List.of());
        runResult.computeAggregates(0.8);

        assertEquals(0, runResult.getTotalCases());
        assertEquals(0, runResult.getPassedCases());
        // Should not throw on empty list
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private EvalCaseResult makeResult(String caseId, TaskOutcome outcome) {
        EvalCaseResult result = new EvalCaseResult();
        result.setCaseId(caseId);
        result.setOutcome(outcome);
        result.setOutcomeReason(outcome.name());
        result.setAssertionResults(List.of());
        return result;
    }
}
