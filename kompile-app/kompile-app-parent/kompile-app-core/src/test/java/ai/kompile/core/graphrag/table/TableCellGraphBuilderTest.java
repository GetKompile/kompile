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
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TableCellGraphBuilderTest {

    // --- build() basics ---

    @Test
    void emptyRowsReturnsEmptyGraph() {
        Graph graph = new TableCellGraphBuilder().build();
        assertNotNull(graph);
        assertTrue(graph.getEntities().isEmpty());
        assertTrue(graph.getRelationships().isEmpty());
    }

    @Test
    void singleRowHeaderOnlyProducesTableAndHeaderCells() {
        Graph graph = new TableCellGraphBuilder()
                .namespace("test-ns")
                .tableName("TestTable")
                .addRow(List.of("Name", "Age"))
                .build();

        // 1 TABLE + 2 HEADER_CELL = 3 entities
        assertEquals(3, graph.getEntities().size());
        assertEquals(1, countByType(graph, GraphConstants.ENTITY_TABLE));
        assertEquals(2, countByType(graph, GraphConstants.ENTITY_HEADER_CELL));
        // Only CONTAINS (TABLE→HEADER_CELL), no HEADER_OF since there are no data rows
        long containsCount = graph.getRelationships().stream()
                .filter(r -> GraphConstants.REL_CONTAINS.equals(r.getType())).count();
        assertEquals(2, containsCount);
        long headerOfCount = graph.getRelationships().stream()
                .filter(r -> GraphConstants.REL_HEADER_OF.equals(r.getType())).count();
        assertEquals(0, headerOfCount);
    }

    @Test
    void headerPlusDataRowsProducesCorrectEntities() {
        Graph graph = new TableCellGraphBuilder()
                .namespace("test")
                .tableName("Sales")
                .addRow(List.of("Region", "Q1", "Q2"))
                .addRow(List.of("North", "100", "200"))
                .addRow(List.of("South", "150", "180"))
                .build();

        // 1 TABLE + 3 HEADER_CELL + 6 CELL = 10
        assertEquals(10, graph.getEntities().size());
        assertEquals(1, countByType(graph, GraphConstants.ENTITY_TABLE));
        assertEquals(3, countByType(graph, GraphConstants.ENTITY_HEADER_CELL));
        assertEquals(6, countByType(graph, GraphConstants.ENTITY_CELL));
    }

    @Test
    void containsRelationshipsLinkTableToAllCells() {
        Graph graph = new TableCellGraphBuilder()
                .namespace("test")
                .tableName("T")
                .addRow(List.of("A", "B"))
                .addRow(List.of("1", "2"))
                .build();

        // TABLE → 2 HEADER_CELL + 2 CELL = 4 CONTAINS
        long containsCount = graph.getRelationships().stream()
                .filter(r -> GraphConstants.REL_CONTAINS.equals(r.getType())).count();
        assertEquals(4, containsCount);

        String tableId = graph.getEntities().stream()
                .filter(e -> GraphConstants.ENTITY_TABLE.equals(e.getType()))
                .map(Entity::getId).findFirst().orElseThrow();
        assertTrue(graph.getRelationships().stream()
                .filter(r -> GraphConstants.REL_CONTAINS.equals(r.getType()))
                .allMatch(r -> r.getSource().equals(tableId)));
    }

    @Test
    void headerOfRelationshipsLinkHeaderToDataCellsInSameColumn() {
        Graph graph = new TableCellGraphBuilder()
                .namespace("test")
                .tableName("T")
                .addRow(List.of("A", "B"))
                .addRow(List.of("1", "2"))
                .addRow(List.of("3", "4"))
                .build();

        // 2 headers × 2 data rows = 4 HEADER_OF
        long headerOfCount = graph.getRelationships().stream()
                .filter(r -> GraphConstants.REL_HEADER_OF.equals(r.getType())).count();
        assertEquals(4, headerOfCount);
    }

    // --- Entity IDs ---

    @Test
    void entityIdsFollowExpectedConvention() {
        Graph graph = new TableCellGraphBuilder()
                .namespace("html:page/tbl:0")
                .tableName("Revenue")
                .addRow(List.of("Col1"))
                .addRow(List.of("Val1"))
                .build();

        assertTrue(graph.getEntities().stream()
                .anyMatch(e -> e.getId().equals("tbl:html:page/tbl:0/table:Revenue")));
        assertTrue(graph.getEntities().stream()
                .anyMatch(e -> e.getId().contains("/cell:R0C0")));
        assertTrue(graph.getEntities().stream()
                .anyMatch(e -> e.getId().contains("/cell:R1C0")));
    }

    // --- Table entity metadata ---

    @Test
    void tableEntityHasCorrectMetadata() {
        Graph graph = new TableCellGraphBuilder()
                .namespace("test")
                .tableName("Stats")
                .addRow(List.of("A", "B"))
                .addRow(List.of("1", "2"))
                .build();

        Entity tableEntity = graph.getEntities().stream()
                .filter(e -> GraphConstants.ENTITY_TABLE.equals(e.getType()))
                .findFirst().orElseThrow();

        assertTrue(Boolean.TRUE.equals(tableEntity.getMetadata().get("isComposite")));
        assertEquals("Stats", tableEntity.getTitle());
        assertEquals(1, tableEntity.getMetadata().get(GraphConstants.PROP_ROW_COUNT));
        assertEquals(2, tableEntity.getMetadata().get(GraphConstants.PROP_COLUMN_COUNT));
        assertEquals("A, B", tableEntity.getMetadata().get(GraphConstants.PROP_HEADERS));
        assertEquals(GraphConstants.SOURCE_TABLE_CELL_GRAPH_BUILDER,
                tableEntity.getMetadata().get(GraphConstants.PROP_ENTITY_SOURCE));
    }

    // --- Cell entity metadata ---

    @Test
    void cellEntityHasCorrectMetadata() {
        Graph graph = new TableCellGraphBuilder()
                .namespace("test")
                .tableName("T")
                .addRow(List.of("Name"))
                .addRow(List.of("Alice"))
                .build();

        Entity dataCell = graph.getEntities().stream()
                .filter(e -> GraphConstants.ENTITY_CELL.equals(e.getType()))
                .findFirst().orElseThrow();

        assertEquals("Alice", dataCell.getTitle());
        assertEquals(1, dataCell.getMetadata().get(GraphConstants.PROP_ROW_INDEX));
        assertEquals(0, dataCell.getMetadata().get(GraphConstants.PROP_COL_INDEX));
        assertEquals("Alice", dataCell.getMetadata().get(GraphConstants.PROP_CELL_VALUE));
        assertEquals(false, dataCell.getMetadata().get(GraphConstants.PROP_IS_HEADER));
        assertEquals("Name", dataCell.getMetadata().get(GraphConstants.PROP_COLUMN_NAME));
    }

    @Test
    void headerCellEntityHasCorrectMetadata() {
        Graph graph = new TableCellGraphBuilder()
                .namespace("test")
                .tableName("T")
                .addRow(List.of("ColA"))
                .addRow(List.of("val"))
                .build();

        Entity headerCell = graph.getEntities().stream()
                .filter(e -> GraphConstants.ENTITY_HEADER_CELL.equals(e.getType()))
                .findFirst().orElseThrow();

        assertEquals("ColA", headerCell.getTitle());
        assertEquals(true, headerCell.getMetadata().get(GraphConstants.PROP_IS_HEADER));
    }

    // --- parentEntityId ---

    @Test
    void cellsHaveParentEntityIdPointingToTable() {
        Graph graph = new TableCellGraphBuilder()
                .namespace("test")
                .tableName("T")
                .addRow(List.of("H"))
                .addRow(List.of("V"))
                .build();

        String tableId = graph.getEntities().stream()
                .filter(e -> GraphConstants.ENTITY_TABLE.equals(e.getType()))
                .map(Entity::getId).findFirst().orElseThrow();

        assertTrue(graph.getEntities().stream()
                .filter(e -> !GraphConstants.ENTITY_TABLE.equals(e.getType()))
                .allMatch(e -> tableId.equals(e.getMetadata().get("parentEntityId"))));
    }

    // --- firstRowIsHeader = false ---

    @Test
    void firstRowIsHeaderFalseWithExplicitHeaders() {
        Graph graph = new TableCellGraphBuilder()
                .namespace("test")
                .tableName("T")
                .headers(List.of("X", "Y"))
                .firstRowIsHeader(false)
                .addRow(List.of("1", "2"))
                .addRow(List.of("3", "4"))
                .build();

        // No HEADER_CELLs, all rows are data CELLs
        assertEquals(0, countByType(graph, GraphConstants.ENTITY_HEADER_CELL));
        assertEquals(4, countByType(graph, GraphConstants.ENTITY_CELL));
        // Column names should still come from explicit headers
        Entity cell = graph.getEntities().stream()
                .filter(e -> "1".equals(e.getTitle()))
                .findFirst().orElseThrow();
        assertEquals("X", cell.getMetadata().get(GraphConstants.PROP_COLUMN_NAME));
    }

    // --- includeHeaderRelations = false ---

    @Test
    void includeHeaderRelationsFalseSkipsHeaderOf() {
        Graph graph = new TableCellGraphBuilder()
                .namespace("test")
                .tableName("T")
                .includeHeaderRelations(false)
                .addRow(List.of("A"))
                .addRow(List.of("1"))
                .build();

        long headerOfCount = graph.getRelationships().stream()
                .filter(r -> GraphConstants.REL_HEADER_OF.equals(r.getType())).count();
        assertEquals(0, headerOfCount);
    }

    // --- Safety cap ---

    @Test
    void safetyCapSkipsHugeTables() {
        TableCellGraphBuilder builder = new TableCellGraphBuilder()
                .namespace("test")
                .tableName("Huge")
                .maxCellsForGraph(5);
        // 3 rows × 3 cols = 9 > 5
        builder.addRow(List.of("A", "B", "C"));
        builder.addRow(List.of("1", "2", "3"));
        builder.addRow(List.of("4", "5", "6"));

        Graph graph = builder.build();
        assertTrue(graph.getEntities().isEmpty());
    }

    @Test
    void tableJustUnderCapIsBuilt() {
        TableCellGraphBuilder builder = new TableCellGraphBuilder()
                .namespace("test")
                .tableName("Small")
                .maxCellsForGraph(10);
        // 2 rows × 2 cols = 4 <= 10
        builder.addRow(List.of("A", "B"));
        builder.addRow(List.of("1", "2"));

        Graph graph = builder.build();
        assertFalse(graph.getEntities().isEmpty());
    }

    // --- Provenance ---

    @Test
    void allRelationshipsHaveExtractedProvenance() {
        Graph graph = new TableCellGraphBuilder()
                .namespace("test")
                .tableName("T")
                .addRow(List.of("H"))
                .addRow(List.of("V"))
                .build();

        assertTrue(graph.getRelationships().stream()
                .allMatch(r -> GraphConstants.PROVENANCE_EXTRACTED.equals(
                        r.getMetadata() != null ? r.getMetadata().get("provenance") : null)));
    }

    // --- Default values ---

    @Test
    void defaultNamespaceAndTableNameAreGenerated() {
        Graph graph = new TableCellGraphBuilder()
                .addRow(List.of("A"))
                .addRow(List.of("1"))
                .build();

        assertNotNull(graph.getId());
        assertTrue(graph.getId().startsWith("table-"));
        Entity tableEntity = graph.getEntities().stream()
                .filter(e -> GraphConstants.ENTITY_TABLE.equals(e.getType()))
                .findFirst().orElseThrow();
        assertEquals("Table", tableEntity.getTitle());
    }

    // --- toJson ---

    @Test
    void toJsonProducesValidJson() {
        Graph graph = new TableCellGraphBuilder()
                .namespace("test")
                .tableName("T")
                .addRow(List.of("A"))
                .addRow(List.of("1"))
                .build();

        String json = TableCellGraphBuilder.toJson(graph);
        assertNotNull(json);
        assertTrue(json.contains("\"entities\""));
        assertTrue(json.contains("\"relationships\""));
        assertTrue(json.contains("HEADER_CELL"));
        assertTrue(json.contains("CELL"));
    }

    @Test
    void toJsonEmptyGraphProducesValidJson() {
        Graph graph = new TableCellGraphBuilder().build();
        String json = TableCellGraphBuilder.toJson(graph);
        assertNotNull(json);
        assertFalse(json.equals("{}"));
    }

    // --- Edge cases ---

    @Test
    void emptyCellValuesGetPositionalTitles() {
        Graph graph = new TableCellGraphBuilder()
                .namespace("test")
                .tableName("T")
                .addRow(List.of("H", ""))
                .addRow(List.of("", "val"))
                .build();

        assertTrue(graph.getEntities().stream()
                .anyMatch(e -> "R0C1".equals(e.getTitle())));
        assertTrue(graph.getEntities().stream()
                .anyMatch(e -> "R1C0".equals(e.getTitle())));
    }

    @Test
    void raggedRowsHandledGracefully() {
        Graph graph = new TableCellGraphBuilder()
                .namespace("test")
                .tableName("T")
                .addRow(List.of("A", "B", "C"))
                .addRow(List.of("1"))  // shorter row
                .build();

        // 1 TABLE + 3 HEADER_CELL + 1 CELL = 5
        assertEquals(5, graph.getEntities().size());
    }

    @Test
    void graphIdContainsNamespace() {
        Graph graph = new TableCellGraphBuilder()
                .namespace("my-ns")
                .addRow(List.of("A"))
                .build();

        assertEquals("table-my-ns", graph.getId());
    }

    // --- Utility ---

    private long countByType(Graph graph, String type) {
        return graph.getEntities().stream()
                .filter(e -> type.equals(e.getType())).count();
    }
}
