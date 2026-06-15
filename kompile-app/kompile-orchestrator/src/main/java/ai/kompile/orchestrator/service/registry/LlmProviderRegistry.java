/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.orchestrator.service.registry;

import ai.kompile.orchestrator.api.LlmProvider;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for LLM providers.
 */
@Slf4j
public class LlmProviderRegistry {

    private final Map<String, LlmProvider> providers = new ConcurrentHashMap<>();
    private volatile String defaultProviderId;

    /**
     * Register an LLM provider.
     */
    public synchronized void register(LlmProvider provider) {
        if (provider == null || provider.getId() == null) {
            throw new IllegalArgumentException("Provider and ID must not be null");
        }
        providers.put(provider.getId(), provider);
        log.debug("Registered LLM provider: {} ({})", provider.getId(), provider.getDisplayName());

        // Set as default if it's the first or has higher priority
        if (defaultProviderId == null) {
            defaultProviderId = provider.getId();
        } else {
            LlmProvider current = providers.get(defaultProviderId);
            if (current == null || provider.getPriority() > current.getPriority()) {
                defaultProviderId = provider.getId();
            }
        }
    }

    /**
     * Unregister an LLM provider.
     */
    public synchronized void unregister(String providerId) {
        providers.remove(providerId);
        if (providerId.equals(defaultProviderId)) {
            // Find new default
            defaultProviderId = providers.values().stream()
                    .filter(LlmProvider::isAvailable)
                    .max(Comparator.comparingInt(LlmProvider::getPriority))
                    .map(LlmProvider::getId)
                    .orElse(null);
        }
        log.debug("Unregistered LLM provider: {}", providerId);
    }

    /**
     * Get a provider by ID.
     */
    public Optional<LlmProvider> get(String providerId) {
        return Optional.ofNullable(providers.get(providerId));
    }

    /**
     * Get the default provider.
     */
    public Optional<LlmProvider> getDefault() {
        if (defaultProviderId == null) {
            return Optional.empty();
        }
        return get(defaultProviderId);
    }

    /**
     * Set the default provider.
     */
    public synchronized void setDefault(String providerId) {
        if (!providers.containsKey(providerId)) {
            throw new IllegalArgumentException("Provider not registered: " + providerId);
        }
        this.defaultProviderId = providerId;
    }

    /**
     * Get all registered providers.
     */
    public List<LlmProvider> getAll() {
        return new ArrayList<>(providers.values());
    }

    /**
     * Get all available providers.
     */
    public List<LlmProvider> getAvailable() {
        List<LlmProvider> available = new ArrayList<>();
        for (LlmProvider provider : providers.values()) {
            if (provider.isAvailable()) {
                available.add(provider);
            }
        }
        return available;
    }

    /**
     * Check if a provider exists.
     */
    public boolean exists(String providerId) {
        return providers.containsKey(providerId);
    }

    /**
     * Get the best available provider (highest priority that's available).
     */
    public Optional<LlmProvider> getBestAvailable() {
        return providers.values().stream()
                .filter(LlmProvider::isAvailable)
                .max(Comparator.comparingInt(LlmProvider::getPriority));
    }

    /**
     * Get all provider IDs.
     */
    public Set<String> getAllProviderIds() {
        return new HashSet<>(providers.keySet());
    }
}
