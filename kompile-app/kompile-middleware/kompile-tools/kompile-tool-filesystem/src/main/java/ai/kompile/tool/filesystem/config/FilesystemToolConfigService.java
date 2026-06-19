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

package ai.kompile.tool.filesystem.config;

import ai.kompile.cli.common.KompileHome;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Service for managing filesystem tool configuration via a JSON file in
 * {@code ~/.kompile/config/filesystem-tool-config.json}.
 *
 * <p>This replaces Spring {@code @ConfigurationProperties(prefix = "mcp.filesystem")}
 * with the kompile JSON config pattern so settings are GUI/CLI configurable.</p>
 */
@Service
public class FilesystemToolConfigService {

    private static final Logger log = LoggerFactory.getLogger(FilesystemToolConfigService.class);
    private static final String CONFIG_FILENAME = "filesystem-tool-config.json";

    private final ObjectMapper objectMapper;
    private final Path configFilePath;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private volatile FilesystemToolConfig config;

    public FilesystemToolConfigService() {
        this.objectMapper = JsonUtils.standardMapper();
        this.configFilePath = KompileHome.dataDir().toPath().resolve("config").resolve(CONFIG_FILENAME);
        this.config = FilesystemToolConfig.defaults();
        log.info("FilesystemToolConfigService initialized, config path: {}", configFilePath);
    }

    @PostConstruct
    public void loadPersistedConfig() {
        if (!Files.exists(configFilePath)) {
            log.info("No persisted filesystem tool config found at {} - using defaults", configFilePath);
            return;
        }
        try {
            String json = Files.readString(configFilePath);
            FilesystemToolConfig loaded = objectMapper.readValue(json, FilesystemToolConfig.class);
            this.config = loaded;
            log.info("Loaded filesystem tool configuration from {}: {} roots configured",
                    configFilePath, loaded.getRoots() != null ? loaded.getRoots().size() : 0);
        } catch (IOException e) {
            log.error("Failed to load filesystem tool config from {}: {}", configFilePath, e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GETTERS
    // ═══════════════════════════════════════════════════════════════════════════

    public FilesystemToolConfig getConfig() {
        lock.readLock().lock();
        try {
            return config;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns the configured roots map, or an empty map if none are configured.
     * The map key is the root name (e.g., "default") and the value is the root config.
     */
    public Map<String, FilesystemToolProperties.RootConfig> getRoots() {
        FilesystemToolConfig cfg = getConfig();
        return cfg.getRoots() != null ? cfg.getRoots() : new HashMap<>();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SETTERS
    // ═══════════════════════════════════════════════════════════════════════════

    public FilesystemToolConfig updateConfig(FilesystemToolConfig newConfig) {
        lock.writeLock().lock();
        try {
            this.config = newConfig;
            persistConfig();
            return this.config;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public FilesystemToolConfig resetToDefaults() {
        lock.writeLock().lock();
        try {
            this.config = FilesystemToolConfig.defaults();
            persistConfig();
            log.info("Reset filesystem tool config to defaults");
            return this.config;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════════

    private void persistConfig() {
        try {
            Path parentDir = configFilePath.getParent();
            if (!Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
                log.info("Created config directory: {}", parentDir);
            }
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
            Files.writeString(configFilePath, json);
            log.info("Persisted filesystem tool config to {}", configFilePath);
        } catch (IOException e) {
            log.error("Failed to persist filesystem tool config to {}: {}", configFilePath, e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIG CLASS
    // ═══════════════════════════════════════════════════════════════════════════

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FilesystemToolConfig {

        private Map<String, FilesystemToolProperties.RootConfig> roots;

        public static FilesystemToolConfig defaults() {
            FilesystemToolConfig cfg = new FilesystemToolConfig();
            Map<String, FilesystemToolProperties.RootConfig> defaultRoots = new HashMap<>();
            FilesystemToolProperties.RootConfig defaultRoot = new FilesystemToolProperties.RootConfig();
            defaultRoot.setPath("./data/shared_files");
            defaultRoot.setAlias("default");
            defaultRoots.put("default", defaultRoot);
            cfg.roots = defaultRoots;
            return cfg;
        }

        public Map<String, FilesystemToolProperties.RootConfig> getRoots() {
            return roots;
        }

        public void setRoots(Map<String, FilesystemToolProperties.RootConfig> roots) {
            this.roots = roots;
        }
    }
}
