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

package ai.kompile.ocr.datapipeline.api;

import ai.kompile.ocr.datapipeline.config.DataPipelineConfig;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Stores and manages pipeline configurations.
 * Persists configurations as JSON files for UI-based management.
 */
public class PipelineConfigStore {

    private static final Logger log = LoggerFactory.getLogger(PipelineConfigStore.class);

    private final Path configDir;
    private final ObjectMapper mapper;
    private final Map<String, DataPipelineConfig> cache;
    private final Map<String, DataPipelineConfig> presets;

    /**
     * Creates a config store with the specified directory.
     *
     * @param configDir Directory for storing configuration files
     */
    public PipelineConfigStore(Path configDir) {
        this.configDir = configDir;
        this.mapper = JsonUtils.newStandardMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
        this.cache = new ConcurrentHashMap<>();
        this.presets = createPresets();

        initializeDirectory();
        loadAllConfigs();
    }

    /**
     * Gets a configuration by ID.
     *
     * @param id Configuration ID
     * @return Configuration or null if not found
     */
    public DataPipelineConfig get(String id) {
        // Check presets first
        if (presets.containsKey(id)) {
            return presets.get(id);
        }
        return cache.get(id);
    }

    /**
     * Gets a configuration by ID, returning default if not found.
     */
    public DataPipelineConfig getOrDefault(String id) {
        DataPipelineConfig config = get(id);
        return config != null ? config : DataPipelineConfig.defaults();
    }

    /**
     * Lists all configurations (including presets).
     */
    public List<DataPipelineConfig> list() {
        List<DataPipelineConfig> all = new ArrayList<>();
        all.addAll(presets.values());
        all.addAll(cache.values());
        return all;
    }

    /**
     * Lists only user-created configurations.
     */
    public List<DataPipelineConfig> listUserConfigs() {
        return new ArrayList<>(cache.values());
    }

    /**
     * Lists only preset configurations.
     */
    public List<DataPipelineConfig> listPresets() {
        return new ArrayList<>(presets.values());
    }

    /**
     * Saves a configuration.
     *
     * @param config Configuration to save
     * @return Saved configuration
     * @throws IOException if save fails
     */
    public DataPipelineConfig save(DataPipelineConfig config) throws IOException {
        if (config.getId() == null) {
            config = DataPipelineConfig.builder()
                    .id(UUID.randomUUID().toString())
                    .name(config.getName())
                    .pipelineType(config.getPipelineType())
                    .preprocess(config.getPreprocess())
                    .outputParse(config.getOutputParse())
                    .entityIndex(config.getEntityIndex())
                    .custom(config.getCustom())
                    .build();
        }

        // Don't allow overwriting presets
        if (presets.containsKey(config.getId())) {
            throw new IllegalArgumentException("Cannot modify preset configuration: " + config.getId());
        }

        Path file = configDir.resolve(config.getId() + ".json");
        mapper.writeValue(file.toFile(), config);
        cache.put(config.getId(), config);

        log.info("Saved pipeline config: {} ({})", config.getName(), config.getId());
        return config;
    }

    /**
     * Deletes a configuration.
     *
     * @param id Configuration ID to delete
     * @throws IOException if delete fails
     */
    public void delete(String id) throws IOException {
        if (presets.containsKey(id)) {
            throw new IllegalArgumentException("Cannot delete preset configuration: " + id);
        }

        Path file = configDir.resolve(id + ".json");
        Files.deleteIfExists(file);
        cache.remove(id);

        log.info("Deleted pipeline config: {}", id);
    }

    /**
     * Checks if a configuration exists.
     */
    public boolean exists(String id) {
        return presets.containsKey(id) || cache.containsKey(id);
    }

    /**
     * Creates a copy of a configuration with a new ID.
     */
    public DataPipelineConfig duplicate(String id, String newName) throws IOException {
        DataPipelineConfig source = get(id);
        if (source == null) {
            throw new IllegalArgumentException("Configuration not found: " + id);
        }

        DataPipelineConfig copy = DataPipelineConfig.builder()
                .id(UUID.randomUUID().toString())
                .name(newName != null ? newName : source.getName() + " (Copy)")
                .pipelineType(source.getPipelineType())
                .preprocess(source.getPreprocess())
                .outputParse(source.getOutputParse())
                .entityIndex(source.getEntityIndex())
                .custom(source.getCustom() != null ? new HashMap<>(source.getCustom()) : null)
                .build();

        return save(copy);
    }

    /**
     * Initializes the config directory.
     */
    private void initializeDirectory() {
        try {
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
                log.info("Created pipeline config directory: {}", configDir);
            }
        } catch (IOException e) {
            log.error("Failed to create config directory: {}", e.getMessage());
        }
    }

    /**
     * Loads all configurations from disk.
     */
    private void loadAllConfigs() {
        try (Stream<Path> files = Files.list(configDir)) {
            files.filter(p -> p.toString().endsWith(".json"))
                    .forEach(this::loadConfigFile);
        } catch (IOException e) {
            log.error("Failed to load configurations: {}", e.getMessage());
        }

        log.info("Loaded {} user pipeline configurations", cache.size());
    }

    /**
     * Loads a single configuration file.
     */
    private void loadConfigFile(Path file) {
        try {
            DataPipelineConfig config = mapper.readValue(file.toFile(), DataPipelineConfig.class);
            cache.put(config.getId(), config);
        } catch (IOException e) {
            log.warn("Failed to load config file {}: {}", file, e.getMessage());
        }
    }

    /**
     * Creates preset configurations.
     */
    private Map<String, DataPipelineConfig> createPresets() {
        Map<String, DataPipelineConfig> presetMap = new LinkedHashMap<>();

        // Default preset
        DataPipelineConfig defaultPreset = DataPipelineConfig.defaults();
        defaultPreset.setId("preset-default");
        defaultPreset.setName("Default");
        presetMap.put(defaultPreset.getId(), defaultPreset);

        // DeepSeek-OCR preset
        DataPipelineConfig deepseekPreset = DataPipelineConfig.forDeepSeek();
        deepseekPreset.setId("preset-deepseek");
        presetMap.put(deepseekPreset.getId(), deepseekPreset);

        // PaddleOCR preset
        DataPipelineConfig paddlePreset = DataPipelineConfig.forPaddleOcr();
        paddlePreset.setId("preset-paddleocr");
        presetMap.put(paddlePreset.getId(), paddlePreset);

        // LayoutLM preset
        DataPipelineConfig layoutlmPreset = DataPipelineConfig.forLayoutLM();
        layoutlmPreset.setId("preset-layoutlm");
        presetMap.put(layoutlmPreset.getId(), layoutlmPreset);

        // Docling preset
        DataPipelineConfig doclingPreset = DataPipelineConfig.forDocling();
        doclingPreset.setId("preset-docling");
        presetMap.put(doclingPreset.getId(), doclingPreset);

        return presetMap;
    }

    /**
     * Gets the config directory path.
     */
    public Path getConfigDir() {
        return configDir;
    }

    /**
     * Reloads all configurations from disk.
     */
    public void reload() {
        cache.clear();
        loadAllConfigs();
    }
}
