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

package ai.kompile.app.config;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Managed-JSON configuration for the periodic backup service.
 * <p>
 * Configuration is loaded from {@code <dataDir>/config/backup-config.json} at
 * startup and can be persisted back via {@link #persist()}. When the file is
 * absent the class field defaults are used unchanged.
 * </p>
 */
@Data
@Component
@JsonIgnoreProperties(ignoreUnknown = true)
public class BackupProperties {

    private static final Logger log = LoggerFactory.getLogger(BackupProperties.class);
    private static final String CONFIG_FILENAME = "backup-config.json";

    @JsonIgnore
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final Path configFilePath;

    @JsonIgnore
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final ObjectMapper objectMapper;

    /**
     * Enable or disable the backup service.
     * When disabled, no scheduled or manual backups will be performed.
     */
    private boolean enabled = true;

    /**
     * Base path for backup storage.
     * Default: ~/.kompile/backups
     */
    private String backupPath = System.getProperty("user.home") + "/.kompile/backups";

    /**
     * Fixed rate interval for scheduled backups in milliseconds.
     * Default: 21600000 (6 hours)
     */
    private long fixedRateMs = 21600000;

    /**
     * Number of days to retain backups before automatic cleanup.
     * Default: 7 days
     */
    private int retentionDays = 7;

    /**
     * Backup format: COMPRESSED (tar.gz) or DIRECTORY (plain copy).
     * Default: COMPRESSED
     */
    private BackupFormat format = BackupFormat.COMPRESSED;

    /**
     * Include H2 databases in the backup.
     */
    private boolean includeDatabase = true;

    /**
     * Include Lucene indexes in the backup.
     */
    private boolean includeIndexes = true;

    /**
     * Path to the main orchestrator H2 database (without extension).
     * This is derived from spring.datasource.url.
     */
    private String orchestratorDbPath = "./data/orchestrator-db";

    /**
     * Path to the chat history H2 database (without extension).
     */
    private String chatHistoryDbPath = "./data/chat-history";

    /**
     * Path to the Anserini vector index directory.
     */
    private String vectorIndexPath = System.getProperty("user.home") + "/.kompile/anserini-vector-index";

    /**
     * Path to the text/keyword index directory.
     */
    private String textIndexPath = "./data/index";

    public BackupProperties(@Value("${kompile.data.dir:#{null}}") String dataDir) {
        String effectiveDataDir = dataDir;
        if (effectiveDataDir == null || effectiveDataDir.isBlank()) {
            effectiveDataDir = System.getProperty("user.home") + "/.kompile";
        }
        this.objectMapper = JsonUtils.newStandardMapper();
        this.configFilePath = Paths.get(effectiveDataDir, "config", CONFIG_FILENAME);
        log.info("BackupProperties initialized, config path: {}", configFilePath);
    }

    /**
     * Loads the JSON config file on startup, overlaying values onto this instance.
     * When the file is absent all field defaults are kept as-is. Never throws.
     */
    @PostConstruct
    public void init() {
        if (!Files.exists(configFilePath)) {
            log.info("No backup config found at {} — using defaults", configFilePath);
            return;
        }
        try {
            String json = Files.readString(configFilePath);
            // readerForUpdating calls setters on *this* for each present key;
            // absent keys keep their existing (default) values.
            objectMapper.readerForUpdating(this).readValue(json);
            log.info("Loaded backup config from {}: enabled={}, backupPath={}, retentionDays={}",
                    configFilePath, enabled, backupPath, retentionDays);
        } catch (IOException e) {
            log.warn("Could not read backup config from {} — using defaults: {}", configFilePath, e.getMessage());
        }
    }

    /**
     * Persists the current field values to {@code <dataDir>/config/backup-config.json}
     * as pretty-printed JSON. Parent directories are created if absent.
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
            log.info("Persisted backup config to {}", configFilePath);
        } catch (IOException e) {
            log.error("Failed to persist backup config to {}: {}", configFilePath, e.getMessage(), e);
        }
    }

    /**
     * Backup format options.
     */
    public enum BackupFormat {
        /**
         * Create a compressed tar.gz archive.
         * Smaller size, portable, but slower to create.
         */
        COMPRESSED,

        /**
         * Create a plain directory copy.
         * Faster to create, larger size, simpler recovery.
         */
        DIRECTORY
    }
}
