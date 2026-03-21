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

package ai.kompile.pipelines.steps.samediff.llm;

import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.context.Context;
import ai.kompile.pipelines.framework.api.data.*;
import ai.kompile.pipelines.framework.api.llm.LLMStepConfig;
import ai.kompile.pipelines.steps.samediff.nlp.SameDiffLLMTokenizer; // Using the SameDiff specific tokenizer
import ai.kompile.pipelines.steps.samediff.nlp.SameDiffWordPieceTokenizer; // Concrete SameDiff tokenizer
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
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
public class SameDiffLanguageModelStepRunner implements PipelineStepRunner {

    private static final String TOOL_CALL_MARKER_START = "<tool_call_json>";
    private static final String TOOL_CALL_MARKER_END = "</tool_call_json>";

    // Default names for SameDiff graph placeholders/outputs if not specified in config
    private static final String DEFAULT_INPUT_IDS_PLACEHOLDER = "input_ids";
    private static final String DEFAULT_ATTENTION_MASK_PLACEHOLDER = "attention_mask"; // Optional
    private static final String DEFAULT_LOGITS_OUTPUT_NAME = "logits";


    private LLMStepConfig config;
    private SameDiff sameDiffModel;
    private SameDiffLLMTokenizer tokenizer;
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
            log.warn("SameDiffLanguageModelStepRunner for step '{}' is already initialized. Re-initialization attempt ignored.", config.getName());
            return;
        }

        try {
            context.profiler().startEvent("SameDiffLanguageModelStepRunner.init:" + config.getName()); // Corrected: context.profiler()
            log.info("Initializing SameDiffLanguageModelStepRunner (Context ID: {}) for step '{}' with model URI: {}",
                    context.executionId().orElse("N/A"), config.getName(), this.config.getModelUri());

            if (this.config.getModelUri() == null || this.config.getModelUri().isEmpty()) {
                throw new IllegalArgumentException("Model URI must be specified in LLMStepConfig for SameDiff model for step '" + config.getName() + "'.");
            }

            File modelFile = new File(new URI(this.config.getModelUri()));
            if (!modelFile.exists() || !modelFile.isFile()){
                throw new IOException("SameDiff model file not found at: " + modelFile.getAbsolutePath());
            }
            this.sameDiffModel = SameDiff.load(modelFile, true);
            log.info("Loaded SameDiff model for step '{}' from: {}", config.getName(), this.config.getModelUri());

            if (this.config.getTokenizerUri() == null || this.config.getTokenizerUri().isEmpty()) {
                throw new IllegalArgumentException("Tokenizer URI must be specified in LLMStepConfig for step '" + config.getName() + "'.");
            }
            String tokenizerType = this.config.getTokenizerType() != null ? this.config.getTokenizerType().toLowerCase() : "samediff_wordpiece";
            if ("samediff_wordpiece".equals(tokenizerType) || "wordpiece".equals(tokenizerType)) {
                this.tokenizer = new SameDiffWordPieceTokenizer();
            } else {
                throw new IllegalArgumentException("Unsupported tokenizerType for step '" + config.getName() + "': " + tokenizerType +
                        ". Expected 'samediff_wordpiece' or 'wordpiece'.");
            }
            this.tokenizer.initialize(this.config.getTokenizerUri(), this.config.getTokenizerConfig());

            this.initialized = true;
            log.info("SameDiff Language Model Runner for step '{}' and Tokenizer initialized successfully. Context ID: {}",
                    config.getName(), context.executionId().orElse("N/A"));

        } catch (Exception e) {
            this.initialized = false;
            log.error("Failed to initialize SameDiffLanguageModelStepRunner for step '{}'. Context ID: {}", config.getName(), context.executionId().orElse("N/A"), e);
            throw e;
        } finally {
            context.profiler().stopEvent(); // Corrected: context.profiler()
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
            throw new IllegalStateException("SameDiffLanguageModelStepRunner for step '" + config.getName() + "' is not initialized. Call init() first.");
        }
        context.profiler().startEvent("SameDiffLanguageModelStepRunner.exec:" + config.getName()); // Corrected: context.profiler()
        log.debug("Executing SameDiffLanguageModelStepRunner for step '{}'. Context ID: {}", config.getName(), context.executionId().orElse("N/A"));

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
            log.info("SameDiff Runner (step '{}'): Received tool response for tool ID '{}', name '{}'. Context ID: {}",
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

        // Track arrays for cleanup - stored for proper closing at the end
        List<INDArray> arraysToClose = new ArrayList<>();

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

        String inputIdsName = (String) config.getGenerationParameters().getOrDefault("inputIdsPlaceholderName", DEFAULT_INPUT_IDS_PLACEHOLDER);
        String attentionMaskName = (String) config.getGenerationParameters().getOrDefault("attentionMaskPlaceholderName", DEFAULT_ATTENTION_MASK_PLACEHOLDER);
        String logitsName = (String) config.getGenerationParameters().getOrDefault("logitsOutputName", DEFAULT_LOGITS_OUTPUT_NAME);

        Data resultData = Data.empty(); // Corrected: Use Data.empty()

        long generationStartNanos = System.nanoTime();
        long firstTokenLatencyNanos = -1;
        int generatedTokenCount = 0;

        try {
            for (int i = 0; i < maxNewTokens; i++) {
                Map<String, INDArray> placeholderMap = new HashMap<>();
                placeholderMap.put(inputIdsName, currentSequenceIds);
                if (attentionMask != null && sameDiffModel.getVariable(attentionMaskName) != null) {
                    placeholderMap.put(attentionMaskName, attentionMask);
                }

                Map<String, INDArray> outputMap = sameDiffModel.output(placeholderMap, logitsName);
                INDArray logits = outputMap.get(logitsName);

                // Track output arrays for cleanup
                if (outputMap != null) {
                    for (INDArray arr : outputMap.values()) {
                        if (arr != null && !arraysToClose.contains(arr)) {
                            arraysToClose.add(arr);
                        }
                    }
                }

                if (logits == null || logits.isEmpty()) {
                    log.warn("SameDiff model (step '{}') produced null or empty logits. Stopping generation.", config.getName());
                    break;
                }

                INDArray nextTokenLogits = logits.get(NDArrayIndex.point(0), NDArrayIndex.point(logits.shape()[1] - 1), NDArrayIndex.all());
                arraysToClose.add(nextTokenLogits);

                long nextTokenId = sampleNextToken(nextTokenLogits, temperature, topK);

                // Track token generation metrics
                if (firstTokenLatencyNanos < 0) {
                    firstTokenLatencyNanos = System.nanoTime() - generationStartNanos;
                }
                generatedTokenCount++;

                if (nextTokenId == eosTokenId) {
                    log.debug("EOS token encountered for step '{}'. Stopping generation.", config.getName());
                    break;
                }
                allGeneratedTokenIdsThisTurn.add(nextTokenId);

                // Create new sequence with appended token - track old for cleanup
                INDArray oldSequenceIds = currentSequenceIds;
                INDArray nextTokenArr = Nd4j.scalar(nextTokenId).reshape(1, 1);
                arraysToClose.add(nextTokenArr);
                currentSequenceIds = Nd4j.concat(1, currentSequenceIds, nextTokenArr);
                if (oldSequenceIds != null && !arraysToClose.contains(oldSequenceIds)) {
                    arraysToClose.add(oldSequenceIds);
                }

                if (attentionMask != null) {
                    INDArray oldMask = attentionMask;
                    INDArray oneArr = Nd4j.scalar(1L).reshape(1, 1);
                    arraysToClose.add(oneArr);
                    attentionMask = Nd4j.concat(1, attentionMask, oneArr);
                    if (oldMask != null && !arraysToClose.contains(oldMask)) {
                        arraysToClose.add(oldMask);
                    }
                }

                List<Long> newlyGeneratedPortion = allGeneratedTokenIdsThisTurn.subList(promptTokenIds.size(), allGeneratedTokenIdsThisTurn.size());
                String currentDecodedText = tokenizer.decode(newlyGeneratedPortion.stream().mapToLong(l -> l).toArray(), false);
                PipelineToolCallRequest toolCallRequest = parseToolCall(currentDecodedText);

                if (toolCallRequest != null) {
                    log.info("SameDiff Runner (step '{}'): LLM requests tool call: ID '{}', Name '{}'. Context ID: {}",
                            config.getName(), toolCallRequest.getId(), toolCallRequest.getName(), context.executionId().orElse("N/A"));
                    resultData.put(config.getToolCallRequestOutputName(), toolCallRequest);
                    conversationHistory.add("assistant_partial_tool_call: " + tokenizer.decode(allGeneratedTokenIdsThisTurn.stream().mapToLong(l->l).toArray(), false));
                    resultData.put(config.getConversationContextName(), new ArrayList<>(conversationHistory));
                    context.profiler().stopEvent(); // Corrected: context.profiler()
                    return resultData;
                }
            }

            List<Long> finalGeneratedPortion = allGeneratedTokenIdsThisTurn.subList(promptTokenIds.size(), allGeneratedTokenIdsThisTurn.size());
            String finalTextResponse = tokenizer.decode(finalGeneratedPortion.stream().mapToLong(l->l).toArray(), true).trim();
            resultData.put(config.getResponseOutputName(), finalTextResponse);
            conversationHistory.add("assistant: " + finalTextResponse);
            resultData.put(config.getConversationContextName(), new ArrayList<>(conversationHistory));

            // Record generation throughput metrics
            long totalGenerationNanos = System.nanoTime() - generationStartNanos;
            long totalGenerationMs = totalGenerationNanos / 1_000_000;
            long firstTokenMs = firstTokenLatencyNanos > 0 ? firstTokenLatencyNanos / 1_000_000 : -1;
            double tokensPerSecond = totalGenerationNanos > 0
                    ? (generatedTokenCount * 1_000_000_000.0) / totalGenerationNanos : 0.0;

            resultData.put("generatedTokens", generatedTokenCount);
            resultData.put("promptTokens", promptTokenIds.size());
            resultData.put("firstTokenLatencyMs", firstTokenMs);
            resultData.put("totalGenerationMs", totalGenerationMs);
            resultData.put("tokensPerSecond", Math.round(tokensPerSecond * 100.0) / 100.0);

            log.info("SameDiff LLM (step '{}'): {} tokens in {}ms ({} tok/s), TTFT {}ms, prompt {} tokens. Context ID: {}",
                    config.getName(), generatedTokenCount, totalGenerationMs,
                    String.format("%.1f", tokensPerSecond), firstTokenMs,
                    promptTokenIds.size(), context.executionId().orElse("N/A"));
        } finally {
            // CRITICAL: Close all tracked arrays to prevent off-heap memory leaks
            // Also close the final currentSequenceIds and attentionMask if not already tracked
            if (currentSequenceIds != null && !arraysToClose.contains(currentSequenceIds)) {
                arraysToClose.add(currentSequenceIds);
            }
            if (attentionMask != null && !arraysToClose.contains(attentionMask)) {
                arraysToClose.add(attentionMask);
            }
            // Close initial encoded arrays
            for (INDArray arr : encoded.values()) {
                if (arr != null && !arraysToClose.contains(arr)) {
                    arraysToClose.add(arr);
                }
            }
            // Close all tracked arrays
            for (INDArray arr : arraysToClose) {
                closeArraySafely(arr);
            }
            context.profiler().stopEvent(); // Corrected: context.profiler()
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
            INDArray argMax = null;
            try {
                argMax = Nd4j.argMax(nextTokenLogits, 0);
                return argMax.getLong(0);
            } finally {
                closeArraySafely(argMax);
            }
        }

        INDArray tempDiv = null;
        INDArray probabilities = null;
        try {
            tempDiv = nextTokenLogits.div(temperature);
            probabilities = Nd4j.nn().softmax(tempDiv, 0);

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
            if (sumTopKProbs == 0.0) {
                return topKTokens.get(0).getLeft();
            }

            List<Double> cumulativeProbs = new ArrayList<>();
            double currentSum = 0;
            for (Pair<Integer, Double> topKToken : topKTokens) {
                currentSum += (topKToken.getRight() / sumTopKProbs);
                cumulativeProbs.add(currentSum);
            }

            double randomVal = this.random.nextDouble();
            for (int k_idx = 0; k_idx < cumulativeProbs.size(); k_idx++) {
                if (randomVal < cumulativeProbs.get(k_idx)) {
                    return topKTokens.get(k_idx).getLeft();
                }
            }
            return topKTokens.get(topKTokens.size() - 1).getLeft();
        } finally {
            closeArraySafely(tempDiv);
            closeArraySafely(probabilities);
        }
    }

    /**
     * Safely close an INDArray, catching any exceptions.
     */
    private void closeArraySafely(INDArray arr) {
        if (arr != null && !arr.wasClosed()) {
            try {
                arr.close();
            } catch (Exception e) {
                // Ignore close errors
            }
        }
    }

    private PipelineToolCallRequest parseToolCall(String llmOutputText) {
        if (config.getToolChoice() == LLMStepConfig.ToolChoiceMode.NONE) return null;

        LLMStepConfig.ToolCallOutputFormat format = config.getToolCallOutputFormat() != null ?
                config.getToolCallOutputFormat() : LLMStepConfig.ToolCallOutputFormat.JSON_MARKER_BASED;

        switch (format) {
            case JSON_MARKER_BASED:
                return parseToolCallWithJsonMarkers(llmOutputText);
            case OPENAI_JSON:
                return parseToolCallWithOpenAIJson(llmOutputText);
            default:
                log.warn("Unsupported/unhandled toolCallOutputFormat: {} for step '{}'", format, config.getName());
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
        } catch (Exception e) {
            return null;
        }
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
            } else {
                log.warn("Tool call arguments for step '{}' are not in expected format (Map or JSON string). Found: {}",
                        config.getName(), argsObject != null ? argsObject.getClass().getName() : "null");
                return null;
            }

            if (toolName != null && argumentsMap != null) {
                if (config.getToolDefinitions() == null || config.getToolDefinitions().stream().noneMatch(td -> td.getName().equals(toolName))) {
                    log.warn("LLM (step '{}') attempted to call undefined tool: {}", config.getName(), toolName);
                    return null;
                }
                if (config.getToolChoice() == LLMStepConfig.ToolChoiceMode.SPECIFIC_TOOL &&
                        !toolName.equals(config.getSpecificToolNameForCall())) {
                    log.warn("LLM (step '{}') called tool '{}' but was required to call '{}'. Ignoring.",
                            config.getName(), toolName, config.getSpecificToolNameForCall());
                    return null;
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
        // SameDiff implements AutoCloseable - calling close() releases all OpContexts
        // cached in InferenceSessions, preventing native memory leaks.
        // We use reflection to call close() to maintain compatibility with nd4j-api versions
        // that may not have the close() method yet (avoids compile-time dependency).
        if (sameDiffModel != null) {
            try {
                // Use reflection to call close() for compatibility with different nd4j-api versions
                java.lang.reflect.Method closeMethod = sameDiffModel.getClass().getMethod("close");
                closeMethod.invoke(sameDiffModel);
            } catch (NoSuchMethodException e) {
                log.debug("SameDiff.close() not available in this nd4j-api version - skipping cleanup");
            } catch (Exception e) {
                log.warn("Error closing SameDiff model for step '{}': {}", config != null ? config.getName() : "UNKNOWN", e.getMessage());
            }
            sameDiffModel = null;
        }
        initialized = false;
        log.info("SameDiffLanguageModelStepRunner for step '{}' closed.", config != null ? config.getName() : "UNKNOWN");
    }
}
