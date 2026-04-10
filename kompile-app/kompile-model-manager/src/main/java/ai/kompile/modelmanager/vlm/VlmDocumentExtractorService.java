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

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Service for VLM-based document extraction that integrates with the document loading pipeline.
 *
 * This service manages VLM models for different extraction types and provides a unified
 * interface for document processing with automatic model selection based on content type.
 *
 * <h2>Service Architecture</h2>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────────────────┐
 * │                    VlmDocumentExtractorService                              │
 * ├─────────────────────────────────────────────────────────────────────────────┤
 * │                                                                             │
 * │  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐            │
 * │  │ Document        │  │ Table           │  │ Image           │            │
 * │  │ Understanding   │  │ Extraction      │  │ Embedding       │            │
 * │  │                 │  │                 │  │                 │            │
 * │  │ SmolDocling     │  │ TableFormer     │  │ SigLIP/CLIP     │            │
 * │  │ Donut           │  │                 │  │                 │            │
 * │  └────────┬────────┘  └────────┬────────┘  └────────┬────────┘            │
 * │           │                    │                    │                      │
 * │           └────────────────────┼────────────────────┘                      │
 * │                                │                                           │
 * │                                ▼                                           │
 * │  ┌─────────────────────────────────────────────────────────────────────┐  │
 * │  │                        Model Resolver                                │  │
 * │  │                                                                      │  │
 * │  │   VlmModelSet → Download → Resolve → Local Paths                    │  │
 * │  └─────────────────────────────────────────────────────────────────────┘  │
 * │                                │                                           │
 * │                                ▼                                           │
 * │  ┌─────────────────────────────────────────────────────────────────────┐  │
 * │  │                        Cache Directory                               │  │
 * │  │                  ~/.kompile/models/vlm/                              │  │
 * │  └─────────────────────────────────────────────────────────────────────┘  │
 * │                                                                             │
 * └─────────────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>Usage in Document Loading Pipeline</h2>
 * <pre>{@code
 * // In PdfExtendedLoaderImpl or similar:
 *
 * @Autowired
 * private VlmDocumentExtractorService vlmService;
 *
 * public List<Document> load(DocumentSourceDescriptor source) {
 *     PdfProcessingConfig config = source.getProcessingConfig();
 *
 *     if (config.getProcessingMode() == ProcessingMode.VLM) {
 *         // Ensure VLM models are ready
 *         vlmService.ensureModelsForConfig(config);
 *
 *         // Render and extract each page
 *         for (int page = 0; page < numPages; page++) {
 *             BufferedImage pageImage = renderPage(page, config.getPdfRenderDpi());
 *
 *             VlmDocumentExtractorService.PageExtractionResult result =
 *                 vlmService.extractPage(pageImage, config);
 *
 *             documents.add(createDocument(result));
 *         }
 *     }
 * }
 * }</pre>
 *
 * @author Kompile Inc.
 */
public class VlmDocumentExtractorService {

    private static final Logger log = LoggerFactory.getLogger(VlmDocumentExtractorService.class);

    private final VlmModelResolver modelResolver;
    private final VlmModelSetDownloader modelDownloader;

    // Cache of extractors by configuration hash
    private final Map<Integer, VlmContentExtractor> extractorCache = new ConcurrentHashMap<>();

    // Model readiness status
    private final Set<String> readyModels = ConcurrentHashMap.newKeySet();

    public VlmDocumentExtractorService() {
        this.modelDownloader = new VlmModelSetDownloader();
        this.modelResolver = new VlmModelResolver(modelDownloader);
    }

    // =====================================================================
    // MODEL MANAGEMENT
    // =====================================================================

    /**
     * Ensure all models needed for a VLM extraction configuration are downloaded.
     *
     * @param config VLM extraction configuration
     * @return list of model IDs that failed to download (empty if all succeeded)
     */
    public List<String> ensureModelsForConfig(VlmExtractionConfig config) {
        List<String> failures = new ArrayList<>();

        for (VlmModelSet modelSet : config.getRequiredModelSets()) {
            if (!readyModels.contains(modelSet.getSetId())) {
                try {
                    if (!modelDownloader.isModelSetCached(modelSet)) {
                        log.info("Downloading model set: {} ({})",
                            modelSet.getDisplayName(), modelSet.getSetId());

                        modelDownloader.downloadModelSet(modelSet, (component, progress) -> {
                            log.debug("  {} - {:.1f}%", component.getFileName(), progress * 100);
                        });
                    }

                    // Verify model is complete
                    VlmModelResolver.ResolvedModel resolved = modelResolver.resolve(modelSet.getSetId(), false);
                    if (resolved.isComplete()) {
                        readyModels.add(modelSet.getSetId());
                        log.info("Model ready: {}", modelSet.getSetId());
                    } else {
                        log.warn("Model incomplete: {} - missing: {}",
                            modelSet.getSetId(), resolved.getMissingComponents());
                        failures.add(modelSet.getSetId());
                    }
                } catch (Exception e) {
                    log.error("Failed to prepare model: {}", modelSet.getSetId(), e);
                    failures.add(modelSet.getSetId());
                }
            }
        }

        return failures;
    }

    /**
     * Ensure models for a PdfProcessingConfig-style map.
     *
     * @param configMap configuration map
     * @return list of failures
     */
    public List<String> ensureModelsForConfig(Map<String, Object> configMap) {
        VlmExtractionConfig config = VlmProcessingConfigBridge.fromPdfProcessingConfigMap(configMap);
        return ensureModelsForConfig(config);
    }

    /**
     * Check if a specific model set is ready.
     */
    public boolean isModelReady(String modelSetId) {
        return readyModels.contains(modelSetId);
    }

    /**
     * Get all ready model IDs.
     */
    public Set<String> getReadyModels() {
        return Collections.unmodifiableSet(readyModels);
    }

    /**
     * Get model paths for a specific extraction type.
     */
    public Map<String, Path> getModelPaths(VlmExtractionType extractionType) {
        VlmModelSet modelSet = extractionType.getDefaultModelSet();
        if (modelSet == null) {
            return Collections.emptyMap();
        }

        try {
            VlmModelResolver.ResolvedModel resolved = modelResolver.resolve(modelSet.getSetId(), false);
            return resolved.getComponentPaths();
        } catch (Exception e) {
            log.warn("Failed to resolve model paths for {}", extractionType, e);
            return Collections.emptyMap();
        }
    }

    // =====================================================================
    // PAGE EXTRACTION
    // =====================================================================

    /**
     * Extract content from a single page image.
     *
     * @param pageImage the page image
     * @param config extraction configuration
     * @return extraction result
     */
    public PageExtractionResult extractPage(BufferedImage pageImage, VlmExtractionConfig config) {
        return extractPage(pageImage, config, null);
    }

    /**
     * Extract content from a single page image with progress callback.
     *
     * @param pageImage the page image
     * @param config extraction configuration
     * @param progressCallback progress updates
     * @return extraction result
     */
    public PageExtractionResult extractPage(BufferedImage pageImage,
                                             VlmExtractionConfig config,
                                             BiConsumer<String, Double> progressCallback) {
        VlmContentExtractor extractor = getOrCreateExtractor(config);

        VlmContentExtractor.VlmExtractionOutput output = extractor.extract(pageImage, progress -> {
            if (progressCallback != null) {
                progressCallback.accept(progress.getPhase(), progress.getPercentComplete() / 100.0);
            }
        });

        return new PageExtractionResult(output);
    }

    /**
     * Extract content from a PDF file.
     *
     * @param pdfFile the PDF file
     * @param config extraction configuration
     * @return list of page extraction results
     */
    /**
     * Extract content from a PDF file by rendering each page and processing with VLM.
     * Requires PDFBox to be on the classpath (available when kompile-loader-pdf-extended is included).
     *
     * @param pdfFile the PDF file
     * @param config extraction configuration
     * @return list of page extraction results
     */
    public List<PageExtractionResult> extractDocument(File pdfFile, VlmExtractionConfig config) {
        return extractDocument(pdfFile, config, null);
    }

    /**
     * Extract content from a PDF file with progress tracking.
     *
     * @param pdfFile the PDF file
     * @param config extraction configuration
     * @param progressCallback progress updates (phase, percent)
     * @return list of page extraction results
     */
    public List<PageExtractionResult> extractDocument(File pdfFile, VlmExtractionConfig config,
                                                       BiConsumer<String, Double> progressCallback) {
        Objects.requireNonNull(pdfFile, "PDF file cannot be null");
        if (!pdfFile.exists() || !pdfFile.isFile()) {
            throw new IllegalArgumentException("PDF file not found: " + pdfFile.getAbsolutePath());
        }

        List<PageExtractionResult> results = new ArrayList<>();

        try {
            // Use reflection to avoid hard dependency on PDFBox
            // PDFBox classes: org.apache.pdfbox.pdmodel.PDDocument, org.apache.pdfbox.rendering.PDFRenderer
            Class<?> pdDocumentClass = Class.forName("org.apache.pdfbox.pdmodel.PDDocument");
            Class<?> pdfRendererClass = Class.forName("org.apache.pdfbox.rendering.PDFRenderer");

            // PDDocument.load(File)
            Object pdDocument = pdDocumentClass.getMethod("load", File.class).invoke(null, pdfFile);

            try {
                // Get page count
                int pageCount = (int) pdDocumentClass.getMethod("getNumberOfPages").invoke(pdDocument);
                log.info("Processing PDF: {} ({} pages)", pdfFile.getName(), pageCount);

                // Create PDFRenderer
                Object renderer = pdfRendererClass.getConstructor(pdDocumentClass).newInstance(pdDocument);

                // Get DPI from config or use default
                float dpi = config.getRenderDpi() > 0 ? config.getRenderDpi() : 150.0f;

                for (int page = 0; page < pageCount; page++) {
                    final int currentPage = page;
                    if (Thread.currentThread().isInterrupted()) {
                        log.info("PDF extraction interrupted at page {}/{}", currentPage + 1, pageCount);
                        break;
                    }

                    if (progressCallback != null) {
                        progressCallback.accept("Rendering page " + (currentPage + 1) + "/" + pageCount,
                                (double) currentPage / pageCount);
                    }

                    try {
                        // PDFRenderer.renderImageWithDPI(int, float)
                        BufferedImage pageImage = (BufferedImage) pdfRendererClass
                                .getMethod("renderImageWithDPI", int.class, float.class)
                                .invoke(renderer, currentPage, dpi);

                        PageExtractionResult result = extractPage(pageImage, config,
                                (phase, pct) -> {
                                    if (progressCallback != null) {
                                        double overallProgress = ((double) currentPage + pct) / pageCount;
                                        progressCallback.accept(phase + " (page " + (currentPage + 1) + ")", overallProgress);
                                    }
                                });

                        results.add(result);

                    } catch (Exception e) {
                        log.warn("Failed to extract page {}: {}", currentPage + 1, e.getMessage());
                        // Add an error result for this page
                        results.add(createErrorResult("Page " + (currentPage + 1) + " extraction failed: " + e.getMessage()));
                    }
                }
            } finally {
                // PDDocument.close()
                pdDocumentClass.getMethod("close").invoke(pdDocument);
            }

        } catch (ClassNotFoundException e) {
            throw new UnsupportedOperationException(
                    "PDFBox is required for direct PDF extraction. " +
                    "Add kompile-loader-pdf-extended to your dependencies, " +
                    "or use extractPage(BufferedImage, config) with pre-rendered pages.");
        } catch (Exception e) {
            throw new RuntimeException("Error processing PDF: " + pdfFile.getAbsolutePath(), e);
        }

        if (progressCallback != null) {
            progressCallback.accept("Complete", 1.0);
        }

        return results;
    }

    private PageExtractionResult createErrorResult(String errorMessage) {
        return new PageExtractionResult(VlmContentExtractor.VlmExtractionOutput.failed(errorMessage));
    }

    private VlmContentExtractor getOrCreateExtractor(VlmExtractionConfig config) {
        int configHash = config.hashCode();
        return extractorCache.computeIfAbsent(configHash, k -> {
            VlmContentExtractor extractor = new VlmContentExtractor(config);
            extractor.ensureModelsReady();
            return extractor;
        });
    }

    // =====================================================================
    // BATCH OPERATIONS
    // =====================================================================

    /**
     * Download all commonly used models in parallel.
     *
     * @param parallelism number of concurrent downloads
     * @return download results
     */
    public List<VlmModelSetDownloader.DownloadSetResult> downloadAllCommonModels(int parallelism) {
        List<VlmModelSet> commonModels = Arrays.asList(
            VlmModelSet.SMOLDOCLING_256M,
            VlmModelSet.DOCLING_TABLEFORMER,
            VlmModelSet.SIGLIP_VISION
        );
        return modelDownloader.downloadModelSets(commonModels, parallelism);
    }

    /**
     * Clear the extractor cache.
     */
    public void clearCache() {
        extractorCache.clear();
    }

    /**
     * Get cache statistics.
     */
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("cachedExtractors", extractorCache.size());
        stats.put("readyModels", readyModels.size());
        stats.put("readyModelIds", new ArrayList<>(readyModels));
        stats.put("cacheDirectory", VlmModelSetDownloader.getCacheDirectory().toString());
        return stats;
    }

    // =====================================================================
    // RESULT CLASS
    // =====================================================================

    /**
     * Result of extracting a single page.
     */
    public static class PageExtractionResult {
        private final boolean success;
        private final String errorMessage;
        private final String text;
        private final String modelId;
        private final String outputFormat;
        private final List<TableResult> tables;
        private final float[] imageEmbedding;
        private final Map<String, String> formFields;
        private final long extractionTimeMs;

        public PageExtractionResult(VlmContentExtractor.VlmExtractionOutput output) {
            this.success = output.isSuccess();
            this.errorMessage = output.getErrorMessage();
            this.text = output.getText();
            this.modelId = output.getModelId();
            this.outputFormat = output.getOutputFormat();
            this.extractionTimeMs = output.getExtractionTimeMs();
            this.imageEmbedding = output.getImageEmbedding();
            this.formFields = output.getFormFields();

            // Convert tables
            this.tables = new ArrayList<>();
            for (VlmContentExtractor.ExtractedTable table : output.getTables()) {
                tables.add(new TableResult(
                    table.getMarkdown(),
                    table.getRowCount(),
                    table.getColumnCount(),
                    table.getHeaders()
                ));
            }
        }

        public boolean isSuccess() { return success; }
        public String getErrorMessage() { return errorMessage; }
        public String getText() { return text; }
        public String getModelId() { return modelId; }
        public String getOutputFormat() { return outputFormat; }
        public List<TableResult> getTables() { return tables; }
        public float[] getImageEmbedding() { return imageEmbedding; }
        public Map<String, String> getFormFields() { return formFields; }
        public long getExtractionTimeMs() { return extractionTimeMs; }

        public boolean hasText() { return text != null && !text.isEmpty(); }
        public boolean hasTables() { return !tables.isEmpty(); }
        public boolean hasImageEmbedding() { return imageEmbedding != null; }
        public boolean hasFormFields() { return !formFields.isEmpty(); }

        /**
         * Get all content as a single string (text + tables as markdown).
         */
        public String getAllContentAsText() {
            StringBuilder sb = new StringBuilder();

            if (text != null && !text.isEmpty()) {
                sb.append(text);
            }

            if (!tables.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                for (int i = 0; i < tables.size(); i++) {
                    if (i > 0) sb.append("\n\n");
                    sb.append("### Table ").append(i + 1).append("\n\n");
                    sb.append(tables.get(i).getMarkdown());
                }
            }

            return sb.toString();
        }
    }

    /**
     * Extracted table result.
     */
    public static class TableResult {
        private final String markdown;
        private final int rowCount;
        private final int columnCount;
        private final List<String> headers;

        public TableResult(String markdown, int rowCount, int columnCount, List<String> headers) {
            this.markdown = markdown;
            this.rowCount = rowCount;
            this.columnCount = columnCount;
            this.headers = headers != null ? new ArrayList<>(headers) : new ArrayList<>();
        }

        public String getMarkdown() { return markdown; }
        public int getRowCount() { return rowCount; }
        public int getColumnCount() { return columnCount; }
        public List<String> getHeaders() { return headers; }
    }
}
