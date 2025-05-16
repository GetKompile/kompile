// Location: kompile-pipelines-framework/kompile-pipelines-framework-runtime/src/main/java/ai/kompile/pipelines/framework/runtime/tooling/
package ai.kompile.pipelines.framework.runtime.tooling;

import ai.kompile.pipelines.framework.api.Pipeline;
import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.PipelineStepRunnerFactory;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.context.Context;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.data.PipelineDataConstants; // For IS_TOOL_CALL_REQUEST_FLAG_KEY
import ai.kompile.pipelines.framework.api.data.PipelineToolCallRequest;
import ai.kompile.pipelines.framework.api.data.PipelineToolCallResponse;
import ai.kompile.pipelines.framework.api.data.ValueType;
import ai.kompile.pipelines.framework.api.llm.LLMStepConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
public class PipelineToolCallOrchestrator {

    private final Pipeline currentPipeline;
    private final Map<String, PipelineStepRunnerFactory> runnerFactories;
    private final ObjectMapper objectMapper; // For parsing arguments JSON string
    private final int maxToolCallingDepth;

    public PipelineToolCallOrchestrator(
            Pipeline currentPipeline,
            Map<String, PipelineStepRunnerFactory> runnerFactories,
            ObjectMapper objectMapper, // Pass ObjectMapper for consistency if needed elsewhere
            int maxToolCallingDepth) {
        this.currentPipeline = Objects.requireNonNull(currentPipeline, "Current Pipeline cannot be null.");
        this.runnerFactories = Objects.requireNonNull(runnerFactories, "Runner Factories map cannot be null.");
        this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper cannot be null.");
        this.maxToolCallingDepth = maxToolCallingDepth;
        if (maxToolCallingDepth <= 0) {
            throw new IllegalArgumentException("Max tool calling depth must be positive.");
        }
    }

    public Data executeLLMStepWithInternalToolLoop(
            PipelineStepRunner llmStepRunner,
            LLMStepConfig llmStepConfig,
            Data initialLlmInput,
            Context parentContext) throws Exception {

        Data currentLlmInput = initialLlmInput;
        Data llmOutput = null;
        int currentDepth = 0;

        String llmStepName = llmStepConfig.getName() != null ? llmStepConfig.getName() : "llmStep";

        while (currentDepth < maxToolCallingDepth) {
            Context llmTurnContext = parentContext.child(llmStepName + "-turn-" + currentDepth);
            llmOutput = llmStepRunner.exec(currentLlmInput, llmTurnContext);

            String toolRequestKey = llmStepConfig.getToolCallRequestOutputName();
            PipelineToolCallRequest toolCallRequest = null;

            if (toolRequestKey != null && !toolRequestKey.isEmpty() && llmOutput.has(toolRequestKey)) {
                if (llmOutput.type(toolRequestKey) == ValueType.OBJECT || llmOutput.type(toolRequestKey) == ValueType.DATA) {
                    // The Data.get(key) returns <T> T, so cast is needed.
                    Object rawRequest = llmOutput.get(toolRequestKey);
                    if (rawRequest instanceof PipelineToolCallRequest) {
                        toolCallRequest = (PipelineToolCallRequest) rawRequest;
                    } else if (rawRequest != null) {
                        log.warn("Orchestrator (Pipeline: '{}', LLM Step: '{}'): Value for tool request key '{}' is not of type PipelineToolCallRequest. Found type: {}",
                                currentPipeline.id(), llmStepName, toolRequestKey, rawRequest.getClass().getName());
                    }
                } else if (llmOutput.type(toolRequestKey) != null) { // Key exists but wrong type
                    log.warn("Orchestrator (Pipeline: '{}', LLM Step: '{}'): Value for tool request key '{}' is of type {} but expected OBJECT or DATA. Tool call will be ignored.",
                            currentPipeline.id(), llmStepName, toolRequestKey, llmOutput.type(toolRequestKey));
                }
            }

            if (toolCallRequest != null) {
                parentContext.profiler().startEvent("PipelineToolCallOrchestrator.handleToolCall:" + toolCallRequest.getName());
                log.info("Orchestrator (Pipeline: '{}', LLM Step: '{}'): Handling tool call request ID '{}' for tool '{}'",
                        currentPipeline.id(), llmStepName, toolCallRequest.getId(), toolCallRequest.getName());

                StepConfig toolStepConfig = findToolStepConfigInPipeline(toolCallRequest.getName());
                PipelineToolCallResponse toolCallResponse;

                if (toolStepConfig == null) {
                    String errorMsg = "Tool '" + toolCallRequest.getName() + "' requested by LLM step '" + llmStepName +
                            "' but no corresponding step definition found in pipeline '" + currentPipeline.id() + "'.";
                    log.error(errorMsg);
                    String errorContent = objectMapper.writeValueAsString(Map.of("error", errorMsg, "details", "Tool configuration not found."));
                    toolCallResponse = new PipelineToolCallResponse( // Using constructor directly
                            toolCallRequest.getId(),
                            toolCallRequest.getName(),
                            errorContent,
                            true); // isError = true
                } else {
                    try {
                        toolCallResponse = executeToolStep(toolStepConfig, toolCallRequest, parentContext.child(llmStepName + "-tool-" + toolCallRequest.getName()));
                    } catch (Exception e) {
                        log.error("Orchestrator (Pipeline: '{}', LLM Step: '{}'): Error executing tool '{}': {}",
                                currentPipeline.id(), llmStepName, toolCallRequest.getName(), e.getMessage(), e);
                        String errorContent = objectMapper.writeValueAsString(Map.of("error", "Tool execution failed: " + e.getMessage()));
                        toolCallResponse = new PipelineToolCallResponse(
                                toolCallRequest.getId(),
                                toolCallRequest.getName(),
                                errorContent,
                                true); // isError = true
                    }
                }
                parentContext.profiler().stopEvent();

                currentLlmInput = Data.empty();
                Object conversationContext = llmOutput.get(llmStepConfig.getConversationContextName());
                if (conversationContext != null) {
                    // Ensure conversationContext is a List<String> before putting
                    if (conversationContext instanceof List) {
                        currentLlmInput.putList(llmStepConfig.getConversationContextName(), (List<?>) conversationContext, ValueType.STRING);
                    } else {
                        log.warn("Orchestrator (Pipeline: '{}', LLM Step: '{}'): Conversation context from LLM output is not a List. Key: {}",
                                currentPipeline.id(), llmStepName, llmStepConfig.getConversationContextName());
                    }
                } else {
                    Object prevConvContext = initialLlmInput.get(llmStepConfig.getConversationContextName());
                    if (prevConvContext instanceof List) {
                        currentLlmInput.putList(llmStepConfig.getConversationContextName(), (List<?>) prevConvContext, ValueType.STRING);
                    }
                }
                currentLlmInput.put(llmStepConfig.getToolCallResponseInputName(), toolCallResponse); // put(String, Object)
                currentDepth++;
            } else {
                // No tool call requested by the LLM in this turn, or request was invalid.
                // Check if the LLM step itself set the pipeline-level pause flag.
                if (llmOutput.getBoolean(PipelineDataConstants.IS_TOOL_CALL_REQUEST_FLAG_KEY, false)) {
                    log.info("Orchestrator (Pipeline: '{}', LLM Step: '{}'): LLM step output signals a pipeline-level pause. Propagating pause.",
                            currentPipeline.id(), llmStepName);
                }
                return llmOutput; // Return LLM's output (final response or explicit pipeline pause signal)
            }
        }

        if (currentDepth >= maxToolCallingDepth) {
            log.warn("Orchestrator (Pipeline: '{}', LLM Step: '{}'): Max tool calling depth ({}) reached. Returning last LLM output (which might still be a tool request if LLM is stuck).",
                    currentPipeline.id(), llmStepName, maxToolCallingDepth);
        }
        return llmOutput; // Return the last output from the LLM after reaching max depth
    }

    private StepConfig findToolStepConfigInPipeline(String toolName) {
        if (currentPipeline.getSteps() == null) return null;
        for (StepConfig stepCfg : currentPipeline.getSteps()) {
            if (toolName.equals(stepCfg.runnerClassName())) { // Assumes StepConfig has getName() for its instance name
                return stepCfg;
            }
        }
        log.warn("Tool '{}' not found as a named step in pipeline '{}'. Available step names: {}",
                toolName, currentPipeline.id(),
                currentPipeline.getSteps().stream().map(sc -> sc.runnerClassName() != null ? sc.runnerClassName() : "(unnamed)").collect(Collectors.toList()));
        return null;
    }

    private PipelineToolCallResponse executeToolStep(
            StepConfig toolStepConfig,
            PipelineToolCallRequest toolCallRequest,
            Context toolContext) throws Exception {

        PipelineStepRunner toolRunner = null;
        String toolStepName = toolStepConfig.runnerClassName() != null ? toolStepConfig.runnerClassName() : toolCallRequest.getName();
        toolContext.profiler().startEvent("PipelineToolCallOrchestrator.executeToolStep:" + toolStepName);

        try {
            PipelineStepRunnerFactory factory = runnerFactories.get(toolStepConfig.runnerClassName());
            if (factory == null) {
                throw new IllegalStateException("No PipelineStepRunnerFactory found for tool runner class: " + toolStepConfig.runnerClassName());
            }
            toolRunner = factory.create();
            // Tool runner init context should be a child of the toolContext passed for execution
            toolRunner.init(toolStepConfig, toolContext.child(toolStepName + ".initContext"));

            if (!toolRunner.isInitialized()) {
                throw new IllegalStateException("Tool runner for '" + toolStepName + "' failed to initialize.");
            }

            Data toolInput = Data.empty();
            // toolCallRequest.getArguments() returns a JSON String. Parse it.
            String argumentsJson = toolCallRequest.getArguments();
            if (argumentsJson != null && !argumentsJson.isEmpty()) {
                try {
                    Map<String, Object> argumentsMap = objectMapper.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {});
                    for (Map.Entry<String, Object> entry : argumentsMap.entrySet()) {
                        toolInput.put(entry.getKey(), entry.getValue()); // Uses Data.put(String, Object)
                    }
                } catch (IOException e) {
                    log.error("Failed to parse JSON arguments for tool '{}': {}", toolCallRequest.getName(), argumentsJson, e);
                    throw new IllegalArgumentException("Invalid JSON arguments for tool " + toolCallRequest.getName(), e);
                }
            }

            log.info("Orchestrator: Executing tool '{}' (Step Type: '{}') with parsed input: {}",
                    toolStepName, toolStepConfig.type(), toolInput.toMap());

            Data toolOutputRaw = toolRunner.exec(toolInput, toolContext); // Tool gets its own exec context
            log.info("Orchestrator: Tool '{}' executed. Raw output keys: {}", toolStepName, toolOutputRaw.keySet());

            String responseContent;
            // Serialize the entire toolOutputRaw Data object to JSON string for the content.
            // This provides the LLM with all outputs from the tool step.
            try {
                responseContent = toolOutputRaw.toJson();
            } catch (Exception e) {
                log.error("Failed to serialize tool output Data to JSON for tool '{}': {}", toolStepName, e.getMessage(), e);
                responseContent = objectMapper.writeValueAsString(Map.of("error", "Failed to serialize tool output: " + e.getMessage()));
            }

            return new PipelineToolCallResponse( // Using constructor directly
                    toolCallRequest.getId(),
                    toolCallRequest.getName(),
                    responseContent,
                    false); // isError = false for successful execution path
        } finally {
            if (toolRunner != null) {
                try {
                    toolRunner.close();
                } catch (Exception e) {
                    log.error("Orchestrator: Error closing tool runner for tool: {}", toolStepName, e);
                }
            }
            toolContext.profiler().stopEvent();
        }
    }
}
