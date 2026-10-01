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

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

/**
 * Formats Excel date/time cell values as time-zone-independent ISO-8601 text.
 *
 * <p>{@link Cell#getDateCellValue()} returns a {@code java.util.Date}, whose
 * {@code toString()} renders in the <em>host JVM's default time zone</em> (e.g.
 * "Mon Sep 28 00:00:00 JST 2026"). That makes the displayed value depend on where the
 * process happens to run rather than on the workbook's actual content, and it is wrong for
 * every caller in this module that shows a date to a user or bakes one into extracted document
 * text. This helper reads the cell's local date/time via {@link Cell#getLocalDateTimeCellValue()}
 * instead, which resolves the workbook's 1900/1904 date-windowing system internally and never
 * touches the JVM default time zone, then renders it as plain ISO-8601.
 *
 * <p>Used by every site in this module that displays a date-formatted numeric cell:
 * {@code MicrosoftOfficeLoaderImpl#getCellValueAsString} and
 * {@code StreamingOfficeLoaderImpl#getCellValueAsString}.
 *
 * <p>Formatting rules:
 * <ul>
 *   <li>Value &lt; 1 (time-of-day only, no date component) &rarr; {@code HH:mm}, or
 *       {@code HH:mm:ss} when the seconds component is non-zero.</li>
 *   <li>Time component is exactly midnight &rarr; ISO local date, {@code yyyy-MM-dd}.</li>
 *   <li>Otherwise &rarr; ISO local date-time via {@link LocalDateTime#toString()}, e.g.
 *       {@code 2026-09-28T14:30} or {@code 2026-09-28T14:30:15}.</li>
 * </ul>
 * In all cases the value is first rounded to the nearest second, since POI's own conversion
 * only rounds to the nearest millisecond.
 */
final class ExcelCellDates {

    private ExcelCellDates() {}

    /**
     * Formats a date-formatted numeric cell's value as time-zone-independent ISO-8601 text.
     * Callers must only invoke this after confirming the cell holds a date/time value (e.g.
     * via {@link org.apache.poi.ss.usermodel.DateUtil#isCellDateFormatted(Cell)}).
     */
    static String format(Cell cell) {
        LocalDateTime rounded = roundToNearestSecond(cell.getLocalDateTimeCellValue());
        double excelSerialValue = cell.getNumericCellValue();

        if (excelSerialValue < 1) {
            return rounded.toLocalTime().toString();
        }
        if (rounded.toLocalTime().equals(LocalTime.MIDNIGHT)) {
            return rounded.toLocalDate().toString();
        }
        return rounded.toString();
    }

    /** POI's cell-to-LocalDateTime conversion only rounds to the nearest millisecond. */
    private static LocalDateTime roundToNearestSecond(LocalDateTime dateTime) {
        LocalDateTime truncated = dateTime.truncatedTo(ChronoUnit.SECONDS);
        return dateTime.getNano() >= 500_000_000 ? truncated.plusSeconds(1) : truncated;
    }
}
