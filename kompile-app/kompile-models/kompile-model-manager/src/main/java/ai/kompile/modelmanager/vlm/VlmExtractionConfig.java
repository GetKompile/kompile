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
 * Configuration for VLM-based content extraction during document processing.
 *
 * This configuration determines which VLM models are used for different content types
 * when loading and chunking documents.
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * VlmExtractionConfig config = VlmExtractionConfig.builder()
 *     // Enable document understanding for scanned PDFs
 *     .enableExtraction(VlmExtractionType.DOCUMENT_UNDERSTANDING)
 *     .setModelSet(VlmExtractionType.DOCUMENT_UNDERSTANDING, VlmModelSet.SMOLDOCLING_256M)
 *
 *     // Enable table extraction
 *     .enableExtraction(VlmExtractionType.TABLE_EXTRACTION)
 *
 *     // Enable image embeddings for figures
 *     .enableExtraction(VlmExtractionType.IMAGE_EMBEDDING)
 *
 *     // Configure document rendering
 *     .setRenderDpi(300)
 *     .setMaxTiles(9)
 *
 *     .build();
 *
 * // Use with document loading
 * documentLoader.load(source, config);
 * }</pre>
 *
 * @author Kompile Inc.
 */
public class VlmExtractionConfig {

    private final Set<VlmExtractionType> enabledExtractions;
    private final Map<VlmExtractionType, VlmModelSet> modelOverrides;
    private final Map<String, Object> parameters;

    // Default parameter keys
    public static final String RENDER_DPI = "renderDpi";
    public static final String MAX_TILES = "maxTiles";
    public static final String TILE_SIZE = "tileSize";
    public static final String MAX_NEW_TOKENS = "maxNewTokens";
    public static final String TEMPERATURE = "temperature";
    public static final String TOP_P = "topP";
    public static final String TOP_K = "topK";
    public static final String REPETITION_PENALTY = "repetitionPenalty";
    public static final String SAMPLING_PRESET = "samplingPreset";
    public static final String DO_SAMPLE = "doSample";
    public static final String OUTPUT_FORMAT = "outputFormat";
    public static final String AUTO_DETECT_CONTENT = "autoDetectContent";
    public static final String MIN_TEXT_CHARS_FOR_TEXT_MODE = "minTextCharsForTextMode";
    public static final String TABLE_DETECTION_THRESHOLD = "tableDetectionThreshold";
    public static final String FIGURE_DETECTION_THRESHOLD = "figureDetectionThreshold";

    // PDF page selection parameters
    public static final String START_PAGE = "startPage";
    public static final String MAX_PAGES = "maxPages";
    public static final String PAGE_RANGE = "pageRange";

    private VlmExtractionConfig(Builder builder) {
        this.enabledExtractions = Collections.unmodifiableSet(new HashSet<>(builder.enabledExtractions));
        this.modelOverrides = Collections.unmodifiableMap(new HashMap<>(builder.modelOverrides));
        this.parameters = Collections.unmodifiableMap(new HashMap<>(builder.parameters));
    }

    // =====================================================================
    // PRESET CONFIGURATIONS
    // =====================================================================

    /**
     * Configuration for text-based PDFs with table support.
     * Uses text extraction primarily, with TableFormer for tables.
     */
    public static VlmExtractionConfig forTextPdfs() {
        return builder()
            .enableExtraction(VlmExtractionType.TABLE_EXTRACTION)
            .setParameter(AUTO_DETECT_CONTENT, true)
            .setParameter(MIN_TEXT_CHARS_FOR_TEXT_MODE, 100)
            .build();
    }

    /**
     * Configuration for scanned documents requiring full VLM processing.
     * Uses SmolDocling for document understanding.
     */
    public static VlmExtractionConfig forScannedDocuments() {
        return builder()
            .enableExtraction(VlmExtractionType.DOCUMENT_UNDERSTANDING)
            .enableExtraction(VlmExtractionType.TABLE_EXTRACTION)
            .enableExtraction(VlmExtractionType.IMAGE_EMBEDDING)
            .setParameter(RENDER_DPI, 300)
            .setParameter(MAX_TILES, 9)
            .setParameter(MAX_NEW_TOKENS, 4096)
            .setParameter(OUTPUT_FORMAT, "MARKDOWN")
            .build();
    }

    /**
     * Configuration for scientific papers with equations and figures.
     * Uses "precise" sampling preset for deterministic, accurate extraction.
     */
    public static VlmExtractionConfig forScientificPapers() {
        return builder()
            .enableExtraction(VlmExtractionType.DOCUMENT_UNDERSTANDING)
            .enableExtraction(VlmExtractionType.TABLE_EXTRACTION)
            .enableExtraction(VlmExtractionType.FIGURE_UNDERSTANDING)
            .enableExtraction(VlmExtractionType.EQUATION_RECOGNITION)
            .setParameter(RENDER_DPI, 300)
            .setParameter(OUTPUT_FORMAT, "MARKDOWN")
            .setParameter(SAMPLING_PRESET, "precise")
            .build();
    }

    /**
     * Configuration for creative text generation from documents.
     * Uses "creative" sampling preset for more varied outputs.
     */
    public static VlmExtractionConfig forCreativeGeneration() {
        return builder()
            .enableExtraction(VlmExtractionType.DOCUMENT_UNDERSTANDING)
            .setParameter(RENDER_DPI, 300)
            .setParameter(OUTPUT_FORMAT, "MARKDOWN")
            .setParameter(SAMPLING_PRESET, "creative")
            .setParameter(MAX_NEW_TOKENS, 8192)
            .build();
    }

    /**
     * Configuration for forms and invoices.
     */
    public static VlmExtractionConfig forForms() {
        return builder()
            .enableExtraction(VlmExtractionType.FORM_EXTRACTION)
            .enableExtraction(VlmExtractionType.TABLE_EXTRACTION)
            .setModelSet(VlmExtractionType.FORM_EXTRACTION, VlmModelSet.DONUT_BASE)
            .setParameter(OUTPUT_FORMAT, "JSON")
            .build();
    }

    /**
     * Configuration for image-heavy documents.
     */
    public static VlmExtractionConfig forImageDocuments() {
        return builder()
            .enableExtraction(VlmExtractionType.IMAGE_EMBEDDING)
            .enableExtraction(VlmExtractionType.FIGURE_UNDERSTANDING)
            .enableExtraction(VlmExtractionType.IMAGE_TEXT_SIMILARITY)
            .build();
    }

    /**
     * Comprehensive configuration enabling all available extraction types.
     */
    public static VlmExtractionConfig comprehensive() {
        Builder builder = builder();
        for (VlmExtractionType type : VlmExtractionType.getTypesWithModels()) {
            builder.enableExtraction(type);
        }
        return builder
            .setParameter(RENDER_DPI, 300)
            .setParameter(AUTO_DETECT_CONTENT, true)
            .build();
    }

    /**
     * Minimal configuration - no VLM processing.
     */
    public static VlmExtractionConfig none() {
        return builder().build();
    }

    // =====================================================================
    // INSTANCE METHODS
    // =====================================================================

    /**
     * Check if a specific extraction type is enabled.
     */
    public boolean isEnabled(VlmExtractionType type) {
        return enabledExtractions.contains(type);
    }

    /**
     * Get all enabled extraction types.
     */
    public Set<VlmExtractionType> getEnabledExtractions() {
        return enabledExtractions;
    }

    /**
     * Get the model set for an extraction type, using override if set.
     */
    public VlmModelSet getModelSet(VlmExtractionType type) {
        VlmModelSet override = modelOverrides.get(type);
        return override != null ? override : type.getDefaultModelSet();
    }

    /**
     * Get all model sets needed for enabled extractions.
     */
    public Set<VlmModelSet> getRequiredModelSets() {
        Set<VlmModelSet> sets = new HashSet<>();
        for (VlmExtractionType type : enabledExtractions) {
            VlmModelSet modelSet = getModelSet(type);
            if (modelSet != null) {
                sets.add(modelSet);
            }
        }
        return sets;
    }

    /**
     * Get a parameter value.
     */
    @SuppressWarnings("unchecked")
    public <T> T getParameter(String key, T defaultValue) {
        Object value = parameters.get(key);
        return value != null ? (T) value : defaultValue;
    }

    public int getRenderDpi() {
        return getParameter(RENDER_DPI, 300);
    }

    public int getMaxTiles() {
        return getParameter(MAX_TILES, 9);
    }

    public int getTileSize() {
        return getParameter(TILE_SIZE, 512);
    }

    public int getMaxNewTokens() {
        return getParameter(MAX_NEW_TOKENS, 4096);
    }

    public double getTemperature() {
        return getParameter(TEMPERATURE, 0.0);
    }

    public double getTopP() {
        return getParameter(TOP_P, 1.0);
    }

    public int getTopK() {
        return getParameter(TOP_K, 0);
    }

    public double getRepetitionPenalty() {
        return getParameter(REPETITION_PENALTY, 1.0);
    }

    public String getSamplingPreset() {
        return getParameter(SAMPLING_PRESET, null);
    }

    public boolean isDoSample() {
        return getParameter(DO_SAMPLE, false);
    }

    public String getOutputFormat() {
        return getParameter(OUTPUT_FORMAT, "MARKDOWN");
    }

    public boolean isAutoDetectContent() {
        return getParameter(AUTO_DETECT_CONTENT, true);
    }

    public int getStartPage() {
        return getParameter(START_PAGE, 1);
    }

    public int getMaxPages() {
        return getParameter(MAX_PAGES, Integer.MAX_VALUE);
    }

    public String getPageRange() {
        return getParameter(PAGE_RANGE, null);
    }

    /**
     * Check if any VLM extraction is enabled.
     */
    public boolean hasAnyExtraction() {
        return !enabledExtractions.isEmpty();
    }

    /**
     * Check if document understanding VLM is needed.
     */
    public boolean needsDocumentUnderstanding() {
        return isEnabled(VlmExtractionType.DOCUMENT_UNDERSTANDING);
    }

    /**
     * Check if table extraction is needed.
     */
    public boolean needsTableExtraction() {
        return isEnabled(VlmExtractionType.TABLE_EXTRACTION);
    }

    /**
     * Check if image embedding is needed.
     */
    public boolean needsImageEmbedding() {
        return isEnabled(VlmExtractionType.IMAGE_EMBEDDING);
    }

    /**
     * Create configuration from a map (for REST API / UI integration).
     */
    public static VlmExtractionConfig fromMap(Map<String, Object> map) {
        Builder builder = builder();

        // Parse enabled extractions
        Object enabledObj = map.get("enabledExtractions");
        if (enabledObj instanceof List) {
            for (Object item : (List<?>) enabledObj) {
                VlmExtractionType type = VlmExtractionType.fromId(item.toString());
                if (type != null) {
                    builder.enableExtraction(type);
                }
            }
        }

        // Parse model overrides
        Object overridesObj = map.get("modelOverrides");
        if (overridesObj instanceof Map) {
            Map<?, ?> overrides = (Map<?, ?>) overridesObj;
            for (Map.Entry<?, ?> entry : overrides.entrySet()) {
                VlmExtractionType type = VlmExtractionType.fromId(entry.getKey().toString());
                VlmModelSet modelSet = VlmModelSet.getModelSet(entry.getValue().toString());
                if (type != null && modelSet != null) {
                    builder.setModelSet(type, modelSet);
                }
            }
        }

        // Parse parameters
        for (String key : Arrays.asList(RENDER_DPI, MAX_TILES, TILE_SIZE, MAX_NEW_TOKENS,
                TEMPERATURE, TOP_P, TOP_K, REPETITION_PENALTY, SAMPLING_PRESET, DO_SAMPLE,
                OUTPUT_FORMAT, AUTO_DETECT_CONTENT,
                MIN_TEXT_CHARS_FOR_TEXT_MODE, TABLE_DETECTION_THRESHOLD, FIGURE_DETECTION_THRESHOLD,
                START_PAGE, MAX_PAGES, PAGE_RANGE)) {
            if (map.containsKey(key)) {
                builder.setParameter(key, map.get(key));
            }
        }

        return builder.build();
    }

    /**
     * Convert to map for serialization.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();

        // Enabled extractions
        List<String> enabledIds = new ArrayList<>();
        for (VlmExtractionType type : enabledExtractions) {
            enabledIds.add(type.getId());
        }
        map.put("enabledExtractions", enabledIds);

        // Model overrides
        Map<String, String> overrides = new LinkedHashMap<>();
        for (Map.Entry<VlmExtractionType, VlmModelSet> entry : modelOverrides.entrySet()) {
            overrides.put(entry.getKey().getId(), entry.getValue().getSetId());
        }
        if (!overrides.isEmpty()) {
            map.put("modelOverrides", overrides);
        }

        // Parameters
        map.putAll(parameters);

        return map;
    }

    @Override
    public String toString() {
        return "VlmExtractionConfig{" +
            "enabled=" + enabledExtractions.stream().map(VlmExtractionType::getId).toList() +
            ", params=" + parameters +
            '}';
    }

    // =====================================================================
    // BUILDER
    // =====================================================================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final Set<VlmExtractionType> enabledExtractions = new HashSet<>();
        private final Map<VlmExtractionType, VlmModelSet> modelOverrides = new HashMap<>();
        private final Map<String, Object> parameters = new LinkedHashMap<>();

        public Builder enableExtraction(VlmExtractionType type) {
            this.enabledExtractions.add(type);
            return this;
        }

        public Builder disableExtraction(VlmExtractionType type) {
            this.enabledExtractions.remove(type);
            return this;
        }

        public Builder setModelSet(VlmExtractionType type, VlmModelSet modelSet) {
            this.modelOverrides.put(type, modelSet);
            return this;
        }

        public Builder setParameter(String key, Object value) {
            this.parameters.put(key, value);
            return this;
        }

        public Builder setRenderDpi(int dpi) {
            return setParameter(RENDER_DPI, dpi);
        }

        public Builder setMaxTiles(int maxTiles) {
            return setParameter(MAX_TILES, maxTiles);
        }

        public Builder setMaxNewTokens(int maxNewTokens) {
            return setParameter(MAX_NEW_TOKENS, maxNewTokens);
        }

        public Builder setTemperature(double temperature) {
            return setParameter(TEMPERATURE, temperature);
        }

        public Builder setTopK(int topK) {
            return setParameter(TOP_K, topK);
        }

        public Builder setTopP(double topP) {
            return setParameter(TOP_P, topP);
        }

        public Builder setRepetitionPenalty(double penalty) {
            return setParameter(REPETITION_PENALTY, penalty);
        }

        public Builder setSamplingPreset(String preset) {
            return setParameter(SAMPLING_PRESET, preset);
        }

        public Builder setDoSample(boolean doSample) {
            return setParameter(DO_SAMPLE, doSample);
        }

        public Builder setOutputFormat(String format) {
            return setParameter(OUTPUT_FORMAT, format);
        }

        public Builder setStartPage(int startPage) {
            return setParameter(START_PAGE, startPage);
        }

        public Builder setMaxPages(int maxPages) {
            return setParameter(MAX_PAGES, maxPages);
        }

        public Builder setPageRange(String pageRange) {
            return setParameter(PAGE_RANGE, pageRange);
        }

        public VlmExtractionConfig build() {
            return new VlmExtractionConfig(this);
        }
    }
}
