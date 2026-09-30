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

import ai.kompile.cli.main.chat.roles.RoleManager;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The harness-owned workflow session context: activation, per-transcript
 * persistence/restore (resume reuses the team), the child environment
 * contract, and the system-prompt acknowledgment. The team's roles are created
 * in the user's home, so the class runs in a temporary one.
 */
@TemporaryUserHome
class WorkflowSessionContextTest {

    @TempDir
    Path project;

    private WorkflowTeam team;
    private WorkflowTeamSnapshot snapshot;

    @BeforeEach
    void createTeamAndSnapshot() throws IOException {
        team = new WorkflowTeam("ctx-team", 1, "designer",
                Map.of(
                        "designer", new WorkflowTeam.Participant("designer", "architect",
                                "cli", List.of("read", "plan", "delegate"), List.of("worker")),
                        "worker", new WorkflowTeam.Participant("worker", "implementer",
                                "cli", List.of("read", "edit-assigned-files", "validate"), List.of())),
                Map.of("implement", "worker"),
                new WorkflowTeam.Limits(2),
                new WorkflowTeam.Gates("approved-design", null));
        assertTrue(WorkflowTeamStore.save(project, team, true));
        RoleManager roleManager = new RoleManager(project);
        if (roleManager.getRole("architect") == null) {
            roleManager.createRole("architect", "Architect", "d", "workflow", "design");
        }
        if (roleManager.getRole("implementer") == null) {
            roleManager.createRole("implementer", "Implementer", "d", "workflow", "implement");
        }
        snapshot = WorkflowTeamSnapshot.resolve(team, roleManager);
    }

    @AfterEach
    void deactivate() {
        WorkflowSessionContext.activate(null);
    }

    private static WorkflowTeam teamNamed(String name) {
        return new WorkflowTeam(name, 1, "designer",
                Map.of("designer", new WorkflowTeam.Participant("designer", "architect",
                        "cli", List.of("delegate"), List.of())),
                Map.of(), null, null);
    }

    @Test
    void activateInstallsLeadIdentityAndChildEnvironment() {
        WorkflowSessionContext.activate(snapshot);

        WorkflowSessionContext context = WorkflowSessionContext.current();
        assertNotNull(context);
        assertEquals("designer", context.enforcement().callerParticipant());
        assertEquals("ctx-team", context.workflowName());
        assertTrue(WorkflowSessionContext.isActive());

        Map<String, String> child = context.childEnvironment();
        assertEquals("ctx-team", child.get(WorkflowTeamEnforcement.ENV_WORKFLOW_NAME));
        assertEquals("designer", child.get(WorkflowTeamEnforcement.ENV_WORKFLOW_PARTICIPANT));

        Map<String, String> inheritable = WorkflowSessionContext.inheritableEnvironment();
        assertEquals(child, inheritable);
    }

    @Test
    void inactiveContextYieldsEmptyChildEnvironmentAndFallsBackToProcessEnv() {
        WorkflowSessionContext.activate(null);
        assertFalse(WorkflowSessionContext.isActive());
        assertTrue(WorkflowSessionContext.inheritableEnvironment().isEmpty());
    }

    @Test
    void persistThenRestoreReturnsTheSameTeamForResume() throws IOException {
        String sessionId = "wf-ctx-test-" + System.nanoTime();
        try {
            WorkflowSessionContext.persist(sessionId, snapshot);

            WorkflowTeamSnapshot restored =
                    WorkflowSessionContext.restore(sessionId, project);
            assertNotNull(restored);
            assertEquals(team, restored.team());
            // resolvedRole maps participant id -> role name.
            assertEquals("architect", restored.resolvedRole("designer"));
            assertEquals("implementer", restored.resolvedRole("worker"));

            // Activating the restore gives the resumed process the same identity.
            WorkflowSessionContext.activate(restored);
            assertEquals("designer",
                    WorkflowSessionContext.current().enforcement().callerParticipant());
        } finally {
            WorkflowSessionContext.clear(sessionId);
        }
        assertFalse(Files.exists(WorkflowSessionContext.sessionPath(sessionId)));
    }

    @Test
    void restoreWithoutSidecarIsEmptyAndNeverThrows() throws IOException {
        assertNull(WorkflowSessionContext.restore("no-such-session", project));
    }

    @Test
    void restoreOfADeletedTeamFailsClosed() throws IOException {
        String sessionId = "wf-ctx-gone-" + System.nanoTime();
        WorkflowSessionContext.persist(sessionId, snapshot);
        // Simulate the team definition being deleted after the session started.
        try {
            Files.delete(WorkflowTeamStore.path(project));
        } catch (IOException ignored) {
            // Treated as already deleted below; deserialize still must fail closed.
        }
        // deserialize throws checked IOException for a missing team; restore
        // propagates it — the caller aborts the resume, never silently proceeds.
        assertThrows(IOException.class,
                () -> WorkflowSessionContext.restore(sessionId, project));
        WorkflowSessionContext.clear(sessionId);
    }

    @Test
    void systemPromptSectionNamesTeamCallerAndPurposes() {
        WorkflowSessionContext.activate(snapshot);
        String section = WorkflowSessionContext.current().systemPromptSection();

        assertTrue(section.contains("ctx-team"));
        assertTrue(section.contains("designer"));
        assertTrue(section.contains("implement"));
        assertTrue(section.contains("implementer"));
    }

    @Test
    void enforcementDelegationStillRoutesThroughPurposeAndGate() {
        WorkflowSessionContext.activate(snapshot);
        WorkflowTeamEnforcement enforcement =
                WorkflowSessionContext.current().enforcement();

        // Implementation gate blocks edit-capable delegation until satisfied.
        var blocked = enforcement.evaluateDelegation("implement", null);
        assertInstanceOf(WorkflowTeamEnforcement.DelegationDecision.Denied.class, blocked);

        enforcement.satisfyGate("approved-design");
        var allowed = enforcement.evaluateDelegation("implement", null);
        if (allowed instanceof WorkflowTeamEnforcement.DelegationDecision.Allowed a) {
            assertEquals("worker", a.resolvedParticipant());
            assertEquals("implementer", a.resolvedRole());
        } else {
            fail("Expected the routed delegation to be allowed after the gate");
        }
    }

    @Test
    void participantEnvironmentNamesTheDelegateNotTheLead() {
        WorkflowSessionContext.activate(snapshot);

        assertEquals(Map.of(WorkflowTeamEnforcement.ENV_WORKFLOW_NAME, "ctx-team",
                        WorkflowTeamEnforcement.ENV_WORKFLOW_PARTICIPANT, "worker"),
                WorkflowSessionContext.inheritableEnvironment("Worker"));
        assertEquals(WorkflowSessionContext.inheritableEnvironment(),
                WorkflowSessionContext.inheritableEnvironment(" "));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowSessionContext.inheritableEnvironment("ghost"));
    }

    @Test
    void participantLaunchOutsideAWorkflowFailsClosed() {
        WorkflowSessionContext.activate(null);
        assertEquals("Workflow participant 'worker' was delegated outside an active workflow team",
                assertThrows(IllegalStateException.class,
                        () -> WorkflowSessionContext.inheritableEnvironment("worker")).getMessage());
    }

    @Test
    void workflowEnvironmentOfKeepsOnlyTheIdentityKeys() {
        assertNull(WorkflowSessionContext.workflowEnvironmentOf(null));
        assertNull(WorkflowSessionContext.workflowEnvironmentOf(Map.of("PATH", "/usr/bin")));
        assertNull(WorkflowSessionContext.workflowEnvironmentOf(
                Map.of(WorkflowTeamEnforcement.ENV_WORKFLOW_NAME, " ")));

        Map<String, String> launch = new LinkedHashMap<>();
        launch.put("PATH", "/usr/bin");
        launch.put(WorkflowTeamEnforcement.ENV_WORKFLOW_SESSION, "s1");
        launch.put(WorkflowTeamEnforcement.ENV_WORKFLOW_PARTICIPANT, "");
        launch.put(WorkflowTeamEnforcement.ENV_WORKFLOW_NAME, "ctx-team");
        Map<String, String> identity = WorkflowSessionContext.workflowEnvironmentOf(launch);
        assertEquals(List.of(WorkflowTeamEnforcement.ENV_WORKFLOW_NAME, WorkflowTeamEnforcement.ENV_WORKFLOW_SESSION),
                List.copyOf(identity.keySet()));
        assertEquals("ctx-team", identity.get(WorkflowTeamEnforcement.ENV_WORKFLOW_NAME));
        assertEquals("s1", identity.get(WorkflowTeamEnforcement.ENV_WORKFLOW_SESSION));
    }

    @Test
    void sessionGatesSurviveResumeAndRebindingButNotANewTranscript() throws IOException {
        String sessionId = "wf-ctx-life-" + System.nanoTime();
        String cleared = "wf-ctx-clear-" + System.nanoTime();
        try {
            WorkflowSessionContext.start(sessionId, snapshot);
            assertTrue(Files.exists(WorkflowSessionContext.sessionPath(sessionId)));
            assertTrue(WorkflowSessionContext.satisfiedGates(sessionId).isEmpty());
            assertEquals(sessionId, WorkflowSessionContext.current().childEnvironment()
                    .get(WorkflowTeamEnforcement.ENV_WORKFLOW_SESSION));

            assertEquals("approved-design", WorkflowSessionContext.current().approve(null));
            assertEquals(Set.of("approved-design"), WorkflowSessionContext.satisfiedGates(sessionId));

            // A restarted chat resumes the transcript with the approval it already had.
            WorkflowSessionContext.activate(null);
            WorkflowSessionContext.resume(sessionId, WorkflowSessionContext.restore(sessionId, project));
            assertTrue(WorkflowSessionContext.current().enforcement().implementationGateSatisfied());

            // Rebinding a participant keeps the session and its approvals; a resume restores the rebound team.
            assertTrue(WorkflowTeamStore.save(project, team.withParticipant(team.participant("worker")
                    .withModel(new WorkflowTeam.ModelBinding("openai", "gpt-5-mini", null))), true));
            WorkflowTeam stored = WorkflowTeamStore.get(project, "ctx-team");
            assertEquals(2, stored.version());
            WorkflowSessionContext.replace(WorkflowTeamSnapshot.resolve(stored, new RoleManager(project)));
            assertEquals(sessionId, WorkflowSessionContext.current().enforcement().sessionId());
            assertTrue(WorkflowSessionContext.current().enforcement().implementationGateSatisfied());
            assertEquals(stored, WorkflowSessionContext.restore(sessionId, project).team());

            // /clear starts a new transcript: the team follows it, the approval does not.
            WorkflowSessionContext.switchTranscript(cleared);
            assertEquals(cleared, WorkflowSessionContext.current().enforcement().sessionId());
            assertFalse(WorkflowSessionContext.current().enforcement().implementationGateSatisfied());
            assertTrue(WorkflowSessionContext.satisfiedGates(cleared).isEmpty());
            assertEquals(Set.of("approved-design"), WorkflowSessionContext.satisfiedGates(sessionId));
        } finally {
            WorkflowSessionContext.clear(sessionId);
            WorkflowSessionContext.clear(cleared);
        }
    }

    @Test
    void aSessionStartedAgainKeepsItsApprovalsOnlyWhileItsTeamIsUnchanged() throws IOException {
        String sessionId = "wf-ctx-restart-" + System.nanoTime();
        try {
            WorkflowSessionContext.start(sessionId, snapshot);
            WorkflowSessionContext.current().approve(null);

            // A web session whose first run ended before its transcript existed starts the team again.
            WorkflowSessionContext.activate(null);
            WorkflowSessionContext.start(sessionId, WorkflowTeamSnapshot.resolve(team, new RoleManager(project)));
            assertTrue(WorkflowSessionContext.current().enforcement().implementationGateSatisfied());
            assertEquals(Set.of("approved-design"), WorkflowSessionContext.satisfiedGates(sessionId));

            // Another team at the same version inherits nothing.
            WorkflowSessionContext.start(sessionId,
                    WorkflowTeamSnapshot.resolve(teamNamed("ctx-other"), new RoleManager(project)));
            assertTrue(WorkflowSessionContext.satisfiedGates(sessionId).isEmpty());
            WorkflowSessionContext.start(sessionId, snapshot);
            assertFalse(WorkflowSessionContext.current().enforcement().implementationGateSatisfied());
            WorkflowSessionContext.current().approve(null);

            // An edited team is a different team: its gates start unapproved.
            assertTrue(WorkflowTeamStore.save(project, team.withParticipant(team.participant("worker")
                    .withModel(new WorkflowTeam.ModelBinding("openai", "gpt-5-mini", null))), true));
            WorkflowSessionContext.start(sessionId, WorkflowTeamSnapshot.resolve(
                    WorkflowTeamStore.get(project, "ctx-team"), new RoleManager(project)));
            assertFalse(WorkflowSessionContext.current().enforcement().implementationGateSatisfied());
            assertTrue(WorkflowSessionContext.satisfiedGates(sessionId).isEmpty());
        } finally {
            WorkflowSessionContext.clear(sessionId);
        }
    }

    @Test
    void approveNamesTheTeamsGatesAndRefusesWhenNothingIsLeft() {
        WorkflowSessionContext.activate(snapshot);
        WorkflowSessionContext context = WorkflowSessionContext.current();

        assertEquals("Workflow 'ctx-team' has no gate 'nope'. Its gates: implementation 'approved-design'.",
                assertThrows(IllegalArgumentException.class, () -> context.approve("nope")).getMessage());
        assertEquals("approved-design", context.approve("Approved-Design"));
        assertEquals("Every gate of workflow 'ctx-team' is already approved.",
                assertThrows(IllegalArgumentException.class, () -> context.approve(null)).getMessage());

        WorkflowSessionContext.activate(WorkflowTeamSnapshot.resolve(teamNamed("bare"), new RoleManager(project)));
        assertEquals("Workflow 'bare' has no gates to approve.",
                assertThrows(IllegalArgumentException.class,
                        () -> WorkflowSessionContext.current().approve(null)).getMessage());
    }
}
