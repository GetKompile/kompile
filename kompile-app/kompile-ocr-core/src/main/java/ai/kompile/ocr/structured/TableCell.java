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

package ai.kompile.ocr.structured;

import ai.kompile.ocr.BoundingBox;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

/**
 * Represents a cell in a structured table with its content and position.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TableCell {

    /**
     * Row index (0-indexed).
     */
    private int row;

    /**
     * Column index (0-indexed).
     */
    private int column;

    /**
     * Number of rows this cell spans.
     */
    @Builder.Default
    private int rowSpan = 1;

    /**
     * Number of columns this cell spans.
     */
    @Builder.Default
    private int colSpan = 1;

    /**
     * Text content of the cell.
     */
    private String text;

    /**
     * Bounding box for the cell.
     */
    private BoundingBox boundingBox;

    /**
     * OCR confidence score for the cell content.
     */
    @Builder.Default
    private double confidence = 1.0;

    /**
     * Whether this cell is a header cell.
     */
    @Builder.Default
    private boolean header = false;

    /**
     * Cell type (text, number, date, etc).
     */
    private CellType cellType;

    /**
     * Types of cell content.
     */
    public enum CellType {
        TEXT,
        NUMBER,
        CURRENCY,
        DATE,
        PERCENTAGE,
        EMPTY
    }

    /**
     * Creates a simple text cell.
     */
    public static TableCell of(int row, int column, String text) {
        return TableCell.builder()
                .row(row)
                .column(column)
                .text(text)
                .cellType(inferCellType(text))
                .build();
    }

    /**
     * Creates a header cell.
     */
    public static TableCell header(int row, int column, String text) {
        return TableCell.builder()
                .row(row)
                .column(column)
                .text(text)
                .header(true)
                .cellType(CellType.TEXT)
                .build();
    }

    /**
     * Creates a spanning cell.
     */
    public static TableCell spanning(int row, int column, int rowSpan, int colSpan, String text) {
        return TableCell.builder()
                .row(row)
                .column(column)
                .rowSpan(rowSpan)
                .colSpan(colSpan)
                .text(text)
                .cellType(inferCellType(text))
                .build();
    }

    /**
     * Infers cell type from content.
     */
    private static CellType inferCellType(String text) {
        if (text == null || text.trim().isEmpty()) {
            return CellType.EMPTY;
        }
        String trimmed = text.trim();

        // Check for currency
        if (trimmed.matches("^[$€£¥]?\\s*-?[\\d,]+\\.?\\d*$") ||
            trimmed.matches("^-?[\\d,]+\\.?\\d*\\s*[$€£¥]?$")) {
            if (trimmed.contains("$") || trimmed.contains("€") ||
                trimmed.contains("£") || trimmed.contains("¥")) {
                return CellType.CURRENCY;
            }
            return CellType.NUMBER;
        }

        // Check for percentage
        if (trimmed.matches("^-?\\d+\\.?\\d*\\s*%$")) {
            return CellType.PERCENTAGE;
        }

        // Check for date patterns
        if (trimmed.matches("^\\d{1,4}[-/.]\\d{1,2}[-/.]\\d{1,4}$") ||
            trimmed.matches("^\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4}$")) {
            return CellType.DATE;
        }

        return CellType.TEXT;
    }

    /**
     * Checks if this is a merged cell.
     */
    public boolean isMerged() {
        return rowSpan > 1 || colSpan > 1;
    }

    /**
     * Checks if content is empty.
     */
    public boolean isEmpty() {
        return text == null || text.trim().isEmpty();
    }

    /**
     * Gets the ending row (exclusive) for merged cells.
     */
    public int getEndRow() {
        return row + rowSpan;
    }

    /**
     * Gets the ending column (exclusive) for merged cells.
     */
    public int getEndColumn() {
        return column + colSpan;
    }
}
