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

package ai.kompile.core.llm.memory;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Service for managing chat memory configuration via a JSON file in
 * {@code ~/.kompile/config/chat-memory-config.json}.
 *
 * <p>This replaces Spring {@code @ConfigurationProperties(prefix = "kompile.chat.memory")}
 * with the kompile JSON config pattern so settings are GUI/CLI configurable.</p>
 */
@Service
public class ChatMemoryConfigService {

    private static final Logger log = LoggerFactory.getLogger(ChatMemoryConfigService.class);
    private static final String CONFIG_FILENAME = "chat-memory-config.json";

    private final ObjectMapper objectMapper;
    private final Path configFilePath;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private volatile ChatMemoryConfig config;

    public ChatMemoryConfigService() {
        this.objectMapper = JsonUtils.standardMapper();
        this.configFilePath = KompileHome.dataDir().toPath().resolve("config").resolve(CONFIG_FILENAME);
        this.config = ChatMemoryConfig.defaults();
        log.info("ChatMemoryConfigService initialized, config path: {}", configFilePath);
    }

    @PostConstruct
    public void loadPersistedConfig() {
        if (!Files.exists(configFilePath)) {
            log.info("No persisted chat memory config found at {} - using defaults", configFilePath);
            return;
        }
        try {
            String json = Files.readString(configFilePath);
            ChatMemoryConfig loaded = objectMapper.readValue(json, ChatMemoryConfig.class);
            this.config = loaded;
            log.info("Loaded chat memory configuration from {}: enabled={}, maxMessages={}",
                    configFilePath, loaded.isEnabled(), loaded.getMaxMessages());
        } catch (IOException e) {
            log.error("Failed to load chat memory config from {}: {}", configFilePath, e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GETTERS
    // ═══════════════════════════════════════════════════════════════════════════

    public ChatMemoryConfig getConfig() {
        lock.readLock().lock();
        try {
            return config;
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean isEnabled() {
        return getConfig().isEnabled();
    }

    public int getMaxMessages() {
        return getConfig().getMaxMessages();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SETTERS
    // ═══════════════════════════════════════════════════════════════════════════

    public ChatMemoryConfig updateConfig(ChatMemoryConfig newConfig) {
        lock.writeLock().lock();
        try {
            this.config = newConfig;
            persistConfig();
            return this.config;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public ChatMemoryConfig resetToDefaults() {
        lock.writeLock().lock();
        try {
            this.config = ChatMemoryConfig.defaults();
            persistConfig();
            log.info("Reset chat memory config to defaults");
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
            log.info("Persisted chat memory config to {}", configFilePath);
        } catch (IOException e) {
            log.error("Failed to persist chat memory config to {}: {}", configFilePath, e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIG RECORD
    // ═══════════════════════════════════════════════════════════════════════════

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class ChatMemoryConfig {

        private boolean enabled = true;
        private int maxMessages = MessageWindowKompileChatMemory.DEFAULT_MAX_MESSAGES;

        public static ChatMemoryConfig defaults() {
            ChatMemoryConfig cfg = new ChatMemoryConfig();
            cfg.enabled = true;
            cfg.maxMessages = MessageWindowKompileChatMemory.DEFAULT_MAX_MESSAGES;
            return cfg;
        }
    }
}
