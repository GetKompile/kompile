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

package ai.kompile.app.web.controllers;

import ai.kompile.app.config.McpClientConfiguration.McpClientRegistry;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST Controller for managing MCP SSE client connections.
 *
 * Provides endpoints for:
 * - Connecting to external MCP servers via SSE
 * - Listing connected servers
 * - Listing tools from connected servers
 * - Calling tools on connected servers
 * - Disconnecting from servers
 */
@RestController
@RequestMapping("/api/mcp/client")
@ConditionalOnBean(McpClientRegistry.class)
public class McpClientController {

    private static final Logger logger = LoggerFactory.getLogger(McpClientController.class);

    private final McpClientRegistry clientRegistry;

    @Autowired
    public McpClientController(McpClientRegistry clientRegistry) {
        this.clientRegistry = clientRegistry;
    }

    /**
     * Connects to an external MCP server via SSE.
     */
    @PostMapping("/connect")
    public ResponseEntity<Map<String, Object>> connect(@RequestBody ConnectRequest request) {
        logger.info("Connecting to MCP server: {} at {}", request.name(), request.sseUrl());

        try {
            clientRegistry.connect(
                    request.name(),
                    request.sseUrl(),
                    request.messageUrl() != null ? request.messageUrl() : request.sseUrl().replace("/sse", "/mcp/message")
            );

            Map<String, Object> response = new HashMap<>();
            response.put("status", "connected");
            response.put("name", request.name());
            response.put("sseUrl", request.sseUrl());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Failed to connect to MCP server: {}", e.getMessage(), e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Disconnects from an MCP server.
     */
    @PostMapping("/disconnect/{name}")
    public ResponseEntity<Map<String, Object>> disconnect(@PathVariable String name) {
        logger.info("Disconnecting from MCP server: {}", name);

        clientRegistry.disconnect(name);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "disconnected");
        response.put("name", name);

        return ResponseEntity.ok(response);
    }

    /**
     * Lists all connected MCP servers.
     */
    @GetMapping("/connections")
    public ResponseEntity<Map<String, Object>> listConnections() {
        List<String> connections = clientRegistry.listConnections();

        List<Map<String, Object>> connectionInfos = connections.stream()
                .map(name -> {
                    var info = clientRegistry.getConnectionInfo(name);
                    Map<String, Object> connInfo = new HashMap<>();
                    connInfo.put("name", name);
                    connInfo.put("sseUrl", info.sseUrl());
                    connInfo.put("messageUrl", info.messageUrl());
                    return connInfo;
                })
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("connections", connectionInfos);
        response.put("count", connections.size());

        return ResponseEntity.ok(response);
    }

    /**
     * Lists tools available on a connected MCP server.
     */
    @GetMapping("/{name}/tools")
    public ResponseEntity<Map<String, Object>> listTools(@PathVariable String name) {
        try {
            McpSchema.ListToolsResult result = clientRegistry.listTools(name);

            List<Map<String, Object>> tools = result.tools().stream()
                    .map(tool -> {
                        Map<String, Object> toolInfo = new HashMap<>();
                        toolInfo.put("name", tool.name());
                        toolInfo.put("description", tool.description());
                        toolInfo.put("inputSchema", tool.inputSchema());
                        return toolInfo;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> response = new HashMap<>();
            response.put("serverName", name);
            response.put("tools", tools);
            response.put("count", tools.size());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Failed to list tools from MCP server {}: {}", name, e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Calls a tool on a connected MCP server.
     */
    @PostMapping("/{name}/tools/call")
    public ResponseEntity<Map<String, Object>> callTool(
            @PathVariable String name,
            @RequestBody CallToolRequest request) {

        logger.info("Calling tool '{}' on MCP server '{}'", request.toolName(), name);

        try {
            McpSchema.CallToolResult result = clientRegistry.callTool(
                    name,
                    request.toolName(),
                    request.arguments() != null ? request.arguments() : Map.of()
            );

            List<Map<String, Object>> content = result.content().stream()
                    .map(c -> {
                        Map<String, Object> contentItem = new HashMap<>();
                        if (c instanceof McpSchema.TextContent textContent) {
                            contentItem.put("type", "text");
                            contentItem.put("text", textContent.text());
                        } else if (c instanceof McpSchema.ImageContent imageContent) {
                            contentItem.put("type", "image");
                            contentItem.put("data", imageContent.data());
                            contentItem.put("mimeType", imageContent.mimeType());
                        } else if (c instanceof McpSchema.EmbeddedResource embeddedResource) {
                            contentItem.put("type", "resource");
                            contentItem.put("resource", embeddedResource.resource());
                        } else {
                            contentItem.put("type", "unknown");
                            contentItem.put("value", c.toString());
                        }
                        return contentItem;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> response = new HashMap<>();
            response.put("serverName", name);
            response.put("toolName", request.toolName());
            response.put("content", content);
            response.put("isError", result.isError() != null && result.isError());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Failed to call tool '{}' on MCP server {}: {}", request.toolName(), name, e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Lists resources available on a connected MCP server.
     */
    @GetMapping("/{name}/resources")
    public ResponseEntity<Map<String, Object>> listResources(@PathVariable String name) {
        try {
            var client = clientRegistry.getClient(name);
            if (client == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("status", "error");
                error.put("message", "MCP client not connected: " + name);
                return ResponseEntity.badRequest().body(error);
            }

            McpSchema.ListResourcesResult result = client.listResources();

            List<Map<String, Object>> resources = result.resources().stream()
                    .map(resource -> {
                        Map<String, Object> resourceInfo = new HashMap<>();
                        resourceInfo.put("uri", resource.uri());
                        resourceInfo.put("name", resource.name());
                        resourceInfo.put("description", resource.description());
                        resourceInfo.put("mimeType", resource.mimeType());
                        return resourceInfo;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> response = new HashMap<>();
            response.put("serverName", name);
            response.put("resources", resources);
            response.put("count", resources.size());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Failed to list resources from MCP server {}: {}", name, e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    /**
     * Lists prompts available on a connected MCP server.
     */
    @GetMapping("/{name}/prompts")
    public ResponseEntity<Map<String, Object>> listPrompts(@PathVariable String name) {
        try {
            var client = clientRegistry.getClient(name);
            if (client == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("status", "error");
                error.put("message", "MCP client not connected: " + name);
                return ResponseEntity.badRequest().body(error);
            }

            McpSchema.ListPromptsResult result = client.listPrompts();

            List<Map<String, Object>> prompts = result.prompts().stream()
                    .map(prompt -> {
                        Map<String, Object> promptInfo = new HashMap<>();
                        promptInfo.put("name", prompt.name());
                        promptInfo.put("description", prompt.description());
                        promptInfo.put("arguments", prompt.arguments());
                        return promptInfo;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> response = new HashMap<>();
            response.put("serverName", name);
            response.put("prompts", prompts);
            response.put("count", prompts.size());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Failed to list prompts from MCP server {}: {}", name, e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    // Request/Response DTOs

    public record ConnectRequest(
            String name,
            String sseUrl,
            String messageUrl
    ) {}

    public record CallToolRequest(
            String toolName,
            Map<String, Object> arguments
    ) {}
}
