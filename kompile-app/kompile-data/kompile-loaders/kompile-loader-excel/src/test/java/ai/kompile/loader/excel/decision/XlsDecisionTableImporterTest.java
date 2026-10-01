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

import ai.kompile.graph.reasoning.table.Table;
import ai.kompile.graph.reasoning.table.TableRow;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Covers the date-formatting behavior of {@link XlsDecisionTableImporter#cellToString(Cell)}
 * (exercised indirectly via {@link XlsDecisionTableImporter#fromWorkbook(org.apache.poi.ss.usermodel.Workbook, int)}),
 * which must render date-formatted numeric cells as time-zone-independent ISO-8601 text rather
 * than {@code Cell.getDateCellValue().toString()}'s host-time-zone-dependent output.
 */
class XlsDecisionTableImporterTest {

    @Test
    void dateFormattedCellImportsAsIsoDateAcrossTimeZones() throws Throwable {
        forEachTimeZone(() -> {
            try (XSSFWorkbook workbook = new XSSFWorkbook()) {
                Sheet sheet = workbook.createSheet("Rules");
                Row header = sheet.createRow(0);
                header.createCell(0).setCellValue("Customer");
                header.createCell(1).setCellValue("RenewalDate");

                CellStyle dateStyle = workbook.createCellStyle();
                dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));

                Row dataRow = sheet.createRow(1);
                dataRow.createCell(0).setCellValue("Acme Corp");
                Cell dateCell = dataRow.createCell(1);
                dateCell.setCellValue(LocalDate.of(2026, 9, 28));
                dateCell.setCellStyle(dateStyle);

                Table table = XlsDecisionTableImporter.fromWorkbook(workbook, 0);

                assertEquals(1, table.rows().size());
                TableRow row = table.rows().get(0);
                String renewalDate = String.valueOf(row.cells().get("RenewalDate"));
                assertEquals("2026-09-28", renewalDate);
                assertFalse(renewalDate.matches("(?s).*\\b(Mon|Tue|Wed|Thu|Fri|Sat|Sun)\\b.*"),
                        "Date should not render via Date.toString() weekday format");
            }
        });
    }

    @Test
    void dateTimeFormattedCellImportsAsIsoLocalDateTime() throws Throwable {
        forEachTimeZone(() -> {
            try (XSSFWorkbook workbook = new XSSFWorkbook()) {
                Sheet sheet = workbook.createSheet("Rules");
                Row header = sheet.createRow(0);
                header.createCell(0).setCellValue("Customer");
                header.createCell(1).setCellValue("LastContact");

                CellStyle dateTimeStyle = workbook.createCellStyle();
                dateTimeStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));

                Row dataRow = sheet.createRow(1);
                dataRow.createCell(0).setCellValue("Acme Corp");
                Cell dateTimeCell = dataRow.createCell(1);
                dateTimeCell.setCellValue(LocalDateTime.of(2026, 9, 28, 14, 30, 15));
                dateTimeCell.setCellStyle(dateTimeStyle);

                Table table = XlsDecisionTableImporter.fromWorkbook(workbook, 0);

                assertEquals(1, table.rows().size());
                TableRow row = table.rows().get(0);
                assertEquals("2026-09-28T14:30:15", String.valueOf(row.cells().get("LastContact")));
            }
        });
    }

    /** Runs {@code action} once per non-UTC zone, restoring the JVM default afterward. */
    private static void forEachTimeZone(Executable action) throws Throwable {
        TimeZone original = TimeZone.getDefault();
        try {
            for (String zone : new String[] {"Asia/Tokyo", "America/Los_Angeles"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                action.execute();
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
