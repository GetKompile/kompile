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

import ai.kompile.app.eval.service.EvalSetService;
import ai.kompile.core.evaluation.EvaluationType;
import ai.kompile.react.eval.model.EvalCase;
import ai.kompile.react.eval.model.EvalSuite;
import ai.kompile.react.eval.model.EvalSuiteResult;
import ai.kompile.react.eval.model.EvalTestResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for ManagedEvalController.
 * Uses standalone MockMvc setup to avoid Spring context loading issues.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ManagedEvalController Tests")
class ManagedEvalControllerTest {

    private MockMvc mockMvc;

    private ObjectMapper objectMapper;

    @Mock
    private EvalSetService evalSetService;

    @InjectMocks
    private ManagedEvalController managedEvalController;

    private EvalSuite testSuite;
    private EvalCase testCase;
    private EvalTestResult testResult;
    private EvalSuiteResult suiteResult;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setObjectMapper(objectMapper);

        mockMvc = MockMvcBuilders.standaloneSetup(managedEvalController)
                .setMessageConverters(converter)
                .build();

        testSuite = EvalSuite.builder()
                .id("suite-123")
                .name("Test Suite")
                .description("A test suite for testing")
                .factSheetId(1L)
                .enabled(true)
                .requiredPassRate(0.8)
                .tags(List.of("tag1", "tag2"))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        testCase = EvalCase.builder()
                .id("case-456")
                .name("Test Case")
                .description("A test case")
                .factSheetId(1L)
                .query("What is the answer?")
                .expectedAnswer("42")
                .expectedFacts(List.of("fact1", "fact2"))
                .forbiddenFacts(List.of("forbidden1"))
                .evaluationTypes(List.of(EvaluationType.RELEVANCY, EvaluationType.ANSWER_CORRECTNESS))
                .thresholds(Map.of(EvaluationType.RELEVANCY, 0.7))
                .tags(List.of("unit-test"))
                .priority(3)
                .enabled(true)
                .timeoutMs(30000L)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        testResult = EvalTestResult.builder()
                .id("result-789")
                .testCaseId("case-456")
                .testCaseName("Test Case")
                .suiteId("suite-123")
                .factSheetId(1L)
                .passed(true)
                .score(0.95)
                .query("What is the answer?")
                .expectedAnswer("42")
                .actualAnswer("42")
                .retrievedDocuments(List.of("doc1", "doc2"))
                .toolCalls(List.of())
                .stepsExecuted(2)
                .scores(Map.of(EvaluationType.RELEVANCY, 0.95))
                .passedByType(Map.of(EvaluationType.RELEVANCY, true))
                .executionTimeMs(1500L)
                .totalTokens(500L)
                .startedAt(Instant.now().minusSeconds(2))
                .completedAt(Instant.now())
                .build();

        suiteResult = EvalSuiteResult.builder()
                .id("suite-result-101")
                .suiteId("suite-123")
                .suiteName("Test Suite")
                .factSheetId(1L)
                .passed(true)
                .passRate(0.9)
                .averageScore(0.85)
                .passedCount(9)
                .failedCount(1)
                .skippedCount(0)
                .totalCount(10)
                .executionTimeMs(15000L)
                .totalTokens(5000L)
                .startedAt(Instant.now().minusSeconds(20))
                .completedAt(Instant.now())
                .build();
    }

    // ========================================
    // Suite Operations Tests
    // ========================================

    @Nested
    @DisplayName("Suite Operations")
    class SuiteOperations {

        @Test
        @DisplayName("GET /api/eval-sets - should return all suites")
        void getAllSuites() throws Exception {
            when(evalSetService.getSuitesForActiveFactSheet()).thenReturn(List.of(testSuite));

            mockMvc.perform(get("/api/eval-sets"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].id", is("suite-123")))
                    .andExpect(jsonPath("$[0].name", is("Test Suite")))
                    .andExpect(jsonPath("$[0].factSheetId", is(1)))
                    .andExpect(jsonPath("$[0].enabled", is(true)));
        }

        @Test
        @DisplayName("GET /api/eval-sets/fact-sheet/{id} - should return suites for fact sheet")
        void getSuitesForFactSheet() throws Exception {
            when(evalSetService.getSuitesForFactSheet(1L)).thenReturn(List.of(testSuite));

            mockMvc.perform(get("/api/eval-sets/fact-sheet/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].id", is("suite-123")));
        }

        @Test
        @DisplayName("GET /api/eval-sets/{suiteId} - should return suite by ID")
        void getSuiteById() throws Exception {
            when(evalSetService.getSuiteById("suite-123")).thenReturn(Optional.of(testSuite));

            mockMvc.perform(get("/api/eval-sets/suite-123"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id", is("suite-123")))
                    .andExpect(jsonPath("$.name", is("Test Suite")))
                    .andExpect(jsonPath("$.description", is("A test suite for testing")));
        }

        @Test
        @DisplayName("GET /api/eval-sets/{suiteId} - should return 404 when suite not found")
        void getSuiteById_notFound() throws Exception {
            when(evalSetService.getSuiteById("nonexistent")).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/eval-sets/nonexistent"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("POST /api/eval-sets - should create suite")
        void createSuite() throws Exception {
            when(evalSetService.createSuiteForFactSheet(eq(1L), eq("New Suite"), eq("Description")))
                    .thenReturn(testSuite);

            String requestJson = """
                {
                    "factSheetId": 1,
                    "name": "New Suite",
                    "description": "Description"
                }
                """;

            mockMvc.perform(post("/api/eval-sets")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id", is("suite-123")));
        }

        @Test
        @DisplayName("PUT /api/eval-sets/{suiteId} - should update suite")
        void updateSuite() throws Exception {
            when(evalSetService.getSuiteById("suite-123")).thenReturn(Optional.of(testSuite));
            doNothing().when(evalSetService).updateSuite(any(EvalSuite.class));

            String requestJson = """
                {
                    "name": "Updated Suite",
                    "description": "Updated description",
                    "enabled": false,
                    "requiredPassRate": 0.9,
                    "tags": ["new-tag"]
                }
                """;

            mockMvc.perform(put("/api/eval-sets/suite-123")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isOk());

            verify(evalSetService).updateSuite(any(EvalSuite.class));
        }

        @Test
        @DisplayName("PUT /api/eval-sets/{suiteId} - should return 404 when suite not found")
        void updateSuite_notFound() throws Exception {
            when(evalSetService.getSuiteById("nonexistent")).thenReturn(Optional.empty());

            String requestJson = """
                {
                    "name": "Updated Suite"
                }
                """;

            mockMvc.perform(put("/api/eval-sets/nonexistent")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("DELETE /api/eval-sets/{suiteId} - should delete suite")
        void deleteSuite() throws Exception {
            when(evalSetService.deleteSuite("suite-123")).thenReturn(true);

            mockMvc.perform(delete("/api/eval-sets/suite-123"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("DELETE /api/eval-sets/{suiteId} - should return 404 when suite not found")
        void deleteSuite_notFound() throws Exception {
            when(evalSetService.deleteSuite("nonexistent")).thenReturn(false);

            mockMvc.perform(delete("/api/eval-sets/nonexistent"))
                    .andExpect(status().isNotFound());
        }
    }

    // ========================================
    // Test Case Operations Tests
    // ========================================

    @Nested
    @DisplayName("Test Case Operations")
    class TestCaseOperations {

        @Test
        @DisplayName("GET /api/eval-sets/{suiteId}/cases - should return test cases in suite")
        void getTestCasesInSuite() throws Exception {
            when(evalSetService.getTestCasesInSuite("suite-123")).thenReturn(List.of(testCase));

            mockMvc.perform(get("/api/eval-sets/suite-123/cases"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].id", is("case-456")))
                    .andExpect(jsonPath("$[0].name", is("Test Case")))
                    .andExpect(jsonPath("$[0].query", is("What is the answer?")));
        }

        @Test
        @DisplayName("GET /api/eval-sets/cases/{caseId} - should return test case by ID")
        void getTestCaseById() throws Exception {
            when(evalSetService.getTestCaseById("case-456")).thenReturn(Optional.of(testCase));

            mockMvc.perform(get("/api/eval-sets/cases/case-456"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id", is("case-456")))
                    .andExpect(jsonPath("$.name", is("Test Case")))
                    .andExpect(jsonPath("$.expectedAnswer", is("42")));
        }

        @Test
        @DisplayName("GET /api/eval-sets/cases/{caseId} - should return 404 when test case not found")
        void getTestCaseById_notFound() throws Exception {
            when(evalSetService.getTestCaseById("nonexistent")).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/eval-sets/cases/nonexistent"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("POST /api/eval-sets/{suiteId}/cases - should create test case")
        void createTestCase() throws Exception {
            when(evalSetService.createTestCaseInSuite(eq("suite-123"), any(EvalCase.class)))
                    .thenReturn(testCase);

            String requestJson = """
                {
                    "name": "New Test Case",
                    "description": "Description",
                    "factSheetId": 1,
                    "query": "What is the answer?",
                    "expectedAnswer": "42",
                    "expectedFacts": ["fact1"],
                    "forbiddenFacts": [],
                    "evaluationTypes": ["RELEVANCY"],
                    "priority": 3,
                    "enabled": true,
                    "timeoutMs": 30000
                }
                """;

            mockMvc.perform(post("/api/eval-sets/suite-123/cases")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id", is("case-456")));
        }

        @Test
        @DisplayName("PUT /api/eval-sets/cases/{caseId} - should update test case")
        void updateTestCase() throws Exception {
            when(evalSetService.getTestCaseById("case-456")).thenReturn(Optional.of(testCase));
            doNothing().when(evalSetService).updateTestCase(any(EvalCase.class));

            String requestJson = """
                {
                    "name": "Updated Test Case",
                    "query": "Updated query?",
                    "expectedAnswer": "Updated answer"
                }
                """;

            mockMvc.perform(put("/api/eval-sets/cases/case-456")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isOk());

            verify(evalSetService).updateTestCase(any(EvalCase.class));
        }

        @Test
        @DisplayName("DELETE /api/eval-sets/cases/{caseId} - should delete test case")
        void deleteTestCase() throws Exception {
            when(evalSetService.deleteTestCase("case-456")).thenReturn(true);

            mockMvc.perform(delete("/api/eval-sets/cases/case-456"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("DELETE /api/eval-sets/cases/{caseId} - should return 404 when test case not found")
        void deleteTestCase_notFound() throws Exception {
            when(evalSetService.deleteTestCase("nonexistent")).thenReturn(false);

            mockMvc.perform(delete("/api/eval-sets/cases/nonexistent"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("POST /api/eval-sets/cases/{caseId}/move - should move test case to different suite")
        void moveTestCase() throws Exception {
            doNothing().when(evalSetService).moveTestCaseToSuite("case-456", "target-suite");

            String requestJson = """
                {
                    "targetSuiteId": "target-suite"
                }
                """;

            mockMvc.perform(post("/api/eval-sets/cases/case-456/move")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isOk());

            verify(evalSetService).moveTestCaseToSuite("case-456", "target-suite");
        }
    }

    // ========================================
    // Results Operations Tests
    // ========================================

    @Nested
    @DisplayName("Results Operations")
    class ResultsOperations {

        @Test
        @DisplayName("GET /api/eval-sets/{suiteId}/results - should return suite result history")
        void getSuiteResultHistory() throws Exception {
            when(evalSetService.getSuiteResultHistory("suite-123", 10)).thenReturn(List.of(suiteResult));

            mockMvc.perform(get("/api/eval-sets/suite-123/results"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].id", is("suite-result-101")))
                    .andExpect(jsonPath("$[0].passRate", is(0.9)));
        }

        @Test
        @DisplayName("GET /api/eval-sets/{suiteId}/results with limit - should respect limit parameter")
        void getSuiteResultHistory_withLimit() throws Exception {
            when(evalSetService.getSuiteResultHistory("suite-123", 5)).thenReturn(List.of(suiteResult));

            mockMvc.perform(get("/api/eval-sets/suite-123/results")
                            .param("limit", "5"))
                    .andExpect(status().isOk());

            verify(evalSetService).getSuiteResultHistory("suite-123", 5);
        }

        @Test
        @DisplayName("GET /api/eval-sets/cases/{caseId}/results - should return test case result history")
        void getTestCaseResultHistory() throws Exception {
            when(evalSetService.getTestCaseResultHistory("case-456", 10)).thenReturn(List.of(testResult));

            mockMvc.perform(get("/api/eval-sets/cases/case-456/results"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].id", is("result-789")))
                    .andExpect(jsonPath("$[0].passed", is(true)))
                    .andExpect(jsonPath("$[0].score", is(0.95)));
        }

        @Test
        @DisplayName("GET /api/eval-sets/{suiteId}/results/latest - should return latest suite result")
        void getLatestSuiteResult() throws Exception {
            when(evalSetService.getLatestSuiteResult("suite-123")).thenReturn(Optional.of(suiteResult));

            mockMvc.perform(get("/api/eval-sets/suite-123/results/latest"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id", is("suite-result-101")))
                    .andExpect(jsonPath("$.passed", is(true)));
        }

        @Test
        @DisplayName("GET /api/eval-sets/{suiteId}/results/latest - should return 404 when no results")
        void getLatestSuiteResult_notFound() throws Exception {
            when(evalSetService.getLatestSuiteResult("suite-123")).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/eval-sets/suite-123/results/latest"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("GET /api/eval-sets/cases/{caseId}/results/latest - should return latest test case result")
        void getLatestTestCaseResult() throws Exception {
            when(evalSetService.getLatestTestCaseResult("case-456")).thenReturn(Optional.of(testResult));

            mockMvc.perform(get("/api/eval-sets/cases/case-456/results/latest"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id", is("result-789")))
                    .andExpect(jsonPath("$.actualAnswer", is("42")));
        }
    }

    // ========================================
    // Metrics Operations Tests
    // ========================================

    @Nested
    @DisplayName("Metrics Operations")
    class MetricsOperations {

        @Test
        @DisplayName("GET /api/eval-sets/fact-sheet/{factSheetId}/metrics - should return metrics")
        void getFactSheetMetrics() throws Exception {
            Map<String, Double> metrics = Map.of(
                    "totalSuites", 3.0,
                    "totalTestCases", 25.0,
                    "overallPassRate", 0.85,
                    "averageScore", 0.78
            );
            when(evalSetService.getFactSheetMetrics(1L)).thenReturn(metrics);

            mockMvc.perform(get("/api/eval-sets/fact-sheet/1/metrics"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalSuites", is(3.0)))
                    .andExpect(jsonPath("$.overallPassRate", is(0.85)));
        }

        @Test
        @DisplayName("GET /api/eval-sets/fact-sheet/{factSheetId}/failing - should return failing test cases")
        void getFailingTestCases() throws Exception {
            when(evalSetService.getFailingTestCases(1L)).thenReturn(List.of(testCase));

            mockMvc.perform(get("/api/eval-sets/fact-sheet/1/failing"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].id", is("case-456")));
        }

        @Test
        @DisplayName("GET /api/eval-sets/cases/{caseId}/pass-rate - should return pass rate and trend")
        void getTestCasePassRate() throws Exception {
            when(evalSetService.getTestCasePassRate("case-456", 30)).thenReturn(0.85);
            when(evalSetService.getTestCaseScoreTrend("case-456", 30)).thenReturn(0.05);

            mockMvc.perform(get("/api/eval-sets/cases/case-456/pass-rate"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.passRate", is(0.85)))
                    .andExpect(jsonPath("$.trend", is(0.05)))
                    .andExpect(jsonPath("$.windowDays", is(30)));
        }

        @Test
        @DisplayName("GET /api/eval-sets/cases/{caseId}/pass-rate with window - should respect window parameter")
        void getTestCasePassRate_withWindow() throws Exception {
            when(evalSetService.getTestCasePassRate("case-456", 7)).thenReturn(0.9);
            when(evalSetService.getTestCaseScoreTrend("case-456", 7)).thenReturn(-0.02);

            mockMvc.perform(get("/api/eval-sets/cases/case-456/pass-rate")
                            .param("windowDays", "7"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.passRate", is(0.9)))
                    .andExpect(jsonPath("$.windowDays", is(7)));
        }
    }

    // ========================================
    // Import/Export Operations Tests
    // ========================================

    @Nested
    @DisplayName("Import/Export Operations")
    class ImportExportOperations {

        @Test
        @DisplayName("GET /api/eval-sets/{suiteId}/export - should export suite")
        void exportSuite() throws Exception {
            EvalSuite exportedSuite = EvalSuite.builder()
                    .id("suite-123")
                    .name("Test Suite")
                    .testCases(List.of(testCase))
                    .build();
            when(evalSetService.exportSuite("suite-123")).thenReturn(exportedSuite);

            mockMvc.perform(get("/api/eval-sets/suite-123/export"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id", is("suite-123")))
                    .andExpect(jsonPath("$.testCases", hasSize(1)));
        }

        @Test
        @DisplayName("GET /api/eval-sets/{suiteId}/export - should return 404 when suite not found")
        void exportSuite_notFound() throws Exception {
            when(evalSetService.exportSuite("nonexistent")).thenReturn(null);

            mockMvc.perform(get("/api/eval-sets/nonexistent/export"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("POST /api/eval-sets/import - should import suite")
        void importSuite() throws Exception {
            EvalSuite importedSuite = EvalSuite.builder()
                    .id("suite-123")
                    .name("Imported Suite")
                    .build();

            when(evalSetService.importSuite(any(EvalSuite.class), eq(2L))).thenReturn(importedSuite);

            String requestJson = """
                {
                    "suite": {
                        "id": "old-id",
                        "name": "Suite to Import",
                        "testCases": []
                    },
                    "targetFactSheetId": 2
                }
                """;

            mockMvc.perform(post("/api/eval-sets/import")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id", is("suite-123")));
        }
    }

    // ========================================
    // DTO Conversion Tests
    // ========================================

    @Nested
    @DisplayName("DTO Conversion Tests")
    class DtoConversionTests {

        @Test
        @DisplayName("Should properly convert evaluation types to strings in response")
        void shouldConvertEvaluationTypesToStrings() throws Exception {
            when(evalSetService.getTestCaseById("case-456")).thenReturn(Optional.of(testCase));

            mockMvc.perform(get("/api/eval-sets/cases/case-456"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.evaluationTypes", hasItem("RELEVANCY")))
                    .andExpect(jsonPath("$.evaluationTypes", hasItem("ANSWER_CORRECTNESS")));
        }

        @Test
        @DisplayName("Should properly convert thresholds with string keys in response")
        void shouldConvertThresholdsWithStringKeys() throws Exception {
            when(evalSetService.getTestCaseById("case-456")).thenReturn(Optional.of(testCase));

            mockMvc.perform(get("/api/eval-sets/cases/case-456"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.thresholds.RELEVANCY", is(0.7)));
        }

        @Test
        @DisplayName("Should include all fields in suite DTO")
        void shouldIncludeAllFieldsInSuiteDto() throws Exception {
            when(evalSetService.getSuiteById("suite-123")).thenReturn(Optional.of(testSuite));

            mockMvc.perform(get("/api/eval-sets/suite-123"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").exists())
                    .andExpect(jsonPath("$.name").exists())
                    .andExpect(jsonPath("$.description").exists())
                    .andExpect(jsonPath("$.factSheetId").exists())
                    .andExpect(jsonPath("$.enabled").exists())
                    .andExpect(jsonPath("$.requiredPassRate").exists())
                    .andExpect(jsonPath("$.testCaseCount").exists())
                    .andExpect(jsonPath("$.tags").exists())
                    .andExpect(jsonPath("$.createdAt").exists())
                    .andExpect(jsonPath("$.updatedAt").exists());
        }

        @Test
        @DisplayName("Should include all fields in test result DTO")
        void shouldIncludeAllFieldsInTestResultDto() throws Exception {
            when(evalSetService.getTestCaseResultHistory("case-456", 10)).thenReturn(List.of(testResult));

            mockMvc.perform(get("/api/eval-sets/cases/case-456/results"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").exists())
                    .andExpect(jsonPath("$[0].testCaseId").exists())
                    .andExpect(jsonPath("$[0].passed").exists())
                    .andExpect(jsonPath("$[0].score").exists())
                    .andExpect(jsonPath("$[0].query").exists())
                    .andExpect(jsonPath("$[0].actualAnswer").exists())
                    .andExpect(jsonPath("$[0].retrievedDocuments").exists())
                    .andExpect(jsonPath("$[0].executionTimeMs").exists());
        }
    }

    // ========================================
    // Error Handling Tests
    // ========================================

    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {

        @Test
        @DisplayName("POST /api/eval-sets - should handle service exception")
        void createSuite_shouldHandleServiceException() throws Exception {
            when(evalSetService.createSuiteForFactSheet(anyLong(), anyString(), anyString()))
                    .thenThrow(new RuntimeException("Database error"));

            String requestJson = """
                {
                    "factSheetId": 1,
                    "name": "New Suite",
                    "description": "Description"
                }
                """;

            mockMvc.perform(post("/api/eval-sets")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("PUT /api/eval-sets/{suiteId} - should handle update exception")
        void updateSuite_shouldHandleException() throws Exception {
            when(evalSetService.getSuiteById("suite-123")).thenReturn(Optional.of(testSuite));
            doThrow(new RuntimeException("Update failed")).when(evalSetService).updateSuite(any(EvalSuite.class));

            String requestJson = """
                {
                    "name": "Updated Suite"
                }
                """;

            mockMvc.perform(put("/api/eval-sets/suite-123")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("POST /api/eval-sets/{suiteId}/cases - should handle creation exception")
        void createTestCase_shouldHandleException() throws Exception {
            when(evalSetService.createTestCaseInSuite(anyString(), any(EvalCase.class)))
                    .thenThrow(new IllegalArgumentException("Suite not found"));

            String requestJson = """
                {
                    "name": "New Test Case",
                    "query": "Query?"
                }
                """;

            mockMvc.perform(post("/api/eval-sets/nonexistent/cases")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("POST /api/eval-sets/cases/{caseId}/move - should handle move exception")
        void moveTestCase_shouldHandleException() throws Exception {
            doThrow(new RuntimeException("Move failed")).when(evalSetService)
                    .moveTestCaseToSuite(anyString(), anyString());

            String requestJson = """
                {
                    "targetSuiteId": "target-suite"
                }
                """;

            mockMvc.perform(post("/api/eval-sets/cases/case-456/move")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("POST /api/eval-sets/import - should handle import exception")
        void importSuite_shouldHandleException() throws Exception {
            when(evalSetService.importSuite(any(EvalSuite.class), anyLong()))
                    .thenThrow(new RuntimeException("Import failed"));

            String requestJson = """
                {
                    "suite": {
                        "name": "Suite to Import"
                    },
                    "targetFactSheetId": 2
                }
                """;

            mockMvc.perform(post("/api/eval-sets/import")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestJson))
                    .andExpect(status().isBadRequest());
        }
    }
}
