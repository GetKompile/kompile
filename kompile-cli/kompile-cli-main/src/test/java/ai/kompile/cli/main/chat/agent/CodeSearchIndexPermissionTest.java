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

package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.permission.PermissionService.PermissionLevel;
import ai.kompile.cli.main.chat.roles.BuiltInRoles;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.CodeSearchTool;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.workflow.WorkflowController;
import ai.kompile.cli.main.chat.workflow.WorkflowPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code code_search action=index} writes the index, so it has its own permission key:
 * read-only agents are denied it, coding agents keep it, and a supervised child gets
 * the parent's level through the inherited ceiling.
 */
@TemporaryUserHome
class CodeSearchIndexPermissionTest {

    private static final String KEY = CodeSearchTool.INDEX_PERMISSION_KEY;
    private static final String DESCRIPTION = "Build or refresh the code index";
    private static final List<String> READ_ONLY_AGENTS =
            List.of("planner", "explore-quick", "explore-deep", "explorer", "code-reviewer", "architect");

    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper();
    final ToolRegistry tools = new ToolRegistry(mapper);
    final PermissionService permissions = new PermissionService();
    final AgentRegistry agents = new AgentRegistry();

    /** No auto-approve: DENY overrides do not bind auto-approve sessions. */
    ToolContext context(AgentConfig agent) {
        ToolContext context = new ToolContext(agent.getName(), agent, permissions, directory, tools);
        context.setOutputConsumer(ignored -> {});
        return context;
    }

    ToolContext supervisedChild(ToolContext parent) {
        ToolContext child = context(agents.get("general"));
        child.markSupervisedChild();
        child.setSubagentSupervision(new DirectSubagentSupervision.Contract(
                new WorkflowController.ChildContract(WorkflowPolicy.off(), "", false),
                "work", null, null, null, false, 1).withCeiling(parent));
        child.setAutoApproveAll(true);
        return child;
    }

    @Test
    void readOnlyAgentsCannotIndexThroughCodeSearch() {
        CodeSearchTool tool = new CodeSearchTool(null, mapper);
        for (String name : READ_ONLY_AGENTS) {
            ToolContext context = context(agents.get(name));
            ToolExecutionException denied = assertThrows(ToolExecutionException.class,
                    () -> tool.execute(mapper.createObjectNode().put("action", "index"), context), name);
            assertTrue(denied.getMessage().startsWith("Permission denied: " + KEY),
                    name + ": " + denied.getMessage());
        }
    }

    @Test
    void codingAgentsKeepTheIndexAction() {
        for (String name : List.of("coder", "general")) {
            ToolContext context = context(agents.get(name));
            assertDoesNotThrow(() -> context.checkPermission(KEY, DESCRIPTION), name);
        }
    }

    @Test
    void aSupervisedChildInheritsTheParentsIndexLevel() {
        ToolContext allowedChild = supervisedChild(context(agents.get("coder")));
        assertEquals(PermissionLevel.ALLOW,
                allowedChild.getSubagentSupervision().ceiling().permissions().get(KEY));
        assertDoesNotThrow(() -> allowedChild.checkPermission(KEY, DESCRIPTION));

        ToolContext deniedChild = supervisedChild(context(agents.get("explore-quick")));
        ToolExecutionException denied = assertThrows(ToolExecutionException.class,
                () -> deniedChild.checkPermission(KEY, DESCRIPTION));
        assertTrue(denied.getMessage().startsWith("Inherited parent permission ceiling: " + KEY + " (DENY)"),
                denied.getMessage());
    }

    @Test
    void theReviewerRoleIsDeniedTheIndexAction() {
        ToolContext context = context(BuiltInRoles.REVIEWER.toAgentConfig());
        ToolExecutionException denied = assertThrows(ToolExecutionException.class,
                () -> context.checkPermission(KEY, DESCRIPTION));
        assertTrue(denied.getMessage().startsWith("Permission denied: " + KEY), denied.getMessage());
    }
}
