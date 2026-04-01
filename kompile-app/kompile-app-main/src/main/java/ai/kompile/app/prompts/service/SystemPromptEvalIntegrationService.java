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
package ai.kompile.app.prompts.service;

import ai.kompile.app.eval.service.EvalSetService;
import ai.kompile.app.prompts.domain.SystemPromptEntity;
import ai.kompile.app.prompts.domain.SystemPromptTestResultEntity;
import ai.kompile.app.prompts.repository.SystemPromptTestResultRepository;
import ai.kompile.react.eval.model.EvalCase;
import ai.kompile.react.eval.model.EvalSuite;
import ai.kompile.react.eval.model.EvalTestResult;
import ai.kompile.react.eval.model.EvalSuiteResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for integrating system prompts with the evaluation framework.
 * Allows testing prompts against eval suites and tracking results.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SystemPromptEvalIntegrationService {

    private final SystemPromptService promptService;
    private final SystemPromptTestResultRepository testResultRepository;
    private final EvalSetService evalSetService;
    private final ObjectMapper objectMapper;

    /**
     * Get all available eval suites for the prompt's fact sheet.
     */
    @Transactional(readOnly = true)
    public List<EvalSuite> getAvailableSuitesForPrompt(String promptId) {
        SystemPromptEntity prompt = promptService.getPromptById(promptId)
                .orElseThrow(() -> new IllegalArgumentException("Prompt not found: " + promptId));

        return evalSetService.getSuitesForFactSheet(prompt.getFactSheetId());
    }

    /**
     * Test a prompt against a specific eval suite.
     * Note: This records the test but does not actually run evaluations.
     * The actual evaluation should be done via the eval framework.
     */
    @Transactional
    public SystemPromptTestResultEntity recordTestResult(
            String promptId,
            String evalSuiteId,
            boolean passed,
            double score,
            int passedCount,
            int failedCount,
            Map<String, Object> detailedResults) {

        SystemPromptEntity prompt = promptService.getPromptById(promptId)
                .orElseThrow(() -> new IllegalArgumentException("Prompt not found: " + promptId));

        EvalSuite suite = evalSetService.getSuiteById(evalSuiteId)
                .orElseThrow(() -> new IllegalArgumentException("Eval suite not found: " + evalSuiteId));

        Instant now = Instant.now();
        String resultsJson = null;
        try {
            if (detailedResults != null) {
                resultsJson = objectMapper.writeValueAsString(detailedResults);
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize detailed results: {}", e.getMessage());
        }

        SystemPromptTestResultEntity result = SystemPromptTestResultEntity.builder()
                .id(UUID.randomUUID().toString())
                .promptId(promptId)
                .promptName(prompt.getName())
                .promptVersion(prompt.getVersion())
                .evalSuiteId(evalSuiteId)
                .evalSuiteName(suite.getName())
                .passed(passed)
                .score(score)
                .passedCount(passedCount)
                .failedCount(failedCount)
                .totalCount(passedCount + failedCount)
                .resultsJson(resultsJson)
                .startedAt(now)
                .completedAt(now)
                .executionTimeMs(0L)
                .build();

        testResultRepository.save(result);
        log.info("Recorded test result for prompt {} against suite {}: passed={}, score={}",
                promptId, evalSuiteId, passed, score);

        return result;
    }

    /**
     * Start a test run (creates a pending result).
     */
    @Transactional
    public SystemPromptTestResultEntity startTestRun(String promptId, String evalSuiteId) {
        SystemPromptEntity prompt = promptService.getPromptById(promptId)
                .orElseThrow(() -> new IllegalArgumentException("Prompt not found: " + promptId));

        EvalSuite suite = evalSetService.getSuiteById(evalSuiteId)
                .orElseThrow(() -> new IllegalArgumentException("Eval suite not found: " + evalSuiteId));

        SystemPromptTestResultEntity result = SystemPromptTestResultEntity.builder()
                .id(UUID.randomUUID().toString())
                .promptId(promptId)
                .promptName(prompt.getName())
                .promptVersion(prompt.getVersion())
                .evalSuiteId(evalSuiteId)
                .evalSuiteName(suite.getName())
                .passed(false)
                .score(0.0)
                .passedCount(0)
                .failedCount(0)
                .totalCount(0)
                .startedAt(Instant.now())
                .build();

        return testResultRepository.save(result);
    }

    /**
     * Complete a test run with results.
     */
    @Transactional
    public SystemPromptTestResultEntity completeTestRun(
            String testResultId,
            boolean passed,
            double score,
            int passedCount,
            int failedCount,
            Map<String, Object> detailedResults,
            String errorMessage) {

        SystemPromptTestResultEntity result = testResultRepository.findById(testResultId)
                .orElseThrow(() -> new IllegalArgumentException("Test result not found: " + testResultId));

        Instant now = Instant.now();
        result.setPassed(passed);
        result.setScore(score);
        result.setPassedCount(passedCount);
        result.setFailedCount(failedCount);
        result.setTotalCount(passedCount + failedCount);
        result.setCompletedAt(now);
        result.setExecutionTimeMs(now.toEpochMilli() - result.getStartedAt().toEpochMilli());
        result.setErrorMessage(errorMessage);

        if (detailedResults != null) {
            try {
                result.setResultsJson(objectMapper.writeValueAsString(detailedResults));
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize detailed results: {}", e.getMessage());
            }
        }

        return testResultRepository.save(result);
    }

    /**
     * Get test result history for a prompt.
     */
    @Transactional(readOnly = true)
    public List<SystemPromptTestResultEntity> getTestResultHistory(String promptId) {
        return testResultRepository.findByPromptIdOrderByCompletedAtDesc(promptId);
    }

    /**
     * Get test result history for a prompt against a specific suite.
     */
    @Transactional(readOnly = true)
    public List<SystemPromptTestResultEntity> getTestResultHistory(String promptId, String evalSuiteId) {
        return testResultRepository.findByPromptIdAndEvalSuiteIdOrderByCompletedAtDesc(promptId, evalSuiteId);
    }

    /**
     * Get the most recent test result for a prompt.
     */
    @Transactional(readOnly = true)
    public SystemPromptTestResultEntity getMostRecentTestResult(String promptId) {
        return testResultRepository.findMostRecentByPromptId(promptId);
    }

    /**
     * Get average score for a prompt across all tests.
     */
    @Transactional(readOnly = true)
    public Double getAverageScore(String promptId) {
        return testResultRepository.findAverageScoreByPromptId(promptId);
    }

    /**
     * Compare two prompts against the same eval suite.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> comparePrompts(String promptId1, String promptId2, String evalSuiteId) {
        SystemPromptEntity prompt1 = promptService.getPromptById(promptId1)
                .orElseThrow(() -> new IllegalArgumentException("Prompt not found: " + promptId1));
        SystemPromptEntity prompt2 = promptService.getPromptById(promptId2)
                .orElseThrow(() -> new IllegalArgumentException("Prompt not found: " + promptId2));

        List<SystemPromptTestResultEntity> results =
                testResultRepository.findForComparison(promptId1, promptId2, evalSuiteId);

        // Group results by prompt
        Map<String, List<SystemPromptTestResultEntity>> byPrompt = results.stream()
                .collect(Collectors.groupingBy(SystemPromptTestResultEntity::getPromptId));

        // Get the most recent result for each prompt
        SystemPromptTestResultEntity result1 = byPrompt.getOrDefault(promptId1, Collections.emptyList())
                .stream().findFirst().orElse(null);
        SystemPromptTestResultEntity result2 = byPrompt.getOrDefault(promptId2, Collections.emptyList())
                .stream().findFirst().orElse(null);

        Map<String, Object> comparison = new HashMap<>();
        comparison.put("prompt1", Map.of(
                "id", prompt1.getId(),
                "name", prompt1.getName(),
                "version", prompt1.getVersion(),
                "result", result1 != null ? formatResultSummary(result1) : null
        ));
        comparison.put("prompt2", Map.of(
                "id", prompt2.getId(),
                "name", prompt2.getName(),
                "version", prompt2.getVersion(),
                "result", result2 != null ? formatResultSummary(result2) : null
        ));

        // Calculate comparison metrics
        if (result1 != null && result2 != null) {
            double scoreDiff = (result1.getScore() != null ? result1.getScore() : 0.0) -
                               (result2.getScore() != null ? result2.getScore() : 0.0);
            comparison.put("scoreDifference", scoreDiff);
            comparison.put("winner", scoreDiff > 0 ? promptId1 : (scoreDiff < 0 ? promptId2 : "tie"));
        }

        return comparison;
    }

    private Map<String, Object> formatResultSummary(SystemPromptTestResultEntity result) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("id", result.getId());
        summary.put("passed", result.getPassed());
        summary.put("score", result.getScore());
        summary.put("passedCount", result.getPassedCount());
        summary.put("failedCount", result.getFailedCount());
        summary.put("totalCount", result.getTotalCount());
        summary.put("completedAt", result.getCompletedAt());
        summary.put("executionTimeMs", result.getExecutionTimeMs());
        return summary;
    }

    /**
     * Get statistics for a prompt's test history.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getPromptTestStats(String promptId) {
        Map<String, Object> stats = new HashMap<>();

        long totalTests = testResultRepository.countByPromptId(promptId);
        long passedTests = testResultRepository.countByPromptIdAndPassedTrue(promptId);
        Double averageScore = testResultRepository.findAverageScoreByPromptId(promptId);

        stats.put("totalTests", totalTests);
        stats.put("passedTests", passedTests);
        stats.put("failedTests", totalTests - passedTests);
        stats.put("passRate", totalTests > 0 ? (double) passedTests / totalTests : 0.0);
        stats.put("averageScore", averageScore != null ? averageScore : 0.0);

        // Get most recent result
        SystemPromptTestResultEntity mostRecent = testResultRepository.findMostRecentByPromptId(promptId);
        if (mostRecent != null) {
            stats.put("lastTestAt", mostRecent.getCompletedAt());
            stats.put("lastTestPassed", mostRecent.getPassed());
            stats.put("lastTestScore", mostRecent.getScore());
        }

        return stats;
    }

    /**
     * Delete all test results for a prompt.
     */
    @Transactional
    public void deleteTestResultsForPrompt(String promptId) {
        testResultRepository.deleteByPromptId(promptId);
        log.info("Deleted all test results for prompt {}", promptId);
    }
}
