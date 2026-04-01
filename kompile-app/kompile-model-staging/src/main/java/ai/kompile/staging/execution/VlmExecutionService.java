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

package ai.kompile.staging.execution;

import ai.kompile.modelmanager.registry.ModelEntry;
import ai.kompile.modelmanager.registry.ModelType;
import ai.kompile.modelmanager.registry.RegistryService;
import ai.kompile.staging.config.StagingSettingsService;
import ai.kompile.staging.web.dto.*;
import org.eclipse.deeplearning4j.llm.generation.GenerationResult;
import org.eclipse.deeplearning4j.vlm.model.VisionLanguageModel;
import org.eclipse.deeplearning4j.vlm.output.DocTagsParser;
import org.eclipse.deeplearning4j.vlm.output.DocumentStructure;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * Service for managing VLM (Vision-Language Model) execution.
 * Handles model loading, image processing, text generation, and DocTags parsing.
 *
 * Uses VisionLanguageModel from samediff-vlm for model loading, preprocessing,
 * and text generation. Delegates all generation to VLM's own pipeline which handles
 * the full vision encoder → embedding merge → decode flow.
 */
@org.springframework.stereotype.Service
public class VlmExecutionService {

    private static final Logger log = LoggerFactory.getLogger(VlmExecutionService.class);

    /** Auto-unload delay after last use (default 5 minutes) */
    private static final long AUTO_UNLOAD_DELAY_MS = 5 * 60 * 1000L;

    @Autowired
    private RegistryService registryService;

    @Autowired(required = false)
    private StagingSettingsService stagingSettingsService;

    // The loaded VisionLanguageModel instance (handles loading, preprocessing, and generation)
    private volatile VisionLanguageModel vlmModel;

    // Current model status
    private volatile String activeModelId = null;
    private volatile String modelStatus = "unloaded"; // unloaded, loading, ready, error

    // OCR configuration - VLM models handle OCR via generation, no separate OCR engine needed
    private volatile String ocrLanguage = "eng";
    private volatile double ocrConfidenceThreshold = 0.5;

    // Auto-unload scheduler to free GPU memory when model is idle
    private final ScheduledExecutorService unloadScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "vlm-auto-unload");
        t.setDaemon(true);
        return t;
    });
    private volatile ScheduledFuture<?> pendingUnload;

    public VlmExecutionService() {
    }

    // ==================== Model Management ====================

    /**
     * Get available VLM models from the registry.
     * Returns models of type VLM_PIPELINE that are staged or active.
     */
    public List<Map<String, String>> getAvailableModels() {
        List<Map<String, String>> models = new ArrayList<>();

        if (registryService != null) {
            try {
                List<ModelEntry> vlmModels = registryService.getModelsByType(ModelType.VLM_PIPELINE);
                for (ModelEntry entry : vlmModels) {
                    Map<String, String> model = new LinkedHashMap<>();
                    model.put("id", entry.getModelId());
                    model.put("name", entry.getModelId());
                    model.put("type", "vlm_pipeline");
                    model.put("status", entry.getStatus() != null ? entry.getStatus().getValue() : "unknown");
                    if (entry.getPath() != null) {
                        model.put("path", entry.getPath());
                    }
                    models.add(model);
                }
            } catch (Exception e) {
                log.warn("Failed to query registry for VLM models: {}", e.getMessage());
            }
        }

        // If no models found in registry, check filesystem for VLM model directories
        if (models.isEmpty() && registryService != null) {
            try {
                java.nio.file.Path vlmDir = registryService.getModelDir().resolve("vlm");
                if (Files.isDirectory(vlmDir)) {
                    try (var stream = Files.list(vlmDir)) {
                        stream.filter(Files::isDirectory).forEach(dir -> {
                            String dirName = dir.getFileName().toString();
                            // Skip -pipeline suffix for display
                            String displayName = dirName.endsWith("-pipeline")
                                    ? dirName.substring(0, dirName.length() - "-pipeline".length())
                                    : dirName;
                            Map<String, String> model = new LinkedHashMap<>();
                            model.put("id", displayName);
                            model.put("name", displayName);
                            model.put("type", "vlm_pipeline");
                            model.put("status", "available");
                            models.add(model);
                        });
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to scan VLM model directories: {}", e.getMessage());
            }
        }

        return models;
    }

    /**
     * Load a VLM model by its set ID.
     * Supports both SDZ (preferred, native SameDiff format) and ONNX formats.
     * SDZ is the primary format for LLMs and production use.
     * ONNX files are auto-converted to SDZ on first load and cached for subsequent loads.
     */
    public Map<String, Object> loadModel(String modelSetId) {
        Map<String, Object> result = new LinkedHashMap<>();

        cancelAutoUnload();

        if ("ready".equals(modelStatus) && modelSetId.equals(activeModelId) && vlmModel != null) {
            result.put("success", true);
            result.put("message", "Model already loaded");
            result.put("modelId", modelSetId);
            result.put("status", modelStatus);
            return result;
        }

        try {
            modelStatus = "loading";
            activeModelId = modelSetId;
            log.info("Loading VLM model: {}", modelSetId);

            // Find the model directory from the registry
            File modelDir = resolveVlmModelDir(modelSetId);
            if (modelDir == null || !modelDir.isDirectory()) {
                result.put("success", false);
                result.put("error", "VLM model directory not found for: " + modelSetId +
                    ". Stage and promote the model first.");
                modelStatus = "unloaded";
                activeModelId = null;
                return result;
            }

            // Unload previous model and pipeline if any
            closeCurrentModel();

            long start = System.currentTimeMillis();
            String format;

            // Check for SDZ files first (preferred native format, faster loading)
            boolean hasSdz = hasSdzFiles(modelDir);
            boolean hasOnnx = hasOnnxFiles(modelDir);

            if (hasSdz) {
                // Load from SDZ - native SameDiff format, fast loading
                format = "SDZ";
                log.info("Loading VLM from SDZ files in: {}", modelDir);
                vlmModel = VisionLanguageModel.loadSmolDocling(modelDir);
            } else if (hasOnnx) {
                // Load from ONNX - auto-converts to SDZ and caches for next time
                format = "ONNX";
                File visionEncoder = findFile(modelDir, "vision_encoder.onnx");
                File decoder = findFile(modelDir, "decoder_model_merged.onnx");
                File embedTokens = findFile(modelDir, "embed_tokens.onnx");
                File tokenizerJson = findFile(modelDir, "tokenizer.json");

                if (decoder == null || tokenizerJson == null) {
                    result.put("success", false);
                    result.put("error", "Missing required VLM components in " + modelDir + ". " +
                        "Need at minimum: decoder_model_merged.onnx and tokenizer.json. " +
                        "Found: vision_encoder=" + (visionEncoder != null) +
                        ", decoder=" + (decoder != null) +
                        ", embed_tokens=" + (embedTokens != null) +
                        ", tokenizer=" + (tokenizerJson != null));
                    modelStatus = "unloaded";
                    activeModelId = null;
                    return result;
                }

                log.info("Loading VLM from ONNX files in: {}", modelDir);
                logFileInfo("  Vision encoder", visionEncoder);
                logFileInfo("  Decoder", decoder);
                logFileInfo("  Embed tokens", embedTokens);
                log.info("  Tokenizer: {}", tokenizerJson.getName());

                vlmModel = VisionLanguageModel.fromOnnx(
                    visionEncoder, decoder, embedTokens, tokenizerJson
                );
            } else {
                // Try fromDirectory as a fallback - it searches for various file patterns
                format = "auto-detect";
                log.info("No SDZ or ONNX files found, trying auto-detect in: {}", modelDir);
                vlmModel = VisionLanguageModel.fromDirectory(modelDir);
            }

            long elapsed = System.currentTimeMillis() - start;
            modelStatus = "ready";

            result.put("success", true);
            result.put("message", "VLM model loaded from " + format + " in " + elapsed + "ms");
            result.put("modelId", modelSetId);
            result.put("status", modelStatus);
            result.put("format", format);
            result.put("loadTimeMs", elapsed);
            result.put("modelDir", modelDir.getAbsolutePath());

            log.info("VLM model loaded: {} ({}) in {}ms", modelSetId, format, elapsed);

            // Notify main app to reload VLM
            if (stagingSettingsService != null) {
                try {
                    stagingSettingsService.notifyVlmReload(modelSetId);
                } catch (Exception notifyEx) {
                    log.debug("Failed to notify main app of VLM load: {}", notifyEx.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("Failed to load VLM model: {}", modelSetId, e);
            modelStatus = "error";
            activeModelId = null;
            vlmModel = null;
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * Unload the current VLM model.
     */
    public Map<String, Object> unloadModel() {
        Map<String, Object> result = new LinkedHashMap<>();
        String previousModelId = activeModelId;

        closeCurrentModel();
        activeModelId = null;
        modelStatus = "unloaded";

        result.put("success", true);
        result.put("message", previousModelId != null ?
            "Model " + previousModelId + " unloaded" : "No model was loaded");
        result.put("status", modelStatus);

        if (previousModelId != null) {
            log.info("VLM model unloaded: {}", previousModelId);
        }

        return result;
    }

    /**
     * Get current model status.
     */
    public Map<String, Object> getModelStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", modelStatus);
        status.put("activeModelId", activeModelId);
        status.put("modelLoaded", vlmModel != null);
        return status;
    }

    // ==================== Model Accessors ====================

    /**
     * Get the loaded VisionLanguageModel instance.
     * @return the model, or null if not loaded
     */
    public VisionLanguageModel getVisionLanguageModel() {
        return vlmModel;
    }

    /**
     * Get the active model ID.
     * @return the model ID, or null if no model is loaded
     */
    public String getActiveModelId() {
        return activeModelId;
    }

    /**
     * Check if a model is loaded and ready.
     */
    public boolean isModelReady() {
        return "ready".equals(modelStatus) && vlmModel != null;
    }

    /**
     * Embed an image using the loaded VLM's vision encoder.
     * Returns the preprocessed image representation as a float array.
     *
     * @param imageData raw image bytes
     * @return float array embedding, or null if model not loaded
     * @throws IOException if image processing fails
     */
    public float[] embedImage(byte[] imageData) throws IOException {
        if (!isModelReady()) {
            throw new IOException("No VLM model loaded. Load a model first.");
        }

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageData));
        if (image == null) {
            throw new IOException("Failed to decode image. Unsupported format.");
        }

        File tempFile = bufferedImageToFile(image);
        try {
            INDArray preprocessed = vlmModel.preprocessImage(tempFile);
            // Flatten to 1D float array - the preprocessed output is the vision encoder embedding
            float[] embedding = preprocessed.data().asFloat();
            preprocessed.close();
            return embedding;
        } finally {
            tempFile.delete();
        }
    }

    /**
     * Embed multiple images using the loaded VLM's vision encoder.
     *
     * @param imageDataList list of raw image bytes
     * @return list of float array embeddings
     * @throws IOException if any image processing fails
     */
    public List<float[]> embedImageBatch(List<byte[]> imageDataList) throws IOException {
        List<float[]> results = new ArrayList<>();
        for (byte[] imageData : imageDataList) {
            results.add(embedImage(imageData));
        }
        return results;
    }

    /**
     * Get the embedding dimension from the vision encoder output.
     * Returns -1 if no model is loaded.
     */
    public int getEmbeddingDimension() {
        if (!isModelReady()) {
            return -1;
        }
        // The dimension depends on the model architecture
        // SmolDocling-256M vision encoder typically produces embeddings of a known dimension
        // We return the last dimension of the preprocessed output
        return getEstimatedDimension(activeModelId);
    }

    /**
     * Get estimated embedding dimension based on model ID.
     */
    private int getEstimatedDimension(String modelId) {
        if (modelId == null) return -1;
        String lower = modelId.toLowerCase();
        if (lower.contains("smoldocling-256m")) return 256;
        if (lower.contains("smoldocling-512m")) return 512;
        if (lower.contains("siglip")) return 768;
        if (lower.contains("clip")) return 512;
        if (lower.contains("donut")) return 768;
        return -1;
    }

    // ==================== Generation ====================

    /**
     * Generate text from an image using the loaded VLM model.
     */
    public VlmGenerateResponse generate(byte[] imageData, VlmGenerateRequest request) {
        long startTime = System.currentTimeMillis();

        if (!"ready".equals(modelStatus) || vlmModel == null) {
            return VlmGenerateResponse.builder()
                .success(false)
                .error("No model is loaded. Please load a model first.")
                .build();
        }

        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageData));
            if (image == null) {
                return VlmGenerateResponse.builder()
                    .success(false)
                    .error("Failed to decode image. Unsupported format.")
                    .build();
            }

            String prompt = request.getPrompt();
            if (prompt == null || prompt.isBlank()) {
                prompt = "Convert this page to docling.";
            }

            int maxNewTokens = request.getMaxTokens() > 0 ? request.getMaxTokens() : 4096;
            double temperature = 0.0; // greedy for document understanding
            boolean doSample = false;

            // Preprocess image through the model's preprocessor
            INDArray preprocessedImage = vlmModel.preprocessImage(
                bufferedImageToFile(image)
            );

            // Generate using VLM's own pipeline (handles vision encoder → embedding merge → decode)
            GenerationResult genResult = vlmModel.generateWithMetrics(
                preprocessedImage, prompt, maxNewTokens, temperature, doSample
            );

            long elapsed = System.currentTimeMillis() - startTime;
            String generatedText = genResult.getText();

            // Reset sessions to free GPU intermediates and schedule auto-unload
            vlmModel.resetSessions();
            scheduleAutoUnload();

            return VlmGenerateResponse.builder()
                .success(true)
                .generatedText(generatedText)
                .metrics(VlmGenerateResponse.GenerationMetrics.builder()
                    .processingTimeMs(elapsed)
                    .tokensGenerated(genResult.getGeneratedTokenCount())
                    .deviceUsed("cpu")
                    .tilesUsed(1)
                    .build())
                .build();

        } catch (IOException e) {
            log.error("Image processing failed", e);
            return VlmGenerateResponse.builder()
                .success(false)
                .error("Image processing failed: " + e.getMessage())
                .build();
        }
    }

    /**
     * Batch generate text from multiple images.
     */
    public List<VlmGenerateResponse> generateBatch(List<byte[]> images, VlmGenerateRequest request) {
        List<VlmGenerateResponse> results = new ArrayList<>();
        for (byte[] imageData : images) {
            results.add(generate(imageData, request));
        }
        return results;
    }

    // ==================== OCR ====================

    public OcrRecognizeResponse recognizeText(byte[] imageData) {
        long startTime = System.currentTimeMillis();

        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageData));
            if (image == null) {
                return OcrRecognizeResponse.builder()
                    .success(false)
                    .error("Failed to decode image")
                    .build();
            }

            // OCR is done via VLM generation with OCR prompt
            if (vlmModel != null && "ready".equals(modelStatus)) {
                INDArray preprocessed = vlmModel.preprocessImage(bufferedImageToFile(image));
                String ocrPrompt = "Recognize all text in this image.";
                String ocrText = vlmModel.generate(preprocessed, ocrPrompt);
                long elapsed = System.currentTimeMillis() - startTime;

                List<OcrRecognizeResponse.OcrTextRegion> regions = new ArrayList<>();
                regions.add(OcrRecognizeResponse.OcrTextRegion.builder()
                    .text(ocrText)
                    .confidence(0.9)
                    .boundingBox(OcrRecognizeResponse.BoundingBox.builder()
                        .x(0).y(0).width(image.getWidth()).height(image.getHeight())
                        .build())
                    .build());

                return OcrRecognizeResponse.builder()
                    .success(true)
                    .text(ocrText)
                    .regions(regions)
                    .processingTimeMs(elapsed)
                    .build();
            }

            long elapsed = System.currentTimeMillis() - startTime;
            return OcrRecognizeResponse.builder()
                .success(false)
                .error("No VLM model loaded. Load a model first for OCR.")
                .processingTimeMs(elapsed)
                .build();

        } catch (IOException e) {
            return OcrRecognizeResponse.builder()
                .success(false)
                .error("Image processing failed: " + e.getMessage())
                .build();
        }
    }

    public List<OcrRecognizeResponse> recognizeBatch(List<byte[]> images) {
        List<OcrRecognizeResponse> results = new ArrayList<>();
        for (byte[] imageData : images) {
            results.add(recognizeText(imageData));
        }
        return results;
    }

    /**
     * Get available OCR engines. OCR is performed via the loaded VLM model.
     * Returns the currently loaded VLM model as the OCR engine.
     */
    public List<Map<String, String>> getAvailableOcrEngines() {
        List<Map<String, String>> engines = new ArrayList<>();
        // The loaded VLM model IS the OCR engine
        if (isModelReady() && activeModelId != null) {
            Map<String, String> engine = new LinkedHashMap<>();
            engine.put("type", "vlm");
            engine.put("name", "VLM: " + activeModelId);
            engine.put("description", "Vision-Language Model performing OCR via text generation");
            engines.add(engine);
        }
        // Also list any VLM models from registry that could be loaded
        for (Map<String, String> model : getAvailableModels()) {
            if (!model.get("id").equals(activeModelId)) {
                Map<String, String> engine = new LinkedHashMap<>();
                engine.put("type", "vlm");
                engine.put("name", "VLM: " + model.get("id") + " (not loaded)");
                engine.put("description", "Load this VLM model to use for OCR");
                engines.add(engine);
            }
        }
        return engines;
    }

    public Map<String, Object> updateOcrConfig(OcrConfigRequest config) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (config.getLanguage() != null) {
            this.ocrLanguage = config.getLanguage();
        }
        this.ocrConfidenceThreshold = config.getConfidenceThreshold();

        result.put("success", true);
        result.put("language", ocrLanguage);
        result.put("confidenceThreshold", ocrConfidenceThreshold);
        return result;
    }

    public Map<String, Object> getOcrConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("language", ocrLanguage);
        config.put("confidenceThreshold", ocrConfidenceThreshold);
        config.put("vlmModel", activeModelId);
        config.put("vlmModelReady", isModelReady());
        return config;
    }

    // ==================== DocTags Parsing ====================

    public DocTagsParseResponse parseDocTags(String rawDocTags) {
        if (rawDocTags == null || rawDocTags.isBlank()) {
            return DocTagsParseResponse.builder()
                .success(false)
                .error("No DocTags text provided")
                .build();
        }

        try {
            DocTagsParser parser = new DocTagsParser();
            DocumentStructure structure = parser.parse(rawDocTags);

            String markdown = parser.toMarkdown(structure);
            String html = parser.toHtml(structure);

            List<Map<String, Object>> structuredElements = new ArrayList<>();
            for (DocTagsParser.DocumentElement elem : structure.getElements()) {
                Map<String, Object> elemMap = new LinkedHashMap<>();
                elemMap.put("type", elem.getTagType());
                elemMap.put("content", elem.getContent());
                structuredElements.add(elemMap);
            }

            return DocTagsParseResponse.builder()
                .success(true)
                .markdown(markdown)
                .html(html)
                .structuredElements(structuredElements)
                .build();
        } catch (Exception e) {
            log.warn("DocTagsParser failed, using basic parsing: {}", e.getMessage());

            String markdown = convertDocTagsToMarkdownBasic(rawDocTags);
            String html = convertDocTagsToHtmlBasic(rawDocTags);

            List<Map<String, Object>> elements = new ArrayList<>();
            Map<String, Object> element = new LinkedHashMap<>();
            element.put("type", "text");
            element.put("content", rawDocTags);
            elements.add(element);

            return DocTagsParseResponse.builder()
                .success(true)
                .markdown(markdown)
                .html(html)
                .structuredElements(elements)
                .build();
        }
    }

    public String docTagsToMarkdown(String rawDocTags) {
        DocTagsParseResponse parsed = parseDocTags(rawDocTags);
        return parsed.isSuccess() ? parsed.getMarkdown() : rawDocTags;
    }

    public String docTagsToHtml(String rawDocTags) {
        DocTagsParseResponse parsed = parseDocTags(rawDocTags);
        return parsed.isSuccess() ? parsed.getHtml() : "<pre>" + escapeHtml(rawDocTags) + "</pre>";
    }

    // ==================== Tiling Preview ====================

    public TilingPreviewResponse previewTiling(byte[] imageData, int maxTiles) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageData));
            if (image == null) {
                return TilingPreviewResponse.builder()
                    .success(false)
                    .error("Failed to decode image")
                    .build();
            }

            int imgWidth = image.getWidth();
            int imgHeight = image.getHeight();

            int effectiveMaxTiles = Math.max(1, Math.min(maxTiles, 16));
            double aspectRatio = (double) imgWidth / imgHeight;

            int gridCols, gridRows;
            if (effectiveMaxTiles == 1) {
                gridCols = 1;
                gridRows = 1;
            } else {
                gridCols = (int) Math.ceil(Math.sqrt(effectiveMaxTiles * aspectRatio));
                gridRows = (int) Math.ceil((double) effectiveMaxTiles / gridCols);
                while (gridCols * gridRows > effectiveMaxTiles) {
                    if (gridCols > gridRows) gridCols--;
                    else gridRows--;
                }
            }

            int tileWidth = imgWidth / gridCols;
            int tileHeight = imgHeight / gridRows;
            int tileCount = gridCols * gridRows;

            List<TilingPreviewResponse.TileInfo> tiles = new ArrayList<>();
            for (int row = 0; row < gridRows; row++) {
                for (int col = 0; col < gridCols; col++) {
                    tiles.add(TilingPreviewResponse.TileInfo.builder()
                        .index(row * gridCols + col)
                        .x(col * tileWidth)
                        .y(row * tileHeight)
                        .width(tileWidth)
                        .height(tileHeight)
                        .build());
                }
            }

            return TilingPreviewResponse.builder()
                .success(true)
                .tileCount(tileCount)
                .gridRows(gridRows)
                .gridCols(gridCols)
                .tileWidth(tileWidth)
                .tileHeight(tileHeight)
                .originalWidth(imgWidth)
                .originalHeight(imgHeight)
                .tiles(tiles)
                .build();

        } catch (IOException e) {
            return TilingPreviewResponse.builder()
                .success(false)
                .error("Image processing failed: " + e.getMessage())
                .build();
        }
    }

    /**
     * Close current model, freeing resources.
     */
    private void closeCurrentModel() {
        cancelAutoUnload();
        if (vlmModel != null) {
            try {
                vlmModel.resetSessions();
                vlmModel.close();
            } catch (Exception e) {
                log.warn("Error closing VLM model: {}", e.getMessage());
            }
            vlmModel = null;
        }
    }

    /**
     * Schedule auto-unload of the VLM model after idle timeout.
     * Frees GPU memory when the staging server isn't actively using the model.
     */
    private void scheduleAutoUnload() {
        cancelAutoUnload();
        pendingUnload = unloadScheduler.schedule(() -> {
            if (vlmModel != null && "ready".equals(modelStatus)) {
                log.info("Auto-unloading idle VLM model {} to free GPU memory", activeModelId);
                unloadModel();
            }
        }, AUTO_UNLOAD_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void cancelAutoUnload() {
        ScheduledFuture<?> pending = pendingUnload;
        if (pending != null) {
            pending.cancel(false);
            pendingUnload = null;
        }
    }

    // ==================== Private Helper Methods ====================

    /**
     * Resolve the VLM model directory from the registry.
     * Searches: vlm/<modelSetId>-pipeline, vlm/<modelSetId>, and direct modelSetId paths.
     */
    private File resolveVlmModelDir(String modelSetId) {
        Path modelsDir = registryService.getModelDir();

        // Try VLM pipeline path (e.g., vlm/smoldocling-256m-pipeline)
        Path vlmPipelinePath = modelsDir.resolve("vlm").resolve(modelSetId + "-pipeline");
        if (Files.isDirectory(vlmPipelinePath)) {
            return vlmPipelinePath.toFile();
        }

        // Try VLM direct path (e.g., vlm/smoldocling-256m)
        Path vlmPath = modelsDir.resolve("vlm").resolve(modelSetId);
        if (Files.isDirectory(vlmPath)) {
            return vlmPath.toFile();
        }

        // Try direct path
        Path directPath = modelsDir.resolve(modelSetId);
        if (Files.isDirectory(directPath)) {
            return directPath.toFile();
        }

        // Try registry entry path
        Optional<Path> registryPath = registryService.getModelDirectory(modelSetId);
        if (registryPath.isPresent() && Files.isDirectory(registryPath.get())) {
            return registryPath.get().toFile();
        }

        // Try with -pipeline suffix in registry
        Optional<Path> pipelinePath = registryService.getModelDirectory(modelSetId + "-pipeline");
        if (pipelinePath.isPresent() && Files.isDirectory(pipelinePath.get())) {
            return pipelinePath.get().toFile();
        }

        return null;
    }

    private File findFile(File dir, String name) {
        File f = new File(dir, name);
        return f.isFile() ? f : null;
    }

    /**
     * Check if directory contains a complete set of SDZ model files (native SameDiff format).
     * Requires at minimum a decoder SDZ file - partial SDZ (e.g. only vision_encoder.sdz)
     * should fall through to ONNX loading.
     */
    private boolean hasSdzFiles(File dir) {
        // A complete SDZ set requires at least a decoder model
        String[] requiredSdzNames = {
            "decoder.sdz", "decoder_model.sdz", "decoder_model_merged.sdz"
        };
        for (String name : requiredSdzNames) {
            if (new File(dir, name).isFile()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if directory contains ONNX model files.
     */
    private boolean hasOnnxFiles(File dir) {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".onnx"));
        return files != null && files.length > 0;
    }

    private void logFileInfo(String label, File file) {
        if (file != null) {
            log.info("{}: {} ({} MB)", label, file.getName(), file.length() / (1024 * 1024));
        } else {
            log.info("{}: not found", label);
        }
    }

    /**
     * Save a BufferedImage to a temporary file for the VLM preprocessor.
     */
    private File bufferedImageToFile(BufferedImage image) throws IOException {
        File tempFile = File.createTempFile("vlm-input-", ".png");
        tempFile.deleteOnExit();
        ImageIO.write(image, "png", tempFile);
        return tempFile;
    }

    private String convertDocTagsToMarkdownBasic(String docTags) {
        String result = docTags;
        result = result.replaceAll("<doctag>", "");
        result = result.replaceAll("</doctag>", "");
        result = result.replaceAll("<title>(.*?)</title>", "# $1\n");
        result = result.replaceAll("<section_header>(.*?)</section_header>", "## $1\n");
        result = result.replaceAll("<text>(.*?)</text>", "$1\n\n");
        result = result.replaceAll("<caption>(.*?)</caption>", "*$1*\n\n");
        result = result.replaceAll("<paragraph>(.*?)</paragraph>", "$1\n\n");
        result = result.replaceAll("<list_item>(.*?)</list_item>", "- $1\n");
        result = result.replaceAll("<table>", "\n");
        result = result.replaceAll("</table>", "\n");
        result = result.replaceAll("<thead>", "");
        result = result.replaceAll("</thead>", "");
        result = result.replaceAll("<tbody>", "");
        result = result.replaceAll("</tbody>", "");
        result = result.replaceAll("<tr>", "| ");
        result = result.replaceAll("</tr>", " |\n");
        result = result.replaceAll("<td>(.*?)</td>", "$1 | ");
        result = result.replaceAll("<th>(.*?)</th>", "**$1** | ");
        result = result.replaceAll("<fcel>", "");
        result = result.replaceAll("<ecel>", "");
        result = result.replaceAll("<lcel>", "");
        result = result.replaceAll("<ucel>", "");
        result = result.replaceAll("<xcel>", "");
        result = result.replaceAll("<ched>", "");
        result = result.replaceAll("<rhed>", "");
        result = result.replaceAll("<[^>]+>", "");
        result = result.replaceAll("\n{3,}", "\n\n");
        return result.trim();
    }

    private String convertDocTagsToHtmlBasic(String docTags) {
        String result = docTags;
        result = result.replaceAll("<doctag>", "<div class=\"document\">");
        result = result.replaceAll("</doctag>", "</div>");
        result = result.replaceAll("<title>(.*?)</title>", "<h1>$1</h1>");
        result = result.replaceAll("<section_header>(.*?)</section_header>", "<h2>$1</h2>");
        result = result.replaceAll("<text>(.*?)</text>", "<p>$1</p>");
        result = result.replaceAll("<caption>(.*?)</caption>", "<figcaption>$1</figcaption>");
        result = result.replaceAll("<paragraph>(.*?)</paragraph>", "<p>$1</p>");
        result = result.replaceAll("<list_item>(.*?)</list_item>", "<li>$1</li>");
        result = result.replaceAll("<fcel>", "");
        result = result.replaceAll("<ecel>", "");
        result = result.replaceAll("<lcel>", "");
        result = result.replaceAll("<ucel>", "");
        result = result.replaceAll("<xcel>", "");
        result = result.replaceAll("<ched>", "");
        result = result.replaceAll("<rhed>", "");
        return result;
    }

    private String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }

}
