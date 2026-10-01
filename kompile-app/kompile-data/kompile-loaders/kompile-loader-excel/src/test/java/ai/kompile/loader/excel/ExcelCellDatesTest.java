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
package ai.kompile.loader.excel;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@link ExcelCellDates#format(Cell)} renders time-zone-independent ISO-8601 text
 * instead of {@code Cell.getDateCellValue().toString()}'s host-time-zone-dependent output.
 * Every case runs under two non-UTC default time zones and must produce identical output.
 */
class ExcelCellDatesTest {

    private static final String[] NON_UTC_ZONES = {"Asia/Tokyo", "America/Los_Angeles"};

    @Test
    void formatsDateOnlyValueAsIsoDate() throws Throwable {
        forEachTimeZone(() -> {
            try (XSSFWorkbook workbook = new XSSFWorkbook()) {
                Cell cell = dateCell(workbook, "yyyy-mm-dd");
                cell.setCellValue(LocalDate.of(2026, 9, 28));
                assertEquals("2026-09-28", ExcelCellDates.format(cell));
            }
        });
    }

    @Test
    void formatsDateTimeValueAsIsoLocalDateTime() throws Throwable {
        forEachTimeZone(() -> {
            try (XSSFWorkbook workbook = new XSSFWorkbook()) {
                Cell cell = dateCell(workbook, "yyyy-mm-dd hh:mm:ss");
                cell.setCellValue(LocalDateTime.of(2026, 9, 28, 14, 30, 15));
                assertEquals("2026-09-28T14:30:15", ExcelCellDates.format(cell));
            }
        });
    }

    @Test
    void formatsDateTimeWithZeroSecondsWithoutSecondsField() throws Throwable {
        forEachTimeZone(() -> {
            try (XSSFWorkbook workbook = new XSSFWorkbook()) {
                Cell cell = dateCell(workbook, "yyyy-mm-dd hh:mm");
                cell.setCellValue(LocalDateTime.of(2026, 9, 28, 14, 30, 0));
                assertEquals("2026-09-28T14:30", ExcelCellDates.format(cell));
            }
        });
    }

    @Test
    void formatsTimeOnlyValueAsIsoTime() throws Throwable {
        forEachTimeZone(() -> {
            try (XSSFWorkbook workbook = new XSSFWorkbook()) {
                Cell cell = dateCell(workbook, "hh:mm:ss");
                cell.setCellValue(DateUtil.convertTime("14:30:15"));
                assertEquals("14:30:15", ExcelCellDates.format(cell));
            }
        });
    }

    @Test
    void formatsTimeOnlyWithZeroSecondsAsHHmm() throws Throwable {
        forEachTimeZone(() -> {
            try (XSSFWorkbook workbook = new XSSFWorkbook()) {
                Cell cell = dateCell(workbook, "hh:mm");
                cell.setCellValue(DateUtil.convertTime("14:30:00"));
                assertEquals("14:30", ExcelCellDates.format(cell));
            }
        });
    }

    @Test
    void roundsSubSecondJitterToNearestSecond() throws Throwable {
        forEachTimeZone(() -> {
            double base = DateUtil.getExcelDate(LocalDateTime.of(2026, 9, 28, 14, 30, 15));
            try (XSSFWorkbook workbook = new XSSFWorkbook()) {
                // 14:30:15.200 (just past the second) must truncate down to :15
                Cell down = dateCell(workbook, "yyyy-mm-dd hh:mm:ss");
                down.setCellValue(base + (0.2 / 86400.0));
                assertEquals("2026-09-28T14:30:15", ExcelCellDates.format(down));

                // 14:30:14.700 (just before the next second) must round up to :15
                Cell up = dateCell(workbook, "yyyy-mm-dd hh:mm:ss");
                up.setCellValue(base - (0.3 / 86400.0));
                assertEquals("2026-09-28T14:30:15", ExcelCellDates.format(up));
            }
        });
    }

    @Test
    void respects1904DateSystem() throws Throwable {
        forEachTimeZone(() -> {
            try (XSSFWorkbook standard = new XSSFWorkbook()) {
                Cell cell = dateCell(standard, "yyyy-mm-dd");
                cell.setCellValue(1.0d);
                assertEquals("1900-01-01", ExcelCellDates.format(cell));
            }
            try (XSSFWorkbook workbook1904 = new XSSFWorkbook()) {
                var workbookPr = workbook1904.getCTWorkbook().isSetWorkbookPr()
                        ? workbook1904.getCTWorkbook().getWorkbookPr()
                        : workbook1904.getCTWorkbook().addNewWorkbookPr();
                workbookPr.setDate1904(true);

                Cell cell = dateCell(workbook1904, "yyyy-mm-dd");
                cell.setCellValue(1.0d);
                assertEquals("1904-01-02", ExcelCellDates.format(cell));
            }
        });
    }

    @Test
    void formatsDateOnlyValueForHssfWorkbook() throws Throwable {
        forEachTimeZone(() -> {
            try (HSSFWorkbook workbook = new HSSFWorkbook()) {
                Cell cell = dateCell(workbook, "yyyy-mm-dd");
                cell.setCellValue(LocalDate.of(2026, 9, 28));
                assertEquals("2026-09-28", ExcelCellDates.format(cell));
            }
        });
    }

    @Test
    void formatsDateTimeValueForHssfWorkbook() throws Throwable {
        forEachTimeZone(() -> {
            try (HSSFWorkbook workbook = new HSSFWorkbook()) {
                Cell cell = dateCell(workbook, "yyyy-mm-dd hh:mm:ss");
                cell.setCellValue(LocalDateTime.of(2026, 9, 28, 14, 30, 15));
                assertEquals("2026-09-28T14:30:15", ExcelCellDates.format(cell));
            }
        });
    }

    @Test
    void formatsTimeOnlyValueForHssfWorkbook() throws Throwable {
        forEachTimeZone(() -> {
            try (HSSFWorkbook workbook = new HSSFWorkbook()) {
                Cell cell = dateCell(workbook, "hh:mm:ss");
                cell.setCellValue(DateUtil.convertTime("14:30:15"));
                assertEquals("14:30:15", ExcelCellDates.format(cell));
            }
        });
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static Cell dateCell(Workbook workbook, String format) {
        Sheet sheet = workbook.createSheet();
        Row row = sheet.createRow(0);
        Cell cell = row.createCell(0);
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat(format));
        cell.setCellStyle(style);
        return cell;
    }

    /** Runs {@code action} once per non-UTC zone in {@link #NON_UTC_ZONES}, restoring the default afterward. */
    private static void forEachTimeZone(Executable action) throws Throwable {
        TimeZone original = TimeZone.getDefault();
        try {
            for (String zone : NON_UTC_ZONES) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                action.execute();
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
