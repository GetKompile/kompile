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

package ai.kompile.loader.gdocs;

import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedEntity;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedRelation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GoogleDocsGraphExtractorTest {

    private GoogleDocsGraphExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new GoogleDocsGraphExtractor();
    }

    // ── Document entity extraction ────────────────────────────────────

    @Test
    void extractsDocumentEntity() {
        Document doc = docWithMeta(Map.of(
                "source_type", "gdocs",
                "gdocs.documentId", "doc123",
                "gdocs.title", "My Document"
        ));

        ExtractionResult result = extractor.extract(doc);
        List<ExtractedEntity> entities = result.entities();

        assertTrue(entities.stream().anyMatch(e ->
                "GDOCS_DOCUMENT".equals(e.type()) && "My Document".equals(e.name())));
    }

    @Test
    void extractsOwnerEntity() {
        Document doc = docWithMeta(Map.of(
                "source_type", "gdocs",
                "gdocs.documentId", "doc123",
                "gdocs.title", "Test",
                "gdocs.owner", "Alice Smith",
                "gdocs.ownerEmail", "alice@example.com"
        ));

        ExtractionResult result = extractor.extract(doc);
        List<ExtractedEntity> entities = result.entities();

        assertTrue(entities.stream().anyMatch(e ->
                "PERSON".equals(e.type()) && "Alice Smith".equals(e.name())));
    }

    @Test
    void extractsOwnedByRelationship() {
        Document doc = docWithMeta(Map.of(
                "source_type", "gdocs",
                "gdocs.documentId", "doc123",
                "gdocs.title", "Test",
                "gdocs.ownerEmail", "alice@example.com"
        ));

        ExtractionResult result = extractor.extract(doc);
        List<ExtractedRelation> rels = result.relations();

        assertTrue(rels.stream().anyMatch(r -> "OWNED_BY".equals(r.type())));
    }

    @Test
    void extractsLastModifiedByRelationship() {
        Document doc = docWithMeta(Map.of(
                "source_type", "gdocs",
                "gdocs.documentId", "doc123",
                "gdocs.title", "Test",
                "gdocs.lastModifiedBy", "Bob Jones",
                "gdocs.lastModifiedByEmail", "bob@example.com"
        ));

        ExtractionResult result = extractor.extract(doc);
        List<ExtractedRelation> rels = result.relations();

        assertTrue(rels.stream().anyMatch(r -> "LAST_MODIFIED_BY".equals(r.type())));
    }

    // ── Folder relation extraction ─────────────────────────────────────

    @Test
    void extractsInFolderRelationship() {
        Document doc = docWithMeta(Map.of(
                "source_type", "gdocs",
                "gdocs.documentId", "doc123",
                "gdocs.title", "Test",
                "gdocs.folderId", "folder456"
        ));

        ExtractionResult result = extractor.extract(doc);
        List<ExtractedRelation> rels = result.relations();
        List<ExtractedEntity> entities = result.entities();

        assertTrue(rels.stream().anyMatch(r -> "IN_FOLDER".equals(r.type())),
                "Should create IN_FOLDER relation when folderId is present");
        assertTrue(entities.stream().anyMatch(e -> "GDOCS_FOLDER".equals(e.type())),
                "Should create GDOCS_FOLDER entity");
    }

    @Test
    void noFolderRelationWithoutFolderId() {
        Document doc = docWithMeta(Map.of(
                "source_type", "gdocs",
                "gdocs.documentId", "doc123",
                "gdocs.title", "Test"
        ));

        ExtractionResult result = extractor.extract(doc);
        List<ExtractedRelation> rels = result.relations();

        assertFalse(rels.stream().anyMatch(r -> "IN_FOLDER".equals(r.type())),
                "Should not create IN_FOLDER relation when folderId is absent");
    }

    // ── Comment entity extraction ─────────────────────────────────────

    @Test
    void extractsCommentEntity() {
        Document doc = docWithMeta(Map.of(
                "source_type", "gdocs_comment",
                "gdocs.documentId", "doc123",
                "gdocs.commentId", "comment456",
                "gdocs.commentAuthor", "Carol"
        ));

        ExtractionResult result = extractor.extract(doc);
        List<ExtractedEntity> entities = result.entities();

        assertTrue(entities.stream().anyMatch(e ->
                "GDOCS_COMMENT".equals(e.type())));
    }

    @Test
    void extractsCommentedOnRelationship() {
        Document doc = docWithMeta(Map.of(
                "source_type", "gdocs_comment",
                "gdocs.documentId", "doc123",
                "gdocs.commentId", "comment456",
                "gdocs.commentAuthor", "Carol"
        ));

        ExtractionResult result = extractor.extract(doc);
        List<ExtractedRelation> rels = result.relations();

        assertTrue(rels.stream().anyMatch(r -> "COMMENTED_ON".equals(r.type())));
        assertTrue(rels.stream().anyMatch(r -> "AUTHORED_BY".equals(r.type())));
    }

    // ── Comment reply extraction ─────────────────────────────────────

    @Test
    void extractsCommentRepliesAsEntities() {
        Map<String, Object> meta = new HashMap<>(Map.of(
                "source_type", "gdocs_comment",
                "gdocs.documentId", "doc123",
                "gdocs.commentId", "comment456",
                "gdocs.commentAuthor", "Carol"
        ));
        meta.put("gdocs.commentReplies", List.of(
                Map.of("replyId", "reply1", "author", "Dave"),
                Map.of("replyId", "reply2", "author", "Eve")
        ));

        ExtractionResult result = extractor.extract(docWithMeta(meta));
        List<ExtractedEntity> entities = result.entities();

        long replyCount = entities.stream()
                .filter(e -> "GDOCS_REPLY".equals(e.type()))
                .count();
        assertEquals(2, replyCount, "Should create one GDOCS_REPLY entity per reply");
        assertTrue(entities.stream().anyMatch(e ->
                "GDOCS_REPLY".equals(e.type()) && e.name().contains("Dave")));
        assertTrue(entities.stream().anyMatch(e ->
                "GDOCS_REPLY".equals(e.type()) && e.name().contains("Eve")));
    }

    @Test
    void extractsRepliedToRelationships() {
        Map<String, Object> meta = new HashMap<>(Map.of(
                "source_type", "gdocs_comment",
                "gdocs.documentId", "doc123",
                "gdocs.commentId", "comment456",
                "gdocs.commentAuthor", "Carol"
        ));
        meta.put("gdocs.commentReplies", List.of(
                Map.of("replyId", "reply1", "author", "Dave")
        ));

        ExtractionResult result = extractor.extract(docWithMeta(meta));
        List<ExtractedRelation> rels = result.relations();

        assertTrue(rels.stream().anyMatch(r -> "REPLIED_TO".equals(r.type())),
                "Should create REPLIED_TO relationship from reply to parent comment");
    }

    @Test
    void replyAuthorsAreCreatedAsPersonEntities() {
        Map<String, Object> meta = new HashMap<>(Map.of(
                "source_type", "gdocs_comment",
                "gdocs.documentId", "doc123",
                "gdocs.commentId", "comment456",
                "gdocs.commentAuthor", "Carol"
        ));
        meta.put("gdocs.commentReplies", List.of(
                Map.of("replyId", "reply1", "author", "Dave")
        ));

        ExtractionResult result = extractor.extract(docWithMeta(meta));
        List<ExtractedEntity> entities = result.entities();

        // Carol (comment author) + Dave (reply author)
        long personCount = entities.stream()
                .filter(e -> "PERSON".equals(e.type()))
                .count();
        assertEquals(2, personCount, "Should create person entities for both comment and reply authors");
    }

    @Test
    void replyContentStoredOnReplyEntity() {
        Map<String, Object> meta = new HashMap<>(Map.of(
                "source_type", "gdocs_comment",
                "gdocs.documentId", "doc123",
                "gdocs.commentId", "comment456",
                "gdocs.commentAuthor", "Carol"
        ));
        meta.put("gdocs.commentReplies", List.of(
                Map.of("replyId", "reply1", "author", "Dave", "content", "I agree with this approach")
        ));

        ExtractionResult result = extractor.extract(docWithMeta(meta));
        ExtractedEntity reply = result.entities().stream()
                .filter(e -> "GDOCS_REPLY".equals(e.type()))
                .findFirst().orElseThrow();
        assertEquals("I agree with this approach", reply.properties().get("content"),
                "Reply content should be captured as a property on the GDOCS_REPLY entity");
    }

    @Test
    void repliesWithoutReplyIdAreSkipped() {
        Map<String, Object> meta = new HashMap<>(Map.of(
                "source_type", "gdocs_comment",
                "gdocs.documentId", "doc123",
                "gdocs.commentId", "comment456",
                "gdocs.commentAuthor", "Carol"
        ));
        meta.put("gdocs.commentReplies", List.of(
                Map.of("author", "Dave")  // no replyId
        ));

        ExtractionResult result = extractor.extract(docWithMeta(meta));
        long replyCount = result.entities().stream()
                .filter(e -> "GDOCS_REPLY".equals(e.type()))
                .count();
        assertEquals(0, replyCount, "Should skip replies without replyId");
    }

    @Test
    void noRepliesProducesNoReplyEntities() {
        Document doc = docWithMeta(Map.of(
                "source_type", "gdocs_comment",
                "gdocs.documentId", "doc123",
                "gdocs.commentId", "comment456",
                "gdocs.commentAuthor", "Carol"
        ));

        ExtractionResult result = extractor.extract(doc);
        long replyCount = result.entities().stream()
                .filter(e -> "GDOCS_REPLY".equals(e.type()))
                .count();
        assertEquals(0, replyCount, "No replies metadata should produce no reply entities");
    }

    // ── Comment metadata properties ──────────────────────────────────

    @Test
    void commentEntityCapturesResolvedStatus() {
        Map<String, Object> meta = new HashMap<>(Map.of(
                "source_type", "gdocs_comment",
                "gdocs.documentId", "doc123",
                "gdocs.commentId", "comment456",
                "gdocs.commentAuthor", "Carol",
                "gdocs.commentResolved", true
        ));

        ExtractionResult result = extractor.extract(docWithMeta(meta));
        ExtractedEntity comment = result.entities().stream()
                .filter(e -> "GDOCS_COMMENT".equals(e.type()))
                .findFirst().orElseThrow();

        assertEquals("true", comment.properties().get("resolved"));
    }

    @Test
    void commentEntityCapturesContentAndQuotedText() {
        Map<String, Object> meta = new HashMap<>(Map.of(
                "source_type", "gdocs_comment",
                "gdocs.documentId", "doc123",
                "gdocs.commentId", "comment456",
                "gdocs.commentAuthor", "Carol",
                "gdocs.commentContent", "Please fix this section",
                "gdocs.commentQuotedText", "The quick brown fox"
        ));

        ExtractionResult result = extractor.extract(docWithMeta(meta));
        ExtractedEntity comment = result.entities().stream()
                .filter(e -> "GDOCS_COMMENT".equals(e.type()))
                .findFirst().orElseThrow();

        assertEquals("Please fix this section", comment.properties().get("content"));
        assertEquals("The quick brown fox", comment.properties().get("quotedText"));
    }

    @Test
    void commentEntityCapturesCreatedTime() {
        Map<String, Object> meta = new HashMap<>(Map.of(
                "source_type", "gdocs_comment",
                "gdocs.documentId", "doc123",
                "gdocs.commentId", "comment456",
                "gdocs.commentAuthor", "Carol",
                "gdocs.commentCreatedTime", "2025-03-15T10:30:00Z"
        ));

        ExtractionResult result = extractor.extract(docWithMeta(meta));
        ExtractedEntity comment = result.entities().stream()
                .filter(e -> "GDOCS_COMMENT".equals(e.type()))
                .findFirst().orElseThrow();

        assertEquals("2025-03-15T10:30:00Z", comment.properties().get("createdTime"));
    }

    @Test
    void commentEntityOmitsNullOptionalProperties() {
        Document doc = docWithMeta(Map.of(
                "source_type", "gdocs_comment",
                "gdocs.documentId", "doc123",
                "gdocs.commentId", "comment456",
                "gdocs.commentAuthor", "Carol"
        ));

        ExtractionResult result = extractor.extract(doc);
        ExtractedEntity comment = result.entities().stream()
                .filter(e -> "GDOCS_COMMENT".equals(e.type()))
                .findFirst().orElseThrow();

        assertFalse(comment.properties().containsKey("resolved"),
                "Should not include resolved when not in metadata");
        assertFalse(comment.properties().containsKey("content"),
                "Should not include content when not in metadata");
        assertFalse(comment.properties().containsKey("quotedText"),
                "Should not include quotedText when not in metadata");
        assertFalse(comment.properties().containsKey("createdTime"),
                "Should not include createdTime when not in metadata");
    }

    // ── Revision entity extraction ────────────────────────────────────

    @Test
    void extractsRevisionEntity() {
        Document doc = docWithMeta(Map.of(
                "source_type", "gdocs_revision",
                "gdocs.documentId", "doc123",
                "gdocs.revisionId", "rev789",
                "gdocs.revisionModifier", "Dave"
        ));

        ExtractionResult result = extractor.extract(doc);
        List<ExtractedEntity> entities = result.entities();

        assertTrue(entities.stream().anyMatch(e ->
                "GDOCS_REVISION".equals(e.type())));
    }

    @Test
    void extractsRevisionOfRelationship() {
        Document doc = docWithMeta(Map.of(
                "source_type", "gdocs_revision",
                "gdocs.documentId", "doc123",
                "gdocs.revisionId", "rev789",
                "gdocs.revisionModifier", "Dave"
        ));

        ExtractionResult result = extractor.extract(doc);
        List<ExtractedRelation> rels = result.relations();

        assertTrue(rels.stream().anyMatch(r -> "REVISION_OF".equals(r.type())));
        assertTrue(rels.stream().anyMatch(r -> "MODIFIED_BY".equals(r.type())));
    }

    // ── Non-gdocs filtering ───────────────────────────────────────────

    @Test
    void returnsEmptyForNonGdocsDocument() {
        Document doc = docWithMeta(Map.of("source_type", "url"));

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().isEmpty());
        assertTrue(result.relations().isEmpty());
    }

    @Test
    void returnsEmptyForMissingDocumentId() {
        Document doc = docWithMeta(Map.of("source_type", "gdocs"));

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().isEmpty());
    }

    // ── Batch deduplication ───────────────────────────────────────────

    @Test
    void batchDeduplicatesPersonEntities() {
        Document doc1 = docWithMeta(Map.of(
                "source_type", "gdocs",
                "gdocs.documentId", "doc1",
                "gdocs.title", "Doc 1",
                "gdocs.ownerEmail", "alice@example.com",
                "gdocs.owner", "Alice"
        ));
        Document doc2 = docWithMeta(Map.of(
                "source_type", "gdocs",
                "gdocs.documentId", "doc2",
                "gdocs.title", "Doc 2",
                "gdocs.ownerEmail", "alice@example.com",
                "gdocs.owner", "Alice Smith"
        ));

        ExtractionResult result = extractor.extractBatch(List.of(doc1, doc2));
        List<ExtractedEntity> entities = result.entities();

        long personCount = entities.stream()
                .filter(e -> "PERSON".equals(e.type()))
                .count();
        assertEquals(1, personCount, "Same email should produce one deduplicated person");
    }

    @Test
    void batchDeduplicatesRelationships() {
        Document doc1 = docWithMeta(Map.of(
                "source_type", "gdocs",
                "gdocs.documentId", "doc1",
                "gdocs.title", "Test",
                "gdocs.ownerEmail", "alice@example.com"
        ));
        Document doc2 = docWithMeta(Map.of(
                "source_type", "gdocs",
                "gdocs.documentId", "doc1",
                "gdocs.title", "Test Updated",
                "gdocs.ownerEmail", "alice@example.com"
        ));

        ExtractionResult result = extractor.extractBatch(List.of(doc1, doc2));
        List<ExtractedRelation> rels = result.relations();

        long ownedByCount = rels.stream()
                .filter(r -> "OWNED_BY".equals(r.type()))
                .count();
        assertEquals(1, ownedByCount, "Identical relationships should be deduplicated");
    }

    @Test
    void batchKeepsFirstEntityForDuplicateIds() {
        Document doc1 = docWithMeta(Map.of(
                "source_type", "gdocs",
                "gdocs.documentId", "doc1",
                "gdocs.title", "First Title"
        ));
        Document doc2 = docWithMeta(Map.of(
                "source_type", "gdocs",
                "gdocs.documentId", "doc1",
                "gdocs.title", "Second Title"
        ));

        ExtractionResult result = extractor.extractBatch(List.of(doc1, doc2));
        List<ExtractedEntity> entities = result.entities();

        // putIfAbsent keeps first entity — verify dedup by count
        long docCount = entities.stream()
                .filter(e -> "GDOCS_DOCUMENT".equals(e.type()))
                .count();
        assertEquals(1, docCount, "Same document ID should produce one deduplicated entity");
        ExtractedEntity docEntity = entities.stream()
                .filter(e -> "GDOCS_DOCUMENT".equals(e.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("First Title", docEntity.name());
    }

    // ── entityId determinism ──────────────────────────────────────────

    @Test
    void entityIdIsDeterministic() {
        String id1 = GoogleDocsGraphExtractor.entityId("gdocs_document", "doc123");
        String id2 = GoogleDocsGraphExtractor.entityId("gdocs_document", "doc123");
        assertEquals(id1, id2);
    }

    @Test
    void entityIdDiffersForDifferentPrefixes() {
        String id1 = GoogleDocsGraphExtractor.entityId("gdocs_document", "doc123");
        String id2 = GoogleDocsGraphExtractor.entityId("person", "doc123");
        assertNotEquals(id1, id2);
    }

    // ── Revision Chaining ─────────────────────────────────────────────

    @Test
    void revisionWithPreviousIdCreatesSuccessorOfRelation() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source_type", "gdocs_revision");
        meta.put("gdocs.documentId", "doc-abc");
        meta.put("gdocs.revisionId", "rev-3");
        meta.put("gdocs.revisionModifier", "Alice");
        meta.put("gdocs.previousRevisionId", "rev-2");

        ExtractionResult result = extractor.extract(docWithMeta(meta));

        assertTrue(result.relations().stream()
                        .anyMatch(r -> "SUCCESSOR_OF".equals(r.type())),
                "Should create SUCCESSOR_OF relationship between revisions");
    }

    @Test
    void revisionWithoutPreviousIdHasNoSuccessorOf() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source_type", "gdocs_revision");
        meta.put("gdocs.documentId", "doc-abc");
        meta.put("gdocs.revisionId", "rev-1");
        meta.put("gdocs.revisionModifier", "Bob");

        ExtractionResult result = extractor.extract(docWithMeta(meta));

        assertFalse(result.relations().stream()
                        .anyMatch(r -> "SUCCESSOR_OF".equals(r.type())),
                "First revision should not have SUCCESSOR_OF");
    }

    // ── Heading / Section extraction ────────────────────────────────────

    @Test
    void extractsHeadingsAsDocumentSections() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source_type", "gdocs");
        meta.put("gdocs.documentId", "doc-headings");
        meta.put("gdocs.title", "Heading Doc");
        List<Map<String, String>> headings = List.of(
                Map.of("text", "Introduction", "level", "1", "index", "0"),
                Map.of("text", "Details", "level", "2", "index", "1")
        );
        meta.put("gdocs.headings", headings);

        ExtractionResult result = extractor.extract(docWithMeta(meta));

        List<ExtractedEntity> sections = result.entities().stream()
                .filter(e -> "DOCUMENT_SECTION".equals(e.type())).toList();
        assertEquals(2, sections.size());
        assertEquals("Introduction", sections.get(0).name());
        assertEquals("1", sections.get(0).properties().get("headingLevel"));
        assertEquals("Details", sections.get(1).name());
        assertEquals("2", sections.get(1).properties().get("headingLevel"));

        // H1 "Introduction" → HAS_SECTION, H2 "Details" → SUBSECTION_OF
        List<ExtractedRelation> hasSectionRels = result.relations().stream()
                .filter(r -> "HAS_SECTION".equals(r.type())).toList();
        assertEquals(1, hasSectionRels.size());
        List<ExtractedRelation> subSectionRels = result.relations().stream()
                .filter(r -> "SUBSECTION_OF".equals(r.type())).toList();
        assertEquals(1, subSectionRels.size());
    }

    @Test
    void skipsBlankHeadings() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source_type", "gdocs");
        meta.put("gdocs.documentId", "doc-blank-h");
        meta.put("gdocs.title", "Blank Heading Doc");
        meta.put("gdocs.headings", List.of(
                Map.of("text", "", "level", "1", "index", "0"),
                Map.of("text", "Real Heading", "level", "2", "index", "1")
        ));

        ExtractionResult result = extractor.extract(docWithMeta(meta));

        List<ExtractedEntity> sections = result.entities().stream()
                .filter(e -> "DOCUMENT_SECTION".equals(e.type())).toList();
        assertEquals(1, sections.size());
        assertEquals("Real Heading", sections.get(0).name());
    }

    // ── Hyperlink extraction ─────────────────────────────────────────────

    @Test
    void extractsHyperlinksAsEntities() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source_type", "gdocs");
        meta.put("gdocs.documentId", "doc-links");
        meta.put("gdocs.title", "Links Doc");
        List<Map<String, String>> links = List.of(
                Map.of("url", "https://example.com", "text", "Example"),
                Map.of("url", "https://kompile.ai", "text", "Kompile")
        );
        meta.put("gdocs.links", links);

        ExtractionResult result = extractor.extract(docWithMeta(meta));

        List<ExtractedEntity> linkEntities = result.entities().stream()
                .filter(e -> "HYPERLINK".equals(e.type())).toList();
        assertEquals(2, linkEntities.size());
        assertEquals("Example", linkEntities.get(0).name());
        assertEquals("https://example.com", linkEntities.get(0).properties().get("url"));

        List<ExtractedRelation> linkRels = result.relations().stream()
                .filter(r -> "HAS_HYPERLINK".equals(r.type())).toList();
        assertEquals(2, linkRels.size());
    }

    @Test
    void deduplicatesHyperlinksByUrl() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source_type", "gdocs");
        meta.put("gdocs.documentId", "doc-dup-links");
        meta.put("gdocs.title", "Dup Links Doc");
        meta.put("gdocs.links", List.of(
                Map.of("url", "https://example.com", "text", "First"),
                Map.of("url", "https://example.com", "text", "Second")
        ));

        ExtractionResult result = extractor.extract(docWithMeta(meta));

        List<ExtractedEntity> linkEntities = result.entities().stream()
                .filter(e -> "HYPERLINK".equals(e.type())).toList();
        assertEquals(1, linkEntities.size(), "Duplicate URLs should be deduplicated");
    }

    // ── Inline image extraction ──────────────────────────────────────

    @Test
    void extract_documentWithInlineImages_createsEmbeddedImageEntities() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source_type", "gdocs");
        meta.put("gdocs.documentId", "doc-img-123");
        meta.put("gdocs.title", "Doc With Images");
        meta.put("gdocs.imageCount", 2);

        String bodyText = "Here is text with [Image: kix.abc123] and more text [Image: kix.def456] end.";
        Document doc = new Document(bodyText, meta);

        ExtractionResult result = extractor.extract(doc);

        List<ExtractedEntity> images = result.entities().stream()
                .filter(e -> "EMBEDDED_IMAGE".equals(e.type())).toList();
        assertEquals(2, images.size(), "Should create 2 EMBEDDED_IMAGE entities");

        List<ExtractedRelation> hasImage = result.relations().stream()
                .filter(r -> "HAS_IMAGE".equals(r.type())).toList();
        assertEquals(2, hasImage.size(), "Should create 2 HAS_IMAGE relations");

        assertTrue(images.stream().anyMatch(e -> "kix.abc123".equals(e.properties().get("objectId"))));
        assertTrue(images.stream().anyMatch(e -> "kix.def456".equals(e.properties().get("objectId"))));
    }

    @Test
    void extract_documentWithDuplicateImages_deduplicates() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source_type", "gdocs");
        meta.put("gdocs.documentId", "doc-dup-img");
        meta.put("gdocs.title", "Dup Images");

        String bodyText = "[Image: kix.same] paragraph text [Image: kix.same] end.";
        Document doc = new Document(bodyText, meta);

        ExtractionResult result = extractor.extract(doc);

        List<ExtractedEntity> images = result.entities().stream()
                .filter(e -> "EMBEDDED_IMAGE".equals(e.type())).toList();
        assertEquals(1, images.size(), "Duplicate image objectIds should be deduplicated");
    }

    // ── Batch merge fix ─────────────────────────────────────────────────

    @Test
    void extractBatch_mergesEntityPropertiesInsteadOfDropping() {
        Map<String, Object> meta1 = new HashMap<>();
        meta1.put("source_type", "gdocs");
        meta1.put("gdocs.documentId", "doc-merge");
        meta1.put("gdocs.title", "Full Document");
        meta1.put("gdocs.ownerEmail", "owner@example.com");

        Map<String, Object> meta2 = new HashMap<>();
        meta2.put("source_type", "gdocs_comment");
        meta2.put("gdocs.documentId", "doc-merge");
        meta2.put("gdocs.commentId", "comment-1");
        meta2.put("gdocs.commentAuthor", "reviewer@example.com");

        // Batch with full doc first, then comment (which creates a stub GDOCS_DOCUMENT)
        ExtractionResult result = extractor.extractBatch(List.of(
                new Document("Full content", meta1),
                new Document("Comment content", meta2)
        ));

        // The full document entity should NOT be shadowed by the stub
        ExtractedEntity docEntity = result.entities().stream()
                .filter(e -> "GDOCS_DOCUMENT".equals(e.type()))
                .findFirst().orElseThrow(() -> new AssertionError("No GDOCS_DOCUMENT entity"));
        assertEquals("Full Document", docEntity.name(), "Full doc entity should win over stub");
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private Document docWithMeta(Map<String, Object> metadata) {
        return new Document("test content", new HashMap<>(metadata));
    }
}
