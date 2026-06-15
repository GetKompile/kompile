/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.harness.eval;

import ai.kompile.cli.main.chat.harness.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Evaluates agent output against an {@link EvalCase}'s assertions to produce
 * a deterministic {@link TaskOutcome}.
 *
 * <p>Evaluation priority:
 * <ol>
 *   <li>If agent escaped (refusal, empty, loop) → {@code ESCAPED}</li>
 *   <li>If agent hit max steps → {@code TIMED_OUT}</li>
 *   <li>If any critical assertion fails → {@code FAILED}</li>
 *   <li>If all critical assertions pass but some non-critical fail → {@code PARTIAL}</li>
 *   <li>If all assertions pass → {@code COMPLETED}</li>
 *   <li>If no assertions and no escape → {@code UNKNOWN} (judge-only evaluation needed)</li>
 * </ol>
 */
public class OutcomeEvaluator {

    /**
     * Evaluate an agent's output against a case's assertions.
     *
     * @param evalCase        the test case definition
     * @param agentOutput     the agent's full text output
     * @param escapeResult    escape detection result (null if not run)
     * @param hitMaxSteps     whether the agent hit the step limit
     * @param toolCallErrors  number of tool execution errors
     * @param toolCalls       map of tool name → invocation count (null if unknown)
     * @param compositeScore  composite quality score from harness (0 if not scored)
     * @param judgeCorrectness judge correctness dimension (0 if not scored)
     * @param judgeCompleteness judge completeness dimension (0 if not scored)
     * @return the evaluation result with outcome and assertion details
     */
    public EvalCaseResult evaluate(EvalCase evalCase, String agentOutput,
                                    EscapeDetector.EscapeResult escapeResult,
                                    boolean hitMaxSteps, int toolCallErrors,
                                    Map<String, Integer> toolCalls,
                                    float compositeScore, float judgeCorrectness,
                                    float judgeCompleteness, String judgeReasoning) {

        EvalCaseResult result = new EvalCaseResult();
        result.setCaseId(evalCase.getId());
        result.setCaseName(evalCase.getName());
        result.setPrompt(evalCase.getPrompt());
        result.setAgentOutput(agentOutput);
        result.setHitMaxSteps(hitMaxSteps);
        result.setToolCallErrors(toolCallErrors);
        result.setCompositeScore(compositeScore);
        result.setJudgeCorrectness(judgeCorrectness);
        result.setJudgeCompleteness(judgeCompleteness);
        result.setJudgeReasoning(judgeReasoning);

        if (escapeResult != null) {
            result.setHadEscape(escapeResult.hasEscape());
            result.setEscapeType(escapeResult.type().name());
        }

        // Run all assertions
        List<AssertionResult> assertionResults = new ArrayList<>();
        for (Assertion assertion : evalCase.getAssertions()) {
            AssertionResult ar = evaluateAssertion(assertion, agentOutput, escapeResult,
                    hitMaxSteps, toolCallErrors, toolCalls, compositeScore,
                    judgeCorrectness, judgeCompleteness);
            assertionResults.add(ar);
        }
        result.setAssertionResults(assertionResults);

        int passed = (int) assertionResults.stream().filter(AssertionResult::passed).count();
        int total = assertionResults.size();
        int criticalFailed = (int) assertionResults.stream()
                .filter(ar -> !ar.passed() && ar.critical()).count();
        result.setAssertionsPassed(passed);
        result.setAssertionsTotal(total);
        result.setCriticalAssertionsFailed(criticalFailed);

        // Determine outcome
        if (escapeResult != null && escapeResult.hasEscape()) {
            // Check if escape is from NO_ESCAPE assertion or is a hard escape
            boolean hasNoEscapeAssertion = evalCase.getAssertions().stream()
                    .anyMatch(a -> a.getType() == Assertion.Type.NO_ESCAPE);
            if (hasNoEscapeAssertion || isHardEscape(escapeResult)) {
                result.setOutcome(TaskOutcome.ESCAPED);
                result.setOutcomeReason("Agent escaped: " + escapeResult.type()
                        + " — " + escapeResult.detail());
                return result;
            }
        }

        if (hitMaxSteps) {
            result.setOutcome(TaskOutcome.TIMED_OUT);
            result.setOutcomeReason("Agent hit maximum step limit");
            return result;
        }

        if (total == 0) {
            // No assertions defined — use judge scores if available
            if (judgeCorrectness >= 4.0 && judgeCompleteness >= 4.0) {
                result.setOutcome(TaskOutcome.COMPLETED);
                result.setOutcomeReason("No assertions; judge scores indicate completion "
                        + "(correctness=" + judgeCorrectness + ", completeness=" + judgeCompleteness + ")");
            } else if (judgeCorrectness > 0 || judgeCompleteness > 0) {
                if (judgeCorrectness >= 3.0 || judgeCompleteness >= 3.0) {
                    result.setOutcome(TaskOutcome.PARTIAL);
                    result.setOutcomeReason("No assertions; judge scores indicate partial completion");
                } else {
                    result.setOutcome(TaskOutcome.FAILED);
                    result.setOutcomeReason("No assertions; judge scores below threshold");
                }
            } else {
                result.setOutcome(TaskOutcome.UNKNOWN);
                result.setOutcomeReason("No assertions defined and no judge scores available");
            }
            return result;
        }

        if (criticalFailed > 0) {
            result.setOutcome(TaskOutcome.FAILED);
            List<String> failedNames = assertionResults.stream()
                    .filter(ar -> !ar.passed() && ar.critical())
                    .map(AssertionResult::description)
                    .toList();
            result.setOutcomeReason("Critical assertions failed: " + String.join("; ", failedNames));
            return result;
        }

        if (passed < total) {
            result.setOutcome(TaskOutcome.PARTIAL);
            result.setOutcomeReason(passed + "/" + total + " assertions passed (" +
                    (total - passed) + " non-critical failures)");
            return result;
        }

        result.setOutcome(TaskOutcome.COMPLETED);
        result.setOutcomeReason("All " + total + " assertions passed");
        return result;
    }

    private boolean isHardEscape(EscapeDetector.EscapeResult result) {
        if (result == null || !result.hasEscape()) return false;
        return switch (result.type()) {
            case EXPLICIT_REFUSAL, EMPTY_OUTPUT -> true;
            default -> false;
        };
    }

    /**
     * Evaluate a single assertion against the agent output and execution context.
     */
    AssertionResult evaluateAssertion(Assertion assertion, String agentOutput,
                                              EscapeDetector.EscapeResult escapeResult,
                                              boolean hitMaxSteps, int toolCallErrors,
                                              Map<String, Integer> toolCalls,
                                              float compositeScore, float judgeCorrectness,
                                              float judgeCompleteness) {
        String output = agentOutput != null ? agentOutput : "";
        String value = assertion.getValue();

        return switch (assertion.getType()) {
            case OUTPUT_CONTAINS -> {
                if (value == null) yield AssertionResult.fail(assertion, "No value specified");
                boolean found = output.toLowerCase().contains(value.toLowerCase());
                yield found ? AssertionResult.pass(assertion)
                        : AssertionResult.fail(assertion, "Output does not contain: " + value);
            }

            case OUTPUT_NOT_CONTAINS -> {
                if (value == null) yield AssertionResult.fail(assertion, "No value specified");
                boolean found = output.toLowerCase().contains(value.toLowerCase());
                yield !found ? AssertionResult.pass(assertion)
                        : AssertionResult.fail(assertion, "Output contains forbidden text: " + value);
            }

            case OUTPUT_MATCHES_REGEX -> {
                if (value == null) yield AssertionResult.fail(assertion, "No regex specified");
                try {
                    boolean matches = Pattern.compile(value, Pattern.DOTALL).matcher(output).find();
                    yield matches ? AssertionResult.pass(assertion)
                            : AssertionResult.fail(assertion, "Output does not match regex: " + value);
                } catch (Exception e) {
                    yield AssertionResult.fail(assertion, "Invalid regex: " + e.getMessage());
                }
            }

            case TOOL_WAS_CALLED -> {
                if (value == null) yield AssertionResult.fail(assertion, "No tool name specified");
                boolean called = toolCalls != null && toolCalls.containsKey(value)
                        && toolCalls.get(value) > 0;
                yield called ? AssertionResult.pass(assertion)
                        : AssertionResult.fail(assertion, "Tool was not called: " + value);
            }

            case TOOL_NOT_CALLED -> {
                if (value == null) yield AssertionResult.fail(assertion, "No tool name specified");
                boolean called = toolCalls != null && toolCalls.containsKey(value)
                        && toolCalls.get(value) > 0;
                yield !called ? AssertionResult.pass(assertion)
                        : AssertionResult.fail(assertion, "Tool was called but shouldn't have been: " + value);
            }

            case NO_ESCAPE -> {
                boolean escaped = escapeResult != null && escapeResult.hasEscape();
                yield !escaped ? AssertionResult.pass(assertion)
                        : AssertionResult.fail(assertion, "Agent escaped: " + escapeResult.type());
            }

            case NO_TOOL_ERRORS -> {
                yield toolCallErrors == 0 ? AssertionResult.pass(assertion)
                        : AssertionResult.fail(assertion, toolCallErrors + " tool errors occurred");
            }

            case MAX_STEPS_NOT_HIT -> {
                yield !hitMaxSteps ? AssertionResult.pass(assertion)
                        : AssertionResult.fail(assertion, "Agent hit maximum step limit");
            }

            case SCORE_ABOVE -> {
                float threshold = parseThreshold(value, 3.0f);
                yield compositeScore >= threshold ? AssertionResult.pass(assertion)
                        : AssertionResult.fail(assertion,
                        String.format("Composite score %.1f below threshold %.1f", compositeScore, threshold));
            }

            case JUDGE_CORRECTNESS_ABOVE -> {
                float threshold = parseThreshold(value, 3.0f);
                yield judgeCorrectness >= threshold ? AssertionResult.pass(assertion)
                        : AssertionResult.fail(assertion,
                        String.format("Judge correctness %.1f below threshold %.1f", judgeCorrectness, threshold));
            }

            case JUDGE_COMPLETENESS_ABOVE -> {
                float threshold = parseThreshold(value, 3.0f);
                yield judgeCompleteness >= threshold ? AssertionResult.pass(assertion)
                        : AssertionResult.fail(assertion,
                        String.format("Judge completeness %.1f below threshold %.1f", judgeCompleteness, threshold));
            }
        };
    }

    private static float parseThreshold(String value, float defaultVal) {
        if (value == null) return defaultVal;
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }
}
