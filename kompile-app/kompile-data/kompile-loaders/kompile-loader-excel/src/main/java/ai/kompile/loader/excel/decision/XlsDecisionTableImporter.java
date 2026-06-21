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
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Imports XLS / XLSX decision tables into the lib's {@link Table} (kind = DECISION).
 *
 * <p>Expected workbook layout (all rows, 0-indexed):
 * <pre>
 *   Row 0 — column headers      (e.g. "Customer Tier", "Amount", "Discount")
 *   Row 1 — role tags (optional) C=CONDITION / A=CONCLUSION / F=FIELD / blank=FIELD
 *   Row 2+ — data rows
 * </pre>
 *
 * <p>If row 1 contains only the role tags {C, A, F, ""} the importer treats it as a role
 * row and reads data from row 2 onward, exactly mirroring the CSV convention in
 * {@link ai.kompile.graph.reasoning.table.io.DroolsFormatImporter#fromCsv(String)}.
 * When no role row is present all columns default to {@link ColumnRole#FIELD}.
 *
 * <p>Only the <em>first sheet</em> of the workbook is read by default; use
 * {@link #fromWorkbook(Workbook, int)} to pick a specific sheet index.
 *
 * <p>This class belongs in a client module (kompile-loader-excel) because Apache POI is
 * not a dependency of the infra-free kompile-graph-reasoning lib — see
 * {@link ai.kompile.graph.reasoning.table.io.DroolsFormatImporter} TODO comment.
 */
public final class XlsDecisionTableImporter {

    private XlsDecisionTableImporter() {}

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Read the first sheet of an XLS or XLSX file. */
    public static Table fromFile(File file) throws IOException {
        try (Workbook wb = WorkbookFactory.create(file)) {
            return fromWorkbook(wb, 0);
        }
    }

    /** Read the first sheet from an InputStream (caller owns closing the stream). */
    public static Table fromStream(InputStream in, boolean isXlsx) throws IOException {
        try (Workbook wb = WorkbookFactory.create(in)) {
            return fromWorkbook(wb, 0);
        }
    }

    /**
     * Read a specific sheet (0-based index) from an already-opened {@link Workbook}.
     * Useful when the caller already has the workbook open (e.g. ExcelLoaderImpl).
     */
    public static Table fromWorkbook(Workbook wb, int sheetIndex) {
        if (wb.getNumberOfSheets() == 0) {
            return emptyTable("empty");
        }
        int idx = Math.min(sheetIndex, wb.getNumberOfSheets() - 1);
        Sheet sheet = wb.getSheetAt(idx);
        return fromSheet(sheet);
    }

    /** Parse a single POI {@link Sheet}. */
    public static Table fromSheet(Sheet sheet) {
        if (sheet == null) {
            return emptyTable("null");
        }

        // Collect all non-empty rows into List<String[]>
        List<String[]> raw = new ArrayList<>();
        int maxCol = 0;

        for (Row row : sheet) {
            int lastCell = row.getLastCellNum(); // exclusive
            if (lastCell > maxCol) maxCol = lastCell;
            String[] cells = new String[Math.max(lastCell, 0)];
            for (int c = 0; c < cells.length; c++) {
                cells[c] = cellToString(row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL));
            }
            raw.add(cells);
        }

        if (raw.isEmpty()) {
            return emptyTable(sheet.getSheetName());
        }

        String[] headers = raw.get(0);
        int dataStart = 1;
        String[] roleRow = null;

        // Detect optional role row (row index 1) — same heuristic as DroolsFormatImporter
        if (raw.size() > 1 && isRoleRow(raw.get(1))) {
            roleRow = raw.get(1);
            dataStart = 2;
        }

        // Build columns
        List<TableColumn> columns = new ArrayList<>();
        for (int i = 0; i < headers.length; i++) {
            String header = headers[i] == null ? "" : headers[i].trim();
            if (header.isEmpty()) header = "col_" + i;
            ColumnRole role = ColumnRole.FIELD;
            if (roleRow != null && i < roleRow.length) {
                role = parseRole(roleRow[i]);
            }
            columns.add(new TableColumn(header, role, ColumnType.STRING, ""));
        }

        // Build data rows
        List<TableRow> rows = new ArrayList<>();
        for (int r = dataStart; r < raw.size(); r++) {
            String[] cells = raw.get(r);
            Map<String, Object> cellMap = new LinkedHashMap<>();
            boolean hasData = false;
            for (int c = 0; c < columns.size(); c++) {
                String val = c < cells.length ? (cells[c] == null ? "" : cells[c].trim()) : "";
                cellMap.put(columns.get(c).name(), val);
                if (!val.isEmpty()) hasData = true;
            }
            if (hasData) {
                rows.add(new TableRow(UUID.randomUUID().toString(), cellMap));
            }
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("sourceSheet", sheet.getSheetName());
        meta.put("format", "xls");

        return new Table(null, sheet.getSheetName(), TableKind.DECISION, columns, rows, meta, null);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static String cellToString(Cell cell) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                }
                double v = cell.getNumericCellValue();
                // Trim ".0" for integers so round-trip comparisons stay clean
                if (v == Math.floor(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15) {
                    return String.valueOf((long) v);
                }
                return String.valueOf(v);
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                // Best-effort: return formula string
                return cell.getCellFormula();
            case BLANK:
            default:
                return "";
        }
    }

    /**
     * A row is a role row if every non-empty cell is one of: C / A / F / CONDITION /
     * CONCLUSION / FIELD (case-insensitive). At least one cell must be non-empty.
     */
    private static boolean isRoleRow(String[] row) {
        boolean anyNonEmpty = false;
        for (String cell : row) {
            String t = cell == null ? "" : cell.trim().toUpperCase();
            if (t.isEmpty()) continue;
            anyNonEmpty = true;
            if (!t.equals("C") && !t.equals("A") && !t.equals("F")
                    && !t.equals("CONDITION") && !t.equals("CONCLUSION") && !t.equals("FIELD")) {
                return false;
            }
        }
        return anyNonEmpty;
    }

    private static ColumnRole parseRole(String tag) {
        if (tag == null) return ColumnRole.FIELD;
        switch (tag.trim().toUpperCase()) {
            case "C":
            case "CONDITION":
                return ColumnRole.CONDITION;
            case "A":
            case "CONCLUSION":
                return ColumnRole.CONCLUSION;
            default:
                return ColumnRole.FIELD;
        }
    }

    private static Table emptyTable(String name) {
        return new Table(null, name, TableKind.DECISION, List.of(), List.of(), Map.of(), null);
    }
}
