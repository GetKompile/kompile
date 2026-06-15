/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.graphrag;

import ai.kompile.core.graphrag.DocumentGraphExtractor;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import ai.kompile.loader.email.inbox.EmailGraphExtractor;
import ai.kompile.loader.pdf.PdfGraphExtractor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.document.Document;

import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies that DocumentGraphExtractor dispatch correctly routes every known
 * document type to at least one extractor and produces non-empty results.
 * Uses reflective instantiation for loader-module extractors that may not
 * be on the compile classpath.
 */
class DocumentGraphExtractorDispatchTest {

    private static List<DocumentGraphExtractor> extractors;
    private static final Set<String> loadedExtractorNames = new LinkedHashSet<>();

    @BeforeAll
    static void setUp() {
        List<DocumentGraphExtractor> list = new ArrayList<>();
        // Direct dependencies available at compile time
        list.add(new ConfluenceGraphExtractor());
        loadedExtractorNames.add("ConfluenceGraphExtractor");

        list.add(new PdfGraphExtractor());
        loadedExtractorNames.add("PdfGraphExtractor");

        list.add(new EmailGraphExtractor());
        loadedExtractorNames.add("EmailGraphExtractor");

        // Reflectively load extractors from loader modules
        tryLoad(list, "ai.kompile.loader.microsoft.OfficeGraphExtractor");
        tryLoad(list, "ai.kompile.loader.web.HtmlWebGraphExtractor");
        tryLoad(list, "ai.kompile.loader.onedrive.OneDriveGraphExtractor");
        tryLoad(list, "ai.kompile.loader.tika.TikaGenericGraphExtractor");
        tryLoad(list, "ai.kompile.loader.slack.SlackGraphExtractor");
        tryLoad(list, "ai.kompile.loader.discord.DiscordGraphExtractor");
        tryLoad(list, "ai.kompile.loader.gmail.GmailGraphExtractor");
        tryLoad(list, "ai.kompile.loader.gdocs.GoogleDocsGraphExtractor");
        tryLoad(list, "ai.kompile.loader.gworkspace.GWorkspaceGraphExtractor");

        extractors = Collections.unmodifiableList(list);
    }

    private static void tryLoad(List<DocumentGraphExtractor> list, String className) {
        try {
            Class<?> clazz = Class.forName(className);
            DocumentGraphExtractor instance = (DocumentGraphExtractor) clazz.getDeclaredConstructor().newInstance();
            list.add(instance);
            loadedExtractorNames.add(clazz.getSimpleName());
        } catch (Exception ignored) {
            // Not on classpath — skip
        }
    }

    // ── Every document type must match at least one extractor ────────

    /**
     * Each argument: (description, Document, requiredExtractor).
     * requiredExtractor is the simple class name of the extractor that should handle this doc.
     * If that extractor wasn't loaded (not on classpath), the test is skipped via assumeTrue.
     */
    static Stream<Arguments> documentTypes() {
        return Stream.of(
                // PDF
                Arguments.of("PDF via documentType",
                        docWithMeta("documentType", "PDF Document", "fileName", "report.pdf", "source", "/docs/report.pdf"),
                        "PdfGraphExtractor"),
                Arguments.of("OCR PDF",
                        docWithMeta("documentType", "PDF Document", "fileName", "scanned.pdf", "source", "/docs/scanned.pdf", "ocr_processed", true),
                        "PdfGraphExtractor"),

                // Word
                Arguments.of("Word DOCX",
                        docWithMeta("documentType", "Microsoft Word Document (.docx)", "fileName", "letter.docx", "source", "/docs/letter.docx"),
                        "OfficeGraphExtractor"),
                Arguments.of("Streaming DOCX",
                        docWithMeta("documentType", "DOCX", "fileName", "streamed.docx", "loader", "Microsoft Office Loader", "source", "/docs/streamed.docx"),
                        "OfficeGraphExtractor"),

                // Excel
                Arguments.of("Excel XLSX",
                        docWithMeta("documentType", "Microsoft Excel Spreadsheet (.xlsx)", "fileName", "data.xlsx", "source", "/docs/data.xlsx"),
                        "OfficeGraphExtractor"),

                // PowerPoint
                Arguments.of("Streaming PPTX",
                        docWithMeta("documentType", "PPTX", "fileName", "deck.pptx", "source", "/docs/deck.pptx"),
                        "OfficeGraphExtractor"),

                // HTML
                Arguments.of("HTML via loader",
                        docWithMeta("loader", "Web/HTML Loader", "source", "https://example.com", "title", "Example"),
                        "HtmlWebGraphExtractor"),

                // Email
                Arguments.of("Email",
                        docWithMeta("email.from", "alice@example.com", "email.subject", "Report", "email.date", "2025-01-15"),
                        "EmailGraphExtractor"),

                // Confluence
                Arguments.of("Confluence page",
                        docWithMeta("source_type", "confluence", "confluence.pageId", "12345", "confluence.title", "Design Doc"),
                        "ConfluenceGraphExtractor"),

                // OneDrive
                Arguments.of("OneDrive file",
                        docWithMeta("source_type", "ONEDRIVE", "onedrive_item_id", "item123", "onedrive_name", "budget.xlsx"),
                        "OneDriveGraphExtractor"),

                // Slack
                Arguments.of("Slack message",
                        docWithMeta("slack.channelId", "C123", "slack.userId", "U456", "slack.messageTs", "1234567.000100"),
                        "SlackGraphExtractor"),

                // Discord
                Arguments.of("Discord message",
                        docWithMeta("discord.guildId", "G123", "discord.channelId", "CH456", "discord.messageId", "MSG789",
                                "discord.authorId", "USER1", "discord.authorName", "alice"),
                        "DiscordGraphExtractor"),

                // Gmail
                Arguments.of("Gmail message",
                        docWithMeta("source_type", "gmail", "gmail.messageId", "msg123",
                                "gmail.from", "sender@example.com", "gmail.subject", "Test Email"),
                        "GmailGraphExtractor"),

                // Google Docs
                Arguments.of("Google Docs document",
                        docWithMeta("source_type", "gdocs", "gdocs.documentId", "doc123",
                                "gdocs.title", "Design Spec"),
                        "GoogleDocsGraphExtractor"),

                // Google Workspace (Google Drive file)
                Arguments.of("Google Drive file",
                        docWithMeta("source_type", "GDRIVE", "gdrive_file_id", "file123",
                                "gdrive_file_name", "budget.xlsx", "gdrive_mime_type", "application/vnd.google-apps.spreadsheet"),
                        "GWorkspaceGraphExtractor"),

                // Tika - RTF
                Arguments.of("RTF via Tika",
                        docWithMeta("documentType", "Rich Text Format", "loader", "Tika Loader",
                                "fileName", "notes.rtf", "source", "/docs/notes.rtf"),
                        "TikaGenericGraphExtractor"),

                // Tika - plain text
                Arguments.of("Plain text via Tika",
                        docWithMeta("documentType", "Plain Text", "loader", "Tika Loader",
                                "fileName", "readme.txt", "source", "/docs/readme.txt"),
                        "TikaGenericGraphExtractor"),

                // Tika - EPUB
                Arguments.of("EPUB via Tika",
                        docWithMeta("documentType", "EPUB", "loader", "Tika Loader",
                                "fileName", "book.epub", "source", "/docs/book.epub"),
                        "TikaGenericGraphExtractor"),

                // Tika - CSV
                Arguments.of("CSV via Tika",
                        docWithMeta("documentType", "CSV", "loader", "Tika Loader",
                                "fileName", "data.csv", "source", "/docs/data.csv"),
                        "TikaGenericGraphExtractor"),

                // Tika - JSON
                Arguments.of("JSON via Tika",
                        docWithMeta("documentType", "JSON", "loader", "Tika Loader",
                                "fileName", "config.json", "source", "/docs/config.json"),
                        "TikaGenericGraphExtractor"),

                // Tika - XML
                Arguments.of("XML via Tika",
                        docWithMeta("documentType", "XML", "loader", "Tika Loader",
                                "fileName", "data.xml", "source", "/docs/data.xml"),
                        "TikaGenericGraphExtractor"),

                // Tika - Markdown
                Arguments.of("Markdown via Tika",
                        docWithMeta("documentType", "Markdown", "loader", "Tika Loader",
                                "fileName", "README.md", "source", "/docs/README.md"),
                        "TikaGenericGraphExtractor"),

                // Google Calendar event
                Arguments.of("Google Calendar event",
                        docWithMeta("gworkspace.service", "calendar",
                                "gworkspace.calendar.eventId", "evt123",
                                "gworkspace.calendar.summary", "Team Standup"),
                        "GWorkspaceGraphExtractor"),

                // Google Sheets spreadsheet
                Arguments.of("Google Sheets via Drive",
                        docWithMeta("source_type", "GDRIVE", "gdrive_file_id", "sheet123",
                                "gdrive_file_name", "Q4 Budget", "gdrive_mime_type", "application/vnd.google-apps.spreadsheet"),
                        "GWorkspaceGraphExtractor"),

                // PST Email via Office
                Arguments.of("PST email via Office",
                        docWithMeta("documentType", "Outlook PST Message", "source", "/emails/pst/msg1",
                                "email.from", "alice@test.com", "email.subject", "Weekly Report"),
                        "OfficeGraphExtractor")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("documentTypes")
    void everyDocumentTypeMatchesAtLeastOneExtractor(String docDescription, Document doc, String requiredExtractor) {
        assumeTrue(loadedExtractorNames.contains(requiredExtractor),
                requiredExtractor + " not on classpath — skipping");

        List<String> matching = new ArrayList<>();
        for (DocumentGraphExtractor extractor : extractors) {
            if (extractor.canExtract(doc)) {
                matching.add(extractor.getClass().getSimpleName());
            }
        }
        assertFalse(matching.isEmpty(),
                docDescription + " — no extractor matched! Metadata: " + doc.getMetadata());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("documentTypes")
    void matchedExtractorProducesEntities(String docDescription, Document doc, String requiredExtractor) {
        assumeTrue(loadedExtractorNames.contains(requiredExtractor),
                requiredExtractor + " not on classpath — skipping");

        for (DocumentGraphExtractor extractor : extractors) {
            if (extractor.canExtract(doc)) {
                ExtractionResult result = extractor.extract(doc);
                assertNotNull(result, docDescription + " — null result");
                assertFalse(result.entities().isEmpty(),
                        docDescription + " — " + extractor.getClass().getSimpleName()
                                + " matched but produced zero entities");
                break;
            }
        }
    }

    // ── Cross-extractor exclusivity ─────────────────────────────────

    @Test
    void pdfDoesNotMatchConfluence() {
        Document doc = docWithMeta("documentType", "PDF Document", "fileName", "report.pdf");
        assertFalse(new ConfluenceGraphExtractor().canExtract(doc));
    }

    @Test
    void confluenceDoesNotMatchPdf() {
        Document doc = docWithMeta("source_type", "confluence", "confluence.pageId", "123");
        assertFalse(new PdfGraphExtractor().canExtract(doc));
    }

    @Test
    void emailDoesNotMatchPdfOrConfluence() {
        Document doc = docWithMeta("email.from", "alice@test.com", "email.subject", "Hi");
        assertFalse(new PdfGraphExtractor().canExtract(doc));
        assertFalse(new ConfluenceGraphExtractor().canExtract(doc));
    }

    // ── Null / empty safety ─────────────────────────────────────────

    @Test
    void nullDocumentNeverMatches() {
        for (DocumentGraphExtractor extractor : extractors) {
            assertFalse(extractor.canExtract(null),
                    extractor.getClass().getSimpleName() + " should not match null");
        }
    }

    @Test
    void emptyMetadataDocumentNeverMatches() {
        Document doc = new Document("content");
        for (DocumentGraphExtractor extractor : extractors) {
            assertFalse(extractor.canExtract(doc),
                    extractor.getClass().getSimpleName() + " should not match empty metadata");
        }
    }

    @Test
    void extractorListHasAtLeastCoreExtractors() {
        // Verify we loaded at least the 3 compile-time extractors
        assertTrue(extractors.size() >= 3,
                "Expected at least ConfluenceGraphExtractor + PdfGraphExtractor + EmailGraphExtractor");
    }

    // ── Entity/relation completeness for core extractors ────────────

    @Test
    void pdfExtractorProducesDocumentEntity() {
        Document doc = docWithMeta("documentType", "PDF Document", "fileName", "report.pdf",
                "source", "/docs/report.pdf", "title", "Annual Report", "author", "Jane Doe");
        PdfGraphExtractor extractor = new PdfGraphExtractor();
        ExtractionResult result = extractor.extract(doc);

        // Must produce document + author entities and AUTHORED_BY relation
        assertTrue(result.entities().stream().anyMatch(e -> e.type().contains("PDF")),
                "Should produce a PDF document entity");
        assertTrue(result.entities().stream().anyMatch(e -> "PERSON".equals(e.type())),
                "Should produce a PERSON entity from author");
        assertTrue(result.relations().stream().anyMatch(r -> "AUTHORED_BY".equals(r.type())),
                "Should produce AUTHORED_BY relation");
    }

    @Test
    void emailExtractorProducesFullGraph() {
        Document doc = docWithMeta(
                "email.from", "alice@example.com",
                "email.to", "bob@example.com",
                "email.subject", "Quarterly Report",
                "email.date", "2025-01-15",
                "email.messageId", "msg-123@example.com"
        );
        EmailGraphExtractor extractor = new EmailGraphExtractor();
        ExtractionResult result = extractor.extract(doc);

        // Must produce message + sender + recipient entities
        assertTrue(result.entities().stream().anyMatch(e -> "EMAIL_MESSAGE".equals(e.type())),
                "Should produce EMAIL_MESSAGE entity");
        assertTrue(result.entities().stream().anyMatch(e -> "PERSON".equals(e.type()) || "EMAIL_PERSON".equals(e.type())),
                "Should produce person entities from sender/recipient");
        // Must produce SENT_BY and SENT_TO relations
        assertTrue(result.relations().stream().anyMatch(r -> "SENT_BY".equals(r.type())),
                "Should produce SENT_BY relation");
        assertTrue(result.relations().stream().anyMatch(r -> "SENT_TO".equals(r.type())),
                "Should produce SENT_TO relation");
    }

    @Test
    void confluenceExtractorProducesFullGraph() {
        Document doc = docWithMeta(
                "source_type", "confluence",
                "confluence.pageId", "12345",
                "confluence.title", "Design Document",
                "confluence.spaceKey", "ENG",
                "confluence.spaceName", "Engineering",
                "confluence.createdBy", "Jane Smith"
        );
        ConfluenceGraphExtractor extractor = new ConfluenceGraphExtractor();
        ExtractionResult result = extractor.extract(doc);

        // Must produce page + space + author entities
        assertTrue(result.entities().stream().anyMatch(e -> "CONFLUENCE_PAGE".equals(e.type())),
                "Should produce CONFLUENCE_PAGE entity");
        assertTrue(result.entities().stream().anyMatch(e -> "CONFLUENCE_SPACE".equals(e.type())),
                "Should produce CONFLUENCE_SPACE entity");
        assertTrue(result.entities().stream().anyMatch(e -> "PERSON".equals(e.type())),
                "Should produce PERSON entity from createdBy");
        // Must produce IN_SPACE and AUTHORED_BY relations
        assertTrue(result.relations().stream().anyMatch(r -> "IN_SPACE".equals(r.type())),
                "Should produce IN_SPACE relation");
        assertTrue(result.relations().stream().anyMatch(r -> "AUTHORED_BY".equals(r.type())),
                "Should produce AUTHORED_BY relation");
    }

    @Test
    void ocrPdfExtractorEnrichesOcrMetadata() {
        Document doc = docWithMeta(
                "documentType", "PDF Document",
                "fileName", "scanned.pdf",
                "source", "/docs/scanned.pdf",
                "ocr_processed", true,
                "pdf_processing_mode", "vlm",
                "vlm_model", "florence-2"
        );
        PdfGraphExtractor extractor = new PdfGraphExtractor();
        ExtractionResult result = extractor.extract(doc);

        assertFalse(result.entities().isEmpty(), "OCR PDF should produce entities");
        // The doc entity should carry OCR metadata as properties
        var docEntity = result.entities().get(0);
        assertEquals("true", docEntity.properties().get("ocrProcessed"),
                "Should annotate ocrProcessed=true on document entity");
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private static Document docWithMeta(Object... keyValues) {
        Map<String, Object> meta = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            meta.put((String) keyValues[i], keyValues[i + 1]);
        }
        Document doc = new Document("Sample document content");
        doc.getMetadata().putAll(meta);
        return doc;
    }
}
