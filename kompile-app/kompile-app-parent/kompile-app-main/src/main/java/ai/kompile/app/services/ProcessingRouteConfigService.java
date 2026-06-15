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

package ai.kompile.app.services;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * JSON-backed config service for processing route configuration.
 *
 * <p>Persists to {@code ~/.kompile/config/processing-route-config.json}.
 * Provides default config when no file exists. Hot-reloadable — callers
 * can call {@link #reload()} to pick up external changes.</p>
 */
@Service
public class ProcessingRouteConfigService {

    private static final Logger log = LoggerFactory.getLogger(ProcessingRouteConfigService.class);
    private static final String CONFIG_FILENAME = "processing-route-config.json";

    private final ObjectMapper objectMapper;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Path configPath;

    private ProcessingRouteConfig currentConfig;

    public ProcessingRouteConfigService() {
        this(KompileHome.dataDir().toPath());
    }

    public ProcessingRouteConfigService(Path dataDir) {
        this.objectMapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
        this.configPath = dataDir.resolve("config").resolve(CONFIG_FILENAME);
    }

    @PostConstruct
    public void init() {
        loadConfig();
    }

    /**
     * Get the current processing route configuration.
     * Returns a defensive copy.
     */
    public ProcessingRouteConfig getConfig() {
        lock.readLock().lock();
        try {
            return currentConfig;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Update the processing route configuration and persist to disk.
     */
    public void updateConfig(ProcessingRouteConfig config) {
        lock.writeLock().lock();
        try {
            this.currentConfig = config;
            saveConfig();
            log.info("Processing route config updated: pdfRouting={}, fallback={}, backends={}",
                    config.getPdfRoutingMode(), config.isFallbackEnabled(),
                    config.getBackends() != null ? config.getBackends().size() : 0);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Reload config from disk (for hot-reload).
     */
    public void reload() {
        loadConfig();
    }

    /**
     * Resolve the effective config for a crawl job: use per-request config if provided,
     * otherwise fall back to the global default.
     */
    public ProcessingRouteConfig resolveForJob(ProcessingRouteConfig perJobConfig) {
        if (perJobConfig != null) {
            return perJobConfig;
        }
        return getConfig();
    }

    private void loadConfig() {
        lock.writeLock().lock();
        try {
            if (Files.exists(configPath)) {
                try {
                    currentConfig = objectMapper.readValue(configPath.toFile(), ProcessingRouteConfig.class);
                    log.info("Loaded processing route config from {}", configPath);
                } catch (IOException e) {
                    log.warn("Failed to load processing route config from {}: {}. Using defaults.",
                            configPath, e.getMessage());
                    currentConfig = createDefault();
                }
            } else {
                currentConfig = createDefault();
                saveConfig();
                log.info("Created default processing route config at {}", configPath);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void saveConfig() {
        try {
            Files.createDirectories(configPath.getParent());
            objectMapper.writeValue(configPath.toFile(), currentConfig);
        } catch (IOException e) {
            log.error("Failed to save processing route config to {}: {}", configPath, e.getMessage());
        }
    }

    private ProcessingRouteConfig createDefault() {
        return ProcessingRouteConfig.builder()
                .pdfRoutingMode(ProcessingRouteConfig.PdfRoutingMode.AUTO)
                .fallbackEnabled(false)
                .extractTablesFromTextPdfs(true)
                .textThresholdCharsPerPage(50)
                .build();
    }
}
