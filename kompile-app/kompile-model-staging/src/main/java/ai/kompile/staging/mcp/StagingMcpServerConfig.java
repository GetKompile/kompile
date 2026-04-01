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

package ai.kompile.staging.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
@ConditionalOnClass(name = "ai.kompile.staging.catalog.CatalogService")
@ConditionalOnProperty(name = "kompile.staging.app.enabled", havingValue = "true")
public class StagingMcpServerConfig implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(StagingMcpServerConfig.class);

    @Autowired
    private StagingMcpServerProperties properties;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StagingServerPortService serverPortService;

    @Autowired(required = false)
    @Lazy
    private StagingMcpToolRegistry toolRegistry;

    @Bean
    public ServerCapabilities mcpServerCapabilities() {
        return ServerCapabilities.builder()
                .tools(true)
                .logging()
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "mcp.server.transport", havingValue = "sse", matchIfMissing = true)
    public StagingSseServerTransport stagingSseServerTransport() {
        String messageEndpoint = properties.getSse().getMessageEndpoint();
        String sseEndpoint = properties.getSse().getEndpoint();

        log.info("Creating Staging MCP SSE Transport: sse={}, message={}", sseEndpoint, messageEndpoint);
        return new StagingSseServerTransport(objectMapper, serverPortService::getBaseUrl, messageEndpoint, sseEndpoint);
    }

    @Bean
    @ConditionalOnProperty(name = "mcp.server.transport", havingValue = "sse", matchIfMissing = true)
    public McpSyncServer mcpSyncServer(StagingSseServerTransport transport, ServerCapabilities capabilities) {
        log.info("Creating MCP Sync Server: {} v{}", properties.getName(), properties.getVersion());

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo(properties.getName(), properties.getVersion())
                .capabilities(capabilities)
                .build();

        if (toolRegistry != null) {
            toolRegistry.registerTools(server);
        }

        return server;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        String baseUrl = serverPortService.getBaseUrl();
        String sseEndpoint = properties.getSse().getEndpoint();
        String messageEndpoint = properties.getSse().getMessageEndpoint();

        log.info("[MCP] Kompile Model Staging MCP Server ready");
        log.info("[MCP] Server: {} v{}", properties.getName(), properties.getVersion());
        log.info("[MCP] SSE Endpoint (GET): {}{}", baseUrl, sseEndpoint);
        log.info("[MCP] Message Endpoint: {}{}?sessionId=<id>", baseUrl, messageEndpoint);

        if (toolRegistry != null) {
            log.info("[MCP] Registered {} tools", toolRegistry.getToolCount());
        }
    }
}
