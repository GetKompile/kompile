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
package ai.kompile.app.llm.pipeline;

import ai.kompile.core.llm.LanguageModel;
import ai.kompile.utils.StringUtils;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * {@link LanguageModel} and {@link ChatModel} implementation that forwards inference
 * requests to the LLM serving subprocess via HTTP.
 *
 * <p>Used in the app-main context where models are served by a separate subprocess
 * (typically on port 8091). This is the SameDiff equivalent of
 * {@code OpenAiLanguageModelImpl} talking to the OpenAI API — a thin HTTP client
 * implementing the standard inference interface.</p>
 *
 * <p>Does not handle model lifecycle (load/unload/status/DSP observability); the
 * serving component owns that responsibility.</p>
 */
public class SubprocessLanguageModelImpl implements LanguageModel, ChatModel {

    private static final Logger logger = LoggerFactory.getLogger(SubprocessLanguageModelImpl.class);
    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();
    private static final int DEFAULT_MAX_TOKENS = 256;
    private static final double DEFAULT_TEMPERATURE = 0.0;

    private final Supplier<String> servingUrlResolver;
    private final HttpClient httpClient;
    private final int maxTokens;
    private final double temperature;

    public SubprocessLanguageModelImpl(String servingUrl) {
        this(() -> servingUrl, DEFAULT_MAX_TOKENS, DEFAULT_TEMPERATURE);
    }

    public SubprocessLanguageModelImpl(Supplier<String> servingUrlResolver) {
        this(servingUrlResolver, DEFAULT_MAX_TOKENS, DEFAULT_TEMPERATURE);
    }

    public SubprocessLanguageModelImpl(String servingUrl, int maxTokens, double temperature) {
        this(() -> servingUrl, maxTokens, temperature);
    }

    public SubprocessLanguageModelImpl(
            Supplier<String> servingUrlResolver, int maxTokens, double temperature) {
        this.servingUrlResolver = Objects.requireNonNull(servingUrlResolver, "servingUrlResolver");
        this.maxTokens = Math.max(1, maxTokens);
        this.temperature = Math.max(0.0, temperature);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        logger.info("SubprocessLanguageModelImpl initialized with managed endpoint resolver → {} "
                        + "(maxTokens={}, temperature={})",
                currentServingUrl(), this.maxTokens, this.temperature);
    }

    // ==================== LanguageModel impl ====================

    @Override
    public String generateResponse(String userQuery, List<String> context) {
        ChatResponse response = generateResponseWithPotentialToolCalls(userQuery, context);
        if (response != null && response.getResult() != null && response.getResult().getOutput() != null) {
            String text = response.getResult().getOutput().getText();
            if (text != null && !text.isBlank()) {
                return text;
            }
        }
        throw new IllegalStateException("Subprocess LLM did not produce a textual response");
    }

    @Override
    public ChatResponse generateResponseWithPotentialToolCalls(String userQuery, List<String> context) {
        String prompt = composePrompt(userQuery, context);
        return callChatCompletions(List.of(chatMessage("user", prompt)), null);
    }

    // ==================== ChatModel impl ====================

    @Override
    public ChatResponse call(Prompt prompt) {
        return callChatCompletions(extractChatMessages(prompt), prompt != null ? prompt.getOptions() : null);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.just(call(prompt));
    }

    // ==================== HTTP forwarding ====================

    private ChatResponse callChatCompletions(List<Map<String, String>> messages, ChatOptions options) {
        try {
            if (messages == null || messages.isEmpty()) {
                throw new IllegalArgumentException("Subprocess LLM request requires at least one message");
            }

            int requestMaxTokens = options != null && options.getMaxTokens() != null
                    ? Math.max(1, options.getMaxTokens())
                    : maxTokens;
            double requestTemperature = options != null && options.getTemperature() != null
                    ? Math.max(0.0, options.getTemperature())
                    : temperature;
            Double requestTopP = options != null ? options.getTopP() : null;
            Integer requestTopK = options != null ? options.getTopK() : null;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("prompt", composeServingPrompt(messages));
            payload.put("maxTokens", requestMaxTokens);
            payload.put("temperature", requestTemperature);
            payload.put("topP", requestTopP != null ? requestTopP : 1.0);
            if (requestTopK != null) {
                payload.put("topK", requestTopK);
            }
            payload.put("stream", false);

            String bodyJson = MAPPER.writeValueAsString(payload);
            String servingUrl = currentServingUrl();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(servingUrl + "/api/llm/generate"))
                    .timeout(Duration.ofMinutes(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Subprocess HTTP " + response.statusCode() + ": "
                        + StringUtils.truncate(response.body(), 800));
            }
            String text = parseGenerateResponse(response.body());
            if (text == null || text.isBlank()) {
                throw new IllegalStateException("Subprocess LLM returned an empty response");
            }
            return new ChatResponse(List.of(
                    new Generation(new AssistantMessage(text), ChatGenerationMetadata.NULL)));
        } catch (RuntimeException e) {
            logger.error("Subprocess generate failed: {}", e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            logger.error("Subprocess generate failed: {}", e.getMessage(), e);
            throw new IllegalStateException("Subprocess generate failed", e);
        }
    }

    private String parseGenerateResponse(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            JsonNode error = node.get("error");
            if (error != null) {
                JsonNode message = error.get("message");
                throw new IllegalStateException("Subprocess LLM returned error: "
                        + (message != null ? message.asText() : error.toString()));
            }
            JsonNode choices = node.get("choices");
            if (choices != null && choices.isArray() && !choices.isEmpty()) {
                JsonNode message = choices.get(0).path("message");
                JsonNode content = message.get("content");
                if (content != null && content.isTextual()) {
                    return content.asText();
                }
            }
            for (String field : new String[]{"generatedText", "response", "text", "output", "generated_text"}) {
                JsonNode val = node.get(field);
                if (val != null && val.isTextual()) {
                    return val.asText();
                }
            }
            JsonNode finishReason = node.get("finishReason");
            if (finishReason != null && finishReason.isTextual()
                    && finishReason.asText().toLowerCase().startsWith("error")) {
                throw new IllegalStateException("Subprocess LLM finish reason: " + finishReason.asText());
            }
            throw new IllegalStateException("Subprocess LLM response did not contain generated text");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse subprocess LLM response", e);
        }
    }

    // ==================== Helpers ====================

    /** Current UI/CLI-managed serving endpoint. Package-private for focused configuration tests. */
    String currentServingUrl() {
        String servingUrl = servingUrlResolver.get();
        if (servingUrl == null || servingUrl.isBlank()) {
            throw new IllegalStateException("Managed servingUrl is blank");
        }
        return servingUrl.trim().replaceAll("/+$", "");
    }

    private static String composePrompt(String userQuery, List<String> context) {
        if (userQuery == null) userQuery = "";
        if (context == null || context.isEmpty()) return userQuery;

        StringBuilder sb = new StringBuilder();
        sb.append("Context:\n");
        List<String> filtered = new ArrayList<>();
        for (String c : context) {
            if (c != null && !c.isBlank()) filtered.add(c);
        }
        for (int i = 0; i < filtered.size(); i++) {
            sb.append("[").append(i + 1).append("] ").append(filtered.get(i)).append("\n");
        }
        sb.append("\nUser: ").append(userQuery);
        return sb.toString();
    }

    private static String composeServingPrompt(List<Map<String, String>> messages) {
        if (messages.size() == 1 && "user".equalsIgnoreCase(messages.get(0).get("role"))) {
            return Objects.toString(messages.get(0).get("content"), "");
        }
        StringBuilder prompt = new StringBuilder();
        for (Map<String, String> message : messages) {
            String role = Objects.toString(message.get("role"), "user").trim();
            String content = Objects.toString(message.get("content"), "");
            if (content.isBlank()) {
                continue;
            }
            String label = role.isBlank()
                    ? "User"
                    : Character.toUpperCase(role.charAt(0)) + role.substring(1).toLowerCase();
            prompt.append(label).append(": ").append(content).append('\n');
        }
        prompt.append("Assistant:");
        return prompt.toString();
    }

    private static List<Map<String, String>> extractChatMessages(Prompt prompt) {
        if (prompt == null || prompt.getInstructions() == null || prompt.getInstructions().isEmpty()) {
            throw new IllegalArgumentException("Subprocess LLM prompt must contain at least one instruction");
        }

        List<Map<String, String>> messages = new ArrayList<>();
        StringBuilder otherText = new StringBuilder();

        for (Message message : prompt.getInstructions()) {
            if (message instanceof SystemMessage) {
                messages.add(chatMessage("system", message.getText()));
            } else if (message instanceof UserMessage) {
                messages.add(chatMessage("user", message.getText()));
            } else {
                if (!otherText.isEmpty()) {
                    otherText.append('\n');
                }
                otherText.append(message.getText());
            }
        }

        if (!otherText.isEmpty()) {
            messages.add(chatMessage("user", otherText.toString()));
        }
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("Subprocess LLM prompt did not contain textual content");
        }

        return messages;
    }

    private static Map<String, String> chatMessage(String role, String content) {
        Map<String, String> message = new LinkedHashMap<>();
        message.put("role", role != null && !role.isBlank() ? role : "user");
        message.put("content", content != null ? content : "");
        return message;
    }

}
