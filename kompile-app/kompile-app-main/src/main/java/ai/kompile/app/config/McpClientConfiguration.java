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

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Configuration for MCP SSE Client connections to external MCP servers.
 *
 * This configuration enables Kompile to act as an MCP client, connecting
 * to external MCP servers (e.g., Claude Desktop, other MCP-compatible services)
 * via the SSE (Server-Sent Events) transport.
 *
 * Configure external MCP servers via application.properties:
 * - spring.ai.mcp.client.enabled=true
 * - spring.ai.mcp.client.servers[0].name=my-server
 * - spring.ai.mcp.client.servers[0].url=http://localhost:8081
 */
@Configuration
@ConditionalOnProperty(name = "spring.ai.mcp.client.enabled", havingValue = "true", matchIfMissing = false)
public class McpClientConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(McpClientConfiguration.class);

    @Value("${spring.ai.mcp.client.timeout:30000}")
    private long clientTimeout;

    /**
     * Registry to hold active MCP client connections.
     * Allows runtime management of connections to multiple MCP servers.
     */
    @Bean
    public McpClientRegistry mcpClientRegistry() {
        return new McpClientRegistry();
    }

    /**
     * Registry for managing MCP client connections.
     */
    public static class McpClientRegistry {

        private final Map<String, McpClientHolder> clients = new ConcurrentHashMap<>();
        private final Logger logger = LoggerFactory.getLogger(McpClientRegistry.class);

        /**
         * Creates and registers a new MCP client connection to an SSE server.
         *
         * @param name Unique identifier for this connection
         * @param sseUrl The SSE endpoint URL of the MCP server
         * @param messageUrl The message endpoint URL for sending requests (unused with HttpClientSseClientTransport)
         * @return The created MCP sync client
         */
        public McpSyncClient connect(String name, String sseUrl, String messageUrl) {
            if (clients.containsKey(name)) {
                logger.warn("MCP client '{}' already exists, closing existing connection", name);
                disconnect(name);
            }

            logger.info("Connecting to MCP server '{}' at {}", name, sseUrl);

            // Create the SSE transport using simple constructor
            HttpClientSseClientTransport transport = new HttpClientSseClientTransport(sseUrl);

            McpSyncClient client = McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(30))
                    .build();

            try {
                // Initialize the connection
                McpSchema.InitializeResult initResult = client.initialize();
                logger.info("Connected to MCP server '{}': protocol={}, server={} v{}",
                        name,
                        initResult.protocolVersion(),
                        initResult.serverInfo().name(),
                        initResult.serverInfo().version());

                // Store the client
                clients.put(name, new McpClientHolder(client, transport, sseUrl, messageUrl));

                return client;
            } catch (Exception e) {
                logger.error("Failed to connect to MCP server '{}': {}", name, e.getMessage());
                try {
                    client.close();
                } catch (Exception closeEx) {
                    logger.warn("Error closing failed client connection", closeEx);
                }
                throw new RuntimeException("Failed to connect to MCP server: " + name, e);
            }
        }

        /**
         * Disconnects and removes an MCP client connection.
         *
         * @param name The name of the client to disconnect
         */
        public void disconnect(String name) {
            McpClientHolder holder = clients.remove(name);
            if (holder != null) {
                try {
                    holder.client().close();
                    logger.info("Disconnected from MCP server '{}'", name);
                } catch (Exception e) {
                    logger.warn("Error disconnecting from MCP server '{}': {}", name, e.getMessage());
                }
            }
        }

        /**
         * Gets a connected MCP client by name.
         *
         * @param name The name of the client
         * @return The MCP client, or null if not connected
         */
        public McpSyncClient getClient(String name) {
            McpClientHolder holder = clients.get(name);
            return holder != null ? holder.client() : null;
        }

        /**
         * Lists all connected MCP clients.
         *
         * @return List of connected client names
         */
        public List<String> listConnections() {
            return List.copyOf(clients.keySet());
        }

        /**
         * Gets information about a connected client.
         *
         * @param name The name of the client
         * @return Connection info, or null if not connected
         */
        public McpClientHolder getConnectionInfo(String name) {
            return clients.get(name);
        }

        /**
         * Lists available tools from a connected MCP server.
         *
         * @param name The name of the connected client
         * @return List of available tools
         */
        public McpSchema.ListToolsResult listTools(String name) {
            McpSyncClient client = getClient(name);
            if (client == null) {
                throw new IllegalArgumentException("MCP client not connected: " + name);
            }
            return client.listTools();
        }

        /**
         * Calls a tool on a connected MCP server.
         *
         * @param name The name of the connected client
         * @param toolName The name of the tool to call
         * @param arguments The tool arguments
         * @return The tool result
         */
        public McpSchema.CallToolResult callTool(String name, String toolName, Map<String, Object> arguments) {
            McpSyncClient client = getClient(name);
            if (client == null) {
                throw new IllegalArgumentException("MCP client not connected: " + name);
            }
            return client.callTool(new McpSchema.CallToolRequest(toolName, arguments));
        }

        /**
         * Disconnects all clients on shutdown.
         */
        public void disconnectAll() {
            for (String name : List.copyOf(clients.keySet())) {
                disconnect(name);
            }
        }

        /**
         * Holder for MCP client connection details.
         */
        public record McpClientHolder(
                McpSyncClient client,
                HttpClientSseClientTransport transport,
                String sseUrl,
                String messageUrl
        ) {}
    }
}
