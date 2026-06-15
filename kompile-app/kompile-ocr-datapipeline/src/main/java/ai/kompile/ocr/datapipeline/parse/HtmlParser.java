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
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Parses HTML output (e.g., from PaddleOCR SLANet) into document entities.
 */
public class HtmlParser {

    private static final Logger log = LoggerFactory.getLogger(HtmlParser.class);

    /**
     * Parses HTML content into entities.
     */
    public List<DocumentEntity> parse(String html, OutputParseConfig config) {
        List<DocumentEntity> entities = new ArrayList<>();
        Document doc = Jsoup.parse(html);

        // Extract tables
        for (Element table : doc.select("table")) {
            TableEntity tableEntity = parseTable(table, config.getTables());
            if (tableEntity != null) {
                entities.add(tableEntity);
            }
        }

        // Extract figures
        if (config.isExtractFigures()) {
            for (Element figure : doc.select("figure, img")) {
                FigureEntity figureEntity = parseFigure(figure);
                if (figureEntity != null) {
                    entities.add(figureEntity);
                }
            }
        }

        // Extract code blocks
        if (config.isExtractCode()) {
            for (Element code : doc.select("pre, code")) {
                CodeEntity codeEntity = parseCode(code);
                if (codeEntity != null) {
                    entities.add(codeEntity);
                }
            }
        }

        // Extract lists
        if (config.isExtractLists()) {
            for (Element list : doc.select("ul, ol")) {
                ListEntity listEntity = parseList(list);
                if (listEntity != null) {
                    entities.add(listEntity);
                }
            }
        }

        // Extract headings
        if (config.isExtractHeadings()) {
            for (Element heading : doc.select("h1, h2, h3, h4, h5, h6")) {
                HeadingEntity headingEntity = parseHeading(heading);
                if (headingEntity != null) {
                    entities.add(headingEntity);
                }
            }
        }

        return entities;
    }

    /**
     * Parses an HTML table element.
     */
    private TableEntity parseTable(Element tableEl, TableParseConfig config) {
        List<List<TableEntity.TableCell>> rows = new ArrayList<>();
        List<String> headers = new ArrayList<>();

        // Parse header row
        Elements headerCells = tableEl.select("thead tr th, thead tr td");
        if (headerCells.isEmpty()) {
            // Try first row if no thead
            headerCells = tableEl.select("tr:first-child th");
        }

        if (!headerCells.isEmpty() && config.isDetectHeaders()) {
            for (Element th : headerCells) {
                headers.add(config.isTrimCellValues() ? th.text().trim() : th.text());
            }
        }

        // Parse body rows
        Elements bodyRows = tableEl.select("tbody tr");
        if (bodyRows.isEmpty()) {
            bodyRows = tableEl.select("tr");
        }

        int startIndex = (!headers.isEmpty() || tableEl.select("thead").isEmpty()) ? 0 : 0;
        // Skip header row if we already extracted headers from first row
        if (headers.isEmpty() && !bodyRows.isEmpty()) {
            Element firstRow = bodyRows.first();
            if (firstRow != null && !firstRow.select("th").isEmpty()) {
                for (Element th : firstRow.select("th")) {
                    headers.add(config.isTrimCellValues() ? th.text().trim() : th.text());
                }
                startIndex = 1;
            }
        }

        for (int i = startIndex; i < bodyRows.size(); i++) {
            Element tr = bodyRows.get(i);
            List<TableEntity.TableCell> row = new ArrayList<>();

            for (Element cell : tr.select("td, th")) {
                String text = config.isTrimCellValues() ? cell.text().trim() : cell.text();
                if (config.isNormalizeLineBreaks()) {
                    text = text.replaceAll("\\s+", " ");
                }

                int colspan = 1;
                int rowspan = 1;

                if (config.isPreserveSpans()) {
                    String colspanAttr = cell.attr("colspan");
                    String rowspanAttr = cell.attr("rowspan");
                    if (!colspanAttr.isEmpty()) {
                        try {
                            colspan = Integer.parseInt(colspanAttr);
                        } catch (NumberFormatException e) {
                            log.debug("Invalid colspan attribute '{}': {}", colspanAttr, e.getMessage());
                        }
                    }
                    if (!rowspanAttr.isEmpty()) {
                        try {
                            rowspan = Integer.parseInt(rowspanAttr);
                        } catch (NumberFormatException e) {
                            log.debug("Invalid rowspan attribute '{}': {}", rowspanAttr, e.getMessage());
                        }
                    }
                }

                row.add(new TableEntity.TableCell(text, colspan, rowspan, cell.tagName().equals("th")));
            }

            if (!row.isEmpty() || config.isIncludeEmptyCells()) {
                rows.add(row);
            }
        }

        // Check minimum size requirements
        if (rows.size() < config.getMinRows()) {
            return null;
        }
        if (!rows.isEmpty() && rows.get(0).size() < config.getMinCols()) {
            return null;
        }

        // Apply max limits
        if (config.getMaxRows() > 0 && rows.size() > config.getMaxRows()) {
            rows = rows.subList(0, config.getMaxRows());
        }
        if (config.getMaxCols() > 0 && !rows.isEmpty()) {
            int maxCols = config.getMaxCols();
            rows = rows.stream()
                    .map(row -> row.size() > maxCols ? row.subList(0, maxCols) : row)
                    .toList();
            if (headers.size() > maxCols) {
                headers = headers.subList(0, maxCols);
            }
        }

        return TableEntity.builder()
                .id(UUID.randomUUID().toString())
                .type(DocumentEntity.EntityType.TABLE)
                .rows(rows)
                .headers(headers.isEmpty() ? null : headers)
                .htmlContent(tableEl.outerHtml())
                .confidence(1.0)
                .build();
    }

    /**
     * Parses a figure element.
     */
    private FigureEntity parseFigure(Element figureEl) {
        String caption = null;
        String altText = null;
        String src = null;

        if (figureEl.tagName().equals("figure")) {
            Element img = figureEl.selectFirst("img");
            Element figcaption = figureEl.selectFirst("figcaption");

            if (img != null) {
                altText = img.attr("alt");
                src = img.attr("src");
            }
            if (figcaption != null) {
                caption = figcaption.text();
            }
        } else {
            // Direct img element
            altText = figureEl.attr("alt");
            src = figureEl.attr("src");
        }

        if (caption == null && altText == null && src == null) {
            return null;
        }

        FigureEntity.FigureEntityBuilder builder = FigureEntity.builder()
                .id(UUID.randomUUID().toString())
                .type(DocumentEntity.EntityType.FIGURE)
                .caption(caption)
                .altText(altText)
                .confidence(1.0);

        // Extract image data if it's a data URL
        if (src != null && src.startsWith("data:image/")) {
            int commaIndex = src.indexOf(',');
            if (commaIndex > 0) {
                String metadata = src.substring(5, commaIndex);
                String[] mimeParts = metadata.split(";")[0].split("/");
                String format = mimeParts.length > 1 ? mimeParts[1] : "png";
                String data = src.substring(commaIndex + 1);
                builder.imageFormat(format);
                builder.imageData(data);
            }
        }

        return builder.build();
    }

    /**
     * Parses a code element.
     */
    private CodeEntity parseCode(Element codeEl) {
        String code = codeEl.text();
        if (code.isEmpty()) {
            return null;
        }

        String language = null;
        String className = codeEl.className();
        if (className != null && className.startsWith("language-")) {
            language = className.substring(9);
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
    private ListEntity parseList(Element listEl) {
        List<String> items = new ArrayList<>();
        for (Element li : listEl.select("li")) {
            items.add(li.text().trim());
        }

        if (items.isEmpty()) {
            return null;
        }

        return ListEntity.builder()
                .id(UUID.randomUUID().toString())
                .type(DocumentEntity.EntityType.LIST)
                .items(items)
                .ordered(listEl.tagName().equals("ol"))
                .confidence(1.0)
                .build();
    }

    /**
     * Parses a heading element.
     */
    private HeadingEntity parseHeading(Element headingEl) {
        String text = headingEl.text().trim();
        if (text.isEmpty()) {
            return null;
        }

        int level = Integer.parseInt(headingEl.tagName().substring(1));

        return HeadingEntity.builder()
                .id(UUID.randomUUID().toString())
                .type(DocumentEntity.EntityType.HEADING)
                .text(text)
                .level(level)
                .confidence(1.0)
                .build();
    }

    /**
     * Extracts plain text from HTML.
     */
    public String extractText(String html) {
        Document doc = Jsoup.parse(html);
        return doc.text();
    }
}
