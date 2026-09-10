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

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.oauth.service.TokenEncryptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

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
    private final TokenEncryptionService encryptionService;
    private volatile NoteSyncConfig currentConfig;

    public NoteSyncConfigService(
            TokenEncryptionService encryptionService,
            @Value("${kompile.data.dir:${user.home}/.kompile}") String dataDir) {
        this.objectMapper = JsonUtils.standardMapper();
        this.encryptionService = encryptionService;
        this.configFilePath = Path.of(dataDir, "config", CONFIG_FILENAME);
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
            boolean migratePlaintext = decryptWebhookSecret(loaded);
            currentConfig = NoteSyncConfig.defaults().merge(loaded);
            if (migratePlaintext) persistConfig();
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
            NoteSyncConfig persisted = persistedCopy(currentConfig);
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(persisted);
            Path temporary = Files.createTempFile(
                    configFilePath.getParent(), ".note-sync-config-", ".tmp");
            try {
                Files.writeString(temporary, json);
                restrictPermissions(temporary);
                try {
                    Files.move(temporary, configFilePath,
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, configFilePath, StandardCopyOption.REPLACE_EXISTING);
                }
                restrictPermissions(configFilePath);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist note sync config", e);
        }
    }

    private boolean decryptWebhookSecret(NoteSyncConfig loaded) {
        String secret = loaded.getNotionWebhookSecret();
        if (secret == null || secret.isBlank()) return false;
        if (secret.startsWith("enc:")) {
            loaded.setNotionWebhookSecret(
                    encryptionService.decrypt(secret.substring("enc:".length())));
            return false;
        }
        // Legacy plaintext is kept in memory just long enough for persistConfig to replace it.
        return true;
    }

    private NoteSyncConfig persistedCopy(NoteSyncConfig source) {
        String secret = source.getNotionWebhookSecret();
        String encryptedSecret = secret == null || secret.isBlank()
                ? "" : "enc:" + encryptionService.encrypt(secret);
        return new NoteSyncConfig(
                source.getNotionEnabled(), encryptedSecret, source.getNotionCallbackBaseUrl(),
                source.getObsidianEnabled(), source.getObsidianFileWatchEnabled(),
                source.getSchedulerEnabled(), source.getSchedulerCheckIntervalMs());
    }

    private static void restrictPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (IOException | UnsupportedOperationException ignored) {
            path.toFile().setReadable(false, false);
            path.toFile().setWritable(false, false);
            path.toFile().setReadable(true, true);
            path.toFile().setWritable(true, true);
        }
    }
}
