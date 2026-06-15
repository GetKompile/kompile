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
package ai.kompile.knowledgegraph.builder.storage;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Registry for graph storage strategies.
 * Collects all available storage implementations and selects the appropriate one.
 */
@Service
@Slf4j
public class GraphStorageRegistry {

    private final List<GraphStorageStrategy> strategies;
    private final Map<String, GraphStorageStrategy> strategyMap = new HashMap<>();

    @Value("${kompile.graph-builder.storage:jpa}")
    private String defaultStorageType;

    public GraphStorageRegistry(List<GraphStorageStrategy> strategies) {
        this.strategies = strategies;
    }

    @PostConstruct
    public void init() {
        for (GraphStorageStrategy strategy : strategies) {
            strategyMap.put(strategy.getStorageType().toLowerCase(), strategy);
            log.info("Registered graph storage strategy: {} (available: {})",
                    strategy.getStorageType(), strategy.isAvailable());
        }

        log.info("Graph storage registry initialized with {} strategies. Default: {}",
                strategyMap.size(), defaultStorageType);
    }

    /**
     * Get the storage strategy by type.
     *
     * @param storageType the storage type (e.g., "jpa", "neo4j")
     * @return the storage strategy, or empty if not found
     */
    public Optional<GraphStorageStrategy> getStrategy(String storageType) {
        if (storageType == null || storageType.isEmpty()) {
            storageType = defaultStorageType;
        }
        return Optional.ofNullable(strategyMap.get(storageType.toLowerCase()));
    }

    /**
     * Get the storage strategy by type, falling back to default if not found or unavailable.
     *
     * @param storageType the requested storage type
     * @return the storage strategy (never null, falls back to default)
     */
    public GraphStorageStrategy getStrategyWithFallback(String storageType) {
        Optional<GraphStorageStrategy> strategy = getStrategy(storageType);

        if (strategy.isPresent() && strategy.get().isAvailable()) {
            return strategy.get();
        }

        // Log warning if requested strategy is unavailable
        if (strategy.isPresent()) {
            log.warn("Requested storage type '{}' is not available, falling back to '{}'",
                    storageType, defaultStorageType);
        } else if (storageType != null && !storageType.isEmpty()) {
            log.warn("Unknown storage type '{}', falling back to '{}'",
                    storageType, defaultStorageType);
        }

        // Fall back to default
        GraphStorageStrategy defaultStrategy = strategyMap.get(defaultStorageType.toLowerCase());
        if (defaultStrategy != null && defaultStrategy.isAvailable()) {
            return defaultStrategy;
        }

        // Last resort: find any available strategy
        for (GraphStorageStrategy s : strategies) {
            if (s.isAvailable()) {
                log.warn("Default storage '{}' is unavailable, using '{}'",
                        defaultStorageType, s.getStorageType());
                return s;
            }
        }

        throw new IllegalStateException("No graph storage strategy is available");
    }

    /**
     * Get all available storage types.
     *
     * @return list of available storage type identifiers
     */
    public List<String> getAvailableStorageTypes() {
        List<String> available = new ArrayList<>();
        for (GraphStorageStrategy strategy : strategies) {
            if (strategy.isAvailable()) {
                available.add(strategy.getStorageType());
            }
        }
        return available;
    }

    /**
     * Get all registered storage types (including unavailable).
     *
     * @return list of all storage type identifiers
     */
    public List<String> getAllStorageTypes() {
        return new ArrayList<>(strategyMap.keySet());
    }

    /**
     * Check if a storage type is available.
     *
     * @param storageType the storage type to check
     * @return true if the storage type exists and is available
     */
    public boolean isStorageAvailable(String storageType) {
        GraphStorageStrategy strategy = strategyMap.get(storageType.toLowerCase());
        return strategy != null && strategy.isAvailable();
    }

    /**
     * Get the default storage type.
     *
     * @return the default storage type
     */
    public String getDefaultStorageType() {
        return defaultStorageType;
    }

    /**
     * Get storage info for all registered strategies.
     *
     * @return list of storage info records
     */
    public List<StorageInfo> getStorageInfo() {
        List<StorageInfo> info = new ArrayList<>();
        for (GraphStorageStrategy strategy : strategies) {
            info.add(new StorageInfo(
                    strategy.getStorageType(),
                    strategy.isAvailable(),
                    strategy.getStorageType().equalsIgnoreCase(defaultStorageType)
            ));
        }
        return info;
    }

    /**
     * Storage info record for API responses.
     */
    public record StorageInfo(
            String storageType,
            boolean available,
            boolean isDefault
    ) {}
}
