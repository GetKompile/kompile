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
import ai.kompile.app.eval.domain.EvalTestResultEntity;
import ai.kompile.app.eval.repository.EvalCaseRepository;
import ai.kompile.app.eval.repository.EvalSuiteRepository;
import ai.kompile.app.eval.repository.EvalSuiteResultRepository;
import ai.kompile.app.eval.repository.EvalTestResultRepository;
import ai.kompile.react.eval.model.EvalCase;
import ai.kompile.react.eval.model.EvalSuite;
import ai.kompile.react.eval.model.EvalTestResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for JpaEvalTracker.
 */
@DisplayName("JpaEvalTracker Tests")
class JpaEvalTrackerTest {

    private EvalCaseRepository caseRepository;
    private EvalSuiteRepository suiteRepository;
    private EvalTestResultRepository testResultRepository;
    private EvalSuiteResultRepository suiteResultRepository;
    private EvalEntityConverter converter;
    private JpaEvalTracker tracker;

    @BeforeEach
    void setUp() {
        // Create mocks explicitly using mockito-core's mock() method
        caseRepository = mock(EvalCaseRepository.class);
        suiteRepository = mock(EvalSuiteRepository.class);
        testResultRepository = mock(EvalTestResultRepository.class);
        suiteResultRepository = mock(EvalSuiteResultRepository.class);

        ObjectMapper objectMapper = new ObjectMapper();
        converter = new EvalEntityConverter(objectMapper);
        tracker = new JpaEvalTracker(
                caseRepository,
                suiteRepository,
                testResultRepository,
                suiteResultRepository,
                converter
        );
    }

    // ========================================
    // Test Case CRUD Tests
    // ========================================

    @Nested
    @DisplayName("Test Case CRUD Operations")
    class TestCaseCrudTests {

        @Test
        @DisplayName("Should register test case")
        void shouldRegisterTestCase() {
            // Arrange
            EvalCase testCase = EvalCase.builder()
                    .id("test-case-1")
                    .name("Test Case 1")
                    .query("What is the answer?")
                    .factSheetId(1L)
                    .build();

            when(caseRepository.save(any(EvalCaseEntity.class))).thenAnswer(invocation -> invocation.getArguments()[0]);

            // Act
            tracker.registerTestCase(testCase);

            // Assert
            verify(caseRepository).save(any(EvalCaseEntity.class));
        }

        @Test
        @DisplayName("Should get test case by ID")
        void shouldGetTestCaseById() {
            // Arrange
            String caseId = "test-case-1";
            EvalCaseEntity entity = createCaseEntity(caseId, "Test Case 1");

            when(caseRepository.findById(caseId)).thenReturn(Optional.of(entity));

            // Act
            Optional<EvalCase> result = tracker.getTestCase(caseId);

            // Assert
            assertTrue(result.isPresent());
            assertEquals("Test Case 1", result.get().getName());
        }

        @Test
        @DisplayName("Should return empty when test case not found")
        void shouldReturnEmptyWhenTestCaseNotFound() {
            // Arrange
            when(caseRepository.findById("nonexistent")).thenReturn(Optional.empty());

            // Act
            Optional<EvalCase> result = tracker.getTestCase("nonexistent");

            // Assert
            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("Should get all test cases")
        void shouldGetAllTestCases() {
            // Arrange
            List<EvalCaseEntity> entities = List.of(
                    createCaseEntity("case-1", "Case 1"),
                    createCaseEntity("case-2", "Case 2")
            );

            // JpaEvalTracker uses findAllByOrderByCreatedAtDesc, not findAll
            when(caseRepository.findAllByOrderByCreatedAtDesc()).thenReturn(entities);

            // Act
            List<EvalCase> result = tracker.getAllTestCases();

            // Assert
            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("Should get test cases for fact sheet")
        void shouldGetTestCasesForFactSheet() {
            // Arrange
            Long factSheetId = 1L;
            List<EvalCaseEntity> entities = List.of(
                    createCaseEntity("case-1", "Case 1"),
                    createCaseEntity("case-2", "Case 2")
            );

            // JpaEvalTracker uses findByFactSheetIdOrderByPriorityDesc, not findByFactSheetId
            when(caseRepository.findByFactSheetIdOrderByPriorityDesc(factSheetId)).thenReturn(entities);

            // Act
            List<EvalCase> result = tracker.getTestCasesForFactSheet(factSheetId);

            // Assert
            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("Should update test case")
        void shouldUpdateTestCase() {
            // Arrange
            EvalCase testCase = EvalCase.builder()
                    .id("test-case-1")
                    .name("Updated Name")
                    .query("Updated query?")
                    .build();

            EvalCaseEntity existingEntity = createCaseEntity("test-case-1", "Old Name");

            when(caseRepository.findById("test-case-1")).thenReturn(Optional.of(existingEntity));
            when(caseRepository.save(any(EvalCaseEntity.class))).thenAnswer(invocation -> invocation.getArguments()[0]);

            // Act
            tracker.updateTestCase(testCase);

            // Assert
            verify(caseRepository).save(any(EvalCaseEntity.class));
        }

        @Test
        @DisplayName("Should delete test case")
        void shouldDeleteTestCase() {
            // Arrange
            String caseId = "test-case-to-delete";
            when(caseRepository.existsById(caseId)).thenReturn(true);

            // Act
            boolean result = tracker.deleteTestCase(caseId);

            // Assert
            assertTrue(result);
            verify(caseRepository).deleteById(caseId);
        }

        @Test
        @DisplayName("Should return false when deleting non-existent test case")
        void shouldReturnFalseWhenDeletingNonExistentTestCase() {
            // Arrange
            String caseId = "nonexistent";
            when(caseRepository.existsById(caseId)).thenReturn(false);

            // Act
            boolean result = tracker.deleteTestCase(caseId);

            // Assert
            assertFalse(result);
        }
    }

    // ========================================
    // Suite CRUD Tests
    // ========================================

    @Nested
    @DisplayName("Suite CRUD Operations")
    class SuiteCrudTests {

        @Test
        @DisplayName("Should register suite")
        void shouldRegisterSuite() {
            // Arrange
            EvalSuite suite = EvalSuite.builder()
                    .id("suite-1")
                    .name("Suite 1")
                    .factSheetId(1L)
                    .build();

            when(suiteRepository.save(any(EvalSuiteEntity.class))).thenAnswer(invocation -> invocation.getArguments()[0]);

            // Act
            tracker.registerSuite(suite);

            // Assert
            verify(suiteRepository).save(any(EvalSuiteEntity.class));
        }

        @Test
        @DisplayName("Should get suite by ID")
        void shouldGetSuiteById() {
            // Arrange
            String suiteId = "suite-1";
            EvalSuiteEntity entity = createSuiteEntity(suiteId, "Suite 1", 1L);

            // JpaEvalTracker uses findByIdWithTestCases, not findById
            when(suiteRepository.findByIdWithTestCases(suiteId)).thenReturn(Optional.of(entity));

            // Act
            Optional<EvalSuite> result = tracker.getSuite(suiteId);

            // Assert
            assertTrue(result.isPresent());
            assertEquals("Suite 1", result.get().getName());
        }

        @Test
        @DisplayName("Should get suites for fact sheet")
        void shouldGetSuitesForFactSheet() {
            // Arrange
            Long factSheetId = 1L;
            List<EvalSuiteEntity> entities = List.of(
                    createSuiteEntity("suite-1", "Suite 1", factSheetId),
                    createSuiteEntity("suite-2", "Suite 2", factSheetId)
            );

            // JpaEvalTracker uses findByFactSheetIdOrderByNameAsc, not findByFactSheetId
            when(suiteRepository.findByFactSheetIdOrderByNameAsc(factSheetId)).thenReturn(entities);

            // Act
            List<EvalSuite> result = tracker.getSuitesForFactSheet(factSheetId);

            // Assert
            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("Should update suite")
        void shouldUpdateSuite() {
            // Arrange
            EvalSuite suite = EvalSuite.builder()
                    .id("suite-1")
                    .name("Updated Suite")
                    .build();

            EvalSuiteEntity existingEntity = createSuiteEntity("suite-1", "Old Name", 1L);

            when(suiteRepository.findById("suite-1")).thenReturn(Optional.of(existingEntity));
            when(suiteRepository.save(any(EvalSuiteEntity.class))).thenAnswer(invocation -> invocation.getArguments()[0]);

            // Act
            tracker.updateSuite(suite);

            // Assert
            verify(suiteRepository).save(any(EvalSuiteEntity.class));
        }

        @Test
        @DisplayName("Should delete suite")
        void shouldDeleteSuite() {
            // Arrange
            String suiteId = "suite-to-delete";
            when(suiteRepository.existsById(suiteId)).thenReturn(true);

            // Act
            boolean result = tracker.deleteSuite(suiteId);

            // Assert
            assertTrue(result);
            verify(suiteRepository).deleteById(suiteId);
        }
    }

    // ========================================
    // Results Tests
    // ========================================

    @Nested
    @DisplayName("Results Operations")
    class ResultsTests {

        @Test
        @DisplayName("Should record test result")
        void shouldRecordTestResult() {
            // Arrange
            EvalTestResult testResult = EvalTestResult.builder()
                    .id("result-1")
                    .testCaseId("case-1")
                    .passed(true)
                    .score(0.95)
                    .query("What is the answer?")
                    .actualAnswer("42")
                    .build();

            when(testResultRepository.save(any(EvalTestResultEntity.class)))
                    .thenAnswer(invocation -> invocation.getArguments()[0]);

            // Act
            tracker.recordTestResult(testResult);

            // Assert
            verify(testResultRepository).save(any(EvalTestResultEntity.class));
        }

        @Test
        @DisplayName("Should get test result history")
        void shouldGetTestResultHistory() {
            // Arrange
            String caseId = "case-1";
            List<EvalTestResultEntity> entities = List.of(
                    createTestResultEntity("result-1", caseId, true, 0.95),
                    createTestResultEntity("result-2", caseId, true, 0.88),
                    createTestResultEntity("result-3", caseId, false, 0.45)
            );

            when(testResultRepository.findByTestCaseIdOrderByCompletedAtDesc(caseId))
                    .thenReturn(entities);

            // Act
            List<EvalTestResult> history = tracker.getTestResultHistory(caseId, 10);

            // Assert
            assertEquals(3, history.size());
            assertTrue(history.get(0).isPassed());
        }

        @Test
        @DisplayName("Should get latest test result")
        void shouldGetLatestTestResult() {
            // Arrange
            String caseId = "case-1";
            EvalTestResultEntity entity = createTestResultEntity("latest-result", caseId, true, 0.92);

            when(testResultRepository.findFirstByTestCaseIdOrderByCompletedAtDesc(caseId))
                    .thenReturn(Optional.of(entity));

            // Act
            Optional<EvalTestResult> result = tracker.getLatestTestResult(caseId);

            // Assert
            assertTrue(result.isPresent());
            assertEquals("latest-result", result.get().getId());
        }
    }

    // ========================================
    // Metrics Tests
    // ========================================

    @Nested
    @DisplayName("Metrics Operations")
    class MetricsTests {

        @Test
        @DisplayName("Should calculate pass rate for test case")
        void shouldCalculatePassRateForTestCase() {
            // Arrange
            String caseId = "case-1";
            when(testResultRepository.countSince(eq(caseId), any(Instant.class)))
                    .thenReturn(10L);
            when(testResultRepository.countPassedSince(eq(caseId), any(Instant.class)))
                    .thenReturn(8L);

            // Act
            double passRate = tracker.getTestCasePassRate(caseId, 30);

            // Assert
            assertEquals(0.8, passRate, 0.001);
        }

        @Test
        @DisplayName("Should return 0 pass rate when no results")
        void shouldReturnZeroPassRateWhenNoResults() {
            // Arrange
            String caseId = "case-no-results";
            when(testResultRepository.countSince(eq(caseId), any(Instant.class)))
                    .thenReturn(0L);

            // Act
            double passRate = tracker.getTestCasePassRate(caseId, 30);

            // Assert
            assertEquals(0.0, passRate);
        }

        @Test
        @DisplayName("Should get failing test cases")
        void shouldGetFailingTestCases() {
            // Arrange
            Long factSheetId = 1L;
            List<String> failingCaseIds = List.of("case-1", "case-2");

            when(testResultRepository.findConsistentlyFailingTestCases(eq(factSheetId), any(Instant.class), eq(3L)))
                    .thenReturn(failingCaseIds);

            EvalCaseEntity case1 = createCaseEntity("case-1", "Failing Case 1");
            EvalCaseEntity case2 = createCaseEntity("case-2", "Failing Case 2");

            when(caseRepository.findById("case-1")).thenReturn(Optional.of(case1));
            when(caseRepository.findById("case-2")).thenReturn(Optional.of(case2));

            // Act
            List<EvalCase> failingCases = tracker.getFailingTestCases(factSheetId);

            // Assert
            assertEquals(2, failingCases.size());
        }
    }

    // ========================================
    // Helper Methods
    // ========================================

    private EvalCaseEntity createCaseEntity(String id, String name) {
        return EvalCaseEntity.builder()
                .id(id)
                .name(name)
                .query("Test query")
                .factSheetId(1L)
                .priority(3)
                .enabled(true)
                .timeoutMs(30000L)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private EvalSuiteEntity createSuiteEntity(String id, String name, Long factSheetId) {
        return EvalSuiteEntity.builder()
                .id(id)
                .name(name)
                .factSheetId(factSheetId)
                .enabled(true)
                .requiredPassRate(0.8)
                .testCases(new ArrayList<>())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private EvalTestResultEntity createTestResultEntity(String id, String testCaseId, boolean passed, double score) {
        return EvalTestResultEntity.builder()
                .id(id)
                .testCaseId(testCaseId)
                .passed(passed)
                .score(score)
                .query("Test query")
                .actualAnswer("Test answer")
                .executionTimeMs(1000L)
                .totalTokens(100L)
                .startedAt(Instant.now().minusSeconds(1))
                .completedAt(Instant.now())
                .build();
    }
}
