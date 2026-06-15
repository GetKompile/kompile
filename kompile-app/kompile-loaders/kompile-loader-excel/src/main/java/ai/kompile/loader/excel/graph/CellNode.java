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

package ai.kompile.loader.excel.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a single cell in the spreadsheet as a graph node.
 * Cells that contain formulas are the primary nodes of interest,
 * but referenced value cells are also included.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CellNode {
    /**
     * Fully qualified cell reference: "SheetName!A1"
     */
    private String cellReference;

    /**
     * Sheet name this cell belongs to.
     */
    private String sheetName;

    /**
     * Column letter(s): "A", "B", "AA", etc.
     */
    private String column;

    /**
     * Row number (1-based).
     */
    private int row;

    /**
     * The cell type: FORMULA, STRING, NUMERIC, BOOLEAN, DATE, BLANK.
     */
    private String cellType;

    /**
     * The formula string if this is a formula cell (e.g., "SUM(A1:A10)").
     * Null for non-formula cells.
     */
    private String formula;

    /**
     * The evaluated/display value of the cell.
     */
    private String displayValue;

    /**
     * Whether this cell is a named range target.
     */
    private boolean namedRange;

    /**
     * The named range name if applicable.
     */
    private String namedRangeName;

    /**
     * Comment/note text on this cell.
     */
    private String comment;

    /**
     * Author of the comment/note on this cell.
     */
    private String commentAuthor;

    /**
     * Hyperlink URL if this cell contains a hyperlink.
     */
    private String hyperlink;

    /**
     * Hyperlink label/display text if different from cell value.
     */
    private String hyperlinkLabel;
}
