/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.loader.excel.decision;

import ai.kompile.graph.reasoning.table.ColumnRole;
import ai.kompile.graph.reasoning.table.ColumnType;
import ai.kompile.graph.reasoning.table.Table;
import ai.kompile.graph.reasoning.table.TableColumn;
import ai.kompile.graph.reasoning.table.TableKind;
import ai.kompile.graph.reasoning.table.TableRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests: Table → XLSX → Table.
 *
 * Verifies that column names, roles, and cell values survive the full
 * XlsDecisionTableExporter → XlsDecisionTableImporter pipeline.
 */
class XlsDecisionTableRoundTripTest {

    @TempDir
    Path tempDir;

    // ── Fixtures ───────────────────────────────────────────────────────────────

    private static List<TableColumn> discountColumns() {
        return List.of(
                new TableColumn("CustomerTier", ColumnRole.CONDITION,  ColumnType.STRING, "Customer tier"),
                new TableColumn("OrderAmount",  ColumnRole.CONDITION,  ColumnType.DOUBLE, "Order amount"),
                new TableColumn("Discount",     ColumnRole.CONCLUSION, ColumnType.DOUBLE, "Discount %"),
                new TableColumn("Notes",        ColumnRole.FIELD,      ColumnType.STRING, "Free-form notes")
        );
    }

    private static List<TableRow> discountRows() {
        return List.of(
                row("Gold",   "1000", "15", "VIP tier"),
                row("Silver", "500",  "10", "Mid tier"),
                row("Bronze", "100",  "5",  "Basic tier"),
                row("",       "0",    "0",  "No discount")
        );
    }

    private static TableRow row(String tier, String amount, String discount, String notes) {
        Map<String, Object> cells = new LinkedHashMap<>();
        cells.put("CustomerTier", tier);
        cells.put("OrderAmount",  amount);
        cells.put("Discount",     discount);
        cells.put("Notes",        notes);
        return new TableRow(java.util.UUID.randomUUID().toString(), cells);
    }

    // ── Tests ──────────────────────────────────────────────────────────────────

    @Test
    void roundTrip_columnNamesPreserved() throws Exception {
        Table original = new Table(null, "DiscountRules", TableKind.DECISION,
                discountColumns(), discountRows(), Map.of(), null);

        Table restored = roundTrip(original);

        List<String> expectedNames = List.of("CustomerTier", "OrderAmount", "Discount", "Notes");
        List<String> actualNames = restored.columns().stream()
                .map(TableColumn::name).toList();
        assertEquals(expectedNames, actualNames, "Column names must survive round-trip");
    }

    @Test
    void roundTrip_columnRolesPreserved() throws Exception {
        Table original = new Table(null, "DiscountRules", TableKind.DECISION,
                discountColumns(), discountRows(), Map.of(), null);

        Table restored = roundTrip(original);

        List<ColumnRole> expectedRoles = List.of(
                ColumnRole.CONDITION, ColumnRole.CONDITION, ColumnRole.CONCLUSION, ColumnRole.FIELD);
        List<ColumnRole> actualRoles = restored.columns().stream()
                .map(TableColumn::role).toList();
        assertEquals(expectedRoles, actualRoles, "Column roles must survive round-trip");
    }

    @Test
    void roundTrip_rowCountPreserved() throws Exception {
        Table original = new Table(null, "DiscountRules", TableKind.DECISION,
                discountColumns(), discountRows(), Map.of(), null);

        Table restored = roundTrip(original);

        assertEquals(original.rows().size(), restored.rows().size(),
                "Row count must match after round-trip");
    }

    @Test
    void roundTrip_cellValuesPreserved() throws Exception {
        Table original = new Table(null, "DiscountRules", TableKind.DECISION,
                discountColumns(), discountRows(), Map.of(), null);

        Table restored = roundTrip(original);

        // Check first data row (Gold / 1000 / 15 / VIP tier)
        Map<String, Object> firstCells = restored.rows().get(0).cells();
        assertEquals("Gold",    String.valueOf(firstCells.get("CustomerTier")));
        assertEquals("1000",    String.valueOf(firstCells.get("OrderAmount")));
        assertEquals("15",      String.valueOf(firstCells.get("Discount")));
        assertEquals("VIP tier",String.valueOf(firstCells.get("Notes")));

        // Check last data row (empty tier)
        Map<String, Object> lastCells = restored.rows().get(3).cells();
        // Empty string cell should come back as empty
        String tierVal = String.valueOf(lastCells.get("CustomerTier")).trim();
        assertTrue(tierVal.isEmpty() || tierVal.equals("null"),
                "Empty condition cell should be blank after round-trip");
    }

    @Test
    void roundTrip_tableKindIsDecision() throws Exception {
        Table original = new Table(null, "Rules", TableKind.DECISION,
                discountColumns(), discountRows(), Map.of(), null);

        Table restored = roundTrip(original);
        assertEquals(TableKind.DECISION, restored.kind());
    }

    @Test
    void roundTrip_emptyTable() throws Exception {
        Table original = new Table(null, "Empty", TableKind.DECISION,
                discountColumns(), List.of(), Map.of(), null);

        Table restored = roundTrip(original);

        assertEquals(0, restored.rows().size(), "Empty table must round-trip with 0 rows");
        assertEquals(4, restored.columns().size(), "Column count must be preserved for empty table");
    }

    @Test
    void roundTrip_viaFile() throws Exception {
        Table original = new Table(null, "DiscountRules", TableKind.DECISION,
                discountColumns(), discountRows(), Map.of(), null);

        File file = tempDir.resolve("decision.xlsx").toFile();
        try (FileOutputStream fos = new FileOutputStream(file)) {
            XlsDecisionTableExporter.toXlsx(original, fos);
        }
        assertTrue(file.exists() && file.length() > 0, "XLSX file must be written");

        Table restored = XlsDecisionTableImporter.fromFile(file);

        assertEquals(original.columns().size(), restored.columns().size());
        assertEquals(original.rows().size(), restored.rows().size());
        assertEquals("CustomerTier", restored.columns().get(0).name());
        assertEquals(ColumnRole.CONDITION, restored.columns().get(0).role());
        assertEquals(ColumnRole.CONCLUSION, restored.columns().get(2).role());
    }

    @Test
    void roundTrip_dataTable_noRoleRow() throws Exception {
        // DATA table: exporter does NOT write a role row; importer must handle columns
        List<TableColumn> cols = List.of(
                new TableColumn("Date",    ColumnRole.FIELD, ColumnType.STRING, ""),
                new TableColumn("Revenue", ColumnRole.FIELD, ColumnType.DOUBLE, ""),
                new TableColumn("Costs",   ColumnRole.FIELD, ColumnType.DOUBLE, "")
        );
        List<TableRow> rows = List.of(
                dataRow("2025-01-01", "100000", "60000"),
                dataRow("2025-02-01", "120000", "65000")
        );
        Table original = new Table(null, "Financials", TableKind.DATA, cols, rows, Map.of(), null);

        Table restored = roundTrip(original);

        // All columns should come back as FIELD (no role row written for DATA tables)
        restored.columns().forEach(c ->
                assertEquals(ColumnRole.FIELD, c.role(), "DATA table columns must default to FIELD"));
        assertEquals(2, restored.rows().size());
        assertEquals("Date",    restored.columns().get(0).name());
        assertEquals("Revenue", restored.columns().get(1).name());
    }

    @Test
    void columnsOfRole_afterRoundTrip() throws Exception {
        Table original = new Table(null, "Rules", TableKind.DECISION,
                discountColumns(), discountRows(), Map.of(), null);

        Table restored = roundTrip(original);

        assertEquals(2, restored.columnsOfRole(ColumnRole.CONDITION).size());
        assertEquals(1, restored.columnsOfRole(ColumnRole.CONCLUSION).size());
        assertEquals(1, restored.columnsOfRole(ColumnRole.FIELD).size());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Perform the round-trip via ByteArrayOutputStream (no temp file required). */
    private static Table roundTrip(Table original) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XlsDecisionTableExporter.toXlsx(original, out);

        byte[] bytes = out.toByteArray();
        assertTrue(bytes.length > 0, "Exported XLSX must not be empty");

        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            return XlsDecisionTableImporter.fromStream(in, true);
        }
    }

    private static TableRow dataRow(String date, String rev, String costs) {
        Map<String, Object> cells = new LinkedHashMap<>();
        cells.put("Date",    date);
        cells.put("Revenue", rev);
        cells.put("Costs",   costs);
        return new TableRow(java.util.UUID.randomUUID().toString(), cells);
    }
}
