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

import ai.kompile.loader.excel.graph.FormulaDependency.DependencyType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FormulaDependency} builder, equality, and dependency type semantics.
 */
class FormulaDependencyTest {

    @Nested
    class BuilderAndFields {

        @Test
        void builderSetsAllFields() {
            FormulaDependency dep = FormulaDependency.builder()
                    .formulaCell("Sheet1!C1")
                    .referencedCell("Sheet1!A1")
                    .dependencyType(DependencyType.CELL_REFERENCE)
                    .crossSheet(false)
                    .formula("A1+B1")
                    .rangeReference(null)
                    .build();

            assertEquals("Sheet1!C1", dep.getFormulaCell());
            assertEquals("Sheet1!A1", dep.getReferencedCell());
            assertEquals(DependencyType.CELL_REFERENCE, dep.getDependencyType());
            assertFalse(dep.isCrossSheet());
            assertEquals("A1+B1", dep.getFormula());
            assertNull(dep.getRangeReference());
        }

        @Test
        void noArgConstructor() {
            FormulaDependency dep = new FormulaDependency();
            assertNull(dep.getFormulaCell());
            assertNull(dep.getReferencedCell());
            assertNull(dep.getDependencyType());
            assertFalse(dep.isCrossSheet());
            assertNull(dep.getFormula());
            assertNull(dep.getRangeReference());
        }

        @Test
        void allArgConstructor() {
            FormulaDependency dep = new FormulaDependency(
                    "Sheet1!D1", "Sheet2!A1",
                    DependencyType.CROSS_SHEET_REFERENCE,
                    true, "Sheet2!A1*2", null
            );

            assertEquals("Sheet1!D1", dep.getFormulaCell());
            assertEquals("Sheet2!A1", dep.getReferencedCell());
            assertEquals(DependencyType.CROSS_SHEET_REFERENCE, dep.getDependencyType());
            assertTrue(dep.isCrossSheet());
            assertEquals("Sheet2!A1*2", dep.getFormula());
            assertNull(dep.getRangeReference());
        }

        @Test
        void settersWork() {
            FormulaDependency dep = new FormulaDependency();
            dep.setFormulaCell("Sheet1!E5");
            dep.setReferencedCell("Sheet1!A1");
            dep.setDependencyType(DependencyType.RANGE_REFERENCE);
            dep.setCrossSheet(false);
            dep.setFormula("SUM(A1:A10)");
            dep.setRangeReference("A1:A10");

            assertEquals("Sheet1!E5", dep.getFormulaCell());
            assertEquals("Sheet1!A1", dep.getReferencedCell());
            assertEquals(DependencyType.RANGE_REFERENCE, dep.getDependencyType());
            assertFalse(dep.isCrossSheet());
            assertEquals("SUM(A1:A10)", dep.getFormula());
            assertEquals("A1:A10", dep.getRangeReference());
        }
    }

    @Nested
    class DependencyTypes {

        @Test
        void cellReference() {
            FormulaDependency dep = FormulaDependency.builder()
                    .formulaCell("Sheet1!C1")
                    .referencedCell("Sheet1!A1")
                    .dependencyType(DependencyType.CELL_REFERENCE)
                    .crossSheet(false)
                    .formula("A1+B1")
                    .build();

            assertEquals(DependencyType.CELL_REFERENCE, dep.getDependencyType());
            assertFalse(dep.isCrossSheet());
            assertNull(dep.getRangeReference());
        }

        @Test
        void rangeReference() {
            FormulaDependency dep = FormulaDependency.builder()
                    .formulaCell("Sheet1!B11")
                    .referencedCell("Sheet1!B1")
                    .dependencyType(DependencyType.RANGE_REFERENCE)
                    .crossSheet(false)
                    .formula("SUM(B1:B10)")
                    .rangeReference("B1:B10")
                    .build();

            assertEquals(DependencyType.RANGE_REFERENCE, dep.getDependencyType());
            assertEquals("B1:B10", dep.getRangeReference());
        }

        @Test
        void crossSheetReference() {
            FormulaDependency dep = FormulaDependency.builder()
                    .formulaCell("Summary!A1")
                    .referencedCell("Data!B5")
                    .dependencyType(DependencyType.CROSS_SHEET_REFERENCE)
                    .crossSheet(true)
                    .formula("Data!B5")
                    .build();

            assertEquals(DependencyType.CROSS_SHEET_REFERENCE, dep.getDependencyType());
            assertTrue(dep.isCrossSheet());
        }

        @Test
        void namedRangeReference() {
            FormulaDependency dep = FormulaDependency.builder()
                    .formulaCell("Sheet1!F1")
                    .referencedCell("Sheet1!A1")
                    .dependencyType(DependencyType.NAMED_RANGE_REFERENCE)
                    .crossSheet(false)
                    .formula("SUM(TotalSales)")
                    .rangeReference("TotalSales")
                    .build();

            assertEquals(DependencyType.NAMED_RANGE_REFERENCE, dep.getDependencyType());
            assertEquals("TotalSales", dep.getRangeReference());
        }

        @Test
        void allDependencyTypeEnumValues() {
            DependencyType[] values = DependencyType.values();
            assertEquals(4, values.length);
            assertEquals(DependencyType.CELL_REFERENCE, DependencyType.valueOf("CELL_REFERENCE"));
            assertEquals(DependencyType.RANGE_REFERENCE, DependencyType.valueOf("RANGE_REFERENCE"));
            assertEquals(DependencyType.CROSS_SHEET_REFERENCE, DependencyType.valueOf("CROSS_SHEET_REFERENCE"));
            assertEquals(DependencyType.NAMED_RANGE_REFERENCE, DependencyType.valueOf("NAMED_RANGE_REFERENCE"));
        }
    }

    @Nested
    class EqualsAndHashCode {

        @Test
        void equalDepsAreEqual() {
            FormulaDependency a = FormulaDependency.builder()
                    .formulaCell("Sheet1!C1")
                    .referencedCell("Sheet1!A1")
                    .dependencyType(DependencyType.CELL_REFERENCE)
                    .crossSheet(false)
                    .formula("A1+B1")
                    .build();
            FormulaDependency b = FormulaDependency.builder()
                    .formulaCell("Sheet1!C1")
                    .referencedCell("Sheet1!A1")
                    .dependencyType(DependencyType.CELL_REFERENCE)
                    .crossSheet(false)
                    .formula("A1+B1")
                    .build();

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        void differentFormulaCellNotEqual() {
            FormulaDependency a = FormulaDependency.builder()
                    .formulaCell("Sheet1!C1")
                    .referencedCell("Sheet1!A1")
                    .dependencyType(DependencyType.CELL_REFERENCE)
                    .build();
            FormulaDependency b = FormulaDependency.builder()
                    .formulaCell("Sheet1!D1")
                    .referencedCell("Sheet1!A1")
                    .dependencyType(DependencyType.CELL_REFERENCE)
                    .build();

            assertNotEquals(a, b);
        }

        @Test
        void differentDependencyTypeNotEqual() {
            FormulaDependency a = FormulaDependency.builder()
                    .formulaCell("Sheet1!C1")
                    .referencedCell("Sheet1!A1")
                    .dependencyType(DependencyType.CELL_REFERENCE)
                    .build();
            FormulaDependency b = FormulaDependency.builder()
                    .formulaCell("Sheet1!C1")
                    .referencedCell("Sheet1!A1")
                    .dependencyType(DependencyType.RANGE_REFERENCE)
                    .build();

            assertNotEquals(a, b);
        }

        @Test
        void crossSheetFlagAffectsEquality() {
            FormulaDependency a = FormulaDependency.builder()
                    .formulaCell("Sheet1!C1")
                    .referencedCell("Sheet2!A1")
                    .dependencyType(DependencyType.CROSS_SHEET_REFERENCE)
                    .crossSheet(true)
                    .build();
            FormulaDependency b = FormulaDependency.builder()
                    .formulaCell("Sheet1!C1")
                    .referencedCell("Sheet2!A1")
                    .dependencyType(DependencyType.CROSS_SHEET_REFERENCE)
                    .crossSheet(false)
                    .build();

            assertNotEquals(a, b);
        }
    }

    @Nested
    class ToStringTest {

        @Test
        void toStringIncludesKeyFields() {
            FormulaDependency dep = FormulaDependency.builder()
                    .formulaCell("Sheet1!C1")
                    .referencedCell("Sheet1!A1")
                    .dependencyType(DependencyType.CELL_REFERENCE)
                    .crossSheet(false)
                    .formula("A1+B1")
                    .build();

            String s = dep.toString();
            assertTrue(s.contains("Sheet1!C1"));
            assertTrue(s.contains("Sheet1!A1"));
            assertTrue(s.contains("CELL_REFERENCE"));
            assertTrue(s.contains("A1+B1"));
        }
    }

    @Nested
    class EdgeCases {

        @Test
        void sameCellAsSourceAndTarget() {
            // Pathological but valid data model: a cell referencing itself
            FormulaDependency dep = FormulaDependency.builder()
                    .formulaCell("Sheet1!A1")
                    .referencedCell("Sheet1!A1")
                    .dependencyType(DependencyType.CELL_REFERENCE)
                    .crossSheet(false)
                    .formula("A1")
                    .build();

            assertEquals(dep.getFormulaCell(), dep.getReferencedCell());
        }

        @Test
        void nullFieldsInBuilder() {
            FormulaDependency dep = FormulaDependency.builder()
                    .formulaCell(null)
                    .referencedCell(null)
                    .dependencyType(null)
                    .formula(null)
                    .rangeReference(null)
                    .build();

            assertNull(dep.getFormulaCell());
            assertNull(dep.getReferencedCell());
            assertNull(dep.getDependencyType());
        }
    }
}
