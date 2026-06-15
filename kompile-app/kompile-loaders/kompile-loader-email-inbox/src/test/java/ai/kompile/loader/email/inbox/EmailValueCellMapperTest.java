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
import ai.kompile.loader.excel.graph.SpreadsheetGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EmailValueCellMapperTest {

    private EmailValueCellMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new EmailValueCellMapper();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a minimal ExtractedValue without going through the full extractor.
     */
    private EmailBodyValueExtractor.ExtractedValue makeValue(
            String type, String rawText, Object parsedValue, String fieldName, double confidence) {
        EmailBodyValueExtractor.ExtractedValue v =
                new EmailBodyValueExtractor.ExtractedValue(type, rawText, parsedValue, rawText, confidence);
        v.fieldName = fieldName;
        return v;
    }

    /**
     * Builds a CellNode with the given reference, type, displayValue, and optional named-range name.
     */
    private CellNode cell(String ref, String cellType, String displayValue) {
        return CellNode.builder()
                .cellReference(ref)
                .sheetName("Sheet1")
                .column(ref.replaceAll("[^A-Za-z]", ""))
                .row(Integer.parseInt(ref.replaceAll("[^0-9]", "")))
                .cellType(cellType)
                .displayValue(displayValue)
                .namedRange(false)
                .build();
    }

    private CellNode namedCell(String ref, String cellType, String displayValue, String rangeName) {
        return CellNode.builder()
                .cellReference(ref)
                .sheetName("Sheet1")
                .column(ref.replaceAll("[^A-Za-z]", ""))
                .row(Integer.parseInt(ref.replaceAll("[^0-9]", "")))
                .cellType(cellType)
                .displayValue(displayValue)
                .namedRange(true)
                .namedRangeName(rangeName)
                .build();
    }

    private SpreadsheetGraph graphWith(CellNode... nodes) {
        SpreadsheetGraph g = new SpreadsheetGraph();
        for (CellNode n : nodes) {
            g.addCell(n);
        }
        return g;
    }

    // ── Numeric type compatibility ────────────────────────────────────────────

    @Test
    void mapValues_mapsNumericValueToNumericCell() {
        // A CURRENCY extracted value should map to a NUMERIC cell
        EmailBodyValueExtractor.ExtractedValue currencyVal =
                makeValue("CURRENCY", "$75000", 75000.0, null, 0.9);

        CellNode numCell = cell("B2", "NUMERIC", "70000");
        SpreadsheetGraph graph = graphWith(numCell);

        List<EmailValueCellMapper.CellMapping> mappings =
                mapper.mapValues(List.of(currencyVal), graph);

        assertFalse(mappings.isEmpty(),
                "CURRENCY value should produce at least one mapping to a NUMERIC cell");

        EmailValueCellMapper.CellMapping m = mappings.get(0);
        assertEquals("B2", m.cellReference,
                "Mapping should target cell B2");
        assertTrue(m.confidence > 0.3,
                "Mapping confidence should be > 0.3, got: " + m.confidence);
    }

    // ── Type mismatch rejection ───────────────────────────────────────────────

    @Test
    void mapValues_rejectsTypeMismatch() {
        // A DATE value is not compatible with a NUMERIC cell when…
        // Actually DATE is compatible with NUMERIC per isTypeCompatible.
        // Test PERCENTAGE → STRING cell is NOT supported per implementation:
        // PERCENTAGE only maps to NUMERIC cells.
        EmailBodyValueExtractor.ExtractedValue pctVal =
                makeValue("PERCENTAGE", "15%", 0.15, null, 0.9);

        // STRING-type cell: PERCENTAGE is incompatible (only NUMERIC allowed)
        CellNode stringCell = cell("A1", "STRING", "some text");
        SpreadsheetGraph graph = graphWith(stringCell);

        List<EmailValueCellMapper.CellMapping> mappings =
                mapper.mapValues(List.of(pctVal), graph);

        assertTrue(mappings.isEmpty(),
                "PERCENTAGE value should not map to a STRING cell");
    }

    // ── Name similarity preference ────────────────────────────────────────────

    @Test
    void mapValues_prefersNameSimilarity() {
        // "revenue" field name should prefer a named-range cell called "revenue"
        // over a plain numeric cell called "cost"
        EmailBodyValueExtractor.ExtractedValue revenueVal =
                makeValue("NUMERIC", "1000000", 1000000L, "revenue", 0.85);

        CellNode revCell  = namedCell("B5", "NUMERIC", "950000", "revenue");
        CellNode costCell = namedCell("C5", "NUMERIC", "200000", "cost");
        SpreadsheetGraph graph = graphWith(revCell, costCell);

        List<EmailValueCellMapper.CellMapping> mappings =
                mapper.mapValues(List.of(revenueVal), graph);

        // There should be at least one mapping
        assertFalse(mappings.isEmpty(), "Expected at least one mapping");

        // The mapping with the highest confidence should be for the "revenue" named range
        EmailValueCellMapper.CellMapping best = mappings.stream()
                .max(java.util.Comparator.comparingDouble(m -> m.confidence))
                .orElseThrow();

        assertEquals("B5", best.cellReference,
                "Best mapping should target 'revenue' cell B5, not 'cost' cell; " +
                        "all mappings: " + mappings.stream()
                        .map(m -> m.cellReference + "@" + m.confidence).toList());
    }

    // ── Deduplication: best confidence wins ──────────────────────────────────

    @Test
    void mapValues_deduplicatesToBestMapping() {
        // Two extracted values compete for the same single cell.
        // The mapper's computeMappingScore determines confidence:
        //   score = 0.3 (type match) + nameScore*0.5 + 0.15 (range plausibility)
        // "matchingName" has fieldName="budget" → exact named-range match → nameScore=1.0 → score = 0.95
        // "noName" has fieldName=null → nameScore=0 → score = 0.3 + 0.15 = 0.45
        // After deduplication, the mapping with fieldName="budget" (higher computed score) should win.
        CellNode budgetCell = namedCell("D3", "NUMERIC", "100000", "budget");

        EmailBodyValueExtractor.ExtractedValue matchingName =
                makeValue("NUMERIC", "99000", 99000L, "budget", 0.9);
        EmailBodyValueExtractor.ExtractedValue noName =
                makeValue("NUMERIC", "50000", 50000L, null, 0.5);

        SpreadsheetGraph graph = graphWith(budgetCell);

        List<EmailValueCellMapper.CellMapping> mappings =
                mapper.mapValues(List.of(noName, matchingName), graph);

        // After deduplicate-by-cell-reference, cell D3 should appear exactly once
        long d3Count = mappings.stream()
                .filter(m -> "D3".equals(m.cellReference))
                .count();
        assertEquals(1, d3Count,
                "Cell D3 should appear exactly once after deduplication");

        // The surviving mapping must be the named-match one (higher computed score)
        EmailValueCellMapper.CellMapping survivor = mappings.stream()
                .filter(m -> "D3".equals(m.cellReference))
                .findFirst().orElseThrow();
        assertEquals("99000", survivor.extractedValue.rawText,
                "The higher-scored mapping (fieldName='budget', rawText='99000') should survive deduplication; " +
                        "actual rawText: " + survivor.extractedValue.rawText +
                        ", confidence: " + survivor.confidence);
    }

    // ── Empty inputs ──────────────────────────────────────────────────────────

    @Test
    void mapValues_emptyExtractedValues_returnsEmpty() {
        CellNode c = cell("A1", "NUMERIC", "100");
        SpreadsheetGraph graph = graphWith(c);

        List<EmailValueCellMapper.CellMapping> mappings =
                mapper.mapValues(List.of(), graph);

        assertTrue(mappings.isEmpty(),
                "No extracted values should yield no mappings");
    }

    // ── CELL_REFERENCE direct mapping ───────────────────────────────────────

    @Test
    void mapValues_cellReferenceDirectMatch() {
        // A CELL_REFERENCE value pointing to a cell that exists in the graph
        EmailBodyValueExtractor.ExtractedValue cellRef =
                makeValue("CELL_REFERENCE", "cell B2", "B2", null, 0.95);

        CellNode targetCell = cell("B2", "NUMERIC", "100");
        SpreadsheetGraph graph = graphWith(targetCell);

        List<EmailValueCellMapper.CellMapping> mappings =
                mapper.mapValues(List.of(cellRef), graph);

        assertFalse(mappings.isEmpty(),
                "CELL_REFERENCE should create a direct mapping when cell exists");
        EmailValueCellMapper.CellMapping m = mappings.get(0);
        assertEquals("B2", m.cellReference);
        assertEquals(0.95, m.confidence, 0.001,
                "Direct cell reference mapping should have 0.95 confidence");
        assertTrue(m.reason.contains("Direct cell reference"),
                "Reason should indicate direct cell reference");
    }

    @Test
    void mapValues_cellReferenceNotInGraph_noMapping() {
        // A CELL_REFERENCE to a cell not in the graph
        EmailBodyValueExtractor.ExtractedValue cellRef =
                makeValue("CELL_REFERENCE", "cell Z99", "Z99", null, 0.95);

        CellNode otherCell = cell("A1", "NUMERIC", "100");
        SpreadsheetGraph graph = graphWith(otherCell);

        List<EmailValueCellMapper.CellMapping> mappings =
                mapper.mapValues(List.of(cellRef), graph);

        assertTrue(mappings.isEmpty(),
                "CELL_REFERENCE to a non-existent cell should produce no mapping");
    }

    @Test
    void mapValues_cellReferenceWithSheetPrefix() {
        // A CELL_REFERENCE like "Sheet1!C3" — should match via the endsWith fallback
        EmailBodyValueExtractor.ExtractedValue cellRef =
                makeValue("CELL_REFERENCE", "Sheet1!C3", "Sheet1!C3", null, 0.95);

        // The graph stores cell as "Sheet1!C3"
        CellNode targetCell = CellNode.builder()
                .cellReference("Sheet1!C3")
                .sheetName("Sheet1")
                .column("C")
                .row(3)
                .cellType("NUMERIC")
                .displayValue("500")
                .namedRange(false)
                .build();
        SpreadsheetGraph graph = graphWith(targetCell);

        List<EmailValueCellMapper.CellMapping> mappings =
                mapper.mapValues(List.of(cellRef), graph);

        assertFalse(mappings.isEmpty(),
                "Sheet-prefixed cell reference should match via exact match");
        assertEquals("Sheet1!C3", mappings.get(0).cellReference);
    }

    // ── DATE compatibility ────────────────────────────────────────────────────

    @Test
    void mapValues_dateCompatibleWithStringCell() {
        // DATE is type-compatible with STRING (score gets 0.3 from type match).
        // With a fieldName that matches a named range, score crosses the >0.3 threshold.
        EmailBodyValueExtractor.ExtractedValue dateVal =
                makeValue("DATE", "2024-06-15", "2024-06-15", "deadline", 0.8);

        CellNode stringCell = namedCell("A1", "STRING", "2024-01-01", "deadline");
        SpreadsheetGraph graph = graphWith(stringCell);

        List<EmailValueCellMapper.CellMapping> mappings =
                mapper.mapValues(List.of(dateVal), graph);

        assertFalse(mappings.isEmpty(),
                "DATE value with matching fieldName should map to STRING cell");
    }

    @Test
    void mapValues_dateCompatibleWithNumericCell() {
        // DATE is type-compatible with NUMERIC. With a fieldName match it crosses threshold.
        EmailBodyValueExtractor.ExtractedValue dateVal =
                makeValue("DATE", "2024-06-15", "2024-06-15", "duedate", 0.8);

        CellNode numericCell = namedCell("A1", "NUMERIC", "45458", "duedate");
        SpreadsheetGraph graph = graphWith(numericCell);

        List<EmailValueCellMapper.CellMapping> mappings =
                mapper.mapValues(List.of(dateVal), graph);

        assertFalse(mappings.isEmpty(),
                "DATE value with matching fieldName should map to NUMERIC cell");
    }

    @Test
    void mapValues_dateIncompatibleWithPercentageOnly() {
        // DATE without a fieldName against a plain NUMERIC cell: score = 0.3 (type match only).
        // The threshold is >0.3 (strict), so this should produce no mapping.
        EmailBodyValueExtractor.ExtractedValue dateVal =
                makeValue("DATE", "2024-06-15", "2024-06-15", null, 0.8);

        CellNode numericCell = cell("A1", "NUMERIC", "45458");
        SpreadsheetGraph graph = graphWith(numericCell);

        List<EmailValueCellMapper.CellMapping> mappings =
                mapper.mapValues(List.of(dateVal), graph);

        // Score = 0.3 (type match) + 0 (no name) + 0 (parsedValue is String, not Number)
        // 0.3 is NOT > 0.3, so no mapping is created
        assertTrue(mappings.isEmpty(),
                "DATE without fieldName should not cross the >0.3 threshold for NUMERIC cell");
    }

    // ── CellMapping.toMap ────────────────────────────────────────────────────

    @Test
    void cellMapping_toMap_containsAllFields() {
        EmailBodyValueExtractor.ExtractedValue v =
                makeValue("CURRENCY", "$1000", 1000.0, "budget", 0.9);
        v.emailSubject = "Q4 Review";

        EmailValueCellMapper.CellMapping m =
                new EmailValueCellMapper.CellMapping(v, "B5", "currency → cell B5", 0.85);

        Map<String, Object> map = m.toMap();

        assertEquals("B5", map.get("cellReference"));
        assertEquals(0.85, map.get("confidence"));
        assertEquals("currency → cell B5", map.get("reason"));
        assertTrue(map.get("extractedValue") instanceof Map,
                "extractedValue should be a nested map from toMap()");
    }

    // ── No matching cells ────────────────────────────────────────────────────

    @Test
    void mapValues_noMatchingCells_returnsEmpty() {
        // A PERCENTAGE value with no NUMERIC cells to match against
        EmailBodyValueExtractor.ExtractedValue pctVal =
                makeValue("PERCENTAGE", "10%", 0.10, null, 0.9);

        // Only FORMULA and BLANK cells — both excluded as non-input cells by the mapper
        CellNode formulaCell = CellNode.builder()
                .cellReference("E1")
                .sheetName("Sheet1")
                .column("E")
                .row(1)
                .cellType("FORMULA")
                .formula("SUM(A1:A10)")
                .displayValue("500")
                .namedRange(false)
                .build();
        CellNode blankCell = CellNode.builder()
                .cellReference("F1")
                .sheetName("Sheet1")
                .column("F")
                .row(1)
                .cellType("BLANK")
                .displayValue("")
                .namedRange(false)
                .build();

        SpreadsheetGraph graph = graphWith(formulaCell, blankCell);

        List<EmailValueCellMapper.CellMapping> mappings =
                mapper.mapValues(List.of(pctVal), graph);

        assertTrue(mappings.isEmpty(),
                "Should return no mappings when no input cells are available");
    }
}
