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

import ai.kompile.core.loaders.PdfProcessingConfig;
import ai.kompile.ocr.OcrPipeline;
import ai.kompile.ocr.OcrPipelineConfig;
import ai.kompile.ocr.document.ParsedDocument;
import ai.kompile.ocr.postprocess.OcrPostProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Main service for OCR processing in the Kompile application.
 * Orchestrates the full OCR pipeline including pre-processing, OCR, and post-processing.
 *
 * <p>Supports two processing modes:</p>
 * <ul>
 *   <li><b>Traditional OCR</b>: Detection → Recognition (DBNet + CRNN)</li>
 *   <li><b>VLM (Vision-Language Model)</b>: End-to-end encoder-decoder (Docling, Donut)</li>
 * </ul>
 */
@Service
public class OcrPipelineService {

    private static final Logger logger = LoggerFactory.getLogger(OcrPipelineService.class);

    private final OcrPipeline defaultOcrPipeline;
    private final OcrPipeline vlmPipeline;
    private final OcrPostProcessor postProcessor;

    // Runtime state - model IDs come from the UI/registry, not from defaults
    private boolean ocrEnabled = true;
    private boolean useVlmByDefault = false;
    private String defaultVlmModel;
    private String defaultDetectionModel;
    private String defaultRecognitionModel;
    private boolean enablePostProcessing = false;
    private int pdfRenderDpi = 300;

    @Autowired
    public OcrPipelineService(
            @Qualifier("defaultOcrPipeline") OcrPipeline defaultOcrPipeline,
            @Autowired(required = false) @Qualifier("vlmDocumentPipeline") OcrPipeline vlmPipeline,
            @Autowired(required = false) OcrPostProcessor postProcessor) {
        this.defaultOcrPipeline = defaultOcrPipeline;
        this.vlmPipeline = vlmPipeline;
        this.postProcessor = postProcessor;
    }

    public boolean isOcrEnabled() {
        return ocrEnabled && defaultOcrPipeline != null;
    }

    public boolean isVlmAvailable() {
        return vlmPipeline != null;
    }

    public boolean isReady() {
        return isOcrEnabled() && getActivePipeline().isReady();
    }

    private OcrPipeline getActivePipeline() {
        if (useVlmByDefault && vlmPipeline != null) {
            return vlmPipeline;
        }
        return defaultOcrPipeline;
    }

    private OcrPipeline getPipelineForConfig(OcrPipelineConfig config) {
        if (config.isUseVlm() && vlmPipeline != null) {
            return vlmPipeline;
        }
        return defaultOcrPipeline;
    }

    public void initialize() throws Exception {
        if (!isOcrEnabled()) {
            logger.info("OCR is disabled");
            return;
        }

        logger.info("Initializing OCR pipeline...");
        defaultOcrPipeline.loadModels();

        if (vlmPipeline != null && useVlmByDefault) {
            logger.info("Initializing VLM pipeline with model: {}", defaultVlmModel);
            vlmPipeline.loadModels();
        }

        logger.info("OCR pipeline initialized");
    }

    /**
     * Initialize only the VLM pipeline, skipping traditional OCR models (dbnet, crnn).
     * Use this when you only need VLM-based document processing.
     */
    public void initializeVlmOnly() throws Exception {
        if (vlmPipeline == null) {
            throw new IllegalStateException("VLM pipeline is not available. " +
                    "Ensure VLM pipeline beans are configured.");
        }

        logger.info("Initializing VLM-only pipeline with model: {}", defaultVlmModel);
        vlmPipeline.loadModels();
        useVlmByDefault = true;
        logger.info("VLM pipeline initialized (traditional OCR skipped)");
    }

    public List<ParsedDocument> processPdf(File pdfFile) {
        return processPdf(pdfFile, null);
    }

    public List<ParsedDocument> processPdf(File pdfFile,
                                           Consumer<OcrPipeline.PipelineProgress> progressCallback) {
        if (!isOcrEnabled()) {
            logger.warn("OCR is not enabled, returning empty result");
            return List.of(ParsedDocument.failed(pdfFile.getAbsolutePath(), 0, "OCR not enabled"));
        }

        OcrPipelineConfig config = createDefaultConfig(pdfFile.getAbsolutePath());
        OcrPipeline pipeline = getPipelineForConfig(config);

        logger.info("Processing PDF with {}: {}", pipeline.getName(), pdfFile.getName());
        List<ParsedDocument> results = pipeline.processPdf(pdfFile, config, progressCallback);

        if (!config.isUseVlm() && enablePostProcessing && postProcessor != null && postProcessor.isAvailable()) {
            results = applyPostProcessing(results);
        }

        return results;
    }

    public List<ParsedDocument> processPdfWithConfig(File pdfFile,
                                                      PdfProcessingConfig uiConfig,
                                                      Consumer<OcrPipeline.PipelineProgress> progressCallback) {
        if (uiConfig == null) {
            return processPdf(pdfFile, progressCallback);
        }

        OcrPipelineConfig.OcrPipelineConfigBuilder builder = OcrPipelineConfig.builder()
                .sourceId(pdfFile.getAbsolutePath())
                .pdfRenderDpi(uiConfig.getPdfRenderDpi())
                .includeAuditTrail(true);

        boolean shouldUseVlm = uiConfig.isUseVlm() ||
                uiConfig.getProcessingMode() == PdfProcessingConfig.ProcessingMode.VLM;

        if (shouldUseVlm && vlmPipeline != null) {
            builder.useVlm(true)
                    .vlmModelId(uiConfig.getVlmModelId())
                    .vlmOutputFormat(convertVlmOutputFormat(uiConfig.getVlmOutputFormat()))
                    .maxNewTokens(uiConfig.getMaxNewTokens())
                    .temperature(uiConfig.getTemperature())
                    .topP(uiConfig.getTopP())
                    .beamSize(uiConfig.getBeamSize())
                    .doSample(uiConfig.isDoSample())
                    .vlmDecoderPath(uiConfig.getVlmDecoderPath())
                    .vlmEncoderPath(uiConfig.getVlmEncoderPath())
                    .vlmEmbedTokensPath(uiConfig.getVlmEmbedTokensPath())
                    .vlmTokenizerPath(uiConfig.getVlmTokenizerPath())
                    .vlmPreprocessorConfigPath(uiConfig.getVlmPreprocessorConfigPath());
        } else {
            builder.useVlm(false)
                    .detectionModelId(uiConfig.getDetectionModelId())
                    .recognitionModelId(uiConfig.getRecognitionModelId())
                    .enableTableExtraction(uiConfig.isExtractTables())
                    .enableLayoutAnalysis(uiConfig.isEnableLayoutAnalysis())
                    .enableLlmPostProcessing(uiConfig.isEnablePostProcessing());
        }

        OcrPipelineConfig config = builder.build();
        OcrPipeline pipeline = getPipelineForConfig(config);

        logger.info("Processing PDF with {} (UI config, mode: {}): {}",
                pipeline.getName(), uiConfig.getProcessingMode(), pdfFile.getName());

        List<ParsedDocument> results = pipeline.processPdf(pdfFile, config, progressCallback);

        if (!config.isUseVlm() && uiConfig.isEnablePostProcessing() &&
                postProcessor != null && postProcessor.isAvailable()) {
            results = applyPostProcessing(results);
        }

        return results;
    }

    private OcrPipelineConfig.VlmOutputFormat convertVlmOutputFormat(PdfProcessingConfig.VlmOutputFormat format) {
        if (format == null) {
            return OcrPipelineConfig.VlmOutputFormat.DOCTAGS;
        }
        switch (format) {
            case MARKDOWN: return OcrPipelineConfig.VlmOutputFormat.MARKDOWN;
            case FLORENCE2: return OcrPipelineConfig.VlmOutputFormat.FLORENCE2;
            case DONUT: return OcrPipelineConfig.VlmOutputFormat.DONUT;
            case PLAIN_TEXT: return OcrPipelineConfig.VlmOutputFormat.PLAIN_TEXT;
            case JSON: return OcrPipelineConfig.VlmOutputFormat.JSON;
            case TEXT: return OcrPipelineConfig.VlmOutputFormat.TEXT;
            case DOCTAGS:
            default: return OcrPipelineConfig.VlmOutputFormat.DOCTAGS;
        }
    }

    public List<ParsedDocument> processPdfWithVlm(File pdfFile, String vlmModelId) {
        return processPdfWithVlm(pdfFile, vlmModelId, OcrPipelineConfig.VlmOutputFormat.DOCTAGS, null);
    }

    public List<ParsedDocument> processPdfWithVlm(File pdfFile, String vlmModelId,
                                                   OcrPipelineConfig.VlmOutputFormat outputFormat,
                                                   Consumer<OcrPipeline.PipelineProgress> progressCallback) {
        if (vlmPipeline == null) {
            logger.error("VLM pipeline not available");
            return List.of(ParsedDocument.failed(pdfFile.getAbsolutePath(), 0, "VLM pipeline not available"));
        }

        OcrPipelineConfig config = OcrPipelineConfig.builder()
                .useVlm(true)
                .vlmModelId(vlmModelId != null ? vlmModelId : defaultVlmModel)
                .vlmOutputFormat(outputFormat)
                .sourceId(pdfFile.getAbsolutePath())
                .pdfRenderDpi(pdfRenderDpi)
                .includeAuditTrail(true)
                .build();

        logger.info("Processing PDF with VLM ({}): {}", vlmModelId, pdfFile.getName());
        return vlmPipeline.processPdf(pdfFile, config, progressCallback);
    }

    /**
     * Process a PDF using VLM with a fully specified OcrPipelineConfig.
     * Allows passing all generation parameters (maxNewTokens, temperature, topP, pageBatchSize, etc.).
     */
    public List<ParsedDocument> processPdfWithVlm(File pdfFile, OcrPipelineConfig config,
                                                   Consumer<OcrPipeline.PipelineProgress> progressCallback) {
        if (vlmPipeline == null) {
            logger.error("VLM pipeline not available");
            return List.of(ParsedDocument.failed(pdfFile.getAbsolutePath(), 0, "VLM pipeline not available"));
        }

        logger.info("Processing PDF with VLM (config: model={}, pageBatchSize={}, maxNewTokens={}): {}",
                config.getVlmModelId(), config.getPageBatchSize(), config.getMaxNewTokens(), pdfFile.getName());
        return vlmPipeline.processPdf(pdfFile, config, progressCallback);
    }

    public ParsedDocument processPage(File pdfFile, int pageNumber) {
        if (!isOcrEnabled()) {
            return ParsedDocument.failed(pdfFile.getAbsolutePath(), pageNumber, "OCR not enabled");
        }

        OcrPipeline pipeline = getActivePipeline();
        ParsedDocument result = pipeline.processPage(pdfFile, pageNumber);

        if (!useVlmByDefault && enablePostProcessing && postProcessor != null && postProcessor.isAvailable()) {
            result = applyPostProcessing(result);
        }

        return result;
    }

    public List<Document> toSpringDocuments(List<ParsedDocument> parsedDocuments) {
        List<Document> documents = new ArrayList<>();
        for (ParsedDocument parsed : parsedDocuments) {
            if (parsed.isSuccess()) {
                documents.add(parsed.toSpringDocument());
            }
        }
        return documents;
    }

    public OcrPipeline getOcrPipeline() {
        return defaultOcrPipeline;
    }

    public OcrPipeline getVlmPipeline() {
        return vlmPipeline;
    }

    public OcrPostProcessor getPostProcessor() {
        return postProcessor;
    }

    private OcrPipelineConfig createDefaultConfig(String sourceId) {
        OcrPipelineConfig.OcrPipelineConfigBuilder builder = OcrPipelineConfig.builder()
                .sourceId(sourceId)
                .pdfRenderDpi(pdfRenderDpi)
                .includeAuditTrail(true);

        if (useVlmByDefault && vlmPipeline != null) {
            builder.useVlm(true)
                    .vlmModelId(defaultVlmModel)
                    .vlmOutputFormat(OcrPipelineConfig.VlmOutputFormat.DOCTAGS);
        } else {
            builder.useVlm(false)
                    .detectionModelId(defaultDetectionModel)
                    .recognitionModelId(defaultRecognitionModel)
                    .enableTableExtraction(true)
                    .enableLayoutAnalysis(false)
                    .enableLlmPostProcessing(enablePostProcessing);
        }

        return builder.build();
    }

    private List<ParsedDocument> applyPostProcessing(List<ParsedDocument> results) {
        List<ParsedDocument> processed = new ArrayList<>();
        for (ParsedDocument doc : results) {
            processed.add(applyPostProcessing(doc));
        }
        return processed;
    }

    private ParsedDocument applyPostProcessing(ParsedDocument doc) {
        if (!doc.isSuccess() || doc.getOcrResult() == null) {
            return doc;
        }

        try {
            var processedOcr = postProcessor.process(
                    doc.getOcrResult(),
                    OcrPostProcessor.PostProcessConfig.defaultConfig()
            );

            return ParsedDocument.builder()
                    .id(doc.getId())
                    .sourceId(doc.getSourceId())
                    .pageNumber(doc.getPageNumber())
                    .totalPages(doc.getTotalPages())
                    .documentType(doc.getDocumentType())
                    .complexity(doc.getComplexity())
                    .text(processedOcr.getFullText())
                    .ocrResult(processedOcr)
                    .tables(doc.getTables())
                    .fields(doc.getFields())
                    .auditTrail(doc.getAuditTrail())
                    .success(true)
                    .processingTimeMs(doc.getProcessingTimeMs())
                    .build();
        } catch (Exception e) {
            logger.error("Post-processing failed: {}", e.getMessage());
            return doc;
        }
    }
}
