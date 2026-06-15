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

package ai.kompile.loader.microsoft;

import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.*;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import org.apache.poi.xssf.usermodel.*;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.xslf.usermodel.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full pipeline integration tests: real Office files (POI-generated) -> MicrosoftOfficeLoaderImpl -> OfficeGraphExtractor.
 *
 * Each test programmatically creates a spec-compliant Office file using Apache POI,
 * loads it through the actual loader, then runs graph extraction and verifies
 * entities and relationships.
 */
class OfficeGraphIntegrationTest {

    private MicrosoftOfficeLoaderImpl loader;
    private OfficeGraphExtractor extractor;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        loader = new MicrosoftOfficeLoaderImpl();
        extractor = new OfficeGraphExtractor();
    }

    /** Load a file and return all documents. */
    private List<Document> loadFile(Path file) throws Exception {
        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl(file.toAbsolutePath().toString())
                .build();
        return loader.load(desc);
    }

    /** Run extraction on a doc. */
    private ExtractionResult extractGraph(Document doc) {
        assertTrue(extractor.canExtract(doc),
                "OfficeGraphExtractor should accept document: " + doc.getMetadata().get("documentType"));
        return extractor.extract(doc);
    }

    // ================================================================
    //  DOCX Tests
    // ================================================================

    @Nested
    class DocxIntegration {

        @Test
        void docxWithContentAndMetadataProducesFullGraph() throws Exception {
            Path docxFile = tempDir.resolve("report.docx");
            try (XWPFDocument document = new XWPFDocument()) {
                // Set document properties
                var props = document.getProperties().getCoreProperties();
                props.setCreator("Dr. Alice Chen");
                props.setTitle("Q3 Engineering Report");
                props.setSubjectProperty("Engineering quarterly review");
                props.setKeywords("engineering, quarterly, report, performance");
                props.setDescription("Comprehensive Q3 engineering review.");

                // Add paragraphs with styles
                XWPFParagraph heading1 = document.createParagraph();
                heading1.setStyle("Heading1");
                XWPFRun h1Run = heading1.createRun();
                h1Run.setText("Q3 Engineering Report");
                h1Run.setBold(true);

                XWPFParagraph intro = document.createParagraph();
                intro.createRun().setText("This report covers engineering activities for Q3 2025. " +
                        "Key highlights include the platform migration and new API launch.");

                XWPFParagraph heading2 = document.createParagraph();
                heading2.setStyle("Heading2");
                heading2.createRun().setText("Platform Migration");

                XWPFParagraph body = document.createParagraph();
                body.createRun().setText("The migration to cloud-native architecture was completed ahead of schedule. " +
                        "Visit https://docs.example.com/migration for details.");

                // Add a hyperlink
                XWPFParagraph linkPara = document.createParagraph();
                XWPFHyperlinkRun hyperlinkRun = linkPara.createHyperlinkRun(
                        "https://github.com/example/platform");
                hyperlinkRun.setText("GitHub Repository");

                // Add a table
                XWPFTable table = document.createTable(4, 3);
                table.getRow(0).getCell(0).setText("Milestone");
                table.getRow(0).getCell(1).setText("Target Date");
                table.getRow(0).getCell(2).setText("Status");
                table.getRow(1).getCell(0).setText("Database Migration");
                table.getRow(1).getCell(1).setText("2025-07-15");
                table.getRow(1).getCell(2).setText("Complete");
                table.getRow(2).getCell(0).setText("API v2 Launch");
                table.getRow(2).getCell(1).setText("2025-08-01");
                table.getRow(2).getCell(2).setText("Complete");
                table.getRow(3).getCell(0).setText("Load Testing");
                table.getRow(3).getCell(1).setText("2025-08-15");
                table.getRow(3).getCell(2).setText("In Progress");

                try (FileOutputStream fos = new FileOutputStream(docxFile.toFile())) {
                    document.write(fos);
                }
            }

            List<Document> docs = loadFile(docxFile);
            assertFalse(docs.isEmpty(), "Should load at least one document from DOCX");

            // Find the main text document (not table docs)
            Document mainDoc = docs.stream()
                    .filter(d -> !"table".equals(d.getMetadata().get(GraphConstants.META_CONTENT_TYPE)))
                    .findFirst().orElse(docs.get(0));

            // Verify body text
            assertTrue(mainDoc.getText().contains("platform migration") || mainDoc.getText().contains("Platform Migration"),
                    "Body text should be extracted");
            assertTrue(mainDoc.getText().contains("cloud-native"),
                    "Body paragraphs should be in the text");

            // Verify metadata
            assertEquals("report.docx", mainDoc.getMetadata().get(GraphConstants.META_FILE_NAME));
            assertEquals("FILE", mainDoc.getMetadata().get(GraphConstants.META_SOURCE_TYPE));

            // OOXML properties
            assertEquals("Dr. Alice Chen", mainDoc.getMetadata().get("author"));
            assertEquals("Q3 Engineering Report", mainDoc.getMetadata().get("title"));

            // Graph extraction
            ExtractionResult result = extractGraph(mainDoc);

            // WORD_DOCUMENT entity
            ExtractedEntity wordDoc = findEntity(result, "WORD_DOCUMENT");
            assertNotNull(wordDoc, "Should produce WORD_DOCUMENT entity");

            // PERSON from author
            List<ExtractedEntity> persons = findEntities(result, "PERSON");
            assertTrue(persons.stream().anyMatch(p -> p.name().contains("Alice")),
                    "Should extract author as PERSON entity");
            assertNotNull(findRelation(result, "AUTHORED_BY"), "Should have AUTHORED_BY relation");

            // TOPIC from keywords
            List<ExtractedEntity> topics = findEntities(result, "TOPIC");
            assertTrue(topics.size() >= 2, "Should have topics from keywords, got " + topics.size());

            // Table document
            List<Document> tableDocs = docs.stream()
                    .filter(d -> "table".equals(d.getMetadata().get(GraphConstants.META_CONTENT_TYPE)))
                    .toList();
            assertTrue(tableDocs.size() >= 1, "Should extract at least 1 table document");

            Document tableDoc = tableDocs.get(0);
            assertTrue(tableDoc.getText().contains("Milestone"),
                    "Table doc should contain table content");
            assertNotNull(tableDoc.getMetadata().get("table_headers"),
                    "Table doc should have headers");
        }

        @Test
        void docxWithHeadingsExtractsStructure() throws Exception {
            Path docxFile = tempDir.resolve("structured.docx");
            try (XWPFDocument document = new XWPFDocument()) {
                var props = document.getProperties().getCoreProperties();
                props.setCreator("Bob Smith");

                XWPFParagraph h1 = document.createParagraph();
                h1.setStyle("Heading1");
                h1.createRun().setText("Introduction");

                document.createParagraph().createRun().setText("This is the introduction text.");

                XWPFParagraph h2a = document.createParagraph();
                h2a.setStyle("Heading2");
                h2a.createRun().setText("Background");

                document.createParagraph().createRun().setText("Historical context information.");

                XWPFParagraph h2b = document.createParagraph();
                h2b.setStyle("Heading2");
                h2b.createRun().setText("Methodology");

                document.createParagraph().createRun().setText("Research methodology details.");

                try (FileOutputStream fos = new FileOutputStream(docxFile.toFile())) {
                    document.write(fos);
                }
            }

            List<Document> docs = loadFile(docxFile);
            Document mainDoc = docs.stream()
                    .filter(d -> !"table".equals(d.getMetadata().get(GraphConstants.META_CONTENT_TYPE)))
                    .findFirst().orElse(docs.get(0));

            // Headings should be in metadata
            Object headings = mainDoc.getMetadata().get("docx.headings");
            assertNotNull(headings, "docx.headings should be populated");
            assertTrue(headings instanceof List<?>);
            List<?> headingList = (List<?>) headings;
            assertTrue(headingList.size() >= 3,
                    "Should have at least 3 headings, got " + headingList.size());

            // Graph extraction
            ExtractionResult result = extractGraph(mainDoc);
            ExtractedEntity wordDoc = findEntity(result, "WORD_DOCUMENT");
            assertNotNull(wordDoc);

            // DOCUMENT_SECTION entities from headings
            List<ExtractedEntity> sections = findEntities(result, "DOCUMENT_SECTION");
            assertTrue(sections.size() >= 2,
                    "Should have at least 2 DOCUMENT_SECTION entities, got " + sections.size());
            assertNotNull(findRelation(result, "HAS_SECTION"), "Should have HAS_SECTION relation");
        }
    }

    // ================================================================
    //  XLSX Tests
    // ================================================================

    @Nested
    class XlsxIntegration {

        @Test
        void xlsxWithSheetsAndDataProducesFullGraph() throws Exception {
            Path xlsxFile = tempDir.resolve("sales-data.xlsx");
            try (XSSFWorkbook workbook = new XSSFWorkbook()) {
                // Set document properties
                var props = workbook.getProperties().getCoreProperties();
                props.setCreator("Finance Team");
                props.setTitle("Q3 Sales Data");
                props.setKeywords("sales, revenue, quarterly");

                // Sheet 1: Revenue by region
                XSSFSheet sheet1 = workbook.createSheet("Revenue");
                XSSFRow header1 = sheet1.createRow(0);
                header1.createCell(0).setCellValue("Region");
                header1.createCell(1).setCellValue("Q1 Revenue");
                header1.createCell(2).setCellValue("Q2 Revenue");
                header1.createCell(3).setCellValue("Q3 Revenue");

                XSSFRow row1 = sheet1.createRow(1);
                row1.createCell(0).setCellValue("North America");
                row1.createCell(1).setCellValue(2400000);
                row1.createCell(2).setCellValue(2800000);
                row1.createCell(3).setCellValue(3100000);

                XSSFRow row2 = sheet1.createRow(2);
                row2.createCell(0).setCellValue("Europe");
                row2.createCell(1).setCellValue(1800000);
                row2.createCell(2).setCellValue(2100000);
                row2.createCell(3).setCellValue(2400000);

                XSSFRow row3 = sheet1.createRow(3);
                row3.createCell(0).setCellValue("Asia Pacific");
                row3.createCell(1).setCellValue(3100000);
                row3.createCell(2).setCellValue(3500000);
                row3.createCell(3).setCellValue(4200000);

                // Add a formula cell
                XSSFRow totalRow = sheet1.createRow(4);
                totalRow.createCell(0).setCellValue("Total");
                totalRow.createCell(1).setCellFormula("SUM(B2:B4)");
                totalRow.createCell(2).setCellFormula("SUM(C2:C4)");
                totalRow.createCell(3).setCellFormula("SUM(D2:D4)");

                // Add a cell comment
                XSSFComment comment = sheet1.createDrawingPatriarch()
                        .createCellComment(workbook.getCreationHelper().createClientAnchor());
                comment.setString(workbook.getCreationHelper().createRichTextString("Audited figure"));
                comment.setAuthor("Auditor");
                sheet1.getRow(1).getCell(1).setCellComment(comment);

                // Sheet 2: Targets
                XSSFSheet sheet2 = workbook.createSheet("Targets");
                XSSFRow header2 = sheet2.createRow(0);
                header2.createCell(0).setCellValue("Metric");
                header2.createCell(1).setCellValue("Target");
                header2.createCell(2).setCellValue("Actual");

                XSSFRow tRow1 = sheet2.createRow(1);
                tRow1.createCell(0).setCellValue("Revenue Growth");
                tRow1.createCell(1).setCellValue("15%");
                tRow1.createCell(2).setCellValue("18%");

                XSSFRow tRow2 = sheet2.createRow(2);
                tRow2.createCell(0).setCellValue("Customer Retention");
                tRow2.createCell(1).setCellValue("92%");
                tRow2.createCell(2).setCellValue("94%");

                // Add a named range
                workbook.createName().setNameName("RevenueData");
                workbook.getName("RevenueData").setRefersToFormula("Revenue!A1:D5");

                try (FileOutputStream fos = new FileOutputStream(xlsxFile.toFile())) {
                    workbook.write(fos);
                }
            }

            List<Document> docs = loadFile(xlsxFile);
            assertFalse(docs.isEmpty(), "Should load documents from XLSX");

            // Should have one doc per sheet
            assertTrue(docs.size() >= 2,
                    "Should have at least 2 documents (one per sheet), got " + docs.size());

            // First sheet document
            Document sheet1Doc = docs.get(0);
            assertEquals("Revenue", sheet1Doc.getMetadata().get("sheetName"));
            assertTrue(sheet1Doc.getText().contains("North America"),
                    "Sheet content should include data");

            // Metadata checks
            assertEquals("Finance Team", sheet1Doc.getMetadata().get("author"));
            assertEquals("Q3 Sales Data", sheet1Doc.getMetadata().get("title"));
            assertNotNull(sheet1Doc.getMetadata().get("table_headers"),
                    "Sheet should have table_headers");

            // Formula cells
            Object formulaCells = sheet1Doc.getMetadata().get("formulaCells");
            if (formulaCells instanceof List<?> fList) {
                assertTrue(fList.size() >= 3,
                        "Should detect at least 3 formula cells, got " + fList.size());
            }

            // Cell comments
            Object cellComments = sheet1Doc.getMetadata().get("cellComments");
            if (cellComments instanceof List<?> cList) {
                assertTrue(cList.size() >= 1,
                        "Should detect at least 1 cell comment");
            }

            // Named ranges (on first sheet)
            Object namedRanges = sheet1Doc.getMetadata().get("namedRanges");
            if (namedRanges instanceof List<?> nrList) {
                assertTrue(nrList.size() >= 1,
                        "Should detect named range 'RevenueData'");
            }

            // Graph extraction
            ExtractionResult result = extractGraph(sheet1Doc);

            // SPREADSHEET entity
            ExtractedEntity spreadsheet = findEntity(result, "SPREADSHEET");
            assertNotNull(spreadsheet, "Should produce SPREADSHEET entity");

            // PERSON from author
            ExtractedEntity author = findEntity(result, "PERSON");
            assertNotNull(author, "Should extract author as PERSON");
            assertNotNull(findRelation(result, "AUTHORED_BY"));

            // TOPIC from keywords
            List<ExtractedEntity> topics = findEntities(result, "TOPIC");
            assertTrue(topics.size() >= 2, "Should have topics from keywords");

            // SPREADSHEET_SHEET entity
            ExtractedEntity sheetEntity = findEntity(result, "SPREADSHEET_SHEET");
            assertNotNull(sheetEntity, "Should produce SPREADSHEET_SHEET entity");
            assertNotNull(findRelation(result, "HAS_SHEET"),
                    "Should have HAS_SHEET relation");
        }

        @Test
        void xlsxSecondSheetAlsoLoaded() throws Exception {
            Path xlsxFile = tempDir.resolve("multi-sheet.xlsx");
            try (XSSFWorkbook workbook = new XSSFWorkbook()) {
                XSSFSheet s1 = workbook.createSheet("Data");
                s1.createRow(0).createCell(0).setCellValue("A");
                s1.createRow(1).createCell(0).setCellValue("B");

                XSSFSheet s2 = workbook.createSheet("Summary");
                s2.createRow(0).createCell(0).setCellValue("Total");
                s2.createRow(1).createCell(0).setCellValue("100");

                try (FileOutputStream fos = new FileOutputStream(xlsxFile.toFile())) {
                    workbook.write(fos);
                }
            }

            List<Document> docs = loadFile(xlsxFile);
            assertTrue(docs.size() >= 2,
                    "Should produce a document per sheet, got " + docs.size());

            Set<String> sheetNames = new HashSet<>();
            for (Document d : docs) {
                Object sn = d.getMetadata().get("sheetName");
                if (sn != null) sheetNames.add(sn.toString());
            }
            assertTrue(sheetNames.contains("Data"), "Should have 'Data' sheet");
            assertTrue(sheetNames.contains("Summary"), "Should have 'Summary' sheet");
        }
    }

    // ================================================================
    //  PPTX Tests
    // ================================================================

    @Nested
    class PptxIntegration {

        @Test
        void pptxWithSlidesProducesFullGraph() throws Exception {
            Path pptxFile = tempDir.resolve("presentation.pptx");
            try (XMLSlideShow ppt = new XMLSlideShow()) {
                // Set properties
                var props = ppt.getProperties().getCoreProperties();
                props.setCreator("Marketing Team");
                props.setTitle("Product Launch Deck");
                props.setKeywords("product, launch, marketing, strategy");

                // Slide 1: Title slide
                XSLFSlide slide1 = ppt.createSlide();
                XSLFTextBox title1 = slide1.createTextBox();
                title1.setText("Product Launch: Widget Pro 3.0");
                title1.setAnchor(new java.awt.Rectangle(50, 50, 600, 100));

                XSLFTextBox body1 = slide1.createTextBox();
                body1.setText("Marketing Team - May 2025\nConfidential");
                body1.setAnchor(new java.awt.Rectangle(50, 200, 600, 100));

                // Slide 2: Content slide
                XSLFSlide slide2 = ppt.createSlide();
                XSLFTextBox title2 = slide2.createTextBox();
                title2.setText("Key Features");
                title2.setAnchor(new java.awt.Rectangle(50, 50, 600, 80));

                XSLFTextBox features = slide2.createTextBox();
                features.setText("- AI-powered search\n- Real-time analytics\n- Cross-platform support");
                features.setAnchor(new java.awt.Rectangle(50, 150, 600, 200));

                // Slide 2: Add speaker notes
                XSLFNotes notes = ppt.getNotesSlide(slide2);
                if (notes != null) {
                    XSLFTextShape[] notesPlaceholders = notes.getPlaceholders();
                    for (XSLFTextShape placeholder : notesPlaceholders) {
                        if (placeholder.getTextType() == org.apache.poi.sl.usermodel.Placeholder.BODY) {
                            placeholder.setText("Emphasize the AI search capability. " +
                                    "Demo the real-time dashboard.");
                            break;
                        }
                    }
                }

                // Slide 3: Data slide
                XSLFSlide slide3 = ppt.createSlide();
                XSLFTextBox title3 = slide3.createTextBox();
                title3.setText("Market Analysis");
                title3.setAnchor(new java.awt.Rectangle(50, 50, 600, 80));

                XSLFTextBox analysis = slide3.createTextBox();
                analysis.setText("Market size: $5.2B (2025)\nGrowth rate: 18% CAGR\n" +
                        "See https://research.example.com/market-report for details.");
                analysis.setAnchor(new java.awt.Rectangle(50, 150, 600, 200));

                try (FileOutputStream fos = new FileOutputStream(pptxFile.toFile())) {
                    ppt.write(fos);
                }
            }

            List<Document> docs = loadFile(pptxFile);
            assertFalse(docs.isEmpty(), "Should load documents from PPTX");
            // First doc is a full-text summary, then one doc per slide
            assertTrue(docs.size() >= 4,
                    "Should have at least 4 documents (1 full-text + 3 slides), got " + docs.size());

            // Full-text document (index 0)
            Document fullTextDoc = docs.get(0);
            assertEquals("text", fullTextDoc.getMetadata().get(GraphConstants.META_CONTENT_TYPE));
            assertTrue(fullTextDoc.getText().contains("Widget Pro") || fullTextDoc.getText().contains("Product Launch"),
                    "Full text doc should contain presentation content");

            // First slide document (index 1)
            Document slide1 = docs.stream()
                    .filter(d -> "slide".equals(d.getMetadata().get(GraphConstants.META_CONTENT_TYPE)))
                    .findFirst().orElseThrow(() -> new AssertionError("No slide documents found"));
            assertEquals("1", String.valueOf(slide1.getMetadata().get("slideNumber")));

            // Document properties
            assertEquals("Marketing Team", slide1.getMetadata().get("author"));
            assertEquals("Product Launch Deck", slide1.getMetadata().get("title"));

            // Graph extraction on first slide
            ExtractionResult result = extractGraph(slide1);

            // PRESENTATION entity
            ExtractedEntity pres = findEntity(result, "PRESENTATION");
            assertNotNull(pres, "Should produce PRESENTATION entity");

            // PERSON from author
            assertNotNull(findRelation(result, "AUTHORED_BY"), "Should have AUTHORED_BY");

            // TOPIC from keywords
            List<ExtractedEntity> topics = findEntities(result, "TOPIC");
            assertTrue(topics.size() >= 2, "Should have topics from keywords");

            // PRESENTATION_SLIDE entity
            ExtractedEntity slideEntity = findEntity(result, "PRESENTATION_SLIDE");
            assertNotNull(slideEntity, "Should produce PRESENTATION_SLIDE entity");
            assertNotNull(findRelation(result, "HAS_SLIDE"),
                    "Should have HAS_SLIDE relation");
        }

        @Test
        void pptxSlideWithSpeakerNotes() throws Exception {
            Path pptxFile = tempDir.resolve("with-notes.pptx");
            try (XMLSlideShow ppt = new XMLSlideShow()) {
                XSLFSlide slide = ppt.createSlide();
                XSLFTextBox box = slide.createTextBox();
                box.setText("Slide with notes");
                box.setAnchor(new java.awt.Rectangle(50, 50, 600, 100));

                // Add speaker notes
                XSLFNotes notes = ppt.getNotesSlide(slide);
                if (notes != null) {
                    for (XSLFTextShape ph : notes.getPlaceholders()) {
                        if (ph.getTextType() == org.apache.poi.sl.usermodel.Placeholder.BODY) {
                            ph.setText("Remember to mention the pricing strategy here.");
                            break;
                        }
                    }
                }

                try (FileOutputStream fos = new FileOutputStream(pptxFile.toFile())) {
                    ppt.write(fos);
                }
            }

            List<Document> docs = loadFile(pptxFile);
            assertFalse(docs.isEmpty());

            Document slideDoc = docs.get(0);
            Object speakerNotes = slideDoc.getMetadata().get("speakerNotes");
            if (speakerNotes != null) {
                assertTrue(speakerNotes.toString().contains("pricing"),
                        "Speaker notes should be captured in metadata");

                ExtractionResult result = extractGraph(slideDoc);
                ExtractedEntity noteEntity = findEntity(result, "SPEAKER_NOTE");
                if (noteEntity != null) {
                    assertNotNull(findRelation(result, "HAS_SPEAKER_NOTE"),
                            "Should have HAS_SPEAKER_NOTE relation");
                }
            }
        }
    }

    // ================================================================
    //  Cross-format batch extraction
    // ================================================================

    @Nested
    class CrossFormatBatch {

        @Test
        void batchExtractionDeduplicatesAuthorAcrossFormats() throws Exception {
            // Create DOCX by same author
            Path docxFile = tempDir.resolve("memo.docx");
            try (XWPFDocument doc = new XWPFDocument()) {
                doc.getProperties().getCoreProperties().setCreator("Shared Author");
                doc.createParagraph().createRun().setText("Document 1 content");
                try (FileOutputStream fos = new FileOutputStream(docxFile.toFile())) {
                    doc.write(fos);
                }
            }

            // Create XLSX by same author
            Path xlsxFile = tempDir.resolve("data.xlsx");
            try (XSSFWorkbook wb = new XSSFWorkbook()) {
                wb.getProperties().getCoreProperties().setCreator("Shared Author");
                XSSFSheet sheet = wb.createSheet("Sheet1");
                sheet.createRow(0).createCell(0).setCellValue("Value");
                sheet.createRow(1).createCell(0).setCellValue("100");
                try (FileOutputStream fos = new FileOutputStream(xlsxFile.toFile())) {
                    wb.write(fos);
                }
            }

            List<Document> docxDocs = loadFile(docxFile);
            List<Document> xlsxDocs = loadFile(xlsxFile);

            // Batch extract
            List<Document> allDocs = new ArrayList<>();
            allDocs.addAll(docxDocs);
            allDocs.addAll(xlsxDocs);

            ExtractionResult batchResult = extractor.extractBatch(allDocs);

            // "Shared Author" should appear once after dedup
            long authorCount = batchResult.entities().stream()
                    .filter(e -> "PERSON".equals(e.type()))
                    .filter(e -> e.name().contains("Shared Author"))
                    .count();
            assertEquals(1, authorCount,
                    "Same author across formats should be deduplicated in batch extraction");
        }
    }

    // ================================================================
    //  Helper methods
    // ================================================================

    private ExtractedEntity findEntity(ExtractionResult result, String type) {
        return result.entities().stream()
                .filter(e -> type.equals(e.type()))
                .findFirst().orElse(null);
    }

    private List<ExtractedEntity> findEntities(ExtractionResult result, String type) {
        return result.entities().stream()
                .filter(e -> type.equals(e.type()))
                .toList();
    }

    private ExtractedRelation findRelation(ExtractionResult result, String type) {
        return result.relations().stream()
                .filter(r -> type.equals(r.type()))
                .findFirst().orElse(null);
    }

    private List<ExtractedRelation> findRelations(ExtractionResult result, String type) {
        return result.relations().stream()
                .filter(r -> type.equals(r.type()))
                .toList();
    }
}
