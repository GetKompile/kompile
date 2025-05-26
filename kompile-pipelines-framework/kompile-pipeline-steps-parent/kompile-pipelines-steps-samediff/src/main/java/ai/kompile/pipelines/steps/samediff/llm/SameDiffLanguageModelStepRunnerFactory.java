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

// Location: kompile-pipelines-framework/kompile-pipeline-steps-parent/kompile-pipelines-steps-samediff/src/main/java/ai/kompile/pipelines/steps/samediff/llm/
package ai.kompile.pipelines.steps.samediff.llm;

import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.PipelineStepRunnerFactory;
import ai.kompile.pipelines.framework.api.llm.LLMStepConfig; // Centralized LLM config
import ai.kompile.pipelines.framework.api.configschema.ParameterSchema;
import ai.kompile.pipelines.framework.api.configschema.StepSchema;
import ai.kompile.pipelines.framework.api.configschema.StepSchemaProvider;
import ai.kompile.pipelines.framework.api.data.ValueType;
import ai.kompile.pipelines.framework.api.data.PipelineToolCallRequest; // For output schema
import ai.kompile.pipelines.framework.api.data.PipelineToolDefinition; // For toolDefinitions subType

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

public class SameDiffLanguageModelStepRunnerFactory implements PipelineStepRunnerFactory, StepSchemaProvider {

    // Matches uniqueTypeName() in SameDiffLanguageModelStepRunner
    public static final String STEP_TYPE_NAME = "SAMEDIFF_LANGUAGE_MODEL";

    @Override
    public PipelineStepRunner create() {
        return new SameDiffLanguageModelStepRunner();
    }

    @Override
    public String stepTypeName() {
        return STEP_TYPE_NAME;
    }

    @Override
    public String getRunnerType() {
        return STEP_TYPE_NAME;
    }

    @Override
    public StepSchema getSchema() {
        return StepSchema.builder()
                .name(stepTypeName())
                .description("Runs a SameDiff-based Language Model for text generation and tool calling, using LLMStepConfig.")
                .configClass(LLMStepConfig.class.getName())
                .parameters(Arrays.asList(
                        ParameterSchema.builder().name("name").type(ValueType.STRING).description("Name of this step instance in the pipeline definition.").required(false).build(),
                        ParameterSchema.builder().name("runnerClassName").type(ValueType.STRING).description("(Optional) Explicit runner class name if overriding factory default.").required(false).build(),
                        ParameterSchema.builder().name("modelUri").type(ValueType.STRING).description("URI to the SameDiff language model file (.fb or .zip).").required(true).build(),
                        ParameterSchema.builder().name("tokenizerUri").type(ValueType.STRING).description("URI to the tokenizer vocabulary file (e.g., for WordPiece).").required(true).build(),
                        ParameterSchema.builder().name("tokenizerType").type(ValueType.STRING).description("(Optional) Type of tokenizer (e.g., 'wordpiece'). Defaults to 'wordpiece'.").defaultValue("wordpiece").required(false).build(),
                        ParameterSchema.builder().name("tokenizerConfig").type(ValueType.OBJECT).description("(Optional) Map of configurations for the tokenizer (e.g., unkToken, subwordPrefix).").subTypeClassName(java.util.Map.class.getName()).required(false).build(),
                        ParameterSchema.builder().name("promptInputName").type(ValueType.STRING).description("Name of the input Data variable for the prompt (default: 'prompt').").defaultValue("prompt").required(false).build(),
                        ParameterSchema.builder().name("responseOutputName").type(ValueType.STRING).description("Name of the output Data variable for the LLM response (default: 'llm_response').").defaultValue("llm_response").required(false).build(),
                        ParameterSchema.builder().name("toolCallRequestOutputName").type(ValueType.STRING).description("Name of the output Data variable for tool call requests (default: 'tool_call_request').").defaultValue("tool_call_request").required(false).build(),
                        ParameterSchema.builder().name("toolCallResponseInputName").type(ValueType.STRING).description("Name of the input Data variable for tool call responses (default: 'tool_call_response').").defaultValue("tool_call_response").required(false).build(),
                        ParameterSchema.builder().name("conversationContextName").type(ValueType.STRING).description("Name of the input/output Data variable for conversation context/history (default: 'llm_conversation_context').").defaultValue("llm_conversation_context").required(false).build(),
                        ParameterSchema.builder().name("toolDefinitions").type(ValueType.LIST).description("List of PipelineToolDefinition objects available to the LLM.").subTypeClassName(PipelineToolDefinition.class.getName()).required(false).build(),
                        ParameterSchema.builder().name("toolChoice").type(ValueType.STRING).description("Tool choice mode: AUTO, REQUIRED, NONE, SPECIFIC_TOOL (default: AUTO).").defaultValue(LLMStepConfig.ToolChoiceMode.AUTO.name()).required(false).build(),
                        ParameterSchema.builder().name("specificToolNameForCall").type(ValueType.STRING).description("Name of the specific tool to call if toolChoice is SPECIFIC_TOOL.").required(false).build(),
                        ParameterSchema.builder().name("toolCallOutputFormat").type(ValueType.STRING).description("Expected LLM output format for tool calls: JSON_MARKER_BASED, OPENAI_JSON, etc. (default: JSON_MARKER_BASED).").defaultValue(LLMStepConfig.ToolCallOutputFormat.JSON_MARKER_BASED.name()).required(false).build(),
                        ParameterSchema.builder().name("generationParameters").type(ValueType.OBJECT)
                                .description("Map of generation parameters (e.g., temperature, maxNewTokens, topK, inputIdsPlaceholderName, logitsOutputName for SameDiff).")
                                .subTypeClassName(java.util.Map.class.getName()).required(false).build()
                ))
                .inputs(Collections.singletonList(
                        ParameterSchema.builder().name("prompt").type(ValueType.STRING).description("Input prompt for the LLM (if 'promptInputName' is 'prompt').").required(true).build()
                ))
                .outputs(Arrays.asList(
                        ParameterSchema.builder().name("llm_response").type(ValueType.STRING).description("Generated text response from the LLM (if 'responseOutputName' is 'llm_response').").required(false).build(),
                        ParameterSchema.builder().name("tool_call_request").type(ValueType.valueOf(PipelineToolCallRequest.class.getName())).description("Request for a tool call, if made by the LLM (if 'toolCallRequestOutputName' is 'tool_call_request').").required(false).build()
                ))
                .build();
    }

    @Override
    public Optional<StepSchema> getSchema(String runnerClassName) {
        return Optional.empty();
    }
}