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

package ai.kompile.ocr.datapipeline.parse;

import ai.kompile.ocr.datapipeline.config.OutputParseConfig;
import ai.kompile.ocr.datapipeline.entity.DocumentEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses model output into structured document entities.
 * Delegates to format-specific parsers based on configuration.
 */
public class OutputParser {

    private static final Logger log = LoggerFactory.getLogger(OutputParser.class);

    private final HtmlParser htmlParser;
    private final MarkdownParser markdownParser;
    private final DocTagsParser docTagsParser;
    private final JsonOutputParser jsonParser;

    public OutputParser() {
        this.htmlParser = new HtmlParser();
        this.markdownParser = new MarkdownParser();
        this.docTagsParser = new DocTagsParser();
        this.jsonParser = new JsonOutputParser();
    }

    /**
     * Parses model output according to configuration.
     *
     * @param rawOutput The raw model output string
     * @param config Parsing configuration
     * @return Parsed output with extracted entities
     */
    public ParsedOutput parse(String rawOutput, OutputParseConfig config) {
        if (rawOutput == null || rawOutput.isEmpty()) {
            return ParsedOutput.empty(config.getExpectedFormat());
        }

        List<DocumentEntity> entities = new ArrayList<>();

        try {
            entities = switch (config.getExpectedFormat()) {
                case HTML -> htmlParser.parse(rawOutput, config);
                case MARKDOWN -> markdownParser.parse(rawOutput, config);
                case DOCTAGS -> docTagsParser.parse(rawOutput, config);
                case JSON -> jsonParser.parse(rawOutput, config);
                case RAW -> List.of();  // No parsing for raw format
            };
        } catch (Exception e) {
            log.error("Failed to parse output as {}: {}", config.getExpectedFormat(), e.getMessage());
            // Return empty result on parse failure
        }

        String fullText = extractFullText(rawOutput, config.getExpectedFormat());

        return new ParsedOutput(
                config.isPreserveRawOutput() ? rawOutput : null,
                config.getExpectedFormat(),
                entities,
                fullText
        );
    }

    /**
     * Extracts plain text from the output.
     */
    private String extractFullText(String rawOutput, OutputParseConfig.OutputFormat format) {
        return switch (format) {
            case HTML -> htmlParser.extractText(rawOutput);
            case MARKDOWN -> markdownParser.extractText(rawOutput);
            case DOCTAGS -> docTagsParser.extractText(rawOutput);
            case JSON, RAW -> rawOutput;
        };
    }

    /**
     * Auto-detects the format of the output.
     */
    public OutputParseConfig.OutputFormat detectFormat(String rawOutput) {
        if (rawOutput == null || rawOutput.isEmpty()) {
            return OutputParseConfig.OutputFormat.RAW;
        }

        String trimmed = rawOutput.trim();

        // Check for HTML
        if (trimmed.startsWith("<") && (trimmed.contains("<table") || trimmed.contains("<html") || trimmed.contains("<div"))) {
            return OutputParseConfig.OutputFormat.HTML;
        }

        // Check for DocTags
        if (trimmed.startsWith("<document>") || trimmed.contains("<loc_")) {
            return OutputParseConfig.OutputFormat.DOCTAGS;
        }

        // Check for JSON
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            return OutputParseConfig.OutputFormat.JSON;
        }

        // Check for Markdown tables
        if (trimmed.contains("|") && trimmed.contains("---")) {
            return OutputParseConfig.OutputFormat.MARKDOWN;
        }

        return OutputParseConfig.OutputFormat.RAW;
    }
}
