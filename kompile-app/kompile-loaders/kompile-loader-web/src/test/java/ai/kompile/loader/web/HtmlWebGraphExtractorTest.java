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

package ai.kompile.loader.web;

import ai.kompile.core.graphrag.format.GraphExtractionSchema.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class HtmlWebGraphExtractorTest {

    private HtmlWebGraphExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new HtmlWebGraphExtractor();
    }

    // --- supportedDocumentTypes ---

    @Test
    void supportedDocumentTypesReturnsExpected() {
        List<String> types = extractor.supportedDocumentTypes();
        assertTrue(types.contains("html"));
        assertTrue(types.contains("web"));
        assertTrue(types.contains("webpage"));
    }

    // --- canExtract ---

    @Test
    void canExtractReturnsTrueForWebHtmlLoader() {
        Document doc = new Document("content", Map.of("loader", "web/html-loader"));
        assertTrue(extractor.canExtract(doc));
    }

    @Test
    void canExtractReturnsTrueForHtmlDocumentType() {
        Document doc = new Document("content", Map.of("documentType", "HTML"));
        assertTrue(extractor.canExtract(doc));
    }

    @Test
    void canExtractReturnsTrueForHtmlExtension() {
        Document doc = new Document("content", Map.of("fileName", "index.html"));
        assertTrue(extractor.canExtract(doc));
    }

    @Test
    void canExtractReturnsTrueForHtmExtension() {
        Document doc = new Document("content", Map.of("fileName", "page.htm"));
        assertTrue(extractor.canExtract(doc));
    }

    @Test
    void canExtractReturnsTrueForTikaHtmlContentType() {
        Document doc = new Document("content", Map.of("tika.contentType", "text/html"));
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
        Document doc = new Document("content", Map.of("documentType", "Word", "fileName", "doc.docx"));
        assertFalse(extractor.canExtract(doc));
    }

    // --- extract ---

    @Test
    void extractCreatesWebPageEntity() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "web/html-loader");
        meta.put("title", "My Web Page");
        meta.put("source", "https://example.com/page");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertFalse(result.entities().isEmpty());
        ExtractedEntity pageEntity = result.entities().get(0);
        assertEquals("My Web Page", pageEntity.name());
        assertEquals("WEB_PAGE", pageEntity.type());
        assertEquals(1.0, pageEntity.confidence());
    }

    @Test
    void extractUsesOgTitleWhenTitleAbsent() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "web/html-loader");
        meta.put("ogTitle", "OG Title");
        meta.put("source", "https://example.com");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertEquals("OG Title", result.entities().get(0).name());
    }

    @Test
    void extractUsesFileNameWhenNoTitle() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("fileName", "page.html");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertEquals("page.html", result.entities().get(0).name());
    }

    @Test
    void extractCreatesAuthoredByFromAuthor() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "web/html-loader");
        meta.put("title", "Article");
        meta.put("author", "John Doe");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e -> e.type().equals("PERSON") && e.name().equals("John Doe")));
        assertTrue(result.relations().stream().anyMatch(r -> r.type().equals("AUTHORED_BY")));
    }

    @Test
    void extractCreatesAuthoredByFromArticleAuthor() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "web/html-loader");
        meta.put("title", "Article");
        meta.put("articleAuthor", "Jane Doe");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e -> e.type().equals("PERSON") && e.name().equals("Jane Doe")));
    }

    @Test
    void extractPrefersAuthorOverArticleAuthor() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "web/html-loader");
        meta.put("title", "Article");
        meta.put("author", "Alice");
        meta.put("articleAuthor", "Bob");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e -> e.type().equals("PERSON") && e.name().equals("Alice")));
        assertFalse(result.entities().stream().anyMatch(e -> e.type().equals("PERSON") && e.name().equals("Bob")));
    }

    @Test
    void extractSplitsMultipleAuthors() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "web/html-loader");
        meta.put("title", "Article");
        meta.put("author", "Alice, Bob & Carol");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        long personCount = result.entities().stream().filter(e -> e.type().equals("PERSON")).count();
        assertEquals(3, personCount);
    }

    @Test
    void extractCreatesHasTopicFromKeywords() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "web/html-loader");
        meta.put("title", "Article");
        meta.put("keywords", "java, spring, microservices");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        long topicCount = result.entities().stream().filter(e -> e.type().equals("TOPIC")).count();
        assertEquals(3, topicCount);
        long hasTopicCount = result.relations().stream().filter(r -> r.type().equals("HAS_TOPIC")).count();
        assertEquals(3, hasTopicCount);
    }

    @Test
    void extractCreatesHostedOnFromSiteName() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "web/html-loader");
        meta.put("title", "Article");
        meta.put("siteName", "Example Blog");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e ->
                e.type().equals("WEBSITE") && e.name().equals("Example Blog")));
        assertTrue(result.relations().stream().anyMatch(r -> r.type().equals("HOSTED_ON")));
    }

    @Test
    void extractCreatesCanonicalOfWhenUrlDiffers() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "web/html-loader");
        meta.put("title", "Article");
        meta.put("source", "https://example.com/page?ref=twitter");
        meta.put("canonicalUrl", "https://example.com/page");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.relations().stream().anyMatch(r -> r.type().equals("CANONICAL_OF")));
        assertTrue(result.entities().stream().anyMatch(e ->
                e.type().equals("WEB_PAGE") && e.properties() != null
                        && "https://example.com/page".equals(e.properties().get("url"))));
    }

    @Test
    void extractDoesNotCreateCanonicalOfWhenUrlMatchesSource() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "web/html-loader");
        meta.put("title", "Article");
        meta.put("source", "https://example.com/page");
        meta.put("canonicalUrl", "https://example.com/page");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertFalse(result.relations().stream().anyMatch(r -> r.type().equals("CANONICAL_OF")));
    }

    @Test
    void extractDoesNotCreateCanonicalOfWhenSourceNull() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "web/html-loader");
        meta.put("title", "Article");
        meta.put("canonicalUrl", "https://example.com/page");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertFalse(result.relations().stream().anyMatch(r -> r.type().equals("CANONICAL_OF")));
    }

    @Test
    void extractReturnsPageEntityWithSparseMetadata() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "web/html-loader");
        Document doc = new Document("content", meta);
        ExtractionResult result = extractor.extract(doc);
        assertFalse(result.entities().isEmpty());
        assertEquals("Untitled Page", result.entities().get(0).name());
    }

    @Test
    void extractIncludesOgMetadata() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "web/html-loader");
        meta.put("title", "Article");
        meta.put("ogDescription", "A great article");
        meta.put("publishedTime", "2025-06-01");
        meta.put("language", "en");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity page = result.entities().get(0);
        assertEquals("A great article", page.properties().get("ogDescription"));
        assertEquals("2025-06-01", page.properties().get("publishedTime"));
        assertEquals("en", page.properties().get("language"));
    }

    @Test
    void extractSetsExtractionMetadata() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "web/html-loader");
        meta.put("title", "Page");
        meta.put("source", "https://example.com");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertNotNull(result.metadata());
        assertEquals("html-metadata-extractor", result.metadata().extractionModel());
    }

    // --- extractBatch ---

    @Test
    void extractBatchDeduplicatesEntities() {
        Map<String, Object> meta1 = new HashMap<>();
        meta1.put("loader", "web/html-loader");
        meta1.put("title", "Page 1");
        meta1.put("author", "Alice");
        Document doc1 = new Document("content1", meta1);

        Map<String, Object> meta2 = new HashMap<>();
        meta2.put("loader", "web/html-loader");
        meta2.put("title", "Page 2");
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
        meta1.put("loader", "web/html-loader");
        meta1.put("title", "Page 1");
        Document doc1 = new Document("content1", meta1);

        Map<String, Object> meta2 = new HashMap<>();
        meta2.put("fileName", "other.html");
        meta2.put("title", "Page 2");
        Document doc2 = new Document("content2", meta2);

        ExtractionResult result = extractor.extractBatch(List.of(doc1, doc2));

        assertEquals(2, result.entities().size());
    }

    @Test
    void extractIgnoresSingleCharKeywords() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "web/html-loader");
        meta.put("title", "Article");
        meta.put("keywords", "a, java, b, spring");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        long topicCount = result.entities().stream().filter(e -> e.type().equals("TOPIC")).count();
        assertEquals(2, topicCount);
    }

    // --- HTML rendered email extraction ---

    @Test
    void extractEmailSenderWhenContentTypeHintEmail() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "web/html-loader");
        meta.put("title", "Meeting Notes");
        meta.put("content_type_hint", "email");
        meta.put("email.from", "Alice Smith <alice@example.com>");
        meta.put("email.fromName", "Alice Smith");
        meta.put("email.fromAddress", "alice@example.com");
        meta.put("email.subject", "Meeting Notes");
        meta.put("email.date", "2025-01-15");
        Document doc = new Document("Email body content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e -> "PERSON".equals(e.type())),
                "Should create PERSON entity for email sender");
        assertTrue(result.relations().stream().anyMatch(r -> "SENT_BY".equals(r.type())),
                "Should create SENT_BY relation for sender");

        ExtractedEntity senderPerson = result.entities().stream()
                .filter(e -> "PERSON".equals(e.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("Alice Smith", senderPerson.name());
        assertEquals("alice@example.com", senderPerson.properties().get("email"));
    }

    @Test
    void extractEmailRecipientsWhenContentTypeHintEmail() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "web/html-loader");
        meta.put("title", "Hello");
        meta.put("content_type_hint", "email");
        meta.put("email.from", "sender@example.com");
        meta.put("email.to", "bob@example.com, carol@example.com");
        Document doc = new Document("Email body", meta);

        ExtractionResult result = extractor.extract(doc);

        long sentToCount = result.relations().stream()
                .filter(r -> "SENT_TO".equals(r.type())).count();
        assertEquals(2, sentToCount, "Should create SENT_TO relation for each To recipient");
    }

    @Test
    void extractEmailCcRecipientsWhenContentTypeHintEmail() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "web/html-loader");
        meta.put("title", "FYI");
        meta.put("content_type_hint", "email");
        meta.put("email.from", "sender@example.com");
        meta.put("email.cc", "dave@example.com");
        Document doc = new Document("CC email body", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.relations().stream().anyMatch(r -> "CC_TO".equals(r.type())),
                "Should create CC_TO relation for CC recipients");
    }

    @Test
    void extractEmailAttachmentsWhenPresent() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "web/html-loader");
        meta.put("title", "Report");
        meta.put("content_type_hint", "email");
        meta.put("email.from", "sender@example.com");
        meta.put("email.attachmentNames", List.of("report.pdf", "data.xlsx"));
        Document doc = new Document("See attached", meta);

        ExtractionResult result = extractor.extract(doc);

        long attachCount = result.entities().stream()
                .filter(e -> "ATTACHMENT".equals(e.type())).count();
        assertEquals(2, attachCount, "Should create ATTACHMENT entities for each attachment");
        long hasAttachCount = result.relations().stream()
                .filter(r -> "HAS_ATTACHMENT".equals(r.type())).count();
        assertEquals(2, hasAttachCount, "Should create HAS_ATTACHMENT relation for each attachment");
    }

    @Test
    void extractSetsEmailPropertiesOnPageEntity() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "web/html-loader");
        meta.put("title", "Important");
        meta.put("content_type_hint", "email");
        meta.put("email.from", "sender@example.com");
        meta.put("email.subject", "Important Notice");
        meta.put("email.date", "2025-06-01");
        Document doc = new Document("Email body", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity pageEntity = result.entities().stream()
                .filter(e -> "WEB_PAGE".equals(e.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("Important Notice", pageEntity.properties().get("emailSubject"));
        assertEquals("2025-06-01", pageEntity.properties().get("emailDate"));
        assertEquals("true", pageEntity.properties().get("isEmail"));
    }

    @Test
    void extractEmailMessageIdStoredOnPageEntity() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "web/html-loader");
        meta.put("title", "Test Email");
        meta.put("content_type_hint", "email");
        meta.put("email.from", "sender@example.com");
        meta.put("email.messageId", "<msg-123@example.com>");
        Document doc = new Document("Email body", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity pageEntity = result.entities().stream()
                .filter(e -> "WEB_PAGE".equals(e.type()))
                .findFirst().orElseThrow();
        assertEquals("<msg-123@example.com>", pageEntity.properties().get("messageId"),
                "Email messageId should be stored on the page entity");
    }

    @Test
    void extractEmailReferencesCreatesStubEntities() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "web/html-loader");
        meta.put("title", "Reply Email");
        meta.put("content_type_hint", "email");
        meta.put("email.from", "sender@example.com");
        meta.put("email.inReplyTo", "<parent@example.com>");
        meta.put("email.references", "<root@example.com> <middle@example.com> <parent@example.com>");
        Document doc = new Document("Email body", meta);

        ExtractionResult result = extractor.extract(doc);

        // parent@example.com handled by IN_REPLY_TO → REPLIED_TO, skipped in REFERENCES
        long referencesCount = result.relations().stream()
                .filter(r -> "REFERENCES".equals(r.type())).count();
        assertEquals(2, referencesCount,
                "Should create REFERENCES relations for root and middle (parent handled by REPLIED_TO)");

        assertTrue(result.entities().stream().anyMatch(e ->
                        "EMAIL_MESSAGE".equals(e.type()) &&
                        e.properties() != null &&
                        "<root@example.com>".equals(e.properties().get("messageId"))),
                "Should create stub EMAIL_MESSAGE entity for root reference");
    }

    @Test
    void extractEmailThreadEntityFromSubject() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "web/html-loader");
        meta.put("title", "Re: Meeting Notes");
        meta.put("content_type_hint", "email");
        meta.put("email.from", "sender@example.com");
        meta.put("email.subject", "Re: Meeting Notes");
        Document doc = new Document("Email body", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e ->
                        "EMAIL_THREAD".equals(e.type()) &&
                        "Meeting Notes".equals(e.name())),
                "Should create EMAIL_THREAD entity with Re: prefix stripped");
        assertTrue(result.relations().stream().anyMatch(r ->
                        "IN_THREAD".equals(r.type())),
                "Should create IN_THREAD relation from email to thread");
    }

    @Test
    void extractDoesNotCreateEmailEntitiesWithoutHint() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "web/html-loader");
        meta.put("title", "Normal Page");
        // No content_type_hint=email
        Document doc = new Document("Regular web page", meta);

        ExtractionResult result = extractor.extract(doc);

        assertFalse(result.relations().stream().anyMatch(r -> "SENT_TO".equals(r.type())),
                "Should NOT create SENT_TO without email hint");
        assertFalse(result.entities().stream().anyMatch(e -> "ATTACHMENT".equals(e.type())),
                "Should NOT create ATTACHMENT without email hint");
    }

    // --- HTML Headings as DOCUMENT_SECTION ---

    @Test
    void htmlHeadingsCreateDocumentSectionEntities() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "web/html-loader");
        meta.put("source", "https://example.com/article");
        meta.put("title", "Article");
        meta.put("html.headings", List.of(
                Map.of("text", "Introduction", "level", "1"),
                Map.of("text", "Background", "level", "2"),
                Map.of("text", "Conclusion", "level", "2")
        ));
        Document doc = new Document("some content", meta);

        ExtractionResult result = extractor.extract(doc);

        List<ExtractedEntity> sections = result.entities().stream()
                .filter(e -> "DOCUMENT_SECTION".equals(e.type()))
                .toList();
        assertEquals(3, sections.size(), "Should create 3 DOCUMENT_SECTION entities");

        ExtractedEntity intro = sections.stream()
                .filter(e -> "Introduction".equals(e.name()))
                .findFirst().orElseThrow();
        assertEquals("1", intro.properties().get("headingLevel"));
        assertEquals("Introduction", intro.properties().get("headingText"));
    }

    @Test
    void htmlHeadingSectionsHaveHasSectionRelations() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "web/html-loader");
        meta.put("source", "https://example.com/page");
        meta.put("title", "Page");
        meta.put("html.headings", List.of(
                Map.of("text", "Methods", "level", "2")
        ));
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        long hasSectionCount = result.relations().stream()
                .filter(r -> "HAS_SECTION".equals(r.type()))
                .count();
        assertEquals(1, hasSectionCount, "Should have 1 HAS_SECTION relation");
    }

    @Test
    void noHeadingsProducesNoSectionEntities() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "web/html-loader");
        meta.put("source", "https://example.com/plain");
        meta.put("title", "Plain");
        Document doc = new Document("no headings here", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream()
                        .noneMatch(e -> "DOCUMENT_SECTION".equals(e.type())),
                "Should produce no DOCUMENT_SECTION without headings metadata");
    }

    // --- JSON-LD Structured Data ---

    @Test
    void extractJsonLdCreatesStructuredDataEntity() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "web/html-loader");
        meta.put("source", "https://example.com/product");
        meta.put("title", "Product Page");
        meta.put("html.jsonld", List.of(
                "{\"@context\":\"https://schema.org\",\"@type\":\"Product\",\"name\":\"Widget Pro\"}"
        ));
        Document doc = new Document("product content", meta);

        ExtractionResult result = extractor.extract(doc);

        List<ExtractedEntity> sdEntities = result.entities().stream()
                .filter(e -> "STRUCTURED_DATA".equals(e.type()))
                .toList();
        assertEquals(1, sdEntities.size(), "Should create 1 STRUCTURED_DATA entity");
        ExtractedEntity sdEntity = sdEntities.get(0);
        assertEquals("Product", sdEntity.properties().get("schemaType"),
                "Should extract @type from JSON-LD");
        assertEquals("Widget Pro", sdEntity.properties().get("schemaName"),
                "Should extract name from JSON-LD");
    }

    @Test
    void extractJsonLdCreatesHasStructuredDataRelation() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "web/html-loader");
        meta.put("source", "https://example.com/article");
        meta.put("title", "Article");
        meta.put("html.jsonld", List.of(
                "{\"@context\":\"https://schema.org\",\"@type\":\"Article\",\"headline\":\"My Article\"}"
        ));
        Document doc = new Document("article content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.relations().stream().anyMatch(r -> "HAS_STRUCTURED_DATA".equals(r.type())),
                "Should create HAS_STRUCTURED_DATA relation");
    }

    @Test
    void extractJsonLdHandlesMultipleBlocks() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "web/html-loader");
        meta.put("source", "https://example.com/page");
        meta.put("title", "Page");
        meta.put("html.jsonld", List.of(
                "{\"@context\":\"https://schema.org\",\"@type\":\"WebPage\",\"name\":\"Home\"}",
                "{\"@context\":\"https://schema.org\",\"@type\":\"BreadcrumbList\"}"
        ));
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        long sdCount = result.entities().stream()
                .filter(e -> "STRUCTURED_DATA".equals(e.type()))
                .count();
        assertEquals(2, sdCount, "Should create one STRUCTURED_DATA entity per JSON-LD block");
        long hasStructuredDataCount = result.relations().stream()
                .filter(r -> "HAS_STRUCTURED_DATA".equals(r.type()))
                .count();
        assertEquals(2, hasStructuredDataCount, "Should create one HAS_STRUCTURED_DATA relation per block");
    }

    @Test
    void extractJsonLdArrayTypeExtractsFirstElement() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "web/html-loader");
        meta.put("source", "https://example.com/page");
        meta.put("title", "Page");
        meta.put("html.jsonld", List.of(
                "{\"@context\":\"https://schema.org\",\"@type\":[\"Product\",\"Thing\"],\"name\":\"Item\"}"
        ));
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity sdEntity = result.entities().stream()
                .filter(e -> "STRUCTURED_DATA".equals(e.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("Product", sdEntity.properties().get("schemaType"),
                "Should extract first element from @type array");
    }

    @Test
    void noJsonLdProducesNoStructuredDataEntities() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "web/html-loader");
        meta.put("source", "https://example.com/simple");
        meta.put("title", "Simple");
        Document doc = new Document("plain content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream()
                        .noneMatch(e -> "STRUCTURED_DATA".equals(e.type())),
                "Should produce no STRUCTURED_DATA without html.jsonld metadata");
        assertFalse(result.relations().stream()
                        .anyMatch(r -> "HAS_STRUCTURED_DATA".equals(r.type())),
                "Should produce no HAS_STRUCTURED_DATA without html.jsonld metadata");
    }

    // --- Embedded Media ---

    @Test
    void extractEmbeddedMediaCreatesEmbeddedMediaEntity() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "web/html-loader");
        meta.put("source", "https://example.com/video-page");
        meta.put("title", "Video Page");
        meta.put("html.embeddedMedia", List.of(
                Map.of("type", "iframe", "src", "https://www.youtube.com/embed/abc123", "title", "Demo Video")
        ));
        Document doc = new Document("content with embedded video", meta);

        ExtractionResult result = extractor.extract(doc);

        List<ExtractedEntity> mediaEntities = result.entities().stream()
                .filter(e -> "EMBEDDED_MEDIA".equals(e.type()))
                .toList();
        assertEquals(1, mediaEntities.size(), "Should create 1 EMBEDDED_MEDIA entity");
        ExtractedEntity mediaEntity = mediaEntities.get(0);
        assertEquals("iframe", mediaEntity.properties().get("mediaType"),
                "Should store mediaType as 'iframe'");
        assertEquals("https://www.youtube.com/embed/abc123", mediaEntity.properties().get("src"),
                "Should store the src URL");
        assertEquals("Demo Video", mediaEntity.properties().get("title"),
                "Should store the title attribute");
    }

    @Test
    void extractEmbeddedMediaCreatesHasMediaRelation() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "web/html-loader");
        meta.put("source", "https://example.com/media");
        meta.put("title", "Media Page");
        meta.put("html.embeddedMedia", List.of(
                Map.of("type", "video", "src", "https://example.com/video.mp4")
        ));
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.relations().stream().anyMatch(r -> "HAS_MEDIA".equals(r.type())),
                "Should create HAS_MEDIA relation for embedded media");
    }

    @Test
    void extractEmbeddedMediaHandlesMultipleElements() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "web/html-loader");
        meta.put("source", "https://example.com/multi-media");
        meta.put("title", "Multi-Media Page");
        meta.put("html.embeddedMedia", List.of(
                Map.of("type", "iframe", "src", "https://www.youtube.com/embed/vid1"),
                Map.of("type", "video", "src", "https://example.com/clip.mp4"),
                Map.of("type", "audio", "src", "https://example.com/track.mp3")
        ));
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        long mediaCount = result.entities().stream()
                .filter(e -> "EMBEDDED_MEDIA".equals(e.type()))
                .count();
        assertEquals(3, mediaCount, "Should create 3 EMBEDDED_MEDIA entities");
        long hasMediaCount = result.relations().stream()
                .filter(r -> "HAS_MEDIA".equals(r.type()))
                .count();
        assertEquals(3, hasMediaCount, "Should create 3 HAS_MEDIA relations");
    }

    @Test
    void embeddedMediaEntityUsesMediaTitleAsName() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "web/html-loader");
        meta.put("source", "https://example.com/page");
        meta.put("title", "Page");
        meta.put("html.embeddedMedia", List.of(
                Map.of("type", "iframe", "src", "https://player.vimeo.com/video/123", "title", "My Presentation")
        ));
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity mediaEntity = result.entities().stream()
                .filter(e -> "EMBEDDED_MEDIA".equals(e.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("My Presentation", mediaEntity.name(),
                "Entity name should use title attribute when present");
    }

    @Test
    void embeddedMediaEntityUseSrcAsNameWhenNoTitle() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "web/html-loader");
        meta.put("source", "https://example.com/page");
        meta.put("title", "Page");
        meta.put("html.embeddedMedia", List.of(
                Map.of("type", "video", "src", "https://example.com/video.mp4")
        ));
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity mediaEntity = result.entities().stream()
                .filter(e -> "EMBEDDED_MEDIA".equals(e.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("https://example.com/video.mp4", mediaEntity.name(),
                "Entity name should fall back to src URL when no title");
    }

    @Test
    void noEmbeddedMediaProducesNoMediaEntities() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "web/html-loader");
        meta.put("source", "https://example.com/text-only");
        meta.put("title", "Text Only");
        Document doc = new Document("just text, no media", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream()
                        .noneMatch(e -> "EMBEDDED_MEDIA".equals(e.type())),
                "Should produce no EMBEDDED_MEDIA without html.embeddedMedia metadata");
        assertFalse(result.relations().stream()
                        .anyMatch(r -> "HAS_MEDIA".equals(r.type())),
                "Should produce no HAS_MEDIA without html.embeddedMedia metadata");
    }

    // ── Table sub-document tests ──────────────────────────────────────

    @Test
    void extract_tableSubDocument_createsTableAndHasTableRelation() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("content_type", "table");
        meta.put("source", "https://example.com/page.html");
        meta.put("source_path", "https://example.com/page.html");
        meta.put("loader", "Web/HTML Loader");
        meta.put("table_index", "2");
        meta.put("table_headers", "Name, Age, City");
        meta.put("table_row_count", 5);
        meta.put("table_column_count", 3);

        Document doc = new Document("Table content", meta);
        var result = extractor.extract(doc);

        // Should have WEB_PAGE + TABLE entities
        var entities = result.entities();
        assertTrue(entities.size() >= 2, "Should have at least WEB_PAGE and TABLE entities");

        var tableEntity = entities.stream()
                .filter(e -> "TABLE".equals(e.type()))
                .findFirst().orElseThrow(() -> new AssertionError("No TABLE entity found"));
        assertEquals("Table: Name, Age, City", tableEntity.name());
        assertEquals("2", tableEntity.properties().get("tableIndex"));

        var pageEntity = entities.stream()
                .filter(e -> "WEB_PAGE".equals(e.type()))
                .findFirst().orElseThrow(() -> new AssertionError("No WEB_PAGE entity found"));

        // Should have HAS_TABLE relation
        var relations = result.relations();
        assertTrue(relations.stream().anyMatch(r ->
                "HAS_TABLE".equals(r.type()) &&
                r.source().equals(pageEntity.id()) &&
                r.target().equals(tableEntity.id())
        ), "Should have HAS_TABLE relation from WEB_PAGE to TABLE");
    }

    @Test
    void extract_tableSubDocument_noSource_returnsEmpty() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("content_type", "table");
        meta.put("loader", "Web/HTML Loader");
        // No source or source_path

        Document doc = new Document("Table content", meta);
        var result = extractor.extract(doc);
        assertTrue(result.entities().isEmpty());
    }

    @Test
    void extract_tableSubDocument_defaultsToIndexZero() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("content_type", "table");
        meta.put("source", "https://example.com/data.html");
        meta.put("loader", "Web/HTML Loader");
        // No table_index — should default to 0

        Document doc = new Document("Table", meta);
        var result = extractor.extract(doc);

        var tableEntity = result.entities().stream()
                .filter(e -> "TABLE".equals(e.type()))
                .findFirst().orElseThrow();
        assertEquals("0", tableEntity.properties().get("tableIndex"));
    }

    @Test
    void canExtract_tableSubDocument_returnsTrue() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("content_type", "table");
        meta.put("loader", "Web/HTML Loader");
        Document doc = new Document("Table", meta);
        assertTrue(extractor.canExtract(doc));
    }

    @Test
    void canExtract_chartSubDocument_returnsFalse() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("content_type", "chart");
        meta.put("loader", "Web/HTML Loader");
        Document doc = new Document("Chart", meta);
        assertFalse(extractor.canExtract(doc));
    }
}
