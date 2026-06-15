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

package ai.kompile.tool.tablesearch;

import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.mcp.optimization.McpOptimizationConfigProvider;
import ai.kompile.core.mcp.optimization.ResultReferenceCache;
import ai.kompile.core.retrievers.DocumentRetriever;
import ai.kompile.core.retrievers.NoOpDocumentRetrieverImpl;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.core.structured.MultiVectorDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP tool for searching tables and entries within indexed documents.
 *
 * <p>Provides structured access to table data extracted from documents in the
 * vector store. Models can discover tables, search across them semantically,
 * retrieve specific tables by ID, and search for rows matching criteria.</p>
 */
@Component
public class TableSearchToolImpl {

    private static final Logger logger = LoggerFactory.getLogger(TableSearchToolImpl.class);

    private static final String CONTENT_TYPE_KEY = "content_type";
    private static final String CONTENT_TYPE_TABLE = "table";
    private static final String FULL_TABLE_CONTENT_KEY = "full_table_content";
    private static final String TABLE_HEADERS_KEY = "table_headers";
    private static final String TABLE_ROW_COUNT_KEY = "table_row_count";
    private static final String TABLE_COLUMN_COUNT_KEY = "table_column_count";
    private static final String TABLE_INDEX_KEY = "table_index";
    private static final String TABLE_PAGE_NUMBER_KEY = "table_page_number";
    private static final String TABLE_ID_KEY = "table_id";

    private final VectorStore vectorStore;
    private final DocumentRetriever documentRetriever;
    private final McpOptimizationConfigProvider optimizationProvider;
    private final ResultReferenceCache resultCache;

    @Autowired
    public TableSearchToolImpl(
            @Autowired(required = false) VectorStore vectorStore,
            @Autowired(required = false) List<DocumentRetriever> documentRetrievers,
            @Autowired(required = false) McpOptimizationConfigProvider optimizationProvider,
            @Autowired(required = false) ResultReferenceCache resultCache) {
        this.vectorStore = vectorStore;
        this.documentRetriever = resolveRetriever(documentRetrievers);
        this.optimizationProvider = optimizationProvider != null
                ? optimizationProvider
                : McpOptimizationConfigProvider.ofDefaults();
        this.resultCache = resultCache;
    }

    private static DocumentRetriever resolveRetriever(List<DocumentRetriever> retrievers) {
        if (retrievers == null || retrievers.isEmpty()) {
            return null;
        }
        for (DocumentRetriever r : retrievers) {
            if (!(r instanceof NoOpDocumentRetrieverImpl)) {
                return r;
            }
        }
        return retrievers.get(0);
    }

    // ── Input records ────────────────────────────────────────────────────────

    public record ListTablesInput(Integer offset, Integer limit) {}

    public record SearchTablesInput(String query, Integer maxResults) {}

    public record GetTableInput(String tableId) {}

    public record SearchTableEntriesInput(String tableId, String query) {}

    public record AnalyzeTableInput(String tableId, String column) {}

    public record FilterTableInput(String tableId, String column, String operator, String value) {}

    public record SortTableInput(String tableId, String column, Boolean descending) {}

    public record ExtractColumnsInput(String tableId, List<String> columns) {}

    public record ExportTableInput(String tableId, String format) {}

    // ── Tool methods ─────────────────────────────────────────────────────────

    @Tool(name = "list_tables",
          description = "Lists all tables that have been indexed from documents. " +
                        "Returns table metadata including headers, dimensions, source file, and page number. " +
                        "Use offset/limit for pagination.")
    public Map<String, Object> listTables(ListTablesInput input) {
        logger.info("TableSearchTool: listing tables, offset={}, limit={}",
                input.offset(), input.limit());

        if (vectorStore == null) {
            return Map.of("error", "No vector store configured.", "tables", Collections.emptyList());
        }

        int offset = input.offset() != null ? Math.max(0, input.offset()) : 0;
        int limit = input.limit() != null ? Math.min(Math.max(1, input.limit()), 100) : 20;

        try {
            List<Map<String, Object>> allDocs = vectorStore.listVectorDocuments(offset, limit + offset);
            // Filter down to table-type documents, then drop those before offset
            List<Map<String, Object>> tables = new ArrayList<>();
            int seen = 0;
            // Scan a wider window to find enough tables
            int scanOffset = 0;
            int scanBatch = 500;
            int collected = 0;
            int skipped = 0;

            while (collected < limit) {
                List<Map<String, Object>> batch = vectorStore.listVectorDocuments(scanOffset, scanBatch);
                if (batch.isEmpty()) break;

                for (Map<String, Object> doc : batch) {
                    if (!isTableDocument(doc)) continue;
                    if (skipped < offset) {
                        skipped++;
                        continue;
                    }
                    tables.add(toTableSummary(doc));
                    collected++;
                    if (collected >= limit) break;
                }
                scanOffset += scanBatch;
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("tables", tables);
            result.put("count", tables.size());
            result.put("offset", offset);
            result.put("limit", limit);
            return result;

        } catch (Exception e) {
            logger.error("TableSearchTool: error listing tables: {}", e.getMessage(), e);
            return Map.of("error", "Failed to list tables: " + e.getMessage(),
                          "tables", Collections.emptyList());
        }
    }

    @Tool(name = "search_tables",
          description = "Searches for tables using a semantic query. " +
                        "Returns tables whose content or summary matches the query, ranked by relevance. " +
                        "Each result includes the full markdown table content, headers, dimensions, and source info.")
    public Map<String, Object> searchTables(SearchTablesInput input) {
        logger.info("TableSearchTool: searching tables with query='{}', maxResults={}",
                input.query(), input.maxResults());

        if (input.query() == null || input.query().trim().isEmpty()) {
            return Map.of("error", "Query cannot be empty.", "tables", Collections.emptyList());
        }

        if (documentRetriever == null) {
            return Map.of("error", "No document retriever configured.", "tables", Collections.emptyList());
        }

        int maxResults = input.maxResults() != null ? Math.min(Math.max(1, input.maxResults()), 20) : 5;

        try {
            // Retrieve more than needed since we filter for tables only
            int fetchCount = maxResults * 4;
            List<RetrievedDoc> docs = documentRetriever.retrieveWithDetails(input.query(), fetchCount);

            List<Map<String, Object>> tables = new ArrayList<>();
            for (RetrievedDoc doc : docs) {
                if (!isTableDoc(doc)) continue;
                tables.add(toTableResult(doc));
                if (tables.size() >= maxResults) break;
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("query", input.query());
            result.put("tables", tables);
            result.put("count", tables.size());
            return result;

        } catch (Exception e) {
            logger.error("TableSearchTool: error searching tables: {}", e.getMessage(), e);
            return Map.of("error", "Failed to search tables: " + e.getMessage(),
                          "query", input.query(),
                          "tables", Collections.emptyList());
        }
    }

    @Tool(name = "get_table",
          description = "Retrieves a specific table by its document ID. " +
                        "Returns the full markdown table content, column headers, dimensions, and all metadata.")
    public Map<String, Object> getTable(GetTableInput input) {
        logger.info("TableSearchTool: getting table by id='{}'", input.tableId());

        if (input.tableId() == null || input.tableId().trim().isEmpty()) {
            return Map.of("error", "tableId is required.");
        }

        if (vectorStore == null) {
            return Map.of("error", "No vector store configured.");
        }

        try {
            Map<String, Object> doc = vectorStore.getVectorDocument(input.tableId());
            if (doc == null) {
                return Map.of("error", "Table not found with id: " + input.tableId());
            }

            if (!isTableDocument(doc)) {
                return Map.of("error", "Document with id '" + input.tableId() + "' is not a table.");
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("table", toTableDetail(doc));
            return result;

        } catch (Exception e) {
            logger.error("TableSearchTool: error getting table {}: {}", input.tableId(), e.getMessage(), e);
            return Map.of("error", "Failed to get table: " + e.getMessage());
        }
    }

    @Tool(name = "search_table_entries",
          description = "Searches for rows within a specific table that match a text query. " +
                        "The table is identified by its document ID. The query is matched against " +
                        "row content as a simple text search. Returns matching rows as text lines.")
    public Map<String, Object> searchTableEntries(SearchTableEntriesInput input) {
        logger.info("TableSearchTool: searching entries in table='{}' with query='{}'",
                input.tableId(), input.query());

        if (input.tableId() == null || input.tableId().trim().isEmpty()) {
            return Map.of("error", "tableId is required.");
        }
        if (input.query() == null || input.query().trim().isEmpty()) {
            return Map.of("error", "query is required.");
        }

        if (vectorStore == null) {
            return Map.of("error", "No vector store configured.");
        }

        try {
            Map<String, Object> doc = vectorStore.getVectorDocument(input.tableId());
            if (doc == null) {
                return Map.of("error", "Table not found with id: " + input.tableId());
            }

            String tableContent = extractTableContent(doc);
            if (tableContent == null || tableContent.isEmpty()) {
                return Map.of("error", "No table content found for id: " + input.tableId());
            }

            // Parse markdown table into rows and search
            List<String> allRows = parseTableRows(tableContent);
            String headerRow = allRows.isEmpty() ? "" : allRows.get(0);
            String queryLower = input.query().toLowerCase();

            List<String> matchingRows = new ArrayList<>();
            for (int i = 0; i < allRows.size(); i++) {
                String row = allRows.get(i);
                // Always include header and separator rows for context
                if (i <= 1) {
                    matchingRows.add(row);
                    continue;
                }
                if (row.toLowerCase().contains(queryLower)) {
                    matchingRows.add(row);
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("tableId", input.tableId());
            result.put("query", input.query());
            result.put("header", headerRow);
            result.put("matchingRows", matchingRows);
            result.put("matchCount", Math.max(0, matchingRows.size() - 2)); // exclude header + separator
            result.put("totalRows", Math.max(0, allRows.size() - 2));
            return result;

        } catch (Exception e) {
            logger.error("TableSearchTool: error searching entries in table {}: {}",
                    input.tableId(), e.getMessage(), e);
            return Map.of("error", "Failed to search table entries: " + e.getMessage());
        }
    }

    // ── Extended analysis tools ────────────────────────────────────────────

    @Tool(name = "analyze_table",
          description = "Analyzes a table's structure and computes column statistics. " +
                        "If column is specified, returns detailed stats for that column (min, max, mean, sum for numeric columns; " +
                        "unique values, most frequent for text columns). If column is omitted, returns an overview of all columns.")
    public Map<String, Object> analyzeTable(AnalyzeTableInput input) {
        logger.info("TableSearchTool: analyzing table='{}', column='{}'", input.tableId(), input.column());

        if (input.tableId() == null || input.tableId().trim().isEmpty()) {
            return Map.of("error", "tableId is required.");
        }

        MarkdownTableParser.ParsedTable table = getAndParseTable(input.tableId());
        if (table == null) {
            return Map.of("error", "Table not found or has no content: " + input.tableId());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tableId", input.tableId());
        result.put("row_count", table.getRowCount());
        result.put("column_count", table.getColumnCount());
        result.put("headers", table.getHeaders());

        if (input.column() != null && !input.column().trim().isEmpty()) {
            int colIdx = table.findColumnIndex(input.column());
            if (colIdx < 0) {
                return Map.of("error", "Column '" + input.column() + "' not found. Available: " + table.getHeaders());
            }

            String header = table.getHeaders().get(colIdx);
            List<String> values = table.getColumn(header);
            result.put("column", header);
            result.put("column_stats", computeColumnStats(values));
        } else {
            // Overview of all columns
            List<Map<String, Object>> columnOverviews = new ArrayList<>();
            for (String header : table.getHeaders()) {
                List<String> values = table.getColumn(header);
                Map<String, Object> overview = new LinkedHashMap<>();
                overview.put("column", header);
                overview.put("stats", computeColumnStats(values));
                columnOverviews.add(overview);
            }
            result.put("columns", columnOverviews);
        }

        return result;
    }

    @Tool(name = "filter_table",
          description = "Filters table rows by a column condition and returns matching rows as a markdown table. " +
                        "Operators: eq (equals), neq (not equals), contains, gt (greater than), lt (less than), " +
                        "gte (>=), lte (<=). Comparison is case-insensitive for text, numeric for number columns.")
    public Map<String, Object> filterTable(FilterTableInput input) {
        logger.info("TableSearchTool: filtering table='{}', column='{}', op='{}', value='{}'",
                input.tableId(), input.column(), input.operator(), input.value());

        if (input.tableId() == null || input.tableId().trim().isEmpty()) {
            return Map.of("error", "tableId is required.");
        }
        if (input.column() == null || input.column().trim().isEmpty()) {
            return Map.of("error", "column is required.");
        }
        if (input.operator() == null || input.operator().trim().isEmpty()) {
            return Map.of("error", "operator is required. Use: eq, neq, contains, gt, lt, gte, lte");
        }
        if (input.value() == null) {
            return Map.of("error", "value is required.");
        }

        MarkdownTableParser.ParsedTable table = getAndParseTable(input.tableId());
        if (table == null) {
            return Map.of("error", "Table not found or has no content: " + input.tableId());
        }

        int colIdx = table.findColumnIndex(input.column());
        if (colIdx < 0) {
            return Map.of("error", "Column '" + input.column() + "' not found. Available: " + table.getHeaders());
        }

        String op = input.operator().trim().toLowerCase();
        String filterValue = input.value();
        List<List<String>> filtered = new ArrayList<>();

        for (List<String> row : table.getRows()) {
            String cellValue = colIdx < row.size() ? row.get(colIdx) : "";
            if (matchesFilter(cellValue, op, filterValue)) {
                filtered.add(row);
            }
        }

        MarkdownTableParser.ParsedTable filteredTable =
                new MarkdownTableParser.ParsedTable(table.getHeaders(), filtered);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tableId", input.tableId());
        result.put("filter", input.column() + " " + input.operator() + " " + input.value());
        result.put("matched_rows", filtered.size());
        result.put("total_rows", table.getRowCount());
        result.put("table", filteredTable.toMarkdown());
        return result;
    }

    @Tool(name = "sort_table",
          description = "Sorts table rows by a column and returns the sorted table as markdown. " +
                        "Numeric columns are sorted numerically, text columns alphabetically. " +
                        "Set descending=true for reverse order.")
    public Map<String, Object> sortTable(SortTableInput input) {
        logger.info("TableSearchTool: sorting table='{}', column='{}', desc={}",
                input.tableId(), input.column(), input.descending());

        if (input.tableId() == null || input.tableId().trim().isEmpty()) {
            return Map.of("error", "tableId is required.");
        }
        if (input.column() == null || input.column().trim().isEmpty()) {
            return Map.of("error", "column is required.");
        }

        MarkdownTableParser.ParsedTable table = getAndParseTable(input.tableId());
        if (table == null) {
            return Map.of("error", "Table not found or has no content: " + input.tableId());
        }

        int colIdx = table.findColumnIndex(input.column());
        if (colIdx < 0) {
            return Map.of("error", "Column '" + input.column() + "' not found. Available: " + table.getHeaders());
        }

        boolean desc = Boolean.TRUE.equals(input.descending());
        List<List<String>> sorted = new ArrayList<>(table.getRows());
        final int ci = colIdx;
        sorted.sort((a, b) -> {
            String va = ci < a.size() ? a.get(ci) : "";
            String vb = ci < b.size() ? b.get(ci) : "";
            int cmp = compareValues(va, vb);
            return desc ? -cmp : cmp;
        });

        MarkdownTableParser.ParsedTable sortedTable =
                new MarkdownTableParser.ParsedTable(table.getHeaders(), sorted);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tableId", input.tableId());
        result.put("sorted_by", input.column());
        result.put("descending", desc);
        result.put("table", sortedTable.toMarkdown());
        return result;
    }

    @Tool(name = "extract_columns",
          description = "Extracts specific columns from a table and returns a new table containing only those columns. " +
                        "Useful for narrowing wide tables to relevant columns before further analysis or export.")
    public Map<String, Object> extractColumns(ExtractColumnsInput input) {
        logger.info("TableSearchTool: extracting columns from table='{}', columns={}",
                input.tableId(), input.columns());

        if (input.tableId() == null || input.tableId().trim().isEmpty()) {
            return Map.of("error", "tableId is required.");
        }
        if (input.columns() == null || input.columns().isEmpty()) {
            return Map.of("error", "columns list is required.");
        }

        MarkdownTableParser.ParsedTable table = getAndParseTable(input.tableId());
        if (table == null) {
            return Map.of("error", "Table not found or has no content: " + input.tableId());
        }

        // Resolve column indices
        List<Integer> indices = new ArrayList<>();
        List<String> resolvedHeaders = new ArrayList<>();
        for (String col : input.columns()) {
            int idx = table.findColumnIndex(col);
            if (idx < 0) {
                return Map.of("error", "Column '" + col + "' not found. Available: " + table.getHeaders());
            }
            indices.add(idx);
            resolvedHeaders.add(table.getHeaders().get(idx));
        }

        // Extract column subsets
        List<List<String>> extractedRows = new ArrayList<>();
        for (List<String> row : table.getRows()) {
            List<String> newRow = new ArrayList<>();
            for (int idx : indices) {
                newRow.add(idx < row.size() ? row.get(idx) : "");
            }
            extractedRows.add(newRow);
        }

        MarkdownTableParser.ParsedTable extracted =
                new MarkdownTableParser.ParsedTable(resolvedHeaders, extractedRows);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tableId", input.tableId());
        result.put("extracted_columns", resolvedHeaders);
        result.put("row_count", extractedRows.size());
        result.put("table", extracted.toMarkdown());
        return result;
    }

    @Tool(name = "export_table",
          description = "Exports a table in a structured format for use in pipelines or code. " +
                        "Supported formats: 'csv' (comma-separated values), 'json' (array of row objects with typed values), " +
                        "'records' (array of column-keyed maps for scripting), 'markdown' (original markdown). " +
                        "JSON format auto-detects numeric values for typed access.")
    public Map<String, Object> exportTable(ExportTableInput input) {
        logger.info("TableSearchTool: exporting table='{}', format='{}'", input.tableId(), input.format());

        if (input.tableId() == null || input.tableId().trim().isEmpty()) {
            return Map.of("error", "tableId is required.");
        }

        String format = input.format() != null ? input.format().trim().toLowerCase() : "json";

        MarkdownTableParser.ParsedTable table = getAndParseTable(input.tableId());
        if (table == null) {
            return Map.of("error", "Table not found or has no content: " + input.tableId());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tableId", input.tableId());
        result.put("format", format);
        result.put("row_count", table.getRowCount());
        result.put("column_count", table.getColumnCount());
        result.put("headers", table.getHeaders());

        switch (format) {
            case "csv":
                result.put("data", table.toCsv());
                break;
            case "json":
            case "records":
                result.put("data", table.toJsonRows());
                break;
            case "markdown":
            case "md":
                result.put("data", table.toMarkdown());
                break;
            default:
                return Map.of("error", "Unsupported format: " + format + ". Use: csv, json, records, markdown");
        }

        return result;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Fetches a table by ID from the vector store and parses its markdown content.
     */
    private MarkdownTableParser.ParsedTable getAndParseTable(String tableId) {
        if (vectorStore == null) return null;
        try {
            Map<String, Object> doc = vectorStore.getVectorDocument(tableId);
            if (doc == null) return null;
            String content = extractTableContent(doc);
            if (content == null || content.isEmpty()) return null;
            return MarkdownTableParser.parse(content);
        } catch (Exception e) {
            logger.error("Failed to parse table {}: {}", tableId, e.getMessage(), e);
            return null;
        }
    }

    private Map<String, Object> computeColumnStats(List<String> values) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total_values", values.size());

        // Count non-empty
        long nonEmpty = values.stream().filter(v -> v != null && !v.trim().isEmpty()).count();
        stats.put("non_empty", nonEmpty);

        // Try to detect numeric column
        List<Double> numericValues = new ArrayList<>();
        for (String v : values) {
            Double d = MarkdownTableParser.tryParseDouble(v);
            if (d != null) numericValues.add(d);
        }

        if (numericValues.size() > 0 && numericValues.size() >= values.size() / 2) {
            // Numeric column — compute numeric stats
            stats.put("type", "numeric");
            double sum = 0, min = Double.MAX_VALUE, max = Double.MIN_VALUE;
            for (double d : numericValues) {
                sum += d;
                if (d < min) min = d;
                if (d > max) max = d;
            }
            stats.put("min", min);
            stats.put("max", max);
            stats.put("sum", sum);
            stats.put("mean", sum / numericValues.size());
            stats.put("numeric_count", numericValues.size());
        } else {
            // Text column — compute distinct values
            stats.put("type", "text");
            Map<String, Integer> freq = new LinkedHashMap<>();
            for (String v : values) {
                String trimmed = v != null ? v.trim() : "";
                freq.merge(trimmed, 1, Integer::sum);
            }
            stats.put("unique_values", freq.size());

            // Top 5 most frequent
            List<Map.Entry<String, Integer>> sorted = new ArrayList<>(freq.entrySet());
            sorted.sort((a, b) -> b.getValue() - a.getValue());
            List<Map<String, Object>> topValues = new ArrayList<>();
            for (int i = 0; i < Math.min(5, sorted.size()); i++) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("value", sorted.get(i).getKey());
                entry.put("count", sorted.get(i).getValue());
                topValues.add(entry);
            }
            stats.put("most_frequent", topValues);
        }
        return stats;
    }

    private boolean matchesFilter(String cellValue, String op, String filterValue) {
        // Try numeric comparison first
        Double cellNum = MarkdownTableParser.tryParseDouble(cellValue);
        Double filterNum = MarkdownTableParser.tryParseDouble(filterValue);

        switch (op) {
            case "eq":
            case "equals":
                return cellValue.equalsIgnoreCase(filterValue);
            case "neq":
            case "not_equals":
                return !cellValue.equalsIgnoreCase(filterValue);
            case "contains":
                return cellValue.toLowerCase().contains(filterValue.toLowerCase());
            case "gt":
                if (cellNum != null && filterNum != null) return cellNum > filterNum;
                return cellValue.compareToIgnoreCase(filterValue) > 0;
            case "lt":
                if (cellNum != null && filterNum != null) return cellNum < filterNum;
                return cellValue.compareToIgnoreCase(filterValue) < 0;
            case "gte":
                if (cellNum != null && filterNum != null) return cellNum >= filterNum;
                return cellValue.compareToIgnoreCase(filterValue) >= 0;
            case "lte":
                if (cellNum != null && filterNum != null) return cellNum <= filterNum;
                return cellValue.compareToIgnoreCase(filterValue) <= 0;
            default:
                return false;
        }
    }

    private int compareValues(String a, String b) {
        Double da = MarkdownTableParser.tryParseDouble(a);
        Double db = MarkdownTableParser.tryParseDouble(b);
        if (da != null && db != null) {
            return Double.compare(da, db);
        }
        return a.compareToIgnoreCase(b);
    }

    private boolean isTableDocument(Map<String, Object> doc) {
        Object contentType = doc.get(CONTENT_TYPE_KEY);
        if (CONTENT_TYPE_TABLE.equals(contentType)) return true;
        // Also check nested metadata map
        Object meta = doc.get("metadata");
        if (meta instanceof Map) {
            Object ct = ((Map<?, ?>) meta).get(CONTENT_TYPE_KEY);
            return CONTENT_TYPE_TABLE.equals(ct);
        }
        return false;
    }

    private boolean isTableDoc(RetrievedDoc doc) {
        if (doc == null || doc.getMetadata() == null) return false;
        return CONTENT_TYPE_TABLE.equals(doc.getMetadata().get(CONTENT_TYPE_KEY));
    }

    private Map<String, Object> toTableSummary(Map<String, Object> doc) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", getDocField(doc, "id"));
        summary.put("headers", getDocField(doc, TABLE_HEADERS_KEY));
        summary.put("row_count", getDocField(doc, TABLE_ROW_COUNT_KEY));
        summary.put("column_count", getDocField(doc, TABLE_COLUMN_COUNT_KEY));
        summary.put("page_number", getDocField(doc, TABLE_PAGE_NUMBER_KEY));
        summary.put("source_filename", getDocField(doc, "source_filename"));

        // Include summary/preview text
        Object content = getDocField(doc, "content");
        if (content instanceof String) {
            String text = (String) content;
            if (text.length() > 200) {
                text = text.substring(0, 200) + "...";
            }
            summary.put("preview", text);
        }
        return summary;
    }

    private Map<String, Object> toTableResult(RetrievedDoc doc) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", doc.getId());

        Map<String, Object> meta = doc.getMetadata();
        result.put("headers", meta.get(TABLE_HEADERS_KEY));
        result.put("row_count", meta.get(TABLE_ROW_COUNT_KEY));
        result.put("column_count", meta.get(TABLE_COLUMN_COUNT_KEY));
        result.put("page_number", meta.get(TABLE_PAGE_NUMBER_KEY));

        doc.getSourceFilename().ifPresent(fn -> result.put("source_filename", fn));

        if (doc.getScore() != null) {
            result.put("score", doc.getScore());
        }

        // Full table content
        String fullContent = MultiVectorDocument.extractFullContent(doc);
        if (fullContent != null) {
            result.put("content", fullContent);
        } else {
            result.put("content", doc.getText());
        }

        return result;
    }

    private Map<String, Object> toTableDetail(Map<String, Object> doc) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", getDocField(doc, "id"));
        detail.put("headers", getDocField(doc, TABLE_HEADERS_KEY));
        detail.put("row_count", getDocField(doc, TABLE_ROW_COUNT_KEY));
        detail.put("column_count", getDocField(doc, TABLE_COLUMN_COUNT_KEY));
        detail.put("page_number", getDocField(doc, TABLE_PAGE_NUMBER_KEY));
        detail.put("table_index", getDocField(doc, TABLE_INDEX_KEY));
        detail.put("source_filename", getDocField(doc, "source_filename"));

        // Full content
        String tableContent = extractTableContent(doc);
        detail.put("content", tableContent != null ? tableContent : getDocField(doc, "content"));

        return detail;
    }

    private String extractTableContent(Map<String, Object> doc) {
        // Check direct fields first
        Object content = doc.get(FULL_TABLE_CONTENT_KEY);
        if (content instanceof String && !((String) content).isEmpty()) {
            return (String) content;
        }
        content = doc.get("full_content");
        if (content instanceof String && !((String) content).isEmpty()) {
            return (String) content;
        }
        // Check nested metadata
        Object meta = doc.get("metadata");
        if (meta instanceof Map) {
            Map<?, ?> metaMap = (Map<?, ?>) meta;
            Object tc = metaMap.get(FULL_TABLE_CONTENT_KEY);
            if (tc instanceof String && !((String) tc).isEmpty()) return (String) tc;
            tc = metaMap.get("full_content");
            if (tc instanceof String && !((String) tc).isEmpty()) return (String) tc;
        }
        // Fallback to content field
        Object c = doc.get("content");
        return c instanceof String ? (String) c : null;
    }

    private Object getDocField(Map<String, Object> doc, String key) {
        Object val = doc.get(key);
        if (val != null) return val;
        // Check nested metadata
        Object meta = doc.get("metadata");
        if (meta instanceof Map) {
            return ((Map<?, ?>) meta).get(key);
        }
        return null;
    }

    private List<String> parseTableRows(String markdownTable) {
        if (markdownTable == null || markdownTable.isEmpty()) {
            return Collections.emptyList();
        }
        String[] lines = markdownTable.split("\\r?\\n");
        List<String> rows = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                rows.add(trimmed);
            }
        }
        return rows;
    }
}
