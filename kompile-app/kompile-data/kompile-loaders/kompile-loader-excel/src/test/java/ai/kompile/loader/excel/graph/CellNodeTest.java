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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CellNode} builder, equality, and field semantics.
 */
class CellNodeTest {

    @Nested
    class BuilderAndFields {

        @Test
        void builderSetsAllFields() {
            CellNode cell = CellNode.builder()
                    .cellReference("Sheet1!A1")
                    .sheetName("Sheet1")
                    .column("A")
                    .row(1)
                    .cellType("FORMULA")
                    .formula("SUM(B1:B10)")
                    .displayValue("55")
                    .namedRange(true)
                    .namedRangeName("TotalSales")
                    .build();

            assertEquals("Sheet1!A1", cell.getCellReference());
            assertEquals("Sheet1", cell.getSheetName());
            assertEquals("A", cell.getColumn());
            assertEquals(1, cell.getRow());
            assertEquals("FORMULA", cell.getCellType());
            assertEquals("SUM(B1:B10)", cell.getFormula());
            assertEquals("55", cell.getDisplayValue());
            assertTrue(cell.isNamedRange());
            assertEquals("TotalSales", cell.getNamedRangeName());
        }

        @Test
        void builderDefaultsBooleanToFalse() {
            CellNode cell = CellNode.builder()
                    .cellReference("Sheet1!A1")
                    .sheetName("Sheet1")
                    .column("A")
                    .row(1)
                    .cellType("NUMERIC")
                    .build();

            assertFalse(cell.isNamedRange());
            assertNull(cell.getNamedRangeName());
            assertNull(cell.getFormula());
            assertNull(cell.getDisplayValue());
        }

        @Test
        void noArgConstructorCreatesEmptyNode() {
            CellNode cell = new CellNode();
            assertNull(cell.getCellReference());
            assertNull(cell.getSheetName());
            assertNull(cell.getColumn());
            assertEquals(0, cell.getRow());
            assertNull(cell.getCellType());
            assertFalse(cell.isNamedRange());
        }

        @Test
        void allArgConstructor() {
            CellNode cell = new CellNode("Sheet1!B2", "Sheet1", "B", 2,
                    "STRING", null, "hello", false, null, "Check this value", "Admin",
                    "https://example.com", "Example Link");

            assertEquals("Sheet1!B2", cell.getCellReference());
            assertEquals("Sheet1", cell.getSheetName());
            assertEquals("B", cell.getColumn());
            assertEquals(2, cell.getRow());
            assertEquals("STRING", cell.getCellType());
            assertNull(cell.getFormula());
            assertEquals("hello", cell.getDisplayValue());
            assertFalse(cell.isNamedRange());
            assertNull(cell.getNamedRangeName());
            assertEquals("Check this value", cell.getComment());
            assertEquals("Admin", cell.getCommentAuthor());
            assertEquals("https://example.com", cell.getHyperlink());
            assertEquals("Example Link", cell.getHyperlinkLabel());
        }

        @Test
        void settersWork() {
            CellNode cell = new CellNode();
            cell.setCellReference("Sheet2!C3");
            cell.setSheetName("Sheet2");
            cell.setColumn("C");
            cell.setRow(3);
            cell.setCellType("BOOLEAN");
            cell.setFormula(null);
            cell.setDisplayValue("TRUE");
            cell.setNamedRange(false);
            cell.setNamedRangeName(null);

            assertEquals("Sheet2!C3", cell.getCellReference());
            assertEquals("Sheet2", cell.getSheetName());
            assertEquals("C", cell.getColumn());
            assertEquals(3, cell.getRow());
            assertEquals("BOOLEAN", cell.getCellType());
            assertEquals("TRUE", cell.getDisplayValue());
        }
    }

    @Nested
    class CellTypes {

        @Test
        void formulaCellHasFormulaAndValue() {
            CellNode cell = CellNode.builder()
                    .cellReference("Sheet1!D5")
                    .sheetName("Sheet1")
                    .column("D")
                    .row(5)
                    .cellType("FORMULA")
                    .formula("A5*B5")
                    .displayValue("100")
                    .build();

            assertEquals("FORMULA", cell.getCellType());
            assertEquals("A5*B5", cell.getFormula());
            assertEquals("100", cell.getDisplayValue());
        }

        @Test
        void numericCellHasNoFormula() {
            CellNode cell = CellNode.builder()
                    .cellReference("Sheet1!A1")
                    .sheetName("Sheet1")
                    .column("A")
                    .row(1)
                    .cellType("NUMERIC")
                    .displayValue("42.5")
                    .build();

            assertEquals("NUMERIC", cell.getCellType());
            assertNull(cell.getFormula());
            assertEquals("42.5", cell.getDisplayValue());
        }

        @Test
        void blankCell() {
            CellNode cell = CellNode.builder()
                    .cellReference("Sheet1!E10")
                    .sheetName("Sheet1")
                    .column("E")
                    .row(10)
                    .cellType("BLANK")
                    .build();

            assertEquals("BLANK", cell.getCellType());
            assertNull(cell.getFormula());
            assertNull(cell.getDisplayValue());
        }

        @Test
        void namedRangeCell() {
            CellNode cell = CellNode.builder()
                    .cellReference("Sheet1!A1")
                    .sheetName("Sheet1")
                    .column("A")
                    .row(1)
                    .cellType("NUMERIC")
                    .displayValue("1000")
                    .namedRange(true)
                    .namedRangeName("Revenue")
                    .build();

            assertTrue(cell.isNamedRange());
            assertEquals("Revenue", cell.getNamedRangeName());
        }
    }

    @Nested
    class EqualsAndHashCode {

        @Test
        void equalNodesAreEqual() {
            CellNode a = CellNode.builder()
                    .cellReference("Sheet1!A1").sheetName("Sheet1")
                    .column("A").row(1).cellType("NUMERIC").displayValue("42")
                    .build();
            CellNode b = CellNode.builder()
                    .cellReference("Sheet1!A1").sheetName("Sheet1")
                    .column("A").row(1).cellType("NUMERIC").displayValue("42")
                    .build();

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        void differentCellRefNotEqual() {
            CellNode a = CellNode.builder()
                    .cellReference("Sheet1!A1").sheetName("Sheet1")
                    .column("A").row(1).cellType("NUMERIC")
                    .build();
            CellNode b = CellNode.builder()
                    .cellReference("Sheet1!B1").sheetName("Sheet1")
                    .column("B").row(1).cellType("NUMERIC")
                    .build();

            assertNotEquals(a, b);
        }

        @Test
        void differentSheetNotEqual() {
            CellNode a = CellNode.builder()
                    .cellReference("Sheet1!A1").sheetName("Sheet1")
                    .column("A").row(1).cellType("NUMERIC")
                    .build();
            CellNode b = CellNode.builder()
                    .cellReference("Sheet1!A1").sheetName("Sheet2")
                    .column("A").row(1).cellType("NUMERIC")
                    .build();

            assertNotEquals(a, b);
        }

        @Test
        void differentRowNotEqual() {
            CellNode a = CellNode.builder()
                    .cellReference("Sheet1!A1").sheetName("Sheet1")
                    .column("A").row(1).cellType("NUMERIC")
                    .build();
            CellNode b = CellNode.builder()
                    .cellReference("Sheet1!A1").sheetName("Sheet1")
                    .column("A").row(2).cellType("NUMERIC")
                    .build();

            assertNotEquals(a, b);
        }

        @Test
        void namedRangeFlagAffectsEquality() {
            CellNode a = CellNode.builder()
                    .cellReference("Sheet1!A1").sheetName("Sheet1")
                    .column("A").row(1).cellType("NUMERIC")
                    .namedRange(false)
                    .build();
            CellNode b = CellNode.builder()
                    .cellReference("Sheet1!A1").sheetName("Sheet1")
                    .column("A").row(1).cellType("NUMERIC")
                    .namedRange(true).namedRangeName("Total")
                    .build();

            assertNotEquals(a, b);
        }
    }

    @Nested
    class MultiColumnCells {

        @Test
        void doubleLetterColumn() {
            CellNode cell = CellNode.builder()
                    .cellReference("Sheet1!AA100")
                    .sheetName("Sheet1")
                    .column("AA")
                    .row(100)
                    .cellType("STRING")
                    .displayValue("far right")
                    .build();

            assertEquals("AA", cell.getColumn());
            assertEquals(100, cell.getRow());
        }

        @Test
        void tripleLetterColumn() {
            CellNode cell = CellNode.builder()
                    .cellReference("Sheet1!XFD1048576")
                    .sheetName("Sheet1")
                    .column("XFD")
                    .row(1048576)
                    .cellType("NUMERIC")
                    .displayValue("0")
                    .build();

            assertEquals("XFD", cell.getColumn());
            assertEquals(1048576, cell.getRow());
        }
    }

    @Nested
    class ToStringTest {

        @Test
        void toStringIncludesKey() {
            CellNode cell = CellNode.builder()
                    .cellReference("Sheet1!A1")
                    .sheetName("Sheet1")
                    .column("A")
                    .row(1)
                    .cellType("NUMERIC")
                    .displayValue("42")
                    .build();

            String s = cell.toString();
            assertTrue(s.contains("Sheet1!A1"));
            assertTrue(s.contains("NUMERIC"));
            assertTrue(s.contains("42"));
        }
    }
}
