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

package ai.kompile.embedding.anserini.config;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Loads and persists the embedding-subprocess restart-governor configuration.
 *
 * <p>Persists to {@code ~/.kompile/config/embedding-restart-config.json}, using the same
 * {@code kompile.data.dir} resolution as the other kompile JSON config services so the file
 * lives next to {@code device-routing-config.json} and is read by both the running app (live)
 * and the CLI (on next start). Stored values are always normalized to concrete (non-null)
 * fields so the file round-trips cleanly.</p>
 */
@Service
public class EmbeddingRestartConfigService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingRestartConfigService.class);
    private static final String CONFIG_FILENAME = "embedding-restart-config.json";

    private final ObjectMapper objectMapper;
    private final Path configFilePath;

    private volatile EmbeddingRestartConfig currentConfig;

    public EmbeddingRestartConfigService(
            @Value("${kompile.data.dir:#{null}}") String dataDir) {
        this.objectMapper = JsonUtils.standardMapper();

        String effectiveDataDir = dataDir;
        if (effectiveDataDir == null || effectiveDataDir.isBlank()) {
            effectiveDataDir = System.getProperty("user.home") + "/.kompile";
        }
        this.configFilePath = Paths.get(effectiveDataDir, "config", CONFIG_FILENAME);
        this.currentConfig = EmbeddingRestartConfig.defaults();
    }

    @PostConstruct
    public void loadPersistedConfig() {
        if (Files.exists(configFilePath)) {
            try {
                String json = Files.readString(configFilePath);
                currentConfig = normalize(objectMapper.readValue(json, EmbeddingRestartConfig.class));
                log.info("Loaded embedding restart config from {}: autoRestartEnabled={}, nativeCrashThreshold={}",
                        configFilePath, currentConfig.isAutoRestartEnabledOrDefault(),
                        currentConfig.nativeCrashThresholdOrDefault());
            } catch (Exception e) {
                log.warn("Failed to load embedding restart config from {}: {}", configFilePath, e.getMessage());
                currentConfig = EmbeddingRestartConfig.defaults();
            }
        } else {
            log.info("No embedding restart config at {}, using defaults (auto-restart enabled, threshold={})",
                    configFilePath, EmbeddingRestartConfig.DEFAULT_NATIVE_CRASH_THRESHOLD);
        }
    }

    public EmbeddingRestartConfig getConfig() {
        return currentConfig;
    }

    public synchronized EmbeddingRestartConfig save(EmbeddingRestartConfig config) throws IOException {
        EmbeddingRestartConfig normalized = normalize(config);
        this.currentConfig = normalized;
        Files.createDirectories(configFilePath.getParent());
        Files.writeString(configFilePath,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(normalized));
        log.info("Saved embedding restart config: autoRestartEnabled={}, nativeCrashThreshold={}",
                normalized.isAutoRestartEnabledOrDefault(), normalized.nativeCrashThresholdOrDefault());
        return normalized;
    }

    /** Resolve every field to a concrete, valid value so the persisted file is unambiguous. */
    private EmbeddingRestartConfig normalize(EmbeddingRestartConfig config) {
        if (config == null) {
            return EmbeddingRestartConfig.defaults();
        }
        return EmbeddingRestartConfig.builder()
                .autoRestartEnabled(config.isAutoRestartEnabledOrDefault())
                .nativeCrashThreshold(config.nativeCrashThresholdOrDefault())
                .build();
    }
}
