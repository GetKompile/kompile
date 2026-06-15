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

import ai.kompile.core.loaders.DocumentSourceDescriptor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExcelLoaderImplTest {

    @TempDir
    Path tempDir;

    @Test
    void testSupportsExcelExtensions() {
        ExcelLoaderImpl loader = new ExcelLoaderImpl();

        assertTrue(loader.supports(descriptor("report.xlsx")));
        assertTrue(loader.supports(descriptor("report.xls")));
        assertTrue(loader.supports(descriptor("report.xlsm")));
        assertTrue(loader.supports(descriptor("report.ods")));
        assertFalse(loader.supports(descriptor("report.doc")));
        assertFalse(loader.supports(descriptor("report.pdf")));
        assertFalse(loader.supports(descriptor("report.csv")));
    }

    @Test
    void testSupportsFileSourceTypeOnly() {
        ExcelLoaderImpl loader = new ExcelLoaderImpl();

        assertFalse(loader.supports(DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.URL)
                .pathOrUrl("http://example.com/file.xlsx")
                .build()));
    }

    @Test
    void testLoadSimpleWorkbook() throws Exception {
        File excelFile = createTestWorkbook("simple.xlsx", false);

        ExcelLoaderImpl loader = new ExcelLoaderImpl();
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl(excelFile.getAbsolutePath())
                .build();

        List<Document> documents = loader.load(descriptor);
        assertFalse(documents.isEmpty());

        // Should have at least one table document (sheets are indexed as tables)
        Document sheetDoc = documents.stream()
                .filter(d -> "table".equals(d.getMetadata().get("content_type")))
                .findFirst()
                .orElse(null);
        assertNotNull(sheetDoc, "Sheet documents should have content_type=table");
        assertEquals("Sales", sheetDoc.getMetadata().get("sheetName"));
        assertEquals(0, sheetDoc.getMetadata().get("sheetIndex"));
        assertEquals("Excel Formula Graph Loader", sheetDoc.getMetadata().get("loader"));

        // Verify standard table metadata keys for RagToolImpl compatibility
        assertNotNull(sheetDoc.getMetadata().get("full_table_content"), "Must have full_table_content for RAG");
        assertNotNull(sheetDoc.getMetadata().get("table_row_count"));
        assertNotNull(sheetDoc.getMetadata().get("table_column_count"));
        assertNotNull(sheetDoc.getMetadata().get("table_headers"));
        assertEquals("excel-poi", sheetDoc.getMetadata().get("table_extraction_method"));

        // Verify source attribution metadata
        assertNotNull(sheetDoc.getMetadata().get("source_path"));
        assertNotNull(sheetDoc.getMetadata().get("source_filename"));
        assertEquals("FILE", sheetDoc.getMetadata().get("source_type"));
        assertEquals("xlsx", sheetDoc.getMetadata().get("file_extension"));
    }

    @Test
    void testLoadWorkbookWithFormulas() throws Exception {
        File excelFile = createTestWorkbook("formulas.xlsx", true);

        ExcelLoaderImpl loader = new ExcelLoaderImpl();
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl(excelFile.getAbsolutePath())
                .build();

        List<Document> documents = loader.load(descriptor);
        assertFalse(documents.isEmpty());

        // Should have a formula graph document
        Document graphDoc = documents.stream()
                .filter(d -> "formula_graph".equals(d.getMetadata().get("content_type")))
                .findFirst()
                .orElse(null);
        assertNotNull(graphDoc, "Should produce a formula graph document");
        assertTrue(graphDoc.getText().contains("Formula cells:"));
        assertNotNull(graphDoc.getMetadata().get("formulaGraph"));
        assertTrue((int) graphDoc.getMetadata().get("totalFormulas") > 0);
        assertTrue((int) graphDoc.getMetadata().get("totalDependencies") > 0);
        assertTrue((int) graphDoc.getMetadata().get("entityCount") > 0);
        assertTrue((int) graphDoc.getMetadata().get("relationshipCount") > 0);

        // Sheet doc should have formula metadata and be indexed as a table
        Document sheetDoc = documents.stream()
                .filter(d -> "table".equals(d.getMetadata().get("content_type")))
                .findFirst()
                .orElse(null);
        assertNotNull(sheetDoc, "Sheet documents should have content_type=table");
        assertNotNull(sheetDoc.getMetadata().get("formulas"));
        assertTrue((int) sheetDoc.getMetadata().get("formulaCount") > 0);

        // Graph doc should have source attribution
        assertNotNull(graphDoc.getMetadata().get("source_path"));
        assertNotNull(graphDoc.getMetadata().get("source_filename"));
    }

    @Test
    void testLoadMultiSheetWorkbook() throws Exception {
        File excelFile = createMultiSheetWorkbook("multi.xlsx");

        ExcelLoaderImpl loader = new ExcelLoaderImpl();
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl(excelFile.getAbsolutePath())
                .build();

        List<Document> documents = loader.load(descriptor);

        // Should have documents for both sheets (as tables) plus a graph doc
        long sheetDocs = documents.stream()
                .filter(d -> "table".equals(d.getMetadata().get("content_type")))
                .count();
        assertEquals(2, sheetDocs, "Should produce one table document per sheet");

        // Graph doc should report cross-sheet dependencies
        Document graphDoc = documents.stream()
                .filter(d -> "formula_graph".equals(d.getMetadata().get("content_type")))
                .findFirst()
                .orElse(null);
        assertNotNull(graphDoc);
        assertTrue((int) graphDoc.getMetadata().get("crossSheetDependencies") > 0);
    }

    @Test
    void testLoadNonExistentFile() {
        ExcelLoaderImpl loader = new ExcelLoaderImpl();
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl("/nonexistent/file.xlsx")
                .build();

        assertThrows(IllegalArgumentException.class, () -> loader.load(descriptor));
    }

    @Test
    void testMarkdownTableContent() throws Exception {
        File excelFile = createTestWorkbook("markdown.xlsx", false);

        ExcelLoaderImpl loader = new ExcelLoaderImpl();
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl(excelFile.getAbsolutePath())
                .build();

        List<Document> documents = loader.load(descriptor);
        Document sheetDoc = documents.stream()
                .filter(d -> "table".equals(d.getMetadata().get("content_type")))
                .findFirst()
                .orElse(null);
        assertNotNull(sheetDoc, "Sheet documents should have content_type=table");

        String markdown = (String) sheetDoc.getMetadata().get("full_table_content");
        assertNotNull(markdown);
        // Markdown table should have pipes
        assertTrue(markdown.contains("|"));
        // Should have separator row
        assertTrue(markdown.contains("---|"));
    }

    @Test
    void testProgressCallback() throws Exception {
        File excelFile = createTestWorkbook("progress.xlsx", true);

        ExcelLoaderImpl loader = new ExcelLoaderImpl();
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl(excelFile.getAbsolutePath())
                .build();

        List<String> phases = new java.util.ArrayList<>();
        loader.load(descriptor, progress -> phases.add(progress.phase()));

        assertFalse(phases.isEmpty());
        assertTrue(phases.contains("Opening"));
        assertTrue(phases.contains("Complete"));
    }

    @Test
    void testDataQualityFlagExtraction() throws Exception {
        File excelFile = createWorkbookWithDqFlags("dq_flags.xlsx");

        ExcelLoaderImpl loader = new ExcelLoaderImpl();
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl(excelFile.getAbsolutePath())
                .build();

        List<Document> documents = loader.load(descriptor);

        // Find the sheet document with DQ flags
        Document sheetDoc = documents.stream()
                .filter(d -> "table".equals(d.getMetadata().get("content_type")))
                .findFirst()
                .orElse(null);
        assertNotNull(sheetDoc, "Should produce a table document");

        // DQ flags should be in metadata
        Object dqFlags = sheetDoc.getMetadata().get("dq_flags");
        assertNotNull(dqFlags, "dq_flags metadata should be populated");
        assertTrue(dqFlags.toString().contains("FAIL"), "DQ flags should contain FAIL");

        // DQ flags should also be in the document text body (not just metadata)
        String text = sheetDoc.getText();
        assertTrue(text.contains("Data Quality") || text.contains("FAIL"),
                "Document body should include DQ signal information for RAG indexing");
    }

    @Test
    void testSheetDocumentsProduceTableGraph() throws Exception {
        File excelFile = createTestWorkbook("tablegraph.xlsx", false);

        ExcelLoaderImpl loader = new ExcelLoaderImpl();
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl(excelFile.getAbsolutePath())
                .build();

        List<Document> documents = loader.load(descriptor);

        Document sheetDoc = documents.stream()
                .filter(d -> "table".equals(d.getMetadata().get("content_type")))
                .findFirst()
                .orElse(null);
        assertNotNull(sheetDoc, "Should produce a table document");

        // Verify tableGraph metadata is present for cell-level graph persistence
        Object tableGraph = sheetDoc.getMetadata().get("tableGraph");
        assertNotNull(tableGraph, "Sheet documents must produce tableGraph metadata for cell-level KG persistence");
        assertTrue(tableGraph instanceof String, "tableGraph must be a JSON string");
        String graphJson = (String) tableGraph;
        assertTrue(graphJson.contains("\"entities\""), "tableGraph JSON must contain entities");
        assertTrue(graphJson.contains("\"relationships\""), "tableGraph JSON must contain relationships");
        assertTrue(graphJson.contains("TABLE"), "tableGraph must contain TABLE entity type");
        assertTrue(graphJson.contains("CELL") || graphJson.contains("HEADER_CELL"),
                "tableGraph must contain CELL or HEADER_CELL entity types");
        assertTrue(graphJson.contains("CONTAINS"), "tableGraph must contain CONTAINS relationships");
    }

    // --- Helper methods ---

    private DocumentSourceDescriptor descriptor(String filename) {
        return DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl("/tmp/" + filename)
                .build();
    }

    private File createTestWorkbook(String name, boolean withFormulas) throws Exception {
        File file = tempDir.resolve(name).toFile();
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sales");

            // Header row
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Product");
            header.createCell(1).setCellValue("Quantity");
            header.createCell(2).setCellValue("Price");
            if (withFormulas) {
                header.createCell(3).setCellValue("Total");
            }

            // Data rows
            Row row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue("Widget A");
            row1.createCell(1).setCellValue(10);
            row1.createCell(2).setCellValue(5.99);
            if (withFormulas) {
                row1.createCell(3).setCellFormula("B2*C2");
            }

            Row row2 = sheet.createRow(2);
            row2.createCell(0).setCellValue("Widget B");
            row2.createCell(1).setCellValue(25);
            row2.createCell(2).setCellValue(3.49);
            if (withFormulas) {
                row2.createCell(3).setCellFormula("B3*C3");
            }

            if (withFormulas) {
                Row totalRow = sheet.createRow(3);
                totalRow.createCell(0).setCellValue("Grand Total");
                totalRow.createCell(3).setCellFormula("SUM(D2:D3)");
            }

            try (FileOutputStream fos = new FileOutputStream(file)) {
                workbook.write(fos);
            }
        }
        return file;
    }

    private File createWorkbookWithDqFlags(String name) throws Exception {
        File file = tempDir.resolve(name).toFile();
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Validation");

            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Item");
            header.createCell(1).setCellValue("Amount");
            header.createCell(2).setCellValue("Status");

            Row row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue("Revenue Check");
            row1.createCell(1).setCellValue(50000);
            row1.createCell(2).setCellValue("PASS");

            Row row2 = sheet.createRow(2);
            row2.createCell(0).setCellValue("Margin Threshold");
            row2.createCell(1).setCellValue(-500);
            row2.createCell(2).setCellValue("FAIL");

            Row row3 = sheet.createRow(3);
            row3.createCell(0).setCellValue("Coverage Threshold");
            row3.createCell(1).setCellValue(0.65);
            row3.createCell(2).setCellValue("REVIEW");

            try (FileOutputStream fos = new FileOutputStream(file)) {
                workbook.write(fos);
            }
        }
        return file;
    }

    private File createMultiSheetWorkbook(String name) throws Exception {
        File file = tempDir.resolve(name).toFile();
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            // Sheet 1: Raw data
            Sheet dataSheet = workbook.createSheet("RawData");
            Row h = dataSheet.createRow(0);
            h.createCell(0).setCellValue("Month");
            h.createCell(1).setCellValue("Revenue");

            Row r1 = dataSheet.createRow(1);
            r1.createCell(0).setCellValue("Jan");
            r1.createCell(1).setCellValue(10000);

            Row r2 = dataSheet.createRow(2);
            r2.createCell(0).setCellValue("Feb");
            r2.createCell(1).setCellValue(15000);

            // Sheet 2: Summary with cross-sheet formulas
            Sheet summarySheet = workbook.createSheet("Summary");
            Row sh = summarySheet.createRow(0);
            sh.createCell(0).setCellValue("Total Revenue");
            sh.createCell(1).setCellFormula("SUM(RawData!B2:B3)");

            Row sh2 = summarySheet.createRow(1);
            sh2.createCell(0).setCellValue("Average");
            sh2.createCell(1).setCellFormula("AVERAGE(RawData!B2:B3)");

            try (FileOutputStream fos = new FileOutputStream(file)) {
                workbook.write(fos);
            }
        }
        return file;
    }
}
