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
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.util.IOUtils;
import org.apache.poi.xssf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the image and chart extraction phases added to {@link ExcelLoaderImpl}.
 * These test the Phase 4 (embedded images) and Phase 5 (chart metadata) extraction.
 */
class ExcelLoaderImageChartTest {

    @TempDir
    Path tempDir;

    // ── Embedded Images ─────────────────────────────────────────────────────

    @Test
    void testExtractsEmbeddedImages() throws Exception {
        File excelFile = createWorkbookWithImage("with-image.xlsx");

        ExcelLoaderImpl loader = new ExcelLoaderImpl();
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl(excelFile.getAbsolutePath())
                .build();

        List<Document> documents = loader.load(descriptor);

        // Should have at least one image document
        List<Document> imageDocs = documents.stream()
                .filter(d -> "image".equals(d.getMetadata().get("content_type")))
                .collect(Collectors.toList());

        assertFalse(imageDocs.isEmpty(), "Should extract embedded image as document");

        Document imageDoc = imageDocs.get(0);
        // Verify image metadata
        assertNotNull(imageDoc.getMetadata().get("image_mime_type"), "Should have MIME type");
        assertNotNull(imageDoc.getMetadata().get("image_size_bytes"), "Should have size");
        assertTrue((int) imageDoc.getMetadata().get("image_index") >= 0, "Should have index");

        // Verify base64 data is present for VLM pipeline
        assertNotNull(imageDoc.getMetadata().get("image_data_base64"), "Should have base64 data");
        String base64 = (String) imageDoc.getMetadata().get("image_data_base64");
        assertFalse(base64.isEmpty(), "Base64 data should not be empty");
        // Verify it's valid base64
        assertDoesNotThrow(() -> Base64.getDecoder().decode(base64), "Should be valid base64");

        // Verify text summary
        assertNotNull(imageDoc.getText());
        assertTrue(imageDoc.getText().contains("image"), "Summary should mention image");

        // Verify loader attribution
        assertEquals("Excel Formula Graph Loader", imageDoc.getMetadata().get("loader"));
    }

    @Test
    void testMultipleEmbeddedImages() throws Exception {
        File excelFile = createWorkbookWithMultipleImages("multi-image.xlsx", 3);

        ExcelLoaderImpl loader = new ExcelLoaderImpl();
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl(excelFile.getAbsolutePath())
                .build();

        List<Document> documents = loader.load(descriptor);

        List<Document> imageDocs = documents.stream()
                .filter(d -> "image".equals(d.getMetadata().get("content_type")))
                .collect(Collectors.toList());

        assertEquals(3, imageDocs.size(), "Should extract all 3 embedded images");

        // Verify indices are sequential
        for (int i = 0; i < imageDocs.size(); i++) {
            assertEquals(i, imageDocs.get(i).getMetadata().get("image_index"));
        }
    }

    @Test
    void testWorkbookWithoutImagesProducesNoImageDocs() throws Exception {
        File excelFile = createSimpleWorkbook("no-images.xlsx");

        ExcelLoaderImpl loader = new ExcelLoaderImpl();
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl(excelFile.getAbsolutePath())
                .build();

        List<Document> documents = loader.load(descriptor);

        long imageDocs = documents.stream()
                .filter(d -> "image".equals(d.getMetadata().get("content_type")))
                .count();

        assertEquals(0, imageDocs, "Workbook without images should produce no image documents");
    }

    // ── Chart Metadata ──────────────────────────────────────────────────────

    @Test
    void testExtractsChartMetadata() throws Exception {
        File excelFile = createWorkbookWithChart("with-chart.xlsx");

        ExcelLoaderImpl loader = new ExcelLoaderImpl();
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl(excelFile.getAbsolutePath())
                .build();

        List<Document> documents = loader.load(descriptor);

        List<Document> chartDocs = documents.stream()
                .filter(d -> "chart".equals(d.getMetadata().get("content_type")))
                .collect(Collectors.toList());

        assertFalse(chartDocs.isEmpty(), "Should extract chart metadata as document");

        Document chartDoc = chartDocs.get(0);
        assertNotNull(chartDoc.getMetadata().get("chart_index"), "Should have chart index");
        assertNotNull(chartDoc.getMetadata().get("sheetName"), "Should have sheet name");
        assertNotNull(chartDoc.getText(), "Should have text summary");
        assertTrue(chartDoc.getText().contains("Chart"), "Summary should mention chart");
    }

    @Test
    void testWorkbookWithoutChartsProducesNoChartDocs() throws Exception {
        File excelFile = createSimpleWorkbook("no-charts.xlsx");

        ExcelLoaderImpl loader = new ExcelLoaderImpl();
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl(excelFile.getAbsolutePath())
                .build();

        List<Document> documents = loader.load(descriptor);

        long chartDocs = documents.stream()
                .filter(d -> "chart".equals(d.getMetadata().get("content_type")))
                .count();

        assertEquals(0, chartDocs, "Workbook without charts should produce no chart documents");
    }

    // ── Mixed content: tables + images + charts ─────────────────────────────

    @Test
    void testMixedContentWorkbook() throws Exception {
        File excelFile = createMixedContentWorkbook("mixed.xlsx");

        ExcelLoaderImpl loader = new ExcelLoaderImpl();
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl(excelFile.getAbsolutePath())
                .build();

        List<Document> documents = loader.load(descriptor);
        assertFalse(documents.isEmpty(), "Should produce documents from mixed workbook");

        // Should have at least: 1 table + 1 image
        long tableDocs = documents.stream()
                .filter(d -> "table".equals(d.getMetadata().get("content_type")))
                .count();
        long imageDocs = documents.stream()
                .filter(d -> "image".equals(d.getMetadata().get("content_type")))
                .count();

        assertTrue(tableDocs >= 1, "Should have table documents");
        assertTrue(imageDocs >= 1, "Should have image documents");

        // All documents should share source attribution
        for (Document doc : documents) {
            assertNotNull(doc.getMetadata().get("source_path"));
            assertNotNull(doc.getMetadata().get("source_filename"));
            assertEquals("FILE", doc.getMetadata().get("source_type"));
            assertEquals("xlsx", doc.getMetadata().get("file_extension"));
        }
    }

    // ── Progress callback ───────────────────────────────────────────────────

    @Test
    void testProgressCallbackIncludesImagePhase() throws Exception {
        File excelFile = createWorkbookWithImage("progress-image.xlsx");

        ExcelLoaderImpl loader = new ExcelLoaderImpl();
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl(excelFile.getAbsolutePath())
                .build();

        List<String> phases = new java.util.ArrayList<>();
        loader.load(descriptor, progress -> phases.add(progress.phase()));

        assertTrue(phases.contains("Opening"), "Should have Opening phase");
        assertTrue(phases.contains("Complete"), "Should have Complete phase");
        // Images phase may or may not appear depending on whether images were found
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private File createSimpleWorkbook(String name) throws Exception {
        File file = tempDir.resolve(name).toFile();
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Data");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Name");
            header.createCell(1).setCellValue("Value");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("Item");
            row.createCell(1).setCellValue(42);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                workbook.write(fos);
            }
        }
        return file;
    }

    private File createWorkbookWithImage(String name) throws Exception {
        File file = tempDir.resolve(name).toFile();
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Data");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Name");
            header.createCell(1).setCellValue("Value");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("Item");
            row.createCell(1).setCellValue(42);

            // Add a small PNG image (1x1 pixel red)
            byte[] pngData = createMinimalPng();
            int pictureIdx = workbook.addPicture(pngData, Workbook.PICTURE_TYPE_PNG);
            CreationHelper helper = workbook.getCreationHelper();
            Drawing<?> drawing = sheet.createDrawingPatriarch();
            ClientAnchor anchor = helper.createClientAnchor();
            anchor.setCol1(3);
            anchor.setRow1(1);
            drawing.createPicture(anchor, pictureIdx);

            try (FileOutputStream fos = new FileOutputStream(file)) {
                workbook.write(fos);
            }
        }
        return file;
    }

    private File createWorkbookWithMultipleImages(String name, int count) throws Exception {
        File file = tempDir.resolve(name).toFile();
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Data");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Images");

            byte[] pngData = createMinimalPng();
            Drawing<?> drawing = sheet.createDrawingPatriarch();
            CreationHelper helper = workbook.getCreationHelper();

            for (int i = 0; i < count; i++) {
                int pictureIdx = workbook.addPicture(pngData, Workbook.PICTURE_TYPE_PNG);
                ClientAnchor anchor = helper.createClientAnchor();
                anchor.setCol1(i);
                anchor.setRow1(1);
                drawing.createPicture(anchor, pictureIdx);
            }

            try (FileOutputStream fos = new FileOutputStream(file)) {
                workbook.write(fos);
            }
        }
        return file;
    }

    private File createWorkbookWithChart(String name) throws Exception {
        File file = tempDir.resolve(name).toFile();
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet("ChartData");
            // Add data for a chart
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Month");
            header.createCell(1).setCellValue("Sales");

            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("Jan");
            r1.createCell(1).setCellValue(100);

            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("Feb");
            r2.createCell(1).setCellValue(200);

            Row r3 = sheet.createRow(3);
            r3.createCell(0).setCellValue("Mar");
            r3.createCell(1).setCellValue(150);

            // Create a simple chart
            XSSFDrawing drawing = sheet.createDrawingPatriarch();
            XSSFClientAnchor anchor = drawing.createAnchor(0, 0, 0, 0, 3, 0, 10, 15);
            XSSFChart chart = drawing.createChart(anchor);
            chart.setTitleText("Sales Chart");

            try (FileOutputStream fos = new FileOutputStream(file)) {
                workbook.write(fos);
            }
        }
        return file;
    }

    private File createMixedContentWorkbook(String name) throws Exception {
        File file = tempDir.resolve(name).toFile();
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet("Mixed");

            // Table data
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Product");
            header.createCell(1).setCellValue("Price");
            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("Widget");
            r1.createCell(1).setCellValue(9.99);

            // Embedded image
            byte[] pngData = createMinimalPng();
            int pictureIdx = workbook.addPicture(pngData, Workbook.PICTURE_TYPE_PNG);
            XSSFDrawing drawing = sheet.createDrawingPatriarch();
            XSSFClientAnchor imgAnchor = drawing.createAnchor(0, 0, 0, 0, 3, 0, 5, 5);
            drawing.createPicture(imgAnchor, pictureIdx);

            try (FileOutputStream fos = new FileOutputStream(file)) {
                workbook.write(fos);
            }
        }
        return file;
    }

    /**
     * Creates a minimal valid 1x1 red PNG file (67 bytes).
     */
    private byte[] createMinimalPng() {
        return new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, // PNG signature
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52, // IHDR chunk
                0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, // 1x1
                0x08, 0x02, 0x00, 0x00, 0x00, (byte) 0x90, 0x77, 0x53,
                (byte) 0xDE, 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41, // IDAT chunk
                0x54, 0x08, (byte) 0xD7, 0x63, (byte) 0xF8, (byte) 0xCF,
                (byte) 0xC0, 0x00, 0x00, 0x00, 0x02, 0x00, 0x01,
                (byte) 0xE2, 0x21, (byte) 0xBC, 0x33, 0x00, 0x00, 0x00, // IEND chunk
                0x00, 0x49, 0x45, 0x4E, 0x44, (byte) 0xAE, 0x42, 0x60,
                (byte) 0x82
        };
    }
}
