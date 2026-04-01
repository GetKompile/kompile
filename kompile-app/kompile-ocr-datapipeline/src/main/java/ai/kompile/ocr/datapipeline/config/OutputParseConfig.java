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
 * Configuration for parsing model output into structured entities.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OutputParseConfig {

    /**
     * Expected format of model output.
     */
    @Builder.Default
    private OutputFormat expectedFormat = OutputFormat.MARKDOWN;

    /**
     * Table parsing configuration.
     */
    @Builder.Default
    private TableParseConfig tables = TableParseConfig.defaults();

    /**
     * Whether to extract figure entities.
     */
    @Builder.Default
    private boolean extractFigures = true;

    /**
     * Whether to extract formula/equation entities.
     */
    @Builder.Default
    private boolean extractFormulas = true;

    /**
     * Whether to extract code block entities.
     */
    @Builder.Default
    private boolean extractCode = true;

    /**
     * Whether to extract list entities.
     */
    @Builder.Default
    private boolean extractLists = false;

    /**
     * Whether to extract heading entities.
     */
    @Builder.Default
    private boolean extractHeadings = false;

    /**
     * Whether to preserve raw output in parsed result.
     */
    @Builder.Default
    private boolean preserveRawOutput = true;

    /**
     * Creates default configuration.
     */
    public static OutputParseConfig defaults() {
        return OutputParseConfig.builder().build();
    }

    /**
     * Creates configuration for HTML output (PaddleOCR).
     */
    public static OutputParseConfig forHtml() {
        return OutputParseConfig.builder()
                .expectedFormat(OutputFormat.HTML)
                .tables(TableParseConfig.forHtml())
                .build();
    }

    /**
     * Creates configuration for Markdown output (DeepSeek-OCR).
     */
    public static OutputParseConfig forMarkdown() {
        return OutputParseConfig.builder()
                .expectedFormat(OutputFormat.MARKDOWN)
                .tables(TableParseConfig.forMarkdown())
                .extractCode(true)
                .extractFormulas(true)
                .build();
    }

    /**
     * Creates configuration for DocTags output (Docling).
     */
    public static OutputParseConfig forDocTags() {
        return OutputParseConfig.builder()
                .expectedFormat(OutputFormat.DOCTAGS)
                .tables(TableParseConfig.defaults())
                .build();
    }

    /**
     * Creates configuration for JSON output (LayoutLM).
     */
    public static OutputParseConfig forJson() {
        return OutputParseConfig.builder()
                .expectedFormat(OutputFormat.JSON)
                .extractFigures(false)
                .extractCode(false)
                .build();
    }

    /**
     * Output format types.
     */
    public enum OutputFormat {
        /**
         * HTML output with table/tr/td tags.
         */
        HTML,

        /**
         * Markdown output with pipe-delimited tables.
         */
        MARKDOWN,

        /**
         * DocTags XML-like format from Docling.
         */
        DOCTAGS,

        /**
         * JSON structured output.
         */
        JSON,

        /**
         * Raw text output.
         */
        RAW
    }
}
