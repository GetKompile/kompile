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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.graphrag;

import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedEntity;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedRelation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ConfluenceGraphExtractorTest {

    private ConfluenceGraphExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new ConfluenceGraphExtractor();
    }

    // ── canExtract ───────────────────────────────────────────────────

    @Test
    void supportedDocumentTypes() {
        assertEquals(List.of("confluence"), extractor.supportedDocumentTypes());
    }

    @Test
    void canExtractConfluencePage() {
        Document doc = confluencePage("123", "My Page", "DEV", null);
        assertTrue(extractor.canExtract(doc));
    }

    @Test
    void cannotExtractWithoutSourceType() {
        Document doc = new Document("test");
        doc.getMetadata().put("confluence.pageId", "123");
        assertFalse(extractor.canExtract(doc));
    }

    @Test
    void canExtractWithoutPageId() {
        // canExtract returns true for source_type=confluence even without pageId
        // (extraction proceeds with fallback ID; a warning is logged)
        Document doc = new Document("test");
        doc.getMetadata().put("source_type", "confluence");
        assertTrue(extractor.canExtract(doc));
    }

    @Test
    void cannotExtractNull() {
        assertFalse(extractor.canExtract(null));
    }

    @Test
    void cannotExtractUnrelatedDoc() {
        Document doc = new Document("test");
        doc.getMetadata().put("source_type", "url");
        assertFalse(extractor.canExtract(doc));
    }

    // ── Page entity ──────────────────────────────────────────────────

    @Nested
    class PageEntity {
        @Test
        void createsPageEntity() {
            Document doc = confluencePage("123", "Design Doc", "DEV", null);
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasEntityOfType(result, "CONFLUENCE_PAGE"));
            ExtractedEntity page = entityOfType(result, "CONFLUENCE_PAGE");
            assertEquals("Design Doc", page.name());
            assertEquals("123", page.properties().get("pageId"));
        }

        @Test
        void pageWithAllMetadata() {
            Document doc = confluencePage("123", "Design Doc", "DEV", null);
            doc.getMetadata().put("confluence.version", "5");
            doc.getMetadata().put("confluence.type", "page");
            doc.getMetadata().put("confluence.webUrl", "https://wiki.example.com/pages/123");
            doc.getMetadata().put("confluence.createdDate", "2025-01-15");
            doc.getMetadata().put("confluence.modifiedDate", "2025-03-20");
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity page = entityOfType(result, "CONFLUENCE_PAGE");
            assertEquals("5", page.properties().get("version"));
            assertEquals("page", page.properties().get("type"));
            assertEquals("https://wiki.example.com/pages/123", page.properties().get("webUrl"));
        }

        @Test
        void statusStoredOnPageEntity() {
            Document doc = confluencePage("123", "Draft Page", "DEV", null);
            doc.getMetadata().put("confluence.status", "draft");
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity page = entityOfType(result, "CONFLUENCE_PAGE");
            assertEquals("draft", page.properties().get("status"),
                    "Page status should be captured as a property");
        }

        @Test
        void pageWithoutTitleUsesId() {
            Document doc = new Document("content");
            doc.getMetadata().put("source_type", "confluence");
            doc.getMetadata().put("confluence.pageId", "456");
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity page = entityOfType(result, "CONFLUENCE_PAGE");
            assertEquals("Page 456", page.name());
        }
    }

    // ── Space entity ─────────────────────────────────────────────────

    @Nested
    class SpaceEntity {
        @Test
        void createsSpaceEntity() {
            Document doc = confluencePage("123", "Design Doc", "DEV", "Development");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasEntityOfType(result, "CONFLUENCE_SPACE"));
            ExtractedEntity space = entityOfType(result, "CONFLUENCE_SPACE");
            assertEquals("Development", space.name());
            assertEquals("DEV", space.properties().get("spaceKey"));
        }

        @Test
        void spaceWithoutNameUsesKey() {
            Document doc = confluencePage("123", "Design Doc", "DEV", null);
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity space = entityOfType(result, "CONFLUENCE_SPACE");
            assertEquals("DEV", space.name());
        }

        @Test
        void inSpaceRelation() {
            Document doc = confluencePage("123", "Design Doc", "DEV", null);
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasRelationType(result, "IN_SPACE"));
        }

        @Test
        void noSpaceWithoutSpaceKey() {
            Document doc = new Document("content");
            doc.getMetadata().put("source_type", "confluence");
            doc.getMetadata().put("confluence.pageId", "123");
            doc.getMetadata().put("confluence.title", "Orphan Page");
            ExtractionResult result = extractor.extract(doc);

            assertFalse(hasEntityOfType(result, "CONFLUENCE_SPACE"));
            assertFalse(hasRelationType(result, "IN_SPACE"));
        }
    }

    // ── Author and modifier ──────────────────────────────────────────

    @Nested
    class PersonEntities {
        @Test
        void createsAuthorEntity() {
            Document doc = confluencePage("123", "Doc", "DEV", null);
            doc.getMetadata().put("confluence.createdBy", "Alice Smith");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(result.entities().stream().anyMatch(e ->
                    "PERSON".equals(e.type()) && "Alice Smith".equals(e.name())));
            assertTrue(hasRelationType(result, "AUTHORED_BY"));
        }

        @Test
        void createsModifierEntity() {
            Document doc = confluencePage("123", "Doc", "DEV", null);
            doc.getMetadata().put("confluence.createdBy", "Alice");
            doc.getMetadata().put("confluence.lastModifiedBy", "Bob");
            ExtractionResult result = extractor.extract(doc);

            long personCount = result.entities().stream()
                    .filter(e -> "PERSON".equals(e.type()))
                    .count();
            assertEquals(2, personCount, "Author + modifier as separate entities");
            assertTrue(hasRelationType(result, "LAST_MODIFIED_BY"));
        }

        @Test
        void sameAuthorAndModifierDeduplicatesEntity() {
            Document doc = confluencePage("123", "Doc", "DEV", null);
            doc.getMetadata().put("confluence.createdBy", "Alice");
            doc.getMetadata().put("confluence.lastModifiedBy", "Alice");
            ExtractionResult result = extractor.extract(doc);

            long personCount = result.entities().stream()
                    .filter(e -> "PERSON".equals(e.type()))
                    .count();
            assertEquals(1, personCount, "Same person should produce one entity");
            assertTrue(hasRelationType(result, "AUTHORED_BY"));
            assertTrue(hasRelationType(result, "LAST_MODIFIED_BY"));
        }

        @Test
        void noAuthorProducesNoPersonEntity() {
            Document doc = confluencePage("123", "Doc", "DEV", null);
            ExtractionResult result = extractor.extract(doc);

            assertFalse(hasEntityOfType(result, "PERSON"));
        }
    }

    // ── Parent page hierarchy ────────────────────────────────────────

    @Nested
    class PageHierarchy {
        @Test
        void createsChildOfRelation() {
            Document doc = confluencePage("456", "Sub Page", "DEV", null);
            doc.getMetadata().put("confluence.parentPageId", "123");
            doc.getMetadata().put("confluence.parentPageTitle", "Parent Page");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(hasRelationType(result, "CHILD_OF"));
            // Parent should be created as a stub entity
            long pageCount = result.entities().stream()
                    .filter(e -> "CONFLUENCE_PAGE".equals(e.type()))
                    .count();
            assertEquals(2, pageCount, "Page + parent page stub");
        }

        @Test
        void parentWithoutTitleUsesId() {
            Document doc = confluencePage("456", "Sub Page", "DEV", null);
            doc.getMetadata().put("confluence.parentPageId", "123");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(result.entities().stream().anyMatch(e ->
                    "CONFLUENCE_PAGE".equals(e.type()) && "Page 123".equals(e.name())));
        }

        @Test
        void noParentWithoutParentPageId() {
            Document doc = confluencePage("123", "Top Page", "DEV", null);
            ExtractionResult result = extractor.extract(doc);

            assertFalse(hasRelationType(result, "CHILD_OF"));
            long pageCount = result.entities().stream()
                    .filter(e -> "CONFLUENCE_PAGE".equals(e.type()))
                    .count();
            assertEquals(1, pageCount, "Only the page itself");
        }
    }

    // ── Child count / hierarchy metadata ────────────────────────────

    @Nested
    class ChildCountMetadata {
        @Test
        void childCountStoredOnPageEntity() {
            Document doc = confluencePage("123", "Parent Page", "DEV", null);
            doc.getMetadata().put("confluence.childCount", "5");
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity page = entityOfType(result, "CONFLUENCE_PAGE");
            assertEquals("5", page.properties().get("childCount"),
                    "childCount should be stored as a property on the page entity");
        }

        @Test
        void hasChildrenStoredWhenChildCountAbsent() {
            Document doc = confluencePage("123", "Parent Page", "DEV", null);
            doc.getMetadata().put("confluence.hasChildren", "true");
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity page = entityOfType(result, "CONFLUENCE_PAGE");
            assertEquals("true", page.properties().get("hasChildren"),
                    "hasChildren flag should be stored when exact count is unavailable");
        }

        @Test
        void noChildCountOrHasChildrenWhenAbsent() {
            Document doc = confluencePage("123", "Leaf Page", "DEV", null);
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity page = entityOfType(result, "CONFLUENCE_PAGE");
            assertNull(page.properties().get("childCount"),
                    "childCount should be absent for leaf pages");
            assertNull(page.properties().get("hasChildren"),
                    "hasChildren should be absent when not set");
        }
    }

    // ── Label extraction ─────────────────────────────────────────────

    @Nested
    class LabelExtraction {
        @Test
        void extractsLabelEntitiesFromMetadata() {
            Document doc = confluencePage("123", "Design Doc", "ENG", null);
            doc.getMetadata().put("confluence.labels", List.of("architecture", "api-design"));

            ExtractionResult result = extractor.extract(doc);

            long labelCount = result.entities().stream()
                    .filter(e -> "CONFLUENCE_LABEL".equals(e.type()))
                    .count();
            assertEquals(2, labelCount, "Should create one CONFLUENCE_LABEL entity per label");
        }

        @Test
        void labelsHaveHasLabelRelation() {
            Document doc = confluencePage("123", "Design Doc", "ENG", null);
            doc.getMetadata().put("confluence.labels", List.of("review"));

            ExtractionResult result = extractor.extract(doc);

            assertTrue(result.relations().stream().anyMatch(r -> "HAS_LABEL".equals(r.type())),
                    "Should create HAS_LABEL relation from page to label");
        }

        @Test
        void labelEntityHasLabelNameProperty() {
            Document doc = confluencePage("123", "Design Doc", "ENG", null);
            doc.getMetadata().put("confluence.labels", List.of("architecture"));

            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity label = result.entities().stream()
                    .filter(e -> "CONFLUENCE_LABEL".equals(e.type()))
                    .findFirst().orElseThrow();
            assertEquals("architecture", label.name());
            assertEquals("architecture", label.properties().get("labelName"));
        }

        @Test
        void noLabelsProducesNoLabelEntities() {
            Document doc = confluencePage("123", "Simple Page", "ENG", null);

            ExtractionResult result = extractor.extract(doc);

            assertFalse(result.entities().stream().anyMatch(e -> "CONFLUENCE_LABEL".equals(e.type())));
            assertFalse(result.relations().stream().anyMatch(r -> "HAS_LABEL".equals(r.type())));
        }

        @Test
        void batchDeduplicatesLabels() {
            Document doc1 = confluencePage("1", "Page A", "ENG", null);
            doc1.getMetadata().put("confluence.labels", List.of("api", "review"));
            Document doc2 = confluencePage("2", "Page B", "ENG", null);
            doc2.getMetadata().put("confluence.labels", List.of("api", "internal"));

            ExtractionResult result = extractor.extractBatch(List.of(doc1, doc2));

            // "api" should be deduplicated
            long labelCount = result.entities().stream()
                    .filter(e -> "CONFLUENCE_LABEL".equals(e.type()))
                    .count();
            assertEquals(3, labelCount, "Should deduplicate 'api' label across pages");
        }
    }

    // ── Batch deduplication ──────────────────────────────────────────

    @Nested
    class BatchExtraction {
        @Test
        void batchDeduplicatesSpaceEntities() {
            Document doc1 = confluencePage("1", "Page A", "DEV", "Development");
            Document doc2 = confluencePage("2", "Page B", "DEV", "Development");
            ExtractionResult result = extractor.extractBatch(List.of(doc1, doc2));

            long spaceCount = result.entities().stream()
                    .filter(e -> "CONFLUENCE_SPACE".equals(e.type()))
                    .count();
            assertEquals(1, spaceCount, "Same space should be deduplicated");
        }

        @Test
        void batchDeduplicatesRelations() {
            Document doc1 = confluencePage("1", "Page A", "DEV", null);
            doc1.getMetadata().put("confluence.createdBy", "Alice");
            Document doc2 = confluencePage("2", "Page B", "DEV", null);
            doc2.getMetadata().put("confluence.createdBy", "Alice");
            ExtractionResult result = extractor.extractBatch(List.of(doc1, doc2));

            // Each page has its own AUTHORED_BY — not duplicates since different source
            long authoredByCount = result.relations().stream()
                    .filter(r -> "AUTHORED_BY".equals(r.type()))
                    .count();
            assertEquals(2, authoredByCount, "Different pages have distinct AUTHORED_BY relations");
        }

        @Test
        void extractionMetadataPresent() {
            Document doc = confluencePage("123", "Doc", "DEV", null);
            ExtractionResult result = extractor.extract(doc);

            assertNotNull(result.metadata());
            assertEquals("confluence-rule-extractor", result.metadata().extractionModel());
        }
    }

    // ── Full ancestor chain ─────────────────────────────────────────

    @Nested
    class AncestorChain {
        @Test
        void fullAncestorChainCreatesEntitiesAndRelations() {
            Document doc = confluencePage("456", "Deep Page", "DEV", null);
            doc.getMetadata().put("confluence.parentPageId", "300");
            doc.getMetadata().put("confluence.parentPageTitle", "Parent");
            doc.getMetadata().put("confluence.ancestors", List.of(
                    Map.of("id", "100", "title", "Root"),
                    Map.of("id", "200", "title", "Middle"),
                    Map.of("id", "300", "title", "Parent")
            ));
            ExtractionResult result = extractor.extract(doc);

            // Root and Middle should produce ANCESTOR_OF relations (parent is CHILD_OF)
            long ancestorOfCount = result.relations().stream()
                    .filter(r -> "ANCESTOR_OF".equals(r.type()))
                    .count();
            assertEquals(2, ancestorOfCount, "Root and Middle are ancestors (Parent handled via CHILD_OF)");

            // All 4 page entities: Deep Page + Parent + Root + Middle
            long pageCount = result.entities().stream()
                    .filter(e -> "CONFLUENCE_PAGE".equals(e.type()))
                    .count();
            assertEquals(4, pageCount);
        }

        @Test
        void ancestorSkipsDuplicateWithParentPageId() {
            Document doc = confluencePage("456", "Deep Page", "DEV", null);
            doc.getMetadata().put("confluence.parentPageId", "100");
            doc.getMetadata().put("confluence.parentPageTitle", "Parent");
            // ancestors list contains only the parent — should NOT double-count
            doc.getMetadata().put("confluence.ancestors", List.of(
                    Map.of("id", "100", "title", "Parent")
            ));
            ExtractionResult result = extractor.extract(doc);

            long ancestorOfCount = result.relations().stream()
                    .filter(r -> "ANCESTOR_OF".equals(r.type()))
                    .count();
            assertEquals(0, ancestorOfCount, "Parent already handled via CHILD_OF, no ANCESTOR_OF");
        }

        @Test
        void noAncestorsProducesNoAncestorRelations() {
            Document doc = confluencePage("123", "Top Page", "DEV", null);
            ExtractionResult result = extractor.extract(doc);

            assertFalse(hasRelationType(result, "ANCESTOR_OF"));
        }
    }

    // ── Comment extraction ──────────────────────────────────────────

    @Nested
    class CommentExtraction {
        @Test
        void extractsCommentEntities() {
            Document doc = confluencePage("123", "Design Doc", "ENG", null);
            doc.getMetadata().put("confluence.comments", List.of(
                    Map.of("id", "c1", "author", "Alice", "body", "Looks good!", "date", "2025-01-20"),
                    Map.of("id", "c2", "author", "Bob", "body", "Needs revision")
            ));
            ExtractionResult result = extractor.extract(doc);

            long commentCount = result.entities().stream()
                    .filter(e -> "CONFLUENCE_COMMENT".equals(e.type()))
                    .count();
            assertEquals(2, commentCount);
            assertTrue(hasRelationType(result, "HAS_COMMENT"));
            assertTrue(hasRelationType(result, "COMMENT_BY"));
        }

        @Test
        void commentAuthorCreatesPersonEntity() {
            Document doc = confluencePage("123", "Doc", "ENG", null);
            doc.getMetadata().put("confluence.comments", List.of(
                    Map.of("id", "c1", "author", "Charlie", "body", "Test")
            ));
            ExtractionResult result = extractor.extract(doc);

            assertTrue(result.entities().stream().anyMatch(e ->
                    "PERSON".equals(e.type()) && "Charlie".equals(e.name())));
        }

        @Test
        void commentDateCreatesDATEEntity() {
            Document doc = confluencePage("123", "Doc", "ENG", null);
            doc.getMetadata().put("confluence.comments", List.of(
                    Map.of("id", "c1", "author", "Alice", "body", "LGTM", "date", "2025-01-20T10:30:00Z")
            ));
            ExtractionResult result = extractor.extract(doc);

            assertTrue(result.entities().stream().anyMatch(e ->
                            "DATE".equals(e.type()) && "2025-01-20T10:30:00Z".equals(e.name())),
                    "Comment date should create a DATE entity");
            assertTrue(result.relations().stream().anyMatch(r ->
                            "PUBLISHED_ON".equals(r.type())),
                    "Comment should have PUBLISHED_ON relation to its DATE entity");
        }

        @Test
        void commentWithoutDateDoesNotCreateDATEEntity() {
            Document doc = confluencePage("123", "Doc", "ENG", null);
            doc.getMetadata().put("confluence.comments", List.of(
                    Map.of("id", "c1", "author", "Bob", "body", "No date here")
            ));
            ExtractionResult result = extractor.extract(doc);

            assertFalse(result.entities().stream().anyMatch(e ->
                            "DATE".equals(e.type()) &&
                            e.properties() != null &&
                            "commentCreated".equals(e.properties().get("dateType"))),
                    "Comment without date should not create a commentCreated DATE entity");
        }

        @Test
        void noCommentsProducesNoCommentEntities() {
            Document doc = confluencePage("123", "Doc", "ENG", null);
            ExtractionResult result = extractor.extract(doc);

            assertFalse(hasEntityOfType(result, "CONFLUENCE_COMMENT"));
            assertFalse(hasRelationType(result, "HAS_COMMENT"));
        }

        @Test
        void commentBodyTruncatedWhenLong() {
            Document doc = confluencePage("123", "Doc", "ENG", null);
            String longBody = "x".repeat(600);
            doc.getMetadata().put("confluence.comments", List.of(
                    Map.of("id", "c1", "author", "Alice", "body", longBody)
            ));
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity comment = result.entities().stream()
                    .filter(e -> "CONFLUENCE_COMMENT".equals(e.type()))
                    .findFirst().orElseThrow();
            assertEquals(500, comment.properties().get("body").length());
        }
    }

    // ── Attachment extraction ───────────────────────────────────────

    @Nested
    class AttachmentExtraction {
        @Test
        void extractsAttachmentEntities() {
            Document doc = confluencePage("123", "Design Doc", "ENG", null);
            doc.getMetadata().put("confluence.attachments", List.of(
                    Map.of("id", "a1", "title", "diagram.png", "mediaType", "image/png", "fileSize", "102400"),
                    Map.of("id", "a2", "title", "spec.pdf", "mediaType", "application/pdf")
            ));
            ExtractionResult result = extractor.extract(doc);

            long attachCount = result.entities().stream()
                    .filter(e -> "CONFLUENCE_ATTACHMENT".equals(e.type()))
                    .count();
            assertEquals(2, attachCount);
            assertTrue(hasRelationType(result, "HAS_ATTACHMENT"));
        }

        @Test
        void attachmentWithCreatorAddsPersonAndUploadedBy() {
            Document doc = confluencePage("123", "Doc", "ENG", null);
            doc.getMetadata().put("confluence.attachments", List.of(
                    Map.of("id", "a1", "title", "file.zip", "creator", "Dave")
            ));
            ExtractionResult result = extractor.extract(doc);

            assertTrue(result.entities().stream().anyMatch(e ->
                    "PERSON".equals(e.type()) && "Dave".equals(e.name())));
            assertTrue(hasRelationType(result, "UPLOADED_BY"));
        }

        @Test
        void attachmentDownloadUrlCreatesExternalResource() {
            Document doc = confluencePage("123", "Doc", "ENG", null);
            doc.getMetadata().put("confluence.attachments", List.of(
                    Map.of("id", "a1", "title", "design.png", "downloadUrl", "https://wiki.example.com/download/attachments/123/design.png")
            ));
            ExtractionResult result = extractor.extract(doc);

            assertTrue(result.entities().stream().anyMatch(e ->
                            "EXTERNAL_RESOURCE".equals(e.type()) &&
                            e.properties() != null &&
                            "https://wiki.example.com/download/attachments/123/design.png".equals(e.properties().get("url"))),
                    "Attachment downloadUrl should create EXTERNAL_RESOURCE entity");
            assertTrue(result.relations().stream().anyMatch(r ->
                            "HYPERLINKS_TO".equals(r.type()) &&
                            r.description() != null &&
                            r.description().contains("design.png")),
                    "Should create HYPERLINKS_TO relation from attachment to download URL");
        }

        @Test
        void attachmentWithoutDownloadUrlNoExternalResource() {
            Document doc = confluencePage("123", "Doc", "ENG", null);
            doc.getMetadata().put("confluence.attachments", List.of(
                    Map.of("id", "a1", "title", "file.txt")
            ));
            ExtractionResult result = extractor.extract(doc);

            assertFalse(result.entities().stream().anyMatch(e ->
                            "EXTERNAL_RESOURCE".equals(e.type())),
                    "Attachment without downloadUrl should not create EXTERNAL_RESOURCE");
        }

        @Test
        void noAttachmentsProducesNoAttachmentEntities() {
            Document doc = confluencePage("123", "Doc", "ENG", null);
            ExtractionResult result = extractor.extract(doc);

            assertFalse(hasEntityOfType(result, "CONFLUENCE_ATTACHMENT"));
            assertFalse(hasRelationType(result, "HAS_ATTACHMENT"));
        }

        @Test
        void attachmentPropertiesStored() {
            Document doc = confluencePage("123", "Doc", "ENG", null);
            doc.getMetadata().put("confluence.attachments", List.of(
                    Map.of("id", "a1", "title", "report.xlsx", "mediaType", "application/vnd.ms-excel",
                            "fileSize", "51200", "creator", "Eve")
            ));
            ExtractionResult result = extractor.extract(doc);

            ExtractedEntity attach = result.entities().stream()
                    .filter(e -> "CONFLUENCE_ATTACHMENT".equals(e.type()))
                    .findFirst().orElseThrow();
            assertEquals("report.xlsx", attach.properties().get("fileName"));
            assertEquals("application/vnd.ms-excel", attach.properties().get("mediaType"));
            assertEquals("51200", attach.properties().get("fileSize"));
        }
    }

    // ── Edge cases ───────────────────────────────────────────────────

    @Nested
    class EdgeCases {
        @Test
        void nonConfluenceSourceReturnsEmpty() {
            Document doc = new Document("test");
            doc.getMetadata().put("source_type", "url");
            ExtractionResult result = extractor.extract(doc);

            assertTrue(result.entities().isEmpty());
            assertTrue(result.relations().isEmpty());
        }

        @Test
        void entityIdIsDeterministic() {
            String id1 = ConfluenceGraphExtractor.entityId("confluence_page", "123");
            String id2 = ConfluenceGraphExtractor.entityId("confluence_page", "123");
            assertEquals(id1, id2);
        }

        @Test
        void entityIdDiffersForDifferentPrefixes() {
            String id1 = ConfluenceGraphExtractor.entityId("confluence_page", "123");
            String id2 = ConfluenceGraphExtractor.entityId("confluence_space", "123");
            assertNotEquals(id1, id2);
        }
    }

    // ── External URL extraction ────────────────────────────────────────

    @Nested
    class ExternalUrls {

        @Test
        void extractsExternalUrlsFromBodyText() {
            Document doc = new Document(
                    "Check out https://github.com/kompile/kompile and also https://docs.oracle.com/en/java/",
                    Map.of(
                            "source_type", "confluence",
                            "confluence.pageId", "99001",
                            "confluence.title", "Resources Page",
                            "confluence.spaceKey", "DEV"
                    ));

            ExtractionResult result = extractor.extract(doc);

            long urlEntityCount = result.entities().stream()
                    .filter(e -> "EXTERNAL_RESOURCE".equals(e.type()))
                    .count();
            assertEquals(2, urlEntityCount, "Should extract 2 external URLs");

            long hyperlinkRelCount = result.relations().stream()
                    .filter(r -> "HYPERLINKS_TO".equals(r.type()))
                    .count();
            assertEquals(2, hyperlinkRelCount, "Should create 2 HYPERLINKS_TO relations");
        }

        @Test
        void skipsInternalConfluenceLinksInUrlExtraction() {
            Document doc = new Document(
                    "See /wiki/spaces/DEV/pages/12345/Setup and https://external.example.com",
                    Map.of(
                            "source_type", "confluence",
                            "confluence.pageId", "99002",
                            "confluence.title", "Internal Links",
                            "confluence.spaceKey", "DEV"
                    ));

            ExtractionResult result = extractor.extract(doc);

            // Internal link should be a REFERENCES relation (from internal page reference block)
            assertTrue(hasRelationType(result, "REFERENCES"),
                    "Should create internal REFERENCES relation");

            // External URL should be HYPERLINKS_TO
            long urlCount = result.entities().stream()
                    .filter(e -> "EXTERNAL_RESOURCE".equals(e.type()))
                    .count();
            assertEquals(1, urlCount, "Should extract only 1 external URL (not internal)");
        }

        @Test
        void noUrlsProducesNoExternalEntities() {
            Document doc = confluencePage("99003", "Plain Page", "DEV", "Development");
            ExtractionResult result = extractor.extract(doc);

            assertFalse(hasEntityOfType(result, "EXTERNAL_RESOURCE"),
                    "No external URLs should produce no EXTERNAL_RESOURCE entities");
        }
    }

    // ── @mention user extraction ───────────────────────────────────────

    @Nested
    class MentionedUsers {

        @Test
        void extractsWikiStyleMentions() {
            Document doc = new Document(
                    "As [~john.doe] mentioned, we should also check with [~jane.smith]",
                    Map.of(
                            "source_type", "confluence",
                            "confluence.pageId", "88001",
                            "confluence.title", "Team Notes",
                            "confluence.spaceKey", "TEAM"
                    ));

            ExtractionResult result = extractor.extract(doc);

            long mentionCount = result.relations().stream()
                    .filter(r -> "MENTIONS_USER".equals(r.type()))
                    .count();
            assertEquals(2, mentionCount, "Should extract 2 user mentions");

            assertTrue(result.entities().stream()
                            .filter(e -> "PERSON".equals(e.type()))
                            .anyMatch(e -> "john.doe".equals(e.name())),
                    "Should create PERSON entity for john.doe");
        }

        @Test
        void extractsRiUserKeyMentionsFromRawStorageBody() {
            // In production, HTML is stripped before reaching the extractor body text.
            // ri:user patterns must be extracted from confluence.rawStorageBody metadata.
            Map<String, Object> meta = new HashMap<>();
            meta.put("source_type", "confluence");
            meta.put("confluence.pageId", "88002");
            meta.put("confluence.title", "Tech Spec");
            meta.put("confluence.spaceKey", "ENG");
            meta.put("confluence.rawStorageBody",
                    "<p>Updated by <ac:link><ri:user ri:userkey=\"abc123\"/></ac:link> " +
                    "and reviewed by <ac:link><ri:user ri:username=\"bob\"/></ac:link></p>");
            // Body text is already stripped — no ri:user patterns visible
            Document doc = new Document("Updated by abc123 and reviewed by bob", meta);

            ExtractionResult result = extractor.extract(doc);

            long mentionCount = result.relations().stream()
                    .filter(r -> "MENTIONS_USER".equals(r.type()))
                    .count();
            assertEquals(2, mentionCount, "Should extract both ri:userkey and ri:username from rawStorageBody");
        }

        @Test
        void accountIdPrefixStrippedFromDisplayName() {
            Document doc = new Document(
                    "Assigned to [~accountid:5f4e3d2c1b0a] for review",
                    Map.of(
                            "source_type", "confluence",
                            "confluence.pageId", "88003",
                            "confluence.title", "Review Task",
                            "confluence.spaceKey", "DEV"
                    ));

            ExtractionResult result = extractor.extract(doc);

            assertTrue(result.entities().stream()
                            .filter(e -> "PERSON".equals(e.type()))
                            .anyMatch(e -> "5f4e3d2c1b0a".equals(e.name())),
                    "Should strip accountid: prefix from display name");
        }

        @Test
        void noMentionsProducesNoMentionRelations() {
            Document doc = confluencePage("88004", "Clean Page", "DEV", null);
            ExtractionResult result = extractor.extract(doc);

            assertFalse(hasRelationType(result, "MENTIONS_USER"),
                    "No mentions should produce no MENTIONS_USER relations");
        }

        @Test
        void duplicateMentionsAreDeduped() {
            Document doc = new Document(
                    "CC: [~alice], also [~alice] and [~alice]",
                    Map.of(
                            "source_type", "confluence",
                            "confluence.pageId", "88005",
                            "confluence.title", "Dedup Test",
                            "confluence.spaceKey", "DEV"
                    ));

            ExtractionResult result = extractor.extract(doc);

            long mentionCount = result.relations().stream()
                    .filter(r -> "MENTIONS_USER".equals(r.type()))
                    .count();
            assertEquals(1, mentionCount, "Duplicate mentions should be deduped");
        }
    }

    // ── extractBatch merge ─────────────────────────────────────────────

    @Nested
    class BatchMerge {

        @Test
        void batchMergesEntityPropertiesInsteadOfDropping() {
            Document doc1 = new Document("Content 1", Map.of(
                    "source_type", "confluence",
                    "confluence.pageId", "P1",
                    "confluence.title", "Page One",
                    "confluence.spaceKey", "DEV",
                    "confluence.createdBy", "Alice"
            ));
            Document doc2 = new Document("Content 2", Map.of(
                    "source_type", "confluence",
                    "confluence.pageId", "P2",
                    "confluence.title", "Page Two",
                    "confluence.spaceKey", "DEV",
                    "confluence.createdBy", "Alice",
                    "confluence.lastModifiedBy", "Alice"
            ));

            ExtractionResult result = extractor.extractBatch(List.of(doc1, doc2));

            // The space entity should appear once (merged), not twice
            long spaceCount = result.entities().stream()
                    .filter(e -> "CONFLUENCE_SPACE".equals(e.type()))
                    .count();
            assertEquals(1, spaceCount, "Same space should be merged");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private Document confluencePage(String pageId, String title, String spaceKey, String spaceName) {
        Document doc = new Document("Confluence page content");
        Map<String, Object> meta = doc.getMetadata();
        meta.put("source_type", "confluence");
        meta.put("confluence.pageId", pageId);
        if (title != null) meta.put("confluence.title", title);
        if (spaceKey != null) meta.put("confluence.spaceKey", spaceKey);
        if (spaceName != null) meta.put("confluence.spaceName", spaceName);
        return doc;
    }

    private boolean hasEntityOfType(ExtractionResult result, String type) {
        return result.entities().stream().anyMatch(e -> type.equals(e.type()));
    }

    private boolean hasRelationType(ExtractionResult result, String type) {
        return result.relations().stream().anyMatch(r -> type.equals(r.type()));
    }

    private ExtractedEntity entityOfType(ExtractionResult result, String type) {
        return result.entities().stream()
                .filter(e -> type.equals(e.type()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No entity of type " + type));
    }
}
