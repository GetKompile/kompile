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

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static ai.kompile.core.graphrag.GraphConstants.*;

/**
 * Builds a cell-level {@link Graph} from raw tabular data (rows and columns).
 *
 * <p>Produces the same {@code Graph}/{@code Entity}/{@code Relationship} model
 * that {@code SpreadsheetGraph.toGraph()} uses for Excel, so the downstream
 * {@code persistFormulaGraph()} pipeline can persist it unchanged.</p>
 *
 * <p>Entity types produced:
 * <ul>
 *   <li>{@code TABLE} — composite container (one per table)</li>
 *   <li>{@code HEADER_CELL} — cells in the header row</li>
 *   <li>{@code CELL} — data cells</li>
 * </ul>
 *
 * <p>Relationship types produced:
 * <ul>
 *   <li>{@code CONTAINS} — TABLE → every CELL (mandatory)</li>
 *   <li>{@code HEADER_OF} — HEADER_CELL → each data CELL in the same column (configurable)</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * Graph g = new TableCellGraphBuilder()
 *     .namespace("html:page.html/tbl:0")
 *     .tableName("Revenue Summary")
 *     .headers(List.of("Region", "Q1", "Q2"))
 *     .addRow(List.of("Region", "Q1", "Q2"))   // header row
 *     .addRow(List.of("North", "100", "200"))
 *     .addRow(List.of("South", "150", "180"))
 *     .build();
 * String json = TableCellGraphBuilder.toJson(g);
 * }</pre>
 */
public class TableCellGraphBuilder {

    private static final Logger log = LoggerFactory.getLogger(TableCellGraphBuilder.class);
    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    private String namespace;
    private String tableName;
    private List<String> headers;
    private final List<List<String>> rows = new ArrayList<>();
    private boolean firstRowIsHeader = true;
    private boolean includeHeaderRelations = true;
    private int maxCellsForGraph = 5000;

    public TableCellGraphBuilder namespace(String namespace) {
        this.namespace = namespace;
        return this;
    }

    public TableCellGraphBuilder tableName(String tableName) {
        this.tableName = tableName;
        return this;
    }

    public TableCellGraphBuilder headers(List<String> headers) {
        this.headers = headers != null ? new ArrayList<>(headers) : null;
        return this;
    }

    /**
     * Bulk-set all rows at once, replacing any previously added rows.
     *
     * @param rows list of rows, each row being a list of cell values
     * @return this builder
     */
    public TableCellGraphBuilder rows(List<List<String>> rows) {
        this.rows.clear();
        if (rows != null) {
            for (List<String> row : rows) {
                this.rows.add(row != null ? new ArrayList<>(row) : new ArrayList<>());
            }
        }
        return this;
    }

    public TableCellGraphBuilder addRow(List<String> cellValues) {
        rows.add(cellValues != null ? new ArrayList<>(cellValues) : List.of());
        return this;
    }

    public TableCellGraphBuilder firstRowIsHeader(boolean firstRowIsHeader) {
        this.firstRowIsHeader = firstRowIsHeader;
        return this;
    }

    public TableCellGraphBuilder includeHeaderRelations(boolean includeHeaderRelations) {
        this.includeHeaderRelations = includeHeaderRelations;
        return this;
    }

    public TableCellGraphBuilder maxCellsForGraph(int maxCellsForGraph) {
        this.maxCellsForGraph = maxCellsForGraph;
        return this;
    }

    /**
     * Build a {@link Graph} with TABLE and CELL entities from the accumulated rows.
     *
     * @return a Graph ready for JSON serialization and persistence via {@code persistFormulaGraph}
     */
    public Graph build() {
        Graph graph = new Graph();
        graph.setEntities(new ArrayList<>());
        graph.setRelationships(new ArrayList<>());

        if (rows.isEmpty()) return graph;

        String ns = namespace != null ? namespace : UUID.randomUUID().toString();
        graph.setId("table-" + ns);
        String tblName = tableName != null ? tableName : "Table";

        // Determine header row and data rows
        List<String> effectiveHeaders;
        int dataStartRow;
        if (firstRowIsHeader && !rows.isEmpty()) {
            effectiveHeaders = rows.get(0);
            dataStartRow = 1;
        } else if (headers != null && !headers.isEmpty()) {
            effectiveHeaders = headers;
            dataStartRow = 0;
        } else {
            effectiveHeaders = List.of();
            dataStartRow = 0;
        }

        // Safety cap: skip graph for huge tables
        int maxCols = rows.stream().mapToInt(List::size).max().orElse(0);
        int totalCells = rows.size() * maxCols;
        if (totalCells > maxCellsForGraph) {
            log.debug("Table '{}' has {} cells (>{}) — skipping cell graph", tblName, totalCells, maxCellsForGraph);
            return graph;
        }

        String tableEntityId = "tbl:" + ns + "/table:" + tblName;

        // TABLE entity
        Entity tableEntity = new Entity();
        tableEntity.setId(tableEntityId);
        tableEntity.setTitle(tblName);
        tableEntity.setType(ENTITY_TABLE);
        tableEntity.setDescription("Table: " + tblName);
        tableEntity.setConfidence(1.0);
        Map<String, Object> tableMeta = new LinkedHashMap<>();
        tableMeta.put("isComposite", true);
        tableMeta.put(PROP_ROW_COUNT, rows.size() - (firstRowIsHeader ? 1 : 0));
        tableMeta.put(PROP_COLUMN_COUNT, maxCols);
        if (!effectiveHeaders.isEmpty()) {
            tableMeta.put(PROP_HEADERS, String.join(", ", effectiveHeaders));
        }
        tableMeta.put(PROP_ENTITY_SOURCE, SOURCE_TABLE_CELL_GRAPH_BUILDER);
        tableEntity.setMetadata(tableMeta);
        graph.getEntities().add(tableEntity);

        // Track header cell IDs per column for HEADER_OF relationships
        Map<Integer, String> headerCellIds = new LinkedHashMap<>();

        // Header row cells
        if (firstRowIsHeader && !rows.isEmpty()) {
            List<String> headerRow = rows.get(0);
            for (int col = 0; col < headerRow.size(); col++) {
                String cellValue = headerRow.get(col);
                String cellId = tableEntityId + "/cell:R0C" + col;

                Entity cellEntity = new Entity();
                cellEntity.setId(cellId);
                cellEntity.setTitle(cellValue != null && !cellValue.isEmpty() ? cellValue : "R0C" + col);
                cellEntity.setType(ENTITY_HEADER_CELL);
                cellEntity.setDescription("Header cell [0," + col + "]: " + cellValue);
                cellEntity.setConfidence(1.0);

                Map<String, Object> cellMeta = new LinkedHashMap<>();
                cellMeta.put(PROP_ROW_INDEX, 0);
                cellMeta.put(PROP_COL_INDEX, col);
                cellMeta.put(PROP_CELL_VALUE, cellValue);
                cellMeta.put(PROP_IS_HEADER, true);
                cellMeta.put(PROP_ENTITY_SOURCE, SOURCE_TABLE_CELL_GRAPH_BUILDER);
                cellMeta.put("parentEntityId", tableEntityId);
                cellEntity.setMetadata(cellMeta);

                graph.getEntities().add(cellEntity);
                headerCellIds.put(col, cellId);

                // CONTAINS: TABLE → HEADER_CELL
                graph.getRelationships().add(containsRelationship(tableEntityId, cellId, tblName, cellValue));
            }
        }

        // Data rows
        for (int rowIdx = dataStartRow; rowIdx < rows.size(); rowIdx++) {
            List<String> row = rows.get(rowIdx);
            for (int col = 0; col < row.size(); col++) {
                String cellValue = row.get(col);
                String cellId = tableEntityId + "/cell:R" + rowIdx + "C" + col;

                String columnName = (col < effectiveHeaders.size()) ? effectiveHeaders.get(col) : null;

                Entity cellEntity = new Entity();
                cellEntity.setId(cellId);
                cellEntity.setTitle(cellValue != null && !cellValue.isEmpty()
                        ? cellValue : "R" + rowIdx + "C" + col);
                cellEntity.setType(ENTITY_CELL);
                cellEntity.setDescription(String.format("Cell [%d,%d]%s: %s",
                        rowIdx, col,
                        columnName != null ? " (" + columnName + ")" : "",
                        cellValue));
                cellEntity.setConfidence(1.0);

                Map<String, Object> cellMeta = new LinkedHashMap<>();
                cellMeta.put(PROP_ROW_INDEX, rowIdx);
                cellMeta.put(PROP_COL_INDEX, col);
                cellMeta.put(PROP_CELL_VALUE, cellValue);
                cellMeta.put(PROP_IS_HEADER, false);
                if (columnName != null) {
                    cellMeta.put(PROP_COLUMN_NAME, columnName);
                }
                cellMeta.put(PROP_ENTITY_SOURCE, SOURCE_TABLE_CELL_GRAPH_BUILDER);
                cellMeta.put("parentEntityId", tableEntityId);
                cellEntity.setMetadata(cellMeta);

                graph.getEntities().add(cellEntity);

                // CONTAINS: TABLE → CELL
                graph.getRelationships().add(containsRelationship(tableEntityId, cellId, tblName, cellValue));

                // HEADER_OF: HEADER_CELL → data CELL in same column
                if (includeHeaderRelations && headerCellIds.containsKey(col)) {
                    Relationship headerOf = new Relationship();
                    headerOf.setSource(headerCellIds.get(col));
                    headerOf.setTarget(cellId);
                    headerOf.setType(REL_HEADER_OF);
                    headerOf.setDescription(String.format("'%s' is header of cell [%d,%d]",
                            col < effectiveHeaders.size() ? effectiveHeaders.get(col) : "?",
                            rowIdx, col));
                    headerOf.setWeight(0.8);
                    headerOf.setConfidence(1.0);
                    headerOf.setMetadata(Collections.singletonMap("provenance", (Object) PROVENANCE_EXTRACTED));
                    graph.getRelationships().add(headerOf);
                }
            }
        }

        return graph;
    }

    private static Relationship containsRelationship(String tableId, String cellId,
                                                      String tableName, String cellValue) {
        Relationship rel = new Relationship();
        rel.setSource(tableId);
        rel.setTarget(cellId);
        rel.setType(REL_CONTAINS);
        rel.setDescription(String.format("Table '%s' contains cell '%s'", tableName, cellValue));
        rel.setWeight(1.0);
        rel.setConfidence(1.0);
        rel.setMetadata(Collections.singletonMap("provenance", (Object) PROVENANCE_EXTRACTED));
        return rel;
    }

    /**
     * Serialize a {@link Graph} to JSON.
     *
     * @param graph the graph to serialize
     * @return JSON string, or empty JSON object on failure
     */
    public static String toJson(Graph graph) {
        try {
            return MAPPER.writeValueAsString(graph);
        } catch (Exception e) {
            log.warn("Failed to serialize table graph: {}", e.getMessage());
            return "{}";
        }
    }
}
