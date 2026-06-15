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

package ai.kompile.core.prompt;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a prompt template with variable substitution support.
 * Templates can be organized by category and include metadata for discoverability.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PromptTemplate {

    /**
     * Unique identifier for the template.
     */
    private String id;

    /**
     * Unique name for the template (used as a key).
     */
    private String name;

    /**
     * Human-readable display name.
     */
    private String displayName;

    /**
     * Short description of what the template does.
     */
    private String description;

    /**
     * Category for organization (e.g., "rag", "summarization", "code", "analysis").
     */
    private String category;

    /**
     * The template content with variable placeholders.
     * Variables use {{variableName}} syntax.
     */
    private String content;

    /**
     * System prompt to be used with this template (optional).
     */
    private String systemPrompt;

    /**
     * Defined variables that can be substituted into the template.
     */
    @Builder.Default
    private List<TemplateVariable> variables = new ArrayList<>();

    /**
     * Usage examples showing how to use this template.
     */
    @Builder.Default
    private List<TemplateExample> examples = new ArrayList<>();

    /**
     * Tags for searchability.
     */
    @Builder.Default
    private List<String> tags = new ArrayList<>();

    /**
     * Whether this template is active/enabled.
     */
    @Builder.Default
    private boolean enabled = true;

    /**
     * Whether this is a built-in system template.
     */
    @Builder.Default
    private boolean builtIn = false;

    /**
     * Template version for tracking changes.
     */
    private String version;

    /**
     * When the template was created.
     */
    private Instant createdAt;

    /**
     * When the template was last updated.
     */
    private Instant updatedAt;

    /**
     * Who created the template.
     */
    private String createdBy;

    /**
     * Output format hint (e.g., "json", "markdown", "text").
     */
    private String outputFormat;

    /**
     * Recommended model for this template (optional hint).
     */
    private String recommendedModel;

    /**
     * Maximum tokens recommendation for this template.
     */
    private Integer maxTokens;

    /**
     * Temperature recommendation for this template.
     */
    private Double temperature;

    // Pattern for matching template variables: {{variableName}} or {{variableName:defaultValue}}
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{([a-zA-Z_][a-zA-Z0-9_]*)(:[^}]*)?\\}\\}");

    /**
     * Renders the template by substituting variables with provided values.
     *
     * @param values Map of variable names to values
     * @return The rendered template string
     */
    public String render(Map<String, Object> values) {
        if (content == null) return "";

        String result = content;
        Matcher matcher = VARIABLE_PATTERN.matcher(content);

        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String varName = matcher.group(1);
            String defaultValue = matcher.group(2) != null ? matcher.group(2).substring(1) : null;

            Object value = values.get(varName);
            String replacement;

            if (value != null) {
                replacement = value.toString();
            } else if (defaultValue != null) {
                replacement = defaultValue;
            } else {
                // Check if variable has a default in the definition
                TemplateVariable varDef = getVariable(varName);
                if (varDef != null && varDef.getDefaultValue() != null) {
                    replacement = varDef.getDefaultValue();
                } else {
                    replacement = "{{" + varName + "}}"; // Leave as is if no value
                }
            }

            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    /**
     * Gets a variable definition by name.
     */
    public TemplateVariable getVariable(String name) {
        if (variables == null) return null;
        return variables.stream()
                .filter(v -> name.equals(v.getName()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Extracts all variable names from the template content.
     */
    public List<String> extractVariableNames() {
        if (content == null) return Collections.emptyList();

        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = VARIABLE_PATTERN.matcher(content);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return new ArrayList<>(names);
    }

    /**
     * Validates that all required variables have values.
     */
    public List<String> validateVariables(Map<String, Object> values) {
        List<String> missing = new ArrayList<>();

        if (variables != null) {
            for (TemplateVariable var : variables) {
                if (var.isRequired()) {
                    Object value = values.get(var.getName());
                    if (value == null || (value instanceof String && ((String) value).isEmpty())) {
                        if (var.getDefaultValue() == null) {
                            missing.add(var.getName());
                        }
                    }
                }
            }
        }

        return missing;
    }

    /**
     * Gets a preview of the template (first N characters).
     */
    public String getPreview(int maxLength) {
        if (content == null) return "";
        if (content.length() <= maxLength) return content;
        return content.substring(0, maxLength - 3) + "...";
    }

    /**
     * Represents a variable that can be substituted in the template.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TemplateVariable {
        /**
         * Variable name (used in {{name}} syntax).
         */
        private String name;

        /**
         * Human-readable display name.
         */
        private String displayName;

        /**
         * Description of what this variable is for.
         */
        private String description;

        /**
         * Type of the variable (string, number, boolean, array, object).
         */
        @Builder.Default
        private String type = "string";

        /**
         * Whether this variable is required.
         */
        @Builder.Default
        private boolean required = false;

        /**
         * Default value if not provided.
         */
        private String defaultValue;

        /**
         * Example value for documentation.
         */
        private String exampleValue;

        /**
         * Allowed values for enum-like variables.
         */
        private List<String> allowedValues;

        /**
         * Validation pattern (regex) for the variable.
         */
        private String validationPattern;

        /**
         * Minimum length for string variables.
         */
        private Integer minLength;

        /**
         * Maximum length for string variables.
         */
        private Integer maxLength;
    }

    /**
     * Represents a usage example for the template.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TemplateExample {
        /**
         * Title of the example.
         */
        private String title;

        /**
         * Description of what this example demonstrates.
         */
        private String description;

        /**
         * Input values for the variables.
         */
        private Map<String, Object> inputs;

        /**
         * The rendered output for this example.
         */
        private String renderedOutput;

        /**
         * Expected response (for documentation).
         */
        private String expectedResponse;
    }

    /**
     * Common template categories.
     */
    public static final Map<String, CategoryInfo> CATEGORIES = new LinkedHashMap<>();

    static {
        CATEGORIES.put("rag", new CategoryInfo("RAG & Retrieval",
                "Templates for retrieval-augmented generation and document Q&A",
                Arrays.asList("retrieval", "search", "document", "qa")));

        CATEGORIES.put("summarization", new CategoryInfo("Summarization",
                "Templates for summarizing text, documents, and conversations",
                Arrays.asList("summary", "condense", "brief")));

        CATEGORIES.put("code", new CategoryInfo("Code Generation",
                "Templates for generating, reviewing, and explaining code",
                Arrays.asList("coding", "programming", "development")));

        CATEGORIES.put("analysis", new CategoryInfo("Analysis",
                "Templates for analyzing data, text, and patterns",
                Arrays.asList("analyze", "examine", "review")));

        CATEGORIES.put("creative", new CategoryInfo("Creative Writing",
                "Templates for creative content generation",
                Arrays.asList("writing", "story", "creative")));

        CATEGORIES.put("extraction", new CategoryInfo("Data Extraction",
                "Templates for extracting structured data from text",
                Arrays.asList("extract", "parse", "structure")));

        CATEGORIES.put("classification", new CategoryInfo("Classification",
                "Templates for categorizing and classifying content",
                Arrays.asList("classify", "categorize", "label")));

        CATEGORIES.put("translation", new CategoryInfo("Translation",
                "Templates for language translation and localization",
                Arrays.asList("translate", "language", "localize")));

        CATEGORIES.put("conversation", new CategoryInfo("Conversation",
                "Templates for chat and conversational interactions",
                Arrays.asList("chat", "dialog", "conversation")));

        CATEGORIES.put("system", new CategoryInfo("System Prompts",
                "System prompts for configuring agent behavior",
                Arrays.asList("system", "persona", "behavior")));

        CATEGORIES.put("custom", new CategoryInfo("Custom",
                "User-defined custom templates",
                Collections.emptyList()));
    }

    /**
     * Category information.
     */
    public record CategoryInfo(String displayName, String description, List<String> keywords) {}
}
