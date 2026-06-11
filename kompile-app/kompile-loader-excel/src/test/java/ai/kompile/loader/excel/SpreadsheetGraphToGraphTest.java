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
import ai.kompile.loader.excel.graph.FormulaDependency;
import ai.kompile.loader.excel.graph.SpreadsheetGraph;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static ai.kompile.core.graphrag.GraphConstants.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive tests for {@link SpreadsheetGraph#toGraph()} which converts
 * the Excel formula graph model into the core knowledge graph model used by
 * the persistence pipeline (persistFormulaGraph / persistGraphJson).
 */
class SpreadsheetGraphToGraphTest {

    // ─────────────────────────────────────────────────────────────────────────
    // Helper builders
    // ─────────────────────────────────────────────────────────────────────────

    private CellNode valueCell(String sheetName, String col, int row, String value) {
        return CellNode.builder()
                .cellReference(sheetName + "!" + col + row)
                .sheetName(sheetName)
                .column(col)
                .row(row)
                .cellType("NUMERIC")
                .displayValue(value)
                .build();
    }

    private CellNode formulaCell(String sheetName, String col, int row, String formula, String value) {
        return CellNode.builder()
                .cellReference(sheetName + "!" + col + row)
                .sheetName(sheetName)
                .column(col)
                .row(row)
                .cellType("FORMULA")
                .formula(formula)
                .displayValue(value)
                .build();
    }

    private CellNode namedRangeCell(String sheetName, String col, int row, String rangeName) {
        return CellNode.builder()
                .cellReference(sheetName + "!" + col + row)
                .sheetName(sheetName)
                .column(col)
                .row(row)
                .cellType("NUMERIC")
                .displayValue("100")
                .namedRange(true)
                .namedRangeName(rangeName)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    class EntityCreation {

        @Test
        void createsSheetEntitiesForEachDistinctSheet() {
            SpreadsheetGraph graph = new SpreadsheetGraph();
            graph.setWorkbookName("budget.xlsx");
            graph.addCell(valueCell("Sheet1", "A", 1, "10"));
            graph.addCell(valueCell("Sheet2", "A", 1, "20"));

            Graph result = graph.toGraph();

            List<Entity> sheetEntities = result.getEntities().stream()
                    .filter(e -> ENTITY_SHEET.equals(e.getType()))
                    .toList();
            assertThat(sheetEntities).hasSize(2);

            Set<String> sheetTitles = sheetEntities.stream()
                    .map(Entity::getTitle).collect(Collectors.toSet());
            assertThat(sheetTitles).containsExactlyInAnyOrder("Sheet1", "Sheet2");
        }

        @Test
        void sheetEntityHasCompositeFlag() {
            SpreadsheetGraph graph = new SpreadsheetGraph();
            graph.setWorkbookName("test.xlsx");
            graph.addCell(valueCell("Data", "A", 1, "42"));

            Graph result = graph.toGraph();

            Entity sheetEntity = result.getEntities().stream()
                    .filter(e -> ENTITY_SHEET.equals(e.getType()))
                    .findFirst().orElseThrow();
            assertThat(sheetEntity.getMetadata()).containsEntry("isComposite", true);
            assertThat(sheetEntity.getMetadata()).containsEntry("workbook", "test.xlsx");
        }

        @Test
        void valueCellBecomesCellEntity() {
            SpreadsheetGraph graph = new SpreadsheetGraph();
            graph.setWorkbookName("test.xlsx");
            graph.addCell(valueCell("Sheet1", "B", 3, "500"));

            Graph result = graph.toGraph();

            Entity cellEntity = result.getEntities().stream()
                    .filter(e -> ENTITY_CELL.equals(e.getType()))
                    .findFirst().orElseThrow();
            assertThat(cellEntity.getTitle()).isEqualTo("Sheet1!B3");
            assertThat(cellEntity.getDescription()).contains("500");
            assertThat(cellEntity.getMetadata()).containsEntry("column", "B");
            assertThat(cellEntity.getMetadata()).containsEntry("row", 3);
            assertThat(cellEntity.getMetadata()).containsEntry("cellType", "NUMERIC");
        }

        @Test
        void formulaCellBecomesFormulaCellEntity() {
            SpreadsheetGraph graph = new SpreadsheetGraph();
            graph.setWorkbookName("test.xlsx");
            graph.addCell(formulaCell("Sheet1", "C", 1, "A1+B1", "30"));

            Graph result = graph.toGraph();

            Entity formulaEntity = result.getEntities().stream()
                    .filter(e -> ENTITY_FORMULA_CELL.equals(e.getType()))
                    .findFirst().orElseThrow();
            assertThat(formulaEntity.getDescription()).contains("A1+B1");
            assertThat(formulaEntity.getDescription()).contains("30");
            assertThat(formulaEntity.getMetadata()).containsEntry("formula", "A1+B1");
            assertThat(formulaEntity.getMetadata()).containsEntry("displayValue", "30");
        }

        @Test
        void namedRangeCellBecomesNamedRangeEntity() {
            SpreadsheetGraph graph = new SpreadsheetGraph();
            graph.setWorkbookName("test.xlsx");
            graph.addCell(namedRangeCell("Sheet1", "A", 1, "TotalSales"));

            Graph result = graph.toGraph();

            Entity nrEntity = result.getEntities().stream()
                    .filter(e -> ENTITY_NAMED_RANGE.equals(e.getType()))
                    .findFirst().orElseThrow();
            assertThat(nrEntity.getDescription()).contains("TotalSales");
            assertThat(nrEntity.getMetadata()).containsEntry("namedRangeName", "TotalSales");
        }

        @Test
        void cellWithCommentCreatesCellCommentEntity() {
            SpreadsheetGraph graph = new SpreadsheetGraph();
            graph.setWorkbookName("test.xlsx");
            CellNode cell = CellNode.builder()
                    .cellReference("Sheet1!A1")
                    .sheetName("Sheet1")
                    .column("A").row(1)
                    .cellType("NUMERIC")
                    .displayValue("100")
                    .comment("Revenue target for Q1")
                    .commentAuthor("CFO")
                    .build();
            graph.addCell(cell);

            Graph result = graph.toGraph();

            Entity commentEntity = result.getEntities().stream()
                    .filter(e -> "CELL_COMMENT".equals(e.getType()))
                    .findFirst().orElseThrow();
            assertThat(commentEntity.getTitle()).isEqualTo("Comment on Sheet1!A1");
            assertThat(commentEntity.getDescription()).contains("Revenue target for Q1");
            assertThat(commentEntity.getMetadata()).containsEntry("text", "Revenue target for Q1");
            assertThat(commentEntity.getMetadata()).containsEntry("author", "CFO");

            // Should have HAS_COMMENT relationship
            Relationship commentRel = result.getRelationships().stream()
                    .filter(r -> "HAS_COMMENT".equals(r.getType()))
                    .findFirst().orElseThrow();
            assertThat(commentRel.getSource()).contains("cell:Sheet1!A1");
            assertThat(commentRel.getTarget()).contains("comment:Sheet1!A1");
        }

        @Test
        void cellWithoutCommentCreatesNoCommentEntity() {
            SpreadsheetGraph graph = new SpreadsheetGraph();
            graph.setWorkbookName("test.xlsx");
            graph.addCell(valueCell("Sheet1", "A", 1, "100"));

            Graph result = graph.toGraph();

            assertThat(result.getEntities().stream()
                    .filter(e -> "CELL_COMMENT".equals(e.getType()))
                    .count()).isZero();
            assertThat(result.getRelationships().stream()
                    .filter(r -> "HAS_COMMENT".equals(r.getType()))
                    .count()).isZero();
        }

        @Test
        void commentMetadataStoredOnCellEntity() {
            SpreadsheetGraph graph = new SpreadsheetGraph();
            graph.setWorkbookName("test.xlsx");
            CellNode cell = CellNode.builder()
                    .cellReference("Sheet1!B2")
                    .sheetName("Sheet1")
                    .column("B").row(2)
                    .cellType("STRING")
                    .displayValue("Budget")
                    .comment("Needs review")
                    .commentAuthor("Analyst")
                    .build();
            graph.addCell(cell);

            Graph result = graph.toGraph();

            Entity cellEntity = result.getEntities().stream()
                    .filter(e -> ENTITY_CELL.equals(e.getType()))
                    .findFirst().orElseThrow();
            assertThat(cellEntity.getMetadata()).containsEntry("comment", "Needs review");
            assertThat(cellEntity.getMetadata()).containsEntry("commentAuthor", "Analyst");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    class Namespacing {

        @Test
        void entityIdsAreNamespacedWithWorkbookPrefix() {
            SpreadsheetGraph graph = new SpreadsheetGraph();
            graph.setWorkbookName("report.xlsx");
            graph.addCell(valueCell("Sheet1", "A", 1, "10"));

            Graph result = graph.toGraph();

            assertThat(result.getEntities()).allSatisfy(entity ->
                    assertThat(entity.getId()).startsWith("wb:report.xlsx/"));
        }

        @Test
        void twoWorkbooksProduceDifferentNamespaces() {
            SpreadsheetGraph graph1 = new SpreadsheetGraph();
            graph1.setWorkbookName("alpha.xlsx");
            graph1.addCell(valueCell("Sheet1", "A", 1, "10"));

            SpreadsheetGraph graph2 = new SpreadsheetGraph();
            graph2.setWorkbookName("beta.xlsx");
            graph2.addCell(valueCell("Sheet1", "A", 1, "20"));

            Graph result1 = graph1.toGraph();
            Graph result2 = graph2.toGraph();

            Set<String> ids1 = result1.getEntities().stream().map(Entity::getId).collect(Collectors.toSet());
            Set<String> ids2 = result2.getEntities().stream().map(Entity::getId).collect(Collectors.toSet());
            // Same cell reference "Sheet1!A1" should produce different entity IDs
            assertThat(ids1).doesNotContainAnyElementsOf(ids2);
        }

        @Test
        void nullWorkbookNameUsesUUIDNamespace() {
            SpreadsheetGraph graph = new SpreadsheetGraph();
            // Don't set workbookName — should use UUID fallback
            graph.addCell(valueCell("Sheet1", "A", 1, "10"));

            Graph result = graph.toGraph();

            assertThat(result.getEntities()).allSatisfy(entity ->
                    assertThat(entity.getId()).startsWith("wb:"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    class RelationshipCreation {

        @Test
        void containsRelationshipFromSheetToCell() {
            SpreadsheetGraph graph = new SpreadsheetGraph();
            graph.setWorkbookName("test.xlsx");
            graph.addCell(valueCell("Sheet1", "A", 1, "10"));

            Graph result = graph.toGraph();

            List<Relationship> containsRels = result.getRelationships().stream()
                    .filter(r -> REL_CONTAINS.equals(r.getType()))
                    .toList();
            assertThat(containsRels).hasSize(1);
            assertThat(containsRels.get(0).getSource()).isEqualTo("wb:test.xlsx/sheet:Sheet1");
            assertThat(containsRels.get(0).getTarget()).isEqualTo("wb:test.xlsx/cell:Sheet1!A1");
        }

        @Test
        void dependsOnRelationshipForCellReference() {
            SpreadsheetGraph graph = new SpreadsheetGraph();
            graph.setWorkbookName("test.xlsx");
            graph.addCell(valueCell("Sheet1", "A", 1, "10"));
            graph.addCell(formulaCell("Sheet1", "B", 1, "A1*2", "20"));
            graph.addDependency(FormulaDependency.builder()
                    .formulaCell("Sheet1!B1")
                    .referencedCell("Sheet1!A1")
                    .dependencyType(FormulaDependency.DependencyType.CELL_REFERENCE)
                    .formula("A1*2")
                    .build());

            Graph result = graph.toGraph();

            List<Relationship> depRels = result.getRelationships().stream()
                    .filter(r -> REL_DEPENDS_ON.equals(r.getType()))
                    .toList();
            assertThat(depRels).hasSize(1);
            assertThat(depRels.get(0).getSource()).endsWith("cell:Sheet1!B1");
            assertThat(depRels.get(0).getTarget()).endsWith("cell:Sheet1!A1");
            assertThat(depRels.get(0).getWeight()).isEqualTo(1.0);
        }

        @Test
        void rangeInputRelationship() {
            SpreadsheetGraph graph = new SpreadsheetGraph();
            graph.setWorkbookName("test.xlsx");
            graph.addCell(valueCell("Sheet1", "A", 1, "10"));
            graph.addCell(formulaCell("Sheet1", "A", 5, "SUM(A1:A4)", "40"));
            graph.addDependency(FormulaDependency.builder()
                    .formulaCell("Sheet1!A5")
                    .referencedCell("Sheet1!A1")
                    .dependencyType(FormulaDependency.DependencyType.RANGE_REFERENCE)
                    .formula("SUM(A1:A4)")
                    .rangeReference("A1:A4")
                    .build());

            Graph result = graph.toGraph();

            List<Relationship> rangeRels = result.getRelationships().stream()
                    .filter(r -> REL_RANGE_INPUT.equals(r.getType()))
                    .toList();
            assertThat(rangeRels).hasSize(1);
            assertThat(rangeRels.get(0).getMetadata()).containsEntry("rangeReference", "A1:A4");
        }

        @Test
        void crossSheetDependencyRelationship() {
            SpreadsheetGraph graph = new SpreadsheetGraph();
            graph.setWorkbookName("test.xlsx");
            graph.addCell(valueCell("Data", "A", 1, "100"));
            graph.addCell(formulaCell("Summary", "A", 1, "Data!A1", "100"));
            graph.addDependency(FormulaDependency.builder()
                    .formulaCell("Summary!A1")
                    .referencedCell("Data!A1")
                    .dependencyType(FormulaDependency.DependencyType.CROSS_SHEET_REFERENCE)
                    .crossSheet(true)
                    .formula("Data!A1")
                    .build());

            Graph result = graph.toGraph();

            // Cross-sheet dependency should produce CROSS_SHEET_DEPENDS_ON
            List<Relationship> crossDepRels = result.getRelationships().stream()
                    .filter(r -> REL_CROSS_SHEET_DEPENDS_ON.equals(r.getType()))
                    .toList();
            assertThat(crossDepRels).hasSize(1);
            assertThat(crossDepRels.get(0).getWeight()).isEqualTo(0.8);

            // Should also produce a CROSS_SHEET_LINK between the sheets
            List<Relationship> sheetLinks = result.getRelationships().stream()
                    .filter(r -> REL_CROSS_SHEET_LINK.equals(r.getType()))
                    .toList();
            assertThat(sheetLinks).hasSize(1);
            assertThat(sheetLinks.get(0).getSource()).endsWith("sheet:Summary");
            assertThat(sheetLinks.get(0).getTarget()).endsWith("sheet:Data");
        }

        @Test
        void namedRangeInputRelationship() {
            SpreadsheetGraph graph = new SpreadsheetGraph();
            graph.setWorkbookName("test.xlsx");
            graph.addCell(namedRangeCell("Sheet1", "A", 1, "Revenue"));
            graph.addCell(formulaCell("Sheet1", "B", 1, "Revenue*0.1", "10"));
            graph.addDependency(FormulaDependency.builder()
                    .formulaCell("Sheet1!B1")
                    .referencedCell("Sheet1!A1")
                    .dependencyType(FormulaDependency.DependencyType.NAMED_RANGE_REFERENCE)
                    .formula("Revenue*0.1")
                    .build());

            Graph result = graph.toGraph();

            List<Relationship> nrRels = result.getRelationships().stream()
                    .filter(r -> REL_NAMED_RANGE_INPUT.equals(r.getType()))
                    .toList();
            assertThat(nrRels).hasSize(1);
        }

        @Test
        void crossSheetLinkDeduplicatesPerPair() {
            SpreadsheetGraph graph = new SpreadsheetGraph();
            graph.setWorkbookName("test.xlsx");
            graph.addCell(valueCell("Data", "A", 1, "10"));
            graph.addCell(valueCell("Data", "A", 2, "20"));
            graph.addCell(formulaCell("Summary", "A", 1, "Data!A1", "10"));
            graph.addCell(formulaCell("Summary", "A", 2, "Data!A2", "20"));

            // Two cross-sheet deps from Summary→Data
            graph.addDependency(FormulaDependency.builder()
                    .formulaCell("Summary!A1").referencedCell("Data!A1")
                    .dependencyType(FormulaDependency.DependencyType.CROSS_SHEET_REFERENCE)
                    .crossSheet(true).formula("Data!A1").build());
            graph.addDependency(FormulaDependency.builder()
                    .formulaCell("Summary!A2").referencedCell("Data!A2")
                    .dependencyType(FormulaDependency.DependencyType.CROSS_SHEET_REFERENCE)
                    .crossSheet(true).formula("Data!A2").build());

            Graph result = graph.toGraph();

            // Only ONE cross-sheet link, even though two deps cross the same pair
            List<Relationship> sheetLinks = result.getRelationships().stream()
                    .filter(r -> REL_CROSS_SHEET_LINK.equals(r.getType()))
                    .toList();
            assertThat(sheetLinks).hasSize(1);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    class GraphHelpers {

        @Test
        void getFormulaCellsReturnsOnlyFormulaCells() {
            SpreadsheetGraph graph = new SpreadsheetGraph();
            graph.addCell(valueCell("Sheet1", "A", 1, "10"));
            graph.addCell(formulaCell("Sheet1", "B", 1, "A1+1", "11"));
            graph.addCell(valueCell("Sheet1", "C", 1, "hello"));

            assertThat(graph.getFormulaCells()).hasSize(1);
            assertThat(graph.getFormulaCells().get(0).getCellReference()).isEqualTo("Sheet1!B1");
        }

        @Test
        void getDependenciesOfReturnsDirectDependencies() {
            SpreadsheetGraph graph = new SpreadsheetGraph();
            graph.addCell(valueCell("Sheet1", "A", 1, "10"));
            graph.addCell(valueCell("Sheet1", "A", 2, "20"));
            graph.addCell(formulaCell("Sheet1", "A", 3, "A1+A2", "30"));
            graph.addDependency(FormulaDependency.builder()
                    .formulaCell("Sheet1!A3").referencedCell("Sheet1!A1")
                    .dependencyType(FormulaDependency.DependencyType.CELL_REFERENCE)
                    .formula("A1+A2").build());
            graph.addDependency(FormulaDependency.builder()
                    .formulaCell("Sheet1!A3").referencedCell("Sheet1!A2")
                    .dependencyType(FormulaDependency.DependencyType.CELL_REFERENCE)
                    .formula("A1+A2").build());

            List<CellNode> deps = graph.getDependenciesOf("Sheet1!A3");
            assertThat(deps).hasSize(2);
        }

        @Test
        void getDependentsReturnsReverseDeps() {
            SpreadsheetGraph graph = new SpreadsheetGraph();
            graph.addCell(valueCell("Sheet1", "A", 1, "10"));
            graph.addCell(formulaCell("Sheet1", "B", 1, "A1*2", "20"));
            graph.addCell(formulaCell("Sheet1", "C", 1, "A1+1", "11"));
            graph.addDependency(FormulaDependency.builder()
                    .formulaCell("Sheet1!B1").referencedCell("Sheet1!A1")
                    .dependencyType(FormulaDependency.DependencyType.CELL_REFERENCE)
                    .formula("A1*2").build());
            graph.addDependency(FormulaDependency.builder()
                    .formulaCell("Sheet1!C1").referencedCell("Sheet1!A1")
                    .dependencyType(FormulaDependency.DependencyType.CELL_REFERENCE)
                    .formula("A1+1").build());

            List<CellNode> dependents = graph.getDependents("Sheet1!A1");
            assertThat(dependents).hasSize(2);
        }

        @Test
        void getCrossSheetDependenciesFiltersCorrectly() {
            SpreadsheetGraph graph = new SpreadsheetGraph();
            graph.addDependency(FormulaDependency.builder()
                    .formulaCell("Sheet1!A1").referencedCell("Sheet1!B1")
                    .dependencyType(FormulaDependency.DependencyType.CELL_REFERENCE)
                    .crossSheet(false).formula("B1").build());
            graph.addDependency(FormulaDependency.builder()
                    .formulaCell("Summary!A1").referencedCell("Data!A1")
                    .dependencyType(FormulaDependency.DependencyType.CROSS_SHEET_REFERENCE)
                    .crossSheet(true).formula("Data!A1").build());

            assertThat(graph.getCrossSheetDependencies()).hasSize(1);
            assertThat(graph.getCrossSheetDependencies().get(0).getFormulaCell()).isEqualTo("Summary!A1");
        }

        @Test
        void getSheetNamesReturnsDistinctSheets() {
            SpreadsheetGraph graph = new SpreadsheetGraph();
            graph.addCell(valueCell("Alpha", "A", 1, "1"));
            graph.addCell(valueCell("Alpha", "A", 2, "2"));
            graph.addCell(valueCell("Beta", "A", 1, "3"));

            assertThat(graph.getSheetNames()).containsExactly("Alpha", "Beta");
        }

        @Test
        void toSummaryIncludesFormulasAndNamedRanges() {
            SpreadsheetGraph graph = new SpreadsheetGraph();
            graph.setWorkbookName("finance.xlsx");
            graph.addCell(formulaCell("Sheet1", "C", 1, "A1+B1", "30"));
            graph.addNamedRange("Total", "Sheet1!C1");
            graph.addDependency(FormulaDependency.builder()
                    .formulaCell("Sheet1!C1").referencedCell("Sheet1!A1")
                    .dependencyType(FormulaDependency.DependencyType.CELL_REFERENCE)
                    .formula("A1+B1").build());

            String summary = graph.toSummary();
            assertThat(summary).contains("finance.xlsx");
            assertThat(summary).contains("Formula cells: 1");
            assertThat(summary).contains("Dependencies: 1");
            assertThat(summary).contains("Named ranges: 1");
            assertThat(summary).contains("A1+B1");
            assertThat(summary).contains("Total -> Sheet1!C1");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    @Nested
    class EntityCounts {

        @Test
        void emptyGraphProducesEmptyResult() {
            SpreadsheetGraph graph = new SpreadsheetGraph();
            graph.setWorkbookName("empty.xlsx");

            Graph result = graph.toGraph();

            assertThat(result.getEntities()).isEmpty();
            assertThat(result.getRelationships()).isEmpty();
        }

        @Test
        void singleCellProducesSheetPlusCellPlusContains() {
            SpreadsheetGraph graph = new SpreadsheetGraph();
            graph.setWorkbookName("simple.xlsx");
            graph.addCell(valueCell("Sheet1", "A", 1, "42"));

            Graph result = graph.toGraph();

            // 1 sheet + 1 cell = 2 entities
            assertThat(result.getEntities()).hasSize(2);
            // 1 CONTAINS relationship
            assertThat(result.getRelationships()).hasSize(1);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Named Range entities
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class NamedRangeEntities {

        @Test
        void namedRangeCreatesTopLevelEntity() {
            SpreadsheetGraph graph = new SpreadsheetGraph();
            graph.setWorkbookName("budget.xlsx");
            graph.addCell(valueCell("Sheet1", "B", 2, "1000"));
            graph.addNamedRange("TotalBudget", "Sheet1!B2");

            Graph result = graph.toGraph();

            List<Entity> namedRangeEntities = result.getEntities().stream()
                    .filter(e -> ENTITY_NAMED_RANGE.equals(e.getType())
                            && e.getId().contains("namedrange:"))
                    .toList();
            assertThat(namedRangeEntities).hasSize(1);

            Entity nrEntity = namedRangeEntities.get(0);
            assertThat(nrEntity.getTitle()).isEqualTo("TotalBudget");
            assertThat(nrEntity.getMetadata().get("rangeName")).isEqualTo("TotalBudget");
            assertThat(nrEntity.getMetadata().get("refersTo")).isEqualTo("Sheet1!B2");
        }

        @Test
        void namedRangeHasDefinesRelationToTargetCell() {
            SpreadsheetGraph graph = new SpreadsheetGraph();
            graph.setWorkbookName("budget.xlsx");
            graph.addCell(valueCell("Sheet1", "B", 2, "1000"));
            graph.addNamedRange("TotalBudget", "Sheet1!B2");

            Graph result = graph.toGraph();

            List<Relationship> definesRels = result.getRelationships().stream()
                    .filter(r -> "DEFINES".equals(r.getType()))
                    .toList();
            assertThat(definesRels).hasSize(1);
            assertThat(definesRels.get(0).getSource()).contains("namedrange:TotalBudget");
            assertThat(definesRels.get(0).getTarget()).contains("cell:Sheet1!B2");
        }

        @Test
        void namedRangeLinkedToContainingSheet() {
            SpreadsheetGraph graph = new SpreadsheetGraph();
            graph.setWorkbookName("budget.xlsx");
            graph.addCell(valueCell("Sheet1", "B", 2, "1000"));
            graph.addNamedRange("TotalBudget", "Sheet1!B2");

            Graph result = graph.toGraph();

            List<Relationship> containsRels = result.getRelationships().stream()
                    .filter(r -> REL_CONTAINS.equals(r.getType())
                            && r.getTarget().contains("namedrange:"))
                    .toList();
            assertThat(containsRels).hasSize(1);
            assertThat(containsRels.get(0).getSource()).contains("sheet:Sheet1");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Structured Table entities
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class StructuredTableEntities {

        @Test
        void structuredTableCreatesTableEntity() {
            SpreadsheetGraph graph = new SpreadsheetGraph();
            graph.setWorkbookName("data.xlsx");
            graph.addCell(valueCell("Sales", "A", 1, "Product"));
            graph.addStructuredTable(new SpreadsheetGraph.StructuredTable(
                    "SalesTable", "Sales", "Sales Data Table",
                    0, 10, 0, 3,
                    List.of("Product", "Q1", "Q2", "Q3")
            ));

            Graph result = graph.toGraph();

            List<Entity> tableEntities = result.getEntities().stream()
                    .filter(e -> ENTITY_TABLE.equals(e.getType()))
                    .toList();
            assertThat(tableEntities).hasSize(1);

            Entity tblEntity = tableEntities.get(0);
            assertThat(tblEntity.getTitle()).isEqualTo("Sales Data Table");
            assertThat(tblEntity.getMetadata().get("tableName")).isEqualTo("SalesTable");
            assertThat(tblEntity.getMetadata().get(PROP_HEADERS)).isEqualTo("Product, Q1, Q2, Q3");
        }

        @Test
        void structuredTableLinkedToSheet() {
            SpreadsheetGraph graph = new SpreadsheetGraph();
            graph.setWorkbookName("data.xlsx");
            graph.addCell(valueCell("Sales", "A", 1, "Product"));
            graph.addStructuredTable(new SpreadsheetGraph.StructuredTable(
                    "SalesTable", "Sales", "Sales Data",
                    0, 5, 0, 2,
                    List.of("Product", "Revenue")
            ));

            Graph result = graph.toGraph();

            List<Relationship> containsRels = result.getRelationships().stream()
                    .filter(r -> REL_CONTAINS.equals(r.getType())
                            && r.getTarget().contains("table:"))
                    .toList();
            assertThat(containsRels).hasSize(1);
            assertThat(containsRels.get(0).getSource()).contains("sheet:Sales");
        }

        @Test
        void noStructuredTablesProducesNoTableEntities() {
            SpreadsheetGraph graph = new SpreadsheetGraph();
            graph.setWorkbookName("plain.xlsx");
            graph.addCell(valueCell("Sheet1", "A", 1, "hello"));

            Graph result = graph.toGraph();

            assertThat(result.getEntities().stream()
                    .noneMatch(e -> ENTITY_TABLE.equals(e.getType())))
                    .isTrue();
        }
    }
}
