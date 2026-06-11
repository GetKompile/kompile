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
package ai.kompile.app.web.controllers;

import ai.kompile.core.evaluation.EvaluationType;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Controller for managing ReAct Agent configuration and evaluation test cases
 * tied to fact sheets.
 */
@Slf4j
@RestController
@RequestMapping("/api/react-agent")
public class ReActAgentConfigController {

    @Autowired
    private Environment environment;

    // In-memory storage for test cases and suites (in production, use database)
    private final Map<String, EvalTestCaseDto> testCases = new ConcurrentHashMap<>();
    private final Map<String, EvalSuiteDto> suites = new ConcurrentHashMap<>();
    private final Map<String, EvalTestResultDto> testResults = new ConcurrentHashMap<>();

    // ==================== Configuration Endpoints ====================

    /**
     * Get the current ReAct agent configuration.
     */
    @GetMapping("/config")
    public ResponseEntity<ReActConfigDto> getConfig() {
        ReActConfigDto config = ReActConfigDto.builder()
                .enabled(getBooleanProperty("kompile.react.enabled", true))
                .maxSteps(getIntProperty("kompile.react.max-steps", 10))
                .executionMode(getProperty("kompile.react.execution-mode", "SEQUENTIAL"))
                .graphRagEnabled(getBooleanProperty("kompile.react.graph-rag-enabled", false))
                .graphRagSearchType(getProperty("kompile.react.graph-rag-search-type", "LOCAL"))
                .graphRagMaxResults(getIntProperty("kompile.react.graph-rag-max-results", 10))
                .filterChainEnabled(getBooleanProperty("kompile.react.filter-chain-enabled", true))
                .evalBasedEnabled(getBooleanProperty("kompile.react.eval-based-enabled", false))
                .evalTrackingEnabled(getBooleanProperty("kompile.react.eval-tracking-enabled", true))
                .evalHookEnabled(getBooleanProperty("kompile.react.eval-hook-enabled", true))
                .selfEvaluate(getBooleanProperty("kompile.react.self-evaluate", true))
                .evaluateReasoning(getBooleanProperty("kompile.react.evaluate-reasoning", false))
                .qualityThreshold(getDoubleProperty("kompile.react.quality-threshold", 0.7))
                .evalRetentionDays(getIntProperty("kompile.react.eval-retention-days", 30))
                .summarizeResults(getBooleanProperty("kompile.react.summarize-results", true))
                .maxResultLength(getIntProperty("kompile.react.max-result-length", 2000))
                .build();

        return ResponseEntity.ok(config);
    }

    /**
     * Get configuration status.
     */
    @GetMapping("/status")
    public ResponseEntity<StatusDto> getStatus() {
        return ResponseEntity.ok(StatusDto.builder()
                .available(getBooleanProperty("kompile.react.enabled", true))
                .evalTrackingEnabled(getBooleanProperty("kompile.react.eval-tracking-enabled", true))
                .testCaseCount(testCases.size())
                .suiteCount(suites.size())
                .resultCount(testResults.size())
                .build());
    }

    // ==================== Test Case Endpoints ====================

    /**
     * Create a new test case.
     */
    @PostMapping("/test-cases")
    public ResponseEntity<EvalTestCaseDto> createTestCase(@RequestBody EvalTestCaseDto testCase) {
        if (testCase.getId() == null || testCase.getId().isEmpty()) {
            testCase.setId(UUID.randomUUID().toString());
        }
        testCase.setCreatedAt(Instant.now());
        testCase.setUpdatedAt(Instant.now());
        testCases.put(testCase.getId(), testCase);
        log.info("Created test case: {} for fact sheet: {}", testCase.getId(), testCase.getFactSheetId());
        return ResponseEntity.ok(testCase);
    }

    /**
     * Get all test cases.
     */
    @GetMapping("/test-cases")
    public ResponseEntity<List<EvalTestCaseDto>> getAllTestCases(
            @RequestParam(required = false) Long factSheetId,
            @RequestParam(required = false) String tag
    ) {
        List<EvalTestCaseDto> result = new ArrayList<>(testCases.values());

        if (factSheetId != null) {
            result = result.stream()
                    .filter(tc -> Objects.equals(tc.getFactSheetId(), factSheetId))
                    .collect(Collectors.toList());
        }

        if (tag != null && !tag.isEmpty()) {
            result = result.stream()
                    .filter(tc -> tc.getTags() != null && tc.getTags().contains(tag))
                    .collect(Collectors.toList());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Get a specific test case.
     */
    @GetMapping("/test-cases/{id}")
    public ResponseEntity<EvalTestCaseDto> getTestCase(@PathVariable String id) {
        EvalTestCaseDto testCase = testCases.get(id);
        if (testCase == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(testCase);
    }

    /**
     * Update a test case.
     */
    @PutMapping("/test-cases/{id}")
    public ResponseEntity<EvalTestCaseDto> updateTestCase(
            @PathVariable String id,
            @RequestBody EvalTestCaseDto testCase
    ) {
        if (!testCases.containsKey(id)) {
            return ResponseEntity.notFound().build();
        }
        testCase.setId(id);
        testCase.setUpdatedAt(Instant.now());
        EvalTestCaseDto existing = testCases.get(id);
        if (existing != null) {
            testCase.setCreatedAt(existing.getCreatedAt());
        }
        testCases.put(id, testCase);
        return ResponseEntity.ok(testCase);
    }

    /**
     * Delete a test case.
     */
    @DeleteMapping("/test-cases/{id}")
    public ResponseEntity<Void> deleteTestCase(@PathVariable String id) {
        testCases.remove(id);
        return ResponseEntity.ok().build();
    }

    /**
     * Get test cases for a specific fact sheet.
     */
    @GetMapping("/fact-sheets/{factSheetId}/test-cases")
    public ResponseEntity<List<EvalTestCaseDto>> getTestCasesForFactSheet(@PathVariable Long factSheetId) {
        List<EvalTestCaseDto> result = testCases.values().stream()
                .filter(tc -> Objects.equals(tc.getFactSheetId(), factSheetId))
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    // ==================== Suite Endpoints ====================

    /**
     * Create a new evaluation suite.
     */
    @PostMapping("/suites")
    public ResponseEntity<EvalSuiteDto> createSuite(@RequestBody EvalSuiteDto suite) {
        if (suite.getId() == null || suite.getId().isEmpty()) {
            suite.setId(UUID.randomUUID().toString());
        }
        suite.setCreatedAt(Instant.now());
        suite.setUpdatedAt(Instant.now());
        suites.put(suite.getId(), suite);
        log.info("Created suite: {} for fact sheet: {}", suite.getId(), suite.getFactSheetId());
        return ResponseEntity.ok(suite);
    }

    /**
     * Get all suites.
     */
    @GetMapping("/suites")
    public ResponseEntity<List<EvalSuiteDto>> getAllSuites(
            @RequestParam(required = false) Long factSheetId
    ) {
        List<EvalSuiteDto> result = new ArrayList<>(suites.values());

        if (factSheetId != null) {
            result = result.stream()
                    .filter(s -> Objects.equals(s.getFactSheetId(), factSheetId))
                    .collect(Collectors.toList());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Get a specific suite.
     */
    @GetMapping("/suites/{id}")
    public ResponseEntity<EvalSuiteDto> getSuite(@PathVariable String id) {
        EvalSuiteDto suite = suites.get(id);
        if (suite == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(suite);
    }

    /**
     * Update a suite.
     */
    @PutMapping("/suites/{id}")
    public ResponseEntity<EvalSuiteDto> updateSuite(
            @PathVariable String id,
            @RequestBody EvalSuiteDto suite
    ) {
        if (!suites.containsKey(id)) {
            return ResponseEntity.notFound().build();
        }
        suite.setId(id);
        suite.setUpdatedAt(Instant.now());
        EvalSuiteDto existing = suites.get(id);
        if (existing != null) {
            suite.setCreatedAt(existing.getCreatedAt());
        }
        suites.put(id, suite);
        return ResponseEntity.ok(suite);
    }

    /**
     * Delete a suite.
     */
    @DeleteMapping("/suites/{id}")
    public ResponseEntity<Void> deleteSuite(@PathVariable String id) {
        suites.remove(id);
        return ResponseEntity.ok().build();
    }

    /**
     * Add a test case to a suite.
     */
    @PostMapping("/suites/{suiteId}/test-cases/{testCaseId}")
    public ResponseEntity<EvalSuiteDto> addTestCaseToSuite(
            @PathVariable String suiteId,
            @PathVariable String testCaseId
    ) {
        EvalSuiteDto suite = suites.get(suiteId);
        if (suite == null) {
            return ResponseEntity.notFound().build();
        }
        if (!testCases.containsKey(testCaseId)) {
            return ResponseEntity.badRequest().build();
        }

        if (suite.getTestCaseIds() == null) {
            suite.setTestCaseIds(new ArrayList<>());
        }
        if (!suite.getTestCaseIds().contains(testCaseId)) {
            suite.getTestCaseIds().add(testCaseId);
            suite.setUpdatedAt(Instant.now());
        }
        suites.put(suiteId, suite);
        return ResponseEntity.ok(suite);
    }

    /**
     * Remove a test case from a suite.
     */
    @DeleteMapping("/suites/{suiteId}/test-cases/{testCaseId}")
    public ResponseEntity<EvalSuiteDto> removeTestCaseFromSuite(
            @PathVariable String suiteId,
            @PathVariable String testCaseId
    ) {
        EvalSuiteDto suite = suites.get(suiteId);
        if (suite == null) {
            return ResponseEntity.notFound().build();
        }

        if (suite.getTestCaseIds() != null) {
            suite.getTestCaseIds().remove(testCaseId);
            suite.setUpdatedAt(Instant.now());
        }
        suites.put(suiteId, suite);
        return ResponseEntity.ok(suite);
    }

    // ==================== Results/Metrics Endpoints ====================

    /**
     * Get test results for a fact sheet.
     */
    @GetMapping("/fact-sheets/{factSheetId}/results")
    public ResponseEntity<List<EvalTestResultDto>> getResultsForFactSheet(
            @PathVariable Long factSheetId,
            @RequestParam(defaultValue = "50") int limit
    ) {
        List<EvalTestResultDto> results = testResults.values().stream()
                .filter(r -> Objects.equals(r.getFactSheetId(), factSheetId))
                .sorted(Comparator.comparing(EvalTestResultDto::getCompletedAt).reversed())
                .limit(limit)
                .collect(Collectors.toList());
        return ResponseEntity.ok(results);
    }

    /**
     * Get test results for a specific test case.
     */
    @GetMapping("/test-cases/{testCaseId}/results")
    public ResponseEntity<List<EvalTestResultDto>> getResultsForTestCase(
            @PathVariable String testCaseId,
            @RequestParam(defaultValue = "20") int limit
    ) {
        List<EvalTestResultDto> results = testResults.values().stream()
                .filter(r -> Objects.equals(r.getTestCaseId(), testCaseId))
                .sorted(Comparator.comparing(EvalTestResultDto::getCompletedAt).reversed())
                .limit(limit)
                .collect(Collectors.toList());
        return ResponseEntity.ok(results);
    }

    /**
     * Get metrics for a fact sheet.
     */
    @GetMapping("/fact-sheets/{factSheetId}/metrics")
    public ResponseEntity<FactSheetMetricsDto> getMetricsForFactSheet(@PathVariable Long factSheetId) {
        List<EvalTestCaseDto> cases = testCases.values().stream()
                .filter(tc -> Objects.equals(tc.getFactSheetId(), factSheetId))
                .collect(Collectors.toList());

        List<EvalTestResultDto> results = testResults.values().stream()
                .filter(r -> Objects.equals(r.getFactSheetId(), factSheetId))
                .collect(Collectors.toList());

        double passRate = results.isEmpty() ? 0.0 :
                (double) results.stream().filter(EvalTestResultDto::isPassed).count() / results.size();

        double avgScore = results.stream()
                .mapToDouble(EvalTestResultDto::getScore)
                .average()
                .orElse(0.0);

        // Find failing test cases
        Set<String> failingTestCaseIds = results.stream()
                .filter(r -> !r.isPassed())
                .map(EvalTestResultDto::getTestCaseId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<EvalTestCaseDto> failingCases = cases.stream()
                .filter(tc -> failingTestCaseIds.contains(tc.getId()))
                .limit(5)
                .collect(Collectors.toList());

        return ResponseEntity.ok(FactSheetMetricsDto.builder()
                .factSheetId(factSheetId)
                .totalTestCases(cases.size())
                .enabledTestCases((int) cases.stream().filter(EvalTestCaseDto::isEnabled).count())
                .totalResults(results.size())
                .passRate(passRate)
                .averageScore(avgScore)
                .failingTestCases(failingCases)
                .build());
    }

    /**
     * Get available evaluation types.
     */
    @GetMapping("/evaluation-types")
    public ResponseEntity<List<EvaluationTypeDto>> getEvaluationTypes() {
        List<EvaluationTypeDto> types = Arrays.stream(EvaluationType.values())
                .map(t -> EvaluationTypeDto.builder()
                        .type(t.name())
                        .name(formatTypeName(t.name()))
                        .description(getTypeDescription(t))
                        .build())
                .collect(Collectors.toList());
        return ResponseEntity.ok(types);
    }

    // ==================== Helper Methods ====================

    private String getProperty(String key, String defaultValue) {
        return environment.getProperty(key, defaultValue);
    }

    private boolean getBooleanProperty(String key, boolean defaultValue) {
        return Boolean.parseBoolean(environment.getProperty(key, String.valueOf(defaultValue)));
    }

    private int getIntProperty(String key, int defaultValue) {
        try {
            return Integer.parseInt(environment.getProperty(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private double getDoubleProperty(String key, double defaultValue) {
        try {
            return Double.parseDouble(environment.getProperty(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String formatTypeName(String name) {
        String formatted = name.toLowerCase().replace('_', ' ');
        return Character.toUpperCase(formatted.charAt(0)) + formatted.substring(1);
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

    // ==================== DTOs ====================

    @Data
    @Builder
    public static class ReActConfigDto {
        private boolean enabled;
        private int maxSteps;
        private String executionMode;
        private boolean graphRagEnabled;
        private String graphRagSearchType;
        private int graphRagMaxResults;
        private boolean filterChainEnabled;
        private boolean evalBasedEnabled;
        private boolean evalTrackingEnabled;
        private boolean evalHookEnabled;
        private boolean selfEvaluate;
        private boolean evaluateReasoning;
        private double qualityThreshold;
        private int evalRetentionDays;
        private boolean summarizeResults;
        private int maxResultLength;
    }

    @Data
    @Builder
    public static class StatusDto {
        private boolean available;
        private boolean evalTrackingEnabled;
        private int testCaseCount;
        private int suiteCount;
        private int resultCount;
    }

    @Data
    @Builder
    public static class EvalTestCaseDto {
        private String id;
        private String name;
        private String description;
        private Long factSheetId;
        private String factSheetName;
        private String query;
        private String expectedAnswer;
        private List<String> expectedFacts;
        private List<String> forbiddenFacts;
        private List<String> expectedEntities;
        private List<String> expectedToolCalls;
        private List<String> evaluationTypes;
        private Map<String, Double> thresholds;
        private List<String> tags;
        private int priority;
        private boolean enabled;
        private long timeoutMs;
        private Instant createdAt;
        private Instant updatedAt;
        private Map<String, Object> metadata;

        public void setId(String id) { this.id = id; }
        public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
        public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    }

    @Data
    @Builder
    public static class EvalSuiteDto {
        private String id;
        private String name;
        private String description;
        private Long factSheetId;
        private List<String> testCaseIds;
        private List<String> tags;
        private boolean enabled;
        private double requiredPassRate;
        private Instant createdAt;
        private Instant updatedAt;
        private Map<String, Object> metadata;

        public void setId(String id) { this.id = id; }
        public void setTestCaseIds(List<String> testCaseIds) { this.testCaseIds = testCaseIds; }
        public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
        public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    }

    @Data
    @Builder
    public static class EvalTestResultDto {
        private String id;
        private String testCaseId;
        private String testCaseName;
        private String suiteId;
        private Long factSheetId;
        private String executionId;
        private boolean passed;
        private double score;
        private String query;
        private String expectedAnswer;
        private String actualAnswer;
        private List<String> retrievedDocuments;
        private List<String> toolCalls;
        private int stepsExecuted;
        private Map<String, Double> scores;
        private Map<String, Boolean> passedByType;
        private List<String> failureReasons;
        private Instant startedAt;
        private Instant completedAt;
        private long executionTimeMs;
        private long totalTokens;
        private Map<String, Object> metadata;
    }

    @Data
    @Builder
    public static class FactSheetMetricsDto {
        private Long factSheetId;
        private int totalTestCases;
        private int enabledTestCases;
        private int totalResults;
        private double passRate;
        private double averageScore;
        private List<EvalTestCaseDto> failingTestCases;
    }

    @Data
    @Builder
    public static class EvaluationTypeDto {
        private String type;
        private String name;
        private String description;
    }
}
