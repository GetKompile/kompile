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

// Path: kompile-app-llm-pipeline/src/main/java/ai/kompile/app/llm/pipeline/KompilePipelineLanguageModelImpl.java
package ai.kompile.app.llm.pipeline;

import ai.kompile.core.llm.LanguageModel;
import ai.kompile.pipelines.framework.api.Pipeline;
import ai.kompile.pipelines.framework.api.PipelineExecutor;
import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.context.Context;
import ai.kompile.pipelines.framework.api.context.Metrics;
import ai.kompile.pipelines.framework.api.context.Profiler;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.data.DataFactory;
import ai.kompile.pipelines.framework.api.data.ValueType;
import ai.kompile.pipelines.framework.api.data.PipelineDataConstants;
import ai.kompile.pipelines.framework.api.data.PipelineToolCallRequest;
import ai.kompile.pipelines.framework.api.data.PipelineToolCallResponse;
import ai.kompile.pipelines.framework.api.data.PipelineToolDefinition;
import ai.kompile.pipelines.framework.core.config.GenericStepConfig;
import ai.kompile.pipelines.framework.core.context.DefaultContext;
import ai.kompile.pipelines.framework.core.context.NoOpProfiler;
import ai.kompile.pipelines.framework.runtime.pipeline.SequencePipeline;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
// ToolResponseMessage is not directly used as input from Spring AI layer in this internal resolution model,
// but PipelineToolCallResponse (our POJO) is used internally.
// import org.springframework.ai.chat.messages.ToolResponseMessage;
// import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
// ToolCall is not used in the output AssistantMessage in this internal resolution model.
// import org.springframework.ai.model.function.ToolCall;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service("kompilePipelineLanguageModel")
@ConditionalOnProperty(prefix = "kompile.langmodel.pipeline", name = "enabled", havingValue = "true", matchIfMissing = false)
public class KompilePipelineLanguageModelImpl implements LanguageModel {

    private static final Logger logger = LoggerFactory.getLogger(KompilePipelineLanguageModelImpl.class);

    private final DataFactory dataFactory;
    private final ObjectMapper objectMapper;
    private final KompilePipelineProviderService kompilePipelineProviderService;
    private final Metrics metrics;
    private final Profiler profiler;
    // This map will hold the executor for the main pipeline if it internally loops for tool calls.
    private final Map<String, PipelineExecutor> activeMainPipelineExecutors = new ConcurrentHashMap<>();

    private final String promptInputName = "prompt";
    // private final String contextDocsInputName = "context_docs"; // Retained if needed for other inputs
    private final String responseOutputName = "response_text";
    private final String conversationIdKeyForContextStorage = "kompile_llm_conversation_id"; // For ThreadLocal workaround

    private static final int MAX_TOOL_ITERATIONS = 5; // Safeguard against infinite tool call loops

    @Autowired
    public KompilePipelineLanguageModelImpl(
            DataFactory dataFactory,
            KompilePipelineProviderService kompilePipelineProviderService,
            ObjectMapper objectMapper,
            Metrics metrics,
            Optional<Profiler> profilerOpt) {
        this.dataFactory = dataFactory;
        this.objectMapper = objectMapper;
        this.kompilePipelineProviderService = kompilePipelineProviderService;
        this.metrics = metrics;
        this.profiler = profilerOpt.orElse(NoOpProfiler.INSTANCE);
        logger.info("KompilePipelineLanguageModelImpl initialized for internal tool resolution.");
    }

    @Override
    public String generateResponse(String userQuery, List<String> contextDokumente) {
        logger.debug("generateResponse (simple string) called with query: '{}'", userQuery);
        // This simple method will now also fully resolve tools internally.
        List<Message> conversationHistory = new ArrayList<>();
        if (contextDokumente != null && !contextDokumente.isEmpty()) {
            String combinedContext = String.join("\n---\n", contextDokumente);
            // Represent context docs as a single prior user message for simplicity
            conversationHistory.add(new UserMessage(combinedContext));
        }

        // A new executionId will be generated by orchestratePipelineExecution if null is passed.
        ChatResponse chatResponse = orchestratePipelineExecution(new UserMessage(userQuery), conversationHistory, null, true);

        if (chatResponse != null && chatResponse.getResult() != null) {
            // Since tools are resolved internally, the result should always be textual content.
            return chatResponse.getResult().getOutput().getText();
        }
        logger.error("generateResponse: Failed to get a final textual response from orchestration for query: {}", userQuery);
        return "Error: Could not generate response from Kompile pipeline.";
    }

    @Override
    public ChatResponse generateResponseWithPotentialToolCalls(String userQuery, List<String> context) {
        logger.debug("generateResponseWithPotentialToolCalls (ChatResponse interface) called with query: '{}'", userQuery);

        // This adapter method is now simpler as internal tool resolution means it doesn't need to
        // handle incoming Spring AI ToolResponseMessages via string markers.
        // The `executionId` from ThreadLocal is mainly for maintaining the main pipeline's executor
        // if the *main pipeline itself* were to be resumable across external calls for reasons other than tools
        // (which is not the current primary design focus for this method when tools are resolved internally).
        // For self-contained internal tool resolution per call, a fresh executionId per call is cleaner.

        String executionId = UUID.randomUUID().toString(); // Fresh execution for each call to this method
        ThreadLocalStorage.set(this.conversationIdKeyForContextStorage, executionId); // Store for logging/internal consistency if needed

        List<Message> conversationHistoryForOrchestrator = new ArrayList<>();
        if (context != null) {
            for (String ctxDoc : context) {
                conversationHistoryForOrchestrator.add(0, new UserMessage(ctxDoc));
            }
        }
        // The current userQuery is the primary message for this turn.
        Message currentInputMessageForOrchestrator = new UserMessage(userQuery);

        // Add current message to the history that will be passed to the pipeline
        // (pipeline might want to see the immediate prompt as part of its history view)
        List<Message> fullHistoryForPipeline = new ArrayList<>(conversationHistoryForOrchestrator);
        fullHistoryForPipeline.add(currentInputMessageForOrchestrator);


        return orchestratePipelineExecution(currentInputMessageForOrchestrator, fullHistoryForPipeline, executionId, false);
    }

    private ChatResponse orchestratePipelineExecution(
            Message currentMessageForMainPipeline,
            List<Message> fullConversationHistoryForMainPipeline, // Includes currentMessageForMainPipeline
            String executionId,
            boolean isSimpleGenerateCallPath) { // True if called from generateResponse(String, List)

        String currentTurnExecutionId = (executionId != null) ? executionId : UUID.randomUUID().toString();
        logger.info("Orchestrating main pipeline for execution ID: {}, current message type: {}",
                currentTurnExecutionId, currentMessageForMainPipeline.getMessageType());

        String mainPipelineId = kompilePipelineProviderService.getDefaultPipelineId();
        if (mainPipelineId == null) {
            return handlePipelineError(currentTurnExecutionId, null, "No default main pipeline configured.", null, isSimpleGenerateCallPath);
        }
        Optional<Pipeline> pipelineOpt = kompilePipelineProviderService.getPipelineByName(mainPipelineId);
        if (pipelineOpt.isEmpty()) {
            return handlePipelineError(currentTurnExecutionId, null, "Default main pipeline '" + mainPipelineId + "' not found.", null, isSimpleGenerateCallPath);
        }
        Pipeline mainPipeline = pipelineOpt.get();

        PipelineExecutor mainPipelineExecutor;
        try {
            // Get or create executor for the main pipeline for this turn.
            // Since tools are resolved internally within one external call, this executor is for this single "session".
            mainPipelineExecutor = activeMainPipelineExecutors.computeIfAbsent(currentTurnExecutionId, eid -> {
                try {
                    logger.debug("Execution ID '{}': Creating new executor for main pipeline '{}'.", eid, mainPipeline.id());
                    return mainPipeline.createExecutor();
                } catch (Exception e) {
                    throw new RuntimeException("Executor creation failed for main pipeline " + mainPipeline.id() + ": " + e.getMessage(), e);
                }
            });
        } catch (RuntimeException e) {
            return handlePipelineError(currentTurnExecutionId, null, "Main pipeline executor creation failed: " + e.getMessage(), e, isSimpleGenerateCallPath);
        }

        Data currentPipelineInput = dataFactory.empty();
        // Build originalInput from the current turn's prompt so pipeline steps have access
        // to the unmodified user input via context.getOriginalInput().
        Data originalInput = dataFactory.empty();
        if (currentMessageForMainPipeline != null && currentMessageForMainPipeline.getText() != null) {
            originalInput.put(this.promptInputName, currentMessageForMainPipeline.getText());
        }
        Context pipelineContext = new DefaultContext(
                originalInput,
                currentTurnExecutionId,
                "ctx-" + currentTurnExecutionId + "-" + System.currentTimeMillis(),
                null, this.metrics, this.profiler);

        // Initial input for the main pipeline
        currentPipelineInput.put(this.promptInputName, currentMessageForMainPipeline.getText());
        try {
            currentPipelineInput.put("conversation_history_json", objectMapper.writeValueAsString(fullConversationHistoryForMainPipeline));
        } catch (JsonProcessingException e) {
            logger.warn("Execution ID '{}': Failed to serialize conversation history: {}", currentTurnExecutionId, e.getMessage());
            currentPipelineInput.put("conversation_history_json", "[]");
        }
        List<PipelineToolDefinition> availableTools = kompilePipelineProviderService.getAvailableToolDefinitions();
        if (!availableTools.isEmpty()) {
            currentPipelineInput.putList(PipelineDataConstants.AVAILABLE_TOOLS_KEY, availableTools, ValueType.DATA);
        }

        Data pipelineOutput = null;
        boolean isResumeFlowForMainPipeline = false;
        int toolIterationCount = 0;

        try {
            while (toolIterationCount < MAX_TOOL_ITERATIONS) {
                if (isResumeFlowForMainPipeline) {
                    logger.debug("Execution ID '{}': Calling mainPipelineExecutor.resume() with updated input.", currentTurnExecutionId);
                    pipelineOutput = mainPipelineExecutor.resume(pipelineContext, currentPipelineInput);
                } else {
                    logger.debug("Execution ID '{}': Calling mainPipelineExecutor.exec() with initial/current input.", currentTurnExecutionId);
                    pipelineOutput = mainPipelineExecutor.exec(currentPipelineInput, pipelineContext); // Note: exec(Context, Data)
                }

                if (pipelineOutput.getBoolean(PipelineDataConstants.IS_TOOL_CALL_REQUEST_FLAG_KEY, false)) {
                    logger.info("Execution ID '{}': Main pipeline requests internal tool call(s). Iteration: {}", currentTurnExecutionId, toolIterationCount + 1);
                    List<Object> rawRequests = pipelineOutput.getList(PipelineDataConstants.TOOL_CALL_REQUESTS_KEY, ValueType.DATA);
                    List<PipelineToolCallRequest> kompileToolRequests = castList(rawRequests, PipelineToolCallRequest.class);

                    if (kompileToolRequests == null || kompileToolRequests.isEmpty()) {
                        logger.warn("Execution ID '{}': Tool call flag set, but no Kompile tool requests found. Breaking loop.", currentTurnExecutionId);
                        break; // Exit loop, treat current pipelineOutput as final
                    }

                    List<PipelineToolCallResponse> internalToolResponses = new ArrayList<>();
                    for (PipelineToolCallRequest toolRequest : kompileToolRequests) {
                        logger.debug("Execution ID '{}': Processing internal tool request: ID='{}', Name='{}'",
                                currentTurnExecutionId, toolRequest.getId(), toolRequest.getName());
                        PipelineToolCallResponse toolResponse = executeInternalTool(toolRequest, pipelineContext.child("tool-" + toolRequest.getName()));
                        internalToolResponses.add(toolResponse);
                    }

                    // Prepare input for the next iteration of the main pipeline (resume)
                    currentPipelineInput = dataFactory.empty(); // Reset for resume, or merge carefully
                    currentPipelineInput.put(this.promptInputName, currentMessageForMainPipeline.getText()); // Maintain original prompt context
                    currentPipelineInput.put("conversation_history_json", toJsonQuietly(fullConversationHistoryForMainPipeline));
                    if (!availableTools.isEmpty()) {
                        currentPipelineInput.putList(PipelineDataConstants.AVAILABLE_TOOLS_KEY, availableTools, ValueType.DATA);
                    }
                    currentPipelineInput.putList(PipelineDataConstants.TOOL_CALL_RESPONSES_KEY, internalToolResponses, ValueType.DATA);
                    isResumeFlowForMainPipeline = true;
                    toolIterationCount++;
                } else {
                    // No tool call request, this is the final output from the main pipeline for this turn
                    logger.info("Execution ID '{}': Main pipeline produced final response after {} tool iterations.", currentTurnExecutionId, toolIterationCount);
                    break; // Exit loop
                }
            } // End of while loop for tool iterations

            if (toolIterationCount >= MAX_TOOL_ITERATIONS) {
                logger.warn("Execution ID '{}': Reached max tool iterations ({}). Returning last pipeline output.", currentTurnExecutionId, MAX_TOOL_ITERATIONS);
                // pipelineOutput would be the one that requested the (MAX_TOOL_ITERATIONS+1)th tool call
                // We should probably return an error or a message indicating this.
                // For now, we fall through and it will be processed as a final response.
                // A better approach might be to create an error message here.
                if (pipelineOutput != null && pipelineOutput.getBoolean(PipelineDataConstants.IS_TOOL_CALL_REQUEST_FLAG_KEY, false)) {
                    // If it was still asking for tools, convert to an error/informative message
                    String lastToolRequests = toJsonQuietly(pipelineOutput.getList(PipelineDataConstants.TOOL_CALL_REQUESTS_KEY, ValueType.DATA));
                    dataFactory.empty().put(this.responseOutputName, "Max tool iterations reached. Last tool requests were: " + lastToolRequests);
                }
            }

        } catch (Exception e) {
            return handlePipelineError(currentTurnExecutionId, mainPipelineExecutor, "Main pipeline execution loop error: " + e.getMessage(), e, isSimpleGenerateCallPath);
        } finally {
            // Cleanup the main pipeline executor for this turn, as its execution (including internal loops) is complete.
            cleanupExecutor(currentTurnExecutionId, mainPipelineExecutor, true); // Force cleanup as this call is done
        }

        if (pipelineOutput == null) { // Should not happen if loop executed at least once
            logger.error("Execution ID '{}': Pipeline output is null after processing loop.", currentTurnExecutionId);
            return errorResponse("Pipeline produced null output.");
        }

        String responseText = pipelineOutput.getString(this.responseOutputName, "");
        if (responseText.isEmpty() && pipelineOutput.size() > 0 &&
                !pipelineOutput.getBoolean(PipelineDataConstants.IS_TOOL_CALL_REQUEST_FLAG_KEY, false)) { // Ensure it's not an unhandled tool request
            logger.warn("Execution ID '{}': Final pipeline output has no '{}' field. Using full JSON output as string.",
                    currentTurnExecutionId, this.responseOutputName);
            responseText = toJsonQuietly(pipelineOutput);
        }

        AssistantMessage finalAssistantMessage = new AssistantMessage(responseText);
        Generation generation = new Generation(finalAssistantMessage, ChatGenerationMetadata.NULL);
        return new ChatResponse(List.of(generation));
    }

    private PipelineToolCallResponse executeInternalTool(PipelineToolCallRequest toolRequest, Context toolContext) {
        String toolPipelineId = toolRequest.getName(); // Assuming tool name directly maps to a pipeline ID
        logger.info("Executing internal tool (pipeline): '{}' with ID '{}'", toolPipelineId, toolRequest.getId());

        Optional<Pipeline> toolPipelineOpt = kompilePipelineProviderService.getPipelineByName(toolPipelineId);
        if (toolPipelineOpt.isEmpty()) {
            logger.error("Internal tool pipeline '{}' not found.", toolPipelineId);
            return new PipelineToolCallResponse(toolRequest.getId(), toolRequest.getName(),
                    "{\"error\":\"Tool " + toolPipelineId + " not found.\"}", true);
        }
        Pipeline toolPipeline = toolPipelineOpt.get();
        PipelineExecutor toolExecutor = null;
        try {
            toolExecutor = toolPipeline.createExecutor();
            Data toolInput = dataFactory.empty();

            // Parse arguments from JSON string in toolRequest and put them into Data for the tool pipeline
            if (toolRequest.getArguments() != null && !toolRequest.getArguments().isEmpty()) {
                try {
                    Map<String, Object> argMap = objectMapper.readValue(toolRequest.getArguments(), new TypeReference<Map<String, Object>>() {});
                    for (Map.Entry<String, Object> entry : argMap.entrySet()) {
                        toolInput.put(entry.getKey(), entry.getValue()); // Assumes Data.put can handle various object types
                    }
                } catch (JsonProcessingException e) {
                    logger.error("Failed to parse arguments for tool '{}': {}", toolPipelineId, toolRequest.getArguments(), e);
                    return new PipelineToolCallResponse(toolRequest.getId(), toolRequest.getName(),
                            "{\"error\":\"Invalid arguments format for tool " + toolPipelineId + ": " + e.getMessage() + "\"}", true);
                }
            }

            Data toolOutputData = toolExecutor.exec(toolInput, toolContext); // Use the passed toolContext

            // The output of the tool pipeline (toolOutputData) needs to be converted to a string for PipelineToolCallResponse.content
            String toolResponseContent = toJsonQuietly(toolOutputData);

            return new PipelineToolCallResponse(toolRequest.getId(), toolRequest.getName(), toolResponseContent, false);

        } catch (Exception e) {
            logger.error("Error executing internal tool pipeline '{}': {}", toolPipelineId, e.getMessage(), e);
            return new PipelineToolCallResponse(toolRequest.getId(), toolRequest.getName(),
                    "{\"error\":\"Exception during " + toolPipelineId + " execution: " + e.getMessage() + "\"}", true);
        } finally {
            if (toolExecutor != null) {
                try {
                    toolExecutor.close();
                } catch (Exception e) {
                    logger.warn("Error closing executor for internal tool pipeline '{}': {}", toolPipelineId, e.getMessage(), e);
                }
            }
        }
    }


    private boolean determineErrorStatusFromToolContent(String content) {
        if (content == null || content.trim().isEmpty()) return false;
        try {
            Map<String, Object> contentMap = objectMapper.readValue(content, new TypeReference<Map<String, Object>>() {});
            return contentMap.containsKey("error") || (contentMap.containsKey("is_error") && Boolean.parseBoolean(String.valueOf(contentMap.get("is_error"))));
        } catch (JsonProcessingException e) {
            String lowerContent = content.toLowerCase();
            return lowerContent.startsWith("error:") || lowerContent.contains("exception:");
        }
    }

    private ChatResponse handlePipelineError(String executionId, PipelineExecutor executor, String errorMessage, Throwable exception, boolean isSimpleGenerateCall) {
        logger.error("Execution ID '{}': Pipeline error - {}", executionId, errorMessage, exception);
        // Force cleanup for the main executor associated with this executionId if an error occurs in its loop
        cleanupExecutor(executionId, activeMainPipelineExecutors.get(executionId), true); // true to force cleanup
        return errorResponse(errorMessage);
    }

    private void cleanupExecutor(String executionId, PipelineExecutor executor, boolean forceRemove) {
        // This cleanup is now primarily for the main pipeline executor for a given external call.
        // Tool executors are created and closed within executeInternalTool.
        if (forceRemove) {
            PipelineExecutor removedExecutor = activeMainPipelineExecutors.remove(executionId);
            if (removedExecutor != null) {
                try {
                    logger.debug("Execution ID '{}': Force closing main pipeline executor.", executionId);
                    removedExecutor.close();
                } catch (Exception e) {
                    logger.warn("Execution ID '{}': Error force closing main pipeline executor.", executionId, e);
                }
            } else if (executor != null) { // If it wasn't in the map but we have a reference
                try {
                    logger.debug("Execution ID '{}': Force closing provided main pipeline executor directly.", executionId);
                    executor.close();
                } catch (Exception e) {
                    logger.warn("Execution ID '{}': Error force closing provided main pipeline executor directly.", executionId, e);
                }
            }
        }
        // Clear ThreadLocal if this execution ID matches, as the top-level call is ending.
        if (Objects.equals(executionId, ThreadLocalStorage.get(this.conversationIdKeyForContextStorage))) {
            ThreadLocalStorage.clearKey(this.conversationIdKeyForContextStorage);
            logger.debug("Cleared ThreadLocal for execution ID '{}' as orchestratePipelineExecution concludes.", executionId);
        }
    }

    private <T> List<T> castList(List<Object> rawList, Class<T> targetType) {
        if (rawList == null) return Collections.emptyList();
        return rawList.stream()
                .filter(Objects::nonNull)
                .map(obj -> {
                    try {
                        return objectMapper.convertValue(obj, targetType);
                    } catch (IllegalArgumentException e) {
                        logger.error("Failed to cast object of type {} to {}. Object: {}",
                                obj.getClass().getName(), targetType.getSimpleName(), toJsonQuietly(obj), e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private String toJsonQuietly(Object obj) {
        if (obj == null) return "null";
        try {
            if (obj instanceof Data) return ((Data) obj).toJson();
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            logger.warn("Failed to serialize {} to JSON: {}", obj.getClass().getSimpleName(), e.getMessage());
            return "{\"error\":\"Failed to serialize to JSON: " + e.getMessage() + "\"}";
        }
    }

    private ChatResponse errorResponse(String message) {
        AssistantMessage errorMessage = new AssistantMessage(message);
        Generation errorGeneration = new Generation(errorMessage, ChatGenerationMetadata.NULL);
        return new ChatResponse(List.of(errorGeneration));
    }




    private static class ThreadLocalStorage {
        private static final ThreadLocal<Map<String, Object>> storage = ThreadLocal.withInitial(HashMap::new);
        public static void set(String key, Object value) { storage.get().put(key, value); }
        public static Object get(String key) { return storage.get().get(key); }
        public static void clearKey(String key) { storage.get().remove(key); }
    }

    @Service
    @ConditionalOnProperty(prefix = "kompile.langmodel.pipeline", name = "enabled", havingValue = "true", matchIfMissing = false)
    public static class KompilePipelineProviderService {
        private static final Logger logger = LoggerFactory.getLogger(KompilePipelineProviderService.class);
        private final Map<String, PipelineMetadataWrapper> pipelines = new ConcurrentHashMap<>();
        private final DataFactory dataFactory;
        private final String defaultPipelineId = "llm_logic_pipeline";

        @Autowired
        public KompilePipelineProviderService(DataFactory dataFactory) {
            this.dataFactory = dataFactory;
            loadAndDescribePipelines();
        }

        public String getDefaultPipelineId() { return defaultPipelineId; }
        public Map<String, PipelineMetadataWrapper> getPipelines() { return Collections.unmodifiableMap(pipelines); }
        public Optional<Pipeline> getPipelineByName(String name) {
            PipelineMetadataWrapper wrapper = pipelines.get(name);
            return (wrapper != null) ? Optional.of(wrapper.getPipeline()) : Optional.empty();
        }

        private void loadAndDescribePipelines() {
            if (!pipelines.isEmpty()) return;
            try {
                Data echoParams = dataFactory.empty();
                echoParams.put("message_prefix", "[LLM Logic Pipeline] ");
                StepConfig echoConfig = new GenericStepConfig(
                        "ai.kompile.app.llm.pipeline.KompilePipelineLanguageModelImpl$DummyEchoStepRunner",
                        echoParams);
                Pipeline llmPipeline = new SequencePipeline("llm_logic_pipeline", List.of(echoConfig));
                pipelines.put("llm_logic_pipeline", new PipelineMetadataWrapper(llmPipeline,
                        "Core LLM reasoning pipeline with tool support.",
                        "{\"type\":\"object\",\"properties\":{\"prompt\":{\"type\":\"string\"},\"conversation_history_json\":{\"type\":\"string\"},\"kompile_available_tools\":{\"type\":\"array\"},\"kompile_tool_responses\":{\"type\":\"array\"}}}"));

                StepConfig weatherConfig = new GenericStepConfig(
                        "ai.kompile.app.llm.pipeline.KompilePipelineLanguageModelImpl$DummyWeatherToolRunner");
                Pipeline weatherToolPipeline = new SequencePipeline("get_weather_tool_pipeline", List.of(weatherConfig));
                pipelines.put("get_weather_tool_pipeline", new PipelineMetadataWrapper(weatherToolPipeline,
                        "Acts as the 'get_weather' tool. Takes location and unit, returns weather info.",
                        "{\"type\":\"object\",\"properties\":{\"location\":{\"type\":\"string\",\"description\":\"The city and state, e.g., San Francisco, CA\"},\"unit\":{\"type\":\"string\",\"enum\":[\"celsius\",\"fahrenheit\"]}},\"required\":[\"location\"]}"
                ));
                logger.info("KompilePipelineProviderService: Loaded dummy pipelines.");
            } catch (Exception e) {
                logger.error("KompilePipelineProviderService: Failed to create dummy pipelines", e);
            }
        }

        public List<PipelineToolDefinition> getAvailableToolDefinitions() {
            List<PipelineToolDefinition> toolDefs = new ArrayList<>();
            PipelineMetadataWrapper weatherToolMeta = pipelines.get("get_weather_tool_pipeline");
            if (weatherToolMeta != null) {
                toolDefs.add(new PipelineToolDefinition(
                        "get_weather_tool_pipeline", // Use the pipeline ID as the tool name
                        weatherToolMeta.getPurpose(),
                        weatherToolMeta.getInputSchemaDescription()
                ));
            }
            return toolDefs;
        }

        static class PipelineMetadataWrapper {
            final Pipeline pipeline; final String purpose; final String inputSchemaDescription;
            PipelineMetadataWrapper(Pipeline p, String pur, String schema) {
                this.pipeline = p; this.purpose = pur; this.inputSchemaDescription = schema;
            }
            Pipeline getPipeline() { return pipeline; }
            String getPurpose() { return purpose; }
            String getInputSchemaDescription() { return inputSchemaDescription; }
        }
    }

    public static class DummyEchoStepRunner implements PipelineStepRunner {
        private static final Logger logger = LoggerFactory.getLogger(DummyEchoStepRunner.class);
        private String prefix = "";
        private ObjectMapper localObjectMapper = JsonUtils.standardMapper();
        private boolean initialized = false;

        @Override
        public void init(StepConfig sc, Context ctx) {
            this.prefix = sc.getParameters().getString("message_prefix", "[LLM Echo] ");
            if (this.localObjectMapper == null) this.localObjectMapper = JsonUtils.standardMapper();
            this.initialized = true;
            logger.info("DummyEchoStepRunner initialized with prefix: {}", this.prefix);
        }

        @Override
        public Data exec(Data input, Context ctx) throws Exception {
            if (!isInitialized()) throw new IllegalStateException("Runner not initialized.");
            String prompt = input.getString("prompt", "No prompt provided.");
            logger.debug("DummyEchoStepRunner (prefix: '{}') received prompt: {}", prefix, prompt);
            Data output = Data.Factory.get().empty();

            if (input.has(PipelineDataConstants.TOOL_CALL_RESPONSES_KEY)) {
                List<Object> rawResponses = input.getList(PipelineDataConstants.TOOL_CALL_RESPONSES_KEY, ValueType.DATA);
                List<PipelineToolCallResponse> toolResponses = castRawListToPojoList(rawResponses, PipelineToolCallResponse.class, localObjectMapper);
                logger.info("DummyEchoStepRunner processing {} tool responses.", toolResponses.size());
                StringBuilder responseBuilder = new StringBuilder(this.prefix + "Processed tool responses: ");
                for (PipelineToolCallResponse res : toolResponses) {
                    responseBuilder.append("\n  Tool '").append(res.getName()).append("'(ID:").append(res.getId()).append("): ").append(res.getContent());
                    if (res.isError()) responseBuilder.append(" (ERROR!)");
                }
                responseBuilder.append("\nNow, what else can I do for you based on these tool results?");
                output.put("response_text", responseBuilder.toString());
            } else if (prompt.toLowerCase().contains("weather in paris")) {
                logger.info("DummyEchoStepRunner: Requesting 'get_weather_tool_pipeline' tool for Paris.");
                PipelineToolCallRequest toolReq = new PipelineToolCallRequest(
                        "toolreq_" + System.nanoTime(),
                        "get_weather_tool_pipeline", // Requesting the pipeline ID
                        "{\"location\": \"Paris, FR\", \"unit\": \"celsius\"}"
                );
                output.put(PipelineDataConstants.IS_TOOL_CALL_REQUEST_FLAG_KEY, true);
                output.putList(PipelineDataConstants.TOOL_CALL_REQUESTS_KEY, List.of(toolReq), ValueType.DATA);
                output.put("response_text", "Sure, let me check the current weather in Paris for you.");
            } else {
                logger.info("DummyEchoStepRunner: Echoing prompt.");
                output.put("response_text", this.prefix + prompt);
            }
            logger.debug("DummyEchoStepRunner output: {}", output.toJson());
            return output;
        }

        private <T> List<T> castRawListToPojoList(List<Object> rawList, Class<T> targetType, ObjectMapper mapper) {
            if (rawList == null) return Collections.emptyList();
            return rawList.stream().filter(Objects::nonNull)
                    .map(obj -> { try { return mapper.convertValue(obj, targetType); } catch (Exception e) { logger.error("Cast error", e); return null; }})
                    .filter(Objects::nonNull).collect(Collectors.toList());
        }
        @Override public boolean isInitialized() { return this.initialized; }
        @Override public void close() { logger.debug("DummyEchoStepRunner closed."); }
    }

    public static class DummyWeatherToolRunner implements PipelineStepRunner {
        private static final Logger logger = LoggerFactory.getLogger(DummyWeatherToolRunner.class);
        private ObjectMapper localObjectMapper = JsonUtils.standardMapper();
        private boolean initialized = false;

        @Override public void init(StepConfig sc, Context ctx) {
            if (this.localObjectMapper == null) this.localObjectMapper = JsonUtils.standardMapper();
            this.initialized = true;
            logger.info("DummyWeatherToolRunner initialized.");
        }

        @Override
        public Data exec(Data input, Context ctx) throws Exception {
            if (!isInitialized()) throw new IllegalStateException("Runner not initialized.");
            String location = input.getString("location", "an unspecified location");
            String unit = input.getString("unit", "celsius");
            logger.info("DummyWeatherToolRunner (acting as tool 'get_weather_tool_pipeline') called for location: {}, unit: {}", location, unit);

            Map<String, String> weatherResultMap = new HashMap<>();
            weatherResultMap.put("location", location);
            weatherResultMap.put("temperature", "10");
            weatherResultMap.put("condition", "Cloudy");
            weatherResultMap.put("unit", unit);
            if ("Paris, FR".equalsIgnoreCase(location)) {
                weatherResultMap.put("temperature", "25");
                weatherResultMap.put("condition", "Gloriously sunny with a gentle breeze");
            }

            Data output = Data.Factory.get().empty();
            // Populate the Data object with structured weather information
            output.put("city", weatherResultMap.get("location"));
            output.put("temp", weatherResultMap.get("temperature"));
            output.put("forecast", weatherResultMap.get("condition"));
            output.put("temp_unit", weatherResultMap.get("unit"));
            // This entire Data object will be serialized to JSON to become the 'content'
            // of the PipelineToolCallResponse by the executeInternalTool method.
            logger.debug("DummyWeatherToolRunner produced output Data: {}", output.toJson());
            return output;
        }
        @Override public boolean isInitialized() { return this.initialized; }
        @Override public void close() { logger.debug("DummyWeatherToolRunner closed."); }
    }
}
