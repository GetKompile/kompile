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

import ai.kompile.app.services.mcp.McpActionLogService;
import ai.kompile.app.services.mcp.McpActionLogService.ActionType;
import ai.kompile.core.mcp.bridge.RestMcpBridgeConfig;
import ai.kompile.core.mcp.bridge.RestMcpBridgeConfig.EndpointMapping;
import ai.kompile.core.mcp.bridge.RestMcpBridgeConfig.McpToolMapping;
import ai.kompile.core.mcp.bridge.RestMcpBridgeManager;
import ai.kompile.core.mcp.server.McpServerConfig;
import ai.kompile.core.mcp.server.McpServerManager;
import ai.kompile.core.mcp.server.McpToolConfig;
import ai.kompile.tool.filesystem.FilesystemToolImpl;
import ai.kompile.tool.rag.RagToolImpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/mcp/tools")
public class McpToolController {

    private static final Logger logger = LoggerFactory.getLogger(McpToolController.class);
    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;
    private final McpActionLogService actionLogService;

    private final RagToolImpl ragToolImpl;
    private final FilesystemToolImpl filesystemToolImpl;
    private final McpServerManager mcpServerManager;
    private final RestMcpBridgeManager restMcpBridgeManager;

    // Tool categories for logging
    private static final String CATEGORY_RAG = "rag";
    private static final String CATEGORY_FILESYSTEM = "filesystem";
    private static final String CATEGORY_SYSTEM = "system";
    private static final String CATEGORY_MCP_SERVER = "mcp_server";
    private static final String CATEGORY_REST_BRIDGE = "rest_bridge";

    @Autowired
    public McpToolController(ApplicationContext applicationContext,
                             ObjectMapper objectMapper,
                             McpActionLogService actionLogService,
                             RagToolImpl ragToolImpl,
                             FilesystemToolImpl filesystemToolImpl,
                             @Autowired(required = false) McpServerManager mcpServerManager,
                             @Autowired(required = false) RestMcpBridgeManager restMcpBridgeManager) {
        this.applicationContext = applicationContext;
        this.objectMapper = objectMapper;
        this.actionLogService = actionLogService;
        this.ragToolImpl = ragToolImpl;
        this.filesystemToolImpl = filesystemToolImpl;
        this.mcpServerManager = mcpServerManager;
        this.restMcpBridgeManager = restMcpBridgeManager;
        logger.info("McpToolController initialized with RagToolImpl: {}, FilesystemToolImpl: {}, McpServerManager: {}, RestMcpBridgeManager: {}",
                ragToolImpl.getClass().getSimpleName(),
                filesystemToolImpl.getClass().getSimpleName(),
                mcpServerManager != null ? "available" : "not available",
                restMcpBridgeManager != null ? "available" : "not available");
    }

    public record FrontendToolCallRequest(String toolName, Map<String, Object> arguments) {}

    @GetMapping("/list")
    public ResponseEntity<?> listAvailableTools() {
        List<Map<String, Object>> toolInfos = new ArrayList<>();

        // 1. Scan ALL beans in the application context for @Tool annotations (built-in tools)
        String[] beanNames = applicationContext.getBeanDefinitionNames();

        for (String beanName : beanNames) {
            try {
                Object bean = applicationContext.getBean(beanName);
                if (bean == null) continue;

                for (Method method : bean.getClass().getDeclaredMethods()) {
                    if (method.isAnnotationPresent(Tool.class)) {
                        Tool toolAnnotation = method.getAnnotation(Tool.class);

                        // Ensure no duplicate tool names
                        if (toolInfos.stream().noneMatch(info -> toolAnnotation.name().equals(info.get("name")))) {
                            Map<String, Object> toolInfo = new LinkedHashMap<>();
                            toolInfo.put("name", toolAnnotation.name());
                            toolInfo.put("description", toolAnnotation.description());
                            toolInfo.put("beanName", beanName);
                            toolInfo.put("className", bean.getClass().getSimpleName());
                            toolInfo.put("source", "built_in");
                            toolInfo.put("category", determineCategory(toolAnnotation.name()));

                            // Build input schema from method parameters
                            Object inputSchema = buildInputSchema(method);
                            if (inputSchema != null) {
                                toolInfo.put("inputSchema", inputSchema);
                            }

                            toolInfos.add(toolInfo);
                            logger.debug("Discovered built-in tool: {} from bean: {}", toolAnnotation.name(), beanName);
                        }
                    }
                }
            } catch (Exception e) {
                // Skip beans that can't be introspected (proxies, etc.)
                logger.trace("Could not introspect bean {} for tools: {}", beanName, e.getMessage());
            }
        }

        // 2. Add tools from configured MCP servers
        if (mcpServerManager != null) {
            try {
                List<McpServerConfig> servers = mcpServerManager.listServers();
                for (McpServerConfig server : servers) {
                    if (server.getTools() != null) {
                        for (McpToolConfig tool : server.getTools()) {
                            if (!tool.isEnabled()) continue;

                            // Skip if tool with same name already exists
                            if (toolInfos.stream().anyMatch(info -> tool.getName().equals(info.get("name")))) {
                                logger.debug("Skipping duplicate tool from MCP server: {}", tool.getName());
                                continue;
                            }

                            Map<String, Object> toolInfo = new LinkedHashMap<>();
                            toolInfo.put("name", tool.getName());
                            toolInfo.put("description", tool.getDescription() != null ? tool.getDescription() : "No description");
                            toolInfo.put("source", "mcp_server");
                            toolInfo.put("serverId", server.getId());
                            toolInfo.put("serverName", server.getName());
                            toolInfo.put("serverStatus", server.getStatus().name());
                            toolInfo.put("category", CATEGORY_MCP_SERVER);
                            toolInfo.put("implementationType", tool.getImplementationType().name());

                            // Use inputSchema from the tool config if available
                            if (tool.getInputSchema() != null) {
                                toolInfo.put("inputSchema", tool.getInputSchema());
                            } else if (tool.getParameters() != null && !tool.getParameters().isEmpty()) {
                                // Build schema from parameters
                                toolInfo.put("inputSchema", buildSchemaFromMcpToolParams(tool.getParameters()));
                            }

                            toolInfos.add(toolInfo);
                            logger.debug("Discovered MCP server tool: {} from server: {}", tool.getName(), server.getName());
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to load tools from MCP servers: {}", e.getMessage());
            }
        }

        // 3. Add tools from REST-MCP bridges
        if (restMcpBridgeManager != null) {
            try {
                List<RestMcpBridgeConfig> bridges = restMcpBridgeManager.listBridges();
                for (RestMcpBridgeConfig bridge : bridges) {
                    if (!bridge.isEnabled()) continue;
                    if (bridge.getMappings() == null) continue;

                    for (EndpointMapping mapping : bridge.getMappings()) {
                        if (!mapping.isEnabled()) continue;
                        McpToolMapping mcpTool = mapping.getMcpTool();
                        if (mcpTool == null || mcpTool.getName() == null) continue;

                        // Skip if tool with same name already exists
                        if (toolInfos.stream().anyMatch(info -> mcpTool.getName().equals(info.get("name")))) {
                            logger.debug("Skipping duplicate tool from REST bridge: {}", mcpTool.getName());
                            continue;
                        }

                        Map<String, Object> toolInfo = new LinkedHashMap<>();
                        toolInfo.put("name", mcpTool.getName());
                        toolInfo.put("description", mcpTool.getDescription() != null ? mcpTool.getDescription() : "No description");
                        toolInfo.put("source", "rest_bridge");
                        toolInfo.put("bridgeId", bridge.getId());
                        toolInfo.put("bridgeName", bridge.getName());
                        toolInfo.put("bridgeStatus", bridge.getStatus().name());
                        toolInfo.put("category", mcpTool.getCategory() != null ? mcpTool.getCategory() : CATEGORY_REST_BRIDGE);

                        // Include REST endpoint info
                        if (mapping.getRestEndpoint() != null) {
                            Map<String, Object> restInfo = new LinkedHashMap<>();
                            restInfo.put("method", mapping.getRestEndpoint().getMethod());
                            restInfo.put("path", mapping.getRestEndpoint().getPath());
                            toolInfo.put("restEndpoint", restInfo);
                        }

                        // Use inputSchema from the mapping if available
                        if (mcpTool.getInputSchema() != null) {
                            toolInfo.put("inputSchema", mcpTool.getInputSchema());
                        } else if (mapping.getRestEndpoint() != null && mapping.getRestEndpoint().getRequestBodySchema() != null) {
                            toolInfo.put("inputSchema", mapping.getRestEndpoint().getRequestBodySchema());
                        }

                        toolInfos.add(toolInfo);
                        logger.debug("Discovered REST bridge tool: {} from bridge: {}", mcpTool.getName(), bridge.getName());
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to load tools from REST-MCP bridges: {}", e.getMessage());
            }
        }

        // Sort tools by name for consistent ordering
        toolInfos.sort((a, b) -> ((String) a.get("name")).compareTo((String) b.get("name")));

        logger.info("Tool discovery found {} tools (built-in + MCP servers + REST bridges)", toolInfos.size());

        if (toolInfos.isEmpty()) {
            logger.warn("No tools found via any source (built-in, MCP servers, or REST bridges)");
        }

        return ResponseEntity.ok(toolInfos);
    }

    /**
     * Builds a JSON Schema from MCP tool parameters.
     */
    private Map<String, Object> buildSchemaFromMcpToolParams(List<McpToolConfig.ParameterConfig> params) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (McpToolConfig.ParameterConfig param : params) {
            Map<String, Object> propSchema = new LinkedHashMap<>();
            propSchema.put("type", param.getType() != null ? param.getType() : "string");
            if (param.getDescription() != null) {
                propSchema.put("description", param.getDescription());
            }
            if (param.getDefaultValue() != null) {
                propSchema.put("default", param.getDefaultValue());
            }
            if (param.getEnumValues() != null && !param.getEnumValues().isEmpty()) {
                propSchema.put("enum", param.getEnumValues());
            }
            properties.put(param.getName(), propSchema);

            if (param.isRequired()) {
                required.add(param.getName());
            }
        }

        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }

        return schema;
    }

    /**
     * Builds an input schema from a method's parameters.
     */
    private Map<String, Object> buildInputSchema(Method method) {
        Class<?>[] paramTypes = method.getParameterTypes();
        if (paramTypes.length == 0) {
            return null;
        }

        if (paramTypes.length == 1) {
            // Single parameter - likely a request record/class
            return buildSchemaFromClass(paramTypes[0]);
        }

        // Multiple parameters - build schema from each
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        java.lang.reflect.Parameter[] params = method.getParameters();
        List<String> required = new ArrayList<>();

        for (java.lang.reflect.Parameter param : params) {
            Map<String, Object> propSchema = new LinkedHashMap<>();
            propSchema.put("type", getJsonType(param.getType()));
            properties.put(param.getName(), propSchema);
            required.add(param.getName());
        }

        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }

        return schema;
    }

    /**
     * Builds a JSON schema from a class (typically a record).
     */
    private Map<String, Object> buildSchemaFromClass(Class<?> clazz) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        // Check for record components first (Java 16+)
        if (clazz.isRecord()) {
            for (java.lang.reflect.RecordComponent component : clazz.getRecordComponents()) {
                Map<String, Object> propSchema = new LinkedHashMap<>();
                propSchema.put("type", getJsonType(component.getType()));
                properties.put(component.getName(), propSchema);
                // For records, all components are effectively required unless nullable
                if (!isNullableType(component.getType())) {
                    required.add(component.getName());
                }
            }
        } else {
            // Fall back to getter methods for regular classes
            for (Method getter : clazz.getDeclaredMethods()) {
                if (getter.getName().startsWith("get") && getter.getParameterCount() == 0
                        && !getter.getName().equals("getClass")) {
                    String propName = getter.getName().substring(3);
                    propName = Character.toLowerCase(propName.charAt(0)) + propName.substring(1);

                    Map<String, Object> propSchema = new LinkedHashMap<>();
                    propSchema.put("type", getJsonType(getter.getReturnType()));
                    properties.put(propName, propSchema);
                }
            }
        }

        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }

        return schema;
    }

    /**
     * Maps Java types to JSON Schema types.
     */
    private String getJsonType(Class<?> type) {
        if (type == String.class) return "string";
        if (type == Integer.class || type == int.class) return "integer";
        if (type == Long.class || type == long.class) return "integer";
        if (type == Double.class || type == double.class) return "number";
        if (type == Float.class || type == float.class) return "number";
        if (type == Boolean.class || type == boolean.class) return "boolean";
        if (type.isArray() || java.util.Collection.class.isAssignableFrom(type)) return "array";
        if (java.util.Map.class.isAssignableFrom(type)) return "object";
        return "object";
    }

    /**
     * Checks if a type is nullable (wrapper types, objects).
     */
    private boolean isNullableType(Class<?> type) {
        return !type.isPrimitive();
    }

    @PostMapping("/invoke-direct")
    public ResponseEntity<?> invokeToolDirectly(@RequestBody FrontendToolCallRequest request) {
        logger.info("McpToolController received direct tool invocation request: {}", request);
        if (request.toolName() == null || request.toolName().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "toolName cannot be empty."));
        }

        try {
            Object result = null;
            String category = CATEGORY_SYSTEM;
            ActionType actionType = ActionType.READ;
            boolean undoable = false;

            switch (request.toolName()) {
                case "rag_query":
                    if (request.arguments() == null) { /* ... error ... */ }
                    RagToolImpl.RagQueryInput ragInput = objectMapper.convertValue(request.arguments(), RagToolImpl.RagQueryInput.class);
                    result = ragToolImpl.executeRagQuery(ragInput);
                    category = CATEGORY_RAG;
                    actionType = ActionType.READ;
                    break;
                case "list_files":
                    if (request.arguments() == null) { /* ... error ... */ }
                    FilesystemToolImpl.ListFilesInput listInput = objectMapper.convertValue(request.arguments(), FilesystemToolImpl.ListFilesInput.class);
                    result = filesystemToolImpl.listFiles(listInput);
                    category = CATEGORY_FILESYSTEM;
                    actionType = ActionType.READ;
                    break;
                case "read_file":
                    if (request.arguments() == null) { /* ... error ... */ }
                    FilesystemToolImpl.ReadFileInput readInput = objectMapper.convertValue(request.arguments(), FilesystemToolImpl.ReadFileInput.class);
                    result = filesystemToolImpl.readFile(readInput);
                    category = CATEGORY_FILESYSTEM;
                    actionType = ActionType.READ;
                    break;
                case "write_file":
                    if (request.arguments() == null) {
                        return ResponseEntity.badRequest().body(Map.of("error", "arguments required for write_file"));
                    }
                    FilesystemToolImpl.WriteFileInput writeInput = objectMapper.convertValue(request.arguments(), FilesystemToolImpl.WriteFileInput.class);
                    result = filesystemToolImpl.writeFile(writeInput);
                    category = CATEGORY_FILESYSTEM;
                    actionType = ActionType.WRITE;
                    undoable = true;
                    break;
                case "delete_file":
                    if (request.arguments() == null) {
                        return ResponseEntity.badRequest().body(Map.of("error", "arguments required for delete_file"));
                    }
                    FilesystemToolImpl.DeleteFileInput deleteInput = objectMapper.convertValue(request.arguments(), FilesystemToolImpl.DeleteFileInput.class);
                    result = filesystemToolImpl.deleteFile(deleteInput);
                    category = CATEGORY_FILESYSTEM;
                    actionType = ActionType.DELETE;
                    undoable = true;
                    break;
                case "create_directory":
                    if (request.arguments() == null) {
                        return ResponseEntity.badRequest().body(Map.of("error", "arguments required for create_directory"));
                    }
                    FilesystemToolImpl.CreateDirectoryInput createDirInput = objectMapper.convertValue(request.arguments(), FilesystemToolImpl.CreateDirectoryInput.class);
                    result = filesystemToolImpl.createDirectory(createDirInput);
                    category = CATEGORY_FILESYSTEM;
                    actionType = ActionType.WRITE;
                    undoable = true;
                    break;
                default:
                    // Try to invoke via reflection for discovered tools
                    result = invokeDiscoveredTool(request.toolName(), request.arguments());
                    if (result == null) {
                        logger.warn("Tool not found or direct invocation not supported for: {}", request.toolName());
                        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                .body(Map.of("error", "Tool not found or direct invocation not supported: " + request.toolName()));
                    }
                    category = determineCategory(request.toolName());
                    actionType = determineActionType(request.toolName());
                    break;
            }

            // Log the action
            actionLogService.logAction(
                    request.toolName(),
                    category,
                    request.arguments(),
                    result,
                    actionType,
                    undoable,
                    null,  // sessionId - could be extracted from headers
                    null   // userId - could be extracted from authentication
            );

            return ResponseEntity.ok(Map.of("toolName", request.toolName(), "result", result));
        } catch (Exception e) {
            logger.error("Error invoking tool '{}' directly: {}", request.toolName(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to invoke tool " + request.toolName() + ": " + e.getMessage()));
        }
    }

    /**
     * Attempts to invoke a discovered tool via reflection.
     */
    private Object invokeDiscoveredTool(String toolName, Map<String, Object> arguments) {
        try {
            String[] beanNames = applicationContext.getBeanDefinitionNames();
            for (String beanName : beanNames) {
                Object bean = applicationContext.getBean(beanName);
                for (Method method : bean.getClass().getDeclaredMethods()) {
                    if (method.isAnnotationPresent(Tool.class)) {
                        Tool toolAnnotation = method.getAnnotation(Tool.class);
                        if (toolAnnotation.name().equals(toolName)) {
                            // Found the tool, invoke it
                            Class<?>[] paramTypes = method.getParameterTypes();
                            if (paramTypes.length == 1) {
                                Object input = objectMapper.convertValue(arguments, paramTypes[0]);
                                return method.invoke(bean, input);
                            } else if (paramTypes.length == 0) {
                                return method.invoke(bean);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error invoking discovered tool '{}': {}", toolName, e.getMessage(), e);
        }
        return null;
    }

    /**
     * Determines the category based on tool name.
     */
    private String determineCategory(String toolName) {
        if (toolName.contains("rag") || toolName.contains("query") || toolName.contains("document")) {
            return CATEGORY_RAG;
        } else if (toolName.contains("file") || toolName.contains("directory")) {
            return CATEGORY_FILESYSTEM;
        } else if (toolName.contains("model") || toolName.contains("embedding")) {
            return "model";
        } else if (toolName.contains("index")) {
            return "indexing";
        } else if (toolName.contains("config") || toolName.contains("setting")) {
            return "config";
        } else if (toolName.contains("action") || toolName.contains("undo")) {
            return "action_log";
        }
        return CATEGORY_SYSTEM;
    }

    /**
     * Determines the action type based on tool name.
     */
    private ActionType determineActionType(String toolName) {
        if (toolName.startsWith("get_") || toolName.startsWith("list_") || toolName.startsWith("read_") ||
                toolName.contains("_status") || toolName.contains("_info") || toolName.contains("_stats")) {
            return ActionType.READ;
        } else if (toolName.startsWith("delete_") || toolName.startsWith("remove_") || toolName.startsWith("clear_")) {
            return ActionType.DELETE;
        } else if (toolName.startsWith("create_") || toolName.startsWith("write_") || toolName.startsWith("add_") ||
                toolName.startsWith("update_") || toolName.startsWith("set_")) {
            return ActionType.WRITE;
        } else if (toolName.contains("config") || toolName.contains("setting")) {
            return ActionType.CONFIG;
        } else if (toolName.startsWith("run_") || toolName.startsWith("execute_") || toolName.contains("undo")) {
            return ActionType.EXECUTE;
        }
        return ActionType.READ; // Default to READ for safety
    }
}