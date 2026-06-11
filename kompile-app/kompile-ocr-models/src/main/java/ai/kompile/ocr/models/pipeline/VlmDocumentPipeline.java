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

package ai.kompile.ocr.models.pipeline;

import ai.kompile.modelmanager.KompileModelManager;
import ai.kompile.modelmanager.ModelConstants;
import ai.kompile.modelmanager.registry.ImagePreprocessorConfig;
import ai.kompile.modelmanager.registry.ModelEntry;
import ai.kompile.modelmanager.registry.ModelMetadata;
import ai.kompile.modelmanager.registry.RegistryService;
import ai.kompile.ocr.*;
import ai.kompile.ocr.audit.AuditTrail;
import ai.kompile.ocr.audit.ConfidenceScore;
import ai.kompile.ocr.audit.ModelResult;
import ai.kompile.ocr.document.DocumentComplexity;
import ai.kompile.ocr.document.DocumentType;
import ai.kompile.ocr.document.ParsedDocument;
import ai.kompile.kvcache.bridge.VlmKvCacheIntegrationService;
import ai.kompile.ocr.structured.StructuredTable;
import ai.kompile.ocr.structured.TableCell;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.eclipse.deeplearning4j.llm.generation.GenerationResult;
import org.eclipse.deeplearning4j.llm.generation.KvCacheStrategy;
import org.eclipse.deeplearning4j.llm.generation.SamplingConfig;
import org.eclipse.deeplearning4j.model.benchmark.BenchmarkConfig;
import org.eclipse.deeplearning4j.model.benchmark.BenchmarkConfigApplier;
import org.eclipse.deeplearning4j.vlm.model.VisionEncoderIOConfig;
import org.eclipse.deeplearning4j.vlm.model.VisionLanguageModel;
import org.eclipse.deeplearning4j.vlm.model.patching.SameDiffGraphPatch;
import org.eclipse.deeplearning4j.vlm.model.patching.SmolDoclingPositionIdsPatch;
import org.eclipse.deeplearning4j.vlm.output.DocTagsParser;
import org.eclipse.deeplearning4j.vlm.output.DocumentStructure;
import org.eclipse.deeplearning4j.llm.config.PreprocessorConfig;
import org.eclipse.deeplearning4j.vlm.preprocessing.ImageTiler;
import org.eclipse.deeplearning4j.vlm.preprocessing.VLMImagePreprocessor;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.function.Consumer;

/**
 * VLM (Vision-Language Model) document pipeline for end-to-end document understanding.
 *
 * <p>Unlike traditional OCR which uses detection → recognition stages, VLM models like
 * Docling/SmolDocling use an encoder-decoder transformer architecture:</p>
 *
 * <pre>
 * Document Image → Vision Encoder → Decoder (autoregressive) → Structured Output
 * </pre>
 *
 * <p>Supports output formats: DocTags, Markdown, JSON, Plain Text</p>
 *
 * <h3>Supported Models:</h3>
 * <ul>
 *   <li>SmolDocling-256M - Compact document understanding model</li>
 *   <li>Donut - Document Understanding Transformer</li>
 *   <li>Pix2Struct - Pixel-to-Structure model</li>
 *   <li>Nougat - Academic document parsing</li>
 * </ul>
 */
@Component
@Qualifier("vlmDocumentPipeline")
public class VlmDocumentPipeline implements OcrPipeline {

    private static final Logger logger = LoggerFactory.getLogger(VlmDocumentPipeline.class);

    // Model manager for downloading and caching models
    private final KompileModelManager modelManager;

    // Registry service for looking up model preprocessor config
    private final RegistryService registryService;

    // Optional VLM KV cache integration for persistent managed caches
    private final VlmKvCacheIntegrationService vlmKvCacheIntegrationService;

    // SameDiff VLM model from deeplearning4j (loaded from .sdz files)
    private VisionLanguageModel vlm;
    private VLMImagePreprocessor imagePreprocessor;

    private String modelId;
    private boolean loaded = false;

    // Custom graph patches applied during ONNX model loading
    private List<SameDiffGraphPatch> visionEncoderPatches = new ArrayList<>();
    private List<SameDiffGraphPatch> decoderPatches = new ArrayList<>();
    private List<SameDiffGraphPatch> embedTokensPatches = new ArrayList<>();

    // DocTags parser from samediff-vlm
    private DocTagsParser docTagsParser = new DocTagsParser();

    // VLM orchestration settings (wired externally by kompile-app-main)
    private boolean releaseEncoderAfterEncoding = true;
    private int encoderDeviceId = -1;
    private int decoderDeviceId = -1;
    private boolean tritonCacheEnabled = true;
    private boolean tritonAutoImport = true;
    private boolean tritonAutoExport = true;
    private Runnable tritonCacheImporter;
    private Runnable tritonCacheExporter;

    @Autowired
    public VlmDocumentPipeline(@Autowired(required = false) KompileModelManager modelManager,
                                @Autowired(required = false) RegistryService registryService,
                                @Autowired(required = false) VlmKvCacheIntegrationService vlmKvCacheIntegrationService) {
        this.modelManager = modelManager != null ? modelManager : new KompileModelManager();
        this.registryService = registryService != null ? registryService : new RegistryService();
        this.vlmKvCacheIntegrationService = vlmKvCacheIntegrationService;
    }

    /**
     * Constructor for programmatic usage without Spring.
     */
    public VlmDocumentPipeline() {
        this.modelManager = new KompileModelManager();
        this.registryService = new RegistryService();
        this.vlmKvCacheIntegrationService = null;
    }

    /**
     * Adds a custom graph patch for the vision encoder, applied during ONNX import.
     * Patches run after ONNX import but before SDZ caching.
     */
    public void addVisionEncoderPatch(SameDiffGraphPatch patch) {
        this.visionEncoderPatches.add(patch);
    }

    /**
     * Adds a custom graph patch for the decoder, applied during ONNX import.
     */
    public void addDecoderPatch(SameDiffGraphPatch patch) {
        this.decoderPatches.add(patch);
    }

    /**
     * Adds a custom graph patch for embed tokens, applied during ONNX import.
     */
    public void addEmbedTokensPatch(SameDiffGraphPatch patch) {
        this.embedTokensPatches.add(patch);
    }

    /**
     * Sets all vision encoder patches, replacing any previously configured ones.
     */
    public void setVisionEncoderPatches(List<SameDiffGraphPatch> patches) {
        this.visionEncoderPatches = new ArrayList<>(patches);
    }

    /**
     * Sets all decoder patches, replacing any previously configured ones.
     */
    public void setDecoderPatches(List<SameDiffGraphPatch> patches) {
        this.decoderPatches = new ArrayList<>(patches);
    }

    /**
     * Sets all embed tokens patches, replacing any previously configured ones.
     */
    public void setEmbedTokensPatches(List<SameDiffGraphPatch> patches) {
        this.embedTokensPatches = new ArrayList<>(patches);
    }

    public String getModelId() {
        return modelId;
    }

    // --- VLM orchestration setters (wired by kompile-app-main VlmOrchestrationConfigService) ---

    public void setReleaseEncoderAfterEncoding(boolean release) {
        this.releaseEncoderAfterEncoding = release;
    }

    public void setEncoderDeviceId(int deviceId) {
        this.encoderDeviceId = deviceId;
    }

    public void setDecoderDeviceId(int deviceId) {
        this.decoderDeviceId = deviceId;
    }

    public void setTritonCacheEnabled(boolean enabled) {
        this.tritonCacheEnabled = enabled;
    }

    public void setTritonAutoImport(boolean autoImport) {
        this.tritonAutoImport = autoImport;
    }

    public void setTritonAutoExport(boolean autoExport) {
        this.tritonAutoExport = autoExport;
    }

    /**
     * Set a callback that imports the Triton cache for the current model.
     * Provided by the Triton cache service in kompile-app-main.
     */
    public void setTritonCacheImporter(Runnable importer) {
        this.tritonCacheImporter = importer;
    }

    /**
     * Set a callback that exports the Triton cache for the current model.
     * Provided by the Triton cache service in kompile-app-main.
     */
    public void setTritonCacheExporter(Runnable exporter) {
        this.tritonCacheExporter = exporter;
    }

    @Override
    public String getName() {
        return "VLM Document Pipeline";
    }

    @Override
    public String getDescription() {
        return "Vision-Language Model pipeline for end-to-end document understanding (Docling, Donut, etc.)";
    }

    @Override
    public ParsedDocument processImage(INDArray image) {
        return processImage(image, OcrPipelineConfig.vlm(modelId));
    }

    @Override
    public ParsedDocument processImage(INDArray image, OcrPipelineConfig config) {
        AuditTrail audit = AuditTrail.forPage(config.getSourceId(), 1);

        try {
            if (!loaded || vlm == null) {
                throw new IllegalStateException("VLM model not loaded. Call loadModels() first.");
            }

            long startTime = System.currentTimeMillis();

            // Build prompt based on output format
            String prompt = buildPromptForFormat(config.getVlmOutputFormat());

            // Build SamplingConfig from pipeline config
            SamplingConfig samplingConfig = buildSamplingConfig(config);

            // Generate using VLM with metrics
            logger.debug("Running VLM generation (max {} tokens, temp {})...",
                    samplingConfig.getMaxNewTokens(), samplingConfig.getTemperature());

            long generateStart = System.nanoTime();
            // Use generateWithMetrics which handles preprocessing internally
            GenerationResult genResult = vlm.generateWithMetrics(
                    image,
                    prompt,
                    samplingConfig.getMaxNewTokens(),
                    samplingConfig.getTemperature(),
                    samplingConfig.isDoSample()
            );
            String generatedText = genResult.getText();
            long generateTimeNanos = System.nanoTime() - generateStart;
            long generateTime = generateTimeNanos / 1_000_000;

            // Use metrics from GenerationResult directly
            int generatedTokens = genResult.getGeneratedTokenCount();
            int promptTokens = genResult.getPromptTokenCount();
            double tokensPerSecond = genResult.getTokensPerSecond();

            // Fallback: compute metrics from tokenizer if not provided by GenerationResult
            if (generatedTokens == 0 || tokensPerSecond <= 0) {
                try {
                    var tokenizer = vlm.getTokenizer();
                    if (tokenizer != null && tokenizer.isValid()) {
                        if (generatedTokens == 0) {
                            generatedTokens = tokenizer.encode(generatedText).getLength();
                        }
                        if (promptTokens == 0) {
                            promptTokens = tokenizer.encode(prompt).getLength();
                        }
                        if (generateTimeNanos > 0 && generatedTokens > 0) {
                            tokensPerSecond = (generatedTokens * 1_000_000_000.0) / generateTimeNanos;
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Could not compute token metrics: {}", e.getMessage());
                }
            }

            double effectiveTokensPerSecond = effectiveTokensPerSecond(genResult);
            String throughputLabel = throughputLabel(genResult);

            logger.info("VLM generation: {} tokens in {}ms (overall={} tok/s, {}={} tok/s), prompt {} tokens, model {}",
                    generatedTokens, generateTime,
                    String.format("%.1f", tokensPerSecond), throughputLabel,
                    String.format("%.1f", effectiveTokensPerSecond),
                    promptTokens, modelId);

            audit.addModelResult(ModelResult.success(
                    modelId,
                    OcrModelType.LAYOUT_MODEL,
                    generateTime,
                    generatedText.length(),
                    1.0
            ));

            // Step 3: Parse output using DocTagsParser
            logger.debug("Parsing output format: {}", config.getVlmOutputFormat());
            String plainText;
            List<StructuredTable> tables = new ArrayList<>();

            switch (config.getVlmOutputFormat()) {
                case DOCTAGS:
                    // Use DocTagsParser for DocTags format
                    DocumentStructure docStructure = docTagsParser.parse(generatedText);
                    plainText = docStructure.getFullText();
                    // Extract tables from parsed structure
                    tables = extractTablesFromDocStructure(docStructure);
                    break;

                case MARKDOWN:
                    // Parse and convert to Markdown via the parser
                    DocumentStructure parsed = docTagsParser.parse(generatedText);
                    plainText = docTagsParser.toMarkdown(parsed);
                    tables = extractTablesFromDocStructure(parsed);
                    break;

                case FLORENCE2:
                    // Florence-2 task-specific format
                    DocumentStructure florenceDoc = docTagsParser.parse(generatedText);
                    plainText = docTagsParser.extractPlainText(generatedText);
                    tables = extractTablesFromDocStructure(florenceDoc);
                    break;

                case DONUT:
                    // Donut JSON-mapped format
                    DocumentStructure donutDoc = docTagsParser.parse(generatedText);
                    plainText = docTagsParser.extractPlainText(generatedText);
                    tables = extractTablesFromDocStructure(donutDoc);
                    break;

                case PLAIN_TEXT:
                    plainText = docTagsParser.extractPlainText(generatedText);
                    break;

                case JSON:
                    plainText = generatedText; // JSON needs further processing by caller
                    break;

                case TEXT:
                default:
                    plainText = docTagsParser.extractPlainText(generatedText);
                    break;
            }

            long totalTime = System.currentTimeMillis() - startTime;

            // Compute confidence (VLM doesn't provide per-region confidence, use 1.0)
            audit.addConfidenceScore("overall", ConfidenceScore.overall(1.0));
            audit.complete();

            // Determine complexity based on content
            DocumentComplexity complexity = DocumentComplexity.SIMPLE;
            if (!tables.isEmpty()) {
                complexity = DocumentComplexity.TABLES;
            } else if (generatedText.contains("<figure>") || generatedText.contains("<formula>")) {
                complexity = DocumentComplexity.MIXED;
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("vlmModel", modelId);
            metadata.put("outputFormat", config.getVlmOutputFormat().name());
            metadata.put("rawOutput", generatedText);
            metadata.put("generatedTokens", generatedTokens);
            metadata.put("promptTokens", promptTokens);
            metadata.put("tokensPerSecond", Math.round(tokensPerSecond * 100.0) / 100.0);
            metadata.put("effectiveTokensPerSecond", Math.round(effectiveTokensPerSecond * 100.0) / 100.0);
            metadata.put("throughputLabel", throughputLabel);
            metadata.put("steadyStateTokensPerSecond", Math.round(genResult.getSteadyStateTokensPerSecond() * 100.0) / 100.0);
            metadata.put("lateSteadyStateTokensPerSecond", Math.round(genResult.getLateSteadyStateTokensPerSecond() * 100.0) / 100.0);
            metadata.put("generateTimeMs", generateTime);

            return ParsedDocument.builder()
                    .id(UUID.randomUUID().toString())
                    .sourceId(config.getSourceId())
                    .pageNumber(1)
                    .documentType(DocumentType.SCANNED)
                    .complexity(complexity)
                    .text(plainText)
                    .tables(tables)
                    .auditTrail(config.isIncludeAuditTrail() ? audit : null)
                    .success(true)
                    .processingTimeMs(totalTime)
                    .metadata(metadata)
                    .build();

        } catch (Exception e) {
            logger.error("VLM processing failed: {}", e.getMessage(), e);
            audit.complete();
            return ParsedDocument.failed(config.getSourceId(), 1, e.getMessage());
        }
    }

    /**
     * Builds the prompt based on the desired output format.
     */
    private String buildPromptForFormat(OcrPipelineConfig.VlmOutputFormat format) {
        switch (format) {
            case DOCTAGS:
                return "Convert this document to DocTags format with structure tags and bounding boxes.";
            case MARKDOWN:
                return "Convert this document to Markdown format, preserving headers, lists, and tables.";
            case FLORENCE2:
                return "<OCR>";
            case DONUT:
                return "<s>";
            case JSON:
                return "Extract the document content as structured JSON with text, tables, and figures.";
            case PLAIN_TEXT:
            case TEXT:
            default:
                return "Extract all text from this document.";
        }
    }

    @Override
    public List<ParsedDocument> processPdf(File pdfFile) {
        return processPdf(pdfFile, OcrPipelineConfig.vlm(modelId), null);
    }

    @Override
    public List<ParsedDocument> processPdf(File pdfFile, OcrPipelineConfig config,
                                           Consumer<PipelineProgress> progressCallback) {
        // Apply KV cache configuration if not yet applied (e.g., when loadModels was called without config)
        if (config != null && vlm != null) {
            applyKvCacheConfig(config);
        }

        List<ParsedDocument> results = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            PDFRenderer renderer = new PDFRenderer(document);
            int totalPages = document.getNumberOfPages();

            List<Integer> pagesToProcess = config.parsePageRange(totalPages);
            if (pagesToProcess == null) {
                pagesToProcess = new ArrayList<>();
                for (int i = 1; i <= totalPages; i++) {
                    pagesToProcess.add(i);
                }
            }

            // Limit pages if maxPages is set
            if (config.getMaxPages() > 0 && pagesToProcess.size() > config.getMaxPages()) {
                pagesToProcess = pagesToProcess.subList(0, config.getMaxPages());
                logger.info("Limiting processing to {} pages (maxPages={})", pagesToProcess.size(), config.getMaxPages());
            }

            // Use effective page count (after range/maxPages filtering) for progress reporting
            int effectiveTotalPages = pagesToProcess.size();

            if (progressCallback != null) {
                progressCallback.accept(PipelineProgress.starting(effectiveTotalPages));
            }

            int pageBatchSize = Math.max(1, config.getPageBatchSize());
            String prompt = buildPromptForFormat(config.getVlmOutputFormat());
            SamplingConfig samplingConfig = buildSamplingConfig(config);

            // Determine tile size from preprocessor config (model's expected input resolution)
            int tileSize = 384; // reasonable default
            if (imagePreprocessor != null) {
                try {
                    int targetH = imagePreprocessor.getTargetHeight();
                    if (targetH > 0) tileSize = targetH;
                } catch (Exception e) {
                    logger.debug("Could not get target height from preprocessor, using default tile size: {}", tileSize);
                }
            }

            logger.info("Processing {} pages with streaming tiled generation (tileSize={})",
                    pagesToProcess.size(), tileSize);

            // Import Triton cache before decode loop if enabled
            if (tritonCacheEnabled && tritonAutoImport && tritonCacheImporter != null) {
                try {
                    logger.info("Auto-importing Triton cache before decode loop");
                    tritonCacheImporter.run();
                } catch (Exception e) {
                    logger.warn("Triton cache auto-import failed: {}", e.getMessage());
                }
            }

            boolean tritonExportDone = false;

            // Stream pages one at a time: render, encode, generate, parse, free, next page
            int failedPages = 0;
            for (int i = 0; i < pagesToProcess.size(); i++) {
                int pageNum = pagesToProcess.get(i);

                int progressPage = i + 1; // 1-based iteration index for progress display
                try {
                    if (progressCallback != null) {
                        progressCallback.accept(PipelineProgress.vlmStep(progressPage, effectiveTotalPages, "Rendering page"));
                    }

                    BufferedImage pageImage = renderPageSafe(renderer, document, pageNum - 1, config.getPdfRenderDpi());
                    ImageTiler.SplitImageResult splitResult = ImageTiler.splitImageForVLM(pageImage, tileSize, config.getMaxTiles());
                    pageImage = null; // Allow GC of rendered image

                    logger.debug("Page {}: split into {} tiles ({}x{} grid)",
                            pageNum, splitResult.getTileCount(), splitResult.numCols, splitResult.numRows);

                    if (progressCallback != null) {
                        progressCallback.accept(PipelineProgress.vlmStep(progressPage, effectiveTotalPages,
                                "Generating tokens (" + splitResult.getTileCount() + " tiles)"));
                    }

                    long pageStartTime = System.currentTimeMillis();

                    // Process single page through generatePagesTiled
                    GenerationResult[] pageGenResults = vlm.generatePagesTiled(
                            java.util.Collections.singletonList(splitResult),
                            prompt,
                            samplingConfig.getMaxNewTokens(),
                            samplingConfig.isDoSample(),
                            samplingConfig.getTemperature(),
                            tileSize
                    );

                    // Free split result frames immediately after generation
                    splitResult = null;

                    // Reset all inference sessions to free GPU memory before next page.
                    // For decode (invariant shapes), use resetSessionsForDecode() which preserves
                    // staging buffers, slot arrays, CUDA graphs, and cuBLAS workspace.
                    vlm.resetSessionsForDecode();

                    // Export Triton cache after first successful decode (JIT compilation happens on first run)
                    if (!tritonExportDone && tritonCacheEnabled && tritonAutoExport && tritonCacheExporter != null) {
                        try {
                            logger.info("Auto-exporting Triton cache after first decode");
                            tritonCacheExporter.run();
                            tritonExportDone = true;
                        } catch (Exception e) {
                            logger.warn("Triton cache auto-export failed: {}", e.getMessage());
                            tritonExportDone = true; // Don't retry on failure
                        }
                    }

                    long generateTimeMs = System.currentTimeMillis() - pageStartTime;

                    GenerationResult genResult = pageGenResults[0];
                    String generatedText = genResult.getText();
                    int generatedTokens = genResult.getGeneratedTokenCount();
                    int promptTokens = genResult.getPromptTokenCount();
                    double tokensPerSecond = generatedTokens > 0 ? (generatedTokens * 1000.0) / Math.max(generateTimeMs, 1) : 0;
                    // Use per-page timing from result if available
                    if (genResult.getGenerationTimeMs() > 0) {
                        generateTimeMs = genResult.getGenerationTimeMs();
                        tokensPerSecond = genResult.getTokensPerSecond();
                    }

                    double effectiveTokensPerSecond = effectiveTokensPerSecond(genResult);
                    String throughputLabel = throughputLabel(genResult);

                    logger.info("Page {}/{}: {} tokens in {}ms (overall={} tok/s, {}={} tok/s), model {}",
                            progressPage, effectiveTotalPages, generatedTokens, generateTimeMs,
                            String.format("%.1f", tokensPerSecond), throughputLabel,
                            String.format("%.1f", effectiveTokensPerSecond), modelId);

                    if (progressCallback != null) {
                        progressCallback.accept(PipelineProgress.vlmStep(progressPage, effectiveTotalPages, "Parsing output"));
                    }

                    String plainText;
                    List<StructuredTable> tables = new ArrayList<>();

                    switch (config.getVlmOutputFormat()) {
                        case DOCTAGS:
                            DocumentStructure docStructure = docTagsParser.parse(generatedText);
                            plainText = docStructure.getFullText();
                            tables = extractTablesFromDocStructure(docStructure);
                            break;
                        case MARKDOWN:
                            DocumentStructure parsed = docTagsParser.parse(generatedText);
                            plainText = docTagsParser.toMarkdown(parsed);
                            tables = extractTablesFromDocStructure(parsed);
                            break;
                        case FLORENCE2:
                            DocumentStructure florenceDoc = docTagsParser.parse(generatedText);
                            plainText = docTagsParser.extractPlainText(generatedText);
                            tables = extractTablesFromDocStructure(florenceDoc);
                            break;
                        case DONUT:
                            DocumentStructure donutDoc = docTagsParser.parse(generatedText);
                            plainText = docTagsParser.extractPlainText(generatedText);
                            tables = extractTablesFromDocStructure(donutDoc);
                            break;
                        case PLAIN_TEXT:
                            plainText = docTagsParser.extractPlainText(generatedText);
                            break;
                        case JSON:
                            plainText = generatedText;
                            break;
                        case TEXT:
                        default:
                            plainText = docTagsParser.extractPlainText(generatedText);
                            break;
                    }

                    DocumentComplexity complexity = DocumentComplexity.SIMPLE;
                    if (!tables.isEmpty()) {
                        complexity = DocumentComplexity.TABLES;
                    } else if (generatedText.contains("<figure>") || generatedText.contains("<formula>")) {
                        complexity = DocumentComplexity.MIXED;
                    }

                    AuditTrail audit = AuditTrail.forPage(pdfFile.getAbsolutePath(), pageNum);
                    audit.addModelResult(ModelResult.success(
                            modelId, OcrModelType.LAYOUT_MODEL, generateTimeMs,
                            generatedText.length(), 1.0));
                    audit.complete();

                    int tileCount = pageGenResults[0].getGeneratedTokenCount() > 0 ?
                            (int) Math.ceil(generatedTokens / 64.0) : 0;

                    Map<String, Object> pageMetadata = new LinkedHashMap<>();
                    pageMetadata.put("vlmModel", modelId);
                    pageMetadata.put("outputFormat", config.getVlmOutputFormat().name());
                    pageMetadata.put("rawOutput", generatedText);
                    pageMetadata.put("generatedTokens", generatedTokens);
                    pageMetadata.put("promptTokens", promptTokens);
                    pageMetadata.put("tokensPerSecond", Math.round(tokensPerSecond * 100.0) / 100.0);
                    pageMetadata.put("effectiveTokensPerSecond", Math.round(effectiveTokensPerSecond * 100.0) / 100.0);
                    pageMetadata.put("throughputLabel", throughputLabel);
                    pageMetadata.put("steadyStateTokensPerSecond", Math.round(genResult.getSteadyStateTokensPerSecond() * 100.0) / 100.0);
                    pageMetadata.put("lateSteadyStateTokensPerSecond", Math.round(genResult.getLateSteadyStateTokensPerSecond() * 100.0) / 100.0);
                    pageMetadata.put("generateTimeMs", generateTimeMs);
                    pageMetadata.put("tiledProcessing", true);
                    pageMetadata.put("tileCount", tileCount);

                    ParsedDocument pageResult = ParsedDocument.builder()
                            .id(UUID.randomUUID().toString())
                            .sourceId(pdfFile.getAbsolutePath())
                            .pageNumber(pageNum)
                            .totalPages(effectiveTotalPages)
                            .documentType(DocumentType.SCANNED)
                            .complexity(complexity)
                            .text(plainText)
                            .tables(tables)
                            .auditTrail(config.isIncludeAuditTrail() ? audit : null)
                            .success(true)
                            .processingTimeMs(generateTimeMs)
                            .metadata(pageMetadata)
                            .build();

                    results.add(pageResult);

                    if (progressCallback != null) {
                        String vlmModelName = config.getVlmModelId() != null ? config.getVlmModelId() : modelId;
                        progressCallback.accept(PipelineProgress.vlmPageCompleted(
                                progressPage, effectiveTotalPages, generatedTokens, promptTokens,
                                tokensPerSecond, generateTimeMs, vlmModelName));
                    }
                } catch (Exception pageEx) {
                    failedPages++;
                    logger.error("Page {}/{} failed: {}. Continuing with remaining pages.",
                            progressPage, effectiveTotalPages, pageEx.getMessage(), pageEx);

                    // Reset sessions to free GPU memory from the failed page
                    try {
                        vlm.resetSessionsForDecode();
                    } catch (Exception resetEx) {
                        logger.warn("Failed to reset sessions after page {} error: {}", pageNum, resetEx.getMessage());
                    }

                    // Record a failed result for this page so it's tracked in output
                    ParsedDocument failedResult = ParsedDocument.builder()
                            .id(UUID.randomUUID().toString())
                            .sourceId(pdfFile.getAbsolutePath())
                            .pageNumber(pageNum)
                            .totalPages(effectiveTotalPages)
                            .documentType(DocumentType.SCANNED)
                            .complexity(DocumentComplexity.SIMPLE)
                            .text("")
                            .tables(new ArrayList<>())
                            .success(false)
                            .processingTimeMs(0)
                            .metadata(Map.of(
                                    "vlmModel", modelId,
                                    "error", pageEx.getMessage() != null ? pageEx.getMessage() : pageEx.getClass().getName(),
                                    "errorType", pageEx.getClass().getSimpleName()
                            ))
                            .build();
                    results.add(failedResult);

                    if (progressCallback != null) {
                        progressCallback.accept(PipelineProgress.vlmStep(progressPage, effectiveTotalPages,
                                "Page failed: " + pageEx.getMessage()));
                    }
                }
            }

            if (failedPages > 0) {
                logger.warn("VLM processing completed with {}/{} pages failed", failedPages, pagesToProcess.size());
            }

            // Release vision encoder GPU memory after all pages are encoded
            if (releaseEncoderAfterEncoding && vlm != null && vlm.getVisionEncoder() != null) {
                try {
                    long beforeFree = getGpuFreeMemory();
                    logger.info("Releasing vision encoder to free GPU memory...");
                    vlm.getVisionEncoder().close();
                    long afterFree = getGpuFreeMemory();
                    long freedMb = (afterFree - beforeFree) / (1024 * 1024);
                    logger.info("Vision encoder released. GPU memory freed: ~{}MB (before: {}MB free, after: {}MB free)",
                            freedMb, beforeFree / (1024 * 1024), afterFree / (1024 * 1024));
                } catch (Exception e) {
                    logger.warn("Failed to release vision encoder: {}", e.getMessage());
                }
            }

            if (progressCallback != null) {
                progressCallback.accept(PipelineProgress.completed(effectiveTotalPages));
            }

        } catch (Exception e) {
            logger.error("Failed to process PDF with VLM: {}", e.getMessage(), e);
            results.add(ParsedDocument.failed(pdfFile.getAbsolutePath(), 0, e.getMessage()));
        }

        return results;
    }

    @Override
    public List<ParsedDocument> processPdf(InputStream pdfStream, String sourceId) {
        try {
            File tempFile = File.createTempFile("vlm_", ".pdf");
            tempFile.deleteOnExit();
            java.nio.file.Files.copy(pdfStream, tempFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            OcrPipelineConfig config = OcrPipelineConfig.builder()
                    .useVlm(true)
                    .vlmModelId(modelId)
                    .sourceId(sourceId)
                    .build();

            return processPdf(tempFile, config, null);
        } catch (Exception e) {
            logger.error("Failed to process PDF stream with VLM: {}", e.getMessage(), e);
            List<ParsedDocument> results = new ArrayList<>();
            results.add(ParsedDocument.failed(sourceId, 0, e.getMessage()));
            return results;
        }
    }

    @Override
    public ParsedDocument processPage(File pdfFile, int pageNumber) {
        OcrPipelineConfig config = OcrPipelineConfig.builder()
                .useVlm(true)
                .vlmModelId(modelId)
                .sourceId(pdfFile.getAbsolutePath())
                .pageRange(String.valueOf(pageNumber))
                .build();

        List<ParsedDocument> results = processPdf(pdfFile, config, null);
        return results.isEmpty() ?
                ParsedDocument.failed(pdfFile.getAbsolutePath(), pageNumber, "No results") :
                results.get(0);
    }

    @Override
    public PipelineModels getModels() {
        // VLM doesn't use traditional detection/recognition models
        return new PipelineModels(null, null, null, null);
    }

    @Override
    public boolean isReady() {
        return loaded && vlm != null && vlm.isValid();
    }

    @Override
    public void loadModels() throws Exception {
        loadModels(ModelConstants.getDefaultVlmModelId()); // Default model from ModelConstants
    }

    /**
     * Loads VLM model components using KompileModelManager with default auto-detection.
     *
     * @param vlmModelId Model ID to load (e.g., "smoldocling-256m")
     */
    public void loadModels(String vlmModelId) throws Exception {
        loadModels(vlmModelId, null);
    }

    /**
     * Loads VLM model components using KompileModelManager.
     * When config provides component paths (vlmDecoderPath, vlmEncoderPath, etc.),
     * those are used directly instead of auto-detecting from the model directory.
     *
     * @param vlmModelId Model ID to load (e.g., "smoldocling-256m")
     * @param config Optional config with component path overrides (null for auto-detection)
     */
    public void loadModels(String vlmModelId, OcrPipelineConfig config) throws Exception {
        this.modelId = vlmModelId;

        logger.info("Loading VLM model: {} using KompileModelManager", vlmModelId);

        // Use KompileModelManager to get the model bundle
        KompileModelManager.VlmModelBundle bundle = modelManager.ensureVlmModelAvailable(vlmModelId);

        if (!bundle.exists()) {
            ModelConstants.VlmModelDescriptor descriptor = ModelConstants.getVlmModelDescriptor(vlmModelId);
            String hint = descriptor != null
                    ? "Please import the model from: " + descriptor.getHuggingFaceUrl()
                    : "Stage and promote a VLM model via the Model Staging UI";
            throw new IllegalStateException("VLM model directory not found for: " + vlmModelId +
                    ". " + hint);
        }

        File modelDirectory = bundle.getModelDirectoryFile();

        logger.info("Loading VLM from directory: {}", modelDirectory);

        // Resolve vision encoder IO config from registry (user-overridable, auto-probed at promotion)
        VisionEncoderIOConfig ioConfig = resolveVisionEncoderIOConfigFromRegistry(vlmModelId);

        // Check if config provides explicit component paths
        if (config != null && hasExplicitComponentPaths(config)) {
            loadFromExplicitPaths(config, modelDirectory, ioConfig);
        } else {
            loadFromAutoDetect(vlmModelId, modelDirectory, ioConfig);
        }

        // Load image preprocessor with fallback chain:
        // 1. Registry-persisted preprocessor config (from staging UI)
        // 2. Explicit path from config
        // 3. Model-bundled preprocessor
        // 4. preprocessor_config.json in model directory
        // 5. SmolDocling defaults (with warning)
        VLMImagePreprocessor registryPreprocessor = resolvePreprocessorFromRegistry(vlmModelId);
        if (registryPreprocessor != null) {
            this.imagePreprocessor = registryPreprocessor;
            logger.info("Loaded image preprocessor from registry config for model: {}", vlmModelId);
        } else if (config != null && config.getVlmPreprocessorConfigPath() != null) {
            File ppConfig = resolveFile(config.getVlmPreprocessorConfigPath(), modelDirectory);
            if (ppConfig != null && ppConfig.exists()) {
                this.imagePreprocessor = VLMImagePreprocessor.fromConfig(ppConfig);
                logger.info("Loaded image preprocessor from explicit path: {}", ppConfig);
            } else {
                logger.warn("Explicit preprocessor config not found: {}, falling back to auto-detect",
                        config.getVlmPreprocessorConfigPath());
                this.imagePreprocessor = resolveImagePreprocessor(modelDirectory);
            }
        } else {
            this.imagePreprocessor = resolveImagePreprocessor(modelDirectory);
        }

        // Apply KV cache configuration from pipeline config
        if (config != null && vlm != null) {
            applyKvCacheConfig(config);
        }

        this.loaded = vlm != null && vlm.isValid();

        if (!loaded) {
            throw new IllegalStateException("Failed to load VLM model: " + vlmModelId);
        }

        logger.info("VLM model loaded: {} (explicit paths: {}, kvCacheStrategy={}, maxKvLen={})",
                vlmModelId,
                config != null && hasExplicitComponentPaths(config),
                vlm.getKvCacheStrategy(),
                vlm.getMaxKvLen());
    }

    /**
     * Apply KV cache configuration from OcrPipelineConfig to the loaded VLM.
     * Rebuilds the VLM with the specified kvCacheStrategy and maxKvLen.
     *
     * <p>When {@code kompile.kvcache.enabled=true} and a {@link VlmKvCacheIntegrationService}
     * is available, creates a persistent managed KV cache for this model. The cache is
     * visible in the KV cache browser UI and survives across inference calls.</p>
     *
     * <p>When maxKvLen is 0 (auto), attempts to pick a sensible default based on
     * available GPU memory via ND4J device info. Falls back to a conservative
     * default of 2048 if GPU memory cannot be detected.</p>
     */
    private void applyKvCacheConfig(OcrPipelineConfig config) {
        KvCacheStrategy strategy = KvCacheStrategy.STATIC;
        if (config.getKvCacheStrategy() != null) {
            try {
                strategy = KvCacheStrategy.valueOf(config.getKvCacheStrategy().toUpperCase());
            } catch (IllegalArgumentException e) {
                logger.warn("Unknown kvCacheStrategy '{}', falling back to STATIC", config.getKvCacheStrategy());
            }
        }

        int maxKvLen = config.getMaxKvLen();

        // Auto-detect sensible maxKvLen if not explicitly set
        if (maxKvLen <= 0) {
            maxKvLen = autoDetectMaxKvLen();
        }

        // Skip rebuild if strategy and maxKvLen already match — rebuilding creates a new
        // VisionLanguageModel which triggers DSP re-compilation, leaking GPU memory.
        if (vlm.getKvCacheStrategy() == strategy && vlm.getMaxKvLen() == maxKvLen) {
            logger.debug("KV cache config unchanged (strategy={}, maxKvLen={}), skipping rebuild", strategy, maxKvLen);
            return;
        }

        // Try to create a persistent managed KV cache via kompile's integration service.
        // This makes the cache visible in the KV cache browser UI with live statistics.
        org.eclipse.deeplearning4j.llm.generation.KvCacheManager externalKvCacheManager = null;
        if (vlmKvCacheIntegrationService != null && vlmKvCacheIntegrationService.isEnabled() && modelId != null) {
            try {
                externalKvCacheManager = vlmKvCacheIntegrationService.getOrCreateKvCacheManager(modelId, strategy);
                logger.info("Using kompile-managed persistent KV cache for VLM model '{}' " +
                        "(visible at /api/kvcache/caches/vlm:{})", modelId, modelId);
            } catch (Exception e) {
                logger.warn("Failed to create kompile-managed KV cache for '{}': {}. " +
                        "Falling back to non-managed cache.", modelId, e.getMessage());
            }
        } else if (vlmKvCacheIntegrationService == null) {
            logger.debug("VlmKvCacheIntegrationService not available; using standard KV cache");
        } else if (!vlmKvCacheIntegrationService.isEnabled()) {
            logger.debug("kompile.kvcache.enabled=false; using standard KV cache. " +
                    "Enable it for persistent KV cache with UI monitoring.");
        }

        logger.info("Applying KV cache config: strategy={}, maxKvLen={}, managed={}",
                strategy, maxKvLen, externalKvCacheManager != null);

        BenchmarkConfig existingBenchmarkConfig = vlm.getBenchmarkConfig();
        var builder = VisionLanguageModel.builder()
                .visionEncoder(vlm.getVisionEncoder())
                .decoder(vlm.getDecoder())
                .embedTokens(vlm.getEmbedTokens())
                .tokenizer(vlm.getTokenizer())
                .config(vlm.getConfig())
                .visionEncoderIOConfig(vlm.getVisionEncoderIOConfig())
                .imagePreprocessor(vlm.getImagePreprocessor())
                .kvCacheStrategy(strategy)
                .maxKvLen(maxKvLen);

        if (externalKvCacheManager != null) {
            builder.externalKvCacheManager(externalKvCacheManager);
        }

        this.vlm = builder.build();
        this.vlm.setBenchmarkConfig(existingBenchmarkConfig);
    }

    /**
     * Auto-detect a sensible maxKvLen based on available GPU memory.
     * Conservative defaults prevent GPU OOM during KV cache allocation.
     *
     * @return recommended maxKvLen
     */
    private int autoDetectMaxKvLen() {
        try {
            // Try to get GPU memory from ND4J
            long totalDeviceMemory = org.nd4j.linalg.factory.Nd4j.getMemoryManager()
                    .getCurrentWorkspace() != null ? 0 : 0; // placeholder
            // Use device properties if available
            var props = org.nd4j.linalg.factory.Nd4j.getAffinityManager();
            int numDevices = props.getNumberOfDevices();
            if (numDevices > 0) {
                long totalMemBytes = org.nd4j.nativeblas.NativeOpsHolder.getInstance()
                        .getDeviceNativeOps().getDeviceTotalMemory(0);
                long totalMemMb = totalMemBytes / (1024 * 1024);
                logger.info("Auto-detecting maxKvLen: GPU 0 has {}MB total memory", totalMemMb);
                if (totalMemMb >= 70000) return 8192;   // 80GB+ GPU (A100, H100)
                if (totalMemMb >= 40000) return 4096;   // 48GB GPU (A6000, RTX 6000)
                if (totalMemMb >= 20000) return 3072;   // 24GB GPU (RTX 3090/4090) - prefill ~2K + ~1K gen headroom
                if (totalMemMb >= 10000) return 2048;   // 16GB GPU
                return 1024;  // Small GPU
            }
        } catch (Exception e) {
            logger.debug("Could not auto-detect GPU memory for maxKvLen: {}", e.getMessage());
        }

        // Conservative default: allows VLM prompts (~2000 tokens) + generation headroom
        logger.info("Using default maxKvLen=4096 (GPU memory not detected)");
        return 4096;
    }

    /**
     * Get GPU free memory for the current device.
     * Returns 0 if not available (e.g., CPU-only mode).
     */
    private long getGpuFreeMemory() {
        try {
            return org.nd4j.nativeblas.NativeOpsHolder.getInstance()
                    .getDeviceNativeOps().getDeviceFreeMemory(0);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Checks if the config has any explicit VLM component paths set.
     */
    private boolean hasExplicitComponentPaths(OcrPipelineConfig config) {
        return config.getVlmDecoderPath() != null
                || config.getVlmEncoderPath() != null
                || config.getVlmEmbedTokensPath() != null
                || config.getVlmTokenizerPath() != null;
    }

    /**
     * Resolves a path that may be absolute or relative to the model directory.
     */
    private File resolveFile(String path, File modelDirectory) {
        if (path == null) return null;
        File f = new File(path);
        if (f.isAbsolute()) return f.exists() ? f : null;
        // Relative to model directory
        File resolved = new File(modelDirectory, path);
        return resolved.exists() ? resolved : null;
    }

    /**
     * Loads VLM from explicit component paths provided in config.
     * Paths can be absolute or relative to the model directory.
     * Any path not specified falls back to auto-detection in the model directory.
     */
    private void loadFromExplicitPaths(OcrPipelineConfig config, File modelDirectory, VisionEncoderIOConfig ioConfig) throws Exception {
        logger.info("Loading VLM from explicit component paths");

        // Resolve each component: explicit path → auto-detect fallback
        File decoder = config.getVlmDecoderPath() != null
                ? resolveFile(config.getVlmDecoderPath(), modelDirectory)
                : autoDetectDecoder(modelDirectory);
        File visionEncoder = config.getVlmEncoderPath() != null
                ? resolveFile(config.getVlmEncoderPath(), modelDirectory)
                : autoDetectFile(modelDirectory, "vision_encoder.onnx", "vision_encoder.sdz",
                        "encoder.onnx", "encoder.sdz");
        File embedTokens = config.getVlmEmbedTokensPath() != null
                ? resolveFile(config.getVlmEmbedTokensPath(), modelDirectory)
                : autoDetectFile(modelDirectory, "embed_tokens.onnx", "embed_tokens.sdz",
                        "embeddings.onnx", "embeddings.sdz");
        File tokenizerJson = config.getVlmTokenizerPath() != null
                ? resolveFile(config.getVlmTokenizerPath(), modelDirectory)
                : findFileInDir(modelDirectory, "tokenizer.json");

        if (decoder == null) {
            throw new IllegalStateException("Decoder not found. Explicit path: " +
                    config.getVlmDecoderPath() + ", model dir: " + modelDirectory);
        }
        if (tokenizerJson == null) {
            throw new IllegalStateException("Tokenizer not found. Explicit path: " +
                    config.getVlmTokenizerPath() + ", model dir: " + modelDirectory);
        }

        // Determine loading strategy based on file extensions
        boolean decoderIsOnnx = decoder.getName().endsWith(".onnx");

        if (decoderIsOnnx) {
            visionEncoder = restoreOnnxFromBackupIfNeeded(visionEncoder);
            decoder = restoreOnnxFromBackupIfNeeded(decoder);
            embedTokens = restoreOnnxFromBackupIfNeeded(embedTokens);
            cleanStaleSdzCache(modelDirectory, visionEncoder);
            cleanStaleSdzCache(modelDirectory, decoder);
            cleanStaleSdzCache(modelDirectory, embedTokens);

            logger.info("Loading VLM from ONNX: decoder={}, encoder={}, embedTokens={}, tokenizer={}",
                    decoder.getName(),
                    visionEncoder != null ? visionEncoder.getName() : "none",
                    embedTokens != null ? embedTokens.getName() : "none",
                    tokenizerJson.getName());

            this.vlm = VisionLanguageModel.fromOnnx(visionEncoder, decoder, embedTokens, tokenizerJson, ioConfig);
        } else {
            // SDZ components - use builder for maximum flexibility
            logger.info("Loading VLM from explicit SDZ/mixed components");
            this.vlm = VisionLanguageModel.fromDirectory(modelDirectory, ioConfig);
        }
    }

    /**
     * Auto-detects the decoder file from a model directory.
     */
    private File autoDetectDecoder(File modelDirectory) {
        return autoDetectFile(modelDirectory,
                "decoder_model_merged.onnx", "decoder_model.onnx", "decoder.onnx",
                "decoder.sdz", "decoder_model.sdz", "language_model.sdz", "model.sdz");
    }

    /**
     * Finds the first existing file from a list of candidate names in a directory.
     */
    private File autoDetectFile(File dir, String... candidates) {
        for (String name : candidates) {
            File f = findFileInDir(dir, name);
            if (f != null) return f;
        }
        return null;
    }

    /**
     * Auto-detects model format and loads from model directory (original behavior).
     */
    private void loadFromAutoDetect(String vlmModelId, File modelDirectory, VisionEncoderIOConfig ioConfig) throws Exception {
        boolean hasOnnxDecoder = findFileInDir(modelDirectory, "decoder_model_merged.onnx") != null
                || findFileInDir(modelDirectory, "decoder_model.onnx") != null
                || findFileInDir(modelDirectory, "decoder.onnx") != null;
        boolean hasSdzDecoder = findFileInDir(modelDirectory, "decoder.sdz") != null
                || findFileInDir(modelDirectory, "decoder_model.sdz") != null
                || findFileInDir(modelDirectory, "decoder_model_merged.sdz") != null
                || findFileInDir(modelDirectory, "language_model.sdz") != null
                || findFileInDir(modelDirectory, "model.sdz") != null;

        String normalizedId = vlmModelId.toLowerCase().replace("_", "-");

        if (hasSdzDecoder) {
            // Prefer SDZ when available - these are already imported and known to work.
            // MultiPartModelLoader applies the agnostic SameDiff optimized-SDZ cache.
            logger.info("Loading VLM from SDZ files in: {}", modelDirectory);
            if (normalizedId.contains("smoldocling")) {
                this.vlm = VisionLanguageModel.loadSmolDocling(modelDirectory, ioConfig);
            } else {
                this.vlm = VisionLanguageModel.fromDirectory(modelDirectory, ioConfig);
            }
        } else if (hasOnnxDecoder) {
            logger.info("Loading VLM from ONNX files in: {}", modelDirectory);
            File visionEncoder = findFileInDir(modelDirectory, "vision_encoder.onnx");
            File decoder = findFileInDir(modelDirectory, "decoder_model_merged.onnx");
            if (decoder == null) decoder = findFileInDir(modelDirectory, "decoder_model.onnx");
            if (decoder == null) decoder = findFileInDir(modelDirectory, "decoder.onnx");
            File embedTokens = findFileInDir(modelDirectory, "embed_tokens.onnx");
            File tokenizerJson = findFileInDir(modelDirectory, "tokenizer.json");

            if (decoder == null || tokenizerJson == null) {
                throw new IllegalStateException("Missing required VLM ONNX components in " + modelDirectory +
                        ". Need at minimum: decoder_model_merged.onnx and tokenizer.json");
            }

            visionEncoder = restoreOnnxFromBackupIfNeeded(visionEncoder);
            decoder = restoreOnnxFromBackupIfNeeded(decoder);
            embedTokens = restoreOnnxFromBackupIfNeeded(embedTokens);

            cleanStaleSdzCache(modelDirectory, visionEncoder);
            cleanStaleSdzCache(modelDirectory, decoder);
            cleanStaleSdzCache(modelDirectory, embedTokens);

            // Auto-register known model-specific patches
            List<SameDiffGraphPatch> effectiveVisionPatches = resolveVisionEncoderPatches(normalizedId);
            List<SameDiffGraphPatch> effectiveDecoderPatches = new ArrayList<>(decoderPatches);
            List<SameDiffGraphPatch> effectiveEmbedPatches = new ArrayList<>(embedTokensPatches);

            if (!effectiveVisionPatches.isEmpty() || !effectiveDecoderPatches.isEmpty() || !effectiveEmbedPatches.isEmpty()) {
                logger.info("Loading VLM from ONNX with patches - vision: {}, decoder: {}, embedTokens: {}",
                        effectiveVisionPatches.size(), effectiveDecoderPatches.size(), effectiveEmbedPatches.size());
                this.vlm = VisionLanguageModel.fromOnnxWithPatches(
                        visionEncoder, decoder, embedTokens, tokenizerJson,
                        8 * 1024 * 1024, ioConfig,
                        effectiveVisionPatches.isEmpty() ? null : effectiveVisionPatches,
                        effectiveDecoderPatches.isEmpty() ? null : effectiveDecoderPatches,
                        effectiveEmbedPatches.isEmpty() ? null : effectiveEmbedPatches);
            } else {
                this.vlm = VisionLanguageModel.fromOnnx(visionEncoder, decoder, embedTokens, tokenizerJson, ioConfig);
            }

            // Override with the correct preprocessor from the model's preprocessor_config.json.
            VLMImagePreprocessor correctPreprocessor = resolveOnnxPreprocessor(normalizedId, modelDirectory);
            if (correctPreprocessor != null) {
                logger.info("Overriding ONNX default preprocessor with model-specific preprocessor (target: {}x{})",
                        correctPreprocessor.getTargetHeight(), correctPreprocessor.getTargetWidth());
                this.vlm = VisionLanguageModel.builder()
                        .visionEncoder(this.vlm.getVisionEncoder())
                        .decoder(this.vlm.getDecoder())
                        .embedTokens(this.vlm.getEmbedTokens())
                        .tokenizer(this.vlm.getTokenizer())
                        .config(this.vlm.getConfig())
                        .visionEncoderIOConfig(ioConfig)
                        .imagePreprocessor(correctPreprocessor)
                        .build();
            }
        } else {
            logger.info("No SDZ or ONNX files found, trying auto-detect in: {}", modelDirectory);
            this.vlm = VisionLanguageModel.fromDirectory(modelDirectory, ioConfig);
        }

        logger.info("VLM auto-detected format: {}", hasSdzDecoder ? "SDZ" : hasOnnxDecoder ? "ONNX" : "fallback");

        // Use the same source-of-truth optimization config as the DL4J VLM benchmark.
        // Apply environment flags now; decoder/embed_tokens compile is deferred until
        // GenerationPipeline has been constructed so the ordering matches platform-tests.
        BenchmarkConfig optimalConfig = BenchmarkConfig.optimal();
        logger.info("Applying VLM benchmark config: {}", optimalConfig.getName());
        BenchmarkConfigApplier.apply(optimalConfig);

        this.vlm.setBenchmarkConfig(optimalConfig);
        logger.info("Deferred BenchmarkConfig.{} decoder/embed_tokens compile until GenerationPipeline creation",
                optimalConfig.getName());
    }

    /**
     * Resolves vision encoder patches - combines user-configured patches with
     * auto-detected model-specific patches based on model ID.
     */
    private List<SameDiffGraphPatch> resolveVisionEncoderPatches(String normalizedId) {
        List<SameDiffGraphPatch> patches = new ArrayList<>(visionEncoderPatches);

        // Auto-register known model-specific patches
        if (normalizedId.contains("smoldocling")) {
            boolean alreadyHasPositionPatch = patches.stream()
                    .anyMatch(p -> p instanceof SmolDoclingPositionIdsPatch);
            if (!alreadyHasPositionPatch) {
                logger.info("Auto-registering SmolDoclingPositionIdsPatch for model: {}", normalizedId);
                patches.add(new SmolDoclingPositionIdsPatch());
            }
        }

        return patches;
    }

    /**
     * Resolves the correct preprocessor for ONNX-loaded models.
     * VisionLanguageModel.fromOnnx() hardcodes CLIP 224x224 defaults which is wrong
     * for models like SmolDocling that require 512x512.
     * Checks: 1) known model types, 2) preprocessor_config.json in model directory.
     */
    private VLMImagePreprocessor resolveOnnxPreprocessor(String normalizedId, File modelDirectory) {
        // Load from preprocessor_config.json or processor_config.json in model directory
        try {
            VLMImagePreprocessor loaded = VLMImagePreprocessor.fromModelDirectory(modelDirectory);
            logger.info("Loaded preprocessor from model directory: {}", modelDirectory.getAbsolutePath());
            return loaded;
        } catch (Exception e) {
            logger.warn("Failed to load preprocessor config from {}: {}", modelDirectory, e.getMessage());
        }

        // Also try processor_config.json (alternative naming)
        File processorConfig = new File(modelDirectory, "processor_config.json");
        if (processorConfig.exists()) {
            try {
                VLMImagePreprocessor loaded = VLMImagePreprocessor.fromConfig(processorConfig);
                logger.info("Loaded preprocessor from {}", processorConfig.getAbsolutePath());
                return loaded;
            } catch (Exception e) {
                logger.warn("Failed to load processor_config.json: {}", e.getMessage());
            }
        }

        return null; // Keep the default from fromOnnx()
    }

    private boolean hasFileWithExtension(File dir, String extension) {
        File[] files = dir.listFiles((d, n) -> n.endsWith(extension));
        return files != null && files.length > 0;
    }

    private File findFileInDir(File dir, String name) {
        File f = new File(dir, name);
        return f.isFile() ? f : null;
    }

    /**
     * The staging optimization pass may overwrite .onnx files with SDZ-format data
     * (magic bytes "SDNB") while keeping the .onnx extension. The original ONNX file
     * gets saved as .onnx.backup. Detect this and restore the real ONNX file.
     */
    private File restoreOnnxFromBackupIfNeeded(File onnxFile) {
        if (onnxFile == null || !onnxFile.exists()) return onnxFile;
        try {
            // Check magic bytes - ONNX protobuf starts with 0x08, SDZ starts with "SDNB"
            byte[] magic = new byte[4];
            try (java.io.FileInputStream fis = new java.io.FileInputStream(onnxFile)) {
                if (fis.read(magic) == 4 && magic[0] == 'S' && magic[1] == 'D' && magic[2] == 'N' && magic[3] == 'B') {
                    // This .onnx file is actually SDZ format - look for the .backup
                    File backup = new File(onnxFile.getPath() + ".backup");
                    if (backup.exists()) {
                        logger.warn("ONNX file {} contains SDZ data (from optimization). Restoring from backup.", onnxFile.getName());
                        // Rename the SDZ-disguised file and restore the real ONNX
                        String sdzName = onnxFile.getName().replace(".onnx", ".sdz");
                        File sdzTarget = new File(onnxFile.getParentFile(), sdzName);
                        onnxFile.renameTo(sdzTarget);
                        backup.renameTo(onnxFile);
                        logger.info("Restored {} from backup, moved SDZ data to {}", onnxFile.getName(), sdzName);
                    } else {
                        logger.error("ONNX file {} contains SDZ data but no .backup exists to restore from", onnxFile.getName());
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to check/restore ONNX file {}: {}", onnxFile.getName(), e.getMessage());
        }
        return onnxFile;
    }

    /**
     * Remove stale .sdz cache files that correspond to an ONNX file.
     * OnnxModelCache uses the ONNX filename with .sdz extension as cache.
     * Stale SDZ files from optimization passes will cause load failures.
     */
    private void cleanStaleSdzCache(File dir, File onnxFile) {
        if (onnxFile == null || !onnxFile.exists()) return;
        String onnxName = onnxFile.getName();
        if (!onnxName.endsWith(".onnx")) return;
        String sdzName = onnxName.replace(".onnx", ".sdz");
        File sdzCache = new File(dir, sdzName);
        if (sdzCache.exists()) {
            logger.info("Removing stale SDZ cache file {} to force ONNX re-import", sdzCache.getName());
            sdzCache.delete();
        }
    }

    /**
     * Loads VLM model from a specific directory (bypasses KompileModelManager).
     * The directory should contain SDZ model files (vision_encoder.sdz, decoder.sdz, etc.)
     *
     * @param vlmModelId Model ID (for identification purposes)
     * @param modelDirectory Directory containing SDZ model files
     */
    public void loadModelsFromDirectory(String vlmModelId, File modelDirectory) throws Exception {
        this.modelId = vlmModelId;

        logger.info("Loading VLM model: {} from directory: {}", vlmModelId, modelDirectory);

        if (modelDirectory == null || !modelDirectory.exists()) {
            throw new IllegalStateException("Model directory not found: " + modelDirectory);
        }

        // Resolve vision encoder IO config from registry (user-overridable)
        VisionEncoderIOConfig ioConfig = resolveVisionEncoderIOConfigFromRegistry(vlmModelId);

        // Load VisionLanguageModel from directory containing SDZ files
        String normalizedId = vlmModelId.toLowerCase().replace("_", "-");
        if (normalizedId.contains("smoldocling")) {
            this.vlm = VisionLanguageModel.loadSmolDocling(modelDirectory, ioConfig);
        } else {
            this.vlm = VisionLanguageModel.fromDirectory(modelDirectory, ioConfig);
        }

        // Load image preprocessor with fallback chain
        this.imagePreprocessor = resolveImagePreprocessor(modelDirectory);

        this.loaded = vlm != null && vlm.isValid();

        if (!loaded) {
            throw new IllegalStateException("Failed to load VLM model: " + vlmModelId);
        }

        logger.info("VLM model loaded from directory: {}", modelDirectory);
    }


    /**
     * Gets the model manager.
     *
     * @return the KompileModelManager instance
     */
    public KompileModelManager getModelManager() {
        return modelManager;
    }

    @Override
    public void unloadModels() {
        if (vlm != null) {
            try {
                vlm.close();
            } catch (Exception e) {
                logger.warn("Error closing VLM: {}", e.getMessage());
            }
            vlm = null;
        }
        imagePreprocessor = null;
        loaded = false;
        logger.info("VLM models unloaded");
    }

    // ==================== Preprocessor Resolution ====================

    /**
     * Attempts to resolve an image preprocessor from the model registry.
     * If the model has a persisted {@link ImagePreprocessorConfig} in the registry
     * (configured via the staging UI), it is converted to a DL4J {@link PreprocessorConfig}
     * and used to construct the {@link VLMImagePreprocessor}.
     *
     * @param vlmModelId the model ID to look up
     * @return a VLMImagePreprocessor if registry config exists, null otherwise
     */
    private VLMImagePreprocessor resolvePreprocessorFromRegistry(String vlmModelId) {
        if (registryService == null || vlmModelId == null) {
            return null;
        }

        try {
            Optional<ModelEntry> entry = registryService.getModel(vlmModelId);
            if (entry.isPresent() && entry.get().getPreprocessor() != null) {
                ImagePreprocessorConfig regConfig = entry.get().getPreprocessor();
                PreprocessorConfig dl4jConfig = convertRegistryToDl4jConfig(regConfig);
                return VLMImagePreprocessor.fromConfig(dl4jConfig);
            }
        } catch (Exception e) {
            logger.debug("Could not look up preprocessor from registry for {}: {}", vlmModelId, e.getMessage());
        }

        return null;
    }

    /**
     * Attempts to resolve a VisionEncoderIOConfig from the model registry.
     * If the model has persisted vision encoder IO config fields (auto-probed at promotion
     * or user-overridden via staging UI), they are used to construct a VisionEncoderIOConfig
     * that overrides auto-discovery from the SameDiff graph.
     *
     * @param vlmModelId the model ID to look up
     * @return a VisionEncoderIOConfig if registry config exists with at least pixelValuesName, null otherwise
     */
    private VisionEncoderIOConfig resolveVisionEncoderIOConfigFromRegistry(String vlmModelId) {
        if (registryService == null || vlmModelId == null) {
            return null;
        }

        try {
            Optional<ModelEntry> entry = registryService.getModel(vlmModelId);
            if (entry.isPresent() && entry.get().getMetadata() != null) {
                ModelMetadata meta = entry.get().getMetadata();
                String pixelValuesName = meta.getVisionEncoderPixelValuesName();
                String primaryOutputName = meta.getVisionEncoderPrimaryOutputName();
                java.util.List<String> outputNames = meta.getVisionEncoderOutputNames();

                // Only use registry config if at least the primary input name is set
                if (pixelValuesName != null && !pixelValuesName.isEmpty()) {
                    VisionEncoderIOConfig.VisionEncoderIOConfigBuilder builder = VisionEncoderIOConfig.builder()
                            .pixelValuesName(pixelValuesName);

                    if (meta.getVisionEncoderPixelAttentionMaskName() != null) {
                        builder.pixelAttentionMaskName(meta.getVisionEncoderPixelAttentionMaskName());
                    }
                    if (primaryOutputName != null && !primaryOutputName.isEmpty()) {
                        builder.primaryOutputName(primaryOutputName);
                    }
                    if (outputNames != null && !outputNames.isEmpty()) {
                        builder.outputNames(outputNames.toArray(new String[0]));
                    }

                    VisionEncoderIOConfig ioConfig = builder.build();
                    logger.info("Resolved VisionEncoderIOConfig from registry for {}: pixelValues={}, primaryOutput={}, outputs={}",
                            vlmModelId, ioConfig.getPixelValuesName(), ioConfig.getPrimaryOutputName(),
                            java.util.Arrays.asList(ioConfig.getOutputNames()));
                    return ioConfig;
                }
            }
        } catch (Exception e) {
            logger.debug("Could not look up VisionEncoderIOConfig from registry for {}: {}", vlmModelId, e.getMessage());
        }

        return null;
    }

    /**
     * Converts a Kompile registry {@link ImagePreprocessorConfig} to a DL4J
     * {@link PreprocessorConfig} for use with {@link VLMImagePreprocessor}.
     */
    private PreprocessorConfig convertRegistryToDl4jConfig(ImagePreprocessorConfig regConfig) {
        PreprocessorConfig config = new PreprocessorConfig();
        config.setImageProcessorType(regConfig.getImageProcessorType());
        config.setDoResize(regConfig.isDoResize());

        // Set size from registry fields
        if (regConfig.getSizeHeight() != null || regConfig.getSizeWidth() != null
                || regConfig.getSizeShortestEdge() != null || regConfig.getSizeLongestEdge() != null) {
            PreprocessorConfig.ImageSize size = new PreprocessorConfig.ImageSize();
            if (regConfig.getSizeHeight() != null) {
                size.setHeight(regConfig.getSizeHeight());
            }
            if (regConfig.getSizeWidth() != null) {
                size.setWidth(regConfig.getSizeWidth());
            }
            if (regConfig.getSizeShortestEdge() != null) {
                size.setShortestEdge(regConfig.getSizeShortestEdge());
            }
            if (regConfig.getSizeLongestEdge() != null) {
                size.setLongestEdge(regConfig.getSizeLongestEdge());
            }
            config.setSize(size);
        }

        if (regConfig.getResample() != null) {
            config.setResample(regConfig.getResample());
        }

        config.setDoRescale(regConfig.isDoRescale());
        config.setRescaleFactor(regConfig.getRescaleFactor());
        config.setDoNormalize(regConfig.isDoNormalize());

        if (regConfig.getImageMean() != null) {
            config.setImageMean(regConfig.getImageMean());
        }
        if (regConfig.getImageStd() != null) {
            config.setImageStd(regConfig.getImageStd());
        }

        config.setDoConvertRgb(regConfig.isDoConvertRgb());
        config.setDoCenterCrop(regConfig.isDoCenterCrop());

        if (regConfig.getCropSizeHeight() != null || regConfig.getCropSizeWidth() != null) {
            PreprocessorConfig.ImageSize cropSize = new PreprocessorConfig.ImageSize();
            if (regConfig.getCropSizeHeight() != null) {
                cropSize.setHeight(regConfig.getCropSizeHeight());
            }
            if (regConfig.getCropSizeWidth() != null) {
                cropSize.setWidth(regConfig.getCropSizeWidth());
            }
            config.setCropSize(cropSize);
        }

        config.setDoPad(regConfig.isDoPad());

        if (regConfig.getPadSizeHeight() != null || regConfig.getPadSizeWidth() != null) {
            PreprocessorConfig.ImageSize padSize = new PreprocessorConfig.ImageSize();
            if (regConfig.getPadSizeHeight() != null) {
                padSize.setHeight(regConfig.getPadSizeHeight());
            }
            if (regConfig.getPadSizeWidth() != null) {
                padSize.setWidth(regConfig.getPadSizeWidth());
            }
            config.setPadSize(padSize);
        }

        if (regConfig.getPatchSize() != null) {
            config.setPatchSize(regConfig.getPatchSize());
        }
        config.setNumChannels(regConfig.getNumChannels());

        return config;
    }

    /**
     * Resolves the image preprocessor using a fallback chain:
     * <ol>
     *   <li>Model-bundled preprocessor from {@code vlm.getImagePreprocessor()}</li>
     *   <li>{@code preprocessor_config.json} in the model directory via
     *       {@code VLMImagePreprocessor.fromConfig(File)}</li>
     *   <li>SmolDocling defaults (with warning)</li>
     * </ol>
     */
    private VLMImagePreprocessor resolveImagePreprocessor(File modelDirectory) {
        // 1. Try model-bundled preprocessor
        if (vlm != null) {
            VLMImagePreprocessor bundled = vlm.getImagePreprocessor();
            if (bundled != null) {
                logger.debug("Using model-bundled image preprocessor");
                return bundled;
            }
        }

        // 2. Load from preprocessor_config.json / processor_config.json in model directory
        if (modelDirectory != null) {
            try {
                VLMImagePreprocessor fromConfig = VLMImagePreprocessor.fromModelDirectory(modelDirectory);
                logger.info("Loaded image preprocessor from model directory: {}", modelDirectory.getAbsolutePath());
                return fromConfig;
            } catch (Exception e) {
                throw new IllegalStateException(
                        "No preprocessor config found for model '" + modelId + "' in " + modelDirectory +
                        ". Every VLM model must ship a preprocessor_config.json file.", e);
            }
        }

        throw new IllegalStateException(
                "Cannot resolve image preprocessor: no VLM model loaded and no model directory available for model '" + modelId + "'");
    }

    // ==================== SamplingConfig Builder ====================

    /**
     * Builds a {@link SamplingConfig} from pipeline configuration.
     * Supports preset names ("creative", "precise") and individual parameter overrides.
     */
    private SamplingConfig buildSamplingConfig(OcrPipelineConfig config) {
        // Check for named presets first
        String preset = config.getSamplingPreset();
        if (preset != null) {
            switch (preset.toLowerCase()) {
                case "creative": {
                    SamplingConfig sc = SamplingConfig.creative();
                    sc.setMaxNewTokens(config.getMaxNewTokens());
                    return sc;
                }
                case "precise": {
                    SamplingConfig sc = SamplingConfig.precise();
                    sc.setMaxNewTokens(config.getMaxNewTokens());
                    return sc;
                }
                default:
                    logger.warn("Unknown sampling preset '{}', using explicit parameters", preset);
                    break;
            }
        }

        // Build from individual parameters
        return SamplingConfig.builder()
                .temperature(config.getTemperature())
                .topP(config.getTopP())
                .topK(config.getTopK())
                .repetitionPenalty(config.getRepetitionPenalty())
                .doSample(config.isDoSample())
                .maxNewTokens(config.getMaxNewTokens())
                .build();
    }

    private double effectiveTokensPerSecond(GenerationResult result) {
        if (result.getLateSteadyStateTokensPerSecond() > 0) {
            return result.getLateSteadyStateTokensPerSecond();
        }
        if (result.getSteadyStateTokensPerSecond() > 0) {
            return result.getSteadyStateTokensPerSecond();
        }
        if (result.getDecodeTokensPerSecond() > 0) {
            return result.getDecodeTokensPerSecond();
        }
        return result.getTokensPerSecond();
    }

    private String throughputLabel(GenerationResult result) {
        if (result.getLateSteadyStateTokensPerSecond() > 0) {
            return "lateSteady";
        }
        if (result.getSteadyStateTokensPerSecond() > 0) {
            return "steady";
        }
        if (result.getDecodeTokensPerSecond() > 0) {
            return "decode";
        }
        return "overall";
    }

    // ==================== Helper Methods ====================

    /**
     * Extracts StructuredTable objects from DocumentStructure.
     */
    private List<StructuredTable> extractTablesFromDocStructure(DocumentStructure docStructure) {
        List<StructuredTable> tables = new ArrayList<>();

        // Collect tables from both "table" and "otsl" element types
        List<DocTagsParser.DocumentElement> tableElements = new ArrayList<>();
        tableElements.addAll(docStructure.getElementsByType("table"));
        tableElements.addAll(docStructure.getElementsByType("otsl"));

        int tableIndex = 0;
        for (DocTagsParser.DocumentElement table : tableElements) {
            List<TableCell> cells = new ArrayList<>();

            int rowIndex = 0;
            int maxColCount = 0;

            if (table.getChildren() != null) {
                for (DocTagsParser.DocumentElement row : table.getChildren()) {
                    int colIndex = 0;
                    if (row.getChildren() != null) {
                        for (DocTagsParser.DocumentElement cell : row.getChildren()) {
                            // First row is header
                            if (rowIndex == 0) {
                                cells.add(TableCell.header(rowIndex, colIndex, cell.getContent()));
                            } else {
                                cells.add(TableCell.of(rowIndex, colIndex, cell.getContent()));
                            }
                            colIndex++;
                        }
                        maxColCount = Math.max(maxColCount, colIndex);
                    }
                    rowIndex++;
                }
            }

            if (!cells.isEmpty()) {
                tables.add(StructuredTable.builder()
                        .id(UUID.randomUUID().toString())
                        .tableIndex(tableIndex)
                        .rowCount(rowIndex)
                        .columnCount(maxColCount)
                        .cells(cells)
                        .headerRows(rowIndex > 0 ? List.of(0) : List.of())
                        .build());
                tableIndex++;
            }
        }

        return tables;
    }

    /**
     * Converts a BufferedImage to INDArray.
     */
    private INDArray bufferedImageToINDArray(BufferedImage image) {
        int h = image.getHeight();
        int w = image.getWidth();

        int[] pixels = image.getRGB(0, 0, w, h, null, 0, w);

        float[] data = new float[3 * h * w];

        int hwSize = h * w;
        for (int i = 0; i < pixels.length; i++) {
            int rgb = pixels[i];
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;

            // NCHW format
            data[i] = r / 255.0f;
            data[hwSize + i] = g / 255.0f;
            data[2 * hwSize + i] = b / 255.0f;
        }

        return Nd4j.create(data, new int[]{1, 3, h, w}, 'c');
    }

    /**
     * Checks if a specific model is loaded.
     */
    public boolean isModelLoaded(String vlmModelId) {
        return loaded && vlmModelId.equals(this.modelId);
    }

    /**
     * Gets the underlying VisionLanguageModel from samediff-vlm.
     * This provides access to lower-level VLM features.
     *
     * @return the VisionLanguageModel or null if not loaded
     */
    public VisionLanguageModel getVisionLanguageModel() {
        return vlm;
    }

    /**
     * Gets the image preprocessor.
     *
     * @return the image preprocessor or null if not loaded
     */
    public VLMImagePreprocessor getImagePreprocessor() {
        return imagePreprocessor;
    }

    /**
     * Gets the current DocTags parser.
     *
     * @return the parser
     */
    public DocTagsParser getDocTagsParser() {
        return docTagsParser;
    }

    /**
     * Sets the DocTags parser to use for parsing VLM output.
     *
     * @param parser the parser to use
     */
    public void setDocTagsParser(DocTagsParser parser) {
        this.docTagsParser = parser;
    }

    /**
     * Render a PDF page to a BufferedImage, with fallback for corrupt font dictionaries.
     *
     * <p>Some scanned/image-based PDFs (e.g., D&D Monster Manual) have corrupt font
     * metadata that causes {@link PDFRenderer#renderImageWithDPI} to throw
     * {@code IOException: Missing descendant font dictionary}. The actual page content
     * is raster images embedded as XObjects — the fonts are irrelevant.</p>
     *
     * <p>When standard rendering fails, this method extracts embedded images directly
     * from the page's XObject resources and composites them onto a white canvas sized
     * to the page's media box at the requested DPI.</p>
     *
     * @param renderer the PDF renderer (used for the fast path)
     * @param document the PDF document (used for fallback extraction)
     * @param pageIndex 0-based page index
     * @param dpi rendering DPI
     * @return the rendered page image
     * @throws IOException if both rendering and fallback extraction fail
     */
    private BufferedImage renderPageSafe(PDFRenderer renderer, PDDocument document,
                                         int pageIndex, float dpi) throws IOException {
        try {
            return renderer.renderImageWithDPI(pageIndex, dpi);
        } catch (IOException e) {
            logger.warn("Page {} standard rendering failed ({}), attempting embedded image extraction",
                    pageIndex + 1, e.getMessage());
            return extractEmbeddedPageImage(document, pageIndex, dpi);
        }
    }

    /**
     * Extract embedded images from a PDF page's XObject resources and composite them
     * onto a single BufferedImage. This is the fallback path for pages with corrupt
     * font dictionaries where standard rendering fails.
     */
    private BufferedImage extractEmbeddedPageImage(PDDocument document, int pageIndex,
                                                    float dpi) throws IOException {
        PDPage page = document.getPage(pageIndex);
        PDResources resources = page.getResources();
        if (resources == null) {
            throw new IOException("Page " + (pageIndex + 1) + " has no resources for image extraction");
        }

        // Collect all embedded images from XObjects
        List<BufferedImage> images = new ArrayList<>();
        for (COSName name : resources.getXObjectNames()) {
            PDXObject xobj = resources.getXObject(name);
            if (xobj instanceof PDImageXObject) {
                images.add(((PDImageXObject) xobj).getImage());
            }
        }

        if (images.isEmpty()) {
            throw new IOException("Page " + (pageIndex + 1) + " has no embedded images to extract");
        }

        // Single image — return directly (most common case for scanned pages)
        if (images.size() == 1) {
            logger.info("Page {}: extracted 1 embedded image ({}x{})",
                    pageIndex + 1, images.get(0).getWidth(), images.get(0).getHeight());
            return images.get(0);
        }

        // Multiple images — composite onto a canvas sized to the page media box
        float scale = dpi / 72f;
        int canvasWidth = Math.round(page.getMediaBox().getWidth() * scale);
        int canvasHeight = Math.round(page.getMediaBox().getHeight() * scale);
        BufferedImage canvas = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, canvasWidth, canvasHeight);

        // Draw all images scaled to fill the canvas (best effort for multi-image pages)
        for (BufferedImage img : images) {
            g.drawImage(img, 0, 0, canvasWidth, canvasHeight, null);
        }
        g.dispose();

        logger.info("Page {}: composited {} embedded images onto {}x{} canvas",
                pageIndex + 1, images.size(), canvasWidth, canvasHeight);
        return canvas;
    }
}
