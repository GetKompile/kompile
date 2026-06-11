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
import ai.kompile.process.execution.WorkflowRun;
import ai.kompile.process.hitl.ApprovalAction;
import ai.kompile.process.hitl.ApprovalRequest;
import ai.kompile.process.hitl.ApprovalResponse;
import ai.kompile.process.ingest.SubmissionManifest;
import ai.kompile.process.ontology.OntologySchema;
import ai.kompile.process.ontology.ValidationRule;
import ai.kompile.process.service.ProcessEngineService;
import ai.kompile.process.service.SpelEvaluationResult;
import ai.kompile.process.workflow.ProcessDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP tools for managing and operating the Kompile process engine.
 * Exposes ontology management, process definitions, workflow runs,
 * human-in-the-loop approval gates, control evaluation, and submission
 * manifests as LLM-accessible Spring AI tools.
 */
@Component
public class ProcessEngineTool {

    private static final Logger log = LoggerFactory.getLogger(ProcessEngineTool.class);

    private final ProcessEngineService processEngineService;

    public ProcessEngineTool(ProcessEngineService processEngineService) {
        this.processEngineService = processEngineService;
    }

    // ---- Input Records ----

    public record ListOntologiesInput() {}

    public record GetOntologyInput(String id, int version) {}

    public record CreateOntologyInput(String name, String templateId) {}

    public record ValidateDataInput(
            String ontologyId,
            int version,
            String entityType,
            Map<String, Object> data) {}

    public record ListDefinitionsInput() {}

    public record GetDefinitionInput(String id, int version) {}

    public record StartRunInput(
            String processDefinitionId,
            Map<String, Object> initialData) {}

    public record GetRunInput(String runId) {}

    public record ListActiveRunsInput() {}

    public record GetPendingApprovalsInput(String assignedTo) {}

    public record SubmitApprovalInput(
            String requestId,
            String action,
            String respondedBy,
            String comment,
            String delegateTo) {}

    public record EvaluateControlInput(
            String controlId,
            String runId,
            Map<String, Object> data) {}

    public record CreateManifestInput(
            String workflowRunId,
            String sourceRegion,
            String authoritativeFileName) {}

    public record CreateDefinitionInput(
            String name,
            String ontologySchemaId,
            int ontologyVersion) {}

    public record ApproveDefinitionInput(
            String id,
            String approvedBy) {}

    public record CancelRunInput(String runId) {}

    public record ListAllRunsInput() {}

    public record CompleteHumanStepInput(
            String runId,
            String stepId,
            String completedBy,
            Map<String, Object> outputs) {}

    public record ListControlsInput() {}

    public record GetControlResultsInput(String runId) {}

    public record ConvertExcelFormulasInput(
            String spreadsheetGraphJson,
            String targetLanguage) {}

    public record ExecuteExcelFormulasInput(
            String spreadsheetGraphJson,
            Map<String, Object> cellOverrides,
            String targetLanguage,
            String generatedCode) {}

    public record GetRunStepsInput(String runId) {}

    public record GetRunContextInput(String runId) {}

    public record GetStepResultInput(String runId, String stepId) {}

    public record EvaluateExpressionInput(
            String expression,
            Map<String, Object> context) {}

    public record UpdateRunDataInput(
            String runId,
            Map<String, Object> data) {}

    // ---- Tool Methods ----

    @Tool(name = "process_list_ontologies",
          description = "Lists all ontology schemas registered in the process engine. " +
                  "Returns a summary of each schema including its ID, name, version, and template.")
    public Map<String, Object> listOntologies(ListOntologiesInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<OntologySchema> schemas = processEngineService.listOntologies();
            List<Map<String, Object>> items = schemas.stream().map(schema -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", schema.getId());
                item.put("name", schema.getName());
                item.put("version", schema.getVersion());
                item.put("templateId", schema.getTemplateId());
                item.put("entityTypeCount", schema.getEntityTypes() != null ? schema.getEntityTypes().size() : 0);
                item.put("createdAt", schema.getCreatedAt() != null ? schema.getCreatedAt().toString() : null);
                return item;
            }).collect(Collectors.toList());
            result.put("ontologies", items);
            result.put("count", items.size());
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error listing ontologies", e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_get_ontology",
          description = "Retrieves a specific version of an ontology schema by its ID. " +
                  "Returns the full schema including entity types, relationship types, " +
                  "validation rules, and metadata. id: the ontology schema ID. " +
                  "version: the version number to retrieve.")
    public Map<String, Object> getOntology(GetOntologyInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            OntologySchema schema = processEngineService.getOntology(input.id(), input.version());
            result.put("id", schema.getId());
            result.put("name", schema.getName());
            result.put("version", schema.getVersion());
            result.put("templateId", schema.getTemplateId());
            result.put("createdAt", schema.getCreatedAt() != null ? schema.getCreatedAt().toString() : null);
            result.put("updatedAt", schema.getUpdatedAt() != null ? schema.getUpdatedAt().toString() : null);
            result.put("updatedBy", schema.getUpdatedBy());
            result.put("entityTypeCount", schema.getEntityTypes() != null ? schema.getEntityTypes().size() : 0);
            result.put("relationshipTypeCount", schema.getRelationshipTypes() != null ? schema.getRelationshipTypes().size() : 0);
            result.put("globalRuleCount", schema.getGlobalRules() != null ? schema.getGlobalRules().size() : 0);
            result.put("metadata", schema.getMetadata());
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error retrieving ontology id={} version={}", input.id(), input.version(), e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_create_ontology",
          description = "Creates a new ontology schema with the given name and optional template ID. " +
                  "Returns the created schema with a server-assigned ID at version 1. " +
                  "name: a human-readable name for the ontology (e.g. 'FP&A CPG Channel'). " +
                  "templateId: optional cross-customer template this schema derives from.")
    public Map<String, Object> createOntology(CreateOntologyInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            OntologySchema schema = OntologySchema.builder()
                    .name(input.name())
                    .templateId(input.templateId())
                    .build();
            OntologySchema created = processEngineService.createOntology(schema);
            result.put("id", created.getId());
            result.put("name", created.getName());
            result.put("version", created.getVersion());
            result.put("templateId", created.getTemplateId());
            result.put("createdAt", created.getCreatedAt() != null ? created.getCreatedAt().toString() : null);
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error creating ontology name={}", input.name(), e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_validate_data",
          description = "Validates a data map against the rules defined for an entity type in a specific " +
                  "ontology version. Returns a list of violated rules (empty list means all rules passed). " +
                  "ontologyId: the ontology schema ID. version: the ontology version to use. " +
                  "entityType: the entity type name whose rules to evaluate. " +
                  "data: key-value map of the data to validate.")
    public Map<String, Object> validateData(ValidateDataInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<ValidationRule> violations = processEngineService.validateData(
                    input.ontologyId(), input.version(), input.entityType(), input.data());
            List<Map<String, Object>> violationList = violations.stream().map(rule -> {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("ruleId", rule.getId());
                v.put("name", rule.getName());
                v.put("description", rule.getDescription());
                v.put("ruleType", rule.getRuleType() != null ? rule.getRuleType().name() : null);
                v.put("severity", rule.getSeverity() != null ? rule.getSeverity().name() : null);
                v.put("expression", rule.getExpression());
                v.put("onViolation", rule.getOnViolation());
                v.put("escalateTo", rule.getEscalateTo());
                return v;
            }).collect(Collectors.toList());
            result.put("violations", violationList);
            result.put("violationCount", violationList.size());
            result.put("valid", violationList.isEmpty());
            result.put("ontologyId", input.ontologyId());
            result.put("version", input.version());
            result.put("entityType", input.entityType());
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error validating data ontologyId={} entityType={}", input.ontologyId(), input.entityType(), e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_list_definitions",
          description = "Lists all process definitions registered in the process engine. " +
                  "Returns a summary of each definition including its ID, name, version, status, and phase count.")
    public Map<String, Object> listDefinitions(ListDefinitionsInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<ProcessDefinition> defs = processEngineService.listProcessDefinitions();
            List<Map<String, Object>> items = defs.stream().map(def -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", def.getId());
                item.put("name", def.getName());
                item.put("version", def.getVersion());
                item.put("processStatus", def.getStatus() != null ? def.getStatus().name() : null);
                item.put("ontologySchemaId", def.getOntologySchemaId());
                item.put("phaseCount", def.getPhases() != null ? def.getPhases().size() : 0);
                item.put("approvedBy", def.getApprovedBy());
                return item;
            }).collect(Collectors.toList());
            result.put("definitions", items);
            result.put("count", items.size());
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error listing process definitions", e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_get_definition",
          description = "Retrieves a specific version of a process definition by its ID. " +
                  "Returns phases, controls, agent specs, approval status, and metadata. " +
                  "id: the process definition ID. version: the version number to retrieve.")
    public Map<String, Object> getDefinition(GetDefinitionInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            ProcessDefinition definition = processEngineService.getProcess(input.id(), input.version());
            result.put("id", definition.getId());
            result.put("name", definition.getName());
            result.put("version", definition.getVersion());
            result.put("ontologySchemaId", definition.getOntologySchemaId());
            result.put("ontologyVersion", definition.getOntologyVersion());
            result.put("processStatus", definition.getStatus() != null ? definition.getStatus().name() : null);
            result.put("approvedBy", definition.getApprovedBy());
            result.put("approvedAt", definition.getApprovedAt() != null ? definition.getApprovedAt().toString() : null);
            result.put("phaseCount", definition.getPhases() != null ? definition.getPhases().size() : 0);
            result.put("controlCount", definition.getControls() != null ? definition.getControls().size() : 0);
            result.put("agentSpecs", definition.getAgentSpecs());
            result.put("metadata", definition.getMetadata());
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error retrieving process definition id={} version={}", input.id(), input.version(), e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_start_run",
          description = "Starts a new workflow run for an approved process definition. " +
                  "The process definition must have APPROVED status before a run can be started. " +
                  "processDefinitionId: the ID of the approved process to execute. " +
                  "initialData: optional key-value seed data injected into the run before execution begins.")
    public Map<String, Object> startRun(StartRunInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Map<String, Object> initialData = input.initialData() != null ? input.initialData() : Map.of();
            WorkflowRun run = processEngineService.startRun(input.processDefinitionId(), initialData);
            result.put("runId", run.getId());
            result.put("processDefinitionId", run.getProcessDefinitionId());
            result.put("processVersion", run.getProcessVersion());
            result.put("ontologySnapshotId", run.getOntologySnapshotId());
            result.put("runStatus", run.getStatus() != null ? run.getStatus().name() : null);
            result.put("startedAt", run.getStartedAt() != null ? run.getStartedAt().toString() : null);
            result.put("estimatedCompletion", run.getEstimatedCompletion() != null ? run.getEstimatedCompletion().toString() : null);
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error starting workflow run for processDefinitionId={}", input.processDefinitionId(), e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_get_run",
          description = "Retrieves the current state of a workflow run, including step executions, " +
                  "pending approvals, control results, and accumulated run data. " +
                  "runId: the workflow run ID.")
    public Map<String, Object> getRun(GetRunInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            WorkflowRun run = processEngineService.getRun(input.runId());
            result.put("runId", run.getId());
            result.put("processDefinitionId", run.getProcessDefinitionId());
            result.put("processVersion", run.getProcessVersion());
            result.put("ontologySnapshotId", run.getOntologySnapshotId());
            result.put("runStatus", run.getStatus() != null ? run.getStatus().name() : null);
            result.put("startedAt", run.getStartedAt() != null ? run.getStartedAt().toString() : null);
            result.put("completedAt", run.getCompletedAt() != null ? run.getCompletedAt().toString() : null);
            result.put("estimatedCompletion", run.getEstimatedCompletion() != null ? run.getEstimatedCompletion().toString() : null);
            result.put("stepExecutionCount", run.getStepExecutions() != null ? run.getStepExecutions().size() : 0);
            result.put("pendingApprovalCount", run.getPendingApprovals() != null ? run.getPendingApprovals().size() : 0);
            result.put("controlResultCount", run.getControlResults() != null ? run.getControlResults().size() : 0);
            result.put("runData", run.getRunData());
            result.put("metrics", run.getMetrics());
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error retrieving workflow run id={}", input.runId(), e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_list_active_runs",
          description = "Lists all workflow runs that are currently in an active state " +
                  "(RUNNING, PAUSED_FOR_APPROVAL, or PAUSED_FOR_HUMAN). " +
                  "Returns run IDs, process definition IDs, statuses, and start times.")
    public Map<String, Object> listActiveRuns(ListActiveRunsInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<WorkflowRun> runs = processEngineService.listActiveRuns();
            List<Map<String, Object>> items = runs.stream().map(run -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("runId", run.getId());
                item.put("processDefinitionId", run.getProcessDefinitionId());
                item.put("processVersion", run.getProcessVersion());
                item.put("runStatus", run.getStatus() != null ? run.getStatus().name() : null);
                item.put("startedAt", run.getStartedAt() != null ? run.getStartedAt().toString() : null);
                item.put("estimatedCompletion", run.getEstimatedCompletion() != null ? run.getEstimatedCompletion().toString() : null);
                item.put("pendingApprovalCount", run.getPendingApprovals() != null ? run.getPendingApprovals().size() : 0);
                return item;
            }).collect(Collectors.toList());
            result.put("runs", items);
            result.put("count", items.size());
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error listing active workflow runs", e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_get_pending_approvals",
          description = "Returns all pending approval requests assigned to a specific person or role. " +
                  "Use this to surface the human-in-the-loop exception queue for a reviewer. " +
                  "assignedTo: the person ID or role name to filter by (e.g. 'finance-controller', 'alice@example.com').")
    public Map<String, Object> getPendingApprovals(GetPendingApprovalsInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<ApprovalRequest> requests = processEngineService.getPendingApprovals(input.assignedTo());
            List<Map<String, Object>> items = requests.stream().map(req -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("requestId", req.getId());
                item.put("workflowRunId", req.getWorkflowRunId());
                item.put("stepId", req.getStepId());
                item.put("stepName", req.getStepName());
                item.put("approvalStatus", req.getStatus() != null ? req.getStatus().name() : null);
                item.put("assignedTo", req.getAssignedTo());
                item.put("createdAt", req.getCreatedAt() != null ? req.getCreatedAt().toString() : null);
                item.put("slaDeadline", req.getSlaDeadline() != null ? req.getSlaDeadline().toString() : null);
                item.put("itemCount", req.getItems() != null ? req.getItems().size() : 0);
                return item;
            }).collect(Collectors.toList());
            result.put("approvals", items);
            result.put("count", items.size());
            result.put("assignedTo", input.assignedTo());
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error retrieving pending approvals for assignedTo={}", input.assignedTo(), e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_submit_approval",
          description = "Submits a human response to a pending approval request and resumes the associated " +
                  "workflow run. Returns the updated run state after processing the response. " +
                  "requestId: the approval request ID. " +
                  "action: one of APPROVE, APPROVE_WITH_EDITS, REJECT, ESCALATE, DELEGATE, REQUEST_INFO. " +
                  "respondedBy: person ID or name submitting the response. " +
                  "comment: optional free-text justification or explanation. " +
                  "delegateTo: required when action is DELEGATE — the target person or role identifier.")
    public Map<String, Object> submitApproval(SubmitApprovalInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            ApprovalAction action = ApprovalAction.valueOf(input.action().toUpperCase());
            ApprovalResponse response = ApprovalResponse.builder()
                    .requestId(input.requestId())
                    .respondedBy(input.respondedBy())
                    .action(action)
                    .comment(input.comment())
                    .delegateTo(input.delegateTo())
                    .build();
            WorkflowRun run = processEngineService.submitApproval(response);
            result.put("runId", run.getId());
            result.put("processDefinitionId", run.getProcessDefinitionId());
            result.put("runStatus", run.getStatus() != null ? run.getStatus().name() : null);
            result.put("pendingApprovalCount", run.getPendingApprovals() != null ? run.getPendingApprovals().size() : 0);
            result.put("requestId", input.requestId());
            result.put("action", action.name());
            result.put("status", "success");
        } catch (IllegalArgumentException e) {
            result.put("status", "error");
            result.put("error", "Invalid action '" + input.action() + "'. Valid values: APPROVE, APPROVE_WITH_EDITS, REJECT, ESCALATE, DELEGATE, REQUEST_INFO.");
        } catch (Exception e) {
            log.error("Error submitting approval for requestId={}", input.requestId(), e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_evaluate_control",
          description = "Evaluates a control gate assertion against data values from a workflow run. " +
                  "Always produces an immutable attestation record for the SOX audit trail, " +
                  "regardless of whether the control passes or fails. " +
                  "controlId: the control definition ID to evaluate. " +
                  "runId: the workflow run ID providing data context. " +
                  "data: key-value data values to evaluate the control expression against.")
    public Map<String, Object> evaluateControl(EvaluateControlInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            ControlAttestation attestation = processEngineService.evaluateControl(
                    input.controlId(), input.runId(), input.data());
            result.put("attestationId", attestation.getId());
            result.put("controlId", attestation.getControlId());
            result.put("workflowRunId", attestation.getWorkflowRunId());
            result.put("stepId", attestation.getStepId());
            result.put("passed", attestation.isPassed());
            result.put("expressionEvaluated", attestation.getExpressionEvaluated());
            result.put("evaluatedAt", attestation.getEvaluatedAt() != null ? attestation.getEvaluatedAt().toString() : null);
            result.put("evaluatedBy", attestation.getEvaluatedBy());
            result.put("inputHash", attestation.getInputHash());
            result.put("outputHash", attestation.getOutputHash());
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error evaluating control controlId={} runId={}", input.controlId(), input.runId(), e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_create_manifest",
          description = "Creates a submission manifest for a new batch of incoming data files, " +
                  "recording which file version is authoritative for this submission cycle. " +
                  "This resolves the 'v3_FINAL_v2' naming ambiguity problem. " +
                  "workflowRunId: the workflow run this manifest belongs to. " +
                  "sourceRegion: the submitting region identifier (e.g. 'AMER', 'EMEA', 'APAC'). " +
                  "authoritativeFileName: the declared canonical file name for this submission cycle.")
    public Map<String, Object> createManifest(CreateManifestInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            SubmissionManifest manifest = SubmissionManifest.builder()
                    .workflowRunId(input.workflowRunId())
                    .sourceRegion(input.sourceRegion())
                    .authoritativeFileName(input.authoritativeFileName())
                    .build();
            SubmissionManifest created = processEngineService.createManifest(manifest);
            result.put("manifestId", created.getId());
            result.put("workflowRunId", created.getWorkflowRunId());
            result.put("sourceRegion", created.getSourceRegion());
            result.put("authoritativeFileId", created.getAuthoritativeFileId());
            result.put("authoritativeFileName", created.getAuthoritativeFileName());
            result.put("fileContentHash", created.getFileContentHash());
            result.put("versionAssertedBy", created.getVersionAssertedBy());
            result.put("receivedAt", created.getReceivedAt() != null ? created.getReceivedAt().toString() : null);
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error creating submission manifest for runId={}", input.workflowRunId(), e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    // ---- Process Definition Lifecycle ----

    @Tool(name = "process_create_definition",
          description = "Creates a new process definition in DRAFT status. " +
                  "The definition can later be reviewed and approved before workflow runs can be started. " +
                  "name: a human-readable name for the process (e.g. 'Monthly FP&A Close'). " +
                  "ontologySchemaId: the ontology schema this process is bound to. " +
                  "ontologyVersion: the version of the ontology to bind to.")
    public Map<String, Object> createDefinition(CreateDefinitionInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            ProcessDefinition definition = ProcessDefinition.builder()
                    .name(input.name())
                    .ontologySchemaId(input.ontologySchemaId())
                    .ontologyVersion(input.ontologyVersion())
                    .build();
            ProcessDefinition created = processEngineService.createProcess(definition);
            result.put("id", created.getId());
            result.put("name", created.getName());
            result.put("version", created.getVersion());
            result.put("processStatus", created.getStatus() != null ? created.getStatus().name() : null);
            result.put("ontologySchemaId", created.getOntologySchemaId());
            result.put("ontologyVersion", created.getOntologyVersion());
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error creating process definition name={}", input.name(), e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_approve_definition",
          description = "Transitions a process definition from IN_REVIEW to APPROVED status. " +
                  "Only approved definitions may be used to start workflow runs. " +
                  "id: the process definition ID. " +
                  "approvedBy: identifier of the person granting approval (e.g. email or name).")
    public Map<String, Object> approveDefinition(ApproveDefinitionInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            ProcessDefinition approved = processEngineService.approveProcess(input.id(), input.approvedBy());
            result.put("id", approved.getId());
            result.put("name", approved.getName());
            result.put("version", approved.getVersion());
            result.put("processStatus", approved.getStatus() != null ? approved.getStatus().name() : null);
            result.put("approvedBy", approved.getApprovedBy());
            result.put("approvedAt", approved.getApprovedAt() != null ? approved.getApprovedAt().toString() : null);
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error approving process definition id={}", input.id(), e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    // ---- Run Lifecycle ----

    @Tool(name = "process_cancel_run",
          description = "Cancels a running or paused workflow run, setting its status to CANCELLED. " +
                  "This is irreversible — a cancelled run cannot be resumed. " +
                  "runId: the workflow run ID to cancel.")
    public Map<String, Object> cancelRun(CancelRunInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            WorkflowRun cancelled = processEngineService.cancelRun(input.runId());
            result.put("runId", cancelled.getId());
            result.put("processDefinitionId", cancelled.getProcessDefinitionId());
            result.put("runStatus", cancelled.getStatus() != null ? cancelled.getStatus().name() : null);
            result.put("completedAt", cancelled.getCompletedAt() != null ? cancelled.getCompletedAt().toString() : null);
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error cancelling workflow run id={}", input.runId(), e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_list_all_runs",
          description = "Lists all workflow runs regardless of status (RUNNING, COMPLETED, CANCELLED, etc.). " +
                  "Returns run IDs, statuses, process definition IDs, and timestamps.")
    public Map<String, Object> listAllRuns(ListAllRunsInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<WorkflowRun> runs = processEngineService.listAllRuns();
            List<Map<String, Object>> items = runs.stream().map(run -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("runId", run.getId());
                item.put("processDefinitionId", run.getProcessDefinitionId());
                item.put("processVersion", run.getProcessVersion());
                item.put("runStatus", run.getStatus() != null ? run.getStatus().name() : null);
                item.put("startedAt", run.getStartedAt() != null ? run.getStartedAt().toString() : null);
                item.put("completedAt", run.getCompletedAt() != null ? run.getCompletedAt().toString() : null);
                return item;
            }).collect(Collectors.toList());
            result.put("runs", items);
            result.put("count", items.size());
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error listing all workflow runs", e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_complete_human_step",
          description = "Completes a HUMAN-type step that is waiting for manual execution, " +
                  "advancing the workflow past the paused step. " +
                  "runId: the workflow run ID. " +
                  "stepId: the step ID to complete. " +
                  "completedBy: identifier of who completed the step. " +
                  "outputs: key-value map of outputs produced by the human for this step.")
    public Map<String, Object> completeHumanStep(CompleteHumanStepInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Map<String, Object> outputs = input.outputs() != null ? input.outputs() : Map.of();
            WorkflowRun run = processEngineService.completeHumanStep(
                    input.runId(), input.stepId(), input.completedBy(), outputs);
            result.put("runId", run.getId());
            result.put("processDefinitionId", run.getProcessDefinitionId());
            result.put("runStatus", run.getStatus() != null ? run.getStatus().name() : null);
            result.put("stepExecutionCount", run.getStepExecutions() != null ? run.getStepExecutions().size() : 0);
            result.put("pendingApprovalCount", run.getPendingApprovals() != null ? run.getPendingApprovals().size() : 0);
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error completing human step runId={} stepId={}", input.runId(), input.stepId(), e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    // ---- Controls ----

    @Tool(name = "process_list_controls",
          description = "Lists all registered control definitions. " +
                  "Controls are formal SOX/J-SOX-compatible assertions that fire at defined " +
                  "points in workflow execution and produce immutable attestation records.")
    public Map<String, Object> listControls(ListControlsInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<ControlDefinition> controls = processEngineService.listControls();
            List<Map<String, Object>> items = controls.stream().map(ctrl -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", ctrl.getId());
                item.put("name", ctrl.getName());
                item.put("description", ctrl.getDescription());
                item.put("gateType", ctrl.getGateType() != null ? ctrl.getGateType().name() : null);
                item.put("expression", ctrl.getExpression());
                item.put("triggerAfterStep", ctrl.getTriggerAfterStep());
                item.put("severity", ctrl.getSeverity() != null ? ctrl.getSeverity().name() : null);
                item.put("regulatoryReference", ctrl.getRegulatoryReference());
                return item;
            }).collect(Collectors.toList());
            result.put("controls", items);
            result.put("count", items.size());
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error listing controls", e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_get_control_results",
          description = "Returns all control attestation records produced during a workflow run. " +
                  "Each attestation records whether a control passed or failed, " +
                  "the expression evaluated, input/output hashes, and who/when. " +
                  "runId: the workflow run ID.")
    public Map<String, Object> getControlResults(GetControlResultsInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<ControlAttestation> attestations = processEngineService.getControlResults(input.runId());
            List<Map<String, Object>> items = attestations.stream().map(att -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("attestationId", att.getId());
                item.put("controlId", att.getControlId());
                item.put("stepId", att.getStepId());
                item.put("passed", att.isPassed());
                item.put("expressionEvaluated", att.getExpressionEvaluated());
                item.put("evaluatedAt", att.getEvaluatedAt() != null ? att.getEvaluatedAt().toString() : null);
                item.put("evaluatedBy", att.getEvaluatedBy());
                item.put("inputHash", att.getInputHash());
                item.put("outputHash", att.getOutputHash());
                return item;
            }).collect(Collectors.toList());
            result.put("runId", input.runId());
            result.put("attestations", items);
            result.put("count", items.size());
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error getting control results for runId={}", input.runId(), e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    // ---- Excel Formula Tools ----

    @Tool(name = "process_convert_excel_formulas",
          description = "Converts Excel spreadsheet formulas to executable code (JavaScript or Python) via LLM. " +
                  "Returns the generated code as an artifact for review and editing without executing it. " +
                  "spreadsheetGraphJson: the SpreadsheetGraph JSON extracted from the knowledge graph. " +
                  "targetLanguage: 'javascript' or 'python'.")
    public Map<String, Object> convertExcelFormulas(ConvertExcelFormulasInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Map<String, Object> conversionResult = processEngineService.convertExcelFormulas(
                    input.spreadsheetGraphJson(), input.targetLanguage());
            result.putAll(conversionResult);
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error converting Excel formulas", e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_execute_excel_formulas",
          description = "Executes Excel spreadsheet computation. If generatedCode is provided, " +
                  "runs that code directly (user-edited artifact). Otherwise converts via LLM first. " +
                  "spreadsheetGraphJson: the SpreadsheetGraph JSON. " +
                  "cellOverrides: optional input cell value overrides (e.g. {\"A1\": 100}). " +
                  "targetLanguage: 'javascript' or 'python'. " +
                  "generatedCode: optional user-supplied code (null to trigger LLM conversion).")
    public Map<String, Object> executeExcelFormulas(ExecuteExcelFormulasInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Map<String, Object> execResult = processEngineService.executeExcelFormulas(
                    input.spreadsheetGraphJson(),
                    input.cellOverrides(),
                    input.targetLanguage(),
                    input.generatedCode());
            result.putAll(execResult);
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error executing Excel formulas", e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    // ---- Run Step Detail & Context ----

    @Tool(name = "process_get_run_steps",
          description = "Returns detailed step execution records for a workflow run, including each step's " +
                  "status, inputs, outputs, hashes, timing, evidence relied on, and errors. " +
                  "Use this to inspect what happened at each step of a process execution. " +
                  "runId: the workflow run ID.")
    public Map<String, Object> getRunSteps(GetRunStepsInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            WorkflowRun run = processEngineService.getRun(input.runId());
            List<Map<String, Object>> steps = run.getStepExecutions() != null
                    ? run.getStepExecutions().stream().map(this::serializeStepExecution).collect(Collectors.toList())
                    : List.of();
            result.put("runId", run.getId());
            result.put("runStatus", run.getStatus() != null ? run.getStatus().name() : null);
            result.put("steps", steps);
            result.put("stepCount", steps.size());
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error getting run steps for runId={}", input.runId(), e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_get_run_context",
          description = "Returns the accumulated data context (runData) for a workflow run. " +
                  "runData is a shared key-value map that steps read from and write to as " +
                  "the workflow progresses. Also returns graph node IDs and metrics. " +
                  "runId: the workflow run ID.")
    public Map<String, Object> getRunContext(GetRunContextInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            WorkflowRun run = processEngineService.getRun(input.runId());
            result.put("runId", run.getId());
            result.put("runStatus", run.getStatus() != null ? run.getStatus().name() : null);
            result.put("runData", run.getRunData() != null ? run.getRunData() : Map.of());
            result.put("graphNodeIds", run.getGraphNodeIds() != null ? run.getGraphNodeIds() : List.of());
            result.put("metrics", run.getMetrics() != null ? run.getMetrics() : Map.of());
            result.put("stepCount", run.getStepExecutions() != null ? run.getStepExecutions().size() : 0);
            result.put("startedAt", run.getStartedAt() != null ? run.getStartedAt().toString() : null);
            result.put("completedAt", run.getCompletedAt() != null ? run.getCompletedAt().toString() : null);
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error getting run context for runId={}", input.runId(), e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_update_run_data",
          description = "Merges key-value data into the accumulated run data map of a workflow run. " +
                  "Use this to store generated code artifacts, agent outputs, intermediate results, " +
                  "or any data that other steps or tools should be able to access. " +
                  "runId: the workflow run ID. " +
                  "data: key-value pairs to merge into the run's data map (e.g. {\"_generatedCode\": \"...\", \"result\": 42}).")
    public Map<String, Object> updateRunData(UpdateRunDataInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            WorkflowRun updated = processEngineService.updateRunData(input.runId(), input.data());
            result.put("runId", updated.getId());
            result.put("runStatus", updated.getStatus() != null ? updated.getStatus().name() : null);
            result.put("runDataKeys", updated.getRunData() != null ? updated.getRunData().keySet() : List.of());
            result.put("runDataSize", updated.getRunData() != null ? updated.getRunData().size() : 0);
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error updating run data for runId={}", input.runId(), e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Tool(name = "process_get_step_result",
          description = "Returns the detailed execution record for a single step in a workflow run, " +
                  "including its inputs, outputs, hashes, timing, and error details. " +
                  "runId: the workflow run ID. stepId: the step ID to query.")
    public Map<String, Object> getStepResult(GetStepResultInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            WorkflowRun run = processEngineService.getRun(input.runId());
            if (run.getStepExecutions() == null) {
                result.put("error", "No step executions found for run: " + input.runId());
                result.put("status", "error");
                return result;
            }
            var stepOpt = run.getStepExecutions().stream()
                    .filter(s -> input.stepId().equals(s.getStepId()))
                    .findFirst();
            if (stepOpt.isEmpty()) {
                result.put("error", "Step not found: " + input.stepId() + " in run: " + input.runId());
                result.put("status", "error");
                return result;
            }
            result.put("runId", run.getId());
            result.put("step", serializeStepExecution(stepOpt.get()));
            result.put("status", "success");
        } catch (Exception e) {
            log.error("Error getting step result runId={} stepId={}", input.runId(), input.stepId(), e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    // ---- Expression Evaluation ----

    @Tool(name = "process_evaluate_expression",
          description = "Evaluates a SpEL (Spring Expression Language) expression against a context map. " +
                  "Useful for testing control assertions, computing derived values, or validating data. " +
                  "expression: the SpEL expression string (e.g. '#total > 0', '#revenue - #costs'). " +
                  "context: key-value map of variable bindings (e.g. {\"total\": 42, \"costs\": 10}).")
    public Map<String, Object> evaluateExpression(EvaluateExpressionInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            SpelEvaluationResult evalResult = processEngineService.evaluateSpelExpression(
                    input.expression(), input.context() != null ? input.context() : Map.of());
            result.put("expression", input.expression());
            result.put("result", evalResult.getResult());
            result.put("resultType", evalResult.getType());
            if (evalResult.getError() != null) {
                result.put("evaluationError", evalResult.getError());
            }
            result.put("status", evalResult.getError() == null ? "success" : "error");
        } catch (Exception e) {
            log.error("Error evaluating expression: {}", input.expression(), e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
        return result;
    }

    // ---- Helpers ----

    private Map<String, Object> serializeStepExecution(
            ai.kompile.process.execution.StepExecution step) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("stepId", step.getStepId());
        item.put("stepName", step.getStepName());
        item.put("stepStatus", step.getStatus() != null ? step.getStatus().name() : null);
        item.put("startedAt", step.getStartedAt() != null ? step.getStartedAt().toString() : null);
        item.put("completedAt", step.getCompletedAt() != null ? step.getCompletedAt().toString() : null);
        item.put("executedBy", step.getExecutedBy());
        item.put("inputs", step.getInputs());
        item.put("outputs", step.getOutputs());
        item.put("inputHash", step.getInputHash());
        item.put("outputHash", step.getOutputHash());
        item.put("evidenceReliedOn", step.getEvidenceReliedOn());
        item.put("graphNodeIds", step.getGraphNodeIds());
        if (step.getError() != null) {
            item.put("error", step.getError());
        }
        return item;
    }
}
