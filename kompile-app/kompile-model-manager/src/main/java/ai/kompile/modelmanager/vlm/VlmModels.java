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

import java.nio.file.Path;
import java.util.*;
import java.util.function.BiConsumer;

/**
 * Convenience class for downloading and managing VLM models.
 *
 * This is the main entry point for model management in the chunking pipeline.
 *
 * <h2>Quick Start</h2>
 * <pre>{@code
 * // Download all models for document understanding
 * VlmModels.downloadForDocumentUnderstanding();
 *
 * // Download all models for a specific extraction configuration
 * VlmModels.downloadForConfig(VlmExtractionConfig.forScannedDocuments());
 *
 * // Check what's cached
 * VlmModels.printCacheStatus();
 *
 * // Get resolved model paths for inference
 * VlmModelResolver.ResolvedModel model = VlmModels.resolve("smoldocling-256m");
 * Path visionEncoder = model.getVisionEncoderPath();
 * }</pre>
 *
 * <h2>Integration with Chunking</h2>
 * <pre>{@code
 * // Before processing documents
 * List<String> failures = VlmModels.ensureModelsForConfig(extractionConfig);
 * if (!failures.isEmpty()) {
 *     log.warn("Some models failed to download: {}", failures);
 * }
 *
 * // Get model paths for the processing pipeline
 * Map<VlmExtractionType, VlmModelResolver.ResolvedModel> models =
 *     VlmModels.resolveForConfig(extractionConfig);
 *
 * // Use in document loader
 * for (VlmExtractionType type : models.keySet()) {
 *     VlmModelResolver.ResolvedModel model = models.get(type);
 *     // Configure the extraction pipeline with model paths
 * }
 * }</pre>
 *
 * @author Kompile Inc.
 */
public final class VlmModels {

    private static final Logger log = LoggerFactory.getLogger(VlmModels.class);

    private static final VlmModelSetDownloader DOWNLOADER = new VlmModelSetDownloader();
    private static final VlmModelResolver RESOLVER = new VlmModelResolver(DOWNLOADER);

    private VlmModels() {}

    // =====================================================================
    // DOWNLOAD METHODS
    // =====================================================================

    /**
     * Download a model set by ID.
     *
     * @param modelSetId e.g., "smoldocling-256m", "donut-base"
     * @return download result
     */
    public static VlmModelSetDownloader.DownloadSetResult download(String modelSetId) {
        VlmModelSet modelSet = VlmModelSet.getModelSet(modelSetId);
        if (modelSet == null) {
            throw new IllegalArgumentException("Unknown model set: " + modelSetId);
        }
        return DOWNLOADER.downloadModelSet(modelSet);
    }

    /**
     * Download a model set with progress callback.
     *
     * @param modelSetId model set ID
     * @param progressCallback receives (component, progress 0-1) updates
     * @return download result
     */
    public static VlmModelSetDownloader.DownloadSetResult download(
            String modelSetId,
            BiConsumer<VlmModelComponent, Double> progressCallback) {
        VlmModelSet modelSet = VlmModelSet.getModelSet(modelSetId);
        if (modelSet == null) {
            throw new IllegalArgumentException("Unknown model set: " + modelSetId);
        }
        return DOWNLOADER.downloadModelSet(modelSet, progressCallback);
    }

    /**
     * Download models for document understanding (SmolDocling).
     */
    public static VlmModelSetDownloader.DownloadSetResult downloadForDocumentUnderstanding() {
        return DOWNLOADER.downloadModelSet(VlmModelSet.SMOLDOCLING_256M);
    }

    /**
     * Download models for table extraction (TableFormer).
     */
    public static VlmModelSetDownloader.DownloadSetResult downloadForTableExtraction() {
        return DOWNLOADER.downloadModelSet(VlmModelSet.DOCLING_TABLEFORMER);
    }

    /**
     * Download models for image embedding (SigLIP).
     */
    public static VlmModelSetDownloader.DownloadSetResult downloadForImageEmbedding() {
        return DOWNLOADER.downloadModelSet(VlmModelSet.SIGLIP_VISION);
    }

    /**
     * Download models for image-text similarity (CLIP).
     */
    public static VlmModelSetDownloader.DownloadSetResult downloadForImageTextSimilarity() {
        return DOWNLOADER.downloadModelSet(VlmModelSet.CLIP_VIT_BASE);
    }

    /**
     * Download models for form extraction (Donut).
     */
    public static VlmModelSetDownloader.DownloadSetResult downloadForFormExtraction() {
        return DOWNLOADER.downloadModelSet(VlmModelSet.DONUT_BASE);
    }

    /**
     * Download all models needed for an extraction configuration.
     *
     * @param config extraction configuration
     * @return list of download results
     */
    public static List<VlmModelSetDownloader.DownloadSetResult> downloadForConfig(VlmExtractionConfig config) {
        List<VlmModelSetDownloader.DownloadSetResult> results = new ArrayList<>();
        for (VlmModelSet modelSet : config.getRequiredModelSets()) {
            results.add(DOWNLOADER.downloadModelSet(modelSet));
        }
        return results;
    }

    /**
     * Ensure all models for a configuration are downloaded.
     *
     * @param config extraction configuration
     * @return list of model IDs that failed to download (empty if all succeeded)
     */
    public static List<String> ensureModelsForConfig(VlmExtractionConfig config) {
        return RESOLVER.ensureDownloaded(config);
    }

    /**
     * Download all available models (useful for offline preparation).
     *
     * @param parallelism number of concurrent downloads
     * @return list of download results
     */
    public static List<VlmModelSetDownloader.DownloadSetResult> downloadAll(int parallelism) {
        List<VlmModelSet> allSets = new ArrayList<>(VlmModelSet.getAllModelSets());
        return DOWNLOADER.downloadModelSets(allSets, parallelism);
    }

    // =====================================================================
    // RESOLUTION METHODS
    // =====================================================================

    /**
     * Resolve a model ID to local paths.
     *
     * @param modelId model ID (e.g., "smoldocling-256m")
     * @return resolved model with local paths
     */
    public static VlmModelResolver.ResolvedModel resolve(String modelId) {
        return RESOLVER.resolve(modelId);
    }

    /**
     * Resolve model for an extraction type.
     *
     * @param extractionType type of extraction
     * @return resolved model, or empty if no model configured for this type
     */
    public static Optional<VlmModelResolver.ResolvedModel> resolveForExtraction(VlmExtractionType extractionType) {
        return RESOLVER.resolveForExtraction(extractionType);
    }

    /**
     * Resolve all models for an extraction configuration.
     *
     * @param config extraction configuration
     * @return map of extraction type to resolved model
     */
    public static Map<VlmExtractionType, VlmModelResolver.ResolvedModel> resolveForConfig(VlmExtractionConfig config) {
        return RESOLVER.resolveForConfig(config);
    }

    // =====================================================================
    // STATUS METHODS
    // =====================================================================

    /**
     * Check if a model is cached locally.
     *
     * @param modelId model ID
     * @return true if cached
     */
    public static boolean isCached(String modelId) {
        return RESOLVER.isCached(modelId);
    }

    /**
     * Get cache status for all models.
     *
     * @return map of model ID to cached status
     */
    public static Map<String, Boolean> getCacheStatus() {
        return RESOLVER.getCacheStatus();
    }

    /**
     * Print cache status to stdout.
     */
    public static void printCacheStatus() {
        System.out.println("VLM Model Cache Status:");
        System.out.println("========================");
        Map<String, Boolean> status = getCacheStatus();
        for (Map.Entry<String, Boolean> entry : status.entrySet()) {
            String icon = entry.getValue() ? "[X]" : "[ ]";
            VlmModelSet set = VlmModelSet.getModelSet(entry.getKey());
            String name = set != null ? set.getDisplayName() : entry.getKey();
            System.out.printf("%s %s (%s)%n", icon, name, entry.getKey());
        }
        System.out.println();
        System.out.println("Cache directory: " + VlmModelSetDownloader.getCacheDirectory());
    }

    /**
     * Print detailed information about a model set.
     */
    public static void printModelInfo(String modelSetId) {
        VlmModelSet set = VlmModelSet.getModelSet(modelSetId);
        if (set == null) {
            System.out.println("Unknown model set: " + modelSetId);
            return;
        }
        System.out.println(set.getSummary());
    }

    /**
     * Get all available model set IDs.
     */
    public static Set<String> getAvailableModelIds() {
        return VlmModelSet.getAvailableModelSetIds();
    }

    /**
     * Get the cache directory path.
     */
    public static Path getCacheDirectory() {
        return VlmModelSetDownloader.getCacheDirectory();
    }

    /**
     * Delete a cached model set.
     *
     * @param modelSetId model set ID to delete
     */
    public static void deleteFromCache(String modelSetId) {
        VlmModelSet modelSet = VlmModelSet.getModelSet(modelSetId);
        if (modelSet != null) {
            try {
                DOWNLOADER.deleteModelSet(modelSet);
                log.info("Deleted model set: {}", modelSetId);
            } catch (Exception e) {
                log.error("Failed to delete model set: {}", modelSetId, e);
            }
        }
    }

    // =====================================================================
    // CONVENIENCE: PRESET CONFIGS
    // =====================================================================

    /**
     * Get extraction config for scanned documents.
     */
    public static VlmExtractionConfig configForScannedDocuments() {
        return VlmExtractionConfig.forScannedDocuments();
    }

    /**
     * Get extraction config for text-based PDFs with tables.
     */
    public static VlmExtractionConfig configForTextPdfs() {
        return VlmExtractionConfig.forTextPdfs();
    }

    /**
     * Get extraction config for scientific papers.
     */
    public static VlmExtractionConfig configForScientificPapers() {
        return VlmExtractionConfig.forScientificPapers();
    }

    /**
     * Get extraction config for forms and invoices.
     */
    public static VlmExtractionConfig configForForms() {
        return VlmExtractionConfig.forForms();
    }

    /**
     * Get comprehensive extraction config with all available types.
     */
    public static VlmExtractionConfig configComprehensive() {
        return VlmExtractionConfig.comprehensive();
    }
}
