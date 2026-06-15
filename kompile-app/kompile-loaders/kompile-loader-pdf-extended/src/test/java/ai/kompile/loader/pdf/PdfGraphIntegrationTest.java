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

package ai.kompile.loader.pdf;

import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.*;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full pipeline integration tests: real PDFs (PDFBox-generated) -> PdfExtendedLoaderImpl -> PdfGraphExtractor.
 *
 * Each test programmatically creates a spec-compliant PDF using Apache PDFBox,
 * loads it through the actual loader, then runs graph extraction and verifies
 * entities and relationships.
 */
class PdfGraphIntegrationTest {

    private PdfExtendedLoaderImpl loader;
    private PdfGraphExtractor extractor;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        loader = new PdfExtendedLoaderImpl();
        extractor = new PdfGraphExtractor();
    }

    /** Load a PDF file and return all documents. */
    private List<Document> loadFile(Path file) throws Exception {
        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl(file.toAbsolutePath().toString())
                .build();
        return loader.load(desc);
    }

    /** Run graph extraction on a document. */
    private ExtractionResult extractGraph(Document doc) {
        assertTrue(extractor.canExtract(doc),
                "PdfGraphExtractor should accept document with metadata: " + doc.getMetadata().get(GraphConstants.META_DOCUMENT_TYPE));
        return extractor.extract(doc);
    }

    // ================================================================
    //  Basic PDF with metadata
    // ================================================================

    @Nested
    class BasicPdfMetadata {

        @Test
        void pdfWithFullMetadataProducesDocPersonTopicOrg() throws Exception {
            Path pdfFile = tempDir.resolve("report.pdf");
            try (PDDocument doc = new PDDocument()) {
                PDDocumentInformation info = doc.getDocumentInformation();
                info.setTitle("Q4 Financial Analysis");
                info.setAuthor("Dr. Sarah Chen");
                info.setSubject("Financial quarterly review");
                info.setKeywords("finance, quarterly, revenue, forecast");
                info.setCreator("Microsoft Word");
                info.setProducer("Apache PDFBox 3.0");

                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 16);
                    cs.newLineAtOffset(72, 700);
                    cs.showText("Q4 Financial Analysis");
                    cs.endText();

                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(72, 660);
                    cs.showText("Revenue grew 18% year-over-year, driven by strong enterprise demand.");
                    cs.endText();

                    cs.beginText();
                    cs.newLineAtOffset(72, 640);
                    cs.showText("The cloud division exceeded targets across all geographic regions.");
                    cs.endText();
                }

                doc.save(pdfFile.toFile());
            }

            List<Document> docs = loadFile(pdfFile);
            assertFalse(docs.isEmpty(), "Should load at least one document from PDF");

            // Find the main full-document text
            Document mainDoc = docs.stream()
                    .filter(d -> GraphConstants.EXTRACTION_TYPE_FULL_DOCUMENT.equals(
                            d.getMetadata().get(GraphConstants.META_PDF_EXTRACTION_TYPE)))
                    .findFirst().orElse(docs.get(0));

            // Verify body text extraction
            assertTrue(mainDoc.getText().contains("Revenue grew") || mainDoc.getText().contains("Financial Analysis"),
                    "Body text should be extracted from PDF");

            // Verify metadata
            assertEquals("report.pdf", mainDoc.getMetadata().get(GraphConstants.META_FILE_NAME));
            assertEquals("Q4 Financial Analysis", mainDoc.getMetadata().get(GraphConstants.META_TITLE));
            assertEquals("Dr. Sarah Chen", mainDoc.getMetadata().get(GraphConstants.META_AUTHOR));
            assertEquals("Financial quarterly review", mainDoc.getMetadata().get(GraphConstants.META_SUBJECT));

            // Graph extraction
            ExtractionResult result = extractGraph(mainDoc);

            // PDF_DOCUMENT entity
            ExtractedEntity pdfDoc = findEntity(result, GraphConstants.ENTITY_PDF_DOCUMENT);
            assertNotNull(pdfDoc, "Should produce PDF_DOCUMENT entity");
            assertEquals("Q4 Financial Analysis", pdfDoc.name());

            // PERSON from author
            List<ExtractedEntity> persons = findEntities(result, GraphConstants.ENTITY_PERSON);
            assertTrue(persons.stream().anyMatch(p -> p.name().contains("Sarah")),
                    "Should extract author as PERSON entity");
            assertNotNull(findRelation(result, GraphConstants.REL_AUTHORED_BY),
                    "Should have AUTHORED_BY relation");

            // ORGANIZATION from producer (Apache PDFBox → organization)
            List<ExtractedEntity> orgs = findEntities(result, GraphConstants.ENTITY_ORGANIZATION);
            assertTrue(orgs.stream().anyMatch(o -> o.name().contains("Apache PDFBox")),
                    "Should extract producer as ORGANIZATION: " + orgs);
            assertNotNull(findRelation(result, GraphConstants.REL_PRODUCED_BY),
                    "Should have PRODUCED_BY relation");

            // ORGANIZATION from creator (Microsoft Word → org since looksLikeSoftware)
            // Creator "Microsoft Word" is recognized as software, creating ORGANIZATION + PRODUCED_BY
            assertTrue(orgs.stream().anyMatch(o -> o.name().contains("Microsoft Word")),
                    "Creator 'Microsoft Word' should be ORGANIZATION since it looks like software");

            // TOPIC from keywords
            List<ExtractedEntity> topics = findEntities(result, GraphConstants.ENTITY_TOPIC);
            assertTrue(topics.size() >= 3,
                    "Should have at least 3 topics from keywords, got " + topics.size());
            assertTrue(topics.stream().anyMatch(t -> t.name().toLowerCase().contains("finance")),
                    "Should have 'finance' topic");
            assertNotNull(findRelation(result, GraphConstants.REL_HAS_TOPIC),
                    "Should have HAS_TOPIC relation");

            // TOPIC from subject
            assertTrue(topics.stream().anyMatch(t -> t.name().contains("Financial quarterly review")),
                    "Subject should be a topic: " + topics);

            // Page count in entity properties
            assertNotNull(pdfDoc.properties());
            assertEquals("1", pdfDoc.properties().get(GraphConstants.META_PAGE_COUNT));
        }

        @Test
        void pdfWithMultipleAuthorsExtractsAllPersons() throws Exception {
            Path pdfFile = tempDir.resolve("collab.pdf");
            try (PDDocument doc = new PDDocument()) {
                PDDocumentInformation info = doc.getDocumentInformation();
                info.setTitle("Collaborative Study");
                info.setAuthor("Alice Wong, Bob Martinez and Carol Davis");

                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(72, 700);
                    cs.showText("Multi-author document for testing.");
                    cs.endText();
                }

                doc.save(pdfFile.toFile());
            }

            List<Document> docs = loadFile(pdfFile);
            Document mainDoc = docs.stream()
                    .filter(d -> GraphConstants.EXTRACTION_TYPE_FULL_DOCUMENT.equals(
                            d.getMetadata().get(GraphConstants.META_PDF_EXTRACTION_TYPE)))
                    .findFirst().orElse(docs.get(0));

            ExtractionResult result = extractGraph(mainDoc);

            // Should split "Alice Wong, Bob Martinez and Carol Davis" into 3 persons
            List<ExtractedEntity> persons = findEntities(result, GraphConstants.ENTITY_PERSON);
            assertTrue(persons.size() >= 3,
                    "Should have 3 separate PERSON entities, got " + persons.size() + ": " + persons);
            assertTrue(persons.stream().anyMatch(p -> p.name().contains("Alice")));
            assertTrue(persons.stream().anyMatch(p -> p.name().contains("Bob")));
            assertTrue(persons.stream().anyMatch(p -> p.name().contains("Carol")));

            // Should have 3 AUTHORED_BY relations
            List<ExtractedRelation> authorRels = findRelations(result, GraphConstants.REL_AUTHORED_BY);
            assertEquals(3, authorRels.size(),
                    "Should have 3 AUTHORED_BY relations, got " + authorRels.size());
        }
    }

    // ================================================================
    //  PDF with bookmarks (document structure)
    // ================================================================

    @Nested
    class PdfBookmarks {

        @Test
        void pdfWithBookmarksExtractsHierarchicalSections() throws Exception {
            Path pdfFile = tempDir.resolve("structured.pdf");
            try (PDDocument doc = new PDDocument()) {
                doc.getDocumentInformation().setTitle("Technical Manual");
                doc.getDocumentInformation().setAuthor("Engineering Team");

                // Create 4 pages
                PDPage page1 = new PDPage();
                PDPage page2 = new PDPage();
                PDPage page3 = new PDPage();
                PDPage page4 = new PDPage();
                doc.addPage(page1);
                doc.addPage(page2);
                doc.addPage(page3);
                doc.addPage(page4);

                // Write content on each page
                for (int i = 0; i < 4; i++) {
                    PDPage page = doc.getPage(i);
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        cs.beginText();
                        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        cs.newLineAtOffset(72, 700);
                        cs.showText("Page " + (i + 1) + " content for the technical manual.");
                        cs.endText();
                    }
                }

                // Create hierarchical bookmarks
                PDDocumentOutline outline = new PDDocumentOutline();
                doc.getDocumentCatalog().setDocumentOutline(outline);

                PDOutlineItem introduction = new PDOutlineItem();
                introduction.setTitle("Introduction");
                PDPageFitDestination dest1 = new PDPageFitDestination();
                dest1.setPage(page1);
                introduction.setDestination(dest1);
                outline.addLast(introduction);

                // Child of Introduction
                PDOutlineItem background = new PDOutlineItem();
                background.setTitle("Background");
                PDPageFitDestination dest2 = new PDPageFitDestination();
                dest2.setPage(page2);
                background.setDestination(dest2);
                introduction.addLast(background);

                PDOutlineItem architecture = new PDOutlineItem();
                architecture.setTitle("Architecture");
                PDPageFitDestination dest3 = new PDPageFitDestination();
                dest3.setPage(page3);
                architecture.setDestination(dest3);
                outline.addLast(architecture);

                PDOutlineItem conclusion = new PDOutlineItem();
                conclusion.setTitle("Conclusion");
                PDPageFitDestination dest4 = new PDPageFitDestination();
                dest4.setPage(page4);
                conclusion.setDestination(dest4);
                outline.addLast(conclusion);

                doc.save(pdfFile.toFile());
            }

            List<Document> docs = loadFile(pdfFile);

            // Find the bookmarks document
            Document bookmarkDoc = docs.stream()
                    .filter(d -> GraphConstants.EXTRACTION_TYPE_BOOKMARKS.equals(
                            d.getMetadata().get(GraphConstants.META_PDF_EXTRACTION_TYPE)))
                    .findFirst().orElse(null);
            assertNotNull(bookmarkDoc, "Should produce a bookmarks document");
            assertTrue(bookmarkDoc.getText().contains("Introduction"),
                    "Bookmark text should contain 'Introduction'");
            assertTrue(bookmarkDoc.getText().contains("Background"),
                    "Bookmark text should contain 'Background'");

            ExtractionResult result = extractGraph(bookmarkDoc);

            // PDF_SECTION entities from bookmarks
            List<ExtractedEntity> sections = findEntities(result, GraphConstants.ENTITY_PDF_SECTION);
            assertTrue(sections.size() >= 4,
                    "Should have at least 4 PDF_SECTION entities (Introduction, Background, Architecture, Conclusion), got "
                            + sections.size() + ": " + sections.stream().map(ExtractedEntity::name).toList());

            assertTrue(sections.stream().anyMatch(s -> s.name().equals("Introduction")));
            assertTrue(sections.stream().anyMatch(s -> s.name().equals("Background")));
            assertTrue(sections.stream().anyMatch(s -> s.name().equals("Architecture")));
            assertTrue(sections.stream().anyMatch(s -> s.name().equals("Conclusion")));

            // HAS_SECTION for top-level bookmarks
            assertNotNull(findRelation(result, GraphConstants.REL_HAS_SECTION),
                    "Should have HAS_SECTION relation for top-level bookmarks");

            // SUBSECTION_OF for "Background" (child of "Introduction")
            assertNotNull(findRelation(result, GraphConstants.REL_SUBSECTION_OF),
                    "Background should have SUBSECTION_OF relation to Introduction");

            // Page number properties on sections
            ExtractedEntity introSection = sections.stream()
                    .filter(s -> s.name().equals("Introduction"))
                    .findFirst().orElse(null);
            assertNotNull(introSection);
            assertNotNull(introSection.properties().get(GraphConstants.PROP_PAGE_NUMBER),
                    "Introduction section should have page number");
        }
    }

    // ================================================================
    //  PDF with annotations (sticky notes / text annotations)
    // ================================================================

    @Nested
    class PdfAnnotations {

        @Test
        void pdfWithTextAnnotationsExtractsAnnotationEntities() throws Exception {
            Path pdfFile = tempDir.resolve("annotated.pdf");
            try (PDDocument doc = new PDDocument()) {
                doc.getDocumentInformation().setTitle("Review Document");
                doc.getDocumentInformation().setAuthor("Jane Reviewer");

                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);

                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(72, 700);
                    cs.showText("This document has annotations for review.");
                    cs.endText();
                }

                // Add a text annotation (sticky note)
                PDAnnotationText stickyNote = new PDAnnotationText();
                stickyNote.setContents("This paragraph needs more supporting data.");
                stickyNote.setTitlePopup("Alice Reviewer");
                stickyNote.setRectangle(new PDRectangle(100, 600, 20, 20));
                page.getAnnotations().add(stickyNote);

                // Add another annotation
                PDAnnotationText note2 = new PDAnnotationText();
                note2.setContents("Check the citation format here.");
                note2.setTitlePopup("Bob Reviewer");
                note2.setRectangle(new PDRectangle(200, 500, 20, 20));
                page.getAnnotations().add(note2);

                doc.save(pdfFile.toFile());
            }

            List<Document> docs = loadFile(pdfFile);

            // Find annotations document
            Document annotDoc = docs.stream()
                    .filter(d -> GraphConstants.EXTRACTION_TYPE_ANNOTATIONS.equals(
                            d.getMetadata().get(GraphConstants.META_PDF_EXTRACTION_TYPE)))
                    .findFirst().orElse(null);
            assertNotNull(annotDoc, "Should produce an annotations document");
            assertTrue(annotDoc.getText().contains("supporting data"),
                    "Annotations text should contain annotation content");

            ExtractionResult result = extractGraph(annotDoc);

            // PDF_ANNOTATION entities
            List<ExtractedEntity> annotations = findEntities(result, GraphConstants.ENTITY_PDF_ANNOTATION);
            assertTrue(annotations.size() >= 2,
                    "Should have at least 2 PDF_ANNOTATION entities, got " + annotations.size());

            // HAS_ANNOTATION relation
            List<ExtractedRelation> hasAnnot = findRelations(result, GraphConstants.REL_HAS_ANNOTATION);
            assertTrue(hasAnnot.size() >= 2,
                    "Should have at least 2 HAS_ANNOTATION relations");

            // ANNOTATED_BY relation → PERSON entity for annotation author
            List<ExtractedRelation> annotBy = findRelations(result, GraphConstants.REL_ANNOTATED_BY);
            assertTrue(annotBy.size() >= 2,
                    "Should have ANNOTATED_BY relations for annotation authors");

            List<ExtractedEntity> persons = findEntities(result, GraphConstants.ENTITY_PERSON);
            assertTrue(persons.stream().anyMatch(p -> p.name().contains("Alice")),
                    "Should have PERSON for 'Alice Reviewer'");
            assertTrue(persons.stream().anyMatch(p -> p.name().contains("Bob")),
                    "Should have PERSON for 'Bob Reviewer'");

            // ON_PAGE relations linking annotations to pages
            assertNotNull(findRelation(result, GraphConstants.REL_ON_PAGE),
                    "Annotations should be linked to their page");
        }
    }

    // ================================================================
    //  PDF with hyperlinks
    // ================================================================

    @Nested
    class PdfHyperlinks {

        @Test
        void pdfWithHyperlinksExtractsExternalResources() throws Exception {
            Path pdfFile = tempDir.resolve("links.pdf");
            try (PDDocument doc = new PDDocument()) {
                doc.getDocumentInformation().setTitle("Resource Guide");

                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);

                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(72, 700);
                    cs.showText("Visit our resources at the links below.");
                    cs.endText();
                }

                // Add hyperlink annotations
                PDAnnotationLink link1 = new PDAnnotationLink();
                PDActionURI uri1 = new PDActionURI();
                uri1.setURI("https://docs.example.com/api-reference");
                link1.setAction(uri1);
                link1.setRectangle(new PDRectangle(72, 650, 200, 15));
                page.getAnnotations().add(link1);

                PDAnnotationLink link2 = new PDAnnotationLink();
                PDActionURI uri2 = new PDActionURI();
                uri2.setURI("https://github.com/example/project");
                link2.setAction(uri2);
                link2.setRectangle(new PDRectangle(72, 620, 200, 15));
                page.getAnnotations().add(link2);

                doc.save(pdfFile.toFile());
            }

            List<Document> docs = loadFile(pdfFile);

            // Find the annotations document (hyperlinks are annotations)
            Document annotDoc = docs.stream()
                    .filter(d -> GraphConstants.EXTRACTION_TYPE_ANNOTATIONS.equals(
                            d.getMetadata().get(GraphConstants.META_PDF_EXTRACTION_TYPE)))
                    .findFirst().orElse(null);
            assertNotNull(annotDoc, "Should produce annotations document with hyperlinks");
            assertTrue(annotDoc.getText().contains("docs.example.com"),
                    "Annotation text should contain the URL");

            ExtractionResult result = extractGraph(annotDoc);

            // EXTERNAL_RESOURCE entities from URLs
            List<ExtractedEntity> resources = findEntities(result, GraphConstants.ENTITY_EXTERNAL_RESOURCE);
            assertTrue(resources.size() >= 2,
                    "Should have at least 2 EXTERNAL_RESOURCE entities, got " + resources.size());
            assertTrue(resources.stream().anyMatch(r -> r.name().contains("docs.example.com")),
                    "Should have resource for docs.example.com");
            assertTrue(resources.stream().anyMatch(r -> r.name().contains("github.com")),
                    "Should have resource for github.com");

            // HYPERLINKS_TO relations
            List<ExtractedRelation> links = findRelations(result, GraphConstants.REL_HYPERLINKS_TO);
            assertTrue(links.size() >= 2,
                    "Should have at least 2 HYPERLINKS_TO relations");

            // PDF_PAGE entities linked via ON_PAGE
            List<ExtractedEntity> pages = findEntities(result, GraphConstants.ENTITY_PDF_PAGE);
            assertFalse(pages.isEmpty(), "Should have PDF_PAGE entities from link page context");
        }
    }

    // ================================================================
    //  PDF with form fields (AcroForm)
    // ================================================================

    @Nested
    class PdfForms {

        @Test
        void pdfWithFormFieldsExtractsFormAndFieldEntities() throws Exception {
            Path pdfFile = tempDir.resolve("form.pdf");
            try (PDDocument doc = new PDDocument()) {
                doc.getDocumentInformation().setTitle("Application Form");

                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);

                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                    cs.newLineAtOffset(72, 750);
                    cs.showText("Application Form");
                    cs.endText();
                }

                // Create AcroForm with fields
                PDAcroForm acroForm = new PDAcroForm(doc);
                doc.getDocumentCatalog().setAcroForm(acroForm);
                // Set up default resources with a font for field appearance generation
                org.apache.pdfbox.pdmodel.PDResources resources = new org.apache.pdfbox.pdmodel.PDResources();
                resources.put(org.apache.pdfbox.cos.COSName.getPDFName("Helv"),
                        new PDType1Font(Standard14Fonts.FontName.HELVETICA));
                acroForm.setDefaultResources(resources);
                String defaultAppearance = "/Helv 12 Tf 0 g";
                acroForm.setDefaultAppearance(defaultAppearance);

                PDTextField nameField = new PDTextField(acroForm);
                nameField.setPartialName("applicant_name");
                nameField.setDefaultAppearance(defaultAppearance);
                nameField.setValue("John Doe");
                acroForm.getFields().add(nameField);

                PDTextField emailField = new PDTextField(acroForm);
                emailField.setPartialName("email_address");
                emailField.setDefaultAppearance(defaultAppearance);
                emailField.setValue("john@example.com");
                acroForm.getFields().add(emailField);

                PDTextField positionField = new PDTextField(acroForm);
                positionField.setPartialName("position");
                positionField.setDefaultAppearance(defaultAppearance);
                positionField.setValue("Senior Engineer");
                positionField.setReadOnly(true);
                acroForm.getFields().add(positionField);

                doc.save(pdfFile.toFile());
            }

            List<Document> docs = loadFile(pdfFile);

            // Find the form fields document
            Document formDoc = docs.stream()
                    .filter(d -> GraphConstants.EXTRACTION_TYPE_FORM_FIELDS.equals(
                            d.getMetadata().get(GraphConstants.META_PDF_EXTRACTION_TYPE)))
                    .findFirst().orElse(null);
            assertNotNull(formDoc, "Should produce a form fields document");
            assertTrue(formDoc.getText().contains("applicant_name"),
                    "Form text should contain field names");

            ExtractionResult result = extractGraph(formDoc);

            // PDF_FORM entity
            ExtractedEntity formEntity = findEntity(result, GraphConstants.ENTITY_PDF_FORM);
            assertNotNull(formEntity, "Should produce PDF_FORM entity");
            assertNotNull(findRelation(result, GraphConstants.REL_HAS_FORM),
                    "Should have HAS_FORM relation");

            // FORM_FIELD entities
            List<ExtractedEntity> fields = findEntities(result, GraphConstants.ENTITY_FORM_FIELD);
            assertTrue(fields.size() >= 3,
                    "Should have at least 3 FORM_FIELD entities, got " + fields.size());
            assertTrue(fields.stream().anyMatch(f -> f.name().equals("applicant_name")),
                    "Should have 'applicant_name' field");
            assertTrue(fields.stream().anyMatch(f -> f.name().equals("email_address")),
                    "Should have 'email_address' field");

            // HAS_FORM_FIELD relations
            List<ExtractedRelation> fieldRels = findRelations(result, GraphConstants.REL_HAS_FORM_FIELD);
            assertTrue(fieldRels.size() >= 3,
                    "Should have at least 3 HAS_FORM_FIELD relations");

            // Field metadata — check a specific field has value
            ExtractedEntity nameFieldEntity = fields.stream()
                    .filter(f -> f.name().equals("applicant_name"))
                    .findFirst().orElse(null);
            assertNotNull(nameFieldEntity);
            assertEquals("John Doe", nameFieldEntity.properties().get("value"));
        }
    }

    // ================================================================
    //  Multi-page PDF (page count, cross-extraction)
    // ================================================================

    @Nested
    class MultiPagePdf {

        @Test
        void multiPagePdfExtractsCorrectPageCount() throws Exception {
            Path pdfFile = tempDir.resolve("multipage.pdf");
            try (PDDocument doc = new PDDocument()) {
                doc.getDocumentInformation().setTitle("Multi-Page Report");
                doc.getDocumentInformation().setAuthor("Report Generator");
                doc.getDocumentInformation().setKeywords("report, multi-page, data");

                for (int i = 0; i < 5; i++) {
                    PDPage page = new PDPage(PDRectangle.A4);
                    doc.addPage(page);
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        cs.beginText();
                        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                        cs.newLineAtOffset(72, 700);
                        cs.showText("Chapter " + (i + 1) + ": Content for page " + (i + 1));
                        cs.endText();

                        cs.beginText();
                        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        cs.newLineAtOffset(72, 670);
                        cs.showText("Detailed analysis follows for this section of the report.");
                        cs.endText();
                    }
                }

                doc.save(pdfFile.toFile());
            }

            List<Document> docs = loadFile(pdfFile);

            // Full document should have all content
            Document mainDoc = docs.stream()
                    .filter(d -> GraphConstants.EXTRACTION_TYPE_FULL_DOCUMENT.equals(
                            d.getMetadata().get(GraphConstants.META_PDF_EXTRACTION_TYPE)))
                    .findFirst().orElse(docs.get(0));

            assertEquals(5, mainDoc.getMetadata().get(GraphConstants.META_PAGE_COUNT),
                    "Should report 5 pages");

            // Full text should include content from all pages
            for (int i = 1; i <= 5; i++) {
                assertTrue(mainDoc.getText().contains("Chapter " + i),
                        "Full text should contain Chapter " + i);
            }

            ExtractionResult result = extractGraph(mainDoc);

            // PDF_DOCUMENT entity with page count
            ExtractedEntity pdfDoc = findEntity(result, GraphConstants.ENTITY_PDF_DOCUMENT);
            assertNotNull(pdfDoc);
            assertEquals("5", pdfDoc.properties().get(GraphConstants.META_PAGE_COUNT));
        }
    }

    // ================================================================
    //  PDF with URLs in body text
    // ================================================================

    @Nested
    class PdfBodyTextUrls {

        @Test
        void pdfWithUrlsInBodyTextExtractsExternalResources() throws Exception {
            Path pdfFile = tempDir.resolve("with-urls.pdf");
            try (PDDocument doc = new PDDocument()) {
                doc.getDocumentInformation().setTitle("Technical Guide");

                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(72, 700);
                    cs.showText("For documentation see https://docs.kompile.ai/guide");
                    cs.endText();

                    cs.beginText();
                    cs.newLineAtOffset(72, 680);
                    cs.showText("Report issues at https://github.com/kompile/issues");
                    cs.endText();
                }

                doc.save(pdfFile.toFile());
            }

            List<Document> docs = loadFile(pdfFile);
            Document mainDoc = docs.stream()
                    .filter(d -> GraphConstants.EXTRACTION_TYPE_FULL_DOCUMENT.equals(
                            d.getMetadata().get(GraphConstants.META_PDF_EXTRACTION_TYPE)))
                    .findFirst().orElse(docs.get(0));

            ExtractionResult result = extractGraph(mainDoc);

            // URLs in body text should produce EXTERNAL_RESOURCE entities
            List<ExtractedEntity> resources = findEntities(result, GraphConstants.ENTITY_EXTERNAL_RESOURCE);
            assertTrue(resources.size() >= 2,
                    "Should extract at least 2 URLs from body text, got " + resources.size());

            List<ExtractedRelation> hyperlinks = findRelations(result, GraphConstants.REL_HYPERLINKS_TO);
            assertTrue(hyperlinks.size() >= 2,
                    "Should have at least 2 HYPERLINKS_TO relations from body text URLs");
        }
    }

    // ================================================================
    //  Batch extraction across multiple PDF docs
    // ================================================================

    @Nested
    class BatchExtraction {

        @Test
        void batchExtractionDeduplicatesAuthorAcrossDocs() throws Exception {
            // Create two PDFs with the same author
            Path pdf1 = tempDir.resolve("doc1.pdf");
            Path pdf2 = tempDir.resolve("doc2.pdf");

            for (Path pdfPath : List.of(pdf1, pdf2)) {
                try (PDDocument doc = new PDDocument()) {
                    doc.getDocumentInformation().setAuthor("Shared Author");
                    doc.getDocumentInformation().setTitle("Document " + pdfPath.getFileName());
                    PDPage page = new PDPage();
                    doc.addPage(page);
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        cs.beginText();
                        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        cs.newLineAtOffset(72, 700);
                        cs.showText("Content of " + pdfPath.getFileName());
                        cs.endText();
                    }
                    doc.save(pdfPath.toFile());
                }
            }

            List<Document> allDocs = new ArrayList<>();
            // Get only fullDocument extraction type from each
            for (Path p : List.of(pdf1, pdf2)) {
                List<Document> docs = loadFile(p);
                docs.stream()
                        .filter(d -> GraphConstants.EXTRACTION_TYPE_FULL_DOCUMENT.equals(
                                d.getMetadata().get(GraphConstants.META_PDF_EXTRACTION_TYPE)))
                        .forEach(allDocs::add);
            }

            assertEquals(2, allDocs.size(), "Should have 2 full-document docs");

            ExtractionResult batchResult = extractor.extractBatch(allDocs);

            // "Shared Author" should appear once after dedup
            long authorCount = batchResult.entities().stream()
                    .filter(e -> GraphConstants.ENTITY_PERSON.equals(e.type()))
                    .filter(e -> e.name().contains("Shared Author"))
                    .count();
            assertEquals(1, authorCount,
                    "Same author across PDFs should be deduplicated in batch extraction");

            // But should have 2 PDF_DOCUMENT entities
            long docCount = batchResult.entities().stream()
                    .filter(e -> GraphConstants.ENTITY_PDF_DOCUMENT.equals(e.type()))
                    .count();
            assertEquals(2, docCount,
                    "Should have 2 PDF_DOCUMENT entities");

            // And 2 AUTHORED_BY relations (one per document)
            long authRelCount = batchResult.relations().stream()
                    .filter(r -> GraphConstants.REL_AUTHORED_BY.equals(r.type()))
                    .count();
            assertEquals(2, authRelCount,
                    "Should have 2 AUTHORED_BY relations (one per doc)");
        }
    }

    // ================================================================
    //  Edge cases
    // ================================================================

    @Nested
    class EdgeCases {

        @Test
        void emptyPdfProducesDocumentEntityOnly() throws Exception {
            Path pdfFile = tempDir.resolve("empty.pdf");
            try (PDDocument doc = new PDDocument()) {
                // Just one blank page, no metadata
                doc.addPage(new PDPage());
                doc.save(pdfFile.toFile());
            }

            List<Document> docs = loadFile(pdfFile);
            Document mainDoc = docs.stream()
                    .filter(d -> GraphConstants.EXTRACTION_TYPE_FULL_DOCUMENT.equals(
                            d.getMetadata().get(GraphConstants.META_PDF_EXTRACTION_TYPE)))
                    .findFirst().orElse(docs.get(0));

            ExtractionResult result = extractGraph(mainDoc);

            // Should still produce PDF_DOCUMENT entity
            ExtractedEntity pdfDoc = findEntity(result, GraphConstants.ENTITY_PDF_DOCUMENT);
            assertNotNull(pdfDoc, "Even empty PDF should produce PDF_DOCUMENT entity");
            assertEquals("empty.pdf", pdfDoc.name(),
                    "Without title, should use filename as name");

            // No PERSON, TOPIC, etc.
            assertTrue(findEntities(result, GraphConstants.ENTITY_PERSON).isEmpty(),
                    "No author → no PERSON entities");
            assertTrue(findEntities(result, GraphConstants.ENTITY_TOPIC).isEmpty(),
                    "No keywords → no TOPIC entities");
        }

        @Test
        void pdfWithCreationDateExtractsDateEntity() throws Exception {
            Path pdfFile = tempDir.resolve("dated.pdf");
            try (PDDocument doc = new PDDocument()) {
                doc.getDocumentInformation().setTitle("Dated Document");
                Calendar cal = Calendar.getInstance();
                cal.set(2025, Calendar.MARCH, 15, 10, 30, 0);
                doc.getDocumentInformation().setCreationDate(cal);

                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(72, 700);
                    cs.showText("Document with creation date.");
                    cs.endText();
                }

                doc.save(pdfFile.toFile());
            }

            List<Document> docs = loadFile(pdfFile);
            Document mainDoc = docs.stream()
                    .filter(d -> GraphConstants.EXTRACTION_TYPE_FULL_DOCUMENT.equals(
                            d.getMetadata().get(GraphConstants.META_PDF_EXTRACTION_TYPE)))
                    .findFirst().orElse(docs.get(0));

            ExtractionResult result = extractGraph(mainDoc);

            // DATE entity from creation date
            List<ExtractedEntity> dates = findEntities(result, GraphConstants.ENTITY_DATE);
            assertFalse(dates.isEmpty(), "Should extract DATE entity from creation date");

            // PUBLISHED_ON relation
            assertNotNull(findRelation(result, GraphConstants.REL_PUBLISHED_ON),
                    "Should have PUBLISHED_ON relation for creation date");
        }

        @Test
        void pdfWithLanguageSetsCatalogLanguage() throws Exception {
            Path pdfFile = tempDir.resolve("lang.pdf");
            try (PDDocument doc = new PDDocument()) {
                doc.getDocumentInformation().setTitle("French Document");
                doc.getDocumentCatalog().setLanguage("fr-FR");

                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(72, 700);
                    cs.showText("Document en francais.");
                    cs.endText();
                }

                doc.save(pdfFile.toFile());
            }

            List<Document> docs = loadFile(pdfFile);
            Document mainDoc = docs.stream()
                    .filter(d -> GraphConstants.EXTRACTION_TYPE_FULL_DOCUMENT.equals(
                            d.getMetadata().get(GraphConstants.META_PDF_EXTRACTION_TYPE)))
                    .findFirst().orElse(docs.get(0));

            // Language should be in metadata
            assertEquals("fr-FR", mainDoc.getMetadata().get(GraphConstants.META_PDF_LANGUAGE));

            ExtractionResult result = extractGraph(mainDoc);

            // PDF_DOCUMENT entity should have language property
            ExtractedEntity pdfDoc = findEntity(result, GraphConstants.ENTITY_PDF_DOCUMENT);
            assertNotNull(pdfDoc);
            assertEquals("fr-FR", pdfDoc.properties().get(GraphConstants.PROP_LANGUAGE),
                    "PDF_DOCUMENT should have language property set from catalog");
        }
    }

    // ================================================================
    //  Combined complex PDF
    // ================================================================

    @Nested
    class ComplexPdf {

        @Test
        void complexPdfWithMultipleFeaturesExtractsAllEntities() throws Exception {
            Path pdfFile = tempDir.resolve("complex.pdf");
            try (PDDocument doc = new PDDocument()) {
                PDDocumentInformation info = doc.getDocumentInformation();
                info.setTitle("Comprehensive Analysis");
                info.setAuthor("Dr. Maria Garcia");
                info.setSubject("Complex document testing");
                info.setKeywords("analysis, testing, comprehensive");
                info.setProducer("Test Producer Tool");

                // Page 1: Main content with annotation
                PDPage page1 = new PDPage(PDRectangle.A4);
                doc.addPage(page1);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page1)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 18);
                    cs.newLineAtOffset(72, 750);
                    cs.showText("Comprehensive Analysis");
                    cs.endText();

                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(72, 710);
                    cs.showText("This document demonstrates comprehensive graph extraction.");
                    cs.endText();

                    cs.beginText();
                    cs.newLineAtOffset(72, 690);
                    cs.showText("See https://research.example.com/paper for the full study.");
                    cs.endText();
                }

                // Annotation on page 1
                PDAnnotationText note = new PDAnnotationText();
                note.setContents("Excellent introduction");
                note.setTitlePopup("Reviewer One");
                note.setRectangle(new PDRectangle(400, 710, 20, 20));
                page1.getAnnotations().add(note);

                // Hyperlink on page 1
                PDAnnotationLink link = new PDAnnotationLink();
                PDActionURI uri = new PDActionURI();
                uri.setURI("https://research.example.com/paper");
                link.setAction(uri);
                link.setRectangle(new PDRectangle(72, 680, 300, 15));
                page1.getAnnotations().add(link);

                // Page 2: More content
                PDPage page2 = new PDPage(PDRectangle.A4);
                doc.addPage(page2);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page2)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(72, 700);
                    cs.showText("Data analysis methodology and results are presented here.");
                    cs.endText();
                }

                // Bookmarks
                PDDocumentOutline outline = new PDDocumentOutline();
                doc.getDocumentCatalog().setDocumentOutline(outline);

                PDOutlineItem bm1 = new PDOutlineItem();
                bm1.setTitle("Introduction");
                PDPageFitDestination dest1 = new PDPageFitDestination();
                dest1.setPage(page1);
                bm1.setDestination(dest1);
                outline.addLast(bm1);

                PDOutlineItem bm2 = new PDOutlineItem();
                bm2.setTitle("Methodology");
                PDPageFitDestination dest2 = new PDPageFitDestination();
                dest2.setPage(page2);
                bm2.setDestination(dest2);
                outline.addLast(bm2);

                // Form
                PDAcroForm acroForm = new PDAcroForm(doc);
                doc.getDocumentCatalog().setAcroForm(acroForm);
                org.apache.pdfbox.pdmodel.PDResources formResources = new org.apache.pdfbox.pdmodel.PDResources();
                formResources.put(org.apache.pdfbox.cos.COSName.getPDFName("Helv"),
                        new PDType1Font(Standard14Fonts.FontName.HELVETICA));
                acroForm.setDefaultResources(formResources);
                String da = "/Helv 12 Tf 0 g";
                acroForm.setDefaultAppearance(da);
                PDTextField searchField = new PDTextField(acroForm);
                searchField.setPartialName("search_query");
                searchField.setDefaultAppearance(da);
                searchField.setValue("graph extraction");
                acroForm.getFields().add(searchField);

                doc.save(pdfFile.toFile());
            }

            List<Document> docs = loadFile(pdfFile);
            assertTrue(docs.size() >= 4,
                    "Should have at least 4 docs (full text, annotations, bookmarks, form), got " + docs.size());

            // Collect all unique entity types across all documents
            Set<String> allEntityTypes = new HashSet<>();
            Set<String> allRelationTypes = new HashSet<>();

            for (Document d : docs) {
                if (extractor.canExtract(d)) {
                    ExtractionResult r = extractGraph(d);
                    r.entities().forEach(e -> allEntityTypes.add(e.type()));
                    r.relations().forEach(rel -> allRelationTypes.add(rel.type()));
                }
            }

            // Verify comprehensive entity types extracted
            assertTrue(allEntityTypes.contains(GraphConstants.ENTITY_PDF_DOCUMENT),
                    "Should have PDF_DOCUMENT: " + allEntityTypes);
            assertTrue(allEntityTypes.contains(GraphConstants.ENTITY_PERSON),
                    "Should have PERSON from author + annotation author: " + allEntityTypes);
            assertTrue(allEntityTypes.contains(GraphConstants.ENTITY_ORGANIZATION),
                    "Should have ORGANIZATION from producer: " + allEntityTypes);
            assertTrue(allEntityTypes.contains(GraphConstants.ENTITY_TOPIC),
                    "Should have TOPIC from keywords + subject: " + allEntityTypes);
            assertTrue(allEntityTypes.contains(GraphConstants.ENTITY_EXTERNAL_RESOURCE),
                    "Should have EXTERNAL_RESOURCE from hyperlinks: " + allEntityTypes);
            assertTrue(allEntityTypes.contains(GraphConstants.ENTITY_PDF_ANNOTATION),
                    "Should have PDF_ANNOTATION from sticky note: " + allEntityTypes);
            assertTrue(allEntityTypes.contains(GraphConstants.ENTITY_PDF_SECTION),
                    "Should have PDF_SECTION from bookmarks: " + allEntityTypes);
            assertTrue(allEntityTypes.contains(GraphConstants.ENTITY_PDF_FORM),
                    "Should have PDF_FORM from AcroForm: " + allEntityTypes);
            assertTrue(allEntityTypes.contains(GraphConstants.ENTITY_FORM_FIELD),
                    "Should have FORM_FIELD from form fields: " + allEntityTypes);

            // Verify comprehensive relation types
            assertTrue(allRelationTypes.contains(GraphConstants.REL_AUTHORED_BY),
                    "Should have AUTHORED_BY: " + allRelationTypes);
            assertTrue(allRelationTypes.contains(GraphConstants.REL_PRODUCED_BY),
                    "Should have PRODUCED_BY: " + allRelationTypes);
            assertTrue(allRelationTypes.contains(GraphConstants.REL_HAS_TOPIC),
                    "Should have HAS_TOPIC: " + allRelationTypes);
            assertTrue(allRelationTypes.contains(GraphConstants.REL_HYPERLINKS_TO),
                    "Should have HYPERLINKS_TO: " + allRelationTypes);
            assertTrue(allRelationTypes.contains(GraphConstants.REL_HAS_ANNOTATION),
                    "Should have HAS_ANNOTATION: " + allRelationTypes);
            assertTrue(allRelationTypes.contains(GraphConstants.REL_HAS_SECTION),
                    "Should have HAS_SECTION: " + allRelationTypes);
            assertTrue(allRelationTypes.contains(GraphConstants.REL_HAS_FORM),
                    "Should have HAS_FORM: " + allRelationTypes);
            assertTrue(allRelationTypes.contains(GraphConstants.REL_HAS_FORM_FIELD),
                    "Should have HAS_FORM_FIELD: " + allRelationTypes);
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
