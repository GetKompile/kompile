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

package ai.kompile.app.tools;

import ai.kompile.app.eval.service.EvalSetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class EvaluationTool {

    private static final Logger logger = LoggerFactory.getLogger(EvaluationTool.class);

    private final EvalSetService evalSetService;

    @Autowired
    public EvaluationTool(@Autowired(required = false) EvalSetService evalSetService) {
        this.evalSetService = evalSetService;
        logger.info("EvaluationTool initialized");
    }

    public record ListEvalSuitesInput() {}
    public record GetEvalSuiteInput(String suiteId) {}
    public record CreateEvalSuiteInput(Long factSheetId, String name, String description) {}
    public record DeleteEvalSuiteInput(String suiteId) {}
    public record ListEvalTestCasesInput(String suiteId) {}
    public record GetEvalTestCaseInput(String testCaseId) {}
    public record DeleteEvalTestCaseInput(String testCaseId) {}
    public record GetEvalResultsInput(String suiteId, Integer limit) {}
    public record GetLatestEvalResultInput(String suiteId) {}
    public record GetFailingTestCasesInput(Long factSheetId) {}
    public record GetPassRateInput(String testCaseId, Integer windowDays) {}
    public record GetFactSheetMetricsInput(Long factSheetId) {}
    public record ExportEvalSuiteInput(String suiteId) {}

    @Tool(name = "list_eval_suites",
            description = "Lists all evaluation suites for the active fact sheet. Returns suite IDs, names, and descriptions.")
    public Map<String, Object> listEvalSuites(ListEvalSuitesInput input) {
        try {
            if (evalSetService == null) return Map.of("status", "error", "error", "EvalSetService not available");
            var suites = evalSetService.getSuitesForActiveFactSheet();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("count", suites.size());
            result.put("suites", suites.stream().map(s -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", s.getId());
                m.put("name", s.getName());
                m.put("description", s.getDescription());
                return m;
            }).collect(Collectors.toList()));
            return result;
        } catch (Exception e) {
            logger.error("Error listing eval suites: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_eval_suite",
            description = "Gets a specific evaluation suite by its ID with full details.")
    public Map<String, Object> getEvalSuite(GetEvalSuiteInput input) {
        try {
            if (evalSetService == null) return Map.of("status", "error", "error", "EvalSetService not available");
            if (input.suiteId() == null) return Map.of("status", "error", "error", "suiteId is required");
            var suite = evalSetService.getSuiteById(input.suiteId());
            if (suite.isEmpty()) return Map.of("status", "error", "error", "Suite not found: " + input.suiteId());
            var s = suite.get();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("id", s.getId());
            result.put("name", s.getName());
            result.put("description", s.getDescription());
            return result;
        } catch (Exception e) {
            logger.error("Error getting eval suite: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "create_eval_suite",
            description = "Creates a new evaluation suite for a given fact sheet. Provide factSheetId, name, and description.")
    public Map<String, Object> createEvalSuite(CreateEvalSuiteInput input) {
        try {
            if (evalSetService == null) return Map.of("status", "error", "error", "EvalSetService not available");
            if (input.factSheetId() == null) return Map.of("status", "error", "error", "factSheetId is required");
            if (input.name() == null || input.name().isBlank()) return Map.of("status", "error", "error", "name is required");
            var suite = evalSetService.createSuiteForFactSheet(input.factSheetId(), input.name(), input.description());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("id", suite.getId());
            result.put("name", suite.getName());
            result.put("message", "Evaluation suite created");
            return result;
        } catch (Exception e) {
            logger.error("Error creating eval suite: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "delete_eval_suite",
            description = "Deletes an evaluation suite by its ID, including all its test cases and results.")
    public Map<String, Object> deleteEvalSuite(DeleteEvalSuiteInput input) {
        try {
            if (evalSetService == null) return Map.of("status", "error", "error", "EvalSetService not available");
            if (input.suiteId() == null) return Map.of("status", "error", "error", "suiteId is required");
            boolean deleted = evalSetService.deleteSuite(input.suiteId());
            if (!deleted) return Map.of("status", "error", "error", "Suite not found: " + input.suiteId());
            return Map.of("status", "success", "message", "Suite deleted", "suiteId", input.suiteId());
        } catch (Exception e) {
            logger.error("Error deleting eval suite: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "list_eval_test_cases",
            description = "Lists all test cases in an evaluation suite.")
    public Map<String, Object> listEvalTestCases(ListEvalTestCasesInput input) {
        try {
            if (evalSetService == null) return Map.of("status", "error", "error", "EvalSetService not available");
            if (input.suiteId() == null) return Map.of("status", "error", "error", "suiteId is required");
            var cases = evalSetService.getTestCasesInSuite(input.suiteId());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("count", cases.size());
            result.put("testCases", cases.stream().map(c -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", c.getId());
                m.put("query", c.getQuery());
                return m;
            }).collect(Collectors.toList()));
            return result;
        } catch (Exception e) {
            logger.error("Error listing eval test cases: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "delete_eval_test_case",
            description = "Deletes a specific test case by its ID.")
    public Map<String, Object> deleteEvalTestCase(DeleteEvalTestCaseInput input) {
        try {
            if (evalSetService == null) return Map.of("status", "error", "error", "EvalSetService not available");
            if (input.testCaseId() == null) return Map.of("status", "error", "error", "testCaseId is required");
            boolean deleted = evalSetService.deleteTestCase(input.testCaseId());
            if (!deleted) return Map.of("status", "error", "error", "Test case not found: " + input.testCaseId());
            return Map.of("status", "success", "message", "Test case deleted", "testCaseId", input.testCaseId());
        } catch (Exception e) {
            logger.error("Error deleting test case: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_eval_results",
            description = "Gets evaluation result history for a suite. Optional limit parameter (default 10).")
    public Map<String, Object> getEvalResults(GetEvalResultsInput input) {
        try {
            if (evalSetService == null) return Map.of("status", "error", "error", "EvalSetService not available");
            if (input.suiteId() == null) return Map.of("status", "error", "error", "suiteId is required");
            int limit = input.limit() != null && input.limit() > 0 ? input.limit() : 10;
            var results = evalSetService.getSuiteResultHistory(input.suiteId(), limit);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("count", results.size());
            result.put("results", results);
            return result;
        } catch (Exception e) {
            logger.error("Error getting eval results: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_latest_eval_result",
            description = "Gets the most recent evaluation result for a suite.")
    public Map<String, Object> getLatestEvalResult(GetLatestEvalResultInput input) {
        try {
            if (evalSetService == null) return Map.of("status", "error", "error", "EvalSetService not available");
            if (input.suiteId() == null) return Map.of("status", "error", "error", "suiteId is required");
            var latest = evalSetService.getLatestSuiteResult(input.suiteId());
            if (latest.isEmpty()) return Map.of("status", "success", "message", "No results found for suite");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("result", latest.get());
            return result;
        } catch (Exception e) {
            logger.error("Error getting latest eval result: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_failing_test_cases",
            description = "Gets all failing test cases for a fact sheet. Useful for identifying quality issues.")
    public Map<String, Object> getFailingTestCases(GetFailingTestCasesInput input) {
        try {
            if (evalSetService == null) return Map.of("status", "error", "error", "EvalSetService not available");
            if (input.factSheetId() == null) return Map.of("status", "error", "error", "factSheetId is required");
            var failing = evalSetService.getFailingTestCases(input.factSheetId());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("count", failing.size());
            result.put("failingCases", failing.stream().map(c -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", c.getId());
                m.put("query", c.getQuery());
                return m;
            }).collect(Collectors.toList()));
            return result;
        } catch (Exception e) {
            logger.error("Error getting failing test cases: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_pass_rate",
            description = "Gets the pass rate for a specific test case over a time window (windowDays, default 30).")
    public Map<String, Object> getPassRate(GetPassRateInput input) {
        try {
            if (evalSetService == null) return Map.of("status", "error", "error", "EvalSetService not available");
            if (input.testCaseId() == null) return Map.of("status", "error", "error", "testCaseId is required");
            int days = input.windowDays() != null && input.windowDays() > 0 ? input.windowDays() : 30;
            double rate = evalSetService.getTestCasePassRate(input.testCaseId(), days);
            return Map.of("status", "success", "testCaseId", input.testCaseId(), "windowDays", days, "passRate", rate);
        } catch (Exception e) {
            logger.error("Error getting pass rate: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_fact_sheet_metrics",
            description = "Gets aggregated evaluation metrics for a fact sheet including pass rates and score trends.")
    public Map<String, Object> getFactSheetMetrics(GetFactSheetMetricsInput input) {
        try {
            if (evalSetService == null) return Map.of("status", "error", "error", "EvalSetService not available");
            if (input.factSheetId() == null) return Map.of("status", "error", "error", "factSheetId is required");
            var metrics = evalSetService.getFactSheetMetrics(input.factSheetId());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.putAll(metrics);
            return result;
        } catch (Exception e) {
            logger.error("Error getting fact sheet metrics: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "export_eval_suite",
            description = "Exports an evaluation suite with all test cases for backup or sharing.")
    public Map<String, Object> exportEvalSuite(ExportEvalSuiteInput input) {
        try {
            if (evalSetService == null) return Map.of("status", "error", "error", "EvalSetService not available");
            if (input.suiteId() == null) return Map.of("status", "error", "error", "suiteId is required");
            var exported = evalSetService.exportSuite(input.suiteId());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("suite", exported);
            return result;
        } catch (Exception e) {
            logger.error("Error exporting eval suite: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }
}
