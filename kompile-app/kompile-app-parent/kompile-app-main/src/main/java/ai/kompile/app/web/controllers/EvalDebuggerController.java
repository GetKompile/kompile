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

package ai.kompile.app.web.controllers;

import ai.kompile.core.evaluation.*;
import ai.kompile.core.llm.chat.LLMChat;
import ai.kompile.core.rag.RagQuery;
import ai.kompile.core.rag.RagResult;
import ai.kompile.core.rag.RagService;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.orchestrator.api.LlmProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Controller for the evaluation debugger UI.
 * Allows users to run RAG queries with expected answers and evaluate the results.
 */
@Slf4j
@RestController
@RequestMapping("/api/eval-debugger")
public class EvalDebuggerController {

    @Autowired(required = false)
    private RagService ragService;

    @Autowired(required = false)
    private EvaluationService evaluationService;

    @Autowired(required = false)
    private List<LlmProvider> llmProviders;

    @Autowired(required = false)
    private LLMChat llmChat;

    @Autowired(required = false)
    private ChatModel chatModel;

    @Autowired
    private ObjectMapper objectMapper;

    // In-memory storage for test suites (in production, would use a database)
    private final Map<String, TestSuite> testSuites = new ConcurrentHashMap<>();

    // In-memory storage for run results
    private final Map<String, TestRunResult> runResults = new ConcurrentHashMap<>();

    // LLM Judge prompt template
    private static final String LLM_JUDGE_SYSTEM_PROMPT = """
        You are an expert evaluation judge for RAG (Retrieval-Augmented Generation) systems.
        Your task is to evaluate the quality of answers produced by a RAG system.

        You will be given:
        1. The original query/question
        2. The expected answer (ground truth)
        3. The actual answer produced by the RAG system
        4. The retrieved context documents used to generate the answer

        Evaluate the answer based on these criteria:
        - CORRECTNESS: Does the answer match the expected answer in meaning and content?
        - RELEVANCY: Is the answer relevant to the question asked?
        - FAITHFULNESS: Is the answer faithful to the retrieved context (no hallucinations)?
        - COMPLETENESS: Does the answer fully address the question?
        - COHERENCE: Is the answer well-structured and easy to understand?

        Provide your evaluation in the following JSON format:
        {
            "passed": true/false,
            "overallScore": 0.0-1.0,
            "criteria": {
                "correctness": { "score": 0.0-1.0, "explanation": "..." },
                "relevancy": { "score": 0.0-1.0, "explanation": "..." },
                "faithfulness": { "score": 0.0-1.0, "explanation": "..." },
                "completeness": { "score": 0.0-1.0, "explanation": "..." },
                "coherence": { "score": 0.0-1.0, "explanation": "..." }
            },
            "summary": "Brief overall assessment",
            "recommendations": ["suggestion1", "suggestion2"]
        }

        Be strict but fair. A score of 0.7 or higher typically indicates a passing answer.
        """;

    private static final String LLM_JUDGE_USER_PROMPT_TEMPLATE = """
        Please evaluate the following RAG system output:

        ## Query
        %s

        ## Expected Answer
        %s

        ## Actual Answer
        %s

        ## Retrieved Context
        %s

        Provide your evaluation in the specified JSON format.
        """;

    /**
     * Check if the eval debugger is available.
     */
    @GetMapping("/status")
    public ResponseEntity<StatusResponse> getStatus() {
        boolean ragAvailable = ragService != null;
        boolean evalAvailable = evaluationService != null && evaluationService.isEnabled();
        boolean llmJudgeAvailable = llmChat != null || chatModel != null;

        int llmProviderCount = 0;
        if (llmProviders != null) {
            llmProviderCount = (int) llmProviders.stream().filter(LlmProvider::isAvailable).count();
        }
        if (llmJudgeAvailable && llmProviderCount == 0) {
            llmProviderCount = 1; // Default provider
        }

        return ResponseEntity.ok(StatusResponse.builder()
                .available(ragAvailable)
                .evaluationAvailable(evalAvailable)
                .evaluatorCount(evalAvailable ? evaluationService.getEvaluators().size() : 0)
                .llmJudgeAvailable(llmJudgeAvailable)
                .llmProviderCount(llmProviderCount)
                .message(ragAvailable ? "Eval debugger is ready" : "RAG service not available")
                .build());
    }

    /**
     * Get available evaluator types.
     */
    @GetMapping("/evaluator-types")
    public ResponseEntity<List<EvaluatorTypeInfo>> getEvaluatorTypes() {
        if (evaluationService == null) {
            return ResponseEntity.ok(Collections.emptyList());
        }

        List<EvaluatorTypeInfo> types = Arrays.stream(EvaluationType.values())
                .map(type -> EvaluatorTypeInfo.builder()
                        .type(type.name())
                        .name(formatTypeName(type))
                        .description(getTypeDescription(type))
                        .available(!evaluationService.getEvaluatorsByType(type).isEmpty())
                        .build())
                .collect(Collectors.toList());

        return ResponseEntity.ok(types);
    }

    /**
     * Get available LLM providers for LLM-as-judge evaluation.
     */
    @GetMapping("/llm-providers")
    public ResponseEntity<List<LlmProviderInfo>> getLlmProviders() {
        List<LlmProviderInfo> providers = new ArrayList<>();

        // Check if we have LLMChat available (primary chat interface)
        boolean llmChatAvailable = llmChat != null || chatModel != null;

        if (llmChatAvailable) {
            // Add default provider from Spring AI ChatModel
            providers.add(LlmProviderInfo.builder()
                    .id("default")
                    .name("Default LLM")
                    .description("Configured Spring AI Chat Model")
                    .available(true)
                    .supportsJudge(true)
                    .models(List.of(new LlmModelInfo("default", "Default Model", "Currently configured model", true)))
                    .build());
        }

        // Add providers from LlmProvider beans
        if (llmProviders != null) {
            for (LlmProvider provider : llmProviders) {
                List<LlmModelInfo> models = new ArrayList<>();

                if (provider.supportsModelListing() && provider.isAvailable()) {
                    try {
                        for (LlmProvider.ModelInfo modelInfo : provider.getAvailableModels()) {
                            models.add(new LlmModelInfo(
                                    modelInfo.id(),
                                    modelInfo.displayName(),
                                    modelInfo.description(),
                                    modelInfo.supportsTools()
                            ));
                        }
                    } catch (Exception e) {
                        log.warn("Failed to get models from provider {}: {}", provider.getId(), e.getMessage());
                    }
                }

                // If no models fetched, add a default entry
                if (models.isEmpty() && provider.isAvailable()) {
                    models.add(new LlmModelInfo(
                            "default",
                            provider.getDisplayName() + " (Default)",
                            "Default model for " + provider.getDisplayName(),
                            true
                    ));
                }

                providers.add(LlmProviderInfo.builder()
                        .id(provider.getId())
                        .name(provider.getDisplayName())
                        .description("Priority: " + provider.getPriority())
                        .available(provider.isAvailable())
                        .supportsJudge(provider.isAvailable())
                        .models(models)
                        .build());
            }
        }

        // If no providers available, indicate that
        if (providers.isEmpty()) {
            providers.add(LlmProviderInfo.builder()
                    .id("none")
                    .name("No LLM Configured")
                    .description("Configure an LLM provider (OpenAI, Anthropic, etc.) to use LLM-as-judge")
                    .available(false)
                    .supportsJudge(false)
                    .models(List.of())
                    .build());
        }

        return ResponseEntity.ok(providers);
    }

    /**
     * Run LLM-as-judge evaluation on a single test case.
     */
    @PostMapping("/run-llm-judge")
    public ResponseEntity<LlmJudgeResult> runLlmJudge(@RequestBody LlmJudgeRequest request) {
        if (llmChat == null && chatModel == null) {
            return ResponseEntity.ok(LlmJudgeResult.builder()
                    .success(false)
                    .error("No LLM configured. Please configure an LLM provider.")
                    .build());
        }

        try {
            long startTime = System.currentTimeMillis();

            // Build the user prompt
            String contextStr = request.getRetrievedDocuments() != null
                    ? String.join("\n---\n", request.getRetrievedDocuments())
                    : "No context provided";

            String userPrompt = String.format(LLM_JUDGE_USER_PROMPT_TEMPLATE,
                    request.getQuery(),
                    request.getExpectedAnswer(),
                    request.getActualAnswer(),
                    contextStr);

            // Call the LLM
            String response;
            if (llmChat != null) {
                response = llmChat.prompt()
                        .system(LLM_JUDGE_SYSTEM_PROMPT)
                        .user(userPrompt)
                        .call()
                        .content();
            } else {
                // Use ChatModel directly
                response = chatModel.call(
                        new Prompt(
                                List.of(
                                        new SystemMessage(LLM_JUDGE_SYSTEM_PROMPT),
                                        new UserMessage(userPrompt)
                                )
                        )
                ).getResult().getOutput().getText();
            }

            long evaluationTimeMs = System.currentTimeMillis() - startTime;

            // Parse the JSON response
            LlmJudgeEvaluation evaluation = parseJudgeResponse(response);
            evaluation.setRawResponse(response);
            evaluation.setEvaluationTimeMs(evaluationTimeMs);
            evaluation.setJudgeModel(request.getModelId() != null ? request.getModelId() : "default");
            evaluation.setJudgeProvider(request.getProviderId() != null ? request.getProviderId() : "default");

            return ResponseEntity.ok(LlmJudgeResult.builder()
                    .success(true)
                    .testCaseId(request.getTestCaseId())
                    .evaluation(evaluation)
                    .timestamp(Instant.now())
                    .build());

        } catch (Exception e) {
            log.error("LLM Judge evaluation failed: {}", e.getMessage(), e);
            return ResponseEntity.ok(LlmJudgeResult.builder()
                    .success(false)
                    .testCaseId(request.getTestCaseId())
                    .error("LLM Judge evaluation failed: " + e.getMessage())
                    .timestamp(Instant.now())
                    .build());
        }
    }

    /**
     * Run both automated evaluation and LLM-as-judge on a test case.
     */
    @PostMapping("/run-combined")
    public ResponseEntity<CombinedEvalResult> runCombinedEvaluation(@RequestBody TestCaseRequest request) {
        if (ragService == null) {
            return ResponseEntity.ok(CombinedEvalResult.builder()
                    .success(false)
                    .error("RAG service not available")
                    .build());
        }

        try {
            // First run the standard test case
            TestCaseResult automatedResult = executeTestCase(request);

            // Then run LLM-as-judge if available
            LlmJudgeEvaluation llmJudgeEvaluation = null;
            if (llmChat != null || chatModel != null) {
                try {
                    LlmJudgeRequest judgeRequest = new LlmJudgeRequest();
                    judgeRequest.setTestCaseId(request.getId());
                    judgeRequest.setQuery(request.getPrompt());
                    judgeRequest.setExpectedAnswer(request.getExpectedAnswer());
                    judgeRequest.setActualAnswer(automatedResult.getActualAnswer());
                    judgeRequest.setRetrievedDocuments(automatedResult.getRetrievedDocuments());

                    ResponseEntity<LlmJudgeResult> judgeResponse = runLlmJudge(judgeRequest);
                    if (judgeResponse.getBody() != null && judgeResponse.getBody().isSuccess()) {
                        llmJudgeEvaluation = judgeResponse.getBody().getEvaluation();
                    }
                } catch (Exception e) {
                    log.warn("LLM Judge evaluation failed in combined run: {}", e.getMessage());
                }
            }

            return ResponseEntity.ok(CombinedEvalResult.builder()
                    .success(true)
                    .testCaseId(request.getId())
                    .automatedResult(automatedResult)
                    .llmJudgeResult(llmJudgeEvaluation)
                    .llmJudgeAvailable(llmChat != null || chatModel != null)
                    .timestamp(Instant.now())
                    .build());

        } catch (Exception e) {
            log.error("Combined evaluation failed: {}", e.getMessage(), e);
            return ResponseEntity.ok(CombinedEvalResult.builder()
                    .success(false)
                    .error(e.getMessage())
                    .timestamp(Instant.now())
                    .build());
        }
    }

    /**
     * Run combined evaluation on a batch of test cases.
     */
    @PostMapping("/run-batch-combined")
    public ResponseEntity<BatchCombinedResult> runBatchCombinedEvaluation(@RequestBody BatchTestRequest request) {
        if (ragService == null) {
            return ResponseEntity.badRequest().body(
                    BatchCombinedResult.builder()
                            .success(false)
                            .error("RAG service not available")
                            .build());
        }

        String runId = UUID.randomUUID().toString();
        List<CombinedEvalResult> results = new ArrayList<>();
        long startTime = System.currentTimeMillis();

        for (TestCaseRequest testCase : request.getTestCases()) {
            try {
                ResponseEntity<CombinedEvalResult> resultResponse = runCombinedEvaluation(testCase);
                if (resultResponse.getBody() != null) {
                    results.add(resultResponse.getBody());
                }
            } catch (Exception e) {
                log.error("Error in batch combined evaluation for test case {}: {}", testCase.getId(), e.getMessage(), e);
                results.add(CombinedEvalResult.builder()
                        .success(false)
                        .testCaseId(testCase.getId())
                        .error(e.getMessage())
                        .timestamp(Instant.now())
                        .build());
            }
        }

        long totalTimeMs = System.currentTimeMillis() - startTime;

        // Calculate aggregate metrics
        int automatedPassed = (int) results.stream()
                .filter(r -> r.getAutomatedResult() != null && r.getAutomatedResult().isPassed())
                .count();
        int llmJudgePassed = (int) results.stream()
                .filter(r -> r.getLlmJudgeResult() != null && r.getLlmJudgeResult().isPassed())
                .count();

        double avgAutomatedScore = results.stream()
                .filter(r -> r.getAutomatedResult() != null && r.getAutomatedResult().getEvaluationReport() != null)
                .mapToDouble(r -> r.getAutomatedResult().getEvaluationReport().getOverallScore())
                .average()
                .orElse(0.0);

        double avgLlmJudgeScore = results.stream()
                .filter(r -> r.getLlmJudgeResult() != null)
                .mapToDouble(r -> r.getLlmJudgeResult().getOverallScore())
                .average()
                .orElse(0.0);

        return ResponseEntity.ok(BatchCombinedResult.builder()
                .runId(runId)
                .success(true)
                .results(results)
                .totalTests(results.size())
                .automatedPassedTests(automatedPassed)
                .llmJudgePassedTests(llmJudgePassed)
                .averageAutomatedScore(avgAutomatedScore)
                .averageLlmJudgeScore(avgLlmJudgeScore)
                .totalTimeMs(totalTimeMs)
                .llmJudgeAvailable(llmChat != null || chatModel != null)
                .timestamp(Instant.now())
                .build());
    }

    private LlmJudgeEvaluation parseJudgeResponse(String response) {
        LlmJudgeEvaluation evaluation = new LlmJudgeEvaluation();

        try {
            // Extract JSON from response (it might have markdown code blocks)
            String jsonStr = response;
            if (response.contains("```json")) {
                int start = response.indexOf("```json") + 7;
                int end = response.indexOf("```", start);
                jsonStr = response.substring(start, end).trim();
            } else if (response.contains("```")) {
                int start = response.indexOf("```") + 3;
                int end = response.indexOf("```", start);
                jsonStr = response.substring(start, end).trim();
            }

            // Parse using Jackson
            JsonNode root = objectMapper.readTree(jsonStr);

            evaluation.setPassed(root.path("passed").asBoolean(false));
            evaluation.setOverallScore(root.path("overallScore").asDouble(0.0));
            evaluation.setSummary(root.path("summary").asText(""));

            Map<String, LlmJudgeCriterion> criteria = new HashMap<>();
            JsonNode criteriaNode = root.path("criteria");
            if (criteriaNode.isObject()) {
                criteriaNode.fields().forEachRemaining(entry -> {
                    LlmJudgeCriterion criterion = new LlmJudgeCriterion();
                    criterion.setScore(entry.getValue().path("score").asDouble(0.0));
                    criterion.setExplanation(entry.getValue().path("explanation").asText(""));
                    criteria.put(entry.getKey(), criterion);
                });
            }
            evaluation.setCriteria(criteria);

            // Parse recommendations
            List<String> recommendations = new ArrayList<>();
            JsonNode recsNode = root.path("recommendations");
            if (recsNode.isArray()) {
                recsNode.forEach(node -> recommendations.add(node.asText()));
            }
            evaluation.setRecommendations(recommendations);

        } catch (Exception e) {
            log.warn("Failed to parse LLM judge response as JSON, using raw response: {}", e.getMessage());
            evaluation.setPassed(false);
            evaluation.setOverallScore(0.0);
            evaluation.setSummary("Failed to parse LLM response: " + e.getMessage());
            evaluation.setCriteria(Map.of());
            evaluation.setRecommendations(List.of());
        }

        return evaluation;
    }

    /**
     * Run a single test case.
     */
    @PostMapping("/run-single")
    public ResponseEntity<TestCaseResult> runSingleTest(@RequestBody TestCaseRequest request) {
        if (ragService == null) {
            return ResponseEntity.badRequest().body(
                    TestCaseResult.builder()
                            .testCaseId(request.getId())
                            .success(false)
                            .error("RAG service not available")
                            .build());
        }

        try {
            TestCaseResult result = executeTestCase(request);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error running test case: {}", e.getMessage(), e);
            return ResponseEntity.ok(TestCaseResult.builder()
                    .testCaseId(request.getId())
                    .prompt(request.getPrompt())
                    .expectedAnswer(request.getExpectedAnswer())
                    .success(false)
                    .error(e.getMessage())
                    .timestamp(Instant.now())
                    .build());
        }
    }

    /**
     * Run multiple test cases.
     */
    @PostMapping("/run-batch")
    public ResponseEntity<BatchTestResult> runBatchTests(@RequestBody BatchTestRequest request) {
        if (ragService == null) {
            return ResponseEntity.badRequest().body(
                    BatchTestResult.builder()
                            .success(false)
                            .error("RAG service not available")
                            .build());
        }

        String runId = UUID.randomUUID().toString();
        List<TestCaseResult> results = new ArrayList<>();
        long startTime = System.currentTimeMillis();

        for (TestCaseRequest testCase : request.getTestCases()) {
            try {
                TestCaseResult result = executeTestCase(testCase);
                results.add(result);
            } catch (Exception e) {
                log.error("Error running test case {}", testCase.getId(), e);
                results.add(TestCaseResult.builder()
                        .testCaseId(testCase.getId())
                        .prompt(testCase.getPrompt())
                        .expectedAnswer(testCase.getExpectedAnswer())
                        .success(false)
                        .error(e.getMessage())
                        .timestamp(Instant.now())
                        .build());
            }
        }

        long totalTime = System.currentTimeMillis() - startTime;

        // Calculate aggregate metrics
        int passed = (int) results.stream().filter(TestCaseResult::isPassed).count();
        int failed = results.size() - passed;
        double avgScore = results.stream()
                .filter(r -> r.getEvaluationReport() != null)
                .mapToDouble(r -> r.getEvaluationReport().getOverallScore())
                .average()
                .orElse(0.0);

        BatchTestResult batchResult = BatchTestResult.builder()
                .runId(runId)
                .success(true)
                .results(results)
                .totalTests(results.size())
                .passedTests(passed)
                .failedTests(failed)
                .averageScore(avgScore)
                .totalTimeMs(totalTime)
                .timestamp(Instant.now())
                .build();

        // Store for later retrieval (evict oldest if over limit)
        if (runResults.size() >= 100) {
            runResults.entrySet().stream()
                    .min(Comparator.comparing(e -> e.getValue().getTimestamp()))
                    .ifPresent(oldest -> runResults.remove(oldest.getKey()));
        }
        runResults.put(runId, TestRunResult.builder()
                .runId(runId)
                .batchResult(batchResult)
                .timestamp(Instant.now())
                .build());

        return ResponseEntity.ok(batchResult);
    }

    /**
     * Save a test suite.
     */
    @PostMapping("/suites")
    public ResponseEntity<TestSuite> saveTestSuite(@RequestBody TestSuite suite) {
        if (suite.getId() == null || suite.getId().isEmpty()) {
            suite.setId(UUID.randomUUID().toString());
        }
        suite.setUpdatedAt(Instant.now());
        if (suite.getCreatedAt() == null) {
            suite.setCreatedAt(Instant.now());
        }
        testSuites.put(suite.getId(), suite);
        return ResponseEntity.ok(suite);
    }

    /**
     * Get all test suites.
     */
    @GetMapping("/suites")
    public ResponseEntity<List<TestSuite>> getTestSuites() {
        return ResponseEntity.ok(new ArrayList<>(testSuites.values()));
    }

    /**
     * Get a specific test suite.
     */
    @GetMapping("/suites/{id}")
    public ResponseEntity<TestSuite> getTestSuite(@PathVariable String id) {
        TestSuite suite = testSuites.get(id);
        if (suite == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(suite);
    }

    /**
     * Delete a test suite.
     */
    @DeleteMapping("/suites/{id}")
    public ResponseEntity<Void> deleteTestSuite(@PathVariable String id) {
        testSuites.remove(id);
        return ResponseEntity.ok().build();
    }

    /**
     * Run a saved test suite.
     */
    @PostMapping("/suites/{id}/run")
    public ResponseEntity<BatchTestResult> runTestSuite(@PathVariable String id) {
        TestSuite suite = testSuites.get(id);
        if (suite == null) {
            return ResponseEntity.notFound().build();
        }

        BatchTestRequest request = new BatchTestRequest();
        request.setTestCases(suite.getTestCases());
        request.setEvaluatorTypes(suite.getEvaluatorTypes());

        return runBatchTests(request);
    }

    /**
     * Get previous run results.
     */
    @GetMapping("/runs")
    public ResponseEntity<List<TestRunResult>> getRunHistory() {
        return ResponseEntity.ok(new ArrayList<>(runResults.values()));
    }

    /**
     * Get a specific run result.
     */
    @GetMapping("/runs/{runId}")
    public ResponseEntity<TestRunResult> getRunResult(@PathVariable String runId) {
        TestRunResult result = runResults.get(runId);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(result);
    }

    // Helper methods

    private TestCaseResult executeTestCase(TestCaseRequest testCase) {
        long startTime = System.currentTimeMillis();

        // Execute RAG query
        RagQuery query = RagQuery.builder()
                .query(testCase.getPrompt())
                .k(testCase.getMaxDocuments() != null ? testCase.getMaxDocuments() : 5)
                .build();

        RagResult ragResult = ragService.answerQuery(query);
        long ragTimeMs = System.currentTimeMillis() - startTime;

        // Extract retrieved document contents
        List<String> retrievedDocs = new ArrayList<>();
        if (ragResult.getRetrievedDocs() != null) {
            retrievedDocs = ragResult.getRetrievedDocs().stream()
                    .map(RetrievedDoc::getText)
                    .collect(Collectors.toList());
        }

        // Build result
        TestCaseResult.TestCaseResultBuilder resultBuilder = TestCaseResult.builder()
                .testCaseId(testCase.getId())
                .prompt(testCase.getPrompt())
                .expectedAnswer(testCase.getExpectedAnswer())
                .actualAnswer(ragResult.getAnswer())
                .retrievedDocuments(retrievedDocs)
                .ragTimeMs(ragTimeMs)
                .success(true)
                .timestamp(Instant.now());

        // Run evaluation if available
        if (evaluationService != null && evaluationService.isEnabled()) {
            try {
                EvaluationContext context = EvaluationContext.builder()
                        .groundTruth(testCase.getExpectedAnswer())
                        .threshold(testCase.getThreshold() != null ? testCase.getThreshold() : 0.5)
                        .includeFindings(true)
                        .includeExplanation(true)
                        .build();

                EvaluationReport report;
                if (testCase.getEvaluatorTypes() != null && !testCase.getEvaluatorTypes().isEmpty()) {
                    List<EvaluationType> types = testCase.getEvaluatorTypes().stream()
                            .map(EvaluationType::valueOf)
                            .collect(Collectors.toList());
                    report = evaluationService.evaluate(
                            testCase.getPrompt(),
                            ragResult.getAnswer(),
                            retrievedDocs,
                            types,
                            context);
                } else {
                    report = evaluationService.evaluate(
                            testCase.getPrompt(),
                            ragResult.getAnswer(),
                            retrievedDocs,
                            context);
                }

                resultBuilder.evaluationReport(convertToDto(report));
                resultBuilder.passed(report.isOverallPassed());
                resultBuilder.evaluationTimeMs(report.getTotalEvaluationTimeMs());
            } catch (Exception e) {
                log.warn("Evaluation failed for test case {}: {}", testCase.getId(), e.getMessage());
                resultBuilder.evaluationError(e.getMessage());
                // Still mark as success since RAG worked, just evaluation failed
                resultBuilder.passed(true);
            }
        } else {
            // No evaluation available, can't determine pass/fail
            resultBuilder.passed(true);
            resultBuilder.evaluationError("Evaluation service not available");
        }

        return resultBuilder.build();
    }

    private EvaluationReportDto convertToDto(EvaluationReport report) {
        return EvaluationReportDto.builder()
                .overallPassed(report.isOverallPassed())
                .overallScore(report.getOverallScore())
                .passedCount(report.getPassedCount())
                .failedCount(report.getFailedCount())
                .summary(report.getSummary())
                .recommendations(report.getRecommendations())
                .totalEvaluationTimeMs(report.getTotalEvaluationTimeMs())
                .results(report.getResults().stream()
                        .map(this::convertResultToDto)
                        .collect(Collectors.toList()))
                .scoresByType(report.getScoresByType().entrySet().stream()
                        .collect(Collectors.toMap(
                                e -> e.getKey().name(),
                                Map.Entry::getValue)))
                .build();
    }

    private EvaluationResultDto convertResultToDto(EvaluationResult result) {
        return EvaluationResultDto.builder()
                .evaluatorName(result.getEvaluatorName())
                .evaluationType(result.getEvaluationType().name())
                .passed(result.isPassed())
                .score(result.getScore())
                .confidence(result.getConfidence())
                .explanation(result.getExplanation())
                .threshold(result.getThreshold())
                .evaluationTimeMs(result.getEvaluationTimeMs())
                .metrics(result.getMetrics())
                .findings(result.getFindings().stream()
                        .map(f -> FindingDto.builder()
                                .type(f.getType().name())
                                .description(f.getDescription())
                                .content(f.getContent())
                                .evidence(f.getEvidence())
                                .severity(f.getSeverity().name())
                                .scoreImpact(f.getScoreImpact())
                                .build())
                        .collect(Collectors.toList()))
                .build();
    }

    private String formatTypeName(EvaluationType type) {
        String name = type.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private String getTypeDescription(EvaluationType type) {
        return switch (type) {
            case RELEVANCY -> "Measures how relevant the response is to the query";
            case FAITHFULNESS -> "Checks if the response is faithful to the retrieved context";
            case FACTUALITY -> "Evaluates factual accuracy of the response";
            case CONTEXT_RELEVANCY -> "Evaluates how relevant retrieved documents are";
            case CONTEXT_SUFFICIENCY -> "Checks if retrieved context is sufficient";
            case COHERENCE -> "Assesses logical flow and consistency";
            case COMPLETENESS -> "Evaluates response completeness";
            case CONCISENESS -> "Evaluates response brevity and efficiency";
            case ANSWER_CORRECTNESS -> "Compares the response against expected ground truth";
            case SEMANTIC_SIMILARITY -> "Measures semantic similarity to expected answer";
            case HALLUCINATION_DETECTION -> "Detects fabricated or unsupported claims";
            case ENTITY_PRESENCE -> "Evaluates entity presence in graph extraction";
            case ENTITY_TYPE_ACCURACY -> "Evaluates entity type accuracy in graph extraction";
            case GRAPH_COMPLETENESS -> "Evaluates graph extraction completeness";
            case RELATIONSHIP_PRESENCE -> "Evaluates relationship presence in graph extraction";
            case CUSTOM -> "Custom evaluation criteria";
        };
    }

    // DTOs

    @Data
    @Builder
    public static class StatusResponse {
        private boolean available;
        private boolean evaluationAvailable;
        private int evaluatorCount;
        private boolean llmJudgeAvailable;
        private int llmProviderCount;
        private String message;
    }

    @Data
    @Builder
    public static class EvaluatorTypeInfo {
        private String type;
        private String name;
        private String description;
        private boolean available;
    }

    @Data
    public static class TestCaseRequest {
        private String id;
        private String prompt;
        private String expectedAnswer;
        private List<String> evaluatorTypes;
        private Double threshold;
        private Integer maxDocuments;
        private Map<String, Object> metadata;
    }

    @Data
    public static class BatchTestRequest {
        private List<TestCaseRequest> testCases;
        private List<String> evaluatorTypes;
    }

    @Data
    @Builder
    public static class TestCaseResult {
        private String testCaseId;
        private String prompt;
        private String expectedAnswer;
        private String actualAnswer;
        private List<String> retrievedDocuments;
        private boolean success;
        private boolean passed;
        private String error;
        private String evaluationError;
        private EvaluationReportDto evaluationReport;
        private long ragTimeMs;
        private long evaluationTimeMs;
        private Instant timestamp;
    }

    @Data
    @Builder
    public static class BatchTestResult {
        private String runId;
        private boolean success;
        private String error;
        private List<TestCaseResult> results;
        private int totalTests;
        private int passedTests;
        private int failedTests;
        private double averageScore;
        private long totalTimeMs;
        private Instant timestamp;
    }

    @Data
    @Builder
    public static class EvaluationReportDto {
        private boolean overallPassed;
        private double overallScore;
        private long passedCount;
        private long failedCount;
        private String summary;
        private List<String> recommendations;
        private long totalEvaluationTimeMs;
        private List<EvaluationResultDto> results;
        private Map<String, Double> scoresByType;
    }

    @Data
    @Builder
    public static class EvaluationResultDto {
        private String evaluatorName;
        private String evaluationType;
        private boolean passed;
        private double score;
        private double confidence;
        private String explanation;
        private double threshold;
        private long evaluationTimeMs;
        private Map<String, Double> metrics;
        private List<FindingDto> findings;
    }

    @Data
    @Builder
    public static class FindingDto {
        private String type;
        private String description;
        private String content;
        private String evidence;
        private String severity;
        private double scoreImpact;
    }

    @Data
    public static class TestSuite {
        private String id;
        private String name;
        private String description;
        private List<TestCaseRequest> testCases;
        private List<String> evaluatorTypes;
        private Instant createdAt;
        private Instant updatedAt;
    }

    @Data
    @Builder
    public static class TestRunResult {
        private String runId;
        private BatchTestResult batchResult;
        private Instant timestamp;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // LLM-AS-JUDGE DTOs
    // ═══════════════════════════════════════════════════════════════════════════════

    @Data
    @Builder
    public static class LlmProviderInfo {
        private String id;
        private String name;
        private String description;
        private boolean available;
        private boolean supportsJudge;
        private List<LlmModelInfo> models;
    }

    @Data
    @Builder
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class LlmModelInfo {
        private String id;
        private String name;
        private String description;
        private boolean supportsTools;
    }

    @Data
    public static class LlmJudgeRequest {
        private String testCaseId;
        private String providerId;
        private String modelId;
        private String query;
        private String expectedAnswer;
        private String actualAnswer;
        private List<String> retrievedDocuments;
        private Double threshold;
    }

    @Data
    @Builder
    public static class LlmJudgeResult {
        private boolean success;
        private String testCaseId;
        private String error;
        private LlmJudgeEvaluation evaluation;
        private Instant timestamp;
    }

    @Data
    public static class LlmJudgeEvaluation {
        private boolean passed;
        private double overallScore;
        private String summary;
        private Map<String, LlmJudgeCriterion> criteria;
        private List<String> recommendations;
        private String rawResponse;
        private long evaluationTimeMs;
        private String judgeModel;
        private String judgeProvider;
    }

    @Data
    public static class LlmJudgeCriterion {
        private double score;
        private String explanation;
    }

    @Data
    @Builder
    public static class CombinedEvalResult {
        private boolean success;
        private String testCaseId;
        private String error;
        private TestCaseResult automatedResult;
        private LlmJudgeEvaluation llmJudgeResult;
        private boolean llmJudgeAvailable;
        private Instant timestamp;
    }

    @Data
    @Builder
    public static class BatchCombinedResult {
        private String runId;
        private boolean success;
        private String error;
        private List<CombinedEvalResult> results;
        private int totalTests;
        private int automatedPassedTests;
        private int llmJudgePassedTests;
        private double averageAutomatedScore;
        private double averageLlmJudgeScore;
        private long totalTimeMs;
        private boolean llmJudgeAvailable;
        private Instant timestamp;
    }
}
