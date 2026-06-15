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

package ai.kompile.core.graphrag.table;

import ai.kompile.core.graphrag.GraphConstants;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Converts between the two table graph JSON formats used in the system:
 * <ul>
 *   <li><b>Graph format</b> (from {@code TableCellGraphBuilder}):
 *       {@code {"id":"...", "entities":[...], "relationships":[...]}}</li>
 *   <li><b>SpreadsheetGraph format</b> (from {@code ExcelFormulaGraphExtractor}):
 *       {@code {"workbookName":"...", "cells":{"Sheet1!A1":{...}}, "dependencies":[...]}}</li>
 * </ul>
 *
 * This adapter enables the process engine's {@code ExcelNodeExecutor} and the
 * frontend's {@code ExcelArtifactComponent} to work with tables from any source
 * (HTML, PDF, Word, Google Sheets) — not just Excel.
 */
public final class TableGraphAdapter {

    private static final Logger log = LoggerFactory.getLogger(TableGraphAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TableGraphAdapter() {}

    /**
     * Detects which format the given JSON is in.
     *
     * @return "graph" if it has "entities", "spreadsheet" if it has "cells", "unknown" otherwise
     */
    @SuppressWarnings("unchecked")
    public static String detectFormat(String json) {
        if (json == null || json.isBlank()) return "unknown";
        try {
            Map<String, Object> map = MAPPER.readValue(json, Map.class);
            if (map.containsKey("entities")) return "graph";
            if (map.containsKey("cells")) return "spreadsheet";
        } catch (Exception e) {
            log.debug("Failed to parse table graph JSON for format detection: {}", e.getMessage());
        }
        return "unknown";
    }

    /**
     * Converts a TableCellGraphBuilder Graph JSON to a SpreadsheetGraph-compatible JSON.
     * If the input is already in SpreadsheetGraph format, returns it unchanged.
     *
     * @param graphJson the graph JSON (either format)
     * @return SpreadsheetGraph-compatible JSON, or the original if already in that format
     */
    @SuppressWarnings("unchecked")
    public static String toSpreadsheetFormat(String graphJson) {
        if (graphJson == null || graphJson.isBlank()) return graphJson;

        try {
            Map<String, Object> map = MAPPER.readValue(graphJson, Map.class);

            // Already in SpreadsheetGraph format
            if (map.containsKey("cells")) return graphJson;

            // Not a Graph format either
            List<Map<String, Object>> entities = (List<Map<String, Object>>) map.get("entities");
            if (entities == null) return graphJson;

            List<Map<String, Object>> relationships = (List<Map<String, Object>>) map.get("relationships");

            return convertGraphToSpreadsheet(entities, relationships != null ? relationships : List.of());
        } catch (Exception e) {
            return graphJson;
        }
    }

    /**
     * Converts a SpreadsheetGraph JSON to a Graph JSON (entities/relationships).
     * If the input is already in Graph format, returns it unchanged.
     *
     * @param spreadsheetJson the SpreadsheetGraph JSON (either format)
     * @return Graph-compatible JSON, or the original if already in that format
     */
    @SuppressWarnings("unchecked")
    public static String toGraphFormat(String spreadsheetJson) {
        if (spreadsheetJson == null || spreadsheetJson.isBlank()) return spreadsheetJson;

        try {
            Map<String, Object> map = MAPPER.readValue(spreadsheetJson, Map.class);

            // Already in Graph format
            if (map.containsKey("entities")) return spreadsheetJson;

            // Not SpreadsheetGraph format
            Map<String, Map<String, Object>> cells = (Map<String, Map<String, Object>>) map.get("cells");
            if (cells == null) return spreadsheetJson;

            List<Map<String, Object>> deps = (List<Map<String, Object>>) map.get("dependencies");
            String workbookName = (String) map.getOrDefault("workbookName", "Workbook");

            return convertSpreadsheetToGraph(workbookName, cells, deps != null ? deps : List.of());
        } catch (Exception e) {
            return spreadsheetJson;
        }
    }

    // ── Internal conversion: Graph → SpreadsheetGraph ──────────────────

    private static String convertGraphToSpreadsheet(List<Map<String, Object>> entities,
                                                     List<Map<String, Object>> relationships) throws JsonProcessingException {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Map<String, Object>> cells = new LinkedHashMap<>();
        List<Map<String, Object>> dependencies = new ArrayList<>();
        String tableName = "Table";

        // Find the TABLE entity for the workbook name
        for (Map<String, Object> entity : entities) {
            String type = (String) entity.getOrDefault("type", "");
            if (GraphConstants.ENTITY_TABLE.equals(type)) {
                tableName = (String) entity.getOrDefault("title", "Table");
                break;
            }
        }

        // Convert CELL and HEADER_CELL entities to CellNode-like maps
        for (Map<String, Object> entity : entities) {
            String type = (String) entity.getOrDefault("type", "");
            if (!GraphConstants.ENTITY_CELL.equals(type)
                    && !GraphConstants.ENTITY_HEADER_CELL.equals(type)) {
                continue;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) entity.getOrDefault("metadata", Map.of());

            int rowIdx = toInt(meta.get(GraphConstants.PROP_ROW_INDEX), 0);
            int colIdx = toInt(meta.get(GraphConstants.PROP_COL_INDEX), 0);
            String cellValue = String.valueOf(meta.getOrDefault(GraphConstants.PROP_CELL_VALUE, ""));
            boolean isHeader = Boolean.TRUE.equals(meta.get(GraphConstants.PROP_IS_HEADER));
            String title = (String) entity.getOrDefault("title", "");

            // Build Excel-style reference: TableName!<Col><Row>
            String colLetter = CellReference.columnIndexToLetter(colIdx);
            String cellRef = tableName + "!" + colLetter + (rowIdx + 1);

            Map<String, Object> cellNode = new LinkedHashMap<>();
            cellNode.put("cellReference", cellRef);
            cellNode.put("sheetName", tableName);
            cellNode.put("column", colLetter);
            cellNode.put("row", rowIdx + 1);
            cellNode.put("cellType", isHeader ? "STRING" : guessCellType(cellValue));
            cellNode.put("formula", null);
            cellNode.put("displayValue", cellValue.isEmpty() ? title : cellValue);
            cellNode.put("namedRange", false);
            cellNode.put("namedRangeName", null);

            cells.put(cellRef, cellNode);
        }

        // Convert HEADER_OF relationships to dependencies (informational, not formula deps)
        for (Map<String, Object> rel : relationships) {
            String relType = (String) rel.getOrDefault("type", "");
            if (GraphConstants.REL_HEADER_OF.equals(relType)) {
                // Map to a lightweight dependency for visualization
                String sourceId = (String) rel.get("source");
                String targetId = (String) rel.get("target");
                // Find cell refs from entity IDs
                String sourceRef = entityIdToCellRef(sourceId, tableName);
                String targetRef = entityIdToCellRef(targetId, tableName);
                if (sourceRef != null && targetRef != null) {
                    Map<String, Object> dep = new LinkedHashMap<>();
                    dep.put("formulaCell", targetRef);
                    dep.put("referencedCell", sourceRef);
                    dep.put("dependencyType", "CELL_REFERENCE");
                    dep.put("crossSheet", false);
                    dependencies.add(dep);
                }
            }
        }

        result.put("workbookName", tableName);
        result.put("cells", cells);
        result.put("dependencies", dependencies);
        result.put("namedRanges", Map.of());

        return MAPPER.writeValueAsString(result);
    }

    // ── Internal conversion: SpreadsheetGraph → Graph ──────────────────

    private static String convertSpreadsheetToGraph(String workbookName,
                                                     Map<String, Map<String, Object>> cells,
                                                     List<Map<String, Object>> deps) throws JsonProcessingException {
        List<Map<String, Object>> entities = new ArrayList<>();
        List<Map<String, Object>> relationships = new ArrayList<>();
        String ns = "wb:" + workbookName;

        // Collect sheet names
        Set<String> sheets = new LinkedHashSet<>();
        for (Map<String, Object> cell : cells.values()) {
            String sheet = (String) cell.getOrDefault("sheetName", workbookName);
            sheets.add(sheet);
        }

        // Create SHEET entities
        for (String sheet : sheets) {
            Map<String, Object> entity = new LinkedHashMap<>();
            entity.put("id", ns + "/sheet:" + sheet);
            entity.put("title", sheet);
            entity.put("type", GraphConstants.ENTITY_SHEET);
            entity.put("isComposite", true);
            entities.add(entity);
        }

        // Create CELL entities
        for (Map.Entry<String, Map<String, Object>> entry : cells.entrySet()) {
            Map<String, Object> cell = entry.getValue();
            String cellRef = entry.getKey();
            String formula = (String) cell.get("formula");
            boolean isFormula = formula != null && !formula.isEmpty();

            Map<String, Object> entity = new LinkedHashMap<>();
            entity.put("id", ns + "/cell:" + cellRef);
            entity.put("title", cellRef);
            entity.put("type", isFormula ? GraphConstants.ENTITY_FORMULA_CELL : GraphConstants.ENTITY_CELL);

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put(GraphConstants.PROP_CELL_VALUE, cell.getOrDefault("displayValue", ""));
            meta.put("cell_reference", cellRef);
            entity.put("metadata", meta);

            String sheet = (String) cell.getOrDefault("sheetName", workbookName);
            entity.put("parentEntityId", ns + "/sheet:" + sheet);

            entities.add(entity);

            // CONTAINS: sheet → cell
            Map<String, Object> containsRel = new LinkedHashMap<>();
            containsRel.put("source", ns + "/sheet:" + sheet);
            containsRel.put("target", ns + "/cell:" + cellRef);
            containsRel.put("type", GraphConstants.REL_CONTAINS);
            relationships.add(containsRel);
        }

        // Convert dependencies to relationships
        for (Map<String, Object> dep : deps) {
            String formulaCell = (String) dep.get("formulaCell");
            String referencedCell = (String) dep.get("referencedCell");
            String depType = String.valueOf(dep.getOrDefault("dependencyType", "CELL_REFERENCE"));
            boolean crossSheet = Boolean.TRUE.equals(dep.get("crossSheet"));

            Map<String, Object> rel = new LinkedHashMap<>();
            rel.put("source", ns + "/cell:" + formulaCell);
            rel.put("target", ns + "/cell:" + referencedCell);
            rel.put("type", crossSheet ? GraphConstants.REL_CROSS_SHEET_DEPENDS_ON : GraphConstants.REL_DEPENDS_ON);
            relationships.add(rel);
        }

        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("id", ns);
        graph.put("entities", entities);
        graph.put("relationships", relationships);

        return MAPPER.writeValueAsString(graph);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /**
     * Extracts a cell ref from a table-format entity ID.
     * "tbl:ns/table:Name/cell:R1C2" → "Name!C2" (converted to A1 notation)
     */
    private static String entityIdToCellRef(String entityId, String fallbackTable) {
        if (entityId == null) return null;
        CellReference ref = CellReference.parse(entityId);
        if (ref.isValid()) {
            return ref.toExcelNotation();
        }
        return null;
    }

    private static String guessCellType(String value) {
        if (value == null || value.isBlank()) return "BLANK";
        try {
            Double.parseDouble(value);
            return "NUMERIC";
        } catch (NumberFormatException e) {
            if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) return "BOOLEAN";
            return "STRING";
        }
    }

    private static int toInt(Object obj, int fallback) {
        if (obj instanceof Number n) return n.intValue();
        if (obj instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return fallback; }
        }
        return fallback;
    }
}
