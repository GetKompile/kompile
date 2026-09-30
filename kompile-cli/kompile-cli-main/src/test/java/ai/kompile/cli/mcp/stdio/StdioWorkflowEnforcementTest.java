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

package ai.kompile.cli.mcp.stdio;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.roles.RoleManager;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.workflow.WorkflowSessionContext;
import ai.kompile.cli.main.chat.workflow.WorkflowTeam;
import ai.kompile.cli.main.chat.workflow.WorkflowTeamEnforcement;
import ai.kompile.cli.main.chat.workflow.WorkflowTeamSnapshot;
import ai.kompile.cli.main.chat.workflow.WorkflowTeamStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Workflow team enforcement through the MCP delegation tools: identity is
 * harness-owned (the inherited environment or the chat's session context),
 * invalid batches launch nobody, and a delegation runs the participant the
 * team routes it to, on that participant's bound CLI agent and model. The
 * team's roles are created in the user's home, so the class runs in a
 * temporary one.
 */
@TemporaryUserHome
class StdioWorkflowEnforcementTest {

    @TempDir
    Path project;

    private static final WorkflowTeamEnforcement.DelegationDecision.Allowed TO_WORKER =
            new WorkflowTeamEnforcement.DelegationDecision.Allowed("worker", "implementer");

    private final ObjectMapper objectMapper = JsonUtils.standardMapper();

    @BeforeEach
    void saveAndSetActiveWorkflow() throws IOException {
        WorkflowTeam team = new WorkflowTeam("wf-test", 1, "designer",
                Map.of(
                        "designer", new WorkflowTeam.Participant("designer", "architect",
                                "cli", List.of("read", "plan", "delegate"), List.of("worker")),
                        "worker", new WorkflowTeam.Participant("worker", "implementer",
                                "cli", List.of("read", "edit-assigned-files", "validate"), List.of())),
                Map.of("implement", "worker"),
                new WorkflowTeam.Limits(2),
                new WorkflowTeam.Gates("approved-design", null));
        assertTrue(WorkflowTeamStore.save(project, team, true));
        // The workflow's referenced roles must exist for enforcement to activate
        // (fail-closed validation); create the ones that are not built in.
        RoleManager roleManager = new RoleManager(project);
        if (roleManager.getRole("architect") == null) {
            roleManager.createRole("architect", "Architect", "Test designer role", "workflow", "Design first.");
        }
        if (roleManager.getRole("implementer") == null) {
            roleManager.createRole("implementer", "Implementer", "Test worker role", "workflow", "Implement the design.");
        }
    }

    @AfterEach
    void clearActiveWorkflow() {
        WorkflowSessionContext.activate(null);
    }

    private static Map<String, Object> subtask(String name, String agent) {
        Map<String, Object> subtask = new LinkedHashMap<>();
        subtask.put("name", name);
        subtask.put("prompt", "do work");
        subtask.put("agent", agent);
        return subtask;
    }

    /** A workflow subtask: the team picks its participant from the purpose, so it names no agent. */
    private static Map<String, Object> routed(String name, String purpose) {
        Map<String, Object> subtask = new LinkedHashMap<>();
        subtask.put("name", name);
        subtask.put("prompt", "do work");
        subtask.put("purpose", purpose);
        return subtask;
    }

    /** The stored wf-test team with its worker bound to {@code binding}. */
    private WorkflowTeam teamWithWorkerOn(WorkflowTeam.ModelBinding binding) throws IOException {
        WorkflowTeam team = WorkflowTeamStore.get(project, "wf-test");
        return team.withParticipant(team.participant("worker").withModel(binding));
    }

    /**
     * Stores wf-test with its worker on openai/gpt-5-mini (thinking medium) and
     * a reviewer on anthropic/claude-sonnet-5 (no thinking), and activates the
     * stored team as the chat's session team.
     */
    private void activateBoundTeam(RoleManager roleManager) throws IOException {
        if (roleManager.getRole("reviewer") == null) {
            roleManager.createRole("reviewer", "Reviewer", "Test reviewer role", "workflow", "Review the change.");
        }
        WorkflowTeam team = new WorkflowTeam("wf-test", 1, "designer",
                Map.of(
                        "designer", new WorkflowTeam.Participant("designer", "architect",
                                "cli", List.of("read", "plan", "delegate"), List.of("worker", "reviewer")),
                        "worker", new WorkflowTeam.Participant("worker", "implementer",
                                "cli", List.of("read", "edit-assigned-files", "validate"), List.of(),
                                new WorkflowTeam.ModelBinding("openai", "gpt-5-mini", "medium")),
                        "reviewer", new WorkflowTeam.Participant("reviewer", "reviewer",
                                "cli", List.of("read", "validate"), List.of(),
                                new WorkflowTeam.ModelBinding("anthropic", "claude-sonnet-5", null))),
                Map.of("implement", "worker", "review", "reviewer"),
                new WorkflowTeam.Limits(2),
                new WorkflowTeam.Gates("approved-design", null));
        assertTrue(WorkflowTeamStore.save(project, team, true));
        WorkflowSessionContext.activate(
                WorkflowTeamSnapshot.resolve(WorkflowTeamStore.get(project, "wf-test"), roleManager));
    }

    @Test
    void noWorkflowEnvironmentMeansUnchangedDelegationBehavior() {
        CapturingRunner runner = new CapturingRunner(project);
        StdioTaskTool task = new StdioTaskTool(null, runner, objectMapper, null, project);
        ToolResult result = task.execute(Map.of(
                "description", "plain", "prompt", "work", "agent", "codex"));
        // Without KOMPILE_WORKFLOW_NAME set, the legacy path runs unchanged. The
        // runner will fail to find a real agent binary; that failure mode is
        // identical to the pre-workflow behavior and is asserted by
        // StdioAgentAvailabilityTest, so here we only assert the tool did not
        // return a workflow-specific rejection.
        assertFalse(String.valueOf(result.getOutput()).contains("Workflow '"));
    }

    @Test
    void batchWithTwoSubtasksStillLaunchesWhenNoWorkflowIsActive() {
        CapturingRunner runner = new CapturingRunner(project);
        StdioMultiTaskTool multi = new StdioMultiTaskTool(null, runner, objectMapper, project, null);
        ToolResult result = multi.execute(Map.of(
                "description", "plain batch",
                "subtasks", List.of(subtask("one", "codex"), subtask("two", "codex"))));
        assertFalse(String.valueOf(result.getOutput()).contains("Workflow '"));
    }

    @Test
    void storedWorkflowResolvesRolesThroughRoleManager() throws IOException {
        // The enforcement helper fails closed when a referenced role is missing
        // and resolves cleanly when roles exist.
        RoleManager roleManager = new RoleManager(project);
        WorkflowTeam team = WorkflowTeamStore.get(project, "wf-test");
        WorkflowTeamEnforcement enforcement = StdioTaskTool.workflowEnforcementWith(
                project, roleManager, team);
        assertNotNull(enforcement);
        assertEquals("designer", enforcement.callerParticipant());

        // Missing role → fail closed before any delegation.
        WorkflowTeam broken = new WorkflowTeam("broken", 1, "designer",
                Map.of("designer", new WorkflowTeam.Participant("designer", "no-such-role",
                        "cli", List.of("delegate"), List.of())),
                Map.of(), null, null);
        assertThrows(IllegalStateException.class,
                () -> StdioTaskTool.workflowEnforcementWith(project, roleManager, broken));
    }

    @Test
    void toolArgumentSelectorsCannotConferWorkflowIdentity() throws IOException {
        RoleManager roleManager = new RoleManager(project);
        WorkflowTeam team = WorkflowTeamStore.get(project, "wf-test");
        WorkflowTeamEnforcement enforcement =
                StdioTaskTool.workflowEnforcementWith(project, roleManager, team);
        // Even if a request claims the designer role, identity stays harness-owned;
        // the worker participant cannot delegate regardless of requested role text.
        WorkflowTeamEnforcement workerView = WorkflowTeamEnforcement.forCaller(
                new WorkflowTeamSnapshot(team,
                        Map.of("designer", "architect", "worker", "implementer"), null),
                "worker");
        var decision = workerView.evaluateDelegation("implement", "architect");
        assertInstanceOf(WorkflowTeamEnforcement.DelegationDecision.Denied.class, decision);
    }

    @Test
    void unboundParticipantRunsTheRequestedSelectors() throws IOException {
        WorkflowTeam team = WorkflowTeamStore.get(project, "wf-test");

        assertEquals(new StdioTaskTool.ParticipantLaunch("worker", "implementer", "codex", null, null),
                StdioTaskTool.participantLaunch(team, TO_WORKER, null, " ", null));
        assertEquals(new StdioTaskTool.ParticipantLaunch("worker", "implementer", "claude", "claude-sonnet-5", "high"),
                StdioTaskTool.participantLaunch(team, TO_WORKER, "CLAUDE", "claude-sonnet-5", "high"));
        // Without a resolved role the participant's own role applies.
        assertEquals("implementer", StdioTaskTool.participantLaunch(team,
                new WorkflowTeamEnforcement.DelegationDecision.Allowed("worker", null), null, null, null).role());
    }

    @Test
    void boundParticipantRunsItsBindingAndAcceptsMatchingSelectors() throws IOException {
        WorkflowTeam team = teamWithWorkerOn(new WorkflowTeam.ModelBinding("openai", "gpt-5-mini", "medium"));
        StdioTaskTool.ParticipantLaunch bound =
                new StdioTaskTool.ParticipantLaunch("worker", "implementer", "codex", "gpt-5-mini", "medium");

        assertEquals(bound, StdioTaskTool.participantLaunch(team, TO_WORKER, null, null, null));
        assertEquals(bound, StdioTaskTool.participantLaunch(team, TO_WORKER, "Codex", "gpt-5-mini", "medium"));

        // A binding without thinking leaves thinking to the request.
        WorkflowTeam noThinking = teamWithWorkerOn(new WorkflowTeam.ModelBinding("openai", "gpt-5-mini", null));
        assertEquals("high", StdioTaskTool.participantLaunch(noThinking, TO_WORKER, null, null, "high").thinking());
        assertNull(StdioTaskTool.participantLaunch(noThinking, TO_WORKER, null, null, null).thinking());
    }

    @Test
    void selectorsThatContradictTheBindingAreRefusedNamingTheBinding() throws IOException {
        WorkflowTeam team = teamWithWorkerOn(new WorkflowTeam.ModelBinding("openai", "gpt-5-mini", "medium"));
        String hint = " The user can change its model with /workflow model worker.";

        assertEquals("Workflow 'wf-test' runs participant 'worker' as the codex CLI agent; "
                        + "omit agent (requested claude)." + hint,
                assertThrows(IllegalArgumentException.class,
                        () -> StdioTaskTool.participantLaunch(team, TO_WORKER, "claude", null, null)).getMessage());
        assertEquals("Workflow 'wf-test' runs participant 'worker' on openai/gpt-5-mini (thinking: medium); "
                        + "omit model (requested gpt-5)." + hint,
                assertThrows(IllegalArgumentException.class,
                        () -> StdioTaskTool.participantLaunch(team, TO_WORKER, null, "gpt-5", null)).getMessage());
        assertEquals("Workflow 'wf-test' runs participant 'worker' with thinking medium; "
                        + "omit thinking (requested high)." + hint,
                assertThrows(IllegalArgumentException.class,
                        () -> StdioTaskTool.participantLaunch(team, TO_WORKER, null, null, "high")).getMessage());

        WorkflowTeam unmapped = teamWithWorkerOn(new WorkflowTeam.ModelBinding("acme", "acme-large", null));
        assertEquals("Workflow 'wf-test' runs participant 'worker' on acme/acme-large, "
                        + "which no supported CLI agent runs." + hint,
                assertThrows(IllegalArgumentException.class,
                        () -> StdioTaskTool.participantLaunch(unmapped, TO_WORKER, null, null, null)).getMessage());
    }

    @Test
    void inheritedIdentityResolvesTheNamedParticipantAndFailsClosed() throws IOException {
        RoleManager roleManager = new RoleManager(project);
        assertNull(StdioTaskTool.inheritedEnforcement(project, roleManager, null, "worker", null));
        assertNull(StdioTaskTool.inheritedEnforcement(project, roleManager, " ", "worker", null));

        assertEquals("designer",
                StdioTaskTool.inheritedEnforcement(project, roleManager, "wf-test", "", null).callerParticipant());
        WorkflowTeamEnforcement worker =
                StdioTaskTool.inheritedEnforcement(project, roleManager, "WF-Test", " Worker ", null);
        assertEquals("worker", worker.callerParticipant());
        assertFalse(worker.evaluateToolUse("task").allowed());

        String undefined = assertThrows(IllegalStateException.class,
                () -> StdioTaskTool.inheritedEnforcement(project, roleManager, "nope", null, null)).getMessage();
        assertTrue(undefined.startsWith("Workflow 'nope' is not defined in "), undefined);
        assertEquals("Workflow 'wf-test' has no participant 'ghost'; refusing to delegate.",
                assertThrows(IllegalStateException.class, () -> StdioTaskTool.inheritedEnforcement(
                        project, roleManager, "wf-test", "ghost", null)).getMessage());

        Files.writeString(WorkflowTeamStore.path(project), "{ not json");
        String unreadable = assertThrows(IllegalStateException.class,
                () -> StdioTaskTool.inheritedEnforcement(project, roleManager, "wf-test", null, null)).getMessage();
        assertTrue(unreadable.startsWith("Workflow 'wf-test' is configured but could not be read: "), unreadable);
    }

    @Test
    void inheritedIdentityEnforcesTheSessionTeamWithTheGatesTheUserApproved() throws IOException {
        RoleManager roleManager = new RoleManager(project);
        WorkflowTeamSnapshot snapshot =
                WorkflowTeamSnapshot.resolve(WorkflowTeamStore.get(project, "wf-test"), roleManager);
        String session = "wf-enf-" + System.nanoTime();
        try {
            WorkflowSessionContext.persist(session, snapshot);
            WorkflowTeamEnforcement lead =
                    StdioTaskTool.inheritedEnforcement(project, roleManager, "wf-test", null, session);
            assertEquals(session, lead.sessionId());
            assertInstanceOf(WorkflowTeamEnforcement.DelegationDecision.Denied.class,
                    lead.evaluateDelegation("implement", null));

            // An approval recorded after the server started applies to its next delegation.
            WorkflowSessionContext.persist(session, snapshot, Set.of("approved-design"));
            assertEquals(TO_WORKER, StdioTaskTool.inheritedEnforcement(project, roleManager, "wf-test", null, session)
                    .evaluateDelegation("implement", null));

            assertEquals("Chat session " + session + " runs workflow 'wf-test', not 'other'; refusing to delegate.",
                    assertThrows(IllegalStateException.class, () -> StdioTaskTool.inheritedEnforcement(
                            project, roleManager, "other", null, session)).getMessage());

            // Rebinding the stored team outside this chat stops delegation until a new chat session starts.
            assertTrue(WorkflowTeamStore.save(project,
                    teamWithWorkerOn(new WorkflowTeam.ModelBinding("openai", "gpt-5-mini", null)), true));
            String changed = assertThrows(IllegalStateException.class, () -> StdioTaskTool.inheritedEnforcement(
                    project, roleManager, "wf-test", null, session)).getMessage();
            assertTrue(changed.contains("changed since this session started"), changed);
            assertTrue(changed.contains("v2"), changed);
        } finally {
            WorkflowSessionContext.clear(session);
        }
    }

    @Test
    void taskRunsTheRoutedParticipantOnItsBoundModelOnceTheUserApproves() throws IOException {
        RoleManager roleManager = new RoleManager(project);
        activateBoundTeam(roleManager);
        CapturingRunner runner = new CapturingRunner(project);
        StdioTaskTool task = new StdioTaskTool(null, runner, objectMapper, roleManager, project);
        Map<String, Object> implement = Map.of("description", "build", "prompt", "build it", "purpose", "implement");

        ToolResult gated = task.execute(implement);
        assertTrue(gated.isError());
        assertTrue(gated.getOutput().contains("gates implementation: 'approved-design' is not satisfied yet"),
                gated.getOutput());
        assertNull(runner.lastAgent);

        assertEquals("approved-design", WorkflowSessionContext.current().approve(null));
        ToolResult result = task.execute(implement);
        assertFalse(result.isError(), result.getOutput());
        assertEquals("codex", runner.lastAgent.getName());
        assertEquals("gpt-5-mini", runner.lastAgent.getModelOverride());
        assertEquals("medium", runner.lastAgent.getThinkingOverride());
        assertEquals("implementer", runner.lastAgent.getRoleName());
        assertEquals("worker", runner.lastAgent.getWorkflowParticipant());
        assertEquals("wf-test", result.getMetadata().get("workflow"));
        assertEquals("worker", result.getMetadata().get("workflowParticipant"));
        assertEquals("gpt-5-mini", result.getMetadata().get("model"));

        String wrongAgent = task.execute(Map.of("description", "build", "prompt", "build it",
                "purpose", "implement", "agent", "claude")).getOutput();
        assertTrue(wrongAgent.contains("as the codex CLI agent; omit agent (requested claude)."), wrongAgent);
        String noPurpose = task.execute(Map.of("description", "build", "prompt", "build it")).getOutput();
        assertTrue(noPurpose.contains("needs a routing 'purpose'"), noPurpose);
    }

    @Test
    void aLeadWithoutDelegateCannotUseTheDelegationTools() {
        WorkflowTeam solo = new WorkflowTeam("solo", 1, "designer",
                Map.of("designer", new WorkflowTeam.Participant("designer", "architect",
                        "cli", List.of("read", "plan"), List.of())),
                Map.of(), null, null);
        WorkflowSessionContext.activate(WorkflowTeamSnapshot.resolve(solo, new RoleManager(project)));
        CapturingRunner runner = new CapturingRunner(project);
        String refusal = "Participant 'designer' lacks the 'delegate' capability in workflow 'solo'.";

        assertEquals(refusal, new StdioTaskTool(null, runner, objectMapper, null, project)
                .execute(Map.of("description", "d", "prompt", "p", "purpose", "implement")).getOutput());
        assertEquals(refusal, new StdioMultiTaskTool(null, runner, objectMapper, project, null)
                .execute(Map.of("description", "d",
                        "subtasks", List.of(routed("one", "implement"), routed("two", "implement"))))
                .getOutput());
        assertTrue(runner.capturedAgents.isEmpty());
    }

    @Test
    void multiTaskLaunchesEachSubtaskAsItsRoutedParticipant() throws IOException {
        RoleManager roleManager = new RoleManager(project);
        activateBoundTeam(roleManager);
        WorkflowSessionContext.current().approve(null);
        CapturingRunner runner = new CapturingRunner(project);
        StdioMultiTaskTool multi = new StdioMultiTaskTool(null, runner, objectMapper, project, roleManager);

        ToolResult result = multi.execute(Map.of("description", "build and review",
                "subtasks", List.of(routed("build", "implement"), routed("check", "review"))));

        assertFalse(result.isError(), result.getOutput());
        assertEquals(2, runner.capturedAgents.size());
        Map<String, AgentConfig> byParticipant = new HashMap<>();
        runner.capturedAgents.forEach(agent -> byParticipant.put(agent.getWorkflowParticipant(), agent));
        assertEquals(Set.of("worker", "reviewer"), byParticipant.keySet());
        AgentConfig worker = byParticipant.get("worker");
        assertEquals("codex", worker.getName());
        assertEquals("gpt-5-mini", worker.getModelOverride());
        assertEquals("medium", worker.getThinkingOverride());
        assertEquals("implementer", worker.getRoleName());
        AgentConfig reviewer = byParticipant.get("reviewer");
        assertEquals("claude", reviewer.getName());
        assertEquals("claude-sonnet-5", reviewer.getModelOverride());
        assertNull(reviewer.getThinkingOverride());
        assertEquals("reviewer", reviewer.getRoleName());
    }

    @Test
    void multiTaskRejectsTheWholeBatchBeforeAnyLaunch() throws IOException {
        RoleManager roleManager = new RoleManager(project);
        activateBoundTeam(roleManager);
        WorkflowSessionContext.current().approve(null);
        CapturingRunner runner = new CapturingRunner(project);
        StdioMultiTaskTool multi = new StdioMultiTaskTool(null, runner, objectMapper, project, roleManager);

        Map<String, Object> build = routed("build", "implement");
        build.put("agent_count", 2);
        Map<String, Object> check = routed("check", "review");
        check.put("agent_count", 2);
        String oversized = multi.execute(Map.of("description", "d", "subtasks", List.of(build, check))).getOutput();
        assertTrue(oversized.startsWith("Workflow 'wf-test' rejected this batch before launch:"), oversized);
        assertTrue(oversized.contains(
                "Requested 4 instances but workflow 'wf-test' allows at most 2 concurrent workers."), oversized);

        Map<String, Object> twoAgents = routed("check", "review");
        twoAgents.put("agents", List.of("codex", "claude"));
        String ambiguous = multi.execute(Map.of("description", "d",
                "subtasks", List.of(routed("build", "implement"), twoAgents))).getOutput();
        assertTrue(ambiguous.contains("subtask[1] 'check': a workflow subtask runs as one participant; "
                + "give at most one agent (requested codex, claude)."), ambiguous);
        assertTrue(runner.capturedAgents.isEmpty());
    }

    /** Mirrors StdioAgentAvailabilityTest's capture pattern. */
    private static final class CapturingRunner extends DirectSubagentRunnerStdio {
        AgentConfig lastAgent;
        final List<AgentConfig> capturedAgents = new CopyOnWriteArrayList<>();

        CapturingRunner(Path workDir) {
            super(workDir);
        }

        @Override
        DirectSubagentRunnerStdio forkForSubagent() {
            return this;
        }

        @Override
        public String runSubagent(AgentConfig agent, String prompt) {
            lastAgent = agent;
            capturedAgents.add(agent);
            return "completed";
        }
    }
}
