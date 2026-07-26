package ai.kompile.serving.openai;

import ai.kompile.serving.openai.dto.ChatCompletionRequest;
import ai.kompile.serving.openai.dto.ChatCompletionResponse;
import ai.kompile.serving.openai.dto.ChatCompletionChunk;
import org.eclipse.deeplearning4j.llm.generation.GenerationPipeline;
import org.eclipse.deeplearning4j.llm.generation.GenerationResult;
import org.eclipse.deeplearning4j.llm.generation.sampling.SamplingConfig;
import org.eclipse.deeplearning4j.llm.tokenizer.ChatTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Bridges between OpenAI-compatible request/response DTOs and the DL4J GenerationPipeline API.
 */
public class ModelBridge {

    private static final Logger log = LoggerFactory.getLogger(ModelBridge.class);

    private final GenerationPipeline pipeline;
    private final ChatTemplate chatTemplate;
    private final String modelId;

    public ModelBridge(GenerationPipeline pipeline, ChatTemplate chatTemplate, String modelId) {
        this.pipeline = pipeline;
        this.chatTemplate = chatTemplate;
        this.modelId = modelId;
    }

    /**
     * Converts OpenAI chat messages to a prompt string using the ChatTemplate.
     */
    public String buildPrompt(List<ChatCompletionRequest.Message> messages) {
        if (chatTemplate != null) {
            List<ChatTemplate.Message> templateMessages = messages.stream()
                    .map(m -> new ChatTemplate.Message(m.getRole(), m.getContent()))
                    .collect(Collectors.toList());
            return chatTemplate.apply(templateMessages, true);
        }

        // Fallback: simple concatenation if no chat template
        StringBuilder sb = new StringBuilder();
        for (ChatCompletionRequest.Message msg : messages) {
            String role = msg.getRole();
            if ("system".equals(role)) {
                sb.append("System: ").append(msg.getContent()).append("\n\n");
            } else if ("user".equals(role)) {
                sb.append("User: ").append(msg.getContent()).append("\n\n");
            } else if ("assistant".equals(role)) {
                sb.append("Assistant: ").append(msg.getContent()).append("\n\n");
            }
        }
        sb.append("Assistant: ");
        return sb.toString();
    }

    /**
     * Creates a per-request SamplingConfig while retaining tokenizer-specific and
     * pipeline-level defaults that the OpenAI request does not override.
     */
    public SamplingConfig buildSamplingConfig(
            ChatCompletionRequest request,
            SamplingConfig defaults) {
        SamplingConfig base = defaults != null ? defaults : SamplingConfig.defaultConfig();
        double temperature = request.getTemperature() != null
                ? request.getTemperature() : base.getTemperature();
        boolean doSample = request.getTemperature() != null
                ? temperature > 0.0
                : base.isDoSample() && temperature > 0.0;

        return base.toBuilder()
                .temperature(temperature)
                .doSample(doSample)
                .topP(request.getTopP() != null ? request.getTopP() : base.getTopP())
                .maxNewTokens(request.getMaxTokens() != null
                        ? request.getMaxTokens() : base.getMaxNewTokens())
                .seed(request.getSeed() != null ? request.getSeed() : base.getSeed())
                .frequencyPenalty(request.getFrequencyPenalty() != null
                        ? request.getFrequencyPenalty() : base.getFrequencyPenalty())
                .presencePenalty(request.getPresencePenalty() != null
                        ? request.getPresencePenalty() : base.getPresencePenalty())
                .build();
    }

    /**
     * Performs non-streaming generation and returns an OpenAI-compatible response.
     */
    public synchronized ChatCompletionResponse generate(
            ChatCompletionRequest request,
            SamplingConfig defaults) {
        String prompt = buildPrompt(request.getMessages());
        int maxTokens = request.getMaxTokens() != null ? request.getMaxTokens() : defaults.getMaxNewTokens();

        log.debug("Generating with prompt length={}, maxTokens={}", prompt.length(), maxTokens);

        SamplingConfig requestSampling = buildSamplingConfig(request, defaults);
        GenerationResult result = pipeline.generate(prompt, maxTokens, requestSampling);

        return toResponse(result, request.getModel());
    }

    /**
     * Performs streaming generation, calling the chunk callback for each token.
     */
    public synchronized void generateStreaming(
            ChatCompletionRequest request,
            SamplingConfig defaults,
            Consumer<ChatCompletionChunk> chunkCallback,
            Runnable doneCallback) {
        String prompt = buildPrompt(request.getMessages());
        int maxTokens = request.getMaxTokens() != null ? request.getMaxTokens() : defaults.getMaxNewTokens();
        String completionId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        long created = System.currentTimeMillis() / 1000;
        String model = request.getModel() != null ? request.getModel() : modelId;

        // Send initial chunk with role
        ChatCompletionChunk roleChunk = ChatCompletionChunk.builder()
                .id(completionId)
                .object("chat.completion.chunk")
                .created(created)
                .model(model)
                .choices(Collections.singletonList(
                        ChatCompletionChunk.Choice.builder()
                                .index(0)
                                .delta(ChatCompletionChunk.Delta.builder()
                                        .role("assistant")
                                        .build())
                                .build()))
                .build();
        chunkCallback.accept(roleChunk);

        // Streaming currently decodes post-hoc inside GenerationPipeline. Apply the
        // request sampling config for that call, then restore defaults before releasing
        // the bridge monitor so no request can observe another request's decode options.
        SamplingConfig requestSampling = buildSamplingConfig(request, defaults);
        SamplingConfig previousSampling = pipeline.getSamplingConfig();
        pipeline.setSamplingConfig(requestSampling);
        try {
            pipeline.generateStream(prompt, maxTokens, token -> {
                ChatCompletionChunk chunk = ChatCompletionChunk.builder()
                        .id(completionId)
                        .object("chat.completion.chunk")
                        .created(created)
                        .model(model)
                        .choices(Collections.singletonList(
                                ChatCompletionChunk.Choice.builder()
                                        .index(0)
                                        .delta(ChatCompletionChunk.Delta.builder()
                                                .content(token)
                                                .build())
                                        .build()))
                        .build();
                chunkCallback.accept(chunk);
            });
        } finally {
            pipeline.setSamplingConfig(previousSampling);
        }

        // Send final chunk with finish_reason
        ChatCompletionChunk finalChunk = ChatCompletionChunk.builder()
                .id(completionId)
                .object("chat.completion.chunk")
                .created(created)
                .model(model)
                .choices(Collections.singletonList(
                        ChatCompletionChunk.Choice.builder()
                                .index(0)
                                .delta(ChatCompletionChunk.Delta.builder().build())
                                .finishReason("stop")
                                .build()))
                .build();
        chunkCallback.accept(finalChunk);

        doneCallback.run();
    }

    /**
     * Converts a GenerationResult to an OpenAI-compatible response.
     */
    private ChatCompletionResponse toResponse(GenerationResult result, String requestModel) {
        String model = requestModel != null ? requestModel : modelId;
        String completionId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);

        return ChatCompletionResponse.builder()
                .id(completionId)
                .object("chat.completion")
                .created(System.currentTimeMillis() / 1000)
                .model(model)
                .choices(Collections.singletonList(
                        ChatCompletionResponse.Choice.builder()
                                .index(0)
                                .message(ChatCompletionResponse.Message.builder()
                                        .role("assistant")
                                        .content(result.getText())
                                        .build())
                                .finishReason(mapFinishReason(result.getFinishReason()))
                                .build()))
                .usage(ChatCompletionResponse.Usage.builder()
                        .promptTokens(result.getPromptTokenCount())
                        .completionTokens(result.getGeneratedTokenCount())
                        .totalTokens(result.getTotalTokenCount())
                        .build())
                .build();
    }

    /**
     * Maps DL4J FinishReason enum to OpenAI finish_reason string.
     */
    private String mapFinishReason(GenerationResult.FinishReason reason) {
        if (reason == null) {
            return "stop";
        }
        switch (reason) {
            case EOS:
            case STOP_SEQUENCE:
                return "stop";
            case MAX_TOKENS:
                return "length";
            case CANCELLED:
            case ERROR:
            default:
                return "stop";
        }
    }

    public String getModelId() {
        return modelId;
    }
}
