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

package ai.kompile.core.config;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Base class that eliminates JSON config persistence boilerplate shared across
 * all kompile config services.
 *
 * <p>Subclasses provide:
 * <ul>
 *   <li>The config type {@code T}</li>
 *   <li>The filename to store under {@code <dataDir>/config/<configFilename>}</li>
 *   <li>A supplier for default config values</li>
 *   <li>The data directory (typically from {@code ${kompile.data.dir}})</li>
 * </ul>
 *
 * <p>This class handles:
 * <ul>
 *   <li>Resolving the effective data directory (falls back to {@code ~/.kompile})</li>
 *   <li>{@code @PostConstruct} auto-load from disk, with fallback to defaults</li>
 *   <li>{@link #persistToDisk()} — atomic write (temp + rename) with directory creation</li>
 *   <li>Thread-safe {@code volatile} config reference</li>
 * </ul>
 *
 * @param <T> the configuration type
 */
public abstract class AbstractJsonConfigService<T> {

    protected static final Logger log = LoggerFactory.getLogger(AbstractJsonConfigService.class);

    protected static final ObjectMapper objectMapper = JsonUtils.standardMapper();

    private final Class<T> configType;
    private final Path configFilePath;
    private final Supplier<T> defaultSupplier;

    /** Current in-memory config — subclasses read/write via {@link #getConfig()} and {@link #setConfig}. */
    protected volatile T currentConfig;

    /**
     * Primary constructor.
     *
     * @param configType      Jackson-deserializable config class
     * @param configFilename  filename under {@code <dataDir>/config/}
     * @param defaultSupplier supplier for the default config value
     * @param dataDir         value of {@code ${kompile.data.dir}} — may be {@code null} or blank,
     *                        in which case {@code ~/.kompile} is used
     */
    protected AbstractJsonConfigService(Class<T> configType,
                                        String configFilename,
                                        Supplier<T> defaultSupplier,
                                        String dataDir) {
        this.configType = configType;
        this.defaultSupplier = defaultSupplier;

        String effectiveDataDir = (dataDir == null || dataDir.isBlank())
                ? System.getProperty("user.home") + "/.kompile"
                : dataDir;
        this.configFilePath = Paths.get(effectiveDataDir, "config", configFilename);
        this.currentConfig = defaultSupplier.get();
    }

    /**
     * Convenience constructor that resolves the data directory from {@code KompileHome}.
     * Subclasses that use {@code KompileHome.dataDir()} instead of {@code @Value} can call this.
     *
     * @param configType      Jackson-deserializable config class
     * @param configFilename  filename under the data dir's {@code config/} subdirectory
     * @param defaultSupplier supplier for the default config value
     * @param configFilePath  fully-resolved absolute path to the config file
     */
    protected AbstractJsonConfigService(Class<T> configType,
                                        Supplier<T> defaultSupplier,
                                        Path configFilePath) {
        this.configType = configType;
        this.defaultSupplier = defaultSupplier;
        this.configFilePath = configFilePath;
        this.currentConfig = defaultSupplier.get();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Loads persisted config from disk on startup. Subclasses may override but
     * should call {@code super.loadPersistedConfig()} first, or call
     * {@link #loadFromDisk()} themselves.
     */
    @PostConstruct
    public void loadPersistedConfig() {
        loadFromDisk();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core persistence helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reads the config file from disk and deserializes it.
     * On any error, falls back to the default config and logs a warning.
     * Does nothing (keeps defaults) if the file does not exist yet.
     */
    protected final void loadFromDisk() {
        if (!Files.exists(configFilePath)) {
            log.info("No config file found at {}, using defaults", configFilePath);
            currentConfig = defaultSupplier.get();
            return;
        }
        try {
            String json = Files.readString(configFilePath);
            currentConfig = objectMapper.readValue(json, configType);
            log.info("Loaded {} from {}", configType.getSimpleName(), configFilePath);
        } catch (Exception e) {
            log.warn("Failed to load {} from {}: {} — using defaults",
                    configType.getSimpleName(), configFilePath, e.getMessage());
            currentConfig = defaultSupplier.get();
        }
    }

    /**
     * Serializes the current config to {@link #configFilePath}.
     * Uses an atomic write (write to temp, then rename) to avoid partial writes.
     * Creates parent directories if they do not exist.
     *
     * @throws IOException if the write fails
     */
    protected final void persistToDisk() throws IOException {
        Path configDir = configFilePath.getParent();
        if (configDir != null && !Files.exists(configDir)) {
            Files.createDirectories(configDir);
        }
        String json = objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(currentConfig);

        Path tempFile = configFilePath.resolveSibling(
                configFilePath.getFileName().toString() + ".tmp." + UUID.randomUUID());
        try {
            Files.writeString(tempFile, json);
            try {
                Files.move(tempFile, configFilePath,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tempFile, configFilePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            // Clean up temp file in case the move failed
            try { Files.deleteIfExists(tempFile); } catch (IOException e) {
                log.warn("Failed to clean up temp config file {}: {}", tempFile, e.getMessage());
            }
        }
    }

    /**
     * Persists without throwing — logs the error instead.
     * Convenient for call-sites that don't propagate {@link IOException}.
     */
    protected final void persistToDiskQuietly() {
        try {
            persistToDisk();
        } catch (IOException e) {
            log.error("Failed to persist {} to {}: {}", configType.getSimpleName(), configFilePath, e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Accessors for subclasses
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns the current in-memory configuration. */
    public T getConfig() {
        return currentConfig;
    }

    /**
     * Replaces the in-memory configuration.
     *
     * @param config the new configuration (must not be {@code null})
     */
    protected final void setConfig(T config) {
        if (config == null) throw new IllegalArgumentException("config must not be null");
        this.currentConfig = config;
    }

    /** Returns the path to the config file on disk (useful for diagnostics / REST responses). */
    public String getConfigFilePath() {
        return configFilePath.toString();
    }

    /** Returns the shared {@link ObjectMapper} (for subclasses that need ad-hoc serialization). */
    protected static ObjectMapper objectMapper() {
        return objectMapper;
    }

    /** Returns the default config supplier (for subclasses that need to reset to defaults). */
    protected final T createDefaults() {
        return defaultSupplier.get();
    }
}
