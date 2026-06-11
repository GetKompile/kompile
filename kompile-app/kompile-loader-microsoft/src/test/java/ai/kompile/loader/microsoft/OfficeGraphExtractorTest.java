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

import ai.kompile.core.graphrag.format.GraphExtractionSchema.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class OfficeGraphExtractorTest {

    private OfficeGraphExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new OfficeGraphExtractor();
    }

    // --- supportedDocumentTypes ---

    @Test
    void supportedDocumentTypesReturnsExpected() {
        List<String> types = extractor.supportedDocumentTypes();
        assertTrue(types.contains("word"));
        assertTrue(types.contains("excel"));
        assertTrue(types.contains("powerpoint"));
        assertTrue(types.contains("spreadsheet"));
        assertTrue(types.contains("presentation"));
    }

    // --- canExtract ---

    @Test
    void canExtractReturnsTrueForWordDocumentType() {
        Document doc = new Document("content", Map.of("documentType", "Word Document"));
        assertTrue(extractor.canExtract(doc));
    }

    @Test
    void canExtractReturnsTrueForExcelExtension() {
        Document doc = new Document("content", Map.of("fileName", "report.xlsx"));
        assertTrue(extractor.canExtract(doc));
    }

    @Test
    void canExtractReturnsTrueForXlsExtension() {
        Document doc = new Document("content", Map.of("fileName", "old-report.xls"));
        assertTrue(extractor.canExtract(doc));
    }

    @Test
    void canExtractReturnsTrueForDocxExtension() {
        Document doc = new Document("content", Map.of("fileName", "letter.docx"));
        assertTrue(extractor.canExtract(doc));
    }

    @Test
    void canExtractReturnsTrueForPptxExtension() {
        Document doc = new Document("content", Map.of("fileName", "slides.pptx"));
        assertTrue(extractor.canExtract(doc));
    }

    @Test
    void canExtractReturnsTrueForSpreadsheetType() {
        Document doc = new Document("content", Map.of("documentType", "Spreadsheet"));
        assertTrue(extractor.canExtract(doc));
    }

    @Test
    void canExtractReturnsTrueForPresentationType() {
        Document doc = new Document("content", Map.of("documentType", "Presentation"));
        assertTrue(extractor.canExtract(doc));
    }

    @Test
    void canExtractReturnsTrueForOdtType() {
        Document doc = new Document("content", Map.of("documentType", "odt"));
        assertTrue(extractor.canExtract(doc));
    }

    @Test
    void canExtractReturnsTrueForOdsExtension() {
        Document doc = new Document("content", Map.of("fileName", "calc.ods"));
        assertTrue(extractor.canExtract(doc));
    }

    @Test
    void canExtractReturnsFalseForPdf() {
        Document doc = new Document("content", Map.of("documentType", "PDF Document"));
        assertFalse(extractor.canExtract(doc));
    }

    @Test
    void canExtractReturnsFalseForNullDoc() {
        assertFalse(extractor.canExtract(null));
    }

    @Test
    void canExtractReturnsFalseForNullMetadata() {
        Document doc = new Document("content");
        assertFalse(extractor.canExtract(doc));
    }

    @Test
    void canExtractReturnsFalseForUnrelatedDoc() {
        Document doc = new Document("content", Map.of("documentType", "image", "fileName", "photo.jpg"));
        assertFalse(extractor.canExtract(doc));
    }

    @Test
    void canExtractReturnsTrueForStreamingDocxType() {
        // StreamingOfficeLoaderImpl sets documentType="DOCX" (not "Microsoft Word Document")
        Document doc = new Document("content", Map.of("documentType", "DOCX"));
        assertTrue(extractor.canExtract(doc));
    }

    @Test
    void canExtractReturnsTrueForStreamingXlsxType() {
        Document doc = new Document("content", Map.of("documentType", "XLSX"));
        assertTrue(extractor.canExtract(doc));
    }

    @Test
    void canExtractReturnsTrueForStreamingPptxType() {
        Document doc = new Document("content", Map.of("documentType", "PPTX"));
        assertTrue(extractor.canExtract(doc));
    }

    // --- extract ---

    @Test
    void extractCreatesWordDocumentEntity() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Word");
        meta.put("title", "My Letter");
        meta.put("source", "/docs/letter.docx");
        meta.put("fileName", "letter.docx");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertFalse(result.entities().isEmpty());
        ExtractedEntity docEntity = result.entities().get(0);
        assertEquals("My Letter", docEntity.name());
        assertEquals("WORD_DOCUMENT", docEntity.type());
        assertEquals(1.0, docEntity.confidence());
    }

    @Test
    void extractCreatesSpreadsheetEntity() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Excel Spreadsheet");
        meta.put("fileName", "data.xlsx");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity docEntity = result.entities().get(0);
        assertEquals("data.xlsx", docEntity.name());
        assertEquals("SPREADSHEET", docEntity.type());
    }

    @Test
    void extractCreatesPresentationEntity() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "PowerPoint Presentation");
        meta.put("title", "Quarterly Review");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity docEntity = result.entities().get(0);
        assertEquals("Quarterly Review", docEntity.name());
        assertEquals("PRESENTATION", docEntity.type());
    }

    @Test
    void extractCreatesAuthoredByRelation() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Word");
        meta.put("title", "Report");
        meta.put("author", "Jane Smith");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e -> e.type().equals("PERSON") && e.name().equals("Jane Smith")));
        assertTrue(result.relations().stream().anyMatch(r -> r.type().equals("AUTHORED_BY")));
    }

    @Test
    void extractSplitsMultipleAuthors() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Word");
        meta.put("title", "Report");
        meta.put("author", "Alice; Bob and Carol");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        long personCount = result.entities().stream().filter(e -> e.type().equals("PERSON")).count();
        assertEquals(3, personCount);
        long authoredByCount = result.relations().stream().filter(r -> r.type().equals("AUTHORED_BY")).count();
        assertEquals(3, authoredByCount);
    }

    @Test
    void extractCreatesProducedByFromProducer() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Word");
        meta.put("title", "Doc");
        meta.put("producer", "Microsoft Word 2021");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e ->
                e.type().equals("ORGANIZATION") && e.name().equals("Microsoft Word 2021")));
        assertTrue(result.relations().stream().anyMatch(r -> r.type().equals("PRODUCED_BY")));
    }

    @Test
    void extractCreatesProducedByFromApplicationName() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Word");
        meta.put("title", "Doc");
        meta.put("applicationName", "LibreOffice");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e ->
                e.type().equals("ORGANIZATION") && e.name().equals("LibreOffice")));
    }

    @Test
    void extractCreatesHasTopicFromKeywords() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Word");
        meta.put("title", "Report");
        meta.put("keywords", "finance, quarterly, budget");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        long topicCount = result.entities().stream().filter(e -> e.type().equals("TOPIC")).count();
        assertEquals(3, topicCount);
        long hasTopicCount = result.relations().stream().filter(r -> r.type().equals("HAS_TOPIC")).count();
        assertEquals(3, hasTopicCount);
    }

    @Test
    void extractCreatesHasSheetForSpreadsheet() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Spreadsheet");
        meta.put("title", "Budget");
        meta.put("source", "/files/budget.xlsx");
        meta.put("sheetName", "Q1 Expenses");
        meta.put("sheetIndex", 0);
        meta.put("table_row_count", 50);
        meta.put("table_column_count", 8);
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e ->
                e.type().equals("SPREADSHEET_SHEET") && e.name().equals("Q1 Expenses")));
        assertTrue(result.relations().stream().anyMatch(r -> r.type().equals("HAS_SHEET")));
        ExtractedEntity sheet = result.entities().stream()
                .filter(e -> e.type().equals("SPREADSHEET_SHEET")).findFirst().orElseThrow();
        assertEquals("Q1 Expenses", sheet.properties().get("sheetName"));
        assertEquals("0", sheet.properties().get("sheetIndex"));
        assertEquals("50", sheet.properties().get("rowCount"));
    }

    @Test
    void extractCreatesHasSlideForPresentation() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Presentation");
        meta.put("title", "Deck");
        meta.put("source", "/files/deck.pptx");
        meta.put("slideTitle", "Introduction");
        meta.put("slideNumber", 1);
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e ->
                e.type().equals("PRESENTATION_SLIDE") && e.name().equals("Introduction")));
        assertTrue(result.relations().stream().anyMatch(r -> r.type().equals("HAS_SLIDE")));
    }

    @Test
    void extractCreatesSlideWithNumberOnlyWhenNoTitle() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Presentation");
        meta.put("title", "Deck");
        meta.put("source", "/files/deck.pptx");
        meta.put("slideNumber", 3);
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e ->
                e.type().equals("PRESENTATION_SLIDE") && e.name().equals("Slide 3")));
    }

    @Test
    void extractReturnsDocEntityEvenWithSparseMetadata() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Word");
        Document doc = new Document("content", meta);
        ExtractionResult result = extractor.extract(doc);
        assertFalse(result.entities().isEmpty());
        assertEquals("Untitled Document", result.entities().get(0).name());
    }

    @Test
    void extractIncludesMetadataProperties() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Word");
        meta.put("title", "Report");
        meta.put("subject", "Finance");
        meta.put("description", "Annual financial report");
        meta.put("pageCount", 25);
        meta.put("creationDate", "2025-01-15");
        meta.put("language", "en");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity docEntity = result.entities().get(0);
        assertEquals("Finance", docEntity.properties().get("subject"));
        assertEquals("Annual financial report", docEntity.properties().get("description"));
        assertEquals("25", docEntity.properties().get("pageCount"));
        assertEquals("en", docEntity.properties().get("language"));
    }

    @Test
    void extractSetsExtractionMetadata() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Word");
        meta.put("title", "Doc");
        meta.put("source", "/path/doc.docx");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertNotNull(result.metadata());
        assertEquals("office-metadata-extractor", result.metadata().extractionModel());
    }

    // --- extractBatch ---

    @Test
    void extractBatchDeduplicatesEntities() {
        Map<String, Object> meta1 = new HashMap<>();
        meta1.put("documentType", "Word");
        meta1.put("title", "Doc1");
        meta1.put("author", "Alice");
        Document doc1 = new Document("content1", meta1);

        Map<String, Object> meta2 = new HashMap<>();
        meta2.put("documentType", "Word");
        meta2.put("title", "Doc2");
        meta2.put("author", "Alice");
        Document doc2 = new Document("content2", meta2);

        ExtractionResult result = extractor.extractBatch(List.of(doc1, doc2));

        long aliceCount = result.entities().stream()
                .filter(e -> e.type().equals("PERSON") && e.name().equals("Alice")).count();
        assertEquals(1, aliceCount);
        assertEquals(2, result.relations().stream().filter(r -> r.type().equals("AUTHORED_BY")).count());
    }

    @Test
    void extractBatchMergesMultipleDocs() {
        Map<String, Object> meta1 = new HashMap<>();
        meta1.put("documentType", "Word");
        meta1.put("title", "Doc1");
        Document doc1 = new Document("content1", meta1);

        Map<String, Object> meta2 = new HashMap<>();
        meta2.put("documentType", "Spreadsheet");
        meta2.put("title", "Sheet1");
        Document doc2 = new Document("content2", meta2);

        ExtractionResult result = extractor.extractBatch(List.of(doc1, doc2));

        assertEquals(2, result.entities().size());
        assertNotNull(result.metadata());
    }

    // --- resolveEntityType ---

    @Test
    void resolveEntityTypeFallsBackToExtension() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("fileName", "data.xlsm");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity docEntity = result.entities().get(0);
        assertEquals("SPREADSHEET", docEntity.type());
    }

    @Test
    void resolveEntityTypeFallsBackToOfficeDocument() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "UnknownOffice");
        meta.put("fileName", "file.doc");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity docEntity = result.entities().get(0);
        assertEquals("WORD_DOCUMENT", docEntity.type());
    }

    // --- Embedded image support ---

    @Test
    void canExtractReturnsTrueForContentTypeImage() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("content_type", "image");
        meta.put("fileName", "workbook.xlsx");
        Document doc = new Document("Embedded image", meta);

        assertTrue(extractor.canExtract(doc));
    }

    @Test
    void extractCreatesEmbeddedImageEntity() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("content_type", "image");
        meta.put("fileName", "data.xlsx");
        meta.put("documentType", "Microsoft Excel Spreadsheet (.xlsx)");
        meta.put("source", "/docs/data.xlsx");
        meta.put("image_index", 0);
        meta.put("image_mime_type", "image/png");
        meta.put("image_size_bytes", 12345);
        Document doc = new Document("Embedded image 1 in data.xlsx", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e -> "EMBEDDED_IMAGE".equals(e.type())),
                "Should create EMBEDDED_IMAGE entity");
        assertTrue(result.relations().stream().anyMatch(r -> "HAS_IMAGE".equals(r.type())),
                "Should create HAS_IMAGE relation");

        ExtractedEntity imageEntity = result.entities().stream()
                .filter(e -> "EMBEDDED_IMAGE".equals(e.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("Image 1", imageEntity.name());
    }

    // --- Word DOCX table support ---

    @Test
    void canExtractReturnsTrueForContentTypeTable() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("content_type", "table");
        meta.put("documentType", "Microsoft Word Table");
        Document doc = new Document("Table content", meta);

        assertTrue(extractor.canExtract(doc));
    }

    @Test
    void extractCreatesWordTableEntity() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("content_type", "table");
        meta.put("fileName", "report.docx");
        meta.put("documentType", "Microsoft Word Table");
        meta.put("source", "/docs/report.docx");
        meta.put("table_index", 2);
        meta.put("table_row_count", 5);
        meta.put("table_column_count", 3);
        meta.put("table_headers", "Name,Age,City");
        Document doc = new Document("| Name | Age | City |", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e -> "TABLE".equals(e.type())),
                "Should create WORD_TABLE entity");
        assertTrue(result.relations().stream().anyMatch(r -> "HAS_TABLE".equals(r.type())),
                "Should create HAS_TABLE relation");

        ExtractedEntity tableEntity = result.entities().stream()
                .filter(e -> "TABLE".equals(e.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("Table 3", tableEntity.name());
        assertEquals("2", tableEntity.properties().get("tableIndex"));
        assertEquals("5", tableEntity.properties().get("rowCount"));
        assertEquals("3", tableEntity.properties().get("columnCount"));
        assertEquals("Name,Age,City", tableEntity.properties().get("headers"));
    }

    @Test
    void extractWordTableHasRelationToParentDoc() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("content_type", "table");
        meta.put("fileName", "report.docx");
        meta.put("documentType", "Microsoft Word Table");
        meta.put("source", "/docs/report.docx");
        meta.put("table_index", 0);
        Document doc = new Document("Table content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedRelation hasTable = result.relations().stream()
                .filter(r -> "HAS_TABLE".equals(r.type()))
                .findFirst()
                .orElseThrow();
        // Source should be the OFFICE_DOCUMENT entity
        ExtractedEntity docEntity = result.entities().stream()
                .filter(e -> "WORD_DOCUMENT".equals(e.type()))
                .findFirst()
                .orElseThrow();
        assertEquals(docEntity.id(), hasTable.source());
    }

    @Test
    void extractLegacyDocTableCreatesWordTableEntity() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("content_type", "table");
        meta.put("fileName", "legacy.doc");
        meta.put("documentType", "Microsoft Word Table (.doc)");
        meta.put("source", "/docs/legacy.doc");
        meta.put("table_index", 0);
        meta.put("table_row_count", 3);
        meta.put("table_column_count", 2);
        Document doc = new Document("Legacy table", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e -> "TABLE".equals(e.type())),
                "Legacy .doc tables should also create WORD_TABLE entity");
    }

    // --- PST/MSG email entity extraction ---

    @Test
    void extractPstEmailCreatesSenderPersonEntity() {
        Document doc = pstEmailDoc("Meeting Notes", "alice@example.com", "Alice Smith",
                "bob@example.com", null, null);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e ->
                "PERSON".equals(e.type()) && "Alice Smith".equals(e.name())),
                "Should create PERSON entity for email sender");
        assertTrue(result.relations().stream().anyMatch(r ->
                "SENT_BY".equals(r.type())),
                "Should create SENT_BY relation");
    }

    @Test
    void extractPstEmailCreatesRecipientPersonEntity() {
        Document doc = pstEmailDoc("Meeting Notes", "alice@example.com", "Alice",
                "Bob Jones; Carol White", null, null);

        ExtractionResult result = extractor.extract(doc);

        long personCount = result.entities().stream()
                .filter(e -> "PERSON".equals(e.type())).count();
        assertTrue(personCount >= 3, "Should create PERSON entities for sender + 2 recipients");
        assertEquals(2, result.relations().stream()
                .filter(r -> "SENT_TO".equals(r.type())).count(),
                "Should create 2 SENT_TO relations");
    }

    @Test
    void extractPstEmailCreatesCcBccRelations() {
        Document doc = pstEmailDoc("FYI", "alice@example.com", "Alice",
                "bob@example.com", "carol@example.com", "dave@example.com");

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.relations().stream().anyMatch(r -> "CC_TO".equals(r.type())));
        assertTrue(result.relations().stream().anyMatch(r -> "BCC_TO".equals(r.type())));
    }

    @Test
    void extractPstEmailCreatesThreadingRelations() {
        Document doc = pstEmailDoc("Re: Budget", "alice@example.com", "Alice",
                "bob@example.com", null, null);
        doc.getMetadata().put("email.inReplyTo", "<original@example.com>");
        doc.getMetadata().put("email.conversationTopic", "Budget Discussion");

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.relations().stream().anyMatch(r -> "REPLIED_TO".equals(r.type())),
                "Should create REPLIED_TO relation from inReplyTo");
        assertTrue(result.entities().stream().anyMatch(e -> "CONVERSATION_TOPIC".equals(e.type())),
                "Should create CONVERSATION_TOPIC entity");
        assertTrue(result.relations().stream().anyMatch(r -> "HAS_CONVERSATION_TOPIC".equals(r.type())));
    }

    @Test
    void extractPstEmailCreatesFolderEntity() {
        Document doc = pstEmailDoc("Inbox Email", "alice@example.com", "Alice",
                "bob@example.com", null, null);
        doc.getMetadata().put("email.pstFolder", "Inbox");

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e ->
                "EMAIL_FOLDER".equals(e.type()) && "Inbox".equals(e.name())));
        assertTrue(result.relations().stream().anyMatch(r -> "IN_FOLDER".equals(r.type())));
    }

    @Test
    void extractPstEmailCreatesAttachmentEntities() {
        Document doc = pstEmailDoc("Report", "alice@example.com", "Alice",
                "bob@example.com", null, null);
        doc.getMetadata().put("email.attachmentNames", List.of("report.xlsx", "notes.pdf"));
        doc.getMetadata().put("email.attachmentCount", 2);

        ExtractionResult result = extractor.extract(doc);

        long attCount = result.entities().stream()
                .filter(e -> "ATTACHMENT".equals(e.type())).count();
        assertEquals(2, attCount, "Should create 2 ATTACHMENT entities");
        assertEquals(2, result.relations().stream()
                .filter(r -> "HAS_ATTACHMENT".equals(r.type())).count());
    }

    @Test
    void extractNonOutlookDocIgnoresEmailFields() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Microsoft Word Document (.docx)");
        meta.put("fileName", "doc.docx");
        meta.put("source", "/docs/doc.docx");
        meta.put("email.from", "alice@example.com"); // should be ignored for non-MSG
        Document doc = new Document("Word content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertFalse(result.relations().stream().anyMatch(r -> "SENT_BY".equals(r.type())),
                "Non-Outlook documents should not produce SENT_BY relations");
    }

    // ── DOCX heading / DOCUMENT_SECTION tests ──────────────────────────

    @Test
    void docxHeadingsCreateDocumentSectionEntities() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "DOCX");
        meta.put("source", "/docs/report.docx");
        meta.put("fileName", "report.docx");
        meta.put("docx.headings", List.of(
                Map.of("text", "Introduction", "level", "1", "paragraphIndex", "0"),
                Map.of("text", "Background", "level", "2", "paragraphIndex", "5"),
                Map.of("text", "Conclusion", "level", "1", "paragraphIndex", "20")
        ));
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        long sectionCount = result.entities().stream()
                .filter(e -> "DOCUMENT_SECTION".equals(e.type())).count();
        assertEquals(3, sectionCount, "Should create DOCUMENT_SECTION for each heading");

        assertTrue(result.entities().stream().anyMatch(e ->
                "DOCUMENT_SECTION".equals(e.type()) && "Introduction".equals(e.name())));
        assertTrue(result.entities().stream().anyMatch(e ->
                "DOCUMENT_SECTION".equals(e.type()) && "Background".equals(e.name())));

        // H1 headings get HAS_SECTION, H2 gets SUBSECTION_OF
        long hasSectionCount = result.relations().stream()
                .filter(r -> "HAS_SECTION".equals(r.type())).count();
        assertEquals(2, hasSectionCount, "H1 headings should get HAS_SECTION");

        long subsectionCount = result.relations().stream()
                .filter(r -> "SUBSECTION_OF".equals(r.type())).count();
        assertEquals(1, subsectionCount, "H2 under H1 should get SUBSECTION_OF");
    }

    @Test
    void docxHeadingSectionIncludesLevelProperty() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "DOCX");
        meta.put("source", "/docs/report.docx");
        meta.put("docx.headings", List.of(
                Map.of("text", "Summary", "level", "2", "paragraphIndex", "10")
        ));
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity section = result.entities().stream()
                .filter(e -> "DOCUMENT_SECTION".equals(e.type()))
                .findFirst().orElseThrow();
        assertEquals("2", section.properties().get("headingLevel"));
        assertEquals("Summary", section.properties().get("headingText"));
        assertEquals("10", section.properties().get("paragraphIndex"));
    }

    @Test
    void noHeadingsProducesNoSectionEntities() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "DOCX");
        meta.put("source", "/docs/flat.docx");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertFalse(result.entities().stream().anyMatch(e -> "DOCUMENT_SECTION".equals(e.type())),
                "Documents without headings should not produce DOCUMENT_SECTION entities");
    }

    // ── DOCX comment / DOCUMENT_COMMENT tests ─────────────────────────

    @Test
    void docxCommentsCreateDocumentCommentEntities() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "DOCX");
        meta.put("source", "/docs/report.docx");
        meta.put("fileName", "report.docx");
        meta.put("docx.comments", List.of(
                Map.of("commentId", "1", "author", "Alice Smith", "text", "Please review this section.", "date", "2025-01-15"),
                Map.of("commentId", "2", "author", "Bob Jones", "text", "Done.", "date", "2025-01-16")
        ));
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        long commentCount = result.entities().stream()
                .filter(e -> "DOCUMENT_COMMENT".equals(e.type())).count();
        assertEquals(2, commentCount, "Should create DOCUMENT_COMMENT for each comment");

        long hasCommentCount = result.relations().stream()
                .filter(r -> "HAS_COMMENT".equals(r.type())).count();
        assertEquals(2, hasCommentCount, "Should create HAS_COMMENT relation for each comment");
    }

    @Test
    void docxCommentIncludesTextAndAuthorProperties() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "DOCX");
        meta.put("source", "/docs/report.docx");
        meta.put("docx.comments", List.of(
                Map.of("commentId", "5", "author", "Carol White", "text", "Check the numbers.", "date", "2025-03-01")
        ));
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity comment = result.entities().stream()
                .filter(e -> "DOCUMENT_COMMENT".equals(e.type()))
                .findFirst().orElseThrow();
        assertEquals("5", comment.properties().get("commentId"));
        assertEquals("Carol White", comment.properties().get("author"));
        assertEquals("Check the numbers.", comment.properties().get("text"));
        assertEquals("2025-03-01", comment.properties().get("date"));
    }

    @Test
    void docxCommentCreatesCommentByRelationToAuthor() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "DOCX");
        meta.put("source", "/docs/draft.docx");
        meta.put("docx.comments", List.of(
                Map.of("commentId", "3", "author", "Dave Lee", "text", "Needs clarification.")
        ));
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        // A PERSON entity for the comment author
        assertTrue(result.entities().stream()
                .anyMatch(e -> "PERSON".equals(e.type()) && "Dave Lee".equals(e.name())),
                "Should create PERSON entity for comment author");

        // A COMMENT_BY relation from the comment entity to the person
        assertTrue(result.relations().stream()
                .anyMatch(r -> "COMMENT_BY".equals(r.type())),
                "Should create COMMENT_BY relation from comment to author");

        // Verify the COMMENT_BY relation's target is the PERSON entity
        ExtractedEntity person = result.entities().stream()
                .filter(e -> "PERSON".equals(e.type()) && "Dave Lee".equals(e.name()))
                .findFirst().orElseThrow();
        ExtractedEntity comment = result.entities().stream()
                .filter(e -> "DOCUMENT_COMMENT".equals(e.type()))
                .findFirst().orElseThrow();
        assertTrue(result.relations().stream()
                .anyMatch(r -> "COMMENT_BY".equals(r.type())
                        && r.source().equals(comment.id())
                        && r.target().equals(person.id())),
                "COMMENT_BY should link from comment entity to person entity");
    }

    @Test
    void docxCommentWithoutAuthorCreatesNoCommentByRelation() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "DOCX");
        meta.put("source", "/docs/anon.docx");
        meta.put("docx.comments", List.of(
                Map.of("commentId", "7", "text", "Anonymous note.")
        ));
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream()
                .anyMatch(e -> "DOCUMENT_COMMENT".equals(e.type())),
                "Should still create DOCUMENT_COMMENT entity");
        assertFalse(result.relations().stream()
                .anyMatch(r -> "COMMENT_BY".equals(r.type())),
                "Should not create COMMENT_BY when author is absent");
    }

    @Test
    void docxCommentWithBlankTextIsSkipped() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "DOCX");
        meta.put("source", "/docs/empty.docx");
        meta.put("docx.comments", List.of(
                Map.of("commentId", "9", "author", "Eve", "text", "   ")
        ));
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertFalse(result.entities().stream()
                .anyMatch(e -> "DOCUMENT_COMMENT".equals(e.type())),
                "Comments with blank text should be skipped");
    }

    @Test
    void noCommentsProducesNoDocumentCommentEntities() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "DOCX");
        meta.put("source", "/docs/quiet.docx");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertFalse(result.entities().stream()
                .anyMatch(e -> "DOCUMENT_COMMENT".equals(e.type())),
                "Documents without comments should produce no DOCUMENT_COMMENT entities");
    }

    @Test
    void hasCommentRelationPointsFromDocEntityToComment() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "DOCX");
        meta.put("source", "/docs/noted.docx");
        meta.put("fileName", "noted.docx");
        meta.put("docx.comments", List.of(
                Map.of("commentId", "1", "author", "Frank", "text", "Looks good.")
        ));
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity docEntity = result.entities().stream()
                .filter(e -> "WORD_DOCUMENT".equals(e.type()))
                .findFirst().orElseThrow();
        ExtractedEntity commentEntity = result.entities().stream()
                .filter(e -> "DOCUMENT_COMMENT".equals(e.type()))
                .findFirst().orElseThrow();
        assertTrue(result.relations().stream()
                .anyMatch(r -> "HAS_COMMENT".equals(r.type())
                        && r.source().equals(docEntity.id())
                        && r.target().equals(commentEntity.id())),
                "HAS_COMMENT should link from doc entity to comment entity");
    }

    // ── DOCX hyperlink / HYPERLINK tests ───────────────────────────────

    @Test
    void docxHyperlinksCreateHyperlinkEntities() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "DOCX");
        meta.put("source", "/docs/links.docx");
        meta.put("fileName", "links.docx");
        meta.put("docx.hyperlinks", List.of(
                Map.of("url", "https://example.com", "text", "Example"),
                Map.of("url", "https://kompile.ai", "text", "Kompile")
        ));
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        long hyperlinkCount = result.entities().stream()
                .filter(e -> "HYPERLINK".equals(e.type())).count();
        assertEquals(2, hyperlinkCount, "Should create HYPERLINK entity for each link");

        long hasHyperlinkCount = result.relations().stream()
                .filter(r -> "HAS_HYPERLINK".equals(r.type())).count();
        assertEquals(2, hasHyperlinkCount, "Should create HAS_HYPERLINK relation for each link");
    }

    @Test
    void docxHyperlinkEntityStoresUrlAndText() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "DOCX");
        meta.put("source", "/docs/links.docx");
        meta.put("docx.hyperlinks", List.of(
                Map.of("url", "https://kompile.ai/docs", "text", "Kompile Docs")
        ));
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity link = result.entities().stream()
                .filter(e -> "HYPERLINK".equals(e.type()))
                .findFirst().orElseThrow();
        assertEquals("https://kompile.ai/docs", link.properties().get("url"));
        assertEquals("Kompile Docs", link.properties().get("text"));
        assertEquals("Kompile Docs", link.name());
    }

    @Test
    void docxHyperlinkWithoutTextUsesUrlAsName() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "DOCX");
        meta.put("source", "/docs/bare.docx");
        meta.put("docx.hyperlinks", List.of(
                Map.of("url", "https://bare-url.example.com")
        ));
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity link = result.entities().stream()
                .filter(e -> "HYPERLINK".equals(e.type()))
                .findFirst().orElseThrow();
        assertEquals("https://bare-url.example.com", link.name(),
                "Hyperlink entity name should fall back to URL when no display text");
    }

    @Test
    void docxHyperlinkWithBlankUrlIsSkipped() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "DOCX");
        meta.put("source", "/docs/blank.docx");
        meta.put("docx.hyperlinks", List.of(
                Map.of("url", "   ", "text", "No URL"),
                Map.of("url", "https://valid.example.com", "text", "Valid")
        ));
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        long hyperlinkCount = result.entities().stream()
                .filter(e -> "HYPERLINK".equals(e.type())).count();
        assertEquals(1, hyperlinkCount, "Hyperlinks with blank URL should be skipped");
    }

    @Test
    void noHyperlinksProducesNoHyperlinkEntities() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "DOCX");
        meta.put("source", "/docs/plain.docx");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertFalse(result.entities().stream()
                .anyMatch(e -> "HYPERLINK".equals(e.type())),
                "Documents without hyperlinks should produce no HYPERLINK entities");
    }

    @Test
    void hasHyperlinkRelationPointsFromDocEntityToHyperlink() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "DOCX");
        meta.put("source", "/docs/linked.docx");
        meta.put("fileName", "linked.docx");
        meta.put("docx.hyperlinks", List.of(
                Map.of("url", "https://target.example.com", "text", "Target")
        ));
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity docEntity = result.entities().stream()
                .filter(e -> "WORD_DOCUMENT".equals(e.type()))
                .findFirst().orElseThrow();
        ExtractedEntity linkEntity = result.entities().stream()
                .filter(e -> "HYPERLINK".equals(e.type()))
                .findFirst().orElseThrow();
        assertTrue(result.relations().stream()
                .anyMatch(r -> "HAS_HYPERLINK".equals(r.type())
                        && r.source().equals(docEntity.id())
                        && r.target().equals(linkEntity.id())),
                "HAS_HYPERLINK should link from doc entity to hyperlink entity");
    }

    @Test
    void docxBothCommentsAndHyperlinksExtractedTogether() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "DOCX");
        meta.put("source", "/docs/combined.docx");
        meta.put("docx.comments", List.of(
                Map.of("commentId", "1", "author", "Grace", "text", "See the link below.")
        ));
        meta.put("docx.hyperlinks", List.of(
                Map.of("url", "https://reference.example.com", "text", "Reference")
        ));
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e -> "DOCUMENT_COMMENT".equals(e.type())),
                "Should extract DOCUMENT_COMMENT");
        assertTrue(result.entities().stream().anyMatch(e -> "HYPERLINK".equals(e.type())),
                "Should extract HYPERLINK");
        assertTrue(result.relations().stream().anyMatch(r -> "HAS_COMMENT".equals(r.type())),
                "Should have HAS_COMMENT relation");
        assertTrue(result.relations().stream().anyMatch(r -> "HAS_HYPERLINK".equals(r.type())),
                "Should have HAS_HYPERLINK relation");
    }

    // ── PPTX embedded media (SLIDE_IMAGE / SLIDE_CHART) tests ─────────

    @Test
    void slideImagesCreateSlideImageEntities() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Presentation");
        meta.put("source", "/pres/demo.pptx");
        meta.put("slideTitle", "Overview");
        meta.put("slideNumber", 1);
        meta.put("pptx.slideImages", List.of(
                Map.of("name", "logo.png", "contentType", "image/png"),
                Map.of("name", "photo.jpg", "contentType", "image/jpeg")
        ));
        Document doc = new Document("Slide content", meta);

        ExtractionResult result = extractor.extract(doc);

        long imageCount = result.entities().stream()
                .filter(e -> "SLIDE_IMAGE".equals(e.type())).count();
        assertEquals(2, imageCount, "Should create SLIDE_IMAGE entity for each image");

        long hasImageCount = result.relations().stream()
                .filter(r -> "HAS_SLIDE_IMAGE".equals(r.type())).count();
        assertEquals(2, hasImageCount, "Should create HAS_SLIDE_IMAGE relation for each image");
    }

    @Test
    void slideImageEntityStoresNameAndContentType() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Presentation");
        meta.put("source", "/pres/deck.pptx");
        meta.put("slideTitle", "Data Slide");
        meta.put("slideNumber", 2);
        meta.put("pptx.slideImages", List.of(
                Map.of("name", "chart-bg.png", "contentType", "image/png")
        ));
        Document doc = new Document("Slide content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity imgEntity = result.entities().stream()
                .filter(e -> "SLIDE_IMAGE".equals(e.type()))
                .findFirst().orElseThrow();
        assertEquals("chart-bg.png", imgEntity.name());
        assertEquals("image/png", imgEntity.properties().get("contentType"));
        assertEquals("Data Slide", imgEntity.properties().get("slideTitle"));
    }

    @Test
    void slideImageRelationPointsFromSlideEntityToImage() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Presentation");
        meta.put("source", "/pres/deck.pptx");
        meta.put("slideTitle", "Intro");
        meta.put("slideNumber", 1);
        meta.put("pptx.slideImages", List.of(
                Map.of("name", "banner.png", "contentType", "image/png")
        ));
        Document doc = new Document("Slide content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity slideEntity = result.entities().stream()
                .filter(e -> "PRESENTATION_SLIDE".equals(e.type()))
                .findFirst().orElseThrow();
        ExtractedEntity imgEntity = result.entities().stream()
                .filter(e -> "SLIDE_IMAGE".equals(e.type()))
                .findFirst().orElseThrow();
        assertTrue(result.relations().stream()
                .anyMatch(r -> "HAS_SLIDE_IMAGE".equals(r.type())
                        && r.source().equals(slideEntity.id())
                        && r.target().equals(imgEntity.id())),
                "HAS_SLIDE_IMAGE should link from slide entity to image entity");
    }

    @Test
    void noSlideImagesProducesNoSlideImageEntities() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Presentation");
        meta.put("source", "/pres/text-only.pptx");
        meta.put("slideTitle", "Text Only");
        meta.put("slideNumber", 1);
        Document doc = new Document("Text slide", meta);

        ExtractionResult result = extractor.extract(doc);

        assertFalse(result.entities().stream().anyMatch(e -> "SLIDE_IMAGE".equals(e.type())),
                "Slides without images should produce no SLIDE_IMAGE entities");
    }

    @Test
    void slideChartsCreateSlideChartEntities() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Presentation");
        meta.put("source", "/pres/metrics.pptx");
        meta.put("slideTitle", "Metrics");
        meta.put("slideNumber", 3);
        meta.put("pptx.slideCharts", List.of(
                Map.of("name", "Chart 1"),
                Map.of("name", "Revenue Chart")
        ));
        Document doc = new Document("Slide content", meta);

        ExtractionResult result = extractor.extract(doc);

        long chartCount = result.entities().stream()
                .filter(e -> "SLIDE_CHART".equals(e.type())).count();
        assertEquals(2, chartCount, "Should create SLIDE_CHART entity for each chart");

        long hasChartCount = result.relations().stream()
                .filter(r -> "HAS_SLIDE_CHART".equals(r.type())).count();
        assertEquals(2, hasChartCount, "Should create HAS_SLIDE_CHART relation for each chart");
    }

    @Test
    void slideChartEntityStoresNameAndSlideTitle() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Presentation");
        meta.put("source", "/pres/report.pptx");
        meta.put("slideTitle", "Q3 Results");
        meta.put("slideNumber", 4);
        meta.put("pptx.slideCharts", List.of(
                Map.of("name", "Bar Chart 1")
        ));
        Document doc = new Document("Slide content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity chartEntity = result.entities().stream()
                .filter(e -> "SLIDE_CHART".equals(e.type()))
                .findFirst().orElseThrow();
        assertEquals("Bar Chart 1", chartEntity.name());
        assertEquals("Q3 Results", chartEntity.properties().get("slideTitle"));
    }

    @Test
    void slideChartRelationPointsFromSlideEntityToChart() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Presentation");
        meta.put("source", "/pres/deck.pptx");
        meta.put("slideTitle", "Sales");
        meta.put("slideNumber", 2);
        meta.put("pptx.slideCharts", List.of(
                Map.of("name", "Sales Chart")
        ));
        Document doc = new Document("Slide content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity slideEntity = result.entities().stream()
                .filter(e -> "PRESENTATION_SLIDE".equals(e.type()))
                .findFirst().orElseThrow();
        ExtractedEntity chartEntity = result.entities().stream()
                .filter(e -> "SLIDE_CHART".equals(e.type()))
                .findFirst().orElseThrow();
        assertTrue(result.relations().stream()
                .anyMatch(r -> "HAS_SLIDE_CHART".equals(r.type())
                        && r.source().equals(slideEntity.id())
                        && r.target().equals(chartEntity.id())),
                "HAS_SLIDE_CHART should link from slide entity to chart entity");
    }

    @Test
    void slideWithBothImagesAndChartsProducesBothEntityTypes() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Presentation");
        meta.put("source", "/pres/mixed.pptx");
        meta.put("slideTitle", "Mixed Slide");
        meta.put("slideNumber", 1);
        meta.put("pptx.slideImages", List.of(Map.of("name", "bg.png", "contentType", "image/png")));
        meta.put("pptx.slideCharts", List.of(Map.of("name", "Trend Chart")));
        Document doc = new Document("Slide content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e -> "SLIDE_IMAGE".equals(e.type())),
                "Should produce SLIDE_IMAGE entity");
        assertTrue(result.entities().stream().anyMatch(e -> "SLIDE_CHART".equals(e.type())),
                "Should produce SLIDE_CHART entity");
    }

    // ── PPTX slide comments ─────────────────────────────────────────

    @Test
    void slideCommentsCreateSlideCommentEntities() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "PPTX");
        meta.put("source", "/decks/review.pptx");
        meta.put("slideTitle", "Introduction");
        meta.put("slideNumber", 1);
        meta.put("pptx.slideComments", List.of(
                Map.of("text", "Needs more data", "author", "Alice", "date", "2025-05-01"),
                Map.of("text", "Good summary", "author", "Bob")
        ));
        Document doc = new Document("Slide content", meta);

        ExtractionResult result = extractor.extract(doc);

        long commentCount = result.entities().stream()
                .filter(e -> "SLIDE_COMMENT".equals(e.type())).count();
        assertEquals(2, commentCount, "Should create SLIDE_COMMENT for each comment");

        long hasCommentCount = result.relations().stream()
                .filter(r -> "HAS_SLIDE_COMMENT".equals(r.type())).count();
        assertEquals(2, hasCommentCount, "Should create HAS_SLIDE_COMMENT for each comment");

        long commentByCount = result.relations().stream()
                .filter(r -> "COMMENT_BY".equals(r.type())).count();
        assertEquals(2, commentByCount, "Should create COMMENT_BY for each comment with author");

        ExtractedEntity aliceComment = result.entities().stream()
                .filter(e -> "SLIDE_COMMENT".equals(e.type()) && "Needs more data".equals(e.properties().get("text")))
                .findFirst().orElseThrow();
        assertEquals("Alice", aliceComment.properties().get("author"));
        assertEquals("2025-05-01", aliceComment.properties().get("date"));
    }

    // ── PPTX hidden slide ────────────────────────────────────────────

    @Test
    void hiddenSlideHasIsHiddenProperty() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "PPTX");
        meta.put("source", "/decks/hidden.pptx");
        meta.put("slideTitle", "Backup Slide");
        meta.put("slideNumber", 5);
        meta.put("pptx.isHidden", true);
        Document doc = new Document("Hidden content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity slide = result.entities().stream()
                .filter(e -> "PRESENTATION_SLIDE".equals(e.type()))
                .findFirst().orElseThrow();
        assertEquals("true", slide.properties().get("isHidden"),
                "Hidden slides should have isHidden=true property");
    }

    @Test
    void nonHiddenSlideHasNoIsHiddenProperty() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "PPTX");
        meta.put("source", "/decks/normal.pptx");
        meta.put("slideTitle", "Normal Slide");
        meta.put("slideNumber", 2);
        Document doc = new Document("Normal content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity slide = result.entities().stream()
                .filter(e -> "PRESENTATION_SLIDE".equals(e.type()))
                .findFirst().orElseThrow();
        assertNull(slide.properties().get("isHidden"),
                "Non-hidden slides should not have isHidden property");
    }

    // ── PPTX slide hyperlinks ────────────────────────────────────────

    @Test
    void slideHyperlinksCreateHyperlinkEntities() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "PPTX");
        meta.put("source", "/decks/links.pptx");
        meta.put("slideTitle", "Resources");
        meta.put("slideNumber", 3);
        meta.put("pptx.slideHyperlinks", List.of(
                Map.of("url", "https://example.com", "text", "Example Site"),
                Map.of("url", "https://docs.example.com")
        ));
        Document doc = new Document("Slide content", meta);

        ExtractionResult result = extractor.extract(doc);

        long linkCount = result.entities().stream()
                .filter(e -> "HYPERLINK".equals(e.type())).count();
        assertEquals(2, linkCount, "Should create HYPERLINK entity for each slide hyperlink");

        long hasLinkCount = result.relations().stream()
                .filter(r -> "HAS_SLIDE_HYPERLINK".equals(r.type())).count();
        assertEquals(2, hasLinkCount, "Should create HAS_SLIDE_HYPERLINK for each link");

        ExtractedEntity exampleLink = result.entities().stream()
                .filter(e -> "HYPERLINK".equals(e.type())
                        && "https://example.com".equals(e.properties().get("url")))
                .findFirst().orElseThrow();
        assertEquals("Example Site", exampleLink.properties().get("displayText"));
    }

    // ── DOCX tracked changes (TRACKED_CHANGE) tests ───────────────────

    @Test
    void trackedChangesCreateTrackedChangeEntities() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "DOCX");
        meta.put("source", "/docs/draft.docx");
        meta.put("fileName", "draft.docx");
        meta.put("docx.trackedChanges", List.of(
                Map.of("type", "insertion", "author", "Alice Smith", "date", "2025-03-01"),
                Map.of("type", "deletion", "author", "Bob Jones", "date", "2025-03-02")
        ));
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        long changeCount = result.entities().stream()
                .filter(e -> "TRACKED_CHANGE".equals(e.type())).count();
        assertEquals(2, changeCount, "Should create TRACKED_CHANGE entity for each tracked change");

        long hasChangeCount = result.relations().stream()
                .filter(r -> "HAS_TRACKED_CHANGE".equals(r.type())).count();
        assertEquals(2, hasChangeCount, "Should create HAS_TRACKED_CHANGE relation for each change");
    }

    @Test
    void trackedChangeEntityStoresTypeAuthorDate() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "DOCX");
        meta.put("source", "/docs/reviewed.docx");
        meta.put("docx.trackedChanges", List.of(
                Map.of("type", "insertion", "author", "Carol White", "date", "2025-04-10")
        ));
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity changeEntity = result.entities().stream()
                .filter(e -> "TRACKED_CHANGE".equals(e.type()))
                .findFirst().orElseThrow();
        assertEquals("insertion", changeEntity.properties().get("type"));
        assertEquals("Carol White", changeEntity.properties().get("author"));
        assertEquals("2025-04-10", changeEntity.properties().get("date"));
    }

    @Test
    void trackedChangeCreatesChangedByRelationToPerson() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "DOCX");
        meta.put("source", "/docs/collab.docx");
        meta.put("docx.trackedChanges", List.of(
                Map.of("type", "deletion", "author", "Dave Lee")
        ));
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream()
                .anyMatch(e -> "PERSON".equals(e.type()) && "Dave Lee".equals(e.name())),
                "Should create PERSON entity for tracked change author");

        assertTrue(result.relations().stream()
                .anyMatch(r -> "CHANGED_BY".equals(r.type())),
                "Should create CHANGED_BY relation from change to author");

        ExtractedEntity person = result.entities().stream()
                .filter(e -> "PERSON".equals(e.type()) && "Dave Lee".equals(e.name()))
                .findFirst().orElseThrow();
        ExtractedEntity changeEntity = result.entities().stream()
                .filter(e -> "TRACKED_CHANGE".equals(e.type()))
                .findFirst().orElseThrow();
        assertTrue(result.relations().stream()
                .anyMatch(r -> "CHANGED_BY".equals(r.type())
                        && r.source().equals(changeEntity.id())
                        && r.target().equals(person.id())),
                "CHANGED_BY should link from change entity to person entity");
    }

    @Test
    void trackedChangeWithoutAuthorCreatesNoChangedByRelation() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "DOCX");
        meta.put("source", "/docs/anon-change.docx");
        meta.put("docx.trackedChanges", List.of(
                Map.of("type", "insertion")
        ));
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream()
                .anyMatch(e -> "TRACKED_CHANGE".equals(e.type())),
                "Should still create TRACKED_CHANGE entity");
        assertFalse(result.relations().stream()
                .anyMatch(r -> "CHANGED_BY".equals(r.type())),
                "Should not create CHANGED_BY when author is absent");
    }

    @Test
    void hasTrackedChangeRelationPointsFromDocEntityToChange() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "DOCX");
        meta.put("source", "/docs/edit.docx");
        meta.put("fileName", "edit.docx");
        meta.put("docx.trackedChanges", List.of(
                Map.of("type", "insertion", "author", "Eve")
        ));
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity docEntity = result.entities().stream()
                .filter(e -> "WORD_DOCUMENT".equals(e.type()))
                .findFirst().orElseThrow();
        ExtractedEntity changeEntity = result.entities().stream()
                .filter(e -> "TRACKED_CHANGE".equals(e.type()))
                .findFirst().orElseThrow();
        assertTrue(result.relations().stream()
                .anyMatch(r -> "HAS_TRACKED_CHANGE".equals(r.type())
                        && r.source().equals(docEntity.id())
                        && r.target().equals(changeEntity.id())),
                "HAS_TRACKED_CHANGE should link from doc entity to change entity");
    }

    @Test
    void noTrackedChangesProducesNoTrackedChangeEntities() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "DOCX");
        meta.put("source", "/docs/clean.docx");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertFalse(result.entities().stream()
                .anyMatch(e -> "TRACKED_CHANGE".equals(e.type())),
                "Documents without tracked changes should produce no TRACKED_CHANGE entities");
    }

    @Test
    void multipleTrackedChangeBySameAuthorShareOnePerson() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "DOCX");
        meta.put("source", "/docs/heavy-edit.docx");
        meta.put("docx.trackedChanges", List.of(
                Map.of("type", "insertion", "author", "Frank"),
                Map.of("type", "deletion", "author", "Frank")
        ));
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        long frankCount = result.entities().stream()
                .filter(e -> "PERSON".equals(e.type()) && "Frank".equals(e.name())).count();
        assertEquals(1, frankCount, "Multiple tracked changes by same author should share one PERSON entity");

        long changedByCount = result.relations().stream()
                .filter(r -> "CHANGED_BY".equals(r.type())).count();
        assertEquals(2, changedByCount, "Should have one CHANGED_BY per tracked change");
    }

    @Test
    void trackedChangeEntityStoresTextAndParagraphIndex() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "DOCX");
        meta.put("source", "/docs/text-change.docx");
        meta.put("docx.trackedChanges", List.of(
                Map.of("type", "insertion", "author", "Grace", "date", "2025-05-01",
                        "text", "added paragraph", "paragraphIndex", "3"),
                Map.of("type", "deletion", "author", "Grace", "date", "2025-05-01",
                        "text", "removed sentence", "paragraphIndex", "7")
        ));
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        List<ExtractedEntity> changes = result.entities().stream()
                .filter(e -> "TRACKED_CHANGE".equals(e.type())).toList();
        assertEquals(2, changes.size());

        ExtractedEntity insertion = changes.stream()
                .filter(e -> "insertion".equals(e.properties().get("type"))).findFirst().orElseThrow();
        assertEquals("added paragraph", insertion.properties().get("text"));
        assertEquals("3", insertion.properties().get("paragraphIndex"));

        ExtractedEntity deletion = changes.stream()
                .filter(e -> "deletion".equals(e.properties().get("type"))).findFirst().orElseThrow();
        assertEquals("removed sentence", deletion.properties().get("text"));
        assertEquals("7", deletion.properties().get("paragraphIndex"));
    }

    // ── Named range extraction ───────────────────────────────────────────

    @Test
    void namedRangeCreatesNamedRangeEntityWithDefinesRelation() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Spreadsheet");
        meta.put("fileName", "budget.xlsx");
        meta.put("source", "/files/budget.xlsx");
        meta.put("namedRanges", List.of(
                Map.of("name", "SalesFigures", "refersTo", "Sheet1!$A$1:$B$10")
        ));
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream()
                .anyMatch(e -> "NAMED_RANGE".equals(e.type()) && "SalesFigures".equals(e.name())),
                "Should create NAMED_RANGE entity");
        assertTrue(result.relations().stream()
                .anyMatch(r -> "DEFINES".equals(r.type())),
                "Should create DEFINES relation from spreadsheet to named range");

        ExtractedEntity namedRange = result.entities().stream()
                .filter(e -> "NAMED_RANGE".equals(e.type()))
                .findFirst().orElseThrow();
        assertEquals("SalesFigures", namedRange.properties().get("name"));
        assertEquals("Sheet1!$A$1:$B$10", namedRange.properties().get("refersToFormula"));
    }

    @Test
    void namedRangeWithSheetNameLinksToSheetEntityViaRangeInput() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Spreadsheet");
        meta.put("fileName", "budget.xlsx");
        meta.put("source", "/files/budget.xlsx");
        meta.put("sheetName", "Sheet1");
        meta.put("sheetIndex", 0);
        meta.put("namedRanges", List.of(
                Map.of("name", "TaxRate", "refersTo", "Sheet1!$C$1", "sheetName", "Sheet1")
        ));
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream()
                .anyMatch(e -> "NAMED_RANGE".equals(e.type()) && "TaxRate".equals(e.name())),
                "Should create NAMED_RANGE entity");
        assertTrue(result.relations().stream()
                .anyMatch(r -> "RANGE_INPUT".equals(r.type())),
                "Should create RANGE_INPUT relation from named range to sheet");

        ExtractedEntity namedRange = result.entities().stream()
                .filter(e -> "NAMED_RANGE".equals(e.type()))
                .findFirst().orElseThrow();
        ExtractedEntity sheetEntity = result.entities().stream()
                .filter(e -> "SPREADSHEET_SHEET".equals(e.type()))
                .findFirst().orElseThrow();
        assertTrue(result.relations().stream()
                .anyMatch(r -> "RANGE_INPUT".equals(r.type())
                        && r.source().equals(namedRange.id())
                        && r.target().equals(sheetEntity.id())),
                "RANGE_INPUT should link from named range to sheet entity");
    }

    @Test
    void emptynNullNamedRangesListProducesNoNamedRangeEntities() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Spreadsheet");
        meta.put("fileName", "empty.xlsx");
        meta.put("source", "/files/empty.xlsx");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertFalse(result.entities().stream()
                .anyMatch(e -> "NAMED_RANGE".equals(e.type())),
                "Spreadsheet without namedRanges metadata should produce no NAMED_RANGE entities");
        assertFalse(result.relations().stream()
                .anyMatch(r -> "DEFINES".equals(r.type())),
                "Spreadsheet without namedRanges metadata should produce no DEFINES relations");
    }

    // ── Cell comment extraction ──────────────────────────────────────────

    @Test
    void cellCommentCreatesCellCommentEntityWithHasCommentRelation() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Spreadsheet");
        meta.put("fileName", "data.xlsx");
        meta.put("source", "/files/data.xlsx");
        meta.put("cellComments", List.of(
                Map.of("cellRef", "B3", "text", "Please verify this value")
        ));
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream()
                .anyMatch(e -> "CELL_COMMENT".equals(e.type())),
                "Should create CELL_COMMENT entity");
        assertTrue(result.relations().stream()
                .anyMatch(r -> "HAS_COMMENT".equals(r.type())),
                "Should create HAS_COMMENT relation");

        ExtractedEntity comment = result.entities().stream()
                .filter(e -> "CELL_COMMENT".equals(e.type()))
                .findFirst().orElseThrow();
        assertEquals("B3", comment.properties().get("cellRef"));
        assertEquals("Please verify this value", comment.properties().get("text"));
    }

    @Test
    void cellCommentWithAuthorCreatesPersonEntityAndAuthoredByRelation() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Spreadsheet");
        meta.put("fileName", "data.xlsx");
        meta.put("source", "/files/data.xlsx");
        meta.put("cellComments", List.of(
                Map.of("cellRef", "A1", "text", "Check this formula", "author", "Jane Doe")
        ));
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream()
                .anyMatch(e -> "PERSON".equals(e.type()) && "Jane Doe".equals(e.name())),
                "Should create PERSON entity for comment author");
        assertTrue(result.relations().stream()
                .anyMatch(r -> "AUTHORED_BY".equals(r.type())),
                "Should create AUTHORED_BY relation from comment to author");

        ExtractedEntity person = result.entities().stream()
                .filter(e -> "PERSON".equals(e.type()) && "Jane Doe".equals(e.name()))
                .findFirst().orElseThrow();
        ExtractedEntity comment = result.entities().stream()
                .filter(e -> "CELL_COMMENT".equals(e.type()))
                .findFirst().orElseThrow();
        assertTrue(result.relations().stream()
                .anyMatch(r -> "AUTHORED_BY".equals(r.type())
                        && r.source().equals(comment.id())
                        && r.target().equals(person.id())),
                "AUTHORED_BY should link from comment entity to person entity");
    }

    // ── Formula cell extraction ──────────────────────────────────────────

    @Test
    void formulaCellCreatesFormulaCellEntityWithDependsOnRelation() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Spreadsheet");
        meta.put("fileName", "model.xlsx");
        meta.put("source", "/files/model.xlsx");
        meta.put("formulaCells", List.of(
                Map.of("cellRef", "C5", "formula", "=SUM(A1:A4)")
        ));
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream()
                .anyMatch(e -> "FORMULA_CELL".equals(e.type())),
                "Should create FORMULA_CELL entity");
        assertTrue(result.relations().stream()
                .anyMatch(r -> "DEPENDS_ON".equals(r.type())),
                "Should create DEPENDS_ON relation");

        ExtractedEntity formulaCell = result.entities().stream()
                .filter(e -> "FORMULA_CELL".equals(e.type()))
                .findFirst().orElseThrow();
        assertEquals("C5", formulaCell.properties().get("cellRef"));
        assertEquals("=SUM(A1:A4)", formulaCell.properties().get("formula"));
        assertEquals("C5 = =SUM(A1:A4)", formulaCell.name());
    }

    private Document pstEmailDoc(String subject, String fromAddr, String fromName,
                                  String to, String cc, String bcc) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Outlook PST Message");
        meta.put("source", "/emails/pst/" + subject.replaceAll("\\s+", "_"));
        meta.put("email.subject", subject);
        meta.put("email.from", fromName + " <" + fromAddr + ">");
        meta.put("email.fromAddress", fromAddr);
        meta.put("email.fromName", fromName);
        meta.put("email.to", to);
        if (cc != null) meta.put("email.cc", cc);
        if (bcc != null) meta.put("email.bcc", bcc);
        return new Document("Email body: " + subject, meta);
    }

    // --- Speaker Notes and Slide Layout ---

    @Test
    void speakerNotesStoredOnSlideEntity() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Presentation");
        meta.put("source", "/pres/talk.pptx");
        meta.put("slideTitle", "Agenda");
        meta.put("slideNumber", 2);
        meta.put("speakerNotes", "Remember to mention the timeline");
        Document doc = new Document("Agenda content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity slide = result.entities().stream()
                .filter(e -> "PRESENTATION_SLIDE".equals(e.type()))
                .findFirst().orElseThrow();
        assertEquals("Remember to mention the timeline", slide.properties().get("speakerNotes"));
    }

    @Test
    void slideLayoutStoredOnSlideEntity() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Presentation");
        meta.put("source", "/pres/talk.pptx");
        meta.put("slideTitle", "Title Slide");
        meta.put("slideNumber", 1);
        meta.put("slideLayout", "Title Slide Layout");
        Document doc = new Document("Title slide content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity slide = result.entities().stream()
                .filter(e -> "PRESENTATION_SLIDE".equals(e.type()))
                .findFirst().orElseThrow();
        assertEquals("Title Slide Layout", slide.properties().get("slideLayout"));
    }

    @Test
    void slideWithoutNotesHasNoNotesProperty() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Presentation");
        meta.put("source", "/pres/simple.pptx");
        meta.put("slideTitle", "Empty Slide");
        meta.put("slideNumber", 1);
        Document doc = new Document("Slide content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity slide = result.entities().stream()
                .filter(e -> "PRESENTATION_SLIDE".equals(e.type()))
                .findFirst().orElseThrow();
        assertNull(slide.properties().get("speakerNotes"));
        assertNull(slide.properties().get("slideLayout"));
    }

    // ── DOCX heading hierarchy (SUBSECTION_OF) ────────────────────────

    @Test
    void docxNestedHeadingsProduceSubsectionOfHierarchy() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "DOCX");
        meta.put("source", "/docs/nested.docx");
        meta.put("fileName", "nested.docx");
        meta.put("docx.headings", List.of(
                Map.of("text", "Chapter 1", "level", "1"),
                Map.of("text", "Section 1.1", "level", "2"),
                Map.of("text", "Section 1.1.1", "level", "3"),
                Map.of("text", "Chapter 2", "level", "1"),
                Map.of("text", "Section 2.1", "level", "2")
        ));
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        // Two H1 headings → HAS_SECTION
        long hasSectionCount = result.relations().stream()
                .filter(r -> "HAS_SECTION".equals(r.type())).count();
        assertEquals(2, hasSectionCount, "Two H1 headings should get HAS_SECTION");

        // Three deeper headings → SUBSECTION_OF
        long subsectionCount = result.relations().stream()
                .filter(r -> "SUBSECTION_OF".equals(r.type())).count();
        assertEquals(3, subsectionCount, "H2 and H3 headings should get SUBSECTION_OF");
    }

    // ── Body text URL extraction for non-email docs ────────────────────

    @Test
    void wordDocBodyUrlsExtractedAsExternalResources() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "DOCX");
        meta.put("source", "/docs/links.docx");
        meta.put("fileName", "links.docx");
        Document doc = new Document(
                "See our project at https://github.com/kompile/kompile for details. "
                        + "Also check https://docs.oracle.com/en/java/",
                meta);

        ExtractionResult result = extractor.extract(doc);

        long urlCount = result.entities().stream()
                .filter(e -> "EXTERNAL_RESOURCE".equals(e.type())).count();
        assertEquals(2, urlCount, "Should extract 2 URLs from body text");

        long linkRelCount = result.relations().stream()
                .filter(r -> "HYPERLINKS_TO".equals(r.type())).count();
        assertEquals(2, linkRelCount, "Should create 2 HYPERLINKS_TO relations");
    }

    @Test
    void emailDocDoesNotDoubleExtractBodyUrls() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Outlook");
        meta.put("source", "/emails/test.msg");
        meta.put("fileName", "test.msg");
        meta.put("email.from", "sender@test.com");
        meta.put("email.fromAddress", "sender@test.com");
        meta.put("email.subject", "Test Email");
        Document doc = new Document("Check https://example.com", meta);

        ExtractionResult result = extractor.extract(doc);

        long urlCount = result.entities().stream()
                .filter(e -> "EXTERNAL_RESOURCE".equals(e.type())).count();
        assertEquals(1, urlCount,
                "Email documents should only extract URLs once (from email block, not double)");
    }
}
