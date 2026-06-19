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

package ai.kompile.llm.agy;

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
 * Antigravity LlmProvider implementation that supports model listing.
 * Uses the Antigravity (Gemini) Developer API to list available models.
 */
@Component
@ConditionalOnProperty(prefix = "spring.ai.vertex.ai.gemini", name = "project-id")
@ConditionalOnClass(name = "ai.kompile.orchestrator.api.LlmProvider")
public class AgyLlmProvider implements LlmProvider {

    private static final Logger logger = LoggerFactory.getLogger(AgyLlmProvider.class);
    private static final String GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta";

    @Value("${spring.ai.vertex.ai.gemini.project-id:}")
    private String projectId;

    @Value("${GOOGLE_API_KEY:}")
    private String apiKey;

    private final RestTemplate restTemplate;

    public AgyLlmProvider() {
        this.restTemplate = new RestTemplate();
    }

    @Override
    public String getId() {
        return "agy";
    }

    @Override
    public String getDisplayName() {
        return "Antigravity";
    }

    @Override
    public boolean isAvailable() {
        // Available if either Vertex AI project or API key is configured
        return (projectId != null && !projectId.isBlank()) ||
               (apiKey != null && !apiKey.isBlank());
    }

    @Override
    public int getPriority() {
        return 80;
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public int getMaxTokens() {
        return 1000000; // Antigravity supports 1M context
    }

    @Override
    public boolean supportsModelListing() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public List<ModelInfo> getAvailableModels() {
        // Model listing requires an API key (not just Vertex AI project)
        if (apiKey == null || apiKey.isBlank()) {
            logger.debug("Google API key not configured, returning static model list");
            return getStaticModelList();
        }

        try {
            String url = GEMINI_API_BASE + "/models?key=" + apiKey;
            ResponseEntity<AgyModelsResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(new HttpHeaders()),
                    AgyModelsResponse.class
            );

            if (response.getBody() == null || response.getBody().models == null) {
                logger.warn("Antigravity models API returned empty response");
                return getStaticModelList();
            }

            List<ModelInfo> models = new ArrayList<>();
            for (AgyModel model : response.getBody().models) {
                // Only include generative models (not embedding models)
                if (model.name != null && (model.name.contains("gemini") || model.name.contains("agy") || model.name.contains("antigravity"))) {
                    String modelId = model.name.replace("models/", "");
                    models.add(new ModelInfo(
                             modelId,
                             model.displayName != null ? model.displayName : modelId,
                             model.description,
                             model.inputTokenLimit != null ? model.inputTokenLimit : -1,
                             true // Antigravity models support function calling
                    ));
                }
            }

            // Sort by priority
            models.sort((a, b) -> {
                int priorityA = getModelPriority(a.id());
                int priorityB = getModelPriority(b.id());
                return Integer.compare(priorityB, priorityA);
            });

            logger.debug("Retrieved {} Antigravity models from API", models.size());
            return models;

        } catch (Exception e) {
            logger.error("Failed to fetch models from Antigravity API: {}", e.getMessage());
            return getStaticModelList();
        }
    }

    private List<ModelInfo> getStaticModelList() {
        // Return known Antigravity/Gemini models when API key is not available
        return List.of(
                new ModelInfo("agy-2.0-flash-exp", "Antigravity 2.0 Flash", "Latest experimental flash model", 1000000, true),
                new ModelInfo("agy-1.5-pro", "Antigravity 1.5 Pro", "Most capable model with 1M context", 1000000, true),
                new ModelInfo("agy-1.5-flash", "Antigravity 1.5 Flash", "Fast and efficient", 1000000, true),
                new ModelInfo("agy-1.5-flash-8b", "Antigravity 1.5 Flash 8B", "Lightweight model", 1000000, true)
        );
    }

    private int getModelPriority(String modelId) {
        if (modelId.contains("2.0")) return 100;
        if (modelId.contains("1.5-pro")) return 90;
        if (modelId.contains("1.5-flash") && !modelId.contains("8b")) return 80;
        if (modelId.contains("1.5-flash-8b")) return 70;
        if (modelId.contains("1.0-pro")) return 50;
        return 0;
    }

    // Session management - delegate to the LanguageModel or throw unsupported
    @Override
    public LlmSession startSession(LlmSessionRequest request) {
        throw new UnsupportedOperationException("Use AgyLanguageModelImpl for chat sessions");
    }

    @Override
    public LlmSession sendMessage(Long sessionId, String message) {
        throw new UnsupportedOperationException("Use AgyLanguageModelImpl for chat sessions");
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

    // Response DTOs for Antigravity API
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class AgyModelsResponse {
        @JsonProperty("models")
        public List<AgyModel> models;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class AgyModel {
        @JsonProperty("name")
        public String name;

        @JsonProperty("displayName")
        public String displayName;

        @JsonProperty("description")
        public String description;

        @JsonProperty("inputTokenLimit")
        public Long inputTokenLimit;

        @JsonProperty("outputTokenLimit")
        public Long outputTokenLimit;

        @JsonProperty("supportedGenerationMethods")
        public List<String> supportedGenerationMethods;
    }
}
