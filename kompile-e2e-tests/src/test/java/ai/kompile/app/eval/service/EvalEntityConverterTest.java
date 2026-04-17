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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for EvalEntityConverter.
 */
@DisplayName("EvalEntityConverter Tests")
class EvalEntityConverterTest {

    private EvalEntityConverter converter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        converter = new EvalEntityConverter(objectMapper);
    }

    // ========================================
    // EvalCase Conversion Tests
    // ========================================

    @Nested
    @DisplayName("EvalCase Conversions")
    class EvalCaseConversions {

        @Test
        @DisplayName("Should convert EvalCase model to entity")
        void shouldConvertModelToEntity() {
            // Arrange
            EvalCase model = EvalCase.builder()
                    .id("test-case-1")
                    .name("Test Case 1")
                    .description("A test case description")
                    .factSheetId(1L)
                    .query("What is the capital of France?")
                    .expectedAnswer("Paris")
                    .expectedFacts(List.of("France is a country", "Paris is the capital"))
                    .forbiddenFacts(List.of("London is the capital"))
                    .expectedEntities(List.of("France", "Paris"))
                    .evaluationTypes(List.of(EvaluationType.RELEVANCY, EvaluationType.FAITHFULNESS))
                    .thresholds(Map.of(EvaluationType.RELEVANCY, 0.8, EvaluationType.FAITHFULNESS, 0.7))
                    .tags(List.of("geography", "capitals"))
                    .priority(4)
                    .enabled(true)
                    .timeoutMs(30000L)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            // Act
            EvalCaseEntity entity = converter.toEntity(model);

            // Assert
            assertNotNull(entity);
            assertEquals("test-case-1", entity.getId());
            assertEquals("Test Case 1", entity.getName());
            assertEquals("A test case description", entity.getDescription());
            assertEquals(1L, entity.getFactSheetId());
            assertEquals("What is the capital of France?", entity.getQuery());
            assertEquals("Paris", entity.getExpectedAnswer());
            assertEquals(4, entity.getPriority());
            assertTrue(entity.getEnabled());
            assertEquals(30000L, entity.getTimeoutMs());

            // Check JSON fields are serialized
            assertNotNull(entity.getExpectedFactsJson());
            assertTrue(entity.getExpectedFactsJson().contains("France is a country"));
            assertNotNull(entity.getEvaluationTypesJson());
            assertTrue(entity.getEvaluationTypesJson().contains("RELEVANCY"));
        }

        @Test
        @DisplayName("Should convert EvalCaseEntity to model")
        void shouldConvertEntityToModel() throws Exception {
            // Arrange
            EvalCaseEntity entity = EvalCaseEntity.builder()
                    .id("test-case-2")
                    .name("Test Case 2")
                    .description("Another test case")
                    .factSheetId(2L)
                    .query("What is 2+2?")
                    .expectedAnswer("4")
                    .priority(3)
                    .enabled(true)
                    .timeoutMs(15000L)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            entity.setExpectedFactsJson(objectMapper.writeValueAsString(List.of("math fact")));
            entity.setTagsJson(objectMapper.writeValueAsString(List.of("math")));
            entity.setEvaluationTypesJson(objectMapper.writeValueAsString(List.of("RELEVANCY")));
            entity.setThresholdsJson(objectMapper.writeValueAsString(Map.of("RELEVANCY", 0.9)));

            // Act
            EvalCase model = converter.toModel(entity);

            // Assert
            assertNotNull(model);
            assertEquals("test-case-2", model.getId());
            assertEquals("Test Case 2", model.getName());
            assertEquals("What is 2+2?", model.getQuery());
            assertEquals("4", model.getExpectedAnswer());
            assertEquals(3, model.getPriority());
            assertTrue(model.isEnabled());

            assertNotNull(model.getExpectedFacts());
            assertEquals(1, model.getExpectedFacts().size());
            assertEquals("math fact", model.getExpectedFacts().get(0));

            assertNotNull(model.getEvaluationTypes());
            assertTrue(model.getEvaluationTypes().contains(EvaluationType.RELEVANCY));

            assertNotNull(model.getThresholds());
            assertEquals(0.9, model.getThresholds().get(EvaluationType.RELEVANCY));
        }

        @Test
        @DisplayName("Should handle null model gracefully")
        void shouldHandleNullModel() {
            assertNull(converter.toEntity((EvalCase) null));
        }

        @Test
        @DisplayName("Should handle null entity gracefully")
        void shouldHandleNullEntity() {
            assertNull(converter.toModel((EvalCaseEntity) null));
        }

        @Test
        @DisplayName("Should use default values for missing entity fields")
        void shouldUseDefaultValuesForMissingFields() {
            // Arrange
            EvalCaseEntity entity = EvalCaseEntity.builder()
                    .id("minimal-case")
                    .name("Minimal")
                    .query("query")
                    .build();

            // Act
            EvalCase model = converter.toModel(entity);

            // Assert
            assertEquals(3, model.getPriority()); // default
            assertTrue(model.isEnabled()); // default
            assertEquals(30000L, model.getTimeoutMs()); // default
        }

        @Test
        @DisplayName("Should roundtrip EvalCase through entity and back")
        void shouldRoundtripEvalCase() {
            // Arrange
            EvalCase original = EvalCase.builder()
                    .id("roundtrip-test")
                    .name("Roundtrip Test")
                    .query("Test query")
                    .expectedAnswer("Expected answer")
                    .expectedFacts(List.of("fact1", "fact2"))
                    .forbiddenFacts(List.of("forbidden1"))
                    .expectedEntities(List.of("entity1"))
                    .evaluationTypes(List.of(EvaluationType.FAITHFULNESS, EvaluationType.COHERENCE))
                    .thresholds(Map.of(EvaluationType.FAITHFULNESS, 0.75))
                    .tags(List.of("tag1", "tag2"))
                    .priority(5)
                    .enabled(false)
                    .timeoutMs(60000L)
                    .build();

            // Act
            EvalCaseEntity entity = converter.toEntity(original);
            EvalCase roundtripped = converter.toModel(entity);

            // Assert
            assertEquals(original.getId(), roundtripped.getId());
            assertEquals(original.getName(), roundtripped.getName());
            assertEquals(original.getQuery(), roundtripped.getQuery());
            assertEquals(original.getExpectedAnswer(), roundtripped.getExpectedAnswer());
            assertEquals(original.getExpectedFacts(), roundtripped.getExpectedFacts());
            assertEquals(original.getForbiddenFacts(), roundtripped.getForbiddenFacts());
            assertEquals(original.getExpectedEntities(), roundtripped.getExpectedEntities());
            assertEquals(original.getEvaluationTypes(), roundtripped.getEvaluationTypes());
            assertEquals(original.getThresholds(), roundtripped.getThresholds());
            assertEquals(original.getTags(), roundtripped.getTags());
            assertEquals(original.getPriority(), roundtripped.getPriority());
            assertEquals(original.isEnabled(), roundtripped.isEnabled());
            assertEquals(original.getTimeoutMs(), roundtripped.getTimeoutMs());
        }
    }

    // ========================================
    // EvalSuite Conversion Tests
    // ========================================

    @Nested
    @DisplayName("EvalSuite Conversions")
    class EvalSuiteConversions {

        @Test
        @DisplayName("Should convert EvalSuite model to entity")
        void shouldConvertModelToEntity() {
            // Arrange
            EvalSuite model = EvalSuite.builder()
                    .id("suite-1")
                    .name("Test Suite 1")
                    .description("A test suite")
                    .factSheetId(1L)
                    .enabled(true)
                    .requiredPassRate(0.9)
                    .tags(List.of("regression", "smoke"))
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            // Act
            EvalSuiteEntity entity = converter.toEntity(model);

            // Assert
            assertNotNull(entity);
            assertEquals("suite-1", entity.getId());
            assertEquals("Test Suite 1", entity.getName());
            assertEquals("A test suite", entity.getDescription());
            assertEquals(1L, entity.getFactSheetId());
            assertTrue(entity.getEnabled());
            assertEquals(0.9, entity.getRequiredPassRate());
            assertNotNull(entity.getTagsJson());
            assertTrue(entity.getTagsJson().contains("regression"));
        }

        @Test
        @DisplayName("Should convert EvalSuiteEntity to model without test cases")
        void shouldConvertEntityToModelWithoutTestCases() throws Exception {
            // Arrange
            EvalSuiteEntity entity = EvalSuiteEntity.builder()
                    .id("suite-2")
                    .name("Suite 2")
                    .description("Another suite")
                    .factSheetId(2L)
                    .enabled(true)
                    .requiredPassRate(0.8)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
            entity.setTagsJson(objectMapper.writeValueAsString(List.of("performance")));

            // Act
            EvalSuite model = converter.toModel(entity, false);

            // Assert
            assertNotNull(model);
            assertEquals("suite-2", model.getId());
            assertEquals("Suite 2", model.getName());
            assertEquals(0.8, model.getRequiredPassRate());
            assertTrue(model.getTags().contains("performance"));
        }

        @Test
        @DisplayName("Should handle null EvalSuite model")
        void shouldHandleNullSuiteModel() {
            assertNull(converter.toEntity((EvalSuite) null));
        }

        @Test
        @DisplayName("Should handle null EvalSuiteEntity")
        void shouldHandleNullSuiteEntity() {
            assertNull(converter.toModel((EvalSuiteEntity) null));
        }
    }

    // ========================================
    // EvalTestResult Conversion Tests
    // ========================================

    @Nested
    @DisplayName("EvalTestResult Conversions")
    class EvalTestResultConversions {

        @Test
        @DisplayName("Should convert EvalTestResult model to entity")
        void shouldConvertModelToEntity() {
            // Arrange
            EvalTestResult model = EvalTestResult.builder()
                    .id("result-1")
                    .testCaseId("case-1")
                    .testCaseName("Test Case 1")
                    .suiteId("suite-1")
                    .factSheetId(1L)
                    .passed(true)
                    .score(0.95)
                    .query("Test query")
                    .expectedAnswer("Expected")
                    .actualAnswer("Actual answer")
                    .retrievedDocuments(List.of("doc1", "doc2"))
                    .toolCalls(List.of("tool1"))
                    .failureReasons(new ArrayList<>())
                    .scores(Map.of(EvaluationType.RELEVANCY, 0.95))
                    .passedByType(Map.of(EvaluationType.RELEVANCY, true))
                    .stepsExecuted(3)
                    .executionTimeMs(1500L)
                    .totalTokens(500L)
                    .startedAt(Instant.now().minusSeconds(2))
                    .completedAt(Instant.now())
                    .build();

            // Act
            EvalTestResultEntity entity = converter.toEntity(model);

            // Assert
            assertNotNull(entity);
            assertEquals("result-1", entity.getId());
            assertEquals("case-1", entity.getTestCaseId());
            assertTrue(entity.getPassed());
            assertEquals(0.95, entity.getScore());
            assertEquals("Actual answer", entity.getActualAnswer());
            assertEquals(3, entity.getStepsExecuted());
            assertEquals(1500L, entity.getExecutionTimeMs());
            assertNotNull(entity.getRetrievedDocumentsJson());
            assertNotNull(entity.getScoresJson());
        }

        @Test
        @DisplayName("Should convert EvalTestResultEntity to model")
        void shouldConvertEntityToModel() throws Exception {
            // Arrange
            EvalTestResultEntity entity = EvalTestResultEntity.builder()
                    .id("result-2")
                    .testCaseId("case-2")
                    .testCaseName("Test Case 2")
                    .passed(false)
                    .score(0.45)
                    .query("Query")
                    .actualAnswer("Wrong answer")
                    .stepsExecuted(2)
                    .executionTimeMs(2000L)
                    .totalTokens(300L)
                    .startedAt(Instant.now().minusSeconds(3))
                    .completedAt(Instant.now())
                    .build();

            entity.setRetrievedDocumentsJson(objectMapper.writeValueAsString(List.of("doc")));
            entity.setFailureReasonsJson(objectMapper.writeValueAsString(List.of("Low score")));
            entity.setScoresJson(objectMapper.writeValueAsString(Map.of("RELEVANCY", 0.45)));
            entity.setPassedByTypeJson(objectMapper.writeValueAsString(Map.of("RELEVANCY", false)));

            // Act
            EvalTestResult model = converter.toModel(entity);

            // Assert
            assertNotNull(model);
            assertEquals("result-2", model.getId());
            assertFalse(model.isPassed());
            assertEquals(0.45, model.getScore());
            assertEquals(1, model.getFailureReasons().size());
            assertEquals("Low score", model.getFailureReasons().get(0));
            assertEquals(0.45, model.getScores().get(EvaluationType.RELEVANCY));
            assertFalse(model.getPassedByType().get(EvaluationType.RELEVANCY));
        }

        @Test
        @DisplayName("Should handle null EvalTestResult model")
        void shouldHandleNullTestResultModel() {
            assertNull(converter.toEntity((EvalTestResult) null));
        }

        @Test
        @DisplayName("Should handle null EvalTestResultEntity")
        void shouldHandleNullTestResultEntity() {
            assertNull(converter.toModel((EvalTestResultEntity) null));
        }
    }

    // ========================================
    // EvalSuiteResult Conversion Tests
    // ========================================

    @Nested
    @DisplayName("EvalSuiteResult Conversions")
    class EvalSuiteResultConversions {

        @Test
        @DisplayName("Should convert EvalSuiteResult model to entity")
        void shouldConvertModelToEntity() {
            // Arrange
            EvalSuiteResult model = EvalSuiteResult.builder()
                    .id("suite-result-1")
                    .suiteId("suite-1")
                    .suiteName("Test Suite")
                    .factSheetId(1L)
                    .passed(true)
                    .passRate(0.9)
                    .averageScore(0.85)
                    .passedCount(9)
                    .failedCount(1)
                    .skippedCount(0)
                    .totalCount(10)
                    .averageScoresByType(Map.of(EvaluationType.RELEVANCY, 0.88))
                    .passRatesByType(Map.of(EvaluationType.RELEVANCY, 0.9))
                    .failedTests(Map.of("suite-1", List.of("case-3")))
                    .executionTimeMs(15000L)
                    .totalTokens(5000L)
                    .startedAt(Instant.now().minusSeconds(15))
                    .completedAt(Instant.now())
                    .build();

            // Act
            EvalSuiteResultEntity entity = converter.toEntity(model);

            // Assert
            assertNotNull(entity);
            assertEquals("suite-result-1", entity.getId());
            assertEquals("suite-1", entity.getSuiteId());
            assertTrue(entity.getPassed());
            assertEquals(0.9, entity.getPassRate());
            assertEquals(0.85, entity.getAverageScore());
            assertEquals(9, entity.getPassedCount());
            assertEquals(1, entity.getFailedCount());
            assertNotNull(entity.getAverageScoresByTypeJson());
            assertNotNull(entity.getFailedTestsJson());
        }

        @Test
        @DisplayName("Should convert EvalSuiteResultEntity to model")
        void shouldConvertEntityToModel() throws Exception {
            // Arrange
            EvalSuiteResultEntity entity = EvalSuiteResultEntity.builder()
                    .id("suite-result-2")
                    .suiteId("suite-2")
                    .suiteName("Another Suite")
                    .passed(false)
                    .passRate(0.6)
                    .averageScore(0.55)
                    .passedCount(6)
                    .failedCount(4)
                    .skippedCount(0)
                    .totalCount(10)
                    .executionTimeMs(20000L)
                    .totalTokens(8000L)
                    .startedAt(Instant.now().minusSeconds(20))
                    .completedAt(Instant.now())
                    .build();

            entity.setAverageScoresByTypeJson(objectMapper.writeValueAsString(Map.of("FAITHFULNESS", 0.55)));
            entity.setPassRatesByTypeJson(objectMapper.writeValueAsString(Map.of("FAITHFULNESS", 0.6)));
            entity.setFailedTestsJson(objectMapper.writeValueAsString(Map.of("suite-2", List.of("c1", "c2"))));

            // Act
            EvalSuiteResult model = converter.toModel(entity, false);

            // Assert
            assertNotNull(model);
            assertEquals("suite-result-2", model.getId());
            assertFalse(model.isPassed());
            assertEquals(0.6, model.getPassRate());
            assertEquals(6, model.getPassedCount());
            assertEquals(4, model.getFailedCount());
            assertEquals(0.55, model.getAverageScoresByType().get(EvaluationType.FAITHFULNESS));
            assertTrue(model.getFailedTests().containsKey("suite-2"));
        }

        @Test
        @DisplayName("Should handle null EvalSuiteResult model")
        void shouldHandleNullSuiteResultModel() {
            assertNull(converter.toEntity((EvalSuiteResult) null));
        }

        @Test
        @DisplayName("Should handle null EvalSuiteResultEntity")
        void shouldHandleNullSuiteResultEntity() {
            assertNull(converter.toModel((EvalSuiteResultEntity) null));
        }
    }

    // ========================================
    // Edge Cases and Error Handling
    // ========================================

    @Nested
    @DisplayName("Edge Cases and Error Handling")
    class EdgeCasesAndErrorHandling {

        @Test
        @DisplayName("Should handle empty lists in EvalCase")
        void shouldHandleEmptyListsInEvalCase() {
            // Arrange
            EvalCase model = EvalCase.builder()
                    .id("empty-lists")
                    .name("Empty Lists Test")
                    .query("query")
                    .expectedFacts(List.of())
                    .forbiddenFacts(List.of())
                    .expectedEntities(List.of())
                    .tags(List.of())
                    .evaluationTypes(List.of())
                    .build();

            // Act
            EvalCaseEntity entity = converter.toEntity(model);
            EvalCase roundtripped = converter.toModel(entity);

            // Assert
            assertNotNull(roundtripped);
            assertTrue(roundtripped.getExpectedFacts().isEmpty());
            assertTrue(roundtripped.getTags().isEmpty());
        }

        @Test
        @DisplayName("Should handle null JSON fields gracefully")
        void shouldHandleNullJsonFieldsGracefully() {
            // Arrange
            EvalCaseEntity entity = EvalCaseEntity.builder()
                    .id("null-json")
                    .name("Null JSON Test")
                    .query("query")
                    .priority(3)
                    .enabled(true)
                    .timeoutMs(30000L)
                    .build();
            // JSON fields are null

            // Act
            EvalCase model = converter.toModel(entity);

            // Assert
            assertNotNull(model);
            assertNotNull(model.getExpectedFacts());
            assertTrue(model.getExpectedFacts().isEmpty());
            assertNotNull(model.getTags());
            assertTrue(model.getTags().isEmpty());
        }

        @Test
        @DisplayName("Should handle invalid evaluation type names gracefully")
        void shouldHandleInvalidEvaluationTypeNamesGracefully() throws Exception {
            // Arrange
            EvalCaseEntity entity = EvalCaseEntity.builder()
                    .id("invalid-type")
                    .name("Invalid Type Test")
                    .query("query")
                    .priority(3)
                    .enabled(true)
                    .timeoutMs(30000L)
                    .build();
            entity.setEvaluationTypesJson(objectMapper.writeValueAsString(
                    List.of("RELEVANCY", "INVALID_TYPE", "FAITHFULNESS")));

            // Act
            EvalCase model = converter.toModel(entity);

            // Assert
            assertNotNull(model);
            assertNotNull(model.getEvaluationTypes());
            assertEquals(2, model.getEvaluationTypes().size()); // Invalid type filtered out
            assertTrue(model.getEvaluationTypes().contains(EvaluationType.RELEVANCY));
            assertTrue(model.getEvaluationTypes().contains(EvaluationType.FAITHFULNESS));
        }

        @Test
        @DisplayName("Should handle special characters in strings")
        void shouldHandleSpecialCharactersInStrings() {
            // Arrange
            EvalCase model = EvalCase.builder()
                    .id("special-chars")
                    .name("Test with \"quotes\" and 'apostrophes'")
                    .query("What about special chars: <>&\"'?")
                    .expectedAnswer("Answer with\nnewline\tand\ttabs")
                    .expectedFacts(List.of("Fact with \"quotes\"", "Fact with <html>"))
                    .build();

            // Act
            EvalCaseEntity entity = converter.toEntity(model);
            EvalCase roundtripped = converter.toModel(entity);

            // Assert
            assertEquals(model.getName(), roundtripped.getName());
            assertEquals(model.getQuery(), roundtripped.getQuery());
            assertEquals(model.getExpectedAnswer(), roundtripped.getExpectedAnswer());
            assertEquals(model.getExpectedFacts(), roundtripped.getExpectedFacts());
        }

        @Test
        @DisplayName("Should handle Unicode characters")
        void shouldHandleUnicodeCharacters() {
            // Arrange
            EvalCase model = EvalCase.builder()
                    .id("unicode-test")
                    .name("Test with unicode: \u4e2d\u6587 \u65e5\u672c\u8a9e")
                    .query("Question with emoji: \ud83d\ude00")
                    .expectedFacts(List.of("Fact with symbols: \u2713 \u2714 \u2715"))
                    .build();

            // Act
            EvalCaseEntity entity = converter.toEntity(model);
            EvalCase roundtripped = converter.toModel(entity);

            // Assert
            assertEquals(model.getName(), roundtripped.getName());
            assertEquals(model.getQuery(), roundtripped.getQuery());
            assertEquals(model.getExpectedFacts(), roundtripped.getExpectedFacts());
        }
    }
}
