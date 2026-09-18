/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ai.kompile.cli.main.chat.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowTeamEnforcementTest {

    private static WorkflowTeam team() {
        return new WorkflowTeam("designer-workers", 1, "designer",
                Map.of(
                        "designer", new WorkflowTeam.Participant("designer", "architect",
                                "cli", List.of("read", "plan", "delegate"), List.of("worker")),
                        "worker", new WorkflowTeam.Participant("worker", "implementer",
                                "cli", List.of("read", "edit-assigned-files", "validate"), List.of())),
                Map.of("implement", "worker"),
                new WorkflowTeam.Limits(3),
                new WorkflowTeam.Gates("user-approved-design", "all-tasks-accepted"));
    }

    private static WorkflowTeamEnforcement asDesigner() {
        return WorkflowTeamEnforcement.forCaller(
                new WorkflowTeamSnapshot(team(),
                        Map.of("designer", "architect", "worker", "implementer"), null),
                "designer");
    }

    private static WorkflowTeamEnforcement asWorker() {
        return WorkflowTeamEnforcement.forCaller(
                new WorkflowTeamSnapshot(team(),
                        Map.of("designer", "architect", "worker", "implementer"), null),
                "worker");
    }

    @Test
    void designerCannotEditButCanReadAndPlan() {
        WorkflowTeamEnforcement enforcement = asDesigner();
        assertTrue(enforcement.evaluateToolUse("read").allowed());
        assertTrue(enforcement.evaluateToolUse("grep").allowed());
        assertTrue(enforcement.evaluateToolUse("todowrite").allowed());
        assertFalse(enforcement.evaluateToolUse("edit").allowed());
        assertFalse(enforcement.evaluateToolUse("write").allowed());
        assertFalse(enforcement.evaluateToolUse("patch").allowed());
        assertFalse(enforcement.evaluateToolUse("bash.write").allowed());
        assertFalse(enforcement.evaluateToolUse("bash.destructive").allowed());
    }

    @Test
    void workerCanEditAndValidate() {
        WorkflowTeamEnforcement enforcement = asWorker();
        assertTrue(enforcement.evaluateToolUse("edit").allowed());
        assertTrue(enforcement.evaluateToolUse("bash.write").allowed());
    }

    @Test
    void workerCannotDelegate() {
        WorkflowTeamEnforcement enforcement = asWorker();
        var decision = enforcement.evaluateDelegation("anything", null);
        assertInstanceOf(WorkflowTeamEnforcement.DelegationDecision.Denied.class, decision);
        assertTrue(((WorkflowTeamEnforcement.DelegationDecision.Denied) decision).reason()
                .contains("lacks the 'delegate' capability"));
        assertFalse(enforcement.evaluateToolUse("task").allowed());
    }

    @Test
    void implementationGateBlocksEditDelegationUntilSatisfied() {
        WorkflowTeamEnforcement enforcement = asDesigner();
        var blocked = enforcement.evaluateDelegation("implement", null);
        assertInstanceOf(WorkflowTeamEnforcement.DelegationDecision.Denied.class, blocked);
        assertTrue(((WorkflowTeamEnforcement.DelegationDecision.Denied) blocked).reason()
                .contains("user-approved-design"));

        enforcement.satisfyGate("User-Approved-Design");
        var allowed = enforcement.evaluateDelegation("implement", null);
        assertInstanceOf(WorkflowTeamEnforcement.DelegationDecision.Allowed.class, allowed);
        assertEquals("worker", ((WorkflowTeamEnforcement.DelegationDecision.Allowed) allowed)
                .resolvedParticipant());
        assertEquals("implementer", ((WorkflowTeamEnforcement.DelegationDecision.Allowed) allowed)
                .resolvedRole());
    }

    @Test
    void unknownPurposeAndUnknownRoleAreRefusedWithRoutingGuidance() {
        WorkflowTeamEnforcement enforcement = asDesigner();
        enforcement.satisfyGate("user-approved-design");
        var unknownPurpose = enforcement.evaluateDelegation("deploy-prod", null);
        assertInstanceOf(WorkflowTeamEnforcement.DelegationDecision.Denied.class, unknownPurpose);
        assertTrue(((WorkflowTeamEnforcement.DelegationDecision.Denied) unknownPurpose).reason()
                .contains("Available purposes: [implement]"));

        var unknownRole = enforcement.evaluateDelegation(null, "devops");
        assertInstanceOf(WorkflowTeamEnforcement.DelegationDecision.Denied.class, unknownRole);
        assertTrue(((WorkflowTeamEnforcement.DelegationDecision.Denied) unknownRole).reason()
                .contains("not bound to any participant"));

        var noSelector = enforcement.evaluateDelegation("", "");
        assertInstanceOf(WorkflowTeamEnforcement.DelegationDecision.Denied.class, noSelector);
    }

    @Test
    void delegationEdgesAreEnforced() {
        // Worker holds delegate in this variant but has no edge to designer.
        WorkflowTeam edgeless = new WorkflowTeam("edgeless", 1, "w1",
                Map.of(
                        "w1", new WorkflowTeam.Participant("w1", "r1", "cli",
                                List.of("delegate"), List.of()),
                        "w2", new WorkflowTeam.Participant("w2", "r2", "cli",
                                List.of("read"), List.of())),
                Map.of("do", "w2"), null, null);
        WorkflowTeamEnforcement enforcement = WorkflowTeamEnforcement.forCaller(
                new WorkflowTeamSnapshot(edgeless, Map.of("w1", "r1", "w2", "r2"), null), "w1");
        // w1 delegates to itself only via missing edge → purpose resolves to w2,
        // but the edge w1→w2 is absent.
        var decision = enforcement.evaluateDelegation("do", null);
        assertInstanceOf(WorkflowTeamEnforcement.DelegationDecision.Denied.class, decision);
        assertTrue(((WorkflowTeamEnforcement.DelegationDecision.Denied) decision).reason()
                .contains("does not allow 'w1' to delegate to 'w2'"));
    }

    @Test
    void batchSizeIsCappedByWorkflowLimits() {
        WorkflowTeamEnforcement enforcement = asDesigner();
        assertInstanceOf(WorkflowTeamEnforcement.DelegationDecision.Allowed.class,
                enforcement.evaluateBatchSize(3));
        var denied = enforcement.evaluateBatchSize(4);
        assertInstanceOf(WorkflowTeamEnforcement.DelegationDecision.Denied.class, denied);
        assertTrue(((WorkflowTeamEnforcement.DelegationDecision.Denied) denied).reason()
                .contains("at most 3"));
    }

    @Test
    void unknownHarnessIdentityIsRefused() {
        assertThrows(IllegalArgumentException.class, () ->
                WorkflowTeamEnforcement.forCaller(
                        new WorkflowTeamSnapshot(team(), Map.of("designer", "architect"), null),
                        "intruder"));
    }

    @Test
    void childEnvironmentBindsTheParticipantAndCompletionGateTracks() {
        WorkflowTeamEnforcement enforcement = asDesigner();
        assertEquals(Map.of(
                        WorkflowTeamEnforcement.ENV_WORKFLOW_NAME, "designer-workers",
                        WorkflowTeamEnforcement.ENV_WORKFLOW_PARTICIPANT, "worker"),
                enforcement.childEnvironment("worker"));
        assertThrows(IllegalArgumentException.class, () -> enforcement.childEnvironment("ghost"));

        assertFalse(enforcement.completionGateSatisfied());
        // The implementation gate is still outstanding until its own gate is satisfied.
        assertTrue(enforcement.outstandingObligations().contains("implementation gate"));
        enforcement.satisfyGate("user-approved-design");
        enforcement.satisfyGate("all-tasks-accepted");
        assertTrue(enforcement.completionGateSatisfied());
        assertEquals("none", enforcement.outstandingObligations());
    }

    @Test
    void statusLineReportsIdentityAndOutstandingGates() {
        WorkflowTeamEnforcement enforcement = asDesigner();
        String status = enforcement.statusLine();
        assertTrue(status.contains("designer-workers"));
        assertTrue(status.contains("you are 'designer'"));
        assertTrue(status.contains("implementation gate: user-approved-design"));
    }
}
