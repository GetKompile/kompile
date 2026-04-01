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

package ai.kompile.ocr.datapipeline.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for indexing extracted entities for search.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EntityIndexConfig {

    /**
     * How to index table entities.
     */
    @Builder.Default
    private TableIndexMode tableMode = TableIndexMode.DUAL;

    /**
     * How to index figure entities.
     */
    @Builder.Default
    private FigureIndexMode figureMode = FigureIndexMode.CAPTION_ONLY;

    /**
     * How to index formula entities.
     */
    @Builder.Default
    private FormulaIndexMode formulaMode = FormulaIndexMode.LATEX_TEXT;

    /**
     * How to index code entities.
     */
    @Builder.Default
    private CodeIndexMode codeMode = CodeIndexMode.WITH_LANGUAGE;

    /**
     * Whether to store full content in metadata.
     */
    @Builder.Default
    private boolean storeFullContent = true;

    /**
     * Whether to generate semantic summaries for tables.
     */
    @Builder.Default
    private boolean generateSemanticSummary = true;

    /**
     * Custom metadata fields to extract and index.
     */
    @Builder.Default
    private List<String> customMetadataFields = new ArrayList<>();

    /**
     * Prefix for entity IDs.
     */
    @Builder.Default
    private String entityIdPrefix = "entity_";

    /**
     * Whether to include source document reference.
     */
    @Builder.Default
    private boolean includeSourceReference = true;

    /**
     * Whether to include page number in metadata.
     */
    @Builder.Default
    private boolean includePageNumber = true;

    /**
     * Whether to include bounding box in metadata.
     */
    @Builder.Default
    private boolean includeBoundingBox = false;

    /**
     * Creates default configuration.
     */
    public static EntityIndexConfig defaults() {
        return EntityIndexConfig.builder().build();
    }

    /**
     * Creates configuration for semantic search optimization.
     */
    public static EntityIndexConfig forSemanticSearch() {
        return EntityIndexConfig.builder()
                .tableMode(TableIndexMode.SEMANTIC_ONLY)
                .generateSemanticSummary(true)
                .storeFullContent(true)
                .build();
    }

    /**
     * Creates configuration for full-text search.
     */
    public static EntityIndexConfig forFullTextSearch() {
        return EntityIndexConfig.builder()
                .tableMode(TableIndexMode.CONTENT_ONLY)
                .generateSemanticSummary(false)
                .storeFullContent(true)
                .build();
    }

    /**
     * Table indexing modes.
     */
    public enum TableIndexMode {
        /**
         * Do not index tables.
         */
        DISABLED,

        /**
         * Index only semantic summary for relevance matching.
         */
        SEMANTIC_ONLY,

        /**
         * Index only full content for exact matching.
         */
        CONTENT_ONLY,

        /**
         * Create both semantic and content index entries.
         */
        DUAL
    }

    /**
     * Figure indexing modes.
     */
    public enum FigureIndexMode {
        /**
         * Do not index figures.
         */
        DISABLED,

        /**
         * Index only caption text.
         */
        CAPTION_ONLY,

        /**
         * Index caption and alt text.
         */
        WITH_ALT_TEXT,

        /**
         * Index with generated description (requires LLM).
         */
        WITH_DESCRIPTION
    }

    /**
     * Formula indexing modes.
     */
    public enum FormulaIndexMode {
        /**
         * Do not index formulas.
         */
        DISABLED,

        /**
         * Index LaTeX source text.
         */
        LATEX_TEXT,

        /**
         * Index with text description (requires LLM).
         */
        WITH_DESCRIPTION
    }

    /**
     * Code indexing modes.
     */
    public enum CodeIndexMode {
        /**
         * Do not index code blocks.
         */
        DISABLED,

        /**
         * Index code content only.
         */
        CONTENT_ONLY,

        /**
         * Index with language tag in metadata.
         */
        WITH_LANGUAGE,

        /**
         * Index with generated summary (requires LLM).
         */
        WITH_SUMMARY
    }
}
