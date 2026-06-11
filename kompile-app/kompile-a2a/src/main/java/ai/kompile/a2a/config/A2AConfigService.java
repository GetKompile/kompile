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

package ai.kompile.a2a.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime configuration service for the A2A module.
 * <p>
 * Configuration is persisted to {@code ~/.kompile/config/a2a-config.json} and
 * can be changed at runtime via the REST API or CLI without restarting.
 * This replaces Spring {@code @ConditionalOnProperty} — the module is always
 * loaded but checks {@link #isEnabled()} before servicing requests.
 */
@Service
public class A2AConfigService {

    private static final Logger logger = LoggerFactory.getLogger(A2AConfigService.class);

    private static final Path CONFIG_PATH = Path.of(
            System.getProperty("user.home"), ".kompile", "config", "a2a-config.json");

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    private final AtomicReference<A2AConfig> config = new AtomicReference<>(new A2AConfig());

    @PostConstruct
    public void init() {
        loadConfig();
        logger.info("A2A config loaded: enabled={}, serverEnabled={}, maxConcurrentTasks={}",
                config.get().enabled, config.get().serverEnabled, config.get().maxConcurrentTasks);
    }

    public boolean isEnabled() {
        return config.get().enabled;
    }

    public boolean isServerEnabled() {
        return config.get().enabled && config.get().serverEnabled;
    }

    public A2AConfig getConfig() {
        return config.get();
    }

    public A2AConfig updateConfig(Map<String, Object> updates) {
        A2AConfig current = config.get();
        A2AConfig updated = current.applyUpdates(updates);
        config.set(updated);
        saveConfig(updated);
        logger.info("A2A config updated: enabled={}, serverEnabled={}", updated.enabled, updated.serverEnabled);
        return updated;
    }

    public void setEnabled(boolean enabled) {
        updateConfig(Map.of("enabled", enabled));
    }

    private void loadConfig() {
        if (!Files.exists(CONFIG_PATH)) {
            // Default: A2A enabled
            A2AConfig defaults = new A2AConfig();
            defaults.enabled = true;
            defaults.serverEnabled = true;
            config.set(defaults);
            saveConfig(defaults);
            return;
        }

        try {
            String json = Files.readString(CONFIG_PATH);
            A2AConfig loaded = objectMapper.readValue(json, A2AConfig.class);
            config.set(loaded);
        } catch (IOException e) {
            logger.warn("Failed to load A2A config, using defaults: {}", e.getMessage());
        }
    }

    private void saveConfig(A2AConfig cfg) {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(cfg);
            Files.writeString(CONFIG_PATH, json);
        } catch (IOException e) {
            logger.error("Failed to save A2A config: {}", e.getMessage());
        }
    }

    /**
     * A2A module configuration.
     */
    public static class A2AConfig {
        public boolean enabled = true;
        public boolean serverEnabled = true;
        public int maxConcurrentTasks = 10;
        public int defaultTimeoutSeconds = 300;
        public boolean autoDiscoverOnStartup = false;

        public A2AConfig applyUpdates(Map<String, Object> updates) {
            A2AConfig copy = new A2AConfig();
            copy.enabled = this.enabled;
            copy.serverEnabled = this.serverEnabled;
            copy.maxConcurrentTasks = this.maxConcurrentTasks;
            copy.defaultTimeoutSeconds = this.defaultTimeoutSeconds;
            copy.autoDiscoverOnStartup = this.autoDiscoverOnStartup;

            if (updates.containsKey("enabled"))
                copy.enabled = Boolean.parseBoolean(updates.get("enabled").toString());
            if (updates.containsKey("serverEnabled"))
                copy.serverEnabled = Boolean.parseBoolean(updates.get("serverEnabled").toString());
            if (updates.containsKey("maxConcurrentTasks"))
                copy.maxConcurrentTasks = Integer.parseInt(updates.get("maxConcurrentTasks").toString());
            if (updates.containsKey("defaultTimeoutSeconds"))
                copy.defaultTimeoutSeconds = Integer.parseInt(updates.get("defaultTimeoutSeconds").toString());
            if (updates.containsKey("autoDiscoverOnStartup"))
                copy.autoDiscoverOnStartup = Boolean.parseBoolean(updates.get("autoDiscoverOnStartup").toString());

            return copy;
        }
    }
}
