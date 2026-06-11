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

package ai.kompile.process.controller;

import ai.kompile.process.controls.ControlAttestation;
import ai.kompile.process.controls.ControlDefinition;
import ai.kompile.process.execution.WorkflowRun;
import ai.kompile.process.hitl.ApprovalRequest;
import ai.kompile.process.hitl.ApprovalResponse;
import ai.kompile.process.ingest.SubmissionManifest;
import ai.kompile.process.ontology.OntologySchema;
import ai.kompile.process.ontology.ValidationRule;
import ai.kompile.process.service.ProcessEngineService;
import ai.kompile.process.service.SpelEvaluationResult;
import ai.kompile.process.service.StepExecutionDispatcher;
import ai.kompile.process.workflow.ProcessDefinition;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * REST controller exposing the {@link ProcessEngineService} over HTTP.
 * All endpoints are rooted at {@code /api/process}.
 */
@RestController
@RequestMapping("/api/process")
@CrossOrigin(origins = "*")
public class ProcessEngineController {

    // -------------------------------------------------------------------------
    // Inner request DTOs
    // -------------------------------------------------------------------------

    record ValidateRequest(String entityType, int version, Map<String, Object> data) {}

    record ApproveRequest(String approvedBy) {}

    record StartRunRequest(String processDefinitionId, Map<String, Object> initialData) {}

    record EvaluateControlRequest(String runId, Map<String, Object> data) {}

    record AssertVersionRequest(String fileId, String assertedBy) {}

    record CompleteStepRequest(String stepId, String completedBy, Map<String, Object> outputs) {}

    record SpelEvaluateRequest(String expression, Map<String, Object> context) {}

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    private final ProcessEngineService processEngineService;
    private final StepExecutionDispatcher stepExecutionDispatcher;

    public ProcessEngineController(ProcessEngineService processEngineService,
                                    @org.springframework.beans.factory.annotation.Autowired(required = false)
                                    StepExecutionDispatcher stepExecutionDispatcher) {
        this.processEngineService = processEngineService;
        this.stepExecutionDispatcher = stepExecutionDispatcher;
    }

    // -------------------------------------------------------------------------
    // Ontology endpoints
    // -------------------------------------------------------------------------

    /**
     * Creates a new ontology schema.
     * {@code POST /api/process/ontology}
     */
    @PostMapping("/ontology")
    public ResponseEntity<OntologySchema> createOntology(@RequestBody OntologySchema schema) {
        OntologySchema created = processEngineService.createOntology(schema);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Retrieves a specific version of an ontology schema.
     * {@code GET /api/process/ontology/{id}?version=N}
     */
    @GetMapping("/ontology")
    public ResponseEntity<List<OntologySchema>> listOntologies() {
        return ResponseEntity.ok(processEngineService.listOntologies());
    }

    @GetMapping("/ontology/{id}")
    public ResponseEntity<OntologySchema> getOntology(
            @PathVariable String id,
            @RequestParam(defaultValue = "1") int version) {
        try {
            OntologySchema schema = processEngineService.getOntology(id, version);
            return ResponseEntity.ok(schema);
        } catch (NoSuchElementException | IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Updates an existing ontology schema (increments version).
     * {@code PUT /api/process/ontology/{id}}
     */
    @PutMapping("/ontology/{id}")
    public ResponseEntity<OntologySchema> updateOntology(
            @PathVariable String id,
            @RequestBody OntologySchema schema) {
        try {
            OntologySchema updated = processEngineService.updateOntology(id, schema);
            return ResponseEntity.ok(updated);
        } catch (NoSuchElementException | IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Validates data against an ontology schema version.
     * {@code POST /api/process/ontology/{id}/validate}
     */
    @PostMapping("/ontology/{id}/validate")
    public ResponseEntity<List<ValidationRule>> validateData(
            @PathVariable String id,
            @RequestBody ValidateRequest request) {
        try {
            List<ValidationRule> violations = processEngineService.validateData(
                    id, request.version(), request.entityType(), request.data());
            return ResponseEntity.ok(violations);
        } catch (NoSuchElementException | IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // -------------------------------------------------------------------------
    // Process definition endpoints
    // -------------------------------------------------------------------------

    /**
     * Creates a new process definition in DRAFT status.
     * {@code POST /api/process/definition}
     */
    @GetMapping("/definition")
    public ResponseEntity<List<ProcessDefinition>> listProcessDefinitions() {
        return ResponseEntity.ok(processEngineService.listProcessDefinitions());
    }

    @PostMapping("/definition")
    public ResponseEntity<ProcessDefinition> createProcess(@RequestBody ProcessDefinition definition) {
        ProcessDefinition created = processEngineService.createProcess(definition);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Retrieves a specific version of a process definition.
     * {@code GET /api/process/definition/{id}?version=N}
     */
    @GetMapping("/definition/{id}")
    public ResponseEntity<ProcessDefinition> getProcess(
            @PathVariable String id,
            @RequestParam(defaultValue = "1") int version) {
        try {
            ProcessDefinition definition = processEngineService.getProcess(id, version);
            return ResponseEntity.ok(definition);
        } catch (NoSuchElementException | IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Approves a process definition, transitioning it from IN_REVIEW to APPROVED.
     * {@code POST /api/process/definition/{id}/approve}
     */
    @PostMapping("/definition/{id}/approve")
    public ResponseEntity<ProcessDefinition> approveProcess(
            @PathVariable String id,
            @RequestBody ApproveRequest request) {
        try {
            ProcessDefinition approved = processEngineService.approveProcess(id, request.approvedBy());
            return ResponseEntity.ok(approved);
        } catch (NoSuchElementException | IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // -------------------------------------------------------------------------
    // Workflow run endpoints
    // -------------------------------------------------------------------------

    /**
     * Starts a new workflow run for a given process definition.
     * {@code POST /api/process/run}
     */
    @PostMapping("/run")
    public ResponseEntity<WorkflowRun> startRun(@RequestBody StartRunRequest request) {
        try {
            WorkflowRun run = processEngineService.startRun(
                    request.processDefinitionId(), request.initialData());
            return ResponseEntity.status(HttpStatus.CREATED).body(run);
        } catch (NoSuchElementException | IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Retrieves the current state of a workflow run.
     * {@code GET /api/process/run/{id}}
     */
    @GetMapping("/run/{id}")
    public ResponseEntity<WorkflowRun> getRun(@PathVariable String id) {
        try {
            WorkflowRun run = processEngineService.getRun(id);
            return ResponseEntity.ok(run);
        } catch (NoSuchElementException | IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Resumes a run paused at a HITL gate after a human approval response.
     * {@code POST /api/process/run/{id}/resume}
     */
    @PostMapping("/run/{id}/resume")
    public ResponseEntity<WorkflowRun> resumeAfterApproval(
            @PathVariable String id,
            @RequestBody ApprovalResponse response) {
        try {
            WorkflowRun run = processEngineService.resumeAfterApproval(id, response);
            return ResponseEntity.ok(run);
        } catch (NoSuchElementException | IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Lists all currently active workflow runs.
     * {@code GET /api/process/run/active}
     */
    @GetMapping("/run/active")
    public ResponseEntity<List<WorkflowRun>> listActiveRuns() {
        return ResponseEntity.ok(processEngineService.listActiveRuns());
    }

    @GetMapping("/run/all")
    public ResponseEntity<List<WorkflowRun>> listAllRuns() {
        return ResponseEntity.ok(processEngineService.listAllRuns());
    }

    @PostMapping("/run/{id}/cancel")
    public ResponseEntity<WorkflowRun> cancelRun(@PathVariable String id) {
        try {
            WorkflowRun cancelled = processEngineService.cancelRun(id);
            return ResponseEntity.ok(cancelled);
        } catch (NoSuchElementException | IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/run/{id}/complete-step")
    public ResponseEntity<WorkflowRun> completeHumanStep(
            @PathVariable String id,
            @RequestBody CompleteStepRequest request) {
        try {
            WorkflowRun run = processEngineService.completeHumanStep(
                    id, request.stepId(), request.completedBy(), request.outputs());
            return ResponseEntity.ok(run);
        } catch (NoSuchElementException | IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // -------------------------------------------------------------------------
    // HITL approval endpoints
    // -------------------------------------------------------------------------

    /**
     * Returns all pending approval requests for a given assignee.
     * {@code GET /api/process/approval/pending?assignedTo=X}
     */
    @GetMapping("/approval/pending")
    public ResponseEntity<List<ApprovalRequest>> getPendingApprovals(
            @RequestParam(required = false) String assignedTo) {
        return ResponseEntity.ok(processEngineService.getPendingApprovals(
                assignedTo == null || assignedTo.isBlank() ? null : assignedTo));
    }

    /**
     * Retrieves a single approval request by ID.
     * {@code GET /api/process/approval/{id}}
     */
    @GetMapping("/approval/{id}")
    public ResponseEntity<ApprovalRequest> getApprovalRequest(@PathVariable String id) {
        try {
            ApprovalRequest request = processEngineService.getApprovalRequest(id);
            return ResponseEntity.ok(request);
        } catch (NoSuchElementException | IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Submits a human response to an approval request.
     * {@code POST /api/process/approval/{id}/respond}
     */
    @PostMapping("/approval/{id}/respond")
    public ResponseEntity<WorkflowRun> submitApproval(
            @PathVariable String id,
            @RequestBody ApprovalResponse response) {
        try {
            WorkflowRun run = processEngineService.submitApproval(response);
            return ResponseEntity.ok(run);
        } catch (NoSuchElementException | IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // -------------------------------------------------------------------------
    // Control endpoints
    // -------------------------------------------------------------------------

    /**
     * Evaluates a control assertion against the current data state of a run.
     * {@code POST /api/process/control/{id}/evaluate}
     */
    @PostMapping("/control")
    public ResponseEntity<ControlDefinition> createControl(@RequestBody ControlDefinition control) {
        ControlDefinition created = processEngineService.createControl(control);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/control")
    public ResponseEntity<List<ControlDefinition>> listControls() {
        return ResponseEntity.ok(processEngineService.listControls());
    }

    @GetMapping("/control/{id}")
    public ResponseEntity<ControlDefinition> getControl(@PathVariable String id) {
        try {
            ControlDefinition control = processEngineService.getControl(id);
            return ResponseEntity.ok(control);
        } catch (NoSuchElementException | IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/control/{id}/evaluate")
    public ResponseEntity<ControlAttestation> evaluateControl(
            @PathVariable String id,
            @RequestBody EvaluateControlRequest request) {
        try {
            ControlAttestation attestation = processEngineService.evaluateControl(
                    id, request.runId(), request.data());
            return ResponseEntity.ok(attestation);
        } catch (NoSuchElementException | IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Returns all control attestation records for a workflow run.
     * {@code GET /api/process/control/results/{runId}}
     */
    @GetMapping("/control/results/{runId}")
    public ResponseEntity<List<ControlAttestation>> getControlResults(@PathVariable String runId) {
        try {
            List<ControlAttestation> results = processEngineService.getControlResults(runId);
            return ResponseEntity.ok(results);
        } catch (NoSuchElementException | IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // -------------------------------------------------------------------------
    // SpEL expression evaluation
    // -------------------------------------------------------------------------

    /**
     * Evaluates a SpEL expression against a provided context map.
     * {@code POST /api/process/spel/evaluate}
     */
    @PostMapping("/spel/evaluate")
    public ResponseEntity<SpelEvaluationResult> evaluateSpelExpression(
            @RequestBody SpelEvaluateRequest request) {
        SpelEvaluationResult result = processEngineService.evaluateSpelExpression(
                request.expression(), request.context());
        return ResponseEntity.ok(result);
    }

    // -------------------------------------------------------------------------
    // Ingestion / manifest endpoints
    // -------------------------------------------------------------------------

    /**
     * Creates a submission manifest for a new batch of incoming data files.
     * {@code POST /api/process/manifest}
     */
    @PostMapping("/manifest")
    public ResponseEntity<SubmissionManifest> createManifest(@RequestBody SubmissionManifest manifest) {
        SubmissionManifest created = processEngineService.createManifest(manifest);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Records a human assertion that a specific file is the authoritative version.
     * {@code POST /api/process/manifest/{id}/assert-version}
     */
    @PostMapping("/manifest/{id}/assert-version")
    public ResponseEntity<SubmissionManifest> assertAuthoritativeVersion(
            @PathVariable String id,
            @RequestBody AssertVersionRequest request) {
        try {
            SubmissionManifest updated = processEngineService.assertAuthoritativeVersion(
                    id, request.fileId(), request.assertedBy());
            return ResponseEntity.ok(updated);
        } catch (NoSuchElementException | IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // -------------------------------------------------------------------------
    // Integration discovery endpoints
    // -------------------------------------------------------------------------

    /**
     * Lists all available tool integrations that can be referenced in TOOL_CALL steps.
     * Returns tool names, descriptions, categories, and input schemas.
     * {@code GET /api/process/integrations}
     */
    @GetMapping("/integrations")
    public ResponseEntity<Map<String, Object>> listAvailableIntegrations() {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        if (stepExecutionDispatcher != null) {
            List<Map<String, Object>> tools = stepExecutionDispatcher.listAvailableTools();
            result.put("tools", tools);
            result.put("count", tools.size());

            // Group by category
            Map<String, List<Map<String, Object>>> byCategory = new java.util.LinkedHashMap<>();
            for (Map<String, Object> tool : tools) {
                String category = (String) tool.getOrDefault("category", "other");
                byCategory.computeIfAbsent(category, k -> new java.util.ArrayList<>()).add(tool);
            }
            result.put("categories", byCategory.keySet());
            result.put("toolsByCategory", byCategory);
        } else {
            result.put("tools", List.of());
            result.put("count", 0);
            result.put("message", "StepExecutionDispatcher not available — no tool integrations discovered");
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Lists all available step types with descriptions.
     * {@code GET /api/process/step-types}
     */
    @GetMapping("/step-types")
    public ResponseEntity<List<Map<String, String>>> listStepTypes() {
        List<Map<String, String>> types = List.of(
            Map.of("type", "AUTO", "description", "Evaluates SpEL expressions against runData. No human gate."),
            Map.of("type", "APPROVE", "description", "Pauses for human approval before proceeding."),
            Map.of("type", "HUMAN", "description", "Entirely manual — a human performs the work."),
            Map.of("type", "CONTROL_GATE", "description", "Evaluates compliance/SOX controls. Halts on HARD gate failure."),
            Map.of("type", "TOOL_CALL", "description", "Invokes a registered MCP/Spring AI tool by name."),
            Map.of("type", "HTTP_CALL", "description", "Makes an HTTP request to an external service."),
            Map.of("type", "SCRIPT", "description", "Executes JavaScript (GraalVM) or Python (CPython via Python4J) code with full runData access. Set scriptLanguage to 'javascript' or 'python'."),
            Map.of("type", "EXCEL_COMPUTE", "description", "Converts Excel spreadsheet formulas to code via LLM and executes the generated code. Requires SpreadsheetGraph JSON."),
            Map.of("type", "PIPELINE", "description", "Executes a serialized Kompile pipeline definition and merges outputs into runData."),
            Map.of("type", "CAMEL_ROUTE", "description", "Executes a saved or inline Apache Camel route using runData as exchange inputs."),
            Map.of("type", "DROOLS_RULE", "description", "Executes Drools DRL rules against facts derived from runData."),
            Map.of("type", "DROOLS_INFERENCE", "description", "Executes Drools forward-chaining inference against facts derived from runData."),
            Map.of("type", "DROOLS_DECISION_TABLE", "description", "Executes a Drools CSV/XLS decision table against facts derived from runData."),
            Map.of("type", "WORKFLOW", "description", "Executes a saved or inline Xircuits/n8n workflow and merges outputs into runData.")
        );
        return ResponseEntity.ok(types);
    }

    // ═══════════════════ Excel Formula Conversion ═══════════════════

    /**
     * Converts Excel spreadsheet formulas to code via LLM without executing.
     * Returns the generated code as a reviewable/editable artifact.
     *
     * {@code POST /api/process/excel/convert}
     */
    @PostMapping("/excel/convert")
    public ResponseEntity<Map<String, Object>> convertExcelFormulas(@RequestBody Map<String, Object> request) {
        String graphJson = (String) request.get("spreadsheetGraphJson");
        String language = (String) request.getOrDefault("targetLanguage", "javascript");
        if (graphJson == null || graphJson.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "spreadsheetGraphJson is required"));
        }
        try {
            Map<String, Object> result = processEngineService.convertExcelFormulas(graphJson, language);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Executes Excel spreadsheet computation. Accepts optional user-edited code;
     * if not provided, converts via LLM first. Result always includes the code
     * artifact for persistence.
     *
     * {@code POST /api/process/excel/execute}
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/excel/execute")
    public ResponseEntity<Map<String, Object>> executeExcelFormulas(@RequestBody Map<String, Object> request) {
        String graphJson = (String) request.get("spreadsheetGraphJson");
        String language = (String) request.getOrDefault("targetLanguage", "javascript");
        String generatedCode = (String) request.get("generatedCode");
        Map<String, Object> cellOverrides = (Map<String, Object>) request.get("cellOverrides");
        if (graphJson == null || graphJson.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "spreadsheetGraphJson is required"));
        }
        try {
            Map<String, Object> result = processEngineService.executeExcelFormulas(
                    graphJson, cellOverrides, language, generatedCode);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
