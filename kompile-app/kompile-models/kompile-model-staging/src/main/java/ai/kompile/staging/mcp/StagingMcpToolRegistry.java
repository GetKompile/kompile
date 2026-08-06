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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.staging.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Auto-discovering MCP tool registry for the staging application.
 *
 * <p>Schema generation and invocation intentionally use Spring AI's
 * {@link MethodToolCallbackProvider}. This is the same AOT-aware path used by the
 * unified server and avoids record/Jackson reflection at native-image runtime.</p>
 */
@Component
@ConditionalOnClass(name = "ai.kompile.staging.catalog.CatalogService")
@ConditionalOnProperty(name = "kompile.staging.app.enabled", havingValue = "true")
public class StagingMcpToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(StagingMcpToolRegistry.class);

    private final ObjectMapper objectMapper;
    private final ApplicationContext applicationContext;
    private int toolCount;

    @Autowired
    public StagingMcpToolRegistry(ObjectMapper objectMapper, ApplicationContext applicationContext) {
        this.objectMapper = objectMapper;
        this.applicationContext = applicationContext;
    }

    public void registerTools(McpSyncServer server) {
        List<McpServerFeatures.SyncToolSpecification> specifications = discoverToolSpecifications();
        int registered = 0;
        for (McpServerFeatures.SyncToolSpecification specification : specifications) {
            try {
                server.addTool(specification);
                registered++;
                log.debug("Registered MCP tool: {}", specification.tool().name());
            } catch (Exception e) {
                log.error("Failed to register tool '{}'", specification.tool().name(), e);
            }
        }
        toolCount = registered;
        log.info("Registered {} MCP tools with the server", toolCount);
    }

    public int getToolCount() {
        return toolCount;
    }

    /** Package-visible for the native registration contract test. */
    List<McpServerFeatures.SyncToolSpecification> discoverToolSpecifications() {
        Object[] toolObjects = discoverToolBeans().toArray();
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(toolObjects)
                .build()
                .getToolCallbacks();

        List<McpServerFeatures.SyncToolSpecification> specifications = new ArrayList<>(callbacks.length);
        for (ToolCallback callback : callbacks) {
            specifications.add(createToolSpec(callback));
        }
        log.info("Discovered {} staging MCP tool objects and {} callbacks", toolObjects.length, callbacks.length);
        return specifications;
    }

    private List<Object> discoverToolBeans() {
        List<Object> toolBeans = new ArrayList<>();
        String[] beanNames = applicationContext.getBeanDefinitionNames();
        Arrays.sort(beanNames);

        for (String beanName : beanNames) {
            try {
                Class<?> type = applicationContext.getType(beanName);
                if (type == null || !declaresToolMethods(type)) {
                    continue;
                }
                toolBeans.add(applicationContext.getBean(beanName));
            } catch (Exception e) {
                log.warn("Staging tool bean '{}' could not be discovered: {}", beanName, e.getMessage());
            }
        }
        return toolBeans;
    }

    private static boolean declaresToolMethods(Class<?> type) {
        for (Method method : ClassUtils.getUserClass(type).getDeclaredMethods()) {
            if (method.isAnnotationPresent(org.springframework.ai.tool.annotation.Tool.class)) {
                return true;
            }
        }
        return false;
    }

    private McpServerFeatures.SyncToolSpecification createToolSpec(ToolCallback callback) {
        ToolDefinition definition = callback.getToolDefinition();
        Tool tool = new Tool(
                definition.name(),
                definition.description(),
                toMcpJsonSchema(definition.inputSchema()));

        return new McpServerFeatures.SyncToolSpecification(
                tool,
                (exchange, args) -> {
                    try {
                        String result = callback.call(objectMapper.writeValueAsString(args));
                        return new CallToolResult(
                                List.of(new TextContent(result != null ? result : "null")),
                                false);
                    } catch (Throwable e) {
                        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                        log.error("Tool {} failed: {}", definition.name(), message, e);
                        return errorResult(message);
                    }
                });
    }

    private JsonSchema toMcpJsonSchema(String schemaJson) {
        try {
            JsonNode root = objectMapper.readTree(schemaJson);
            String type = root.path("type").asText("object");
            List<String> required = new ArrayList<>();
            JsonNode requiredNode = root.get("required");
            if (requiredNode != null && requiredNode.isArray()) {
                requiredNode.forEach(node -> required.add(node.asText()));
            }
            Boolean additionalProperties = root.path("additionalProperties").isBoolean()
                    ? root.path("additionalProperties").booleanValue()
                    : null;
            return new JsonSchema(
                    type,
                    jsonObjectMap(root.get("properties")),
                    required,
                    additionalProperties,
                    jsonObjectMap(root.get("$defs")),
                    jsonObjectMap(root.get("definitions")));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Spring AI schema: " + schemaJson, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonObjectMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Collections.emptyMap();
        }
        return objectMapper.convertValue(node, Map.class);
    }

    private static CallToolResult errorResult(String message) {
        return new CallToolResult(List.of(new TextContent("Error: " + message)), true);
    }
}
