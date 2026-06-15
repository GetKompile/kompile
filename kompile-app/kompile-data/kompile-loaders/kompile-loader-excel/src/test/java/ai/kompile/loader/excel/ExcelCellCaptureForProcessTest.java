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

package ai.kompile.loader.excel;

import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.loader.excel.graph.CellNode;
import ai.kompile.loader.excel.graph.ExcelFormulaGraphExtractor;
import ai.kompile.loader.excel.graph.SpreadsheetGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that Excel extraction produces cell entity IDs and graph structures
 * that can be resolved downstream by the process engine pipeline.
 *
 * The key contract: when a process step references a graph node ID like
 * "wb:Budget.xlsx/cell:Revenue!B2", the process engine must be able to
 * look up that entity in the persisted formula graph JSON and find the
 * cell's metadata (displayValue, formula, cellType).
 *
 * These tests verify:
 * 1. Cell entity IDs follow the "wb:<workbook>/cell:<sheet>!<col><row>" format
 * 2. Sheet entity IDs follow the "wb:<workbook>/sheet:<sheet>" format
 * 3. The graph JSON can be serialized and deserialized for persistence
 * 4. Input cells vs formula cells are correctly categorized
 * 5. Cell references from email mentions can be resolved in the graph
 */
class ExcelCellCaptureForProcessTest {

    @TempDir
    Path tempDir;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Cell entity ID format ────────────────────────────────────────────────

    @Nested
    class CellEntityIdFormat {

        @Test
        void cellEntityIds_followWbCellNamespaceConvention() throws Exception {
            File file = createBudgetWorkbook("Budget.xlsx");
            try (XSSFWorkbook workbook = new XSSFWorkbook(file)) {
                SpreadsheetGraph sg = new ExcelFormulaGraphExtractor(workbook).extract(workbook, "Budget.xlsx");
                Graph graph = sg.toGraph();

                // All cell entities should use the wb:<name>/cell:<ref> format
                List<Entity> cellEntities = graph.getEntities().stream()
                        .filter(e -> !"SHEET".equals(e.getType()))
                        .toList();

                assertFalse(cellEntities.isEmpty(), "Should have cell entities");
                for (Entity cell : cellEntities) {
                    assertTrue(cell.getId().startsWith("wb:Budget.xlsx/cell:"),
                            "Cell entity ID should start with 'wb:Budget.xlsx/cell:', got: " + cell.getId());
                }
            }
        }

        @Test
        void sheetEntityIds_followWbSheetNamespaceConvention() throws Exception {
            File file = createBudgetWorkbook("Report.xlsx");
            try (XSSFWorkbook workbook = new XSSFWorkbook(file)) {
                SpreadsheetGraph sg = new ExcelFormulaGraphExtractor(workbook).extract(workbook, "Report.xlsx");
                Graph graph = sg.toGraph();

                List<Entity> sheetEntities = graph.getEntities().stream()
                        .filter(e -> "SHEET".equals(e.getType()))
                        .toList();

                assertFalse(sheetEntities.isEmpty(), "Should have sheet entities");
                for (Entity sheet : sheetEntities) {
                    assertTrue(sheet.getId().startsWith("wb:Report.xlsx/sheet:"),
                            "Sheet entity ID should start with 'wb:Report.xlsx/sheet:', got: " + sheet.getId());
                }
            }
        }

        @Test
        void cellEntityId_containsSheetPrefixedReference() throws Exception {
            File file = createBudgetWorkbook("Test.xlsx");
            try (XSSFWorkbook workbook = new XSSFWorkbook(file)) {
                SpreadsheetGraph sg = new ExcelFormulaGraphExtractor(workbook).extract(workbook, "Test.xlsx");
                Graph graph = sg.toGraph();

                // Look for cell Revenue!B2 (revenue Q1 = 50000)
                Entity b2 = graph.getEntities().stream()
                        .filter(e -> e.getId().equals("wb:Test.xlsx/cell:Revenue!B2"))
                        .findFirst().orElse(null);

                assertNotNull(b2, "Should find entity for cell Revenue!B2; all IDs: " +
                        graph.getEntities().stream().map(Entity::getId).toList());
                String displayVal = String.valueOf(b2.getMetadata().get("displayValue"));
                assertTrue(displayVal.startsWith("50000"),
                        "Cell B2 displayValue should be 50000, got: " + displayVal);
                assertEquals("NUMERIC", b2.getMetadata().get("cellType"));
            }
        }
    }

    // ── Input vs formula cell categorization ─────────────────────────────────

    @Nested
    class InputVsFormulaCells {

        @Test
        void formulaCells_haveFormulaMetadata() throws Exception {
            File file = createBudgetWorkbook("Formulas.xlsx");
            try (XSSFWorkbook workbook = new XSSFWorkbook(file)) {
                SpreadsheetGraph sg = new ExcelFormulaGraphExtractor(workbook).extract(workbook, "Formulas.xlsx");
                Graph graph = sg.toGraph();

                List<Entity> formulaCells = graph.getEntities().stream()
                        .filter(e -> "FORMULA_CELL".equals(e.getType()))
                        .toList();

                assertFalse(formulaCells.isEmpty(), "Should have formula cell entities");
                for (Entity fc : formulaCells) {
                    assertNotNull(fc.getMetadata().get("formula"),
                            "Formula cell should have formula metadata; entity: " + fc.getId());
                }
            }
        }

        @Test
        void inputCells_doNotHaveFormulaMetadata() throws Exception {
            File file = createBudgetWorkbook("Inputs.xlsx");
            try (XSSFWorkbook workbook = new XSSFWorkbook(file)) {
                SpreadsheetGraph sg = new ExcelFormulaGraphExtractor(workbook).extract(workbook, "Inputs.xlsx");
                Graph graph = sg.toGraph();

                // Non-formula, non-sheet entities are input cells
                List<Entity> inputCells = graph.getEntities().stream()
                        .filter(e -> !"SHEET".equals(e.getType()) && !"FORMULA_CELL".equals(e.getType()))
                        .toList();

                for (Entity ic : inputCells) {
                    Object formula = ic.getMetadata().get("formula");
                    assertTrue(formula == null || "".equals(formula),
                            "Input cell " + ic.getId() + " should not have a formula");
                }
            }
        }

        @Test
        void formulaCellCount_matchesSpreadsheetGraph() throws Exception {
            File file = createBudgetWorkbook("Count.xlsx");
            try (XSSFWorkbook workbook = new XSSFWorkbook(file)) {
                SpreadsheetGraph sg = new ExcelFormulaGraphExtractor(workbook).extract(workbook, "Count.xlsx");
                int sgFormulaCount = sg.getFormulaCells().size();

                Graph graph = sg.toGraph();
                long graphFormulaCount = graph.getEntities().stream()
                        .filter(e -> "FORMULA_CELL".equals(e.getType()))
                        .count();

                assertEquals(sgFormulaCount, graphFormulaCount,
                        "Graph entity count should match SpreadsheetGraph formula cell count");
            }
        }
    }

    // ── Graph JSON persistence round-trip ────────────────────────────────────

    @Nested
    class JsonPersistence {

        @Test
        void graphJson_roundTrip_preservesCellIds() throws Exception {
            File file = createBudgetWorkbook("Persist.xlsx");
            try (XSSFWorkbook workbook = new XSSFWorkbook(file)) {
                SpreadsheetGraph sg = new ExcelFormulaGraphExtractor(workbook).extract(workbook, "Persist.xlsx");
                Graph original = sg.toGraph();

                // Serialize to JSON (as persistFormulaGraph does)
                String json = MAPPER.writeValueAsString(original);
                assertNotNull(json);
                assertTrue(json.contains("wb:Persist.xlsx/cell:"));

                // Deserialize back (as resolveExcelGraphJson does)
                Graph restored = MAPPER.readValue(json, Graph.class);

                // All entity IDs must survive
                Set<String> originalIds = original.getEntities().stream()
                        .map(Entity::getId).collect(Collectors.toSet());
                Set<String> restoredIds = restored.getEntities().stream()
                        .map(Entity::getId).collect(Collectors.toSet());
                assertEquals(originalIds, restoredIds, "Entity IDs must survive JSON round-trip");

                // Relationship count must match
                assertEquals(original.getRelationships().size(), restored.getRelationships().size());
            }
        }

        @Test
        void spreadsheetGraphJson_roundTrip_preservesCellNodes() throws Exception {
            File file = createBudgetWorkbook("SpGraph.xlsx");
            try (XSSFWorkbook workbook = new XSSFWorkbook(file)) {
                SpreadsheetGraph original = new ExcelFormulaGraphExtractor(workbook).extract(workbook, "SpGraph.xlsx");

                // Serialize the SpreadsheetGraph itself (used by executeExcelWithResult)
                String json = MAPPER.writeValueAsString(original);

                SpreadsheetGraph restored = MAPPER.readValue(json, SpreadsheetGraph.class);

                // Cell count must match
                assertEquals(original.getCells().size(), restored.getCells().size());

                // Specific cells should have correct values
                CellNode b2 = restored.getCells().get("Revenue!B2");
                assertNotNull(b2, "Cell Revenue!B2 should survive round-trip");
                assertTrue(b2.getDisplayValue().startsWith("50000"),
                        "Display value should be 50000, got: " + b2.getDisplayValue());
                assertEquals("NUMERIC", b2.getCellType());
            }
        }
    }

    // ── Cell reference resolution (email mention → graph entity) ─────────────

    @Nested
    class CellReferenceResolution {

        @Test
        void emailCellReference_B2_resolvesToGraphEntity() throws Exception {
            File file = createBudgetWorkbook("Resolve.xlsx");
            try (XSSFWorkbook workbook = new XSSFWorkbook(file)) {
                SpreadsheetGraph sg = new ExcelFormulaGraphExtractor(workbook).extract(workbook, "Resolve.xlsx");
                Graph graph = sg.toGraph();

                // Simulate what happens when an email says "update cell B2":
                // EmailBodyValueExtractor produces parsedValue = "B2"
                // The system needs to find the matching entity in the graph
                String emailCellRef = "B2"; // from EmailBodyValueExtractor

                // Strategy 1: Direct lookup in SpreadsheetGraph cells
                CellNode directLookup = sg.getCells().get(emailCellRef);
                // B2 alone won't match because cells use "Revenue!B2" format
                assertNull(directLookup, "Plain 'B2' won't match sheet-prefixed cell refs");

                // Strategy 2: Suffix-based lookup (as EmailValueCellMapper.findCellByRef does)
                CellNode suffixLookup = null;
                for (Map.Entry<String, CellNode> entry : sg.getCells().entrySet()) {
                    if (entry.getKey().endsWith("!" + emailCellRef)) {
                        suffixLookup = entry.getValue();
                        break;
                    }
                }
                assertNotNull(suffixLookup, "Should find Revenue!B2 via '!B2' suffix match");
                assertEquals("Revenue!B2", suffixLookup.getCellReference());

                // Strategy 3: Graph entity lookup via the cell entity ID
                String expectedEntityId = "wb:Resolve.xlsx/cell:Revenue!" + emailCellRef;
                Entity entity = graph.getEntities().stream()
                        .filter(e -> e.getId().equals(expectedEntityId))
                        .findFirst().orElse(null);
                assertNotNull(entity, "Should find graph entity for 'wb:Resolve.xlsx/cell:Revenue!B2'");
            }
        }

        @Test
        void emailCellReference_withSheetPrefix_resolvesDirectly() throws Exception {
            File file = createBudgetWorkbook("Direct.xlsx");
            try (XSSFWorkbook workbook = new XSSFWorkbook(file)) {
                SpreadsheetGraph sg = new ExcelFormulaGraphExtractor(workbook).extract(workbook, "Direct.xlsx");

                // Email says "Revenue!B2" — this matches exactly
                String emailCellRef = "Revenue!B2";
                CellNode cell = sg.getCells().get(emailCellRef);
                assertNotNull(cell, "Sheet-prefixed reference should match directly");
                assertTrue(cell.getDisplayValue().startsWith("50000"),
                        "Display value should be 50000, got: " + cell.getDisplayValue());
            }
        }

        @Test
        void formulaCellDependencies_traceFromOutputToInputs() throws Exception {
            File file = createBudgetWorkbook("Trace.xlsx");
            try (XSSFWorkbook workbook = new XSSFWorkbook(file)) {
                SpreadsheetGraph sg = new ExcelFormulaGraphExtractor(workbook).extract(workbook, "Trace.xlsx");
                Graph graph = sg.toGraph();

                // Find the profit formula cell (D2 = B2 - C2)
                String formulaCellId = "wb:Trace.xlsx/cell:Revenue!D2";
                Entity profitCell = graph.getEntities().stream()
                        .filter(e -> e.getId().equals(formulaCellId))
                        .findFirst().orElse(null);
                assertNotNull(profitCell, "Should find profit formula cell D2");
                assertEquals("FORMULA_CELL", profitCell.getType());

                // Trace DEPENDS_ON relationships from D2 to its inputs
                List<Relationship> deps = graph.getRelationships().stream()
                        .filter(r -> r.getSource().equals(formulaCellId))
                        .filter(r -> "DEPENDS_ON".equals(r.getType()))
                        .toList();

                assertFalse(deps.isEmpty(), "Formula cell D2 should have DEPENDS_ON relationships");

                // The targets should be B2 and C2
                Set<String> targetIds = deps.stream()
                        .map(Relationship::getTarget)
                        .collect(Collectors.toSet());
                assertTrue(targetIds.contains("wb:Trace.xlsx/cell:Revenue!B2"),
                        "D2 should depend on B2; targets: " + targetIds);
                assertTrue(targetIds.contains("wb:Trace.xlsx/cell:Revenue!C2"),
                        "D2 should depend on C2; targets: " + targetIds);
            }
        }
    }

    // ── Cross-workbook isolation ─────────────────────────────────────────────

    @Nested
    class CrossWorkbookIsolation {

        @Test
        void differentWorkbooks_haveDifferentNamespaces() throws Exception {
            File file1 = createBudgetWorkbook("Alpha.xlsx");
            File file2 = createBudgetWorkbook("Beta.xlsx");

            try (XSSFWorkbook wb1 = new XSSFWorkbook(file1);
                 XSSFWorkbook wb2 = new XSSFWorkbook(file2)) {

                Graph g1 = new ExcelFormulaGraphExtractor(wb1).extract(wb1, "Alpha.xlsx").toGraph();
                Graph g2 = new ExcelFormulaGraphExtractor(wb2).extract(wb2, "Beta.xlsx").toGraph();

                // Entity IDs from Alpha should NOT appear in Beta's graph
                Set<String> g1Ids = g1.getEntities().stream().map(Entity::getId).collect(Collectors.toSet());
                Set<String> g2Ids = g2.getEntities().stream().map(Entity::getId).collect(Collectors.toSet());

                // No overlap
                Set<String> overlap = new HashSet<>(g1Ids);
                overlap.retainAll(g2Ids);
                assertTrue(overlap.isEmpty(),
                        "Two different workbooks should have zero entity ID overlap; overlap: " + overlap);

                // Verify prefixes
                assertTrue(g1Ids.stream().allMatch(id -> id.startsWith("wb:Alpha.xlsx/")));
                assertTrue(g2Ids.stream().allMatch(id -> id.startsWith("wb:Beta.xlsx/")));
            }
        }
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private File createBudgetWorkbook(String name) throws Exception {
        File file = tempDir.resolve(name).toFile();
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Revenue");

            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Quarter");
            header.createCell(1).setCellValue("Sales");
            header.createCell(2).setCellValue("Costs");
            header.createCell(3).setCellValue("Profit");

            Row q1 = sheet.createRow(1);
            q1.createCell(0).setCellValue("Q1");
            q1.createCell(1).setCellValue(50000);
            q1.createCell(2).setCellValue(30000);
            q1.createCell(3).setCellFormula("B2-C2");

            Row q2 = sheet.createRow(2);
            q2.createCell(0).setCellValue("Q2");
            q2.createCell(1).setCellValue(75000);
            q2.createCell(2).setCellValue(40000);
            q2.createCell(3).setCellFormula("B3-C3");

            Row total = sheet.createRow(3);
            total.createCell(0).setCellValue("Total");
            total.createCell(1).setCellFormula("SUM(B2:B3)");
            total.createCell(2).setCellFormula("SUM(C2:C3)");
            total.createCell(3).setCellFormula("SUM(D2:D3)");

            try (FileOutputStream fos = new FileOutputStream(file)) {
                workbook.write(fos);
            }
        }
        return file;
    }
}
