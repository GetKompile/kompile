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

package ai.kompile.core.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Enhanced tool definition with comprehensive metadata for better agent discoverability.
 * This model extends the basic MCP tool definition to include:
 * - Categories and tags for organization
 * - Usage examples with expected inputs/outputs
 * - Detailed parameter descriptions
 * - Agent-friendly context about when/how to use the tool
 * - Persistence metadata for auto-loading
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EnhancedToolDefinition {

    /**
     * Unique identifier for this tool definition.
     */
    private String id;

    /**
     * The unique name of the tool (used for invocation).
     */
    private String name;

    /**
     * Display name for UI presentation.
     */
    private String displayName;

    /**
     * Short description of what the tool does (1-2 sentences).
     */
    private String description;

    /**
     * Detailed description explaining the tool's functionality,
     * use cases, and any important caveats. This helps agents
     * understand when to use this tool.
     */
    private String detailedDescription;

    /**
     * Category for organizing tools (e.g., "rag", "filesystem", "system", "model").
     */
    private String category;

    /**
     * Tags for additional categorization and search.
     */
    @Builder.Default
    private List<String> tags = new ArrayList<>();

    /**
     * JSON Schema defining the tool's input parameters.
     */
    private JsonNode inputSchema;

    /**
     * JSON Schema defining the expected output format.
     */
    private JsonNode outputSchema;

    /**
     * Detailed parameter definitions with examples and descriptions.
     */
    @Builder.Default
    private List<ParameterDefinition> parameters = new ArrayList<>();

    /**
     * Usage examples demonstrating how to call this tool.
     */
    @Builder.Default
    private List<UsageExample> examples = new ArrayList<>();

    /**
     * Hints for agents about when to use this tool.
     */
    @Builder.Default
    private List<String> usageHints = new ArrayList<>();

    /**
     * Related tools that agents might want to use together.
     */
    @Builder.Default
    private List<String> relatedTools = new ArrayList<>();

    /**
     * Source of this tool definition.
     */
    @Builder.Default
    private ToolSource source = ToolSource.CUSTOM;

    /**
     * Implementation details for execution.
     */
    private ToolImplementation implementation;

    /**
     * Whether this tool is enabled and available for use.
     */
    @Builder.Default
    private boolean enabled = true;

    /**
     * Whether this tool performs write operations (affects undo support).
     */
    @Builder.Default
    private boolean isWriteOperation = false;

    /**
     * Whether results from this tool can be undone.
     */
    @Builder.Default
    private boolean undoable = false;

    /**
     * Version of this tool definition.
     */
    @Builder.Default
    private String version = "1.0.0";

    /**
     * When this definition was created.
     */
    private Instant createdAt;

    /**
     * When this definition was last modified.
     */
    private Instant updatedAt;

    /**
     * Creator/author of this tool definition.
     */
    private String createdBy;

    /**
     * Source of tool definitions.
     */
    public enum ToolSource {
        /**
         * Built into the application (Spring AI @Tool annotation)
         */
        BUILT_IN,
        /**
         * Configured MCP server tool
         */
        MCP_SERVER,
        /**
         * REST-MCP bridge tool
         */
        REST_BRIDGE,
        /**
         * Custom user-defined tool
         */
        CUSTOM,
        /**
         * Imported from external source
         */
        IMPORTED
    }

    /**
     * Detailed parameter definition with enhanced metadata.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ParameterDefinition {
        /**
         * Parameter name.
         */
        private String name;

        /**
         * Display name for UI.
         */
        private String displayName;

        /**
         * JSON Schema type (string, number, boolean, object, array).
         */
        private String type;

        /**
         * Human-readable description of what this parameter controls.
         */
        private String description;

        /**
         * Whether this parameter is required.
         */
        @Builder.Default
        private boolean required = false;

        /**
         * Default value if not provided.
         */
        private Object defaultValue;

        /**
         * Example values for this parameter.
         */
        @Builder.Default
        private List<Object> exampleValues = new ArrayList<>();

        /**
         * Allowed values (for enum-type parameters).
         */
        private List<Object> enumValues;

        /**
         * Minimum value (for numeric parameters).
         */
        private Number minimum;

        /**
         * Maximum value (for numeric parameters).
         */
        private Number maximum;

        /**
         * Pattern (for string validation).
         */
        private String pattern;

        /**
         * Nested properties (for object parameters).
         */
        private List<ParameterDefinition> properties;

        /**
         * Items definition (for array parameters).
         */
        private ParameterDefinition items;
    }

    /**
     * Usage example demonstrating tool invocation.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class UsageExample {
        /**
         * Title/name for this example.
         */
        private String title;

        /**
         * Description of what this example demonstrates.
         */
        private String description;

        /**
         * Example input arguments.
         */
        private Map<String, Object> input;

        /**
         * Expected output (for reference).
         */
        private Object expectedOutput;

        /**
         * Natural language description of the scenario.
         */
        private String scenario;
    }

    /**
     * Tool implementation configuration.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ToolImplementation {
        /**
         * Type of implementation.
         */
        private ImplementationType type;

        /**
         * Spring bean name (for BUILT_IN type).
         */
        private String beanName;

        /**
         * Class name (for JAVA_CLASS type).
         */
        private String className;

        /**
         * Method name (for JAVA_CLASS or BUILT_IN type).
         */
        private String methodName;

        /**
         * HTTP endpoint URL (for HTTP_ENDPOINT type).
         */
        private String httpUrl;

        /**
         * HTTP method (for HTTP_ENDPOINT type).
         */
        private String httpMethod;

        /**
         * HTTP headers (for HTTP_ENDPOINT type).
         */
        private Map<String, String> httpHeaders;

        /**
         * Script content (for SCRIPT type).
         */
        private String script;

        /**
         * Script language (for SCRIPT type).
         */
        private String scriptLanguage;

        /**
         * MCP server ID (for MCP_SERVER type).
         */
        private String mcpServerId;

        /**
         * REST bridge ID (for REST_BRIDGE type).
         */
        private String restBridgeId;
    }

    /**
     * Implementation types.
     */
    public enum ImplementationType {
        BUILT_IN,
        JAVA_CLASS,
        HTTP_ENDPOINT,
        SCRIPT,
        MCP_SERVER,
        REST_BRIDGE
    }

    /**
     * Creates a builder with timestamps initialized.
     */
    public static EnhancedToolDefinitionBuilder builderWithTimestamps() {
        Instant now = Instant.now();
        return EnhancedToolDefinition.builder()
                .createdAt(now)
                .updatedAt(now);
    }

    /**
     * Updates the modification timestamp.
     */
    public void touch() {
        this.updatedAt = Instant.now();
    }

    /**
     * Generates an agent-friendly description combining all metadata.
     */
    public String getAgentPrompt() {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Tool: ").append(name).append("\n");
        prompt.append("Description: ").append(description).append("\n");

        if (detailedDescription != null && !detailedDescription.isEmpty()) {
            prompt.append("Details: ").append(detailedDescription).append("\n");
        }

        if (category != null) {
            prompt.append("Category: ").append(category).append("\n");
        }

        if (usageHints != null && !usageHints.isEmpty()) {
            prompt.append("When to use:\n");
            for (String hint : usageHints) {
                prompt.append("  - ").append(hint).append("\n");
            }
        }

        if (parameters != null && !parameters.isEmpty()) {
            prompt.append("Parameters:\n");
            for (ParameterDefinition param : parameters) {
                prompt.append("  - ").append(param.getName())
                      .append(" (").append(param.getType()).append(")")
                      .append(param.isRequired() ? " [required]" : " [optional]");
                if (param.getDescription() != null) {
                    prompt.append(": ").append(param.getDescription());
                }
                prompt.append("\n");
            }
        }

        if (examples != null && !examples.isEmpty()) {
            prompt.append("Example usage:\n");
            UsageExample example = examples.get(0);
            if (example.getInput() != null) {
                prompt.append("  Input: ").append(example.getInput()).append("\n");
            }
        }

        if (relatedTools != null && !relatedTools.isEmpty()) {
            prompt.append("Related tools: ").append(String.join(", ", relatedTools)).append("\n");
        }

        return prompt.toString();
    }
}
