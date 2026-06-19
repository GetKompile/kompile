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

package ai.kompile.modelmanager.vlm.registry;

import ai.kompile.modelmanager.vlm.*;
import ai.kompile.modelmanager.vlm.dynamic.*;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for managing VLM pipelines, stages, and model sets.
 *
 * <p>Provides CRUD operations for dynamic VLM configurations with JSON persistence
 * following the BatchSizeConfigService pattern. Configuration files are stored at
 * {@code ~/.kompile/config/vlm-*.json}.</p>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Automatic registration of builtin items from enum values</li>
 *   <li>JSON persistence for custom pipelines, stages, and model sets</li>
 *   <li>Validation at registration time</li>
 *   <li>Thread-safe operations</li>
 * </ul>
 *
 * <h2>Configuration Files</h2>
 * <ul>
 *   <li>{@code vlm-pipelines.json} - Custom pipeline definitions</li>
 *   <li>{@code vlm-stages.json} - Custom stage definitions</li>
 *   <li>{@code vlm-model-sets.json} - Custom model sets</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * VlmPipelineRegistry registry = VlmPipelineRegistry.getInstance();
 *
 * // Get all pipelines (builtin + custom)
 * Collection<VlmPipelineDefinition> pipelines = registry.getAllPipelines();
 *
 * // Register custom pipeline
 * VlmPipelineDefinition custom = VlmPipelineDefinition.builder()
 *     .pipelineId("my-custom-pipeline")
 *     .displayName("My Custom Pipeline")
 *     .build();
 * registry.registerPipeline(custom);
 * }</pre>
 *
 * @author Kompile Inc.
 */
public class VlmPipelineRegistry {

    private static final Logger log = LoggerFactory.getLogger(VlmPipelineRegistry.class);

    // Config file names
    private static final String PIPELINES_CONFIG = "vlm-pipelines.json";
    private static final String STAGES_CONFIG = "vlm-stages.json";
    private static final String MODEL_SETS_CONFIG = "vlm-model-sets.json";

    // Registries
    private final Map<String, VlmPipelineDefinition> pipelines = new ConcurrentHashMap<>();
    private final Map<String, VlmStageDefinition> stages = new ConcurrentHashMap<>();
    private final Map<String, VlmCustomModelSet> modelSets = new ConcurrentHashMap<>();

    // Configuration
    private final Path configDir;
    private final ObjectMapper objectMapper;

    // Singleton instance
    private static volatile VlmPipelineRegistry instance;

    /**
     * Get the singleton instance with default config directory (~/.kompile/config).
     */
    public static VlmPipelineRegistry getInstance() {
        if (instance == null) {
            synchronized (VlmPipelineRegistry.class) {
                if (instance == null) {
                    String home = System.getProperty("user.home");
                    Path configDir = Paths.get(home, ".kompile", "config");
                    instance = new VlmPipelineRegistry(configDir);
                }
            }
        }
        return instance;
    }

    /**
     * Create a registry with custom config directory.
     * Used for testing or alternative configurations.
     */
    public static VlmPipelineRegistry create(Path configDir) {
        return new VlmPipelineRegistry(configDir);
    }

    private VlmPipelineRegistry(Path configDir) {
        this.configDir = configDir;
        this.objectMapper = JsonUtils.newStandardMapper().enable(SerializationFeature.INDENT_OUTPUT);

        // Initialize with builtins
        initializeBuiltins();

        // Load persisted configs
        loadPersistedConfigs();
    }

    // =====================================================================
    // INITIALIZATION
    // =====================================================================

    /**
     * Initialize builtin items from existing enum values.
     */
    private void initializeBuiltins() {
        // Register builtin stages from VlmPipelineStage enum
        for (VlmPipelineStage stage : VlmPipelineStage.values()) {
            VlmStageDefinition def = VlmStageDefinition.fromEnum(stage);
            stages.put(def.getStageId(), def);
        }
        log.info("Registered {} builtin stages", VlmPipelineStage.values().length);

        // Register builtin model sets from VlmModelSet
        for (VlmModelSet modelSet : VlmModelSet.getAllModelSets()) {
            VlmCustomModelSet custom = VlmCustomModelSet.fromBuiltin(modelSet);
            modelSets.put(custom.getSetId(), custom);
        }
        log.info("Registered {} builtin model sets", VlmModelSet.getAllModelSets().size());

        // Register builtin pipelines from VlmExtractionConfig presets
        registerBuiltinPipeline("scanned-documents", "Scanned Documents",
            "Full VLM processing for scanned PDFs and images",
            VlmExtractionConfig.forScannedDocuments());

        registerBuiltinPipeline("text-pdfs", "Text PDFs",
            "Text extraction with table support",
            VlmExtractionConfig.forTextPdfs());

        registerBuiltinPipeline("scientific-papers", "Scientific Papers",
            "Document understanding with figures and tables",
            VlmExtractionConfig.forScientificPapers());

        registerBuiltinPipeline("forms", "Forms & Invoices",
            "Form field extraction with document understanding",
            VlmExtractionConfig.forForms());

        registerBuiltinPipeline("image-documents", "Image Documents",
            "Image embedding and figure understanding",
            VlmExtractionConfig.forImageDocuments());

        registerBuiltinPipeline("comprehensive", "Comprehensive",
            "All extraction types enabled",
            VlmExtractionConfig.comprehensive());

        log.info("Registered {} builtin pipelines", 6);
    }

    private void registerBuiltinPipeline(String id, String displayName, String description,
                                          VlmExtractionConfig config) {
        VlmPipelineDefinition pipeline = VlmPipelineDefinition.fromExtractionConfig(id, displayName, config);
        pipeline.setDescription(description);
        pipelines.put(id, pipeline);
    }

    /**
     * Load persisted configurations from JSON files.
     */
    private void loadPersistedConfigs() {
        loadPipelines();
        loadStages();
        loadModelSets();
    }

    private void loadPipelines() {
        Path path = configDir.resolve(PIPELINES_CONFIG);
        if (!Files.exists(path)) {
            log.debug("No persisted pipelines config found at {}", path);
            return;
        }

        try {
            String json = Files.readString(path);
            List<VlmPipelineDefinition> loaded = objectMapper.readValue(json,
                new TypeReference<List<VlmPipelineDefinition>>() {});

            int count = 0;
            for (VlmPipelineDefinition pipeline : loaded) {
                // Skip builtins - they're already registered
                if (!pipeline.isBuiltin()) {
                    pipelines.put(pipeline.getPipelineId(), pipeline);
                    count++;
                }
            }
            log.info("Loaded {} custom pipelines from {}", count, path);
        } catch (IOException e) {
            log.error("Failed to load pipelines config from {}: {}", path, e.getMessage());
        }
    }

    private void loadStages() {
        Path path = configDir.resolve(STAGES_CONFIG);
        if (!Files.exists(path)) {
            log.debug("No persisted stages config found at {}", path);
            return;
        }

        try {
            String json = Files.readString(path);
            List<VlmStageDefinition> loaded = objectMapper.readValue(json,
                new TypeReference<List<VlmStageDefinition>>() {});

            int count = 0;
            for (VlmStageDefinition stage : loaded) {
                if (!stage.isBuiltin()) {
                    stages.put(stage.getStageId(), stage);
                    count++;
                }
            }
            log.info("Loaded {} custom stages from {}", count, path);
        } catch (IOException e) {
            log.error("Failed to load stages config from {}: {}", path, e.getMessage());
        }
    }

    private void loadModelSets() {
        Path path = configDir.resolve(MODEL_SETS_CONFIG);
        if (!Files.exists(path)) {
            log.debug("No persisted model sets config found at {}", path);
            return;
        }

        try {
            String json = Files.readString(path);
            List<VlmCustomModelSet> loaded = objectMapper.readValue(json,
                new TypeReference<List<VlmCustomModelSet>>() {});

            int count = 0;
            for (VlmCustomModelSet modelSet : loaded) {
                if (!modelSet.isBuiltin()) {
                    modelSets.put(modelSet.getSetId(), modelSet);
                    count++;
                }
            }
            log.info("Loaded {} custom model sets from {}", count, path);
        } catch (IOException e) {
            log.error("Failed to load model sets config from {}: {}", path, e.getMessage());
        }
    }

    // =====================================================================
    // PIPELINE OPERATIONS
    // =====================================================================

    /**
     * Get all registered pipelines (builtin + custom).
     */
    public Collection<VlmPipelineDefinition> getAllPipelines() {
        return Collections.unmodifiableCollection(pipelines.values());
    }

    /**
     * Get only custom (non-builtin) pipelines.
     */
    public List<VlmPipelineDefinition> getCustomPipelines() {
        return pipelines.values().stream()
            .filter(p -> !p.isBuiltin())
            .toList();
    }

    /**
     * Get only builtin pipelines.
     */
    public List<VlmPipelineDefinition> getBuiltinPipelines() {
        return pipelines.values().stream()
            .filter(VlmPipelineDefinition::isBuiltin)
            .toList();
    }

    /**
     * Get a pipeline by ID.
     */
    public Optional<VlmPipelineDefinition> getPipeline(String pipelineId) {
        return Optional.ofNullable(pipelines.get(pipelineId));
    }

    /**
     * Register a pipeline.
     *
     * @param pipeline the pipeline to register
     * @return validation errors, empty if successful
     */
    public List<String> registerPipeline(VlmPipelineDefinition pipeline) {
        List<String> errors = pipeline.validate();
        if (!errors.isEmpty()) {
            return errors;
        }

        // Check for builtin override
        VlmPipelineDefinition existing = pipelines.get(pipeline.getPipelineId());
        if (existing != null && existing.isBuiltin() && !pipeline.isBuiltin()) {
            return List.of("Cannot override builtin pipeline: " + pipeline.getPipelineId());
        }

        pipeline.setUpdatedAt(System.currentTimeMillis());
        pipelines.put(pipeline.getPipelineId(), pipeline);
        persistPipelines();

        log.info("Registered pipeline: {}", pipeline.getPipelineId());
        return List.of();
    }

    /**
     * Update an existing pipeline.
     */
    public List<String> updatePipeline(String pipelineId, VlmPipelineDefinition updated) {
        VlmPipelineDefinition existing = pipelines.get(pipelineId);
        if (existing == null) {
            return List.of("Pipeline not found: " + pipelineId);
        }
        if (existing.isBuiltin()) {
            return List.of("Cannot modify builtin pipeline: " + pipelineId);
        }

        updated.setPipelineId(pipelineId);
        updated.setCreatedAt(existing.getCreatedAt());
        updated.setUpdatedAt(System.currentTimeMillis());

        List<String> errors = updated.validate();
        if (!errors.isEmpty()) {
            return errors;
        }

        pipelines.put(pipelineId, updated);
        persistPipelines();

        log.info("Updated pipeline: {}", pipelineId);
        return List.of();
    }

    /**
     * Delete a pipeline.
     */
    public boolean deletePipeline(String pipelineId) {
        VlmPipelineDefinition existing = pipelines.get(pipelineId);
        if (existing == null) {
            return false;
        }
        if (existing.isBuiltin()) {
            log.warn("Cannot delete builtin pipeline: {}", pipelineId);
            return false;
        }

        pipelines.remove(pipelineId);
        persistPipelines();

        log.info("Deleted pipeline: {}", pipelineId);
        return true;
    }

    // =====================================================================
    // STAGE OPERATIONS
    // =====================================================================

    /**
     * Get all registered stages (builtin + custom).
     */
    public Collection<VlmStageDefinition> getAllStages() {
        return Collections.unmodifiableCollection(stages.values());
    }

    /**
     * Get only custom stages.
     */
    public List<VlmStageDefinition> getCustomStages() {
        return stages.values().stream()
            .filter(s -> !s.isBuiltin())
            .toList();
    }

    /**
     * Get a stage by ID.
     */
    public Optional<VlmStageDefinition> getStage(String stageId) {
        return Optional.ofNullable(stages.get(stageId));
    }

    /**
     * Register a custom stage.
     */
    public List<String> registerStage(VlmStageDefinition stage) {
        if (stage.getStageId() == null || stage.getStageId().isBlank()) {
            return List.of("stageId is required");
        }
        if (stage.getDisplayName() == null || stage.getDisplayName().isBlank()) {
            return List.of("displayName is required");
        }

        VlmStageDefinition existing = stages.get(stage.getStageId());
        if (existing != null && existing.isBuiltin()) {
            return List.of("Cannot override builtin stage: " + stage.getStageId());
        }

        stages.put(stage.getStageId(), stage);
        persistStages();

        log.info("Registered stage: {}", stage.getStageId());
        return List.of();
    }

    /**
     * Delete a custom stage.
     */
    public boolean deleteStage(String stageId) {
        VlmStageDefinition existing = stages.get(stageId);
        if (existing == null) {
            return false;
        }
        if (existing.isBuiltin()) {
            log.warn("Cannot delete builtin stage: {}", stageId);
            return false;
        }

        stages.remove(stageId);
        persistStages();

        log.info("Deleted stage: {}", stageId);
        return true;
    }

    // =====================================================================
    // MODEL SET OPERATIONS
    // =====================================================================

    /**
     * Get all registered model sets (builtin + custom).
     */
    public Collection<VlmCustomModelSet> getAllModelSets() {
        return Collections.unmodifiableCollection(modelSets.values());
    }

    /**
     * Get only custom model sets.
     */
    public List<VlmCustomModelSet> getCustomModelSets() {
        return modelSets.values().stream()
            .filter(m -> !m.isBuiltin())
            .toList();
    }

    /**
     * Get a model set by ID.
     */
    public Optional<VlmCustomModelSet> getModelSet(String setId) {
        return Optional.ofNullable(modelSets.get(setId));
    }

    /**
     * Register a custom model set.
     */
    public List<String> registerModelSet(VlmCustomModelSet modelSet) {
        if (modelSet.getSetId() == null || modelSet.getSetId().isBlank()) {
            return List.of("setId is required");
        }
        if (modelSet.getDisplayName() == null || modelSet.getDisplayName().isBlank()) {
            return List.of("displayName is required");
        }

        VlmCustomModelSet existing = modelSets.get(modelSet.getSetId());
        if (existing != null && existing.isBuiltin()) {
            return List.of("Cannot override builtin model set: " + modelSet.getSetId());
        }

        modelSet.setUpdatedAt(System.currentTimeMillis());
        modelSets.put(modelSet.getSetId(), modelSet);
        persistModelSets();

        log.info("Registered model set: {}", modelSet.getSetId());
        return List.of();
    }

    /**
     * Delete a custom model set.
     */
    public boolean deleteModelSet(String setId) {
        VlmCustomModelSet existing = modelSets.get(setId);
        if (existing == null) {
            return false;
        }
        if (existing.isBuiltin()) {
            log.warn("Cannot delete builtin model set: {}", setId);
            return false;
        }

        modelSets.remove(setId);
        persistModelSets();

        log.info("Deleted model set: {}", setId);
        return true;
    }

    // =====================================================================
    // PERSISTENCE
    // =====================================================================

    private void persistPipelines() {
        List<VlmPipelineDefinition> toSave = getCustomPipelines();
        persist(PIPELINES_CONFIG, toSave);
    }

    private void persistStages() {
        List<VlmStageDefinition> toSave = getCustomStages();
        persist(STAGES_CONFIG, toSave);
    }

    private void persistModelSets() {
        List<VlmCustomModelSet> toSave = getCustomModelSets();
        persist(MODEL_SETS_CONFIG, toSave);
    }

    private void persist(String filename, Object data) {
        try {
            // Ensure directory exists
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
                log.info("Created config directory: {}", configDir);
            }

            Path path = configDir.resolve(filename);
            String json = objectMapper.writeValueAsString(data);
            Files.writeString(path, json);
            log.debug("Persisted config to {}", path);
        } catch (IOException e) {
            log.error("Failed to persist config to {}: {}", filename, e.getMessage());
        }
    }

    // =====================================================================
    // UTILITY METHODS
    // =====================================================================

    /**
     * Validate a pipeline configuration without registering it.
     */
    public List<String> validatePipeline(VlmPipelineDefinition pipeline) {
        List<String> errors = new ArrayList<>(pipeline.validate());

        // Check stage references
        if (pipeline.isSequence()) {
            for (VlmPipelineStageConfig stageConfig : pipeline.getStages()) {
                if (!stages.containsKey(stageConfig.getStageId())) {
                    errors.add("Unknown stage: " + stageConfig.getStageId());
                }
            }
        } else if (pipeline.isGraph()) {
            for (VlmGraphNodeConfig node : pipeline.getGraphNodes().values()) {
                if (!stages.containsKey(node.getStageId())) {
                    errors.add("Unknown stage in node " + node.getNodeId() + ": " + node.getStageId());
                }
            }
        }

        // Check model set reference
        if (pipeline.getModelSetId() != null && !modelSets.containsKey(pipeline.getModelSetId())) {
            errors.add("Unknown model set: " + pipeline.getModelSetId());
        }

        return errors;
    }

    /**
     * Get the config directory path.
     */
    public Path getConfigDirectory() {
        return configDir;
    }

    /**
     * Get statistics about the registry.
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalPipelines", pipelines.size());
        stats.put("builtinPipelines", getBuiltinPipelines().size());
        stats.put("customPipelines", getCustomPipelines().size());
        stats.put("totalStages", stages.size());
        stats.put("builtinStages", stages.values().stream().filter(VlmStageDefinition::isBuiltin).count());
        stats.put("customStages", getCustomStages().size());
        stats.put("totalModelSets", modelSets.size());
        stats.put("builtinModelSets", modelSets.values().stream().filter(VlmCustomModelSet::isBuiltin).count());
        stats.put("customModelSets", getCustomModelSets().size());
        stats.put("configDirectory", configDir.toString());
        return stats;
    }

    /**
     * Clear all custom (non-builtin) configurations.
     */
    public void clearCustomConfigs() {
        pipelines.entrySet().removeIf(e -> !e.getValue().isBuiltin());
        stages.entrySet().removeIf(e -> !e.getValue().isBuiltin());
        modelSets.entrySet().removeIf(e -> !e.getValue().isBuiltin());

        persistPipelines();
        persistStages();
        persistModelSets();

        log.info("Cleared all custom configurations");
    }
}
