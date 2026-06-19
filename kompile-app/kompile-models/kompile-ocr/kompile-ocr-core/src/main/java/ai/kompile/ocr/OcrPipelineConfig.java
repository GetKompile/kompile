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

package ai.kompile.ocr;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Configuration for OCR pipeline processing.
 * Controls which stages run and their parameters.
 *
 * <p>Supports two processing modes:</p>
 * <ul>
 *   <li><b>Traditional OCR</b>: Detection → Recognition (two-stage)</li>
 *   <li><b>VLM (Vision-Language Model)</b>: End-to-end encoder-decoder (Docling, Donut, etc.)</li>
 * </ul>
 */
@Data
@Builder
public class OcrPipelineConfig {

    // ==================== Pipeline Mode ====================

    /**
     * Whether to use VLM (Vision-Language Model) pipeline instead of traditional OCR.
     * VLM models like Docling/SmolDocling use encoder-decoder architecture.
     */
    @Builder.Default
    private boolean useVlm = false;

    /**
     * VLM model ID to use (e.g., "smoldocling-256m", "donut-base").
     */
    private String vlmModelId;

    /**
     * VLM output format.
     */
    @Builder.Default
    private VlmOutputFormat vlmOutputFormat = VlmOutputFormat.DOCTAGS;

    // ==================== VLM Component Paths (optional overrides) ====================

    /**
     * Custom path to decoder model file (e.g., decoder_model_merged.onnx or decoder.sdz).
     * If null, auto-detected from model directory.
     */
    private String vlmDecoderPath;

    /**
     * Custom path to vision encoder model file (e.g., vision_encoder.onnx).
     * If null, auto-detected from model directory.
     */
    private String vlmEncoderPath;

    /**
     * Custom path to embed tokens model file (e.g., embed_tokens.onnx).
     * If null, auto-detected from model directory.
     */
    private String vlmEmbedTokensPath;

    /**
     * Custom path to tokenizer file (e.g., tokenizer.json).
     * If null, auto-detected from model directory.
     */
    private String vlmTokenizerPath;

    /**
     * Custom path to preprocessor config file (e.g., preprocessor_config.json).
     * If null, auto-detected from model directory.
     */
    private String vlmPreprocessorConfigPath;

    // ==================== VLM Generation Parameters ====================

    /**
     * Maximum tokens to generate (for VLM).
     */
    @Builder.Default
    private int maxNewTokens = 4096;

    /**
     * Temperature for generation (0.0 = deterministic, 1.0 = creative).
     */
    @Builder.Default
    private double temperature = 0.0;

    /**
     * Top-p (nucleus sampling) for generation.
     */
    @Builder.Default
    private double topP = 1.0;

    /**
     * Top-k filtering for generation (0 = disabled).
     */
    @Builder.Default
    private int topK = 0;

    /**
     * Repetition penalty for generation (1.0 = disabled).
     */
    @Builder.Default
    private double repetitionPenalty = 1.0;

    /**
     * Sampling preset name (e.g., "creative", "precise").
     * When set, overrides individual temperature/topP/topK values.
     */
    private String samplingPreset;

    /**
     * Beam size for beam search (1 = greedy).
     */
    @Builder.Default
    private int beamSize = 1;

    /**
     * Whether to use sampling (vs greedy/beam search).
     */
    @Builder.Default
    private boolean doSample = false;

    // ==================== KV Cache Configuration ====================

    /**
     * KV cache strategy: "STATIC", "QUANTIZED", or "PAGED".
     * STATIC (default) pre-allocates dense buffers; QUANTIZED uses INT8 compression
     * for lower memory; PAGED uses block-based allocation.
     */
    @Builder.Default
    private String kvCacheStrategy = "STATIC";

    /**
     * Hard cap on KV cache sequence length. Prevents GPU OOM by limiting total
     * KV cache size. When 0 (default), sized automatically as prefillLen + maxNewTokens.
     *
     * <p>Recommended values by GPU memory:</p>
     * <ul>
     *   <li>24 GB: 1024-2048</li>
     *   <li>48 GB: 4096-8192</li>
     *   <li>80 GB: 8192-16384</li>
     * </ul>
     */
    @Builder.Default
    private int maxKvLen = 0;

    // ==================== Traditional OCR Models ====================

    /**
     * Detection model ID to use.
     */
    private String detectionModelId;

    /**
     * Recognition model ID to use.
     */
    private String recognitionModelId;

    /**
     * Table extraction model ID (null to skip).
     */
    private String tableModelId;

    /**
     * Layout model ID (null to skip).
     */
    private String layoutModelId;

    /**
     * Whether to enable table extraction.
     */
    @Builder.Default
    private boolean enableTableExtraction = true;

    /**
     * Whether to enable layout understanding.
     */
    @Builder.Default
    private boolean enableLayoutAnalysis = false;

    /**
     * Whether to enable LLM post-processing.
     */
    @Builder.Default
    private boolean enableLlmPostProcessing = false;

    /**
     * Minimum confidence threshold for detection.
     */
    @Builder.Default
    private double detectionConfidenceThreshold = 0.5;

    /**
     * Minimum confidence threshold for recognition.
     */
    @Builder.Default
    private double recognitionConfidenceThreshold = 0.0;

    /**
     * Languages to detect (ISO 639-1 codes).
     */
    @Builder.Default
    private List<String> languages = List.of("en");

    /**
     * Maximum image dimension for processing (larger images are scaled).
     */
    @Builder.Default
    private int maxImageDimension = 2048;

    /**
     * DPI for rendering PDF pages.
     */
    @Builder.Default
    private int pdfRenderDpi = 300;

    /**
     * Number of PDF pages to process in a single GPU batch.
     * Default 1 = process one page at a time (safest for GPU memory).
     * Higher values (2, 4) allow throughput optimization if GPU has enough VRAM.
     */
    @Builder.Default
    private int pageBatchSize = 1;

    /**
     * Maximum number of pages to process. 0 = all pages (default).
     */
    @Builder.Default
    private int maxPages = 0;

    /**
     * Maximum number of image tiles per page. Controls how many sub-images the
     * vision encoder processes. Lower values reduce VRAM usage and speed up inference
     * at the cost of detail. -1 = unlimited (default), 9 = recommended for SmolDocling-256M.
     * Each tile produces 64 image tokens in the prompt.
     */
    @Builder.Default
    private int maxTiles = -1;

    /**
     * CUDA pinned host memory limit in MB. 0 = system default.
     * Set via SD_CUDA_PINNED_HOST_LIMIT env var on subprocess.
     */
    @Builder.Default
    private int cudaPinnedHostLimitMb = 0;

    /**
     * Whether to process pages in parallel.
     */
    @Builder.Default
    private boolean parallelProcessing = true;

    /**
     * Number of parallel workers (0 = auto).
     */
    @Builder.Default
    private int parallelWorkers = 0;

    /**
     * Whether to include full audit trail.
     */
    @Builder.Default
    private boolean includeAuditTrail = true;

    /**
     * Whether to detect handwriting.
     */
    @Builder.Default
    private boolean detectHandwriting = false;

    /**
     * Source identifier for tracking.
     */
    private String sourceId;

    /**
     * Collection name for storage.
     */
    private String collectionName;

    /**
     * Page range to process (null = all pages).
     * Format: "1-5" or "1,3,5" or "1-3,5,7-10"
     */
    private String pageRange;

    /**
     * Creates a default configuration.
     */
    public static OcrPipelineConfig defaultConfig() {
        return OcrPipelineConfig.builder().build();
    }

    /**
     * Creates a minimal configuration (detection + recognition only).
     */
    public static OcrPipelineConfig minimal() {
        return OcrPipelineConfig.builder()
                .enableTableExtraction(false)
                .enableLayoutAnalysis(false)
                .enableLlmPostProcessing(false)
                .includeAuditTrail(false)
                .build();
    }

    /**
     * Creates a full configuration with all features.
     */
    public static OcrPipelineConfig full() {
        return OcrPipelineConfig.builder()
                .enableTableExtraction(true)
                .enableLayoutAnalysis(true)
                .enableLlmPostProcessing(true)
                .includeAuditTrail(true)
                .detectHandwriting(true)
                .build();
    }

    /**
     * Parses page range string into list of page numbers.
     */
    public List<Integer> parsePageRange(int totalPages) {
        if (pageRange == null || pageRange.isEmpty()) {
            return null; // All pages
        }

        List<Integer> pages = new java.util.ArrayList<>();
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
                if (page <= totalPages) {
                    pages.add(page);
                }
            }
        }
        return pages;
    }

    /**
     * Creates a VLM-based configuration.
     *
     * @param vlmModelId VLM model ID (e.g., "smoldocling-256m")
     * @return VLM pipeline configuration
     */
    public static OcrPipelineConfig vlm(String vlmModelId) {
        return OcrPipelineConfig.builder()
                .useVlm(true)
                .vlmModelId(vlmModelId)
                .vlmOutputFormat(VlmOutputFormat.DOCTAGS)
                .maxNewTokens(4096)
                .temperature(0.0)
                .build();
    }

    /**
     * Creates a VLM configuration with markdown output.
     *
     * @param vlmModelId VLM model ID
     * @return VLM pipeline configuration for markdown output
     */
    public static OcrPipelineConfig vlmMarkdown(String vlmModelId) {
        return OcrPipelineConfig.builder()
                .useVlm(true)
                .vlmModelId(vlmModelId)
                .vlmOutputFormat(VlmOutputFormat.MARKDOWN)
                .maxNewTokens(4096)
                .temperature(0.0)
                .build();
    }

}
