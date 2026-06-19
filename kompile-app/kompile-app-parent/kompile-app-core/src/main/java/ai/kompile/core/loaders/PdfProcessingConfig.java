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

package ai.kompile.core.loaders;

import ai.kompile.ocr.VlmOutputFormat;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Configuration for PDF processing that can be passed per-request from the UI.
 * This replaces Spring @Value-based configuration with runtime configuration.
 *
 * <p>Processing modes:</p>
 * <ul>
 *   <li><b>TEXT_EXTRACTION</b>: Use PDFBox text extraction (fast, for text-based PDFs)</li>
 *   <li><b>VLM</b>: Use Vision-Language Model (for scanned/image PDFs)</li>
 *   <li><b>AUTO</b>: Try text extraction first, fall back to VLM if little content</li>
 *   <li><b>COMPARE</b>: Try multiple loaders and use the one with most content</li>
 * </ul>
 */
@Getter
public class PdfProcessingConfig {

    private static final Logger log = LoggerFactory.getLogger(PdfProcessingConfig.class);

    /**
     * Processing mode for PDF files.
     */
    public enum ProcessingMode {
        /** Direct text extraction using PDFBox (fast, for text-based PDFs) */
        TEXT_EXTRACTION,
        /** Vision-Language Model processing (for scanned/image PDFs) */
        VLM,
        /** Traditional OCR (detection + recognition pipeline) */
        TRADITIONAL_OCR,
        /** Try text extraction first, use VLM if insufficient content */
        AUTO,
        /** Compare multiple loaders and select the one with most content */
        COMPARE
    }

    /**
     * Table storage mode for extracted tables.
     */
    public enum TableStorageMode {
        /** Tables embedded inline in document text */
        INLINE,
        /** Each table as a separate document */
        SEPARATE,
        /** Both inline and separate documents */
        BOTH,
        /** Skip table extraction */
        NONE
    }

    /**
     * Table extraction method options.
     */
    public enum TableExtractionMethod {
        /** Use Tabula (rule-based, fast, works on text PDFs) */
        TABULA,
        /** Use VLM (AI-based, better for scanned/image PDFs) */
        VLM,
        /** Try Tabula first, fall back to VLM if no tables found */
        AUTO,
        /** Disable table extraction */
        NONE
    }

    // Processing mode
    private ProcessingMode processingMode = ProcessingMode.AUTO;

    // VLM configuration
    private boolean useVlm = false;
    private String vlmModelId;
    private VlmOutputFormat vlmOutputFormat = VlmOutputFormat.MARKDOWN;
    private int maxNewTokens = 4096;
    private double temperature = 0.0;
    private double topP = 1.0;
    private int beamSize = 1;
    private boolean doSample = false;

    // VLM component path overrides (optional - null means auto-detect from model directory)
    private String vlmDecoderPath;
    private String vlmEncoderPath;
    private String vlmEmbedTokensPath;
    private String vlmTokenizerPath;
    private String vlmPreprocessorConfigPath;

    // Traditional OCR configuration
    private String detectionModelId;
    private String recognitionModelId;
    private String tableModelId = null;
    private String layoutModelId = null;

    // PDF rendering
    private int pdfRenderDpi = 300;

    // Table extraction
    private boolean extractTables = true;
    private TableStorageMode tableStorageMode = TableStorageMode.BOTH;
    private TableExtractionMethod tableExtractionMethod = TableExtractionMethod.AUTO;
    private String tableFormat = "markdown";
    private int minTableRows = 2;
    private int minTableCols = 2;

    // Text extraction settings
    private boolean extractByPage = false;
    private boolean extractMetadata = true;
    private boolean extractAnnotations = true;
    private boolean extractFormFields = true;
    private boolean extractBookmarks = true;
    private boolean extractLinks = true;

    // Post-processing
    private boolean enablePostProcessing = false;
    private boolean enableLayoutAnalysis = false;

    // Auto mode threshold (minimum characters to consider text extraction successful)
    private int autoModeMinCharacters = 100;

    // Composite loader: try multiple loaders and pick best
    private boolean useCompositeLoader = false;

    private PdfProcessingConfig() {}

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates default configuration for text extraction mode.
     */
    public static PdfProcessingConfig textExtraction() {
        return builder()
                .processingMode(ProcessingMode.TEXT_EXTRACTION)
                .useVlm(false)
                .build();
    }

    /**
     * Creates default configuration for VLM mode.
     */
    public static PdfProcessingConfig vlm(String modelId) {
        return builder()
                .processingMode(ProcessingMode.VLM)
                .useVlm(true)
                .vlmModelId(modelId)
                .build();
    }

    /**
     * Creates default configuration for auto mode.
     */
    public static PdfProcessingConfig auto() {
        return builder()
                .processingMode(ProcessingMode.AUTO)
                .build();
    }

    /**
     * Creates configuration for composite loader mode (try multiple and pick best).
     */
    public static PdfProcessingConfig composite() {
        return builder()
                .processingMode(ProcessingMode.COMPARE)
                .useCompositeLoader(true)
                .build();
    }

    /**
     * Creates configuration from a map (for REST API requests).
     */
    public static PdfProcessingConfig fromMap(Map<String, Object> map) {
        Builder builder = builder();

        if (map == null) {
            return builder.build();
        }

        if (map.containsKey("processingMode")) {
            try {
                builder.processingMode(ProcessingMode.valueOf(String.valueOf(map.get("processingMode")).toUpperCase()));
            } catch (Exception e) {
                log.warn("Failed to parse config value for processingMode: {}", e.getMessage());
            }
        }

        if (map.containsKey("useVlm")) {
            builder.useVlm(Boolean.parseBoolean(String.valueOf(map.get("useVlm"))));
        }
        if (map.containsKey("vlmModelId")) {
            builder.vlmModelId(String.valueOf(map.get("vlmModelId")));
        }
        if (map.containsKey("vlmOutputFormat")) {
            try {
                builder.vlmOutputFormat(VlmOutputFormat.valueOf(String.valueOf(map.get("vlmOutputFormat")).toUpperCase()));
            } catch (Exception e) {
                log.warn("Failed to parse config value for vlmOutputFormat: {}", e.getMessage());
            }
        }
        if (map.containsKey("maxNewTokens")) {
            builder.maxNewTokens(Integer.parseInt(String.valueOf(map.get("maxNewTokens"))));
        }
        if (map.containsKey("temperature")) {
            builder.temperature(Double.parseDouble(String.valueOf(map.get("temperature"))));
        }
        if (map.containsKey("pdfRenderDpi")) {
            builder.pdfRenderDpi(Integer.parseInt(String.valueOf(map.get("pdfRenderDpi"))));
        }
        if (map.containsKey("extractTables")) {
            builder.extractTables(Boolean.parseBoolean(String.valueOf(map.get("extractTables"))));
        }
        if (map.containsKey("tableStorageMode")) {
            try {
                builder.tableStorageMode(TableStorageMode.valueOf(String.valueOf(map.get("tableStorageMode")).toUpperCase()));
            } catch (Exception e) {
                log.warn("Failed to parse config value for tableStorageMode: {}", e.getMessage());
            }
        }
        if (map.containsKey("tableExtractionMethod")) {
            try {
                builder.tableExtractionMethod(TableExtractionMethod.valueOf(String.valueOf(map.get("tableExtractionMethod")).toUpperCase()));
            } catch (Exception e) {
                log.warn("Failed to parse config value for tableExtractionMethod: {}", e.getMessage());
            }
        }
        if (map.containsKey("useCompositeLoader")) {
            builder.useCompositeLoader(Boolean.parseBoolean(String.valueOf(map.get("useCompositeLoader"))));
        }
        if (map.containsKey("autoModeMinCharacters")) {
            builder.autoModeMinCharacters(Integer.parseInt(String.valueOf(map.get("autoModeMinCharacters"))));
        }
        if (map.containsKey("vlmDecoderPath")) {
            builder.vlmDecoderPath(String.valueOf(map.get("vlmDecoderPath")));
        }
        if (map.containsKey("vlmEncoderPath")) {
            builder.vlmEncoderPath(String.valueOf(map.get("vlmEncoderPath")));
        }
        if (map.containsKey("vlmEmbedTokensPath")) {
            builder.vlmEmbedTokensPath(String.valueOf(map.get("vlmEmbedTokensPath")));
        }
        if (map.containsKey("vlmTokenizerPath")) {
            builder.vlmTokenizerPath(String.valueOf(map.get("vlmTokenizerPath")));
        }
        if (map.containsKey("vlmPreprocessorConfigPath")) {
            builder.vlmPreprocessorConfigPath(String.valueOf(map.get("vlmPreprocessorConfigPath")));
        }

        return builder.build();
    }

    /**
     * Converts this configuration to a map (for REST API responses).
     */
    public Map<String, Object> toMap() {
        return Map.ofEntries(
                Map.entry("processingMode", processingMode.name()),
                Map.entry("useVlm", useVlm),
                Map.entry("vlmModelId", vlmModelId),
                Map.entry("vlmOutputFormat", vlmOutputFormat.name()),
                Map.entry("maxNewTokens", maxNewTokens),
                Map.entry("temperature", temperature),
                Map.entry("topP", topP),
                Map.entry("beamSize", beamSize),
                Map.entry("doSample", doSample),
                Map.entry("pdfRenderDpi", pdfRenderDpi),
                Map.entry("extractTables", extractTables),
                Map.entry("tableStorageMode", tableStorageMode.name()),
                Map.entry("tableExtractionMethod", tableExtractionMethod.name()),
                Map.entry("tableFormat", tableFormat),
                Map.entry("useCompositeLoader", useCompositeLoader),
                Map.entry("autoModeMinCharacters", autoModeMinCharacters),
                Map.entry("extractByPage", extractByPage),
                Map.entry("extractMetadata", extractMetadata)
        );
    }

    public static class Builder {
        private final PdfProcessingConfig config = new PdfProcessingConfig();

        public Builder processingMode(ProcessingMode mode) {
            config.processingMode = mode;
            if (mode == ProcessingMode.VLM) {
                config.useVlm = true;
            } else if (mode == ProcessingMode.TEXT_EXTRACTION) {
                config.useVlm = false;
            }
            return this;
        }

        public Builder useVlm(boolean useVlm) {
            config.useVlm = useVlm;
            return this;
        }

        public Builder vlmModelId(String modelId) {
            config.vlmModelId = modelId;
            return this;
        }

        public Builder vlmOutputFormat(VlmOutputFormat format) {
            config.vlmOutputFormat = format;
            return this;
        }

        public Builder maxNewTokens(int maxNewTokens) {
            config.maxNewTokens = maxNewTokens;
            return this;
        }

        public Builder temperature(double temperature) {
            config.temperature = temperature;
            return this;
        }

        public Builder topP(double topP) {
            config.topP = topP;
            return this;
        }

        public Builder beamSize(int beamSize) {
            config.beamSize = beamSize;
            return this;
        }

        public Builder doSample(boolean doSample) {
            config.doSample = doSample;
            return this;
        }

        public Builder detectionModelId(String modelId) {
            config.detectionModelId = modelId;
            return this;
        }

        public Builder recognitionModelId(String modelId) {
            config.recognitionModelId = modelId;
            return this;
        }

        public Builder tableModelId(String modelId) {
            config.tableModelId = modelId;
            return this;
        }

        public Builder layoutModelId(String modelId) {
            config.layoutModelId = modelId;
            return this;
        }

        public Builder pdfRenderDpi(int dpi) {
            config.pdfRenderDpi = dpi;
            return this;
        }

        public Builder extractTables(boolean extract) {
            config.extractTables = extract;
            return this;
        }

        public Builder tableStorageMode(TableStorageMode mode) {
            config.tableStorageMode = mode;
            return this;
        }

        public Builder tableExtractionMethod(TableExtractionMethod method) {
            config.tableExtractionMethod = method;
            return this;
        }

        public Builder tableFormat(String format) {
            config.tableFormat = format;
            return this;
        }

        public Builder minTableRows(int rows) {
            config.minTableRows = rows;
            return this;
        }

        public Builder minTableCols(int cols) {
            config.minTableCols = cols;
            return this;
        }

        public Builder extractByPage(boolean byPage) {
            config.extractByPage = byPage;
            return this;
        }

        public Builder extractMetadata(boolean extract) {
            config.extractMetadata = extract;
            return this;
        }

        public Builder extractAnnotations(boolean extract) {
            config.extractAnnotations = extract;
            return this;
        }

        public Builder extractFormFields(boolean extract) {
            config.extractFormFields = extract;
            return this;
        }

        public Builder extractBookmarks(boolean extract) {
            config.extractBookmarks = extract;
            return this;
        }

        public Builder extractLinks(boolean extract) {
            config.extractLinks = extract;
            return this;
        }

        public Builder enablePostProcessing(boolean enable) {
            config.enablePostProcessing = enable;
            return this;
        }

        public Builder enableLayoutAnalysis(boolean enable) {
            config.enableLayoutAnalysis = enable;
            return this;
        }

        public Builder autoModeMinCharacters(int minChars) {
            config.autoModeMinCharacters = minChars;
            return this;
        }

        public Builder useCompositeLoader(boolean use) {
            config.useCompositeLoader = use;
            return this;
        }

        public Builder vlmDecoderPath(String path) {
            config.vlmDecoderPath = path;
            return this;
        }

        public Builder vlmEncoderPath(String path) {
            config.vlmEncoderPath = path;
            return this;
        }

        public Builder vlmEmbedTokensPath(String path) {
            config.vlmEmbedTokensPath = path;
            return this;
        }

        public Builder vlmTokenizerPath(String path) {
            config.vlmTokenizerPath = path;
            return this;
        }

        public Builder vlmPreprocessorConfigPath(String path) {
            config.vlmPreprocessorConfigPath = path;
            return this;
        }

        public PdfProcessingConfig build() {
            return config;
        }
    }
}
