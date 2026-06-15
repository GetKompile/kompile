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

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a structured table extracted from a document.
 * Contains full cell structure with row/column information.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StructuredTable {

    /**
     * Unique identifier for this table.
     */
    private String id;

    /**
     * Page number where the table was found (1-indexed).
     */
    private int pageNumber;

    /**
     * Table index on the page (0-indexed).
     */
    private int tableIndex;

    /**
     * Bounding box for the entire table.
     */
    private BoundingBox boundingBox;

    /**
     * Number of rows in the table.
     */
    private int rowCount;

    /**
     * Number of columns in the table.
     */
    private int columnCount;

    /**
     * All cells in the table.
     */
    private List<TableCell> cells;

    /**
     * Header row indices (0-indexed).
     */
    private List<Integer> headerRows;

    /**
     * Overall confidence score for table extraction.
     */
    @Builder.Default
    private double confidence = 1.0;

    /**
     * Optional: Detected table title/caption.
     */
    private String caption;

    /**
     * Optional: Summary of table contents.
     */
    private String summary;

    /**
     * Gets a cell at the specified position.
     */
    public TableCell getCell(int row, int column) {
        if (cells == null) {
            return null;
        }
        for (TableCell cell : cells) {
            if (cell.getRow() == row && cell.getColumn() == column) {
                return cell;
            }
            // Check for merged cells
            if (cell.getRow() <= row && row < cell.getEndRow() &&
                cell.getColumn() <= column && column < cell.getEndColumn()) {
                return cell;
            }
        }
        return null;
    }

    /**
     * Gets all cells in a row.
     */
    public List<TableCell> getRow(int rowIndex) {
        List<TableCell> rowCells = new ArrayList<>();
        if (cells == null) {
            return rowCells;
        }
        for (TableCell cell : cells) {
            if (cell.getRow() == rowIndex) {
                rowCells.add(cell);
            }
        }
        rowCells.sort((a, b) -> Integer.compare(a.getColumn(), b.getColumn()));
        return rowCells;
    }

    /**
     * Gets all cells in a column.
     */
    public List<TableCell> getColumn(int columnIndex) {
        List<TableCell> columnCells = new ArrayList<>();
        if (cells == null) {
            return columnCells;
        }
        for (TableCell cell : cells) {
            if (cell.getColumn() == columnIndex) {
                columnCells.add(cell);
            }
        }
        columnCells.sort((a, b) -> Integer.compare(a.getRow(), b.getRow()));
        return columnCells;
    }

    /**
     * Gets header cells.
     */
    public List<TableCell> getHeaders() {
        List<TableCell> headers = new ArrayList<>();
        if (cells == null) {
            return headers;
        }
        for (TableCell cell : cells) {
            if (cell.isHeader()) {
                headers.add(cell);
            }
        }
        return headers;
    }

    /**
     * Gets header text values.
     */
    public List<String> getHeaderTexts() {
        return getHeaders().stream()
                .map(TableCell::getText)
                .toList();
    }

    /**
     * Converts the table to markdown format.
     */
    public String toMarkdown() {
        if (cells == null || cells.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        // Add caption if present
        if (caption != null && !caption.isEmpty()) {
            sb.append("**").append(caption).append("**\n\n");
        }

        // Build row by row
        for (int row = 0; row < rowCount; row++) {
            sb.append("|");
            for (int col = 0; col < columnCount; col++) {
                TableCell cell = getCell(row, col);
                String text = cell != null ? cell.getText() : "";
                if (text == null) text = "";
                sb.append(" ").append(text.replace("|", "\\|")).append(" |");
            }
            sb.append("\n");

            // Add header separator after first row (or specified header rows)
            if (row == 0 || (headerRows != null && headerRows.contains(row))) {
                sb.append("|");
                for (int col = 0; col < columnCount; col++) {
                    sb.append(" --- |");
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * Converts the table to CSV format.
     */
    public String toCsv() {
        if (cells == null || cells.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (int row = 0; row < rowCount; row++) {
            for (int col = 0; col < columnCount; col++) {
                if (col > 0) {
                    sb.append(",");
                }
                TableCell cell = getCell(row, col);
                String text = cell != null ? cell.getText() : "";
                if (text == null) text = "";
                // Escape quotes and wrap in quotes if contains comma
                if (text.contains(",") || text.contains("\"") || text.contains("\n")) {
                    text = "\"" + text.replace("\"", "\"\"") + "\"";
                }
                sb.append(text);
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Creates a table builder with dimensions.
     */
    public static StructuredTable.StructuredTableBuilder withDimensions(int rows, int columns) {
        return StructuredTable.builder()
                .rowCount(rows)
                .columnCount(columns)
                .cells(new ArrayList<>());
    }
}
