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

import ai.kompile.app.services.mcp.McpActionLogService;
import ai.kompile.app.services.mcp.ToolDefinitionService;
import ai.kompile.app.tools.*;
import ai.kompile.core.mcp.EnhancedToolDefinition;
import ai.kompile.core.mcp.ToolChangeEvent;
import ai.kompile.tool.filesystem.FilesystemToolImpl;
import ai.kompile.tool.rag.RagToolImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Registry for all MCP Tools exposed by Kompile.
 *
 * This registry bridges Spring AI's @Tool annotated methods to the MCP SDK's
 * tool registration system. It scans all tool beans for @Tool annotated methods
 * and creates corresponding MCP tool specifications.
 *
 * Tools are organized by domain:
 * - RAG operations (query, search)
 * - Document management (upload, delete, list)
 * - Index operations (status, rebuild)
 * - Model debugging (inspect SameDiff models)
 * - System diagnostics (memory, threads)
 * - Chat session management
 * - Action logging
 * - Application configuration
 * - Filesystem operations
 * - Model management
 */
@Component
public class McpToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistry.class);

    private final ObjectMapper objectMapper;
    private final McpActionLogService actionLogService;
    private final ToolDefinitionService toolDefinitionService;
    private final List<Object> toolBeans = new ArrayList<>();
    private int toolCount = 0;

    // Cache of enhanced tool definitions for agent discovery
    private final Map<String, EnhancedToolDefinition> toolDefinitions = new LinkedHashMap<>();

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

    @Autowired
    public McpToolRegistry(ObjectMapper objectMapper,
                           McpActionLogService actionLogService,
                           @Autowired(required = false) ToolDefinitionService toolDefinitionService) {
        this.objectMapper = objectMapper;
        this.actionLogService = actionLogService;
        this.toolDefinitionService = toolDefinitionService;
    }

    /**
     * Register all tools with the MCP server.
     */
    public void registerTools(McpSyncServer server) {
        // Collect all tool beans
        collectToolBeans();

        // Scan each bean for @Tool annotated methods
        List<McpServerFeatures.SyncToolSpecification> allTools = new ArrayList<>();

        for (Object bean : toolBeans) {
            List<McpServerFeatures.SyncToolSpecification> specs = scanBeanForTools(bean);
            allTools.addAll(specs);
        }

        // Register each tool with the server
        for (McpServerFeatures.SyncToolSpecification toolSpec : allTools) {
            try {
                server.addTool(toolSpec);
                log.debug("Registered MCP tool: {}", toolSpec.tool().name());
            } catch (Exception e) {
                log.error("Failed to register tool {}: {}", toolSpec.tool().name(), e.getMessage());
            }
        }

        toolCount = allTools.size();
        log.info("Registered {} MCP tools with the server", toolCount);
    }

    /**
     * Get the count of registered tools.
     */
    public int getToolCount() {
        return toolCount;
    }

    /**
     * Handles tool change events to keep the registry in sync.
     * This is called when tools are created, updated, deleted, enabled, or disabled.
     */
    @EventListener
    public void onToolChange(ToolChangeEvent event) {
        log.info("Tool change event received: {} for tool '{}'", event.getChangeType(), event.getToolName());

        switch (event.getChangeType()) {
            case CREATED:
            case UPDATED:
                if (event.getToolDefinition() != null) {
                    toolDefinitions.put(event.getToolName(), event.getToolDefinition());
                    log.debug("Updated tool definition cache for: {}", event.getToolName());
                }
                break;
            case DELETED:
                toolDefinitions.remove(event.getToolName());
                log.debug("Removed tool definition from cache: {}", event.getToolName());
                break;
            case ENABLED:
            case DISABLED:
                if (event.getToolDefinition() != null) {
                    toolDefinitions.put(event.getToolName(), event.getToolDefinition());
                    log.debug("Updated enabled state for tool: {}", event.getToolName());
                }
                break;
            case REFRESHED:
                // Refresh the entire cache from the service
                if (toolDefinitionService != null) {
                    toolDefinitions.clear();
                    for (EnhancedToolDefinition def : toolDefinitionService.getAllTools()) {
                        toolDefinitions.put(def.getName(), def);
                    }
                    log.debug("Refreshed all tool definitions from service");
                }
                break;
        }
    }

    /**
     * Returns enhanced tool definitions for all registered tools.
     * These include detailed metadata for agent discoverability.
     */
    public List<EnhancedToolDefinition> getEnhancedToolDefinitions() {
        if (toolDefinitionService != null) {
            return toolDefinitionService.getEnabledTools();
        }
        return new ArrayList<>(toolDefinitions.values());
    }

    /**
     * Gets enhanced definition for a specific tool.
     */
    public Optional<EnhancedToolDefinition> getToolDefinition(String toolName) {
        if (toolDefinitionService != null) {
            return toolDefinitionService.getToolByName(toolName);
        }
        return Optional.ofNullable(toolDefinitions.get(toolName));
    }

    /**
     * Generates an agent-friendly prompt describing all available tools.
     */
    public String getAgentToolsPrompt() {
        if (toolDefinitionService != null) {
            return toolDefinitionService.generateAgentToolsPrompt();
        }

        // Fallback to basic prompt generation
        StringBuilder prompt = new StringBuilder();
        prompt.append("# Available Tools\n\n");

        for (EnhancedToolDefinition tool : toolDefinitions.values()) {
            prompt.append("## ").append(tool.getName()).append("\n");
            prompt.append(tool.getDescription()).append("\n\n");
        }

        return prompt.toString();
    }

    /**
     * Returns tools grouped by category for structured discovery.
     */
    public Map<String, List<EnhancedToolDefinition>> getToolsByCategory() {
        if (toolDefinitionService != null) {
            return toolDefinitionService.getToolsByCategories();
        }

        return toolDefinitions.values().stream()
                .collect(Collectors.groupingBy(
                        t -> t.getCategory() != null ? t.getCategory() : "other"
                ));
    }

    private void collectToolBeans() {
        addBeanIfAvailable(ragTool, "RAG");
        addBeanIfAvailable(filesystemTool, "Filesystem");
        addBeanIfAvailable(modelDebugTool, "Model Debug");
        addBeanIfAvailable(chatSessionTool, "Chat Session");
        addBeanIfAvailable(actionLogTool, "Action Log");
        addBeanIfAvailable(applicationConfigTool, "Application Config");
        addBeanIfAvailable(indexOperationsTool, "Index Operations");
        addBeanIfAvailable(documentManagementTool, "Document Management");
        addBeanIfAvailable(systemDiagnosticsTool, "System Diagnostics");
        addBeanIfAvailable(modelManagementTool, "Model Management");
    }

    private void addBeanIfAvailable(Object bean, String name) {
        if (bean != null) {
            toolBeans.add(bean);
            log.debug("Found {} tool bean", name);
        }
    }

    /**
     * Scan a bean for @Tool annotated methods and create MCP tool specifications.
     */
    private List<McpServerFeatures.SyncToolSpecification> scanBeanForTools(Object bean) {
        List<McpServerFeatures.SyncToolSpecification> specs = new ArrayList<>();
        Class<?> clazz = bean.getClass();

        for (Method method : clazz.getDeclaredMethods()) {
            org.springframework.ai.tool.annotation.Tool toolAnnotation =
                    method.getAnnotation(org.springframework.ai.tool.annotation.Tool.class);

            if (toolAnnotation != null) {
                try {
                    McpServerFeatures.SyncToolSpecification spec = createToolSpec(bean, method, toolAnnotation);
                    specs.add(spec);
                    log.debug("Created MCP tool spec for method: {}.{}", clazz.getSimpleName(), method.getName());
                } catch (Exception e) {
                    log.error("Failed to create tool spec for {}.{}: {}",
                            clazz.getSimpleName(), method.getName(), e.getMessage());
                }
            }
        }

        return specs;
    }

    /**
     * Create an MCP tool specification from a Spring AI @Tool annotated method.
     */
    private McpServerFeatures.SyncToolSpecification createToolSpec(
            Object bean, Method method, org.springframework.ai.tool.annotation.Tool toolAnnotation) {

        String toolName = toolAnnotation.name().isEmpty() ? method.getName() : toolAnnotation.name();
        String description = toolAnnotation.description();

        // Build JSON schema for parameters
        String inputSchema = buildInputSchema(method);

        Tool tool = new Tool(toolName, description, inputSchema);

        return new McpServerFeatures.SyncToolSpecification(
                tool,
                (exchange, args) -> {
                    // Log action start
                    McpActionLogService.McpAction logEntry = actionLogService.logActionStart(toolName, args);

                    try {
                        // Invoke the method
                        Object result = invokeToolMethod(bean, method, args);

                        // Log success
                        String resultStr = formatResult(result);
                        actionLogService.logActionSuccess(logEntry.getId(), resultStr, null);

                        return successResult(result);
                    } catch (Exception e) {
                        // Log failure
                        actionLogService.logActionFailure(logEntry.getId(), e.getMessage());
                        log.error("Tool {} failed: {}", toolName, e.getMessage(), e);
                        return errorResult(e.getMessage());
                    }
                }
        );
    }

    /**
     * Build JSON schema for method parameters.
     */
    private String buildInputSchema(Method method) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();
        List<String> required = new ArrayList<>();

        Parameter[] parameters = method.getParameters();
        for (Parameter param : parameters) {
            Class<?> paramType = param.getType();

            // Check if parameter is a record (input DTO)
            if (paramType.isRecord()) {
                // Extract properties from record components
                RecordComponent[] components = paramType.getRecordComponents();
                for (RecordComponent component : components) {
                    ObjectNode propSchema = createPropertySchema(component.getType(), component.getName());

                    // Check for @ToolParam annotation for description
                    ToolParam toolParam = component.getAnnotation(ToolParam.class);
                    if (toolParam != null) {
                        propSchema.put("description", toolParam.description());
                        if (toolParam.required()) {
                            required.add(component.getName());
                        }
                    }

                    properties.set(component.getName(), propSchema);
                }
            } else {
                // Simple parameter
                String paramName = param.getName();
                ObjectNode propSchema = createPropertySchema(paramType, paramName);

                ToolParam toolParam = param.getAnnotation(ToolParam.class);
                if (toolParam != null) {
                    propSchema.put("description", toolParam.description());
                    if (toolParam.required()) {
                        required.add(paramName);
                    }
                }

                properties.set(paramName, propSchema);
            }
        }

        schema.set("properties", properties);

        if (!required.isEmpty()) {
            schema.set("required", objectMapper.valueToTree(required));
        }

        try {
            return objectMapper.writeValueAsString(schema);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private ObjectNode createPropertySchema(Class<?> type, String name) {
        ObjectNode schema = objectMapper.createObjectNode();

        if (type == String.class) {
            schema.put("type", "string");
        } else if (type == Integer.class || type == int.class) {
            schema.put("type", "integer");
        } else if (type == Long.class || type == long.class) {
            schema.put("type", "integer");
        } else if (type == Double.class || type == double.class) {
            schema.put("type", "number");
        } else if (type == Float.class || type == float.class) {
            schema.put("type", "number");
        } else if (type == Boolean.class || type == boolean.class) {
            schema.put("type", "boolean");
        } else if (type.isArray() || List.class.isAssignableFrom(type)) {
            schema.put("type", "array");
        } else if (Map.class.isAssignableFrom(type)) {
            schema.put("type", "object");
        } else {
            schema.put("type", "object");
        }

        return schema;
    }

    /**
     * Invoke the tool method with the given arguments.
     */
    private Object invokeToolMethod(Object bean, Method method, Map<String, Object> args) throws Exception {
        method.setAccessible(true);

        Parameter[] parameters = method.getParameters();
        Object[] invokeArgs = new Object[parameters.length];

        for (int i = 0; i < parameters.length; i++) {
            Parameter param = parameters[i];
            Class<?> paramType = param.getType();

            if (paramType.isRecord()) {
                // Create record instance from args
                invokeArgs[i] = createRecordInstance(paramType, args);
            } else {
                // Get simple parameter value
                String paramName = param.getName();
                Object value = args.get(paramName);
                invokeArgs[i] = convertValue(value, paramType);
            }
        }

        return method.invoke(bean, invokeArgs);
    }

    /**
     * Create a record instance from a map of arguments.
     */
    private Object createRecordInstance(Class<?> recordClass, Map<String, Object> args) throws Exception {
        RecordComponent[] components = recordClass.getRecordComponents();
        Class<?>[] paramTypes = new Class<?>[components.length];
        Object[] paramValues = new Object[components.length];

        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            paramTypes[i] = component.getType();
            Object value = args.get(component.getName());
            paramValues[i] = convertValue(value, component.getType());
        }

        return recordClass.getDeclaredConstructor(paramTypes).newInstance(paramValues);
    }

    /**
     * Convert a value to the target type.
     */
    private Object convertValue(Object value, Class<?> targetType) {
        if (value == null) {
            return null;
        }

        if (targetType.isInstance(value)) {
            return value;
        }

        // Convert using ObjectMapper for complex types
        try {
            return objectMapper.convertValue(value, targetType);
        } catch (Exception e) {
            // Handle primitive conversions
            if (targetType == Integer.class || targetType == int.class) {
                return ((Number) value).intValue();
            } else if (targetType == Long.class || targetType == long.class) {
                return ((Number) value).longValue();
            } else if (targetType == Double.class || targetType == double.class) {
                return ((Number) value).doubleValue();
            } else if (targetType == Float.class || targetType == float.class) {
                return ((Number) value).floatValue();
            } else if (targetType == Boolean.class || targetType == boolean.class) {
                return Boolean.valueOf(value.toString());
            } else if (targetType == String.class) {
                return value.toString();
            }
            throw e;
        }
    }

    private String formatResult(Object result) {
        if (result == null) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(result);
            // Truncate long results
            if (json.length() > 500) {
                return json.substring(0, 497) + "...";
            }
            return json;
        } catch (JsonProcessingException e) {
            return result.toString();
        }
    }

    /**
     * Helper to create a successful tool result with JSON content.
     */
    public CallToolResult successResult(Object data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            return new CallToolResult(List.of(new TextContent(json)), false);
        } catch (JsonProcessingException e) {
            return errorResult("Failed to serialize result: " + e.getMessage());
        }
    }

    /**
     * Helper to create an error tool result.
     */
    public static CallToolResult errorResult(String message) {
        return new CallToolResult(List.of(new TextContent("Error: " + message)), true);
    }

    /**
     * Helper to extract a required string parameter.
     */
    public static String getRequiredString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required parameter: " + key);
        }
        return value.toString();
    }

    /**
     * Helper to extract a required Long parameter.
     */
    public static Long getRequiredLong(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required parameter: " + key);
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.parseLong(value.toString());
    }

    /**
     * Helper to extract an optional Long parameter.
     */
    public static Long getOptionalLong(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.parseLong(value.toString());
    }

    /**
     * Helper to extract an optional String parameter.
     */
    public static String getOptionalString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value != null ? value.toString() : null;
    }

    /**
     * Helper to extract an optional Integer parameter.
     */
    public static Integer getOptionalInt(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return Integer.parseInt(value.toString());
    }

    /**
     * Helper to extract a required Integer parameter.
     */
    public static Integer getRequiredInt(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required parameter: " + key);
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return Integer.parseInt(value.toString());
    }

    /**
     * Helper to extract a boolean parameter with default value.
     */
    public static boolean getBoolean(Map<String, Object> args, String key, boolean defaultValue) {
        Object value = args.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString());
    }

    /**
     * Helper to extract an optional boolean parameter.
     */
    public static Boolean getOptionalBoolean(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString());
    }
}
