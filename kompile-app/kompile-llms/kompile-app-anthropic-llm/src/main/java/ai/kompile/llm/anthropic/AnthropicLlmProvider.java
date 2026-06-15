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

package ai.kompile.llm.anthropic;

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

/**
 * Anthropic LlmProvider implementation that supports model listing via Anthropic API.
 * Queries GET /v1/models to discover available Claude models dynamically.
 */
@Component
@ConditionalOnProperty(name = "spring.ai.anthropic.api-key")
@ConditionalOnClass(name = "ai.kompile.orchestrator.api.LlmProvider")
public class AnthropicLlmProvider implements LlmProvider {

    private static final Logger logger = LoggerFactory.getLogger(AnthropicLlmProvider.class);
    private static final String ANTHROPIC_API_BASE = "https://api.anthropic.com/v1";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    @Value("${spring.ai.anthropic.api-key:}")
    private String apiKey;

    private final RestTemplate restTemplate;

    public AnthropicLlmProvider() {
        this.restTemplate = new RestTemplate();
    }

    @Override
    public String getId() {
        return "anthropic";
    }

    @Override
    public String getDisplayName() {
        return "Anthropic";
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public int getPriority() {
        return 90; // High priority, slightly below OpenAI
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public int getMaxTokens() {
        return 200000; // Claude 3 context window
    }

    @Override
    public boolean supportsModelListing() {
        return true;
    }

    @Override
    public List<ModelInfo> getAvailableModels() {
        if (!isAvailable()) {
            logger.debug("Anthropic API key not configured, returning empty model list");
            return List.of();
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", ANTHROPIC_VERSION);

            HttpEntity<Void> request = new HttpEntity<>(headers);
            ResponseEntity<AnthropicModelsResponse> response = restTemplate.exchange(
                    ANTHROPIC_API_BASE + "/models",
                    HttpMethod.GET,
                    request,
                    AnthropicModelsResponse.class
            );

            if (response.getBody() == null || response.getBody().data == null) {
                logger.warn("Anthropic models API returned empty response");
                return List.of();
            }

            List<ModelInfo> models = new ArrayList<>();
            for (AnthropicModel model : response.getBody().data) {
                models.add(new ModelInfo(
                        model.id,
                        model.displayName != null ? model.displayName : formatModelName(model.id),
                        getModelDescription(model.id),
                        getContextWindow(model.id),
                        true // All Claude models support tools
                ));
            }

            // Sort by priority (newer/better models first)
            models.sort((a, b) -> {
                int priorityA = getModelPriority(a.id());
                int priorityB = getModelPriority(b.id());
                return Integer.compare(priorityB, priorityA);
            });

            logger.debug("Retrieved {} models from Anthropic API", models.size());
            return models;

        } catch (Exception e) {
            logger.error("Failed to fetch models from Anthropic API: {}", e.getMessage());
            return List.of();
        }
    }

    private String formatModelName(String modelId) {
        if (modelId.contains("claude-opus-4")) return "Claude Opus 4";
        if (modelId.contains("claude-sonnet-4")) return "Claude Sonnet 4";
        if (modelId.contains("claude-3-5-sonnet")) return "Claude 3.5 Sonnet";
        if (modelId.contains("claude-3-5-haiku")) return "Claude 3.5 Haiku";
        if (modelId.contains("claude-3-opus")) return "Claude 3 Opus";
        if (modelId.contains("claude-3-sonnet")) return "Claude 3 Sonnet";
        if (modelId.contains("claude-3-haiku")) return "Claude 3 Haiku";
        return modelId;
    }

    private String getModelDescription(String modelId) {
        if (modelId.contains("opus-4")) return "Most capable model for complex tasks";
        if (modelId.contains("sonnet-4")) return "Best balance of speed and capability";
        if (modelId.contains("3-5-sonnet")) return "Enhanced capability, great for coding";
        if (modelId.contains("3-5-haiku")) return "Fast and efficient";
        if (modelId.contains("opus")) return "Most capable model";
        if (modelId.contains("sonnet")) return "Balanced performance";
        if (modelId.contains("haiku")) return "Fast and efficient";
        return null;
    }

    private long getContextWindow(String modelId) {
        // Claude 3+ models have 200K context
        if (modelId.contains("claude-3") || modelId.contains("claude-opus-4") || modelId.contains("claude-sonnet-4")) {
            return 200000;
        }
        return 100000; // Older Claude models
    }

    private int getModelPriority(String modelId) {
        if (modelId.contains("opus-4")) return 100;
        if (modelId.contains("sonnet-4")) return 95;
        if (modelId.contains("3-5-sonnet")) return 90;
        if (modelId.contains("3-5-haiku")) return 85;
        if (modelId.contains("3-opus")) return 80;
        if (modelId.contains("3-sonnet")) return 70;
        if (modelId.contains("3-haiku")) return 60;
        return 0;
    }

    // Session management - delegate to the LanguageModel or throw unsupported
    @Override
    public LlmSession startSession(LlmSessionRequest request) {
        throw new UnsupportedOperationException("Use AnthropicLanguageModelImpl for chat sessions");
    }

    @Override
    public LlmSession sendMessage(Long sessionId, String message) {
        throw new UnsupportedOperationException("Use AnthropicLanguageModelImpl for chat sessions");
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

    // Response DTOs for Anthropic API
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class AnthropicModelsResponse {
        @JsonProperty("data")
        public List<AnthropicModel> data;

        @JsonProperty("has_more")
        public Boolean hasMore;

        @JsonProperty("first_id")
        public String firstId;

        @JsonProperty("last_id")
        public String lastId;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class AnthropicModel {
        @JsonProperty("id")
        public String id;

        @JsonProperty("display_name")
        public String displayName;

        @JsonProperty("created_at")
        public String createdAt;

        @JsonProperty("type")
        public String type;
    }
}
