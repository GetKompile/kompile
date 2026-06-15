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

package ai.kompile.core.graphrag.table;

import lombok.Getter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes and parses cell references from both formats used in the
 * knowledge graph:
 * <ul>
 *   <li><b>Excel format:</b> {@code wb:Budget.xlsx/cell:Sheet1!A1}</li>
 *   <li><b>Table format:</b> {@code tbl:html:page.html/tbl:0/table:Revenue/cell:R1C2}</li>
 * </ul>
 *
 * This utility bridges the two ID schemes so the process engine, cell
 * resolver, and query layer can work with either format uniformly.
 */
@Getter
public final class CellReference {

    /** Matches Excel-style external IDs: wb:<workbook>/cell:<Sheet>!<ColLetter><Row> */
    private static final Pattern EXCEL_PATTERN = Pattern.compile(
            "^wb:(.+?)/cell:(.+?)!([A-Z]{1,3})(\\d+)$");

    /** Matches generic table external IDs: tbl:<ns>/table:<name>/cell:R<row>C<col> */
    private static final Pattern TABLE_PATTERN = Pattern.compile(
            "^tbl:(.+?)/table:(.+?)/cell:R(\\d+)C(\\d+)$");

    /** Matches a bare cell reference extracted from externalId after "cell:" prefix */
    private static final Pattern BARE_EXCEL_REF = Pattern.compile(
            "^(.+?)!([A-Z]{1,3})(\\d+)$");

    /** Matches a bare table cell reference after "cell:" prefix */
    private static final Pattern BARE_TABLE_REF = Pattern.compile(
            "^R(\\d+)C(\\d+)$");

    /** Which ID format was parsed. */
    public enum Format { EXCEL, TABLE, UNKNOWN }

    private final Format format;
    private final String namespace;
    private final String tableName;
    private final int row;
    private final int col;
    private final String originalExternalId;
    private final String cellRef;

    private CellReference(Format format, String namespace, String tableName,
                          int row, int col, String originalExternalId, String cellRef) {
        this.format = format;
        this.namespace = namespace;
        this.tableName = tableName;
        this.row = row;
        this.col = col;
        this.originalExternalId = originalExternalId;
        this.cellRef = cellRef;
    }

    /**
     * Parses an external ID from the knowledge graph into a CellReference.
     *
     * @param externalId the full namespaced external ID (e.g., "wb:Budget.xlsx/cell:Sheet1!A1"
     *                   or "tbl:html:page/tbl:0/table:Revenue/cell:R1C2")
     * @return parsed CellReference, or one with {@link Format#UNKNOWN} if unparseable
     */
    public static CellReference parse(String externalId) {
        if (externalId == null || externalId.isBlank()) {
            return unknown(externalId);
        }

        Matcher excelMatcher = EXCEL_PATTERN.matcher(externalId);
        if (excelMatcher.matches()) {
            String workbook = excelMatcher.group(1);
            String sheet = excelMatcher.group(2);
            String colLetters = excelMatcher.group(3);
            int rowNum = Integer.parseInt(excelMatcher.group(4));
            int colNum = columnLetterToIndex(colLetters);
            String ref = sheet + "!" + colLetters + rowNum;
            return new CellReference(Format.EXCEL, workbook, sheet, rowNum - 1, colNum, externalId, ref);
        }

        Matcher tableMatcher = TABLE_PATTERN.matcher(externalId);
        if (tableMatcher.matches()) {
            String ns = tableMatcher.group(1);
            String tblName = tableMatcher.group(2);
            int rowIdx = Integer.parseInt(tableMatcher.group(3));
            int colIdx = Integer.parseInt(tableMatcher.group(4));
            String ref = "R" + rowIdx + "C" + colIdx;
            return new CellReference(Format.TABLE, ns, tblName, rowIdx, colIdx, externalId, ref);
        }

        return unknown(externalId);
    }

    /**
     * Parses a bare cell reference string (the part after "cell:" in an external ID).
     *
     * @param cellRefStr e.g. "Sheet1!A1" or "R1C2"
     * @return parsed CellReference, or UNKNOWN if unparseable
     */
    public static CellReference parseRef(String cellRefStr) {
        if (cellRefStr == null || cellRefStr.isBlank()) {
            return unknown(null);
        }

        Matcher excelRef = BARE_EXCEL_REF.matcher(cellRefStr);
        if (excelRef.matches()) {
            String sheet = excelRef.group(1);
            String colLetters = excelRef.group(2);
            int rowNum = Integer.parseInt(excelRef.group(3));
            int colNum = columnLetterToIndex(colLetters);
            return new CellReference(Format.EXCEL, null, sheet, rowNum - 1, colNum, null, cellRefStr);
        }

        Matcher tableRef = BARE_TABLE_REF.matcher(cellRefStr);
        if (tableRef.matches()) {
            int rowIdx = Integer.parseInt(tableRef.group(1));
            int colIdx = Integer.parseInt(tableRef.group(2));
            return new CellReference(Format.TABLE, null, null, rowIdx, colIdx, null, cellRefStr);
        }

        return unknown(null);
    }

    /**
     * Extracts the table/sheet name from any cell external ID format.
     * Useful for finding the parent TABLE node in the knowledge graph.
     *
     * @param externalId the cell's external ID
     * @return table/sheet name (lowercase), or null if not parseable
     */
    public static String extractTableName(String externalId) {
        CellReference ref = parse(externalId);
        return ref.tableName != null ? ref.tableName.toLowerCase() : null;
    }

    /** Convert column letters to 0-based index: A=0, B=1, Z=25, AA=26 */
    public static int columnLetterToIndex(String letters) {
        int result = 0;
        for (char c : letters.toUpperCase().toCharArray()) {
            result = result * 26 + (c - 'A' + 1);
        }
        return result - 1;
    }

    /** Convert 0-based column index to letters: 0=A, 25=Z, 26=AA */
    public static String columnIndexToLetter(int col) {
        StringBuilder sb = new StringBuilder();
        col++;
        while (col > 0) {
            col--;
            sb.insert(0, (char) ('A' + col % 26));
            col /= 26;
        }
        return sb.toString();
    }

    /**
     * Converts this cell reference to the Excel A1 notation.
     * For TABLE format cells, produces "TableName!<ColLetter><Row+1>".
     */
    public String toExcelNotation() {
        String colLetter = columnIndexToLetter(col);
        String prefix = tableName != null ? tableName + "!" : "";
        return prefix + colLetter + (row + 1);
    }

    /**
     * Converts this cell reference to the RnCn notation.
     */
    public String toRnCnNotation() {
        return "R" + row + "C" + col;
    }

    private static CellReference unknown(String externalId) {
        return new CellReference(Format.UNKNOWN, null, null, -1, -1, externalId, null);
    }

    public boolean isValid() { return format != Format.UNKNOWN; }

    @Override
    public String toString() {
        if (format == Format.UNKNOWN) return "CellReference{UNKNOWN}";
        return "CellReference{" + format + " table=" + tableName + " row=" + row + " col=" + col + "}";
    }
}
