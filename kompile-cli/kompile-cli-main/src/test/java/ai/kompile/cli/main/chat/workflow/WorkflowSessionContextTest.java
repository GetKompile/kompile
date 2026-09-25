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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The harness-owned workflow session context: activation, per-transcript
 * persistence/restore (resume reuses the team), the child environment
 * contract, and the system-prompt acknowledgment.
 */
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
        assertThrows(java.io.IOException.class,
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
}
