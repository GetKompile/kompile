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

package ai.kompile.filterchain.service;

import ai.kompile.filterchain.config.FilterChainConfig;
import ai.kompile.filterchain.config.FilterConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Service for managing filter chain configuration persistence.
 * Loads configuration from JSON on startup and persists changes to disk.
 */
@Service
public class FilterChainConfigService {

    private static final Logger log = LoggerFactory.getLogger(FilterChainConfigService.class);
    private static final String CONFIG_FILENAME = "filter-chain-config.json";

    private final ObjectMapper objectMapper;
    private final Path configFilePath;

    private volatile FilterChainConfig currentConfig;

    public FilterChainConfigService(
            @Value("${kompile.data.dir:#{null}}") String dataDir) {

        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // Determine config directory
        String effectiveDataDir = dataDir;
        if (effectiveDataDir == null || effectiveDataDir.isBlank()) {
            effectiveDataDir = System.getProperty("user.home") + "/.kompile";
        }

        this.configFilePath = Paths.get(effectiveDataDir, "config", CONFIG_FILENAME);
        this.currentConfig = FilterChainConfig.defaults();

        log.info("FilterChainConfigService initialized, config path: {}", configFilePath);
    }

    /**
     * Load configuration on startup.
     */
    @PostConstruct
    public void loadConfiguration() {
        log.info("Loading filter chain configuration from: {}", configFilePath);

        if (!Files.exists(configFilePath)) {
            log.info("No persisted filter chain config found at {} - using defaults", configFilePath);
            currentConfig = FilterChainConfig.defaults();
            // Persist defaults so the file exists
            persistConfig();
            return;
        }

        try {
            String json = Files.readString(configFilePath);
            log.debug("Read filter chain config: {} bytes", json.length());

            FilterChainConfig loaded = objectMapper.readValue(json, FilterChainConfig.class);

            // Merge with defaults to ensure all fields are present
            currentConfig = FilterChainConfig.defaults().merge(loaded);

            log.info("Loaded filter chain config: enabled={}, {} filters",
                    currentConfig.isEnabled(), currentConfig.getFilters().size());

        } catch (IOException e) {
            log.error("Failed to load filter chain config from {}: {}",
                    configFilePath, e.getMessage(), e);
            currentConfig = FilterChainConfig.defaults();
        }
    }

    /**
     * Get the current configuration.
     */
    public FilterChainConfig getConfiguration() {
        return currentConfig;
    }

    /**
     * Check if filter chain is enabled.
     */
    public boolean isEnabled() {
        return currentConfig != null && currentConfig.isEnabled();
    }

    /**
     * Update the configuration.
     *
     * @param update The new configuration
     * @return The updated configuration
     */
    public FilterChainConfig updateConfiguration(FilterChainConfig update) {
        if (update == null) {
            return currentConfig;
        }

        currentConfig = currentConfig.merge(update);
        persistConfig();

        log.info("Filter chain configuration updated: enabled={}, {} filters",
                currentConfig.isEnabled(), currentConfig.getFilters().size());

        return currentConfig;
    }

    /**
     * Enable or disable the filter chain.
     */
    public FilterChainConfig setEnabled(boolean enabled) {
        currentConfig.setEnabled(enabled);
        persistConfig();
        log.info("Filter chain {}", enabled ? "enabled" : "disabled");
        return currentConfig;
    }

    /**
     * Add a filter to the configuration.
     */
    public FilterChainConfig addFilter(FilterConfig filter) {
        if (filter == null || filter.getId() == null) {
            throw new IllegalArgumentException("Filter must have an ID");
        }

        // Check for duplicate ID
        if (currentConfig.getFilter(filter.getId()) != null) {
            throw new IllegalArgumentException("Filter with ID '" + filter.getId() + "' already exists");
        }

        currentConfig.addFilter(filter);
        persistConfig();

        log.info("Added filter '{}' ({})", filter.getId(), filter.getType());
        return currentConfig;
    }

    /**
     * Update a filter in the configuration.
     */
    public FilterChainConfig updateFilter(FilterConfig filter) {
        if (filter == null || filter.getId() == null) {
            throw new IllegalArgumentException("Filter must have an ID");
        }

        if (!currentConfig.updateFilter(filter)) {
            throw new IllegalArgumentException("Filter with ID '" + filter.getId() + "' not found");
        }

        persistConfig();

        log.info("Updated filter '{}'", filter.getId());
        return currentConfig;
    }

    /**
     * Remove a filter from the configuration.
     */
    public FilterChainConfig removeFilter(String filterId) {
        if (!currentConfig.removeFilter(filterId)) {
            throw new IllegalArgumentException("Filter with ID '" + filterId + "' not found");
        }

        persistConfig();

        log.info("Removed filter '{}'", filterId);
        return currentConfig;
    }

    /**
     * Toggle a filter's enabled state.
     */
    public FilterChainConfig toggleFilter(String filterId) {
        FilterConfig filter = currentConfig.getFilter(filterId);
        if (filter == null) {
            throw new IllegalArgumentException("Filter with ID '" + filterId + "' not found");
        }

        filter.setEnabled(!filter.isEnabled());
        persistConfig();

        log.info("Filter '{}' {}", filterId, filter.isEnabled() ? "enabled" : "disabled");
        return currentConfig;
    }

    /**
     * Get all filter configurations.
     */
    public List<FilterConfig> getFilters() {
        return currentConfig.getFilters();
    }

    /**
     * Get a filter by ID.
     */
    public FilterConfig getFilter(String filterId) {
        return currentConfig.getFilter(filterId);
    }

    /**
     * Reset to default configuration.
     */
    public FilterChainConfig resetConfiguration() {
        currentConfig = FilterChainConfig.defaults();
        persistConfig();
        log.info("Filter chain configuration reset to defaults");
        return currentConfig;
    }

    /**
     * Persist configuration to disk.
     */
    private void persistConfig() {
        try {
            // Ensure directory exists
            Path parentDir = configFilePath.getParent();
            if (!Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
                log.info("Created config directory: {}", parentDir);
            }

            String json = objectMapper.writeValueAsString(currentConfig);
            Files.writeString(configFilePath, json);
            log.debug("Persisted filter chain config to {}", configFilePath);

        } catch (IOException e) {
            log.error("Failed to persist filter chain config to {}: {}",
                    configFilePath, e.getMessage(), e);
        }
    }

    /**
     * Get the path to the configuration file.
     */
    public String getConfigFilePath() {
        return configFilePath.toString();
    }
}
