// Location: kompile-pipelines-framework/kompile-pipeline-steps-parent/kompile-pipelines-steps-samediff/src/main/java/ai/kompile/pipelines/steps/samediff/llm/
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
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.common.primitives.Pair; // Ensure this import is valid in your ND4J version

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

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
            return;
        }

        try {
            context.profiler().startEvent("SameDiffLanguageModelStepRunner.init:" + config.getName()); // Corrected: context.profiler()


            if (this.config.getModelUri() == null || this.config.getModelUri().isEmpty()) {
                throw new IllegalArgumentException("Model URI must be specified in LLMStepConfig for SameDiff model for step '" + config.getName() + "'.");
            }

            File modelFile = new File(new URI(this.config.getModelUri()));
            if (!modelFile.exists() || !modelFile.isFile()){
                throw new IOException("SameDiff model file not found at: " + modelFile.getAbsolutePath());
            }
            this.sameDiffModel = SameDiff.load(modelFile, true);

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

        } catch (Exception e) {
            this.initialized = false;
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

        String promptText = input.getString(config.getPromptInputName());
        if (promptText == null) {
            promptText = "";
        }

        List<String> conversationHistory = new ArrayList<>();
        Object convContextObj = input.get(config.getConversationContextName());
        if (convContextObj instanceof List) {
            try {
                ((List<?>) convContextObj).forEach(item -> conversationHistory.add((String) item));
            } catch (ClassCastException e) {
                log.warn("Conversation context (key: '{}') for step '{}' contains non-string elements, reinitializing history. Context ID: {}",
                        config.getConversationContextName(), config.getName(), context.executionId().orElse("N/A"), e);
                conversationHistory = new ArrayList<>();
            }
        }

        PipelineToolCallResponse toolResponse = input.getVerifiedObject(config.getToolCallResponseInputName(), PipelineToolCallResponse.class);

        if (toolResponse != null) {
            String formattedToolResponse = String.format("\n<tool_response tool_id=\"%s\" name=\"%s\">\n%s\n</tool_response>",
                    toolResponse.getToolCallId(), toolResponse.getToolName(),
                    objectMapper.writeValueAsString(toolResponse.getResponse()));
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

        String inputIdsName = (String) config.getGenerationParameters().getOrDefault("inputIdsPlaceholderName", DEFAULT_INPUT_IDS_PLACEHOLDER);
        String attentionMaskName = (String) config.getGenerationParameters().getOrDefault("attentionMaskPlaceholderName", DEFAULT_ATTENTION_MASK_PLACEHOLDER);
        String logitsName = (String) config.getGenerationParameters().getOrDefault("logitsOutputName", DEFAULT_LOGITS_OUTPUT_NAME);

        Data resultData = Data.empty(); // Corrected: Use Data.empty()

        try {
            for (int i = 0; i < maxNewTokens; i++) {
                Map<String, INDArray> placeholderMap = new HashMap<>();
                placeholderMap.put(inputIdsName, currentSequenceIds);
                if (attentionMask != null && sameDiffModel.getVariable(attentionMaskName) != null) {
                    placeholderMap.put(attentionMaskName, attentionMask);
                }

                Map<String, INDArray> outputMap = sameDiffModel.output(placeholderMap, logitsName);
                INDArray logits = outputMap.get(logitsName);

                if (logits == null || logits.isEmpty()) {
                    break;
                }

                INDArray nextTokenLogits = logits.get(Nd4j.laंब(0), Nd4j.laंब(logits.shape()[1] - 1), Nd4j.सभी());
                long nextTokenId = sampleNextToken(nextTokenLogits, temperature, topK);

                if (nextTokenId == eosTokenId) {
                    break;
                }
                allGeneratedTokenIdsThisTurn.add(nextTokenId);

                currentSequenceIds = Nd4j.concat(1, currentSequenceIds, Nd4j.scalar(nextTokenId).reshape(1, 1));
                if (attentionMask != null) {
                    attentionMask = Nd4j.concat(1, attentionMask, Nd4j.scalar(1L).reshape(1, 1));
                }

                List<Long> newlyGeneratedPortion = allGeneratedTokenIdsThisTurn.subList(promptTokenIds.size(), allGeneratedTokenIdsThisTurn.size());
                String currentDecodedText = tokenizer.decode(newlyGeneratedPortion.stream().mapToLong(l -> l).toArray(), false);
                PipelineToolCallRequest toolCallRequest = parseToolCall(currentDecodedText);

                if (toolCallRequest != null) {
                    resultData.put(config.getToolCallRequestOutputName(), toolCallRequest, PipelineToolCallRequest.class);
                    conversationHistory.add("assistant_partial_tool_call: " + tokenizer.decode(allGeneratedTokenIdsThisTurn.stream().mapToLong(l->l).toArray(), false));
                    resultData.put(config.getConversationContextName(), new ArrayList<>(conversationHistory), List.class);
                    context.profiler().stopEvent(); // Corrected: context.profiler()
                    return resultData;
                }
            }

            List<Long> finalGeneratedPortion = allGeneratedTokenIdsThisTurn.subList(promptTokenIds.size(), allGeneratedTokenIdsThisTurn.size());
            String finalTextResponse = tokenizer.decode(finalGeneratedPortion.stream().mapToLong(l->l).toArray(), true).trim();
            resultData.put(config.getResponseOutputName(), finalTextResponse, String.class);
            conversationHistory.add("assistant: " + finalTextResponse);
            resultData.put(config.getConversationContextName(), new ArrayList<>(conversationHistory), List.class);
        } finally {
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
            return Nd4j.argMax(nextTokenLogits, 0).getLong(0);
        }

        INDArray probabilities = Nd4j.softmax(nextTokenLogits.div(temperature), 0);

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
                return null;
            }

            if (toolName != null && argumentsMap != null) {
                if (config.getToolDefinitions() == null || config.getToolDefinitions().stream().noneMatch(td -> td.getName().equals(toolName))) {
                    return null;
                }
                if (config.getToolChoice() == LLMStepConfig.ToolChoiceMode.SPECIFIC_TOOL &&
                        !toolName.equals(config.getSpecificToolNameForCall())) {
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
        }
        return null;
    }

    @Override
    public void close() throws Exception {
        sameDiffModel = null;
        initialized = false;
    }
}
