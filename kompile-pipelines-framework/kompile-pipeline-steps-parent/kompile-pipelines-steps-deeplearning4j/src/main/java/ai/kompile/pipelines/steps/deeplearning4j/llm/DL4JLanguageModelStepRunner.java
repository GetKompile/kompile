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

// Location: kompile-pipelines-framework/kompile-pipeline-steps-parent/kompile-pipelines-steps-deeplearning4j/src/main/java/ai/kompile/pipelines/steps/deeplearning4j/llm/
package ai.kompile.pipelines.steps.deeplearning4j.llm;

import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.context.Context;
import ai.kompile.pipelines.framework.api.data.*;
import ai.kompile.pipelines.framework.api.llm.LLMStepConfig;
import ai.kompile.pipelines.steps.deeplearning4j.nlp.DL4JLLMTokenizer;
import ai.kompile.pipelines.steps.deeplearning4j.nlp.WordPieceLLMTokenizer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.util.ModelSerializer;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.common.primitives.Pair;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.NDArrayIndex;


import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class DL4JLanguageModelStepRunner implements PipelineStepRunner {

    private static final String TOOL_CALL_MARKER_START = "<tool_call_json>";
    private static final String TOOL_CALL_MARKER_END = "</tool_call_json>";

    private static final String DEFAULT_SAMEDIFF_INPUT_IDS_PLACEHOLDER = "input_ids";
    private static final String DEFAULT_SAMEDIFF_ATTENTION_MASK_PLACEHOLDER = "attention_mask";
    private static final String DEFAULT_SAMEDIFF_LOGITS_OUTPUT_NAME = "logits";

    private LLMStepConfig config;
    private ComputationGraph computationGraphModel;
    private SameDiff sameDiffModel;
    private DL4JLLMTokenizer tokenizer;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Random random = new Random();
    private volatile boolean initialized = false;

    @Override
    public void init(StepConfig stepConfig, Context context) throws Exception {
        if (!(stepConfig instanceof LLMStepConfig)) {
            throw new IllegalArgumentException("Configuration must be an instance of LLMStepConfig. Found: " +
                    (stepConfig != null ? stepConfig.getClass().getName() : "null"));
        }
        this.config = (LLMStepConfig) stepConfig;

        if (this.initialized) {
            log.warn("DL4JLanguageModelStepRunner for step '{}' is already initialized. Re-initialization attempt ignored.", config.getName());
            return;
        }

        String profilerEventName = "DL4JLanguageModelStepRunner.init:" + (config.getName() != null ? config.getName() : "unnamedStep");
        try {
            context.profiler().startEvent(profilerEventName); // Corrected
            log.info("Initializing DL4JLanguageModelStepRunner (Context ID: {}) for step '{}' with model URI: {}",
                    context.executionId().orElse("N/A"), config.getName(), this.config.getModelUri());

            if (this.config.getModelUri() == null || this.config.getModelUri().isEmpty()) {
                throw new IllegalArgumentException("Model URI must be specified in LLMStepConfig.");
            }

            File modelFile = new File(new URI(this.config.getModelUri()));
            try {
                this.computationGraphModel = ModelSerializer.restoreComputationGraph(modelFile, true);
                log.info("Loaded model as ComputationGraph from: {}", this.config.getModelUri());
            } catch (Exception e) {
                log.warn("Failed to load as ComputationGraph for step '{}' ({}). Attempting to load as SameDiff.", config.getName(), e.getMessage());
                try {
                    this.sameDiffModel = SameDiff.load(modelFile, true);
                    log.info("Loaded model as SameDiff from: {}", this.config.getModelUri());
                } catch (Exception sdException) {
                    log.error("Failed to load model as either ComputationGraph or SameDiff for step '{}' from URI: {}", config.getName(), this.config.getModelUri(), sdException);
                    throw new RuntimeException("Could not load DL4J model as ComputationGraph or SameDiff for step " + config.getName(), sdException);
                }
            }

            if (this.computationGraphModel == null && this.sameDiffModel == null) {
                throw new RuntimeException("DL4J Model (ComputationGraph or SameDiff) could not be loaded for step '" + config.getName() + "' from: " + this.config.getModelUri());
            }

            if (this.config.getTokenizerUri() == null || this.config.getTokenizerUri().isEmpty()) {
                throw new IllegalArgumentException("Tokenizer URI must be specified in LLMStepConfig for step '" + config.getName() + "'.");
            }
            String tokenizerType = this.config.getTokenizerType() != null ? this.config.getTokenizerType().toLowerCase() : "wordpiece";
            if ("wordpiece".equals(tokenizerType)) {
                this.tokenizer = new WordPieceLLMTokenizer();
            } else {
                throw new IllegalArgumentException("Unsupported tokenizerType for step '" + config.getName() + "': " + tokenizerType);
            }
            this.tokenizer.initialize(this.config.getTokenizerUri(), this.config.getTokenizerConfig());

            this.initialized = true;
            log.info("DL4J Language Model Runner for step '{}' (using {} model) and Tokenizer initialized successfully. Context ID: {}",
                    config.getName(), (this.computationGraphModel != null ? "ComputationGraph" : "SameDiff"), context.executionId().orElse("N/A"));
        } catch (Exception e) {
            this.initialized = false;
            log.error("Failed to initialize DL4JLanguageModelStepRunner for step '{}'. Context ID: {}", config.getName(), context.executionId().orElse("N/A"), e);
            throw e;
        } finally {
            context.profiler().stopEvent(); // Corrected
        }
    }

    @Override
    public boolean isInitialized() {
        return this.initialized;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Data exec(Data input, Context context) throws Exception {
        if (!isInitialized()) {
            throw new IllegalStateException("DL4JLanguageModelStepRunner for step '" + config.getName() + "' is not initialized. Call init() first.");
        }
        String profilerEventName = "DL4JLanguageModelStepRunner.exec:" + (config.getName() != null ? config.getName() : "unnamedStep");
        context.profiler().startEvent(profilerEventName); // Corrected
        log.debug("Executing DL4JLanguageModelStepRunner for step '{}'. Context ID: {}", config.getName(), context.executionId().orElse("N/A"));

        String promptText = input.getString(config.getPromptInputName());
        if (promptText == null) {
            log.warn("Input Data missing prompt at key: '{}' for step '{}'. Using empty prompt. Context ID: {}",
                    config.getPromptInputName(), config.getName(), context.executionId().orElse("N/A"));
            promptText = "";
        }

        List<String> conversationHistory = new ArrayList<>();
        Object convContextObj = input.get(config.getConversationContextName());
        if (convContextObj instanceof List) {
            try {
                List<String> finalConversationHistory = conversationHistory;
                ((List<?>) convContextObj).forEach(item -> finalConversationHistory.add((String) item));
            } catch (ClassCastException e) {
                log.warn("Conversation context (key: '{}') for step '{}' contains non-string elements, reinitializing history. Context ID: {}",
                        config.getConversationContextName(), config.getName(), context.executionId().orElse("N/A"), e);
                conversationHistory = new ArrayList<>();
            }
        }

        PipelineToolCallResponse toolResponse = input.get(config.getToolCallResponseInputName());

        if (toolResponse != null) {
            log.info("DL4J Runner (step '{}'): Received tool response for tool ID '{}', name '{}'. Context ID: {}",
                    config.getName(), toolResponse.getId(), toolResponse.getName(), context.executionId().orElse("N/A"));
            String formattedToolResponse = String.format("\n<tool_response tool_id=\"%s\" name=\"%s\">\n%s\n</tool_response>",
                    toolResponse.getId(), toolResponse.getName(),
                    objectMapper.writeValueAsString(toolResponse.getContent()));
            conversationHistory.add("system_tool_response: " + formattedToolResponse);
        } else {
            if (conversationHistory.isEmpty() || !lastMessageWasUserPrompt(conversationHistory, promptText)) {
                conversationHistory.add("user: " + promptText);
            }
        }

        String fullPromptForModel = String.join("\n", conversationHistory);
        Map<String, INDArray> encoded = tokenizer.batchEncode(Collections.singletonList(fullPromptForModel), true);
        INDArray currentSequenceIds = encoded.get("input_ids");
        INDArray attentionMask = encoded.get("attention_mask");

        int maxNewTokens = ((Number) config.getGenerationParameters().getOrDefault("maxNewTokens", 128)).intValue();
        float temperature = ((Number) config.getGenerationParameters().getOrDefault("temperature", 0.7f)).floatValue();
        int topK = ((Number) config.getGenerationParameters().getOrDefault("topK", 0)).intValue();
        long eosTokenId = tokenizer.getEosTokenId();
        long padTokenId = tokenizer.getPadTokenId();

        List<Long> promptTokenIds = new ArrayList<>();
        for(int i = 0; i < currentSequenceIds.columns(); i++) {
            if(currentSequenceIds.getLong(0, i) != padTokenId) {
                promptTokenIds.add(currentSequenceIds.getLong(0, i));
            }
        }
        List<Long> allGeneratedTokenIdsThisTurn = new ArrayList<>(promptTokenIds);

        Data resultData = Data.empty(); // Corrected: Use Data.empty()

        try {
            for (int i = 0; i < maxNewTokens; i++) {
                INDArray logits;
                Map<String, INDArray> placeholderMap = new HashMap<>();
                List<INDArray> inputs = new ArrayList<>();
                if (computationGraphModel != null) {
                    inputs.add(currentSequenceIds);
                    if (attentionMask != null && computationGraphModel.getConfiguration().getNetworkInputs().size() > 1) {
                        inputs.add(attentionMask);
                    }
                    if (i == 0 && toolResponse == null) {
                        computationGraphModel.rnnClearPreviousState();
                    }
                    Map<String, INDArray> cgOutputMap = computationGraphModel.feedForward(inputs.toArray(new INDArray[2]),false);
                    logits = cgOutputMap.get(computationGraphModel.getConfiguration().getNetworkOutputs().get(0));
                } else if (sameDiffModel != null) {
                    String inputIdsName = (String) config.getGenerationParameters().getOrDefault("inputIdsPlaceholderName", DEFAULT_SAMEDIFF_INPUT_IDS_PLACEHOLDER);
                    String attentionMaskName = (String) config.getGenerationParameters().getOrDefault("attentionMaskPlaceholderName", DEFAULT_SAMEDIFF_ATTENTION_MASK_PLACEHOLDER);
                    String logitsName = (String) config.getGenerationParameters().getOrDefault("logitsOutputName", DEFAULT_SAMEDIFF_LOGITS_OUTPUT_NAME);

                    placeholderMap.put(inputIdsName, currentSequenceIds);
                    if (attentionMask != null && sameDiffModel.getVariable(attentionMaskName) != null) {
                        placeholderMap.put(attentionMaskName, attentionMask);
                    }
                    Map<String, INDArray> sdOutputMap = sameDiffModel.output(placeholderMap, logitsName);
                    logits = sdOutputMap.get(logitsName);
                } else {
                    throw new IllegalStateException("No DL4J model loaded for step '" + config.getName() + "'.");
                }

                INDArray nextTokenLogits = logits.get(NDArrayIndex.point(0), NDArrayIndex.point(logits.shape()[1] - 1), NDArrayIndex.all());
                long nextTokenId = sampleNextToken(nextTokenLogits, temperature, topK);

                if (nextTokenId == eosTokenId) break;
                allGeneratedTokenIdsThisTurn.add(nextTokenId);

                currentSequenceIds = Nd4j.concat(1, currentSequenceIds, Nd4j.scalar(nextTokenId).reshape(1, 1));
                if (attentionMask != null) {
                    attentionMask = Nd4j.concat(1, attentionMask, Nd4j.scalar(1L).reshape(1, 1));
                }

                List<Long> newlyGeneratedPortion = allGeneratedTokenIdsThisTurn.subList(promptTokenIds.size(), allGeneratedTokenIdsThisTurn.size());
                String currentDecodedText = tokenizer.decode(newlyGeneratedPortion.stream().mapToLong(l -> l).toArray(), false);
                PipelineToolCallRequest toolCallRequest = parseToolCall(currentDecodedText);

                if (toolCallRequest != null) {
                    resultData.put(config.getToolCallRequestOutputName(), toolCallRequest);
                    conversationHistory.add("assistant_partial_tool_call: " + tokenizer.decode(allGeneratedTokenIdsThisTurn.stream().mapToLong(l->l).toArray(), false));
                    resultData.put(config.getConversationContextName(), new ArrayList<>(conversationHistory));
                    context.profiler().stopEvent(); // Corrected
                    return resultData;
                }
            }

            List<Long> finalGeneratedPortion = allGeneratedTokenIdsThisTurn.subList(promptTokenIds.size(), allGeneratedTokenIdsThisTurn.size());
            String finalTextResponse = tokenizer.decode(finalGeneratedPortion.stream().mapToLong(l->l).toArray(), true).trim();
            resultData.put(config.getResponseOutputName(), finalTextResponse);
            conversationHistory.add("assistant: " + finalTextResponse);
            resultData.put(config.getConversationContextName(), new ArrayList<>(conversationHistory));
            log.debug("DL4J LLM (step '{}') generated response. Context ID: {}", config.getName(), context.executionId().orElse("N/A"));
        } finally {
            context.profiler().stopEvent(); // Corrected
        }
        return resultData;
    }

    private boolean lastMessageWasUserPrompt(List<String> history, String currentPromptText) {
        if (history.isEmpty()) return false;
        String lastMessage = history.get(history.size() - 1);
        return lastMessage.startsWith("user: ") && lastMessage.substring("user: ".length()).equals(currentPromptText);
    }

    private long sampleNextToken(INDArray nextTokenLogits, float temperature, int topKParam) {
        if (!this.initialized || this.tokenizer == null) {
            throw new IllegalStateException("Tokenizer not initialized for sampling in step '" + config.getName() + "'.");
        }
        if (temperature <= 0.0f || topKParam == 1) {
            return Nd4j.argMax(nextTokenLogits, 0).getLong(0);
        }
        INDArray probabilities = Nd4j.nn().softmax(nextTokenLogits.div(temperature), 0);
        int kValue = (topKParam <= 0 || topKParam > probabilities.length()) ? (int) probabilities.length() : topKParam;
        if (kValue == 0 && probabilities.length() > 0) kValue = (int) probabilities.length();
        else if (kValue == 0) return tokenizer.getUnkTokenId();

        List<Pair<Integer, Double>> tokenProbs = new ArrayList<>();
        for (int k_idx = 0; k_idx < probabilities.length(); k_idx++) {
            tokenProbs.add(Pair.of(k_idx, probabilities.getDouble(k_idx)));
        }
        tokenProbs.sort((p1, p2) -> Double.compare(p2.getRight(), p1.getRight()));
        List<Pair<Integer, Double>> topKTokens = tokenProbs.subList(0, Math.min(kValue, tokenProbs.size()));

        if (topKTokens.isEmpty()) {
            return tokenizer.getUnkTokenId();
        }

        double sumTopKProbs = topKTokens.stream().mapToDouble(Pair::getRight).sum();
        if (sumTopKProbs == 0.0) return topKTokens.get(0).getLeft();

        List<Double> cumulativeProbs = new ArrayList<>();
        double currentSum = 0;
        for (Pair<Integer, Double> topKToken : topKTokens) {
            currentSum += (topKToken.getRight() / sumTopKProbs);
            cumulativeProbs.add(currentSum);
        }

        double randomVal = this.random.nextDouble();
        for (int k_idx = 0; k_idx < cumulativeProbs.size(); k_idx++) {
            if (randomVal < cumulativeProbs.get(k_idx)) return topKTokens.get(k_idx).getLeft();
        }
        return topKTokens.get(topKTokens.size() - 1).getLeft();
    }

    private PipelineToolCallRequest parseToolCall(String llmOutputText) {
        if (config.getToolChoice() == LLMStepConfig.ToolChoiceMode.NONE) return null;
        LLMStepConfig.ToolCallOutputFormat format = config.getToolCallOutputFormat() != null ?
                config.getToolCallOutputFormat() : LLMStepConfig.ToolCallOutputFormat.JSON_MARKER_BASED;
        switch (format) {
            case JSON_MARKER_BASED: return parseToolCallWithJsonMarkers(llmOutputText);
            case OPENAI_JSON: return parseToolCallWithOpenAIJson(llmOutputText);
            default: log.warn("Unsupported/unhandled toolCallOutputFormat: {} for step '{}'", format, config.getName());
                return parseToolCallWithJsonMarkers(llmOutputText);
        }
    }

    private PipelineToolCallRequest parseToolCallWithJsonMarkers(String text) {
        int startIndex = text.indexOf(TOOL_CALL_MARKER_START);
        int endIndex = text.indexOf(TOOL_CALL_MARKER_END, startIndex);
        if (startIndex != -1 && endIndex != -1 && startIndex < endIndex) {
            String jsonPayload = text.substring(startIndex + TOOL_CALL_MARKER_START.length(), endIndex).trim();
            return deserializeToolCallRequest(jsonPayload);
        }
        return null;
    }

    private PipelineToolCallRequest parseToolCallWithOpenAIJson(String text) {
        try {
            return deserializeToolCallRequest(text.trim());
        } catch (Exception e) { return null; }
    }

    private PipelineToolCallRequest deserializeToolCallRequest(String jsonPayload) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(jsonPayload, new TypeReference<Map<String, Object>>() {});
            String toolName = (String) parsed.get("toolName");
            Object argsObject = parsed.get("arguments");
            Map<String, Object> argumentsMap;

            if (argsObject instanceof String) {
                argumentsMap = objectMapper.readValue((String) argsObject, new TypeReference<Map<String, Object>>() {});
            } else if (argsObject instanceof Map) {
                argumentsMap = (Map<String, Object>) argsObject;
            } else { return null; }

            if (toolName != null && argumentsMap != null) {
                if (config.getToolDefinitions() == null || config.getToolDefinitions().stream().noneMatch(td -> td.getName().equals(toolName))) {
                    log.warn("LLM (step '{}') attempted to call undefined tool: {}", config.getName(), toolName); return null;
                }
                if (config.getToolChoice() == LLMStepConfig.ToolChoiceMode.SPECIFIC_TOOL &&
                        !toolName.equals(config.getSpecificToolNameForCall())) {
                    log.warn("LLM (step '{}') called tool '{}' but was required to call '{}'. Ignoring.", config.getName(), toolName, config.getSpecificToolNameForCall()); return null;
                }

                String argumentsJsonString = objectMapper.writeValueAsString(argumentsMap);
                String callId = UUID.randomUUID().toString();

                return PipelineToolCallRequest.builder()
                        .id(callId)
                        .name(toolName)
                        .arguments(argumentsJsonString)
                        .build();
            }
        } catch (Exception e) {
            log.error("Failed to deserialize tool call JSON payload for step '{}': '{}'", config.getName(), jsonPayload, e);
        }
        return null;
    }

    @Override
    public void close() throws Exception {
        computationGraphModel = null;
        sameDiffModel = null;
        initialized = false;
        log.info("DL4JLanguageModelStepRunner for step '{}' closed and models set to null.", config != null ? config.getName() : "UNKNOWN");
    }
}
