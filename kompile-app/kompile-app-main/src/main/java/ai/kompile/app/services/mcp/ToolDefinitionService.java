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

import ai.kompile.core.mcp.EnhancedToolDefinition;
import ai.kompile.core.mcp.EnhancedToolDefinition.*;
import ai.kompile.core.mcp.ToolChangeEvent;
import ai.kompile.core.mcp.ToolDefinitionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service for managing tool definitions with enhanced metadata and persistence.
 * Combines built-in tool discovery with custom user-defined tools.
 * Provides agent-friendly tool information for better discoverability.
 */
@Service
public class ToolDefinitionService {

    private static final Logger logger = LoggerFactory.getLogger(ToolDefinitionService.class);

    private final ApplicationContext applicationContext;
    private final ToolDefinitionRepository repository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    // Cached unified view of all tools (built-in + custom)
    private final Map<String, EnhancedToolDefinition> unifiedToolCache = new ConcurrentHashMap<>();

    // Predefined categories with descriptions
    private static final Map<String, CategoryInfo> CATEGORIES = new LinkedHashMap<>();

    static {
        CATEGORIES.put("rag", new CategoryInfo("RAG & Document Search",
                "Tools for retrieving and querying documents using semantic search and RAG (Retrieval-Augmented Generation).",
                Arrays.asList("query", "search", "retrieve", "document")));

        CATEGORIES.put("filesystem", new CategoryInfo("File System Operations",
                "Tools for reading, writing, and managing files and directories.",
                Arrays.asList("file", "directory", "path", "read", "write")));

        CATEGORIES.put("indexing", new CategoryInfo("Index Management",
                "Tools for managing document indexes, including building, updating, and querying index statistics.",
                Arrays.asList("index", "rebuild", "stats")));

        CATEGORIES.put("model", new CategoryInfo("Model Management",
                "Tools for inspecting and managing ML models, embeddings, and SameDiff graphs.",
                Arrays.asList("model", "embedding", "samediff", "nd4j")));

        CATEGORIES.put("system", new CategoryInfo("System Diagnostics",
                "Tools for monitoring system resources, memory, threads, and JVM configuration.",
                Arrays.asList("memory", "cpu", "thread", "jvm", "gc")));

        CATEGORIES.put("config", new CategoryInfo("Application Configuration",
                "Tools for viewing and modifying application settings and Spring configuration.",
                Arrays.asList("config", "setting", "property", "bean")));

        CATEGORIES.put("action_log", new CategoryInfo("Action History",
                "Tools for viewing and managing action history, including undo operations.",
                Arrays.asList("action", "undo", "history", "log")));

        CATEGORIES.put("chat", new CategoryInfo("Chat & Sessions",
                "Tools for managing chat sessions and conversation history.",
                Arrays.asList("chat", "session", "conversation", "message")));

        CATEGORIES.put("mcp", new CategoryInfo("MCP Integration",
                "Tools for managing MCP servers, bridges, and protocol operations.",
                Arrays.asList("mcp", "server", "bridge")));

        CATEGORIES.put("custom", new CategoryInfo("Custom Tools",
                "User-defined custom tools created through the tool builder.",
                Collections.emptyList()));
    }

    @Autowired
    public ToolDefinitionService(ApplicationContext applicationContext,
                                  ToolDefinitionRepository repository,
                                  ObjectMapper objectMapper,
                                  ApplicationEventPublisher eventPublisher) {
        this.applicationContext = applicationContext;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        // Discover built-in tools and merge with persisted definitions
        discoverAndMergeTools();
        logger.info("Tool definition service initialized with {} tools", unifiedToolCache.size());
    }

    /**
     * Discovers built-in tools and merges them with persisted custom definitions.
     */
    public void discoverAndMergeTools() {
        unifiedToolCache.clear();

        // 1. Discover built-in tools from @Tool annotations
        Map<String, EnhancedToolDefinition> builtInTools = discoverBuiltInTools();
        logger.info("Discovered {} built-in tools", builtInTools.size());

        // 2. Load persisted tool definitions
        List<EnhancedToolDefinition> persistedTools = repository.findAll();
        logger.info("Loaded {} persisted tool definitions", persistedTools.size());

        // 3. Merge: persisted definitions can enhance built-in tools with additional metadata
        for (EnhancedToolDefinition builtIn : builtInTools.values()) {
            EnhancedToolDefinition persisted = repository.findByName(builtIn.getName()).orElse(null);
            if (persisted != null) {
                // Merge persisted metadata into built-in tool
                EnhancedToolDefinition merged = mergeDefinitions(builtIn, persisted);
                unifiedToolCache.put(merged.getName(), merged);
            } else {
                unifiedToolCache.put(builtIn.getName(), builtIn);
            }
        }

        // 4. Add custom tools that are not built-in
        for (EnhancedToolDefinition persisted : persistedTools) {
            if (!unifiedToolCache.containsKey(persisted.getName())) {
                unifiedToolCache.put(persisted.getName(), persisted);
            }
        }

        logger.info("Unified tool cache contains {} tools", unifiedToolCache.size());
    }

    /**
     * Discovers all built-in tools from Spring AI @Tool annotations.
     */
    private Map<String, EnhancedToolDefinition> discoverBuiltInTools() {
        Map<String, EnhancedToolDefinition> tools = new LinkedHashMap<>();

        String[] beanNames = applicationContext.getBeanDefinitionNames();
        for (String beanName : beanNames) {
            try {
                Object bean = applicationContext.getBean(beanName);
                Class<?> beanClass = bean.getClass();

                for (Method method : beanClass.getDeclaredMethods()) {
                    if (method.isAnnotationPresent(Tool.class)) {
                        Tool toolAnnotation = method.getAnnotation(Tool.class);
                        EnhancedToolDefinition definition = createDefinitionFromAnnotation(
                                toolAnnotation, method, beanName, beanClass);
                        tools.put(definition.getName(), definition);
                    }
                }
            } catch (Exception e) {
                logger.debug("Could not introspect bean {}: {}", beanName, e.getMessage());
            }
        }

        return tools;
    }

    /**
     * Creates an enhanced tool definition from a @Tool annotation.
     */
    private EnhancedToolDefinition createDefinitionFromAnnotation(Tool annotation, Method method,
                                                                   String beanName, Class<?> beanClass) {
        String toolName = annotation.name().isEmpty() ? method.getName() : annotation.name();
        String description = annotation.description();
        String category = inferCategory(toolName, description);

        EnhancedToolDefinition.EnhancedToolDefinitionBuilder builder = EnhancedToolDefinition.builder()
                .id(UUID.randomUUID().toString())
                .name(toolName)
                .displayName(formatDisplayName(toolName))
                .description(description)
                .detailedDescription(generateDetailedDescription(toolName, description, method))
                .category(category)
                .tags(generateTags(toolName, description, category))
                .source(ToolSource.BUILT_IN)
                .enabled(true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now());

        // Build input schema and parameters
        JsonNode inputSchema = buildInputSchema(method);
        builder.inputSchema(inputSchema);
        builder.parameters(extractParameters(method));

        // Set usage hints based on category and name
        builder.usageHints(generateUsageHints(toolName, category));

        // Set write operation flag
        builder.isWriteOperation(isWriteOperation(toolName));
        builder.undoable(isUndoable(toolName));

        // Set implementation details
        builder.implementation(ToolImplementation.builder()
                .type(ImplementationType.BUILT_IN)
                .beanName(beanName)
                .className(beanClass.getName())
                .methodName(method.getName())
                .build());

        // Find related tools
        builder.relatedTools(findRelatedTools(toolName, category));

        return builder.build();
    }

    /**
     * Merges a built-in tool definition with a persisted one.
     * Persisted definition can override/enhance certain fields.
     */
    private EnhancedToolDefinition mergeDefinitions(EnhancedToolDefinition builtIn,
                                                     EnhancedToolDefinition persisted) {
        return EnhancedToolDefinition.builder()
                .id(persisted.getId() != null ? persisted.getId() : builtIn.getId())
                .name(builtIn.getName())
                .displayName(persisted.getDisplayName() != null ? persisted.getDisplayName() : builtIn.getDisplayName())
                .description(persisted.getDescription() != null ? persisted.getDescription() : builtIn.getDescription())
                .detailedDescription(persisted.getDetailedDescription() != null ?
                        persisted.getDetailedDescription() : builtIn.getDetailedDescription())
                .category(persisted.getCategory() != null ? persisted.getCategory() : builtIn.getCategory())
                .tags(mergeLists(builtIn.getTags(), persisted.getTags()))
                .inputSchema(builtIn.getInputSchema())
                .outputSchema(persisted.getOutputSchema())
                .parameters(builtIn.getParameters())
                .examples(persisted.getExamples() != null && !persisted.getExamples().isEmpty() ?
                        persisted.getExamples() : builtIn.getExamples())
                .usageHints(mergeLists(builtIn.getUsageHints(), persisted.getUsageHints()))
                .relatedTools(mergeLists(builtIn.getRelatedTools(), persisted.getRelatedTools()))
                .source(ToolSource.BUILT_IN)
                .implementation(builtIn.getImplementation())
                .enabled(persisted.isEnabled())
                .isWriteOperation(builtIn.isWriteOperation())
                .undoable(builtIn.isUndoable())
                .version(persisted.getVersion())
                .createdAt(persisted.getCreatedAt())
                .updatedAt(Instant.now())
                .createdBy(persisted.getCreatedBy())
                .build();
    }

    private List<String> mergeLists(List<String> list1, List<String> list2) {
        Set<String> merged = new LinkedHashSet<>();
        if (list1 != null) merged.addAll(list1);
        if (list2 != null) merged.addAll(list2);
        return new ArrayList<>(merged);
    }

    // === Public API Methods ===

    /**
     * Returns all available tools (built-in + custom).
     */
    public List<EnhancedToolDefinition> getAllTools() {
        return new ArrayList<>(unifiedToolCache.values());
    }

    /**
     * Returns all enabled tools.
     */
    public List<EnhancedToolDefinition> getEnabledTools() {
        return unifiedToolCache.values().stream()
                .filter(EnhancedToolDefinition::isEnabled)
                .collect(Collectors.toList());
    }

    /**
     * Gets a tool by name.
     */
    public Optional<EnhancedToolDefinition> getToolByName(String name) {
        return Optional.ofNullable(unifiedToolCache.get(name));
    }

    /**
     * Gets tools by category.
     */
    public List<EnhancedToolDefinition> getToolsByCategory(String category) {
        return unifiedToolCache.values().stream()
                .filter(t -> category.equalsIgnoreCase(t.getCategory()))
                .collect(Collectors.toList());
    }

    /**
     * Searches tools by query.
     */
    public List<EnhancedToolDefinition> searchTools(String query) {
        if (query == null || query.isEmpty()) return getAllTools();
        String lowerQuery = query.toLowerCase();

        return unifiedToolCache.values().stream()
                .filter(t -> matchesSearch(t, lowerQuery))
                .collect(Collectors.toList());
    }

    private boolean matchesSearch(EnhancedToolDefinition def, String query) {
        if (def.getName() != null && def.getName().toLowerCase().contains(query)) return true;
        if (def.getDescription() != null && def.getDescription().toLowerCase().contains(query)) return true;
        if (def.getCategory() != null && def.getCategory().toLowerCase().contains(query)) return true;
        if (def.getTags() != null) {
            for (String tag : def.getTags()) {
                if (tag.toLowerCase().contains(query)) return true;
            }
        }
        return false;
    }

    /**
     * Saves or updates a tool definition.
     * For built-in tools, this persists customized metadata.
     */
    public EnhancedToolDefinition saveToolDefinition(EnhancedToolDefinition definition) {
        boolean isNew = !unifiedToolCache.containsKey(definition.getName());
        EnhancedToolDefinition saved = repository.save(definition);
        unifiedToolCache.put(saved.getName(), saved);
        logger.info("Saved tool definition: {}", saved.getName());

        // Publish change event
        ToolChangeEvent.ChangeType changeType = isNew ? ToolChangeEvent.ChangeType.CREATED : ToolChangeEvent.ChangeType.UPDATED;
        publishToolChangeEvent(saved.getName(), changeType, saved);

        return saved;
    }

    /**
     * Publishes a tool change event for listeners to react to.
     */
    private void publishToolChangeEvent(String toolName, ToolChangeEvent.ChangeType changeType, EnhancedToolDefinition definition) {
        try {
            ToolChangeEvent event = new ToolChangeEvent(this, toolName, changeType, definition);
            eventPublisher.publishEvent(event);
            logger.debug("Published tool change event: {}", event);
        } catch (Exception e) {
            logger.warn("Failed to publish tool change event: {}", e.getMessage());
        }
    }

    /**
     * Creates a new custom tool definition.
     */
    public EnhancedToolDefinition createCustomTool(EnhancedToolDefinition definition) {
        if (unifiedToolCache.containsKey(definition.getName())) {
            throw new IllegalArgumentException("Tool with name '" + definition.getName() + "' already exists");
        }

        definition.setSource(ToolSource.CUSTOM);
        if (definition.getCategory() == null) {
            definition.setCategory("custom");
        }

        return saveToolDefinition(definition);
    }

    /**
     * Updates an existing tool definition.
     */
    public EnhancedToolDefinition updateToolDefinition(String name, EnhancedToolDefinition updates) {
        EnhancedToolDefinition existing = unifiedToolCache.get(name);
        if (existing == null) {
            throw new IllegalArgumentException("Tool not found: " + name);
        }

        // Apply updates while preserving immutable fields for built-in tools
        if (existing.getSource() == ToolSource.BUILT_IN) {
            // For built-in tools, only allow updating certain fields
            existing.setDisplayName(updates.getDisplayName() != null ? updates.getDisplayName() : existing.getDisplayName());
            existing.setDescription(updates.getDescription() != null ? updates.getDescription() : existing.getDescription());
            existing.setDetailedDescription(updates.getDetailedDescription());
            existing.setCategory(updates.getCategory() != null ? updates.getCategory() : existing.getCategory());
            existing.setTags(updates.getTags() != null ? updates.getTags() : existing.getTags());
            existing.setExamples(updates.getExamples() != null ? updates.getExamples() : existing.getExamples());
            existing.setUsageHints(updates.getUsageHints() != null ? updates.getUsageHints() : existing.getUsageHints());
            existing.setRelatedTools(updates.getRelatedTools() != null ? updates.getRelatedTools() : existing.getRelatedTools());
            existing.setEnabled(updates.isEnabled());
        } else {
            // For custom tools, update all fields
            existing.setDisplayName(updates.getDisplayName());
            existing.setDescription(updates.getDescription());
            existing.setDetailedDescription(updates.getDetailedDescription());
            existing.setCategory(updates.getCategory());
            existing.setTags(updates.getTags());
            existing.setInputSchema(updates.getInputSchema());
            existing.setOutputSchema(updates.getOutputSchema());
            existing.setParameters(updates.getParameters());
            existing.setExamples(updates.getExamples());
            existing.setUsageHints(updates.getUsageHints());
            existing.setRelatedTools(updates.getRelatedTools());
            existing.setImplementation(updates.getImplementation());
            existing.setEnabled(updates.isEnabled());
            existing.setWriteOperation(updates.isWriteOperation());
            existing.setUndoable(updates.isUndoable());
        }

        return saveToolDefinition(existing);
    }

    /**
     * Deletes a custom tool definition.
     * Built-in tools cannot be deleted but can be disabled.
     */
    public boolean deleteToolDefinition(String name) {
        EnhancedToolDefinition existing = unifiedToolCache.get(name);
        if (existing == null) {
            return false;
        }

        if (existing.getSource() == ToolSource.BUILT_IN) {
            throw new IllegalArgumentException("Cannot delete built-in tool. Use disable instead.");
        }

        boolean deleted = repository.deleteByName(name);
        if (deleted) {
            unifiedToolCache.remove(name);
            logger.info("Deleted tool definition: {}", name);
            // Publish deletion event
            publishToolChangeEvent(name, ToolChangeEvent.ChangeType.DELETED, null);
        }
        return deleted;
    }

    /**
     * Enables or disables a tool.
     */
    public EnhancedToolDefinition setToolEnabled(String name, boolean enabled) {
        EnhancedToolDefinition existing = unifiedToolCache.get(name);
        if (existing == null) {
            throw new IllegalArgumentException("Tool not found: " + name);
        }
        existing.setEnabled(enabled);
        EnhancedToolDefinition saved = repository.save(existing);
        unifiedToolCache.put(saved.getName(), saved);

        // Publish enabled/disabled event
        ToolChangeEvent.ChangeType changeType = enabled ? ToolChangeEvent.ChangeType.ENABLED : ToolChangeEvent.ChangeType.DISABLED;
        publishToolChangeEvent(name, changeType, saved);

        logger.info("{} tool: {}", enabled ? "Enabled" : "Disabled", name);
        return saved;
    }

    /**
     * Returns available categories with their descriptions.
     */
    public Map<String, CategoryInfo> getCategories() {
        return Collections.unmodifiableMap(CATEGORIES);
    }

    /**
     * Gets tools grouped by category.
     */
    public Map<String, List<EnhancedToolDefinition>> getToolsByCategories() {
        return unifiedToolCache.values().stream()
                .collect(Collectors.groupingBy(
                        t -> t.getCategory() != null ? t.getCategory() : "other"
                ));
    }

    /**
     * Generates a comprehensive agent prompt describing all available tools.
     */
    public String generateAgentToolsPrompt() {
        StringBuilder prompt = new StringBuilder();
        prompt.append("# Available Tools\n\n");

        Map<String, List<EnhancedToolDefinition>> byCategory = getToolsByCategories();

        for (Map.Entry<String, List<EnhancedToolDefinition>> entry : byCategory.entrySet()) {
            String category = entry.getKey();
            List<EnhancedToolDefinition> tools = entry.getValue();

            CategoryInfo categoryInfo = CATEGORIES.get(category);
            String categoryTitle = categoryInfo != null ? categoryInfo.displayName() : category;
            String categoryDesc = categoryInfo != null ? categoryInfo.description() : "";

            prompt.append("## ").append(categoryTitle).append("\n");
            if (!categoryDesc.isEmpty()) {
                prompt.append(categoryDesc).append("\n");
            }
            prompt.append("\n");

            for (EnhancedToolDefinition tool : tools) {
                if (!tool.isEnabled()) continue;
                prompt.append("### ").append(tool.getName()).append("\n");
                prompt.append(tool.getDescription()).append("\n");

                if (tool.getUsageHints() != null && !tool.getUsageHints().isEmpty()) {
                    prompt.append("**When to use:**\n");
                    for (String hint : tool.getUsageHints()) {
                        prompt.append("- ").append(hint).append("\n");
                    }
                }

                if (tool.getParameters() != null && !tool.getParameters().isEmpty()) {
                    prompt.append("**Parameters:**\n");
                    for (ParameterDefinition param : tool.getParameters()) {
                        prompt.append("- `").append(param.getName()).append("` (").append(param.getType()).append(")");
                        if (param.isRequired()) prompt.append(" [required]");
                        if (param.getDescription() != null) {
                            prompt.append(": ").append(param.getDescription());
                        }
                        prompt.append("\n");
                    }
                }

                if (tool.getExamples() != null && !tool.getExamples().isEmpty()) {
                    prompt.append("**Example:**\n");
                    UsageExample example = tool.getExamples().get(0);
                    if (example.getInput() != null) {
                        prompt.append("```json\n").append(example.getInput()).append("\n```\n");
                    }
                }

                prompt.append("\n");
            }
        }

        return prompt.toString();
    }

    /**
     * Refreshes tool definitions from disk and rediscovers built-in tools.
     */
    public void refresh() {
        repository.refresh();
        discoverAndMergeTools();
        // Publish refresh event
        publishToolChangeEvent(null, ToolChangeEvent.ChangeType.REFRESHED, null);
        logger.info("Tool definitions refreshed");
    }

    // === Helper Methods ===

    private String inferCategory(String toolName, String description) {
        String combined = (toolName + " " + description).toLowerCase();

        for (Map.Entry<String, CategoryInfo> entry : CATEGORIES.entrySet()) {
            for (String keyword : entry.getValue().keywords()) {
                if (combined.contains(keyword)) {
                    return entry.getKey();
                }
            }
        }

        return "system";
    }

    private String formatDisplayName(String toolName) {
        // Convert snake_case to Title Case
        return Arrays.stream(toolName.split("_"))
                .map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1).toLowerCase())
                .collect(Collectors.joining(" "));
    }

    private String generateDetailedDescription(String toolName, String description, Method method) {
        StringBuilder detailed = new StringBuilder(description);

        if (method.getReturnType() != void.class) {
            detailed.append(" Returns ").append(method.getReturnType().getSimpleName()).append(".");
        }

        return detailed.toString();
    }

    private List<String> generateTags(String toolName, String description, String category) {
        Set<String> tags = new LinkedHashSet<>();
        tags.add(category);

        // Add tags from tool name parts
        for (String part : toolName.split("_")) {
            if (part.length() > 2) {
                tags.add(part.toLowerCase());
            }
        }

        return new ArrayList<>(tags);
    }

    private List<String> generateUsageHints(String toolName, String category) {
        List<String> hints = new ArrayList<>();

        switch (category) {
            case "rag":
                hints.add("Use this when the user asks questions about documents or needs information retrieval");
                hints.add("Combine with other tools for complex document analysis");
                break;
            case "filesystem":
                hints.add("Use this for file and directory operations within allowed paths");
                hints.add("Always verify paths are within configured root directories");
                break;
            case "system":
                hints.add("Use this to diagnose system issues or monitor resource usage");
                break;
            case "model":
                hints.add("Use this to inspect ML models or troubleshoot embedding issues");
                break;
            case "indexing":
                hints.add("Use this to manage document indexes and check indexing status");
                break;
        }

        return hints;
    }

    private boolean isWriteOperation(String toolName) {
        return toolName.startsWith("write_") || toolName.startsWith("create_") ||
               toolName.startsWith("delete_") || toolName.startsWith("update_") ||
               toolName.startsWith("set_") || toolName.startsWith("add_") ||
               toolName.startsWith("remove_");
    }

    private boolean isUndoable(String toolName) {
        return toolName.startsWith("write_") || toolName.startsWith("create_") ||
               toolName.startsWith("delete_");
    }

    private List<String> findRelatedTools(String toolName, String category) {
        List<String> related = new ArrayList<>();

        // Find tools in the same category
        for (EnhancedToolDefinition tool : unifiedToolCache.values()) {
            if (!tool.getName().equals(toolName) && category.equals(tool.getCategory())) {
                related.add(tool.getName());
                if (related.size() >= 3) break;
            }
        }

        return related;
    }

    private JsonNode buildInputSchema(Method method) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = objectMapper.createObjectNode();
        List<String> required = new ArrayList<>();

        Parameter[] parameters = method.getParameters();
        for (Parameter param : parameters) {
            Class<?> paramType = param.getType();

            if (paramType.isRecord()) {
                // Extract from record components
                for (RecordComponent component : paramType.getRecordComponents()) {
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
                ObjectNode propSchema = createPropertySchema(paramType);
                properties.set(param.getName(), propSchema);
                required.add(param.getName());
            }
        }

        schema.set("properties", properties);
        if (!required.isEmpty()) {
            schema.set("required", objectMapper.valueToTree(required));
        }

        return schema;
    }

    private ObjectNode createPropertySchema(Class<?> type) {
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
        } else {
            schema.put("type", "object");
        }

        return schema;
    }

    private List<ParameterDefinition> extractParameters(Method method) {
        List<ParameterDefinition> params = new ArrayList<>();

        for (Parameter param : method.getParameters()) {
            Class<?> paramType = param.getType();

            if (paramType.isRecord()) {
                for (RecordComponent component : paramType.getRecordComponents()) {
                    ParameterDefinition.ParameterDefinitionBuilder builder = ParameterDefinition.builder()
                            .name(component.getName())
                            .displayName(formatDisplayName(component.getName()))
                            .type(getJsonType(component.getType()));

                    ToolParam toolParam = component.getAnnotation(ToolParam.class);
                    if (toolParam != null) {
                        builder.description(toolParam.description());
                        builder.required(toolParam.required());
                    }

                    params.add(builder.build());
                }
            } else {
                params.add(ParameterDefinition.builder()
                        .name(param.getName())
                        .displayName(formatDisplayName(param.getName()))
                        .type(getJsonType(paramType))
                        .required(true)
                        .build());
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

    /**
     * Category information record.
     */
    public record CategoryInfo(String displayName, String description, List<String> keywords) {}
}
