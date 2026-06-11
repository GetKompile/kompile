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
import ai.kompile.loader.excel.graph.FormulaDependency;
import ai.kompile.loader.excel.graph.SpreadsheetGraph;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ExcelFormulaGraphExtractorTest {

    @Test
    void testSimpleCellReference() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Data");
            Row row0 = sheet.createRow(0);
            row0.createCell(0).setCellValue(10);    // A1 = 10
            row0.createCell(1).setCellValue(20);    // B1 = 20
            row0.createCell(2).setCellFormula("A1+B1"); // C1 = A1 + B1

            ExcelFormulaGraphExtractor extractor = new ExcelFormulaGraphExtractor(workbook);
            SpreadsheetGraph graph = extractor.extract(workbook, "test.xlsx");

            assertNotNull(graph);
            assertEquals("test.xlsx", graph.getWorkbookName());

            // Should have formula cells
            List<CellNode> formulas = graph.getFormulaCells();
            assertEquals(1, formulas.size());
            assertEquals("Data!C1", formulas.get(0).getCellReference());
            assertEquals("A1+B1", formulas.get(0).getFormula());

            // Should have dependencies: C1 -> A1, C1 -> B1
            List<FormulaDependency> deps = graph.getDependencies();
            assertEquals(2, deps.size());

            List<String> referencedCells = deps.stream()
                    .map(FormulaDependency::getReferencedCell)
                    .sorted()
                    .collect(Collectors.toList());
            assertTrue(referencedCells.contains("Data!A1"));
            assertTrue(referencedCells.contains("Data!B1"));
        }
    }

    @Test
    void testRangeReference() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sales");
            for (int i = 0; i < 5; i++) {
                sheet.createRow(i).createCell(0).setCellValue(i * 10);
            }
            // A6 = SUM(A1:A5)
            sheet.createRow(5).createCell(0).setCellFormula("SUM(A1:A5)");

            ExcelFormulaGraphExtractor extractor = new ExcelFormulaGraphExtractor(workbook);
            SpreadsheetGraph graph = extractor.extract(workbook, "sales.xlsx");

            List<CellNode> formulas = graph.getFormulaCells();
            assertEquals(1, formulas.size());
            assertEquals("SUM(A1:A5)", formulas.get(0).getFormula());

            // Should have 5 range dependencies (A1 through A5)
            List<FormulaDependency> deps = graph.getDependencies();
            assertEquals(5, deps.size());
            assertTrue(deps.stream().allMatch(d ->
                    d.getDependencyType() == FormulaDependency.DependencyType.RANGE_REFERENCE));
            assertTrue(deps.stream().allMatch(d ->
                    "A1:A5".equals(d.getRangeReference())));
        }
    }

    @Test
    void testCrossSheetReference() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet1 = workbook.createSheet("Input");
            sheet1.createRow(0).createCell(0).setCellValue(100); // Input!A1 = 100

            Sheet sheet2 = workbook.createSheet("Output");
            sheet2.createRow(0).createCell(0).setCellFormula("Input!A1*2"); // Output!A1 = Input!A1 * 2

            ExcelFormulaGraphExtractor extractor = new ExcelFormulaGraphExtractor(workbook);
            SpreadsheetGraph graph = extractor.extract(workbook, "multi-sheet.xlsx");

            // Should detect cross-sheet dependency
            List<FormulaDependency> crossSheet = graph.getCrossSheetDependencies();
            assertFalse(crossSheet.isEmpty());
            assertEquals("Output!A1", crossSheet.get(0).getFormulaCell());
            assertEquals("Input!A1", crossSheet.get(0).getReferencedCell());
            assertTrue(crossSheet.get(0).isCrossSheet());

            // Graph should have both sheets
            assertEquals(2, graph.getSheetNames().size());
            assertTrue(graph.getSheetNames().contains("Input"));
            assertTrue(graph.getSheetNames().contains("Output"));
        }
    }

    @Test
    void testNamedRanges() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Budget");
            for (int i = 0; i < 3; i++) {
                sheet.createRow(i).createCell(0).setCellValue((i + 1) * 1000);
            }

            // Create a named range
            Name namedRange = workbook.createName();
            namedRange.setNameName("Expenses");
            namedRange.setRefersToFormula("Budget!A1:A3");

            // A4 = SUM(Expenses)
            sheet.createRow(3).createCell(0).setCellFormula("SUM(Expenses)");

            ExcelFormulaGraphExtractor extractor = new ExcelFormulaGraphExtractor(workbook);
            SpreadsheetGraph graph = extractor.extract(workbook, "budget.xlsx");

            assertFalse(graph.getNamedRanges().isEmpty());
            assertEquals("Budget!A1:A3", graph.getNamedRanges().get("Expenses"));
        }
    }

    @Test
    void testToGraphConversion() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Data");
            Row row0 = sheet.createRow(0);
            row0.createCell(0).setCellValue(10);        // A1 = 10
            row0.createCell(1).setCellValue(20);        // B1 = 20
            row0.createCell(2).setCellFormula("A1+B1"); // C1 = A1 + B1

            ExcelFormulaGraphExtractor extractor = new ExcelFormulaGraphExtractor(workbook);
            SpreadsheetGraph spreadsheetGraph = extractor.extract(workbook, "test.xlsx");

            Graph coreGraph = spreadsheetGraph.toGraph();

            assertNotNull(coreGraph);

            // Should have sheet entity + cell entities
            assertFalse(coreGraph.getEntities().isEmpty());

            // Verify sheet entity
            List<Entity> sheetEntities = coreGraph.getEntities().stream()
                    .filter(e -> "SHEET".equals(e.getType()))
                    .collect(Collectors.toList());
            assertEquals(1, sheetEntities.size());
            assertEquals("Data", sheetEntities.get(0).getTitle());
            assertTrue(Boolean.TRUE.equals(sheetEntities.get(0).getMetadata().get("isComposite")));

            // Verify formula cell entity
            List<Entity> formulaEntities = coreGraph.getEntities().stream()
                    .filter(e -> "FORMULA_CELL".equals(e.getType()))
                    .collect(Collectors.toList());
            assertEquals(1, formulaEntities.size());
            assertTrue(formulaEntities.get(0).getDescription().contains("A1+B1"));

            // Verify relationships include DEPENDS_ON
            List<Relationship> dependsOn = coreGraph.getRelationships().stream()
                    .filter(r -> "DEPENDS_ON".equals(r.getType()))
                    .collect(Collectors.toList());
            assertEquals(2, dependsOn.size());
            assertTrue(dependsOn.stream().allMatch(r -> "EXTRACTED".equals(r.getMetadata().get("provenance"))));
            assertTrue(dependsOn.stream().allMatch(r -> r.getConfidence() == 1.0));

            // Verify CONTAINS relationships (sheet -> cell)
            List<Relationship> contains = coreGraph.getRelationships().stream()
                    .filter(r -> "CONTAINS".equals(r.getType()))
                    .collect(Collectors.toList());
            assertFalse(contains.isEmpty());
        }
    }

    @Test
    void testCrossSheetGraphHasSheetLinks() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet1 = workbook.createSheet("Revenue");
            sheet1.createRow(0).createCell(0).setCellValue(50000);

            Sheet sheet2 = workbook.createSheet("Summary");
            sheet2.createRow(0).createCell(0).setCellFormula("Revenue!A1");

            ExcelFormulaGraphExtractor extractor = new ExcelFormulaGraphExtractor(workbook);
            SpreadsheetGraph spreadsheetGraph = extractor.extract(workbook, "report.xlsx");
            Graph coreGraph = spreadsheetGraph.toGraph();

            // Verify CROSS_SHEET_LINK between sheet entities
            List<Relationship> sheetLinks = coreGraph.getRelationships().stream()
                    .filter(r -> "CROSS_SHEET_LINK".equals(r.getType()))
                    .collect(Collectors.toList());
            assertEquals(1, sheetLinks.size());
            assertEquals("wb:report.xlsx/sheet:Summary", sheetLinks.get(0).getSource());
            assertEquals("wb:report.xlsx/sheet:Revenue", sheetLinks.get(0).getTarget());

            // Verify CROSS_SHEET_DEPENDS_ON between cells
            List<Relationship> crossDeps = coreGraph.getRelationships().stream()
                    .filter(r -> "CROSS_SHEET_DEPENDS_ON".equals(r.getType()))
                    .collect(Collectors.toList());
            assertFalse(crossDeps.isEmpty());
        }
    }

    @Test
    void testGraphSummary() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Calc");
            Row row0 = sheet.createRow(0);
            row0.createCell(0).setCellValue(5);
            row0.createCell(1).setCellFormula("A1*2");

            ExcelFormulaGraphExtractor extractor = new ExcelFormulaGraphExtractor(workbook);
            SpreadsheetGraph graph = extractor.extract(workbook, "calc.xlsx");

            String summary = graph.toSummary();
            assertNotNull(summary);
            assertTrue(summary.contains("calc.xlsx"));
            assertTrue(summary.contains("Formula cells: 1"));
            assertTrue(summary.contains("A1*2"));
        }
    }

    @Test
    void testReverseDependencies() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            Row row0 = sheet.createRow(0);
            row0.createCell(0).setCellValue(10);         // A1
            row0.createCell(1).setCellFormula("A1*2");    // B1 depends on A1
            row0.createCell(2).setCellFormula("A1+B1");   // C1 depends on A1 and B1

            ExcelFormulaGraphExtractor extractor = new ExcelFormulaGraphExtractor(workbook);
            SpreadsheetGraph graph = extractor.extract(workbook, "deps.xlsx");

            // A1 has 2 dependents: B1 and C1
            List<CellNode> dependents = graph.getDependents("Sheet1!A1");
            assertEquals(2, dependents.size());

            // B1 has 1 dependent: C1
            dependents = graph.getDependents("Sheet1!B1");
            assertEquals(1, dependents.size());
            assertEquals("Sheet1!C1", dependents.get(0).getCellReference());
        }
    }

    @Test
    void testChainedFormulas() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Chain");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(1);          // A1 = 1
            row.createCell(1).setCellFormula("A1+1");   // B1 = A1 + 1
            row.createCell(2).setCellFormula("B1+1");   // C1 = B1 + 1
            row.createCell(3).setCellFormula("C1+1");   // D1 = C1 + 1

            ExcelFormulaGraphExtractor extractor = new ExcelFormulaGraphExtractor(workbook);
            SpreadsheetGraph graph = extractor.extract(workbook, "chain.xlsx");

            assertEquals(3, graph.getFormulaCells().size());

            // Each formula depends on the previous cell
            List<CellNode> b1Deps = graph.getDependenciesOf("Chain!B1");
            assertEquals(1, b1Deps.size());
            assertEquals("Chain!A1", b1Deps.get(0).getCellReference());

            List<CellNode> d1Deps = graph.getDependenciesOf("Chain!D1");
            assertEquals(1, d1Deps.size());
            assertEquals("Chain!C1", d1Deps.get(0).getCellReference());
        }
    }
}
