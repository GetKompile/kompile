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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that {@link SpreadsheetGraph#toGraph()} correctly namespaces all entity and
 * relationship IDs with the {@code wb:<workbookName>/} prefix, preventing collisions
 * when multiple workbooks are loaded into the same knowledge graph.
 */
class SpreadsheetGraphNamespacingTest {

    // ─── helpers ────────────────────────────────────────────────────────────────

    /**
     * Build a minimal CellNode for the given fully-qualified cell reference.
     * The reference must be in "SheetName!ColRow" format (e.g. "Sheet1!A1").
     */
    private CellNode cell(String cellRef, String sheetName, String column, int row) {
        return CellNode.builder()
                .cellReference(cellRef)
                .sheetName(sheetName)
                .column(column)
                .row(row)
                .cellType("NUMERIC")
                .displayValue("42")
                .build();
    }

    private CellNode formulaCell(String cellRef, String sheetName, String column, int row,
                                  String formula) {
        return CellNode.builder()
                .cellReference(cellRef)
                .sheetName(sheetName)
                .column(column)
                .row(row)
                .cellType("FORMULA")
                .formula(formula)
                .displayValue("99")
                .build();
    }

    // ─── tests ──────────────────────────────────────────────────────────────────

    /**
     * Every entity ID in the resulting Graph must begin with "wb:Budget.xlsx/".
     */
    @Test
    void toGraph_entityIdsAreNamespaced() {
        SpreadsheetGraph sg = new SpreadsheetGraph();
        sg.setWorkbookName("Budget.xlsx");
        sg.addCell(cell("Sheet1!A1", "Sheet1", "A", 1));
        sg.addCell(cell("Sheet1!B1", "Sheet1", "B", 1));

        Graph graph = sg.toGraph();

        assertFalse(graph.getEntities().isEmpty(), "Graph should contain entities");
        for (Entity entity : graph.getEntities()) {
            assertTrue(
                    entity.getId().startsWith("wb:Budget.xlsx/"),
                    "Entity ID '" + entity.getId() + "' does not start with 'wb:Budget.xlsx/'");
        }
    }

    /**
     * The sheet entity for "Sheet1" must have the exact ID "wb:Budget.xlsx/sheet:Sheet1".
     */
    @Test
    void toGraph_sheetEntityHasCorrectPrefix() {
        SpreadsheetGraph sg = new SpreadsheetGraph();
        sg.setWorkbookName("Budget.xlsx");
        sg.addCell(cell("Sheet1!A1", "Sheet1", "A", 1));

        Graph graph = sg.toGraph();

        List<Entity> sheetEntities = graph.getEntities().stream()
                .filter(e -> "SHEET".equals(e.getType()))
                .collect(Collectors.toList());

        assertEquals(1, sheetEntities.size(), "Expected exactly one SHEET entity");
        assertEquals("wb:Budget.xlsx/sheet:Sheet1", sheetEntities.get(0).getId());
    }

    /**
     * The cell entity for "Sheet1!A1" must have the exact ID "wb:Budget.xlsx/cell:Sheet1!A1".
     */
    @Test
    void toGraph_cellEntityHasCorrectPrefix() {
        SpreadsheetGraph sg = new SpreadsheetGraph();
        sg.setWorkbookName("Budget.xlsx");
        sg.addCell(cell("Sheet1!A1", "Sheet1", "A", 1));

        Graph graph = sg.toGraph();

        List<Entity> cellEntities = graph.getEntities().stream()
                .filter(e -> "CELL".equals(e.getType()))
                .collect(Collectors.toList());

        assertEquals(1, cellEntities.size(), "Expected exactly one CELL entity");
        assertEquals("wb:Budget.xlsx/cell:Sheet1!A1", cellEntities.get(0).getId());
    }

    /**
     * Two workbooks that both contain a "Sheet1" must produce non-overlapping entity IDs.
     */
    @Test
    void toGraph_crossWorkbookIsolation() {
        SpreadsheetGraph sg1 = new SpreadsheetGraph();
        sg1.setWorkbookName("Q1.xlsx");
        sg1.addCell(cell("Sheet1!A1", "Sheet1", "A", 1));

        SpreadsheetGraph sg2 = new SpreadsheetGraph();
        sg2.setWorkbookName("Q2.xlsx");
        sg2.addCell(cell("Sheet1!A1", "Sheet1", "A", 1));

        Graph graph1 = sg1.toGraph();
        Graph graph2 = sg2.toGraph();

        Set<String> ids1 = graph1.getEntities().stream()
                .map(Entity::getId)
                .collect(Collectors.toSet());
        Set<String> ids2 = graph2.getEntities().stream()
                .map(Entity::getId)
                .collect(Collectors.toSet());

        // The two sets must be completely disjoint
        Set<String> intersection = ids1.stream()
                .filter(ids2::contains)
                .collect(Collectors.toSet());

        assertTrue(intersection.isEmpty(),
                "Workbook entity IDs must not collide across workbooks; collision: " + intersection);
    }

    /**
     * Relationship source and target IDs must also carry the namespace prefix.
     */
    @Test
    void toGraph_relationshipSourceTargetAreNamespaced() {
        SpreadsheetGraph sg = new SpreadsheetGraph();
        sg.setWorkbookName("Budget.xlsx");

        CellNode srcCell = formulaCell("Sheet1!C1", "Sheet1", "C", 1, "A1+B1");
        CellNode refCell = cell("Sheet1!A1", "Sheet1", "A", 1);
        sg.addCell(srcCell);
        sg.addCell(refCell);

        FormulaDependency dep = FormulaDependency.builder()
                .formulaCell("Sheet1!C1")
                .referencedCell("Sheet1!A1")
                .dependencyType(FormulaDependency.DependencyType.CELL_REFERENCE)
                .formula("A1+B1")
                .crossSheet(false)
                .build();
        sg.addDependency(dep);

        Graph graph = sg.toGraph();

        assertFalse(graph.getRelationships().isEmpty(), "Expected at least one relationship");

        for (Relationship rel : graph.getRelationships()) {
            assertTrue(
                    rel.getSource().startsWith("wb:Budget.xlsx/"),
                    "Relationship source '" + rel.getSource() + "' is not namespaced");
            assertTrue(
                    rel.getTarget().startsWith("wb:Budget.xlsx/"),
                    "Relationship target '" + rel.getTarget() + "' is not namespaced");
        }
    }

    /**
     * When workbookName is null, toGraph() falls back to a UUID.
     * All entity IDs must still start with "wb:" and the same UUID-based prefix must be
     * used consistently throughout the graph (i.e. no two different prefixes appear).
     */
    @Test
    void toGraph_nullWorkbookName_usesUUID() {
        SpreadsheetGraph sg = new SpreadsheetGraph();
        // workbookName deliberately left null
        sg.addCell(cell("Sheet1!A1", "Sheet1", "A", 1));

        Graph graph = sg.toGraph();

        assertFalse(graph.getEntities().isEmpty(), "Expected entities even with null workbookName");

        // All entity IDs must start with "wb:"
        for (Entity entity : graph.getEntities()) {
            assertTrue(
                    entity.getId().startsWith("wb:"),
                    "Entity ID '" + entity.getId() + "' must start with 'wb:' even when workbookName is null");
        }

        // All IDs must share the same prefix (consistent UUID usage)
        Set<String> prefixes = graph.getEntities().stream()
                .map(e -> {
                    // Extract "wb:<uuid>/" prefix: everything up to and including the first "/" after "wb:"
                    int slash = e.getId().indexOf('/', 3); // skip "wb:"
                    return slash >= 0 ? e.getId().substring(0, slash + 1) : e.getId();
                })
                .collect(Collectors.toSet());

        assertEquals(1, prefixes.size(),
                "All entity IDs must share the same 'wb:<uuid>/' prefix, but found: " + prefixes);
    }
}
