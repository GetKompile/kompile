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
package ai.kompile.loader.microsoft;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Tests {@link ExcelCellDates#format(Cell)}, in particular the guarantee that motivated it:
 * the rendered value must depend only on the workbook's stored date value, never on the host
 * JVM's default time zone (unlike {@code Cell#getDateCellValue().toString()}, which the
 * production code used before this fix).
 */
class ExcelCellDatesTest {

    private XSSFWorkbook workbook;
    private Sheet sheet;
    private TimeZone originalDefaultTimeZone;

    @BeforeEach
    void setUp() {
        workbook = new XSSFWorkbook();
        sheet = workbook.createSheet();
        originalDefaultTimeZone = TimeZone.getDefault();
    }

    @AfterEach
    void tearDown() throws Exception {
        // TimeZone.setDefault mutates global JVM state - restore it so other tests (which may
        // run in the same fork) are never affected by a test in this class.
        TimeZone.setDefault(originalDefaultTimeZone);
        workbook.close();
    }

    private Cell dateCell(LocalDateTime value) {
        Row row = sheet.createRow(0);
        Cell cell = row.createCell(0);
        cell.setCellValue(value);
        return cell;
    }

    /**
     * Builds a cell holding a raw Excel serial value below 1 (time-of-day only, no date
     * component) - the way a real spreadsheet stores a time-only cell. This deliberately
     * bypasses {@link Cell#setCellValue(LocalDateTime)}: its underlying
     * {@code DateUtil.getExcelDate(LocalDateTime, boolean)} returns {@code BAD_DATE} (-1.0) for
     * any {@link LocalDateTime} with a year before 1900, even though year 1899 is exactly how
     * POI's own read path ({@code DateUtil.getLocalDateTime}) represents a serial value between
     * 0 and 1. Setting the raw serial double instead sidesteps that write-side restriction.
     */
    private Cell timeOnlyCell(LocalTime time) {
        Row row = sheet.createRow(0);
        Cell cell = row.createCell(0);
        cell.setCellValue(time.toSecondOfDay() / 86400.0);
        return cell;
    }

    @Test
    void fullDateTime_formatsAsIsoLocalDateTime() {
        Cell cell = dateCell(LocalDateTime.of(2026, 9, 28, 14, 30, 0));
        assertEquals("2026-09-28T14:30", ExcelCellDates.format(cell));
    }

    @Test
    void midnightTimeComponent_formatsAsDateOnly() {
        Cell cell = dateCell(LocalDateTime.of(2026, 9, 28, 0, 0, 0));
        assertEquals("2026-09-28", ExcelCellDates.format(cell));
    }

    @Test
    void valueBelowOne_formatsAsTimeOnly() {
        // An Excel serial value < 1 has no date component at all (time-of-day only).
        Cell cell = timeOnlyCell(LocalTime.of(9, 15, 0));
        String formatted = ExcelCellDates.format(cell);
        assertEquals("09:15", formatted, "expected time-only formatting, got: " + formatted);
    }

    @Test
    void valueBelowOneWithSeconds_includesSeconds() {
        Cell cell = timeOnlyCell(LocalTime.of(9, 15, 45));
        assertEquals("09:15:45", ExcelCellDates.format(cell));
    }

    @Test
    void roundsUpWhenFractionalSecondAtOrAboveHalf() {
        Cell cell = dateCell(LocalDateTime.of(2026, 9, 28, 14, 30, 15, 600_000_000));
        assertEquals("2026-09-28T14:30:16", ExcelCellDates.format(cell));
    }

    @Test
    void roundsDownWhenFractionalSecondBelowHalf() {
        Cell cell = dateCell(LocalDateTime.of(2026, 9, 28, 14, 30, 15, 200_000_000));
        assertEquals("2026-09-28T14:30:15", ExcelCellDates.format(cell));
    }

    @Test
    void formattedValue_isIndependentOfHostDefaultTimeZone() {
        LocalDateTime value = LocalDateTime.of(2026, 3, 15, 23, 45, 30);

        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
        Cell tokyoCell = dateCell(value);
        String tokyoFormatted = ExcelCellDates.format(tokyoCell);

        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
        Cell laCell = dateCell(value);
        String laFormatted = ExcelCellDates.format(laCell);

        assertEquals(tokyoFormatted, laFormatted,
                "the formatted date/time must not depend on the host JVM's default time zone");
        assertEquals("2026-03-15T23:45:30", tokyoFormatted);
    }

    @Test
    void formattedValue_neverContainsTimeZoneAbbreviation() {
        // A regression guard against reintroducing Date#toString(), whose output like
        // "Mon Sep 28 00:00:00 JST 2026" embeds a time zone abbreviation.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
        Cell cell = dateCell(LocalDateTime.of(2026, 9, 28, 0, 0, 0));
        String formatted = ExcelCellDates.format(cell);
        assertFalse(formatted.contains("JST") || formatted.contains("GMT") || formatted.contains("UTC"),
                "got: " + formatted);
    }
}
