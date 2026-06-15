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

package ai.kompile.modelmanager.vlm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Resolves VLM model IDs to local model paths, downloading if necessary.
 *
 * This service bridges the gap between configuration (which uses model IDs like
 * "smoldocling-256m") and the actual model files needed for inference.
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * VlmModelResolver resolver = new VlmModelResolver();
 *
 * // Resolve model for document understanding
 * ResolvedModel model = resolver.resolveForExtraction(VlmExtractionType.DOCUMENT_UNDERSTANDING);
 *
 * // Get paths to specific components
 * Path visionEncoder = model.getComponentPath("vision_encoder");
 * Path decoder = model.getComponentPath("decoder");
 * Path tokenizer = model.getComponentPath("tokenizer");
 *
 * // Resolve from PdfProcessingConfig model ID
 * ResolvedModel smoldocling = resolver.resolve("smoldocling-256m");
 * }</pre>
 *
 * <h2>Integration with PdfProcessingConfig</h2>
 * <pre>{@code
 * PdfProcessingConfig config = PdfProcessingConfig.vlm("smoldocling-256m");
 *
 * // Resolve the configured model
 * ResolvedModel model = resolver.resolve(config.getVlmModelId());
 *
 * // Check if all required components are available
 * if (!model.isComplete()) {
 *     // Download missing components
 *     resolver.ensureDownloaded(config.getVlmModelId());
 * }
 * }</pre>
 *
 * @author Kompile Inc.
 */
public class VlmModelResolver {

    private static final Logger log = LoggerFactory.getLogger(VlmModelResolver.class);

    private final VlmModelSetDownloader downloader;
    private final Map<String, VlmModelSet> modelIdToSet;

    public VlmModelResolver() {
        this(new VlmModelSetDownloader());
    }

    public VlmModelResolver(VlmModelSetDownloader downloader) {
        this.downloader = downloader;
        this.modelIdToSet = buildModelIdMapping();
    }

    /**
     * Build mapping from model IDs (used in config) to model sets.
     */
    private Map<String, VlmModelSet> buildModelIdMapping() {
        Map<String, VlmModelSet> mapping = new HashMap<>();

        // Register all model sets by their set ID
        for (VlmModelSet set : VlmModelSet.getAllModelSets()) {
            mapping.put(set.getSetId(), set);
        }

        // Also add common aliases
        mapping.put("smoldocling", VlmModelSet.SMOLDOCLING_256M);
        mapping.put("smol-docling", VlmModelSet.SMOLDOCLING_256M);
        mapping.put("donut", VlmModelSet.DONUT_BASE);
        mapping.put("siglip", VlmModelSet.SIGLIP_VISION);
        mapping.put("clip", VlmModelSet.CLIP_VIT_BASE);
        mapping.put("tableformer", VlmModelSet.DOCLING_TABLEFORMER);

        return mapping;
    }

    // =====================================================================
    // RESOLUTION METHODS
    // =====================================================================

    /**
     * Resolve a model ID to a ResolvedModel with local paths.
     * Downloads the model set if not cached.
     *
     * @param modelId the model ID (e.g., "smoldocling-256m")
     * @return resolved model with local paths
     * @throws IllegalArgumentException if model ID is unknown
     */
    public ResolvedModel resolve(String modelId) {
        return resolve(modelId, true);
    }

    /**
     * Resolve a model ID to a ResolvedModel with local paths.
     *
     * @param modelId the model ID (e.g., "smoldocling-256m")
     * @param downloadIfMissing whether to download if not cached
     * @return resolved model with local paths
     * @throws IllegalArgumentException if model ID is unknown
     */
    public ResolvedModel resolve(String modelId, boolean downloadIfMissing) {
        VlmModelSet modelSet = modelIdToSet.get(modelId.toLowerCase());
        if (modelSet == null) {
            throw new IllegalArgumentException("Unknown VLM model ID: " + modelId +
                ". Available: " + modelIdToSet.keySet());
        }

        return resolveModelSet(modelSet, downloadIfMissing);
    }

    /**
     * Resolve the default model for an extraction type.
     *
     * @param extractionType the type of extraction
     * @return resolved model, or empty if no default model for this type
     */
    public Optional<ResolvedModel> resolveForExtraction(VlmExtractionType extractionType) {
        return resolveForExtraction(extractionType, true);
    }

    /**
     * Resolve the default model for an extraction type.
     *
     * @param extractionType the type of extraction
     * @param downloadIfMissing whether to download if not cached
     * @return resolved model, or empty if no default model for this type
     */
    public Optional<ResolvedModel> resolveForExtraction(VlmExtractionType extractionType,
                                                         boolean downloadIfMissing) {
        VlmModelSet modelSet = extractionType.getDefaultModelSet();
        if (modelSet == null) {
            log.debug("No default model set for extraction type: {}", extractionType);
            return Optional.empty();
        }

        return Optional.of(resolveModelSet(modelSet, downloadIfMissing));
    }

    /**
     * Resolve all models needed for a VlmExtractionConfig.
     *
     * @param config the extraction configuration
     * @return map of extraction type to resolved model
     */
    public Map<VlmExtractionType, ResolvedModel> resolveForConfig(VlmExtractionConfig config) {
        return resolveForConfig(config, true);
    }

    /**
     * Resolve all models needed for a VlmExtractionConfig.
     *
     * @param config the extraction configuration
     * @param downloadIfMissing whether to download if not cached
     * @return map of extraction type to resolved model
     */
    public Map<VlmExtractionType, ResolvedModel> resolveForConfig(VlmExtractionConfig config,
                                                                   boolean downloadIfMissing) {
        Map<VlmExtractionType, ResolvedModel> resolved = new LinkedHashMap<>();

        for (VlmExtractionType type : config.getEnabledExtractions()) {
            VlmModelSet modelSet = config.getModelSet(type);
            if (modelSet != null) {
                try {
                    resolved.put(type, resolveModelSet(modelSet, downloadIfMissing));
                } catch (Exception e) {
                    log.warn("Failed to resolve model for {}: {}", type, e.getMessage());
                }
            }
        }

        return resolved;
    }

    /**
     * Resolve a VlmModelSet to local paths.
     */
    private ResolvedModel resolveModelSet(VlmModelSet modelSet, boolean downloadIfMissing) {
        Path setPath = downloader.getModelSetPath(modelSet);

        // Check if already cached
        if (!downloader.isModelSetCached(modelSet)) {
            if (downloadIfMissing) {
                log.info("Downloading model set: {}", modelSet.getSetId());
                downloader.downloadModelSet(modelSet);
            } else {
                log.warn("Model set not cached and download disabled: {}", modelSet.getSetId());
            }
        }

        // Build resolved model
        Map<String, Path> componentPaths = new LinkedHashMap<>();
        List<String> missingComponents = new ArrayList<>();

        for (VlmModelComponent component : modelSet.getComponents()) {
            Path componentPath = setPath.resolve(component.getFileName());
            if (Files.exists(componentPath)) {
                componentPaths.put(component.getComponentKey(), componentPath);
            } else {
                missingComponents.add(component.getComponentKey());
            }
        }

        return new ResolvedModel(modelSet, setPath, componentPaths, missingComponents);
    }

    // =====================================================================
    // DOWNLOAD MANAGEMENT
    // =====================================================================

    /**
     * Ensure a model is downloaded.
     *
     * @param modelId the model ID
     * @return true if model is available after this call
     */
    public boolean ensureDownloaded(String modelId) {
        try {
            resolve(modelId, true);
            return true;
        } catch (Exception e) {
            log.error("Failed to download model: {}", modelId, e);
            return false;
        }
    }

    /**
     * Ensure all models needed for a configuration are downloaded.
     *
     * @param config the extraction configuration
     * @return list of model IDs that failed to download
     */
    public List<String> ensureDownloaded(VlmExtractionConfig config) {
        List<String> failures = new ArrayList<>();

        for (VlmModelSet modelSet : config.getRequiredModelSets()) {
            try {
                resolveModelSet(modelSet, true);
            } catch (Exception e) {
                log.error("Failed to download model set: {}", modelSet.getSetId(), e);
                failures.add(modelSet.getSetId());
            }
        }

        return failures;
    }

    /**
     * Check if a model is cached locally.
     *
     * @param modelId the model ID
     * @return true if cached
     */
    public boolean isCached(String modelId) {
        VlmModelSet modelSet = modelIdToSet.get(modelId.toLowerCase());
        return modelSet != null && downloader.isModelSetCached(modelSet);
    }

    /**
     * Get status of all known models.
     *
     * @return map of model ID to cached status
     */
    public Map<String, Boolean> getCacheStatus() {
        Map<String, Boolean> status = new LinkedHashMap<>();
        for (VlmModelSet set : VlmModelSet.getAllModelSets()) {
            status.put(set.getSetId(), downloader.isModelSetCached(set));
        }
        return status;
    }

    /**
     * Get all available model IDs.
     */
    public Set<String> getAvailableModelIds() {
        return Collections.unmodifiableSet(modelIdToSet.keySet());
    }

    // =====================================================================
    // RESOLVED MODEL CLASS
    // =====================================================================

    /**
     * A resolved model with local file paths for all components.
     */
    public static class ResolvedModel {
        private final VlmModelSet modelSet;
        private final Path basePath;
        private final Map<String, Path> componentPaths;
        private final List<String> missingComponents;

        public ResolvedModel(VlmModelSet modelSet, Path basePath,
                             Map<String, Path> componentPaths, List<String> missingComponents) {
            this.modelSet = modelSet;
            this.basePath = basePath;
            this.componentPaths = Collections.unmodifiableMap(componentPaths);
            this.missingComponents = Collections.unmodifiableList(missingComponents);
        }

        public VlmModelSet getModelSet() {
            return modelSet;
        }

        public String getModelId() {
            return modelSet.getSetId();
        }

        public Path getBasePath() {
            return basePath;
        }

        /**
         * Get path to a specific component.
         *
         * @param componentKey e.g., "vision_encoder", "decoder", "tokenizer"
         * @return path to component file, or null if not found
         */
        public Path getComponentPath(String componentKey) {
            return componentPaths.get(componentKey);
        }

        /**
         * Get all component paths.
         */
        public Map<String, Path> getComponentPaths() {
            return componentPaths;
        }

        /**
         * Get list of missing components.
         */
        public List<String> getMissingComponents() {
            return missingComponents;
        }

        /**
         * Check if all components are available.
         */
        public boolean isComplete() {
            return missingComponents.isEmpty();
        }

        // Convenience getters for common components

        /**
         * Get path to vision encoder model.
         */
        public Path getVisionEncoderPath() {
            return componentPaths.get("vision_encoder");
        }

        /**
         * Get path to decoder model.
         */
        public Path getDecoderPath() {
            return componentPaths.get("decoder");
        }

        /**
         * Get path to embed_tokens model.
         */
        public Path getEmbedTokensPath() {
            return componentPaths.get("embed_tokens");
        }

        /**
         * Get path to tokenizer.
         */
        public Path getTokenizerPath() {
            return componentPaths.get("tokenizer");
        }

        /**
         * Get path to tokenizer config.
         */
        public Path getTokenizerConfigPath() {
            return componentPaths.get("tokenizer_config");
        }

        /**
         * Get pipeline configuration value.
         */
        public <T> T getPipelineConfig(String key, T defaultValue) {
            return modelSet.getPipelineConfigValue(key, defaultValue);
        }

        @Override
        public String toString() {
            return "ResolvedModel{" +
                "modelId='" + getModelId() + '\'' +
                ", components=" + componentPaths.keySet() +
                ", complete=" + isComplete() +
                '}';
        }
    }
}
