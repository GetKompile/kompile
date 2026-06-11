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

package ai.kompile.tool.tablesearch;

import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.retrievers.DocumentRetriever;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.core.structured.TableDocument;
import ai.kompile.core.structured.TableMetadata;
import ai.kompile.vectorstore.anserini.AnseriniVectorStoreImpl;
import ai.kompile.vectorstore.anserini.AnseriniVectorStoreProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.springframework.ai.document.Document;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link TableSearchToolImpl}.
 *
 * <p>These tests use a real {@link AnseriniVectorStoreImpl} backed by a temp directory
 * to verify end-to-end table indexing and search. A stub {@link EmbeddingModel}
 * produces deterministic 8-dimensional vectors so no real model is needed.</p>
 */
class TableSearchToolImplTest {

    @TempDir
    Path tempDir;

    private AnseriniVectorStoreImpl vectorStore;
    private TableSearchToolImpl tool;
    private StubDocumentRetriever stubRetriever;

    private static final String SALES_TABLE_MD =
            "| Product | Q1 Sales | Q2 Sales | Q3 Sales |\n" +
            "| ------- | -------- | -------- | -------- |\n" +
            "| Widget  | 1200     | 1500     | 1800     |\n" +
            "| Gadget  | 800      | 950      | 1100     |\n" +
            "| Gizmo   | 2000     | 2200     | 2400     |";

    private static final String EMPLOYEE_TABLE_MD =
            "| Name    | Department | Role           |\n" +
            "| ------- | ---------- | -------------- |\n" +
            "| Alice   | Engineering| Senior Engineer |\n" +
            "| Bob     | Marketing  | Manager        |\n" +
            "| Charlie | Sales      | Associate      |";

    @BeforeEach
    void setUp() {
        AnseriniVectorStoreProperties props = new AnseriniVectorStoreProperties();
        props.setIndexPath(tempDir.resolve("vector-index").toString());
        props.setPersistenceEnabled(false);
        props.setMemoryBufferSizeMb(16.0);
        props.setBatchCommitInterval(1);

        StubEmbeddingModel embeddingModel = new StubEmbeddingModel();
        vectorStore = new AnseriniVectorStoreImpl(props, embeddingModel);

        indexTestDocuments();

        stubRetriever = new StubDocumentRetriever();

        tool = new TableSearchToolImpl(
                vectorStore,
                List.of(stubRetriever),
                null,
                null
        );
    }

    @AfterEach
    void tearDown() throws Exception {
        if (vectorStore != null) {
            vectorStore.destroy();
        }
    }

    // ── list_tables ──────────────────────────────────────────────────────────

    @Test
    void listTables_returnsOnlyTableDocuments() {
        Map<String, Object> result = tool.listTables(
                new TableSearchToolImpl.ListTablesInput(null, null));

        assertThat(result).doesNotContainKey("error");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = (List<Map<String, Object>>) result.get("tables");
        assertThat(tables).isNotNull();
        // We indexed 2 tables and 1 plain text — only tables should appear
        assertThat(tables).hasSize(2);
    }

    @Test
    void listTables_respectsPagination() {
        Map<String, Object> result = tool.listTables(
                new TableSearchToolImpl.ListTablesInput(0, 1));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = (List<Map<String, Object>>) result.get("tables");
        assertThat(tables).hasSize(1);
        assertThat(result.get("limit")).isEqualTo(1);
    }

    @Test
    void listTables_offsetSkipsTables() {
        Map<String, Object> result = tool.listTables(
                new TableSearchToolImpl.ListTablesInput(1, 10));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = (List<Map<String, Object>>) result.get("tables");
        assertThat(tables).hasSize(1);
        assertThat(result.get("offset")).isEqualTo(1);
    }

    // ── search_tables ────────────────────────────────────────────────────────

    @Test
    void searchTables_findsMatchingTables() {
        Map<String, Object> result = tool.searchTables(
                new TableSearchToolImpl.SearchTablesInput("quarterly sales", null));

        assertThat(result).doesNotContainKey("error");
        assertThat(result.get("query")).isEqualTo("quarterly sales");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = (List<Map<String, Object>>) result.get("tables");
        assertThat(tables).isNotEmpty();
        Map<String, Object> firstTable = tables.get(0);
        assertThat(firstTable.get("content")).asString().contains("Widget");
    }

    @Test
    void searchTables_filtersOutNonTableDocuments() {
        Map<String, Object> result = tool.searchTables(
                new TableSearchToolImpl.SearchTablesInput("general information", 10));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = (List<Map<String, Object>>) result.get("tables");
        // All results should be tables, even though the retriever returns a text doc too
        for (Map<String, Object> table : tables) {
            assertThat(table).containsKey("headers");
        }
    }

    @Test
    void searchTables_returnsErrorForEmptyQuery() {
        Map<String, Object> result = tool.searchTables(
                new TableSearchToolImpl.SearchTablesInput("", null));

        assertThat(result).containsKey("error");
    }

    @Test
    void searchTables_respectsMaxResults() {
        Map<String, Object> result = tool.searchTables(
                new TableSearchToolImpl.SearchTablesInput("data", 1));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = (List<Map<String, Object>>) result.get("tables");
        assertThat(tables.size()).isLessThanOrEqualTo(1);
    }

    @Test
    void searchTables_includesScoreAndMetadata() {
        Map<String, Object> result = tool.searchTables(
                new TableSearchToolImpl.SearchTablesInput("sales data", null));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tables = (List<Map<String, Object>>) result.get("tables");
        assertThat(tables).isNotEmpty();

        Map<String, Object> table = tables.get(0);
        assertThat(table).containsKey("score");
        assertThat(table).containsKey("row_count");
        assertThat(table).containsKey("column_count");
        assertThat(table).containsKey("source_filename");
    }

    // ── get_table ────────────────────────────────────────────────────────────

    @Test
    void getTable_returnsTableById() {
        Map<String, Object> result = tool.getTable(
                new TableSearchToolImpl.GetTableInput("table-sales-001"));

        assertThat(result).doesNotContainKey("error");

        @SuppressWarnings("unchecked")
        Map<String, Object> table = (Map<String, Object>) result.get("table");
        assertThat(table).isNotNull();
        assertThat(table.get("content")).asString().contains("Widget");
        assertThat(table.get("content")).asString().contains("Gadget");
        assertThat(table.get("content")).asString().contains("Gizmo");
    }

    @Test
    void getTable_returnsFullMetadata() {
        Map<String, Object> result = tool.getTable(
                new TableSearchToolImpl.GetTableInput("table-employee-002"));

        @SuppressWarnings("unchecked")
        Map<String, Object> table = (Map<String, Object>) result.get("table");
        assertThat(table).isNotNull();
        assertThat(table.get("content")).asString().contains("Alice");
        assertThat(table.get("content")).asString().contains("Bob");
    }

    @Test
    void getTable_returnsErrorForMissingId() {
        Map<String, Object> result = tool.getTable(
                new TableSearchToolImpl.GetTableInput("nonexistent-id"));

        assertThat(result).containsKey("error");
        assertThat(result.get("error")).asString().contains("not found");
    }

    @Test
    void getTable_returnsErrorForEmptyId() {
        Map<String, Object> result = tool.getTable(
                new TableSearchToolImpl.GetTableInput(""));

        assertThat(result).containsKey("error");
    }

    @Test
    void getTable_returnsErrorForNonTableDocument() {
        Map<String, Object> result = tool.getTable(
                new TableSearchToolImpl.GetTableInput("text-doc-001"));

        assertThat(result).containsKey("error");
        assertThat(result.get("error")).asString().contains("not a table");
    }

    // ── search_table_entries ─────────────────────────────────────────────────

    @Test
    void searchTableEntries_findsMatchingRows() {
        Map<String, Object> result = tool.searchTableEntries(
                new TableSearchToolImpl.SearchTableEntriesInput("table-sales-001", "Widget"));

        assertThat(result).doesNotContainKey("error");
        assertThat(result.get("tableId")).isEqualTo("table-sales-001");
        assertThat(result.get("query")).isEqualTo("Widget");

        @SuppressWarnings("unchecked")
        List<String> matchingRows = (List<String>) result.get("matchingRows");
        // Header + separator + the Widget row = 3
        assertThat(matchingRows).hasSize(3);
        assertThat(matchingRows.stream().anyMatch(r -> r.contains("Widget"))).isTrue();

        assertThat(result.get("matchCount")).isEqualTo(1);
        assertThat(result.get("totalRows")).isEqualTo(3);
    }

    @Test
    void searchTableEntries_caseInsensitiveMatch() {
        Map<String, Object> result = tool.searchTableEntries(
                new TableSearchToolImpl.SearchTableEntriesInput("table-sales-001", "gadget"));

        @SuppressWarnings("unchecked")
        List<String> matchingRows = (List<String>) result.get("matchingRows");
        // Header + separator + the Gadget row
        assertThat(matchingRows).hasSize(3);
        assertThat(result.get("matchCount")).isEqualTo(1);
    }

    @Test
    void searchTableEntries_multipleMatches() {
        Map<String, Object> result = tool.searchTableEntries(
                new TableSearchToolImpl.SearchTableEntriesInput("table-sales-001", "00"));

        @SuppressWarnings("unchecked")
        List<String> matchingRows = (List<String>) result.get("matchingRows");
        // Header + separator + all 3 data rows (all contain "00" in sales figures)
        assertThat(matchingRows).hasSize(5);
        assertThat(result.get("matchCount")).isEqualTo(3);
    }

    @Test
    void searchTableEntries_noMatchesReturnsHeaderOnly() {
        Map<String, Object> result = tool.searchTableEntries(
                new TableSearchToolImpl.SearchTableEntriesInput("table-sales-001", "zzz_no_match"));

        @SuppressWarnings("unchecked")
        List<String> matchingRows = (List<String>) result.get("matchingRows");
        // Only header + separator
        assertThat(matchingRows).hasSize(2);
        assertThat(result.get("matchCount")).isEqualTo(0);
    }

    @Test
    void searchTableEntries_returnsErrorForMissingTable() {
        Map<String, Object> result = tool.searchTableEntries(
                new TableSearchToolImpl.SearchTableEntriesInput("nonexistent", "Widget"));

        assertThat(result).containsKey("error");
    }

    @Test
    void searchTableEntries_returnsErrorForEmptyQuery() {
        Map<String, Object> result = tool.searchTableEntries(
                new TableSearchToolImpl.SearchTableEntriesInput("table-sales-001", ""));

        assertThat(result).containsKey("error");
    }

    @Test
    void searchTableEntries_returnsErrorForEmptyTableId() {
        Map<String, Object> result = tool.searchTableEntries(
                new TableSearchToolImpl.SearchTableEntriesInput("", "Widget"));

        assertThat(result).containsKey("error");
    }

    @Test
    void searchTableEntries_searchesEmployeeTable() {
        Map<String, Object> result = tool.searchTableEntries(
                new TableSearchToolImpl.SearchTableEntriesInput("table-employee-002", "Alice"));

        assertThat(result).doesNotContainKey("error");

        @SuppressWarnings("unchecked")
        List<String> matchingRows = (List<String>) result.get("matchingRows");
        assertThat(matchingRows.stream().anyMatch(r -> r.contains("Alice"))).isTrue();
        assertThat(result.get("matchCount")).isEqualTo(1);
    }

    // ── analyze_table ─────────────────────────────────────────────────────

    @Test
    void analyzeTable_overviewAllColumns() {
        Map<String, Object> result = tool.analyzeTable(
                new TableSearchToolImpl.AnalyzeTableInput("table-sales-001", null));

        assertThat(result).doesNotContainKey("error");
        assertThat(result.get("row_count")).isEqualTo(3);
        assertThat(result.get("column_count")).isEqualTo(4);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns = (List<Map<String, Object>>) result.get("columns");
        assertThat(columns).hasSize(4);

        // Product column should be text type
        Map<String, Object> productStats = columns.get(0);
        assertThat(productStats.get("column")).isEqualTo("Product");
        @SuppressWarnings("unchecked")
        Map<String, Object> pStats = (Map<String, Object>) productStats.get("stats");
        assertThat(pStats.get("type")).isEqualTo("text");

        // Q1 Sales column should be numeric
        Map<String, Object> q1Stats = columns.get(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> q1s = (Map<String, Object>) q1Stats.get("stats");
        assertThat(q1s.get("type")).isEqualTo("numeric");
    }

    @Test
    void analyzeTable_singleNumericColumn() {
        Map<String, Object> result = tool.analyzeTable(
                new TableSearchToolImpl.AnalyzeTableInput("table-sales-001", "Q1 Sales"));

        assertThat(result).doesNotContainKey("error");
        assertThat(result.get("column")).isEqualTo("Q1 Sales");

        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) result.get("column_stats");
        assertThat(stats.get("type")).isEqualTo("numeric");
        assertThat((Double) stats.get("min")).isEqualTo(800.0);
        assertThat((Double) stats.get("max")).isEqualTo(2000.0);
        assertThat((Double) stats.get("sum")).isEqualTo(4000.0);
    }

    @Test
    void analyzeTable_singleTextColumn() {
        Map<String, Object> result = tool.analyzeTable(
                new TableSearchToolImpl.AnalyzeTableInput("table-employee-002", "Department"));

        assertThat(result).doesNotContainKey("error");

        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) result.get("column_stats");
        assertThat(stats.get("type")).isEqualTo("text");
        assertThat(stats.get("unique_values")).isEqualTo(3);
    }

    @Test
    void analyzeTable_errorForMissingColumn() {
        Map<String, Object> result = tool.analyzeTable(
                new TableSearchToolImpl.AnalyzeTableInput("table-sales-001", "Nonexistent"));

        assertThat(result).containsKey("error");
        assertThat(result.get("error")).asString().contains("not found");
    }

    @Test
    void analyzeTable_errorForMissingTable() {
        Map<String, Object> result = tool.analyzeTable(
                new TableSearchToolImpl.AnalyzeTableInput("nonexistent", null));

        assertThat(result).containsKey("error");
    }

    // ── filter_table ─────────────────────────────────────────────────────────

    @Test
    void filterTable_equalsMatch() {
        Map<String, Object> result = tool.filterTable(
                new TableSearchToolImpl.FilterTableInput("table-sales-001", "Product", "eq", "Widget"));

        assertThat(result).doesNotContainKey("error");
        assertThat(result.get("matched_rows")).isEqualTo(1);
        assertThat(result.get("table")).asString().contains("Widget");
        assertThat(result.get("table")).asString().doesNotContain("Gadget");
    }

    @Test
    void filterTable_containsMatch() {
        Map<String, Object> result = tool.filterTable(
                new TableSearchToolImpl.FilterTableInput("table-employee-002", "Role", "contains", "Engineer"));

        assertThat(result).doesNotContainKey("error");
        assertThat(result.get("matched_rows")).isEqualTo(1);
        assertThat(result.get("table")).asString().contains("Alice");
    }

    @Test
    void filterTable_numericGreaterThan() {
        Map<String, Object> result = tool.filterTable(
                new TableSearchToolImpl.FilterTableInput("table-sales-001", "Q1 Sales", "gt", "1000"));

        assertThat(result).doesNotContainKey("error");
        assertThat(result.get("matched_rows")).isEqualTo(2); // Widget(1200) and Gizmo(2000)
        assertThat(result.get("table")).asString().contains("Widget");
        assertThat(result.get("table")).asString().contains("Gizmo");
        assertThat(result.get("table")).asString().doesNotContain("Gadget");
    }

    @Test
    void filterTable_numericLessThan() {
        Map<String, Object> result = tool.filterTable(
                new TableSearchToolImpl.FilterTableInput("table-sales-001", "Q1 Sales", "lt", "1000"));

        assertThat(result).doesNotContainKey("error");
        assertThat(result.get("matched_rows")).isEqualTo(1); // Gadget(800)
    }

    @Test
    void filterTable_noMatches() {
        Map<String, Object> result = tool.filterTable(
                new TableSearchToolImpl.FilterTableInput("table-sales-001", "Product", "eq", "NoSuchProduct"));

        assertThat(result).doesNotContainKey("error");
        assertThat(result.get("matched_rows")).isEqualTo(0);
    }

    @Test
    void filterTable_errorMissingColumn() {
        Map<String, Object> result = tool.filterTable(
                new TableSearchToolImpl.FilterTableInput("table-sales-001", "BadCol", "eq", "x"));

        assertThat(result).containsKey("error");
    }

    // ── sort_table ───────────────────────────────────────────────────────────

    @Test
    void sortTable_numericAscending() {
        Map<String, Object> result = tool.sortTable(
                new TableSearchToolImpl.SortTableInput("table-sales-001", "Q1 Sales", false));

        assertThat(result).doesNotContainKey("error");
        String table = (String) result.get("table");
        // Gadget(800) should come before Widget(1200) which is before Gizmo(2000)
        int gadgetIdx = table.indexOf("Gadget");
        int widgetIdx = table.indexOf("Widget");
        int gizmoIdx = table.indexOf("Gizmo");
        assertThat(gadgetIdx).isLessThan(widgetIdx);
        assertThat(widgetIdx).isLessThan(gizmoIdx);
    }

    @Test
    void sortTable_numericDescending() {
        Map<String, Object> result = tool.sortTable(
                new TableSearchToolImpl.SortTableInput("table-sales-001", "Q1 Sales", true));

        assertThat(result).doesNotContainKey("error");
        assertThat(result.get("descending")).isEqualTo(true);
        String table = (String) result.get("table");
        // Gizmo(2000) should come before Widget(1200) which is before Gadget(800)
        int gizmoIdx = table.indexOf("Gizmo");
        int widgetIdx = table.indexOf("Widget");
        int gadgetIdx = table.indexOf("Gadget");
        assertThat(gizmoIdx).isLessThan(widgetIdx);
        assertThat(widgetIdx).isLessThan(gadgetIdx);
    }

    @Test
    void sortTable_textAlphabetical() {
        Map<String, Object> result = tool.sortTable(
                new TableSearchToolImpl.SortTableInput("table-sales-001", "Product", false));

        assertThat(result).doesNotContainKey("error");
        String table = (String) result.get("table");
        // Gadget < Gizmo < Widget alphabetically
        assertThat(table.indexOf("Gadget")).isLessThan(table.indexOf("Gizmo"));
        assertThat(table.indexOf("Gizmo")).isLessThan(table.indexOf("Widget"));
    }

    @Test
    void sortTable_errorMissingColumn() {
        Map<String, Object> result = tool.sortTable(
                new TableSearchToolImpl.SortTableInput("table-sales-001", "BadCol", false));

        assertThat(result).containsKey("error");
    }

    // ── extract_columns ──────────────────────────────────────────────────────

    @Test
    void extractColumns_subsetOfColumns() {
        Map<String, Object> result = tool.extractColumns(
                new TableSearchToolImpl.ExtractColumnsInput("table-sales-001",
                        List.of("Product", "Q3 Sales")));

        assertThat(result).doesNotContainKey("error");

        @SuppressWarnings("unchecked")
        List<String> extracted = (List<String>) result.get("extracted_columns");
        assertThat(extracted).containsExactly("Product", "Q3 Sales");
        assertThat(result.get("row_count")).isEqualTo(3);

        String table = (String) result.get("table");
        assertThat(table).contains("Product");
        assertThat(table).contains("Q3 Sales");
        // Q1/Q2 columns should not appear
        assertThat(table).doesNotContain("Q1 Sales");
        assertThat(table).doesNotContain("Q2 Sales");
    }

    @Test
    void extractColumns_singleColumn() {
        Map<String, Object> result = tool.extractColumns(
                new TableSearchToolImpl.ExtractColumnsInput("table-employee-002", List.of("Name")));

        assertThat(result).doesNotContainKey("error");
        String table = (String) result.get("table");
        assertThat(table).contains("Alice");
        assertThat(table).doesNotContain("Department");
    }

    @Test
    void extractColumns_errorMissingColumn() {
        Map<String, Object> result = tool.extractColumns(
                new TableSearchToolImpl.ExtractColumnsInput("table-sales-001",
                        List.of("Product", "BadCol")));

        assertThat(result).containsKey("error");
    }

    @Test
    void extractColumns_errorEmptyList() {
        Map<String, Object> result = tool.extractColumns(
                new TableSearchToolImpl.ExtractColumnsInput("table-sales-001", List.of()));

        assertThat(result).containsKey("error");
    }

    // ── export_table ─────────────────────────────────────────────────────────

    @Test
    void exportTable_csv() {
        Map<String, Object> result = tool.exportTable(
                new TableSearchToolImpl.ExportTableInput("table-sales-001", "csv"));

        assertThat(result).doesNotContainKey("error");
        assertThat(result.get("format")).isEqualTo("csv");
        String csv = (String) result.get("data");
        assertThat(csv).contains("Product,Q1 Sales,Q2 Sales,Q3 Sales");
        assertThat(csv).contains("Widget,1200,1500,1800");
        assertThat(csv).contains("Gadget,800,950,1100");
    }

    @Test
    void exportTable_json() {
        Map<String, Object> result = tool.exportTable(
                new TableSearchToolImpl.ExportTableInput("table-sales-001", "json"));

        assertThat(result).doesNotContainKey("error");
        assertThat(result.get("format")).isEqualTo("json");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("data");
        assertThat(rows).hasSize(3);
        // JSON export should auto-parse numeric values
        Map<String, Object> firstRow = rows.get(0);
        assertThat(firstRow.get("Product")).isEqualTo("Widget");
        assertThat(firstRow.get("Q1 Sales")).isEqualTo(1200L);
    }

    @Test
    void exportTable_markdown() {
        Map<String, Object> result = tool.exportTable(
                new TableSearchToolImpl.ExportTableInput("table-sales-001", "markdown"));

        assertThat(result).doesNotContainKey("error");
        String md = (String) result.get("data");
        assertThat(md).contains("|");
        assertThat(md).contains("Widget");
    }

    @Test
    void exportTable_defaultsToJson() {
        Map<String, Object> result = tool.exportTable(
                new TableSearchToolImpl.ExportTableInput("table-sales-001", null));

        assertThat(result).doesNotContainKey("error");
        assertThat(result.get("format")).isEqualTo("json");
        assertThat(result.get("data")).isInstanceOf(List.class);
    }

    @Test
    void exportTable_errorUnsupportedFormat() {
        Map<String, Object> result = tool.exportTable(
                new TableSearchToolImpl.ExportTableInput("table-sales-001", "xml"));

        assertThat(result).containsKey("error");
    }

    @Test
    void exportTable_errorMissingTable() {
        Map<String, Object> result = tool.exportTable(
                new TableSearchToolImpl.ExportTableInput("nonexistent", "json"));

        assertThat(result).containsKey("error");
    }

    @Test
    void exportTable_includesMetadata() {
        Map<String, Object> result = tool.exportTable(
                new TableSearchToolImpl.ExportTableInput("table-sales-001", "csv"));

        assertThat(result.get("row_count")).isEqualTo(3);
        assertThat(result.get("column_count")).isEqualTo(4);

        @SuppressWarnings("unchecked")
        List<String> headers = (List<String>) result.get("headers");
        assertThat(headers).containsExactly("Product", "Q1 Sales", "Q2 Sales", "Q3 Sales");
    }

    // ── Null vector store / retriever handling ───────────────────────────────

    @Test
    void listTables_returnsErrorWhenNoVectorStore() {
        TableSearchToolImpl noStoreTool = new TableSearchToolImpl(
                null, List.of(stubRetriever), null, null);

        Map<String, Object> result = noStoreTool.listTables(
                new TableSearchToolImpl.ListTablesInput(null, null));

        assertThat(result).containsKey("error");
    }

    @Test
    void searchTables_returnsErrorWhenNoRetriever() {
        TableSearchToolImpl noRetrieverTool = new TableSearchToolImpl(
                vectorStore, null, null, null);

        Map<String, Object> result = noRetrieverTool.searchTables(
                new TableSearchToolImpl.SearchTablesInput("test", null));

        assertThat(result).containsKey("error");
    }

    @Test
    void getTable_returnsErrorWhenNoVectorStore() {
        TableSearchToolImpl noStoreTool = new TableSearchToolImpl(
                null, List.of(stubRetriever), null, null);

        Map<String, Object> result = noStoreTool.getTable(
                new TableSearchToolImpl.GetTableInput("table-sales-001"));

        assertThat(result).containsKey("error");
    }

    @Test
    void searchTableEntries_returnsErrorWhenNoVectorStore() {
        TableSearchToolImpl noStoreTool = new TableSearchToolImpl(
                null, List.of(stubRetriever), null, null);

        Map<String, Object> result = noStoreTool.searchTableEntries(
                new TableSearchToolImpl.SearchTableEntriesInput("table-sales-001", "Widget"));

        assertThat(result).containsKey("error");
    }

    // ── Test data setup ──────────────────────────────────────────────────────

    /**
     * Indexes two table documents and one plain text document into the real
     * Anserini vector store. Uses {@link TableDocument#toSearchDocument()} which
     * stores full markdown content in metadata under "full_table_content" and sets
     * "content_type" to "table" — exactly as production code does.
     */
    private void indexTestDocuments() {
        TableDocument salesTable = TableDocument.builder()
                .id("table-sales-001")
                .markdownContent(SALES_TABLE_MD)
                .summary("Table with 3 rows and 4 columns. Columns: Product, Q1 Sales, Q2 Sales, Q3 Sales.")
                .tableMetadata(TableMetadata.builder()
                        .rowCount(3)
                        .columnCount(4)
                        .columnHeaders(List.of("Product", "Q1 Sales", "Q2 Sales", "Q3 Sales"))
                        .pageNumber(1)
                        .tableIndex(0)
                        .extractionMethod("test")
                        .build())
                .sourcePath("/docs/report.pdf")
                .sourceFilename("report.pdf")
                .build();

        TableDocument employeeTable = TableDocument.builder()
                .id("table-employee-002")
                .markdownContent(EMPLOYEE_TABLE_MD)
                .summary("Table with 3 rows and 3 columns. Columns: Name, Department, Role.")
                .tableMetadata(TableMetadata.builder()
                        .rowCount(3)
                        .columnCount(3)
                        .columnHeaders(List.of("Name", "Department", "Role"))
                        .pageNumber(2)
                        .tableIndex(0)
                        .extractionMethod("test")
                        .build())
                .sourcePath("/docs/org-chart.pdf")
                .sourceFilename("org-chart.pdf")
                .build();

        Document salesDoc = salesTable.toSearchDocument();
        Document employeeDoc = employeeTable.toSearchDocument();

        // Build documents with explicit IDs using the (id, text, metadata) constructor
        List<Document> docs = List.of(
                new Document("table-sales-001", salesDoc.getText(), new HashMap<>(salesDoc.getMetadata())),
                new Document("table-employee-002", employeeDoc.getText(), new HashMap<>(employeeDoc.getMetadata())),
                new Document("text-doc-001", "This is a plain text document about general information.",
                        new HashMap<>(Map.of("content_type", "text")))
        );

        // Pre-computed 8-dim embeddings (non-zero so Lucene doesn't reject them)
        List<List<Float>> embeddings = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            List<Float> emb = new ArrayList<>();
            for (int j = 0; j < 8; j++) {
                emb.add((float) (0.1 * (i + 1) + 0.01 * (j + 1)));
            }
            embeddings.add(emb);
        }

        int added = vectorStore.add(docs, embeddings);
        assertThat(added).isEqualTo(3);
    }

    // ── Stub EmbeddingModel ──────────────────────────────────────────────────

    /**
     * Produces deterministic 8-dimensional embeddings. No real model needed.
     */
    static class StubEmbeddingModel implements EmbeddingModel {

        @Override
        public INDArray embed(String text) {
            float[] values = new float[8];
            int hash = text.hashCode();
            for (int i = 0; i < 8; i++) {
                values[i] = (float) (0.1 + 0.01 * ((hash >> (i * 4)) & 0xF));
            }
            return Nd4j.createFromArray(values).reshape(1, 8);
        }

        @Override
        public INDArray embed(List<String> texts) {
            float[][] matrix = new float[texts.size()][8];
            for (int i = 0; i < texts.size(); i++) {
                INDArray row = embed(texts.get(i));
                matrix[i] = row.toFloatVector();
                row.close();
            }
            return Nd4j.createFromArray(matrix);
        }

        @Override
        public INDArray embedDocuments(List<Document> documents) {
            List<String> texts = new ArrayList<>();
            for (Document doc : documents) {
                texts.add(doc.getText() != null ? doc.getText() : "");
            }
            return embed(texts);
        }

        @Override
        public int dimensions() {
            return 8;
        }
    }

    // ── Stub DocumentRetriever ───────────────────────────────────────────────

    /**
     * Returns canned table and text documents for search queries.
     * Simulates what a real retriever backed by the vector store would return.
     */
    static class StubDocumentRetriever implements DocumentRetriever {

        @Override
        public List<String> retrieve(String query, int maxResults) {
            List<String> results = new ArrayList<>();
            for (RetrievedDoc doc : retrieveWithDetails(query, maxResults)) {
                results.add(doc.getText());
            }
            return results;
        }

        @Override
        public List<RetrievedDoc> retrieveWithDetails(String query, int maxResults) {
            List<RetrievedDoc> results = new ArrayList<>();

            Map<String, Object> salesMeta = new HashMap<>();
            salesMeta.put("content_type", "table");
            salesMeta.put("table_headers", "Product, Q1 Sales, Q2 Sales, Q3 Sales");
            salesMeta.put("table_row_count", 3);
            salesMeta.put("table_column_count", 4);
            salesMeta.put("table_page_number", 1);
            salesMeta.put("full_table_content", SALES_TABLE_MD);
            salesMeta.put("source_filename", "report.pdf");
            results.add(new RetrievedDoc("table-sales-001",
                    "Table with 3 rows and 4 columns. Columns: Product, Q1 Sales, Q2 Sales, Q3 Sales.",
                    salesMeta, 0.95));

            Map<String, Object> empMeta = new HashMap<>();
            empMeta.put("content_type", "table");
            empMeta.put("table_headers", "Name, Department, Role");
            empMeta.put("table_row_count", 3);
            empMeta.put("table_column_count", 3);
            empMeta.put("table_page_number", 2);
            empMeta.put("full_table_content", EMPLOYEE_TABLE_MD);
            empMeta.put("source_filename", "org-chart.pdf");
            results.add(new RetrievedDoc("table-employee-002",
                    "Table with 3 rows and 3 columns. Columns: Name, Department, Role.",
                    empMeta, 0.88));

            // Non-table doc — should be filtered out by searchTables
            Map<String, Object> textMeta = new HashMap<>();
            textMeta.put("content_type", "text");
            results.add(new RetrievedDoc("text-doc-001",
                    "This is a plain text document about general information.",
                    textMeta, 0.70));

            return results.subList(0, Math.min(results.size(), maxResults));
        }
    }
}
