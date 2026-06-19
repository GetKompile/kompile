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

package ai.kompile.anserini.config;

import ai.kompile.cli.common.KompileHome;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Service for managing Anserini configuration via a JSON file in
 * {@code ~/.kompile/config/anserini-config.json}.
 *
 * <p>This replaces Spring {@code @ConfigurationProperties(prefix = "anserini")}
 * with the kompile JSON config pattern so settings are GUI/CLI configurable.</p>
 *
 * <p>The GraalVM AOT runtime hints previously on {@code AnseriniConfig} have been moved here
 * since this is the active Spring bean for the Anserini configuration.</p>
 */
@ImportRuntimeHints(AnseriniConfig.AnseriniReflectionHints.class)
@Service
public class AnseriniConfigService {

    private static final Logger log = LoggerFactory.getLogger(AnseriniConfigService.class);
    private static final String CONFIG_FILENAME = "anserini-config.json";

    private final ObjectMapper objectMapper;
    private final Path configFilePath;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private volatile AnseriniJsonConfig config;

    public AnseriniConfigService() {
        this.objectMapper = JsonUtils.standardMapper();
        this.configFilePath = KompileHome.dataDir().toPath().resolve("config").resolve(CONFIG_FILENAME);
        this.config = AnseriniJsonConfig.defaults();
        log.info("AnseriniConfigService initialized, config path: {}", configFilePath);
    }

    /**
     * Constructor for use in tests or when explicit paths are needed without a JSON file.
     * The {@link #loadPersistedConfig()} method will still be a no-op if the file does not exist.
     *
     * @param indexPath  the initial Anserini keyword index path (may be null)
     * @param corpusPath the initial Anserini corpus staging path (may be null)
     */
    public AnseriniConfigService(String indexPath, String corpusPath) {
        this.objectMapper = JsonUtils.standardMapper();
        this.configFilePath = KompileHome.dataDir().toPath().resolve("config").resolve(CONFIG_FILENAME);
        AnseriniJsonConfig cfg = new AnseriniJsonConfig();
        cfg.setIndexPath(indexPath);
        cfg.setCorpusPath(corpusPath != null ? corpusPath : "./data/anserini_corpus_json_staging");
        this.config = cfg;
        log.debug("AnseriniConfigService initialized with explicit paths: indexPath={}, corpusPath={}", indexPath, corpusPath);
    }

    @PostConstruct
    public void loadPersistedConfig() {
        if (!Files.exists(configFilePath)) {
            log.info("No persisted Anserini config found at {} - using defaults", configFilePath);
            return;
        }
        try {
            String json = Files.readString(configFilePath);
            AnseriniJsonConfig loaded = objectMapper.readValue(json, AnseriniJsonConfig.class);
            this.config = loaded;
            log.info("Loaded Anserini configuration from {}: indexPath={}, corpusPath={}",
                    configFilePath, loaded.getIndexPath(), loaded.getCorpusPath());
        } catch (IOException e) {
            log.error("Failed to load Anserini config from {}: {}", configFilePath, e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GETTERS
    // ═══════════════════════════════════════════════════════════════════════════

    public AnseriniJsonConfig getConfig() {
        lock.readLock().lock();
        try {
            return config;
        } finally {
            lock.readLock().unlock();
        }
    }

    public String getIndexPath() {
        return getConfig().getIndexPath();
    }

    public String getCorpusPath() {
        return getConfig().getCorpusPath();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SETTERS
    // ═══════════════════════════════════════════════════════════════════════════

    public AnseriniJsonConfig updateConfig(AnseriniJsonConfig newConfig) {
        lock.writeLock().lock();
        try {
            this.config = newConfig;
            persistConfig();
            return this.config;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public AnseriniJsonConfig resetToDefaults() {
        lock.writeLock().lock();
        try {
            this.config = AnseriniJsonConfig.defaults();
            persistConfig();
            log.info("Reset Anserini config to defaults");
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
            log.info("Persisted Anserini config to {}", configFilePath);
        } catch (IOException e) {
            log.error("Failed to persist Anserini config to {}: {}", configFilePath, e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIG CLASS
    // ═══════════════════════════════════════════════════════════════════════════

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AnseriniJsonConfig {

        private String indexPath;
        private String corpusPath;

        public static AnseriniJsonConfig defaults() {
            AnseriniJsonConfig cfg = new AnseriniJsonConfig();
            cfg.indexPath = null;
            cfg.corpusPath = "./data/anserini_corpus_json_staging";
            return cfg;
        }

        public String getIndexPath() {
            return indexPath;
        }

        public void setIndexPath(String indexPath) {
            this.indexPath = indexPath;
        }

        public String getCorpusPath() {
            return corpusPath;
        }

        public void setCorpusPath(String corpusPath) {
            this.corpusPath = corpusPath;
        }
    }
}
