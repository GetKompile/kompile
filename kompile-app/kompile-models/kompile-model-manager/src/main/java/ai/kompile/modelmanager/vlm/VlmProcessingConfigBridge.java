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

import java.util.*;

/**
 * Bridges VLM model management with document processing configuration.
 *
 * This class provides utilities to convert between VlmExtractionConfig and
 * the existing PdfProcessingConfig format used by document loaders.
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create VLM extraction config
 * VlmExtractionConfig vlmConfig = VlmExtractionConfig.forScannedDocuments();
 *
 * // Convert to PDF processing config map
 * Map<String, Object> pdfConfigMap = VlmProcessingConfigBridge.toPdfProcessingConfigMap(vlmConfig);
 *
 * // Use with existing document loading API
 * PdfProcessingConfig pdfConfig = PdfProcessingConfig.fromMap(pdfConfigMap);
 * documentLoader.load(source, pdfConfig);
 * }</pre>
 *
 * <h2>Extraction Type to Processing Mode Mapping</h2>
 * <pre>
 * VlmExtractionType              → PdfProcessingConfig.ProcessingMode
 * ─────────────────────────────────────────────────────────────────────
 * DOCUMENT_UNDERSTANDING         → VLM
 * TABLE_EXTRACTION              → Uses tableExtractionMethod = VLM/AUTO
 * FIGURE_UNDERSTANDING          → VLM + extractFigures = true
 * OCR_WITH_LAYOUT               → TRADITIONAL_OCR
 * FORM_EXTRACTION               → VLM + extractFormFields = true
 * </pre>
 *
 * @author Kompile Inc.
 */
public class VlmProcessingConfigBridge {

    private VlmProcessingConfigBridge() {}

    /**
     * Convert VlmExtractionConfig to a map compatible with PdfProcessingConfig.fromMap().
     *
     * @param vlmConfig the VLM extraction configuration
     * @return map suitable for PdfProcessingConfig.fromMap()
     */
    public static Map<String, Object> toPdfProcessingConfigMap(VlmExtractionConfig vlmConfig) {
        Map<String, Object> map = new LinkedHashMap<>();

        // Determine processing mode based on enabled extractions
        String processingMode = determineProcessingMode(vlmConfig);
        map.put("processingMode", processingMode);

        // VLM settings
        boolean needsVlm = vlmConfig.isEnabled(VlmExtractionType.DOCUMENT_UNDERSTANDING) ||
                          vlmConfig.isEnabled(VlmExtractionType.FIGURE_UNDERSTANDING) ||
                          vlmConfig.isEnabled(VlmExtractionType.FORM_EXTRACTION);

        map.put("useVlm", needsVlm);

        if (needsVlm) {
            VlmModelSet docModelSet = vlmConfig.getModelSet(VlmExtractionType.DOCUMENT_UNDERSTANDING);
            if (docModelSet != null) {
                map.put("vlmModelId", docModelSet.getSetId());
            }
            map.put("vlmOutputFormat", vlmConfig.getOutputFormat());
            map.put("maxNewTokens", vlmConfig.getMaxNewTokens());
            map.put("temperature", vlmConfig.getTemperature());
            map.put("topP", vlmConfig.getTopP());
        }

        // Sampling parameters
        if (vlmConfig.getTopK() > 0) {
            map.put("topK", vlmConfig.getTopK());
        }
        if (vlmConfig.getRepetitionPenalty() != 1.0) {
            map.put("repetitionPenalty", vlmConfig.getRepetitionPenalty());
        }
        if (vlmConfig.getSamplingPreset() != null) {
            map.put("samplingPreset", vlmConfig.getSamplingPreset());
        }
        map.put("doSample", vlmConfig.isDoSample());

        // PDF rendering
        map.put("pdfRenderDpi", vlmConfig.getRenderDpi());

        // PDF page selection
        if (vlmConfig.getStartPage() > 1) {
            map.put("startPage", vlmConfig.getStartPage());
        }
        if (vlmConfig.getMaxPages() < Integer.MAX_VALUE) {
            map.put("maxPages", vlmConfig.getMaxPages());
        }
        if (vlmConfig.getPageRange() != null) {
            map.put("pageRange", vlmConfig.getPageRange());
        }

        // Table extraction
        boolean extractTables = vlmConfig.isEnabled(VlmExtractionType.TABLE_EXTRACTION);
        map.put("extractTables", extractTables);
        if (extractTables) {
            VlmModelSet tableModelSet = vlmConfig.getModelSet(VlmExtractionType.TABLE_EXTRACTION);
            if (tableModelSet != null) {
                // Use VLM-based table extraction
                map.put("tableExtractionMethod", "VLM");
            } else {
                // Use Tabula as fallback
                map.put("tableExtractionMethod", "AUTO");
            }
            map.put("tableStorageMode", "BOTH");
        }

        // Form extraction
        map.put("extractFormFields", vlmConfig.isEnabled(VlmExtractionType.FORM_EXTRACTION));

        // Auto detection
        map.put("autoModeMinCharacters", vlmConfig.getParameter(
            VlmExtractionConfig.MIN_TEXT_CHARS_FOR_TEXT_MODE, 100));

        return map;
    }

    /**
     * Determine the processing mode based on enabled extraction types.
     */
    private static String determineProcessingMode(VlmExtractionConfig config) {
        if (config.isEnabled(VlmExtractionType.DOCUMENT_UNDERSTANDING)) {
            // Full VLM processing
            return "VLM";
        } else if (config.isEnabled(VlmExtractionType.OCR_WITH_LAYOUT)) {
            // Traditional OCR
            return "TRADITIONAL_OCR";
        } else if (config.isAutoDetectContent()) {
            // Auto mode: try text extraction, fall back to VLM
            return "AUTO";
        } else {
            // Text extraction only
            return "TEXT_EXTRACTION";
        }
    }

    /**
     * Create VlmExtractionConfig from a PdfProcessingConfig-style map.
     *
     * @param pdfConfigMap configuration map
     * @return VLM extraction configuration
     */
    public static VlmExtractionConfig fromPdfProcessingConfigMap(Map<String, Object> pdfConfigMap) {
        VlmExtractionConfig.Builder builder = VlmExtractionConfig.builder();

        // Parse processing mode
        String processingMode = getStringValue(pdfConfigMap, "processingMode", "AUTO");
        boolean useVlm = getBooleanValue(pdfConfigMap, "useVlm", false);

        if ("VLM".equalsIgnoreCase(processingMode) || useVlm) {
            builder.enableExtraction(VlmExtractionType.DOCUMENT_UNDERSTANDING);

            // Set model if specified
            String vlmModelId = getStringValue(pdfConfigMap, "vlmModelId", null);
            if (vlmModelId != null) {
                VlmModelSet modelSet = VlmModelSet.getModelSet(vlmModelId);
                if (modelSet != null) {
                    builder.setModelSet(VlmExtractionType.DOCUMENT_UNDERSTANDING, modelSet);
                }
            }
        }

        if ("TRADITIONAL_OCR".equalsIgnoreCase(processingMode)) {
            builder.enableExtraction(VlmExtractionType.OCR_WITH_LAYOUT);
        }

        // Table extraction
        if (getBooleanValue(pdfConfigMap, "extractTables", true)) {
            builder.enableExtraction(VlmExtractionType.TABLE_EXTRACTION);
        }

        // Form extraction
        if (getBooleanValue(pdfConfigMap, "extractFormFields", false)) {
            builder.enableExtraction(VlmExtractionType.FORM_EXTRACTION);
        }

        // Parameters
        if (pdfConfigMap.containsKey("pdfRenderDpi")) {
            builder.setRenderDpi(getIntValue(pdfConfigMap, "pdfRenderDpi", 300));
        }
        if (pdfConfigMap.containsKey("maxNewTokens")) {
            builder.setMaxNewTokens(getIntValue(pdfConfigMap, "maxNewTokens", 4096));
        }
        if (pdfConfigMap.containsKey("temperature")) {
            builder.setTemperature(getDoubleValue(pdfConfigMap, "temperature", 0.0));
        }
        if (pdfConfigMap.containsKey("topK")) {
            builder.setTopK(getIntValue(pdfConfigMap, "topK", 0));
        }
        if (pdfConfigMap.containsKey("topP")) {
            builder.setTopP(getDoubleValue(pdfConfigMap, "topP", 1.0));
        }
        if (pdfConfigMap.containsKey("repetitionPenalty")) {
            builder.setRepetitionPenalty(getDoubleValue(pdfConfigMap, "repetitionPenalty", 1.0));
        }
        if (pdfConfigMap.containsKey("samplingPreset")) {
            builder.setSamplingPreset(getStringValue(pdfConfigMap, "samplingPreset", null));
        }
        if (pdfConfigMap.containsKey("doSample")) {
            builder.setDoSample(getBooleanValue(pdfConfigMap, "doSample", false));
        }
        if (pdfConfigMap.containsKey("vlmOutputFormat")) {
            builder.setOutputFormat(getStringValue(pdfConfigMap, "vlmOutputFormat", "MARKDOWN"));
        }

        // PDF page selection
        if (pdfConfigMap.containsKey("startPage")) {
            builder.setStartPage(getIntValue(pdfConfigMap, "startPage", 1));
        }
        if (pdfConfigMap.containsKey("maxPages")) {
            builder.setMaxPages(getIntValue(pdfConfigMap, "maxPages", Integer.MAX_VALUE));
        }
        if (pdfConfigMap.containsKey("pageRange")) {
            builder.setPageRange(getStringValue(pdfConfigMap, "pageRange", null));
        }

        // Auto detect
        builder.setParameter(VlmExtractionConfig.AUTO_DETECT_CONTENT,
            "AUTO".equalsIgnoreCase(processingMode));

        return builder.build();
    }

    /**
     * Get the model sets needed for a PdfProcessingConfig-style map.
     *
     * @param pdfConfigMap configuration map
     * @return set of model sets to download
     */
    public static Set<VlmModelSet> getRequiredModelSets(Map<String, Object> pdfConfigMap) {
        VlmExtractionConfig config = fromPdfProcessingConfigMap(pdfConfigMap);
        return config.getRequiredModelSets();
    }

    /**
     * Ensure all required models are downloaded for a configuration.
     *
     * @param pdfConfigMap configuration map
     * @return list of model IDs that failed to download
     */
    public static List<String> ensureModelsDownloaded(Map<String, Object> pdfConfigMap) {
        VlmExtractionConfig config = fromPdfProcessingConfigMap(pdfConfigMap);
        VlmModelResolver resolver = new VlmModelResolver();
        return resolver.ensureDownloaded(config);
    }

    /**
     * Get a summary of what models will be used for a configuration.
     *
     * @param pdfConfigMap configuration map
     * @return human-readable summary
     */
    public static String getModelsSummary(Map<String, Object> pdfConfigMap) {
        VlmExtractionConfig config = fromPdfProcessingConfigMap(pdfConfigMap);
        StringBuilder sb = new StringBuilder();
        sb.append("VLM Models Configuration:\n");

        for (VlmExtractionType type : config.getEnabledExtractions()) {
            VlmModelSet modelSet = config.getModelSet(type);
            sb.append("  - ").append(type.getId()).append(": ");
            if (modelSet != null) {
                sb.append(modelSet.getDisplayName())
                  .append(" (").append(modelSet.getComponents().size()).append(" components)");
            } else {
                sb.append("(no model configured)");
            }
            sb.append("\n");
        }

        if (config.getEnabledExtractions().isEmpty()) {
            sb.append("  (no VLM extractions enabled)\n");
        }

        return sb.toString();
    }

    // Helper methods for parsing config values
    private static String getStringValue(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? String.valueOf(value) : defaultValue;
    }

    private static boolean getBooleanValue(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static int getIntValue(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static double getDoubleValue(Map<String, Object> map, String key, double defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
