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

package ai.kompile.app.mcp;

import ai.kompile.app.services.ServerPortService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * MCP Server Configuration for Kompile.
 *
 * This configuration creates the MCP server infrastructure supporting:
 * - SSE Transport (protocol version 2024-11-05)
 * - Streamable HTTP Transport (protocol version 2025-03-26)
 * - STDIO Transport (for CLI integration)
 *
 * Enable with: mcp.server.enabled=true
 * Transport: mcp.server.transport=sse (default) or stdio
 *
 * SSE Transport endpoints:
 * - GET /mcp/sse - Establish SSE connection
 * - POST /mcp/sse - Streamable HTTP (protocol 2025-03-26)
 * - POST /mcp/message?sessionId=xxx - Send JSON-RPC messages (protocol 2024-11-05)
 * - DELETE /mcp/sse - Terminate session (Streamable HTTP)
 * - GET /mcp/status - Health check
 */
@Configuration
@ConditionalOnProperty(name = "mcp.server.enabled", havingValue = "true", matchIfMissing = true)
public class McpServerConfig implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(McpServerConfig.class);

    @Autowired
    private McpServerProperties properties;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ServerPortService serverPortService;

    @Autowired(required = false)
    @Lazy
    private McpToolRegistry toolRegistry;

    private volatile McpSyncServer sseServer;

    /**
     * Create the MCP Server capabilities.
     */
    @Bean
    public ServerCapabilities mcpServerCapabilities() {
        return ServerCapabilities.builder()
                .tools(true)      // Enable tool support with list changes
                .resources(true, true)  // Enable resource support (subscribe, listChanged)
                .prompts(true)    // Enable prompt support with list changes
                .logging()        // Enable logging support
                .build();
    }

    // ========================
    // SSE Transport Configuration
    // ========================

    /**
     * Create the Spring MVC SSE transport provider.
     * Uses a Supplier for baseUrl to defer port resolution until after the server has started.
     */
    @Bean
    @ConditionalOnProperty(name = "mcp.server.transport", havingValue = "sse", matchIfMissing = true)
    public SpringMvcSseServerTransport springMvcSseServerTransport() {
        String messageEndpoint = properties.getSse().getMessageEndpoint();
        String sseEndpoint = properties.getSse().getEndpoint();

        log.info("Creating Spring MVC SSE Transport: sse={}, message={} (baseUrl will be resolved lazily)",
                sseEndpoint, messageEndpoint);

        // Use method reference to defer URL resolution until a client actually connects
        return new SpringMvcSseServerTransport(objectMapper, serverPortService::getBaseUrl, messageEndpoint, sseEndpoint);
    }

    /**
     * Create the MCP Sync Server for SSE transport.
     */
    @Bean
    @ConditionalOnProperty(name = "mcp.server.transport", havingValue = "sse", matchIfMissing = true)
    public McpSyncServer mcpSyncServerSse(SpringMvcSseServerTransport transport,
                                           ServerCapabilities capabilities) {
        log.info("Creating MCP Sync Server for SSE transport: {} v{}",
                properties.getName(), properties.getVersion());

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo(properties.getName(), properties.getVersion())
                .capabilities(capabilities)
                .build();

        // Register tools if registry is available
        if (toolRegistry != null) {
            toolRegistry.registerTools(server);
        }

        this.sseServer = server;
        return server;
    }

    // ========================
    // STDIO Transport Configuration
    // ========================

    /**
     * Create the STDIO transport provider for CLI-based MCP clients.
     */
    @Bean
    @ConditionalOnProperty(name = "mcp.server.transport", havingValue = "stdio")
    public StdioServerTransportProvider stdioTransportProvider() {
        return new StdioServerTransportProvider(objectMapper);
    }

    /**
     * Create the MCP Sync Server for STDIO transport.
     */
    @Bean
    @ConditionalOnProperty(name = "mcp.server.transport", havingValue = "stdio")
    public McpSyncServer mcpSyncServerStdio(StdioServerTransportProvider transportProvider,
                                             ServerCapabilities capabilities) {
        log.info("Creating MCP Sync Server for STDIO transport: {} v{}",
                properties.getName(), properties.getVersion());

        McpSyncServer server = McpServer.sync(transportProvider)
                .serverInfo(properties.getName(), properties.getVersion())
                .capabilities(capabilities)
                .build();

        // Register tools if registry is available
        if (toolRegistry != null) {
            toolRegistry.registerTools(server);
        }

        return server;
    }

    /**
     * Called when the application is ready.
     * Logs the MCP server endpoints.
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!"sse".equals(properties.getTransport())) {
            if ("stdio".equals(properties.getTransport())) {
                System.err.println("[MCP] Kompile MCP Server ready with STDIO transport");
                System.err.println("[MCP] Server: " + properties.getName() + " v" + properties.getVersion());
            }
            return;
        }

        String baseUrl = serverPortService.getBaseUrl();
        String sseEndpoint = properties.getSse().getEndpoint();
        String messageEndpoint = properties.getSse().getMessageEndpoint();

        log.info("[MCP] Kompile MCP Server ready");
        log.info("[MCP] Server: {} v{}", properties.getName(), properties.getVersion());
        log.info("[MCP] SSE Endpoint (GET): {}{}", baseUrl, sseEndpoint);
        log.info("[MCP] Streamable HTTP (POST): {}{}", baseUrl, sseEndpoint);
        log.info("[MCP] Message Endpoint: {}{}?sessionId=<id>", baseUrl, messageEndpoint);
        log.info("[MCP] Status Endpoint: {}/mcp/status", baseUrl);
        log.info("[MCP] Transport protocols supported:");
        log.info("[MCP]   - SSE Transport (2024-11-05): GET {} + POST {}", sseEndpoint, messageEndpoint);
        log.info("[MCP]   - Streamable HTTP (2025-03-26): POST {} with Mcp-Session-Id header", sseEndpoint);

        if (toolRegistry != null) {
            log.info("[MCP] Registered {} tools", toolRegistry.getToolCount());
        }
    }
}
