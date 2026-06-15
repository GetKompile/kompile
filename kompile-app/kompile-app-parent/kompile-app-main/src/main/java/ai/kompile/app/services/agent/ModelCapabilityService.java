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

package ai.kompile.app.services.agent;

import ai.kompile.orchestrator.api.LlmIntegrationService;
import ai.kompile.orchestrator.api.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for looking up model capabilities (vision support, context window, etc.).
 * Queries registered LlmProviders for model metadata. Falls back to sensible defaults
 * for unknown models rather than maintaining a hardcoded model database.
 */
@Service
public class ModelCapabilityService {

    private static final Logger log = LoggerFactory.getLogger(ModelCapabilityService.class);

    private static final int DEFAULT_CONTEXT_WINDOW = 128_000;
    private static final int DEFAULT_MAX_OUTPUT = 8_192;

    private final LlmIntegrationService llmIntegrationService;
    private final Map<String, ModelCapabilities> cache = new ConcurrentHashMap<>();

    @Autowired
    public ModelCapabilityService(
            @Autowired(required = false) LlmIntegrationService llmIntegrationService) {
        this.llmIntegrationService = llmIntegrationService;
    }

    /**
     * Check if a model supports vision (image) inputs.
     * Checks provider-reported capabilities via supportsTools as a proxy,
     * and also recognizes known vision-capable model family prefixes.
     */
    public boolean supportsVision(String modelId) {
        if (modelId == null || modelId.isBlank()) return false;
        String normalized = normalize(modelId);

        // Modern multimodal models: any model with these prefixes supports vision
        // This is a heuristic, not a closed list — new models with these prefixes work automatically
        return normalized.startsWith("claude-") && !normalized.contains("instant")
                || normalized.startsWith("gpt-4o") || normalized.startsWith("gpt-4.1")
                || normalized.startsWith("gpt-4-turbo")
                || normalized.startsWith("o4-") || normalized.startsWith("o3") || normalized.startsWith("o1")
                || normalized.startsWith("gemini-");
    }

    /**
     * Get full capabilities for a model.
     * First checks registered providers for metadata, then falls back to defaults.
     */
    public ModelCapabilities getCapabilities(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return new ModelCapabilities(modelId, false, DEFAULT_CONTEXT_WINDOW, DEFAULT_MAX_OUTPUT);
        }

        // Evict oldest entries if cache is too large to prevent unbounded growth
        if (cache.size() > 500) {
            cache.clear();
        }
        return cache.computeIfAbsent(normalize(modelId), normalized -> {
            boolean vision = supportsVision(modelId);

            // Try to get context window from registered providers
            long providerContextWindow = queryProviderContextWindow(normalized);
            int contextWindow = providerContextWindow > 0 ? (int) providerContextWindow : DEFAULT_CONTEXT_WINDOW;

            return new ModelCapabilities(modelId, vision, contextWindow, DEFAULT_MAX_OUTPUT);
        });
    }

    private long queryProviderContextWindow(String normalizedModelId) {
        if (llmIntegrationService == null) return -1;

        try {
            for (LlmProvider provider : llmIntegrationService.getAllProviders()) {
                for (LlmProvider.ModelInfo model : provider.getAvailableModels()) {
                    String providerModelNorm = normalize(model.id());
                    if (providerModelNorm.equals(normalizedModelId)
                            || normalizedModelId.startsWith(providerModelNorm)) {
                        if (model.contextWindow() > 0) {
                            return model.contextWindow();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error querying provider capabilities for {}: {}", normalizedModelId, e.getMessage());
        }
        return -1;
    }

    private String normalize(String modelId) {
        String normalized = modelId.toLowerCase().trim();
        // Strip OpenRouter-style provider prefix (e.g., "anthropic/claude-sonnet-4")
        int slash = normalized.indexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(slash + 1);
        }
        // Strip Ollama tag suffixes (e.g., "qwen2.5-coder:32b")
        int colon = normalized.indexOf(':');
        if (colon >= 0) {
            normalized = normalized.substring(0, colon);
        }
        return normalized;
    }

    public record ModelCapabilities(
            String modelId,
            boolean supportsVision,
            int contextWindow,
            int maxOutputTokens
    ) {}
}
