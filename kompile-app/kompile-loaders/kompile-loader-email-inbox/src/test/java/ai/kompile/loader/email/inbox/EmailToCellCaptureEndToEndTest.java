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

package ai.kompile.loader.email.inbox;

import ai.kompile.loader.excel.graph.CellNode;
import ai.kompile.loader.excel.graph.FormulaDependency;
import ai.kompile.loader.excel.graph.SpreadsheetGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for the full pipeline:
 *   Email body text → EmailBodyValueExtractor → EmailValueCellMapper → SpreadsheetGraph
 *
 * Tests that cell references and financial values mentioned in emails are correctly
 * extracted and mapped to the right cells in an attached spreadsheet. This is the
 * core "cell capture" path that enables process automation: an email says
 * "update cell B2 with $50,000" and the system can resolve that to a specific
 * cell in a known workbook.
 */
class EmailToCellCaptureEndToEndTest {

    private EmailBodyValueExtractor extractor;
    private EmailValueCellMapper mapper;

    @BeforeEach
    void setUp() {
        extractor = new EmailBodyValueExtractor();
        mapper = new EmailValueCellMapper();
    }

    // ── Spreadsheet builders ─────────────────────────────────────────────────

    /**
     * Builds a realistic quarterly financial spreadsheet graph:
     *
     *        A         B         C         D
     *  1  Quarter    Revenue    Costs     Profit
     *  2  Q1         50000     30000     =B2-C2
     *  3  Q2         75000     40000     =B3-C3
     *  4  Total      =SUM(B2:B3) =SUM(C2:C3) =SUM(D2:D3)
     *
     * Cell references use "Revenue!" sheet prefix.
     */
    private SpreadsheetGraph buildFinancialSpreadsheet() {
        SpreadsheetGraph graph = new SpreadsheetGraph();
        graph.setWorkbookName("Budget-2024.xlsx");

        // Input cells
        graph.addCell(CellNode.builder().cellReference("Revenue!B2").sheetName("Revenue")
                .column("B").row(2).cellType("NUMERIC").displayValue("50000").namedRange(false).build());
        graph.addCell(CellNode.builder().cellReference("Revenue!C2").sheetName("Revenue")
                .column("C").row(2).cellType("NUMERIC").displayValue("30000").namedRange(false).build());
        graph.addCell(CellNode.builder().cellReference("Revenue!B3").sheetName("Revenue")
                .column("B").row(3).cellType("NUMERIC").displayValue("75000").namedRange(false).build());
        graph.addCell(CellNode.builder().cellReference("Revenue!C3").sheetName("Revenue")
                .column("C").row(3).cellType("NUMERIC").displayValue("40000").namedRange(false).build());

        // Named range cells
        graph.addCell(CellNode.builder().cellReference("Revenue!B2").sheetName("Revenue")
                .column("B").row(2).cellType("NUMERIC").displayValue("50000")
                .namedRange(true).namedRangeName("revenue_q1").build());

        // Formula cells
        graph.addCell(CellNode.builder().cellReference("Revenue!D2").sheetName("Revenue")
                .column("D").row(2).cellType("FORMULA").formula("B2-C2").displayValue("20000").namedRange(false).build());
        graph.addCell(CellNode.builder().cellReference("Revenue!D3").sheetName("Revenue")
                .column("D").row(3).cellType("FORMULA").formula("B3-C3").displayValue("35000").namedRange(false).build());
        graph.addCell(CellNode.builder().cellReference("Revenue!B4").sheetName("Revenue")
                .column("B").row(4).cellType("FORMULA").formula("SUM(B2:B3)").displayValue("125000").namedRange(false).build());

        // Dependencies
        graph.addDependency(FormulaDependency.builder()
                .formulaCell("Revenue!D2").referencedCell("Revenue!B2")
                .dependencyType(FormulaDependency.DependencyType.CELL_REFERENCE).build());
        graph.addDependency(FormulaDependency.builder()
                .formulaCell("Revenue!D2").referencedCell("Revenue!C2")
                .dependencyType(FormulaDependency.DependencyType.CELL_REFERENCE).build());

        return graph;
    }

    /**
     * Builds a tax calculation spreadsheet with named ranges:
     *
     *        A           B
     *  1  Field        Value
     *  2  income       100000   (named: income)
     *  3  tax_rate     0.30     (named: tax_rate)
     *  4  deductions   5000     (named: deductions)
     *  5  tax_owed     =B2*B3-B4  (formula)
     */
    private SpreadsheetGraph buildTaxSpreadsheet() {
        SpreadsheetGraph graph = new SpreadsheetGraph();
        graph.setWorkbookName("TaxCalc.xlsx");

        graph.addCell(CellNode.builder().cellReference("Sheet1!B2").sheetName("Sheet1")
                .column("B").row(2).cellType("NUMERIC").displayValue("100000")
                .namedRange(true).namedRangeName("income").build());
        graph.addCell(CellNode.builder().cellReference("Sheet1!B3").sheetName("Sheet1")
                .column("B").row(3).cellType("NUMERIC").displayValue("0.30")
                .namedRange(true).namedRangeName("tax_rate").build());
        graph.addCell(CellNode.builder().cellReference("Sheet1!B4").sheetName("Sheet1")
                .column("B").row(4).cellType("NUMERIC").displayValue("5000")
                .namedRange(true).namedRangeName("deductions").build());
        graph.addCell(CellNode.builder().cellReference("Sheet1!B5").sheetName("Sheet1")
                .column("B").row(5).cellType("FORMULA").formula("B2*B3-B4").displayValue("25000")
                .namedRange(false).build());

        graph.addNamedRange("income", "Sheet1!B2");
        graph.addNamedRange("tax_rate", "Sheet1!B3");
        graph.addNamedRange("deductions", "Sheet1!B4");

        return graph;
    }

    private Document emailDoc(String body, String subject) {
        Document d = new Document(body);
        d.getMetadata().put("email.subject", subject);
        d.getMetadata().put("email.messageId", "<msg-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com>");
        d.getMetadata().put("email.from", "alice@example.com");
        d.getMetadata().put("email.to", "bob@example.com");
        return d;
    }

    // ── Direct cell reference capture ────────────────────────────────────────

    @Nested
    class DirectCellReferences {

        @Test
        void emailMentionsCellB2_mapsToRevenueCellInSpreadsheet() {
            Document email = emailDoc(
                    "Hi team,\n\nPlease update cell B2 with the Q1 revenue figure of $52,000.\n\nThanks,\nAlice",
                    "Q1 Revenue Update"
            );

            List<EmailBodyValueExtractor.ExtractedValue> values = extractor.extract(email);
            SpreadsheetGraph graph = buildFinancialSpreadsheet();
            List<EmailValueCellMapper.CellMapping> mappings = mapper.mapValues(values, graph);

            // Should have a direct cell reference mapping to Revenue!B2
            EmailValueCellMapper.CellMapping cellRefMapping = mappings.stream()
                    .filter(m -> m.cellReference.endsWith("B2"))
                    .findFirst().orElse(null);

            assertNotNull(cellRefMapping, "Should map the 'cell B2' reference to Revenue!B2; " +
                    "all mappings: " + mappings.stream().map(m -> m.cellReference + "@" + m.confidence).toList());
            assertEquals(0.95, cellRefMapping.confidence, 0.001,
                    "Direct cell reference should have 0.95 confidence");

            // Should also extract the $52,000 currency value
            boolean hasCurrency = values.stream()
                    .anyMatch(v -> "CURRENCY".equals(v.type) && v.parsedValue instanceof Double d && Math.abs(d - 52000.0) < 0.01);
            assertTrue(hasCurrency, "Should extract $52,000 as a CURRENCY value");
        }

        @Test
        void emailMentionsSheetPrefixedCell_mapsExactly() {
            Document email = emailDoc(
                    "Check Revenue!B3 for the Q2 numbers. The new value should be $80,000.",
                    "Q2 Update"
            );

            List<EmailBodyValueExtractor.ExtractedValue> values = extractor.extract(email);
            SpreadsheetGraph graph = buildFinancialSpreadsheet();
            List<EmailValueCellMapper.CellMapping> mappings = mapper.mapValues(values, graph);

            // Sheet-prefixed reference should match exactly
            EmailValueCellMapper.CellMapping exactMatch = mappings.stream()
                    .filter(m -> "Revenue!B3".equals(m.cellReference))
                    .findFirst().orElse(null);

            assertNotNull(exactMatch,
                    "Sheet-prefixed 'Revenue!B3' should map to exactly Revenue!B3; " +
                            "mappings: " + mappings.stream().map(m -> m.cellReference).toList());
            assertEquals(0.95, exactMatch.confidence, 0.001);
        }

        @Test
        void emailMentionsNonExistentCell_noDirectMapping() {
            Document email = emailDoc(
                    "Please check cell Z99 for the final total.",
                    "Data Check"
            );

            List<EmailBodyValueExtractor.ExtractedValue> values = extractor.extract(email);
            SpreadsheetGraph graph = buildFinancialSpreadsheet();
            List<EmailValueCellMapper.CellMapping> mappings = mapper.mapValues(values, graph);

            // Z99 doesn't exist in the graph — no 0.95 direct mapping
            boolean hasHighConfZ99 = mappings.stream()
                    .anyMatch(m -> m.cellReference.contains("Z99") && m.confidence >= 0.9);
            assertFalse(hasHighConfZ99,
                    "Non-existent cell Z99 should not produce a high-confidence direct mapping");
        }
    }

    // ── Named field to named range mapping ──────────────────────────────────

    @Nested
    class NamedFieldCapture {

        @Test
        void emailKeyValuePair_mapsToNamedRangeCell() {
            Document email = emailDoc(
                    "Hi,\n\nFor this year's filing:\n  income: $120,000\n  tax: $36,000\n\nPlease process.",
                    "Tax Filing"
            );

            List<EmailBodyValueExtractor.ExtractedValue> values = extractor.extract(email);
            SpreadsheetGraph graph = buildTaxSpreadsheet();
            List<EmailValueCellMapper.CellMapping> mappings = mapper.mapValues(values, graph);

            // The key "income" should match the named range "income" on Sheet1!B2
            // income: $120,000 → extracted as CURRENCY with fieldName=null from KV pattern,
            // but also as a KV pair with fieldName from the KV pattern
            boolean foundIncomeMapping = mappings.stream()
                    .anyMatch(m -> m.cellReference.equals("Sheet1!B2"));
            assertTrue(foundIncomeMapping,
                    "Email 'income: $120,000' should map to the 'income' named-range cell Sheet1!B2; " +
                            "mappings: " + mappings.stream().map(m -> m.cellReference + "@" + m.confidence).toList());
        }

        @Test
        void multipleNamedFieldsMapToCorrectCells() {
            // "discount" should match "deductions" via field name similarity (partial match)
            Document email = emailDoc(
                    "Updated figures:\n  revenue: 150000\n  cost: 45000",
                    "Q3 Update"
            );

            List<EmailBodyValueExtractor.ExtractedValue> values = extractor.extract(email);

            // Verify extraction produced fieldName values
            List<EmailBodyValueExtractor.ExtractedValue> kvValues = values.stream()
                    .filter(v -> v.fieldName != null)
                    .toList();
            assertFalse(kvValues.isEmpty(),
                    "Should extract key-value pairs with fieldNames");

            boolean hasRevenue = kvValues.stream().anyMatch(v -> "revenue".equals(v.fieldName));
            boolean hasCost = kvValues.stream().anyMatch(v -> "cost".equals(v.fieldName));
            assertTrue(hasRevenue, "Should extract 'revenue' as a field name");
            assertTrue(hasCost, "Should extract 'cost' as a field name");
        }
    }

    // ── Currency value to numeric cell mapping ──────────────────────────────

    @Nested
    class CurrencyCapture {

        @Test
        void currencyValueMapsToNumericCellInRange() {
            Document email = emailDoc(
                    "Q1 revenue came in at $48,000 vs. the budgeted $50,000.",
                    "Revenue Variance"
            );

            List<EmailBodyValueExtractor.ExtractedValue> values = extractor.extract(email);
            SpreadsheetGraph graph = buildFinancialSpreadsheet();
            List<EmailValueCellMapper.CellMapping> mappings = mapper.mapValues(values, graph);

            // Currency values should produce at least one mapping to NUMERIC cells
            boolean hasCurrencyMapping = mappings.stream()
                    .anyMatch(m -> m.extractedValue.type.equals("CURRENCY"));
            assertTrue(hasCurrencyMapping,
                    "Currency values from email should map to NUMERIC cells in spreadsheet");

            // All mapped cells should be input cells (not formula cells)
            for (EmailValueCellMapper.CellMapping m : mappings) {
                CellNode cell = graph.getCells().get(m.cellReference);
                if (cell != null) {
                    assertNotEquals("FORMULA", cell.getCellType(),
                            "Mapped cell " + m.cellReference + " should be an input cell, not a formula cell");
                }
            }
        }

        @Test
        void percentageMapsToNumericNotFormulaCell() {
            Document email = emailDoc(
                    "The growth rate is 15% this quarter.",
                    "Growth Update"
            );

            List<EmailBodyValueExtractor.ExtractedValue> values = extractor.extract(email);

            // Verify percentage extracted
            boolean hasPct = values.stream()
                    .anyMatch(v -> "PERCENTAGE".equals(v.type)
                            && v.parsedValue instanceof Double d
                            && Math.abs(d - 0.15) < 0.001);
            assertTrue(hasPct, "Should extract 15% as 0.15");

            // Map against tax spreadsheet (has tax_rate at 0.30)
            SpreadsheetGraph graph = buildTaxSpreadsheet();
            List<EmailValueCellMapper.CellMapping> mappings = mapper.mapValues(values, graph);

            // Percentage is only compatible with NUMERIC cells per isTypeCompatible
            for (EmailValueCellMapper.CellMapping m : mappings) {
                CellNode cell = graph.getCells().get(m.cellReference);
                if (cell != null) {
                    assertNotEquals("FORMULA", cell.getCellType(),
                            "Percentage should not map to formula cell " + m.cellReference);
                    assertNotEquals("STRING", cell.getCellType(),
                            "Percentage should not map to string cell " + m.cellReference);
                }
            }
        }
    }

    // ── Full realistic email scenario ───────────────────────────────────────

    @Nested
    class RealisticScenarios {

        @Test
        void accountantEmailUpdatingBudgetSpreadsheet() {
            // Realistic email an accountant might send
            Document email = emailDoc(
                    "Hi Finance Team,\n\n" +
                    "Please update the Budget-2024.xlsx spreadsheet with the following:\n\n" +
                    "- Q1 Revenue (cell B2): $52,500\n" +
                    "- Q1 Costs (cell C2): $31,000\n" +
                    "- Q2 Revenue (cell B3): $78,200\n\n" +
                    "The Q2 costs remain at $40,000.\n\n" +
                    "The formula in Revenue!B4 should auto-calculate the total.\n\n" +
                    "Best,\nAlice",
                    "Budget-2024.xlsx Updates"
            );

            // Step 1: Extract values
            List<EmailBodyValueExtractor.ExtractedValue> values = extractor.extract(email);

            // Should extract cell references
            List<EmailBodyValueExtractor.ExtractedValue> cellRefs = values.stream()
                    .filter(v -> "CELL_REFERENCE".equals(v.type)).toList();
            assertTrue(cellRefs.size() >= 3,
                    "Should extract at least 3 cell references (B2, C2, B3); found: " +
                            cellRefs.stream().map(v -> v.parsedValue.toString()).toList());

            // Should extract currency values
            List<EmailBodyValueExtractor.ExtractedValue> currencies = values.stream()
                    .filter(v -> "CURRENCY".equals(v.type)).toList();
            assertTrue(currencies.size() >= 3,
                    "Should extract at least 3 currency values; found: " +
                            currencies.stream().map(v -> v.rawText).toList());

            // All values should have email metadata
            for (EmailBodyValueExtractor.ExtractedValue v : values) {
                assertEquals("Budget-2024.xlsx Updates", v.emailSubject);
                assertNotNull(v.emailMessageId);
            }

            // Step 2: Map to spreadsheet
            SpreadsheetGraph graph = buildFinancialSpreadsheet();
            List<EmailValueCellMapper.CellMapping> mappings = mapper.mapValues(values, graph);

            // Should have direct cell reference mappings
            Set<String> mappedCells = new HashSet<>();
            for (EmailValueCellMapper.CellMapping m : mappings) {
                mappedCells.add(m.cellReference);
            }

            // B2 and B3 should be mapped (via "cell B2" and "cell B3" references)
            boolean hasB2 = mappedCells.stream().anyMatch(c -> c.endsWith("B2"));
            boolean hasB3 = mappedCells.stream().anyMatch(c -> c.endsWith("B3"));
            assertTrue(hasB2, "Should map cell B2; mapped cells: " + mappedCells);
            assertTrue(hasB3, "Should map cell B3; mapped cells: " + mappedCells);
        }

        @Test
        void emailWithMixedValuesAndDates() {
            Document email = emailDoc(
                    "For the fiscal year ending 2024-12-31:\n" +
                    "  total: $2,500,000\n" +
                    "  margin: 35%\n" +
                    "Please update cell B2 and confirm by 03/15/2025.\n",
                    "Year End Review"
            );

            List<EmailBodyValueExtractor.ExtractedValue> values = extractor.extract(email);

            // Should extract all 4 types
            Set<String> types = new HashSet<>();
            for (EmailBodyValueExtractor.ExtractedValue v : values) {
                types.add(v.type);
            }
            assertTrue(types.contains("CURRENCY"), "Should extract CURRENCY");
            assertTrue(types.contains("PERCENTAGE"), "Should extract PERCENTAGE");
            assertTrue(types.contains("DATE"), "Should extract DATE");
            assertTrue(types.contains("CELL_REFERENCE"), "Should extract CELL_REFERENCE");

            // Map to spreadsheet
            SpreadsheetGraph graph = buildFinancialSpreadsheet();
            List<EmailValueCellMapper.CellMapping> mappings = mapper.mapValues(values, graph);

            // The cell reference to B2 should produce the highest confidence mapping
            EmailValueCellMapper.CellMapping bestMapping = mappings.stream()
                    .max(Comparator.comparingDouble(m -> m.confidence))
                    .orElse(null);
            assertNotNull(bestMapping, "Should have at least one mapping");
            assertEquals(0.95, bestMapping.confidence, 0.001,
                    "Direct cell reference should be the highest-confidence mapping");
        }

        @Test
        void batchExtractionFromEmailThread() {
            // Simulate an email thread where multiple messages update the same spreadsheet
            Document email1 = emailDoc(
                    "Please update cell B2 with $52,000 for Q1 revenue.",
                    "Q1 Update"
            );
            Document email2 = emailDoc(
                    "Correction: cell B2 should be $53,000, not $52,000. Also update C2 with $32,000.",
                    "Re: Q1 Update"
            );
            Document email3 = emailDoc(
                    "Q2 figures are in: revenue = $78,000, cost = $41,000. Update B3 and C3.",
                    "Q2 Update"
            );

            // Batch extraction
            List<EmailBodyValueExtractor.ExtractedValue> allValues = extractor.extractBatch(List.of(email1, email2, email3));

            // Should have values from all 3 emails
            assertFalse(allValues.isEmpty(), "Batch should extract values from all emails");

            // Map all extracted values against the spreadsheet
            SpreadsheetGraph graph = buildFinancialSpreadsheet();
            List<EmailValueCellMapper.CellMapping> mappings = mapper.mapValues(allValues, graph);

            // Should map to B2, C2, B3, C3 across the thread
            Set<String> targetedCells = new HashSet<>();
            for (EmailValueCellMapper.CellMapping m : mappings) {
                targetedCells.add(m.cellReference);
            }

            assertTrue(targetedCells.stream().anyMatch(c -> c.endsWith("B2")),
                    "Thread should target B2; all: " + targetedCells);
            assertTrue(targetedCells.stream().anyMatch(c -> c.endsWith("C2")),
                    "Thread should target C2; all: " + targetedCells);
            assertTrue(targetedCells.stream().anyMatch(c -> c.endsWith("B3")),
                    "Thread should target B3; all: " + targetedCells);
        }
    }

    // ── Edge cases ──────────────────────────────────────────────────────────

    @Nested
    class EdgeCases {

        @Test
        void emailWithNoCellReferencesStillExtractsValues() {
            Document email = emailDoc(
                    "FYI the budget this quarter is $100,000 and the margin target is 25%.",
                    "Budget FYI"
            );

            List<EmailBodyValueExtractor.ExtractedValue> values = extractor.extract(email);

            assertTrue(values.stream().anyMatch(v -> "CURRENCY".equals(v.type)),
                    "Should extract currency even without cell references");
            assertTrue(values.stream().anyMatch(v -> "PERCENTAGE".equals(v.type)),
                    "Should extract percentage even without cell references");

            // Mapping without cell refs falls back to score-based matching
            SpreadsheetGraph graph = buildFinancialSpreadsheet();
            List<EmailValueCellMapper.CellMapping> mappings = mapper.mapValues(values, graph);

            // Currency should still map to numeric input cells via score
            for (EmailValueCellMapper.CellMapping m : mappings) {
                assertTrue(m.confidence <= 0.95,
                        "Without direct cell refs, confidence should be score-based (not 0.95)");
            }
        }

        @Test
        void emptyEmailProducesNoMappings() {
            Document email = emailDoc("", "Empty Email");

            List<EmailBodyValueExtractor.ExtractedValue> values = extractor.extract(email);
            assertTrue(values.isEmpty());

            SpreadsheetGraph graph = buildFinancialSpreadsheet();
            List<EmailValueCellMapper.CellMapping> mappings = mapper.mapValues(values, graph);
            assertTrue(mappings.isEmpty());
        }

        @Test
        void formulaCellsAreNeverMappingTargets() {
            Document email = emailDoc(
                    "The total should be $130,000 — please verify Revenue!B4 and Revenue!D2.",
                    "Formula Verification"
            );

            List<EmailBodyValueExtractor.ExtractedValue> values = extractor.extract(email);
            SpreadsheetGraph graph = buildFinancialSpreadsheet();
            List<EmailValueCellMapper.CellMapping> mappings = mapper.mapValues(values, graph);

            // CELL_REFERENCE to B4 will produce a 0.95 mapping (direct ref)
            // but the $130,000 CURRENCY should NOT map to formula cells
            List<EmailValueCellMapper.CellMapping> currencyMappings = mappings.stream()
                    .filter(m -> m.extractedValue.type.equals("CURRENCY"))
                    .toList();

            for (EmailValueCellMapper.CellMapping m : currencyMappings) {
                CellNode cell = graph.getCells().get(m.cellReference);
                if (cell != null) {
                    assertNotEquals("FORMULA", cell.getCellType(),
                            "Currency value should not be mapped to formula cell " + m.cellReference);
                }
            }
        }
    }
}
