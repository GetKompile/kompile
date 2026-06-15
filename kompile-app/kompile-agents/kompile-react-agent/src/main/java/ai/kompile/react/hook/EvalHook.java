/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.react.hook;

import ai.kompile.core.evaluation.*;
import ai.kompile.react.context.AgentContext;
import ai.kompile.react.eval.EvalTracker;
import ai.kompile.react.eval.model.EvalCase;
import ai.kompile.react.eval.model.EvalTestResult;
import ai.kompile.react.model.ReActMessage;
import ai.kompile.react.model.ReActResult;
import ai.kompile.react.model.ToolCall;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Hook that integrates evaluations at different phases of agent execution.
 * Runs evaluations on reasoning outputs and final answers, and tracks results.
 *
 * <p>Integration points:
 * <ul>
 *   <li>preRun - Looks up relevant test cases and expected answers</li>
 *   <li>postReason - Evaluates reasoning quality (optional)</li>
 *   <li>postRun - Full evaluation of the final answer</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class EvalHook implements AgentHook {

    private final EvaluationService evaluationService;
    private final EvalTracker evalTracker;
    private final boolean evaluateReasoning;
    private final double qualityThreshold;

    /**
     * Create with default settings.
     */
    public EvalHook(EvaluationService evaluationService, EvalTracker evalTracker) {
        this(evaluationService, evalTracker, false, 0.7);
    }

    @Override
    public int getPriority() {
        return 100; // Run after other hooks
    }

    @Override
    public void preRun(AgentContext context) {
        if (evalTracker == null) {
            return;
        }

        // Look up relevant test cases for the query
        String query = extractQuery(context);
        Long factSheetId = getFactSheetId(context);

        if (query != null && !query.isBlank()) {
            // Find matching test cases
            List<EvalCase> matchingCases = findMatchingTestCases(query, factSheetId);

            if (!matchingCases.isEmpty()) {
                // Store in context for later reference
                context.setMetadata("matching_test_cases", matchingCases);

                // If there's an exact match, store the expected answer
                for (EvalCase tc : matchingCases) {
                    if (tc.getQuery().equalsIgnoreCase(query.trim())) {
                        context.setMetadata("expected_answer", tc.getExpectedAnswer());
                        context.setMetadata("expected_facts", tc.getExpectedFacts());
                        context.setMetadata("test_case_id", tc.getId());
                        log.debug("Found exact test case match: {}", tc.getId());
                        break;
                    }
                }
            }
        }
    }

    @Override
    public void postReason(AgentContext context, int step, ReActMessage message) {
        if (!evaluateReasoning || evaluationService == null || !evaluationService.isEnabled()) {
            return;
        }

        if (message == null || message.getThought() == null) {
            return;
        }

        try {
            // Quick relevancy check on the reasoning
            String query = extractQuery(context);
            String thought = message.getThought();

            EvaluationContext evalContext = EvaluationContext.builder()
                    .build();

            // Only run relevancy evaluation for reasoning steps
            EvaluationReport report = evaluationService.evaluate(
                    query, thought, List.of(),
                    List.of(EvaluationType.RELEVANCY),
                    evalContext
            );

            if (report.getOverallScore() < qualityThreshold) {
                log.debug("Reasoning step {} has low relevancy score: {}",
                        step, report.getOverallScore());

                // Add warning to context
                @SuppressWarnings("unchecked")
                List<String> warnings = (List<String>) context.getMetadata("reasoning_warnings");
                if (warnings == null) {
                    warnings = new ArrayList<>();
                    context.setMetadata("reasoning_warnings", warnings);
                }
                warnings.add("Step " + step + ": Low relevancy (" +
                        String.format("%.2f", report.getOverallScore()) + ")");
            }

        } catch (Exception e) {
            log.debug("Reasoning evaluation failed: {}", e.getMessage());
        }
    }

    @Override
    public void postRun(AgentContext context, ReActResult result) {
        if (evaluationService == null || !evaluationService.isEnabled()) {
            return;
        }

        if (result == null || result.getAnswer() == null) {
            return;
        }

        try {
            String query = extractQuery(context);
            String answer = result.getAnswer();
            List<String> retrievedDocs = extractRetrievedDocuments(context);

            // Check for expected answer
            String expectedAnswer = (String) context.getMetadata("expected_answer");

            @SuppressWarnings("unchecked")
            List<String> expectedFacts = (List<String>) context.getMetadata("expected_facts");

            // Build evaluation context
            EvaluationContext evalContext = EvaluationContext.builder()
                    .groundTruth(expectedAnswer)
                    .requiredKeywords(expectedFacts != null ? expectedFacts : List.of())
                    .build();

            // Run full evaluation
            EvaluationReport report = evaluationService.evaluate(
                    query, answer, retrievedDocs, evalContext
            );

            // Store evaluation results
            context.setMetadata("evaluation_report", report);
            context.setMetadata("evaluation_score", report.getOverallScore());
            context.setMetadata("evaluation_passed", report.isOverallPassed());

            log.debug("Final evaluation - Score: {}, Passed: {}",
                    report.getOverallScore(), report.isOverallPassed());

            // Track the result
            recordTestResult(context, result, report);

        } catch (Exception e) {
            log.error("Post-run evaluation failed: {}", e.getMessage(), e);
        }
    }

    private String extractQuery(AgentContext context) {
        List<ReActMessage> messages = context.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ReActMessage msg = messages.get(i);
            if (msg.getRole() == ReActMessage.Role.USER && msg.getContent() != null) {
                return msg.getContent();
            }
        }
        return null;
    }

    private Long getFactSheetId(AgentContext context) {
        Object factSheetId = context.getMetadata("fact_sheet_id");
        if (factSheetId instanceof Long) {
            return (Long) factSheetId;
        } else if (factSheetId instanceof Number) {
            return ((Number) factSheetId).longValue();
        }
        return null;
    }

    private List<EvalCase> findMatchingTestCases(String query, Long factSheetId) {
        if (evalTracker == null) {
            return List.of();
        }

        List<EvalCase> candidates;
        if (factSheetId != null) {
            candidates = evalTracker.getTestCasesForFactSheet(factSheetId);
        } else {
            candidates = evalTracker.getAllTestCases();
        }

        String lowerQuery = query.toLowerCase();

        // Find test cases with similar queries
        return candidates.stream()
                .filter(EvalCase::isEnabled)
                .filter(tc -> tc.getQuery() != null)
                .filter(tc -> {
                    String tcQuery = tc.getQuery().toLowerCase();
                    // Check for word overlap
                    Set<String> queryWords = new HashSet<>(Arrays.asList(lowerQuery.split("\\W+")));
                    Set<String> tcWords = new HashSet<>(Arrays.asList(tcQuery.split("\\W+")));
                    queryWords.retainAll(tcWords);
                    return !queryWords.isEmpty();
                })
                .limit(5)
                .collect(Collectors.toList());
    }

    private List<String> extractRetrievedDocuments(AgentContext context) {
        List<String> docs = new ArrayList<>();

        for (ReActMessage msg : context.getMessages()) {
            if (msg.getRole() == ReActMessage.Role.TOOL) {
                if ("search_knowledge_graph".equals(msg.getToolName()) ||
                        "rag_query".equals(msg.getToolName()) ||
                        "lookup_entity".equals(msg.getToolName())) {
                    if (msg.getContent() != null) {
                        docs.add(msg.getContent());
                    }
                }
            }
        }

        return docs;
    }

    private void recordTestResult(AgentContext context, ReActResult result, EvaluationReport report) {
        if (evalTracker == null) {
            return;
        }

        try {
            String testCaseId = (String) context.getMetadata("test_case_id");
            String query = extractQuery(context);
            Long factSheetId = getFactSheetId(context);

            // Build scores map
            Map<EvaluationType, Double> scores = new HashMap<>();
            Map<EvaluationType, Boolean> passedByType = new HashMap<>();
            Map<EvaluationType, EvaluationResult> evalResults = new HashMap<>();

            for (EvaluationResult evalResult : report.getResults()) {
                EvaluationType type = evalResult.getEvaluationType();
                scores.put(type, evalResult.getScore());
                passedByType.put(type, evalResult.isPassed());
                evalResults.put(type, evalResult);
            }

            // Build failure reasons
            List<String> failureReasons = report.getResults().stream()
                    .filter(r -> !r.isPassed())
                    .map(r -> r.getEvaluatorName() + ": " + r.getExplanation())
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            // Extract tool calls
            List<String> toolCalls = new ArrayList<>();
            for (ReActMessage msg : context.getMessages()) {
                if (msg.getToolCalls() != null) {
                    for (ToolCall tc : msg.getToolCalls()) {
                        toolCalls.add(tc.getName());
                    }
                }
            }

            EvalTestResult testResult = EvalTestResult.builder()
                    .id(UUID.randomUUID().toString())
                    .testCaseId(testCaseId)
                    .suiteId(null)
                    .factSheetId(factSheetId)
                    .executionId(context.getExecutionId())
                    .passed(report.isOverallPassed())
                    .score(report.getOverallScore())
                    .query(query)
                    .expectedAnswer((String) context.getMetadata("expected_answer"))
                    .actualAnswer(result.getAnswer())
                    .retrievedDocuments(extractRetrievedDocuments(context))
                    .toolCalls(toolCalls)
                    .stepsExecuted(result.getStepsExecuted())
                    .evaluationResults(evalResults)
                    .evaluationReport(report)
                    .scores(scores)
                    .passedByType(passedByType)
                    .failureReasons(failureReasons)
                    .startedAt(result.getStartTime() != null ? result.getStartTime() : Instant.now())
                    .completedAt(result.getEndTime() != null ? result.getEndTime() : Instant.now())
                    .executionTimeMs(report.getTotalEvaluationTimeMs())
                    .totalTokens(result.getTotalUsage() != null ? result.getTotalUsage().getTotalTokens() : 0)
                    .build();

            evalTracker.recordTestResult(testResult);
            log.debug("Recorded eval test result for execution {}", context.getExecutionId());

        } catch (Exception e) {
            log.warn("Failed to record test result: {}", e.getMessage());
        }
    }
}
