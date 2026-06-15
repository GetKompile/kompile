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

import com.fasterxml.jackson.core.JsonProcessingException;
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
import org.springframework.context.ApplicationContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.*;

/**
 * Auto-discovering MCP tool registry for the staging application.
 * Scans all Spring beans for @Tool annotated methods and registers them with the MCP server.
 */
@Component
@ConditionalOnClass(name = "ai.kompile.staging.catalog.CatalogService")
@ConditionalOnProperty(name = "kompile.staging.app.enabled", havingValue = "true")
public class StagingMcpToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(StagingMcpToolRegistry.class);

    private final ObjectMapper objectMapper;
    private final ApplicationContext applicationContext;
    private int toolCount = 0;

    @Autowired
    public StagingMcpToolRegistry(ObjectMapper objectMapper, ApplicationContext applicationContext) {
        this.objectMapper = objectMapper;
        this.applicationContext = applicationContext;
    }

    public void registerTools(McpSyncServer server) {
        List<McpServerFeatures.SyncToolSpecification> allTools = new ArrayList<>();

        // Auto-discover all beans with @Tool methods
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            try {
                Object bean = applicationContext.getBean(beanName);
                Class<?> clazz = bean.getClass();

                for (Method method : clazz.getDeclaredMethods()) {
                    org.springframework.ai.tool.annotation.Tool toolAnnotation =
                            method.getAnnotation(org.springframework.ai.tool.annotation.Tool.class);
                    if (toolAnnotation != null) {
                        try {
                            McpServerFeatures.SyncToolSpecification spec = createToolSpec(bean, method, toolAnnotation);
                            allTools.add(spec);
                            log.debug("Discovered MCP tool: {}.{}", clazz.getSimpleName(), method.getName());
                        } catch (Exception e) {
                            log.error("Failed to create tool spec for {}.{}: {}",
                                    clazz.getSimpleName(), method.getName(), e.getMessage());
                        }
                    }
                }
            } catch (Throwable e) {
                // Skip beans that can't be instantiated (catches GraalVM UnsupportedFeatureError too)
            }
        }

        for (McpServerFeatures.SyncToolSpecification toolSpec : allTools) {
            try {
                server.addTool(toolSpec);
                log.debug("Registered MCP tool: {}", toolSpec.tool().name());
            } catch (Exception e) {
                log.error("Failed to register tool '{}'", toolSpec.tool().name(), e);
            }
        }

        toolCount = allTools.size();
        log.info("Registered {} MCP tools with the server", toolCount);
    }

    public int getToolCount() {
        return toolCount;
    }

    private McpServerFeatures.SyncToolSpecification createToolSpec(
            Object bean, Method method, org.springframework.ai.tool.annotation.Tool toolAnnotation) {

        String toolName = toolAnnotation.name().isEmpty() ? method.getName() : toolAnnotation.name();
        String description = toolAnnotation.description();
        String inputSchema = buildInputSchema(method);

        Tool tool = new Tool(toolName, description, inputSchema);

        return new McpServerFeatures.SyncToolSpecification(
                tool,
                (exchange, args) -> {
                    try {
                        Object result = invokeToolMethod(bean, method, args);
                        return successResult(result);
                    } catch (Throwable e) {
                        log.error("Tool {} failed: {}", toolName, e.getMessage(), e);
                        return errorResult(e.getMessage());
                    }
                }
        );
    }

    private String buildInputSchema(Method method) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();
        List<String> required = new ArrayList<>();

        Parameter[] parameters = method.getParameters();
        for (Parameter param : parameters) {
            Class<?> paramType = param.getType();

            if (paramType.isRecord()) {
                RecordComponent[] components = paramType.getRecordComponents();
                for (RecordComponent component : components) {
                    ObjectNode propSchema = createPropertySchema(component.getType());

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
                String paramName = param.getName();
                ObjectNode propSchema = createPropertySchema(paramType);

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

    private ObjectNode createPropertySchema(Class<?> type) {
        ObjectNode schema = objectMapper.createObjectNode();
        if (type == String.class) {
            schema.put("type", "string");
        } else if (type == Integer.class || type == int.class) {
            schema.put("type", "integer");
        } else if (type == Long.class || type == long.class) {
            schema.put("type", "integer");
        } else if (type == Double.class || type == double.class || type == Float.class || type == float.class) {
            schema.put("type", "number");
        } else if (type == Boolean.class || type == boolean.class) {
            schema.put("type", "boolean");
        } else if (type.isArray() || List.class.isAssignableFrom(type)) {
            schema.put("type", "array");
        } else {
            schema.put("type", "object");
        }
        return schema;
    }

    private Object invokeToolMethod(Object bean, Method method, Map<String, Object> args) throws Exception {
        method.setAccessible(true);

        Parameter[] parameters = method.getParameters();
        Object[] invokeArgs = new Object[parameters.length];

        for (int i = 0; i < parameters.length; i++) {
            Parameter param = parameters[i];
            Class<?> paramType = param.getType();

            if (paramType.isRecord()) {
                invokeArgs[i] = createRecordInstance(paramType, args);
            } else {
                String paramName = param.getName();
                Object value = args.get(paramName);
                invokeArgs[i] = convertValue(value, paramType);
            }
        }

        return method.invoke(bean, invokeArgs);
    }

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

    private Object convertValue(Object value, Class<?> targetType) {
        if (value == null) return null;
        if (targetType.isInstance(value)) return value;

        try {
            return objectMapper.convertValue(value, targetType);
        } catch (Exception e) {
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

    private CallToolResult successResult(Object data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            return new CallToolResult(List.of(new TextContent(json)), false);
        } catch (JsonProcessingException e) {
            return errorResult("Failed to serialize result: " + e.getMessage());
        }
    }

    private static CallToolResult errorResult(String message) {
        return new CallToolResult(List.of(new TextContent("Error: " + message)), true);
    }
}
