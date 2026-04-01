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

package ai.kompile.llm.openai;

import ai.kompile.orchestrator.api.LlmProvider;
import ai.kompile.orchestrator.model.llm.LlmSession;
import ai.kompile.orchestrator.model.llm.LlmSessionRequest;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * OpenAI LlmProvider implementation that supports model listing via OpenAI API.
 * Queries GET /v1/models to discover available models dynamically.
 */
@Component
@ConditionalOnProperty(name = "spring.ai.openai.api-key")
@ConditionalOnClass(name = "ai.kompile.orchestrator.api.LlmProvider")
public class OpenAiLlmProvider implements LlmProvider {

    private static final Logger logger = LoggerFactory.getLogger(OpenAiLlmProvider.class);
    private static final String OPENAI_API_BASE = "https://api.openai.com/v1";

    // Chat-capable models we want to expose (filter out embedding/audio/etc models)
    private static final Set<String> CHAT_MODEL_PREFIXES = Set.of(
            "gpt-4", "gpt-3.5", "o1", "o3", "chatgpt"
    );

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url:" + OPENAI_API_BASE + "}")
    private String baseUrl;

    private final RestTemplate restTemplate;

    public OpenAiLlmProvider() {
        this.restTemplate = new RestTemplate();
    }

    @Override
    public String getId() {
        return "openai";
    }

    @Override
    public String getDisplayName() {
        return "OpenAI";
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public int getPriority() {
        return 100; // High priority
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public int getMaxTokens() {
        return 128000; // GPT-4 Turbo context window
    }

    @Override
    public boolean supportsModelListing() {
        return true;
    }

    @Override
    public List<ModelInfo> getAvailableModels() {
        if (!isAvailable()) {
            logger.debug("OpenAI API key not configured, returning empty model list");
            return List.of();
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(apiKey);

            HttpEntity<Void> request = new HttpEntity<>(headers);
            ResponseEntity<OpenAiModelsResponse> response = restTemplate.exchange(
                    baseUrl + "/models",
                    HttpMethod.GET,
                    request,
                    OpenAiModelsResponse.class
            );

            if (response.getBody() == null || response.getBody().data == null) {
                logger.warn("OpenAI models API returned empty response");
                return List.of();
            }

            List<ModelInfo> models = new ArrayList<>();
            for (OpenAiModel model : response.getBody().data) {
                // Filter to only include chat-capable models
                if (isChatModel(model.id)) {
                    models.add(new ModelInfo(
                            model.id,
                            formatModelName(model.id),
                            getModelDescription(model.id),
                            getContextWindow(model.id),
                            true // All chat models support tools
                    ));
                }
            }

            // Sort by model name (newer/better models first)
            models.sort((a, b) -> {
                // Prioritize gpt-4o, then gpt-4, then gpt-3.5
                int priorityA = getModelPriority(a.id());
                int priorityB = getModelPriority(b.id());
                return Integer.compare(priorityB, priorityA);
            });

            logger.debug("Retrieved {} chat models from OpenAI API", models.size());
            return models;

        } catch (Exception e) {
            logger.error("Failed to fetch models from OpenAI API: {}", e.getMessage());
            return List.of();
        }
    }

    private boolean isChatModel(String modelId) {
        String lower = modelId.toLowerCase();
        return CHAT_MODEL_PREFIXES.stream().anyMatch(lower::startsWith);
    }

    private String formatModelName(String modelId) {
        // Convert model IDs to display names
        return switch (modelId) {
            case "gpt-4o" -> "GPT-4o";
            case "gpt-4o-mini" -> "GPT-4o Mini";
            case "gpt-4-turbo" -> "GPT-4 Turbo";
            case "gpt-4-turbo-preview" -> "GPT-4 Turbo Preview";
            case "gpt-4" -> "GPT-4";
            case "gpt-3.5-turbo" -> "GPT-3.5 Turbo";
            case "o1" -> "O1";
            case "o1-mini" -> "O1 Mini";
            case "o1-preview" -> "O1 Preview";
            default -> modelId.toUpperCase().replace("-", " ");
        };
    }

    private String getModelDescription(String modelId) {
        return switch (modelId) {
            case "gpt-4o" -> "Most capable model, optimized for speed";
            case "gpt-4o-mini" -> "Fast and cost-effective for simpler tasks";
            case "gpt-4-turbo" -> "High capability with large context window";
            case "gpt-4" -> "High capability model";
            case "gpt-3.5-turbo" -> "Fast and efficient for most tasks";
            case "o1" -> "Advanced reasoning model";
            case "o1-mini" -> "Efficient reasoning model";
            case "o1-preview" -> "Preview of advanced reasoning";
            default -> null;
        };
    }

    private long getContextWindow(String modelId) {
        if (modelId.startsWith("gpt-4o") || modelId.startsWith("gpt-4-turbo")) {
            return 128000;
        } else if (modelId.startsWith("gpt-4")) {
            return 8192;
        } else if (modelId.startsWith("gpt-3.5")) {
            return 16385;
        } else if (modelId.startsWith("o1")) {
            return 128000;
        }
        return -1;
    }

    private int getModelPriority(String modelId) {
        if (modelId.equals("gpt-4o")) return 100;
        if (modelId.equals("gpt-4o-mini")) return 95;
        if (modelId.startsWith("o1")) return 90;
        if (modelId.startsWith("gpt-4-turbo")) return 85;
        if (modelId.startsWith("gpt-4")) return 80;
        if (modelId.startsWith("gpt-3.5")) return 50;
        return 0;
    }

    // Session management - delegate to the LanguageModel or throw unsupported
    @Override
    public LlmSession startSession(LlmSessionRequest request) {
        throw new UnsupportedOperationException("Use OpenAiLanguageModelImpl for chat sessions");
    }

    @Override
    public LlmSession sendMessage(Long sessionId, String message) {
        throw new UnsupportedOperationException("Use OpenAiLanguageModelImpl for chat sessions");
    }

    @Override
    public void cancelSession(Long sessionId) {
        // No-op
    }

    @Override
    public boolean isSessionActive(Long sessionId) {
        return false;
    }

    @Override
    public Flux<String> streamOutput(Long sessionId) {
        return Flux.empty();
    }

    // Response DTOs for OpenAI API
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class OpenAiModelsResponse {
        @JsonProperty("data")
        public List<OpenAiModel> data;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class OpenAiModel {
        @JsonProperty("id")
        public String id;

        @JsonProperty("created")
        public Long created;

        @JsonProperty("owned_by")
        public String ownedBy;
    }
}
