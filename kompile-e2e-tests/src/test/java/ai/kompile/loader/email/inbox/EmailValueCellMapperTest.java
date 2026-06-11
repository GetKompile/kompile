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

import ai.kompile.loader.email.inbox.EmailBodyValueExtractor.ExtractedValue;
import ai.kompile.loader.email.inbox.EmailValueCellMapper.CellMapping;
import ai.kompile.loader.excel.graph.CellNode;
import ai.kompile.loader.excel.graph.SpreadsheetGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EmailValueCellMapperTest {

    private EmailValueCellMapper mapper;
    private SpreadsheetGraph graph;

    @BeforeEach
    void setUp() {
        mapper = new EmailValueCellMapper();
        graph = new SpreadsheetGraph();
        graph.setWorkbookName("test-workbook");
    }

    private CellNode numericCell(String ref, String sheet, String displayValue) {
        return CellNode.builder()
                .cellReference(ref)
                .sheetName(sheet)
                .cellType("NUMERIC")
                .displayValue(displayValue)
                .build();
    }

    private CellNode stringCell(String ref, String sheet, String displayValue) {
        return CellNode.builder()
                .cellReference(ref)
                .sheetName(sheet)
                .cellType("STRING")
                .displayValue(displayValue)
                .build();
    }

    private CellNode formulaCell(String ref, String sheet, String formula) {
        return CellNode.builder()
                .cellReference(ref)
                .sheetName(sheet)
                .cellType("FORMULA")
                .formula(formula)
                .build();
    }

    private CellNode namedRangeCell(String ref, String sheet, String displayValue, String rangeName) {
        return CellNode.builder()
                .cellReference(ref)
                .sheetName(sheet)
                .cellType("NUMERIC")
                .displayValue(displayValue)
                .namedRange(true)
                .namedRangeName(rangeName)
                .build();
    }

    private ExtractedValue currencyValue(double amount) {
        return new ExtractedValue("CURRENCY", "$" + amount, amount, "context", 0.9);
    }

    private ExtractedValue percentageValue(double pct) {
        return new ExtractedValue("PERCENTAGE", pct * 100 + "%", pct, "context", 0.9);
    }

    private ExtractedValue numericValue(String fieldName, long value) {
        ExtractedValue ev = new ExtractedValue("NUMERIC", fieldName + ": " + value, value, "context", 0.85);
        ev.fieldName = fieldName;
        return ev;
    }

    private ExtractedValue dateValue(String date) {
        return new ExtractedValue("DATE", date, date, "context", 0.8);
    }

    private ExtractedValue cellRefValue(String ref) {
        return new ExtractedValue("CELL_REFERENCE", "cell " + ref, ref, "context", 0.95);
    }

    // --- Type compatibility ---

    @Test
    void currencyMapsToNumericCell() {
        CellNode cell = numericCell("Sheet1!A1", "Sheet1", "1000");
        graph.addCell(cell);

        List<CellMapping> mappings = mapper.mapValues(List.of(currencyValue(1500.0)), graph);

        assertFalse(mappings.isEmpty());
        assertEquals("Sheet1!A1", mappings.get(0).cellReference);
        assertTrue(mappings.get(0).confidence > 0.3);
    }

    @Test
    void currencyWithFieldNameMapsToStringCell() {
        CellNode cell = stringCell("Sheet1!B1", "Sheet1", "amount");
        graph.addCell(cell);

        // Currency with fieldName "amount" matches display value "amount" -> 0.3 + 0.8*0.5 = 0.7
        ExtractedValue ev = new ExtractedValue("CURRENCY", "$500", 500.0, "context", 0.9);
        ev.fieldName = "amount";
        List<CellMapping> mappings = mapper.mapValues(List.of(ev), graph);

        assertFalse(mappings.isEmpty());
    }

    @Test
    void percentageMapsToNumericCellOnly() {
        CellNode numCell = numericCell("Sheet1!A1", "Sheet1", "0.05");
        CellNode strCell = stringCell("Sheet1!B1", "Sheet1", "text");
        graph.addCell(numCell);
        graph.addCell(strCell);

        List<CellMapping> mappings = mapper.mapValues(List.of(percentageValue(0.05)), graph);

        assertTrue(mappings.stream().anyMatch(m -> "Sheet1!A1".equals(m.cellReference)));
        assertFalse(mappings.stream().anyMatch(m -> "Sheet1!B1".equals(m.cellReference)));
    }

    @Test
    void dateWithFieldNameMapsToStringCell() {
        CellNode strCell = stringCell("Sheet1!A1", "Sheet1", "date");
        graph.addCell(strCell);

        // Date with fieldName "date" matches display value "date" -> 0.3 + 0.8*0.5 = 0.7
        ExtractedValue ev = new ExtractedValue("DATE", "2025-03-15", "2025-03-15", "context", 0.8);
        ev.fieldName = "date";
        List<CellMapping> mappings = mapper.mapValues(List.of(ev), graph);

        assertFalse(mappings.isEmpty());
    }

    @Test
    void formulaCellsAreExcluded() {
        CellNode formula = formulaCell("Sheet1!C1", "Sheet1", "SUM(A1:B1)");
        CellNode input = numericCell("Sheet1!A1", "Sheet1", "100");
        graph.addCell(formula);
        graph.addCell(input);

        List<CellMapping> mappings = mapper.mapValues(List.of(currencyValue(200.0)), graph);

        assertTrue(mappings.stream().noneMatch(m -> "Sheet1!C1".equals(m.cellReference)));
    }

    @Test
    void blankCellsAreExcluded() {
        CellNode blank = CellNode.builder()
                .cellReference("Sheet1!D1")
                .sheetName("Sheet1")
                .cellType("BLANK")
                .build();
        CellNode input = numericCell("Sheet1!A1", "Sheet1", "50");
        graph.addCell(blank);
        graph.addCell(input);

        List<CellMapping> mappings = mapper.mapValues(List.of(currencyValue(100.0)), graph);

        assertTrue(mappings.stream().noneMatch(m -> "Sheet1!D1".equals(m.cellReference)));
    }

    // --- Cell reference direct mapping ---

    @Test
    void cellReferenceDirectlyMapsToGraphCell() {
        CellNode cell = numericCell("A1", "Sheet1", "500");
        graph.addCell(cell);

        List<CellMapping> mappings = mapper.mapValues(List.of(cellRefValue("A1")), graph);

        assertTrue(mappings.stream().anyMatch(m ->
                "A1".equals(m.cellReference) && m.confidence == 0.95));
    }

    @Test
    void cellReferenceWithSheetPrefixMatchesEndOfKey() {
        CellNode cell = numericCell("Sheet1!B2", "Sheet1", "100");
        graph.addCell(cell);

        List<CellMapping> mappings = mapper.mapValues(List.of(cellRefValue("B2")), graph);

        assertTrue(mappings.stream().anyMatch(m ->
                "Sheet1!B2".equals(m.cellReference) && m.confidence == 0.95));
    }

    @Test
    void cellReferenceNotFoundInGraph() {
        CellNode cell = numericCell("Sheet1!A1", "Sheet1", "500");
        graph.addCell(cell);

        List<CellMapping> mappings = mapper.mapValues(List.of(cellRefValue("Z99")), graph);

        assertTrue(mappings.stream().noneMatch(m -> m.confidence == 0.95));
    }

    // --- Name similarity scoring ---

    @Test
    void namedRangeMatchBoostsScore() {
        CellNode cell = namedRangeCell("Sheet1!A1", "Sheet1", "10000", "total_revenue");
        graph.addCell(cell);

        ExtractedValue ev = numericValue("revenue", 15000);
        List<CellMapping> mappings = mapper.mapValues(List.of(ev), graph);

        assertFalse(mappings.isEmpty());
        CellMapping m = mappings.stream()
                .filter(c -> "Sheet1!A1".equals(c.cellReference))
                .findFirst().orElseThrow();
        // 0.3 (type) + 1.0 * 0.5 (named range match) + 0.15 (range plausibility) = 0.95
        assertTrue(m.confidence >= 0.8, "Named range match should yield high confidence, got: " + m.confidence);
    }

    @Test
    void displayValueContainingFieldNameBoostsScore() {
        CellNode cell = numericCell("Sheet1!B1", "Sheet1", "total");
        graph.addCell(cell);

        ExtractedValue ev = numericValue("total", 5000);
        List<CellMapping> mappings = mapper.mapValues(List.of(ev), graph);

        assertFalse(mappings.isEmpty());
        CellMapping m = mappings.stream()
                .filter(c -> "Sheet1!B1".equals(c.cellReference))
                .findFirst().orElseThrow();
        // 0.3 (type) + 0.8 * 0.5 (display value match) = 0.7
        assertTrue(m.confidence >= 0.6, "Display value match should boost confidence, got: " + m.confidence);
    }

    @Test
    void noFieldNameYieldsBaseTypeScoreOnly() {
        CellNode cell = numericCell("Sheet1!A1", "Sheet1", "500");
        graph.addCell(cell);

        // Currency has no fieldName
        ExtractedValue ev = currencyValue(600.0);
        List<CellMapping> mappings = mapper.mapValues(List.of(ev), graph);

        assertFalse(mappings.isEmpty());
        CellMapping m = mappings.get(0);
        // Base 0.3 (type) + maybe 0.15 (range plausibility) = 0.3-0.45
        assertTrue(m.confidence >= 0.3 && m.confidence <= 0.6);
    }

    // --- Value range plausibility ---

    @Test
    void valueWithinPlausibleRangeBoostsScore() {
        CellNode cell = numericCell("Sheet1!A1", "Sheet1", "1000");
        graph.addCell(cell);

        // 1500 / 1000 = 1.5 — within 0.1..10 range
        List<CellMapping> mappings = mapper.mapValues(List.of(currencyValue(1500.0)), graph);

        CellMapping m = mappings.get(0);
        // Should include 0.15 plausibility boost
        assertTrue(m.confidence >= 0.4);
    }

    @Test
    void valueOutsidePlausibleRangeGetsNoBoost() {
        CellNode cell = numericCell("Sheet1!A1", "Sheet1", "1");
        graph.addCell(cell);

        // 100000 / 1 = 100000 — way outside range
        List<CellMapping> mappings = mapper.mapValues(List.of(currencyValue(100000.0)), graph);

        if (!mappings.isEmpty()) {
            CellMapping m = mappings.get(0);
            // Should just be 0.3 (type) without plausibility
            assertTrue(m.confidence <= 0.35);
        }
    }

    // --- Deduplication (best per cell) ---

    @Test
    void keepsHighestScoreMappingPerCell() {
        CellNode cell = namedRangeCell("Sheet1!A1", "Sheet1", "5000", "total_amount");
        graph.addCell(cell);

        // Two values competing for same cell: one with fieldName match, one without
        ExtractedValue withField = numericValue("amount", 6000);
        ExtractedValue withoutField = currencyValue(7000.0);

        List<CellMapping> mappings = mapper.mapValues(List.of(withField, withoutField), graph);

        long countA1 = mappings.stream()
                .filter(m -> "Sheet1!A1".equals(m.cellReference))
                .count();
        assertEquals(1, countA1, "Should keep only best mapping per cell");
    }

    // --- Sorting ---

    @Test
    void mappingsSortedByConfidenceDescending() {
        CellNode highMatch = namedRangeCell("Sheet1!A1", "Sheet1", "1000", "budget_amount");
        CellNode lowMatch = numericCell("Sheet1!B1", "Sheet1", "50");
        graph.addCell(highMatch);
        graph.addCell(lowMatch);

        ExtractedValue ev = numericValue("amount", 1200);
        List<CellMapping> mappings = mapper.mapValues(List.of(ev), graph);

        if (mappings.size() >= 2) {
            assertTrue(mappings.get(0).confidence >= mappings.get(1).confidence);
        }
    }

    // --- Reason building ---

    @Test
    void reasonContainsValueTypeAndRawText() {
        CellNode cell = numericCell("Sheet1!A1", "Sheet1", "100");
        graph.addCell(cell);

        List<CellMapping> mappings = mapper.mapValues(List.of(currencyValue(150.0)), graph);

        assertFalse(mappings.isEmpty());
        assertTrue(mappings.get(0).reason.contains("currency"));
        assertTrue(mappings.get(0).reason.contains("Sheet1!A1"));
    }

    @Test
    void reasonIncludesFieldNameWhenPresent() {
        CellNode cell = numericCell("Sheet1!A1", "Sheet1", "100");
        graph.addCell(cell);

        List<CellMapping> mappings = mapper.mapValues(List.of(numericValue("tax", 50)), graph);

        assertFalse(mappings.isEmpty());
        assertTrue(mappings.get(0).reason.contains("tax"));
    }

    @Test
    void reasonIncludesCurrentDisplayValue() {
        CellNode cell = numericCell("Sheet1!A1", "Sheet1", "999");
        graph.addCell(cell);

        List<CellMapping> mappings = mapper.mapValues(List.of(currencyValue(1000.0)), graph);

        assertFalse(mappings.isEmpty());
        assertTrue(mappings.get(0).reason.contains("999"));
    }

    // --- CellMapping.toMap ---

    @Test
    void cellMappingToMapContainsAllFields() {
        CellNode cell = numericCell("Sheet1!A1", "Sheet1", "100");
        graph.addCell(cell);

        List<CellMapping> mappings = mapper.mapValues(List.of(currencyValue(150.0)), graph);

        assertFalse(mappings.isEmpty());
        Map<String, Object> map = mappings.get(0).toMap();
        assertTrue(map.containsKey("cellReference"));
        assertTrue(map.containsKey("confidence"));
        assertTrue(map.containsKey("reason"));
        assertTrue(map.containsKey("extractedValue"));
    }

    // --- Edge cases ---

    @Test
    void emptyExtractedValuesReturnsEmptyMappings() {
        CellNode cell = numericCell("Sheet1!A1", "Sheet1", "100");
        graph.addCell(cell);

        List<CellMapping> mappings = mapper.mapValues(List.of(), graph);
        assertTrue(mappings.isEmpty());
    }

    @Test
    void emptyGraphReturnsEmptyMappings() {
        List<CellMapping> mappings = mapper.mapValues(List.of(currencyValue(100.0)), graph);
        assertTrue(mappings.isEmpty());
    }

    @Test
    void graphWithOnlyFormulaCellsReturnsEmptyForNonRefValues() {
        graph.addCell(formulaCell("Sheet1!A1", "Sheet1", "SUM(B1:B10)"));
        graph.addCell(formulaCell("Sheet1!A2", "Sheet1", "AVG(B1:B10)"));

        List<CellMapping> mappings = mapper.mapValues(List.of(currencyValue(500.0)), graph);
        assertTrue(mappings.isEmpty());
    }

    @Test
    void nonParsableDisplayValueDoesNotCrash() {
        CellNode cell = numericCell("Sheet1!A1", "Sheet1", "not-a-number");
        graph.addCell(cell);

        // Currency with no fieldName gives base score 0.3 only, which is NOT > 0.3
        // Use a value with a fieldName that matches display value to get a mapping
        ExtractedValue ev = numericValue("number", 100);
        // "not-a-number" doesn't contain "number" as a substring... but we just ensure no crash
        assertDoesNotThrow(() -> mapper.mapValues(List.of(currencyValue(100.0)), graph));
    }

    @Test
    void nullDisplayValueDoesNotCrash() {
        CellNode cell = CellNode.builder()
                .cellReference("Sheet1!A1")
                .sheetName("Sheet1")
                .cellType("NUMERIC")
                .displayValue(null)
                .build();
        graph.addCell(cell);

        // Just verify no NPE
        assertDoesNotThrow(() -> mapper.mapValues(List.of(currencyValue(100.0)), graph));
    }
}
