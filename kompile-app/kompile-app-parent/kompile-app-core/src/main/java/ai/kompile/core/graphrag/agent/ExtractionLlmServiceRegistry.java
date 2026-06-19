/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.core.graphrag.agent;

import ai.kompile.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry for all {@link ExtractionLlmService} providers.
 *
 * <p>Collects all Spring-registered {@link ExtractionLlmService} beans
 * and any dynamically registered providers (e.g., CLI agents discovered at startup).
 * The extraction agent selects a provider by ID from this registry.
 */
@Component
public class ExtractionLlmServiceRegistry {

    private static final Logger log = LoggerFactory.getLogger(ExtractionLlmServiceRegistry.class);

    private final Map<String, ExtractionLlmService> providers = new ConcurrentHashMap<>();

    @Autowired(required = false)
    public void setProviders(List<ExtractionLlmService> services) {
        if (services != null) {
            for (ExtractionLlmService service : services) {
                register(service);
            }
        }
    }

    /**
     * Register a provider. Can be called at any time (startup or runtime).
     */
    public void register(ExtractionLlmService service) {
        providers.put(service.getId(), service);
        log.info("Registered extraction LLM provider: {} ({})", service.getId(), service.getDescription());
    }

    /**
     * Get a provider by ID, or null if not found.
     */
    public ExtractionLlmService get(String id) {
        return providers.get(id);
    }

    /**
     * Get the first available provider, preferring the given ID.
     * Falls back to any available provider if the requested one is missing or unavailable.
     *
     * <p>When CLI fallback is enabled, the returned service also performs call-time
     * fallback for provider limit/capacity failures. Availability checks only catch
     * missing binaries; quota and rate-limit failures are discovered when a prompt is
     * executed, so the fallback needs to sit around {@link ExtractionLlmService#complete(String)}.
     */
    public ExtractionLlmService getOrFallback(String preferredId) {
        String resolvedPreferredId = resolvePreferredProviderId(preferredId);
        List<ExtractionLlmService> candidates = new ArrayList<>();
        if (resolvedPreferredId != null) {
            ExtractionLlmService preferred = providers.get(resolvedPreferredId);
            if (preferred != null && preferred.isAvailable()) {
                candidates.add(preferred);
            } else {
                log.warn("Requested extraction LLM provider '{}' is not available, falling back", resolvedPreferredId);
            }
        }

        providers.values().stream()
                .sorted(Comparator.comparingInt(service -> fallbackPriority(service.getId())))
                .filter(ExtractionLlmService::isAvailable)
                .filter(service -> candidates.stream().noneMatch(existing -> existing.getId().equals(service.getId())))
                .forEach(candidates::add);

        if (candidates.isEmpty()) {
            return null;
        }
        if (fallbackEnabled() && candidates.size() > 1) {
            return new FallbackExtractionLlmService(candidates, fallbackLimitOnly());
        }
        return candidates.get(0);
    }

    private String resolvePreferredProviderId(String preferredId) {
        if (preferredId != null && !preferredId.isBlank() && !"default".equalsIgnoreCase(preferredId)) {
            return preferredId;
        }
        // Check system property / env var first
        String configured = System.getProperty("kompile.cli-agent.default");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("KOMPILE_CLI_AGENT_DEFAULT");
        }
        // Fall back to cli-llm-config.json command setting
        if (configured == null || configured.isBlank()) {
            configured = readCliLlmConfigCommand();
        }
        if (configured == null || configured.isBlank()) {
            return preferredId;
        }
        String normalized = configured.trim();
        if (providers.containsKey(normalized)) {
            return normalized;
        }
        if (!normalized.endsWith("-cli") && providers.containsKey(normalized + "-cli")) {
            return normalized + "-cli";
        }
        return normalized;
    }

    /**
     * Read the configured CLI agent command from ~/.kompile/config/cli-llm-config.json.
     * Returns null if the file doesn't exist or can't be read.
     */
    private String readCliLlmConfigCommand() {
        try {
            java.nio.file.Path configPath = java.nio.file.Path.of(
                    System.getProperty("user.home"), ".kompile", "config", "cli-llm-config.json");
            if (!java.nio.file.Files.exists(configPath)) {
                return null;
            }
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(configPath.toFile());
            if (root.has("command") && !root.get("command").isNull()) {
                String command = root.get("command").asText();
                if (!command.isBlank()) {
                    log.debug("Resolved default extraction LLM from cli-llm-config.json: {}", command);
                    return command;
                }
            }
        } catch (Exception e) {
            log.warn("Could not read cli-llm-config.json: {}", e.getMessage());
        }
        return null;
    }

    private int fallbackPriority(String id) {
        String normalized = id != null ? id.toLowerCase(Locale.ROOT) : "";
        if (normalized.contains("codex")) return 0;
        if (normalized.contains("claude")) return 1;
        if (normalized.contains("gemini")) return 2;
        return 10;
    }

    private boolean fallbackEnabled() {
        String configured = System.getProperty("kompile.cli-agent.fallback-enabled");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("KOMPILE_CLI_AGENT_FALLBACK_ENABLED");
        }
        return configured == null || configured.isBlank() || Boolean.parseBoolean(configured);
    }

    private boolean fallbackLimitOnly() {
        String configured = System.getProperty("kompile.cli-agent.fallback-limit-only");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("KOMPILE_CLI_AGENT_FALLBACK_LIMIT_ONLY");
        }
        return configured == null || configured.isBlank() || Boolean.parseBoolean(configured);
    }

    private static final class FallbackExtractionLlmService implements ExtractionLlmService {

        private final List<ExtractionLlmService> candidates;
        private final boolean limitOnly;

        private FallbackExtractionLlmService(List<ExtractionLlmService> candidates, boolean limitOnly) {
            this.candidates = List.copyOf(candidates);
            this.limitOnly = limitOnly;
        }

        @Override
        public String getId() {
            return candidates.get(0).getId();
        }

        @Override
        public String getDescription() {
            return candidates.get(0).getDescription() + " with limit fallback";
        }

        @Override
        public String complete(String prompt) {
            List<String> failures = new ArrayList<>();
            for (ExtractionLlmService candidate : candidates) {
                try {
                    String result = candidate.complete(prompt);
                    if (result != null && !result.isBlank()) {
                        if (!failures.isEmpty()) {
                            log.info("Extraction LLM fallback succeeded with provider '{}' after: {}",
                                    candidate.getId(), String.join(" | ", failures));
                        }
                        return result;
                    }
                    String failure = candidate.getId() + ": returned no text content";
                    failures.add(failure);
                    if (!shouldFallback("returned no text content")) {
                        throw new ExtractionLlmException(failure);
                    }
                    log.warn("Extraction LLM provider '{}' returned no text content; trying fallback provider",
                            candidate.getId());
                } catch (ExtractionLlmException e) {
                    String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    failures.add(candidate.getId() + ": " + StringUtils.truncate(message, 400));
                    if (!shouldFallback(message)) {
                        throw e;
                    }
                    log.warn("Extraction LLM provider '{}' hit a recoverable limit/capacity failure; trying fallback provider. Error: {}",
                            candidate.getId(), StringUtils.truncate(message, 800));
                }
            }
            throw new ExtractionLlmException("All extraction LLM providers failed. " + String.join(" | ", failures));
        }

        @Override
        public boolean isAvailable() {
            return candidates.stream().anyMatch(ExtractionLlmService::isAvailable);
        }

        @Override
        public void setModelOverride(String model) {
            for (ExtractionLlmService candidate : candidates) {
                candidate.setModelOverride(model);
            }
        }

        @Override
        public String getEffectiveModel() {
            return candidates.isEmpty() ? null : candidates.get(0).getEffectiveModel();
        }

        private boolean shouldFallback(String message) {
            String lower = message != null ? message.toLowerCase(Locale.ROOT) : "";
            boolean limitFailure = lower.contains("terminalquotaerror")
                    || lower.contains("insufficient_quota")
                    || lower.contains("insufficient balance")
                    || lower.contains("quota exceeded")
                    || lower.contains("usage limit")
                    || lower.contains("rate limit")
                    || lower.contains("429")
                    || lower.contains("too many requests")
                    || lower.contains("capacity")
                    || lower.contains("you've hit your limit")
                    || lower.contains("you have hit your limit")
                    || lower.contains("you've hit your usage limit")
                    || lower.contains("you have hit your usage limit");
            if (limitFailure) {
                return true;
            }
            if (limitOnly) {
                return false;
            }
            return lower.contains("timed out")
                    || lower.contains("returned no text content")
                    || lower.contains("returned no output")
                    || lower.contains("failed to read agent output")
                    || lower.contains("error executing cli agent");
        }

    }

    /**
     * Set the model for a specific provider at runtime.
     * This is used when the user changes the model via the UI/API.
     *
     * @param providerId the provider ID (e.g., "claude-cli", "codex-cli")
     * @param model the model name to set
     * @return true if the provider was found and updated, false if provider not found
     */
    public boolean setProviderModel(String providerId, String model) {
        ExtractionLlmService service = providers.get(providerId);
        if (service != null) {
            log.info("Setting model for provider '{}': {}", providerId, model);
            service.setModelOverride(model);
            return true;
        } else {
            log.warn("Cannot set model for unknown provider: {}", providerId);
            return false;
        }
    }

    /**
     * List all registered providers with their availability status.
     */
    public List<ProviderInfo> listProviders() {
        List<ProviderInfo> result = new ArrayList<>();
        for (ExtractionLlmService service : providers.values()) {
            result.add(new ProviderInfo(
                    service.getId(),
                    service.getDescription(),
                    service.isAvailable(),
                    service.getEffectiveModel()
            ));
        }
        result.sort(Comparator.comparing(ProviderInfo::id));
        return result;
    }

    /**
     * Info about a registered provider.
     */
    public record ProviderInfo(String id, String description, boolean available, String effectiveModel) {}

    /**
     * Configuration for fallback behavior when a preferred provider fails.
     *
     * @param enabled       whether fallback is enabled
     * @param limitOnly     only fallback on limit/quota errors (not on general failures)
     * @param providerOrder explicit provider priority ordering
     * @param timeoutSeconds timeout for each provider attempt
     */
    public record FallbackConfig(boolean enabled, boolean limitOnly,
                                  List<String> providerOrder, int timeoutSeconds) {}

    private volatile FallbackConfig fallbackConfig = new FallbackConfig(true, false, List.of(), 300);

    /**
     * Set the fallback configuration.
     */
    public void setFallbackConfig(FallbackConfig config) {
        this.fallbackConfig = config;
        log.info("Updated fallback config: enabled={}, limitOnly={}, order={}, timeout={}s",
                config.enabled(), config.limitOnly(), config.providerOrder(), config.timeoutSeconds());
    }

    /**
     * Get the current fallback configuration.
     */
    public FallbackConfig getFallbackConfig() {
        return fallbackConfig;
    }
}
