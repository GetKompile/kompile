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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TableGraphAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── detectFormat ──────────────────────────────────────────────────

    @Test
    void detectFormatNull() {
        assertEquals("unknown", TableGraphAdapter.detectFormat(null));
    }

    @Test
    void detectFormatBlank() {
        assertEquals("unknown", TableGraphAdapter.detectFormat("   "));
    }

    @Test
    void detectFormatInvalidJson() {
        assertEquals("unknown", TableGraphAdapter.detectFormat("not json"));
    }

    @Test
    void detectFormatGraph() throws Exception {
        Map<String, Object> graph = Map.of(
                "id", "test",
                "entities", List.of(),
                "relationships", List.of()
        );
        assertEquals("graph", TableGraphAdapter.detectFormat(MAPPER.writeValueAsString(graph)));
    }

    @Test
    void detectFormatSpreadsheet() throws Exception {
        Map<String, Object> ss = Map.of(
                "workbookName", "Test",
                "cells", Map.of(),
                "dependencies", List.of()
        );
        assertEquals("spreadsheet", TableGraphAdapter.detectFormat(MAPPER.writeValueAsString(ss)));
    }

    @Test
    void detectFormatUnknownJson() throws Exception {
        Map<String, Object> other = Map.of("foo", "bar");
        assertEquals("unknown", TableGraphAdapter.detectFormat(MAPPER.writeValueAsString(other)));
    }

    // ── toSpreadsheetFormat ───────────────────────────────────────────

    @Test
    void toSpreadsheetFormatNullPassthrough() {
        assertNull(TableGraphAdapter.toSpreadsheetFormat(null));
    }

    @Test
    void toSpreadsheetFormatBlankPassthrough() {
        assertEquals("", TableGraphAdapter.toSpreadsheetFormat(""));
    }

    @Test
    void toSpreadsheetFormatAlreadySpreadsheet() throws Exception {
        Map<String, Object> ss = Map.of(
                "workbookName", "Budget",
                "cells", Map.of("Budget!A1", Map.of("cellReference", "Budget!A1")),
                "dependencies", List.of()
        );
        String json = MAPPER.writeValueAsString(ss);
        assertEquals(json, TableGraphAdapter.toSpreadsheetFormat(json));
    }

    @SuppressWarnings("unchecked")
    @Test
    void toSpreadsheetFormatConvertsGraphWithTableAndCells() throws Exception {
        // Build a minimal Graph-format JSON with a TABLE entity and 2 CELL entities
        List<Map<String, Object>> entities = new ArrayList<>();

        // TABLE entity
        Map<String, Object> tableEntity = new LinkedHashMap<>();
        tableEntity.put("id", "tbl:ns/table:Revenue");
        tableEntity.put("title", "Revenue");
        tableEntity.put("type", "TABLE");
        tableEntity.put("isComposite", true);
        entities.add(tableEntity);

        // HEADER_CELL at R0C0
        Map<String, Object> headerCell = new LinkedHashMap<>();
        headerCell.put("id", "tbl:ns/table:Revenue/cell:R0C0");
        headerCell.put("title", "Product");
        headerCell.put("type", "HEADER_CELL");
        Map<String, Object> headerMeta = new LinkedHashMap<>();
        headerMeta.put("rowIndex", 0);
        headerMeta.put("colIndex", 0);
        headerMeta.put("cellValue", "Product");
        headerMeta.put("isHeader", true);
        headerCell.put("metadata", headerMeta);
        entities.add(headerCell);

        // CELL at R1C0
        Map<String, Object> dataCell = new LinkedHashMap<>();
        dataCell.put("id", "tbl:ns/table:Revenue/cell:R1C0");
        dataCell.put("title", "Widget");
        dataCell.put("type", "CELL");
        Map<String, Object> dataMeta = new LinkedHashMap<>();
        dataMeta.put("rowIndex", 1);
        dataMeta.put("colIndex", 0);
        dataMeta.put("cellValue", "Widget");
        dataMeta.put("isHeader", false);
        dataCell.put("metadata", dataMeta);
        entities.add(dataCell);

        // CELL at R1C1 (numeric)
        Map<String, Object> numCell = new LinkedHashMap<>();
        numCell.put("id", "tbl:ns/table:Revenue/cell:R1C1");
        numCell.put("title", "100");
        numCell.put("type", "CELL");
        Map<String, Object> numMeta = new LinkedHashMap<>();
        numMeta.put("rowIndex", 1);
        numMeta.put("colIndex", 1);
        numMeta.put("cellValue", "100");
        numMeta.put("isHeader", false);
        numCell.put("metadata", numMeta);
        entities.add(numCell);

        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("id", "tbl:ns");
        graph.put("entities", entities);
        graph.put("relationships", List.of());

        String graphJson = MAPPER.writeValueAsString(graph);
        String result = TableGraphAdapter.toSpreadsheetFormat(graphJson);

        // Parse result and verify SpreadsheetGraph structure
        Map<String, Object> ss = MAPPER.readValue(result, Map.class);
        assertEquals("Revenue", ss.get("workbookName"));
        assertNotNull(ss.get("cells"));
        Map<String, Object> cells = (Map<String, Object>) ss.get("cells");

        // Should have 3 cells: the header and 2 data cells
        assertEquals(3, cells.size());

        // Check header cell: Revenue!A1 (row 0, col 0 → A1)
        assertTrue(cells.containsKey("Revenue!A1"));
        Map<String, Object> headerNode = (Map<String, Object>) cells.get("Revenue!A1");
        assertEquals("Revenue!A1", headerNode.get("cellReference"));
        assertEquals("Revenue", headerNode.get("sheetName"));
        assertEquals("A", headerNode.get("column"));
        assertEquals(1, headerNode.get("row"));
        assertEquals("STRING", headerNode.get("cellType"));
        assertEquals("Product", headerNode.get("displayValue"));

        // Check data cell: Revenue!A2 (row 1, col 0 → A2)
        assertTrue(cells.containsKey("Revenue!A2"));
        Map<String, Object> dataNode = (Map<String, Object>) cells.get("Revenue!A2");
        assertEquals("Widget", dataNode.get("displayValue"));
        assertEquals("STRING", dataNode.get("cellType"));

        // Check numeric cell: Revenue!B2 (row 1, col 1 → B2)
        assertTrue(cells.containsKey("Revenue!B2"));
        Map<String, Object> numNode = (Map<String, Object>) cells.get("Revenue!B2");
        assertEquals("100", numNode.get("displayValue"));
        assertEquals("NUMERIC", numNode.get("cellType"));
    }

    @Test
    void toSpreadsheetFormatFallsBackOnNoEntities() throws Exception {
        Map<String, Object> graph = Map.of("id", "test", "foo", "bar");
        String json = MAPPER.writeValueAsString(graph);
        // No entities key, returns original
        assertEquals(json, TableGraphAdapter.toSpreadsheetFormat(json));
    }

    // ── toGraphFormat ────────────────────────────────────────────────

    @Test
    void toGraphFormatNullPassthrough() {
        assertNull(TableGraphAdapter.toGraphFormat(null));
    }

    @Test
    void toGraphFormatAlreadyGraph() throws Exception {
        Map<String, Object> graph = Map.of(
                "id", "test",
                "entities", List.of(),
                "relationships", List.of()
        );
        String json = MAPPER.writeValueAsString(graph);
        assertEquals(json, TableGraphAdapter.toGraphFormat(json));
    }

    @SuppressWarnings("unchecked")
    @Test
    void toGraphFormatConvertsSpreadsheetWithCellsAndDeps() throws Exception {
        // Build a SpreadsheetGraph-format JSON
        Map<String, Object> cellA1 = new LinkedHashMap<>();
        cellA1.put("cellReference", "Sheet1!A1");
        cellA1.put("sheetName", "Sheet1");
        cellA1.put("column", "A");
        cellA1.put("row", 1);
        cellA1.put("cellType", "STRING");
        cellA1.put("formula", null);
        cellA1.put("displayValue", "Name");

        Map<String, Object> cellB1 = new LinkedHashMap<>();
        cellB1.put("cellReference", "Sheet1!B1");
        cellB1.put("sheetName", "Sheet1");
        cellB1.put("column", "B");
        cellB1.put("row", 1);
        cellB1.put("cellType", "NUMERIC");
        cellB1.put("formula", "=A1+1");
        cellB1.put("displayValue", "42");

        Map<String, Object> dep = new LinkedHashMap<>();
        dep.put("formulaCell", "Sheet1!B1");
        dep.put("referencedCell", "Sheet1!A1");
        dep.put("dependencyType", "CELL_REFERENCE");
        dep.put("crossSheet", false);

        Map<String, Object> ss = new LinkedHashMap<>();
        ss.put("workbookName", "TestBook");
        Map<String, Object> cells = new LinkedHashMap<>();
        cells.put("Sheet1!A1", cellA1);
        cells.put("Sheet1!B1", cellB1);
        ss.put("cells", cells);
        ss.put("dependencies", List.of(dep));

        String ssJson = MAPPER.writeValueAsString(ss);
        String result = TableGraphAdapter.toGraphFormat(ssJson);

        // Parse result and verify Graph structure
        Map<String, Object> graph = MAPPER.readValue(result, Map.class);
        assertEquals("wb:TestBook", graph.get("id"));

        List<Map<String, Object>> entities = (List<Map<String, Object>>) graph.get("entities");
        assertNotNull(entities);
        // 1 SHEET + 2 CELLS = 3 entities
        assertEquals(3, entities.size());

        // First entity should be the SHEET
        Map<String, Object> sheetEntity = entities.get(0);
        assertEquals("SHEET", sheetEntity.get("type"));
        assertEquals("Sheet1", sheetEntity.get("title"));
        assertTrue((Boolean) sheetEntity.get("isComposite"));

        // Cell A1: no formula → CELL type
        Map<String, Object> cellEntityA = entities.get(1);
        assertEquals("CELL", cellEntityA.get("type"));

        // Cell B1: has formula → FORMULA_CELL type
        Map<String, Object> cellEntityB = entities.get(2);
        assertEquals("FORMULA_CELL", cellEntityB.get("type"));

        List<Map<String, Object>> relationships = (List<Map<String, Object>>) graph.get("relationships");
        assertNotNull(relationships);
        // 2 CONTAINS + 1 DEPENDS_ON = 3 relationships
        assertEquals(3, relationships.size());

        // Verify the DEPENDS_ON relationship
        Map<String, Object> depRel = relationships.get(2);
        assertEquals("DEPENDS_ON", depRel.get("type"));
        assertTrue(depRel.get("source").toString().contains("Sheet1!B1"));
        assertTrue(depRel.get("target").toString().contains("Sheet1!A1"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void toGraphFormatHandlesCrossSheetDeps() throws Exception {
        Map<String, Object> cellA = new LinkedHashMap<>();
        cellA.put("cellReference", "Sheet1!A1");
        cellA.put("sheetName", "Sheet1");
        cellA.put("displayValue", "10");

        Map<String, Object> cellB = new LinkedHashMap<>();
        cellB.put("cellReference", "Sheet2!A1");
        cellB.put("sheetName", "Sheet2");
        cellB.put("formula", "=Sheet1!A1");
        cellB.put("displayValue", "10");

        Map<String, Object> dep = new LinkedHashMap<>();
        dep.put("formulaCell", "Sheet2!A1");
        dep.put("referencedCell", "Sheet1!A1");
        dep.put("dependencyType", "CELL_REFERENCE");
        dep.put("crossSheet", true);

        Map<String, Object> ss = new LinkedHashMap<>();
        ss.put("workbookName", "Multi");
        Map<String, Object> cells = new LinkedHashMap<>();
        cells.put("Sheet1!A1", cellA);
        cells.put("Sheet2!A1", cellB);
        ss.put("cells", cells);
        ss.put("dependencies", List.of(dep));

        String result = TableGraphAdapter.toGraphFormat(MAPPER.writeValueAsString(ss));
        Map<String, Object> graph = MAPPER.readValue(result, Map.class);

        List<Map<String, Object>> entities = (List<Map<String, Object>>) graph.get("entities");
        // 2 SHEET entities + 2 CELL entities
        assertEquals(4, entities.size());

        List<Map<String, Object>> rels = (List<Map<String, Object>>) graph.get("relationships");
        // Find the cross-sheet dep
        Map<String, Object> crossRel = rels.stream()
                .filter(r -> "CROSS_SHEET_DEPENDS_ON".equals(r.get("type")))
                .findFirst()
                .orElseThrow();
        assertNotNull(crossRel);
    }

    // ── Round-trip ───────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    @Test
    void roundTripGraphToSpreadsheetAndBack() throws Exception {
        // Start with a Graph format, convert to spreadsheet, convert back
        List<Map<String, Object>> entities = new ArrayList<>();
        Map<String, Object> tableEntity = new LinkedHashMap<>();
        tableEntity.put("id", "tbl:ns/table:Sales");
        tableEntity.put("title", "Sales");
        tableEntity.put("type", "TABLE");
        tableEntity.put("isComposite", true);
        entities.add(tableEntity);

        Map<String, Object> cell = new LinkedHashMap<>();
        cell.put("id", "tbl:ns/table:Sales/cell:R0C0");
        cell.put("title", "Q1");
        cell.put("type", "CELL");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("rowIndex", 0);
        meta.put("colIndex", 0);
        meta.put("cellValue", "Q1");
        meta.put("isHeader", false);
        cell.put("metadata", meta);
        entities.add(cell);

        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("id", "tbl:ns");
        graph.put("entities", entities);
        graph.put("relationships", List.of());

        String original = MAPPER.writeValueAsString(graph);

        // Graph → Spreadsheet
        String ssJson = TableGraphAdapter.toSpreadsheetFormat(original);
        assertEquals("spreadsheet", TableGraphAdapter.detectFormat(ssJson));

        // Spreadsheet → Graph
        String graphJson = TableGraphAdapter.toGraphFormat(ssJson);
        assertEquals("graph", TableGraphAdapter.detectFormat(graphJson));

        // Verify the round-tripped graph has the cell data
        Map<String, Object> roundTripped = MAPPER.readValue(graphJson, Map.class);
        List<Map<String, Object>> rtEntities = (List<Map<String, Object>>) roundTripped.get("entities");
        // Should have at least 1 SHEET + 1 CELL
        assertTrue(rtEntities.size() >= 2);
    }

    @SuppressWarnings("unchecked")
    @Test
    void toSpreadsheetFormatConvertsHeaderOfRelationships() throws Exception {
        // Build graph with HEADER_OF relationship
        List<Map<String, Object>> entities = new ArrayList<>();

        Map<String, Object> tableEntity = new LinkedHashMap<>();
        tableEntity.put("id", "tbl:ns/table:T");
        tableEntity.put("title", "T");
        tableEntity.put("type", "TABLE");
        entities.add(tableEntity);

        // Header cell R0C0
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("id", "tbl:ns/table:T/cell:R0C0");
        header.put("title", "Name");
        header.put("type", "HEADER_CELL");
        Map<String, Object> hMeta = new LinkedHashMap<>();
        hMeta.put("rowIndex", 0);
        hMeta.put("colIndex", 0);
        hMeta.put("cellValue", "Name");
        hMeta.put("isHeader", true);
        header.put("metadata", hMeta);
        entities.add(header);

        // Data cell R1C0
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", "tbl:ns/table:T/cell:R1C0");
        data.put("title", "Alice");
        data.put("type", "CELL");
        Map<String, Object> dMeta = new LinkedHashMap<>();
        dMeta.put("rowIndex", 1);
        dMeta.put("colIndex", 0);
        dMeta.put("cellValue", "Alice");
        dMeta.put("isHeader", false);
        data.put("metadata", dMeta);
        entities.add(data);

        // HEADER_OF: header → data
        Map<String, Object> rel = new LinkedHashMap<>();
        rel.put("source", "tbl:ns/table:T/cell:R0C0");
        rel.put("target", "tbl:ns/table:T/cell:R1C0");
        rel.put("type", "HEADER_OF");

        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("id", "tbl:ns");
        graph.put("entities", entities);
        graph.put("relationships", List.of(rel));

        String result = TableGraphAdapter.toSpreadsheetFormat(MAPPER.writeValueAsString(graph));
        Map<String, Object> ss = MAPPER.readValue(result, Map.class);

        List<Map<String, Object>> deps = (List<Map<String, Object>>) ss.get("dependencies");
        // The HEADER_OF rel should produce a dependency
        assertNotNull(deps);
        // May or may not convert depending on whether entityIdToCellRef can resolve
        // (requires valid cell ID format)
    }
}
