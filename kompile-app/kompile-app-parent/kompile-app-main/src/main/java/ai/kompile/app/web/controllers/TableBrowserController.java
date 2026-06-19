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

package ai.kompile.app.web.controllers;

import ai.kompile.core.embeddings.NoOpVectorStoreImpl;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.retrievers.DocumentRetriever;
import ai.kompile.core.retrievers.NoOpDocumentRetrieverImpl;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.tool.tablesearch.MarkdownTableParser;
import ai.kompile.tool.tablesearch.MarkdownTableParser.ParsedTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * REST controller for browsing and analyzing tables extracted from indexed documents.
 * Exposes the same analysis capabilities as TableSearchToolImpl but over HTTP for the UI.
 */
@RestController
@RequestMapping("/api/tables")
public class TableBrowserController {

    private static final Logger logger = LoggerFactory.getLogger(TableBrowserController.class);

    private final VectorStore vectorStore;
    private final DocumentRetriever documentRetriever;
    private final KnowledgeGraphService knowledgeGraphService;

    @Autowired
    public TableBrowserController(
            List<VectorStore> vectorStores,
            List<DocumentRetriever> documentRetrievers,
            KnowledgeGraphService knowledgeGraphService) {
        // Select non-NoOp vector store
        this.vectorStore = vectorStores.stream()
                .filter(vs -> !(vs instanceof NoOpVectorStoreImpl))
                .findFirst()
                .orElse(vectorStores.isEmpty() ? null : vectorStores.get(0));
        // Select non-NoOp document retriever
        this.documentRetriever = documentRetrievers.stream()
                .filter(dr -> !(dr instanceof NoOpDocumentRetrieverImpl))
                .findFirst()
                .orElse(documentRetrievers.isEmpty() ? null : documentRetrievers.get(0));
        // Vector store is SINGLE SOURCE OF TRUTH for TABLE graph nodes
        this.knowledgeGraphService = knowledgeGraphService;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRAPH TABLE NODES (primary source)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Convert a TABLE graph node to the table summary map consumed by the UI.
     */
    private Map<String, Object> buildTableSummaryFromGraphNode(GraphNode node) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", node.getNodeId());
        summary.put("nodeId", node.getNodeId());
        summary.put("externalId", node.getExternalId());
        summary.put("title", node.getTitle());
        summary.put("preview", node.getDescription());
        summary.put("fullContent", node.getDescription());

        // Parse table dimensions from metadata stored as JSON
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        Map<String, Object> meta = Collections.emptyMap();
        if (node.getMetadataJson() != null && !node.getMetadataJson().isEmpty()) {
            try {
                meta = om.readValue(node.getMetadataJson(),
                        om.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
            } catch (Exception ignored) {}
        }

        Object rowCount = meta.get("rowCount");
        Object colCount = meta.get("columnCount");
        Object headers = meta.get("headers");

        summary.put("rowCount", rowCount instanceof Number ? ((Number) rowCount).intValue() : 0);
        summary.put("columnCount", colCount instanceof Number ? ((Number) colCount).intValue() : 0);
        summary.put("headers", headers instanceof String h
                ? Arrays.asList(h.split(",")) : Collections.emptyList());
        summary.put("sourceFile", meta.get("source_path"));
        summary.put("sourceType", "graph_node");
        return summary;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LIST & GET
    // ═══════════════════════════════════════════════════════════════════════════

    @GetMapping
    public ResponseEntity<Map<String, Object>> listTables(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit) {
        try {
            // Primary path: read TABLE graph nodes from the vector store (SINGLE SOURCE OF TRUTH).
            List<GraphNode> tableNodes = knowledgeGraphService.getNodesByType(NodeLevel.TABLE);
            if (!tableNodes.isEmpty()) {
                List<Map<String, Object>> allTables = tableNodes.stream()
                        .map(this::buildTableSummaryFromGraphNode)
                        .collect(Collectors.toList());
                int total = allTables.size();
                int safeOffset = Math.min(offset, total);
                int safeEnd = Math.min(safeOffset + limit, total);
                List<Map<String, Object>> page = allTables.subList(safeOffset, safeEnd);
                return ResponseEntity.ok(Map.of(
                        "tables", page,
                        "total", total,
                        "offset", offset,
                        "limit", limit,
                        "source", "graph_nodes"));
            }

            // Fallback: search vector store documents for content_type=table chunks.
            if (vectorStore == null || vectorStore instanceof NoOpVectorStoreImpl) {
                return ResponseEntity.ok(Map.of(
                        "tables", Collections.emptyList(),
                        "total", 0,
                        "offset", offset,
                        "limit", limit,
                        "warning", "Vector store not available and no graph TABLE nodes found"));
            }

            // Fetch more than limit to account for non-table documents we filter out
            int fetchLimit = Math.min((offset + limit) * 3, 500);
            var allDocs = vectorStore.listVectorDocuments(0, fetchLimit);
            List<Map<String, Object>> tableDocs = new ArrayList<>();

            for (var doc : allDocs) {
                if (!isTableDocument(doc)) continue;
                tableDocs.add(buildTableSummary(doc));
            }

            int total = tableDocs.size();
            int safeOffset = Math.min(offset, total);
            int safeEnd = Math.min(safeOffset + limit, total);
            List<Map<String, Object>> page = tableDocs.subList(safeOffset, safeEnd);

            return ResponseEntity.ok(Map.of(
                    "tables", page,
                    "total", total,
                    "offset", offset,
                    "limit", limit,
                    "source", "vector_documents"));
        } catch (Exception e) {
            logger.error("Error listing tables", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{tableId}")
    public ResponseEntity<Map<String, Object>> getTable(@PathVariable String tableId) {
        try {
            // Primary path: look up TABLE graph node from vector store (SINGLE SOURCE OF TRUTH).
            var graphNode = knowledgeGraphService.getNode(tableId);
            if (graphNode.isPresent() && graphNode.get().getNodeType() == NodeLevel.TABLE) {
                return ResponseEntity.ok(buildTableSummaryFromGraphNode(graphNode.get()));
            }

            // Fallback: look in vector store documents.
            if (vectorStore == null || vectorStore instanceof NoOpVectorStoreImpl) {
                return ResponseEntity.notFound().build();
            }
            var doc = vectorStore.getVectorDocument(tableId);
            if (doc == null) {
                return ResponseEntity.notFound().build();
            }
            Map<String, Object> result = buildTableDetail(doc);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("Error getting table: {}", tableId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SEARCH
    // ═══════════════════════════════════════════════════════════════════════════

    @PostMapping("/search")
    public ResponseEntity<Map<String, Object>> searchTables(@RequestBody Map<String, Object> body) {
        String query = (String) body.get("query");
        int maxResults = body.containsKey("maxResults") ? ((Number) body.get("maxResults")).intValue() : 10;

        if (documentRetriever == null || documentRetriever instanceof NoOpDocumentRetrieverImpl) {
            return ResponseEntity.ok(Map.of("results", Collections.emptyList(), "total", 0));
        }

        try {
            List<RetrievedDoc> results = documentRetriever.retrieveWithDetails(query, maxResults * 3);
            List<Map<String, Object>> tableResults = results.stream()
                    .filter(this::isTableRetrievedDoc)
                    .limit(maxResults)
                    .map(this::buildTableSearchResult)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of("results", tableResults, "total", tableResults.size()));
        } catch (Exception e) {
            logger.error("Error searching tables", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ANALYZE
    // ═══════════════════════════════════════════════════════════════════════════

    @PostMapping("/{tableId}/analyze")
    public ResponseEntity<Map<String, Object>> analyzeTable(
            @PathVariable String tableId,
            @RequestBody(required = false) Map<String, String> body) {
        String column = body != null ? body.get("column") : null;

        try {
            String content = getTableContent(tableId);
            if (content == null) {
                return ResponseEntity.notFound().build();
            }

            ParsedTable parsed = MarkdownTableParser.parse(content);
            if (parsed.getHeaders().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Could not parse table"));
            }

            if (column != null && !column.isEmpty()) {
                // Single column analysis
                int colIdx = parsed.findColumnIndex(column);
                if (colIdx < 0) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Column not found: " + column));
                }

                List<String> values = parsed.getColumn(column);
                Map<String, Object> stats = computeColumnStats(values);
                return ResponseEntity.ok(Map.of("column", column, "columnStats", stats));
            } else {
                // Overview analysis
                Map<String, Object> columnStats = new LinkedHashMap<>();
                for (String header : parsed.getHeaders()) {
                    List<String> values = parsed.getColumn(header);
                    columnStats.put(header, computeColumnStats(values));
                }

                return ResponseEntity.ok(Map.of(
                        "rowCount", parsed.getRowCount(),
                        "columnCount", parsed.getColumnCount(),
                        "headers", parsed.getHeaders(),
                        "columnStats", columnStats));
            }
        } catch (Exception e) {
            logger.error("Error analyzing table: {}", tableId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FILTER
    // ═══════════════════════════════════════════════════════════════════════════

    @PostMapping("/{tableId}/filter")
    public ResponseEntity<Map<String, Object>> filterTable(
            @PathVariable String tableId,
            @RequestBody Map<String, String> body) {
        String column = body.get("column");
        String operator = body.get("operator");
        String value = body.get("value");

        if (column == null || operator == null || value == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "column, operator, and value are required"));
        }

        try {
            String content = getTableContent(tableId);
            if (content == null) return ResponseEntity.notFound().build();

            ParsedTable parsed = MarkdownTableParser.parse(content);
            int colIdx = parsed.findColumnIndex(column);
            if (colIdx < 0) {
                return ResponseEntity.badRequest().body(Map.of("error", "Column not found: " + column));
            }

            List<List<String>> filteredRows = new ArrayList<>();
            for (List<String> row : parsed.getRows()) {
                String cell = colIdx < row.size() ? row.get(colIdx) : "";
                if (matchesFilter(cell, operator, value)) {
                    filteredRows.add(row);
                }
            }

            ParsedTable filteredTable = new ParsedTable(parsed.getHeaders(), filteredRows);
            return ResponseEntity.ok(Map.of("content", filteredTable.toMarkdown()));
        } catch (Exception e) {
            logger.error("Error filtering table: {}", tableId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SORT
    // ═══════════════════════════════════════════════════════════════════════════

    @PostMapping("/{tableId}/sort")
    public ResponseEntity<Map<String, Object>> sortTable(
            @PathVariable String tableId,
            @RequestBody Map<String, Object> body) {
        String column = (String) body.get("column");
        boolean descending = Boolean.TRUE.equals(body.get("descending"));

        if (column == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "column is required"));
        }

        try {
            String content = getTableContent(tableId);
            if (content == null) return ResponseEntity.notFound().build();

            ParsedTable parsed = MarkdownTableParser.parse(content);
            int colIdx = parsed.findColumnIndex(column);
            if (colIdx < 0) {
                return ResponseEntity.badRequest().body(Map.of("error", "Column not found: " + column));
            }

            List<List<String>> sortedRows = new ArrayList<>(parsed.getRows());
            final int ci = colIdx;
            sortedRows.sort((a, b) -> {
                String va = ci < a.size() ? a.get(ci) : "";
                String vb = ci < b.size() ? b.get(ci) : "";
                Double na = MarkdownTableParser.tryParseDouble(va);
                Double nb = MarkdownTableParser.tryParseDouble(vb);
                int cmp;
                if (na != null && nb != null) {
                    cmp = na.compareTo(nb);
                } else {
                    cmp = va.compareToIgnoreCase(vb);
                }
                return descending ? -cmp : cmp;
            });

            ParsedTable sortedTable = new ParsedTable(parsed.getHeaders(), sortedRows);
            return ResponseEntity.ok(Map.of("content", sortedTable.toMarkdown()));
        } catch (Exception e) {
            logger.error("Error sorting table: {}", tableId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EXPORT
    // ═══════════════════════════════════════════════════════════════════════════

    @PostMapping("/{tableId}/export")
    public ResponseEntity<Map<String, Object>> exportTable(
            @PathVariable String tableId,
            @RequestBody Map<String, String> body) {
        String format = body.getOrDefault("format", "csv");

        try {
            String content = getTableContent(tableId);
            if (content == null) return ResponseEntity.notFound().build();

            ParsedTable parsed = MarkdownTableParser.parse(content);
            String exported;
            switch (format.toLowerCase()) {
                case "csv":
                    exported = parsed.toCsv();
                    break;
                case "json":
                case "records":
                    exported = toJsonString(parsed.toJsonRows());
                    break;
                case "markdown":
                default:
                    exported = parsed.toMarkdown();
                    format = "markdown";
                    break;
            }

            return ResponseEntity.ok(Map.of("content", exported, "format", format));
        } catch (Exception e) {
            logger.error("Error exporting table: {}", tableId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private boolean isTableDocument(Map<String, Object> doc) {
        Object ct = doc.get("content_type");
        if ("table".equals(ct)) return true;
        Object meta = doc.get("metadata");
        if (meta instanceof Map) {
            return "table".equals(((Map<?, ?>) meta).get("content_type"));
        }
        return false;
    }

    private boolean isTableRetrievedDoc(RetrievedDoc doc) {
        Map<String, Object> meta = doc.getMetadata();
        return meta != null && "table".equals(meta.get("content_type"));
    }

    private String getTableContent(String tableId) {
        if (vectorStore == null || vectorStore instanceof NoOpVectorStoreImpl) return null;
        var doc = vectorStore.getVectorDocument(tableId);
        if (doc == null) return null;
        return extractTableContent(doc);
    }

    /**
     * Extract table content from a vector store document map.
     * Checks full_table_content, full_content, then nested metadata, then content field.
     */
    private String extractTableContent(Map<String, Object> doc) {
        // Check direct fields first
        Object content = doc.get("full_table_content");
        if (content instanceof String s && !s.isEmpty()) return s;
        content = doc.get("full_content");
        if (content instanceof String s && !s.isEmpty()) return s;
        // Check nested metadata
        Object meta = doc.get("metadata");
        if (meta instanceof Map<?, ?> metaMap) {
            Object tc = metaMap.get("full_table_content");
            if (tc instanceof String s && !s.isEmpty()) return s;
            tc = metaMap.get("full_content");
            if (tc instanceof String s && !s.isEmpty()) return s;
        }
        // Fallback to content field
        Object c = doc.get("content");
        return c instanceof String s ? s : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildTableSummary(Map<String, Object> doc) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", doc.getOrDefault("id", ""));
        String content = extractTableContent(doc);
        summary.put("fullContent", content);

        Map<String, Object> meta = getMetadata(doc);
        summary.put("sourceFile", meta.getOrDefault("source_file",
                meta.getOrDefault("source_path", meta.getOrDefault("original_file", null))));
        summary.put("pageNumber", meta.getOrDefault("table_page_number", null));

        // Parse table for headers/dimensions
        if (content != null && !content.isEmpty()) {
            try {
                ParsedTable parsed = MarkdownTableParser.parse(content);
                summary.put("headers", parsed.getHeaders());
                summary.put("rowCount", parsed.getRowCount());
                summary.put("columnCount", parsed.getColumnCount());
                summary.put("preview", content.length() > 200 ? content.substring(0, 200) + "..." : content);
            } catch (Exception e) {
                summary.put("headers", Collections.emptyList());
                summary.put("rowCount", 0);
                summary.put("columnCount", 0);
                summary.put("preview", content.length() > 200 ? content.substring(0, 200) + "..." : content);
            }
        } else {
            // Fall back to metadata
            summary.put("headers", parseHeaderString((String) meta.get("table_headers")));
            summary.put("rowCount", toIntOrZero(meta.get("table_row_count")));
            summary.put("columnCount", toIntOrZero(meta.get("table_column_count")));
            summary.put("preview", "");
        }

        return summary;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildTableDetail(Map<String, Object> doc) {
        Map<String, Object> detail = buildTableSummary(doc);
        detail.put("metadata", getMetadata(doc));
        return detail;
    }

    private Map<String, Object> buildTableSearchResult(RetrievedDoc doc) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", doc.getId());
        result.put("score", doc.getScore());
        String content = doc.getText();
        result.put("fullContent", content);
        result.put("sourceFile", doc.getMetadata() != null ? doc.getMetadata().get("source_file") : null);

        if (content != null && !content.isEmpty()) {
            try {
                ParsedTable parsed = MarkdownTableParser.parse(content);
                result.put("headers", parsed.getHeaders());
                result.put("rowCount", parsed.getRowCount());
                result.put("columnCount", parsed.getColumnCount());
            } catch (Exception e) {
                result.put("headers", Collections.emptyList());
                result.put("rowCount", 0);
                result.put("columnCount", 0);
            }
        }
        result.put("preview", content != null && content.length() > 200 ? content.substring(0, 200) + "..." : content);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMetadata(Map<String, Object> doc) {
        Object meta = doc.get("metadata");
        if (meta instanceof Map) return (Map<String, Object>) meta;
        return Collections.emptyMap();
    }

    private Map<String, Object> computeColumnStats(List<String> values) {
        Map<String, Object> stats = new LinkedHashMap<>();
        int nonEmpty = 0;
        int numericCount = 0;
        double sum = 0;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        Map<String, Integer> frequency = new LinkedHashMap<>();

        for (String val : values) {
            if (val != null && !val.trim().isEmpty()) {
                nonEmpty++;
                frequency.merge(val.trim(), 1, Integer::sum);
                Double num = MarkdownTableParser.tryParseDouble(val.trim());
                if (num != null) {
                    numericCount++;
                    sum += num;
                    min = Math.min(min, num);
                    max = Math.max(max, num);
                }
            }
        }

        stats.put("totalValues", values.size());
        stats.put("nonEmpty", nonEmpty);

        // Decide type: numeric if >= 50% of non-empty values are numeric
        if (numericCount > 0 && numericCount >= nonEmpty * 0.5) {
            stats.put("type", "numeric");
            stats.put("min", min == Double.MAX_VALUE ? 0 : min);
            stats.put("max", max == -Double.MAX_VALUE ? 0 : max);
            stats.put("sum", sum);
            stats.put("mean", numericCount > 0 ? sum / numericCount : 0);
            stats.put("numericCount", numericCount);
        } else {
            stats.put("type", "text");
            stats.put("uniqueValues", frequency.size());
            // Top 5 most frequent
            List<Map<String, Object>> mostFrequent = frequency.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(5)
                    .map(e -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("value", e.getKey());
                        item.put("count", e.getValue());
                        return item;
                    })
                    .collect(Collectors.toList());
            stats.put("mostFrequent", mostFrequent);
        }

        return stats;
    }

    private boolean matchesFilter(String cellValue, String operator, String filterValue) {
        if (cellValue == null) cellValue = "";
        String cell = cellValue.trim();
        String filter = filterValue.trim();

        switch (operator) {
            case "eq": return cell.equalsIgnoreCase(filter);
            case "neq": return !cell.equalsIgnoreCase(filter);
            case "contains": return cell.toLowerCase().contains(filter.toLowerCase());
            case "gt":
            case "lt":
            case "gte":
            case "lte":
                Double cellNum = MarkdownTableParser.tryParseDouble(cell);
                Double filterNum = MarkdownTableParser.tryParseDouble(filter);
                if (cellNum != null && filterNum != null) {
                    return switch (operator) {
                        case "gt" -> cellNum > filterNum;
                        case "lt" -> cellNum < filterNum;
                        case "gte" -> cellNum >= filterNum;
                        case "lte" -> cellNum <= filterNum;
                        default -> false;
                    };
                }
                // Fall back to string comparison
                int cmp = cell.compareToIgnoreCase(filter);
                return switch (operator) {
                    case "gt" -> cmp > 0;
                    case "lt" -> cmp < 0;
                    case "gte" -> cmp >= 0;
                    case "lte" -> cmp <= 0;
                    default -> false;
                };
            default: return false;
        }
    }

    private List<String> parseHeaderString(String headerStr) {
        if (headerStr == null || headerStr.isEmpty()) return Collections.emptyList();
        return Arrays.stream(headerStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private int toIntOrZero(Object val) {
        if (val instanceof Number) return ((Number) val).intValue();
        if (val instanceof String) {
            try { return Integer.parseInt((String) val); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    private String toJsonString(List<Map<String, Object>> rows) {
        // Simple JSON serialization without external dependencies
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < rows.size(); i++) {
            sb.append("  {");
            Map<String, Object> row = rows.get(i);
            int j = 0;
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (j > 0) sb.append(", ");
                sb.append("\"").append(escapeJson(entry.getKey())).append("\": ");
                Object val = entry.getValue();
                if (val instanceof Number) {
                    sb.append(val);
                } else {
                    sb.append("\"").append(escapeJson(String.valueOf(val))).append("\"");
                }
                j++;
            }
            sb.append("}");
            if (i < rows.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("]");
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
