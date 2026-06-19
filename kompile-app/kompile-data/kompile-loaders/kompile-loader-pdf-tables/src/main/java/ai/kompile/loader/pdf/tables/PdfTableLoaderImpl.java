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

import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.table.TableCellGraphBuilder;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.loaders.PdfProcessingConfig;
import ai.kompile.core.structured.TableDocument;
import ai.kompile.core.structured.TableSummaryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Document loader that extracts tables from PDF files.
 *
 * <p>This loader uses Tabula-java to detect and extract tables from PDFs,
 * converting them to markdown format for LLM compatibility.</p>
 *
 * <p>Configuration is now per-request via PdfProcessingConfig from the UI.
 * No Spring properties are used.</p>
 *
 * <p>Storage modes:</p>
 * <ul>
 *   <li><b>inline</b> - Tables are embedded in the document text</li>
 *   <li><b>separate</b> - Each table is a separate document with its own embedding</li>
 *   <li><b>both</b> - Both inline and separate documents are created</li>
 * </ul>
 */
@Component
public class PdfTableLoaderImpl implements DocumentLoader {

    private static final Logger logger = LoggerFactory.getLogger(PdfTableLoaderImpl.class);
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf");

    /**
     * Storage mode for extracted tables.
     */
    public enum TableStorage {
        /** Tables are embedded inline with document text */
        INLINE,
        /** Each table is a separate searchable document */
        SEPARATE,
        /** Both inline and separate documents (maximum retrieval) */
        BOTH,
        /** Skip table extraction */
        NONE
    }

    private final TabulaTableExtractor tableExtractor;
    private final TableSummaryService summaryService;

    // Runtime configurable (no Spring @Value - set via UI/API)
    private String tableStorageConfig = "both";

    public PdfTableLoaderImpl(TabulaTableExtractor tableExtractor,
                               @Autowired(required = false) TableSummaryService summaryService) {
        this.tableExtractor = tableExtractor;
        this.summaryService = summaryService;
    }

    /**
     * Sets the table storage mode. Can be called from UI/API.
     */
    public void setTableStorageConfig(String tableStorageConfig) {
        this.tableStorageConfig = tableStorageConfig;
    }

    /**
     * Gets the current table storage configuration.
     */
    public String getTableStorageConfig() {
        return tableStorageConfig;
    }

    @Override
    public String getName() {
        return "PDF Table Loader";
    }

    @Override
    public boolean supports(DocumentSourceDescriptor sourceDescriptor) {
        if (sourceDescriptor.getType() != DocumentSourceDescriptor.SourceType.FILE) {
            return false;
        }

        String path = sourceDescriptor.getPathOrUrl();
        if (path == null) {
            return false;
        }

        String lowerPath = path.toLowerCase();
        return SUPPORTED_EXTENSIONS.stream().anyMatch(lowerPath::endsWith);
    }

    @Override
    public List<Document> load(DocumentSourceDescriptor sourceDescriptor) throws Exception {
        return load(sourceDescriptor, (PdfProcessingConfig) null);
    }

    /**
     * Loads documents with per-request PDF processing configuration from the UI.
     *
     * @param sourceDescriptor Source descriptor
     * @param pdfConfig PDF processing configuration (null for defaults)
     * @return List of table documents
     */
    public List<Document> load(DocumentSourceDescriptor sourceDescriptor, PdfProcessingConfig pdfConfig) throws Exception {
        if (!supports(sourceDescriptor)) {
            throw new IllegalArgumentException("PdfTableLoader only supports PDF files");
        }

        File file = new File(sourceDescriptor.getPathOrUrl());
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("File does not exist or is not a regular file: " + sourceDescriptor.getPathOrUrl());
        }

        List<Document> documents = new ArrayList<>();

        // Use per-request config if provided, otherwise fall back to instance config
        String storageConfigToUse = tableStorageConfig;
        if (pdfConfig != null && pdfConfig.getTableStorageMode() != null) {
            storageConfigToUse = pdfConfig.getTableStorageMode().name().toLowerCase();

            // Check if table extraction is disabled
            if (pdfConfig.getTableStorageMode() == PdfProcessingConfig.TableStorageMode.NONE ||
                !pdfConfig.isExtractTables()) {
                logger.debug("Table extraction disabled by config for: {}", file.getName());
                return documents;
            }
        }

        TableStorage storageMode = parseStorageMode(storageConfigToUse);

        try {
            List<TableDocument> tables = tableExtractor.extractTables(file);

            if (tables.isEmpty()) {
                logger.debug("No tables found in PDF: {}", file.getName());
                return documents;
            }

            logger.info("Extracted {} tables from PDF: {}", tables.size(), file.getName());

            // Generate summaries if service is available
            for (TableDocument table : tables) {
                if (summaryService != null && table.getSummary() == null) {
                    String summary = summaryService.generateSummary(table);
                    table = TableDocument.builder()
                        .id(table.getId())
                        .markdownContent(table.getMarkdownContent())
                        .summary(summary)
                        .tableMetadata(table.getTableMetadata())
                        .sourceMetadata(table.getSourceMetadata())
                        .build();
                }

                // Add documents based on storage mode
                switch (storageMode) {
                    case SEPARATE:
                        documents.add(createSearchDocument(table, sourceDescriptor));
                        break;

                    case INLINE:
                        documents.add(createInlineDocument(table, sourceDescriptor));
                        break;

                    case BOTH:
                    default:
                        // Add both: one for search (summary), one inline (full content)
                        documents.add(createSearchDocument(table, sourceDescriptor));
                        documents.add(createInlineDocument(table, sourceDescriptor));
                        break;
                }
            }

        } catch (Exception e) {
            logger.error("Error extracting tables from PDF {}: {}", file.getName(), e.getMessage(), e);

            // Create error document
            Document errorDoc = new Document("[Error: Failed to extract tables from PDF. " + e.getMessage() + "]");
            errorDoc.getMetadata().put("source", file.getAbsolutePath());
            errorDoc.getMetadata().put("fileName", file.getName());
            errorDoc.getMetadata().put("parseError", true);
            errorDoc.getMetadata().put("errorMessage", e.getMessage());
            errorDoc.getMetadata().put("loader", getName());
            documents.add(errorDoc);
        }

        return documents;
    }

    /**
     * Creates a document optimized for search (uses summary for embedding).
     */
    private Document createSearchDocument(TableDocument table, DocumentSourceDescriptor source) {
        // Use summary as the document text for embedding
        String summary = table.getSummary();
        if (summary == null || summary.isEmpty()) {
            summary = "Table with " + table.getTableMetadata().rowCount() + " rows and "
                    + table.getTableMetadata().columnCount() + " columns.";
        }

        Document doc = new Document(summary);

        // Add table metadata
        doc.getMetadata().put("content_type", "table");
        doc.getMetadata().put("table_id", table.getId());
        doc.getMetadata().put("full_table_content", table.getMarkdownContent());
        doc.getMetadata().put("table_row_count", table.getTableMetadata().rowCount());
        doc.getMetadata().put("table_column_count", table.getTableMetadata().columnCount());
        doc.getMetadata().put("table_headers", table.getTableMetadata().getHeadersAsString());
        doc.getMetadata().put("table_page_number", table.getTableMetadata().pageNumber());
        doc.getMetadata().put("table_index", table.getTableMetadata().tableIndex());
        doc.getMetadata().put("table_extraction_method", table.getTableMetadata().extractionMethod());
        doc.getMetadata().put("storage_type", "search");

        // Add source metadata
        addSourceMetadata(doc, table, source);

        return doc;
    }

    /**
     * Creates a document with full table content inline.
     */
    private Document createInlineDocument(TableDocument table, DocumentSourceDescriptor source) {
        // Use full markdown content as the document text
        Document doc = new Document(table.getMarkdownContent());

        // Add table metadata
        doc.getMetadata().put("content_type", "table");
        doc.getMetadata().put("table_id", table.getId());
        doc.getMetadata().put("table_summary", table.getSummary());
        doc.getMetadata().put("table_row_count", table.getTableMetadata().rowCount());
        doc.getMetadata().put("table_column_count", table.getTableMetadata().columnCount());
        doc.getMetadata().put("table_headers", table.getTableMetadata().getHeadersAsString());
        doc.getMetadata().put("table_page_number", table.getTableMetadata().pageNumber());
        doc.getMetadata().put("table_index", table.getTableMetadata().tableIndex());
        doc.getMetadata().put("table_extraction_method", table.getTableMetadata().extractionMethod());
        doc.getMetadata().put("storage_type", "inline");

        // Add source metadata
        addSourceMetadata(doc, table, source);

        return doc;
    }

    private void addSourceMetadata(Document doc, TableDocument table, DocumentSourceDescriptor source) {
        doc.getMetadata().put("source", source.getPathOrUrl());
        doc.getMetadata().put("fileName", new File(source.getPathOrUrl()).getName());
        doc.getMetadata().put("loader", getName());

        // Copy any additional source metadata
        if (table.getSourceMetadata() != null) {
            table.getSourceMetadata().forEach((key, value) -> {
                if (!doc.getMetadata().containsKey(key)) {
                    doc.getMetadata().put(key, value);
                }
            });
        }

        // Add source descriptor metadata if available
        if (source.getSourceId() != null) {
            doc.getMetadata().put("source_id", source.getSourceId());
        }
        if (source.getCollectionName() != null) {
            doc.getMetadata().put("collection_name", source.getCollectionName());
        }

        // Cell-level graph so PDF tables get structured TABLE/CELL nodes like CSV/Excel/HTML.
        // With single-owner dedup in ContentTypeRouter, persistGraphJson owns the TABLE node when
        // this graph is present (no duplicate alongside the promote path).
        attachTableGraph(doc, table);
    }

    /**
     * Builds a {@link TableCellGraphBuilder} cell graph from the table's markdown and stores it under
     * {@code tableGraph} so PDF tables surface as structured TABLE/CELL nodes in the index browser.
     */
    private void attachTableGraph(Document doc, TableDocument table) {
        List<List<String>> rows = parseMarkdownRows(table.getMarkdownContent());
        if (rows.isEmpty()) {
            return;
        }
        var meta = table.getTableMetadata();
        Graph graph = new TableCellGraphBuilder()
                .namespace("pdf:" + table.getId())
                .tableName("Table p" + meta.pageNumber() + " #" + (meta.tableIndex() + 1))
                .rows(rows)
                .firstRowIsHeader(true)
                .build();
        if (!graph.getEntities().isEmpty()) {
            doc.getMetadata().put(GraphConstants.META_TABLE_GRAPH, TableCellGraphBuilder.toJson(graph));
        }
    }

    /** Parse a GFM markdown table back into rows (header + data), dropping the separator row. */
    private static List<List<String>> parseMarkdownRows(String markdown) {
        List<List<String>> rows = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return rows;
        }
        for (String line : markdown.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || !t.contains("|")) {
                continue;
            }
            // Drop separator rows such as |---|:--:|
            if (t.chars().allMatch(c -> c == '|' || c == '-' || c == ':' || c == ' ')) {
                continue;
            }
            rows.add(splitMarkdownRow(t));
        }
        return rows;
    }

    private static List<String> splitMarkdownRow(String line) {
        String t = line.trim();
        if (t.startsWith("|")) {
            t = t.substring(1);
        }
        if (t.endsWith("|")) {
            t = t.substring(0, t.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        for (String c : t.split("\\|", -1)) {
            cells.add(c.replace("\\|", "|").trim());
        }
        return cells;
    }

    private TableStorage parseStorageMode(String config) {
        if (config == null) {
            return TableStorage.BOTH;
        }
        try {
            return TableStorage.valueOf(config.toUpperCase());
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid table storage mode '{}', defaulting to BOTH", config);
            return TableStorage.BOTH;
        }
    }

}
