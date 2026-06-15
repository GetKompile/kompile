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

package ai.kompile.process.service;

import ai.kompile.process.controls.ControlAttestation;
import ai.kompile.process.controls.ControlDefinition;
import ai.kompile.process.execution.WorkflowRun;
import ai.kompile.process.hitl.ApprovalRequest;
import ai.kompile.process.hitl.ApprovalResponse;
import ai.kompile.process.ingest.SubmissionManifest;
import ai.kompile.process.ontology.OntologySchema;
import ai.kompile.process.ontology.ValidationRule;
import ai.kompile.process.workflow.ProcessDefinition;

import java.util.List;
import java.util.Map;

/**
 * Main service interface for the process engine.
 * Orchestrates workflow execution, HITL gates, and control evaluation.
 * Implementations of this interface drive the full lifecycle from ontology design
 * through process execution and SOX-compliant audit trail generation.
 */
public interface ProcessEngineService {

    // -------------------------------------------------------------------------
    // Ontology management
    // -------------------------------------------------------------------------

    /**
     * Persists a new ontology schema and returns it with a generated ID and version 1.
     *
     * @param schema the schema to create
     * @return the created schema with server-assigned ID and metadata
     */
    OntologySchema createOntology(OntologySchema schema);

    /**
     * Retrieves a specific version of an ontology schema.
     *
     * @param id      the ontology schema ID
     * @param version the version number to retrieve
     * @return the requested ontology schema snapshot
     */
    OntologySchema getOntology(String id, int version);

    /**
     * Updates an existing ontology schema, incrementing the version number.
     * Previous versions remain immutable and available for retrieval.
     *
     * @param id     the ID of the schema to update
     * @param schema the new schema content
     * @return the updated schema with an incremented version
     */
    OntologySchema updateOntology(String id, OntologySchema schema);

    /**
     * Validates a data map against the rules defined for a specific entity type
     * in the given ontology version.
     *
     * @param ontologyId  the ontology schema ID
     * @param version     the ontology version to use for validation
     * @param entityType  the entity type name whose rules to evaluate
     * @param data        the key-value data to validate
     * @return a list of violated {@link ValidationRule}s (empty if all rules pass)
     */
    List<ValidationRule> validateData(String ontologyId, int version, String entityType, Map<String, Object> data);

    // -------------------------------------------------------------------------
    // Process definition
    // -------------------------------------------------------------------------

    /**
     * Creates a new process definition in DRAFT status.
     *
     * @param definition the process definition to persist
     * @return the created definition with server-assigned ID and version 1
     */
    ProcessDefinition createProcess(ProcessDefinition definition);

    /**
     * Retrieves a specific version of a process definition.
     *
     * @param id      the process definition ID
     * @param version the version number to retrieve
     * @return the requested process definition snapshot
     */
    ProcessDefinition getProcess(String id, int version);

    /**
     * Transitions a process definition from IN_REVIEW to APPROVED.
     * Approved definitions may be used to start workflow runs.
     *
     * @param id         the process definition ID to approve
     * @param approvedBy the identifier of the person granting approval
     * @return the updated definition with status APPROVED
     */
    ProcessDefinition approveProcess(String id, String approvedBy);

    /**
     * Returns all ontology schemas (latest version of each).
     */
    List<OntologySchema> listOntologies();

    /**
     * Returns all process definitions (latest version of each).
     */
    List<ProcessDefinition> listProcessDefinitions();

    // -------------------------------------------------------------------------
    // Workflow execution
    // -------------------------------------------------------------------------

    /**
     * Starts a new workflow run for the specified process definition.
     * The process must have APPROVED status before a run can be started.
     *
     * @param processDefinitionId the ID of the approved process to execute
     * @param initialData         seed data injected into the run's data state before execution begins
     * @return the newly created {@link WorkflowRun} in RUNNING status
     */
    WorkflowRun startRun(String processDefinitionId, Map<String, Object> initialData);

    /**
     * Retrieves the current state of a workflow run.
     *
     * @param runId the workflow run ID
     * @return the current {@link WorkflowRun} snapshot
     */
    WorkflowRun getRun(String runId);

    /**
     * Resumes a run that is paused at a HITL approval gate after a human response has been submitted.
     *
     * @param runId    the ID of the paused run to resume
     * @param response the human's approval response
     * @return the updated {@link WorkflowRun} after processing the response
     */
    WorkflowRun resumeAfterApproval(String runId, ApprovalResponse response);

    /**
     * Lists all workflow runs that are currently in an active state
     * (RUNNING, PAUSED_FOR_APPROVAL, or PAUSED_FOR_HUMAN).
     *
     * @return a list of active {@link WorkflowRun} instances
     */
    List<WorkflowRun> listActiveRuns();

    /**
     * Returns all workflow runs regardless of status.
     */
    List<WorkflowRun> listAllRuns();

    /**
     * Cancels a running or paused workflow run.
     *
     * @param runId the ID of the run to cancel
     * @return the updated run with CANCELLED status
     */
    WorkflowRun cancelRun(String runId);

    /**
     * Completes a HUMAN-type step that is waiting for manual execution.
     *
     * @param runId       the workflow run ID
     * @param stepId      the step ID to complete
     * @param completedBy who completed the step
     * @param outputs     the outputs produced by the human
     * @return the updated run after advancing past the completed step
     */
    WorkflowRun completeHumanStep(String runId, String stepId, String completedBy, Map<String, Object> outputs);

    // -------------------------------------------------------------------------
    // Human-in-the-loop (HITL)
    // -------------------------------------------------------------------------

    /**
     * Returns all pending approval requests assigned to the specified person or role.
     *
     * @param assignedTo the person ID or role name to filter by
     * @return a list of {@link ApprovalRequest}s awaiting response
     */
    List<ApprovalRequest> getPendingApprovals(String assignedTo);

    /**
     * Retrieves a single approval request by ID.
     *
     * @param requestId the approval request ID
     * @return the {@link ApprovalRequest}
     */
    ApprovalRequest getApprovalRequest(String requestId);

    /**
     * Submits a human response to an approval request and resumes the associated workflow run.
     *
     * @param response the human's approval response
     * @return the updated {@link WorkflowRun} after the response is processed
     */
    WorkflowRun submitApproval(ApprovalResponse response);

    // -------------------------------------------------------------------------
    // Controls
    // -------------------------------------------------------------------------

    /**
     * Evaluates a control assertion against the current data state of a run.
     * Always produces an immutable {@link ControlAttestation} record regardless of outcome.
     *
     * @param controlId the ID of the control definition to evaluate
     * @param runId     the workflow run ID providing data context
     * @param data      the data values to evaluate the control expression against
     * @return the immutable attestation record
     */
    ControlAttestation evaluateControl(String controlId, String runId, Map<String, Object> data);

    /**
     * Returns all control attestation records produced during a workflow run.
     *
     * @param runId the workflow run ID
     * @return an ordered list of {@link ControlAttestation} records
     */
    List<ControlAttestation> getControlResults(String runId);

    /**
     * Creates a new control definition at runtime.
     */
    ControlDefinition createControl(ControlDefinition control);

    /**
     * Returns all registered control definitions.
     */
    List<ControlDefinition> listControls();

    /**
     * Retrieves a single control definition by ID.
     */
    ControlDefinition getControl(String controlId);

    /**
     * Evaluates a SpEL expression against a context map without requiring a saved ControlDefinition.
     *
     * @param expression the SpEL expression string
     * @param context    variable bindings for the evaluation context
     * @return the evaluation result including the value, type, and any error
     */
    SpelEvaluationResult evaluateSpelExpression(String expression, Map<String, Object> context);

    // -------------------------------------------------------------------------
    // Ingestion
    // -------------------------------------------------------------------------

    /**
     * Creates a submission manifest for a new batch of incoming data files.
     *
     * @param manifest the manifest to persist
     * @return the created manifest with a server-assigned ID
     */
    SubmissionManifest createManifest(SubmissionManifest manifest);

    /**
     * Records a human assertion that a specific file is the authoritative version
     * for a given submission cycle, resolving the "v3_FINAL_v2" ambiguity problem.
     *
     * @param manifestId  the manifest to update
     * @param fileId      the file ID being declared authoritative
     * @param assertedBy  the person making the assertion (name or user ID)
     * @return the updated manifest with the authoritative file recorded
     */
    SubmissionManifest assertAuthoritativeVersion(String manifestId, String fileId, String assertedBy);

    /**
     * Merges key-value data into the accumulated run data map of a workflow run.
     * Use this to store agent outputs, generated code artifacts, or any intermediate
     * results that other steps or tools should be able to access.
     *
     * @param runId the workflow run ID
     * @param data  key-value pairs to merge into the run's data map
     * @return the updated {@link WorkflowRun}
     */
    WorkflowRun updateRunData(String runId, Map<String, Object> data);

    // ════════════════ Excel Formula Conversion ════════════════

    /**
     * Converts Excel spreadsheet formulas to executable code via LLM.
     * Returns the generated code as an artifact for review and editing
     * without executing it.
     *
     * @param spreadsheetGraphJson  the SpreadsheetGraph JSON
     * @param targetLanguage        "javascript" or "python"
     * @return map containing "code", "language", "inputCells", "outputCells", metadata
     */
    Map<String, Object> convertExcelFormulas(String spreadsheetGraphJson, String targetLanguage);

    /**
     * Executes Excel spreadsheet computation. If generatedCode is provided,
     * it runs that code directly (user-edited artifact). Otherwise, converts
     * via LLM first. Result always includes _generatedCode for persistence.
     *
     * @param spreadsheetGraphJson  the SpreadsheetGraph JSON
     * @param cellOverrides         input cell value overrides
     * @param targetLanguage        "javascript" or "python"
     * @param generatedCode         user-supplied code (null to trigger LLM conversion)
     * @return map of computed outputs including _generatedCode artifact
     */
    Map<String, Object> executeExcelFormulas(String spreadsheetGraphJson,
                                              Map<String, Object> cellOverrides,
                                              String targetLanguage,
                                              String generatedCode);
}
