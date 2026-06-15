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

package ai.kompile.app.tools;

import ai.kompile.app.services.mcp.BuiltInToolDiscoveryService;
import ai.kompile.app.services.mcp.BuiltInToolDiscoveryService.DiscoveredTool;
import ai.kompile.app.services.mcp.ToolDefinitionService;
import ai.kompile.core.mcp.EnhancedToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Dynamic-toolset meta-tools that let agents discover and invoke tools on
 * demand instead of paying the full {@code tools/list} schema cost up front.
 *
 * <p>Three methods are exposed:
 * <ul>
 *   <li>{@code search_tools(query, tags?)} — lightweight name/description search
 *       returning only {@code {name, description, category, tags}} per match.</li>
 *   <li>{@code describe_tools(names)} — full {@link EnhancedToolDefinition}
 *       (including input schema) for a batch of tools.</li>
 *   <li>{@code execute_tool(name, args)} — invokes the named tool with the given
 *       argument map and returns the tool's raw result.</li>
 * </ul>
 *
 * <p>These are the only Kompile tools exposed in {@code DYNAMIC} mode, and they
 * ride alongside a small curated allow-list in {@code HYBRID} mode. Agent
 * prompts should lead with {@code search_tools} to minimize upfront schema
 * cost.
 */
@Component
public class DynamicToolsetsMetaTool {

    private static final Logger log = LoggerFactory.getLogger(DynamicToolsetsMetaTool.class);

    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;
    private final BuiltInToolDiscoveryService toolDiscoveryService;
    private final ToolDefinitionService toolDefinitionService;

    @Autowired
    public DynamicToolsetsMetaTool(ApplicationContext applicationContext,
                                   ObjectMapper objectMapper,
                                   @Autowired(required = false) BuiltInToolDiscoveryService toolDiscoveryService,
                                   @Autowired(required = false) ToolDefinitionService toolDefinitionService) {
        this.applicationContext = applicationContext;
        this.objectMapper = objectMapper;
        this.toolDiscoveryService = toolDiscoveryService;
        this.toolDefinitionService = toolDefinitionService;
    }

    public record SearchToolsInput(String query, List<String> tags) {}

    public record DescribeToolsInput(List<String> names) {}

    public record ExecuteToolInput(String name, Map<String, Object> args) {}

    @Tool(name = "search_tools",
          description = "Search the Kompile tool catalog by keyword. Always call this first " +
                        "before describe_tools/execute_tool unless you already know the tool " +
                        "name. Returns lightweight entries (name, description, category, tags) " +
                        "without full input schemas to minimize context cost.")
    public Map<String, Object> searchTools(SearchToolsInput input) {
        String query = input != null && input.query() != null ? input.query() : "";
        List<String> tagFilter = input != null ? input.tags() : null;

        List<Map<String, Object>> matches = new ArrayList<>();
        if (toolDefinitionService != null) {
            List<EnhancedToolDefinition> defs = toolDefinitionService.searchTools(query);
            for (EnhancedToolDefinition def : defs) {
                if (!def.isEnabled()) {
                    continue;
                }
                if (!matchesTags(def.getTags(), tagFilter)) {
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", def.getName());
                entry.put("description", def.getDescription());
                entry.put("category", def.getCategory());
                entry.put("tags", def.getTags());
                matches.add(entry);
            }
        } else if (toolDiscoveryService != null) {
            String lowerQuery = query.toLowerCase(Locale.ROOT);
            for (DiscoveredTool tool : toolDiscoveryService.getDiscoveredTools()) {
                if (!lowerQuery.isEmpty()
                        && !containsIgnoreCase(tool.getName(), lowerQuery)
                        && !containsIgnoreCase(tool.getDescription(), lowerQuery)) {
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", tool.getName());
                entry.put("description", tool.getDescription());
                matches.add(entry);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query", query);
        response.put("count", matches.size());
        response.put("matches", matches);
        return response;
    }

    @Tool(name = "describe_tools",
          description = "Return the full schema (input parameters, descriptions, tags) for a " +
                        "batch of tool names. Use this after search_tools to inspect the exact " +
                        "arguments required before calling execute_tool.")
    public Map<String, Object> describeTools(DescribeToolsInput input) {
        List<String> names = input != null && input.names() != null ? input.names() : List.of();
        List<Object> descriptions = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            Optional<EnhancedToolDefinition> def = toolDefinitionService != null
                    ? toolDefinitionService.getToolByName(name)
                    : Optional.empty();
            if (def.isPresent()) {
                descriptions.add(def.get());
            } else {
                DiscoveredTool fallback = findDiscoveredTool(name);
                if (fallback != null) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", fallback.getName());
                    entry.put("description", fallback.getDescription());
                    entry.put("inputSchema", fallback.getInputSchema());
                    entry.put("parameters", fallback.getParameters());
                    descriptions.add(entry);
                } else {
                    missing.add(name);
                }
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("tools", descriptions);
        if (!missing.isEmpty()) {
            response.put("missing", missing);
        }
        return response;
    }

    @Tool(name = "execute_tool",
          description = "Invoke a named Kompile tool with the given argument map. Equivalent to " +
                        "calling the tool directly but available even when the tool is not in the " +
                        "visible toolset (DYNAMIC mode). Returns the tool's raw result or an error " +
                        "map if the tool is unknown.")
    public Object executeTool(ExecuteToolInput input) {
        if (input == null || input.name() == null || input.name().isBlank()) {
            return error("name is required");
        }
        String name = input.name();
        Map<String, Object> args = input.args() != null ? input.args() : Map.of();

        DiscoveredTool tool = findDiscoveredTool(name);
        if (tool == null) {
            return error("unknown tool: " + name);
        }

        Object bean = resolveBean(tool);
        if (bean == null) {
            return error("bean not available for tool: " + name);
        }

        Method method = findAnnotatedMethod(bean.getClass(), name, tool.getMethodName());
        if (method == null) {
            return error("method not found for tool: " + name);
        }

        try {
            Object result = invoke(bean, method, args);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("tool", name);
            response.put("result", result);
            return response;
        } catch (Exception e) {
            log.warn("execute_tool failed for '{}': {}", name, e.getMessage(), e);
            return error("execution failed: " + rootCause(e));
        }
    }

    private Object resolveBean(DiscoveredTool tool) {
        if (tool.getBeanName() != null) {
            try {
                return applicationContext.getBean(tool.getBeanName());
            } catch (Exception e) {
                log.debug("Bean lookup by name '{}' failed, trying class lookup: {}", tool.getBeanName(), e.getMessage());
            }
        }
        if (tool.getBeanClass() != null) {
            try {
                Class<?> clazz = Class.forName(tool.getBeanClass());
                return applicationContext.getBean(clazz);
            } catch (Exception e) {
                log.warn("Bean lookup by class '{}' failed: {}", tool.getBeanClass(), e.getMessage());
            }
        }
        return null;
    }

    private Method findAnnotatedMethod(Class<?> clazz, String toolName, String methodName) {
        for (Method method : clazz.getDeclaredMethods()) {
            org.springframework.ai.tool.annotation.Tool toolAnnotation =
                    method.getAnnotation(org.springframework.ai.tool.annotation.Tool.class);
            if (toolAnnotation == null) {
                continue;
            }
            String declaredName = toolAnnotation.name().isBlank() ? method.getName() : toolAnnotation.name();
            if (toolName.equals(declaredName) || toolName.equals(method.getName())) {
                return method;
            }
            if (methodName != null && methodName.equals(method.getName())) {
                return method;
            }
        }
        return null;
    }

    private Object invoke(Object bean, Method method, Map<String, Object> args) throws Exception {
        method.setAccessible(true);
        Parameter[] parameters = method.getParameters();
        Object[] invokeArgs = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Class<?> paramType = parameters[i].getType();
            if (paramType.isRecord()) {
                invokeArgs[i] = buildRecord(paramType, args);
            } else {
                invokeArgs[i] = objectMapper.convertValue(args.get(parameters[i].getName()), paramType);
            }
        }
        return method.invoke(bean, invokeArgs);
    }

    private Object buildRecord(Class<?> recordClass, Map<String, Object> args) throws Exception {
        // Wrap in try/catch(Throwable) because GraalVM native image throws
        // com.oracle.svm.core.jdk.UnsupportedFeatureError (extends Error, not Exception)
        // when getRecordComponents() is called without native-image reflection config.
        RecordComponent[] components;
        try {
            components = recordClass.getRecordComponents();
        } catch (Throwable t) {
            throw new UnsupportedOperationException(
                    "getRecordComponents() not available for " + recordClass.getName() + " in native image", t);
        }
        Class<?>[] paramTypes = new Class<?>[components.length];
        Object[] paramValues = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            paramTypes[i] = components[i].getType();
            Object raw = args.get(components[i].getName());
            paramValues[i] = raw == null ? null : objectMapper.convertValue(raw, components[i].getType());
        }
        return recordClass.getDeclaredConstructor(paramTypes).newInstance(paramValues);
    }

    private DiscoveredTool findDiscoveredTool(String name) {
        if (toolDiscoveryService == null) {
            return null;
        }
        for (DiscoveredTool tool : toolDiscoveryService.getDiscoveredTools()) {
            if (name.equals(tool.getName())) {
                return tool;
            }
        }
        return null;
    }

    private static boolean matchesTags(List<String> toolTags, List<String> filter) {
        if (filter == null || filter.isEmpty()) {
            return true;
        }
        if (toolTags == null) {
            return false;
        }
        for (String wanted : filter) {
            if (wanted == null) continue;
            String lower = wanted.toLowerCase(Locale.ROOT);
            boolean found = false;
            for (String tag : toolTags) {
                if (tag != null && tag.toLowerCase(Locale.ROOT).contains(lower)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsIgnoreCase(String haystack, String lowerNeedle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(lowerNeedle);
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", message);
        return out;
    }

    private static String rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() != null ? cur.getMessage() : cur.getClass().getSimpleName();
    }
}
