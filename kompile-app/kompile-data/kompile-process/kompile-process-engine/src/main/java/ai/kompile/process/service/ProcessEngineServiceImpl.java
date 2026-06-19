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
import ai.kompile.process.controls.ControlGateType;
import ai.kompile.process.execution.RunStatus;
import ai.kompile.process.execution.StepExecution;
import ai.kompile.process.execution.StepExecutionStatus;
import ai.kompile.process.execution.WorkflowRun;
import ai.kompile.process.hitl.ApprovalAction;
import ai.kompile.process.hitl.ApprovalRequest;
import ai.kompile.process.hitl.ApprovalRequestStatus;
import ai.kompile.process.hitl.ApprovalResponse;
import ai.kompile.process.ingest.SubmissionManifest;
import ai.kompile.process.ontology.EntityTypeDefinition;
import ai.kompile.process.ontology.FieldDefinition;
import ai.kompile.process.ontology.FieldType;
import ai.kompile.process.ontology.OntologySchema;
import ai.kompile.process.ontology.ValidationRule;
import ai.kompile.process.workflow.ProcessDefinition;
import ai.kompile.process.workflow.ProcessPhase;
import ai.kompile.process.workflow.ProcessStatus;
import ai.kompile.process.workflow.ProcessStep;
import ai.kompile.process.workflow.StepType;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Default in-memory implementation of {@link ProcessEngineService} with write-through
 * JSON file persistence to {@code ~/.kompile/processes/}.
 *
 * <p>State is kept in {@link ConcurrentHashMap}s for fast access. Every mutation is
 * immediately flushed to disk so the full state survives restarts. Versioned artefacts
 * (ontologies and process definitions) are stored as {@code <id>_v<version>.json} files;
 * other entities use {@code <id>.json}.
 *
 * <p>The service auto-enables unless the property
 * {@code kompile.process.engine.enabled=false} is explicitly set.
 */
@Service
@ConditionalOnProperty(
        name = "kompile.process.engine.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ProcessEngineServiceImpl implements ProcessEngineService {

    private static final Logger log = LoggerFactory.getLogger(ProcessEngineServiceImpl.class);

    // ---------------------------------------------------------------------------
    // Persistence directories
    // ---------------------------------------------------------------------------

    private Path ontologyDir;
    private Path definitionDir;
    private Path runDir;
    private Path controlDir;
    private Path manifestDir;
    private Path approvalDir;
    private Path attestationDir;

    // ---------------------------------------------------------------------------
    // In-memory stores
    // Key pattern for versioned types: "<id>_v<version>"
    // ---------------------------------------------------------------------------

    /** ontologyKey → OntologySchema */
    private final Map<String, OntologySchema> ontologies = new ConcurrentHashMap<>();
    /** Latest version number per ontology ID */
    private final Map<String, Integer> ontologyVersions = new ConcurrentHashMap<>();

    /** definitionKey → ProcessDefinition */
    private final Map<String, ProcessDefinition> definitions = new ConcurrentHashMap<>();
    /** Latest version number per definition ID */
    private final Map<String, Integer> definitionVersions = new ConcurrentHashMap<>();

    /** runId → WorkflowRun */
    private final Map<String, WorkflowRun> runs = new ConcurrentHashMap<>();

    /** controlId → ControlDefinition */
    private final Map<String, ControlDefinition> controls = new ConcurrentHashMap<>();

    /** manifestId → SubmissionManifest */
    private final Map<String, SubmissionManifest> manifests = new ConcurrentHashMap<>();

    /** approvalRequestId → ApprovalRequest */
    private final Map<String, ApprovalRequest> approvalRequests = new ConcurrentHashMap<>();

    /** attestationId → ControlAttestation */
    private final Map<String, ControlAttestation> attestations = new ConcurrentHashMap<>();

    // ---------------------------------------------------------------------------
    // Infrastructure
    // ---------------------------------------------------------------------------

    private final ObjectMapper objectMapper;
    private final SpelExpressionParser spelParser = new SpelExpressionParser();

    /** Optional dispatcher for TOOL_CALL and HTTP_CALL steps. Null if not wired. */
    private StepExecutionDispatcher stepExecutionDispatcher;

    /** Optional callback for writing execution results to the knowledge graph. */
    private ProcessGraphCallback processGraphCallback;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setStepExecutionDispatcher(StepExecutionDispatcher dispatcher) {
        this.stepExecutionDispatcher = dispatcher;
        if (dispatcher != null) {
            log.info("StepExecutionDispatcher wired — TOOL_CALL and HTTP_CALL steps enabled");
        }
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setProcessGraphCallback(ProcessGraphCallback callback) {
        this.processGraphCallback = callback;
        if (callback != null) {
            log.info("ProcessGraphCallback wired — execution results will be written to KG");
        }
    }

    public ProcessEngineServiceImpl() {
        this.objectMapper = JsonUtils.newStandardMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    // ---------------------------------------------------------------------------
    // Initialisation
    // ---------------------------------------------------------------------------

    @PostConstruct
    public void init() {
        Path base = Paths.get(System.getProperty("user.home"), ".kompile", "processes");
        ontologyDir    = base.resolve("ontologies");
        definitionDir  = base.resolve("definitions");
        runDir         = base.resolve("runs");
        controlDir     = base.resolve("controls");
        manifestDir    = base.resolve("manifests");
        approvalDir    = base.resolve("approvals");
        attestationDir = base.resolve("attestations");

        for (Path dir : List.of(ontologyDir, definitionDir, runDir,
                                controlDir, manifestDir, approvalDir, attestationDir)) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                log.error("Failed to create process-engine directory {}: {}", dir, e.getMessage());
            }
        }

        loadAll();
        log.info("ProcessEngineService initialised. ontologies={} definitions={} runs={} controls={} manifests={} approvals={} attestations={}",
                ontologies.size(), definitions.size(), runs.size(),
                controls.size(), manifests.size(), approvalRequests.size(), attestations.size());
    }

    // ---------------------------------------------------------------------------
    // Ontology management
    // ---------------------------------------------------------------------------

    @Override
    public OntologySchema createOntology(OntologySchema schema) {
        String id = schema.getId() != null ? schema.getId() : UUID.randomUUID().toString();
        Instant now = Instant.now();
        OntologySchema created = OntologySchema.builder()
                .id(id)
                .name(schema.getName())
                .version(1)
                .templateId(schema.getTemplateId())
                .createdAt(now)
                .updatedAt(now)
                .updatedBy(schema.getUpdatedBy())
                .entityTypes(schema.getEntityTypes())
                .relationshipTypes(schema.getRelationshipTypes())
                .globalRules(schema.getGlobalRules())
                .metadata(schema.getMetadata())
                .build();

        String key = versionedKey(id, 1);
        ontologies.put(key, created);
        ontologyVersions.put(id, 1);
        persistOntology(created);
        log.debug("Created ontology id={} version=1", id);
        return created;
    }

    @Override
    public OntologySchema getOntology(String id, int version) {
        String key = versionedKey(id, version);
        OntologySchema schema = ontologies.get(key);
        if (schema == null) {
            throw new IllegalArgumentException("Ontology not found: id=" + id + " version=" + version);
        }
        return schema;
    }

    @Override
    public OntologySchema updateOntology(String id, OntologySchema schema) {
        int currentVersion = ontologyVersions.getOrDefault(id, 0);
        if (currentVersion == 0) {
            throw new IllegalArgumentException("No ontology exists with id=" + id);
        }
        int newVersion = currentVersion + 1;
        Instant now = Instant.now();
        OntologySchema updated = OntologySchema.builder()
                .id(id)
                .name(schema.getName() != null ? schema.getName() : ontologies.get(versionedKey(id, currentVersion)).getName())
                .version(newVersion)
                .templateId(schema.getTemplateId())
                .createdAt(ontologies.get(versionedKey(id, 1)).getCreatedAt())
                .updatedAt(now)
                .updatedBy(schema.getUpdatedBy())
                .entityTypes(schema.getEntityTypes())
                .relationshipTypes(schema.getRelationshipTypes())
                .globalRules(schema.getGlobalRules())
                .metadata(schema.getMetadata())
                .build();

        String key = versionedKey(id, newVersion);
        ontologies.put(key, updated);
        ontologyVersions.put(id, newVersion);
        persistOntology(updated);
        log.debug("Updated ontology id={} newVersion={}", id, newVersion);
        return updated;
    }

    @Override
    public List<ValidationRule> validateData(String ontologyId, int version,
                                             String entityType, Map<String, Object> data) {
        OntologySchema schema = getOntology(ontologyId, version);
        if (schema.getEntityTypes() == null) {
            return Collections.emptyList();
        }

        EntityTypeDefinition entityTypeDef = schema.getEntityTypes().stream()
                .filter(e -> entityType.equals(e.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Entity type '" + entityType + "' not found in ontology " + ontologyId));

        if (entityTypeDef.getRules() == null || entityTypeDef.getRules().isEmpty()) {
            return Collections.emptyList();
        }

        List<ValidationRule> violated = new ArrayList<>();
        for (ValidationRule rule : entityTypeDef.getRules()) {
            if (rule.getExpression() == null || rule.getExpression().isBlank()) {
                continue;
            }
            try {
                boolean passed = evaluateSpelBoolean(rule.getExpression(), data);
                if (!passed) {
                    log.debug("Validation rule '{}' violated for entityType={}", rule.getName(), entityType);
                    violated.add(rule);
                }
            } catch (Exception e) {
                log.warn("Error evaluating validation rule '{}': {}", rule.getName(), e.getMessage());
                // Treat expression errors as violations (fail-safe)
                violated.add(rule);
            }
        }
        return violated;
    }

    @Override
    public List<OntologySchema> listOntologies() {
        return ontologyVersions.entrySet().stream()
                .map(e -> ontologies.get(versionedKey(e.getKey(), e.getValue())))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Override
    public List<ProcessDefinition> listProcessDefinitions() {
        return definitionVersions.entrySet().stream()
                .map(e -> definitions.get(versionedKey(e.getKey(), e.getValue())))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    // ---------------------------------------------------------------------------
    // Process definition
    // ---------------------------------------------------------------------------

    @Override
    public ProcessDefinition createProcess(ProcessDefinition definition) {
        String id = definition.getId() != null ? definition.getId() : UUID.randomUUID().toString();
        ProcessDefinition created = cloneDefinitionWithIdVersionStatus(definition, id, 1, ProcessStatus.DRAFT);

        String key = versionedKey(id, 1);
        definitions.put(key, created);
        definitionVersions.put(id, 1);
        persistDefinition(created);
        log.debug("Created process definition id={} version=1", id);
        return created;
    }

    @Override
    public ProcessDefinition getProcess(String id, int version) {
        String key = versionedKey(id, version);
        ProcessDefinition def = definitions.get(key);
        if (def == null) {
            throw new IllegalArgumentException("Process definition not found: id=" + id + " version=" + version);
        }
        return def;
    }

    @Override
    public ProcessDefinition approveProcess(String id, String approvedBy) {
        int currentVersion = definitionVersions.getOrDefault(id, 0);
        if (currentVersion == 0) {
            throw new IllegalArgumentException("No process definition exists with id=" + id);
        }
        ProcessDefinition existing = getProcess(id, currentVersion);
        int newVersion = currentVersion + 1;

        ProcessDefinition approved = ProcessDefinition.builder()
                .id(id)
                .name(existing.getName())
                .version(newVersion)
                .ontologySchemaId(existing.getOntologySchemaId())
                .ontologyVersion(existing.getOntologyVersion())
                .status(ProcessStatus.APPROVED)
                .approvedBy(approvedBy)
                .approvedAt(Instant.now())
                .phases(existing.getPhases())
                .controls(existing.getControls())
                .agentSpecs(existing.getAgentSpecs())
                .metadata(existing.getMetadata())
                .build();

        String key = versionedKey(id, newVersion);
        definitions.put(key, approved);
        definitionVersions.put(id, newVersion);
        persistDefinition(approved);
        log.debug("Approved process definition id={} newVersion={} approvedBy={}", id, newVersion, approvedBy);
        return approved;
    }

    // ---------------------------------------------------------------------------
    // Workflow execution
    // ---------------------------------------------------------------------------

    @Override
    public WorkflowRun startRun(String processDefinitionId, Map<String, Object> initialData) {
        int version = definitionVersions.getOrDefault(processDefinitionId, 0);
        if (version == 0) {
            throw new IllegalArgumentException("Process definition not found: " + processDefinitionId);
        }
        ProcessDefinition def = getProcess(processDefinitionId, version);
        if (def.getStatus() != ProcessStatus.APPROVED && def.getStatus() != ProcessStatus.LIVE) {
            throw new IllegalStateException(
                    "Process definition " + processDefinitionId + " is not approved (status=" + def.getStatus() + ")");
        }

        String runId = "wf-" + Instant.now().toString().substring(0, 10) + "-" + shortId();
        Instant now = Instant.now();
        Map<String, Object> runData = initialData != null ? new HashMap<>(initialData) : new HashMap<>();

        // Build step executions from every phase/step in definition order
        List<StepExecution> stepExecutions = new ArrayList<>();
        if (def.getPhases() != null) {
            List<ProcessPhase> sortedPhases = def.getPhases().stream()
                    .sorted((a, b) -> Integer.compare(a.getOrder(), b.getOrder()))
                    .collect(Collectors.toList());
            for (ProcessPhase phase : sortedPhases) {
                if (phase.getSteps() == null) continue;
                for (ProcessStep step : phase.getSteps()) {
                    StepExecution se = StepExecution.builder()
                            .stepId(step.getId())
                            .stepName(step.getName())
                            .status(StepExecutionStatus.PENDING)
                            .build();
                    stepExecutions.add(se);
                }
            }
        }

        WorkflowRun run = WorkflowRun.builder()
                .id(runId)
                .processDefinitionId(processDefinitionId)
                .processVersion(version)
                .ontologySnapshotId(def.getOntologySchemaId())
                .status(RunStatus.RUNNING)
                .startedAt(now)
                .stepExecutions(stepExecutions)
                .pendingApprovals(new ArrayList<>())
                .controlResults(new ArrayList<>())
                .runData(runData)
                .metrics(new HashMap<>())
                .build();

        // Advance to execute the first step (or pause if it needs approval)
        run = advanceRun(run, def);

        runs.put(runId, run);
        persistRun(run);
        log.info("Started workflow run id={} processDefinitionId={}", runId, processDefinitionId);
        return run;
    }

    @Override
    public WorkflowRun getRun(String runId) {
        WorkflowRun run = runs.get(runId);
        if (run == null) {
            throw new IllegalArgumentException("Workflow run not found: " + runId);
        }
        return run;
    }

    @Override
    public WorkflowRun resumeAfterApproval(String runId, ApprovalResponse response) {
        return submitApproval(response);
    }

    @Override
    public List<WorkflowRun> listActiveRuns() {
        return runs.values().stream()
                .filter(r -> r.getStatus() == RunStatus.RUNNING
                          || r.getStatus() == RunStatus.PAUSED_FOR_APPROVAL
                          || r.getStatus() == RunStatus.PAUSED_FOR_HUMAN)
                .collect(Collectors.toList());
    }

    @Override
    public List<WorkflowRun> listAllRuns() {
        return new ArrayList<>(runs.values());
    }

    @Override
    public WorkflowRun cancelRun(String runId) {
        WorkflowRun run = getRun(runId);
        if (run.getStatus() == RunStatus.COMPLETED
                || run.getStatus() == RunStatus.FAILED
                || run.getStatus() == RunStatus.CANCELLED) {
            throw new IllegalArgumentException("Cannot cancel a run in terminal state: " + run.getStatus());
        }
        WorkflowRun cancelled = WorkflowRun.builder()
                .id(run.getId())
                .processDefinitionId(run.getProcessDefinitionId())
                .processVersion(run.getProcessVersion())
                .ontologySnapshotId(run.getOntologySnapshotId())
                .status(RunStatus.CANCELLED)
                .startedAt(run.getStartedAt())
                .completedAt(Instant.now())
                .estimatedCompletion(run.getEstimatedCompletion())
                .stepExecutions(run.getStepExecutions())
                .pendingApprovals(run.getPendingApprovals())
                .controlResults(run.getControlResults())
                .runData(run.getRunData())
                .graphNodeIds(run.getGraphNodeIds())
                .metrics(run.getMetrics())
                .build();
        runs.put(cancelled.getId(), cancelled);
        persistRun(cancelled);

        // Fire graph callback for cancelled run
        if (processGraphCallback != null) {
            try {
                processGraphCallback.onRunCompleted(cancelled);
            } catch (Exception e) {
                log.debug("Graph callback onRunCompleted failed for cancelled run {}: {}",
                        cancelled.getId(), e.getMessage());
            }
        }

        return cancelled;
    }

    @Override
    public WorkflowRun updateRunData(String runId, Map<String, Object> data) {
        WorkflowRun run = getRun(runId);
        Map<String, Object> runData = run.getRunData() != null
                ? new LinkedHashMap<>(run.getRunData()) : new LinkedHashMap<>();
        if (data != null) {
            runData.putAll(data);
        }
        WorkflowRun updated = WorkflowRun.builder()
                .id(run.getId())
                .processDefinitionId(run.getProcessDefinitionId())
                .processVersion(run.getProcessVersion())
                .ontologySnapshotId(run.getOntologySnapshotId())
                .status(run.getStatus())
                .startedAt(run.getStartedAt())
                .completedAt(run.getCompletedAt())
                .estimatedCompletion(run.getEstimatedCompletion())
                .stepExecutions(run.getStepExecutions())
                .pendingApprovals(run.getPendingApprovals())
                .controlResults(run.getControlResults())
                .runData(runData)
                .graphNodeIds(run.getGraphNodeIds())
                .metrics(run.getMetrics())
                .build();
        runs.put(updated.getId(), updated);
        persistRun(updated);
        return updated;
    }

    @Override
    public WorkflowRun completeHumanStep(String runId, String stepId, String completedBy,
                                          Map<String, Object> outputs) {
        WorkflowRun run = getRun(runId);
        if (run.getStatus() != RunStatus.PAUSED_FOR_HUMAN && run.getStatus() != RunStatus.PAUSED_FOR_APPROVAL) {
            throw new IllegalArgumentException("Run is not paused: " + run.getStatus());
        }

        List<StepExecution> updated = run.getStepExecutions() == null
                ? new ArrayList<>() : new ArrayList<>(run.getStepExecutions());
        boolean found = false;
        for (int i = 0; i < updated.size(); i++) {
            StepExecution se = updated.get(i);
            if (stepId.equals(se.getStepId())
                    && se.getStatus() == StepExecutionStatus.AWAITING_APPROVAL) {
                String outputHash = sha256(outputs != null ? outputs : Collections.emptyMap());
                updated.set(i, StepExecution.builder()
                        .stepId(se.getStepId())
                        .stepName(se.getStepName())
                        .status(StepExecutionStatus.COMPLETED)
                        .startedAt(se.getStartedAt())
                        .completedAt(Instant.now())
                        .executedBy(completedBy)
                        .inputs(se.getInputs())
                        .outputs(outputs != null ? outputs : new HashMap<>())
                        .inputHash(se.getInputHash())
                        .outputHash(outputHash)
                        .evidenceReliedOn(se.getEvidenceReliedOn())
                        .graphNodeIds(se.getGraphNodeIds())
                        .build());
                found = true;
                break;
            }
        }
        if (!found) {
            throw new IllegalArgumentException("No AWAITING_APPROVAL step with id: " + stepId);
        }

        // Merge human step outputs into runData
        Map<String, Object> mergedRunData = run.getRunData() != null ? new HashMap<>(run.getRunData()) : new HashMap<>();
        if (outputs != null) {
            mergedRunData.putAll(outputs);
        }

        WorkflowRun resumed = WorkflowRun.builder()
                .id(run.getId())
                .processDefinitionId(run.getProcessDefinitionId())
                .processVersion(run.getProcessVersion())
                .ontologySnapshotId(run.getOntologySnapshotId())
                .status(RunStatus.RUNNING)
                .startedAt(run.getStartedAt())
                .completedAt(run.getCompletedAt())
                .estimatedCompletion(run.getEstimatedCompletion())
                .stepExecutions(updated)
                .pendingApprovals(run.getPendingApprovals())
                .controlResults(run.getControlResults())
                .runData(mergedRunData)
                .graphNodeIds(run.getGraphNodeIds())
                .metrics(run.getMetrics())
                .build();

        // Continue advancing
        int defVersion = definitionVersions.getOrDefault(run.getProcessDefinitionId(), 0);
        if (defVersion > 0) {
            ProcessDefinition def = getProcess(run.getProcessDefinitionId(), defVersion);
            resumed = advanceRun(resumed, def);
        }

        runs.put(resumed.getId(), resumed);
        persistRun(resumed);
        return resumed;
    }

    // ---------------------------------------------------------------------------
    // Human-in-the-loop (HITL)
    // ---------------------------------------------------------------------------

    @Override
    public List<ApprovalRequest> getPendingApprovals(String assignedTo) {
        return approvalRequests.values().stream()
                .filter(r -> r.getStatus() == ApprovalRequestStatus.PENDING)
                .filter(r -> assignedTo == null || assignedTo.equals(r.getAssignedTo()))
                .collect(Collectors.toList());
    }

    @Override
    public ApprovalRequest getApprovalRequest(String requestId) {
        ApprovalRequest req = approvalRequests.get(requestId);
        if (req == null) {
            throw new IllegalArgumentException("Approval request not found: " + requestId);
        }
        return req;
    }

    @Override
    public WorkflowRun submitApproval(ApprovalResponse response) {
        if (response == null || response.getRequestId() == null) {
            throw new IllegalArgumentException("ApprovalResponse must have a non-null requestId");
        }
        ApprovalRequest req = getApprovalRequest(response.getRequestId());

        // Determine new request status from action
        ApprovalRequestStatus newReqStatus = mapActionToRequestStatus(response.getAction());
        ApprovalRequest updatedReq = ApprovalRequest.builder()
                .id(req.getId())
                .workflowRunId(req.getWorkflowRunId())
                .stepId(req.getStepId())
                .stepName(req.getStepName())
                .status(newReqStatus)
                .createdAt(req.getCreatedAt())
                .slaDeadline(req.getSlaDeadline())
                .assignedTo(req.getAssignedTo())
                .items(req.getItems())
                .context(req.getContext())
                .evidenceReliedOn(req.getEvidenceReliedOn())
                .evidenceHashAtCreation(req.getEvidenceHashAtCreation())
                .build();
        approvalRequests.put(req.getId(), updatedReq);
        persistApproval(updatedReq);

        WorkflowRun run = getRun(req.getWorkflowRunId());

        // Three-way decision: approved → COMPLETED, pending further action → stay AWAITING, rejected → FAILED
        StepExecutionStatus newStepStatus;
        RunStatus nextRunStatus;
        if (isApproved(response.getAction())) {
            newStepStatus = StepExecutionStatus.COMPLETED;
            nextRunStatus = RunStatus.RUNNING;
        } else if (isPendingFurtherAction(response.getAction())) {
            // ESCALATE, DELEGATE, REQUEST_INFO — step stays AWAITING_APPROVAL, run stays paused
            newStepStatus = StepExecutionStatus.AWAITING_APPROVAL;
            nextRunStatus = RunStatus.PAUSED_FOR_APPROVAL;
        } else {
            // REJECT
            newStepStatus = StepExecutionStatus.FAILED;
            nextRunStatus = RunStatus.FAILED;
        }

        // For DELEGATE, update the assignedTo on the approval request
        if (response.getAction() == ApprovalAction.DELEGATE && response.getDelegateTo() != null) {
            ApprovalRequest reDelegated = ApprovalRequest.builder()
                    .id(req.getId())
                    .workflowRunId(req.getWorkflowRunId())
                    .stepId(req.getStepId())
                    .stepName(req.getStepName())
                    .status(ApprovalRequestStatus.PENDING)
                    .createdAt(req.getCreatedAt())
                    .slaDeadline(req.getSlaDeadline())
                    .assignedTo(response.getDelegateTo())
                    .items(req.getItems())
                    .context(req.getContext())
                    .evidenceReliedOn(req.getEvidenceReliedOn())
                    .evidenceHashAtCreation(req.getEvidenceHashAtCreation())
                    .build();
            approvalRequests.put(req.getId(), reDelegated);
            persistApproval(reDelegated);
        }

        List<StepExecution> updated = run.getStepExecutions() == null
                ? new ArrayList<>() : new ArrayList<>(run.getStepExecutions());

        // Only update the step if we are changing its state (not for pending-further-action)
        if (newStepStatus != StepExecutionStatus.AWAITING_APPROVAL) {
            for (int i = 0; i < updated.size(); i++) {
                StepExecution se = updated.get(i);
                if (req.getStepId().equals(se.getStepId())
                        && se.getStatus() == StepExecutionStatus.AWAITING_APPROVAL) {
                    updated.set(i, StepExecution.builder()
                            .stepId(se.getStepId())
                            .stepName(se.getStepName())
                            .status(newStepStatus)
                            .startedAt(se.getStartedAt())
                            .completedAt(Instant.now())
                            .executedBy(response.getRespondedBy())
                            .inputs(se.getInputs())
                            .outputs(se.getOutputs())
                            .inputHash(se.getInputHash())
                            .outputHash(se.getOutputHash())
                            .evidenceReliedOn(se.getEvidenceReliedOn())
                            .graphNodeIds(se.getGraphNodeIds())
                            .error(newStepStatus == StepExecutionStatus.FAILED ? "Rejected by " + response.getRespondedBy() : null)
                            .build());
                    break;
                }
            }
        }

        // Remove request from pendingApprovals list on the run only when resolved
        List<ApprovalRequest> pendingApprovals = run.getPendingApprovals() == null
                ? new ArrayList<>() : new ArrayList<>(run.getPendingApprovals());
        if (newStepStatus != StepExecutionStatus.AWAITING_APPROVAL) {
            pendingApprovals.removeIf(a -> req.getId().equals(a.getId()));
        }

        WorkflowRun resumed = WorkflowRun.builder()
                .id(run.getId())
                .processDefinitionId(run.getProcessDefinitionId())
                .processVersion(run.getProcessVersion())
                .ontologySnapshotId(run.getOntologySnapshotId())
                .status(nextRunStatus)
                .startedAt(run.getStartedAt())
                .completedAt(run.getCompletedAt())
                .estimatedCompletion(run.getEstimatedCompletion())
                .stepExecutions(updated)
                .pendingApprovals(pendingApprovals)
                .controlResults(run.getControlResults())
                .runData(run.getRunData())
                .graphNodeIds(run.getGraphNodeIds())
                .metrics(run.getMetrics())
                .build();

        if (newStepStatus == StepExecutionStatus.COMPLETED) {
            // Continue advancing remaining steps
            int defVersion = definitionVersions.getOrDefault(run.getProcessDefinitionId(), 0);
            if (defVersion > 0) {
                ProcessDefinition def = getProcess(run.getProcessDefinitionId(), defVersion);
                resumed = advanceRun(resumed, def);
            }
        }

        runs.put(resumed.getId(), resumed);
        persistRun(resumed);
        return resumed;
    }

    // ---------------------------------------------------------------------------
    // Controls
    // ---------------------------------------------------------------------------

    @Override
    public ControlAttestation evaluateControl(String controlId, String runId, Map<String, Object> data) {
        ControlDefinition control = controls.get(controlId);
        if (control == null) {
            throw new IllegalArgumentException("ControlDefinition not found: " + controlId);
        }

        String expression = control.getExpression();
        boolean passed = false;
        String inputHash = sha256(data);

        try {
            passed = evaluateSpelBoolean(expression, data);
        } catch (Exception e) {
            log.warn("Control expression evaluation failed for controlId={}: {}", controlId, e.getMessage());
            // Fail-safe: treat eval errors as control failure
        }

        String attestationId = UUID.randomUUID().toString();
        ControlAttestation attestation = ControlAttestation.builder()
                .id(attestationId)
                .controlId(controlId)
                .workflowRunId(runId)
                .evaluatedAt(Instant.now())
                .passed(passed)
                .expressionEvaluated(expression)
                .inputValues(data)
                .inputHash(inputHash)
                .evaluatedBy("system")
                .build();

        attestations.put(attestationId, attestation);
        persistAttestation(attestation);

        // Attach attestation to the run if it exists
        WorkflowRun run = runs.get(runId);
        if (run != null) {
            List<ControlAttestation> results = run.getControlResults() == null
                    ? new ArrayList<>() : new ArrayList<>(run.getControlResults());
            results.add(attestation);
            WorkflowRun updated = WorkflowRun.builder()
                    .id(run.getId())
                    .processDefinitionId(run.getProcessDefinitionId())
                    .processVersion(run.getProcessVersion())
                    .ontologySnapshotId(run.getOntologySnapshotId())
                    .status(run.getStatus())
                    .startedAt(run.getStartedAt())
                    .completedAt(run.getCompletedAt())
                    .estimatedCompletion(run.getEstimatedCompletion())
                    .stepExecutions(run.getStepExecutions())
                    .pendingApprovals(run.getPendingApprovals())
                    .controlResults(results)
                    .runData(run.getRunData())
                    .metrics(run.getMetrics())
                    .build();
            runs.put(run.getId(), updated);
            persistRun(updated);
        }

        log.debug("Control evaluated controlId={} runId={} passed={}", controlId, runId, passed);
        return attestation;
    }

    @Override
    public List<ControlAttestation> getControlResults(String runId) {
        WorkflowRun run = runs.get(runId);
        if (run == null) {
            throw new IllegalArgumentException("Workflow run not found: " + runId);
        }
        return run.getControlResults() == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(run.getControlResults());
    }

    @Override
    public ControlDefinition createControl(ControlDefinition control) {
        String id = control.getId() != null ? control.getId() : UUID.randomUUID().toString();
        ControlDefinition created = ControlDefinition.builder()
                .id(id)
                .name(control.getName())
                .description(control.getDescription())
                .gateType(control.getGateType())
                .expression(control.getExpression())
                .triggerAfterStep(control.getTriggerAfterStep())
                .inputKeys(control.getInputKeys())
                .regulatoryReference(control.getRegulatoryReference())
                .severity(control.getSeverity())
                .onFailure(control.getOnFailure())
                .provenance(control.getProvenance())
                .build();
        controls.put(id, created);
        persistControl(created);
        log.debug("Created control definition id={}", id);
        return created;
    }

    @Override
    public List<ControlDefinition> listControls() {
        return new ArrayList<>(controls.values());
    }

    @Override
    public ControlDefinition getControl(String controlId) {
        ControlDefinition control = controls.get(controlId);
        if (control == null) {
            throw new IllegalArgumentException("ControlDefinition not found: " + controlId);
        }
        return control;
    }

    @Override
    public SpelEvaluationResult evaluateSpelExpression(String expression, Map<String, Object> context) {
        if (expression == null || expression.isBlank()) {
            return SpelEvaluationResult.builder()
                    .error("Expression must not be blank")
                    .build();
        }
        try {
            StandardEvaluationContext ctx = new StandardEvaluationContext();
            if (context != null) {
                for (Map.Entry<String, Object> entry : context.entrySet()) {
                    ctx.setVariable(entry.getKey(), entry.getValue());
                }
            }
            Expression expr = spelParser.parseExpression(expression);
            Object result = expr.getValue(ctx);
            String type = result != null ? result.getClass().getSimpleName() : "null";
            return SpelEvaluationResult.builder()
                    .result(result)
                    .type(type)
                    .build();
        } catch (Exception e) {
            return SpelEvaluationResult.builder()
                    .error(e.getMessage())
                    .build();
        }
    }

    // ---------------------------------------------------------------------------
    // Ingestion
    // ---------------------------------------------------------------------------

    @Override
    public SubmissionManifest createManifest(SubmissionManifest manifest) {
        String id = manifest.getId() != null ? manifest.getId() : UUID.randomUUID().toString();
        SubmissionManifest created = SubmissionManifest.builder()
                .id(id)
                .workflowRunId(manifest.getWorkflowRunId())
                .sourceRegion(manifest.getSourceRegion())
                .authoritativeFileId(manifest.getAuthoritativeFileId())
                .authoritativeFileName(manifest.getAuthoritativeFileName())
                .fileContentHash(manifest.getFileContentHash())
                .versionAssertedBy(manifest.getVersionAssertedBy())
                .versionAssertionSource(manifest.getVersionAssertionSource())
                .receivedAt(manifest.getReceivedAt() != null ? manifest.getReceivedAt() : Instant.now())
                .exclusions(manifest.getExclusions())
                .normalizations(manifest.getNormalizations())
                .embeddedAssumptions(manifest.getEmbeddedAssumptions())
                .metadata(manifest.getMetadata())
                .build();

        manifests.put(id, created);
        persistManifest(created);
        log.debug("Created manifest id={}", id);
        return created;
    }

    @Override
    public SubmissionManifest assertAuthoritativeVersion(String manifestId, String fileId, String assertedBy) {
        SubmissionManifest existing = manifests.get(manifestId);
        if (existing == null) {
            throw new IllegalArgumentException("SubmissionManifest not found: " + manifestId);
        }
        SubmissionManifest updated = SubmissionManifest.builder()
                .id(existing.getId())
                .workflowRunId(existing.getWorkflowRunId())
                .sourceRegion(existing.getSourceRegion())
                .authoritativeFileId(fileId)
                .authoritativeFileName(existing.getAuthoritativeFileName())
                .fileContentHash(existing.getFileContentHash())
                .versionAssertedBy(assertedBy)
                .versionAssertionSource("system")
                .receivedAt(existing.getReceivedAt())
                .exclusions(existing.getExclusions())
                .normalizations(existing.getNormalizations())
                .embeddedAssumptions(existing.getEmbeddedAssumptions())
                .metadata(existing.getMetadata())
                .build();

        manifests.put(manifestId, updated);
        persistManifest(updated);
        log.debug("Asserted authoritative version manifestId={} fileId={} assertedBy={}", manifestId, fileId, assertedBy);
        return updated;
    }

    // ---------------------------------------------------------------------------
    // Excel formula conversion / execution
    // ---------------------------------------------------------------------------

    @Override
    public Map<String, Object> convertExcelFormulas(String spreadsheetGraphJson, String targetLanguage) {
        if (stepExecutionDispatcher == null) {
            throw new IllegalStateException("Excel conversion requires StepExecutionDispatcher");
        }
        return stepExecutionDispatcher.convertExcel(spreadsheetGraphJson, targetLanguage);
    }

    @Override
    public Map<String, Object> executeExcelFormulas(String spreadsheetGraphJson,
                                                     Map<String, Object> cellOverrides,
                                                     String targetLanguage,
                                                     String generatedCode) {
        if (stepExecutionDispatcher == null) {
            throw new IllegalStateException("Excel execution requires StepExecutionDispatcher");
        }
        return stepExecutionDispatcher.executeExcel(
                spreadsheetGraphJson, cellOverrides, targetLanguage, generatedCode);
    }

    // ---------------------------------------------------------------------------
    // Run advancement — walks pending steps and pauses on APPROVE/HUMAN steps
    // ---------------------------------------------------------------------------

    /**
     * Advances a run by executing PENDING steps in order until it encounters
     * an APPROVE or HUMAN step (which causes a pause) or all steps are done.
     *
     * @param run the current run state
     * @param def the process definition driving the run
     * @return the updated run (status may have changed to PAUSED_FOR_APPROVAL,
     *         PAUSED_FOR_HUMAN, COMPLETED, or FAILED)
     */
    private WorkflowRun advanceRun(WorkflowRun run, ProcessDefinition def) {
        // Build an ordered lookup of step definitions by stepId
        Map<String, ProcessStep> stepDefMap = buildStepDefMap(def);

        List<StepExecution> stepExecutions = run.getStepExecutions() == null
                ? new ArrayList<>() : new ArrayList<>(run.getStepExecutions());
        List<ApprovalRequest> pendingApprovals = run.getPendingApprovals() == null
                ? new ArrayList<>() : new ArrayList<>(run.getPendingApprovals());
        List<ControlAttestation> controlResults = run.getControlResults() == null
                ? new ArrayList<>() : new ArrayList<>(run.getControlResults());
        Map<String, Object> runData = run.getRunData() == null
                ? new HashMap<>() : new HashMap<>(run.getRunData());

        RunStatus runStatus = RunStatus.RUNNING;

        // Build a set of completed step IDs for dependency checking
        java.util.Set<String> completedStepIds = stepExecutions.stream()
                .filter(s -> s.getStatus() == StepExecutionStatus.COMPLETED
                          || s.getStatus() == StepExecutionStatus.SKIPPED)
                .map(StepExecution::getStepId)
                .collect(Collectors.toSet());

        for (int i = 0; i < stepExecutions.size(); i++) {
            StepExecution se = stepExecutions.get(i);
            if (se.getStatus() != StepExecutionStatus.PENDING) {
                // Already done or in progress
                continue;
            }

            ProcessStep stepDef = stepDefMap.get(se.getStepId());
            if (stepDef == null) {
                log.warn("No ProcessStep definition found for stepId={}, marking SKIPPED", se.getStepId());
                stepExecutions.set(i, updateStepStatus(se, StepExecutionStatus.SKIPPED, null));
                completedStepIds.add(se.getStepId());
                continue;
            }

            // Enforce dependsOn ordering: skip this step if any dependency hasn't completed
            if (stepDef.getDependsOn() != null && !stepDef.getDependsOn().isEmpty()) {
                boolean allDepsComplete = completedStepIds.containsAll(stepDef.getDependsOn());
                if (!allDepsComplete) {
                    log.debug("Step {} waiting on dependencies: {}", se.getStepId(), stepDef.getDependsOn());
                    continue; // Leave as PENDING, will be advanced on next pass
                }
            }

            Instant now = Instant.now();
            if (!isStepConditionSatisfied(stepDef, runData)) {
                Map<String, Object> inputs = extractInputs(stepDef, runData);
                Map<String, Object> outputs = new HashMap<>();
                outputs.put("_skippedReason", "condition_not_met");
                outputs.put("_conditionExpression", stepDef.getConditionExpression());
                if (stepDef.getConditionLabel() != null && !stepDef.getConditionLabel().isBlank()) {
                    outputs.put("_conditionLabel", stepDef.getConditionLabel());
                }
                StepExecution skipped = StepExecution.builder()
                        .stepId(se.getStepId())
                        .stepName(se.getStepName())
                        .status(StepExecutionStatus.SKIPPED)
                        .startedAt(now)
                        .completedAt(now)
                        .executedBy("condition")
                        .inputs(inputs)
                        .outputs(outputs)
                        .inputHash(sha256(inputs))
                        .outputHash(sha256(outputs))
                        .graphNodeIds(stepDef.getGraphNodeIds())
                        .build();
                stepExecutions.set(i, skipped);
                completedStepIds.add(se.getStepId());
                continue;
            }

            AccessDecision accessDecision = checkStepAccess(stepDef, runData);
            if (!accessDecision.allowed()) {
                Map<String, Object> inputs = extractInputsOrAllRunData(stepDef, runData);
                Map<String, Object> outputs = new HashMap<>();
                outputs.put("_accessDenied", true);
                outputs.put("_requiredRoles", stepDef.getRequiredRoles());
                outputs.put("_requiredPermissions", stepDef.getRequiredPermissions());
                outputs.put("_actorRoles", accessDecision.actorRoles());
                outputs.put("_actorPermissions", accessDecision.actorPermissions());
                StepExecution failed = StepExecution.builder()
                        .stepId(se.getStepId()).stepName(se.getStepName())
                        .status(StepExecutionStatus.FAILED).startedAt(now).completedAt(now)
                        .inputs(inputs).outputs(outputs)
                        .inputHash(sha256(inputs)).outputHash(sha256(outputs))
                        .graphNodeIds(stepDef.getGraphNodeIds())
                        .error(accessDecision.reason())
                        .build();
                stepExecutions.set(i, failed);
                runStatus = RunStatus.FAILED;
                return buildRun(run, stepExecutions, pendingApprovals, controlResults, runData, runStatus);
            }

            StepType stepType = stepDef.getStepType() != null ? stepDef.getStepType() : StepType.AUTO;

            switch (stepType) {
                case AUTO: {
                    // Execute SpEL expressions against runData to produce outputs
                    Map<String, Object> inputs = extractInputs(stepDef, runData);
                    String inputHash = sha256(inputs);
                    Map<String, Object> outputs = executeSpelExpressions(stepDef, runData);
                    String outputHash = sha256(outputs);

                    // Merge step outputs into shared runData for downstream steps
                    runData.putAll(outputs);

                    StepExecution completed = StepExecution.builder()
                            .stepId(se.getStepId())
                            .stepName(se.getStepName())
                            .status(StepExecutionStatus.COMPLETED)
                            .startedAt(now)
                            .completedAt(now)
                            .executedBy("system")
                            .inputs(inputs)
                            .outputs(outputs)
                            .inputHash(inputHash)
                            .outputHash(outputHash)
                            .graphNodeIds(stepDef.getGraphNodeIds())
                            .build();
                    stepExecutions.set(i, completed);
                    completedStepIds.add(se.getStepId());

                    // Validate outputs against ontology if one is bound to this run
                    List<String> validationErrors = validateRunDataAgainstOntology(run, runData);
                    if (!validationErrors.isEmpty()) {
                        log.warn("Ontology validation warnings after step {}: {}", se.getStepId(), validationErrors);
                        // Store violations in metrics for visibility, but don't halt the run
                        Map<String, Object> metrics = run.getMetrics() != null ? new HashMap<>(run.getMetrics()) : new HashMap<>();
                        metrics.put("ontologyViolations_" + se.getStepId(), validationErrors);
                        run = WorkflowRun.builder()
                                .id(run.getId()).processDefinitionId(run.getProcessDefinitionId())
                                .processVersion(run.getProcessVersion()).ontologySnapshotId(run.getOntologySnapshotId())
                                .status(run.getStatus()).startedAt(run.getStartedAt()).completedAt(run.getCompletedAt())
                                .estimatedCompletion(run.getEstimatedCompletion()).stepExecutions(run.getStepExecutions())
                                .pendingApprovals(run.getPendingApprovals()).controlResults(run.getControlResults())
                                .runData(run.getRunData()).graphNodeIds(run.getGraphNodeIds()).metrics(metrics)
                                .build();
                    }

                    // Fire any controls attached to this step
                    controlResults.addAll(evaluateStepControls(stepDef, run.getId(), runData));
                    break;
                }

                case APPROVE: {
                    // Pause for human approval
                    Map<String, Object> inputs = extractInputs(stepDef, runData);
                    String inputHash = sha256(inputs);
                    StepExecution waiting = StepExecution.builder()
                            .stepId(se.getStepId())
                            .stepName(se.getStepName())
                            .status(StepExecutionStatus.AWAITING_APPROVAL)
                            .startedAt(now)
                            .inputs(inputs)
                            .inputHash(inputHash)
                            .graphNodeIds(stepDef.getGraphNodeIds())
                            .build();
                    stepExecutions.set(i, waiting);

                    // Create an ApprovalRequest
                    ApprovalRequest approvalRequest = createApprovalRequest(run.getId(), stepDef, inputs);
                    approvalRequests.put(approvalRequest.getId(), approvalRequest);
                    persistApproval(approvalRequest);
                    pendingApprovals.add(approvalRequest);

                    runStatus = RunStatus.PAUSED_FOR_APPROVAL;
                    // Stop advancing — wait for human response
                    return buildRun(run, stepExecutions, pendingApprovals, controlResults, runData, runStatus);
                }

                case HUMAN: {
                    // Mark the step as needing human execution and pause
                    StepExecution waiting = StepExecution.builder()
                            .stepId(se.getStepId())
                            .stepName(se.getStepName())
                            .status(StepExecutionStatus.AWAITING_APPROVAL)
                            .startedAt(now)
                            .graphNodeIds(stepDef.getGraphNodeIds())
                            .build();
                    stepExecutions.set(i, waiting);
                    runStatus = RunStatus.PAUSED_FOR_HUMAN;
                    return buildRun(run, stepExecutions, pendingApprovals, controlResults, runData, runStatus);
                }

                case CONTROL_GATE: {
                    // Evaluate attached controls; if a HARD gate fails, halt the run
                    Map<String, Object> inputs = extractInputs(stepDef, runData);
                    String inputHash = sha256(inputs);
                    List<ControlAttestation> gateResults = evaluateStepControls(stepDef, run.getId(), runData);
                    controlResults.addAll(gateResults);
                    boolean allPassed = gateResults.stream().allMatch(ControlAttestation::isPassed);
                    if (!allPassed) {
                        // Check if any failed control is a HARD gate
                        boolean hasHardFailure = gateResults.stream()
                                .filter(a -> !a.isPassed())
                                .anyMatch(a -> isHardGate(a.getControlId()));
                        if (hasHardFailure) {
                            StepExecution failed = StepExecution.builder()
                                    .stepId(se.getStepId())
                                    .stepName(se.getStepName())
                                    .status(StepExecutionStatus.FAILED)
                                    .startedAt(now)
                                    .completedAt(now)
                                    .inputs(inputs)
                                    .inputHash(inputHash)
                                    .graphNodeIds(stepDef.getGraphNodeIds())
                                    .error("Hard control gate failed")
                                    .build();
                            stepExecutions.set(i, failed);
                            runStatus = RunStatus.FAILED;
                            return buildRun(run, stepExecutions, pendingApprovals, controlResults, runData, runStatus);
                        }
                    }
                    // Also execute any SpEL expressions on the control gate step
                    Map<String, Object> gateOutputs = executeSpelExpressions(stepDef, runData);
                    runData.putAll(gateOutputs);

                    // Soft gate or all passed — mark COMPLETED
                    StepExecution completed = StepExecution.builder()
                            .stepId(se.getStepId())
                            .stepName(se.getStepName())
                            .status(StepExecutionStatus.COMPLETED)
                            .startedAt(now)
                            .completedAt(now)
                            .executedBy("system")
                            .inputs(inputs)
                            .outputs(gateOutputs)
                            .inputHash(inputHash)
                            .outputHash(sha256(gateOutputs))
                            .graphNodeIds(stepDef.getGraphNodeIds())
                            .build();
                    stepExecutions.set(i, completed);
                    completedStepIds.add(se.getStepId());
                    break;
                }

                case TOOL_CALL: {
                    // Invoke a registered MCP/Spring AI tool by name
                    if (stepExecutionDispatcher == null) {
                        StepExecution failed = StepExecution.builder()
                                .stepId(se.getStepId()).stepName(se.getStepName())
                                .status(StepExecutionStatus.FAILED).startedAt(now).completedAt(now)
                                .graphNodeIds(stepDef.getGraphNodeIds())
                                .error("TOOL_CALL step requires StepExecutionDispatcher but none is wired")
                                .build();
                        stepExecutions.set(i, failed);
                        runStatus = RunStatus.FAILED;
                        return buildRun(run, stepExecutions, pendingApprovals, controlResults, runData, runStatus);
                    }

                    Map<String, Object> toolArgs = resolveToolArguments(stepDef, runData);
                    Map<String, Object> inputs = new HashMap<>(toolArgs);
                    inputs.put("_toolName", stepDef.getToolName());
                    String inputHash = sha256(inputs);

                    try {
                        Map<String, Object> toolResult = stepExecutionDispatcher.invokeTool(
                                stepDef.getToolName(), toolArgs);
                        runData.putAll(toolResult);

                        StepExecution completed = StepExecution.builder()
                                .stepId(se.getStepId()).stepName(se.getStepName())
                                .status(StepExecutionStatus.COMPLETED)
                                .startedAt(now).completedAt(Instant.now())
                                .executedBy("tool:" + stepDef.getToolName())
                                .inputs(inputs).outputs(toolResult)
                                .inputHash(inputHash).outputHash(sha256(toolResult))
                                .graphNodeIds(stepDef.getGraphNodeIds())
                                .build();
                        stepExecutions.set(i, completed);
                        completedStepIds.add(se.getStepId());
                    } catch (Exception e) {
                        StepExecution failed = StepExecution.builder()
                                .stepId(se.getStepId()).stepName(se.getStepName())
                                .status(StepExecutionStatus.FAILED).startedAt(now).completedAt(Instant.now())
                                .inputs(inputs).inputHash(inputHash)
                                .graphNodeIds(stepDef.getGraphNodeIds())
                                .error("Tool '" + stepDef.getToolName() + "' failed: " + e.getMessage())
                                .build();
                        stepExecutions.set(i, failed);
                        runStatus = RunStatus.FAILED;
                        return buildRun(run, stepExecutions, pendingApprovals, controlResults, runData, runStatus);
                    }
                    controlResults.addAll(evaluateStepControls(stepDef, run.getId(), runData));
                    break;
                }

                case HTTP_CALL: {
                    // Make an HTTP request to an external service
                    if (stepExecutionDispatcher == null) {
                        StepExecution failed = StepExecution.builder()
                                .stepId(se.getStepId()).stepName(se.getStepName())
                                .status(StepExecutionStatus.FAILED).startedAt(now).completedAt(now)
                                .graphNodeIds(stepDef.getGraphNodeIds())
                                .error("HTTP_CALL step requires StepExecutionDispatcher but none is wired")
                                .build();
                        stepExecutions.set(i, failed);
                        runStatus = RunStatus.FAILED;
                        return buildRun(run, stepExecutions, pendingApprovals, controlResults, runData, runStatus);
                    }

                    String resolvedUrl = resolveSpelString(stepDef.getHttpUrl(), runData);
                    Map<String, String> resolvedHeaders = resolveHeaders(stepDef.getHttpHeaders(), runData);
                    Object resolvedBody = stepDef.getHttpBody() != null
                            ? evaluateSpelObject(stepDef.getHttpBody(), runData) : null;
                    String method = stepDef.getHttpMethod() != null ? stepDef.getHttpMethod() : "GET";

                    Map<String, Object> inputs = new HashMap<>();
                    inputs.put("method", method);
                    inputs.put("url", resolvedUrl);
                    String inputHash = sha256(inputs);

                    try {
                        Map<String, Object> httpResult = stepExecutionDispatcher.executeHttpCall(
                                method, resolvedUrl, resolvedHeaders, resolvedBody);

                        String responseKey = stepDef.getHttpResponseKey() != null
                                ? stepDef.getHttpResponseKey() : "httpResponse";
                        runData.put(responseKey, httpResult.getOrDefault("bodyParsed",
                                httpResult.getOrDefault("body", httpResult)));
                        runData.put(responseKey + "_status", httpResult.get("statusCode"));

                        StepExecution completed = StepExecution.builder()
                                .stepId(se.getStepId()).stepName(se.getStepName())
                                .status(StepExecutionStatus.COMPLETED)
                                .startedAt(now).completedAt(Instant.now())
                                .executedBy("http:" + method + ":" + resolvedUrl)
                                .inputs(inputs).outputs(httpResult)
                                .inputHash(inputHash).outputHash(sha256(httpResult))
                                .graphNodeIds(stepDef.getGraphNodeIds())
                                .build();
                        stepExecutions.set(i, completed);
                        completedStepIds.add(se.getStepId());
                    } catch (Exception e) {
                        StepExecution failed = StepExecution.builder()
                                .stepId(se.getStepId()).stepName(se.getStepName())
                                .status(StepExecutionStatus.FAILED).startedAt(now).completedAt(Instant.now())
                                .inputs(inputs).inputHash(inputHash)
                                .graphNodeIds(stepDef.getGraphNodeIds())
                                .error("HTTP " + method + " " + resolvedUrl + " failed: " + e.getMessage())
                                .build();
                        stepExecutions.set(i, failed);
                        runStatus = RunStatus.FAILED;
                        return buildRun(run, stepExecutions, pendingApprovals, controlResults, runData, runStatus);
                    }
                    controlResults.addAll(evaluateStepControls(stepDef, run.getId(), runData));
                    break;
                }

                case SCRIPT: {
                    // Execute JavaScript or Python script via GraalVM Polyglot
                    if (stepExecutionDispatcher == null) {
                        StepExecution failed = StepExecution.builder()
                                .stepId(se.getStepId()).stepName(se.getStepName())
                                .status(StepExecutionStatus.FAILED).startedAt(now).completedAt(now)
                                .graphNodeIds(stepDef.getGraphNodeIds())
                                .error("SCRIPT step requires StepExecutionDispatcher but none is wired")
                                .build();
                        stepExecutions.set(i, failed);
                        runStatus = RunStatus.FAILED;
                        return buildRun(run, stepExecutions, pendingApprovals, controlResults, runData, runStatus);
                    }

                    Map<String, Object> inputs = extractInputs(stepDef, runData);
                    String inputHash = sha256(inputs);

                    if (stepDef.getScriptBody() != null && !stepDef.getScriptBody().isBlank()) {
                        try {
                            String language = stepDef.getScriptLanguage() != null
                                    ? stepDef.getScriptLanguage() : "javascript";
                            Map<String, Object> scriptOutputs = stepExecutionDispatcher.executeScript(
                                    language, stepDef.getScriptBody(), runData);

                            // Store outputs under scriptOutputKey or merge directly
                            String outputKey = stepDef.getScriptOutputKey();
                            Map<String, Object> outputs;
                            if (outputKey != null && !outputKey.isBlank()) {
                                outputs = new HashMap<>();
                                // If script returned a _result, use that as the primary value
                                Object result = scriptOutputs.getOrDefault("_result", scriptOutputs);
                                outputs.put(outputKey, result);
                                // Also include any named outputs from the script
                                for (Map.Entry<String, Object> e : scriptOutputs.entrySet()) {
                                    if (!e.getKey().startsWith("_")) {
                                        outputs.put(e.getKey(), e.getValue());
                                    }
                                }
                            } else {
                                outputs = new HashMap<>(scriptOutputs);
                            }

                            runData.putAll(outputs);
                            String outputHash = sha256(outputs);

                            StepExecution completed = StepExecution.builder()
                                    .stepId(se.getStepId()).stepName(se.getStepName())
                                    .status(StepExecutionStatus.COMPLETED)
                                    .startedAt(now).completedAt(Instant.now())
                                    .executedBy("script:" + language)
                                    .inputs(inputs).outputs(outputs)
                                    .inputHash(inputHash).outputHash(outputHash)
                                    .graphNodeIds(stepDef.getGraphNodeIds())
                                    .build();
                            stepExecutions.set(i, completed);
                            completedStepIds.add(se.getStepId());
                        } catch (Exception e) {
                            StepExecution failed = StepExecution.builder()
                                    .stepId(se.getStepId()).stepName(se.getStepName())
                                    .status(StepExecutionStatus.FAILED).startedAt(now).completedAt(Instant.now())
                                    .inputs(inputs).inputHash(inputHash)
                                    .graphNodeIds(stepDef.getGraphNodeIds())
                                    .error("Script execution failed: " + e.getMessage())
                                    .build();
                            stepExecutions.set(i, failed);
                            runStatus = RunStatus.FAILED;
                            return buildRun(run, stepExecutions, pendingApprovals, controlResults, runData, runStatus);
                        }
                    } else {
                        // No script body — just mark completed
                        StepExecution completed = StepExecution.builder()
                                .stepId(se.getStepId()).stepName(se.getStepName())
                                .status(StepExecutionStatus.COMPLETED)
                                .startedAt(now).completedAt(now)
                                .executedBy("script:noop")
                                .inputs(inputs).inputHash(inputHash)
                                .graphNodeIds(stepDef.getGraphNodeIds())
                                .build();
                        stepExecutions.set(i, completed);
                        completedStepIds.add(se.getStepId());
                    }
                    controlResults.addAll(evaluateStepControls(stepDef, run.getId(), runData));
                    break;
                }

                case EXCEL_COMPUTE: {
                    // Execute Excel spreadsheet formulas via LLM-generated code
                    if (stepExecutionDispatcher == null) {
                        StepExecution failed = StepExecution.builder()
                                .stepId(se.getStepId()).stepName(se.getStepName())
                                .status(StepExecutionStatus.FAILED).startedAt(now).completedAt(now)
                                .graphNodeIds(stepDef.getGraphNodeIds())
                                .error("EXCEL_COMPUTE step requires StepExecutionDispatcher but none is wired")
                                .build();
                        stepExecutions.set(i, failed);
                        runStatus = RunStatus.FAILED;
                        return buildRun(run, stepExecutions, pendingApprovals, controlResults, runData, runStatus);
                    }

                    Map<String, Object> inputs = extractInputs(stepDef, runData);
                    String inputHash = sha256(inputs);

                    // Resolve graph JSON: prefer inline, fall back to KG node resolution
                    String excelGraphJson = stepDef.getExcelGraphJson();
                    if ((excelGraphJson == null || excelGraphJson.isBlank())
                            && stepDef.getGraphNodeIds() != null && !stepDef.getGraphNodeIds().isEmpty()) {
                        excelGraphJson = stepExecutionDispatcher.resolveExcelGraphJson(stepDef.getGraphNodeIds());
                    }

                    if (excelGraphJson != null && !excelGraphJson.isBlank()) {
                        try {
                            String targetLang = stepDef.getExcelTargetLanguage() != null
                                    ? stepDef.getExcelTargetLanguage() : "javascript";

                            // Merge cell overrides from step config + runData inputs
                            Map<String, Object> cellOverrides = new HashMap<>();
                            if (stepDef.getExcelCellOverrides() != null) {
                                cellOverrides.putAll(stepDef.getExcelCellOverrides());
                            }
                            // Also allow runData values as cell overrides
                            cellOverrides.putAll(inputs);

                            DispatchResult dispatchResult = stepExecutionDispatcher.executeExcelWithResult(
                                    excelGraphJson, cellOverrides, targetLang,
                                    stepDef.getExcelGeneratedCode());
                            Map<String, Object> excelOutputs = dispatchResult.getOutputs();

                            // Store outputs
                            String outputKey = stepDef.getExcelOutputKey();
                            Map<String, Object> outputs;
                            if (outputKey != null && !outputKey.isBlank()) {
                                outputs = new HashMap<>();
                                outputs.put(outputKey, excelOutputs);
                                // Also include non-meta outputs at top level
                                for (Map.Entry<String, Object> e : excelOutputs.entrySet()) {
                                    if (!e.getKey().startsWith("_")) {
                                        outputs.put(e.getKey(), e.getValue());
                                    }
                                }
                            } else {
                                outputs = new HashMap<>(excelOutputs);
                            }

                            runData.putAll(outputs);
                            String outputHash = sha256(outputs);

                            // Merge static graph node IDs with runtime-discovered ones
                            List<String> mergedNodeIds = mergeGraphNodeIds(
                                    stepDef.getGraphNodeIds(), dispatchResult.getDiscoveredGraphNodeIds());

                            StepExecution completed = StepExecution.builder()
                                    .stepId(se.getStepId()).stepName(se.getStepName())
                                    .status(StepExecutionStatus.COMPLETED)
                                    .startedAt(now).completedAt(Instant.now())
                                    .executedBy("excel:" + targetLang)
                                    .inputs(inputs).outputs(outputs)
                                    .inputHash(inputHash).outputHash(outputHash)
                                    .graphNodeIds(mergedNodeIds)
                                    .build();
                            stepExecutions.set(i, completed);
                            completedStepIds.add(se.getStepId());
                        } catch (Exception e) {
                            StepExecution failed = StepExecution.builder()
                                    .stepId(se.getStepId()).stepName(se.getStepName())
                                    .status(StepExecutionStatus.FAILED).startedAt(now).completedAt(Instant.now())
                                    .inputs(inputs).inputHash(inputHash)
                                    .graphNodeIds(stepDef.getGraphNodeIds())
                                    .error("Excel execution failed: " + e.getMessage())
                                    .build();
                            stepExecutions.set(i, failed);
                            runStatus = RunStatus.FAILED;
                            return buildRun(run, stepExecutions, pendingApprovals, controlResults, runData, runStatus);
                        }
                    } else {
                        StepExecution failed = StepExecution.builder()
                                .stepId(se.getStepId()).stepName(se.getStepName())
                                .status(StepExecutionStatus.FAILED).startedAt(now).completedAt(now)
                                .graphNodeIds(stepDef.getGraphNodeIds())
                                .error("EXCEL_COMPUTE step requires excelGraphJson but none is provided")
                                .build();
                        stepExecutions.set(i, failed);
                        runStatus = RunStatus.FAILED;
                        return buildRun(run, stepExecutions, pendingApprovals, controlResults, runData, runStatus);
                    }
                    controlResults.addAll(evaluateStepControls(stepDef, run.getId(), runData));
                    break;
                }

                case CAMEL_ROUTE: {
                    if (stepExecutionDispatcher == null) {
                        StepExecution failed = StepExecution.builder()
                                .stepId(se.getStepId()).stepName(se.getStepName())
                                .status(StepExecutionStatus.FAILED).startedAt(now).completedAt(now)
                                .graphNodeIds(stepDef.getGraphNodeIds())
                                .error("CAMEL_ROUTE step requires StepExecutionDispatcher but none is wired")
                                .build();
                        stepExecutions.set(i, failed);
                        runStatus = RunStatus.FAILED;
                        return buildRun(run, stepExecutions, pendingApprovals, controlResults, runData, runStatus);
                    }

                    Map<String, Object> inputs = extractInputsOrAllRunData(stepDef, runData);
                    String inputHash = sha256(inputs);

                    try {
                        Map<String, Object> camelOutputs = stepExecutionDispatcher.executeCamelRoute(
                                stepDef.getCamelRouteId(), stepDef.getCamelInlineScript(), inputs);
                        Map<String, Object> outputs = applyOutputKey(stepDef.getCamelOutputKey(), camelOutputs);
                        runData.putAll(outputs);

                        StepExecution completed = StepExecution.builder()
                                .stepId(se.getStepId()).stepName(se.getStepName())
                                .status(StepExecutionStatus.COMPLETED)
                                .startedAt(now).completedAt(Instant.now())
                                .executedBy("camel:" + firstNonBlank(stepDef.getCamelRouteId(), "inline"))
                                .inputs(inputs).outputs(outputs)
                                .inputHash(inputHash).outputHash(sha256(outputs))
                                .graphNodeIds(stepDef.getGraphNodeIds())
                                .build();
                        stepExecutions.set(i, completed);
                        completedStepIds.add(se.getStepId());
                    } catch (Exception e) {
                        StepExecution failed = StepExecution.builder()
                                .stepId(se.getStepId()).stepName(se.getStepName())
                                .status(StepExecutionStatus.FAILED).startedAt(now).completedAt(Instant.now())
                                .inputs(inputs).inputHash(inputHash)
                                .graphNodeIds(stepDef.getGraphNodeIds())
                                .error("Camel route execution failed: " + e.getMessage())
                                .build();
                        stepExecutions.set(i, failed);
                        runStatus = RunStatus.FAILED;
                        return buildRun(run, stepExecutions, pendingApprovals, controlResults, runData, runStatus);
                    }
                    controlResults.addAll(evaluateStepControls(stepDef, run.getId(), runData));
                    break;
                }

                case DROOLS_RULE:
                case DROOLS_INFERENCE: {
                    if (stepExecutionDispatcher == null) {
                        StepExecution failed = StepExecution.builder()
                                .stepId(se.getStepId()).stepName(se.getStepName())
                                .status(StepExecutionStatus.FAILED).startedAt(now).completedAt(now)
                                .graphNodeIds(stepDef.getGraphNodeIds())
                                .error(stepType + " step requires StepExecutionDispatcher but none is wired")
                                .build();
                        stepExecutions.set(i, failed);
                        runStatus = RunStatus.FAILED;
                        return buildRun(run, stepExecutions, pendingApprovals, controlResults, runData, runStatus);
                    }
                    if (stepDef.getDroolsRuleDrl() == null || stepDef.getDroolsRuleDrl().isBlank()) {
                        StepExecution failed = StepExecution.builder()
                                .stepId(se.getStepId()).stepName(se.getStepName())
                                .status(StepExecutionStatus.FAILED).startedAt(now).completedAt(now)
                                .graphNodeIds(stepDef.getGraphNodeIds())
                                .error(stepType + " step requires droolsRuleDrl but none is provided")
                                .build();
                        stepExecutions.set(i, failed);
                        runStatus = RunStatus.FAILED;
                        return buildRun(run, stepExecutions, pendingApprovals, controlResults, runData, runStatus);
                    }

                    Map<String, Object> facts = enrichDroolsFacts(stepDef, extractInputsOrAllRunData(stepDef, runData), runData);
                    String inputHash = sha256(facts);

                    try {
                        Map<String, Object> droolsOutputs = stepExecutionDispatcher.executeDroolsRules(
                                stepDef.getDroolsRuleDrl(), facts, stepDef.getDroolsAgendaGroup(),
                                stepDef.getDroolsMaxFirings(), stepType == StepType.DROOLS_INFERENCE);
                        Map<String, Object> outputs = applyDroolsOutputs(stepDef, droolsOutputs);
                        runData.putAll(outputs);

                        StepExecution completed = StepExecution.builder()
                                .stepId(se.getStepId()).stepName(se.getStepName())
                                .status(StepExecutionStatus.COMPLETED)
                                .startedAt(now).completedAt(Instant.now())
                                .executedBy("drools:" + stepType.name().toLowerCase())
                                .inputs(facts).outputs(outputs)
                                .inputHash(inputHash).outputHash(sha256(outputs))
                                .graphNodeIds(stepDef.getGraphNodeIds())
                                .build();
                        stepExecutions.set(i, completed);
                        completedStepIds.add(se.getStepId());
                    } catch (Exception e) {
                        StepExecution failed = StepExecution.builder()
                                .stepId(se.getStepId()).stepName(se.getStepName())
                                .status(StepExecutionStatus.FAILED).startedAt(now).completedAt(Instant.now())
                                .inputs(facts).inputHash(inputHash)
                                .graphNodeIds(stepDef.getGraphNodeIds())
                                .error(stepType + " execution failed: " + e.getMessage())
                                .build();
                        stepExecutions.set(i, failed);
                        runStatus = RunStatus.FAILED;
                        return buildRun(run, stepExecutions, pendingApprovals, controlResults, runData, runStatus);
                    }
                    controlResults.addAll(evaluateStepControls(stepDef, run.getId(), runData));
                    break;
                }

                case DROOLS_DECISION_TABLE: {
                    if (stepExecutionDispatcher == null) {
                        StepExecution failed = StepExecution.builder()
                                .stepId(se.getStepId()).stepName(se.getStepName())
                                .status(StepExecutionStatus.FAILED).startedAt(now).completedAt(now)
                                .graphNodeIds(stepDef.getGraphNodeIds())
                                .error("DROOLS_DECISION_TABLE step requires StepExecutionDispatcher but none is wired")
                                .build();
                        stepExecutions.set(i, failed);
                        runStatus = RunStatus.FAILED;
                        return buildRun(run, stepExecutions, pendingApprovals, controlResults, runData, runStatus);
                    }
                    if (stepDef.getDroolsDecisionTable() == null || stepDef.getDroolsDecisionTable().isBlank()) {
                        StepExecution failed = StepExecution.builder()
                                .stepId(se.getStepId()).stepName(se.getStepName())
                                .status(StepExecutionStatus.FAILED).startedAt(now).completedAt(now)
                                .graphNodeIds(stepDef.getGraphNodeIds())
                                .error("DROOLS_DECISION_TABLE step requires droolsDecisionTable but none is provided")
                                .build();
                        stepExecutions.set(i, failed);
                        runStatus = RunStatus.FAILED;
                        return buildRun(run, stepExecutions, pendingApprovals, controlResults, runData, runStatus);
                    }

                    Map<String, Object> facts = enrichDroolsFacts(stepDef, extractInputsOrAllRunData(stepDef, runData), runData);
                    String inputHash = sha256(facts);

                    try {
                        Map<String, Object> tableOutputs = stepExecutionDispatcher.executeDroolsDecisionTable(
                                stepDef.getDroolsDecisionTable(), stepDef.getDroolsInputType(),
                                facts, stepDef.getDroolsWorksheetName());
                        Map<String, Object> outputs = applyDroolsOutputs(stepDef, tableOutputs);
                        runData.putAll(outputs);

                        StepExecution completed = StepExecution.builder()
                                .stepId(se.getStepId()).stepName(se.getStepName())
                                .status(StepExecutionStatus.COMPLETED)
                                .startedAt(now).completedAt(Instant.now())
                                .executedBy("drools:decision-table")
                                .inputs(facts).outputs(outputs)
                                .inputHash(inputHash).outputHash(sha256(outputs))
                                .graphNodeIds(stepDef.getGraphNodeIds())
                                .build();
                        stepExecutions.set(i, completed);
                        completedStepIds.add(se.getStepId());
                    } catch (Exception e) {
                        StepExecution failed = StepExecution.builder()
                                .stepId(se.getStepId()).stepName(se.getStepName())
                                .status(StepExecutionStatus.FAILED).startedAt(now).completedAt(Instant.now())
                                .inputs(facts).inputHash(inputHash)
                                .graphNodeIds(stepDef.getGraphNodeIds())
                                .error("Drools decision table execution failed: " + e.getMessage())
                                .build();
                        stepExecutions.set(i, failed);
                        runStatus = RunStatus.FAILED;
                        return buildRun(run, stepExecutions, pendingApprovals, controlResults, runData, runStatus);
                    }
                    controlResults.addAll(evaluateStepControls(stepDef, run.getId(), runData));
                    break;
                }

                case WORKFLOW: {
                    if (stepExecutionDispatcher == null) {
                        StepExecution failed = StepExecution.builder()
                                .stepId(se.getStepId()).stepName(se.getStepName())
                                .status(StepExecutionStatus.FAILED).startedAt(now).completedAt(now)
                                .graphNodeIds(stepDef.getGraphNodeIds())
                                .error("WORKFLOW step requires StepExecutionDispatcher but none is wired")
                                .build();
                        stepExecutions.set(i, failed);
                        runStatus = RunStatus.FAILED;
                        return buildRun(run, stepExecutions, pendingApprovals, controlResults, runData, runStatus);
                    }

                    Map<String, Object> workflowInputs = mappedOrAllRunData(stepDef.getWorkflowInputMapping(), runData);
                    String inputHash = sha256(workflowInputs);

                    try {
                        Map<String, Object> workflowOutputs = stepExecutionDispatcher.executeWorkflow(
                                stepDef.getWorkflowEngineType(), stepDef.getWorkflowName(),
                                stepDef.getWorkflowInlineContent(), workflowInputs,
                                stepDef.getWorkflowTimeoutSeconds());
                        Map<String, Object> outputs = applyOutputKey(stepDef.getWorkflowOutputKey(), workflowOutputs);
                        runData.putAll(outputs);

                        StepExecution completed = StepExecution.builder()
                                .stepId(se.getStepId()).stepName(se.getStepName())
                                .status(StepExecutionStatus.COMPLETED)
                                .startedAt(now).completedAt(Instant.now())
                                .executedBy("workflow:" + firstNonBlank(stepDef.getWorkflowEngineType(), "unknown"))
                                .inputs(workflowInputs).outputs(outputs)
                                .inputHash(inputHash).outputHash(sha256(outputs))
                                .graphNodeIds(stepDef.getGraphNodeIds())
                                .build();
                        stepExecutions.set(i, completed);
                        completedStepIds.add(se.getStepId());
                    } catch (Exception e) {
                        StepExecution failed = StepExecution.builder()
                                .stepId(se.getStepId()).stepName(se.getStepName())
                                .status(StepExecutionStatus.FAILED).startedAt(now).completedAt(Instant.now())
                                .inputs(workflowInputs).inputHash(inputHash)
                                .graphNodeIds(stepDef.getGraphNodeIds())
                                .error("Workflow execution failed: " + e.getMessage())
                                .build();
                        stepExecutions.set(i, failed);
                        runStatus = RunStatus.FAILED;
                        return buildRun(run, stepExecutions, pendingApprovals, controlResults, runData, runStatus);
                    }
                    controlResults.addAll(evaluateStepControls(stepDef, run.getId(), runData));
                    break;
                }

                case PIPELINE: {
                    // Execute a kompile pipeline as a workflow step
                    if (stepExecutionDispatcher == null) {
                        StepExecution failed = StepExecution.builder()
                                .stepId(se.getStepId()).stepName(se.getStepName())
                                .status(StepExecutionStatus.FAILED).startedAt(now).completedAt(now)
                                .graphNodeIds(stepDef.getGraphNodeIds())
                                .error("PIPELINE step requires StepExecutionDispatcher but none is wired")
                                .build();
                        stepExecutions.set(i, failed);
                        runStatus = RunStatus.FAILED;
                        return buildRun(run, stepExecutions, pendingApprovals, controlResults, runData, runStatus);
                    }

                    if (stepDef.getPipelineDefinitionJson() == null || stepDef.getPipelineDefinitionJson().isBlank()) {
                        StepExecution failed = StepExecution.builder()
                                .stepId(se.getStepId()).stepName(se.getStepName())
                                .status(StepExecutionStatus.FAILED).startedAt(now).completedAt(now)
                                .graphNodeIds(stepDef.getGraphNodeIds())
                                .error("PIPELINE step requires pipelineDefinitionJson but none is provided")
                                .build();
                        stepExecutions.set(i, failed);
                        runStatus = RunStatus.FAILED;
                        return buildRun(run, stepExecutions, pendingApprovals, controlResults, runData, runStatus);
                    }

                    // Map runData keys to pipeline input keys
                    Map<String, Object> pipelineInputs = new HashMap<>();
                    if (stepDef.getPipelineInputMapping() != null && !stepDef.getPipelineInputMapping().isEmpty()) {
                        for (Map.Entry<String, String> mapping : stepDef.getPipelineInputMapping().entrySet()) {
                            String runDataKey = mapping.getKey();
                            String pipelineKey = mapping.getValue();
                            if (runData.containsKey(runDataKey)) {
                                pipelineInputs.put(pipelineKey, runData.get(runDataKey));
                            }
                        }
                    } else {
                        // No explicit mapping — pass all runData as pipeline inputs
                        pipelineInputs.putAll(runData);
                    }

                    Map<String, Object> inputs = new HashMap<>(pipelineInputs);
                    inputs.put("_pipelineJson", stepDef.getPipelineDefinitionJson());
                    String inputHash = sha256(inputs);

                    try {
                        Map<String, Object> pipelineOutputs = stepExecutionDispatcher.executePipeline(
                                stepDef.getPipelineDefinitionJson(), pipelineInputs);

                        // Store outputs under pipelineOutputKey or merge directly
                        String outputKey = stepDef.getPipelineOutputKey();
                        Map<String, Object> outputs;
                        if (outputKey != null && !outputKey.isBlank()) {
                            outputs = new HashMap<>();
                            outputs.put(outputKey, pipelineOutputs);
                            // Also include non-meta outputs at top level
                            for (Map.Entry<String, Object> e : pipelineOutputs.entrySet()) {
                                if (!e.getKey().startsWith("_")) {
                                    outputs.put(e.getKey(), e.getValue());
                                }
                            }
                        } else {
                            outputs = new HashMap<>(pipelineOutputs);
                        }

                        runData.putAll(outputs);
                        String outputHash = sha256(outputs);

                        StepExecution completed = StepExecution.builder()
                                .stepId(se.getStepId()).stepName(se.getStepName())
                                .status(StepExecutionStatus.COMPLETED)
                                .startedAt(now).completedAt(Instant.now())
                                .executedBy("pipeline")
                                .inputs(inputs).outputs(outputs)
                                .inputHash(inputHash).outputHash(outputHash)
                                .graphNodeIds(stepDef.getGraphNodeIds())
                                .build();
                        stepExecutions.set(i, completed);
                        completedStepIds.add(se.getStepId());
                    } catch (Exception e) {
                        StepExecution failed = StepExecution.builder()
                                .stepId(se.getStepId()).stepName(se.getStepName())
                                .status(StepExecutionStatus.FAILED).startedAt(now).completedAt(Instant.now())
                                .inputs(inputs).inputHash(inputHash)
                                .graphNodeIds(stepDef.getGraphNodeIds())
                                .error("Pipeline execution failed: " + e.getMessage())
                                .build();
                        stepExecutions.set(i, failed);
                        runStatus = RunStatus.FAILED;
                        return buildRun(run, stepExecutions, pendingApprovals, controlResults, runData, runStatus);
                    }
                    controlResults.addAll(evaluateStepControls(stepDef, run.getId(), runData));
                    break;
                }

                default:
                    log.warn("Unknown step type {} for stepId={}, skipping", stepType, se.getStepId());
                    stepExecutions.set(i, updateStepStatus(se, StepExecutionStatus.SKIPPED, null));
            }
        }

        // If we fall through all steps without pausing, check if everything is done
        boolean allDone = stepExecutions.stream().allMatch(
                se -> se.getStatus() == StepExecutionStatus.COMPLETED
                   || se.getStatus() == StepExecutionStatus.SKIPPED);
        boolean anyFailed = stepExecutions.stream().anyMatch(
                se -> se.getStatus() == StepExecutionStatus.FAILED);

        if (anyFailed) {
            runStatus = RunStatus.FAILED;
        } else if (allDone) {
            runStatus = RunStatus.COMPLETED;
        }

        return buildRun(run, stepExecutions, pendingApprovals, controlResults, runData, runStatus);
    }

    // ---------------------------------------------------------------------------
    // Private helpers — run construction
    // ---------------------------------------------------------------------------

    private WorkflowRun buildRun(WorkflowRun original,
                                  List<StepExecution> stepExecutions,
                                  List<ApprovalRequest> pendingApprovals,
                                  List<ControlAttestation> controlResults,
                                  Map<String, Object> runData,
                                  RunStatus status) {
        Instant completedAt = (status == RunStatus.COMPLETED || status == RunStatus.FAILED)
                ? Instant.now() : original.getCompletedAt();

        // Union all graphNodeIds from step executions into the run
        List<String> allGraphNodeIds = new ArrayList<>();
        if (original.getGraphNodeIds() != null) {
            allGraphNodeIds.addAll(original.getGraphNodeIds());
        }
        for (StepExecution se : stepExecutions) {
            if (se.getGraphNodeIds() != null) {
                for (String gid : se.getGraphNodeIds()) {
                    if (!allGraphNodeIds.contains(gid)) {
                        allGraphNodeIds.add(gid);
                    }
                }
            }
        }

        WorkflowRun run = WorkflowRun.builder()
                .id(original.getId())
                .processDefinitionId(original.getProcessDefinitionId())
                .processVersion(original.getProcessVersion())
                .ontologySnapshotId(original.getOntologySnapshotId())
                .status(status)
                .startedAt(original.getStartedAt())
                .completedAt(completedAt)
                .estimatedCompletion(original.getEstimatedCompletion())
                .stepExecutions(stepExecutions)
                .pendingApprovals(pendingApprovals)
                .controlResults(controlResults)
                .runData(runData)
                .graphNodeIds(allGraphNodeIds.isEmpty() ? null : allGraphNodeIds)
                .metrics(original.getMetrics())
                .build();

        // Fire graph callbacks for newly completed/failed steps
        if (processGraphCallback != null) {
            // Build a set of previously-terminal step IDs to avoid re-notifying
            Set<String> previouslyTerminal = new HashSet<>();
            if (original.getStepExecutions() != null) {
                for (StepExecution oldSe : original.getStepExecutions()) {
                    if (oldSe.getStatus() == StepExecutionStatus.COMPLETED
                            || oldSe.getStatus() == StepExecutionStatus.FAILED) {
                        previouslyTerminal.add(oldSe.getStepId());
                    }
                }
            }
            for (StepExecution se : stepExecutions) {
                if ((se.getStatus() == StepExecutionStatus.COMPLETED
                        || se.getStatus() == StepExecutionStatus.FAILED)
                        && !previouslyTerminal.contains(se.getStepId())) {
                    try {
                        processGraphCallback.onStepCompleted(run, se);
                    } catch (Exception e) {
                        log.debug("Graph callback onStepCompleted failed for step {}: {}",
                                se.getStepId(), e.getMessage());
                    }
                }
            }

            // Fire run-level callback for terminal statuses
            if (status == RunStatus.COMPLETED || status == RunStatus.FAILED
                    || status == RunStatus.CANCELLED) {
                try {
                    processGraphCallback.onRunCompleted(run);
                } catch (Exception e) {
                    log.debug("Graph callback onRunCompleted failed for run {}: {}",
                            run.getId(), e.getMessage());
                }
            }
        }

        return run;
    }

    private StepExecution updateStepStatus(StepExecution se, StepExecutionStatus status, String error) {
        return StepExecution.builder()
                .stepId(se.getStepId())
                .stepName(se.getStepName())
                .status(status)
                .startedAt(se.getStartedAt())
                .completedAt(Instant.now())
                .executedBy(se.getExecutedBy())
                .inputs(se.getInputs())
                .outputs(se.getOutputs())
                .inputHash(se.getInputHash())
                .outputHash(se.getOutputHash())
                .evidenceReliedOn(se.getEvidenceReliedOn())
                .error(error)
                .build();
    }

    private Map<String, ProcessStep> buildStepDefMap(ProcessDefinition def) {
        Map<String, ProcessStep> map = new HashMap<>();
        if (def.getPhases() != null) {
            for (ProcessPhase phase : def.getPhases()) {
                if (phase.getSteps() != null) {
                    for (ProcessStep step : phase.getSteps()) {
                        map.put(step.getId(), step);
                    }
                }
            }
        }
        return map;
    }

    private Map<String, Object> extractInputs(ProcessStep stepDef, Map<String, Object> runData) {
        if (stepDef.getInputKeys() == null || stepDef.getInputKeys().isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> inputs = new HashMap<>();
        for (String key : stepDef.getInputKeys()) {
            if (runData.containsKey(key)) {
                inputs.put(key, runData.get(key));
            }
        }
        return inputs;
    }

    private Map<String, Object> extractInputsOrAllRunData(ProcessStep stepDef, Map<String, Object> runData) {
        if (runData == null || runData.isEmpty()) {
            return new HashMap<>();
        }
        if (stepDef.getInputKeys() == null || stepDef.getInputKeys().isEmpty()) {
            return new HashMap<>(runData);
        }
        return extractInputs(stepDef, runData);
    }

    private Map<String, Object> mappedOrAllRunData(Map<String, String> mapping, Map<String, Object> runData) {
        Map<String, Object> inputs = new HashMap<>();
        if (runData == null || runData.isEmpty()) {
            return inputs;
        }
        if (mapping == null || mapping.isEmpty()) {
            inputs.putAll(runData);
            return inputs;
        }
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            if (runData.containsKey(entry.getKey())) {
                inputs.put(entry.getValue(), runData.get(entry.getKey()));
            }
        }
        return inputs;
    }

    private Map<String, Object> applyOutputKey(String outputKey, Map<String, Object> rawOutputs) {
        Map<String, Object> outputs = new HashMap<>();
        Map<String, Object> safeOutputs = rawOutputs != null ? rawOutputs : Collections.emptyMap();
        if (outputKey != null && !outputKey.isBlank()) {
            outputs.put(outputKey, safeOutputs);
            for (Map.Entry<String, Object> entry : safeOutputs.entrySet()) {
                if (!entry.getKey().startsWith("_")) {
                    outputs.put(entry.getKey(), entry.getValue());
                }
            }
        } else {
            outputs.putAll(safeOutputs);
        }
        return outputs;
    }

    private boolean isStepConditionSatisfied(ProcessStep stepDef, Map<String, Object> runData) {
        String expression = stepDef.getConditionExpression();
        if (expression == null || expression.isBlank()) {
            return true;
        }
        try {
            return evaluateSpelBoolean(expression, runData);
        } catch (Exception e) {
            log.warn("Condition expression failed for step {}: {}", stepDef.getId(), e.getMessage());
            return false;
        }
    }

    private AccessDecision checkStepAccess(ProcessStep stepDef, Map<String, Object> runData) {
        List<String> requiredRoles = nonBlankList(stepDef.getRequiredRoles());
        List<String> requiredPermissions = nonBlankList(stepDef.getRequiredPermissions());
        List<String> actorRoles = resolveActorRoles(runData);
        List<String> actorPermissions = resolveActorPermissions(runData);

        if (!requiredRoles.isEmpty() && !containsAnyIgnoreCase(actorRoles, requiredRoles)) {
            return new AccessDecision(false,
                    "Step requires one of roles " + requiredRoles + " but actor roles were " + actorRoles,
                    actorRoles,
                    actorPermissions);
        }

        List<String> missingPermissions = missingIgnoreCase(actorPermissions, requiredPermissions);
        if (!missingPermissions.isEmpty()) {
            return new AccessDecision(false,
                    "Step requires permissions " + requiredPermissions + " but actor is missing " + missingPermissions,
                    actorRoles,
                    actorPermissions);
        }

        return new AccessDecision(true, null, actorRoles, actorPermissions);
    }

    private Map<String, Object> enrichDroolsFacts(ProcessStep stepDef,
                                                  Map<String, Object> baseFacts,
                                                  Map<String, Object> runData) {
        Map<String, Object> facts = new HashMap<>();
        if (baseFacts != null) {
            facts.putAll(baseFacts);
        }
        facts.put("_stepId", stepDef.getId());
        facts.put("_stepName", stepDef.getName());
        facts.put("_stepType", stepDef.getStepType() != null ? stepDef.getStepType().name() : StepType.AUTO.name());

        Object actor = resolveActor(runData);
        if (actor != null) {
            facts.put("_actor", actor);
        }

        List<String> actorRoles = resolveActorRoles(runData);
        if (!actorRoles.isEmpty()) {
            facts.put("_roles", actorRoles);
        }
        List<String> actorPermissions = resolveActorPermissions(runData);
        if (!actorPermissions.isEmpty()) {
            facts.put("_permissions", actorPermissions);
        }

        List<String> requiredRoles = nonBlankList(stepDef.getRequiredRoles());
        if (!requiredRoles.isEmpty()) {
            facts.put("_requiredRoles", requiredRoles);
        }
        List<String> requiredPermissions = nonBlankList(stepDef.getRequiredPermissions());
        if (!requiredPermissions.isEmpty()) {
            facts.put("_requiredPermissions", requiredPermissions);
        }

        return facts;
    }

    private Map<String, Object> applyDroolsOutputs(ProcessStep stepDef, Map<String, Object> rawOutputs) {
        Map<String, Object> outputs = applyOutputKey(stepDef.getDroolsOutputKey(), rawOutputs);
        Map<String, Object> safeOutputs = rawOutputs != null ? rawOutputs : Collections.emptyMap();
        String decisionKey = firstNonBlank(stepDef.getDroolsDecisionKey(), "decision");
        if (safeOutputs.containsKey(decisionKey)) {
            Object decision = safeOutputs.get(decisionKey);
            outputs.putIfAbsent("decision", decision);
            outputs.put(safeStepId(stepDef.getId()) + "_decision", decision);
        }
        return outputs;
    }

    private List<String> resolveActorRoles(Map<String, Object> runData) {
        LinkedHashSet<String> roles = new LinkedHashSet<>();
        collectStrings(roles, lookup(runData, "_roles", "roles", "userRoles", "_userRoles", "actorRoles"));
        Object actor = resolveActor(runData);
        if (actor instanceof Map<?, ?> actorMap) {
            collectStrings(roles, lookup(actorMap, "_roles", "roles", "userRoles", "_userRoles", "actorRoles"));
        }
        return new ArrayList<>(roles);
    }

    private List<String> resolveActorPermissions(Map<String, Object> runData) {
        LinkedHashSet<String> permissions = new LinkedHashSet<>();
        collectStrings(permissions, lookup(runData, "_permissions", "permissions", "userPermissions",
                "_userPermissions", "actorPermissions"));
        Object actor = resolveActor(runData);
        if (actor instanceof Map<?, ?> actorMap) {
            collectStrings(permissions, lookup(actorMap, "_permissions", "permissions", "userPermissions",
                    "_userPermissions", "actorPermissions"));
        }
        return new ArrayList<>(permissions);
    }

    private Object resolveActor(Map<String, Object> runData) {
        return lookup(runData, "_actor", "actor", "_user", "user", "username", "userId");
    }

    private Object lookup(Map<?, ?> data, String... keys) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            if (data.containsKey(key)) {
                return data.get(key);
            }
        }
        return null;
    }

    private void collectStrings(LinkedHashSet<String> target, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                collectStrings(target, item);
            }
            return;
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                collectStrings(target, java.lang.reflect.Array.get(value, i));
            }
            return;
        }
        String text = value.toString();
        for (String token : text.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                target.add(trimmed);
            }
        }
    }

    private List<String> nonBlankList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toList());
    }

    private boolean containsAnyIgnoreCase(List<String> actual, List<String> required) {
        if (required.isEmpty()) {
            return true;
        }
        Set<String> normalizedActual = actual.stream()
                .map(value -> value.toLowerCase(java.util.Locale.ROOT))
                .collect(Collectors.toSet());
        return required.stream()
                .map(value -> value.toLowerCase(java.util.Locale.ROOT))
                .anyMatch(normalizedActual::contains);
    }

    private List<String> missingIgnoreCase(List<String> actual, List<String> required) {
        if (required.isEmpty()) {
            return List.of();
        }
        Set<String> normalizedActual = actual.stream()
                .map(value -> value.toLowerCase(java.util.Locale.ROOT))
                .collect(Collectors.toSet());
        return required.stream()
                .filter(value -> !normalizedActual.contains(value.toLowerCase(java.util.Locale.ROOT)))
                .collect(Collectors.toList());
    }

    private String safeStepId(String stepId) {
        if (stepId == null || stepId.isBlank()) {
            return "drools";
        }
        return stepId.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private record AccessDecision(boolean allowed,
                                  String reason,
                                  List<String> actorRoles,
                                  List<String> actorPermissions) {
    }

    private String firstNonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    /**
     * Evaluates the SpEL execution expressions defined on a step against the current runData.
     * Returns a map of output key → evaluated value. If no expressions are defined, returns empty map.
     */
    private Map<String, Object> executeSpelExpressions(ProcessStep stepDef, Map<String, Object> runData) {
        Map<String, Object> outputs = new HashMap<>();
        if (stepDef.getExecutionExpressions() == null || stepDef.getExecutionExpressions().isEmpty()) {
            return outputs;
        }

        StandardEvaluationContext ctx = new StandardEvaluationContext();
        if (runData != null) {
            for (Map.Entry<String, Object> entry : runData.entrySet()) {
                ctx.setVariable(entry.getKey(), entry.getValue());
            }
        }

        for (Map.Entry<String, String> expr : stepDef.getExecutionExpressions().entrySet()) {
            String outputKey = expr.getKey();
            String expression = expr.getValue();
            if (expression == null || expression.isBlank()) continue;
            try {
                Expression parsed = spelParser.parseExpression(expression);
                Object result = parsed.getValue(ctx);
                outputs.put(outputKey, result);
                // Also make newly computed values available for subsequent expressions in same step
                ctx.setVariable(outputKey, result);
            } catch (Exception e) {
                log.warn("SpEL execution failed for step {} output '{}': {}",
                        stepDef.getId(), outputKey, e.getMessage());
                outputs.put(outputKey + "_error", e.getMessage());
            }
        }
        return outputs;
    }

    /**
     * Validates the current runData against the ontology bound to the run.
     * Returns a list of violation descriptions (empty if valid or no ontology bound).
     */
    private List<String> validateRunDataAgainstOntology(WorkflowRun run, Map<String, Object> runData) {
        List<String> violations = new ArrayList<>();
        if (run.getOntologySnapshotId() == null) return violations;

        int ontologyVersion = ontologyVersions.getOrDefault(run.getOntologySnapshotId(), 0);
        if (ontologyVersion == 0) return violations;

        OntologySchema schema;
        try {
            schema = getOntology(run.getOntologySnapshotId(), ontologyVersion);
        } catch (Exception e) {
            return violations; // ontology not found — skip validation
        }

        if (schema.getEntityTypes() == null) return violations;

        for (EntityTypeDefinition entityType : schema.getEntityTypes()) {
            // Validate field constraints
            if (entityType.getFields() != null) {
                for (FieldDefinition field : entityType.getFields()) {
                    List<String> fieldViolations = validateFieldConstraint(field, runData);
                    violations.addAll(fieldViolations);
                }
            }

            // Validate SpEL rules
            if (entityType.getRules() != null) {
                for (ValidationRule rule : entityType.getRules()) {
                    if (rule.getExpression() == null || rule.getExpression().isBlank()) continue;
                    try {
                        boolean passed = evaluateSpelBoolean(rule.getExpression(), runData);
                        if (!passed) {
                            violations.add(String.format("Rule '%s' on entity '%s' violated: %s",
                                    rule.getName(), entityType.getName(), rule.getExpression()));
                        }
                    } catch (Exception e) {
                        // Expression references variables not yet in runData — skip silently
                        log.trace("Ontology rule '{}' skipped (likely missing variables): {}",
                                rule.getName(), e.getMessage());
                    }
                }
            }
        }
        return violations;
    }

    /**
     * Validates a single field constraint from the ontology against runData.
     * Checks: required, min, max, regex, enum values.
     */
    private List<String> validateFieldConstraint(FieldDefinition field, Map<String, Object> runData) {
        List<String> violations = new ArrayList<>();
        String fieldName = field.getName();
        Object value = runData.get(fieldName);

        // Required check
        if (field.isRequired() && value == null) {
            violations.add(String.format("Required field '%s' is missing", fieldName));
            return violations; // no point checking further
        }

        if (value == null) return violations;

        // Min/max for numeric fields
        if (field.getMin() != null || field.getMax() != null) {
            double numVal;
            try {
                numVal = ((Number) value).doubleValue();
                if (field.getMin() != null && numVal < field.getMin()) {
                    violations.add(String.format("Field '%s' value %.2f is below minimum %.2f",
                            fieldName, numVal, field.getMin()));
                }
                if (field.getMax() != null && numVal > field.getMax()) {
                    violations.add(String.format("Field '%s' value %.2f exceeds maximum %.2f",
                            fieldName, numVal, field.getMax()));
                }
            } catch (ClassCastException e) {
                // Not a number — skip numeric checks
            }
        }

        // Regex for string fields
        if (field.getRegex() != null && value instanceof String) {
            if (!((String) value).matches(field.getRegex())) {
                violations.add(String.format("Field '%s' value '%s' does not match pattern '%s'",
                        fieldName, value, field.getRegex()));
            }
        }

        // Enum validation
        if (field.getEnumValues() != null && !field.getEnumValues().isEmpty()) {
            if (value instanceof String) {
                if (!field.getEnumValues().contains(value)) {
                    violations.add(String.format("Field '%s' value '%s' not in allowed values: %s",
                            fieldName, value, field.getEnumValues()));
                }
            } else if (value instanceof List) {
                for (Object item : (List<?>) value) {
                    if (!field.getEnumValues().contains(String.valueOf(item))) {
                        violations.add(String.format("Field '%s' array item '%s' not in allowed values: %s",
                                fieldName, item, field.getEnumValues()));
                    }
                }
            }
        }

        // MaxLength for string fields
        if (field.getMaxLength() != null && value instanceof String) {
            if (((String) value).length() > field.getMaxLength()) {
                violations.add(String.format("Field '%s' length %d exceeds maximum %d",
                        fieldName, ((String) value).length(), field.getMaxLength()));
            }
        }

        return violations;
    }

    /**
     * Resolves tool arguments for a TOOL_CALL step by evaluating SpEL expressions
     * in toolArguments against runData. Literal values are passed through.
     */
    private Map<String, Object> resolveToolArguments(ProcessStep stepDef, Map<String, Object> runData) {
        Map<String, Object> args = new HashMap<>();
        if (stepDef.getToolArguments() == null) return args;

        StandardEvaluationContext ctx = new StandardEvaluationContext();
        if (runData != null) {
            runData.forEach(ctx::setVariable);
        }

        for (Map.Entry<String, String> entry : stepDef.getToolArguments().entrySet()) {
            String argName = entry.getKey();
            String expr = entry.getValue();
            if (expr == null) continue;
            try {
                // Try to parse as SpEL expression
                if (expr.startsWith("#") || expr.startsWith("T(") || expr.contains("?")
                        || expr.contains("+") || expr.contains("*")) {
                    Expression parsed = spelParser.parseExpression(expr);
                    args.put(argName, parsed.getValue(ctx));
                } else {
                    // Literal value — pass through
                    args.put(argName, expr);
                }
            } catch (Exception e) {
                // Expression parse failed — treat as literal string
                args.put(argName, expr);
            }
        }
        return args;
    }

    /**
     * Resolves a string that may contain SpEL expressions (prefixed with #).
     */
    private String resolveSpelString(String template, Map<String, Object> runData) {
        if (template == null) return null;
        if (!template.contains("#")) return template;
        try {
            StandardEvaluationContext ctx = new StandardEvaluationContext();
            if (runData != null) runData.forEach(ctx::setVariable);
            Expression expr = spelParser.parseExpression("'" + template.replace("'", "\\'") + "'");
            // For simple variable references, just evaluate directly
            if (template.startsWith("#")) {
                expr = spelParser.parseExpression(template);
            }
            Object result = expr.getValue(ctx);
            return result != null ? result.toString() : template;
        } catch (Exception e) {
            return template;
        }
    }

    /**
     * Resolves HTTP headers, evaluating SpEL expressions in values.
     */
    private Map<String, String> resolveHeaders(Map<String, String> headers, Map<String, Object> runData) {
        if (headers == null) return null;
        Map<String, String> resolved = new HashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            resolved.put(entry.getKey(), resolveSpelString(entry.getValue(), runData));
        }
        return resolved;
    }

    /**
     * Evaluates a SpEL expression and returns the raw Object result.
     */
    private Object evaluateSpelObject(String expression, Map<String, Object> runData) {
        StandardEvaluationContext ctx = new StandardEvaluationContext();
        if (runData != null) {
            runData.forEach(ctx::setVariable);
        }
        Expression expr = spelParser.parseExpression(expression);
        return expr.getValue(ctx);
    }

    private ApprovalRequest createApprovalRequest(String runId, ProcessStep stepDef,
                                                   Map<String, Object> inputs) {
        String requestId = UUID.randomUUID().toString();
        String evidenceHash = sha256(inputs);
        Instant createdAt = Instant.now();

        String assignedTo = null;
        if (stepDef.getApprovalPolicy() != null
                && stepDef.getApprovalPolicy().getApproverPool() != null
                && !stepDef.getApprovalPolicy().getApproverPool().isEmpty()) {
            assignedTo = stepDef.getApprovalPolicy().getApproverPool().get(0);
        }

        return ApprovalRequest.builder()
                .id(requestId)
                .workflowRunId(runId)
                .stepId(stepDef.getId())
                .stepName(stepDef.getName())
                .status(ApprovalRequestStatus.PENDING)
                .createdAt(createdAt)
                .assignedTo(assignedTo)
                .items(Collections.emptyList())
                .context(new HashMap<>(inputs))
                .evidenceReliedOn(Collections.emptyList())
                .evidenceHashAtCreation(evidenceHash)
                .build();
    }

    private List<ControlAttestation> evaluateStepControls(ProcessStep stepDef,
                                                           String runId,
                                                           Map<String, Object> runData) {
        List<ControlAttestation> results = new ArrayList<>();
        if (stepDef.getControlIds() == null) return results;
        for (String controlId : stepDef.getControlIds()) {
            if (controls.containsKey(controlId)) {
                try {
                    ControlAttestation a = evaluateControl(controlId, runId, runData);
                    results.add(a);
                } catch (Exception e) {
                    log.warn("Failed to evaluate control {} for step {}: {}", controlId, stepDef.getId(), e.getMessage());
                }
            } else {
                log.warn("Control definition {} referenced by step {} not found", controlId, stepDef.getId());
            }
        }
        return results;
    }

    private boolean isHardGate(String controlId) {
        ControlDefinition def = controls.get(controlId);
        return def != null && def.getGateType() == ControlGateType.HARD;
    }

    private ProcessDefinition cloneDefinitionWithIdVersionStatus(ProcessDefinition src,
                                                                  String id,
                                                                  int version,
                                                                  ProcessStatus status) {
        return ProcessDefinition.builder()
                .id(id)
                .name(src.getName())
                .version(version)
                .ontologySchemaId(src.getOntologySchemaId())
                .ontologyVersion(src.getOntologyVersion())
                .status(status)
                .approvedBy(src.getApprovedBy())
                .approvedAt(src.getApprovedAt())
                .phases(src.getPhases())
                .controls(src.getControls())
                .agentSpecs(src.getAgentSpecs())
                .metadata(src.getMetadata())
                .build();
    }

    private ApprovalRequestStatus mapActionToRequestStatus(ApprovalAction action) {
        if (action == null) return ApprovalRequestStatus.PENDING;
        switch (action) {
            case APPROVE:
            case APPROVE_WITH_EDITS:
                return ApprovalRequestStatus.APPROVED;
            case REJECT:
                return ApprovalRequestStatus.REJECTED;
            case ESCALATE:
                return ApprovalRequestStatus.ESCALATED;
            case DELEGATE:
                return ApprovalRequestStatus.DELEGATED;
            default:
                return ApprovalRequestStatus.PENDING;
        }
    }

    private boolean isApproved(ApprovalAction action) {
        return action == ApprovalAction.APPROVE || action == ApprovalAction.APPROVE_WITH_EDITS;
    }

    private boolean isPendingFurtherAction(ApprovalAction action) {
        return action == ApprovalAction.ESCALATE
                || action == ApprovalAction.DELEGATE
                || action == ApprovalAction.REQUEST_INFO;
    }

    // ---------------------------------------------------------------------------
    // SpEL evaluation
    // ---------------------------------------------------------------------------

    /**
     * Evaluates a SpEL boolean expression against a data map.
     * The data entries are exposed as top-level variables (#key) in the context.
     *
     * @param expression the SpEL expression string
     * @param data       the variable bindings
     * @return true if the expression evaluates to Boolean.TRUE, false otherwise
     */
    private boolean evaluateSpelBoolean(String expression, Map<String, Object> data) {
        StandardEvaluationContext ctx = new StandardEvaluationContext();
        if (data != null) {
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                ctx.setVariable(entry.getKey(), entry.getValue());
            }
        }
        Expression expr = spelParser.parseExpression(expression);
        Object result = expr.getValue(ctx);
        if (result instanceof Boolean) {
            return (Boolean) result;
        }
        // Non-boolean results: truthy if non-null, non-zero number, non-empty string
        if (result == null) return false;
        if (result instanceof Number) return ((Number) result).doubleValue() != 0.0;
        return !result.toString().isEmpty();
    }

    // ---------------------------------------------------------------------------
    // SHA-256 utilities
    // ---------------------------------------------------------------------------

    /**
     * Merges static graph node IDs from the step definition with runtime-discovered IDs.
     * Returns a deduplicated list preserving order (static first, then discovered).
     */
    private List<String> mergeGraphNodeIds(List<String> staticIds, List<String> discoveredIds) {
        if ((staticIds == null || staticIds.isEmpty())
                && (discoveredIds == null || discoveredIds.isEmpty())) {
            return List.of();
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (staticIds != null) merged.addAll(staticIds);
        if (discoveredIds != null) merged.addAll(discoveredIds);
        return new ArrayList<>(merged);
    }

    /**
     * Computes a SHA-256 hex digest of the JSON-serialised map.
     *
     * @param data the map to hash (may be null or empty)
     * @return 64-character lowercase hex string
     */
    private String sha256(Map<String, Object> data) {
        try {
            String json = data == null ? "{}" : objectMapper.writeValueAsString(data);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            log.warn("SHA-256 computation failed: {}", e.getMessage());
            return "error";
        }
    }

    /**
     * Computes a SHA-256 hex digest of any serialisable object.
     */
    private String sha256Object(Object obj) {
        try {
            String json = objectMapper.writeValueAsString(obj);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            log.warn("SHA-256 computation failed: {}", e.getMessage());
            return "error";
        }
    }

    // ---------------------------------------------------------------------------
    // ID utilities
    // ---------------------------------------------------------------------------

    private static String versionedKey(String id, int version) {
        return id + "_v" + version;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    // ---------------------------------------------------------------------------
    // Persistence — write-through helpers
    // ---------------------------------------------------------------------------

    private void persistOntology(OntologySchema schema) {
        Path file = ontologyDir.resolve(versionedKey(schema.getId(), schema.getVersion()) + ".json");
        writeJson(file, schema);
    }

    private void persistDefinition(ProcessDefinition def) {
        Path file = definitionDir.resolve(versionedKey(def.getId(), def.getVersion()) + ".json");
        writeJson(file, def);
    }

    private void persistRun(WorkflowRun run) {
        Path file = runDir.resolve(run.getId() + ".json");
        writeJson(file, run);
    }

    private void persistControl(ControlDefinition control) {
        Path file = controlDir.resolve(control.getId() + ".json");
        writeJson(file, control);
    }

    private void persistManifest(SubmissionManifest manifest) {
        Path file = manifestDir.resolve(manifest.getId() + ".json");
        writeJson(file, manifest);
    }

    private void persistApproval(ApprovalRequest request) {
        Path file = approvalDir.resolve(request.getId() + ".json");
        writeJson(file, request);
    }

    private void persistAttestation(ControlAttestation attestation) {
        Path file = attestationDir.resolve(attestation.getId() + ".json");
        writeJson(file, attestation);
    }

    private void writeJson(Path path, Object value) {
        try {
            objectMapper.writeValue(path.toFile(), value);
        } catch (IOException e) {
            log.error("Failed to persist to {}: {}", path, e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------------------
    // Startup load — restore in-memory state from disk
    // ---------------------------------------------------------------------------

    private void loadAll() {
        loadOntologies();
        loadDefinitions();
        loadRuns();
        loadControls();
        loadManifests();
        loadApprovals();
        loadAttestations();
    }

    private void loadOntologies() {
        loadDirectory(ontologyDir, OntologySchema.class, (schema) -> {
            String key = versionedKey(schema.getId(), schema.getVersion());
            ontologies.put(key, schema);
            ontologyVersions.merge(schema.getId(), schema.getVersion(), Math::max);
        });
    }

    private void loadDefinitions() {
        loadDirectory(definitionDir, ProcessDefinition.class, (def) -> {
            String key = versionedKey(def.getId(), def.getVersion());
            definitions.put(key, def);
            definitionVersions.merge(def.getId(), def.getVersion(), Math::max);
        });
    }

    private void loadRuns() {
        loadDirectory(runDir, WorkflowRun.class, (run) -> runs.put(run.getId(), run));
    }

    private void loadControls() {
        loadDirectory(controlDir, ControlDefinition.class, (c) -> controls.put(c.getId(), c));
    }

    private void loadManifests() {
        loadDirectory(manifestDir, SubmissionManifest.class, (m) -> manifests.put(m.getId(), m));
    }

    private void loadApprovals() {
        loadDirectory(approvalDir, ApprovalRequest.class, (a) -> approvalRequests.put(a.getId(), a));
    }

    private void loadAttestations() {
        loadDirectory(attestationDir, ControlAttestation.class, (a) -> attestations.put(a.getId(), a));
    }

    @FunctionalInterface
    private interface Consumer<T> {
        void accept(T t);
    }

    private <T> void loadDirectory(Path dir, Class<T> type, Consumer<T> consumer) {
        if (!Files.isDirectory(dir)) return;
        try (var files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".json"))
                 .forEach(p -> {
                     try {
                         T value = objectMapper.readValue(p.toFile(), type);
                         consumer.accept(value);
                     } catch (IOException e) {
                         log.warn("Failed to load {} from {}: {}", type.getSimpleName(), p, e.getMessage());
                     }
                 });
        } catch (IOException e) {
            log.error("Failed to list directory {}: {}", dir, e.getMessage());
        }
    }
}
