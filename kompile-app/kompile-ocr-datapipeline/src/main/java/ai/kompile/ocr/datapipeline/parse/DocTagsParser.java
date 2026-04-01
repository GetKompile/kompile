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
import ai.kompile.ocr.datapipeline.entity.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses DocTags format output (from Docling/Granite-Docling) into document entities.
 *
 * DocTags format example:
 * <document>
 *   <title><loc_20>Document Title</title>
 *   <table><loc_100><otsl>header1|header2\nrow1|row2</otsl></table>
 *   <text><loc_200>Paragraph text...</text>
 *   <figure><loc_300>Figure caption</figure>
 * </document>
 */
public class DocTagsParser {

    // Pattern for DocTags elements: <tagname><loc_N>content</tagname>
    private static final Pattern ELEMENT_PATTERN = Pattern.compile(
            "<(\\w+)><loc_(\\d+)>([\\s\\S]*?)</\\1>",
            Pattern.MULTILINE
    );

    // Pattern for OTSL table content within <otsl> tags
    private static final Pattern OTSL_PATTERN = Pattern.compile(
            "<otsl>([\\s\\S]*?)</otsl>"
    );

    // Pattern for nested elements
    private static final Pattern NESTED_PATTERN = Pattern.compile(
            "<(\\w+)>([^<]*)</\\1>"
    );

    /**
     * Parses DocTags content into entities.
     */
    public List<DocumentEntity> parse(String docTags, OutputParseConfig config) {
        List<DocumentEntity> entities = new ArrayList<>();

        Matcher matcher = ELEMENT_PATTERN.matcher(docTags);
        while (matcher.find()) {
            String tagName = matcher.group(1).toLowerCase();
            int location = Integer.parseInt(matcher.group(2));
            String content = matcher.group(3);

            DocumentEntity entity = parseElement(tagName, content, location, config);
            if (entity != null) {
                entities.add(entity);
            }
        }

        return entities;
    }

    /**
     * Parses a single DocTags element.
     */
    private DocumentEntity parseElement(String tagName, String content, int location, OutputParseConfig config) {
        return switch (tagName) {
            case "table" -> parseTable(content, location, config);
            case "figure", "picture", "image" -> config.isExtractFigures() ? parseFigure(content, location) : null;
            case "formula", "equation" -> config.isExtractFormulas() ? parseFormula(content, location) : null;
            case "code" -> config.isExtractCode() ? parseCode(content, location) : null;
            case "list" -> config.isExtractLists() ? parseList(content, location) : null;
            case "title", "heading", "section" -> config.isExtractHeadings() ? parseHeading(content, location, tagName) : null;
            default -> null;  // Skip unknown tags
        };
    }

    /**
     * Parses a table from DocTags format.
     */
    private TableEntity parseTable(String content, int location, OutputParseConfig config) {
        // Check for OTSL (Optimized Table Structure Language) content
        Matcher otslMatcher = OTSL_PATTERN.matcher(content);
        String tableContent = otslMatcher.find() ? otslMatcher.group(1) : content;

        // Parse OTSL format: rows separated by \n, cells by |
        List<List<TableEntity.TableCell>> rows = new ArrayList<>();
        List<String> headers = null;

        String[] lines = tableContent.split("\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            String[] cellValues = line.split("\\|");
            List<TableEntity.TableCell> row = new ArrayList<>();

            for (String cellValue : cellValues) {
                String text = config.getTables().isTrimCellValues() ? cellValue.trim() : cellValue;
                row.add(new TableEntity.TableCell(text));
            }

            if (!row.isEmpty()) {
                if (i == 0 && config.getTables().isDetectHeaders()) {
                    headers = row.stream().map(TableEntity.TableCell::getText).toList();
                } else {
                    rows.add(row);
                }
            }
        }

        // Check minimum requirements
        if (rows.size() < config.getTables().getMinRows()) {
            return null;
        }

        return TableEntity.builder()
                .id(UUID.randomUUID().toString())
                .type(DocumentEntity.EntityType.TABLE)
                .rows(rows)
                .headers(headers)
                .confidence(1.0)
                .build();
    }

    /**
     * Parses a figure element.
     */
    private FigureEntity parseFigure(String content, int location) {
        // Check for nested caption
        String caption = content.trim();
        Matcher captionMatcher = NESTED_PATTERN.matcher(content);
        if (captionMatcher.find() && captionMatcher.group(1).equals("caption")) {
            caption = captionMatcher.group(2);
        }

        return FigureEntity.builder()
                .id(UUID.randomUUID().toString())
                .type(DocumentEntity.EntityType.FIGURE)
                .caption(caption.isEmpty() ? null : caption)
                .figureType(FigureEntity.FigureType.UNKNOWN)
                .confidence(1.0)
                .build();
    }

    /**
     * Parses a formula element.
     */
    private FormulaEntity parseFormula(String content, int location) {
        String latex = content.trim();

        // Check for nested latex tag
        Matcher latexMatcher = NESTED_PATTERN.matcher(content);
        if (latexMatcher.find() && latexMatcher.group(1).equals("latex")) {
            latex = latexMatcher.group(2);
        }

        return FormulaEntity.builder()
                .id(UUID.randomUUID().toString())
                .type(DocumentEntity.EntityType.FORMULA)
                .latex(latex)
                .inline(false)  // DocTags typically has block formulas
                .confidence(1.0)
                .build();
    }

    /**
     * Parses a code element.
     */
    private CodeEntity parseCode(String content, int location) {
        String code = content.trim();
        String language = null;

        // Check for language attribute
        Matcher langMatcher = NESTED_PATTERN.matcher(content);
        if (langMatcher.find() && langMatcher.group(1).equals("lang")) {
            language = langMatcher.group(2);
        }

        return CodeEntity.builder()
                .id(UUID.randomUUID().toString())
                .type(DocumentEntity.EntityType.CODE)
                .code(code)
                .language(language)
                .confidence(1.0)
                .build();
    }

    /**
     * Parses a list element.
     */
    private ListEntity parseList(String content, int location) {
        List<String> items = new ArrayList<>();

        // Check for nested item tags
        Pattern itemPattern = Pattern.compile("<item>([^<]*)</item>");
        Matcher itemMatcher = itemPattern.matcher(content);

        while (itemMatcher.find()) {
            items.add(itemMatcher.group(1).trim());
        }

        // If no item tags, try splitting by newlines
        if (items.isEmpty()) {
            for (String line : content.split("\\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    // Remove common list markers
                    trimmed = trimmed.replaceFirst("^[-*+•]\\s*", "");
                    trimmed = trimmed.replaceFirst("^\\d+[.)]\\s*", "");
                    items.add(trimmed);
                }
            }
        }

        if (items.isEmpty()) {
            return null;
        }

        // Check if ordered based on content patterns
        boolean ordered = content.matches("(?s).*\\d+[.)].*");

        return ListEntity.builder()
                .id(UUID.randomUUID().toString())
                .type(DocumentEntity.EntityType.LIST)
                .items(items)
                .ordered(ordered)
                .confidence(1.0)
                .build();
    }

    /**
     * Parses a heading element.
     */
    private HeadingEntity parseHeading(String content, int location, String tagName) {
        String text = content.trim();
        int level = 1;

        // Determine level based on tag
        if (tagName.equals("title")) {
            level = 1;
        } else if (tagName.equals("section")) {
            // Count section nesting or use location as hint
            level = 2;
        }

        // Check for nested level indicator
        Matcher levelMatcher = NESTED_PATTERN.matcher(content);
        if (levelMatcher.find() && levelMatcher.group(1).equals("level")) {
            try {
                level = Integer.parseInt(levelMatcher.group(2));
            } catch (NumberFormatException ignored) {}
        }

        return HeadingEntity.builder()
                .id(UUID.randomUUID().toString())
                .type(DocumentEntity.EntityType.HEADING)
                .text(text)
                .level(level)
                .confidence(1.0)
                .build();
    }

    /**
     * Extracts plain text from DocTags.
     */
    public String extractText(String docTags) {
        StringBuilder text = new StringBuilder();

        Matcher matcher = ELEMENT_PATTERN.matcher(docTags);
        while (matcher.find()) {
            String tagName = matcher.group(1).toLowerCase();
            String content = matcher.group(3);

            // Skip certain element types for text extraction
            if (!tagName.equals("table") && !tagName.equals("figure") && !tagName.equals("code")) {
                // Remove nested tags
                String plainContent = content.replaceAll("<[^>]+>", "").trim();
                if (!plainContent.isEmpty()) {
                    text.append(plainContent).append("\n\n");
                }
            }
        }

        return text.toString().trim();
    }
}
