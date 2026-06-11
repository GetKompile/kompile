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

package ai.kompile.app.config;

import ai.kompile.app.services.mcp.optimization.CompressingToolCallbackProvider;
import ai.kompile.app.services.mcp.optimization.ToolResponseCompressorRegistry;
import ai.kompile.app.tools.*;
import ai.kompile.core.mcp.optimization.McpOptimizationConfig;
import ai.kompile.core.mcp.optimization.McpOptimizationConfig.MetaToolMode;
import ai.kompile.core.mcp.optimization.McpOptimizationConfigProvider;
import ai.kompile.tool.filesystem.FilesystemToolImpl;
import ai.kompile.tool.rag.RagToolImpl;
import ai.kompile.tool.tablesearch.TableSearchToolImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Configuration for Spring AI MCP SSE Server.
 *
 * This configuration provides two MCP server implementations:
 *
 * 1. Spring AI's built-in MCP server (via ToolCallbackProvider):
 *    - Uses Spring AI's automatic tool discovery
 *    - Enabled via: spring.ai.mcp.server.enabled=true
 *
 * 2. Custom SpringMvcSseServerTransport (for advanced features):
 *    - Supports both SSE (2024-11-05) and Streamable HTTP (2025-03-26) protocols
 *    - Provides session management and health checks
 *    - Enabled via: mcp.server.enabled=true
 *
 * Endpoints:
 * - Spring AI: /sse, /mcp/message (configurable)
 * - Custom: /mcp/sse, /mcp/message, /mcp/status
 */
@Configuration
@ConditionalOnProperty(name = "spring.ai.mcp.server.enabled", havingValue = "true", matchIfMissing = true)
public class McpSseServerConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(McpSseServerConfiguration.class);

    // Core tools from kompile-tool modules
    @Autowired(required = false)
    private RagToolImpl ragTool;

    @Autowired(required = false)
    private FilesystemToolImpl filesystemTool;

    @Autowired(required = false)
    private TableSearchToolImpl tableSearchTool;

    // Application tools from kompile-app-main
    @Autowired(required = false)
    private ModelDebugTool modelDebugTool;

    @Autowired(required = false)
    private ChatSessionTool chatSessionTool;

    @Autowired(required = false)
    private ActionLogTool actionLogTool;

    @Autowired(required = false)
    private ApplicationConfigTool applicationConfigTool;

    @Autowired(required = false)
    private IndexOperationsTool indexOperationsTool;

    @Autowired(required = false)
    private DocumentManagementTool documentManagementTool;

    @Autowired(required = false)
    private SystemDiagnosticsTool systemDiagnosticsTool;

    @Autowired(required = false)
    private ModelManagementTool modelManagementTool;

    @Autowired(required = false)
    private AgentDelegationTool agentDelegationTool;

    @Autowired(required = false)
    private AgentTaskTool agentTaskTool;

    @Autowired(required = false)
    private DiffTrackerTool diffTrackerTool;

    @Autowired(required = false)
    private DiffIndexTool diffIndexTool;

    @Autowired(required = false)
    private ToolResponseCompressorRegistry compressorRegistry;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired(required = false)
    private DynamicToolsetsMetaTool dynamicToolsetsMetaTool;

    @Autowired(required = false)
    private ResultFetchTool resultFetchTool;

    @Autowired(required = false)
    private McpOptimizationConfigProvider optimizationConfigProvider;

    /**
     * Creates a ToolCallbackProvider that exposes all discovered tool beans
     * to the MCP server. Spring AI will automatically register these tools
     * and make them available to MCP clients.
     *
     * <p>When {@link ToolResponseCompressorRegistry} is available the provider
     * is wrapped in {@link CompressingToolCallbackProvider} so every tool result
     * goes through the configured compressor chain before reaching the client.
     */
    @Bean
    public ToolCallbackProvider kompileToolCallbackProvider() {
        List<Object> toolObjects = new ArrayList<>();

        // Add core tools from kompile-tool modules
        addToolIfAvailable(toolObjects, ragTool, "RAG");
        addToolIfAvailable(toolObjects, filesystemTool, "Filesystem");
        addToolIfAvailable(toolObjects, tableSearchTool, "Table Search");

        // Add application tools from kompile-app-main
        addToolIfAvailable(toolObjects, modelDebugTool, "Model Debug");
        addToolIfAvailable(toolObjects, chatSessionTool, "Chat Session");
        addToolIfAvailable(toolObjects, actionLogTool, "Action Log");
        addToolIfAvailable(toolObjects, applicationConfigTool, "Application Config");
        addToolIfAvailable(toolObjects, indexOperationsTool, "Index Operations");
        addToolIfAvailable(toolObjects, documentManagementTool, "Document Management");
        addToolIfAvailable(toolObjects, systemDiagnosticsTool, "System Diagnostics");
        addToolIfAvailable(toolObjects, modelManagementTool, "Model Management");
        addToolIfAvailable(toolObjects, agentDelegationTool, "Agent Delegation");
        addToolIfAvailable(toolObjects, agentTaskTool, "Agent Task");
        addToolIfAvailable(toolObjects, diffTrackerTool, "Diff Tracker");
        addToolIfAvailable(toolObjects, diffIndexTool, "Diff Index");

        // Meta-tools are always registered; the mode filter below decides which
        // tool *names* the MCP client actually sees.
        addToolIfAvailable(toolObjects, dynamicToolsetsMetaTool, "Dynamic Toolsets");
        addToolIfAvailable(toolObjects, resultFetchTool, "Result Fetch");

        logger.info("Created MCP SSE tool callback provider with {} tool objects", toolObjects.size());

        ToolCallbackProvider base = MethodToolCallbackProvider.builder()
                .toolObjects(toolObjects.toArray())
                .build();

        Set<String> allowedToolNames = resolveAllowedToolNames(toolObjects);
        if (allowedToolNames != null) {
            logger.info("MCP SSE meta-tool mode active: exposing {} tool names", allowedToolNames.size());
        }

        if (compressorRegistry != null) {
            logger.info("Wrapping MCP SSE tool callbacks with response compression");
            return new CompressingToolCallbackProvider(base, compressorRegistry, objectMapper, allowedToolNames);
        }
        if (allowedToolNames != null) {
            // Still need to apply filtering even when compression isn't wired.
            return new CompressingToolCallbackProvider(base, null, objectMapper, allowedToolNames);
        }
        return base;
    }

    /**
     * Returns {@code null} for {@code DIRECT} mode (no filter). For DYNAMIC
     * exposes only meta-tools + {@code alwaysExposedTools}; for HYBRID the
     * same plus a small built-in whitelist.
     */
    private Set<String> resolveAllowedToolNames(List<Object> toolObjects) {
        McpOptimizationConfig cfg = optimizationConfigProvider != null
                ? optimizationConfigProvider.getConfiguration()
                : null;
        MetaToolMode mode = cfg != null && cfg.getMetaToolMode() != null
                ? cfg.getMetaToolMode()
                : MetaToolMode.DIRECT;
        if (mode == MetaToolMode.DIRECT) {
            return null;
        }

        Set<String> allowed = new HashSet<>();
        // Meta-tool names are always visible in DYNAMIC / HYBRID modes.
        allowed.add("search_tools");
        allowed.add("describe_tools");
        allowed.add("execute_tool");
        allowed.add("fetch_result");

        if (cfg.getAlwaysExposedTools() != null) {
            for (String name : cfg.getAlwaysExposedTools()) {
                if (name != null && !name.isBlank()) {
                    allowed.add(name);
                }
            }
        }

        if (mode == MetaToolMode.HYBRID) {
            // Common high-traffic tools stay direct to avoid the discovery hop
            // for the hot path.
            allowed.add("rag_query");
            allowed.add("read_file");
            allowed.add("list_files");
        }

        // Also respect per-tool overrides that force expose-in-dynamic.
        if (cfg.getToolOverrides() != null) {
            cfg.getToolOverrides().forEach((name, override) -> {
                if (override != null && Boolean.TRUE.equals(override.getExposeInDynamicMode())) {
                    allowed.add(name);
                } else if (override != null && Boolean.FALSE.equals(override.getExposeInDynamicMode())) {
                    allowed.remove(name);
                }
            });
        }

        // Sanity: if a listed tool doesn't exist among toolObjects we still
        // leave it in the set (Spring AI just won't match it); no harm done.
        return allowed;
    }

    @SuppressWarnings("unused")
    private Set<String> extractToolNames(List<Object> toolObjects) {
        Set<String> names = new HashSet<>();
        for (Object bean : toolObjects) {
            for (Method m : bean.getClass().getDeclaredMethods()) {
                Tool t = m.getAnnotation(Tool.class);
                if (t != null) {
                    names.add(t.name().isBlank() ? m.getName() : t.name());
                }
            }
        }
        return names;
    }

    // Note: SpringMvcSseServerTransport is now created in McpServerConfig.java
    // to avoid bean definition conflicts and provide proper MCP server integration.

    private void addToolIfAvailable(List<Object> toolObjects, Object tool, String toolName) {
        if (tool != null) {
            toolObjects.add(tool);
            logger.info("Registered {} tool for MCP SSE server", toolName);
        }
    }
}
