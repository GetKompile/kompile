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

import ai.kompile.app.web.controllers.EvalDebuggerController;
import ai.kompile.app.web.controllers.ManagedEvalController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * MCP Tool for evaluation debugging and managed evaluation.
 * Exposes eval debugger and managed evaluation suite functionality.
 */
@Component
public class EvalDebugTool {

    private static final Logger logger = LoggerFactory.getLogger(EvalDebugTool.class);

    private final EvalDebuggerController evalDebuggerController;
    private final ManagedEvalController managedEvalController;

    @Autowired
    public EvalDebugTool(
            @Autowired(required = false) EvalDebuggerController evalDebuggerController,
            @Autowired(required = false) ManagedEvalController managedEvalController) {
        this.evalDebuggerController = evalDebuggerController;
        this.managedEvalController = managedEvalController;
    }

    // Input records
    public record GetEvalDebugStatusInput() {}
    public record GetEvaluatorTypesInput() {}
    public record GetLlmProvidersInput() {}
    public record GetEvalTestSuitesInput() {}
    public record GetEvalTestSuiteInput(String id) {}
    public record DeleteEvalTestSuiteInput(String id) {}
    public record RunEvalTestSuiteInput(String id) {}
    public record GetEvalRunHistoryInput() {}
    public record GetEvalRunResultInput(String runId) {}

    public record GetAllManagedSuitesInput() {}
    public record GetManagedSuitesForFactSheetInput(Long factSheetId) {}
    public record GetManagedSuiteInput(String suiteId) {}
    public record DeleteManagedSuiteInput(String suiteId) {}
    public record GetManagedTestCasesInput(String suiteId) {}
    public record GetManagedTestCaseInput(String caseId) {}
    public record DeleteManagedTestCaseInput(String caseId) {}
    public record GetManagedSuiteResultHistoryInput(String suiteId, Integer limit) {}
    public record GetLatestManagedSuiteResultInput(String suiteId) {}
    public record GetManagedFactSheetMetricsInput(Long factSheetId) {}
    public record GetManagedFailingTestCasesInput(Long factSheetId) {}
    public record GetManagedTestCasePassRateInput(String caseId, Integer windowDays) {}
    public record ExportManagedSuiteInput(String suiteId) {}

    // === Eval Debugger ===

    @Tool(name = "get_eval_debugger_status",
            description = "Checks if the evaluation debugger is available.")
    public Map<String, Object> getEvalDebugStatus(GetEvalDebugStatusInput input) {
        try {
            if (evalDebuggerController == null) return Map.of("status", "error", "error", "Eval debugger not available");
            ResponseEntity<?> response = evalDebuggerController.getStatus();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting eval debugger status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_evaluator_types",
            description = "Gets available evaluator types for evaluation testing.")
    public Map<String, Object> getEvaluatorTypes(GetEvaluatorTypesInput input) {
        try {
            if (evalDebuggerController == null) return Map.of("status", "error", "error", "Eval debugger not available");
            ResponseEntity<?> response = evalDebuggerController.getEvaluatorTypes();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting evaluator types: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_eval_llm_providers",
            description = "Gets available LLM providers for evaluation judging.")
    public Map<String, Object> getLlmProviders(GetLlmProvidersInput input) {
        try {
            if (evalDebuggerController == null) return Map.of("status", "error", "error", "Eval debugger not available");
            ResponseEntity<?> response = evalDebuggerController.getLlmProviders();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting LLM providers: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_eval_test_suites",
            description = "Gets all saved evaluation test suites.")
    public Map<String, Object> getEvalTestSuites(GetEvalTestSuitesInput input) {
        try {
            if (evalDebuggerController == null) return Map.of("status", "error", "error", "Eval debugger not available");
            ResponseEntity<?> response = evalDebuggerController.getTestSuites();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting eval test suites: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_eval_test_suite",
            description = "Gets a specific evaluation test suite by its ID.")
    public Map<String, Object> getEvalTestSuite(GetEvalTestSuiteInput input) {
        try {
            if (evalDebuggerController == null) return Map.of("status", "error", "error", "Eval debugger not available");
            if (input.id() == null) return Map.of("status", "error", "error", "Suite ID is required");
            ResponseEntity<?> response = evalDebuggerController.getTestSuite(input.id());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting eval test suite: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "delete_eval_test_suite",
            description = "Deletes an evaluation test suite by its ID.")
    public Map<String, Object> deleteEvalTestSuite(DeleteEvalTestSuiteInput input) {
        try {
            if (evalDebuggerController == null) return Map.of("status", "error", "error", "Eval debugger not available");
            if (input.id() == null) return Map.of("status", "error", "error", "Suite ID is required");
            ResponseEntity<?> response = evalDebuggerController.deleteTestSuite(input.id());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error deleting eval test suite: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "run_eval_test_suite",
            description = "Runs a saved evaluation test suite by its ID.")
    public Map<String, Object> runEvalTestSuite(RunEvalTestSuiteInput input) {
        try {
            if (evalDebuggerController == null) return Map.of("status", "error", "error", "Eval debugger not available");
            if (input.id() == null) return Map.of("status", "error", "error", "Suite ID is required");
            ResponseEntity<?> response = evalDebuggerController.runTestSuite(input.id());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error running eval test suite: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_eval_run_history",
            description = "Gets previous evaluation run results.")
    public Map<String, Object> getEvalRunHistory(GetEvalRunHistoryInput input) {
        try {
            if (evalDebuggerController == null) return Map.of("status", "error", "error", "Eval debugger not available");
            ResponseEntity<?> response = evalDebuggerController.getRunHistory();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting eval run history: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_eval_run_result",
            description = "Gets a specific evaluation run result by its run ID.")
    public Map<String, Object> getEvalRunResult(GetEvalRunResultInput input) {
        try {
            if (evalDebuggerController == null) return Map.of("status", "error", "error", "Eval debugger not available");
            if (input.runId() == null) return Map.of("status", "error", "error", "Run ID is required");
            ResponseEntity<?> response = evalDebuggerController.getRunResult(input.runId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting eval run result: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    // === Managed Evaluation ===

    @Tool(name = "get_all_managed_eval_suites",
            description = "Lists all managed evaluation suites.")
    public Map<String, Object> getAllManagedSuites(GetAllManagedSuitesInput input) {
        try {
            if (managedEvalController == null) return Map.of("status", "error", "error", "Managed eval not available");
            ResponseEntity<?> response = managedEvalController.getAllSuites();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting all managed suites: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_managed_eval_suites_for_fact_sheet",
            description = "Gets managed evaluation suites for a specific fact sheet.")
    public Map<String, Object> getManagedSuitesForFactSheet(GetManagedSuitesForFactSheetInput input) {
        try {
            if (managedEvalController == null) return Map.of("status", "error", "error", "Managed eval not available");
            if (input.factSheetId() == null) return Map.of("status", "error", "error", "Fact sheet ID is required");
            ResponseEntity<?> response = managedEvalController.getSuitesForFactSheet(input.factSheetId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting managed suites for fact sheet: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_managed_eval_suite",
            description = "Gets a managed evaluation suite with full details by its ID.")
    public Map<String, Object> getManagedSuite(GetManagedSuiteInput input) {
        try {
            if (managedEvalController == null) return Map.of("status", "error", "error", "Managed eval not available");
            if (input.suiteId() == null) return Map.of("status", "error", "error", "Suite ID is required");
            ResponseEntity<?> response = managedEvalController.getSuite(input.suiteId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting managed suite: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "delete_managed_eval_suite",
            description = "Deletes a managed evaluation suite by its ID.")
    public Map<String, Object> deleteManagedSuite(DeleteManagedSuiteInput input) {
        try {
            if (managedEvalController == null) return Map.of("status", "error", "error", "Managed eval not available");
            if (input.suiteId() == null) return Map.of("status", "error", "error", "Suite ID is required");
            ResponseEntity<?> response = managedEvalController.deleteSuite(input.suiteId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error deleting managed suite: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_managed_eval_test_cases",
            description = "Gets test cases in a managed evaluation suite.")
    public Map<String, Object> getManagedTestCases(GetManagedTestCasesInput input) {
        try {
            if (managedEvalController == null) return Map.of("status", "error", "error", "Managed eval not available");
            if (input.suiteId() == null) return Map.of("status", "error", "error", "Suite ID is required");
            ResponseEntity<?> response = managedEvalController.getTestCasesInSuite(input.suiteId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting managed test cases: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_managed_eval_test_case",
            description = "Gets a specific managed test case by its ID.")
    public Map<String, Object> getManagedTestCase(GetManagedTestCaseInput input) {
        try {
            if (managedEvalController == null) return Map.of("status", "error", "error", "Managed eval not available");
            if (input.caseId() == null) return Map.of("status", "error", "error", "Case ID is required");
            ResponseEntity<?> response = managedEvalController.getTestCase(input.caseId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting managed test case: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "delete_managed_eval_test_case",
            description = "Deletes a managed test case by its ID.")
    public Map<String, Object> deleteManagedTestCase(DeleteManagedTestCaseInput input) {
        try {
            if (managedEvalController == null) return Map.of("status", "error", "error", "Managed eval not available");
            if (input.caseId() == null) return Map.of("status", "error", "error", "Case ID is required");
            ResponseEntity<?> response = managedEvalController.deleteTestCase(input.caseId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error deleting managed test case: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_managed_eval_suite_results",
            description = "Gets result history for a managed evaluation suite.")
    public Map<String, Object> getManagedSuiteResultHistory(GetManagedSuiteResultHistoryInput input) {
        try {
            if (managedEvalController == null) return Map.of("status", "error", "error", "Managed eval not available");
            if (input.suiteId() == null) return Map.of("status", "error", "error", "Suite ID is required");
            int limit = input.limit() != null ? input.limit() : 10;
            ResponseEntity<?> response = managedEvalController.getSuiteResultHistory(input.suiteId(), limit);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting managed suite result history: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_latest_managed_eval_result",
            description = "Gets the latest evaluation result for a managed suite.")
    public Map<String, Object> getLatestManagedSuiteResult(GetLatestManagedSuiteResultInput input) {
        try {
            if (managedEvalController == null) return Map.of("status", "error", "error", "Managed eval not available");
            if (input.suiteId() == null) return Map.of("status", "error", "error", "Suite ID is required");
            ResponseEntity<?> response = managedEvalController.getLatestSuiteResult(input.suiteId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting latest managed suite result: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_managed_eval_fact_sheet_metrics",
            description = "Gets aggregated evaluation metrics for a fact sheet.")
    public Map<String, Object> getManagedFactSheetMetrics(GetManagedFactSheetMetricsInput input) {
        try {
            if (managedEvalController == null) return Map.of("status", "error", "error", "Managed eval not available");
            if (input.factSheetId() == null) return Map.of("status", "error", "error", "Fact sheet ID is required");
            ResponseEntity<?> response = managedEvalController.getFactSheetMetrics(input.factSheetId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting managed fact sheet metrics: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_managed_eval_failing_test_cases",
            description = "Gets failing test cases for a fact sheet.")
    public Map<String, Object> getManagedFailingTestCases(GetManagedFailingTestCasesInput input) {
        try {
            if (managedEvalController == null) return Map.of("status", "error", "error", "Managed eval not available");
            if (input.factSheetId() == null) return Map.of("status", "error", "error", "Fact sheet ID is required");
            ResponseEntity<?> response = managedEvalController.getFailingTestCases(input.factSheetId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting managed failing test cases: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_managed_eval_test_case_pass_rate",
            description = "Gets the pass rate with trend for a specific test case.")
    public Map<String, Object> getManagedTestCasePassRate(GetManagedTestCasePassRateInput input) {
        try {
            if (managedEvalController == null) return Map.of("status", "error", "error", "Managed eval not available");
            if (input.caseId() == null) return Map.of("status", "error", "error", "Case ID is required");
            int windowDays = input.windowDays() != null ? input.windowDays() : 30;
            ResponseEntity<?> response = managedEvalController.getTestCasePassRate(input.caseId(), windowDays);
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting managed test case pass rate: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "export_managed_eval_suite",
            description = "Exports a managed evaluation suite as JSON.")
    public Map<String, Object> exportManagedSuite(ExportManagedSuiteInput input) {
        try {
            if (managedEvalController == null) return Map.of("status", "error", "error", "Managed eval not available");
            if (input.suiteId() == null) return Map.of("status", "error", "error", "Suite ID is required");
            ResponseEntity<?> response = managedEvalController.exportSuite(input.suiteId());
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error exporting managed suite: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }
}
