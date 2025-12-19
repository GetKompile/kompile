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

package ai.kompile.core.source.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registry for source providers.
 * Auto-discovers all SourceProvider beans and provides methods to query
 * available providers for the UI.
 *
 * This allows the UI to dynamically render source options based on which
 * modules are included in the application.
 */
@Component
public class SourceProviderRegistry {

    private static final Logger logger = LoggerFactory.getLogger(SourceProviderRegistry.class);

    private final Map<String, SourceProvider> providers = new ConcurrentHashMap<>();
    private final List<SourceProvider> injectedProviders;

    @Autowired(required = false)
    public SourceProviderRegistry(List<SourceProvider> sourceProviders) {
        this.injectedProviders = sourceProviders != null ? sourceProviders : Collections.emptyList();
    }

    @PostConstruct
    public void init() {
        for (SourceProvider provider : injectedProviders) {
            registerProvider(provider);
        }
        logger.info("SourceProviderRegistry initialized with {} providers: {}",
                providers.size(),
                providers.keySet());
    }

    /**
     * Registers a source provider.
     *
     * @param provider the provider to register
     */
    public void registerProvider(SourceProvider provider) {
        String id = provider.getId();
        if (providers.containsKey(id)) {
            logger.warn("Overwriting existing source provider with id: {}", id);
        }
        providers.put(id, provider);
        logger.debug("Registered source provider: {} ({})", provider.getDisplayName(), id);
    }

    /**
     * Unregisters a source provider.
     *
     * @param providerId the provider ID to unregister
     */
    public void unregisterProvider(String providerId) {
        SourceProvider removed = providers.remove(providerId);
        if (removed != null) {
            logger.debug("Unregistered source provider: {}", providerId);
        }
    }

    /**
     * Gets a provider by ID.
     *
     * @param providerId the provider ID
     * @return the provider or null if not found
     */
    public SourceProvider getProvider(String providerId) {
        return providers.get(providerId);
    }

    /**
     * Gets all registered providers.
     *
     * @return unmodifiable collection of all providers
     */
    public Collection<SourceProvider> getAllProviders() {
        return Collections.unmodifiableCollection(providers.values());
    }

    /**
     * Gets all available (enabled) providers.
     *
     * @return list of available providers sorted by category and order
     */
    public List<SourceProvider> getAvailableProviders() {
        return providers.values().stream()
                .filter(SourceProvider::isAvailable)
                .sorted(Comparator
                        .comparing(SourceProvider::getCategory)
                        .thenComparing(SourceProvider::getOrder)
                        .thenComparing(SourceProvider::getDisplayName))
                .collect(Collectors.toList());
    }

    /**
     * Gets providers by category.
     *
     * @param category the category to filter by
     * @return list of providers in that category, sorted by order
     */
    public List<SourceProvider> getProvidersByCategory(String category) {
        return providers.values().stream()
                .filter(p -> p.isAvailable() && category.equals(p.getCategory()))
                .sorted(Comparator
                        .comparing(SourceProvider::getOrder)
                        .thenComparing(SourceProvider::getDisplayName))
                .collect(Collectors.toList());
    }

    /**
     * Gets all categories with their providers.
     *
     * @return map of category -> providers
     */
    public Map<String, List<SourceProvider>> getProvidersByCategories() {
        return getAvailableProviders().stream()
                .collect(Collectors.groupingBy(
                        SourceProvider::getCategory,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
    }

    /**
     * Checks if a provider ID exists.
     *
     * @param providerId the provider ID
     * @return true if the provider exists
     */
    public boolean hasProvider(String providerId) {
        return providers.containsKey(providerId);
    }

    /**
     * Gets the count of registered providers.
     *
     * @return number of providers
     */
    public int getProviderCount() {
        return providers.size();
    }

    /**
     * Gets the count of available providers.
     *
     * @return number of available providers
     */
    public int getAvailableProviderCount() {
        return (int) providers.values().stream().filter(SourceProvider::isAvailable).count();
    }
}
