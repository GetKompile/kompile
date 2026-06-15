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

package ai.kompile.staging.catalog;

import ai.kompile.modelmanager.registry.ModelEntry;
import ai.kompile.modelmanager.registry.ModelMetadata;
import ai.kompile.modelmanager.registry.ModelType;
import ai.kompile.modelmanager.registry.RegistryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.Collection;

/**
 * Service for loading and providing the model catalog.
 * Merges static YAML catalog with dynamic registry models.
 */
@Service
public class CatalogService {

    private static final Logger log = LoggerFactory.getLogger(CatalogService.class);
    private static final String CATALOG_FILE = "model-sources.yml";

    private ModelCatalog staticCatalog;
    private final ObjectMapper yamlMapper;

    @Autowired
    private RegistryService registryService;

    public CatalogService() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    @PostConstruct
    public void init() {
        loadCatalog();
    }

    /**
     * Load the catalog from the YAML file.
     */
    @SuppressWarnings("unchecked")
    private void loadCatalog() {
        try {
            ClassPathResource resource = new ClassPathResource(CATALOG_FILE);
            if (!resource.exists()) {
                log.warn("Catalog file not found: {}", CATALOG_FILE);
                staticCatalog = ModelCatalog.builder()
                        .sources(new HashMap<>())
                        .encoders(new ArrayList<>())
                        .crossEncoders(new ArrayList<>())
                        .vlm(new ArrayList<>())
                        .build();
                return;
            }

            try (InputStream is = resource.getInputStream()) {
                Map<String, Object> root = yamlMapper.readValue(is, Map.class);

                // Parse sources
                Map<String, ModelCatalog.SourceConfig> sources = new HashMap<>();
                Map<String, Object> sourcesMap = (Map<String, Object>) root.get("sources");
                if (sourcesMap != null) {
                    for (Map.Entry<String, Object> entry : sourcesMap.entrySet()) {
                        Map<String, Object> sourceData = (Map<String, Object>) entry.getValue();
                        sources.put(entry.getKey(), ModelCatalog.SourceConfig.builder()
                                .baseUrl((String) sourceData.get("base_url"))
                                .enabled(Boolean.TRUE.equals(sourceData.get("enabled")))
                                .build());
                    }
                }

                // Parse model catalog
                Map<String, Object> modelCatalogMap = (Map<String, Object>) root.get("model_catalog");
                List<CatalogModel> encoders = new ArrayList<>();
                List<CatalogModel> crossEncoders = new ArrayList<>();
                List<CatalogModel> vlm = new ArrayList<>();

                if (modelCatalogMap != null) {
                    // Parse encoders
                    List<Map<String, Object>> encodersList = (List<Map<String, Object>>) modelCatalogMap.get("encoders");
                    if (encodersList != null) {
                        for (Map<String, Object> modelData : encodersList) {
                            encoders.add(parseModel(modelData));
                        }
                    }

                    // Parse cross-encoders
                    List<Map<String, Object>> crossEncodersList = (List<Map<String, Object>>) modelCatalogMap.get("cross_encoders");
                    if (crossEncodersList != null) {
                        for (Map<String, Object> modelData : crossEncodersList) {
                            crossEncoders.add(parseModel(modelData));
                        }
                    }

                    // Parse VLM models
                    List<Map<String, Object>> vlmList = (List<Map<String, Object>>) modelCatalogMap.get("vlm");
                    if (vlmList != null) {
                        for (Map<String, Object> modelData : vlmList) {
                            vlm.add(parseModel(modelData));
                        }
                    }
                }

                staticCatalog = ModelCatalog.builder()
                        .sources(sources)
                        .encoders(encoders)
                        .crossEncoders(crossEncoders)
                        .vlm(vlm)
                        .build();

                log.info("Loaded static catalog: {} encoders, {} cross-encoders, {} vlm",
                        encoders.size(), crossEncoders.size(), vlm.size());
            }
        } catch (IOException e) {
            log.error("Failed to load catalog", e);
            staticCatalog = ModelCatalog.builder()
                    .sources(new HashMap<>())
                    .encoders(new ArrayList<>())
                    .crossEncoders(new ArrayList<>())
                    .vlm(new ArrayList<>())
                    .build();
        }
    }

    @SuppressWarnings("unchecked")
    private CatalogModel parseModel(Map<String, Object> modelData) {
        CatalogModel.CatalogModelMetadata metadata = null;
        Map<String, Object> metadataMap = (Map<String, Object>) modelData.get("metadata");
        if (metadataMap != null) {
            metadata = CatalogModel.CatalogModelMetadata.builder()
                    .embeddingDim((Integer) metadataMap.get("embedding_dim"))
                    .hiddenSize((Integer) metadataMap.get("hidden_size"))
                    .numLayers((Integer) metadataMap.get("num_layers"))
                    .maxSequenceLength((Integer) metadataMap.get("max_sequence_length"))
                    .trainingData((String) metadataMap.get("training_data"))
                    .description((String) metadataMap.get("description"))
                    .build();
        }

        Map<String, String> files = new HashMap<>();
        Map<String, Object> filesMap = (Map<String, Object>) modelData.get("files");
        if (filesMap != null) {
            for (Map.Entry<String, Object> entry : filesMap.entrySet()) {
                files.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }

        return CatalogModel.builder()
                .id((String) modelData.get("id"))
                .source((String) modelData.get("source"))
                .repo((String) modelData.get("repo"))
                .format((String) modelData.get("format"))
                .files(files)
                .metadata(metadata)
                .build();
    }

    /**
     * Get the full catalog, dynamically merging registry models.
     */
    public ModelCatalog getCatalog() {
        ModelCatalog merged = ModelCatalog.builder()
                .sources(staticCatalog.getSources())
                .encoders(getEncoders())
                .crossEncoders(getCrossEncoders())
                .vlm(getVlm())
                .build();
        return merged;
    }

    /**
     * Get all encoders (static catalog + registry).
     */
    public List<CatalogModel> getEncoders() {
        List<CatalogModel> result = new ArrayList<>(staticCatalog.getEncoders());
        Set<String> staticIds = new HashSet<>();
        for (CatalogModel m : result) {
            staticIds.add(m.getId());
        }
        // Add registry models that aren't already in static catalog
        for (ModelEntry entry : getRegistryModels()) {
            if (entry.getType() != null && entry.getType().isRetrieval() && !staticIds.contains(entry.getModelId())) {
                result.add(registryEntryToCatalogModel(entry));
            }
        }
        // Mark installed status on static catalog entries
        markInstalled(result);
        return result;
    }

    /**
     * Get all cross-encoders (static catalog + registry).
     */
    public List<CatalogModel> getCrossEncoders() {
        List<CatalogModel> result = new ArrayList<>(staticCatalog.getCrossEncoders());
        Set<String> staticIds = new HashSet<>();
        for (CatalogModel m : result) {
            staticIds.add(m.getId());
        }
        for (ModelEntry entry : getRegistryModels()) {
            if (entry.getType() != null && entry.getType().isReranking() && !staticIds.contains(entry.getModelId())) {
                result.add(registryEntryToCatalogModel(entry));
            }
        }
        markInstalled(result);
        return result;
    }

    /**
     * Get all VLM models (static catalog + registry).
     */
    public List<CatalogModel> getVlm() {
        List<CatalogModel> result = new ArrayList<>(
                staticCatalog.getVlm() != null ? staticCatalog.getVlm() : new ArrayList<>());
        Set<String> staticIds = new HashSet<>();
        for (CatalogModel m : result) {
            staticIds.add(m.getId());
        }
        for (ModelEntry entry : getRegistryModels()) {
            if (entry.getType() != null && entry.getType().isVlm() && !staticIds.contains(entry.getModelId())) {
                result.add(registryEntryToCatalogModel(entry));
            }
        }
        markInstalled(result);
        return result;
    }

    /**
     * Get a model by ID.
     */
    public Optional<CatalogModel> getModel(String modelId) {
        for (CatalogModel model : getEncoders()) {
            if (model.getId().equals(modelId)) {
                return Optional.of(model);
            }
        }
        for (CatalogModel model : getCrossEncoders()) {
            if (model.getId().equals(modelId)) {
                return Optional.of(model);
            }
        }
        for (CatalogModel model : getVlm()) {
            if (model.getId().equals(modelId)) {
                return Optional.of(model);
            }
        }
        return Optional.empty();
    }

    /**
     * Reload the catalog from disk.
     */
    public void reload() {
        loadCatalog();
    }

    /**
     * Get all model entries from the registry.
     */
    private Collection<ModelEntry> getRegistryModels() {
        try {
            var registry = registryService.loadRegistry();
            if (registry != null && registry.getModels() != null) {
                return registry.getModels().values();
            }
        } catch (Exception e) {
            log.debug("Could not load registry models: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    /**
     * Convert a registry ModelEntry to a CatalogModel.
     * Sets installed=true if any model file exists on disk.
     * Sets optimizable=true if a SameDiff (.fb/.sdz) file exists (regardless of declared format).
     */
    private CatalogModel registryEntryToCatalogModel(ModelEntry entry) {
        boolean fileExists = isAnyModelFilePresent(entry);
        boolean canOptimize = hasSameDiffFile(entry);

        ModelMetadata srcMeta = entry.getMetadata();
        CatalogModel.CatalogModelMetadata.CatalogModelMetadataBuilder metaBuilder = CatalogModel.CatalogModelMetadata.builder()
                .description(srcMeta != null && srcMeta.getDescription() != null
                        ? srcMeta.getDescription()
                        : entry.getType() != null ? entry.getType().getDisplayName() + " - " + entry.getModelId() : entry.getModelId())
                .embeddingDim(srcMeta != null ? srcMeta.getEmbeddingDim() : null)
                .hiddenSize(srcMeta != null ? srcMeta.getHiddenSize() : null)
                .numLayers(srcMeta != null ? srcMeta.getNumLayers() : null)
                .maxSequenceLength(srcMeta != null ? srcMeta.getMaxSequenceLength() : null)
                .trainingData(srcMeta != null ? srcMeta.getTrainingData() : null)
                .framework(srcMeta != null ? srcMeta.getFramework() : null)
                .encoderType(srcMeta != null ? srcMeta.getEncoderType() : null)
                .ragRole(srcMeta != null ? srcMeta.getRagRole() : null)
                .version(srcMeta != null ? srcMeta.getVersion() : null)
                // OCR fields
                .inputHeight(srcMeta != null ? srcMeta.getInputHeight() : null)
                .inputWidth(srcMeta != null ? srcMeta.getInputWidth() : null)
                .supportedLanguages(srcMeta != null ? srcMeta.getSupportedLanguages() : null)
                .supportsBatch(srcMeta != null ? srcMeta.getSupportsBatch() : null)
                .maxBatchSize(srcMeta != null ? srcMeta.getMaxBatchSize() : null)
                .supportsHandwriting(srcMeta != null ? srcMeta.getSupportsHandwriting() : null)
                .averageAccuracy(srcMeta != null ? srcMeta.getAverageAccuracy() : null)
                .ocrVocabSize(srcMeta != null ? srcMeta.getOcrVocabSize() : null)
                .usesCtc(srcMeta != null ? srcMeta.getUsesCtc() : null)
                // VLM fields
                .visionFrames(srcMeta != null ? srcMeta.getVisionFrames() : null)
                .imageSize(srcMeta != null ? srcMeta.getImageSize() : null)
                .tileSize(srcMeta != null ? srcMeta.getTileSize() : null)
                .components(srcMeta != null ? srcMeta.getComponents() : null)
                .visionEncoderOutputNames(srcMeta != null ? srcMeta.getVisionEncoderOutputNames() : null)
                .visionEncoderPrimaryOutputName(srcMeta != null ? srcMeta.getVisionEncoderPrimaryOutputName() : null);

        // Only copy optimization data if the SameDiff model file actually exists
        if (canOptimize) {
            copyOptimizationData(entry, metaBuilder);
        }
        CatalogModel.CatalogModelMetadata metadata = metaBuilder.build();

        Map<String, String> files = new HashMap<>();
        if (entry.getModelFile() != null) files.put("model", entry.getModelFile());
        if (entry.getVocabFile() != null) files.put("vocab", entry.getVocabFile());

        String format = "samediff";
        if (entry.getModelFile() != null) {
            if (entry.getModelFile().endsWith(".onnx")) format = "onnx";
            else if (entry.getModelFile().endsWith(".sdz") || entry.getModelFile().endsWith(".fb")) format = "samediff";
        }
        if (entry.getMetadata() != null && entry.getMetadata().getFramework() != null) {
            format = entry.getMetadata().getFramework();
        }

        return CatalogModel.builder()
                .id(entry.getModelId())
                .source("local")
                .repo(entry.getPath())
                .format(format)
                .files(files)
                .metadata(metadata)
                .modelType(entry.getType() != null ? entry.getType().getValue() : null)
                .installed(fileExists)
                .optimizable(canOptimize)
                .status(entry.getStatus() != null ? entry.getStatus().getValue() : "active")
                .path(entry.getPath())
                .build();
    }

    /**
     * Check whether ANY model file for a registry entry exists on disk.
     * This determines the "installed" flag — any format counts (.fb, .sdz, .onnx, .pb, .ggml, etc.).
     */
    private boolean isAnyModelFilePresent(ModelEntry entry) {
        if (entry.getModelId() == null && entry.getPath() == null) return false;
        Path modelDir = registryService.getModelDir();

        // Check explicit model file path via registry entry
        if (entry.getPath() != null && entry.getModelFile() != null) {
            Path modelFilePath = modelDir.resolve(entry.getModelFilePath());
            if (Files.exists(modelFilePath)) return true;
        }

        // Check model directories for any known model files
        for (Path dir : resolveModelDirs(entry, modelDir)) {
            if (Files.isDirectory(dir) && hasAnyModelFile(dir)) return true;
        }

        return false;
    }

    /**
     * Check whether a SameDiff-compatible model file (.fb or .sdz) exists for this entry.
     * This determines the "optimizable" flag — only SameDiff files can be run through GraphOptimizer.
     * A model could be ONNX/TF/GGML but still be optimizable if a converted .sdz exists alongside it.
     */
    private boolean hasSameDiffFile(ModelEntry entry) {
        if (entry.getModelId() == null && entry.getPath() == null) return false;
        Path modelDir = registryService.getModelDir();

        // Check explicit model file if it's a SameDiff format
        if (entry.getPath() != null && entry.getModelFile() != null) {
            String mf = entry.getModelFile();
            if (mf.endsWith(".fb") || mf.endsWith(".sdz")) {
                Path modelFilePath = modelDir.resolve(entry.getModelFilePath());
                if (Files.exists(modelFilePath)) return true;
            }
        }

        // Check model directories for .fb/.sdz files (including converted equivalents)
        String modelId = entry.getModelId();
        if (modelId != null) {
            // Direct file checks (mirrors CompilerService.resolveModelFile)
            if (Files.exists(modelDir.resolve(modelId + ".fb"))) return true;
            if (Files.exists(modelDir.resolve(modelId + ".sdz"))) return true;
        }

        for (Path dir : resolveModelDirs(entry, modelDir)) {
            if (Files.isDirectory(dir) && dirContainsFileType(dir, ".fb", ".sdz")) return true;
        }

        return false;
    }

    /**
     * Get all candidate directories where model files could live for an entry.
     */
    private List<Path> resolveModelDirs(ModelEntry entry, Path modelDir) {
        List<Path> dirs = new ArrayList<>();
        String modelId = entry.getModelId();
        if (modelId != null) {
            dirs.add(modelDir.resolve(modelId));
        }
        if (entry.getPath() != null && !entry.getPath().equals(modelId)) {
            dirs.add(modelDir.resolve(entry.getPath()));
        }
        return dirs;
    }

    /**
     * Check if a directory contains any recognized model file.
     */
    private boolean hasAnyModelFile(Path dir) {
        return dirContainsFileType(dir, ".fb", ".sdz", ".onnx", ".pb", ".h5", ".keras", ".ggml", ".gguf", ".bin", ".safetensors");
    }

    /**
     * Check if a directory contains files with any of the given extensions.
     */
    private boolean dirContainsFileType(Path dir, String... extensions) {
        try (var stream = Files.list(dir)) {
            return stream.anyMatch(p -> {
                String name = p.getFileName().toString().toLowerCase();
                for (String ext : extensions) {
                    if (name.endsWith(ext)) return true;
                }
                return false;
            });
        } catch (IOException e) {
            log.debug("Could not list model directory {}: {}", dir, e.getMessage());
            return false;
        }
    }

    /**
     * Mark models as installed/optimizable based on actual disk contents.
     * installed = any model file exists; optimizable = SameDiff .fb/.sdz exists.
     */
    private void markInstalled(List<CatalogModel> models) {
        Map<String, ModelEntry> registryMap = new HashMap<>();
        for (ModelEntry entry : getRegistryModels()) {
            registryMap.put(entry.getModelId(), entry);
        }
        for (CatalogModel model : models) {
            ModelEntry entry = registryMap.get(model.getId());
            if (entry != null) {
                boolean fileExists = isAnyModelFilePresent(entry);
                boolean canOptimize = hasSameDiffFile(entry);
                model.setInstalled(fileExists);
                model.setOptimizable(canOptimize);
                model.setStatus(entry.getStatus() != null ? entry.getStatus().getValue() : "active");
                // Only enrich with optimization data if SameDiff model file exists
                if (canOptimize && model.getMetadata() != null && entry.getMetadata() != null) {
                    ModelMetadata regMeta = entry.getMetadata();
                    model.getMetadata().setOptimized(regMeta.getOptimized());
                    model.getMetadata().setOptimizedAt(regMeta.getOptimizedAt());
                    model.getMetadata().setOptimizationTimeMs(regMeta.getOptimizationTimeMs());
                    model.getMetadata().setAppliedOptimizations(regMeta.getAppliedOptimizations());
                    if (regMeta.getOptimizationStats() != null) {
                        model.getMetadata().setOptimizationStats(CatalogModel.OptimizationStatsData.builder()
                                .opsBefore(regMeta.getOptimizationStats().getOpsBefore())
                                .opsAfter(regMeta.getOptimizationStats().getOpsAfter())
                                .varsBefore(regMeta.getOptimizationStats().getVarsBefore())
                                .varsAfter(regMeta.getOptimizationStats().getVarsAfter())
                                .sizeBeforeBytes(regMeta.getOptimizationStats().getSizeBeforeBytes())
                                .sizeAfterBytes(regMeta.getOptimizationStats().getSizeAfterBytes())
                                .reductionPercent(regMeta.getOptimizationStats().getReductionPercent())
                                .build());
                    }
                    if (regMeta.getOptimizationConfig() != null) {
                        model.getMetadata().setOptimizationConfig(CatalogModel.OptimizationConfigData.builder()
                                .enabledPasses(regMeta.getOptimizationConfig().getEnabledPasses())
                                .preset(regMeta.getOptimizationConfig().getPreset())
                                .quantizationType(regMeta.getOptimizationConfig().getQuantizationType())
                                .quantizePerChannel(regMeta.getOptimizationConfig().isQuantizePerChannel())
                                .maxIterations(regMeta.getOptimizationConfig().getMaxIterations())
                                .build());
                    }
                }
            }
        }
    }

    /**
     * Copy optimization data from a registry ModelEntry to a CatalogModelMetadata builder.
     */
    private void copyOptimizationData(ModelEntry entry, CatalogModel.CatalogModelMetadata.CatalogModelMetadataBuilder metaBuilder) {
        if (entry.getMetadata() == null) return;
        ModelMetadata regMeta = entry.getMetadata();
        metaBuilder.optimized(regMeta.getOptimized());
        metaBuilder.optimizedAt(regMeta.getOptimizedAt());
        metaBuilder.optimizationTimeMs(regMeta.getOptimizationTimeMs());
        metaBuilder.appliedOptimizations(regMeta.getAppliedOptimizations());
        if (regMeta.getOptimizationStats() != null) {
            metaBuilder.optimizationStats(CatalogModel.OptimizationStatsData.builder()
                    .opsBefore(regMeta.getOptimizationStats().getOpsBefore())
                    .opsAfter(regMeta.getOptimizationStats().getOpsAfter())
                    .varsBefore(regMeta.getOptimizationStats().getVarsBefore())
                    .varsAfter(regMeta.getOptimizationStats().getVarsAfter())
                    .sizeBeforeBytes(regMeta.getOptimizationStats().getSizeBeforeBytes())
                    .sizeAfterBytes(regMeta.getOptimizationStats().getSizeAfterBytes())
                    .reductionPercent(regMeta.getOptimizationStats().getReductionPercent())
                    .build());
        }
        if (regMeta.getOptimizationConfig() != null) {
            metaBuilder.optimizationConfig(CatalogModel.OptimizationConfigData.builder()
                    .enabledPasses(regMeta.getOptimizationConfig().getEnabledPasses())
                    .preset(regMeta.getOptimizationConfig().getPreset())
                    .quantizationType(regMeta.getOptimizationConfig().getQuantizationType())
                    .quantizePerChannel(regMeta.getOptimizationConfig().isQuantizePerChannel())
                    .maxIterations(regMeta.getOptimizationConfig().getMaxIterations())
                    .build());
        }
    }
}
