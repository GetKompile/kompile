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

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.agent.DirectSubagentRunner;
import ai.kompile.cli.main.chat.agent.ServerSubagentRunner;
import ai.kompile.cli.main.chat.agent.SubagentRunner;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.roles.RoleManager;
import ai.kompile.cli.main.chat.tui.SidePanelManager;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Factory for creating a fully-wired ToolRegistry with all built-in tools.
 */
public class ToolRegistryFactory {

    /**
     * Create a ToolRegistry for server mode (kompile-app backend).
     */
    public static ToolRegistry create(ObjectMapper objectMapper, String baseUrl,
                                       AgentRegistry agentRegistry,
                                       PermissionService permissionService,
                                       TerminalRenderer renderer,
                                       BackgroundProcessManager processManager) {
        return create(objectMapper, baseUrl, agentRegistry, permissionService,
                renderer, processManager, null, null);
    }

    /**
     * Create a ToolRegistry with all built-in tools registered.
     * In local mode (chatConfig != null, baseUrl empty), uses DirectSubagentRunner
     * for subagent execution. In server mode, uses ServerSubagentRunner.
     *
     * @param objectMapper    Jackson ObjectMapper for JSON
     * @param baseUrl         kompile-app base URL for subagent communication
     * @param agentRegistry   agent registry for subagent lookup
     * @param permissionService permission service for tool access control
     * @param renderer        terminal renderer for output formatting
     * @param processManager  background process manager for process tracking
     * @param chatConfig      LLM config for direct mode (null for server mode)
     * @param roleManager     role manager for role-based agent configuration (null to skip role tool)
     * @return fully populated ToolRegistry
     */
    public static ToolRegistry create(ObjectMapper objectMapper, String baseUrl,
                                       AgentRegistry agentRegistry,
                                       PermissionService permissionService,
                                       TerminalRenderer renderer,
                                       BackgroundProcessManager processManager,
                                       ChatConfig chatConfig,
                                       RoleManager roleManager) {
        ToolRegistry registry = new ToolRegistry(objectMapper);

        // File I/O tools
        registry.register(new ReadTool());
        registry.register(new WriteTool());
        registry.register(new EditTool());
        registry.register(new PatchTool());

        // Search tools
        registry.register(new GrepTool());
        registry.register(new GlobTool());
        registry.register(new ListTool());

        // Execution tools
        registry.register(new BashTool());

        // Network tools
        registry.register(new WebFetchTool());
        registry.register(new WebSearchTool());

        // Workflow and TUI tools
        registry.register(new TodoWriteTool());
        registry.register(new TodoReadTool());
        registry.register(new SidePanelTool(new SidePanelManager()));

        // Process management tools
        if (processManager != null) {
            registry.register(new ProcessManagementTool(processManager));
        }

        // Knowledge & memory tools
        registry.register(new TranscriptSearchTool());
        registry.register(new ConversationImportTool());
        registry.register(new RagSearchTool(baseUrl, objectMapper));
        registry.register(new GraphRagSearchTool(baseUrl, objectMapper));
        registry.register(new MemoryTool());

        // Delegation tools (subagent spawning)
        // Use DirectSubagentRunner in local mode, ServerSubagentRunner for server mode
        SubagentRunner subagentRunner;
        boolean isLocalMode = chatConfig != null && (baseUrl == null || baseUrl.isEmpty());
        if (isLocalMode) {
            subagentRunner = new DirectSubagentRunner(
                    chatConfig, objectMapper, registry, permissionService, renderer);
        } else {
            subagentRunner = new ServerSubagentRunner(
                    baseUrl, registry, permissionService, objectMapper, renderer);
        }
        registry.register(new TaskTool(agentRegistry, subagentRunner));
        registry.setSubagentRunner(subagentRunner);

        // Evaluation tool
        registry.register(new EvalTool());

        // Role management tool (exposed as MCP tool for subagent coordination)
        if (roleManager != null) {
            registry.register(new RoleManagerTool(roleManager, objectMapper));
        }

        return registry;
    }
}
