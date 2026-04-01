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

package ai.kompile.app.services.mcp;

import ai.kompile.app.services.ServerPortService;
import ai.kompile.core.mcp.EnhancedToolDefinition;
import ai.kompile.core.mcp.bridge.RestMcpBridgeConfig;
import ai.kompile.core.mcp.bridge.RestMcpBridgeConfig.*;
import ai.kompile.core.mcp.server.McpServerConfig;
import ai.kompile.core.mcp.server.McpToolConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for discovering built-in MCP tools from Spring AI @Tool annotations
 * and creating bridge mappings for them.
 */
@Service
public class BuiltInToolDiscoveryService {

    private static final Logger logger = LoggerFactory.getLogger(BuiltInToolDiscoveryService.class);

    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;
    private final ServerPortService serverPortService;
    private ToolDefinitionService toolDefinitionService;

    // Cache of discovered tools
    private final List<DiscoveredTool> discoveredTools = new ArrayList<>();

    // Tool category mappings for better organization
    private static final Map<String, String> CATEGORY_KEYWORDS = new LinkedHashMap<>();
    static {
        CATEGORY_KEYWORDS.put("rag", "rag,query,document,retrieve,search");
        CATEGORY_KEYWORDS.put("filesystem", "file,directory,path,read,write,delete");
        CATEGORY_KEYWORDS.put("indexing", "index,rebuild,stats");
        CATEGORY_KEYWORDS.put("model", "model,embedding,samediff,nd4j");
        CATEGORY_KEYWORDS.put("system", "memory,cpu,thread,jvm,gc,resource");
        CATEGORY_KEYWORDS.put("config", "config,setting,property,bean");
        CATEGORY_KEYWORDS.put("action_log", "action,undo,history,log");
        CATEGORY_KEYWORDS.put("chat", "chat,session,conversation,message");
        CATEGORY_KEYWORDS.put("factsheet", "fact_sheet,fact sheet,factsheet");
        CATEGORY_KEYWORDS.put("evaluation", "eval,evaluation,test_case,pass_rate,failing");
        CATEGORY_KEYWORDS.put("ingestion", "ingest,youtube,text_source");
        CATEGORY_KEYWORDS.put("pipeline", "pipeline,execute_pipeline,async_result");
        CATEGORY_KEYWORDS.put("settings", "filter_chain,filter,guardrail,transformer");
        CATEGORY_KEYWORDS.put("chunk", "chunk,deduplic,duplicate");
        CATEGORY_KEYWORDS.put("prompt", "prompt,template,render");
        CATEGORY_KEYWORDS.put("timing", "timing,profil,chrome_trace,op_breakdown,histogram");
        CATEGORY_KEYWORDS.put("benchmark", "benchmark,samediff_benchmark");
        CATEGORY_KEYWORDS.put("backup", "backup,restore");
        CATEGORY_KEYWORDS.put("orchestrator", "orchestrator,workflow,state_machine");
        CATEGORY_KEYWORDS.put("experiment", "experiment,dataset_csv,dataset_jsonl");
        CATEGORY_KEYWORDS.put("crossindex", "cross_index,sync_job,index_sync");
        CATEGORY_KEYWORDS.put("archive", "archive,karch");
        CATEGORY_KEYWORDS.put("vlm", "vlm,vision_language,vlm_pipeline,vlm_stage");
        CATEGORY_KEYWORDS.put("kvcache", "kvcache,kv_cache,checkpoint");
        CATEGORY_KEYWORDS.put("device", "device_routing,device_config");
        CATEGORY_KEYWORDS.put("subprocess", "subprocess,heap_size,subprocess_config");
        CATEGORY_KEYWORDS.put("delegation", "delegate,delegation,inter_agent");
    }

    @Autowired
    public BuiltInToolDiscoveryService(ApplicationContext applicationContext, ObjectMapper objectMapper,
                                        ServerPortService serverPortService) {
        this.applicationContext = applicationContext;
        this.objectMapper = objectMapper;
        this.serverPortService = serverPortService;
    }

    @Autowired(required = false)
    public void setToolDefinitionService(ToolDefinitionService toolDefinitionService) {
        this.toolDefinitionService = toolDefinitionService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        discoverBuiltInTools();
    }

    /**
     * Discovers all @Tool annotated methods in the application context.
     */
    public void discoverBuiltInTools() {
        discoveredTools.clear();

        // Get all beans and scan for @Tool annotations
        String[] beanNames = applicationContext.getBeanDefinitionNames();

        for (String beanName : beanNames) {
            try {
                Object bean = applicationContext.getBean(beanName);
                Class<?> beanClass = bean.getClass();

                for (Method method : beanClass.getDeclaredMethods()) {
                    if (method.isAnnotationPresent(Tool.class)) {
                        Tool toolAnnotation = method.getAnnotation(Tool.class);

                        DiscoveredTool tool = new DiscoveredTool();
                        tool.setName(toolAnnotation.name());
                        tool.setDescription(toolAnnotation.description());
                        tool.setBeanName(beanName);
                        tool.setMethodName(method.getName());
                        tool.setBeanClass(beanClass.getName());
                        tool.setReturnType(method.getReturnType().getSimpleName());
                        tool.setInputSchema(buildInputSchemaFromMethod(method));
                        tool.setParameters(extractParametersFromMethod(method));

                        discoveredTools.add(tool);
                        logger.info("Discovered built-in tool: {} from {}.{}",
                                tool.getName(), beanClass.getSimpleName(), method.getName());
                    }
                }
            } catch (Exception e) {
                // Skip beans that can't be introspected
                logger.debug("Could not introspect bean {}: {}", beanName, e.getMessage());
            }
        }

        logger.info("Discovered {} built-in MCP tools", discoveredTools.size());
    }

    /**
     * Returns all discovered built-in tools.
     */
    public List<DiscoveredTool> getDiscoveredTools() {
        return new ArrayList<>(discoveredTools);
    }

    /**
     * Returns enhanced tool definitions from the ToolDefinitionService if available,
     * otherwise falls back to basic discovered tools converted to enhanced format.
     */
    public List<EnhancedToolDefinition> getEnhancedToolDefinitions() {
        if (toolDefinitionService != null) {
            return toolDefinitionService.getEnabledTools();
        }

        // Fallback: Convert discovered tools to enhanced format
        return discoveredTools.stream()
                .map(this::convertToEnhancedDefinition)
                .collect(Collectors.toList());
    }

    /**
     * Returns tools grouped by category.
     */
    public Map<String, List<DiscoveredTool>> getToolsByCategory() {
        return discoveredTools.stream()
                .collect(Collectors.groupingBy(tool -> inferCategory(tool.getName(), tool.getDescription())));
    }

    /**
     * Returns enhanced tools grouped by category.
     */
    public Map<String, List<EnhancedToolDefinition>> getEnhancedToolsByCategory() {
        if (toolDefinitionService != null) {
            return toolDefinitionService.getToolsByCategories();
        }

        return getEnhancedToolDefinitions().stream()
                .collect(Collectors.groupingBy(
                        tool -> tool.getCategory() != null ? tool.getCategory() : "other"
                ));
    }

    /**
     * Generates an agent-friendly tools prompt.
     */
    public String generateAgentToolsPrompt() {
        if (toolDefinitionService != null) {
            return toolDefinitionService.generateAgentToolsPrompt();
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("# Available Tools\n\n");

        Map<String, List<DiscoveredTool>> byCategory = getToolsByCategory();
        for (Map.Entry<String, List<DiscoveredTool>> entry : byCategory.entrySet()) {
            prompt.append("## ").append(formatCategoryName(entry.getKey())).append("\n\n");
            for (DiscoveredTool tool : entry.getValue()) {
                prompt.append("### ").append(tool.getName()).append("\n");
                prompt.append(tool.getDescription()).append("\n");
                if (tool.getParameters() != null && !tool.getParameters().isEmpty()) {
                    prompt.append("**Parameters:**\n");
                    for (ToolParameter param : tool.getParameters()) {
                        prompt.append("- `").append(param.getName()).append("` (").append(param.getType()).append(")");
                        if (param.isRequired()) prompt.append(" [required]");
                        if (param.getDescription() != null) {
                            prompt.append(": ").append(param.getDescription());
                        }
                        prompt.append("\n");
                    }
                }
                prompt.append("\n");
            }
        }

        return prompt.toString();
    }

    /**
     * Converts a DiscoveredTool to an EnhancedToolDefinition.
     */
    private EnhancedToolDefinition convertToEnhancedDefinition(DiscoveredTool tool) {
        String category = inferCategory(tool.getName(), tool.getDescription());

        List<EnhancedToolDefinition.ParameterDefinition> params = new ArrayList<>();
        if (tool.getParameters() != null) {
            for (ToolParameter param : tool.getParameters()) {
                params.add(EnhancedToolDefinition.ParameterDefinition.builder()
                        .name(param.getName())
                        .type(param.getType())
                        .description(param.getDescription())
                        .required(param.isRequired())
                        .build());
            }
        }

        return EnhancedToolDefinition.builder()
                .name(tool.getName())
                .displayName(formatDisplayName(tool.getName()))
                .description(tool.getDescription())
                .category(category)
                .source(EnhancedToolDefinition.ToolSource.BUILT_IN)
                .parameters(params)
                .enabled(true)
                .implementation(EnhancedToolDefinition.ToolImplementation.builder()
                        .type(EnhancedToolDefinition.ImplementationType.BUILT_IN)
                        .beanName(tool.getBeanName())
                        .className(tool.getBeanClass())
                        .methodName(tool.getMethodName())
                        .build())
                .build();
    }

    /**
     * Public method to infer the category for a tool by name.
     * Looks up the tool's description from discovered tools, then delegates to inferCategory().
     */
    public String inferCategoryForTool(String toolName) {
        // Try to find the tool in discovered tools for its description
        String description = discoveredTools.stream()
                .filter(t -> toolName.equals(t.getName()))
                .map(DiscoveredTool::getDescription)
                .findFirst()
                .orElse(null);
        return inferCategory(toolName, description);
    }

    /**
     * Infers the category based on tool name and description.
     */
    private String inferCategory(String toolName, String description) {
        String combined = (toolName + " " + (description != null ? description : "")).toLowerCase();

        for (Map.Entry<String, String> entry : CATEGORY_KEYWORDS.entrySet()) {
            String[] keywords = entry.getValue().split(",");
            for (String keyword : keywords) {
                if (combined.contains(keyword.trim())) {
                    return entry.getKey();
                }
            }
        }

        return "system";
    }

    /**
     * Formats a tool name as a display name (snake_case to Title Case).
     */
    private String formatDisplayName(String toolName) {
        return Arrays.stream(toolName.split("_"))
                .map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1).toLowerCase())
                .collect(Collectors.joining(" "));
    }

    /**
     * Formats a category name for display.
     */
    private String formatCategoryName(String category) {
        switch (category) {
            case "rag": return "RAG & Document Search";
            case "filesystem": return "File System Operations";
            case "indexing": return "Index Management";
            case "model": return "Model Management";
            case "system": return "System Diagnostics";
            case "config": return "Application Configuration";
            case "action_log": return "Action History";
            case "chat": return "Chat & Sessions";
            case "factsheet": return "Fact Sheet Management";
            case "evaluation": return "Evaluation & Testing";
            case "ingestion": return "Document Ingestion";
            case "pipeline": return "Pipeline Management";
            case "settings": return "Filter & Settings";
            case "chunk": return "Chunk Management";
            case "prompt": return "Prompt Templates";
            case "timing": return "Operation Timing";
            case "benchmark": return "Benchmarks";
            case "backup": return "Backup & Restore";
            case "orchestrator": return "Orchestration";
            case "experiment": return "Experiments";
            case "crossindex": return "Cross-Index Tracking";
            case "archive": return "Archives";
            case "vlm": return "VLM Configuration";
            case "kvcache": return "KV Cache";
            case "device": return "Device Routing";
            case "subprocess": return "Subprocess Configuration";
            case "delegation": return "Agent Delegation";
            default: return Character.toUpperCase(category.charAt(0)) + category.substring(1);
        }
    }

    /**
     * Creates endpoint mappings for all discovered built-in tools.
     * These mappings point to the application's own /api/mcp/tools/invoke-direct endpoint.
     */
    public List<EndpointMapping> createMappingsForBuiltInTools() {
        List<EndpointMapping> mappings = new ArrayList<>();

        for (DiscoveredTool tool : discoveredTools) {
            EndpointMapping mapping = EndpointMapping.builder()
                    .id(UUID.randomUUID().toString())
                    .enabled(true)
                    .restEndpoint(RestEndpoint.builder()
                            .method("POST")
                            .path("/api/mcp/tools/invoke-direct")
                            .contentType("application/json")
                            .acceptType("application/json")
                            .requestBodySchema(createInvokeDirectSchema(tool))
                            .build())
                    .mcpTool(McpToolMapping.builder()
                            .name(tool.getName())
                            .description(tool.getDescription())
                            .inputSchema(tool.getInputSchema())
                            .category("built-in")
                            .build())
                    .build();

            mappings.add(mapping);
        }

        return mappings;
    }

    /**
     * Creates a pre-configured bridge for the application's own MCP tools.
     */
    public RestMcpBridgeConfig createSelfBridgeConfig() {
        String baseUrl = serverPortService.getBaseUrl();

        RestMcpBridgeConfig config = RestMcpBridgeConfig.builder()
                .name("Kompile Built-in Tools Bridge")
                .description("Bridge exposing Kompile's built-in MCP tools (RAG, Filesystem) as an MCP server")
                .direction(BridgeDirection.REST_TO_MCP)
                .enabled(true)
                .status(BridgeStatus.STOPPED)
                .restApiConfig(RestApiConfig.builder()
                        .baseUrl(baseUrl)
                        .timeoutMs(30000)
                        .verifySsl(false)
                        .build())
                .mcpServerRef(McpServerRef.builder()
                        .port(8083)
                        .basePath("/mcp-builtin")
                        .build())
                .mappings(createMappingsForBuiltInTools())
                .authConfig(AuthConfig.builder()
                        .type(AuthType.NONE)
                        .build())
                .build();

        return config;
    }

    /**
     * Creates McpToolConfig objects for all discovered tools.
     * These can be used by McpServerRuntime.
     */
    public List<McpToolConfig> createToolConfigsForBuiltInTools() {
        List<McpToolConfig> configs = new ArrayList<>();

        for (DiscoveredTool tool : discoveredTools) {
            // Convert inputSchema to JsonNode if needed
            JsonNode schemaNode = null;
            if (tool.getInputSchema() != null) {
                Object schema = tool.getInputSchema();
                if (schema instanceof JsonNode) {
                    schemaNode = (JsonNode) schema;
                } else {
                    schemaNode = objectMapper.valueToTree(schema);
                }
            }

            McpToolConfig config = McpToolConfig.builder()
                    .name(tool.getName())
                    .description(tool.getDescription())
                    .implementationType(McpToolConfig.ToolImplementationType.BUILT_IN)
                    .inputSchema(schemaNode)
                    .parameters(convertToParameterConfigs(tool.getParameters()))
                    .enabled(true)
                    .javaClassConfig(McpToolConfig.JavaClassConfig.builder()
                            .className(tool.getBeanClass())
                            .methodName(tool.getMethodName())
                            .beanName(tool.getBeanName())
                            .build())
                    .build();

            configs.add(config);
        }

        return configs;
    }

    /**
     * Generates an OpenAPI-style specification for the application's MCP tools endpoint.
     */
    public ObjectNode generateOpenApiSpec() {
        ObjectNode spec = objectMapper.createObjectNode();
        spec.put("openapi", "3.0.3");

        ObjectNode info = spec.putObject("info");
        info.put("title", "Kompile MCP Tools API");
        info.put("version", "1.0.0");
        info.put("description", "REST API for invoking Kompile's built-in MCP tools");

        ArrayNode servers = spec.putArray("servers");
        ObjectNode server = servers.addObject();
        server.put("url", serverPortService.getBaseUrl());

        ObjectNode paths = spec.putObject("paths");

        // /api/mcp/tools/list endpoint
        ObjectNode listPath = paths.putObject("/api/mcp/tools/list");
        ObjectNode listGet = listPath.putObject("get");
        listGet.put("summary", "List available MCP tools");
        listGet.put("operationId", "listMcpTools");
        ObjectNode listResponses = listGet.putObject("responses");
        ObjectNode list200 = listResponses.putObject("200");
        list200.put("description", "List of available tools");

        // /api/mcp/tools/invoke-direct endpoint
        ObjectNode invokePath = paths.putObject("/api/mcp/tools/invoke-direct");
        ObjectNode invokePost = invokePath.putObject("post");
        invokePost.put("summary", "Invoke an MCP tool directly");
        invokePost.put("operationId", "invokeMcpTool");

        ObjectNode requestBody = invokePost.putObject("requestBody");
        requestBody.put("required", true);
        ObjectNode content = requestBody.putObject("content");
        ObjectNode jsonContent = content.putObject("application/json");
        ObjectNode schema = jsonContent.putObject("schema");
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        ObjectNode toolNameProp = properties.putObject("toolName");
        toolNameProp.put("type", "string");
        toolNameProp.put("description", "Name of the tool to invoke");
        ArrayNode enumValues = toolNameProp.putArray("enum");
        for (DiscoveredTool tool : discoveredTools) {
            enumValues.add(tool.getName());
        }

        ObjectNode argumentsProp = properties.putObject("arguments");
        argumentsProp.put("type", "object");
        argumentsProp.put("description", "Tool-specific arguments");

        ArrayNode required = schema.putArray("required");
        required.add("toolName");
        required.add("arguments");

        ObjectNode invokeResponses = invokePost.putObject("responses");
        ObjectNode invoke200 = invokeResponses.putObject("200");
        invoke200.put("description", "Tool execution result");

        // Add individual tool endpoints as operations
        for (DiscoveredTool tool : discoveredTools) {
            addToolToSpec(paths, tool);
        }

        return spec;
    }

    private void addToolToSpec(ObjectNode paths, DiscoveredTool tool) {
        // Create a virtual endpoint for each tool for documentation purposes
        String virtualPath = "/api/mcp/tools/" + tool.getName();
        ObjectNode toolPath = paths.putObject(virtualPath);
        ObjectNode toolPost = toolPath.putObject("post");
        toolPost.put("summary", tool.getDescription());
        toolPost.put("operationId", tool.getName());
        toolPost.putArray("tags").add("MCP Tools");

        if (tool.getInputSchema() != null) {
            ObjectNode requestBody = toolPost.putObject("requestBody");
            requestBody.put("required", true);
            ObjectNode content = requestBody.putObject("content");
            ObjectNode jsonContent = content.putObject("application/json");
            jsonContent.set("schema", objectMapper.valueToTree(tool.getInputSchema()));
        }

        ObjectNode responses = toolPost.putObject("responses");
        ObjectNode resp200 = responses.putObject("200");
        resp200.put("description", "Tool execution result");
    }

    private Object buildInputSchemaFromMethod(Method method) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = objectMapper.createArrayNode();

        Parameter[] parameters = method.getParameters();
        if (parameters.length == 1) {
            // Single parameter - likely a request object
            Class<?> paramType = parameters[0].getType();
            return buildSchemaFromClass(paramType);
        }

        for (Parameter param : parameters) {
            String paramName = param.getName();
            ObjectNode paramSchema = properties.putObject(paramName);
            paramSchema.put("type", getJsonType(param.getType()));
            required.add(paramName);
        }

        if (required.size() > 0) {
            schema.set("required", required);
        }

        return schema;
    }

    private Object buildSchemaFromClass(Class<?> clazz) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = objectMapper.createArrayNode();

        // Check for record components first
        if (clazz.isRecord()) {
            for (var component : clazz.getRecordComponents()) {
                ObjectNode propSchema = properties.putObject(component.getName());
                propSchema.put("type", getJsonType(component.getType()));
                // Assume all record components are required
                required.add(component.getName());
            }
        } else {
            // Fall back to getter methods
            for (Method getter : clazz.getDeclaredMethods()) {
                if (getter.getName().startsWith("get") && getter.getParameterCount() == 0) {
                    String propName = getter.getName().substring(3);
                    propName = Character.toLowerCase(propName.charAt(0)) + propName.substring(1);

                    ObjectNode propSchema = properties.putObject(propName);
                    propSchema.put("type", getJsonType(getter.getReturnType()));
                }
            }
        }

        if (required.size() > 0) {
            schema.set("required", required);
        }

        return schema;
    }

    private List<ToolParameter> extractParametersFromMethod(Method method) {
        List<ToolParameter> params = new ArrayList<>();

        Parameter[] parameters = method.getParameters();
        if (parameters.length == 1) {
            // Single parameter - extract from request object
            Class<?> paramType = parameters[0].getType();
            return extractParametersFromClass(paramType);
        }

        for (Parameter param : parameters) {
            ToolParameter toolParam = new ToolParameter();
            toolParam.setName(param.getName());
            toolParam.setType(getJsonType(param.getType()));
            toolParam.setRequired(true);
            params.add(toolParam);
        }

        return params;
    }

    private List<ToolParameter> extractParametersFromClass(Class<?> clazz) {
        List<ToolParameter> params = new ArrayList<>();

        if (clazz.isRecord()) {
            for (var component : clazz.getRecordComponents()) {
                ToolParameter param = new ToolParameter();
                param.setName(component.getName());
                param.setType(getJsonType(component.getType()));
                param.setRequired(true);
                params.add(param);
            }
        } else {
            for (Method getter : clazz.getDeclaredMethods()) {
                if (getter.getName().startsWith("get") && getter.getParameterCount() == 0) {
                    String propName = getter.getName().substring(3);
                    propName = Character.toLowerCase(propName.charAt(0)) + propName.substring(1);

                    ToolParameter param = new ToolParameter();
                    param.setName(propName);
                    param.setType(getJsonType(getter.getReturnType()));
                    param.setRequired(false);
                    params.add(param);
                }
            }
        }

        return params;
    }

    private String getJsonType(Class<?> type) {
        if (type == String.class) return "string";
        if (type == Integer.class || type == int.class) return "integer";
        if (type == Long.class || type == long.class) return "integer";
        if (type == Double.class || type == double.class) return "number";
        if (type == Float.class || type == float.class) return "number";
        if (type == Boolean.class || type == boolean.class) return "boolean";
        if (type.isArray() || List.class.isAssignableFrom(type)) return "array";
        return "object";
    }

    private Object createInvokeDirectSchema(DiscoveredTool tool) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        ObjectNode toolNameProp = properties.putObject("toolName");
        toolNameProp.put("type", "string");
        toolNameProp.put("const", tool.getName());

        ObjectNode argumentsProp = properties.putObject("arguments");
        if (tool.getInputSchema() != null) {
            Object inputSchema = tool.getInputSchema();
            JsonNode schemaNode;
            if (inputSchema instanceof JsonNode) {
                schemaNode = (JsonNode) inputSchema;
            } else {
                schemaNode = objectMapper.valueToTree(inputSchema);
            }
            if (schemaNode.isObject()) {
                argumentsProp.setAll((ObjectNode) schemaNode);
            } else {
                argumentsProp.put("type", "object");
            }
        } else {
            argumentsProp.put("type", "object");
        }

        ArrayNode required = schema.putArray("required");
        required.add("toolName");
        required.add("arguments");

        return schema;
    }

    private List<McpToolConfig.ParameterConfig> convertToParameterConfigs(List<ToolParameter> params) {
        List<McpToolConfig.ParameterConfig> configs = new ArrayList<>();
        for (ToolParameter param : params) {
            configs.add(McpToolConfig.ParameterConfig.builder()
                    .name(param.getName())
                    .type(param.getType())
                    .description(param.getDescription())
                    .required(param.isRequired())
                    .build());
        }
        return configs;
    }

    /**
     * Represents a discovered tool.
     */
    public static class DiscoveredTool {
        private String name;
        private String description;
        private String beanName;
        private String beanClass;
        private String methodName;
        private String returnType;
        private Object inputSchema;
        private List<ToolParameter> parameters = new ArrayList<>();

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getBeanName() { return beanName; }
        public void setBeanName(String beanName) { this.beanName = beanName; }
        public String getBeanClass() { return beanClass; }
        public void setBeanClass(String beanClass) { this.beanClass = beanClass; }
        public String getMethodName() { return methodName; }
        public void setMethodName(String methodName) { this.methodName = methodName; }
        public String getReturnType() { return returnType; }
        public void setReturnType(String returnType) { this.returnType = returnType; }
        public Object getInputSchema() { return inputSchema; }
        public void setInputSchema(Object inputSchema) { this.inputSchema = inputSchema; }
        public List<ToolParameter> getParameters() { return parameters; }
        public void setParameters(List<ToolParameter> parameters) { this.parameters = parameters; }
    }

    /**
     * Represents a tool parameter.
     */
    public static class ToolParameter {
        private String name;
        private String type;
        private String description;
        private boolean required;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public boolean isRequired() { return required; }
        public void setRequired(boolean required) { this.required = required; }
    }
}
