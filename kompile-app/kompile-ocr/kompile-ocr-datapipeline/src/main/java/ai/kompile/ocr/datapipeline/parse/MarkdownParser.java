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
import ai.kompile.ocr.datapipeline.config.TableParseConfig;
import ai.kompile.ocr.datapipeline.entity.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Markdown output (e.g., from DeepSeek-OCR) into document entities.
 */
public class MarkdownParser {

    // Markdown table pattern: | col | col | with separator row | --- | --- |
    private static final Pattern TABLE_PATTERN = Pattern.compile(
            "(\\|[^\\n]+\\|\\n)(\\|[-:| ]+\\|\\n)((?:\\|[^\\n]+\\|\\n?)+)",
            Pattern.MULTILINE
    );

    // Code block pattern: ```language\ncode\n```
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile(
            "```(\\w*)\\n([\\s\\S]*?)```",
            Pattern.MULTILINE
    );

    // Display formula pattern: $$...$$ (block)
    private static final Pattern DISPLAY_FORMULA_PATTERN = Pattern.compile(
            "\\$\\$([^$]+)\\$\\$",
            Pattern.MULTILINE
    );

    // Inline formula pattern: $...$
    private static final Pattern INLINE_FORMULA_PATTERN = Pattern.compile(
            "(?<!\\$)\\$([^$\\n]+)\\$(?!\\$)"
    );

    // Image pattern: ![alt](url)
    private static final Pattern IMAGE_PATTERN = Pattern.compile(
            "!\\[([^\\]]*)\\]\\(([^)]+)\\)"
    );

    // Heading pattern: # Heading
    private static final Pattern HEADING_PATTERN = Pattern.compile(
            "^(#{1,6})\\s+(.+)$",
            Pattern.MULTILINE
    );

    // List pattern: - item or 1. item
    private static final Pattern UNORDERED_LIST_PATTERN = Pattern.compile(
            "(?:^[-*+]\\s+.+$\\n?)+",
            Pattern.MULTILINE
    );

    private static final Pattern ORDERED_LIST_PATTERN = Pattern.compile(
            "(?:^\\d+\\.\\s+.+$\\n?)+",
            Pattern.MULTILINE
    );

    /**
     * Parses Markdown content into entities.
     */
    public List<DocumentEntity> parse(String markdown, OutputParseConfig config) {
        List<DocumentEntity> entities = new ArrayList<>();

        // Extract tables
        Matcher tableMatcher = TABLE_PATTERN.matcher(markdown);
        while (tableMatcher.find()) {
            String headerRow = tableMatcher.group(1);
            String bodyRows = tableMatcher.group(3);
            TableEntity table = parseTable(headerRow, bodyRows, config.getTables());
            if (table != null) {
                entities.add(table);
            }
        }

        // Extract code blocks
        if (config.isExtractCode()) {
            Matcher codeMatcher = CODE_BLOCK_PATTERN.matcher(markdown);
            while (codeMatcher.find()) {
                String language = codeMatcher.group(1);
                String code = codeMatcher.group(2);
                entities.add(CodeEntity.builder()
                        .id(UUID.randomUUID().toString())
                        .type(DocumentEntity.EntityType.CODE)
                        .code(code.trim())
                        .language(language.isEmpty() ? null : language)
                        .confidence(1.0)
                        .build());
            }
        }

        // Extract formulas
        if (config.isExtractFormulas()) {
            // Display formulas (block)
            Matcher displayMatcher = DISPLAY_FORMULA_PATTERN.matcher(markdown);
            while (displayMatcher.find()) {
                entities.add(FormulaEntity.builder()
                        .id(UUID.randomUUID().toString())
                        .type(DocumentEntity.EntityType.FORMULA)
                        .latex(displayMatcher.group(1).trim())
                        .inline(false)
                        .confidence(1.0)
                        .build());
            }

            // Inline formulas
            Matcher inlineMatcher = INLINE_FORMULA_PATTERN.matcher(markdown);
            while (inlineMatcher.find()) {
                entities.add(FormulaEntity.builder()
                        .id(UUID.randomUUID().toString())
                        .type(DocumentEntity.EntityType.FORMULA)
                        .latex(inlineMatcher.group(1).trim())
                        .inline(true)
                        .confidence(1.0)
                        .build());
            }
        }

        // Extract figures
        if (config.isExtractFigures()) {
            Matcher imageMatcher = IMAGE_PATTERN.matcher(markdown);
            while (imageMatcher.find()) {
                String altText = imageMatcher.group(1);
                String url = imageMatcher.group(2);

                FigureEntity.FigureEntityBuilder builder = FigureEntity.builder()
                        .id(UUID.randomUUID().toString())
                        .type(DocumentEntity.EntityType.FIGURE)
                        .altText(altText.isEmpty() ? null : altText)
                        .confidence(1.0);

                // Check for data URL
                if (url.startsWith("data:image/")) {
                    int commaIndex = url.indexOf(',');
                    if (commaIndex > 0) {
                        String format = url.substring(11, url.indexOf(';'));
                        String data = url.substring(commaIndex + 1);
                        builder.imageFormat(format);
                        builder.imageData(data);
                    }
                }

                entities.add(builder.build());
            }
        }

        // Extract headings
        if (config.isExtractHeadings()) {
            Matcher headingMatcher = HEADING_PATTERN.matcher(markdown);
            while (headingMatcher.find()) {
                int level = headingMatcher.group(1).length();
                String text = headingMatcher.group(2).trim();
                entities.add(HeadingEntity.builder()
                        .id(UUID.randomUUID().toString())
                        .type(DocumentEntity.EntityType.HEADING)
                        .text(text)
                        .level(level)
                        .confidence(1.0)
                        .build());
            }
        }

        // Extract lists
        if (config.isExtractLists()) {
            // Unordered lists
            Matcher ulMatcher = UNORDERED_LIST_PATTERN.matcher(markdown);
            while (ulMatcher.find()) {
                List<String> items = parseListItems(ulMatcher.group(), false);
                if (!items.isEmpty()) {
                    entities.add(ListEntity.builder()
                            .id(UUID.randomUUID().toString())
                            .type(DocumentEntity.EntityType.LIST)
                            .items(items)
                            .ordered(false)
                            .confidence(1.0)
                            .build());
                }
            }

            // Ordered lists
            Matcher olMatcher = ORDERED_LIST_PATTERN.matcher(markdown);
            while (olMatcher.find()) {
                List<String> items = parseListItems(olMatcher.group(), true);
                if (!items.isEmpty()) {
                    entities.add(ListEntity.builder()
                            .id(UUID.randomUUID().toString())
                            .type(DocumentEntity.EntityType.LIST)
                            .items(items)
                            .ordered(true)
                            .confidence(1.0)
                            .build());
                }
            }
        }

        return entities;
    }

    /**
     * Parses a Markdown table.
     */
    private TableEntity parseTable(String headerRow, String bodyRows, TableParseConfig config) {
        List<String> headers = parseTableRow(headerRow, config);
        List<List<TableEntity.TableCell>> rows = new ArrayList<>();

        for (String line : bodyRows.split("\\n")) {
            if (line.trim().isEmpty()) continue;
            List<String> values = parseTableRow(line, config);
            List<TableEntity.TableCell> cells = values.stream()
                    .map(TableEntity.TableCell::new)
                    .toList();
            if (!cells.isEmpty()) {
                rows.add(new ArrayList<>(cells));
            }
        }

        // Check minimum requirements
        if (rows.size() < config.getMinRows() || headers.size() < config.getMinCols()) {
            return null;
        }

        // Apply limits
        if (config.getMaxRows() > 0 && rows.size() > config.getMaxRows()) {
            rows = rows.subList(0, config.getMaxRows());
        }
        if (config.getMaxCols() > 0) {
            int maxCols = config.getMaxCols();
            if (headers.size() > maxCols) {
                headers = headers.subList(0, maxCols);
            }
            rows = rows.stream()
                    .map(row -> row.size() > maxCols ? row.subList(0, maxCols) : row)
                    .toList();
        }

        String markdownContent = headerRow + "| --- ".repeat(headers.size()) + "|\n" + bodyRows;

        return TableEntity.builder()
                .id(UUID.randomUUID().toString())
                .type(DocumentEntity.EntityType.TABLE)
                .rows(rows)
                .headers(headers)
                .markdownContent(markdownContent)
                .confidence(1.0)
                .build();
    }

    /**
     * Parses a single table row.
     */
    private List<String> parseTableRow(String row, TableParseConfig config) {
        List<String> cells = new ArrayList<>();
        String[] parts = row.split("\\|");

        for (String part : parts) {
            String cell = config.isTrimCellValues() ? part.trim() : part;
            if (!cell.isEmpty()) {
                if (config.isNormalizeLineBreaks()) {
                    cell = cell.replaceAll("\\s+", " ");
                }
                cells.add(cell);
            }
        }

        return cells;
    }

    /**
     * Parses list items.
     */
    private List<String> parseListItems(String listText, boolean ordered) {
        List<String> items = new ArrayList<>();
        Pattern itemPattern = ordered ?
                Pattern.compile("^\\d+\\.\\s+(.+)$", Pattern.MULTILINE) :
                Pattern.compile("^[-*+]\\s+(.+)$", Pattern.MULTILINE);

        Matcher matcher = itemPattern.matcher(listText);
        while (matcher.find()) {
            items.add(matcher.group(1).trim());
        }

        return items;
    }

    /**
     * Extracts plain text from Markdown.
     */
    public String extractText(String markdown) {
        String text = markdown;

        // Remove code blocks
        text = CODE_BLOCK_PATTERN.matcher(text).replaceAll("");

        // Remove images (keep alt text)
        text = IMAGE_PATTERN.matcher(text).replaceAll("$1");

        // Remove formatting
        text = text.replaceAll("\\*\\*(.+?)\\*\\*", "$1");  // Bold
        text = text.replaceAll("\\*(.+?)\\*", "$1");        // Italic
        text = text.replaceAll("`(.+?)`", "$1");            // Inline code
        text = text.replaceAll("\\[(.+?)\\]\\(.+?\\)", "$1"); // Links

        // Remove heading markers
        text = HEADING_PATTERN.matcher(text).replaceAll("$2");

        // Clean up whitespace
        text = text.replaceAll("\\n{3,}", "\n\n");

        return text.trim();
    }
}
