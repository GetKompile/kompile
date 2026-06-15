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
package ai.kompile.app.eval.service;

import ai.kompile.app.eval.domain.EvalCaseEntity;
import ai.kompile.app.eval.domain.EvalSuiteEntity;
import ai.kompile.app.eval.domain.EvalSuiteResultEntity;
import ai.kompile.app.eval.domain.EvalTestResultEntity;
import ai.kompile.core.evaluation.EvaluationType;
import ai.kompile.react.eval.model.EvalCase;
import ai.kompile.react.eval.model.EvalSuite;
import ai.kompile.react.eval.model.EvalSuiteResult;
import ai.kompile.react.eval.model.EvalTestResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Converts between JPA entities and model classes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EvalEntityConverter {

    private final ObjectMapper objectMapper;

    // ==================== EvalCase Conversions ====================

    /**
     * Convert EvalCase model to EvalCaseEntity.
     */
    public EvalCaseEntity toEntity(EvalCase model) {
        if (model == null) {
            return null;
        }

        EvalCaseEntity entity = EvalCaseEntity.builder()
                .id(model.getId())
                .name(model.getName())
                .description(model.getDescription())
                .factSheetId(model.getFactSheetId())
                .factSheetName(model.getFactSheetName())
                .query(model.getQuery())
                .expectedAnswer(model.getExpectedAnswer())
                .priority(model.getPriority())
                .enabled(model.isEnabled())
                .timeoutMs(model.getTimeoutMs())
                .createdAt(model.getCreatedAt())
                .updatedAt(model.getUpdatedAt())
                .build();

        // Serialize lists to JSON
        entity.setExpectedFactsJson(toJson(model.getExpectedFacts()));
        entity.setForbiddenFactsJson(toJson(model.getForbiddenFacts()));
        entity.setExpectedEntitiesJson(toJson(model.getExpectedEntities()));
        entity.setExpectedToolCallsJson(toJson(model.getExpectedToolCalls()));
        entity.setTagsJson(toJson(model.getTags()));

        // Serialize evaluation types
        if (model.getEvaluationTypes() != null) {
            List<String> typeNames = model.getEvaluationTypes().stream()
                    .map(Enum::name)
                    .collect(Collectors.toList());
            entity.setEvaluationTypesJson(toJson(typeNames));
        }

        // Serialize thresholds map
        if (model.getThresholds() != null) {
            Map<String, Double> thresholdMap = model.getThresholds().entrySet().stream()
                    .collect(Collectors.toMap(e -> e.getKey().name(), Map.Entry::getValue));
            entity.setThresholdsJson(toJson(thresholdMap));
        }

        // Serialize metadata
        entity.setMetadataJson(toJson(model.getMetadata()));

        return entity;
    }

    /**
     * Convert EvalCaseEntity to EvalCase model.
     */
    public EvalCase toModel(EvalCaseEntity entity) {
        if (entity == null) {
            return null;
        }

        EvalCase.EvalCaseBuilder builder = EvalCase.builder()
                .id(entity.getId())
                .name(entity.getName())
                .description(entity.getDescription())
                .factSheetId(entity.getFactSheetId())
                .factSheetName(entity.getFactSheetName())
                .query(entity.getQuery())
                .expectedAnswer(entity.getExpectedAnswer())
                .priority(entity.getPriority() != null ? entity.getPriority() : 3)
                .enabled(entity.getEnabled() != null ? entity.getEnabled() : true)
                .timeoutMs(entity.getTimeoutMs() != null ? entity.getTimeoutMs() : 30000L)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt());

        // Deserialize lists from JSON
        builder.expectedFacts(fromJsonList(entity.getExpectedFactsJson()));
        builder.forbiddenFacts(fromJsonList(entity.getForbiddenFactsJson()));
        builder.expectedEntities(fromJsonList(entity.getExpectedEntitiesJson()));
        builder.expectedToolCalls(fromJsonList(entity.getExpectedToolCallsJson()));
        builder.tags(fromJsonList(entity.getTagsJson()));

        // Deserialize evaluation types
        List<String> typeNames = fromJsonList(entity.getEvaluationTypesJson());
        if (typeNames != null && !typeNames.isEmpty()) {
            List<EvaluationType> types = typeNames.stream()
                    .map(name -> {
                        try {
                            return EvaluationType.valueOf(name);
                        } catch (IllegalArgumentException e) {
                            log.warn("Unknown evaluation type: {}", name);
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            builder.evaluationTypes(types);
        }

        // Deserialize thresholds map
        Map<String, Double> thresholdMap = fromJsonMap(entity.getThresholdsJson());
        if (thresholdMap != null && !thresholdMap.isEmpty()) {
            Map<EvaluationType, Double> thresholds = thresholdMap.entrySet().stream()
                    .collect(Collectors.toMap(
                            e -> {
                                try {
                                    return EvaluationType.valueOf(e.getKey());
                                } catch (IllegalArgumentException ex) {
                                    log.warn("Unknown evaluation type in thresholds: {}", e.getKey());
                                    return null;
                                }
                            },
                            Map.Entry::getValue
                    ));
            thresholds.remove(null);
            builder.thresholds(thresholds);
        }

        // Deserialize metadata
        builder.metadata(fromJsonObjectMap(entity.getMetadataJson()));

        return builder.build();
    }

    // ==================== EvalSuite Conversions ====================

    /**
     * Convert EvalSuite model to EvalSuiteEntity.
     */
    public EvalSuiteEntity toEntity(EvalSuite model) {
        if (model == null) {
            return null;
        }

        EvalSuiteEntity entity = EvalSuiteEntity.builder()
                .id(model.getId())
                .name(model.getName())
                .description(model.getDescription())
                .factSheetId(model.getFactSheetId())
                .enabled(model.isEnabled())
                .requiredPassRate(model.getRequiredPassRate())
                .createdAt(model.getCreatedAt())
                .updatedAt(model.getUpdatedAt())
                .build();

        entity.setTagsJson(toJson(model.getTags()));
        entity.setMetadataJson(toJson(model.getMetadata()));

        // Note: test cases are converted separately to handle bidirectional relationship
        return entity;
    }

    /**
     * Convert EvalSuiteEntity to EvalSuite model.
     */
    public EvalSuite toModel(EvalSuiteEntity entity) {
        return toModel(entity, true);
    }

    /**
     * Convert EvalSuiteEntity to EvalSuite model with option to include test cases.
     */
    public EvalSuite toModel(EvalSuiteEntity entity, boolean includeTestCases) {
        if (entity == null) {
            return null;
        }

        EvalSuite.EvalSuiteBuilder builder = EvalSuite.builder()
                .id(entity.getId())
                .name(entity.getName())
                .description(entity.getDescription())
                .factSheetId(entity.getFactSheetId())
                .enabled(entity.getEnabled() != null ? entity.getEnabled() : true)
                .requiredPassRate(entity.getRequiredPassRate() != null ? entity.getRequiredPassRate() : 0.8)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt());

        builder.tags(fromJsonList(entity.getTagsJson()));
        builder.metadata(fromJsonObjectMap(entity.getMetadataJson()));

        // Convert test cases if requested and available
        if (includeTestCases && entity.getTestCases() != null) {
            List<EvalCase> testCases = entity.getTestCases().stream()
                    .map(this::toModel)
                    .collect(Collectors.toList());
            builder.testCases(new ArrayList<>(testCases));
        }

        return builder.build();
    }

    // ==================== EvalTestResult Conversions ====================

    /**
     * Convert EvalTestResult model to EvalTestResultEntity.
     */
    public EvalTestResultEntity toEntity(EvalTestResult model) {
        if (model == null) {
            return null;
        }

        EvalTestResultEntity entity = EvalTestResultEntity.builder()
                .id(model.getId())
                .testCaseId(model.getTestCaseId())
                .testCaseName(model.getTestCaseName())
                .suiteId(model.getSuiteId())
                .factSheetId(model.getFactSheetId())
                .executionId(model.getExecutionId())
                .passed(model.isPassed())
                .score(model.getScore())
                .query(model.getQuery())
                .expectedAnswer(model.getExpectedAnswer())
                .actualAnswer(model.getActualAnswer())
                .stepsExecuted(model.getStepsExecuted())
                .startedAt(model.getStartedAt())
                .completedAt(model.getCompletedAt())
                .executionTimeMs(model.getExecutionTimeMs())
                .totalTokens(model.getTotalTokens())
                .build();

        entity.setRetrievedDocumentsJson(toJson(model.getRetrievedDocuments()));
        entity.setToolCallsJson(toJson(model.getToolCalls()));
        entity.setFailureReasonsJson(toJson(model.getFailureReasons()));

        // Serialize scores map
        if (model.getScores() != null) {
            Map<String, Double> scoresMap = model.getScores().entrySet().stream()
                    .collect(Collectors.toMap(e -> e.getKey().name(), Map.Entry::getValue));
            entity.setScoresJson(toJson(scoresMap));
        }

        // Serialize passedByType map
        if (model.getPassedByType() != null) {
            Map<String, Boolean> passedMap = model.getPassedByType().entrySet().stream()
                    .collect(Collectors.toMap(e -> e.getKey().name(), Map.Entry::getValue));
            entity.setPassedByTypeJson(toJson(passedMap));
        }

        entity.setMetadataJson(toJson(model.getMetadata()));

        // Note: evaluationResults contains complex objects that may need special handling
        // For now, we store a simplified version
        if (model.getEvaluationResults() != null && !model.getEvaluationResults().isEmpty()) {
            entity.setEvaluationResultsJson(toJson(serializeEvaluationResults(model.getEvaluationResults())));
        }

        return entity;
    }

    /**
     * Convert EvalTestResultEntity to EvalTestResult model.
     */
    public EvalTestResult toModel(EvalTestResultEntity entity) {
        if (entity == null) {
            return null;
        }

        EvalTestResult.EvalTestResultBuilder builder = EvalTestResult.builder()
                .id(entity.getId())
                .testCaseId(entity.getTestCaseId())
                .testCaseName(entity.getTestCaseName())
                .suiteId(entity.getSuiteId())
                .factSheetId(entity.getFactSheetId())
                .executionId(entity.getExecutionId())
                .passed(entity.getPassed() != null ? entity.getPassed() : false)
                .score(entity.getScore() != null ? entity.getScore() : 0.0)
                .query(entity.getQuery())
                .expectedAnswer(entity.getExpectedAnswer())
                .actualAnswer(entity.getActualAnswer())
                .stepsExecuted(entity.getStepsExecuted() != null ? entity.getStepsExecuted() : 0)
                .startedAt(entity.getStartedAt())
                .completedAt(entity.getCompletedAt())
                .executionTimeMs(entity.getExecutionTimeMs() != null ? entity.getExecutionTimeMs() : 0L)
                .totalTokens(entity.getTotalTokens() != null ? entity.getTotalTokens() : 0L);

        builder.retrievedDocuments(new ArrayList<>(fromJsonList(entity.getRetrievedDocumentsJson())));
        builder.toolCalls(new ArrayList<>(fromJsonList(entity.getToolCallsJson())));
        builder.failureReasons(new ArrayList<>(fromJsonList(entity.getFailureReasonsJson())));

        // Deserialize scores map
        Map<String, Double> scoresMap = fromJsonMap(entity.getScoresJson());
        if (scoresMap != null && !scoresMap.isEmpty()) {
            Map<EvaluationType, Double> scores = new HashMap<>();
            for (Map.Entry<String, Double> entry : scoresMap.entrySet()) {
                try {
                    scores.put(EvaluationType.valueOf(entry.getKey()), entry.getValue());
                } catch (IllegalArgumentException e) {
                    log.warn("Unknown evaluation type in scores: {}", entry.getKey());
                }
            }
            builder.scores(scores);
        }

        // Deserialize passedByType map
        Map<String, Boolean> passedMap = fromJsonMapBoolean(entity.getPassedByTypeJson());
        if (passedMap != null && !passedMap.isEmpty()) {
            Map<EvaluationType, Boolean> passedByType = new HashMap<>();
            for (Map.Entry<String, Boolean> entry : passedMap.entrySet()) {
                try {
                    passedByType.put(EvaluationType.valueOf(entry.getKey()), entry.getValue());
                } catch (IllegalArgumentException e) {
                    log.warn("Unknown evaluation type in passedByType: {}", entry.getKey());
                }
            }
            builder.passedByType(passedByType);
        }

        builder.metadata(fromJsonObjectMap(entity.getMetadataJson()));

        return builder.build();
    }

    // ==================== EvalSuiteResult Conversions ====================

    /**
     * Convert EvalSuiteResult model to EvalSuiteResultEntity.
     */
    public EvalSuiteResultEntity toEntity(EvalSuiteResult model) {
        if (model == null) {
            return null;
        }

        EvalSuiteResultEntity entity = EvalSuiteResultEntity.builder()
                .id(model.getId())
                .suiteId(model.getSuiteId())
                .suiteName(model.getSuiteName())
                .factSheetId(model.getFactSheetId())
                .passed(model.isPassed())
                .passRate(model.getPassRate())
                .averageScore(model.getAverageScore())
                .passedCount(model.getPassedCount())
                .failedCount(model.getFailedCount())
                .skippedCount(model.getSkippedCount())
                .totalCount(model.getTotalCount())
                .startedAt(model.getStartedAt())
                .completedAt(model.getCompletedAt())
                .executionTimeMs(model.getExecutionTimeMs())
                .totalTokens(model.getTotalTokens())
                .build();

        // Serialize maps
        if (model.getAverageScoresByType() != null) {
            Map<String, Double> scoresMap = model.getAverageScoresByType().entrySet().stream()
                    .collect(Collectors.toMap(e -> e.getKey().name(), Map.Entry::getValue));
            entity.setAverageScoresByTypeJson(toJson(scoresMap));
        }

        if (model.getPassRatesByType() != null) {
            Map<String, Double> ratesMap = model.getPassRatesByType().entrySet().stream()
                    .collect(Collectors.toMap(e -> e.getKey().name(), Map.Entry::getValue));
            entity.setPassRatesByTypeJson(toJson(ratesMap));
        }

        entity.setFailedTestsJson(toJson(model.getFailedTests()));
        entity.setMetadataJson(toJson(model.getMetadata()));

        // Note: test results are converted separately to handle relationship
        return entity;
    }

    /**
     * Convert EvalSuiteResultEntity to EvalSuiteResult model.
     */
    public EvalSuiteResult toModel(EvalSuiteResultEntity entity) {
        return toModel(entity, true);
    }

    /**
     * Convert EvalSuiteResultEntity to EvalSuiteResult model with option to include test results.
     */
    public EvalSuiteResult toModel(EvalSuiteResultEntity entity, boolean includeTestResults) {
        if (entity == null) {
            return null;
        }

        EvalSuiteResult.EvalSuiteResultBuilder builder = EvalSuiteResult.builder()
                .id(entity.getId())
                .suiteId(entity.getSuiteId())
                .suiteName(entity.getSuiteName())
                .factSheetId(entity.getFactSheetId())
                .passed(entity.getPassed() != null ? entity.getPassed() : false)
                .passRate(entity.getPassRate() != null ? entity.getPassRate() : 0.0)
                .averageScore(entity.getAverageScore() != null ? entity.getAverageScore() : 0.0)
                .passedCount(entity.getPassedCount() != null ? entity.getPassedCount() : 0)
                .failedCount(entity.getFailedCount() != null ? entity.getFailedCount() : 0)
                .skippedCount(entity.getSkippedCount() != null ? entity.getSkippedCount() : 0)
                .totalCount(entity.getTotalCount() != null ? entity.getTotalCount() : 0)
                .startedAt(entity.getStartedAt())
                .completedAt(entity.getCompletedAt())
                .executionTimeMs(entity.getExecutionTimeMs() != null ? entity.getExecutionTimeMs() : 0L)
                .totalTokens(entity.getTotalTokens() != null ? entity.getTotalTokens() : 0L);

        // Deserialize averageScoresByType
        Map<String, Double> scoresMap = fromJsonMap(entity.getAverageScoresByTypeJson());
        if (scoresMap != null && !scoresMap.isEmpty()) {
            Map<EvaluationType, Double> scores = new HashMap<>();
            for (Map.Entry<String, Double> entry : scoresMap.entrySet()) {
                try {
                    scores.put(EvaluationType.valueOf(entry.getKey()), entry.getValue());
                } catch (IllegalArgumentException e) {
                    log.warn("Unknown evaluation type: {}", entry.getKey());
                }
            }
            builder.averageScoresByType(scores);
        }

        // Deserialize passRatesByType
        Map<String, Double> ratesMap = fromJsonMap(entity.getPassRatesByTypeJson());
        if (ratesMap != null && !ratesMap.isEmpty()) {
            Map<EvaluationType, Double> rates = new HashMap<>();
            for (Map.Entry<String, Double> entry : ratesMap.entrySet()) {
                try {
                    rates.put(EvaluationType.valueOf(entry.getKey()), entry.getValue());
                } catch (IllegalArgumentException e) {
                    log.warn("Unknown evaluation type: {}", entry.getKey());
                }
            }
            builder.passRatesByType(rates);
        }

        // Deserialize failedTests
        Map<String, List<String>> failedTests = fromJsonMapList(entity.getFailedTestsJson());
        if (failedTests != null) {
            builder.failedTests(new HashMap<>(failedTests));
        }

        builder.metadata(new HashMap<>(fromJsonObjectMap(entity.getMetadataJson())));

        // Convert test results if requested
        if (includeTestResults && entity.getTestResults() != null) {
            List<EvalTestResult> testResults = entity.getTestResults().stream()
                    .map(this::toModel)
                    .collect(Collectors.toList());
            builder.testResults(new ArrayList<>(testResults));
        }

        return builder.build();
    }

    // ==================== JSON Helper Methods ====================

    private String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize object to JSON", e);
            return null;
        }
    }

    private List<String> fromJsonList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize JSON to List<String>: {}", json, e);
            return new ArrayList<>();
        }
    }

    private Map<String, Double> fromJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Double>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize JSON to Map<String, Double>: {}", json, e);
            return new HashMap<>();
        }
    }

    private Map<String, Boolean> fromJsonMapBoolean(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Boolean>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize JSON to Map<String, Boolean>: {}", json, e);
            return new HashMap<>();
        }
    }

    private Map<String, Object> fromJsonObjectMap(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize JSON to Map<String, Object>: {}", json, e);
            return new HashMap<>();
        }
    }

    private Map<String, List<String>> fromJsonMapList(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, List<String>>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize JSON to Map<String, List<String>>: {}", json, e);
            return new HashMap<>();
        }
    }

    /**
     * Serialize evaluation results to a simplified map format for storage.
     */
    private Map<String, Object> serializeEvaluationResults(
            Map<EvaluationType, ai.kompile.core.evaluation.EvaluationResult> results) {
        Map<String, Object> serialized = new HashMap<>();
        for (Map.Entry<EvaluationType, ai.kompile.core.evaluation.EvaluationResult> entry : results.entrySet()) {
            ai.kompile.core.evaluation.EvaluationResult result = entry.getValue();
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("passed", result.isPassed());
            resultMap.put("score", result.getScore());
            resultMap.put("explanation", result.getExplanation());
            resultMap.put("confidence", result.getConfidence());
            serialized.put(entry.getKey().name(), resultMap);
        }
        return serialized;
    }
}
