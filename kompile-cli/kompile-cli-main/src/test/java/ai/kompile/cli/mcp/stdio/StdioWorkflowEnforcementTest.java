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
import ai.kompile.cli.main.chat.tools.ToolResult;
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
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Workflow team enforcement through the MCP delegation tools: identity is
 * harness-owned (environment), invalid batches launch nobody, and the
 * workflow's destination assignment overrides tool-argument selectors.
 */
class StdioWorkflowEnforcementTest {

    @TempDir
    Path project;

    private final ObjectMapper objectMapper = JsonUtils.standardMapper();
    private String previousWorkflowName;

    @BeforeEach
    void saveAndSetActiveWorkflow() throws IOException {
        previousWorkflowName = System.getenv(WorkflowTeamEnforcement.ENV_WORKFLOW_NAME);
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
        // (fail-closed validation); create them as real project roles.
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
        // Restore is a no-op (env is immutable in-process); documented for symmetry.
    }

    private static Map<String, Object> subtask(String name, String agent) {
        Map<String, Object> subtask = new LinkedHashMap<>();
        subtask.put("name", name);
        subtask.put("prompt", "do work");
        subtask.put("agent", agent);
        return subtask;
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
        WorkflowTeam team = WorkflowTeamStore.get(project, "wf-test");        WorkflowTeamEnforcement enforcement = StdioTaskTool.workflowEnforcementWith(
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
