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

package ai.kompile.process.tool;

import ai.kompile.process.controls.ControlAttestation;
import ai.kompile.process.controls.ControlDefinition;
import ai.kompile.process.execution.RunStatus;
import ai.kompile.process.execution.StepExecution;
import ai.kompile.process.execution.StepExecutionStatus;
import ai.kompile.process.execution.WorkflowRun;
import ai.kompile.process.hitl.ApprovalRequest;
import ai.kompile.process.ingest.SubmissionManifest;
import ai.kompile.process.ontology.OntologySchema;
import ai.kompile.process.ontology.ValidationRule;
import ai.kompile.process.service.ProcessEngineService;
import ai.kompile.process.service.SpelEvaluationResult;
import ai.kompile.process.workflow.ProcessDefinition;
import ai.kompile.process.workflow.ProcessStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ProcessEngineTool} — verifies all 13 LLM-accessible tool methods
 * delegate correctly to {@link ProcessEngineService} and handle errors gracefully.
 */
@ExtendWith(MockitoExtension.class)
class ProcessEngineToolTest {

    @Mock
    private ProcessEngineService processEngineService;

    private ProcessEngineTool tool;

    @BeforeEach
    void setUp() {
        tool = new ProcessEngineTool(processEngineService);
    }

    // ─── listOntologies ──────────────────────────────────────────────────

    @Test
    void listOntologies_returnsSchemas() {
        OntologySchema schema = OntologySchema.builder()
                .id("ont-1").name("Test Ontology").version(1)
                .createdAt(Instant.now())
                .build();
        when(processEngineService.listOntologies()).thenReturn(List.of(schema));

        Map<String, Object> result = tool.listOntologies(new ProcessEngineTool.ListOntologiesInput());

        assertEquals("success", result.get("status"));
        assertEquals(1, result.get("count"));
    }

    @Test
    void listOntologies_handlesError() {
        when(processEngineService.listOntologies()).thenThrow(new RuntimeException("DB down"));

        Map<String, Object> result = tool.listOntologies(new ProcessEngineTool.ListOntologiesInput());

        assertEquals("error", result.get("status"));
        assertEquals("DB down", result.get("error"));
    }

    // ─── getOntology ─────────────────────────────────────────────────────

    @Test
    void getOntology_returnsSchema() {
        OntologySchema schema = OntologySchema.builder()
                .id("ont-1").name("FPA").version(1).build();
        when(processEngineService.getOntology("ont-1", 1)).thenReturn(schema);

        Map<String, Object> result = tool.getOntology(new ProcessEngineTool.GetOntologyInput("ont-1", 1));

        assertEquals("success", result.get("status"));
        assertEquals("ont-1", result.get("id"));
    }

    @Test
    void getOntology_handlesNotFound() {
        when(processEngineService.getOntology(any(), anyInt()))
                .thenThrow(new IllegalArgumentException("Not found"));

        Map<String, Object> result = tool.getOntology(new ProcessEngineTool.GetOntologyInput("missing", 1));

        assertEquals("error", result.get("status"));
        assertTrue(result.get("error").toString().contains("Not found"));
    }

    // ─── createOntology ──────────────────────────────────────────────────

    @Test
    void createOntology_returnsCreated() {
        OntologySchema created = OntologySchema.builder()
                .id("new-ont").name("New").version(1).createdAt(Instant.now()).build();
        when(processEngineService.createOntology(any())).thenReturn(created);

        Map<String, Object> result = tool.createOntology(
                new ProcessEngineTool.CreateOntologyInput("New", null));

        assertEquals("success", result.get("status"));
        assertEquals("new-ont", result.get("id"));
        assertEquals(1, result.get("version"));
    }

    // ─── validateData ────────────────────────────────────────────────────

    @Test
    void validateData_noViolations() {
        when(processEngineService.validateData(eq("ont-1"), eq(1), eq("entity"), anyMap()))
                .thenReturn(List.of());

        Map<String, Object> result = tool.validateData(
                new ProcessEngineTool.ValidateDataInput("ont-1", 1, "entity", Map.of("val", 42)));

        assertEquals("success", result.get("status"));
        assertTrue((Boolean) result.get("valid"));
        assertEquals(0, result.get("violationCount"));
    }

    @Test
    void validateData_withViolations() {
        ValidationRule rule = ValidationRule.builder()
                .id("r1").name("range-check").expression("#val > 100").build();
        when(processEngineService.validateData(anyString(), anyInt(), anyString(), anyMap()))
                .thenReturn(List.of(rule));

        Map<String, Object> result = tool.validateData(
                new ProcessEngineTool.ValidateDataInput("ont-1", 1, "entity", Map.of("val", 200)));

        assertEquals("success", result.get("status"));
        assertFalse((Boolean) result.get("valid"));
        assertEquals(1, result.get("violationCount"));
    }

    // ─── listDefinitions ─────────────────────────────────────────────────

    @Test
    void listDefinitions_returnsDefinitions() {
        ProcessDefinition def = ProcessDefinition.builder()
                .id("def-1").name("Process A").version(1).status(ProcessStatus.APPROVED).build();
        when(processEngineService.listProcessDefinitions()).thenReturn(List.of(def));

        Map<String, Object> result = tool.listDefinitions(new ProcessEngineTool.ListDefinitionsInput());

        assertEquals("success", result.get("status"));
        assertEquals(1, result.get("count"));
    }

    // ─── getDefinition ───────────────────────────────────────────────────

    @Test
    void getDefinition_returnsDefinition() {
        ProcessDefinition def = ProcessDefinition.builder()
                .id("def-1").name("Process").version(1).status(ProcessStatus.DRAFT).build();
        when(processEngineService.getProcess("def-1", 1)).thenReturn(def);

        Map<String, Object> result = tool.getDefinition(new ProcessEngineTool.GetDefinitionInput("def-1", 1));

        assertEquals("success", result.get("status"));
        assertEquals("def-1", result.get("id"));
    }

    // ─── startRun ────────────────────────────────────────────────────────

    @Test
    void startRun_returnsWorkflowRun() {
        WorkflowRun run = WorkflowRun.builder().id("run-1").build();
        when(processEngineService.startRun(eq("def-1"), anyMap())).thenReturn(run);

        Map<String, Object> result = tool.startRun(
                new ProcessEngineTool.StartRunInput("def-1", Map.of("amount", 5000)));

        assertEquals("success", result.get("status"));
    }

    @Test
    void startRun_handlesError() {
        when(processEngineService.startRun(any(), any()))
                .thenThrow(new IllegalArgumentException("Definition not found"));

        Map<String, Object> result = tool.startRun(
                new ProcessEngineTool.StartRunInput("missing", Map.of()));

        assertEquals("error", result.get("status"));
    }

    // ─── getRun ──────────────────────────────────────────────────────────

    @Test
    void getRun_returnsRun() {
        WorkflowRun run = WorkflowRun.builder().id("run-1").build();
        when(processEngineService.getRun("run-1")).thenReturn(run);

        Map<String, Object> result = tool.getRun(new ProcessEngineTool.GetRunInput("run-1"));

        assertEquals("success", result.get("status"));
    }

    // ─── listActiveRuns ──────────────────────────────────────────────────

    @Test
    void listActiveRuns_returnsRuns() {
        when(processEngineService.listActiveRuns()).thenReturn(List.of());

        Map<String, Object> result = tool.listActiveRuns(new ProcessEngineTool.ListActiveRunsInput());

        assertEquals("success", result.get("status"));
        assertEquals(0, result.get("count"));
    }

    // ─── getPendingApprovals ─────────────────────────────────────────────

    @Test
    void getPendingApprovals_returnsApprovals() {
        when(processEngineService.getPendingApprovals("user1")).thenReturn(List.of());

        Map<String, Object> result = tool.getPendingApprovals(
                new ProcessEngineTool.GetPendingApprovalsInput("user1"));

        assertEquals("success", result.get("status"));
        assertEquals(0, result.get("count"));
    }

    // ─── evaluateControl ─────────────────────────────────────────────────

    @Test
    void evaluateControl_returnsAttestation() {
        ControlAttestation attestation = ControlAttestation.builder()
                .controlId("ctrl-1").passed(true).build();
        when(processEngineService.evaluateControl(eq("ctrl-1"), eq("run-1"), anyMap()))
                .thenReturn(attestation);

        Map<String, Object> result = tool.evaluateControl(
                new ProcessEngineTool.EvaluateControlInput("ctrl-1", "run-1", Map.of()));

        assertEquals("success", result.get("status"));
    }

    // ─── createManifest ──────────────────────────────────────────────────

    @Test
    void createManifest_returnsManifest() {
        SubmissionManifest manifest = SubmissionManifest.builder()
                .id("manifest-1").workflowRunId("run-1").build();
        when(processEngineService.createManifest(any())).thenReturn(manifest);

        Map<String, Object> result = tool.createManifest(
                new ProcessEngineTool.CreateManifestInput("run-1", "US", "budget.xlsx"));

        assertEquals("success", result.get("status"));
    }

    // ─── createDefinition ────────────────────────────────────────────────

    @Test
    void createDefinition_returnsDefinitionWithDraftStatus() {
        ProcessDefinition created = ProcessDefinition.builder()
                .id("def-new").name("Monthly Close").version(1)
                .status(ProcessStatus.DRAFT)
                .ontologySchemaId("ont-1").ontologyVersion(1)
                .build();
        when(processEngineService.createProcess(any())).thenReturn(created);

        Map<String, Object> result = tool.createDefinition(
                new ProcessEngineTool.CreateDefinitionInput("Monthly Close", "ont-1", 1));

        assertEquals("success", result.get("status"));
        assertEquals("def-new", result.get("id"));
        assertEquals("DRAFT", result.get("processStatus"));
        assertEquals(1, result.get("version"));
    }

    @Test
    void createDefinition_handlesError() {
        when(processEngineService.createProcess(any()))
                .thenThrow(new RuntimeException("Schema not found"));

        Map<String, Object> result = tool.createDefinition(
                new ProcessEngineTool.CreateDefinitionInput("Bad Process", "missing-ont", 1));

        assertEquals("error", result.get("status"));
        assertEquals("Schema not found", result.get("error"));
    }

    // ─── approveDefinition ───────────────────────────────────────────────

    @Test
    void approveDefinition_returnsApprovedDefinition() {
        ProcessDefinition approved = ProcessDefinition.builder()
                .id("def-1").name("Monthly Close").version(1)
                .status(ProcessStatus.APPROVED)
                .approvedBy("alice@example.com")
                .build();
        when(processEngineService.approveProcess("def-1", "alice@example.com")).thenReturn(approved);

        Map<String, Object> result = tool.approveDefinition(
                new ProcessEngineTool.ApproveDefinitionInput("def-1", "alice@example.com"));

        assertEquals("success", result.get("status"));
        assertEquals("def-1", result.get("id"));
        assertEquals("APPROVED", result.get("processStatus"));
        assertEquals("alice@example.com", result.get("approvedBy"));
    }

    @Test
    void approveDefinition_handlesError() {
        when(processEngineService.approveProcess(any(), any()))
                .thenThrow(new RuntimeException("Definition not in review"));

        Map<String, Object> result = tool.approveDefinition(
                new ProcessEngineTool.ApproveDefinitionInput("def-draft", "alice@example.com"));

        assertEquals("error", result.get("status"));
        assertEquals("Definition not in review", result.get("error"));
    }

    // ─── cancelRun ───────────────────────────────────────────────────────

    @Test
    void cancelRun_returnsCancelledRun() {
        WorkflowRun cancelled = WorkflowRun.builder()
                .id("run-1").processDefinitionId("def-1")
                .status(RunStatus.CANCELLED)
                .build();
        when(processEngineService.cancelRun("run-1")).thenReturn(cancelled);

        Map<String, Object> result = tool.cancelRun(new ProcessEngineTool.CancelRunInput("run-1"));

        assertEquals("success", result.get("status"));
        assertEquals("run-1", result.get("runId"));
        assertEquals("CANCELLED", result.get("runStatus"));
    }

    @Test
    void cancelRun_handlesError() {
        when(processEngineService.cancelRun(any()))
                .thenThrow(new RuntimeException("Run already completed"));

        Map<String, Object> result = tool.cancelRun(new ProcessEngineTool.CancelRunInput("run-done"));

        assertEquals("error", result.get("status"));
        assertEquals("Run already completed", result.get("error"));
    }

    // ─── listAllRuns ─────────────────────────────────────────────────────

    @Test
    void listAllRuns_returnsAllRuns() {
        WorkflowRun run1 = WorkflowRun.builder().id("run-1").status(RunStatus.COMPLETED).build();
        WorkflowRun run2 = WorkflowRun.builder().id("run-2").status(RunStatus.CANCELLED).build();
        when(processEngineService.listAllRuns()).thenReturn(List.of(run1, run2));

        Map<String, Object> result = tool.listAllRuns(new ProcessEngineTool.ListAllRunsInput());

        assertEquals("success", result.get("status"));
        assertEquals(2, result.get("count"));
    }

    @Test
    void listAllRuns_handlesError() {
        when(processEngineService.listAllRuns())
                .thenThrow(new RuntimeException("DB unavailable"));

        Map<String, Object> result = tool.listAllRuns(new ProcessEngineTool.ListAllRunsInput());

        assertEquals("error", result.get("status"));
        assertEquals("DB unavailable", result.get("error"));
    }

    // ─── completeHumanStep ───────────────────────────────────────────────

    @Test
    void completeHumanStep_returnsUpdatedRun() {
        WorkflowRun run = WorkflowRun.builder()
                .id("run-1").processDefinitionId("def-1")
                .status(RunStatus.RUNNING)
                .build();
        when(processEngineService.completeHumanStep(eq("run-1"), eq("step-2"),
                eq("bob@example.com"), anyMap())).thenReturn(run);

        Map<String, Object> result = tool.completeHumanStep(
                new ProcessEngineTool.CompleteHumanStepInput(
                        "run-1", "step-2", "bob@example.com", Map.of("approved", true)));

        assertEquals("success", result.get("status"));
        assertEquals("run-1", result.get("runId"));
    }

    @Test
    void completeHumanStep_handlesError() {
        when(processEngineService.completeHumanStep(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Step not found"));

        Map<String, Object> result = tool.completeHumanStep(
                new ProcessEngineTool.CompleteHumanStepInput(
                        "run-1", "missing-step", "bob@example.com", Map.of()));

        assertEquals("error", result.get("status"));
        assertEquals("Step not found", result.get("error"));
    }

    // ─── listControls ────────────────────────────────────────────────────

    @Test
    void listControls_returnsControls() {
        ControlDefinition ctrl = ControlDefinition.builder()
                .id("C-01").name("Trial Balance Ties").build();
        when(processEngineService.listControls()).thenReturn(List.of(ctrl));

        Map<String, Object> result = tool.listControls(new ProcessEngineTool.ListControlsInput());

        assertEquals("success", result.get("status"));
        assertEquals(1, result.get("count"));
    }

    @Test
    void listControls_handlesError() {
        when(processEngineService.listControls())
                .thenThrow(new RuntimeException("Registry unavailable"));

        Map<String, Object> result = tool.listControls(new ProcessEngineTool.ListControlsInput());

        assertEquals("error", result.get("status"));
        assertEquals("Registry unavailable", result.get("error"));
    }

    // ─── getControlResults ───────────────────────────────────────────────

    @Test
    void getControlResults_returnsAttestations() {
        ControlAttestation att = ControlAttestation.builder()
                .id("att-1").controlId("C-01").passed(true).build();
        when(processEngineService.getControlResults("run-1")).thenReturn(List.of(att));

        Map<String, Object> result = tool.getControlResults(
                new ProcessEngineTool.GetControlResultsInput("run-1"));

        assertEquals("success", result.get("status"));
        assertEquals(1, result.get("count"));
        assertEquals("run-1", result.get("runId"));
    }

    @Test
    void getControlResults_handlesError() {
        when(processEngineService.getControlResults(any()))
                .thenThrow(new RuntimeException("Run not found"));

        Map<String, Object> result = tool.getControlResults(
                new ProcessEngineTool.GetControlResultsInput("missing-run"));

        assertEquals("error", result.get("status"));
        assertEquals("Run not found", result.get("error"));
    }

    // ─── convertExcelFormulas ────────────────────────────────────────────

    @Test
    void convertExcelFormulas_returnsCodeArtifact() {
        when(processEngineService.convertExcelFormulas(anyString(), anyString()))
                .thenReturn(Map.of("code", "function compute() { return 42; }",
                        "language", "javascript"));

        Map<String, Object> result = tool.convertExcelFormulas(
                new ProcessEngineTool.ConvertExcelFormulasInput("{\"cells\":[]}", "javascript"));

        assertEquals("success", result.get("status"));
        assertNotNull(result.get("code"));
    }

    @Test
    void convertExcelFormulas_handlesError() {
        when(processEngineService.convertExcelFormulas(any(), any()))
                .thenThrow(new RuntimeException("LLM service unavailable"));

        Map<String, Object> result = tool.convertExcelFormulas(
                new ProcessEngineTool.ConvertExcelFormulasInput("{}", "javascript"));

        assertEquals("error", result.get("status"));
        assertEquals("LLM service unavailable", result.get("error"));
    }

    // ─── executeExcelFormulas ────────────────────────────────────────────

    @Test
    void executeExcelFormulas_returnsComputedResults() {
        when(processEngineService.executeExcelFormulas(anyString(), any(), anyString(), any()))
                .thenReturn(Map.of("_generatedCode", "function compute() { return 42; }",
                        "output", 42));

        Map<String, Object> result = tool.executeExcelFormulas(
                new ProcessEngineTool.ExecuteExcelFormulasInput(
                        "{\"cells\":[]}", Map.of("A1", 100), "javascript", null));

        assertEquals("success", result.get("status"));
        assertNotNull(result.get("_generatedCode"));
    }

    @Test
    void executeExcelFormulas_handlesError() {
        when(processEngineService.executeExcelFormulas(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Execution failed"));

        Map<String, Object> result = tool.executeExcelFormulas(
                new ProcessEngineTool.ExecuteExcelFormulasInput("{}", null, "python", null));

        assertEquals("error", result.get("status"));
        assertEquals("Execution failed", result.get("error"));
    }

    // ─── getRunSteps ─────────────────────────────────────────────────────

    @Test
    void getRunSteps_returnsStepExecutions() {
        StepExecution step = StepExecution.builder()
                .stepId("step-1").stepName("Validate TB")
                .status(StepExecutionStatus.COMPLETED)
                .build();
        WorkflowRun run = WorkflowRun.builder()
                .id("run-1").status(RunStatus.RUNNING)
                .stepExecutions(List.of(step))
                .build();
        when(processEngineService.getRun("run-1")).thenReturn(run);

        Map<String, Object> result = tool.getRunSteps(new ProcessEngineTool.GetRunStepsInput("run-1"));

        assertEquals("success", result.get("status"));
        assertEquals("run-1", result.get("runId"));
        assertEquals(1, result.get("stepCount"));
    }

    @Test
    void getRunSteps_handlesError() {
        when(processEngineService.getRun(any()))
                .thenThrow(new RuntimeException("Run not found"));

        Map<String, Object> result = tool.getRunSteps(new ProcessEngineTool.GetRunStepsInput("missing"));

        assertEquals("error", result.get("status"));
        assertEquals("Run not found", result.get("error"));
    }

    // ─── getRunContext ────────────────────────────────────────────────────

    @Test
    void getRunContext_returnsRunData() {
        WorkflowRun run = WorkflowRun.builder()
                .id("run-1").status(RunStatus.RUNNING)
                .runData(Map.of("total_revenue", 1_000_000))
                .build();
        when(processEngineService.getRun("run-1")).thenReturn(run);

        Map<String, Object> result = tool.getRunContext(new ProcessEngineTool.GetRunContextInput("run-1"));

        assertEquals("success", result.get("status"));
        assertEquals("run-1", result.get("runId"));
        assertNotNull(result.get("runData"));
    }

    @Test
    void getRunContext_handlesError() {
        when(processEngineService.getRun(any()))
                .thenThrow(new RuntimeException("Run not found"));

        Map<String, Object> result = tool.getRunContext(
                new ProcessEngineTool.GetRunContextInput("missing"));

        assertEquals("error", result.get("status"));
        assertEquals("Run not found", result.get("error"));
    }

    // ─── getStepResult ───────────────────────────────────────────────────

    @Test
    void getStepResult_returnsSpecificStep() {
        StepExecution step = StepExecution.builder()
                .stepId("step-2").stepName("Reconcile")
                .status(StepExecutionStatus.COMPLETED)
                .outputs(Map.of("balance", 0))
                .build();
        WorkflowRun run = WorkflowRun.builder()
                .id("run-1").status(RunStatus.COMPLETED)
                .stepExecutions(List.of(step))
                .build();
        when(processEngineService.getRun("run-1")).thenReturn(run);

        Map<String, Object> result = tool.getStepResult(
                new ProcessEngineTool.GetStepResultInput("run-1", "step-2"));

        assertEquals("success", result.get("status"));
        assertEquals("run-1", result.get("runId"));
        assertNotNull(result.get("step"));
    }

    @Test
    void getStepResult_handlesError() {
        when(processEngineService.getRun(any()))
                .thenThrow(new RuntimeException("Run not found"));

        Map<String, Object> result = tool.getStepResult(
                new ProcessEngineTool.GetStepResultInput("missing", "step-1"));

        assertEquals("error", result.get("status"));
        assertEquals("Run not found", result.get("error"));
    }

    // ─── evaluateExpression ──────────────────────────────────────────────

    @Test
    void evaluateExpression_returnsResult() {
        SpelEvaluationResult evalResult = SpelEvaluationResult.builder()
                .result(true).type("Boolean").build();
        when(processEngineService.evaluateSpelExpression(eq("#total > 0"), anyMap()))
                .thenReturn(evalResult);

        Map<String, Object> result = tool.evaluateExpression(
                new ProcessEngineTool.EvaluateExpressionInput(
                        "#total > 0", Map.of("total", 42)));

        assertEquals("success", result.get("status"));
        assertEquals("#total > 0", result.get("expression"));
        assertEquals(true, result.get("result"));
        assertEquals("Boolean", result.get("resultType"));
    }

    @Test
    void evaluateExpression_handlesError() {
        when(processEngineService.evaluateSpelExpression(any(), any()))
                .thenThrow(new RuntimeException("Parse error"));

        Map<String, Object> result = tool.evaluateExpression(
                new ProcessEngineTool.EvaluateExpressionInput("!!!invalid", Map.of()));

        assertEquals("error", result.get("status"));
        assertEquals("Parse error", result.get("error"));
    }

    // ─── updateRunData ───────────────────────────────────────────────────

    @Test
    void updateRunData_returnsMergedRun() {
        WorkflowRun updated = WorkflowRun.builder()
                .id("run-1").status(RunStatus.RUNNING)
                .runData(Map.of("_generatedCode", "function f() {}", "total", 99))
                .build();
        when(processEngineService.updateRunData(eq("run-1"), anyMap())).thenReturn(updated);

        Map<String, Object> result = tool.updateRunData(
                new ProcessEngineTool.UpdateRunDataInput(
                        "run-1", Map.of("_generatedCode", "function f() {}")));

        assertEquals("success", result.get("status"));
        assertEquals("run-1", result.get("runId"));
        assertEquals(2, result.get("runDataSize"));
    }

    @Test
    void updateRunData_handlesError() {
        when(processEngineService.updateRunData(any(), any()))
                .thenThrow(new RuntimeException("Run not found"));

        Map<String, Object> result = tool.updateRunData(
                new ProcessEngineTool.UpdateRunDataInput("missing", Map.of("key", "val")));

        assertEquals("error", result.get("status"));
        assertEquals("Run not found", result.get("error"));
    }
}
