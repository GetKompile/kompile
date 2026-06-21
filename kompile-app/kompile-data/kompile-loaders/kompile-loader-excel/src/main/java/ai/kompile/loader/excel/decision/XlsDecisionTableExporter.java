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
import ai.kompile.graph.reasoning.table.Table;
import ai.kompile.graph.reasoning.table.TableColumn;
import ai.kompile.graph.reasoning.table.TableRow;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * Exports a {@link Table} (any {@link ai.kompile.graph.reasoning.table.TableKind}) to XLSX.
 *
 * <p>Layout mirrors the convention expected by {@link XlsDecisionTableImporter}:
 * <pre>
 *   Row 0 — header row  (bold, light-grey background)
 *   Row 1 — role row    (C / A / F) — written for DECISION tables; omitted otherwise
 *   Row 2+ — data rows
 * </pre>
 *
 * <p>CONDITION columns get a light-blue header tint; CONCLUSION columns get light-green;
 * FIELD / ANNOTATION columns are plain. This matches the Drools XLS decision-table style
 * convention so the file is immediately recognisable to human readers.
 *
 * <p>The output format is always XLSX (Open XML). For legacy .xls use
 * {@link org.apache.poi.hssf.usermodel.HSSFWorkbook} instead — this exporter targets the
 * modern format because HSSF has a hard limit of 65 536 rows and the newer API is cleaner.
 *
 * <p>This class lives in a client module (kompile-loader-excel) because Apache POI is not a
 * dependency of the infra-free kompile-graph-reasoning lib.
 */
public final class XlsDecisionTableExporter {

    /** Sheet name used when the table name is blank. */
    private static final String DEFAULT_SHEET_NAME = "DecisionTable";

    private XlsDecisionTableExporter() {}

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Write a single {@link Table} as XLSX to the given {@link OutputStream}.
     * The caller is responsible for closing the stream.
     */
    public static void toXlsx(Table table, OutputStream out) throws IOException {
        try (Workbook wb = buildWorkbook(table)) {
            wb.write(out);
        }
    }

    /**
     * Serialise a {@link Table} to a new {@link XSSFWorkbook} and return it.
     * Useful when the caller wants to add additional sheets before writing.
     * The caller is responsible for closing the workbook.
     */
    public static XSSFWorkbook toWorkbook(Table table) {
        return (XSSFWorkbook) buildWorkbook(table);
    }

    // ── Build ──────────────────────────────────────────────────────────────────

    private static Workbook buildWorkbook(Table table) {
        XSSFWorkbook wb = new XSSFWorkbook();
        String sheetName = sanitiseSheetName(table.name());
        Sheet sheet = wb.createSheet(sheetName);

        List<TableColumn> columns = table.columns();
        boolean isDecision = switch (table.kind()) {
            case DECISION -> true;
            default -> false;
        };

        // ── Styles ──────────────────────────────────────────────────────────
        CellStyle headerStyle = headerStyle(wb, IndexedColors.GREY_25_PERCENT);
        CellStyle condStyle   = headerStyle(wb, IndexedColors.LIGHT_CORNFLOWER_BLUE);
        CellStyle concStyle   = headerStyle(wb, IndexedColors.LIGHT_GREEN);
        CellStyle roleStyle   = roleStyle(wb);

        // ── Row 0: headers ──────────────────────────────────────────────────
        Row headerRow = sheet.createRow(0);
        for (int c = 0; c < columns.size(); c++) {
            TableColumn col = columns.get(c);
            Cell cell = headerRow.createCell(c);
            cell.setCellValue(col.name());
            // Pick tint based on role (only meaningful for DECISION tables)
            if (isDecision) {
                cell.setCellStyle(switch (col.role()) {
                    case CONDITION  -> condStyle;
                    case CONCLUSION -> concStyle;
                    default         -> headerStyle;
                });
            } else {
                cell.setCellStyle(headerStyle);
            }
        }

        // ── Row 1: role tags (DECISION tables only) ─────────────────────────
        int dataStartRow = 1;
        if (isDecision) {
            Row roleRow = sheet.createRow(1);
            for (int c = 0; c < columns.size(); c++) {
                Cell cell = roleRow.createCell(c);
                cell.setCellStyle(roleStyle);
                cell.setCellValue(roleTag(columns.get(c).role()));
            }
            dataStartRow = 2;
        }

        // ── Data rows ────────────────────────────────────────────────────────
        int rowNum = dataStartRow;
        for (TableRow dataRow : table.rows()) {
            Row xlsRow = sheet.createRow(rowNum++);
            for (int c = 0; c < columns.size(); c++) {
                Object val = dataRow.cells().get(columns.get(c).name());
                Cell cell = xlsRow.createCell(c);
                setCell(cell, val);
            }
        }

        // Auto-size columns (up to 50 columns to avoid O(n·m) cost on huge tables)
        int colsToResize = Math.min(columns.size(), 50);
        for (int c = 0; c < colsToResize; c++) {
            sheet.autoSizeColumn(c);
            // Cap at ~50 characters width to avoid unusably wide columns
            int maxWidth = 50 * 256;
            if (sheet.getColumnWidth(c) > maxWidth) {
                sheet.setColumnWidth(c, maxWidth);
            }
        }

        return wb;
    }

    // ── Cell helpers ──────────────────────────────────────────────────────────

    private static void setCell(Cell cell, Object value) {
        if (value == null) {
            cell.setCellValue("");
            return;
        }
        String s = value.toString().trim();
        // Attempt numeric coercion for clean round-trips
        if (!s.isEmpty()) {
            try {
                cell.setCellValue(Double.parseDouble(s));
                return;
            } catch (NumberFormatException ignored) { /* fall through */ }
            if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")) {
                cell.setCellValue(Boolean.parseBoolean(s));
                return;
            }
        }
        cell.setCellValue(s);
    }

    // ── Style helpers ─────────────────────────────────────────────────────────

    private static CellStyle headerStyle(Workbook wb, IndexedColors bg) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(bg.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private static CellStyle roleStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setItalic(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.LEMON_CHIFFON.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    // ── Misc helpers ──────────────────────────────────────────────────────────

    private static String roleTag(ColumnRole role) {
        return switch (role) {
            case CONDITION  -> "C";
            case CONCLUSION -> "A";
            default         -> "F";
        };
    }

    /**
     * Sheet names must be 1-31 chars and must not contain: \ / ? * [ ] : (XLSX spec).
     */
    private static String sanitiseSheetName(String name) {
        if (name == null || name.isBlank()) return DEFAULT_SHEET_NAME;
        String sanitised = name.replaceAll("[\\\\/?*\\[\\]:]", "_");
        if (sanitised.length() > 31) sanitised = sanitised.substring(0, 31);
        return sanitised.isBlank() ? DEFAULT_SHEET_NAME : sanitised;
    }
}
