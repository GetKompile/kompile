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

/**
 * Configuration for parsing tables from model output.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TableParseConfig {

    /**
     * Whether to preserve colspan/rowspan in parsed tables.
     */
    @Builder.Default
    private boolean preserveSpans = true;

    /**
     * Whether to automatically detect header rows.
     */
    @Builder.Default
    private boolean detectHeaders = true;

    /**
     * Minimum number of rows for a valid table.
     */
    @Builder.Default
    private int minRows = 1;

    /**
     * Minimum number of columns for a valid table.
     */
    @Builder.Default
    private int minCols = 1;

    /**
     * Maximum number of rows to parse (0 = unlimited).
     */
    @Builder.Default
    private int maxRows = 0;

    /**
     * Maximum number of columns to parse (0 = unlimited).
     */
    @Builder.Default
    private int maxCols = 0;

    /**
     * Output format for parsed tables.
     */
    @Builder.Default
    private TableOutputFormat outputFormat = TableOutputFormat.MARKDOWN;

    /**
     * Whether to trim whitespace from cell values.
     */
    @Builder.Default
    private boolean trimCellValues = true;

    /**
     * Whether to normalize line breaks in cell values.
     */
    @Builder.Default
    private boolean normalizeLineBreaks = true;

    /**
     * Whether to include empty cells in output.
     */
    @Builder.Default
    private boolean includeEmptyCells = true;

    /**
     * Creates default configuration.
     */
    public static TableParseConfig defaults() {
        return TableParseConfig.builder().build();
    }

    /**
     * Creates configuration for HTML input.
     */
    public static TableParseConfig forHtml() {
        return TableParseConfig.builder()
                .preserveSpans(true)
                .detectHeaders(true)
                .outputFormat(TableOutputFormat.MARKDOWN)
                .build();
    }

    /**
     * Creates configuration for Markdown input.
     */
    public static TableParseConfig forMarkdown() {
        return TableParseConfig.builder()
                .preserveSpans(false)  // Markdown doesn't support spans
                .detectHeaders(true)
                .outputFormat(TableOutputFormat.MARKDOWN)
                .build();
    }

    /**
     * Output format for parsed tables.
     */
    public enum TableOutputFormat {
        /**
         * Markdown pipe-delimited table.
         */
        MARKDOWN,

        /**
         * HTML table tags.
         */
        HTML,

        /**
         * JSON array of rows/cells.
         */
        JSON,

        /**
         * CSV format.
         */
        CSV
    }
}
