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

package ai.kompile.ocr;

import ai.kompile.ocr.structured.StructuredTable;
import org.nd4j.linalg.api.ndarray.INDArray;

import java.util.List;

/**
 * Interface for table extraction models.
 * These models identify tables and their structure (rows, columns, cells).
 *
 * <p>Examples: TableFormer, PubLayNet table detector, Docling TableStructure</p>
 */
public interface TableExtractionModel extends OcrModel {

    /**
     * Extracts table structure from an image.
     *
     * @param image Page image containing tables as INDArray [1, C, H, W]
     * @return List of detected tables with structure
     */
    List<DetectedTable> extractTables(INDArray image);

    /**
     * Extracts tables with OCR for cell contents.
     *
     * @param image Page image as INDArray
     * @param recognizer Recognition model for cell text
     * @return List of tables with recognized cell contents
     */
    List<StructuredTable> extractTablesWithText(INDArray image, TextRecognitionModel recognizer);

    /**
     * Detected table with structure information.
     */
    record DetectedTable(
        BoundingBox tableBounds,
        double confidence,
        List<TableRow> rows,
        List<TableColumn> columns,
        List<TableCellInfo> cells,
        int rowCount,
        int columnCount
    ) {
        /**
         * Creates a table from detected bounds.
         */
        public static DetectedTable withBounds(BoundingBox bounds, double confidence) {
            return new DetectedTable(bounds, confidence, null, null, null, 0, 0);
        }

        /**
         * Creates a fully structured table.
         */
        public static DetectedTable structured(BoundingBox bounds, double confidence,
                                               List<TableRow> rows, List<TableColumn> columns,
                                               List<TableCellInfo> cells) {
            return new DetectedTable(bounds, confidence, rows, columns, cells,
                    rows != null ? rows.size() : 0,
                    columns != null ? columns.size() : 0);
        }
    }

    /**
     * Row information.
     */
    record TableRow(
        int index,
        int y,
        int height,
        boolean isHeader
    ) {}

    /**
     * Column information.
     */
    record TableColumn(
        int index,
        int x,
        int width
    ) {}

    /**
     * Cell information before OCR.
     */
    record TableCellInfo(
        int row,
        int column,
        int rowSpan,
        int colSpan,
        BoundingBox bounds,
        boolean isHeader,
        double confidence
    ) {
        /**
         * Creates a simple cell.
         */
        public static TableCellInfo of(int row, int column, BoundingBox bounds) {
            return new TableCellInfo(row, column, 1, 1, bounds, false, 1.0);
        }

        /**
         * Creates a cell with span.
         */
        public static TableCellInfo spanning(int row, int column, int rowSpan, int colSpan,
                                             BoundingBox bounds, boolean isHeader) {
            return new TableCellInfo(row, column, rowSpan, colSpan, bounds, isHeader, 1.0);
        }
    }

    /**
     * Table extraction configuration.
     */
    record TableExtractionConfig(
        double confidenceThreshold,     // minimum table confidence
        boolean detectHeaders,          // identify header rows
        boolean detectMergedCells,      // identify merged cells
        int maxTables                   // maximum tables to detect (-1 unlimited)
    ) {
        public static TableExtractionConfig defaultConfig() {
            return new TableExtractionConfig(0.5, true, true, -1);
        }
    }

    /**
     * Extracts tables with custom configuration.
     */
    default List<DetectedTable> extractTables(INDArray image, TableExtractionConfig config) {
        return extractTables(image);
    }
}
