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

package ai.kompile.loader.email.inbox;

import ai.kompile.core.graphrag.format.GraphExtractionSchema.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E tests for {@link EmailGraphExtractor} focusing on integration scenarios:
 * cross-document deduplication, alias merging, threading chains, batch extraction,
 * and full graph structure for realistic email threads.
 *
 * <p>Complements the module-local unit test which covers individual entity
 * types and parsing edge cases.
 */
class EmailGraphExtractorTest {

    private EmailGraphExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new EmailGraphExtractor();
    }

    private Document email(Map<String, Object> metadata) {
        return new Document("email body", metadata);
    }

    private ExtractedEntity findEntity(ExtractionResult result, String type, String nameContains) {
        return result.entities().stream()
                .filter(e -> type.equals(e.type())
                        && (nameContains == null || e.name().toLowerCase().contains(nameContains.toLowerCase())))
                .findFirst().orElse(null);
    }

    private List<ExtractedRelation> findRelations(ExtractionResult result, String type) {
        return result.relations().stream()
                .filter(r -> type.equals(r.type()))
                .toList();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Single email extraction — entity types
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class SingleEmailEntityTypes {

        @Test
        void extractsEmailMessageEntity() {
            ExtractionResult result = extractor.extract(email(Map.of(
                    "email.messageId", "<msg-001@example.com>",
                    "email.subject", "Q1 Budget Review",
                    "email.date", "2025-03-15",
                    "email.from", "alice@example.com"
            )));

            ExtractedEntity msg = findEntity(result, "EMAIL_MESSAGE", "Q1 Budget");
            assertNotNull(msg, "Should extract EMAIL_MESSAGE entity");
            assertEquals("Q1 Budget Review", msg.name());
            assertEquals("<msg-001@example.com>", msg.properties().get("messageId"));
            assertEquals("2025-03-15", msg.properties().get("date"));
            assertEquals(1.0, msg.confidence());
        }

        @Test
        void extractsPersonFromSender() {
            ExtractionResult result = extractor.extract(email(Map.of(
                    "email.messageId", "<msg@ex.com>",
                    "email.from", "Alice Smith <alice@example.com>"
            )));

            ExtractedEntity person = findEntity(result, "PERSON", "Alice Smith");
            assertNotNull(person, "Should extract PERSON entity for sender");
            assertEquals("alice@example.com", person.properties().get("email"));
            assertTrue(person.aliases().contains("Alice Smith"));
        }

        @Test
        void extractsPersonFromRecipient() {
            ExtractionResult result = extractor.extract(email(Map.of(
                    "email.messageId", "<msg@ex.com>",
                    "email.from", "alice@example.com",
                    "email.to", "Bob Jones <bob@example.com>"
            )));

            ExtractedEntity person = findEntity(result, "PERSON", "Bob Jones");
            assertNotNull(person);
        }

        @Test
        void extractsMailingListEntity() {
            ExtractionResult result = extractor.extract(email(Map.of(
                    "email.messageId", "<msg@ex.com>",
                    "email.from", "alice@example.com",
                    "email.listId", "dev@lists.example.com"
            )));

            ExtractedEntity list = findEntity(result, "MAILING_LIST", "dev@lists.example.com");
            assertNotNull(list, "Should extract MAILING_LIST entity");
        }

        @Test
        void extractsAttachmentEntity() {
            ExtractionResult result = extractor.extract(email(Map.of(
                    "email.messageId", "<msg@ex.com>",
                    "email.from", "alice@example.com",
                    "email.attachmentName", "report.pdf",
                    "email.attachmentMimeType", "application/pdf"
            )));

            ExtractedEntity attach = findEntity(result, "ATTACHMENT", "report.pdf");
            assertNotNull(attach, "Should extract ATTACHMENT entity");
            assertEquals("report.pdf", attach.properties().get("filename"));
            assertEquals("application/pdf", attach.properties().get("mimeType"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Single email extraction — relationship types
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class SingleEmailRelationships {

        @Test
        void sentByRelationship() {
            ExtractionResult result = extractor.extract(email(Map.of(
                    "email.messageId", "<msg@ex.com>",
                    "email.from", "alice@example.com"
            )));

            List<ExtractedRelation> rels = findRelations(result, "SENT_BY");
            assertEquals(1, rels.size());
            assertEquals(1.0, rels.get(0).confidence());
        }

        @Test
        void sentToRelationship() {
            ExtractionResult result = extractor.extract(email(Map.of(
                    "email.messageId", "<msg@ex.com>",
                    "email.from", "alice@example.com",
                    "email.to", "bob@example.com"
            )));

            List<ExtractedRelation> rels = findRelations(result, "SENT_TO");
            assertEquals(1, rels.size());
        }

        @Test
        void ccToRelationship() {
            ExtractionResult result = extractor.extract(email(Map.of(
                    "email.messageId", "<msg@ex.com>",
                    "email.from", "alice@example.com",
                    "email.cc", "carol@example.com"
            )));

            List<ExtractedRelation> rels = findRelations(result, "CC_TO");
            assertEquals(1, rels.size());
        }

        @Test
        void bccToRelationship() {
            ExtractionResult result = extractor.extract(email(Map.of(
                    "email.messageId", "<msg@ex.com>",
                    "email.from", "alice@example.com",
                    "email.bcc", "dave@example.com"
            )));

            List<ExtractedRelation> rels = findRelations(result, "BCC_TO");
            assertEquals(1, rels.size());
            assertEquals(0.9, rels.get(0).confidence());
        }

        @Test
        void repliedToRelationship() {
            ExtractionResult result = extractor.extract(email(Map.of(
                    "email.messageId", "<reply@ex.com>",
                    "email.from", "bob@example.com",
                    "email.inReplyTo", "<original@ex.com>"
            )));

            List<ExtractedRelation> rels = findRelations(result, "REPLIED_TO");
            assertEquals(1, rels.size());
            // Reply creates a second EMAIL_MESSAGE entity for the referenced message
            assertEquals(2, result.entities().stream()
                    .filter(e -> "EMAIL_MESSAGE".equals(e.type())).count());
        }

        @Test
        void referencesRelationship() {
            ExtractionResult result = extractor.extract(email(Map.of(
                    "email.messageId", "<msg3@ex.com>",
                    "email.from", "alice@example.com",
                    "email.references", List.of("<msg1@ex.com>", "<msg2@ex.com>")
            )));

            List<ExtractedRelation> rels = findRelations(result, "REFERENCES");
            assertEquals(2, rels.size());
            assertEquals(0.9, rels.get(0).confidence());
        }

        @Test
        void referencesSkipsDuplicateWithInReplyTo() {
            ExtractionResult result = extractor.extract(email(Map.of(
                    "email.messageId", "<msg3@ex.com>",
                    "email.from", "alice@example.com",
                    "email.inReplyTo", "<msg2@ex.com>",
                    "email.references", List.of("<msg1@ex.com>", "<msg2@ex.com>")
            )));

            // <msg2@ex.com> should not appear twice — once as REPLIED_TO and once as REFERENCES
            List<ExtractedRelation> refs = findRelations(result, "REFERENCES");
            assertEquals(1, refs.size(), "Should skip the reference that matches inReplyTo");
            assertTrue(refs.get(0).description().contains("<msg1@ex.com>"));
        }

        @Test
        void postedToRelationship() {
            ExtractionResult result = extractor.extract(email(Map.of(
                    "email.messageId", "<msg@ex.com>",
                    "email.from", "alice@example.com",
                    "email.listId", "team@lists.example.com"
            )));

            List<ExtractedRelation> rels = findRelations(result, "POSTED_TO");
            assertEquals(1, rels.size());
        }

        @Test
        void hasAttachmentRelationship() {
            ExtractionResult result = extractor.extract(email(Map.of(
                    "email.messageId", "<msg@ex.com>",
                    "email.from", "alice@example.com",
                    "email.attachmentName", "data.xlsx"
            )));

            List<ExtractedRelation> rels = findRelations(result, "HAS_ATTACHMENT");
            assertEquals(1, rels.size());
        }

        @Test
        void multipleRecipientsSentTo() {
            ExtractionResult result = extractor.extract(email(Map.of(
                    "email.messageId", "<msg@ex.com>",
                    "email.from", "alice@example.com",
                    "email.to", "bob@example.com, carol@example.com, dave@example.com"
            )));

            List<ExtractedRelation> rels = findRelations(result, "SENT_TO");
            assertEquals(3, rels.size());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Cross-document deduplication (batch)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class BatchDeduplicationTests {

        @Test
        void samePersonDeduplicatedAcrossEmails() {
            Document email1 = email(Map.of(
                    "email.messageId", "<msg1@ex.com>",
                    "email.from", "alice@example.com",
                    "email.to", "bob@example.com"
            ));
            Document email2 = email(Map.of(
                    "email.messageId", "<msg2@ex.com>",
                    "email.from", "alice@example.com",
                    "email.to", "carol@example.com"
            ));

            ExtractionResult result = extractor.extractBatch(List.of(email1, email2));

            // alice appears in both emails but should be one entity
            long aliceCount = result.entities().stream()
                    .filter(e -> "PERSON".equals(e.type()) && "alice@example.com".equals(e.properties().get("email")))
                    .count();
            assertEquals(1, aliceCount, "Same person across emails should be deduplicated");
        }

        @Test
        void aliasesMergedOnDeduplication() {
            // alice@example.com appears as "alice@example.com" (bare) in email1
            // and as "Alice Smith <alice@example.com>" (named) in email2
            Document email1 = email(Map.of(
                    "email.messageId", "<msg1@ex.com>",
                    "email.from", "alice@example.com"
            ));
            Document email2 = email(Map.of(
                    "email.messageId", "<msg2@ex.com>",
                    "email.from", "Alice Smith <alice@example.com>"
            ));

            ExtractionResult result = extractor.extractBatch(List.of(email1, email2));

            ExtractedEntity alice = result.entities().stream()
                    .filter(e -> "PERSON".equals(e.type()) && "alice@example.com".equals(e.properties().get("email")))
                    .findFirst().orElseThrow();

            // After merge, the name should be the display name (not the email)
            assertEquals("Alice Smith", alice.name(), "Merge should prefer real name over email address");
        }

        @Test
        void batchRelationsPreserved() {
            Document email1 = email(Map.of(
                    "email.messageId", "<msg1@ex.com>",
                    "email.from", "alice@example.com",
                    "email.to", "bob@example.com"
            ));
            Document email2 = email(Map.of(
                    "email.messageId", "<msg2@ex.com>",
                    "email.from", "bob@example.com",
                    "email.to", "alice@example.com"
            ));

            ExtractionResult result = extractor.extractBatch(List.of(email1, email2));

            // Each email produces SENT_BY and SENT_TO, so 4 total
            long sentByCount = findRelations(result, "SENT_BY").size();
            long sentToCount = findRelations(result, "SENT_TO").size();
            assertEquals(2, sentByCount);
            assertEquals(2, sentToCount);
        }

        @Test
        void batchMetadataUsesEmailHeaderExtractor() {
            ExtractionResult result = extractor.extractBatch(List.of(
                    email(Map.of("email.messageId", "<msg@ex.com>", "email.from", "a@ex.com"))
            ));

            assertNotNull(result.metadata());
            assertEquals("email-header-extractor", result.metadata().extractionModel());
        }

        @Test
        void emptyBatchReturnsEmptyResult() {
            ExtractionResult result = extractor.extractBatch(List.of());
            assertTrue(result.entities().isEmpty());
            assertTrue(result.relations().isEmpty());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Threading chain integrity
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class ThreadingChainTests {

        @Test
        void replyChainFormsDag() {
            // msg1 (original) → msg2 (reply) → msg3 (reply-to-reply)
            Document msg1 = email(Map.of(
                    "email.messageId", "<msg1@ex.com>",
                    "email.subject", "Original",
                    "email.from", "alice@example.com",
                    "email.to", "bob@example.com"
            ));
            Document msg2 = email(Map.of(
                    "email.messageId", "<msg2@ex.com>",
                    "email.subject", "Re: Original",
                    "email.from", "bob@example.com",
                    "email.to", "alice@example.com",
                    "email.inReplyTo", "<msg1@ex.com>",
                    "email.references", List.of("<msg1@ex.com>")
            ));
            Document msg3 = email(Map.of(
                    "email.messageId", "<msg3@ex.com>",
                    "email.subject", "Re: Re: Original",
                    "email.from", "alice@example.com",
                    "email.to", "bob@example.com",
                    "email.inReplyTo", "<msg2@ex.com>",
                    "email.references", List.of("<msg1@ex.com>", "<msg2@ex.com>")
            ));

            ExtractionResult result = extractor.extractBatch(List.of(msg1, msg2, msg3));

            // 3 primary EMAIL_MESSAGE entities + referenced ones from inReplyTo
            // msg2 references msg1 (REPLIED_TO) — msg1 already exists as primary
            // msg3 references msg2 (REPLIED_TO) — msg2 already exists as primary
            // msg3 has reference <msg1@ex.com> which equals inReplyTo of msg2 — deduplicated
            long msgCount = result.entities().stream()
                    .filter(e -> "EMAIL_MESSAGE".equals(e.type())).count();
            assertTrue(msgCount >= 3, "At least 3 email messages in the thread");

            // 2 REPLIED_TO relations: msg2→msg1, msg3→msg2
            List<ExtractedRelation> replies = findRelations(result, "REPLIED_TO");
            assertEquals(2, replies.size());

            // msg3 references msg1 (but msg1 is NOT the inReplyTo of msg3, which is msg2)
            // so msg3 should have a REFERENCES relation to msg1
            List<ExtractedRelation> refs = findRelations(result, "REFERENCES");
            assertTrue(refs.stream().anyMatch(r -> r.description().contains("<msg1@ex.com>")));
        }

        @Test
        void deterministicIdsAllowCrossDocumentLinking() {
            // Two separate extractions of the same message-id produce the same entity ID
            Document doc1 = email(Map.of(
                    "email.messageId", "<unique@ex.com>",
                    "email.from", "alice@example.com"
            ));
            Document doc2 = email(Map.of(
                    "email.messageId", "<reply@ex.com>",
                    "email.from", "bob@example.com",
                    "email.inReplyTo", "<unique@ex.com>"
            ));

            ExtractionResult r1 = extractor.extract(doc1);
            ExtractionResult r2 = extractor.extract(doc2);

            // The primary message entity from r1
            ExtractedEntity primaryMsg = r1.entities().stream()
                    .filter(e -> "EMAIL_MESSAGE".equals(e.type()) && e.name().equals("(no subject)"))
                    .findFirst().orElseThrow();

            // The reference entity created by r2's REPLIED_TO
            ExtractedRelation replyRel = findRelations(r2, "REPLIED_TO").get(0);

            // The target of the REPLIED_TO should have the same ID as the primary message
            assertEquals(primaryMsg.id(), replyRel.target(),
                    "Deterministic IDs should allow cross-document linking");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Edge cases
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class EdgeCaseTests {

        @Test
        void emptyMetadataStillCreatesMessageEntity() {
            // Spring AI Document always has a metadata map (never null),
            // so extract() still creates an EMAIL_MESSAGE entity with "(no subject)"
            Document doc = new Document("body");
            ExtractionResult result = extractor.extract(doc);
            assertFalse(result.entities().isEmpty());
            ExtractedEntity msg = findEntity(result, "EMAIL_MESSAGE", "(no subject)");
            assertNotNull(msg, "Should still create EMAIL_MESSAGE with no-subject fallback");
        }

        @Test
        void minimalMetadataNoSubject() {
            ExtractionResult result = extractor.extract(email(Map.of(
                    "email.from", "alice@example.com"
            )));

            // Should still create an EMAIL_MESSAGE with "(no subject)" name
            ExtractedEntity msg = findEntity(result, "EMAIL_MESSAGE", "(no subject)");
            assertNotNull(msg);
        }

        @Test
        void folderStoredInProperties() {
            ExtractionResult result = extractor.extract(email(Map.of(
                    "email.messageId", "<msg@ex.com>",
                    "email.from", "alice@example.com",
                    "email.folder", "INBOX"
            )));

            ExtractedEntity msg = findEntity(result, "EMAIL_MESSAGE", null);
            assertNotNull(msg);
            assertEquals("INBOX", msg.properties().get("folder"));
        }

        @Test
        void personParsingNameInAngleBrackets() {
            ExtractionResult result = extractor.extract(email(Map.of(
                    "email.messageId", "<msg@ex.com>",
                    "email.from", "\"Dr. Alice Smith\" <alice@example.com>"
            )));

            ExtractedEntity person = findEntity(result, "PERSON", "Dr. Alice Smith");
            assertNotNull(person, "Should parse quoted display name");
        }

        @Test
        void bareEmailAddressUsedAsName() {
            ExtractionResult result = extractor.extract(email(Map.of(
                    "email.messageId", "<msg@ex.com>",
                    "email.from", "alice@example.com"
            )));

            ExtractedEntity person = findEntity(result, "PERSON", "alice@example.com");
            assertNotNull(person, "Bare email should be used as display name");
        }

        @Test
        void emailAddressNormalizedToLowerCase() {
            ExtractionResult result = extractor.extract(email(Map.of(
                    "email.messageId", "<msg@ex.com>",
                    "email.from", "Alice@EXAMPLE.COM"
            )));

            ExtractedEntity person = findEntity(result, "PERSON", null);
            assertNotNull(person);
            assertEquals("alice@example.com", person.properties().get("email"));
        }

        @Test
        void extractionMetadataIncludesSource() {
            ExtractionResult result = extractor.extract(email(Map.of(
                    "email.messageId", "<msg@ex.com>",
                    "email.from", "alice@example.com",
                    "source", "imap://mail.example.com"
            )));

            assertNotNull(result.metadata());
            assertEquals("email-header-extractor", result.metadata().extractionModel());
            assertEquals("imap://mail.example.com", result.metadata().sourceDocumentId());
        }

        @Test
        void schemaVersionSet() {
            ExtractionResult result = extractor.extract(email(Map.of(
                    "email.messageId", "<msg@ex.com>",
                    "email.from", "alice@example.com"
            )));
            assertEquals("kompile-graph-extraction/v1", result.schema());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Full integration — realistic email thread
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void fullEmailThreadGraphStructure() {
        // A 3-message thread with CC, attachment, and mailing list
        Document original = email(Map.of(
                "email.messageId", "<budget-review-001@acme.com>",
                "email.subject", "Q1 Budget Review",
                "email.date", "2025-03-01",
                "email.from", "Alice Chen <alice@acme.com>",
                "email.to", "bob@acme.com, Carol Davis <carol@acme.com>",
                "email.cc", "dave@acme.com",
                "email.listId", "finance@lists.acme.com",
                "email.attachmentName", "Q1-budget.xlsx",
                "email.attachmentMimeType", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        ));

        Document reply1 = email(Map.of(
                "email.messageId", "<budget-review-002@acme.com>",
                "email.subject", "Re: Q1 Budget Review",
                "email.date", "2025-03-02",
                "email.from", "Bob Zhao <bob@acme.com>",
                "email.to", "alice@acme.com",
                "email.inReplyTo", "<budget-review-001@acme.com>",
                "email.references", List.of("<budget-review-001@acme.com>")
        ));

        Document reply2 = email(Map.of(
                "email.messageId", "<budget-review-003@acme.com>",
                "email.subject", "Re: Re: Q1 Budget Review",
                "email.date", "2025-03-03",
                "email.from", "Carol Davis <carol@acme.com>",
                "email.to", "alice@acme.com, bob@acme.com",
                "email.inReplyTo", "<budget-review-002@acme.com>",
                "email.references", List.of("<budget-review-001@acme.com>", "<budget-review-002@acme.com>")
        ));

        ExtractionResult result = extractor.extractBatch(List.of(original, reply1, reply2));

        // ── Entity counts ──
        // EMAIL_MESSAGE: 3 primary (deduplicated with referenced ones from inReplyTo)
        long msgCount = result.entities().stream()
                .filter(e -> "EMAIL_MESSAGE".equals(e.type())).count();
        assertEquals(3, msgCount, "3 primary email messages (reply targets deduplicated)");

        // PERSON: alice, bob, carol, dave = 4 unique
        long personCount = result.entities().stream()
                .filter(e -> "PERSON".equals(e.type())).count();
        assertEquals(4, personCount, "4 unique persons across all emails");

        // Alice appears as "Alice Chen" (not bare email) after merge
        ExtractedEntity alice = result.entities().stream()
                .filter(e -> "PERSON".equals(e.type()) && "alice@acme.com".equals(e.properties().get("email")))
                .findFirst().orElseThrow();
        assertEquals("Alice Chen", alice.name());

        // Bob appears as "Bob Zhao" after merge
        ExtractedEntity bob = result.entities().stream()
                .filter(e -> "PERSON".equals(e.type()) && "bob@acme.com".equals(e.properties().get("email")))
                .findFirst().orElseThrow();
        assertEquals("Bob Zhao", bob.name());

        // MAILING_LIST: 1
        assertEquals(1, result.entities().stream()
                .filter(e -> "MAILING_LIST".equals(e.type())).count());

        // ATTACHMENT: 1
        assertEquals(1, result.entities().stream()
                .filter(e -> "ATTACHMENT".equals(e.type())).count());

        // ── Relationship counts ──
        // SENT_BY: 3 (one per email)
        assertEquals(3, findRelations(result, "SENT_BY").size());

        // SENT_TO: original→bob + original→carol + reply1→alice + reply2→alice + reply2→bob = 5
        assertEquals(5, findRelations(result, "SENT_TO").size());

        // CC_TO: original→dave = 1
        assertEquals(1, findRelations(result, "CC_TO").size());

        // REPLIED_TO: reply1→original + reply2→reply1 = 2
        assertEquals(2, findRelations(result, "REPLIED_TO").size());

        // REFERENCES: reply2 references msg1 (msg2 is deduplicated with inReplyTo)
        // reply1 has references [msg1] but msg1 = inReplyTo, so 0 from reply1
        // reply2 has references [msg1, msg2] but msg2 = inReplyTo, so 1 from reply2
        assertEquals(1, findRelations(result, "REFERENCES").size());

        // POSTED_TO: 1 (mailing list)
        assertEquals(1, findRelations(result, "POSTED_TO").size());

        // HAS_ATTACHMENT: 1
        assertEquals(1, findRelations(result, "HAS_ATTACHMENT").size());
    }
}
