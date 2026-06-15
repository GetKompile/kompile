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

package ai.kompile.ocr.integration;

import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.loaders.PdfProcessingConfig;
import ai.kompile.ocr.OcrPipeline;
import ai.kompile.ocr.document.ParsedDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Document loader that uses OCR for processing scanned documents.
 * This integrates OCR as a pre-chunking step in the document loading pipeline.
 *
 * <p>Configuration is now per-request via PdfProcessingConfig rather than
 * Spring properties. This allows UI-driven configuration.</p>
 */
@Component
public class OcrDocumentProcessor implements DocumentLoader {

    private static final Logger logger = LoggerFactory.getLogger(OcrDocumentProcessor.class);

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf", "png", "jpg", "jpeg", "tiff", "tif", "bmp");

    private final OcrPipelineService pipelineService;

    // Runtime configurable (no Spring @Value - set via UI/API)
    private boolean forceOcrForPdf = false;

    @Autowired
    public OcrDocumentProcessor(OcrPipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    /**
     * Sets whether to force OCR processing for all PDFs.
     * Can be called from UI/API to change behavior at runtime.
     */
    public void setForceOcrForPdf(boolean forceOcrForPdf) {
        this.forceOcrForPdf = forceOcrForPdf;
    }

    /**
     * Gets whether OCR is forced for PDFs.
     */
    public boolean isForceOcrForPdf() {
        return forceOcrForPdf;
    }

    @Override
    public String getName() {
        return "OCR Document Processor";
    }

    @Override
    public boolean supports(DocumentSourceDescriptor sourceDescriptor) {
        if (sourceDescriptor.getType() != DocumentSourceDescriptor.SourceType.FILE) {
            return false;
        }

        String path = sourceDescriptor.getPathOrUrl();
        if (path == null) {
            return false;
        }

        String lowerPath = path.toLowerCase();
        return SUPPORTED_EXTENSIONS.stream().anyMatch(lowerPath::endsWith);
    }

    @Override
    public List<Document> load(DocumentSourceDescriptor sourceDescriptor) throws Exception {
        return load(sourceDescriptor, (PdfProcessingConfig) null);
    }

    @Override
    public List<Document> load(DocumentSourceDescriptor sourceDescriptor,
                               Consumer<LoaderProgress> progressCallback) throws Exception {
        return load(sourceDescriptor, null, progressCallback);
    }

    /**
     * Loads documents with per-request PDF processing configuration from the UI.
     *
     * @param sourceDescriptor Source descriptor
     * @param pdfConfig PDF processing configuration (null for defaults)
     * @return List of loaded documents
     */
    public List<Document> load(DocumentSourceDescriptor sourceDescriptor, PdfProcessingConfig pdfConfig) throws Exception {
        return load(sourceDescriptor, pdfConfig, null);
    }

    /**
     * Loads documents with per-request PDF processing configuration and progress callback.
     *
     * @param sourceDescriptor Source descriptor
     * @param pdfConfig PDF processing configuration (null for defaults)
     * @param progressCallback Callback for reporting loading progress (may be null)
     * @return List of loaded documents
     */
    public List<Document> load(DocumentSourceDescriptor sourceDescriptor, PdfProcessingConfig pdfConfig,
                               Consumer<LoaderProgress> progressCallback) throws Exception {
        if (!supports(sourceDescriptor)) {
            throw new IllegalArgumentException("OCR Document Processor does not support: " +
                    sourceDescriptor.getPathOrUrl());
        }

        if (!pipelineService.isOcrEnabled()) {
            logger.warn("OCR is not enabled, returning empty result");
            return List.of();
        }

        File file = new File(sourceDescriptor.getPathOrUrl());
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("File does not exist: " + file.getAbsolutePath());
        }

        String fileName = file.getName().toLowerCase();

        if (fileName.endsWith(".pdf")) {
            return processPdf(file, sourceDescriptor, pdfConfig, progressCallback);
        } else {
            return processImage(file, sourceDescriptor);
        }
    }

    /**
     * Processes a PDF file with OCR using per-request configuration and progress callback.
     *
     * @param pdfFile The PDF file to process
     * @param sourceDescriptor Source metadata
     * @param pdfConfig PDF processing configuration from UI (null for defaults)
     * @param progressCallback Callback for reporting loading progress (may be null)
     */
    private List<Document> processPdf(File pdfFile, DocumentSourceDescriptor sourceDescriptor,
                                       PdfProcessingConfig pdfConfig,
                                       Consumer<LoaderProgress> progressCallback) {
        logger.info("Processing PDF with OCR: {} (config: {})",
                pdfFile.getName(),
                pdfConfig != null ? pdfConfig.getProcessingMode() : "default");

        // Convert PipelineProgress → LoaderProgress for the callback chain
        Consumer<OcrPipeline.PipelineProgress> ocrProgressHandler = progress -> {
            logger.debug("OCR progress: {}", progress.statusMessage());
            if (progressCallback != null) {
                int percent = (int) progress.overallProgress();
                Map<String, Object> metrics = new HashMap<>();
                metrics.put("currentPage", progress.currentPage());
                metrics.put("totalPages", progress.totalPages());
                metrics.put("currentStage", progress.currentStage());
                if (progress.generatedTokens() != null) {
                    metrics.put("generatedTokens", progress.generatedTokens());
                    metrics.put("promptTokens", progress.promptTokens());
                    metrics.put("tokensPerSecond", progress.tokensPerSecond());
                    metrics.put("generateTimeMs", progress.generateTimeMs());
                }
                if (progress.vlmModelId() != null) {
                    metrics.put("vlmModelId", progress.vlmModelId());
                }
                progressCallback.accept(new LoaderProgress(
                        "OCR_PROCESSING", percent, progress.currentStage(),
                        progress.statusMessage(), metrics));
            }
        };

        List<ParsedDocument> parsed;
        if (pdfConfig != null) {
            parsed = pipelineService.processPdfWithConfig(pdfFile, pdfConfig, ocrProgressHandler);
        } else {
            parsed = pipelineService.processPdf(pdfFile, ocrProgressHandler);
        }

        List<Document> documents = new ArrayList<>();
        for (ParsedDocument doc : parsed) {
            if (doc.isSuccess()) {
                Document springDoc = doc.toSpringDocument();

                // Add source descriptor metadata
                if (sourceDescriptor.getSourceId() != null) {
                    springDoc.getMetadata().put("source_id", sourceDescriptor.getSourceId());
                }
                if (sourceDescriptor.getCollectionName() != null) {
                    springDoc.getMetadata().put("collection_name", sourceDescriptor.getCollectionName());
                }
                springDoc.getMetadata().put("loader", getName());
                springDoc.getMetadata().put("ocr_processed", true);

                // Add processing mode info
                if (pdfConfig != null) {
                    springDoc.getMetadata().put("pdf_processing_mode", pdfConfig.getProcessingMode().name());
                    if (pdfConfig.isUseVlm()) {
                        springDoc.getMetadata().put("vlm_model", pdfConfig.getVlmModelId());
                    }
                }

                documents.add(springDoc);
            } else {
                logger.warn("OCR failed for page {}: {}", doc.getPageNumber(), doc.getErrorMessage());
            }
        }

        logger.info("Processed {} pages from PDF: {}", documents.size(), pdfFile.getName());
        return documents;
    }

    /**
     * Processes an image file with OCR.
     */
    private List<Document> processImage(File imageFile, DocumentSourceDescriptor sourceDescriptor) throws Exception {
        logger.info("Processing image with OCR: {}", imageFile.getName());

        // For images, we need to convert to INDArray first
        // This is a simplified implementation - real implementation would use ImageIO

        // For now, create a placeholder that indicates this needs to be implemented
        // with proper image loading
        List<Document> documents = new ArrayList<>();

        Document doc = new Document("[Image OCR processing for: " + imageFile.getName() + "]");
        doc.getMetadata().put("source", imageFile.getAbsolutePath());
        doc.getMetadata().put("fileName", imageFile.getName());
        doc.getMetadata().put("loader", getName());
        doc.getMetadata().put("ocr_processed", true);
        doc.getMetadata().put("pending_implementation", true);

        if (sourceDescriptor.getSourceId() != null) {
            doc.getMetadata().put("source_id", sourceDescriptor.getSourceId());
        }
        if (sourceDescriptor.getCollectionName() != null) {
            doc.getMetadata().put("collection_name", sourceDescriptor.getCollectionName());
        }

        documents.add(doc);

        return documents;
    }

    /**
     * Checks if OCR is available.
     */
    public boolean isOcrAvailable() {
        return pipelineService.isOcrEnabled();
    }
}
