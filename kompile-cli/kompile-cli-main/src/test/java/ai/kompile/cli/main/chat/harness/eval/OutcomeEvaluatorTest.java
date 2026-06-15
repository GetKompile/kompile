package ai.kompile.cli.main.chat.harness.eval;

import ai.kompile.cli.main.chat.harness.EscapeDetector;
import ai.kompile.cli.main.chat.harness.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OutcomeEvaluatorTest {

    private OutcomeEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new OutcomeEvaluator();
    }

    // ========================================================================
    // Basic outcome determination
    // ========================================================================

    @Test
    void allAssertionsPass_yieldsCompleted() {
        EvalCase evalCase = makeCase(
                Assertion.noEscape(),
                Assertion.outputContains("hello")
        );

        EvalCaseResult result = evaluator.evaluate(
                evalCase, "hello world",
                noEscape(), false, 0, null,
                0, 0, 0, null);

        assertEquals(TaskOutcome.COMPLETED, result.getOutcome());
        assertTrue(result.passed());
        assertEquals(2, result.getAssertionsPassed());
        assertEquals(2, result.getAssertionsTotal());
        assertEquals(0, result.getCriticalAssertionsFailed());
    }

    @Test
    void criticalAssertionFails_yieldsFailed() {
        EvalCase evalCase = makeCase(
                Assertion.outputContains("missing_text")
        );

        EvalCaseResult result = evaluator.evaluate(
                evalCase, "some other output",
                noEscape(), false, 0, null,
                0, 0, 0, null);

        assertEquals(TaskOutcome.FAILED, result.getOutcome());
        assertFalse(result.passed());
        assertEquals(1, result.getCriticalAssertionsFailed());
    }

    @Test
    void nonCriticalAssertionFails_yieldsPartial() {
        Assertion critical = Assertion.outputContains("hello");
        Assertion nonCritical = new Assertion();
        nonCritical.setType(Assertion.Type.OUTPUT_CONTAINS);
        nonCritical.setValue("bonus");
        nonCritical.setCritical(false);
        nonCritical.setDescription("Optional bonus text");

        EvalCase evalCase = makeCase(critical, nonCritical);

        EvalCaseResult result = evaluator.evaluate(
                evalCase, "hello world",
                noEscape(), false, 0, null,
                0, 0, 0, null);

        assertEquals(TaskOutcome.PARTIAL, result.getOutcome());
        assertEquals(1, result.getAssertionsPassed());
        assertEquals(2, result.getAssertionsTotal());
        assertEquals(0, result.getCriticalAssertionsFailed());
    }

    // ========================================================================
    // Escape detection priority
    // ========================================================================

    @Test
    void escapeDetected_withNoEscapeAssertion_yieldsEscaped() {
        EvalCase evalCase = makeCase(Assertion.noEscape());

        EscapeDetector.EscapeResult escape = new EscapeDetector.EscapeResult(
                true, EscapeDetector.EscapeType.EXPLICIT_REFUSAL,
                "I cannot do that", 0.95f);

        EvalCaseResult result = evaluator.evaluate(
                evalCase, "I cannot do that",
                escape, false, 0, null,
                0, 0, 0, null);

        assertEquals(TaskOutcome.ESCAPED, result.getOutcome());
        assertTrue(result.isHadEscape());
    }

    @Test
    void hardEscapeEmptyOutput_yieldsEscaped_evenWithoutNoEscapeAssertion() {
        EvalCase evalCase = makeCase(Assertion.outputContains("something"));

        EscapeDetector.EscapeResult escape = new EscapeDetector.EscapeResult(
                true, EscapeDetector.EscapeType.EMPTY_OUTPUT,
                "Empty output", 1.0f);

        EvalCaseResult result = evaluator.evaluate(
                evalCase, "",
                escape, false, 0, null,
                0, 0, 0, null);

        assertEquals(TaskOutcome.ESCAPED, result.getOutcome());
    }

    // ========================================================================
    // Timeout / max steps
    // ========================================================================

    @Test
    void hitMaxSteps_yieldsTimedOut() {
        EvalCase evalCase = makeCase(Assertion.noEscape());

        EvalCaseResult result = evaluator.evaluate(
                evalCase, "partial output",
                noEscape(), true, 0, null,
                0, 0, 0, null);

        assertEquals(TaskOutcome.TIMED_OUT, result.getOutcome());
    }

    @Test
    void escapeBeatsTimeout() {
        EvalCase evalCase = makeCase(Assertion.noEscape());

        EscapeDetector.EscapeResult escape = new EscapeDetector.EscapeResult(
                true, EscapeDetector.EscapeType.EXPLICIT_REFUSAL,
                "Refused", 0.9f);

        EvalCaseResult result = evaluator.evaluate(
                evalCase, "I refuse",
                escape, true, 0, null,
                0, 0, 0, null);

        // Escape has higher priority than timeout
        assertEquals(TaskOutcome.ESCAPED, result.getOutcome());
    }

    // ========================================================================
    // No assertions — judge-based outcome
    // ========================================================================

    @Test
    void noAssertions_noJudge_yieldsUnknown() {
        EvalCase evalCase = makeCase(); // no assertions

        EvalCaseResult result = evaluator.evaluate(
                evalCase, "some output",
                noEscape(), false, 0, null,
                0, 0, 0, null);

        assertEquals(TaskOutcome.UNKNOWN, result.getOutcome());
    }

    @Test
    void noAssertions_highJudgeScores_yieldsCompleted() {
        EvalCase evalCase = makeCase();

        EvalCaseResult result = evaluator.evaluate(
                evalCase, "good output",
                noEscape(), false, 0, null,
                0, 4.5f, 4.2f, "Good work");

        assertEquals(TaskOutcome.COMPLETED, result.getOutcome());
        assertTrue(result.getOutcomeReason().contains("judge scores"));
    }

    @Test
    void noAssertions_mediumJudgeScores_yieldsPartial() {
        EvalCase evalCase = makeCase();

        EvalCaseResult result = evaluator.evaluate(
                evalCase, "ok output",
                noEscape(), false, 0, null,
                0, 3.5f, 3.0f, "Partial");

        assertEquals(TaskOutcome.PARTIAL, result.getOutcome());
    }

    @Test
    void noAssertions_lowJudgeScores_yieldsFailed() {
        EvalCase evalCase = makeCase();

        EvalCaseResult result = evaluator.evaluate(
                evalCase, "bad output",
                noEscape(), false, 0, null,
                0, 1.5f, 2.0f, "Poor");

        assertEquals(TaskOutcome.FAILED, result.getOutcome());
    }

    // ========================================================================
    // Individual assertion types
    // ========================================================================

    @Test
    void outputContains_caseInsensitive() {
        EvalCase evalCase = makeCase(Assertion.outputContains("HELLO"));

        EvalCaseResult result = evaluator.evaluate(
                evalCase, "Hello World",
                noEscape(), false, 0, null,
                0, 0, 0, null);

        assertEquals(TaskOutcome.COMPLETED, result.getOutcome());
    }

    @Test
    void outputNotContains_passes() {
        Assertion a = new Assertion();
        a.setType(Assertion.Type.OUTPUT_NOT_CONTAINS);
        a.setValue("error");
        EvalCase evalCase = makeCase(a);

        EvalCaseResult result = evaluator.evaluate(
                evalCase, "All good, no problems",
                noEscape(), false, 0, null,
                0, 0, 0, null);

        assertEquals(TaskOutcome.COMPLETED, result.getOutcome());
    }

    @Test
    void outputNotContains_fails() {
        Assertion a = new Assertion();
        a.setType(Assertion.Type.OUTPUT_NOT_CONTAINS);
        a.setValue("error");
        EvalCase evalCase = makeCase(a);

        EvalCaseResult result = evaluator.evaluate(
                evalCase, "There was an error in processing",
                noEscape(), false, 0, null,
                0, 0, 0, null);

        assertEquals(TaskOutcome.FAILED, result.getOutcome());
    }

    @Test
    void outputMatchesRegex_passes() {
        Assertion a = new Assertion();
        a.setType(Assertion.Type.OUTPUT_MATCHES_REGEX);
        a.setValue("\\d{4}-\\d{2}-\\d{2}");
        EvalCase evalCase = makeCase(a);

        EvalCaseResult result = evaluator.evaluate(
                evalCase, "Date: 2026-05-27",
                noEscape(), false, 0, null,
                0, 0, 0, null);

        assertEquals(TaskOutcome.COMPLETED, result.getOutcome());
    }

    @Test
    void outputMatchesRegex_fails() {
        Assertion a = new Assertion();
        a.setType(Assertion.Type.OUTPUT_MATCHES_REGEX);
        a.setValue("^\\d+$");
        EvalCase evalCase = makeCase(a);

        EvalCaseResult result = evaluator.evaluate(
                evalCase, "not a number",
                noEscape(), false, 0, null,
                0, 0, 0, null);

        assertEquals(TaskOutcome.FAILED, result.getOutcome());
    }

    @Test
    void outputMatchesRegex_invalidRegex() {
        Assertion a = new Assertion();
        a.setType(Assertion.Type.OUTPUT_MATCHES_REGEX);
        a.setValue("[invalid");
        EvalCase evalCase = makeCase(a);

        EvalCaseResult result = evaluator.evaluate(
                evalCase, "anything",
                noEscape(), false, 0, null,
                0, 0, 0, null);

        assertEquals(TaskOutcome.FAILED, result.getOutcome());
        assertTrue(result.getAssertionResults().get(0).detail().contains("Invalid regex"));
    }

    @Test
    void toolWasCalled_passes() {
        Assertion a = Assertion.toolWasCalled("Read");
        EvalCase evalCase = makeCase(a);

        Map<String, Integer> toolCalls = Map.of("Read", 3, "Grep", 1);
        EvalCaseResult result = evaluator.evaluate(
                evalCase, "output",
                noEscape(), false, 0, toolCalls,
                0, 0, 0, null);

        assertEquals(TaskOutcome.COMPLETED, result.getOutcome());
    }

    @Test
    void toolWasCalled_fails_whenNotCalled() {
        Assertion a = Assertion.toolWasCalled("Write");
        EvalCase evalCase = makeCase(a);

        Map<String, Integer> toolCalls = Map.of("Read", 3);
        EvalCaseResult result = evaluator.evaluate(
                evalCase, "output",
                noEscape(), false, 0, toolCalls,
                0, 0, 0, null);

        assertEquals(TaskOutcome.FAILED, result.getOutcome());
    }

    @Test
    void toolWasCalled_fails_whenToolCallsNull() {
        Assertion a = Assertion.toolWasCalled("Read");
        EvalCase evalCase = makeCase(a);

        EvalCaseResult result = evaluator.evaluate(
                evalCase, "output",
                noEscape(), false, 0, null,
                0, 0, 0, null);

        assertEquals(TaskOutcome.FAILED, result.getOutcome());
    }

    @Test
    void toolNotCalled_passes() {
        Assertion a = new Assertion();
        a.setType(Assertion.Type.TOOL_NOT_CALLED);
        a.setValue("Write");
        EvalCase evalCase = makeCase(a);

        Map<String, Integer> toolCalls = Map.of("Read", 3);
        EvalCaseResult result = evaluator.evaluate(
                evalCase, "output",
                noEscape(), false, 0, toolCalls,
                0, 0, 0, null);

        assertEquals(TaskOutcome.COMPLETED, result.getOutcome());
    }

    @Test
    void noToolErrors_passes() {
        Assertion a = new Assertion();
        a.setType(Assertion.Type.NO_TOOL_ERRORS);
        EvalCase evalCase = makeCase(a);

        EvalCaseResult result = evaluator.evaluate(
                evalCase, "output",
                noEscape(), false, 0, null,
                0, 0, 0, null);

        assertEquals(TaskOutcome.COMPLETED, result.getOutcome());
    }

    @Test
    void noToolErrors_fails() {
        Assertion a = new Assertion();
        a.setType(Assertion.Type.NO_TOOL_ERRORS);
        EvalCase evalCase = makeCase(a);

        EvalCaseResult result = evaluator.evaluate(
                evalCase, "output",
                noEscape(), false, 3, null,
                0, 0, 0, null);

        assertEquals(TaskOutcome.FAILED, result.getOutcome());
    }

    @Test
    void maxStepsNotHit_passes() {
        Assertion a = new Assertion();
        a.setType(Assertion.Type.MAX_STEPS_NOT_HIT);
        EvalCase evalCase = makeCase(a);

        EvalCaseResult result = evaluator.evaluate(
                evalCase, "output",
                noEscape(), false, 0, null,
                0, 0, 0, null);

        assertEquals(TaskOutcome.COMPLETED, result.getOutcome());
    }

    @Test
    void scoreAbove_passes() {
        Assertion a = new Assertion();
        a.setType(Assertion.Type.SCORE_ABOVE);
        a.setValue("3.5");
        EvalCase evalCase = makeCase(a);

        EvalCaseResult result = evaluator.evaluate(
                evalCase, "output",
                noEscape(), false, 0, null,
                4.0f, 0, 0, null);

        assertEquals(TaskOutcome.COMPLETED, result.getOutcome());
    }

    @Test
    void scoreAbove_fails() {
        Assertion a = new Assertion();
        a.setType(Assertion.Type.SCORE_ABOVE);
        a.setValue("4.0");
        EvalCase evalCase = makeCase(a);

        EvalCaseResult result = evaluator.evaluate(
                evalCase, "output",
                noEscape(), false, 0, null,
                3.0f, 0, 0, null);

        assertEquals(TaskOutcome.FAILED, result.getOutcome());
    }

    @Test
    void judgeCorrectnessAbove_passes() {
        Assertion a = new Assertion();
        a.setType(Assertion.Type.JUDGE_CORRECTNESS_ABOVE);
        a.setValue("3.0");
        EvalCase evalCase = makeCase(a);

        EvalCaseResult result = evaluator.evaluate(
                evalCase, "output",
                noEscape(), false, 0, null,
                0, 4.0f, 0, null);

        assertEquals(TaskOutcome.COMPLETED, result.getOutcome());
    }

    @Test
    void judgeCompletenessAbove_fails() {
        Assertion a = new Assertion();
        a.setType(Assertion.Type.JUDGE_COMPLETENESS_ABOVE);
        a.setValue("4.0");
        EvalCase evalCase = makeCase(a);

        EvalCaseResult result = evaluator.evaluate(
                evalCase, "output",
                noEscape(), false, 0, null,
                0, 0, 3.0f, null);

        assertEquals(TaskOutcome.FAILED, result.getOutcome());
    }

    // ========================================================================
    // Edge cases
    // ========================================================================

    @Test
    void nullOutput_handledGracefully() {
        EvalCase evalCase = makeCase(Assertion.outputContains("hello"));

        EvalCaseResult result = evaluator.evaluate(
                evalCase, null,
                noEscape(), false, 0, null,
                0, 0, 0, null);

        assertEquals(TaskOutcome.FAILED, result.getOutcome());
    }

    @Test
    void assertionWithNoValue_failsGracefully() {
        Assertion a = new Assertion();
        a.setType(Assertion.Type.OUTPUT_CONTAINS);
        // no value set
        EvalCase evalCase = makeCase(a);

        EvalCaseResult result = evaluator.evaluate(
                evalCase, "output",
                noEscape(), false, 0, null,
                0, 0, 0, null);

        assertEquals(TaskOutcome.FAILED, result.getOutcome());
        assertTrue(result.getAssertionResults().get(0).detail().contains("No value"));
    }

    @Test
    void mixedCriticalAndNonCritical_correctCounting() {
        Assertion criticalPass = Assertion.outputContains("hello");
        Assertion criticalFail = Assertion.outputContains("missing");

        Assertion nonCriticalPass = new Assertion();
        nonCriticalPass.setType(Assertion.Type.OUTPUT_CONTAINS);
        nonCriticalPass.setValue("hello");
        nonCriticalPass.setCritical(false);

        EvalCase evalCase = makeCase(criticalPass, criticalFail, nonCriticalPass);

        EvalCaseResult result = evaluator.evaluate(
                evalCase, "hello world",
                noEscape(), false, 0, null,
                0, 0, 0, null);

        assertEquals(TaskOutcome.FAILED, result.getOutcome());
        assertEquals(2, result.getAssertionsPassed());  // criticalPass + nonCriticalPass
        assertEquals(3, result.getAssertionsTotal());
        assertEquals(1, result.getCriticalAssertionsFailed());
    }

    @Test
    void resultMetadataPopulated() {
        EvalCase evalCase = makeCase(Assertion.noEscape());
        evalCase.setId("test-123");
        evalCase.setName("Test Case");
        evalCase.setPrompt("Do something");

        EvalCaseResult result = evaluator.evaluate(
                evalCase, "done",
                noEscape(), false, 0, null,
                3.5f, 4.0f, 4.5f, "Good job");

        assertEquals("test-123", result.getCaseId());
        assertEquals("Test Case", result.getCaseName());
        assertEquals("Do something", result.getPrompt());
        assertEquals("done", result.getAgentOutput());
        assertEquals(3.5f, result.getCompositeScore());
        assertEquals(4.0f, result.getJudgeCorrectness());
        assertEquals(4.5f, result.getJudgeCompleteness());
        assertEquals("Good job", result.getJudgeReasoning());
        assertFalse(result.isHadEscape());
        assertFalse(result.isHitMaxSteps());
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private EvalCase makeCase(Assertion... assertions) {
        EvalCase evalCase = new EvalCase();
        evalCase.setId("test-case");
        evalCase.setName("Test");
        evalCase.setPrompt("Test prompt");
        List<Assertion> list = new ArrayList<>(List.of(assertions));
        evalCase.setAssertions(list);
        return evalCase;
    }

    private EscapeDetector.EscapeResult noEscape() {
        return new EscapeDetector.EscapeResult(
                false, EscapeDetector.EscapeType.NONE, null, 0);
    }
}
