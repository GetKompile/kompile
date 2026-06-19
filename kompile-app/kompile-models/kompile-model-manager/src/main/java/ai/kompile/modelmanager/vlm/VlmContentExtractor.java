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

import ai.kompile.modelmanager.cache.NoOpPipelineOutputCache;
import ai.kompile.modelmanager.cache.PipelineCacheEntry;
import ai.kompile.modelmanager.cache.PipelineCacheKey;
import ai.kompile.modelmanager.cache.PipelineOutputCache;
import ai.kompile.modelmanager.vlm.dynamic.VlmPipelineDefinition;
import ai.kompile.modelmanager.vlm.dynamic.VlmPipelineStageConfig;
import ai.kompile.modelmanager.vlm.dynamic.VlmStageDefinition;
import ai.kompile.modelmanager.vlm.registry.VlmPipelineRegistry;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import javax.imageio.ImageIO;

/**
 * VLM-based content extractor for document understanding.
 *
 * This class orchestrates VLM model inference for extracting structured content
 * from document images. It manages model loading, preprocessing, and inference
 * to produce text, tables, and other structured outputs.
 *
 * <h2>Supported Extraction Types</h2>
 * <ul>
 *   <li><b>Document Understanding</b> - Full page → structured text (DocTags/Markdown)</li>
 *   <li><b>Table Extraction</b> - Table regions → markdown tables</li>
 *   <li><b>Figure Understanding</b> - Images/charts → captions and data</li>
 *   <li><b>Form Extraction</b> - Forms → key-value JSON</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create extractor with configuration
 * VlmExtractionConfig config = VlmExtractionConfig.forScannedDocuments();
 * VlmContentExtractor extractor = new VlmContentExtractor(config);
 *
 * // Ensure models are downloaded
 * extractor.ensureModelsReady();
 *
 * // Extract content from a PDF page image
 * BufferedImage pageImage = renderPdfPage(pdfFile, pageNum, 300);
 * VlmExtractionOutput output = extractor.extract(pageImage);
 *
 * // Get the extracted text
 * String markdown = output.getText();
 * List<ExtractedTable> tables = output.getTables();
 * }</pre>
 *
 * <h2>Integration with Document Loading</h2>
 * <pre>{@code
 * // In a PDF loader
 * for (int page = 0; page < numPages; page++) {
 *     BufferedImage pageImage = renderer.renderImageWithDPI(page, dpi);
 *
 *     VlmExtractionOutput output = vlmExtractor.extract(pageImage, progress -> {
 *         log.info("Page {}: {}", page, progress.getMessage());
 *     });
 *
 *     // Create document from extracted text
 *     Document doc = new Document(output.getText());
 *     doc.getMetadata().put("page", page);
 *     doc.getMetadata().put("extractionMethod", "vlm");
 *     doc.getMetadata().put("vlmModel", output.getModelId());
 *
 *     // Add table documents if extracted separately
 *     for (ExtractedTable table : output.getTables()) {
 *         Document tableDoc = new Document(table.getMarkdown());
 *         tableDoc.getMetadata().put("contentType", "table");
 *         documents.add(tableDoc);
 *     }
 * }
 * }</pre>
 *
 * @author Kompile Inc.
 */
public class VlmContentExtractor {

    private static final Logger log = LoggerFactory.getLogger(VlmContentExtractor.class);

    private final VlmExtractionConfig config;
    private final VlmModelResolver resolver;
    private final Map<VlmExtractionType, VlmModelResolver.ResolvedModel> resolvedModels;
    private final PipelineOutputCache outputCache;
    private final ObjectMapper cacheObjectMapper;

    // Optional inference delegate (set when samediff-vlm is available)
    private VlmInferenceDelegate inferenceDelegate;

    // Inference engines (to be initialized when models are loaded)
    private volatile boolean modelsReady = false;

    public VlmContentExtractor(VlmExtractionConfig config) {
        this(config, new NoOpPipelineOutputCache());
    }

    public VlmContentExtractor(VlmExtractionConfig config, PipelineOutputCache outputCache) {
        this.config = config;
        this.resolver = new VlmModelResolver();
        this.resolvedModels = new HashMap<>();
        this.outputCache = outputCache != null ? outputCache : new NoOpPipelineOutputCache();
        this.cacheObjectMapper = JsonUtils.standardMapper();
    }

    /**
     * Set the inference delegate for actual VLM inference.
     *
     * <p>When a delegate is set, the stub inference methods will delegate to it
     * instead of returning placeholder results.</p>
     *
     * @param delegate the inference delegate implementation
     */
    public void setInferenceDelegate(VlmInferenceDelegate delegate) {
        this.inferenceDelegate = delegate;
        log.info("VLM inference delegate set: {}", delegate != null ? delegate.getClass().getSimpleName() : "null");
    }

    /**
     * Get the current inference delegate.
     */
    public VlmInferenceDelegate getInferenceDelegate() {
        return inferenceDelegate;
    }

    /**
     * Create extractor for scanned documents.
     */
    public static VlmContentExtractor forScannedDocuments() {
        return new VlmContentExtractor(VlmExtractionConfig.forScannedDocuments());
    }

    /**
     * Create extractor for text PDFs with table support.
     */
    public static VlmContentExtractor forTextPdfs() {
        return new VlmContentExtractor(VlmExtractionConfig.forTextPdfs());
    }

    /**
     * Create extractor for forms.
     */
    public static VlmContentExtractor forForms() {
        return new VlmContentExtractor(VlmExtractionConfig.forForms());
    }

    // =====================================================================
    // MODEL MANAGEMENT
    // =====================================================================

    /**
     * Ensure all required models are downloaded and ready.
     *
     * @return list of model IDs that failed to download (empty if all succeeded)
     */
    public List<String> ensureModelsReady() {
        List<String> failures = new ArrayList<>();

        for (VlmExtractionType type : config.getEnabledExtractions()) {
            try {
                VlmModelSet modelSet = config.getModelSet(type);
                if (modelSet != null) {
                    VlmModelResolver.ResolvedModel resolved = resolver.resolve(modelSet.getSetId());
                    if (resolved.isComplete()) {
                        resolvedModels.put(type, resolved);
                        log.info("Model ready for {}: {}", type.getId(), resolved.getModelId());
                    } else {
                        log.warn("Model incomplete for {}: missing {}", type.getId(), resolved.getMissingComponents());
                        failures.add(modelSet.getSetId());
                    }
                }
            } catch (Exception e) {
                log.error("Failed to resolve model for {}: {}", type.getId(), e.getMessage());
                failures.add(type.getId());
            }
        }

        modelsReady = failures.isEmpty();
        return failures;
    }

    /**
     * Check if models are ready for inference.
     */
    public boolean isModelsReady() {
        return modelsReady;
    }

    /**
     * Get the resolved model for an extraction type.
     */
    public Optional<VlmModelResolver.ResolvedModel> getResolvedModel(VlmExtractionType type) {
        return Optional.ofNullable(resolvedModels.get(type));
    }

    /**
     * Get paths to model components for external inference engines.
     *
     * @param type extraction type
     * @return map of component key to file path
     */
    public Map<String, Path> getModelPaths(VlmExtractionType type) {
        VlmModelResolver.ResolvedModel model = resolvedModels.get(type);
        return model != null ? model.getComponentPaths() : Collections.emptyMap();
    }

    // =====================================================================
    // EXTRACTION METHODS
    // =====================================================================

    /**
     * Extract content from a document image.
     *
     * @param image the document page image
     * @return extraction output
     */
    public VlmExtractionOutput extract(BufferedImage image) {
        return extract(image, null);
    }

    /**
     * Extract content from a document image with progress callback.
     *
     * @param image the document page image
     * @param progressCallback progress updates
     * @return extraction output
     */
    public VlmExtractionOutput extract(BufferedImage image, Consumer<ExtractionProgress> progressCallback) {
        if (!modelsReady) {
            List<String> failures = ensureModelsReady();
            if (!failures.isEmpty()) {
                return VlmExtractionOutput.failed("Models not ready: " + failures);
            }
        }

        VlmExtractionOutput.Builder outputBuilder = VlmExtractionOutput.builder();
        long startTime = System.currentTimeMillis();

        try {
            // Document understanding (primary extraction)
            if (config.isEnabled(VlmExtractionType.DOCUMENT_UNDERSTANDING)) {
                if (progressCallback != null) {
                    progressCallback.accept(ExtractionProgress.starting("Document Understanding"));
                }

                VlmModelResolver.ResolvedModel model = resolvedModels.get(VlmExtractionType.DOCUMENT_UNDERSTANDING);
                if (model != null) {
                    String text = runDocumentUnderstanding(image, model, progressCallback);
                    outputBuilder.text(text);
                    outputBuilder.modelId(model.getModelId());
                    outputBuilder.outputFormat(config.getOutputFormat());
                }
            }

            // Table extraction
            if (config.isEnabled(VlmExtractionType.TABLE_EXTRACTION)) {
                if (progressCallback != null) {
                    progressCallback.accept(ExtractionProgress.starting("Table Extraction"));
                }

                VlmModelResolver.ResolvedModel model = resolvedModels.get(VlmExtractionType.TABLE_EXTRACTION);
                if (model != null) {
                    List<ExtractedTable> tables = runTableExtraction(image, model, progressCallback);
                    outputBuilder.tables(tables);
                }
            }

            // Image embedding
            if (config.isEnabled(VlmExtractionType.IMAGE_EMBEDDING)) {
                if (progressCallback != null) {
                    progressCallback.accept(ExtractionProgress.starting("Image Embedding"));
                }

                VlmModelResolver.ResolvedModel model = resolvedModels.get(VlmExtractionType.IMAGE_EMBEDDING);
                if (model != null) {
                    float[] embedding = runImageEmbedding(image, model);
                    outputBuilder.imageEmbedding(embedding);
                }
            }

            // Form extraction
            if (config.isEnabled(VlmExtractionType.FORM_EXTRACTION)) {
                if (progressCallback != null) {
                    progressCallback.accept(ExtractionProgress.starting("Form Extraction"));
                }

                VlmModelResolver.ResolvedModel model = resolvedModels.get(VlmExtractionType.FORM_EXTRACTION);
                if (model != null) {
                    Map<String, String> formFields = runFormExtraction(image, model, progressCallback);
                    outputBuilder.formFields(formFields);
                }
            }

            long elapsed = System.currentTimeMillis() - startTime;
            outputBuilder.extractionTimeMs(elapsed);
            outputBuilder.success(true);

            if (progressCallback != null) {
                progressCallback.accept(ExtractionProgress.complete(elapsed));
            }

        } catch (Exception e) {
            log.error("VLM extraction failed", e);
            outputBuilder.success(false);
            outputBuilder.errorMessage(e.getMessage());
        }

        return outputBuilder.build();
    }

    /**
     * Extract content from a PDF file.
     *
     * @param pdfFile the PDF file
     * @param dpi rendering DPI
     * @return list of extraction outputs (one per page)
     */
    public List<VlmExtractionOutput> extractFromPdf(File pdfFile, int dpi) {
        return extractFromPdf(pdfFile, dpi, null);
    }

    /**
     * Extract content from a PDF file with progress callback.
     *
     * <p>Renders pages using PDFBox at the specified DPI and processes each page.
     * Respects page selection from the config: startPage, maxPages, and pageRange.</p>
     *
     * @param pdfFile the PDF file
     * @param dpi rendering DPI
     * @param progressCallback progress updates
     * @return list of extraction outputs (one per page)
     */
    public List<VlmExtractionOutput> extractFromPdf(File pdfFile, int dpi,
                                                     Consumer<ExtractionProgress> progressCallback) {
        List<VlmExtractionOutput> results = new ArrayList<>();

        try {
            // Use PDFBox via reflection to avoid hard dependency in model-manager
            Class<?> loaderClass = Class.forName("org.apache.pdfbox.Loader");
            java.lang.reflect.Method loadPDF = loaderClass.getMethod("loadPDF", File.class);
            Object document = loadPDF.invoke(null, pdfFile);

            Class<?> pdDocumentClass = document.getClass();
            java.lang.reflect.Method getNumberOfPages = pdDocumentClass.getMethod("getNumberOfPages");
            int totalPages = (int) getNumberOfPages.invoke(document);

            // Create renderer
            Class<?> rendererClass = Class.forName("org.apache.pdfbox.rendering.PDFRenderer");
            Object renderer = rendererClass.getConstructor(pdDocumentClass).newInstance(document);
            java.lang.reflect.Method renderImageWithDPI = rendererClass.getMethod(
                    "renderImageWithDPI", int.class, float.class);

            // Determine which pages to process from config
            List<Integer> pagesToProcess = determinePagesToProcess(totalPages);

            if (progressCallback != null) {
                progressCallback.accept(ExtractionProgress.starting(
                        "Processing " + pagesToProcess.size() + " pages"));
            }

            for (int i = 0; i < pagesToProcess.size(); i++) {
                int pageNum = pagesToProcess.get(i);

                if (progressCallback != null) {
                    int percent = ((i + 1) * 100) / pagesToProcess.size();
                    progressCallback.accept(ExtractionProgress.inProgress(
                            "page-" + pageNum, percent,
                            "Processing page " + pageNum + "/" + totalPages));
                }

                BufferedImage pageImage = (BufferedImage) renderImageWithDPI.invoke(
                        renderer, pageNum - 1, (float) dpi);

                VlmExtractionOutput output = extract(pageImage, progressCallback);
                results.add(output);
            }

            // Close document
            java.lang.reflect.Method closeMethod = pdDocumentClass.getMethod("close");
            closeMethod.invoke(document);

            if (progressCallback != null) {
                progressCallback.accept(ExtractionProgress.complete(0));
            }

        } catch (ClassNotFoundException e) {
            log.error("PDFBox not available on classpath. Add pdfbox dependency to use PDF extraction.");
            results.add(VlmExtractionOutput.failed(
                    "PDFBox not available. Add org.apache.pdfbox:pdfbox to classpath."));
        } catch (Exception e) {
            log.error("Failed to extract from PDF: {}", e.getMessage(), e);
            results.add(VlmExtractionOutput.failed("PDF extraction failed: " + e.getMessage()));
        }

        return results;
    }

    /**
     * Determine which pages to process based on config parameters:
     * pageRange, startPage, and maxPages.
     */
    private List<Integer> determinePagesToProcess(int totalPages) {
        // Check pageRange first (e.g., "1-3,5,7-10")
        String pageRange = config.getPageRange();
        if (pageRange != null && !pageRange.isEmpty()) {
            List<Integer> pages = new ArrayList<>();
            String[] parts = pageRange.split(",");
            for (String part : parts) {
                part = part.trim();
                if (part.contains("-")) {
                    String[] range = part.split("-");
                    int start = Integer.parseInt(range[0].trim());
                    int end = Integer.parseInt(range[1].trim());
                    for (int i = start; i <= Math.min(end, totalPages); i++) {
                        pages.add(i);
                    }
                } else {
                    int page = Integer.parseInt(part);
                    if (page >= 1 && page <= totalPages) {
                        pages.add(page);
                    }
                }
            }
            return pages;
        }

        // Use startPage + maxPages
        int startPage = config.getStartPage();
        int maxPages = config.getMaxPages();
        List<Integer> pages = new ArrayList<>();
        int endPage = Math.min(startPage + maxPages - 1, totalPages);
        for (int i = startPage; i <= endPage; i++) {
            pages.add(i);
        }
        return pages;
    }

    // =====================================================================
    // CACHE-AWARE EXTRACTION
    // =====================================================================

    /**
     * Extract content with output caching.
     *
     * <p>Checks the cache before running extraction. If a cached result exists
     * for the same content hash and pipeline configuration, returns it immediately.
     * Otherwise, runs extraction and caches the result.</p>
     *
     * @param image      the document page image
     * @param pipelineId the pipeline ID (used as part of cache key)
     * @return extraction output (from cache or fresh)
     */
    public VlmExtractionOutput extractWithCache(BufferedImage image, String pipelineId) {
        return extractWithCache(image, pipelineId, null);
    }

    /**
     * Extract content with output caching and progress callback.
     *
     * @param image            the document page image
     * @param pipelineId       the pipeline ID
     * @param progressCallback progress updates
     * @return extraction output (from cache or fresh)
     */
    public VlmExtractionOutput extractWithCache(BufferedImage image, String pipelineId,
                                                  Consumer<ExtractionProgress> progressCallback) {
        if (!outputCache.isAvailable()) {
            return extractWithPipeline(image, pipelineId, progressCallback);
        }

        // Compute content hash from image bytes
        byte[] imageBytes = imageToBytes(image);
        if (imageBytes == null) {
            log.warn("Failed to serialize image for cache key; proceeding without cache");
            return extractWithPipeline(image, pipelineId, progressCallback);
        }

        PipelineCacheKey cacheKey = PipelineCacheKey.forPipelineOutput(imageBytes, pipelineId);

        // Check cache
        Optional<PipelineCacheEntry> cached = outputCache.get(cacheKey);
        if (cached.isPresent()) {
            log.info("Cache hit for pipeline '{}' (content hash: {}...)",
                    pipelineId, cacheKey.getContentHash().substring(0, 12));
            if (progressCallback != null) {
                progressCallback.accept(ExtractionProgress.complete(0));
            }
            return deserializeOutput(cached.get());
        }

        // Cache miss - run extraction
        log.debug("Cache miss for pipeline '{}', running extraction", pipelineId);
        VlmExtractionOutput output = extractWithPipeline(image, pipelineId, progressCallback);

        // Cache successful results
        if (output.isSuccess()) {
            cacheOutput(cacheKey, output, pipelineId);
        }

        return output;
    }

    /**
     * Extract with cache using a pre-computed content hash.
     * Use this when the content hash is already available (e.g., from source_checksum metadata).
     *
     * @param image       the document page image
     * @param contentHash pre-computed SHA-256 hex string of the content
     * @param pipelineId  pipeline ID
     * @param progressCallback progress updates
     * @return extraction output
     */
    public VlmExtractionOutput extractWithCacheByHash(BufferedImage image, String contentHash,
                                                        String pipelineId,
                                                        Consumer<ExtractionProgress> progressCallback) {
        if (!outputCache.isAvailable()) {
            return extractWithPipeline(image, pipelineId, progressCallback);
        }

        PipelineCacheKey cacheKey = PipelineCacheKey.fromContentHash(contentHash, pipelineId);

        // Check cache
        Optional<PipelineCacheEntry> cached = outputCache.get(cacheKey);
        if (cached.isPresent()) {
            log.info("Cache hit for pipeline '{}' by hash", pipelineId);
            if (progressCallback != null) {
                progressCallback.accept(ExtractionProgress.complete(0));
            }
            return deserializeOutput(cached.get());
        }

        // Cache miss
        VlmExtractionOutput output = extractWithPipeline(image, pipelineId, progressCallback);
        if (output.isSuccess()) {
            cacheOutput(cacheKey, output, pipelineId);
        }
        return output;
    }

    /**
     * Get the output cache instance.
     */
    public PipelineOutputCache getOutputCache() {
        return outputCache;
    }

    /**
     * Invalidate all cached outputs for a specific content hash.
     *
     * @param contentHash SHA-256 hash of the content
     * @return number of entries removed
     */
    public int invalidateCache(String contentHash) {
        return outputCache.removeByContentHash(contentHash);
    }

    /**
     * Invalidate all cached outputs for a specific pipeline.
     *
     * @param pipelineId pipeline identifier
     * @return number of entries removed
     */
    public int invalidatePipelineCache(String pipelineId) {
        return outputCache.removeByPipeline(pipelineId);
    }

    // ========== Cache Serialization Helpers ==========

    private void cacheOutput(PipelineCacheKey key, VlmExtractionOutput output, String pipelineId) {
        try {
            String serialized = cacheObjectMapper.writeValueAsString(new CacheableOutput(output));
            PipelineCacheEntry entry = PipelineCacheEntry.builder()
                    .cacheKey(key.toKeyString())
                    .entryType(PipelineCacheEntry.EntryType.FINAL_OUTPUT)
                    .pipelineId(pipelineId)
                    .contentHash(key.getContentHash())
                    .serializedOutput(serialized)
                    .outputClassName(CacheableOutput.class.getName())
                    .metadata("extractionTimeMs", output.getExtractionTimeMs())
                    .sizeBytes(serialized.length())
                    .build();
            outputCache.put(key, entry);
            log.debug("Cached output for pipeline '{}' ({} bytes)", pipelineId, serialized.length());
        } catch (Exception e) {
            log.warn("Failed to cache pipeline output: {}", e.getMessage());
        }
    }

    private void cacheStageCheckpoint(PipelineCacheKey key, Object stageOutput,
                                       String pipelineId, String stageId) {
        try {
            String serialized = cacheObjectMapper.writeValueAsString(stageOutput);
            PipelineCacheEntry entry = PipelineCacheEntry.builder()
                    .cacheKey(key.toKeyString())
                    .entryType(PipelineCacheEntry.EntryType.STAGE_CHECKPOINT)
                    .pipelineId(pipelineId)
                    .stageId(stageId)
                    .contentHash(key.getContentHash())
                    .serializedOutput(serialized)
                    .outputClassName(stageOutput.getClass().getName())
                    .build();
            outputCache.put(key, entry);
            log.debug("Checkpointed stage '{}' for pipeline '{}'", stageId, pipelineId);
        } catch (Exception e) {
            log.warn("Failed to checkpoint stage output: {}", e.getMessage());
        }
    }

    private VlmExtractionOutput deserializeOutput(PipelineCacheEntry entry) {
        try {
            CacheableOutput cached = cacheObjectMapper.readValue(
                    entry.getSerializedOutput(), CacheableOutput.class);
            return cached.toOutput();
        } catch (Exception e) {
            log.warn("Failed to deserialize cached output: {}", e.getMessage());
            return VlmExtractionOutput.failed("Cache deserialization error: " + e.getMessage());
        }
    }

    private static byte[] imageToBytes(BufferedImage image) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Serializable wrapper for VlmExtractionOutput fields for caching.
     */
    static class CacheableOutput {
        public boolean success;
        public String errorMessage;
        public String text;
        public String modelId;
        public String outputFormat;
        public List<Map<String, Object>> tables;
        public float[] imageEmbedding;
        public Map<String, String> formFields;
        public long extractionTimeMs;

        public CacheableOutput() {} // For Jackson

        public CacheableOutput(VlmExtractionOutput output) {
            this.success = output.isSuccess();
            this.errorMessage = output.getErrorMessage();
            this.text = output.getText();
            this.modelId = output.getModelId();
            this.outputFormat = output.getOutputFormat();
            this.imageEmbedding = output.getImageEmbedding();
            this.formFields = output.getFormFields();
            this.extractionTimeMs = output.getExtractionTimeMs();
            // Serialize tables as maps
            this.tables = new ArrayList<>();
            if (output.getTables() != null) {
                for (ExtractedTable table : output.getTables()) {
                    Map<String, Object> tableMap = new HashMap<>();
                    tableMap.put("markdown", table.getMarkdown());
                    tableMap.put("rowCount", table.getRowCount());
                    tableMap.put("columnCount", table.getColumnCount());
                    tableMap.put("headers", table.getHeaders());
                    tableMap.put("metadata", table.getMetadata());
                    this.tables.add(tableMap);
                }
            }
        }

        @SuppressWarnings("unchecked")
        public VlmExtractionOutput toOutput() {
            VlmExtractionOutput.Builder builder = VlmExtractionOutput.builder()
                    .success(success)
                    .errorMessage(errorMessage)
                    .text(text)
                    .modelId(modelId)
                    .outputFormat(outputFormat)
                    .imageEmbedding(imageEmbedding)
                    .formFields(formFields)
                    .extractionTimeMs(extractionTimeMs);

            if (tables != null) {
                List<ExtractedTable> extractedTables = new ArrayList<>();
                for (Map<String, Object> tableMap : tables) {
                    String markdown = (String) tableMap.get("markdown");
                    int rowCount = tableMap.get("rowCount") instanceof Number n ? n.intValue() : 0;
                    int columnCount = tableMap.get("columnCount") instanceof Number n ? n.intValue() : 0;
                    List<String> headers = tableMap.get("headers") instanceof List<?> list
                            ? list.stream().map(Object::toString).toList()
                            : Collections.emptyList();
                    Map<String, Object> metadata = tableMap.get("metadata") instanceof Map<?, ?> map
                            ? (Map<String, Object>) map
                            : Collections.emptyMap();
                    extractedTables.add(new ExtractedTable(markdown, rowCount, columnCount, headers, metadata));
                }
                builder.tables(extractedTables);
            }

            return builder.build();
        }
    }

    // =====================================================================
    // DYNAMIC PIPELINE EXTRACTION
    // =====================================================================

    /**
     * Extract content using a dynamic pipeline configuration.
     *
     * <p>This method uses pipelines defined in the {@link VlmPipelineRegistry}
     * rather than hardcoded extraction configurations.</p>
     *
     * <h2>Usage Example</h2>
     * <pre>{@code
     * VlmContentExtractor extractor = new VlmContentExtractor(VlmExtractionConfig.none());
     *
     * // Use a builtin pipeline
     * VlmExtractionOutput output = extractor.extractWithPipeline(image, "scanned-documents");
     *
     * // Use a custom pipeline
     * VlmExtractionOutput output = extractor.extractWithPipeline(image, "my-custom-pipeline");
     * }</pre>
     *
     * @param image the document page image
     * @param pipelineId the pipeline ID from VlmPipelineRegistry
     * @return extraction output
     * @throws IllegalArgumentException if pipeline is not found
     */
    public VlmExtractionOutput extractWithPipeline(BufferedImage image, String pipelineId) {
        return extractWithPipeline(image, pipelineId, null);
    }

    /**
     * Extract content using a dynamic pipeline configuration with progress callback.
     *
     * @param image the document page image
     * @param pipelineId the pipeline ID from VlmPipelineRegistry
     * @param progressCallback progress updates
     * @return extraction output
     * @throws IllegalArgumentException if pipeline is not found
     */
    public VlmExtractionOutput extractWithPipeline(BufferedImage image, String pipelineId,
                                                    Consumer<ExtractionProgress> progressCallback) {
        VlmPipelineRegistry registry = VlmPipelineRegistry.getInstance();
        Optional<VlmPipelineDefinition> pipelineOpt = registry.getPipeline(pipelineId);

        if (pipelineOpt.isEmpty()) {
            throw new IllegalArgumentException("Pipeline not found: " + pipelineId);
        }

        VlmPipelineDefinition pipeline = pipelineOpt.get();

        // Validate pipeline is enabled
        if (!pipeline.isEnabled()) {
            return VlmExtractionOutput.failed("Pipeline is disabled: " + pipelineId);
        }

        // Convert pipeline to extraction config and extract
        VlmExtractionConfig pipelineConfig = pipeline.toExtractionConfig();

        // Create a temporary extractor with the pipeline config
        VlmContentExtractor pipelineExtractor = new VlmContentExtractor(pipelineConfig);

        // Execute extraction
        return pipelineExtractor.extractWithPipelineDefinition(image, pipeline, progressCallback);
    }

    /**
     * Extract content using an in-memory pipeline definition.
     *
     * <p>Use this when you have a pipeline definition that may not be registered
     * in the registry, such as a pipeline being validated or previewed.</p>
     *
     * @param image the document page image
     * @param pipeline the pipeline definition
     * @param progressCallback progress updates
     * @return extraction output
     */
    public VlmExtractionOutput extractWithPipelineDefinition(BufferedImage image,
                                                              VlmPipelineDefinition pipeline,
                                                              Consumer<ExtractionProgress> progressCallback) {
        if (pipeline == null) {
            return VlmExtractionOutput.failed("Pipeline definition is null");
        }

        // Validate pipeline
        List<String> validationErrors = pipeline.validate();
        if (!validationErrors.isEmpty()) {
            return VlmExtractionOutput.failed("Pipeline validation failed: " + validationErrors);
        }

        long startTime = System.currentTimeMillis();
        VlmExtractionOutput.Builder outputBuilder = VlmExtractionOutput.builder();

        try {
            if (progressCallback != null) {
                progressCallback.accept(ExtractionProgress.starting("Pipeline: " + pipeline.getDisplayName()));
            }

            // Execute based on pipeline type
            if (pipeline.isSequence()) {
                executeSequencePipeline(image, pipeline, outputBuilder, progressCallback);
            } else if (pipeline.isGraph()) {
                executeGraphPipeline(image, pipeline, outputBuilder, progressCallback);
            }

            long elapsed = System.currentTimeMillis() - startTime;
            outputBuilder.extractionTimeMs(elapsed);
            outputBuilder.success(true);

            if (progressCallback != null) {
                progressCallback.accept(ExtractionProgress.complete(elapsed));
            }

        } catch (Exception e) {
            log.error("Pipeline extraction failed: {}", pipeline.getPipelineId(), e);
            outputBuilder.success(false);
            outputBuilder.errorMessage(e.getMessage());
        }

        return outputBuilder.build();
    }

    /**
     * Execute a sequence (linear) pipeline with per-stage checkpointing.
     *
     * <p>If a cache is available, each stage's output is checkpointed after
     * completion. On crash recovery, the pipeline can resume from the last
     * checkpointed stage rather than restarting from scratch.</p>
     */
    private void executeSequencePipeline(BufferedImage image,
                                          VlmPipelineDefinition pipeline,
                                          VlmExtractionOutput.Builder outputBuilder,
                                          Consumer<ExtractionProgress> progressCallback) {
        List<VlmPipelineStageConfig> stages = pipeline.getEnabledStages();
        VlmPipelineRegistry registry = VlmPipelineRegistry.getInstance();
        String pipelineId = pipeline.getPipelineId();

        log.info("Executing sequence pipeline '{}' with {} stages",
            pipelineId, stages.size());

        int stageNum = 0;
        int totalStages = stages.size();

        // Compute content hash for checkpointing (if cache available)
        String contentHash = null;
        if (outputCache.isAvailable()) {
            byte[] imageBytes = imageToBytes(image);
            if (imageBytes != null) {
                contentHash = PipelineCacheKey.sha256(imageBytes);
            }
        }

        // Check for existing stage checkpoints (crash recovery)
        int resumeFromStage = 0;
        Object currentData = image;
        if (contentHash != null && outputCache.isAvailable()) {
            List<PipelineCacheEntry> checkpoints = outputCache.getStageCheckpoints(contentHash, pipelineId);
            if (!checkpoints.isEmpty()) {
                PipelineCacheEntry lastCheckpoint = checkpoints.get(checkpoints.size() - 1);
                String lastStageId = lastCheckpoint.getStageId();
                // Find the index of the last checkpointed stage
                for (int i = 0; i < stages.size(); i++) {
                    if (stages.get(i).getStageId().equals(lastStageId)) {
                        resumeFromStage = i + 1; // Resume from the next stage
                        // Deserialize the last checkpoint output as current data
                        try {
                            currentData = cacheObjectMapper.readValue(
                                    lastCheckpoint.getSerializedOutput(), Object.class);
                            log.info("Resuming pipeline '{}' from stage {} (after '{}')",
                                    pipelineId, resumeFromStage + 1, lastStageId);
                        } catch (Exception e) {
                            log.warn("Failed to deserialize checkpoint, restarting pipeline: {}",
                                    e.getMessage());
                            resumeFromStage = 0;
                            currentData = image;
                        }
                        break;
                    }
                }
            }
        }

        // Execute stages
        for (int i = resumeFromStage; i < stages.size(); i++) {
            VlmPipelineStageConfig stageConfig = stages.get(i);
            stageNum = i + 1;
            String stageId = stageConfig.getStageId();

            if (progressCallback != null) {
                int percent = (stageNum * 100) / totalStages;
                progressCallback.accept(ExtractionProgress.inProgress(
                    stageId, percent, "Executing stage " + stageNum + "/" + totalStages));
            }

            // Get stage definition
            Optional<VlmStageDefinition> stageDefOpt = registry.getStage(stageId);
            if (stageDefOpt.isEmpty()) {
                log.warn("Unknown stage in pipeline: {}", stageId);
                continue;
            }

            VlmStageDefinition stageDef = stageDefOpt.get();

            // Execute stage
            currentData = executeStage(currentData, stageDef, stageConfig, pipeline, outputBuilder);

            // Checkpoint stage output (async, non-blocking)
            if (contentHash != null && outputCache.isAvailable() && currentData != null) {
                PipelineCacheKey stageKey = PipelineCacheKey.fromContentHash(
                        contentHash, pipelineId, stageId, stageConfig.getParameters());
                cacheStageCheckpoint(stageKey, currentData, pipelineId, stageId);
            }

            log.debug("Completed stage {}: {}", stageNum, stageId);
        }

        // If we got text output, add it to the builder
        if (currentData instanceof String text) {
            outputBuilder.text(text);
        }

        // Clean up stage checkpoints after successful pipeline completion
        if (contentHash != null && outputCache.isAvailable()) {
            outputCache.removeStageCheckpoints(contentHash, pipelineId);
        }
    }

    /**
     * Execute a graph (DAG) pipeline.
     */
    private void executeGraphPipeline(BufferedImage image,
                                       VlmPipelineDefinition pipeline,
                                       VlmExtractionOutput.Builder outputBuilder,
                                       Consumer<ExtractionProgress> progressCallback) {
        log.info("Executing graph pipeline '{}' with {} nodes",
            pipeline.getPipelineId(), pipeline.getGraphNodes().size());

        // Graph execution is more complex - requires topological sort and
        // tracking outputs from each node. This is a placeholder for the full implementation.

        if (progressCallback != null) {
            progressCallback.accept(ExtractionProgress.inProgress(
                "graph-execution", 50, "Graph pipeline execution"));
        }

        // For now, fall back to sequence execution based on extraction types
        VlmExtractionConfig fallbackConfig = pipeline.toExtractionConfig();
        VlmContentExtractor fallbackExtractor = new VlmContentExtractor(fallbackConfig);
        VlmExtractionOutput fallbackOutput = fallbackExtractor.extract(image, progressCallback);

        // Copy results to output builder
        if (fallbackOutput.hasText()) {
            outputBuilder.text(fallbackOutput.getText());
        }
        if (fallbackOutput.hasTables()) {
            outputBuilder.tables(fallbackOutput.getTables());
        }
        if (fallbackOutput.hasImageEmbedding()) {
            outputBuilder.imageEmbedding(fallbackOutput.getImageEmbedding());
        }
        if (fallbackOutput.hasFormFields()) {
            outputBuilder.formFields(fallbackOutput.getFormFields());
        }
        outputBuilder.modelId(fallbackOutput.getModelId());
        outputBuilder.outputFormat(fallbackOutput.getOutputFormat());
    }

    /**
     * Execute a single stage.
     */
    private Object executeStage(Object input,
                                 VlmStageDefinition stageDef,
                                 VlmPipelineStageConfig stageConfig,
                                 VlmPipelineDefinition pipeline,
                                 VlmExtractionOutput.Builder outputBuilder) {
        String stageId = stageDef.getStageId();
        log.debug("Executing stage: {}", stageId);

        // Map to builtin enum if possible
        VlmPipelineStage builtinStage = stageDef.toEnumIfBuiltin();

        if (builtinStage != null) {
            // Execute builtin stage logic
            return executeBuiltinStage(input, builtinStage, stageConfig, pipeline, outputBuilder);
        } else {
            // Custom stage - placeholder for extensible stage execution
            log.info("Custom stage execution not yet implemented: {}", stageId);
            return input;
        }
    }

    /**
     * Execute a builtin pipeline stage.
     */
    private Object executeBuiltinStage(Object input,
                                        VlmPipelineStage stage,
                                        VlmPipelineStageConfig stageConfig,
                                        VlmPipelineDefinition pipeline,
                                        VlmExtractionOutput.Builder outputBuilder) {
        // This is where the actual stage execution logic would go.
        // For now, this is a placeholder that demonstrates the structure.

        switch (stage) {
            case IMAGE_PREPROCESSING:
                log.debug("IMAGE_PREPROCESSING: Tiling image");
                // Would return List<BufferedImage> tiles
                return input;

            case IMAGE_NORMALIZATION:
                log.debug("IMAGE_NORMALIZATION: Normalizing pixel values");
                // Would return INDArray [batch, frames, 3, H, W]
                return input;

            case VISION_ENCODING:
                log.debug("VISION_ENCODING: Running vision encoder");
                // Would return image features tensor
                return input;

            case TEXT_TOKENIZATION:
                log.debug("TEXT_TOKENIZATION: Tokenizing text");
                // Would return token IDs
                return input;

            case TEXT_EMBEDDING:
                log.debug("TEXT_EMBEDDING: Embedding tokens");
                // Would return text embeddings
                return input;

            case VISION_TEXT_FUSION:
                log.debug("VISION_TEXT_FUSION: Fusing vision and text");
                // Would return fused embeddings
                return input;

            case AUTOREGRESSIVE_DECODING:
                log.debug("AUTOREGRESSIVE_DECODING: Running decoder");
                // Would return logits
                return input;

            case TOKEN_SAMPLING:
                log.debug("TOKEN_SAMPLING: Sampling tokens");
                // Would return selected token IDs
                return input;

            case TOKEN_DECODING:
                log.debug("TOKEN_DECODING: Decoding to text");
                // Would return decoded text
                String placeholderText = "[Dynamic pipeline extraction - stage: " + stage.name() +
                    ", pipeline: " + pipeline.getPipelineId() + "]";
                return placeholderText;

            default:
                log.warn("Unknown builtin stage: {}", stage);
                return input;
        }
    }

    /**
     * Create an extractor configured for a specific pipeline.
     *
     * @param pipelineId the pipeline ID
     * @return configured extractor
     * @throws IllegalArgumentException if pipeline not found
     */
    public static VlmContentExtractor forPipeline(String pipelineId) {
        return forPipeline(pipelineId, null);
    }

    /**
     * Create an extractor configured for a specific pipeline with output caching.
     *
     * @param pipelineId  the pipeline ID
     * @param outputCache the cache to use (null for no caching)
     * @return configured extractor
     * @throws IllegalArgumentException if pipeline not found
     */
    public static VlmContentExtractor forPipeline(String pipelineId, PipelineOutputCache outputCache) {
        VlmPipelineRegistry registry = VlmPipelineRegistry.getInstance();
        Optional<VlmPipelineDefinition> pipelineOpt = registry.getPipeline(pipelineId);

        if (pipelineOpt.isEmpty()) {
            throw new IllegalArgumentException("Pipeline not found: " + pipelineId);
        }

        VlmExtractionConfig config = pipelineOpt.get().toExtractionConfig();
        return new VlmContentExtractor(config, outputCache);
    }

    /**
     * Get list of available pipeline IDs.
     */
    public static List<String> getAvailablePipelineIds() {
        VlmPipelineRegistry registry = VlmPipelineRegistry.getInstance();
        return registry.getAllPipelines().stream()
            .filter(VlmPipelineDefinition::isEnabled)
            .map(VlmPipelineDefinition::getPipelineId)
            .toList();
    }

    // =====================================================================
    // INFERENCE METHODS (PLACEHOLDERS FOR SAMEDIFF/ONNX INTEGRATION)
    // =====================================================================

    /**
     * Run document understanding inference.
     * Delegates to {@link VlmInferenceDelegate} when available.
     */
    private String runDocumentUnderstanding(BufferedImage image,
                                            VlmModelResolver.ResolvedModel model,
                                            Consumer<ExtractionProgress> progressCallback) {
        // Delegate to inference delegate if available
        if (inferenceDelegate != null && inferenceDelegate.isAvailable()) {
            Consumer<String> simpleCallback = progressCallback != null
                    ? msg -> progressCallback.accept(ExtractionProgress.inProgress("doc-understanding", 50, msg))
                    : null;
            String result = inferenceDelegate.runDocumentUnderstanding(
                    image, model.getModelId(), config, simpleCallback);
            if (result != null) {
                return result;
            }
        }

        // Fallback: placeholder
        log.info("Document understanding inference would use model at: {}", model.getBasePath());
        return String.format("[VLM extraction placeholder - model: %s, format: %s]\n" +
                "To enable actual inference, set a VlmInferenceDelegate.",
            model.getModelId(), config.getOutputFormat());
    }

    /**
     * Run table extraction inference.
     * Delegates to {@link VlmInferenceDelegate} when available.
     */
    private List<ExtractedTable> runTableExtraction(BufferedImage image,
                                                     VlmModelResolver.ResolvedModel model,
                                                     Consumer<ExtractionProgress> progressCallback) {
        if (inferenceDelegate != null && inferenceDelegate.isAvailable()) {
            Consumer<String> simpleCallback = progressCallback != null
                    ? msg -> progressCallback.accept(ExtractionProgress.inProgress("table-extraction", 50, msg))
                    : null;
            List<Map<String, Object>> tableData = inferenceDelegate.runTableExtraction(
                    image, model.getModelId(), config, simpleCallback);
            if (tableData != null && !tableData.isEmpty()) {
                List<ExtractedTable> tables = new ArrayList<>();
                for (Map<String, Object> td : tableData) {
                    String markdown = td.get("markdown") instanceof String s ? s : "";
                    int rowCount = td.get("rowCount") instanceof Number n ? n.intValue() : 0;
                    int colCount = td.get("columnCount") instanceof Number n ? n.intValue() : 0;
                    tables.add(new ExtractedTable(markdown, rowCount, colCount,
                            Collections.emptyList(), Collections.emptyMap()));
                }
                return tables;
            }
        }

        log.info("Table extraction would use model at: {}", model.getBasePath());
        return Collections.emptyList();
    }

    /**
     * Run image embedding inference.
     * Delegates to {@link VlmInferenceDelegate} when available.
     */
    private float[] runImageEmbedding(BufferedImage image,
                                       VlmModelResolver.ResolvedModel model) {
        if (inferenceDelegate != null && inferenceDelegate.isAvailable()) {
            float[] embedding = inferenceDelegate.runImageEmbedding(image, model.getModelId());
            if (embedding != null) {
                return embedding;
            }
        }

        log.info("Image embedding would use model at: {}", model.getBasePath());
        return null;
    }

    /**
     * Run form extraction inference.
     * Delegates to {@link VlmInferenceDelegate} when available.
     */
    private Map<String, String> runFormExtraction(BufferedImage image,
                                                   VlmModelResolver.ResolvedModel model,
                                                   Consumer<ExtractionProgress> progressCallback) {
        if (inferenceDelegate != null && inferenceDelegate.isAvailable()) {
            Consumer<String> simpleCallback = progressCallback != null
                    ? msg -> progressCallback.accept(ExtractionProgress.inProgress("form-extraction", 50, msg))
                    : null;
            Map<String, String> fields = inferenceDelegate.runFormExtraction(
                    image, model.getModelId(), config, simpleCallback);
            if (fields != null && !fields.isEmpty()) {
                return fields;
            }
        }

        log.info("Form extraction would use model at: {}", model.getBasePath());
        return Collections.emptyMap();
    }

    // =====================================================================
    // OUTPUT AND PROGRESS CLASSES
    // =====================================================================

    /**
     * Progress information for VLM extraction.
     */
    public static class ExtractionProgress {
        private final String phase;
        private final int percentComplete;
        private final String message;
        private final long elapsedMs;

        private ExtractionProgress(String phase, int percentComplete, String message, long elapsedMs) {
            this.phase = phase;
            this.percentComplete = percentComplete;
            this.message = message;
            this.elapsedMs = elapsedMs;
        }

        public static ExtractionProgress starting(String phase) {
            return new ExtractionProgress(phase, 0, "Starting " + phase, 0);
        }

        public static ExtractionProgress inProgress(String phase, int percent, String message) {
            return new ExtractionProgress(phase, percent, message, 0);
        }

        public static ExtractionProgress complete(long elapsedMs) {
            return new ExtractionProgress("complete", 100, "Extraction complete", elapsedMs);
        }

        public String getPhase() { return phase; }
        public int getPercentComplete() { return percentComplete; }
        public String getMessage() { return message; }
        public long getElapsedMs() { return elapsedMs; }
    }

    /**
     * Represents an extracted table.
     */
    public static class ExtractedTable {
        private final String markdown;
        private final int rowCount;
        private final int columnCount;
        private final List<String> headers;
        private final Map<String, Object> metadata;

        public ExtractedTable(String markdown, int rowCount, int columnCount,
                              List<String> headers, Map<String, Object> metadata) {
            this.markdown = markdown;
            this.rowCount = rowCount;
            this.columnCount = columnCount;
            this.headers = headers != null ? new ArrayList<>(headers) : new ArrayList<>();
            this.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
        }

        public String getMarkdown() { return markdown; }
        public int getRowCount() { return rowCount; }
        public int getColumnCount() { return columnCount; }
        public List<String> getHeaders() { return headers; }
        public Map<String, Object> getMetadata() { return metadata; }
    }

    /**
     * Output from VLM extraction.
     */
    public static class VlmExtractionOutput {
        private final boolean success;
        private final String errorMessage;
        private final String text;
        private final String modelId;
        private final String outputFormat;
        private final List<ExtractedTable> tables;
        private final float[] imageEmbedding;
        private final Map<String, String> formFields;
        private final long extractionTimeMs;

        private VlmExtractionOutput(Builder builder) {
            this.success = builder.success;
            this.errorMessage = builder.errorMessage;
            this.text = builder.text;
            this.modelId = builder.modelId;
            this.outputFormat = builder.outputFormat;
            this.tables = builder.tables != null ? new ArrayList<>(builder.tables) : new ArrayList<>();
            this.imageEmbedding = builder.imageEmbedding;
            this.formFields = builder.formFields != null ? new HashMap<>(builder.formFields) : new HashMap<>();
            this.extractionTimeMs = builder.extractionTimeMs;
        }

        public static VlmExtractionOutput failed(String errorMessage) {
            return builder().success(false).errorMessage(errorMessage).build();
        }

        public static Builder builder() {
            return new Builder();
        }

        public boolean isSuccess() { return success; }
        public String getErrorMessage() { return errorMessage; }
        public String getText() { return text; }
        public String getModelId() { return modelId; }
        public String getOutputFormat() { return outputFormat; }
        public List<ExtractedTable> getTables() { return tables; }
        public float[] getImageEmbedding() { return imageEmbedding; }
        public Map<String, String> getFormFields() { return formFields; }
        public long getExtractionTimeMs() { return extractionTimeMs; }

        public boolean hasText() { return text != null && !text.isEmpty(); }
        public boolean hasTables() { return !tables.isEmpty(); }
        public boolean hasImageEmbedding() { return imageEmbedding != null; }
        public boolean hasFormFields() { return !formFields.isEmpty(); }

        public static class Builder {
            private boolean success;
            private String errorMessage;
            private String text;
            private String modelId;
            private String outputFormat;
            private List<ExtractedTable> tables;
            private float[] imageEmbedding;
            private Map<String, String> formFields;
            private long extractionTimeMs;

            public Builder success(boolean success) { this.success = success; return this; }
            public Builder errorMessage(String errorMessage) { this.errorMessage = errorMessage; return this; }
            public Builder text(String text) { this.text = text; return this; }
            public Builder modelId(String modelId) { this.modelId = modelId; return this; }
            public Builder outputFormat(String outputFormat) { this.outputFormat = outputFormat; return this; }
            public Builder tables(List<ExtractedTable> tables) { this.tables = tables; return this; }
            public Builder imageEmbedding(float[] imageEmbedding) { this.imageEmbedding = imageEmbedding; return this; }
            public Builder formFields(Map<String, String> formFields) { this.formFields = formFields; return this; }
            public Builder extractionTimeMs(long extractionTimeMs) { this.extractionTimeMs = extractionTimeMs; return this; }

            public VlmExtractionOutput build() {
                return new VlmExtractionOutput(this);
            }
        }
    }
}
