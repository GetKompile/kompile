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

import ai.kompile.app.tools.*;
import ai.kompile.tool.filesystem.FilesystemToolImpl;
import ai.kompile.tool.rag.RagToolImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

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

    /**
     * Creates a ToolCallbackProvider that exposes all discovered tool beans
     * to the MCP server. Spring AI will automatically register these tools
     * and make them available to MCP clients.
     */
    @Bean
    public ToolCallbackProvider kompileToolCallbackProvider() {
        List<Object> toolObjects = new ArrayList<>();

        // Add core tools from kompile-tool modules
        addToolIfAvailable(toolObjects, ragTool, "RAG");
        addToolIfAvailable(toolObjects, filesystemTool, "Filesystem");

        // Add application tools from kompile-app-main
        addToolIfAvailable(toolObjects, modelDebugTool, "Model Debug");
        addToolIfAvailable(toolObjects, chatSessionTool, "Chat Session");
        addToolIfAvailable(toolObjects, actionLogTool, "Action Log");
        addToolIfAvailable(toolObjects, applicationConfigTool, "Application Config");
        addToolIfAvailable(toolObjects, indexOperationsTool, "Index Operations");
        addToolIfAvailable(toolObjects, documentManagementTool, "Document Management");
        addToolIfAvailable(toolObjects, systemDiagnosticsTool, "System Diagnostics");
        addToolIfAvailable(toolObjects, modelManagementTool, "Model Management");

        logger.info("Created MCP SSE tool callback provider with {} tool objects", toolObjects.size());

        return MethodToolCallbackProvider.builder()
                .toolObjects(toolObjects.toArray())
                .build();
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
