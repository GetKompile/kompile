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
import ai.kompile.process.execution.StepExecutionStatus;
import ai.kompile.process.execution.WorkflowRun;
import ai.kompile.process.hitl.ApprovalAction;
import ai.kompile.process.hitl.ApprovalRequest;
import ai.kompile.process.hitl.ApprovalRequestStatus;
import ai.kompile.process.hitl.ApprovalResponse;
import ai.kompile.process.ingest.SubmissionManifest;
import ai.kompile.process.ontology.EntityTypeDefinition;
import ai.kompile.process.ontology.OntologySchema;
import ai.kompile.process.ontology.RuleSeverity;
import ai.kompile.process.ontology.RuleType;
import ai.kompile.process.ontology.ValidationRule;
import ai.kompile.process.workflow.ApprovalMode;
import ai.kompile.process.workflow.ApprovalPolicy;
import ai.kompile.process.workflow.ProcessDefinition;
import ai.kompile.process.workflow.ProcessPhase;
import ai.kompile.process.workflow.ProcessStatus;
import ai.kompile.process.workflow.ProcessStep;
import ai.kompile.process.workflow.StepType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ProcessEngineServiceImpl}.
 *
 * The service persists to {@code ~/.kompile/processes/}. To keep tests isolated
 * and side-effect-free we redirect {@code user.home} to a temporary directory
 * before calling {@link ProcessEngineServiceImpl#init()} and clean up afterwards.
 */
class ProcessEngineServiceImplTest {

    private static final String ORIGINAL_USER_HOME = System.getProperty("user.home");

    private Path tempHome;
    private ProcessEngineServiceImpl service;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @BeforeEach
    void setUp() throws IOException {
        tempHome = Files.createTempDirectory("kompile-process-test-");
        System.setProperty("user.home", tempHome.toString());

        service = new ProcessEngineServiceImpl();
        service.init();
    }

    @AfterEach
    void tearDown() throws IOException {
        System.setProperty("user.home", ORIGINAL_USER_HOME);

        // Recursively delete the temp directory
        if (tempHome != null && Files.exists(tempHome)) {
            Files.walk(tempHome)
                 .sorted(Comparator.reverseOrder())
                 .forEach(p -> {
                     try { Files.delete(p); } catch (IOException ignored) {}
                 });
        }
    }

    // -------------------------------------------------------------------------
    // Ontology CRUD
    // -------------------------------------------------------------------------

    @Test
    void createOntology_assignsIdAndVersionOne() {
        OntologySchema schema = OntologySchema.builder()
                .name("Test Ontology")
                .updatedBy("tester")
                .build();

        OntologySchema created = service.createOntology(schema);

        assertNotNull(created.getId(), "id must be assigned by the service");
        assertEquals(1, created.getVersion(), "first version must be 1");
        assertEquals("Test Ontology", created.getName());
        assertNotNull(created.getCreatedAt());
        assertNotNull(created.getUpdatedAt());
    }

    @Test
    void getOntology_returnsCreatedSchema() {
        OntologySchema schema = OntologySchema.builder().name("Get Test").build();
        OntologySchema created = service.createOntology(schema);

        OntologySchema fetched = service.getOntology(created.getId(), 1);

        assertEquals(created.getId(), fetched.getId());
        assertEquals(1, fetched.getVersion());
        assertEquals("Get Test", fetched.getName());
    }

    @Test
    void getOntology_throwsForUnknownId() {
        assertThrows(IllegalArgumentException.class,
                () -> service.getOntology("nonexistent-id", 1));
    }

    @Test
    void updateOntology_incrementsVersion() {
        OntologySchema original = service.createOntology(
                OntologySchema.builder().name("v1 Name").updatedBy("alice").build());

        OntologySchema patch = OntologySchema.builder()
                .name("v2 Name")
                .updatedBy("bob")
                .build();

        OntologySchema updated = service.updateOntology(original.getId(), patch);

        assertEquals(2, updated.getVersion(), "version should be incremented to 2");
        assertEquals("v2 Name", updated.getName());
        assertEquals("bob", updated.getUpdatedBy());

        // Previous version must still be accessible
        OntologySchema v1 = service.getOntology(original.getId(), 1);
        assertEquals("v1 Name", v1.getName());
    }

    @Test
    void updateOntology_throwsForUnknownId() {
        assertThrows(IllegalArgumentException.class,
                () -> service.updateOntology("does-not-exist",
                        OntologySchema.builder().name("x").build()));
    }

    // -------------------------------------------------------------------------
    // Process definition create and approve
    // -------------------------------------------------------------------------

    @Test
    void createProcess_createsDraftDefinition() {
        ProcessDefinition def = minimalDefinition("My Process");

        ProcessDefinition created = service.createProcess(def);

        assertNotNull(created.getId());
        assertEquals(1, created.getVersion());
        assertEquals(ProcessStatus.DRAFT, created.getStatus());
        assertEquals("My Process", created.getName());
    }

    @Test
    void approveProcess_transitionsToApprovedAndIncrementsVersion() {
        ProcessDefinition def = service.createProcess(minimalDefinition("Approvable"));

        ProcessDefinition approved = service.approveProcess(def.getId(), "admin@example.com");

        assertEquals(ProcessStatus.APPROVED, approved.getStatus());
        assertEquals(2, approved.getVersion());
        assertEquals("admin@example.com", approved.getApprovedBy());
        assertNotNull(approved.getApprovedAt());
    }

    @Test
    void approveProcess_throwsForUnknownId() {
        assertThrows(IllegalArgumentException.class,
                () -> service.approveProcess("bad-id", "approver"));
    }

    // -------------------------------------------------------------------------
    // Workflow run — AUTO steps complete immediately
    // -------------------------------------------------------------------------

    @Test
    void startRun_withAllAutoSteps_completesImmediately() {
        ProcessDefinition approved = approvedDefinition("Auto Process",
                buildPhaseWithAutoStep("phase-1", 1, "step-1", "Auto Step 1"));

        WorkflowRun run = service.startRun(approved.getId(), Map.of("foo", "bar"));

        assertNotNull(run.getId());
        assertEquals(RunStatus.COMPLETED, run.getStatus(),
                "all AUTO steps should complete synchronously");
        assertTrue(run.getStepExecutions().stream()
                .allMatch(se -> se.getStatus() == StepExecutionStatus.COMPLETED),
                "every step execution must be COMPLETED");
        assertNotNull(run.getCompletedAt());
    }

    @Test
    void startRun_requiresApprovedDefinition() {
        // Create a DRAFT definition — not approved
        ProcessDefinition draft = service.createProcess(minimalDefinition("Draft"));

        assertThrows(IllegalStateException.class,
                () -> service.startRun(draft.getId(), null));
    }

    @Test
    void startRun_throwsForUnknownDefinitionId() {
        assertThrows(IllegalArgumentException.class,
                () -> service.startRun("unknown-def-id", null));
    }

    // -------------------------------------------------------------------------
    // Workflow run — APPROVE step pauses the run
    // -------------------------------------------------------------------------

    @Test
    void startRun_withApproveStep_pausesForApproval() {
        ProcessStep approveStep = ProcessStep.builder()
                .id("1.1")
                .name("Test Approval")
                .stepType(StepType.APPROVE)
                .approvalPolicy(ApprovalPolicy.builder()
                        .approverPool(List.of("tester@example.com"))
                        .mode(ApprovalMode.SINGLE)
                        .build())
                .build();

        ProcessPhase phase = ProcessPhase.builder()
                .id("p1")
                .name("Approval Phase")
                .order(1)
                .steps(List.of(approveStep))
                .build();

        ProcessDefinition def = service.createProcess(ProcessDefinition.builder()
                .name("Approve Process")
                .phases(List.of(phase))
                .build());
        ProcessDefinition approved = service.approveProcess(def.getId(), "admin");

        WorkflowRun run = service.startRun(approved.getId(), null);

        assertEquals(RunStatus.PAUSED_FOR_APPROVAL, run.getStatus(),
                "run should be PAUSED_FOR_APPROVAL when it hits an APPROVE step");
        assertFalse(run.getPendingApprovals().isEmpty(),
                "at least one ApprovalRequest should exist");

        ApprovalRequest request = run.getPendingApprovals().get(0);
        assertEquals("1.1", request.getStepId());
        assertEquals(ApprovalRequestStatus.PENDING, request.getStatus());
        assertEquals("tester@example.com", request.getAssignedTo());
    }

    // -------------------------------------------------------------------------
    // submitApproval — APPROVE resumes the run
    // -------------------------------------------------------------------------

    @Test
    void submitApproval_approve_resumesAndCompletesRun() {
        WorkflowRun paused = startRunWithApproveStep();

        ApprovalRequest pendingRequest = paused.getPendingApprovals().get(0);
        ApprovalResponse response = ApprovalResponse.builder()
                .requestId(pendingRequest.getId())
                .respondedBy("tester@example.com")
                .action(ApprovalAction.APPROVE)
                .build();

        WorkflowRun resumed = service.submitApproval(response);

        // With only the one APPROVE step, the run should now be COMPLETED
        assertEquals(RunStatus.COMPLETED, resumed.getStatus(),
                "run should reach COMPLETED after the only approval step is approved");
        assertTrue(resumed.getStepExecutions().stream()
                .allMatch(se -> se.getStatus() == StepExecutionStatus.COMPLETED),
                "all step executions should be COMPLETED");
    }

    // -------------------------------------------------------------------------
    // submitApproval — REJECT fails the run
    // -------------------------------------------------------------------------

    @Test
    void submitApproval_reject_failsTheRun() {
        WorkflowRun paused = startRunWithApproveStep();

        ApprovalRequest pendingRequest = paused.getPendingApprovals().get(0);
        ApprovalResponse response = ApprovalResponse.builder()
                .requestId(pendingRequest.getId())
                .respondedBy("tester@example.com")
                .action(ApprovalAction.REJECT)
                .build();

        WorkflowRun failed = service.submitApproval(response);

        assertEquals(RunStatus.FAILED, failed.getStatus(),
                "rejecting an approval should put the run into FAILED status");
    }

    @Test
    void submitApproval_throwsForUnknownRequestId() {
        assertThrows(IllegalArgumentException.class,
                () -> service.submitApproval(ApprovalResponse.builder()
                        .requestId("nonexistent-request-id")
                        .action(ApprovalAction.APPROVE)
                        .build()));
    }

    // -------------------------------------------------------------------------
    // validateData — rule violations
    // -------------------------------------------------------------------------

    @Test
    void validateData_returnsEmptyListWhenAllRulesPass() {
        ValidationRule rule = ValidationRule.builder()
                .name("amount_positive")
                .expression("#amount > 0")
                .ruleType(RuleType.RANGE_CHECK)
                .severity(RuleSeverity.ERROR)
                .build();

        OntologySchema schema = service.createOntology(ontologyWithRule("Amount", rule));

        Map<String, Object> data = new HashMap<>();
        data.put("amount", 100);

        List<ValidationRule> violations = service.validateData(schema.getId(), 1, "Amount", data);

        assertTrue(violations.isEmpty(), "no violations expected when rule passes");
    }

    @Test
    void validateData_returnsViolatedRulesWhenRuleFails() {
        ValidationRule rule = ValidationRule.builder()
                .name("amount_positive")
                .expression("#amount > 0")
                .ruleType(RuleType.RANGE_CHECK)
                .severity(RuleSeverity.ERROR)
                .build();

        OntologySchema schema = service.createOntology(ontologyWithRule("Amount", rule));

        Map<String, Object> data = new HashMap<>();
        data.put("amount", -5);

        List<ValidationRule> violations = service.validateData(schema.getId(), 1, "Amount", data);

        assertEquals(1, violations.size(), "exactly one rule should be violated");
        assertEquals("amount_positive", violations.get(0).getName());
    }

    @Test
    void validateData_throwsForUnknownEntityType() {
        OntologySchema schema = service.createOntology(OntologySchema.builder()
                .name("Empty Schema")
                .entityTypes(List.of(EntityTypeDefinition.builder().name("KnownType").build()))
                .build());

        assertThrows(IllegalArgumentException.class,
                () -> service.validateData(schema.getId(), 1, "UnknownType", Map.of()));
    }

    // -------------------------------------------------------------------------
    // evaluateControl — passing and failing expressions
    // -------------------------------------------------------------------------

    @Test
    void evaluateControl_passing_recordsAttestation() {
        String controlId = registerControl("ctrl-pass", "#value > 0", ControlGateType.SOFT);

        ControlAttestation attestation = service.evaluateControl(
                controlId, "test-run", Map.of("value", 42));

        assertTrue(attestation.isPassed(), "control should pass when expression evaluates to true");
        assertEquals(controlId, attestation.getControlId());
        assertEquals("test-run", attestation.getWorkflowRunId());
        assertNotNull(attestation.getId());
        assertNotNull(attestation.getEvaluatedAt());
    }

    @Test
    void evaluateControl_failing_recordsFalseAttestation() {
        String controlId = registerControl("ctrl-fail", "#value > 100", ControlGateType.SOFT);

        ControlAttestation attestation = service.evaluateControl(
                controlId, "test-run", Map.of("value", 5));

        assertFalse(attestation.isPassed(), "control should fail when expression evaluates to false");
    }

    @Test
    void evaluateControl_throwsForUnknownControlId() {
        assertThrows(IllegalArgumentException.class,
                () -> service.evaluateControl("no-such-control", "run-1", Map.of()));
    }

    // -------------------------------------------------------------------------
    // Submission manifest — create and assertAuthoritativeVersion
    // -------------------------------------------------------------------------

    @Test
    void createManifest_assignsIdAndPreservesFields() {
        SubmissionManifest manifest = SubmissionManifest.builder()
                .workflowRunId("wf-001")
                .sourceRegion("AMER")
                .authoritativeFileName("q1-forecast.xlsx")
                .versionAssertedBy("alice")
                .build();

        SubmissionManifest created = service.createManifest(manifest);

        assertNotNull(created.getId());
        assertEquals("AMER", created.getSourceRegion());
        assertEquals("q1-forecast.xlsx", created.getAuthoritativeFileName());
        assertEquals("alice", created.getVersionAssertedBy());
    }

    @Test
    void assertAuthoritativeVersion_updatesFileIdAndAssertedBy() {
        SubmissionManifest manifest = service.createManifest(SubmissionManifest.builder()
                .sourceRegion("EMEA")
                .build());

        SubmissionManifest updated = service.assertAuthoritativeVersion(
                manifest.getId(), "file-xyz-v3", "bob@example.com");

        assertEquals("file-xyz-v3", updated.getAuthoritativeFileId());
        assertEquals("bob@example.com", updated.getVersionAssertedBy());
        assertEquals(manifest.getId(), updated.getId());
    }

    @Test
    void assertAuthoritativeVersion_throwsForUnknownManifestId() {
        assertThrows(IllegalArgumentException.class,
                () -> service.assertAuthoritativeVersion("bad-id", "file", "user"));
    }

    // -------------------------------------------------------------------------
    // getPendingApprovals — filtering
    // -------------------------------------------------------------------------

    @Test
    void getPendingApprovals_returnsOnlyPendingRequestsForAssignee() {
        WorkflowRun paused = startRunWithApproveStep();
        ApprovalRequest request = paused.getPendingApprovals().get(0);

        // Should appear for the correct assignee
        List<ApprovalRequest> forTester = service.getPendingApprovals("tester@example.com");
        assertTrue(forTester.stream().anyMatch(r -> r.getId().equals(request.getId())),
                "the pending request should appear for the assigned tester");

        // Should NOT appear for a different assignee
        List<ApprovalRequest> forOther = service.getPendingApprovals("other@example.com");
        assertTrue(forOther.stream().noneMatch(r -> r.getId().equals(request.getId())),
                "the pending request must not appear for a different assignee");
    }

    @Test
    void getPendingApprovals_withNullFilter_returnsAll() {
        WorkflowRun paused = startRunWithApproveStep();
        ApprovalRequest request = paused.getPendingApprovals().get(0);

        List<ApprovalRequest> all = service.getPendingApprovals(null);
        assertTrue(all.stream().anyMatch(r -> r.getId().equals(request.getId())),
                "null assignee filter should return all pending requests");
    }

    @Test
    void getPendingApprovals_afterApproval_requestIsNoLongerPending() {
        WorkflowRun paused = startRunWithApproveStep();
        ApprovalRequest request = paused.getPendingApprovals().get(0);

        service.submitApproval(ApprovalResponse.builder()
                .requestId(request.getId())
                .respondedBy("tester@example.com")
                .action(ApprovalAction.APPROVE)
                .build());

        List<ApprovalRequest> pending = service.getPendingApprovals(null);
        assertTrue(pending.stream().noneMatch(r -> r.getId().equals(request.getId())),
                "approved request should no longer appear as pending");
    }

    // -------------------------------------------------------------------------
    // Graph node linkage (document-to-process binding)
    // -------------------------------------------------------------------------

    @Test
    void stepExecution_graphNodeIds_persistsThroughRun() {
        // Create a process with an AUTO step that has definition-time graphNodeIds
        ProcessStep step = ProcessStep.builder()
                .id("1.1")
                .name("Extract trial balance")
                .stepType(StepType.AUTO)
                .inputKeys(List.of("trial_balance_csv"))
                .outputKeys(List.of("normalized_data"))
                .graphNodeIds(List.of("graph-node-doc-123", "graph-node-cell-456"))
                .build();

        ProcessPhase phase = ProcessPhase.builder()
                .id("p1").name("Intake").order(1).steps(List.of(step))
                .build();

        ProcessDefinition def = service.createProcess(ProcessDefinition.builder()
                .name("Graph Link Test")
                .phases(List.of(phase))
                .build());
        ProcessDefinition approved = service.approveProcess(def.getId(), "admin");

        // Verify definition-time graphNodeIds are preserved
        ProcessStep savedStep = approved.getPhases().get(0).getSteps().get(0);
        assertNotNull(savedStep.getGraphNodeIds());
        assertEquals(2, savedStep.getGraphNodeIds().size());
        assertTrue(savedStep.getGraphNodeIds().contains("graph-node-doc-123"));
        assertTrue(savedStep.getGraphNodeIds().contains("graph-node-cell-456"));

        // Start a run and verify the step runs
        WorkflowRun run = service.startRun(approved.getId(), null);
        assertNotNull(run);
        assertFalse(run.getStepExecutions().isEmpty());
    }

    @Test
    void workflowRun_graphNodeIds_canBeSetAndRetrieved() {
        WorkflowRun run = WorkflowRun.builder()
                .id("wf-test-001")
                .graphNodeIds(List.of("node-a", "node-b", "node-c"))
                .build();

        assertEquals(3, run.getGraphNodeIds().size());
        assertTrue(run.getGraphNodeIds().contains("node-b"));
    }

    @Test
    void provenanceCitation_graphNodeId_linksToKnowledgeGraph() {
        ai.kompile.process.ontology.ProvenanceCitation citation =
                ai.kompile.process.ontology.ProvenanceCitation.builder()
                        .sourceType(ai.kompile.process.ontology.SourceType.DOCUMENT)
                        .sourceId("budget_2026.xlsx")
                        .location("cell D60")
                        .extractedText("Total Revenue: $1.2M")
                        .confidence(0.95)
                        .contentHash("sha256:abc123")
                        .graphNodeId("kg-node-uuid-789")
                        .build();

        assertEquals("kg-node-uuid-789", citation.getGraphNodeId());
        assertEquals("budget_2026.xlsx", citation.getSourceId());
    }

    // -------------------------------------------------------------------------
    // listOntologies
    // -------------------------------------------------------------------------

    @Test
    void listOntologies_returnsLatestVersionOfEach() {
        OntologySchema s1 = service.createOntology(OntologySchema.builder().name("Ont A").build());
        OntologySchema s2 = service.createOntology(OntologySchema.builder().name("Ont B").build());
        // Update s1 to v2
        service.updateOntology(s1.getId(), OntologySchema.builder().name("Ont A v2").build());

        List<OntologySchema> all = service.listOntologies();

        assertEquals(2, all.size(), "should have two ontologies");
        OntologySchema a = all.stream().filter(o -> o.getId().equals(s1.getId())).findFirst().orElseThrow();
        assertEquals(2, a.getVersion(), "should return latest version of Ont A");
        assertEquals("Ont A v2", a.getName());
    }

    @Test
    void listOntologies_emptyWhenNoneCreated() {
        assertTrue(service.listOntologies().isEmpty());
    }

    // -------------------------------------------------------------------------
    // listProcessDefinitions
    // -------------------------------------------------------------------------

    @Test
    void listProcessDefinitions_returnsLatestVersionOfEach() {
        ProcessDefinition d1 = service.createProcess(minimalDefinition("Proc A"));
        service.createProcess(minimalDefinition("Proc B"));
        // Approve d1 (creates v2)
        service.approveProcess(d1.getId(), "admin");

        List<ProcessDefinition> all = service.listProcessDefinitions();

        assertEquals(2, all.size());
        ProcessDefinition a = all.stream().filter(d -> d.getId().equals(d1.getId())).findFirst().orElseThrow();
        assertEquals(2, a.getVersion(), "should return latest (approved) version");
    }

    // -------------------------------------------------------------------------
    // listAllRuns
    // -------------------------------------------------------------------------

    @Test
    void listAllRuns_includesCompletedAndActiveRuns() {
        ProcessDefinition approved = approvedDefinition("Multi Run",
                buildPhaseWithAutoStep("p1", 1, "s1", "Auto Step"));

        // Run 1: completes immediately (all AUTO)
        WorkflowRun r1 = service.startRun(approved.getId(), null);
        assertEquals(RunStatus.COMPLETED, r1.getStatus());

        // Run 2: also completes
        WorkflowRun r2 = service.startRun(approved.getId(), null);
        assertEquals(RunStatus.COMPLETED, r2.getStatus());

        List<WorkflowRun> allRuns = service.listAllRuns();
        assertTrue(allRuns.size() >= 2, "should include at least the two runs we just created");

        // Active runs should be empty since both completed
        assertTrue(service.listActiveRuns().isEmpty(), "no active runs expected");
    }

    // -------------------------------------------------------------------------
    // cancelRun
    // -------------------------------------------------------------------------

    @Test
    void cancelRun_setsCancelledStatus() {
        WorkflowRun paused = startRunWithApproveStep();
        assertEquals(RunStatus.PAUSED_FOR_APPROVAL, paused.getStatus());

        WorkflowRun cancelled = service.cancelRun(paused.getId());
        assertEquals(RunStatus.CANCELLED, cancelled.getStatus());
    }

    @Test
    void cancelRun_throwsForTerminalRun() {
        ProcessDefinition approved = approvedDefinition("Auto",
                buildPhaseWithAutoStep("p1", 1, "s1", "Step"));
        WorkflowRun completed = service.startRun(approved.getId(), null);
        assertEquals(RunStatus.COMPLETED, completed.getStatus());

        assertThrows(IllegalArgumentException.class,
                () -> service.cancelRun(completed.getId()));
    }

    @Test
    void cancelRun_throwsForUnknownId() {
        assertThrows(IllegalArgumentException.class,
                () -> service.cancelRun("nonexistent-run"));
    }

    // -------------------------------------------------------------------------
    // ESCALATE keeps run paused
    // -------------------------------------------------------------------------

    @Test
    void submitApproval_escalate_keepsRunPaused() {
        WorkflowRun paused = startRunWithApproveStep();
        ApprovalRequest request = paused.getPendingApprovals().get(0);

        ApprovalResponse response = ApprovalResponse.builder()
                .requestId(request.getId())
                .respondedBy("tester@example.com")
                .action(ApprovalAction.ESCALATE)
                .comment("Need manager review")
                .build();

        WorkflowRun afterEscalate = service.submitApproval(response);

        assertEquals(RunStatus.PAUSED_FOR_APPROVAL, afterEscalate.getStatus(),
                "ESCALATE should keep the run paused, not fail it");
    }

    // -------------------------------------------------------------------------
    // DELEGATE updates assignedTo
    // -------------------------------------------------------------------------

    @Test
    void submitApproval_delegate_updatesAssignedTo() {
        WorkflowRun paused = startRunWithApproveStep();
        ApprovalRequest request = paused.getPendingApprovals().get(0);
        assertEquals("tester@example.com", request.getAssignedTo());

        ApprovalResponse response = ApprovalResponse.builder()
                .requestId(request.getId())
                .respondedBy("tester@example.com")
                .action(ApprovalAction.DELEGATE)
                .delegateTo("manager@example.com")
                .build();

        WorkflowRun afterDelegate = service.submitApproval(response);

        assertEquals(RunStatus.PAUSED_FOR_APPROVAL, afterDelegate.getStatus());
        // Check the pending approval was re-assigned
        List<ApprovalRequest> pending = service.getPendingApprovals("manager@example.com");
        assertFalse(pending.isEmpty(), "delegated approval should be assigned to manager");
    }

    // -------------------------------------------------------------------------
    // completeHumanStep
    // -------------------------------------------------------------------------

    @Test
    void completeHumanStep_advancesRunPastHumanStep() {
        // Build a process with a HUMAN step
        ProcessStep humanStep = ProcessStep.builder()
                .id("h1")
                .name("Manual Review")
                .stepType(StepType.HUMAN)
                .build();

        ProcessPhase phase = ProcessPhase.builder()
                .id("p1").name("Human Phase").order(1)
                .steps(List.of(humanStep))
                .build();

        ProcessDefinition def = service.createProcess(ProcessDefinition.builder()
                .name("Human Step Process")
                .phases(List.of(phase))
                .build());
        ProcessDefinition approved = service.approveProcess(def.getId(), "admin");

        WorkflowRun run = service.startRun(approved.getId(), null);
        assertEquals(RunStatus.PAUSED_FOR_HUMAN, run.getStatus(),
                "run should pause at HUMAN step");

        // Complete the human step
        WorkflowRun completed = service.completeHumanStep(
                run.getId(), "h1", "reviewer@example.com", Map.of("verdict", "ok"));

        assertEquals(RunStatus.COMPLETED, completed.getStatus(),
                "run should complete after human step is done");
        assertTrue(completed.getStepExecutions().stream()
                .allMatch(se -> se.getStatus() == StepExecutionStatus.COMPLETED));
    }

    // -------------------------------------------------------------------------
    // createControl / listControls / getControl
    // -------------------------------------------------------------------------

    @Test
    void createControl_persistsAndReturns() {
        ControlDefinition control = ControlDefinition.builder()
                .name("Revenue Check")
                .expression("#amount < 10000")
                .gateType(ControlGateType.HARD)
                .build();

        ControlDefinition created = service.createControl(control);

        assertNotNull(created.getId());
        assertEquals("Revenue Check", created.getName());
        assertEquals(ControlGateType.HARD, created.getGateType());
    }

    @Test
    void listControls_returnsAllCreated() {
        service.createControl(ControlDefinition.builder()
                .name("Ctrl A").expression("true").gateType(ControlGateType.SOFT).build());
        service.createControl(ControlDefinition.builder()
                .name("Ctrl B").expression("false").gateType(ControlGateType.HARD).build());

        List<ControlDefinition> all = service.listControls();
        assertTrue(all.size() >= 2);
    }

    @Test
    void getControl_returnsById() {
        ControlDefinition created = service.createControl(ControlDefinition.builder()
                .name("Findable").expression("true").gateType(ControlGateType.SOFT).build());

        ControlDefinition found = service.getControl(created.getId());
        assertEquals("Findable", found.getName());
    }

    @Test
    void getControl_throwsForUnknownId() {
        assertThrows(IllegalArgumentException.class,
                () -> service.getControl("no-such-ctrl"));
    }

    // -------------------------------------------------------------------------
    // graphNodeIds propagation through run execution
    // -------------------------------------------------------------------------

    @Test
    void graphNodeIds_propagatedFromDefinitionToRunExecution() {
        ProcessStep step = ProcessStep.builder()
                .id("s1")
                .name("Linked Step")
                .stepType(StepType.AUTO)
                .graphNodeIds(List.of("kg-node-1", "kg-node-2"))
                .build();

        ProcessPhase phase = ProcessPhase.builder()
                .id("p1").name("Phase 1").order(1).steps(List.of(step))
                .build();

        ProcessDefinition def = service.createProcess(ProcessDefinition.builder()
                .name("GraphNode Test").phases(List.of(phase)).build());
        ProcessDefinition approved = service.approveProcess(def.getId(), "admin");

        WorkflowRun run = service.startRun(approved.getId(), null);

        // Step execution should carry the graphNodeIds
        assertFalse(run.getStepExecutions().isEmpty());
        List<String> stepGraphNodes = run.getStepExecutions().get(0).getGraphNodeIds();
        assertNotNull(stepGraphNodes, "step execution should have graphNodeIds");
        assertTrue(stepGraphNodes.contains("kg-node-1"));
        assertTrue(stepGraphNodes.contains("kg-node-2"));

        // Run-level graphNodeIds should be the union
        assertNotNull(run.getGraphNodeIds());
        assertTrue(run.getGraphNodeIds().contains("kg-node-1"));
        assertTrue(run.getGraphNodeIds().contains("kg-node-2"));
    }

    // -------------------------------------------------------------------------
    // ProcessGraphCallback integration
    // -------------------------------------------------------------------------

    @Test
    void graphCallback_onStepCompleted_firesForNewlyCompletedSteps() {
        // Wire a recording callback
        RecordingGraphCallback callback = new RecordingGraphCallback();
        service.setProcessGraphCallback(callback);

        ProcessDefinition approved = approvedDefinition("Callback Test",
                buildPhaseWithAutoStep("p1", 1, "s1", "Auto Step"));

        // startRun completes the auto step immediately
        WorkflowRun run = service.startRun(approved.getId(), null);

        assertEquals(RunStatus.COMPLETED, run.getStatus());
        assertFalse(callback.stepCalls.isEmpty(), "onStepCompleted should fire for completed step");
        assertEquals("s1", callback.stepCalls.get(0).stepExecution().getStepId());
        assertFalse(callback.runCalls.isEmpty(), "onRunCompleted should fire for terminal run");
        assertEquals(run.getId(), callback.runCalls.get(0).getId());
    }

    @Test
    void graphCallback_onRunCompleted_firesOnCancellation() {
        RecordingGraphCallback callback = new RecordingGraphCallback();
        service.setProcessGraphCallback(callback);

        // Start a run that pauses for approval, then cancel it
        WorkflowRun pausedRun = startRunWithApproveStep();
        assertEquals(RunStatus.PAUSED_FOR_APPROVAL, pausedRun.getStatus());

        callback.runCalls.clear(); // clear any calls from startRun
        WorkflowRun cancelled = service.cancelRun(pausedRun.getId());

        assertEquals(RunStatus.CANCELLED, cancelled.getStatus());
        assertFalse(callback.runCalls.isEmpty(), "onRunCompleted should fire on cancellation");
        assertEquals(cancelled.getId(), callback.runCalls.get(0).getId());
    }

    @Test
    void graphCallback_exceptionDoesNotBlockEngine() {
        // Wire a callback that throws on every call
        ProcessGraphCallback throwingCallback = new ProcessGraphCallback() {
            @Override
            public void onStepCompleted(WorkflowRun run, ai.kompile.process.execution.StepExecution se) {
                throw new RuntimeException("Simulated KG failure");
            }
            @Override
            public void onRunCompleted(WorkflowRun run) {
                throw new RuntimeException("Simulated KG failure");
            }
        };
        service.setProcessGraphCallback(throwingCallback);

        ProcessDefinition approved = approvedDefinition("Error Resilience",
                buildPhaseWithAutoStep("p1", 1, "s1", "Step"));

        // Should complete without exception despite callback failures
        WorkflowRun run = assertDoesNotThrow(() -> service.startRun(approved.getId(), null));
        assertEquals(RunStatus.COMPLETED, run.getStatus());
    }

    @Test
    void graphCallback_notFiredWhenNull() {
        // Verify service works without callback (default null)
        ProcessDefinition approved = approvedDefinition("No Callback",
                buildPhaseWithAutoStep("p1", 1, "s1", "Step"));
        WorkflowRun run = assertDoesNotThrow(() -> service.startRun(approved.getId(), null));
        assertEquals(RunStatus.COMPLETED, run.getStatus());
    }

    // ─── updateRunData ───────────────────────────────────────────────────

    @Test
    void updateRunData_mergesIntoExistingRunData() {
        ProcessDefinition approved = approvedDefinition("RunData Test",
                buildPhaseWithAutoStep("p1", 1, "s1", "Auto Step"));

        WorkflowRun run = service.startRun(approved.getId(), Map.of("key1", "value1"));
        assertEquals(RunStatus.COMPLETED, run.getStatus());

        WorkflowRun updated = service.updateRunData(run.getId(), Map.of("key2", "value2", "key3", 42));

        assertNotNull(updated.getRunData());
        assertEquals("value1", updated.getRunData().get("key1"));
        assertEquals("value2", updated.getRunData().get("key2"));
        assertEquals(42, updated.getRunData().get("key3"));
    }

    @Test
    void updateRunData_overridesExistingKeys() {
        ProcessDefinition approved = approvedDefinition("Override Test",
                buildPhaseWithAutoStep("p1", 1, "s1", "Step"));

        WorkflowRun run = service.startRun(approved.getId(), Map.of("total", 100));

        WorkflowRun updated = service.updateRunData(run.getId(), Map.of("total", 200));

        assertEquals(200, updated.getRunData().get("total"));
    }

    @Test
    void updateRunData_preservesRunStatus() {
        WorkflowRun pausedRun = startRunWithApproveStep();
        assertEquals(RunStatus.PAUSED_FOR_APPROVAL, pausedRun.getStatus());

        WorkflowRun updated = service.updateRunData(pausedRun.getId(),
                Map.of("_generatedCode", "console.log('hello')"));

        assertEquals(RunStatus.PAUSED_FOR_APPROVAL, updated.getStatus());
        assertEquals("console.log('hello')", updated.getRunData().get("_generatedCode"));
    }

    @Test
    void updateRunData_throwsForUnknownRun() {
        assertThrows(IllegalArgumentException.class,
                () -> service.updateRunData("non-existent", Map.of("k", "v")));
    }

    /** Recording stub for ProcessGraphCallback — captures calls for assertion. */
    private static class RecordingGraphCallback implements ProcessGraphCallback {
        record StepCall(WorkflowRun run, ai.kompile.process.execution.StepExecution stepExecution) {}
        final List<StepCall> stepCalls = new java.util.ArrayList<>();
        final List<WorkflowRun> runCalls = new java.util.ArrayList<>();

        @Override
        public void onStepCompleted(WorkflowRun run, ai.kompile.process.execution.StepExecution se) {
            stepCalls.add(new StepCall(run, se));
        }
        @Override
        public void onRunCompleted(WorkflowRun run) {
            runCalls.add(run);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Builds a minimal (valid) ProcessDefinition with no phases. */
    private ProcessDefinition minimalDefinition(String name) {
        return ProcessDefinition.builder()
                .name(name)
                .build();
    }

    /** Creates and approves a process definition with the given phases. */
    private ProcessDefinition approvedDefinition(String name, ProcessPhase... phases) {
        ProcessDefinition def = service.createProcess(ProcessDefinition.builder()
                .name(name)
                .phases(List.of(phases))
                .build());
        return service.approveProcess(def.getId(), "admin");
    }

    /** Builds a ProcessPhase containing a single AUTO step. */
    private ProcessPhase buildPhaseWithAutoStep(String phaseId, int order,
                                                 String stepId, String stepName) {
        ProcessStep step = ProcessStep.builder()
                .id(stepId)
                .name(stepName)
                .stepType(StepType.AUTO)
                .build();
        return ProcessPhase.builder()
                .id(phaseId)
                .name("Phase " + phaseId)
                .order(order)
                .steps(List.of(step))
                .build();
    }

    /**
     * Starts a run that pauses at a single APPROVE step.
     * The approver pool is set to {@code tester@example.com}.
     */
    private WorkflowRun startRunWithApproveStep() {
        ProcessStep approveStep = ProcessStep.builder()
                .id("1.1")
                .name("Test Approval")
                .stepType(StepType.APPROVE)
                .approvalPolicy(ApprovalPolicy.builder()
                        .approverPool(List.of("tester@example.com"))
                        .mode(ApprovalMode.SINGLE)
                        .build())
                .build();

        ProcessPhase phase = ProcessPhase.builder()
                .id("p1")
                .name("Approval Phase")
                .order(1)
                .steps(List.of(approveStep))
                .build();

        ProcessDefinition def = service.createProcess(ProcessDefinition.builder()
                .name("Approve Only Process")
                .phases(List.of(phase))
                .build());
        ProcessDefinition approved = service.approveProcess(def.getId(), "admin");

        return service.startRun(approved.getId(), null);
    }

    /**
     * Builds an OntologySchema containing a single entity type with the supplied rule.
     */
    private OntologySchema ontologyWithRule(String entityTypeName, ValidationRule rule) {
        EntityTypeDefinition entity = EntityTypeDefinition.builder()
                .name(entityTypeName)
                .rules(List.of(rule))
                .build();
        return OntologySchema.builder()
                .name("Validation Test Ontology")
                .entityTypes(List.of(entity))
                .build();
    }

    /**
     * Registers a {@link ControlDefinition} directly into the service's internal
     * controls map via {@link ProcessEngineServiceImpl}.
     *
     * Because the service exposes no public API to register controls at runtime
     * (they are loaded from disk during {@code init()}), we write the control JSON
     * to the temp control directory and re-initialize the service.
     *
     * @return the registered control's ID
     */
    private String registerControl(String controlId, String expression, ControlGateType gateType)  {
        ControlDefinition control = ControlDefinition.builder()
                .id(controlId)
                .name("Control " + controlId)
                .expression(expression)
                .gateType(gateType)
                .build();

        // Write to the temp controls directory so init() will pick it up
        Path controlDir = tempHome.resolve(".kompile/processes/controls");
        Path file = controlDir.resolve(controlId + ".json");
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
            mapper.writeValue(file.toFile(), control);
        } catch (IOException e) {
            throw new RuntimeException("Failed to persist test control", e);
        }

        // Re-initialize to load the new control from disk
        service.init();
        return controlId;
    }

    // -------------------------------------------------------------------------
    // Excel formula conversion / execution
    // -------------------------------------------------------------------------

    @Test
    void convertExcelFormulas_throwsWhenDispatcherNull() {
        // dispatcher is null by default — the service must reject the call immediately
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.convertExcelFormulas("{}", "javascript"));
        assertTrue(ex.getMessage().contains("StepExecutionDispatcher"),
                "exception message should mention StepExecutionDispatcher");
    }

    @Test
    void convertExcelFormulas_delegatesToDispatcher() {
        StepExecutionDispatcher dispatcher = mock(StepExecutionDispatcher.class);
        Map<String, Object> expected = Map.of("code", "function compute(){}", "language", "javascript");
        when(dispatcher.convertExcel("{\"cells\":[]}", "javascript")).thenReturn(expected);

        service.setStepExecutionDispatcher(dispatcher);

        Map<String, Object> result = service.convertExcelFormulas("{\"cells\":[]}", "javascript");

        assertEquals(expected, result);
        verify(dispatcher, times(1)).convertExcel("{\"cells\":[]}", "javascript");
    }

    @Test
    void executeExcelFormulas_throwsWhenDispatcherNull() {
        // dispatcher is null by default — the service must reject the call immediately
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.executeExcelFormulas("{}", Map.of(), "javascript", null));
        assertTrue(ex.getMessage().contains("StepExecutionDispatcher"),
                "exception message should mention StepExecutionDispatcher");
    }

    @Test
    void executeExcelFormulas_delegatesToDispatcher() {
        StepExecutionDispatcher dispatcher = mock(StepExecutionDispatcher.class);
        Map<String, Object> overrides = Map.of("B2", 42);
        Map<String, Object> expected = Map.of("D10", 84, "_generatedCode", "function compute(){}");
        when(dispatcher.executeExcel("{\"cells\":[]}", overrides, "javascript", "function compute(){}"))
                .thenReturn(expected);

        service.setStepExecutionDispatcher(dispatcher);

        Map<String, Object> result = service.executeExcelFormulas(
                "{\"cells\":[]}", overrides, "javascript", "function compute(){}");

        assertEquals(expected, result);
        verify(dispatcher, times(1)).executeExcel(
                "{\"cells\":[]}", overrides, "javascript", "function compute(){}");
    }

    // -------------------------------------------------------------------------
    // Pipeline step execution
    // -------------------------------------------------------------------------

    @Test
    void pipelineStep_failsWithoutDispatcher() {
        ProcessStep pipelineStep = ProcessStep.builder()
                .id("1.1")
                .name("Run Pipeline")
                .stepType(StepType.PIPELINE)
                .pipelineDefinitionJson("{\"@class\":\"ai.kompile.pipelines.framework.runtime.SequencePipeline\",\"steps\":[]}")
                .build();

        ProcessPhase phase = ProcessPhase.builder()
                .id("p1").name("Pipeline Phase").order(1)
                .steps(List.of(pipelineStep))
                .build();

        ProcessDefinition def = service.createProcess(ProcessDefinition.builder()
                .name("Pipeline Without Dispatcher")
                .phases(List.of(phase))
                .build());
        ProcessDefinition approved = service.approveProcess(def.getId(), "admin");

        WorkflowRun run = service.startRun(approved.getId(), null);

        assertEquals(RunStatus.FAILED, run.getStatus());
        assertTrue(run.getStepExecutions().stream()
                .anyMatch(se -> se.getError() != null && se.getError().contains("StepExecutionDispatcher")));
    }

    @Test
    void pipelineStep_failsWithoutPipelineJson() {
        StepExecutionDispatcher dispatcher = mock(StepExecutionDispatcher.class);
        service.setStepExecutionDispatcher(dispatcher);

        ProcessStep pipelineStep = ProcessStep.builder()
                .id("1.1")
                .name("Run Pipeline")
                .stepType(StepType.PIPELINE)
                // pipelineDefinitionJson is null
                .build();

        ProcessPhase phase = ProcessPhase.builder()
                .id("p1").name("Pipeline Phase").order(1)
                .steps(List.of(pipelineStep))
                .build();

        ProcessDefinition def = service.createProcess(ProcessDefinition.builder()
                .name("Pipeline Without JSON")
                .phases(List.of(phase))
                .build());
        ProcessDefinition approved = service.approveProcess(def.getId(), "admin");

        WorkflowRun run = service.startRun(approved.getId(), null);

        assertEquals(RunStatus.FAILED, run.getStatus());
        assertTrue(run.getStepExecutions().stream()
                .anyMatch(se -> se.getError() != null && se.getError().contains("pipelineDefinitionJson")));
    }

    @Test
    void pipelineStep_executesSuccessfullyWithDispatcher() {
        StepExecutionDispatcher dispatcher = mock(StepExecutionDispatcher.class);
        String pipelineJson = "{\"@class\":\"ai.kompile.pipelines.framework.runtime.SequencePipeline\",\"steps\":[]}";
        Map<String, Object> pipelineOutputs = Map.of("computedValue", 42, "status", "done");
        when(dispatcher.executePipeline(eq(pipelineJson), any())).thenReturn(pipelineOutputs);
        service.setStepExecutionDispatcher(dispatcher);

        ProcessStep pipelineStep = ProcessStep.builder()
                .id("1.1")
                .name("Run Pipeline")
                .stepType(StepType.PIPELINE)
                .pipelineDefinitionJson(pipelineJson)
                .pipelineOutputKey("pipelineResult")
                .build();

        ProcessPhase phase = ProcessPhase.builder()
                .id("p1").name("Pipeline Phase").order(1)
                .steps(List.of(pipelineStep))
                .build();

        ProcessDefinition def = service.createProcess(ProcessDefinition.builder()
                .name("Pipeline Success")
                .phases(List.of(phase))
                .build());
        ProcessDefinition approved = service.approveProcess(def.getId(), "admin");

        WorkflowRun run = service.startRun(approved.getId(), Map.of("input1", "hello"));

        assertEquals(RunStatus.COMPLETED, run.getStatus());
        assertTrue(run.getStepExecutions().stream()
                .allMatch(se -> se.getStatus() == StepExecutionStatus.COMPLETED));
        // The pipeline outputs should be merged into runData
        assertEquals(42, run.getRunData().get("computedValue"));
        assertEquals("done", run.getRunData().get("status"));
        // pipelineResult key should contain the full output map
        assertNotNull(run.getRunData().get("pipelineResult"));
    }

    @Test
    void pipelineStep_inputMappingSelectsSubsetOfRunData() {
        StepExecutionDispatcher dispatcher = mock(StepExecutionDispatcher.class);
        String pipelineJson = "{\"@class\":\"test\"}";
        Map<String, Object> pipelineOutputs = Map.of("doubled", 200);
        when(dispatcher.executePipeline(eq(pipelineJson), any())).thenReturn(pipelineOutputs);
        service.setStepExecutionDispatcher(dispatcher);

        ProcessStep pipelineStep = ProcessStep.builder()
                .id("1.1")
                .name("Run Pipeline With Mapping")
                .stepType(StepType.PIPELINE)
                .pipelineDefinitionJson(pipelineJson)
                .pipelineInputMapping(Map.of("amount", "value"))
                .build();

        ProcessPhase phase = ProcessPhase.builder()
                .id("p1").name("Pipeline Phase").order(1)
                .steps(List.of(pipelineStep))
                .build();

        ProcessDefinition def = service.createProcess(ProcessDefinition.builder()
                .name("Pipeline Input Mapping")
                .phases(List.of(phase))
                .build());
        ProcessDefinition approved = service.approveProcess(def.getId(), "admin");

        WorkflowRun run = service.startRun(approved.getId(), Map.of("amount", 100, "extraField", "ignored"));

        assertEquals(RunStatus.COMPLETED, run.getStatus());

        // Verify the dispatcher was called with mapped inputs: {"value": 100}
        verify(dispatcher).executePipeline(eq(pipelineJson), argThat(inputs ->
                inputs.containsKey("value") && inputs.get("value").equals(100)
                && !inputs.containsKey("extraField") && !inputs.containsKey("amount")));
    }

    @Test
    void pipelineStep_handlesExecutionFailure() {
        StepExecutionDispatcher dispatcher = mock(StepExecutionDispatcher.class);
        String pipelineJson = "{\"bad\":\"pipeline\"}";
        when(dispatcher.executePipeline(eq(pipelineJson), any()))
                .thenThrow(new RuntimeException("Pipeline deserialization failed"));
        service.setStepExecutionDispatcher(dispatcher);

        ProcessStep pipelineStep = ProcessStep.builder()
                .id("1.1")
                .name("Failing Pipeline")
                .stepType(StepType.PIPELINE)
                .pipelineDefinitionJson(pipelineJson)
                .build();

        ProcessPhase phase = ProcessPhase.builder()
                .id("p1").name("Pipeline Phase").order(1)
                .steps(List.of(pipelineStep))
                .build();

        ProcessDefinition def = service.createProcess(ProcessDefinition.builder()
                .name("Pipeline Failure")
                .phases(List.of(phase))
                .build());
        ProcessDefinition approved = service.approveProcess(def.getId(), "admin");

        WorkflowRun run = service.startRun(approved.getId(), null);

        assertEquals(RunStatus.FAILED, run.getStatus());
        assertTrue(run.getStepExecutions().stream()
                .anyMatch(se -> se.getStatus() == StepExecutionStatus.FAILED
                        && se.getError() != null && se.getError().contains("Pipeline")));
    }

    @Test
    void pipelineStep_followedByAutoStep_chainsOutputs() {
        StepExecutionDispatcher dispatcher = mock(StepExecutionDispatcher.class);
        String pipelineJson = "{\"pipeline\":true}";
        Map<String, Object> pipelineOutputs = Map.of("total", 500);
        when(dispatcher.executePipeline(eq(pipelineJson), any())).thenReturn(pipelineOutputs);
        service.setStepExecutionDispatcher(dispatcher);

        // Step 1: PIPELINE produces "total"
        ProcessStep pipelineStep = ProcessStep.builder()
                .id("1.1")
                .name("Compute Pipeline")
                .stepType(StepType.PIPELINE)
                .pipelineDefinitionJson(pipelineJson)
                .build();

        // Step 2: AUTO evaluates SpEL using pipeline output
        ProcessStep autoStep = ProcessStep.builder()
                .id("1.2")
                .name("Check Total")
                .stepType(StepType.AUTO)
                .executionExpressions(Map.of("isHighValue", "#total > 100"))
                .build();

        ProcessPhase phase = ProcessPhase.builder()
                .id("p1").name("Chained Phase").order(1)
                .steps(List.of(pipelineStep, autoStep))
                .build();

        ProcessDefinition def = service.createProcess(ProcessDefinition.builder()
                .name("Pipeline + Auto Chain")
                .phases(List.of(phase))
                .build());
        ProcessDefinition approved = service.approveProcess(def.getId(), "admin");

        WorkflowRun run = service.startRun(approved.getId(), null);

        assertEquals(RunStatus.COMPLETED, run.getStatus());
        assertEquals(2, run.getStepExecutions().stream()
                .filter(se -> se.getStatus() == StepExecutionStatus.COMPLETED).count());
        // The AUTO step should have computed isHighValue from pipeline's "total"
        assertEquals(true, run.getRunData().get("isHighValue"));
    }

    // -------------------------------------------------------------------------
    // Camel, Drools, and workflow step execution
    // -------------------------------------------------------------------------

    @Test
    void camelRouteStep_executesWithDispatcherAndMergesOutputs() {
        StepExecutionDispatcher dispatcher = mock(StepExecutionDispatcher.class);
        Map<String, Object> routeOutputs = Map.of("routed", true, "destination", "approved");
        when(dispatcher.executeCamelRoute(eq("approval-route"), isNull(), any()))
                .thenReturn(routeOutputs);
        service.setStepExecutionDispatcher(dispatcher);

        ProcessStep camelStep = ProcessStep.builder()
                .id("1.1")
                .name("Route Approval")
                .stepType(StepType.CAMEL_ROUTE)
                .inputKeys(List.of("orderId"))
                .camelRouteId("approval-route")
                .camelOutputKey("camelResult")
                .build();

        ProcessPhase phase = ProcessPhase.builder()
                .id("p1").name("Camel Phase").order(1)
                .steps(List.of(camelStep))
                .build();

        ProcessDefinition approved = approvedDefinition("Camel Route Process", phase);

        WorkflowRun run = service.startRun(approved.getId(),
                Map.of("orderId", "ORD-100", "ignored", "not-sent"));

        assertEquals(RunStatus.COMPLETED, run.getStatus());
        assertEquals(true, run.getRunData().get("routed"));
        assertEquals("approved", run.getRunData().get("destination"));
        assertNotNull(run.getRunData().get("camelResult"));
        verify(dispatcher).executeCamelRoute(eq("approval-route"), isNull(), argThat(inputs ->
                inputs.size() == 1 && "ORD-100".equals(inputs.get("orderId"))));
    }

    @Test
    void droolsRuleStep_executesWithDispatcherAndOutputKey() {
        StepExecutionDispatcher dispatcher = mock(StepExecutionDispatcher.class);
        String drl = "rule \"approve\" when then end";
        Map<String, Object> rulesOutputs = Map.of("approved", true, "_rulesFired", 1);
        when(dispatcher.executeDroolsRules(eq(drl), any(), eq("validation"), eq(10), eq(false)))
                .thenReturn(rulesOutputs);
        service.setStepExecutionDispatcher(dispatcher);

        ProcessStep droolsStep = ProcessStep.builder()
                .id("1.1")
                .name("Apply Rules")
                .stepType(StepType.DROOLS_RULE)
                .inputKeys(List.of("amount"))
                .droolsRuleDrl(drl)
                .droolsAgendaGroup("validation")
                .droolsMaxFirings(10)
                .droolsOutputKey("rulesResult")
                .build();

        ProcessPhase phase = ProcessPhase.builder()
                .id("p1").name("Rules Phase").order(1)
                .steps(List.of(droolsStep))
                .build();

        ProcessDefinition approved = approvedDefinition("Drools Rules Process", phase);

        WorkflowRun run = service.startRun(approved.getId(), Map.of("amount", 125, "extra", "ignored"));

        assertEquals(RunStatus.COMPLETED, run.getStatus());
        assertEquals(true, run.getRunData().get("approved"));
        assertNotNull(run.getRunData().get("rulesResult"));
        verify(dispatcher).executeDroolsRules(eq(drl), argThat(facts ->
                Integer.valueOf(125).equals(facts.get("amount"))
                        && "1.1".equals(facts.get("_stepId"))
                        && "DROOLS_RULE".equals(facts.get("_stepType"))),
                eq("validation"), eq(10), eq(false));
    }

    @Test
    void droolsStep_passesRolesAndPermissionsToDispatcher() {
        StepExecutionDispatcher dispatcher = mock(StepExecutionDispatcher.class);
        String drl = "rule \"route\" when then end";
        when(dispatcher.executeDroolsRules(eq(drl), any(), isNull(), isNull(), eq(false)))
                .thenReturn(Map.of("decision", "APPROVE"));
        service.setStepExecutionDispatcher(dispatcher);

        ProcessStep droolsStep = ProcessStep.builder()
                .id("1.1")
                .name("Policy Decision")
                .stepType(StepType.DROOLS_RULE)
                .inputKeys(List.of("amount"))
                .droolsRuleDrl(drl)
                .requiredRoles(List.of("finance"))
                .requiredPermissions(List.of("invoice.approve"))
                .build();

        ProcessPhase phase = ProcessPhase.builder()
                .id("p1").name("Rules Phase").order(1)
                .steps(List.of(droolsStep))
                .build();

        ProcessDefinition approved = approvedDefinition("Drools Security Context", phase);

        WorkflowRun run = service.startRun(approved.getId(), Map.of(
                "amount", 250,
                "_actor", "analyst@example.com",
                "_roles", List.of("finance", "reviewer"),
                "_permissions", List.of("invoice.approve", "invoice.read")));

        assertEquals(RunStatus.COMPLETED, run.getStatus());
        assertEquals("APPROVE", run.getRunData().get("decision"));
        assertEquals("APPROVE", run.getRunData().get("1_1_decision"));
        verify(dispatcher).executeDroolsRules(eq(drl), argThat(facts ->
                        Integer.valueOf(250).equals(facts.get("amount"))
                                && "analyst@example.com".equals(facts.get("_actor"))
                                && ((List<?>) facts.get("_roles")).contains("finance")
                                && ((List<?>) facts.get("_permissions")).contains("invoice.approve")
                                && ((List<?>) facts.get("_requiredRoles")).contains("finance")
                                && ((List<?>) facts.get("_requiredPermissions")).contains("invoice.approve")
                                && "1.1".equals(facts.get("_stepId"))),
                isNull(), isNull(), eq(false));
    }

    @Test
    void stepFailsWhenRequiredRoleMissing() {
        ProcessStep step = ProcessStep.builder()
                .id("1.1")
                .name("Protected Step")
                .stepType(StepType.AUTO)
                .requiredRoles(List.of("finance"))
                .build();

        ProcessPhase phase = ProcessPhase.builder()
                .id("p1").name("Access Phase").order(1)
                .steps(List.of(step))
                .build();

        ProcessDefinition approved = approvedDefinition("Protected Process", phase);

        WorkflowRun run = service.startRun(approved.getId(), Map.of("_roles", List.of("operations")));

        assertEquals(RunStatus.FAILED, run.getStatus());
        assertEquals(StepExecutionStatus.FAILED, run.getStepExecutions().get(0).getStatus());
        assertTrue(run.getStepExecutions().get(0).getError().contains("roles"));
    }

    @Test
    void droolsDecisionOutputCanDriveConditionalBranches() {
        StepExecutionDispatcher dispatcher = mock(StepExecutionDispatcher.class);
        String drl = "rule \"route\" when then end";
        when(dispatcher.executeDroolsRules(eq(drl), any(), isNull(), isNull(), eq(false)))
                .thenReturn(Map.of("decision", "APPROVE"));
        service.setStepExecutionDispatcher(dispatcher);

        ProcessStep droolsStep = ProcessStep.builder()
                .id("rules")
                .name("Route Request")
                .stepType(StepType.DROOLS_RULE)
                .droolsRuleDrl(drl)
                .build();
        ProcessStep approveStep = ProcessStep.builder()
                .id("approve")
                .name("Approve Path")
                .stepType(StepType.AUTO)
                .dependsOn(List.of("rules"))
                .conditionExpression("#decision != null && #decision.toString().equalsIgnoreCase('APPROVE')")
                .executionExpressions(Map.of("route", "'approved'"))
                .build();
        ProcessStep rejectStep = ProcessStep.builder()
                .id("reject")
                .name("Reject Path")
                .stepType(StepType.AUTO)
                .dependsOn(List.of("rules"))
                .conditionExpression("#decision != null && #decision.toString().equalsIgnoreCase('REJECT')")
                .executionExpressions(Map.of("rejected", "true"))
                .build();

        ProcessPhase phase = ProcessPhase.builder()
                .id("p1").name("Decision Phase").order(1)
                .steps(List.of(droolsStep, approveStep, rejectStep))
                .build();

        ProcessDefinition approved = approvedDefinition("Drools Branch Process", phase);

        WorkflowRun run = service.startRun(approved.getId(), Map.of("amount", 100));

        assertEquals(RunStatus.COMPLETED, run.getStatus());
        assertEquals("approved", run.getRunData().get("route"));
        assertFalse(run.getRunData().containsKey("rejected"));
        assertEquals(StepExecutionStatus.COMPLETED, run.getStepExecutions().stream()
                .filter(se -> "approve".equals(se.getStepId())).findFirst().orElseThrow().getStatus());
        assertEquals(StepExecutionStatus.SKIPPED, run.getStepExecutions().stream()
                .filter(se -> "reject".equals(se.getStepId())).findFirst().orElseThrow().getStatus());
    }

    @Test
    void droolsDecisionTableStep_failsWithoutDecisionTable() {
        StepExecutionDispatcher dispatcher = mock(StepExecutionDispatcher.class);
        service.setStepExecutionDispatcher(dispatcher);

        ProcessStep tableStep = ProcessStep.builder()
                .id("1.1")
                .name("Decision Table")
                .stepType(StepType.DROOLS_DECISION_TABLE)
                .droolsInputType("CSV")
                .build();

        ProcessPhase phase = ProcessPhase.builder()
                .id("p1").name("Decision Phase").order(1)
                .steps(List.of(tableStep))
                .build();

        ProcessDefinition approved = approvedDefinition("Drools Table Missing Content", phase);

        WorkflowRun run = service.startRun(approved.getId(), Map.of("amount", 125));

        assertEquals(RunStatus.FAILED, run.getStatus());
        assertTrue(run.getStepExecutions().stream()
                .anyMatch(se -> se.getError() != null && se.getError().contains("droolsDecisionTable")));
        verify(dispatcher, never()).executeDroolsDecisionTable(any(), any(), any(), any());
    }

    @Test
    void workflowStep_inputMappingSelectsSubsetOfRunData() {
        StepExecutionDispatcher dispatcher = mock(StepExecutionDispatcher.class);
        Map<String, Object> workflowOutputs = Map.of("answer", "ok", "score", 0.92);
        when(dispatcher.executeWorkflow(eq("xircuits"), eq("customer-flow"), isNull(), any(), eq(120)))
                .thenReturn(workflowOutputs);
        service.setStepExecutionDispatcher(dispatcher);

        ProcessStep workflowStep = ProcessStep.builder()
                .id("1.1")
                .name("Run Workflow")
                .stepType(StepType.WORKFLOW)
                .workflowEngineType("xircuits")
                .workflowName("customer-flow")
                .workflowTimeoutSeconds(120)
                .workflowInputMapping(Map.of("question", "query"))
                .workflowOutputKey("workflowResult")
                .build();

        ProcessPhase phase = ProcessPhase.builder()
                .id("p1").name("Workflow Phase").order(1)
                .steps(List.of(workflowStep))
                .build();

        ProcessDefinition approved = approvedDefinition("Workflow Process", phase);

        WorkflowRun run = service.startRun(approved.getId(),
                Map.of("question", "hello", "ignored", "not-sent"));

        assertEquals(RunStatus.COMPLETED, run.getStatus());
        assertEquals("ok", run.getRunData().get("answer"));
        assertNotNull(run.getRunData().get("workflowResult"));
        verify(dispatcher).executeWorkflow(eq("xircuits"), eq("customer-flow"), isNull(), argThat(inputs ->
                inputs.size() == 1 && "hello".equals(inputs.get("query"))), eq(120));
    }
}
