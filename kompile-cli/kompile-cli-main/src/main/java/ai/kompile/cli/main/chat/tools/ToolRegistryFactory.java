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

import ai.kompile.cli.common.routing.KompileService;
import ai.kompile.cli.common.routing.KompileServiceEndpoints;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.agent.DirectSubagentRunner;
import ai.kompile.cli.main.chat.agent.ServerSubagentRunner;
import ai.kompile.cli.main.chat.agent.SubagentRunner;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.roles.RoleManager;
import ai.kompile.cli.main.chat.tools.grounding.AskGraphAssertTool;
import ai.kompile.cli.main.chat.tools.grounding.AskGraphClaimTool;
import ai.kompile.cli.main.chat.tools.grounding.AskGraphExplainTool;
import ai.kompile.cli.main.chat.tools.grounding.AskGraphRetractTool;
import ai.kompile.cli.main.chat.tools.grounding.AskGraphFusedTool;
import ai.kompile.cli.main.chat.tools.grounding.AskGraphMebnTool;
import ai.kompile.cli.main.chat.tools.grounding.AskGraphQueryTool;
import ai.kompile.cli.main.chat.tools.grounding.AskGraphSubscribeTool;
import ai.kompile.cli.main.chat.tools.grounding.AskGraphSynthesizeTool;
import ai.kompile.cli.main.chat.tools.grounding.AskGraphVerifyTool;
import ai.kompile.cli.main.chat.tools.grounding.CrawlControlTool;
import ai.kompile.cli.main.chat.tools.grounding.CrawlDiscoveryTool;
import ai.kompile.cli.main.chat.tools.grounding.CrawlDocumentsTool;
import ai.kompile.cli.main.chat.tools.grounding.ModelRuntimeTool;
import ai.kompile.cli.main.chat.tools.grounding.PipelineTool;
import ai.kompile.cli.main.chat.tools.grounding.VlmModelDefinitionTool;
import ai.kompile.cli.main.chat.tools.grounding.CrawlResultTool;
import ai.kompile.cli.main.chat.tools.grounding.CrawlSourceTool;
import ai.kompile.cli.main.chat.tools.grounding.GraphExportTool;
import ai.kompile.cli.main.chat.tools.grounding.GraphImportTool;
import ai.kompile.cli.main.chat.tools.grounding.GraphReasonTool;
import ai.kompile.cli.main.chat.tools.grounding.GraphReasoningQueryTool;
import ai.kompile.cli.main.chat.tui.SidePanelManager;
import ai.kompile.cli.main.graph.GraphServiceRouting;
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
        return create(objectMapper, baseUrl, agentRegistry, permissionService, renderer,
                processManager, chatConfig, roleManager, null);
    }

    /**
     * Create a registry with an optional crawl-manager routing override.
     *
     * <p>The override is intentionally independent from {@code baseUrl}: an offline
     * direct LLM worker has no chat-server URL, but still needs to address a local
     * crawl/index service.</p>
     */
    public static ToolRegistry create(ObjectMapper objectMapper, String baseUrl,
                                       AgentRegistry agentRegistry,
                                       PermissionService permissionService,
                                       TerminalRenderer renderer,
                                       BackgroundProcessManager processManager,
                                       ChatConfig chatConfig,
                                       RoleManager roleManager,
                                       String crawlBaseUrlOverride) {
        ToolRegistry registry = new ToolRegistry(objectMapper);
        GraphServiceRouting.Resolution graphResolution = GraphServiceRouting.resolve(null);
        String graphBaseUrl = resolveGraphBaseUrl(baseUrl, graphResolution);
        String crawlRoutingUrl = crawlBaseUrlOverride == null || crawlBaseUrlOverride.isBlank()
                ? baseUrl : crawlBaseUrlOverride;
        String crawlBaseUrl = crawlRoutingUrl == null || crawlRoutingUrl.isBlank()
                ? null
                : KompileServiceEndpoints.resolve(KompileService.CRAWL, crawlRoutingUrl).baseUrl();

        // File I/O tools
        registry.register(new ReadTool());
        registry.register(new ReadBatchTool());
        registry.register(new WriteTool());
        registry.register(new EditTool());
        registry.register(new EditBatchTool());
        registry.register(new PatchTool());
        registry.register(new EditPatchTool());

        // Search tools
        registry.register(new GrepTool());
        registry.register(new GrepBatchTool());
        registry.register(new GlobTool());
        registry.register(new ListTool());

        // Language-server code intelligence
        registry.register(new LspTool());

        // Execution tools
        registry.register(new BashTool());

        // Network tools
        registry.register(new WebFetchTool());
        registry.register(new WebSearchTool());
        registry.register(new BrowserTool());

        // Workflow and TUI tools
        registry.register(new TodoWriteTool());
        registry.register(new TodoReadTool());
        registry.register(new SidePanelTool(new SidePanelManager()));
        registry.register(new ActivateToolsTool(registry.getDynamicToolManager()));

        // Process management tools
        if (processManager != null) {
            registry.register(new ProcessManagementTool(processManager));
        }

        // Knowledge & memory tools
        registry.register(new TranscriptSearchTool());
        registry.register(new ConversationImportTool());
        registry.register(new KnowledgeSearchCliTool(baseUrl, objectMapper));
        registry.register(new KnowledgeStatusCliTool(baseUrl, objectMapper));
        registry.register(new DiffIndexTool(baseUrl, objectMapper));
        registry.register(new RagSearchTool(baseUrl, objectMapper));
        registry.register(new GraphRagSearchTool(baseUrl, objectMapper));
        registry.register(new GraphAggregateTool(baseUrl, objectMapper));
        registry.register(new GraphForecastTool(baseUrl, objectMapper));
        registry.register(new GraphCentralityTool(baseUrl, objectMapper));
        registry.register(new KnowledgeGraphTool(baseUrl, objectMapper));
        registry.register(new AskGraphQueryTool(baseUrl, objectMapper));
        registry.register(new AskGraphVerifyTool(baseUrl, objectMapper));
        registry.register(new AskGraphAssertTool(baseUrl, objectMapper));
        registry.register(new AskGraphRetractTool(baseUrl, objectMapper));
        registry.register(new AskGraphMebnTool(baseUrl, objectMapper));
        registry.register(new AskGraphExplainTool(baseUrl, objectMapper));
        registry.register(new AskGraphFusedTool(baseUrl, objectMapper));
        registry.register(new AskGraphSynthesizeTool(baseUrl, objectMapper));
        registry.register(new AskGraphSubscribeTool(baseUrl, objectMapper));
        registry.register(new GraphReasonTool(baseUrl, objectMapper));
        registry.register(new GraphImportTool(graphBaseUrl, objectMapper));
        registry.register(new GraphExportTool(graphBaseUrl, objectMapper));
        registry.register(new CrawlSourceTool(crawlBaseUrl, objectMapper));
        registry.register(new CrawlDocumentsTool(crawlBaseUrl, objectMapper));
        registry.register(new CrawlDiscoveryTool(crawlBaseUrl, objectMapper));
        registry.register(new ModelRuntimeTool(objectMapper));
        registry.register(new VlmModelDefinitionTool(objectMapper));
        registry.register(new PipelineTool(objectMapper));
        registry.register(new CrawlControlTool(crawlBaseUrl, objectMapper));
        registry.register(new CrawlResultTool(crawlBaseUrl, objectMapper));
        registry.register(new ProcessMiningCliTool(baseUrl, objectMapper));
        registry.register(new AskGraphClaimTool(baseUrl, objectMapper));
        registry.register(new GraphReasoningQueryTool(graphBaseUrl, objectMapper));
        registry.register(new GraphBayesTool(baseUrl, objectMapper));
        registry.register(new GraphEmbeddingsTool(baseUrl, objectMapper));
        registry.register(new GraphSimulateTool(baseUrl, objectMapper));
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

    /**
     * Standard local chat owns a project-local graph archive. Default and discovered managed
     * routes are intentionally ignored in that mode because registrations can outlive their
     * process. A deliberate property, environment, or explicit route still overrides local.
     */
    static String resolveGraphBaseUrl(
            String appBaseUrl, GraphServiceRouting.Resolution graphResolution) {
        boolean localChat = appBaseUrl == null || appBaseUrl.isBlank();
        boolean explicitlyConfigured = switch (graphResolution.source()) {
            case EXPLICIT, SYSTEM_PROPERTY, ENVIRONMENT -> true;
            case MANAGED_INSTANCE, DEFAULT -> false;
        };
        if (localChat && !explicitlyConfigured) {
            return null;
        }
        return graphResolution.baseUrl();
    }
}
