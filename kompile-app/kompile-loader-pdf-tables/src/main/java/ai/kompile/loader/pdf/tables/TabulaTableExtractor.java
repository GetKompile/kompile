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

package ai.kompile.loader.pdf.tables;

import ai.kompile.core.structured.TableDocument;
import ai.kompile.core.structured.TableMetadata;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import technology.tabula.ObjectExtractor;
import technology.tabula.Page;
import technology.tabula.RectangularTextContainer;
import technology.tabula.Table;
import technology.tabula.extractors.BasicExtractionAlgorithm;
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Extracts tables from PDF documents using Tabula-java.
 *
 * <p>Supports two extraction modes:</p>
 * <ul>
 *   <li><b>Lattice (Spreadsheet)</b> - For tables with visible cell borders</li>
 *   <li><b>Stream (Basic)</b> - For tables without visible borders, using whitespace</li>
 * </ul>
 *
 * <p>The extractor tries lattice mode first, falling back to stream mode if no tables found.</p>
 */
@Component
public class TabulaTableExtractor {

    private static final Logger logger = LoggerFactory.getLogger(TabulaTableExtractor.class);

    /**
     * Extraction mode for tables.
     */
    public enum ExtractionMode {
        /** Tables with visible cell borders */
        LATTICE,
        /** Tables without borders, detected by whitespace */
        STREAM,
        /** Try lattice first, fall back to stream */
        AUTO
    }

    private ExtractionMode defaultMode = ExtractionMode.AUTO;
    private int minRows = 2; // Minimum rows to consider as a table
    private int minCols = 2; // Minimum columns to consider as a table

    /**
     * Extracts all tables from a PDF file.
     *
     * @param pdfFile The PDF file to extract tables from
     * @return List of TableDocument objects, one per table
     * @throws IOException If the PDF cannot be read
     */
    public List<TableDocument> extractTables(File pdfFile) throws IOException {
        return extractTables(pdfFile, defaultMode);
    }

    /**
     * Extracts all tables from a PDF file using the specified mode.
     *
     * @param pdfFile The PDF file to extract tables from
     * @param mode    The extraction mode to use
     * @return List of TableDocument objects, one per table
     * @throws IOException If the PDF cannot be read
     */
    public List<TableDocument> extractTables(File pdfFile, ExtractionMode mode) throws IOException {
        List<TableDocument> allTables = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            ObjectExtractor extractor = new ObjectExtractor(document);
            SpreadsheetExtractionAlgorithm latticeAlgo = new SpreadsheetExtractionAlgorithm();
            BasicExtractionAlgorithm streamAlgo = new BasicExtractionAlgorithm();

            int totalPages = document.getNumberOfPages();
            logger.debug("Extracting tables from PDF with {} pages: {}", totalPages, pdfFile.getName());

            for (int pageNum = 1; pageNum <= totalPages; pageNum++) {
                try {
                    Page page = extractor.extract(pageNum);
                    List<Table> pageTables = extractTablesFromPage(page, mode, latticeAlgo, streamAlgo);

                    for (int tableIdx = 0; tableIdx < pageTables.size(); tableIdx++) {
                        Table table = pageTables.get(tableIdx);

                        // Filter out tables that are too small
                        if (table.getRowCount() < minRows || table.getColCount() < minCols) {
                            logger.trace("Skipping small table on page {} ({}x{})",
                                pageNum, table.getRowCount(), table.getColCount());
                            continue;
                        }

                        TableDocument tableDoc = convertToTableDocument(
                            table, pageNum, tableIdx, pdfFile,
                            mode == ExtractionMode.LATTICE || mode == ExtractionMode.AUTO);
                        allTables.add(tableDoc);
                    }
                } catch (Exception e) {
                    logger.warn("Error extracting tables from page {}: {}", pageNum, e.getMessage());
                }
            }
        }

        logger.info("Extracted {} tables from {}", allTables.size(), pdfFile.getName());
        return allTables;
    }

    private List<Table> extractTablesFromPage(Page page, ExtractionMode mode,
                                              SpreadsheetExtractionAlgorithm latticeAlgo,
                                              BasicExtractionAlgorithm streamAlgo) {
        List<Table> tables = new ArrayList<>();

        switch (mode) {
            case LATTICE:
                tables = latticeAlgo.extract(page);
                break;

            case STREAM:
                tables = streamAlgo.extract(page);
                break;

            case AUTO:
            default:
                // Try lattice first (better for bordered tables)
                tables = latticeAlgo.extract(page);

                // Fall back to stream if no tables found
                if (tables.isEmpty()) {
                    tables = streamAlgo.extract(page);
                }
                break;
        }

        return tables;
    }

    private TableDocument convertToTableDocument(Table table, int pageNum, int tableIndex,
                                                   File sourceFile, boolean usedLattice) {
        String markdown = formatAsMarkdown(table);
        List<String> headers = extractHeaders(table);

        String extractionMethod = usedLattice ? "tabula-lattice" : "tabula-stream";

        TableMetadata metadata = TableMetadata.builder()
            .rowCount(table.getRowCount() - 1) // Exclude header row
            .columnCount(table.getColCount())
            .columnHeaders(headers)
            .pageNumber(pageNum)
            .tableIndex(tableIndex)
            .extractionMethod(extractionMethod)
            .build();

        String tableId = generateTableId(sourceFile, pageNum, tableIndex);

        return TableDocument.builder()
            .id(tableId)
            .markdownContent(markdown)
            .tableMetadata(metadata)
            .sourcePath(sourceFile.getAbsolutePath())
            .sourceFilename(sourceFile.getName())
            .build();
    }

    private String generateTableId(File sourceFile, int pageNum, int tableIndex) {
        String baseName = sourceFile.getName();
        if (baseName.contains(".")) {
            baseName = baseName.substring(0, baseName.lastIndexOf('.'));
        }
        return String.format("%s_p%d_t%d", baseName, pageNum, tableIndex);
    }

    /**
     * Formats a Tabula table as a Markdown table.
     */
    String formatAsMarkdown(Table table) {
        StringBuilder md = new StringBuilder();
        List<List<RectangularTextContainer>> rows = table.getRows();

        if (rows.isEmpty()) {
            return "";
        }

        // Header row
        List<RectangularTextContainer> headerRow = rows.get(0);
        md.append("|");
        for (RectangularTextContainer cell : headerRow) {
            String cellText = cell.getText().trim().replace("|", "\\|");
            md.append(" ").append(cellText).append(" |");
        }
        md.append("\n");

        // Separator row
        md.append("|");
        for (int i = 0; i < headerRow.size(); i++) {
            md.append("---|");
        }
        md.append("\n");

        // Data rows
        for (int i = 1; i < rows.size(); i++) {
            md.append("|");
            List<RectangularTextContainer> row = rows.get(i);
            for (RectangularTextContainer cell : row) {
                String cellText = cell.getText().trim().replace("|", "\\|");
                // Replace newlines within cells with spaces
                cellText = cellText.replace("\n", " ").replace("\r", "");
                md.append(" ").append(cellText).append(" |");
            }
            md.append("\n");
        }

        return md.toString();
    }

    /**
     * Extracts column headers from the first row of a table.
     */
    List<String> extractHeaders(Table table) {
        List<List<RectangularTextContainer>> rows = table.getRows();
        if (rows.isEmpty()) {
            return List.of();
        }

        return rows.get(0).stream()
            .map(cell -> cell.getText().trim())
            .collect(Collectors.toList());
    }

    // Configuration setters

    public void setDefaultMode(ExtractionMode defaultMode) {
        this.defaultMode = defaultMode;
    }

    public void setMinRows(int minRows) {
        this.minRows = minRows;
    }

    public void setMinCols(int minCols) {
        this.minCols = minCols;
    }

    public ExtractionMode getDefaultMode() {
        return defaultMode;
    }

    public int getMinRows() {
        return minRows;
    }

    public int getMinCols() {
        return minCols;
    }
}
