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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CellReferenceTest {

    // ── Excel format parsing ───────────────────────────────────────────

    @Test
    void parseExcelFormatFullId() {
        CellReference ref = CellReference.parse("wb:Budget.xlsx/cell:Sheet1!A1");
        assertTrue(ref.isValid());
        assertEquals(CellReference.Format.EXCEL, ref.getFormat());
        assertEquals("Budget.xlsx", ref.getNamespace());
        assertEquals("Sheet1", ref.getTableName());
        assertEquals(0, ref.getRow());   // A1 = row 0 (0-based)
        assertEquals(0, ref.getCol());   // A = col 0
        assertEquals("Sheet1!A1", ref.getCellRef());
    }

    @Test
    void parseExcelMultiLetterColumn() {
        CellReference ref = CellReference.parse("wb:Report.xlsx/cell:Data!AA10");
        assertTrue(ref.isValid());
        assertEquals(CellReference.Format.EXCEL, ref.getFormat());
        assertEquals("Data", ref.getTableName());
        assertEquals(9, ref.getRow());    // row 10 = index 9
        assertEquals(26, ref.getCol());   // AA = 26
    }

    @Test
    void parseExcelColumnZ() {
        CellReference ref = CellReference.parse("wb:X.xlsx/cell:S1!Z100");
        assertTrue(ref.isValid());
        assertEquals(25, ref.getCol());   // Z = 25
        assertEquals(99, ref.getRow());   // row 100 = index 99
    }

    // ── Table format parsing ───────────────────────────────────────────

    @Test
    void parseTableFormatFullId() {
        CellReference ref = CellReference.parse("tbl:html:page.html/tbl:0/table:Revenue/cell:R1C2");
        assertTrue(ref.isValid());
        assertEquals(CellReference.Format.TABLE, ref.getFormat());
        assertEquals("html:page.html/tbl:0", ref.getNamespace());
        assertEquals("Revenue", ref.getTableName());
        assertEquals(1, ref.getRow());
        assertEquals(2, ref.getCol());
        assertEquals("R1C2", ref.getCellRef());
    }

    @Test
    void parseTableFormatOriginCell() {
        CellReference ref = CellReference.parse("tbl:pdf:doc.pdf/p1t0/table:Table-p1-t0/cell:R0C0");
        assertTrue(ref.isValid());
        assertEquals(CellReference.Format.TABLE, ref.getFormat());
        assertEquals("Table-p1-t0", ref.getTableName());
        assertEquals(0, ref.getRow());
        assertEquals(0, ref.getCol());
    }

    @Test
    void parseTableFormatGoogleSheets() {
        CellReference ref = CellReference.parse("tbl:gsheet:abc123/Sales/table:Sales/cell:R5C10");
        assertTrue(ref.isValid());
        assertEquals("gsheet:abc123/Sales", ref.getNamespace());
        assertEquals("Sales", ref.getTableName());
        assertEquals(5, ref.getRow());
        assertEquals(10, ref.getCol());
    }

    // ── Invalid/unknown inputs ─────────────────────────────────────────

    @Test
    void parseNullReturnsUnknown() {
        CellReference ref = CellReference.parse(null);
        assertFalse(ref.isValid());
        assertEquals(CellReference.Format.UNKNOWN, ref.getFormat());
    }

    @Test
    void parseBlankReturnsUnknown() {
        CellReference ref = CellReference.parse("   ");
        assertFalse(ref.isValid());
    }

    @Test
    void parseGarbageReturnsUnknown() {
        CellReference ref = CellReference.parse("not-a-cell-reference");
        assertFalse(ref.isValid());
        assertEquals(CellReference.Format.UNKNOWN, ref.getFormat());
    }

    // ── Bare reference parsing ─────────────────────────────────────────

    @Test
    void parseRefExcelBare() {
        CellReference ref = CellReference.parseRef("Sheet1!B3");
        assertTrue(ref.isValid());
        assertEquals(CellReference.Format.EXCEL, ref.getFormat());
        assertEquals("Sheet1", ref.getTableName());
        assertEquals(2, ref.getRow());    // row 3 = index 2
        assertEquals(1, ref.getCol());    // B = 1
    }

    @Test
    void parseRefTableBare() {
        CellReference ref = CellReference.parseRef("R4C7");
        assertTrue(ref.isValid());
        assertEquals(CellReference.Format.TABLE, ref.getFormat());
        assertEquals(4, ref.getRow());
        assertEquals(7, ref.getCol());
    }

    @Test
    void parseRefNullReturnsUnknown() {
        CellReference ref = CellReference.parseRef(null);
        assertFalse(ref.isValid());
    }

    // ── extractTableName ───────────────────────────────────────────────

    @Test
    void extractTableNameFromExcelId() {
        String name = CellReference.extractTableName("wb:Budget.xlsx/cell:Sheet1!A1");
        assertEquals("sheet1", name);
    }

    @Test
    void extractTableNameFromTableId() {
        String name = CellReference.extractTableName("tbl:html:page/tbl:0/table:Revenue/cell:R0C0");
        assertEquals("revenue", name);
    }

    @Test
    void extractTableNameFromNullReturnsNull() {
        assertNull(CellReference.extractTableName(null));
    }

    // ── Column letter/index conversion ─────────────────────────────────

    @Test
    void columnLetterToIndex() {
        assertEquals(0, CellReference.columnLetterToIndex("A"));
        assertEquals(1, CellReference.columnLetterToIndex("B"));
        assertEquals(25, CellReference.columnLetterToIndex("Z"));
        assertEquals(26, CellReference.columnLetterToIndex("AA"));
        assertEquals(27, CellReference.columnLetterToIndex("AB"));
        assertEquals(51, CellReference.columnLetterToIndex("AZ"));
        assertEquals(52, CellReference.columnLetterToIndex("BA"));
        assertEquals(701, CellReference.columnLetterToIndex("ZZ"));
    }

    @Test
    void columnIndexToLetter() {
        assertEquals("A", CellReference.columnIndexToLetter(0));
        assertEquals("B", CellReference.columnIndexToLetter(1));
        assertEquals("Z", CellReference.columnIndexToLetter(25));
        assertEquals("AA", CellReference.columnIndexToLetter(26));
        assertEquals("AB", CellReference.columnIndexToLetter(27));
        assertEquals("AZ", CellReference.columnIndexToLetter(51));
        assertEquals("BA", CellReference.columnIndexToLetter(52));
        assertEquals("ZZ", CellReference.columnIndexToLetter(701));
    }

    @Test
    void columnLetterRoundTrip() {
        for (int i = 0; i < 100; i++) {
            String letter = CellReference.columnIndexToLetter(i);
            assertEquals(i, CellReference.columnLetterToIndex(letter),
                    "Round-trip failed for index " + i + " → " + letter);
        }
    }

    // ── toExcelNotation / toRnCnNotation ───────────────────────────────

    @Test
    void excelRefToExcelNotationIsIdentity() {
        CellReference ref = CellReference.parse("wb:X.xlsx/cell:Sheet1!C5");
        assertEquals("Sheet1!C5", ref.toExcelNotation());
    }

    @Test
    void tableRefToExcelNotation() {
        CellReference ref = CellReference.parse("tbl:ns/table:Sales/cell:R2C3");
        // row 2 → Excel row 3, col 3 → D
        assertEquals("Sales!D3", ref.toExcelNotation());
    }

    @Test
    void excelRefToRnCnNotation() {
        CellReference ref = CellReference.parse("wb:X.xlsx/cell:Sheet1!C5");
        // C=col 2, 5=row index 4
        assertEquals("R4C2", ref.toRnCnNotation());
    }

    @Test
    void tableRefToRnCnNotationIsIdentity() {
        CellReference ref = CellReference.parse("tbl:ns/table:T/cell:R2C3");
        assertEquals("R2C3", ref.toRnCnNotation());
    }

    // ── toString ───────────────────────────────────────────────────────

    @Test
    void toStringForUnknown() {
        assertEquals("CellReference{UNKNOWN}", CellReference.parse("bad").toString());
    }

    @Test
    void toStringForValid() {
        String s = CellReference.parse("wb:X.xlsx/cell:S!A1").toString();
        assertTrue(s.contains("EXCEL"));
        assertTrue(s.contains("table=S"));
    }
}
