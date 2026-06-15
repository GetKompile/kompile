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

// Updated Class: LLMStepConfig.java
// Purpose: Defines the configuration for any LLM step, correctly implementing StepConfig.
// Location: kompile-pipelines-framework/kompile-pipelines-framework-api/src/main/java/ai/kompile/pipelines/framework/api/llm/
package ai.kompile.pipelines.framework.api.llm;

import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.data.DataFactory;
import ai.kompile.pipelines.framework.api.data.PipelineToolDefinition;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.*;

// Using @EqualsAndHashCode and @ToString from Lombok for convenience on declared fields.
// We are not using @Data or @Getter/@Setter from Lombok directly on the class to have more control,
// especially due to the interaction with the 'parameters' Data object.
@EqualsAndHashCode(callSuper = false) // Only consider fields of this class for equals/hashcode
@ToString
public class LLMStepConfig implements StepConfig {
    private static final long serialVersionUID = 1L; // From Configuration interface via StepConfig

    // Fields from StepConfig that are typically part of the constructor or direct properties
    private final String name; // Name of the step instance
    private final String type; // Symbolic type of the step, maps to a factory (e.g., "SAMEDIFF_LANGUAGE_MODEL")
    private final String runnerClassName; // Specific runner class, often determined by the factory based on 'type'

    // Specific fields for LLMStepConfig
    private String modelUri;
    private String tokenizerUri;
    private String tokenizerType;
    private Map<String, String> tokenizerConfig;

    private String promptInputName;
    private String responseOutputName;
    private String toolCallRequestOutputName;
    private String toolCallResponseInputName;
    private List<PipelineToolDefinition> toolDefinitions;

    private ToolChoiceMode toolChoice;
    private String specificToolNameForCall;
    private ToolCallOutputFormat toolCallOutputFormat;

    private Map<String, Object> generationParameters;
    private String conversationContextName;

    // Internal Data object to hold all parameters, including the specific ones above
    // and any additional generic parameters. This aligns with GenericStepConfig.
    private final Data internalParameters;


    public enum ToolChoiceMode {
        AUTO, REQUIRED, NONE, SPECIFIC_TOOL
    }

    public enum ToolCallOutputFormat {
        JSON_MARKER_BASED, OPENAI_JSON, GGUF_NATIVE_FUNCTIONARY_V2
    }

    @JsonCreator
    public LLMStepConfig(
            @JsonProperty("name") String name,
            @JsonProperty(value = "type", required = true) String type, // This 'type' links to the factory
            @JsonProperty("runnerClassName") String runnerClassName, // Optional, factory might set this
            // LLM Specific fields that should also go into 'internalParameters'
            @JsonProperty("modelUri") String modelUri,
            @JsonProperty("tokenizerUri") String tokenizerUri,
            @JsonProperty("tokenizerType") String tokenizerType,
            @JsonProperty("tokenizerConfig") Map<String, String> tokenizerConfig,
            @JsonProperty("promptInputName") String promptInputName,
            @JsonProperty("responseOutputName") String responseOutputName,
            @JsonProperty("toolCallRequestOutputName") String toolCallRequestOutputName,
            @JsonProperty("toolCallResponseInputName") String toolCallResponseInputName,
            @JsonProperty("toolDefinitions") List<PipelineToolDefinition> toolDefinitions,
            @JsonProperty("toolChoice") ToolChoiceMode toolChoice,
            @JsonProperty("specificToolNameForCall") String specificToolNameForCall,
            @JsonProperty("toolCallOutputFormat") ToolCallOutputFormat toolCallOutputFormat,
            @JsonProperty("generationParameters") Map<String, Object> generationParameters,
            @JsonProperty("conversationContextName") String conversationContextName,
            // This 'parameters' map is for any other generic key-value pairs passed in JSON/YAML
            // that are not explicitly declared as fields above.
            @JsonProperty("parameters") Data additionalParametersFromJson) {

        this.name = name;
        this.type = Objects.requireNonNull(type, "StepConfig type cannot be null.");
        // runnerClassName can be null if the factory is responsible for providing it based on 'type'.
        // Or, it can be explicitly set.
        this.runnerClassName = runnerClassName;


        // Initialize internalParameters. If additionalParametersFromJson is null, start fresh.
        // Otherwise, use it as the base and then overlay the specific typed fields.
        this.internalParameters = (additionalParametersFromJson != null) ? additionalParametersFromJson : Data.empty();

        // Set specific fields and also ensure they are in internalParameters
        this.modelUri = modelUri;
        if (modelUri != null) this.internalParameters.put("modelUri", modelUri);

        this.tokenizerUri = tokenizerUri;
        if (tokenizerUri != null) this.internalParameters.put("tokenizerUri", tokenizerUri);

        this.tokenizerType = tokenizerType;
        if (tokenizerType != null) this.internalParameters.put("tokenizerType", tokenizerType);

        this.tokenizerConfig = tokenizerConfig != null ? tokenizerConfig : Collections.emptyMap();
        if (tokenizerConfig != null && !tokenizerConfig.isEmpty()) this.internalParameters.put("tokenizerConfig", this.tokenizerConfig);


        this.promptInputName = promptInputName != null ? promptInputName : "prompt";
        this.internalParameters.put("promptInputName", this.promptInputName);

        this.responseOutputName = responseOutputName != null ? responseOutputName : "llm_response";
        this.internalParameters.put("responseOutputName", this.responseOutputName);

        this.toolCallRequestOutputName = toolCallRequestOutputName != null ? toolCallRequestOutputName : "tool_call_request";
        this.internalParameters.put("toolCallRequestOutputName", this.toolCallRequestOutputName);

        this.toolCallResponseInputName = toolCallResponseInputName != null ? toolCallResponseInputName : "tool_call_response";
        this.internalParameters.put("toolCallResponseInputName", this.toolCallResponseInputName);

        this.toolDefinitions = toolDefinitions != null ? toolDefinitions : Collections.emptyList();
        if (toolDefinitions != null && !toolDefinitions.isEmpty()) this.internalParameters.put("toolDefinitions", this.toolDefinitions);

        this.toolChoice = toolChoice != null ? toolChoice : ToolChoiceMode.AUTO;
        this.internalParameters.put("toolChoice", this.toolChoice.name()); // Store enum as string

        this.specificToolNameForCall = specificToolNameForCall;
        if (specificToolNameForCall != null) this.internalParameters.put("specificToolNameForCall", specificToolNameForCall);

        this.toolCallOutputFormat = toolCallOutputFormat != null ? toolCallOutputFormat : ToolCallOutputFormat.JSON_MARKER_BASED;
        this.internalParameters.put("toolCallOutputFormat", this.toolCallOutputFormat.name()); // Store enum as string

        this.generationParameters = generationParameters != null ? generationParameters : Collections.emptyMap();
        // Flatten generation parameters into internalParameters with "gen." prefix
        // rather than putting a Map (which Data doesn't support as a value type).
        for (Map.Entry<String, Object> e : this.generationParameters.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String || v instanceof Number || v instanceof Boolean) {
                this.internalParameters.put("gen." + e.getKey(), v);
            }
        }

        this.conversationContextName = conversationContextName != null ? conversationContextName : "llm_conversation_context";
        this.internalParameters.put("conversationContextName", this.conversationContextName);

        // Ensure StepConfig's own 'name' and 'type' (if they are also expected in 'parameters' Data by some convention)
        // Note: 'name' is not part of StepConfig interface's getParameters(), but 'type' is sometimes implicitly there.
        // The @JsonProperty on the methods in the interface controls serialization.
        // 'type' and 'runnerClassName' are top-level in JSON.
        // 'parameters' is a nested object.
    }

    /**
     * Builder for more convenient programmatic construction.
     * This is a manual builder because Lombok's @SuperBuilder with inheritance
     * and specific constructor logic for parameter overlay can be tricky.
     */
    public static LLMStepConfigBuilder builder() {
        return new LLMStepConfigBuilder();
    }


    // Implementation of StepConfig interface methods

    @Override
    @JsonProperty("type")
    public String type() {
        return this.type;
    }

    @Override
    @JsonProperty("runnerClassName")
    public String runnerClassName() {
        // This can be null if the specific factory (e.g., DL4JLanguageModelStepRunnerFactory)
        // is solely responsible for knowing its runner class.
        // If this LLMStepConfig is ALWAYS tied to one runner, this should return that runner's class name.
        // For a generic LLMStepConfig used by multiple runner types (DL4J, SameDiff, Python),
        // the 'type' field is more important for factory lookup.
        return this.runnerClassName; // Return the one set at construction or null
    }

    @Override
    @JsonIgnore // This method is a convenience; actual parameters are in getParameters() for serialization
    public <T> T get(String key) {
        // Prioritize explicitly defined fields, then check internalParameters for others.
        // This makes the typed fields directly accessible via get("fieldName") too.
        switch (key) {
            case "modelUri": return (T) modelUri;
            case "tokenizerUri": return (T) tokenizerUri;
            case "tokenizerType": return (T) tokenizerType;
            case "tokenizerConfig": return (T) tokenizerConfig;
            case "promptInputName": return (T) promptInputName;
            case "responseOutputName": return (T) responseOutputName;
            case "toolCallRequestOutputName": return (T) toolCallRequestOutputName;
            case "toolCallResponseInputName": return (T) toolCallResponseInputName;
            case "toolDefinitions": return (T) toolDefinitions;
            case "toolChoice": return (T) toolChoice.name(); // Return as string for generic get
            case "specificToolNameForCall": return (T) specificToolNameForCall;
            case "toolCallOutputFormat": return (T) toolCallOutputFormat.name(); // Return as string
            case "generationParameters": return (T) generationParameters;
            case "conversationContextName": return (T) conversationContextName;
            default:
                return internalParameters.get(key);
        }
    }

    @Override
    @JsonIgnore // Convenience method
    public <T> T get(String key, T defaultValue) {
        T value = get(key);
        return (value != null) ? value : defaultValue;
    }

    @Override
    public StepConfig put(String key, Object value) {
        // Update specific fields if the key matches, otherwise store in internalParameters
        switch (key) {
            case "modelUri": this.modelUri = (String) value; break;
            case "tokenizerUri": this.tokenizerUri = (String) value; break;
            case "tokenizerType": this.tokenizerType = (String) value; break;
            case "tokenizerConfig":
                this.tokenizerConfig = value instanceof Map ? (Map<String, String>) value : Collections.emptyMap();
                break;
            case "promptInputName": this.promptInputName = (String) value; break;
            case "responseOutputName": this.responseOutputName = (String) value; break;
            case "toolCallRequestOutputName": this.toolCallRequestOutputName = (String) value; break;
            case "toolCallResponseInputName": this.toolCallResponseInputName = (String) value; break;
            case "toolDefinitions":
                this.toolDefinitions = value instanceof List ? (List<PipelineToolDefinition>) value : Collections.emptyList();
                break;
            case "toolChoice":
                this.toolChoice = value instanceof String ? ToolChoiceMode.valueOf((String) value) : (ToolChoiceMode) value;
                break;
            case "specificToolNameForCall": this.specificToolNameForCall = (String) value; break;
            case "toolCallOutputFormat":
                this.toolCallOutputFormat = value instanceof String ? ToolCallOutputFormat.valueOf((String)value) : (ToolCallOutputFormat) value;
                break;
            case "generationParameters":
                this.generationParameters = value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap();
                break;
            case "conversationContextName": this.conversationContextName = (String) value; break;
            default:
                // Fallback to internalParameters for keys not matching specific fields
                break; // Value will be put into internalParameters below
        }
        // Always update the internalParameters map as it's the source of truth for getParameters()
        internalParameters.put(key, value);
        return this;
    }


    @Override
    @JsonProperty("parameters") // This is what gets serialized as the "parameters" block
    public Data getParameters() {
        // Ensure internalParameters reflects the current state of all fields
        // This can be redundant if 'put' updates both field and internalParameters,
        // but good for consistency if fields were modified by other means (not recommended).
        // For this implementation, constructor and 'put' already keep internalParameters up-to-date.
        return this.internalParameters;
    }

    // --- Getter methods for specific fields (useful for direct typed access) ---
    // These are not part of StepConfig interface but good for usability of LLMStepConfig.
    // Lombok's @Getter could also be used on individual fields if preferred.
    public String getModelUri() { return modelUri; }
    public String getTokenizerUri() { return tokenizerUri; }
    public String getTokenizerType() { return tokenizerType; }
    public Map<String, String> getTokenizerConfig() { return Collections.unmodifiableMap(tokenizerConfig); }
    public String getPromptInputName() { return promptInputName; }
    public String getResponseOutputName() { return responseOutputName; }
    public String getToolCallRequestOutputName() { return toolCallRequestOutputName; }
    public String getToolCallResponseInputName() { return toolCallResponseInputName; }
    public List<PipelineToolDefinition> getToolDefinitions() { return Collections.unmodifiableList(toolDefinitions); }
    public ToolChoiceMode getToolChoice() { return toolChoice; }
    public String getSpecificToolNameForCall() { return specificToolNameForCall; }
    public ToolCallOutputFormat getToolCallOutputFormat() { return toolCallOutputFormat; }
    public Map<String, Object> getGenerationParameters() { return Collections.unmodifiableMap(generationParameters); }
    public String getConversationContextName() { return conversationContextName; }
    public String getName() { return name; }


    // Manual Builder class
    public static class LLMStepConfigBuilder {
        private String name;
        private String type;
        private String runnerClassName;
        private String modelUri;
        private String tokenizerUri;
        private String tokenizerType;
        private Map<String, String> tokenizerConfig = new HashMap<>();
        private String promptInputName = "prompt";
        private String responseOutputName = "llm_response";
        private String toolCallRequestOutputName = "tool_call_request";
        private String toolCallResponseInputName = "tool_call_response";
        private List<PipelineToolDefinition> toolDefinitions = new ArrayList<>();
        private ToolChoiceMode toolChoice = ToolChoiceMode.AUTO;
        private String specificToolNameForCall;
        private ToolCallOutputFormat toolCallOutputFormat = ToolCallOutputFormat.JSON_MARKER_BASED;
        private Map<String, Object> generationParameters = new HashMap<>();
        private String conversationContextName = "llm_conversation_context";
        private Data additionalParameters = Data.empty();

        public LLMStepConfigBuilder name(String name) { this.name = name; return this; }
        public LLMStepConfigBuilder type(String type) { this.type = type; return this; }
        public LLMStepConfigBuilder runnerClassName(String runnerClassName) { this.runnerClassName = runnerClassName; return this; }
        public LLMStepConfigBuilder modelUri(String modelUri) { this.modelUri = modelUri; return this; }
        public LLMStepConfigBuilder tokenizerUri(String tokenizerUri) { this.tokenizerUri = tokenizerUri; return this; }
        public LLMStepConfigBuilder tokenizerType(String tokenizerType) { this.tokenizerType = tokenizerType; return this; }

        public LLMStepConfigBuilder tokenizerConfigEntry(String key, String value) {
            if (this.tokenizerConfig == null) this.tokenizerConfig = new HashMap<>();
            this.tokenizerConfig.put(key, value); return this;
        }
        public LLMStepConfigBuilder tokenizerConfig(Map<String, String> tokenizerConfig) {
            this.tokenizerConfig = tokenizerConfig; return this;
        }

        public LLMStepConfigBuilder promptInputName(String promptInputName) { this.promptInputName = promptInputName; return this; }
        public LLMStepConfigBuilder responseOutputName(String responseOutputName) { this.responseOutputName = responseOutputName; return this; }
        public LLMStepConfigBuilder toolCallRequestOutputName(String toolCallRequestOutputName) { this.toolCallRequestOutputName = toolCallRequestOutputName; return this; }
        public LLMStepConfigBuilder toolCallResponseInputName(String toolCallResponseInputName) { this.toolCallResponseInputName = toolCallResponseInputName; return this; }

        public LLMStepConfigBuilder toolDefinition(PipelineToolDefinition toolDefinition) {
            if (this.toolDefinitions == null) this.toolDefinitions = new ArrayList<>();
            this.toolDefinitions.add(toolDefinition); return this;
        }
        public LLMStepConfigBuilder toolDefinitions(List<PipelineToolDefinition> toolDefinitions) {
            this.toolDefinitions = toolDefinitions; return this;
        }

        public LLMStepConfigBuilder toolChoice(ToolChoiceMode toolChoice) { this.toolChoice = toolChoice; return this; }
        public LLMStepConfigBuilder specificToolNameForCall(String specificToolNameForCall) { this.specificToolNameForCall = specificToolNameForCall; return this; }
        public LLMStepConfigBuilder toolCallOutputFormat(ToolCallOutputFormat toolCallOutputFormat) { this.toolCallOutputFormat = toolCallOutputFormat; return this; }

        public LLMStepConfigBuilder generationParameterEntry(String key, Object value) {
            if (this.generationParameters == null) this.generationParameters = new HashMap<>();
            this.generationParameters.put(key, value); return this;
        }
        public LLMStepConfigBuilder generationParameters(Map<String, Object> generationParameters) {
            this.generationParameters = generationParameters; return this;
        }
        public LLMStepConfigBuilder conversationContextName(String conversationContextName) { this.conversationContextName = conversationContextName; return this; }

        public LLMStepConfigBuilder additionalParameter(String key, Object value) {
            if(this.additionalParameters == null) this.additionalParameters = Data.empty();
            this.additionalParameters.put(key, value); return this;
        }
        public LLMStepConfigBuilder additionalParameters(Data additionalParameters) {
            this.additionalParameters = additionalParameters; return this;
        }


        public LLMStepConfig build() {
            return new LLMStepConfig(name, type, runnerClassName,
                    modelUri, tokenizerUri, tokenizerType, tokenizerConfig,
                    promptInputName, responseOutputName,
                    toolCallRequestOutputName, toolCallResponseInputName,
                    toolDefinitions, toolChoice, specificToolNameForCall, toolCallOutputFormat,
                    generationParameters, conversationContextName,
                    additionalParameters);
        }
    }
}