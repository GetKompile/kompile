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

package ai.kompile.app.sync.config;

import ai.kompile.cli.common.KompileHome;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Manages bilateral sync configuration persisted to JSON.
 * Configuration is stored at: ~/.kompile/config/note-sync-config.json
 *
 * Follows the established AppIndexConfigService pattern.
 */
@Service
public class NoteSyncConfigService {

    private static final Logger log = LoggerFactory.getLogger(NoteSyncConfigService.class);
    private static final String CONFIG_FILENAME = "note-sync-config.json";

    private final ObjectMapper objectMapper;
    private final Path configFilePath;
    private volatile NoteSyncConfig currentConfig;

    public NoteSyncConfigService() {
        this.objectMapper = new ObjectMapper();

        this.configFilePath = KompileHome.dataDir().toPath().resolve("config").resolve(CONFIG_FILENAME);
        this.currentConfig = NoteSyncConfig.defaults();

        log.info("NoteSyncConfigService initialized, config path: {}", configFilePath);
    }

    @PostConstruct
    public void loadConfig() {
        if (configFilePath == null) {
            log.warn("Cannot load note sync config - path not configured. Using defaults.");
            currentConfig = NoteSyncConfig.defaults();
            return;
        }

        if (!Files.exists(configFilePath)) {
            log.info("No persisted note sync config found - using defaults");
            currentConfig = NoteSyncConfig.defaults();
            persistConfig();
            return;
        }

        try {
            String json = Files.readString(configFilePath);
            NoteSyncConfig loaded = objectMapper.readValue(json, NoteSyncConfig.class);
            currentConfig = NoteSyncConfig.defaults().merge(loaded);
            log.info("Loaded note sync config: notionEnabled={}, obsidianEnabled={}, schedulerEnabled={}",
                    currentConfig.getNotionEnabled(),
                    currentConfig.getObsidianEnabled(),
                    currentConfig.getSchedulerEnabled());
        } catch (IOException e) {
            log.error("Failed to load note sync config: {}. Using defaults.", e.getMessage());
            currentConfig = NoteSyncConfig.defaults();
        }
    }

    public NoteSyncConfig getConfiguration() {
        return currentConfig;
    }

    public NoteSyncConfig updateConfiguration(NoteSyncConfig update) {
        currentConfig = NoteSyncConfig.defaults().merge(currentConfig).merge(update);
        persistConfig();
        log.info("Updated note sync config: notionEnabled={}, obsidianEnabled={}, schedulerEnabled={}",
                currentConfig.getNotionEnabled(),
                currentConfig.getObsidianEnabled(),
                currentConfig.getSchedulerEnabled());
        return currentConfig;
    }

    public NoteSyncConfig resetConfiguration() {
        currentConfig = NoteSyncConfig.defaults();
        persistConfig();
        log.info("Reset note sync config to defaults");
        return currentConfig;
    }

    public boolean isNotionEnabled() {
        return Boolean.TRUE.equals(currentConfig.getNotionEnabled());
    }

    public boolean isObsidianEnabled() {
        return Boolean.TRUE.equals(currentConfig.getObsidianEnabled());
    }

    public boolean isSchedulerEnabled() {
        return Boolean.TRUE.equals(currentConfig.getSchedulerEnabled());
    }

    public boolean isObsidianFileWatchEnabled() {
        return Boolean.TRUE.equals(currentConfig.getObsidianFileWatchEnabled());
    }

    public long getSchedulerCheckIntervalMs() {
        Long interval = currentConfig.getSchedulerCheckIntervalMs();
        return interval != null ? interval : 60_000L;
    }

    private void persistConfig() {
        try {
            Files.createDirectories(configFilePath.getParent());
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(currentConfig);
            Files.writeString(configFilePath, json);
        } catch (IOException e) {
            log.error("Failed to persist note sync config: {}", e.getMessage());
        }
    }
}
