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

package ai.kompile.chat.history.config;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Configuration properties for chat history.
 * Values are loaded from {@code <dataDir>/config/chat-history-config.json} on startup
 * and can be persisted back via {@link #persist()}.
 */
@Data
@Component
public class ChatHistoryProperties {

    private static final Logger log = LoggerFactory.getLogger(ChatHistoryProperties.class);
    private static final String CONFIG_FILENAME = "chat-history-config.json";

    @JsonIgnore
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private Path configFilePath;

    @JsonIgnore
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private ObjectMapper objectMapper;

    /**
     * Enable or disable chat history feature.
     */
    private boolean enabled = true;

    /**
     * Database type: h2, postgres, mysql, etc.
     */
    private String databaseType = "h2";

    /**
     * Path for H2 database file (when using embedded H2).
     */
    private String h2DatabasePath = "./data/chat-history";

    /**
     * JDBC URL (overrides H2 settings when specified).
     */
    private String jdbcUrl;

    /**
     * JDBC username.
     */
    private String jdbcUsername;

    /**
     * JDBC password.
     */
    private String jdbcPassword;

    /**
     * JDBC driver class name.
     */
    private String jdbcDriverClassName;

    /**
     * Maximum number of sessions to retain per user (0 = unlimited).
     */
    private int maxSessionsPerUser = 0;

    /**
     * Maximum age of sessions in days before cleanup (0 = no cleanup).
     */
    private int maxSessionAgeDays = 0;

    /**
     * Enable H2 console for debugging.
     */
    private boolean h2ConsoleEnabled = false;

    /**
     * Path to CLI conversations directory. Defaults to ~/.kompile/conversations.
     */
    private String cliConversationsPath;

    /**
     * Enable CLI transcript sync features.
     */
    private boolean cliSyncEnabled = true;

    /**
     * Interval in milliseconds between CLI transcript sync runs. Default 5 minutes.
     */
    private long cliSyncIntervalMs = 300000;

    /**
     * Maximum number of sessions to import per source per sync cycle.
     */
    private int cliSyncBatchSize = 50;

    /**
     * Enable scheduled retention cleanup of Kompile transcript .txt files.
     */
    private boolean cleanupEnabled = true;

    /**
     * Interval in milliseconds between retention cleanup runs. Default 1 hour.
     */
    private long cleanupIntervalMs = 3600000;

    /**
     * Delete transcripts older than this (days). 0 disables age-based cleanup.
     */
    private long cleanupMaxAgeDays = 90;

    /**
     * Cap total transcript directory size (MB). 0 disables size-based cleanup.
     */
    private long cleanupMaxTotalMb = 2048;

    /**
     * Maximum number of transcripts to keep (newest first). 0 disables count-based cleanup.
     */
    private int cleanupMaxPerSource = 1000;

    @Autowired
    public ChatHistoryProperties(@Value("${kompile.data.dir:#{null}}") String dataDir) {
        this.objectMapper = JsonUtils.standardMapper();
        String effectiveDataDir = (dataDir == null || dataDir.isBlank())
                ? System.getProperty("user.home") + "/.kompile"
                : dataDir;
        this.configFilePath = Paths.get(effectiveDataDir, "config", CONFIG_FILENAME);
        log.info("ChatHistoryProperties initialized, config path: {}", configFilePath);
    }

    /**
     * Loads configuration from {@code <dataDir>/config/chat-history-config.json} on startup.
     * Overlays file values onto this bean; if the file is absent or unreadable the
     * field defaults declared above are preserved unchanged.
     */
    @PostConstruct
    public void loadConfig() {
        if (!Files.exists(configFilePath)) {
            log.info("No chat-history config file found at {} - using defaults", configFilePath);
            return;
        }
        try {
            String json = Files.readString(configFilePath);
            log.info("Loading chat-history config from {} ({} bytes)", configFilePath, json.length());
            objectMapper.readerForUpdating(this).readValue(json);
            log.info("Chat-history config loaded successfully");
        } catch (IOException e) {
            log.warn("Failed to read chat-history config from {} - using defaults: {}", configFilePath, e.getMessage());
        }
    }

    /**
     * Persists the current field values to {@code <dataDir>/config/chat-history-config.json}
     * as pretty-printed JSON, creating parent directories as needed.
     */
    public void persist() {
        try {
            Path parent = configFilePath.getParent();
            if (!Files.exists(parent)) {
                Files.createDirectories(parent);
                log.info("Created config directory: {}", parent);
            }
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(this);
            Files.writeString(configFilePath, json);
            log.info("Persisted chat-history config to {}", configFilePath);
        } catch (IOException e) {
            log.error("Failed to persist chat-history config to {}: {}", configFilePath, e.getMessage(), e);
        }
    }
}
