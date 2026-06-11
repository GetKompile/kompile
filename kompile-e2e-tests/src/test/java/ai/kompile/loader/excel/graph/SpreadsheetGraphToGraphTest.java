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

package ai.kompile.loader.excel.graph;

import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SpreadsheetGraph#toGraph()} covering entity type mapping,
 * relationship type variety, metadata fields, parent entity wiring, and
 * {@link SpreadsheetGraph#toSummary()} output.
 *
 * <p>Complements the module-local {@code SpreadsheetGraphNamespacingTest} which
 * focuses exclusively on namespace prefix correctness.
 */
class SpreadsheetGraphToGraphTest {

    private static final String WB = "Budget.xlsx";
    private static final String NS = "wb:" + WB + "/";

    private SpreadsheetGraph sg;

    @BeforeEach
    void setUp() {
        sg = new SpreadsheetGraph();
        sg.setWorkbookName(WB);
    }

    // ─── helper builders ────────────────────────────────────────────────────

    private CellNode numericCell(String ref, String sheet, String col, int row, String displayValue) {
        return CellNode.builder()
                .cellReference(ref).sheetName(sheet)
                .column(col).row(row)
                .cellType("NUMERIC").displayValue(displayValue)
                .build();
    }

    private CellNode stringCell(String ref, String sheet, String col, int row, String displayValue) {
        return CellNode.builder()
                .cellReference(ref).sheetName(sheet)
                .column(col).row(row)
                .cellType("STRING").displayValue(displayValue)
                .build();
    }

    private CellNode formulaCell(String ref, String sheet, String col, int row,
                                 String formula, String displayValue) {
        return CellNode.builder()
                .cellReference(ref).sheetName(sheet)
                .column(col).row(row)
                .cellType("FORMULA").formula(formula).displayValue(displayValue)
                .build();
    }

    private CellNode namedRangeCell(String ref, String sheet, String col, int row,
                                    String displayValue, String rangeName) {
        return CellNode.builder()
                .cellReference(ref).sheetName(sheet)
                .column(col).row(row)
                .cellType("NUMERIC").displayValue(displayValue)
                .namedRange(true).namedRangeName(rangeName)
                .build();
    }

    private FormulaDependency dep(String formulaRef, String targetRef,
                                  FormulaDependency.DependencyType type,
                                  String formula, boolean crossSheet) {
        return FormulaDependency.builder()
                .formulaCell(formulaRef).referencedCell(targetRef)
                .dependencyType(type).formula(formula).crossSheet(crossSheet)
                .build();
    }

    private FormulaDependency dep(String formulaRef, String targetRef,
                                  FormulaDependency.DependencyType type,
                                  String formula, boolean crossSheet, String rangeRef) {
        return FormulaDependency.builder()
                .formulaCell(formulaRef).referencedCell(targetRef)
                .dependencyType(type).formula(formula).crossSheet(crossSheet)
                .rangeReference(rangeRef)
                .build();
    }

    private Entity findEntity(Graph g, String type, String titleContains) {
        return g.getEntities().stream()
                .filter(e -> type.equals(e.getType())
                        && (titleContains == null || e.getTitle().contains(titleContains)))
                .findFirst().orElse(null);
    }

    private List<Relationship> findRels(Graph g, String type) {
        return g.getRelationships().stream()
                .filter(r -> type.equals(r.getType()))
                .collect(Collectors.toList());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Entity type mapping
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class EntityTypeMappingTests {

        @Test
        void regularCellMappedToTypeCell() {
            sg.addCell(numericCell("Sheet1!A1", "Sheet1", "A", 1, "100"));
            Graph g = sg.toGraph();

            Entity cell = findEntity(g, "CELL", "A1");
            assertNotNull(cell, "Regular numeric cell should map to CELL type");
            assertEquals(NS + "cell:Sheet1!A1", cell.getId());
            assertTrue(cell.getDescription().contains("Cell Sheet1!A1"));
            assertTrue(cell.getDescription().contains("100"));
        }

        @Test
        void formulaCellMappedToTypeFormulaCell() {
            sg.addCell(formulaCell("Sheet1!C1", "Sheet1", "C", 1, "A1+B1", "300"));
            Graph g = sg.toGraph();

            Entity cell = findEntity(g, "FORMULA_CELL", "C1");
            assertNotNull(cell, "Formula cell should map to FORMULA_CELL type");
            assertTrue(cell.getDescription().contains("A1+B1"), "Description should include formula");
            assertTrue(cell.getDescription().contains("300"), "Description should include display value");
        }

        @Test
        void namedRangeCellMappedToTypeNamedRange() {
            sg.addCell(namedRangeCell("Sheet1!D1", "Sheet1", "D", 1, "50000", "TotalRevenue"));
            Graph g = sg.toGraph();

            Entity cell = findEntity(g, "NAMED_RANGE", "D1");
            assertNotNull(cell, "Named range cell should map to NAMED_RANGE type");
            assertTrue(cell.getDescription().contains("TotalRevenue"));
            assertTrue(cell.getDescription().contains("50000"));
        }

        @Test
        void sheetEntityCreatedAsComposite() {
            sg.addCell(numericCell("Sheet1!A1", "Sheet1", "A", 1, "10"));
            Graph g = sg.toGraph();

            Entity sheet = findEntity(g, "SHEET", "Sheet1");
            assertNotNull(sheet);
            assertEquals(NS + "sheet:Sheet1", sheet.getId());
            assertEquals(1.0, sheet.getConfidence());
            assertTrue(sheet.getDescription().contains("Sheet1"));
        }

        @Test
        void stringCellMappedToTypeCell() {
            sg.addCell(stringCell("Sheet1!B1", "Sheet1", "B", 1, "Total"));
            Graph g = sg.toGraph();

            Entity cell = findEntity(g, "CELL", "B1");
            assertNotNull(cell, "String cell should also map to CELL type");
        }

        @Test
        void multipleSheetEntitiesCreated() {
            sg.addCell(numericCell("Sheet1!A1", "Sheet1", "A", 1, "1"));
            sg.addCell(numericCell("Sheet2!A1", "Sheet2", "A", 1, "2"));
            sg.addCell(numericCell("Sheet3!A1", "Sheet3", "A", 1, "3"));
            Graph g = sg.toGraph();

            long sheetCount = g.getEntities().stream()
                    .filter(e -> "SHEET".equals(e.getType())).count();
            assertEquals(3, sheetCount);
        }

        @Test
        void entityCountMatchesCellsPlusSheets() {
            sg.addCell(numericCell("Sheet1!A1", "Sheet1", "A", 1, "10"));
            sg.addCell(formulaCell("Sheet1!B1", "Sheet1", "B", 1, "A1*2", "20"));
            sg.addCell(numericCell("Sheet2!A1", "Sheet2", "A", 1, "30"));
            Graph g = sg.toGraph();

            // 3 cells + 2 sheets = 5 entities
            assertEquals(5, g.getEntities().size());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Entity metadata
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class EntityMetadataTests {

        @Test
        void cellEntityHasMetadata() {
            sg.addCell(numericCell("Sheet1!B3", "Sheet1", "B", 3, "42"));
            Graph g = sg.toGraph();

            Entity cell = findEntity(g, "CELL", "B3");
            assertNotNull(cell);
            Map<String, Object> meta = cell.getMetadata();
            assertEquals("Sheet1", meta.get("sheetName"));
            assertEquals("B", meta.get("column"));
            assertEquals(3, meta.get("row"));
            assertEquals("NUMERIC", meta.get("cellType"));
            assertEquals("42", meta.get("displayValue"));
            assertEquals("excel-loader", meta.get("entitySource"));
        }

        @Test
        void formulaCellMetadataIncludesFormula() {
            sg.addCell(formulaCell("Sheet1!C1", "Sheet1", "C", 1, "SUM(A1:B1)", "150"));
            Graph g = sg.toGraph();

            Entity cell = findEntity(g, "FORMULA_CELL", "C1");
            assertNotNull(cell);
            assertEquals("SUM(A1:B1)", cell.getMetadata().get("formula"));
            assertEquals("150", cell.getMetadata().get("displayValue"));
        }

        @Test
        void namedRangeMetadataIncludesRangeName() {
            sg.addCell(namedRangeCell("Sheet1!D1", "Sheet1", "D", 1, "99", "Budget"));
            Graph g = sg.toGraph();

            Entity cell = findEntity(g, "NAMED_RANGE", "D1");
            assertNotNull(cell);
            assertEquals("Budget", cell.getMetadata().get("namedRangeName"));
        }

        @Test
        void sheetEntityMetadata() {
            sg.addCell(numericCell("Sheet1!A1", "Sheet1", "A", 1, "1"));
            Graph g = sg.toGraph();

            Entity sheet = findEntity(g, "SHEET", "Sheet1");
            assertNotNull(sheet);
            assertEquals(WB, sheet.getMetadata().get("workbook"));
            assertEquals("excel-loader", sheet.getMetadata().get("entitySource"));
        }

        @Test
        void nullDisplayValueOmittedFromMetadata() {
            CellNode cell = CellNode.builder()
                    .cellReference("Sheet1!A1").sheetName("Sheet1")
                    .column("A").row(1).cellType("NUMERIC")
                    .displayValue(null)
                    .build();
            sg.addCell(cell);
            Graph g = sg.toGraph();

            Entity entity = findEntity(g, "CELL", "A1");
            assertNotNull(entity);
            assertFalse(entity.getMetadata().containsKey("displayValue"));
        }

        @Test
        void nullFormulaOmittedFromMetadata() {
            sg.addCell(numericCell("Sheet1!A1", "Sheet1", "A", 1, "5"));
            Graph g = sg.toGraph();

            Entity entity = findEntity(g, "CELL", "A1");
            assertNotNull(entity);
            assertFalse(entity.getMetadata().containsKey("formula"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Parent entity wiring
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class ParentEntityTests {

        @Test
        void cellParentEntityIdPointsToSheet() {
            sg.addCell(numericCell("Sheet1!A1", "Sheet1", "A", 1, "10"));
            Graph g = sg.toGraph();

            Entity cell = findEntity(g, "CELL", "A1");
            assertNotNull(cell);
            assertEquals(NS + "sheet:Sheet1", cell.getMetadata().get("parentEntityId"));
        }

        @Test
        void formulaCellParentEntityIdPointsToSheet() {
            sg.addCell(formulaCell("Sheet1!C1", "Sheet1", "C", 1, "A1+B1", "30"));
            Graph g = sg.toGraph();

            Entity cell = findEntity(g, "FORMULA_CELL", "C1");
            assertNotNull(cell);
            assertEquals(NS + "sheet:Sheet1", cell.getMetadata().get("parentEntityId"));
        }

        @Test
        void cellsOnDifferentSheetsPointToTheirOwnSheet() {
            sg.addCell(numericCell("Sheet1!A1", "Sheet1", "A", 1, "1"));
            sg.addCell(numericCell("Sheet2!A1", "Sheet2", "A", 1, "2"));
            Graph g = sg.toGraph();

            Entity cell1 = g.getEntities().stream()
                    .filter(e -> e.getId().equals(NS + "cell:Sheet1!A1")).findFirst().orElseThrow();
            Entity cell2 = g.getEntities().stream()
                    .filter(e -> e.getId().equals(NS + "cell:Sheet2!A1")).findFirst().orElseThrow();

            assertEquals(NS + "sheet:Sheet1", cell1.getMetadata().get("parentEntityId"));
            assertEquals(NS + "sheet:Sheet2", cell2.getMetadata().get("parentEntityId"));
        }

        @Test
        void sheetEntityHasNoParent() {
            sg.addCell(numericCell("Sheet1!A1", "Sheet1", "A", 1, "1"));
            Graph g = sg.toGraph();

            Entity sheet = findEntity(g, "SHEET", "Sheet1");
            assertNotNull(sheet);
            assertNull(sheet.getMetadata().get("parentEntityId"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CONTAINS relationships (sheet → cell)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class ContainsRelationshipTests {

        @Test
        void containsRelationshipCreatedPerCell() {
            sg.addCell(numericCell("Sheet1!A1", "Sheet1", "A", 1, "10"));
            sg.addCell(numericCell("Sheet1!B1", "Sheet1", "B", 1, "20"));
            Graph g = sg.toGraph();

            List<Relationship> contains = findRels(g, "CONTAINS");
            assertEquals(2, contains.size());
        }

        @Test
        void containsRelSourceIsSheetTargetIsCell() {
            sg.addCell(numericCell("Sheet1!A1", "Sheet1", "A", 1, "10"));
            Graph g = sg.toGraph();

            Relationship rel = findRels(g, "CONTAINS").get(0);
            assertEquals(NS + "sheet:Sheet1", rel.getSource());
            assertEquals(NS + "cell:Sheet1!A1", rel.getTarget());
            assertEquals(1.0, rel.getWeight());
            assertEquals(1.0, rel.getConfidence());
            assertEquals("EXTRACTED", rel.getMetadata().get("provenance"));
        }

        @Test
        void containsRelDescriptionMentionsSheetAndCell() {
            sg.addCell(numericCell("Sheet1!A1", "Sheet1", "A", 1, "10"));
            Graph g = sg.toGraph();

            Relationship rel = findRels(g, "CONTAINS").get(0);
            assertTrue(rel.getDescription().contains("Sheet1"));
            assertTrue(rel.getDescription().contains("Sheet1!A1"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Dependency relationship types
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class DependencyRelationshipTests {

        @Test
        void cellReferenceMappedToDependsOn() {
            sg.addCell(formulaCell("Sheet1!C1", "Sheet1", "C", 1, "A1+B1", "30"));
            sg.addCell(numericCell("Sheet1!A1", "Sheet1", "A", 1, "10"));
            sg.addDependency(dep("Sheet1!C1", "Sheet1!A1",
                    FormulaDependency.DependencyType.CELL_REFERENCE, "A1+B1", false));
            Graph g = sg.toGraph();

            List<Relationship> deps = findRels(g, "DEPENDS_ON");
            assertEquals(1, deps.size());
            assertEquals(NS + "cell:Sheet1!C1", deps.get(0).getSource());
            assertEquals(NS + "cell:Sheet1!A1", deps.get(0).getTarget());
            assertEquals(1.0, deps.get(0).getWeight(), "Same-sheet dep should have weight 1.0");
        }

        @Test
        void rangeReferenceMappedToRangeInput() {
            sg.addCell(formulaCell("Sheet1!C1", "Sheet1", "C", 1, "SUM(A1:A10)", "55"));
            sg.addCell(numericCell("Sheet1!A1", "Sheet1", "A", 1, "10"));
            sg.addDependency(dep("Sheet1!C1", "Sheet1!A1",
                    FormulaDependency.DependencyType.RANGE_REFERENCE, "SUM(A1:A10)", false, "A1:A10"));
            Graph g = sg.toGraph();

            List<Relationship> rangeRels = findRels(g, "RANGE_INPUT");
            assertEquals(1, rangeRels.size());
            assertTrue(rangeRels.get(0).getDescription().contains("A1:A10"));
        }

        @Test
        void crossSheetReferenceMappedToCrossSheetDependsOn() {
            sg.addCell(formulaCell("Sheet1!C1", "Sheet1", "C", 1, "Sheet2!A1*2", "20"));
            sg.addCell(numericCell("Sheet2!A1", "Sheet2", "A", 1, "10"));
            sg.addDependency(dep("Sheet1!C1", "Sheet2!A1",
                    FormulaDependency.DependencyType.CROSS_SHEET_REFERENCE, "Sheet2!A1*2", true));
            Graph g = sg.toGraph();

            List<Relationship> crossRels = findRels(g, "CROSS_SHEET_DEPENDS_ON");
            assertEquals(1, crossRels.size());
            assertEquals(0.8, crossRels.get(0).getWeight(), "Cross-sheet dep should have weight 0.8");
        }

        @Test
        void namedRangeReferenceMappedToNamedRangeInput() {
            sg.addCell(formulaCell("Sheet1!C1", "Sheet1", "C", 1, "TotalSales*0.1", "500"));
            sg.addCell(namedRangeCell("Sheet1!D1", "Sheet1", "D", 1, "5000", "TotalSales"));
            sg.addDependency(dep("Sheet1!C1", "Sheet1!D1",
                    FormulaDependency.DependencyType.NAMED_RANGE_REFERENCE, "TotalSales*0.1", false));
            Graph g = sg.toGraph();

            List<Relationship> namedRels = findRels(g, "NAMED_RANGE_INPUT");
            assertEquals(1, namedRels.size());
        }

        @Test
        void dependencyRelationshipHasMetadata() {
            sg.addCell(formulaCell("Sheet1!C1", "Sheet1", "C", 1, "A1+B1", "30"));
            sg.addCell(numericCell("Sheet1!A1", "Sheet1", "A", 1, "10"));
            sg.addDependency(dep("Sheet1!C1", "Sheet1!A1",
                    FormulaDependency.DependencyType.CELL_REFERENCE, "A1+B1", false));
            Graph g = sg.toGraph();

            Relationship rel = findRels(g, "DEPENDS_ON").get(0);
            assertEquals("A1+B1", rel.getMetadata().get("formula"));
            assertEquals(false, rel.getMetadata().get("crossSheet"));
            assertEquals("CELL_REFERENCE", rel.getMetadata().get("dependencyType"));
            assertEquals("EXTRACTED", rel.getMetadata().get("provenance"));
        }

        @Test
        void rangeInputMetadataIncludesRangeReference() {
            sg.addCell(formulaCell("Sheet1!C1", "Sheet1", "C", 1, "SUM(A1:A5)", "15"));
            sg.addCell(numericCell("Sheet1!A1", "Sheet1", "A", 1, "1"));
            sg.addDependency(dep("Sheet1!C1", "Sheet1!A1",
                    FormulaDependency.DependencyType.RANGE_REFERENCE, "SUM(A1:A5)", false, "A1:A5"));
            Graph g = sg.toGraph();

            Relationship rel = findRels(g, "RANGE_INPUT").get(0);
            assertEquals("A1:A5", rel.getMetadata().get("rangeReference"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CROSS_SHEET_LINK (sheet-to-sheet)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class CrossSheetLinkTests {

        @Test
        void crossSheetLinkCreatedBetweenSheets() {
            sg.addCell(formulaCell("Sheet1!C1", "Sheet1", "C", 1, "Sheet2!A1*2", "20"));
            sg.addCell(numericCell("Sheet2!A1", "Sheet2", "A", 1, "10"));
            sg.addDependency(dep("Sheet1!C1", "Sheet2!A1",
                    FormulaDependency.DependencyType.CROSS_SHEET_REFERENCE, "Sheet2!A1*2", true));
            Graph g = sg.toGraph();

            List<Relationship> links = findRels(g, "CROSS_SHEET_LINK");
            assertEquals(1, links.size());
            assertEquals(NS + "sheet:Sheet1", links.get(0).getSource());
            assertEquals(NS + "sheet:Sheet2", links.get(0).getTarget());
            assertEquals(0.9, links.get(0).getWeight());
        }

        @Test
        void crossSheetLinkDeduplicatedPerPair() {
            sg.addCell(formulaCell("Sheet1!C1", "Sheet1", "C", 1, "Sheet2!A1", "10"));
            sg.addCell(formulaCell("Sheet1!C2", "Sheet1", "C", 2, "Sheet2!B1", "20"));
            sg.addCell(numericCell("Sheet2!A1", "Sheet2", "A", 1, "10"));
            sg.addCell(numericCell("Sheet2!B1", "Sheet2", "B", 1, "20"));
            sg.addDependency(dep("Sheet1!C1", "Sheet2!A1",
                    FormulaDependency.DependencyType.CROSS_SHEET_REFERENCE, "Sheet2!A1", true));
            sg.addDependency(dep("Sheet1!C2", "Sheet2!B1",
                    FormulaDependency.DependencyType.CROSS_SHEET_REFERENCE, "Sheet2!B1", true));
            Graph g = sg.toGraph();

            List<Relationship> links = findRels(g, "CROSS_SHEET_LINK");
            assertEquals(1, links.size(), "Multiple deps between same sheet pair should produce one link");
        }

        @Test
        void crossSheetLinkDirectional() {
            // Sheet1→Sheet2 and Sheet2→Sheet1 are two distinct links
            sg.addCell(formulaCell("Sheet1!C1", "Sheet1", "C", 1, "Sheet2!A1", "10"));
            sg.addCell(formulaCell("Sheet2!C1", "Sheet2", "C", 1, "Sheet1!A1", "5"));
            sg.addCell(numericCell("Sheet2!A1", "Sheet2", "A", 1, "10"));
            sg.addCell(numericCell("Sheet1!A1", "Sheet1", "A", 1, "5"));
            sg.addDependency(dep("Sheet1!C1", "Sheet2!A1",
                    FormulaDependency.DependencyType.CROSS_SHEET_REFERENCE, "Sheet2!A1", true));
            sg.addDependency(dep("Sheet2!C1", "Sheet1!A1",
                    FormulaDependency.DependencyType.CROSS_SHEET_REFERENCE, "Sheet1!A1", true));
            Graph g = sg.toGraph();

            List<Relationship> links = findRels(g, "CROSS_SHEET_LINK");
            assertEquals(2, links.size(), "Opposite directions should produce two distinct links");
        }

        @Test
        void noCrossSheetLinkWhenNoCrossSheetDeps() {
            sg.addCell(formulaCell("Sheet1!C1", "Sheet1", "C", 1, "A1+B1", "30"));
            sg.addCell(numericCell("Sheet1!A1", "Sheet1", "A", 1, "10"));
            sg.addDependency(dep("Sheet1!C1", "Sheet1!A1",
                    FormulaDependency.DependencyType.CELL_REFERENCE, "A1+B1", false));
            Graph g = sg.toGraph();

            List<Relationship> links = findRels(g, "CROSS_SHEET_LINK");
            assertTrue(links.isEmpty());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Graph identity
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class GraphIdentityTests {

        @Test
        void graphIdContainsWorkbookName() {
            sg.addCell(numericCell("Sheet1!A1", "Sheet1", "A", 1, "1"));
            Graph g = sg.toGraph();
            assertEquals("spreadsheet-" + WB, g.getId());
        }

        @Test
        void nullWorkbookNameUsesUuidInGraphId() {
            SpreadsheetGraph noName = new SpreadsheetGraph();
            noName.addCell(numericCell("Sheet1!A1", "Sheet1", "A", 1, "1"));
            Graph g = noName.toGraph();
            assertTrue(g.getId().startsWith("spreadsheet-"));
            assertNotEquals("spreadsheet-null", g.getId());
        }

        @Test
        void allEntityConfidencesAreOne() {
            sg.addCell(numericCell("Sheet1!A1", "Sheet1", "A", 1, "1"));
            sg.addCell(formulaCell("Sheet1!B1", "Sheet1", "B", 1, "A1*2", "2"));
            Graph g = sg.toGraph();

            for (Entity e : g.getEntities()) {
                assertEquals(1.0, e.getConfidence(), "All toGraph entities should have confidence 1.0");
            }
        }

        @Test
        void allRelationshipConfidencesAreOne() {
            sg.addCell(formulaCell("Sheet1!C1", "Sheet1", "C", 1, "A1+B1", "30"));
            sg.addCell(numericCell("Sheet1!A1", "Sheet1", "A", 1, "10"));
            sg.addDependency(dep("Sheet1!C1", "Sheet1!A1",
                    FormulaDependency.DependencyType.CELL_REFERENCE, "A1+B1", false));
            Graph g = sg.toGraph();

            for (Relationship r : g.getRelationships()) {
                assertEquals(1.0, r.getConfidence());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // toSummary()
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class ToSummaryTests {

        @Test
        void summaryIncludesWorkbookName() {
            sg.addCell(numericCell("Sheet1!A1", "Sheet1", "A", 1, "10"));
            String summary = sg.toSummary();
            assertTrue(summary.contains(WB));
        }

        @Test
        void summaryCountsSheets() {
            sg.addCell(numericCell("Sheet1!A1", "Sheet1", "A", 1, "1"));
            sg.addCell(numericCell("Sheet2!A1", "Sheet2", "A", 1, "2"));
            String summary = sg.toSummary();
            assertTrue(summary.contains("Sheets: 2"));
        }

        @Test
        void summaryCountsFormulaCells() {
            sg.addCell(formulaCell("Sheet1!C1", "Sheet1", "C", 1, "A1+B1", "30"));
            sg.addCell(numericCell("Sheet1!A1", "Sheet1", "A", 1, "10"));
            String summary = sg.toSummary();
            assertTrue(summary.contains("Formula cells: 1"));
        }

        @Test
        void summaryCountsDependencies() {
            sg.addCell(formulaCell("Sheet1!C1", "Sheet1", "C", 1, "A1+B1", "30"));
            sg.addCell(numericCell("Sheet1!A1", "Sheet1", "A", 1, "10"));
            sg.addDependency(dep("Sheet1!C1", "Sheet1!A1",
                    FormulaDependency.DependencyType.CELL_REFERENCE, "A1+B1", false));
            String summary = sg.toSummary();
            assertTrue(summary.contains("Dependencies: 1"));
        }

        @Test
        void summaryCountsCrossSheetRefs() {
            sg.addCell(formulaCell("Sheet1!C1", "Sheet1", "C", 1, "Sheet2!A1", "10"));
            sg.addCell(numericCell("Sheet2!A1", "Sheet2", "A", 1, "10"));
            sg.addDependency(dep("Sheet1!C1", "Sheet2!A1",
                    FormulaDependency.DependencyType.CROSS_SHEET_REFERENCE, "Sheet2!A1", true));
            String summary = sg.toSummary();
            assertTrue(summary.contains("Cross-sheet refs: 1"));
        }

        @Test
        void summaryCountsNamedRanges() {
            sg.addCell(numericCell("Sheet1!A1", "Sheet1", "A", 1, "10"));
            sg.addNamedRange("Revenue", "Sheet1!A1");
            String summary = sg.toSummary();
            assertTrue(summary.contains("Named ranges: 1"));
            assertTrue(summary.contains("Revenue"));
        }

        @Test
        void summaryListsFormulasGroupedBySheet() {
            sg.addCell(formulaCell("Sheet1!C1", "Sheet1", "C", 1, "A1+B1", "30"));
            sg.addCell(formulaCell("Sheet2!D1", "Sheet2", "D", 1, "A1*2", "20"));
            sg.addCell(numericCell("Sheet1!A1", "Sheet1", "A", 1, "10"));
            sg.addCell(numericCell("Sheet2!A1", "Sheet2", "A", 1, "10"));
            String summary = sg.toSummary();

            assertTrue(summary.contains("Sheet 'Sheet1' formulas:"));
            assertTrue(summary.contains("Sheet 'Sheet2' formulas:"));
            assertTrue(summary.contains("A1+B1"));
            assertTrue(summary.contains("A1*2"));
        }

        @Test
        void summaryWithNoWorkbookNameOmitsColon() {
            SpreadsheetGraph noName = new SpreadsheetGraph();
            noName.addCell(numericCell("Sheet1!A1", "Sheet1", "A", 1, "10"));
            String summary = noName.toSummary();
            assertTrue(summary.startsWith("Spreadsheet Formula Graph\n"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Comprehensive integration
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void fullGraphHasAllRelationshipTypes() {
        // Build a realistic two-sheet workbook
        sg.addCell(numericCell("Sheet1!A1", "Sheet1", "A", 1, "1000"));
        sg.addCell(numericCell("Sheet1!A2", "Sheet1", "A", 2, "2000"));
        sg.addCell(formulaCell("Sheet1!B1", "Sheet1", "B", 1, "SUM(A1:A2)", "3000"));
        sg.addCell(namedRangeCell("Sheet1!C1", "Sheet1", "C", 1, "3000", "SubTotal"));
        sg.addCell(numericCell("Sheet2!A1", "Sheet2", "A", 1, "0.1"));
        sg.addCell(formulaCell("Sheet2!B1", "Sheet2", "B", 1, "Sheet1!C1*A1", "300"));

        // RANGE_REFERENCE: SUM(A1:A2) -> A1
        sg.addDependency(dep("Sheet1!B1", "Sheet1!A1",
                FormulaDependency.DependencyType.RANGE_REFERENCE, "SUM(A1:A2)", false, "A1:A2"));
        // CELL_REFERENCE: SUM result -> named range (same cell)
        sg.addDependency(dep("Sheet1!B1", "Sheet1!A2",
                FormulaDependency.DependencyType.CELL_REFERENCE, "SUM(A1:A2)", false));
        // NAMED_RANGE_REFERENCE: Sheet2!B1 -> SubTotal
        sg.addDependency(dep("Sheet2!B1", "Sheet1!C1",
                FormulaDependency.DependencyType.NAMED_RANGE_REFERENCE, "Sheet1!C1*A1", true));
        // CROSS_SHEET_REFERENCE: Sheet2!B1 -> Sheet2!A1
        sg.addDependency(dep("Sheet2!B1", "Sheet2!A1",
                FormulaDependency.DependencyType.CELL_REFERENCE, "Sheet1!C1*A1", false));

        sg.addNamedRange("SubTotal", "Sheet1!C1");

        Graph g = sg.toGraph();

        // Verify entity types present
        assertTrue(g.getEntities().stream().anyMatch(e -> "SHEET".equals(e.getType())));
        assertTrue(g.getEntities().stream().anyMatch(e -> "CELL".equals(e.getType())));
        assertTrue(g.getEntities().stream().anyMatch(e -> "FORMULA_CELL".equals(e.getType())));
        assertTrue(g.getEntities().stream().anyMatch(e -> "NAMED_RANGE".equals(e.getType())));

        // 6 cells + 2 sheets + 1 top-level named range = 9 entities
        assertEquals(9, g.getEntities().size());

        // Verify relationship types present
        assertFalse(findRels(g, "CONTAINS").isEmpty(), "Should have CONTAINS rels");
        assertFalse(findRels(g, "DEPENDS_ON").isEmpty(), "Should have DEPENDS_ON rels");
        assertFalse(findRels(g, "RANGE_INPUT").isEmpty(), "Should have RANGE_INPUT rels");
        assertFalse(findRels(g, "NAMED_RANGE_INPUT").isEmpty(), "Should have NAMED_RANGE_INPUT rels");
        assertFalse(findRels(g, "DEFINES").isEmpty(), "Should have DEFINES rels");

        // CROSS_SHEET_LINK created because of the cross-sheet named range dep
        assertFalse(findRels(g, "CROSS_SHEET_LINK").isEmpty(), "Should have CROSS_SHEET_LINK rels");

        // CONTAINS: one per cell (6) plus the top-level named range container link
        assertEquals(7, findRels(g, "CONTAINS").size());

        // Total relationship count: 7 CONTAINS + 4 dep rels + 1 cross-sheet link + 1 DEFINES = 13
        // (The NAMED_RANGE_REFERENCE dep is cross-sheet, so one CROSS_SHEET_LINK is created)
        assertEquals(13, g.getRelationships().size());
    }

    @Test
    void emptyGraphProducesEmptyEntitiesAndRelationships() {
        Graph g = sg.toGraph();
        assertTrue(g.getEntities().isEmpty());
        assertTrue(g.getRelationships().isEmpty());
    }
}
