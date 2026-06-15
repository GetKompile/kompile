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

package ai.kompile.loader.excel;

import ai.kompile.core.graphrag.format.GraphExtractionSchema;
import ai.kompile.core.graphrag.format.GraphExtractionValidator;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.loader.excel.graph.CellNode;
import ai.kompile.loader.excel.graph.ExcelFormulaGraphExtractor;
import ai.kompile.loader.excel.graph.SpreadsheetGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for Excel document loading through the full graph extraction pipeline:
 * Excel file -> ExcelLoaderImpl -> Documents with formula_graph -> SpreadsheetGraph.toGraph()
 * -> Graph model -> JSON serialization/deserialization -> GraphExtractionValidator.
 *
 * These tests verify the complete chain from raw Excel data to validated knowledge graph entities.
 */
class ExcelEndToEndGraphExtractionTest {

    @TempDir
    Path tempDir;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Tests the full pipeline: load Excel -> extract formula graph document ->
     * deserialize the graph JSON -> verify Graph entities and relationships.
     */
    @Test
    void endToEndFormulaGraphExtraction() throws Exception {
        File excelFile = createFinancialWorkbook("financial.xlsx");

        ExcelLoaderImpl loader = new ExcelLoaderImpl();
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl(excelFile.getAbsolutePath())
                .build();

        List<Document> documents = loader.load(descriptor);

        // 1. Verify formula_graph document was produced
        Document graphDoc = documents.stream()
                .filter(d -> "formula_graph".equals(d.getMetadata().get("content_type")))
                .findFirst()
                .orElse(null);
        assertNotNull(graphDoc, "Loader must produce a formula_graph document");

        // 2. Deserialize the formulaGraph JSON metadata
        String graphJson = (String) graphDoc.getMetadata().get("formulaGraph");
        assertNotNull(graphJson, "formula_graph document must contain formulaGraph JSON");

        Graph graph = MAPPER.readValue(graphJson, Graph.class);
        assertNotNull(graph);
        assertNotNull(graph.getEntities());
        assertNotNull(graph.getRelationships());
        assertFalse(graph.getEntities().isEmpty(), "Graph must contain entities");
        assertFalse(graph.getRelationships().isEmpty(), "Graph must contain relationships");

        // 3. Verify entity types
        Set<String> entityTypes = graph.getEntities().stream()
                .map(Entity::getType)
                .collect(Collectors.toSet());
        assertTrue(entityTypes.contains("SHEET"), "Graph must have SHEET entities");
        assertTrue(entityTypes.contains("FORMULA_CELL"), "Graph must have FORMULA_CELL entities");

        // 4. Verify relationship types
        Set<String> relTypes = graph.getRelationships().stream()
                .map(Relationship::getType)
                .collect(Collectors.toSet());
        assertTrue(relTypes.contains("CONTAINS"), "Graph must have CONTAINS relationships");
        // Should have some dependency type (DEPENDS_ON or RANGE_INPUT)
        assertTrue(relTypes.contains("DEPENDS_ON") || relTypes.contains("RANGE_INPUT"),
                "Graph must have dependency relationships");

        // 5. Verify all relationships reference valid entity IDs
        Set<String> entityIds = graph.getEntities().stream()
                .map(Entity::getId)
                .collect(Collectors.toSet());
        for (Relationship rel : graph.getRelationships()) {
            assertTrue(entityIds.contains(rel.getSource()),
                    "Relationship source " + rel.getSource() + " must reference a valid entity");
            assertTrue(entityIds.contains(rel.getTarget()),
                    "Relationship target " + rel.getTarget() + " must reference a valid entity");
        }

        // 6. Verify SHEET entities are composite
        for (Entity entity : graph.getEntities()) {
            if ("SHEET".equals(entity.getType())) {
                assertTrue(Boolean.TRUE.equals(entity.getMetadata().get("isComposite")), "SHEET entities must be composite");
            }
        }

        // 7. Verify confidence scores
        for (Entity entity : graph.getEntities()) {
            assertNotNull(entity.getConfidence());
            assertEquals(1.0, entity.getConfidence(), "Structural entities should have confidence 1.0");
        }
        for (Relationship rel : graph.getRelationships()) {
            assertNotNull(rel.getConfidence());
            assertEquals(1.0, rel.getConfidence());
            assertEquals("EXTRACTED", rel.getMetadata().get("provenance"));
        }
    }

    /**
     * Tests round-trip: Graph -> ExtractionResult JSON -> Graph,
     * verifying no data loss in serialization.
     */
    @Test
    void graphRoundTripSerialization() throws Exception {
        File excelFile = createFinancialWorkbook("roundtrip.xlsx");

        try (XSSFWorkbook workbook = new XSSFWorkbook(excelFile)) {
            ExcelFormulaGraphExtractor extractor = new ExcelFormulaGraphExtractor(workbook);
            SpreadsheetGraph spreadsheetGraph = extractor.extract(workbook, "roundtrip.xlsx");
            Graph originalGraph = spreadsheetGraph.toGraph();

            // Convert to ExtractionResult (the wire format)
            GraphExtractionSchema.ExtractionResult extractionResult =
                    GraphExtractionValidator.fromGraph(originalGraph, "excel-loader");

            // Validate the extraction result
            GraphExtractionValidator.ValidationResult validation =
                    GraphExtractionValidator.validate(extractionResult);
            assertTrue(validation.valid(),
                    "ExtractionResult should be valid. Errors: " + validation.errors());

            // Serialize to JSON
            String json = GraphExtractionValidator.toJson(extractionResult);
            assertNotNull(json);
            assertTrue(json.contains("entities"));
            assertTrue(json.contains("relations"));

            // Deserialize back
            GraphExtractionSchema.ExtractionResult deserialized =
                    GraphExtractionValidator.fromJson(json);
            assertNotNull(deserialized);

            // Convert back to Graph
            Graph roundTrippedGraph = GraphExtractionValidator.toGraph(deserialized);

            // Verify entity count preserved
            assertEquals(originalGraph.getEntities().size(),
                    roundTrippedGraph.getEntities().size(),
                    "Entity count must survive round-trip");

            // Verify relationship count preserved
            assertEquals(originalGraph.getRelationships().size(),
                    roundTrippedGraph.getRelationships().size(),
                    "Relationship count must survive round-trip");

            // Verify entity IDs preserved
            Set<String> originalIds = originalGraph.getEntities().stream()
                    .map(Entity::getId).collect(Collectors.toSet());
            Set<String> roundTrippedIds = roundTrippedGraph.getEntities().stream()
                    .map(Entity::getId).collect(Collectors.toSet());
            assertEquals(originalIds, roundTrippedIds, "Entity IDs must survive round-trip");
        }
    }

    /**
     * Tests cross-sheet formula graph extraction produces valid inter-sheet
     * relationships and entity structure.
     */
    @Test
    void crossSheetGraphExtractionEndToEnd() throws Exception {
        File excelFile = createCrossSheetWorkbook("crosssheet.xlsx");

        ExcelLoaderImpl loader = new ExcelLoaderImpl();
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl(excelFile.getAbsolutePath())
                .build();

        List<Document> documents = loader.load(descriptor);

        // Should have table docs for each sheet
        long tableDocs = documents.stream()
                .filter(d -> "table".equals(d.getMetadata().get("content_type")))
                .count();
        assertEquals(3, tableDocs, "Should produce one table document per sheet");

        // Get formula graph
        Document graphDoc = documents.stream()
                .filter(d -> "formula_graph".equals(d.getMetadata().get("content_type")))
                .findFirst()
                .orElse(null);
        assertNotNull(graphDoc);

        String graphJson = (String) graphDoc.getMetadata().get("formulaGraph");
        Graph graph = MAPPER.readValue(graphJson, Graph.class);

        // Verify 3 SHEET entities
        List<Entity> sheetEntities = graph.getEntities().stream()
                .filter(e -> "SHEET".equals(e.getType()))
                .collect(Collectors.toList());
        assertEquals(3, sheetEntities.size(), "Should have 3 sheet entities");

        // Verify cross-sheet link relationships between sheet entities
        List<Relationship> crossSheetLinks = graph.getRelationships().stream()
                .filter(r -> "CROSS_SHEET_LINK".equals(r.getType()))
                .collect(Collectors.toList());
        assertFalse(crossSheetLinks.isEmpty(), "Should have cross-sheet link relationships");

        // Cross-sheet dependencies come in as RANGE_INPUT (for SUM(Sales!B2:B3)) with
        // the crossSheet metadata flag set to true, rather than CROSS_SHEET_DEPENDS_ON
        // (which is only used for single-cell cross-sheet refs like Sheet1!A1).
        List<Relationship> crossSheetDeps = graph.getRelationships().stream()
                .filter(r -> r.getMetadata() != null
                        && Boolean.TRUE.equals(r.getMetadata().get("crossSheet")))
                .collect(Collectors.toList());
        assertFalse(crossSheetDeps.isEmpty(), "Should have cross-sheet dependency relationships");

        // Verify cross-sheet metadata
        assertTrue((int) graphDoc.getMetadata().get("crossSheetDependencies") > 0);

        // Verify all cross-sheet relationships reference entities from different sheets
        for (Relationship rel : crossSheetDeps) {
            Entity source = graph.getEntities().stream()
                    .filter(e -> e.getId().equals(rel.getSource())).findFirst().orElse(null);
            Entity target = graph.getEntities().stream()
                    .filter(e -> e.getId().equals(rel.getTarget())).findFirst().orElse(null);
            assertNotNull(source, "Cross-sheet dep source entity must exist");
            assertNotNull(target, "Cross-sheet dep target entity must exist");

            if (source.getMetadata() != null && target.getMetadata() != null) {
                String sourceSheet = (String) source.getMetadata().get("sheetName");
                String targetSheet = (String) target.getMetadata().get("sheetName");
                if (sourceSheet != null && targetSheet != null) {
                    assertNotEquals(sourceSheet, targetSheet,
                            "Cross-sheet dependency must span different sheets");
                }
            }
        }
    }

    /**
     * Tests that table documents carry proper markdown content and metadata
     * for downstream chunking/embedding.
     */
    @Test
    void tableDocumentContentForEmbedding() throws Exception {
        File excelFile = createFinancialWorkbook("tables.xlsx");

        ExcelLoaderImpl loader = new ExcelLoaderImpl();
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl(excelFile.getAbsolutePath())
                .build();

        List<Document> documents = loader.load(descriptor);

        List<Document> tableDocs = documents.stream()
                .filter(d -> "table".equals(d.getMetadata().get("content_type")))
                .collect(Collectors.toList());
        assertFalse(tableDocs.isEmpty());

        for (Document tableDoc : tableDocs) {
            // Verify markdown table content
            String fullContent = (String) tableDoc.getMetadata().get("full_table_content");
            assertNotNull(fullContent, "Table doc must have full_table_content");
            assertTrue(fullContent.contains("|"), "Full content should be markdown table");
            assertTrue(fullContent.contains("---|"), "Markdown table should have separator");

            // Verify required metadata for RAG pipeline
            assertNotNull(tableDoc.getMetadata().get("table_row_count"));
            assertNotNull(tableDoc.getMetadata().get("table_column_count"));
            assertNotNull(tableDoc.getMetadata().get("table_headers"));
            assertEquals("excel-poi", tableDoc.getMetadata().get("table_extraction_method"));
            assertNotNull(tableDoc.getMetadata().get("sheetName"));
            assertNotNull(tableDoc.getMetadata().get("sheetIndex"));
        }
    }

    /**
     * Tests named range extraction and its representation in the graph model.
     * Named ranges that refer to cell ranges (e.g., Finance!B2:B3) are stored in
     * the SpreadsheetGraph's namedRanges map, and the formulas that use them
     * produce RANGE_REFERENCE dependencies to the individual cells in the range.
     * Only single-cell named ranges produce NAMED_RANGE entity types.
     */
    @Test
    void namedRangeGraphExtraction() throws Exception {
        File excelFile = createWorkbookWithNamedRanges("named-ranges.xlsx");

        try (XSSFWorkbook workbook = new XSSFWorkbook(excelFile)) {
            ExcelFormulaGraphExtractor extractor = new ExcelFormulaGraphExtractor(workbook);
            SpreadsheetGraph spreadsheetGraph = extractor.extract(workbook, "named-ranges.xlsx");

            // Verify named ranges were captured in the spreadsheet graph
            assertFalse(spreadsheetGraph.getNamedRanges().isEmpty());
            assertTrue(spreadsheetGraph.getNamedRanges().containsKey("Revenue"));
            assertTrue(spreadsheetGraph.getNamedRanges().containsKey("Costs"));
            assertEquals("Finance!B2:B3", spreadsheetGraph.getNamedRanges().get("Revenue"));
            assertEquals("Finance!B4:B5", spreadsheetGraph.getNamedRanges().get("Costs"));

            // Verify formula cells exist for SUM(Revenue), SUM(Costs), B6-B7
            assertEquals(3, spreadsheetGraph.getFormulaCells().size(),
                    "Should have 3 formula cells");

            // Verify dependencies: SUM(Revenue) should depend on B2 and B3
            List<CellNode> sumRevDeps = spreadsheetGraph.getDependenciesOf("Finance!B6");
            assertFalse(sumRevDeps.isEmpty(),
                    "SUM(Revenue) should have dependencies on individual cells in the range");

            // Convert to core graph and validate
            Graph graph = spreadsheetGraph.toGraph();
            assertNotNull(graph);
            assertFalse(graph.getEntities().isEmpty());
            assertFalse(graph.getRelationships().isEmpty());

            // Verify FORMULA_CELL entities exist
            List<Entity> formulaCells = graph.getEntities().stream()
                    .filter(e -> "FORMULA_CELL".equals(e.getType()))
                    .collect(Collectors.toList());
            assertEquals(3, formulaCells.size(), "Should have 3 FORMULA_CELL entities");

            // Verify at least some entities have formula metadata referencing the named ranges
            boolean hasRevenueFormula = formulaCells.stream()
                    .anyMatch(e -> e.getDescription() != null &&
                            e.getDescription().contains("SUM(Revenue)"));
            assertTrue(hasRevenueFormula, "Should have a formula referencing the Revenue named range");

            // Round-trip through ExtractionResult
            GraphExtractionSchema.ExtractionResult result =
                    GraphExtractionValidator.fromGraph(graph, "excel-loader");
            assertTrue(GraphExtractionValidator.validate(result).valid());
        }
    }

    /**
     * Tests that a workbook with no formulas produces table documents but no formula_graph.
     */
    @Test
    void dataOnlyWorkbookProducesNoFormulaGraph() throws Exception {
        File excelFile = createDataOnlyWorkbook("data-only.xlsx");

        ExcelLoaderImpl loader = new ExcelLoaderImpl();
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl(excelFile.getAbsolutePath())
                .build();

        List<Document> documents = loader.load(descriptor);

        // Should have table documents
        assertTrue(documents.stream()
                .anyMatch(d -> "table".equals(d.getMetadata().get("content_type"))));

        // Should NOT have formula_graph document
        assertFalse(documents.stream()
                        .anyMatch(d -> "formula_graph".equals(d.getMetadata().get("content_type"))),
                "Data-only workbook should not produce formula_graph document");
    }

    /**
     * Tests graph summary text quality for embedding/search.
     */
    @Test
    void graphSummaryContainsSearchableContent() throws Exception {
        File excelFile = createFinancialWorkbook("summary.xlsx");

        try (XSSFWorkbook workbook = new XSSFWorkbook(excelFile)) {
            ExcelFormulaGraphExtractor extractor = new ExcelFormulaGraphExtractor(workbook);
            SpreadsheetGraph graph = extractor.extract(workbook, "summary.xlsx");

            String summary = graph.toSummary();

            // Summary should be useful for keyword search
            assertNotNull(summary);
            assertTrue(summary.contains("summary.xlsx"), "Summary should reference workbook name");
            assertTrue(summary.contains("Formula cells:"), "Summary should count formula cells");
            assertTrue(summary.contains("Dependencies:"), "Summary should count dependencies");
            assertTrue(summary.contains("Sheets:"), "Summary should count sheets");

            // Summary should contain actual formula text
            assertTrue(summary.contains("SUM") || summary.contains("*"),
                    "Summary should contain formula expressions");
        }
    }

    /**
     * Tests complex chained formula dependencies are fully resolved.
     */
    @Test
    void chainedFormulaDependenciesFullyResolved() throws Exception {
        File excelFile = createChainedFormulaWorkbook("chained.xlsx");

        try (XSSFWorkbook workbook = new XSSFWorkbook(excelFile)) {
            ExcelFormulaGraphExtractor extractor = new ExcelFormulaGraphExtractor(workbook);
            SpreadsheetGraph spreadsheetGraph = extractor.extract(workbook, "chained.xlsx");
            Graph graph = spreadsheetGraph.toGraph();

            // Verify the chain A1 -> B1 -> C1 -> D1 is captured
            List<Entity> formulaCells = graph.getEntities().stream()
                    .filter(e -> "FORMULA_CELL".equals(e.getType()))
                    .collect(Collectors.toList());
            assertEquals(3, formulaCells.size(), "Should have 3 formula cells (B1, C1, D1)");

            // Verify DEPENDS_ON relationships form a chain
            List<Relationship> dependsOn = graph.getRelationships().stream()
                    .filter(r -> "DEPENDS_ON".equals(r.getType()))
                    .collect(Collectors.toList());
            assertEquals(3, dependsOn.size(),
                    "Should have 3 DEPENDS_ON relationships (B1->A1, C1->B1, D1->C1)");

            // Validate the full graph
            GraphExtractionSchema.ExtractionResult result =
                    GraphExtractionValidator.fromGraph(graph, "excel-loader");
            GraphExtractionValidator.ValidationResult validation =
                    GraphExtractionValidator.validate(result);
            assertTrue(validation.valid(),
                    "Chained formula graph should be valid. Errors: " + validation.errors());
        }
    }

    // --- Helper methods ---

    private File createFinancialWorkbook(String name) throws Exception {
        File file = tempDir.resolve(name).toFile();
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Revenue");

            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Quarter");
            header.createCell(1).setCellValue("Sales");
            header.createCell(2).setCellValue("Costs");
            header.createCell(3).setCellValue("Profit");

            Row q1 = sheet.createRow(1);
            q1.createCell(0).setCellValue("Q1");
            q1.createCell(1).setCellValue(50000);
            q1.createCell(2).setCellValue(30000);
            q1.createCell(3).setCellFormula("B2-C2");

            Row q2 = sheet.createRow(2);
            q2.createCell(0).setCellValue("Q2");
            q2.createCell(1).setCellValue(75000);
            q2.createCell(2).setCellValue(40000);
            q2.createCell(3).setCellFormula("B3-C3");

            Row total = sheet.createRow(3);
            total.createCell(0).setCellValue("Total");
            total.createCell(1).setCellFormula("SUM(B2:B3)");
            total.createCell(2).setCellFormula("SUM(C2:C3)");
            total.createCell(3).setCellFormula("SUM(D2:D3)");

            try (FileOutputStream fos = new FileOutputStream(file)) {
                workbook.write(fos);
            }
        }
        return file;
    }

    private File createCrossSheetWorkbook(String name) throws Exception {
        File file = tempDir.resolve(name).toFile();
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            // Sheet 1: Raw Sales Data
            Sheet salesSheet = workbook.createSheet("Sales");
            Row sh = salesSheet.createRow(0);
            sh.createCell(0).setCellValue("Region");
            sh.createCell(1).setCellValue("Revenue");
            salesSheet.createRow(1).createCell(0).setCellValue("North");
            salesSheet.getRow(1).createCell(1).setCellValue(100000);
            salesSheet.createRow(2).createCell(0).setCellValue("South");
            salesSheet.getRow(2).createCell(1).setCellValue(80000);

            // Sheet 2: Costs
            Sheet costsSheet = workbook.createSheet("Costs");
            Row ch = costsSheet.createRow(0);
            ch.createCell(0).setCellValue("Category");
            ch.createCell(1).setCellValue("Amount");
            costsSheet.createRow(1).createCell(0).setCellValue("Operations");
            costsSheet.getRow(1).createCell(1).setCellValue(50000);
            costsSheet.createRow(2).createCell(0).setCellValue("Marketing");
            costsSheet.getRow(2).createCell(1).setCellValue(30000);

            // Sheet 3: Summary with cross-sheet formulas
            Sheet summarySheet = workbook.createSheet("Summary");
            Row smh = summarySheet.createRow(0);
            smh.createCell(0).setCellValue("Metric");
            smh.createCell(1).setCellValue("Value");

            Row totalRev = summarySheet.createRow(1);
            totalRev.createCell(0).setCellValue("Total Revenue");
            totalRev.createCell(1).setCellFormula("SUM(Sales!B2:B3)");

            Row totalCost = summarySheet.createRow(2);
            totalCost.createCell(0).setCellValue("Total Costs");
            totalCost.createCell(1).setCellFormula("SUM(Costs!B2:B3)");

            Row netProfit = summarySheet.createRow(3);
            netProfit.createCell(0).setCellValue("Net Profit");
            netProfit.createCell(1).setCellFormula("B2-B3");

            try (FileOutputStream fos = new FileOutputStream(file)) {
                workbook.write(fos);
            }
        }
        return file;
    }

    private File createWorkbookWithNamedRanges(String name) throws Exception {
        File file = tempDir.resolve(name).toFile();
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Finance");

            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Item");
            header.createCell(1).setCellValue("Amount");

            sheet.createRow(1).createCell(0).setCellValue("Revenue Q1");
            sheet.getRow(1).createCell(1).setCellValue(50000);
            sheet.createRow(2).createCell(0).setCellValue("Revenue Q2");
            sheet.getRow(2).createCell(1).setCellValue(70000);
            sheet.createRow(3).createCell(0).setCellValue("Cost Q1");
            sheet.getRow(3).createCell(1).setCellValue(20000);
            sheet.createRow(4).createCell(0).setCellValue("Cost Q2");
            sheet.getRow(4).createCell(1).setCellValue(30000);

            // Named ranges
            Name revenue = workbook.createName();
            revenue.setNameName("Revenue");
            revenue.setRefersToFormula("Finance!B2:B3");

            Name costs = workbook.createName();
            costs.setNameName("Costs");
            costs.setRefersToFormula("Finance!B4:B5");

            // Formulas using named ranges
            Row totalRow = sheet.createRow(5);
            totalRow.createCell(0).setCellValue("Total Revenue");
            totalRow.createCell(1).setCellFormula("SUM(Revenue)");

            Row costTotalRow = sheet.createRow(6);
            costTotalRow.createCell(0).setCellValue("Total Costs");
            costTotalRow.createCell(1).setCellFormula("SUM(Costs)");

            Row profitRow = sheet.createRow(7);
            profitRow.createCell(0).setCellValue("Profit");
            profitRow.createCell(1).setCellFormula("B6-B7");

            try (FileOutputStream fos = new FileOutputStream(file)) {
                workbook.write(fos);
            }
        }
        return file;
    }

    private File createDataOnlyWorkbook(String name) throws Exception {
        File file = tempDir.resolve(name).toFile();
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Data");

            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Name");
            header.createCell(1).setCellValue("Age");
            header.createCell(2).setCellValue("City");

            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("Alice");
            r1.createCell(1).setCellValue(30);
            r1.createCell(2).setCellValue("New York");

            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("Bob");
            r2.createCell(1).setCellValue(25);
            r2.createCell(2).setCellValue("London");

            try (FileOutputStream fos = new FileOutputStream(file)) {
                workbook.write(fos);
            }
        }
        return file;
    }

    private File createChainedFormulaWorkbook(String name) throws Exception {
        File file = tempDir.resolve(name).toFile();
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Chain");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(100);        // A1 = 100
            row.createCell(1).setCellFormula("A1*2");    // B1 = A1 * 2
            row.createCell(2).setCellFormula("B1+10");   // C1 = B1 + 10
            row.createCell(3).setCellFormula("C1/2");    // D1 = C1 / 2

            try (FileOutputStream fos = new FileOutputStream(file)) {
                workbook.write(fos);
            }
        }
        return file;
    }
}
