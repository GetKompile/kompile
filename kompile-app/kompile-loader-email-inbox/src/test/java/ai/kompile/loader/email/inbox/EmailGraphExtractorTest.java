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
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class EmailGraphExtractorTest {

    private EmailGraphExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new EmailGraphExtractor();
    }

    // ── Entity extraction ────────────────────────────────────────────────

    @Test
    void extractsEmailMessageEntity() {
        Document doc = emailDoc(Map.of(
                "email.subject", "Test Subject",
                "email.messageId", "<msg001@example.com>",
                "email.date", "2025-05-15T10:00:00Z"
        ));

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity msgEntity = findEntityByType(result, "EMAIL_MESSAGE");
        assertNotNull(msgEntity, "Should have EMAIL_MESSAGE entity");
        assertEquals("Test Subject", msgEntity.name());
        assertEquals("msg001@example.com", msgEntity.properties().get("messageId").replace("<", "").replace(">", ""));
    }

    @Test
    void extractsPersonFromSender() {
        Document doc = emailDoc(Map.of(
                "email.from", "Alice Smith <alice@example.com>",
                "email.subject", "Hello"
        ));

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity person = findEntityByType(result, "PERSON");
        assertNotNull(person, "Should extract PERSON from sender");
        assertEquals("alice@example.com", person.properties().get("email"));

        ExtractedRelation sentBy = findRelationByType(result, "SENT_BY");
        assertNotNull(sentBy, "Should have SENT_BY relation");
        assertEquals(person.id(), sentBy.target());
    }

    @Test
    void extractsPersonsFromRecipients() {
        Document doc = emailDoc(Map.of(
                "email.to", "bob@example.com, carol@example.com",
                "email.subject", "Team update"
        ));

        ExtractionResult result = extractor.extract(doc);

        List<ExtractedRelation> sentTo = result.relations().stream()
                .filter(r -> "SENT_TO".equals(r.type()))
                .toList();
        assertEquals(2, sentTo.size(), "Should have 2 SENT_TO relations");

        List<ExtractedEntity> persons = result.entities().stream()
                .filter(e -> "PERSON".equals(e.type()))
                .toList();
        assertEquals(2, persons.size(), "Should have 2 PERSON entities");
    }

    @Test
    void extractsCcRecipients() {
        Document doc = emailDoc(Map.of(
                "email.cc", "dave@example.com",
                "email.subject", "FYI"
        ));

        ExtractionResult result = extractor.extract(doc);

        ExtractedRelation ccTo = findRelationByType(result, "CC_TO");
        assertNotNull(ccTo, "Should have CC_TO relation");
    }

    @Test
    void extractsBccRecipients() {
        Document doc = emailDoc(Map.of(
                "email.bcc", "secret@example.com",
                "email.subject", "Confidential"
        ));

        ExtractionResult result = extractor.extract(doc);

        ExtractedRelation bccTo = findRelationByType(result, "BCC_TO");
        assertNotNull(bccTo, "Should have BCC_TO relation");
        assertEquals(0.9, bccTo.confidence(), 0.01, "BCC should have lower confidence");
    }

    // ── Threading relations ──────────────────────────────────────────────

    @Test
    void extractsReplyToRelation() {
        Document doc = emailDoc(Map.of(
                "email.subject", "Re: Original",
                "email.messageId", "<reply@example.com>",
                "email.inReplyTo", "<original@example.com>"
        ));

        ExtractionResult result = extractor.extract(doc);

        ExtractedRelation replied = findRelationByType(result, "REPLIED_TO");
        assertNotNull(replied, "Should have REPLIED_TO relation");

        // Should also create an EMAIL_MESSAGE entity for the referenced message
        long emailMessages = result.entities().stream()
                .filter(e -> "EMAIL_MESSAGE".equals(e.type()))
                .count();
        assertEquals(2, emailMessages, "Should have 2 EMAIL_MESSAGE entities (current + referenced)");
    }

    @Test
    void extractsReferencesRelations() {
        Document doc = emailDoc(Map.of(
                "email.subject", "Re: Re: Thread",
                "email.messageId", "<msg3@example.com>",
                "email.inReplyTo", "<msg2@example.com>",
                "email.references", List.of("<msg1@example.com>", "<msg2@example.com>")
        ));

        ExtractionResult result = extractor.extract(doc);

        // msg2 is already linked via REPLIED_TO, so only msg1 should get REFERENCES
        List<ExtractedRelation> references = result.relations().stream()
                .filter(r -> "REFERENCES".equals(r.type()))
                .toList();
        assertEquals(1, references.size(), "Should have 1 REFERENCES relation (excluding inReplyTo duplicate)");
    }

    // ── Mailing list ─────────────────────────────────────────────────────

    @Test
    void extractsMailingList() {
        Document doc = emailDoc(Map.of(
                "email.subject", "Discussion",
                "email.listId", "<dev.example.com>"
        ));

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity listEntity = findEntityByType(result, "MAILING_LIST");
        assertNotNull(listEntity, "Should have MAILING_LIST entity");

        ExtractedRelation postedTo = findRelationByType(result, "POSTED_TO");
        assertNotNull(postedTo, "Should have POSTED_TO relation");
    }

    // ── Attachments ──────────────────────────────────────────────────────

    @Test
    void extractsAttachmentEntity() {
        Document doc = emailDoc(Map.of(
                "email.subject", "See attached",
                "email.attachmentName", "report.pdf",
                "email.attachmentMimeType", "application/pdf"
        ));

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity attachment = findEntityByType(result, "ATTACHMENT");
        assertNotNull(attachment, "Should have ATTACHMENT entity");
        assertEquals("report.pdf", attachment.name());
        assertEquals("application/pdf", attachment.properties().get("mimeType"));

        ExtractedRelation hasAttachment = findRelationByType(result, "HAS_ATTACHMENT");
        assertNotNull(hasAttachment, "Should have HAS_ATTACHMENT relation");
    }

    // ── Batch deduplication ──────────────────────────────────────────────

    @Test
    void batchDeduplicatesEntitiesByEmail() {
        Document doc1 = emailDoc(Map.of(
                "email.from", "alice@example.com",
                "email.to", "bob@example.com",
                "email.subject", "First"
        ));
        Document doc2 = emailDoc(Map.of(
                "email.from", "alice@example.com",
                "email.to", "carol@example.com",
                "email.subject", "Second"
        ));

        ExtractionResult result = extractor.extractBatch(List.of(doc1, doc2));

        // alice@example.com appears in both emails — should be deduplicated
        long aliceCount = result.entities().stream()
                .filter(e -> "PERSON".equals(e.type()))
                .filter(e -> e.properties() != null && "alice@example.com".equals(e.properties().get("email")))
                .count();
        assertEquals(1, aliceCount, "Alice should appear once after deduplication");

        // Should have 2 EMAIL_MESSAGE entities
        long msgCount = result.entities().stream()
                .filter(e -> "EMAIL_MESSAGE".equals(e.type()))
                .count();
        assertEquals(2, msgCount);
    }

    @Test
    void batchMergesAliases() {
        // Same email, different display names across emails
        Document doc1 = emailDoc(Map.of(
                "email.from", "Alice <alice@example.com>",
                "email.subject", "First"
        ));
        Document doc2 = emailDoc(Map.of(
                "email.from", "Alice Smith <alice@example.com>",
                "email.subject", "Second"
        ));

        ExtractionResult result = extractor.extractBatch(List.of(doc1, doc2));

        List<ExtractedEntity> aliceEntities = result.entities().stream()
                .filter(e -> "PERSON".equals(e.type()))
                .filter(e -> e.properties() != null && "alice@example.com".equals(e.properties().get("email")))
                .toList();
        assertEquals(1, aliceEntities.size());

        ExtractedEntity alice = aliceEntities.get(0);
        // Both aliases should be merged
        assertTrue(alice.aliases().size() >= 2,
                "Should have merged aliases from both emails: " + alice.aliases());
    }

    // ── Person parsing edge cases ────────────────────────────────────────

    @Test
    void parsesNameAndEmailFormats() {
        EmailGraphExtractor ext = new EmailGraphExtractor();

        // Name <email> format
        var persons1 = ext.parsePersons("Alice Smith <alice@example.com>");
        assertEquals(1, persons1.size());
        assertEquals("alice@example.com", persons1.get(0).email());
        assertEquals("Alice Smith", persons1.get(0).displayName());

        // Bare email
        var persons2 = ext.parsePersons("bob@example.com");
        assertEquals(1, persons2.size());
        assertEquals("bob@example.com", persons2.get(0).email());

        // Quoted name
        var persons3 = ext.parsePersons("\"Carol Jones\" <carol@example.com>");
        assertEquals(1, persons3.size());
        assertEquals("Carol Jones", persons3.get(0).displayName());
    }

    @Test
    void parsesMultipleAddresses() {
        var persons = extractor.parsePersons(
                "Alice <alice@example.com>, bob@example.com, \"Carol\" <carol@example.com>");
        assertEquals(3, persons.size());
    }

    @Test
    void parsesEmptyAndNullAddresses() {
        assertTrue(extractor.parsePersons(null).isEmpty());
        assertTrue(extractor.parsePersons("").isEmpty());
        assertTrue(extractor.parsePersons("   ").isEmpty());
    }

    // ── Null metadata handling ───────────────────────────────────────────

    @Test
    void handlesNullMetadata() {
        Document doc = new Document("test email body");
        // Spring AI Document starts with a non-null empty metadata map,
        // but we test the extractor's null guard
        ExtractionResult result = extractor.extract(doc);
        assertNotNull(result);
    }

    @Test
    void handlesMinimalMetadata() {
        // No subject, no messageId — should still produce an entity
        Document doc = emailDoc(Map.of("email.from", "test@example.com"));
        ExtractionResult result = extractor.extract(doc);

        assertFalse(result.entities().isEmpty());
        ExtractedEntity msg = findEntityByType(result, "EMAIL_MESSAGE");
        assertNotNull(msg);
        assertEquals("(no subject)", msg.name());
    }

    // ── Deterministic IDs ────────────────────────────────────────────────

    @Test
    void sameEmailProducesSameEntityId() {
        Document doc1 = emailDoc(Map.of(
                "email.from", "alice@example.com",
                "email.subject", "A"
        ));
        Document doc2 = emailDoc(Map.of(
                "email.to", "alice@example.com",
                "email.subject", "B"
        ));

        ExtractionResult r1 = extractor.extract(doc1);
        ExtractionResult r2 = extractor.extract(doc2);

        ExtractedEntity alice1 = r1.entities().stream()
                .filter(e -> "PERSON".equals(e.type())).findFirst().orElse(null);
        ExtractedEntity alice2 = r2.entities().stream()
                .filter(e -> "PERSON".equals(e.type())).findFirst().orElse(null);

        assertNotNull(alice1);
        assertNotNull(alice2);
        assertEquals(alice1.id(), alice2.id(), "Same email address should produce same entity ID");
    }

    // ── Full email extraction ────────────────────────────────────────────

    @Test
    void fullEmailExtraction() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("email.from", "Alice Smith <alice@example.com>");
        meta.put("email.to", "bob@example.com, carol@example.com");
        meta.put("email.cc", "dave@example.com");
        meta.put("email.subject", "Project Update");
        meta.put("email.messageId", "<msg001@example.com>");
        meta.put("email.date", "2025-05-15T10:00:00Z");
        meta.put("email.inReplyTo", "<msg000@example.com>");
        meta.put("email.listId", "<team.example.com>");
        meta.put("email.attachmentName", "slides.pptx");
        meta.put("email.attachmentMimeType", "application/vnd.ms-powerpoint");
        meta.put("source", "/inbox/cur/12345");
        Document doc = emailDoc(meta);

        ExtractionResult result = extractor.extract(doc);

        // Entities: 1 EMAIL_MESSAGE + 4 PERSON + 1 replied-to EMAIL_MESSAGE + 1 MAILING_LIST + 1 ATTACHMENT
        assertTrue(result.entities().size() >= 8, "Expected at least 8 entities, got " + result.entities().size());

        // Relations: SENT_BY + 2 SENT_TO + CC_TO + REPLIED_TO + POSTED_TO + HAS_ATTACHMENT
        assertTrue(result.relations().size() >= 7, "Expected at least 7 relations, got " + result.relations().size());

        // Metadata
        assertNotNull(result.metadata());
        assertEquals("email-header-extractor", result.metadata().extractionModel());
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Document emailDoc(Map<String, Object> metadata) {
        Document doc = new Document("email body text");
        doc.getMetadata().putAll(metadata);
        return doc;
    }

    // --- Email folder ---

    @Test
    void extractCreatesEmailFolderHierarchyFromNestedFolder() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("email.from", "alice@test.com");
        meta.put("email.subject", "Report");
        meta.put("email.folder", "INBOX/Work");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        // Should create two EMAIL_FOLDER entities: "INBOX" and "Work"
        List<ExtractedEntity> folders = result.entities().stream()
                .filter(e -> "EMAIL_FOLDER".equals(e.type()))
                .toList();
        assertEquals(2, folders.size(), "Should create 2 folder entities for INBOX/Work");
        assertTrue(folders.stream().anyMatch(f -> "INBOX".equals(f.name())));
        assertTrue(folders.stream().anyMatch(f -> "Work".equals(f.name())));
        // SUBFOLDER_OF should link Work → INBOX
        ExtractedRelation subfolderOf = findRelationByType(result, "SUBFOLDER_OF");
        assertNotNull(subfolderOf, "SUBFOLDER_OF relation should link Work to INBOX");
        // IN_FOLDER should link message to leaf folder (Work)
        ExtractedRelation inFolder = findRelationByType(result, "IN_FOLDER");
        assertNotNull(inFolder, "IN_FOLDER relation should be created");
    }

    @Test
    void extractCreatesEmailFolderFromPstFolder() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("email.from", "alice@test.com");
        meta.put("email.subject", "Report");
        meta.put("email.pstFolder", "Sent Items");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity folder = findEntityByType(result, "EMAIL_FOLDER");
        assertNotNull(folder, "EMAIL_FOLDER entity should be created from pstFolder");
        assertEquals("Sent Items", folder.name());
    }

    @Test
    void extractCreatesEmailFolderFromMaildirSubdir() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("email.from", "alice@test.com");
        meta.put("email.subject", "Report");
        meta.put("email.maildirSubdir", "cur");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity folder = findEntityByType(result, "EMAIL_FOLDER");
        assertNotNull(folder, "EMAIL_FOLDER entity should be created from maildirSubdir");
        assertEquals("cur", folder.name());
    }

    @Test
    void extractPrefersEmailFolderOverPstFolder() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("email.from", "alice@test.com");
        meta.put("email.subject", "Report");
        meta.put("email.folder", "INBOX");
        meta.put("email.pstFolder", "Sent Items");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity folder = findEntityByType(result, "EMAIL_FOLDER");
        assertNotNull(folder);
        assertEquals("INBOX", folder.name(), "email.folder should take priority over pstFolder");
    }

    @Test
    void extractNoFolderWhenNoneAvailable() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("email.from", "alice@test.com");
        meta.put("email.subject", "Report");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertNull(findEntityByType(result, "EMAIL_FOLDER"));
        assertNull(findRelationByType(result, "IN_FOLDER"));
    }

    // ── Conversation topic ────────────────────────────────────────────

    @Test
    void extractConversationTopicCreatesEntity() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("email.from", "alice@test.com");
        meta.put("email.subject", "Re: Q3 Budget Review");
        meta.put("email.messageId", "<msg999@test.com>");
        meta.put("email.conversationTopic", "Q3 Budget Review");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity topic = result.entities().stream()
                .filter(e -> "CONVERSATION_TOPIC".equals(e.type()))
                .findFirst().orElse(null);
        assertNotNull(topic, "Should create CONVERSATION_TOPIC entity");
        assertEquals("Q3 Budget Review", topic.name());
        assertTrue(result.relations().stream()
                .anyMatch(r -> "HAS_CONVERSATION_TOPIC".equals(r.type())));
    }

    @Test
    void noConversationTopicWithoutMetadata() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("email.from", "alice@test.com");
        meta.put("email.subject", "Hello");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertFalse(result.entities().stream()
                .anyMatch(e -> "CONVERSATION_TOPIC".equals(e.type())));
    }

    // ── From name enrichment ────────────────────────────────────────────

    @Test
    void extractEnrichesPersonNameFromExplicitFromName() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("email.from", "alice@test.com");
        meta.put("email.fromName", "Alice Smith");
        meta.put("email.fromAddress", "alice@test.com");
        meta.put("email.subject", "Test");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity sender = result.entities().stream()
                .filter(e -> "PERSON".equals(e.type()))
                .findFirst().orElse(null);
        assertNotNull(sender);
        assertEquals("Alice Smith", sender.name(),
                "When email.from is a bare address, email.fromName should enrich the PERSON entity name");
    }

    @Test
    void extractPreservesExistingNameOverFromName() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("email.from", "Alice Jones <alice@test.com>");
        meta.put("email.fromName", "Alice Smith");
        meta.put("email.subject", "Test");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity sender = result.entities().stream()
                .filter(e -> "PERSON".equals(e.type()))
                .findFirst().orElse(null);
        assertNotNull(sender);
        assertEquals("Alice Jones", sender.name(),
                "When email.from already has a name, it should take precedence over email.fromName");
    }

    // ── Attachment size and email format ────────────────────────────────

    @Test
    void attachmentSizeStoredOnAttachmentEntity() {
        Document doc = emailDoc(Map.of(
                "email.from", "alice@example.com",
                "email.subject", "With file",
                "email.attachmentNames", List.of("report.pdf"),
                "email.attachmentMimeType", "application/pdf",
                "email.attachmentSize", 54321
        ));

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity attach = findEntityByType(result, "ATTACHMENT");
        assertNotNull(attach, "ATTACHMENT entity should exist");
        assertEquals("54321", attach.properties().get("size"),
                "Attachment size should be captured");
    }

    @Test
    void standaloneAttachmentSizeStoredOnEntity() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("email.from", "bob@example.com");
        meta.put("email.subject", "Standalone");
        meta.put("email.isAttachment", true);
        meta.put("email.attachmentName", "photo.jpg");
        meta.put("email.attachmentMimeType", "image/jpeg");
        meta.put("email.attachmentSize", "98765");
        meta.put("email.parentMessageId", "<parent@example.com>");
        Document doc = new Document("photo content", meta);

        ExtractionResult result = extractor.extract(doc);

        List<ExtractedEntity> attachments = result.entities().stream()
                .filter(e -> "ATTACHMENT".equals(e.type())).toList();
        assertFalse(attachments.isEmpty(), "Should have ATTACHMENT entity");
        assertTrue(attachments.stream().anyMatch(a -> "98765".equals(a.properties().get("size"))),
                "Standalone attachment size should be captured");
    }

    @Test
    void emailFormatStoredOnMessageEntity() {
        Document doc = emailDoc(Map.of(
                "email.from", "alice@example.com",
                "email.subject", "EMLX test",
                "email.format", "emlx"
        ));

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity msg = findEntityByType(result, "EMAIL_MESSAGE");
        assertNotNull(msg);
        assertEquals("emlx", msg.properties().get("format"),
                "email.format should be stored as 'format' property on EMAIL_MESSAGE");
    }

    // ── ICS / Calendar event tests ───────────────────────────────────────

    @Test
    void icsAttachmentByMimeTypeProducesCalendarEventEntity() {
        Document doc = emailDoc(Map.of(
                "email.subject", "Team standup",
                "email.from", "alice@example.com",
                "email.attachmentName", "invite.ics",
                "email.attachmentMimeType", "text/calendar"
        ));

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity calEvent = findEntityByType(result, "CALENDAR_EVENT");
        assertNotNull(calEvent, "Should produce CALENDAR_EVENT entity for text/calendar attachment");
        assertEquals("invite.ics", calEvent.name()); // no SUMMARY in content, falls back to filename

        ExtractedRelation rel = findRelationByType(result, "HAS_CALENDAR_EVENT");
        assertNotNull(rel, "Should have HAS_CALENDAR_EVENT relation");
        assertNull(findEntityByType(result, "ATTACHMENT"), "Should not produce plain ATTACHMENT for ICS");
    }

    @Test
    void icsAttachmentByFilenameExtensionProducesCalendarEventEntity() {
        Document doc = emailDoc(Map.of(
                "email.subject", "Conference call",
                "email.from", "bob@example.com",
                "email.attachmentName", "meeting.ICS",
                "email.attachmentMimeType", "application/octet-stream"
        ));

        ExtractionResult result = extractor.extract(doc);

        assertNotNull(findEntityByType(result, "CALENDAR_EVENT"),
                "Should detect ICS by .ICS extension regardless of MIME type");
    }

    @Test
    void icsAttachmentWithIcsContentExtractsFields() {
        String icsContent = """
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                SUMMARY:Quarterly Review
                DTSTART:20250601T100000Z
                DTEND:20250601T110000Z
                LOCATION:Conference Room A
                ORGANIZER:MAILTO:organizer@example.com
                ATTENDEE:MAILTO:alice@example.com
                ATTENDEE:MAILTO:bob@example.com
                END:VEVENT
                END:VCALENDAR
                """;

        Map<String, Object> meta = new HashMap<>();
        meta.put("email.subject", "Meeting invite");
        meta.put("email.from", "organizer@example.com");
        meta.put("email.attachmentName", "invite.ics");
        meta.put("email.attachmentMimeType", "text/calendar");
        meta.put("email.icsContent", icsContent);
        Document doc = emailDoc(meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity calEvent = findEntityByType(result, "CALENDAR_EVENT");
        assertNotNull(calEvent);
        assertEquals("Quarterly Review", calEvent.name(), "SUMMARY should be used as entity name");
        assertEquals("20250601T100000Z", calEvent.properties().get("dtstart"));
        assertEquals("20250601T110000Z", calEvent.properties().get("dtend"));
        assertEquals("Conference Room A", calEvent.properties().get("location"));
        assertEquals("organizer@example.com", calEvent.properties().get("organizer"));

        ExtractedRelation organizedBy = findRelationByType(result, "ORGANIZED_BY");
        assertNotNull(organizedBy, "Should have ORGANIZED_BY relation");

        long attendedByCount = result.relations().stream()
                .filter(r -> "ATTENDED_BY".equals(r.type()))
                .count();
        assertEquals(2, attendedByCount, "Should have 2 ATTENDED_BY relations");
    }

    @Test
    void nonIcsAttachmentStillProducesAttachmentEntity() {
        Document doc = emailDoc(Map.of(
                "email.subject", "See attached",
                "email.from", "alice@example.com",
                "email.attachmentName", "report.pdf",
                "email.attachmentMimeType", "application/pdf"
        ));

        ExtractionResult result = extractor.extract(doc);

        assertNotNull(findEntityByType(result, "ATTACHMENT"), "PDF should still be ATTACHMENT");
        assertNull(findEntityByType(result, "CALENDAR_EVENT"), "PDF should not produce CALENDAR_EVENT");
    }

    @Test
    void standaloneIcsAttachmentProducesCalendarEventEntity() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("email.from", "alice@example.com");
        meta.put("email.subject", "ICS forward");
        meta.put("email.isAttachment", true);
        meta.put("email.attachmentName", "event.ics");
        meta.put("email.attachmentMimeType", "text/calendar");
        meta.put("email.parentMessageId", "<parent001@example.com>");
        Document doc = new Document("calendar content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertNotNull(findEntityByType(result, "CALENDAR_EVENT"),
                "Standalone ICS attachment should produce CALENDAR_EVENT");
        assertNotNull(findRelationByType(result, "HAS_CALENDAR_EVENT"),
                "Should have HAS_CALENDAR_EVENT relation from parent message");
    }

    @Test
    void icsParserExtractsAllFields() {
        String icsContent = """
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                SUMMARY:Sprint Planning
                DTSTART;TZID=America/New_York:20250610T090000
                DTEND;TZID=America/New_York:20250610T100000
                LOCATION:Zoom
                ORGANIZER:MAILTO:pm@example.com
                ATTENDEE:MAILTO:dev1@example.com
                ATTENDEE:MAILTO:dev2@example.com
                END:VEVENT
                END:VCALENDAR
                """;
        Map<String, String> props = new LinkedHashMap<>();
        EmailGraphExtractor.extractIcsFields(icsContent, props);

        assertEquals("Sprint Planning", props.get("summary"));
        assertEquals("20250610T090000", props.get("dtstart")); // timezone stripped by base key parsing
        assertEquals("Zoom", props.get("location"));
        assertEquals("pm@example.com", props.get("organizer"));
        assertTrue(props.get("attendees").contains("dev1@example.com"));
        assertTrue(props.get("attendees").contains("dev2@example.com"));
    }

    @Test
    void icsParserExtractsStatusRruleUidSequenceDescription() {
        String icsContent = """
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                SUMMARY:Weekly Standup
                DTSTART:20250615T090000Z
                DTEND:20250615T091500Z
                LOCATION:Room 101
                STATUS:CONFIRMED
                UID:abc-123@example.com
                SEQUENCE:2
                RRULE:FREQ=WEEKLY;BYDAY=MO
                DESCRIPTION:Weekly standup meeting for the engineering team
                ORGANIZER:MAILTO:lead@example.com
                ATTENDEE:MAILTO:dev@example.com
                END:VEVENT
                END:VCALENDAR
                """;
        Map<String, String> props = new LinkedHashMap<>();
        EmailGraphExtractor.extractIcsFields(icsContent, props);

        assertEquals("Weekly Standup", props.get("summary"));
        assertEquals("CONFIRMED", props.get("status"));
        assertEquals("abc-123@example.com", props.get("uid"));
        assertEquals("2", props.get("sequence"));
        assertEquals("FREQ=WEEKLY;BYDAY=MO", props.get("rrule"));
        assertEquals("Weekly standup meeting for the engineering team", props.get("description"));
        assertEquals("lead@example.com", props.get("organizer"));
        assertTrue(props.get("attendees").contains("dev@example.com"));
    }

    @Test
    void icsParserHandlesCancelledEvent() {
        String icsContent = """
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                SUMMARY:Cancelled Meeting
                STATUS:CANCELLED
                UID:cancel-456@example.com
                END:VEVENT
                END:VCALENDAR
                """;
        Map<String, String> props = new LinkedHashMap<>();
        EmailGraphExtractor.extractIcsFields(icsContent, props);

        assertEquals("Cancelled Meeting", props.get("summary"));
        assertEquals("CANCELLED", props.get("status"));
        assertEquals("cancel-456@example.com", props.get("uid"));
    }

    @Test
    void icsParserHandlesEmptyContent() {
        Map<String, String> props = new LinkedHashMap<>();
        EmailGraphExtractor.extractIcsFields("", props);
        assertTrue(props.isEmpty(), "Empty ICS content should produce no properties");

        EmailGraphExtractor.extractIcsFields(null, props);
        assertTrue(props.isEmpty(), "Null ICS content should produce no properties");
    }

    // ── ICS LOCATION entity and AT_LOCATION relation ──────────────────────

    @Test
    void icsWithLocationFieldCreatesLocationEntityAndRelation() {
        String icsContent = """
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                SUMMARY:Team Standup
                DTSTART:20250601T090000Z
                DTEND:20250601T093000Z
                LOCATION:Conference Room B
                ORGANIZER:MAILTO:boss@example.com
                ATTENDEE:MAILTO:dev1@example.com
                ATTENDEE:MAILTO:dev2@example.com
                END:VEVENT
                END:VCALENDAR
                """;

        Map<String, Object> meta = new HashMap<>();
        meta.put("email.subject", "Team Standup invite");
        meta.put("email.from", "boss@example.com");
        meta.put("email.attachmentName", "invite.ics");
        meta.put("email.attachmentMimeType", "text/calendar");
        meta.put("email.icsContent", icsContent);
        Document doc = emailDoc(meta);

        ExtractionResult result = extractor.extract(doc);

        // LOCATION entity exists with correct title and property
        ExtractedEntity location = findEntityByType(result, "LOCATION");
        assertNotNull(location, "Should have a LOCATION entity");
        assertEquals("Conference Room B", location.name(),
                "LOCATION entity name should be the location name");
        assertEquals("Conference Room B", location.properties().get("locationName"),
                "LOCATION entity should have locationName property");

        // AT_LOCATION relation exists from CALENDAR_EVENT to LOCATION
        ExtractedEntity calEvent = findEntityByType(result, "CALENDAR_EVENT");
        assertNotNull(calEvent, "Should have a CALENDAR_EVENT entity");

        ExtractedRelation atLocation = findRelationByType(result, "AT_LOCATION");
        assertNotNull(atLocation, "Should have an AT_LOCATION relation");
        assertEquals(calEvent.id(), atLocation.source(),
                "AT_LOCATION source should be the CALENDAR_EVENT entity");
        assertEquals(location.id(), atLocation.target(),
                "AT_LOCATION target should be the LOCATION entity");

        // Organizer and attendees still work correctly
        ExtractedRelation organizedBy = findRelationByType(result, "ORGANIZED_BY");
        assertNotNull(organizedBy, "Should still have ORGANIZED_BY relation");

        long attendedByCount = result.relations().stream()
                .filter(r -> "ATTENDED_BY".equals(r.type()))
                .count();
        assertEquals(2, attendedByCount, "Should still have 2 ATTENDED_BY relations");
    }

    @Test
    void icsWithoutLocationFieldCreatesNoLocationEntity() {
        String icsContent = """
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                SUMMARY:No Location Meeting
                DTSTART:20250601T090000Z
                DTEND:20250601T093000Z
                ORGANIZER:MAILTO:host@example.com
                ATTENDEE:MAILTO:guest@example.com
                END:VEVENT
                END:VCALENDAR
                """;

        Map<String, Object> meta = new HashMap<>();
        meta.put("email.subject", "No Location invite");
        meta.put("email.from", "host@example.com");
        meta.put("email.attachmentName", "invite.ics");
        meta.put("email.attachmentMimeType", "text/calendar");
        meta.put("email.icsContent", icsContent);
        Document doc = emailDoc(meta);

        ExtractionResult result = extractor.extract(doc);

        assertNull(findEntityByType(result, "LOCATION"),
                "Should not create a LOCATION entity when ICS has no LOCATION field");
        assertNull(findRelationByType(result, "AT_LOCATION"),
                "Should not create an AT_LOCATION relation when ICS has no LOCATION field");

        // Calendar event should still be created
        assertNotNull(findEntityByType(result, "CALENDAR_EVENT"),
                "CALENDAR_EVENT entity should still be created");
    }

    // ── IMAP flags and extended headers ──────────────────────────────────

    @Test
    void imapFlagsAppearsInMessageEntityProperties() {
        Document doc = emailDoc(Map.of(
                "email.subject", "Flagged Message",
                "email.from", "alice@example.com",
                "email.flagSeen", true,
                "email.flagFlagged", true,
                "email.flagDraft", false,
                "email.flagAnswered", false,
                "email.flagDeleted", false
        ));

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity msg = findEntityByType(result, "EMAIL_MESSAGE");
        assertNotNull(msg, "Should have EMAIL_MESSAGE entity");
        assertEquals("true", msg.properties().get("flagSeen"),
                "flagSeen=true should appear as 'true' in message properties");
        assertEquals("true", msg.properties().get("flagFlagged"),
                "flagFlagged=true should appear as 'true' in message properties");
        assertEquals("false", msg.properties().get("flagDraft"),
                "flagDraft=false should appear as 'false' in message properties");
        assertEquals("false", msg.properties().get("flagAnswered"),
                "flagAnswered=false should appear as 'false' in message properties");
        assertEquals("false", msg.properties().get("flagDeleted"),
                "flagDeleted=false should appear as 'false' in message properties");
    }

    @Test
    void replyToAppearsInMessageEntityProperties() {
        Document doc = emailDoc(Map.of(
                "email.subject", "Reply-To Test",
                "email.from", "sender@example.com",
                "email.replyTo", "replies@example.com"
        ));

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity msg = findEntityByType(result, "EMAIL_MESSAGE");
        assertNotNull(msg, "Should have EMAIL_MESSAGE entity");
        assertEquals("replies@example.com", msg.properties().get("replyTo"),
                "Reply-To header should be stored as 'replyTo' in message properties");
    }

    @Test
    void autoReplyDetectionAppearsInMessageEntityProperties() {
        Document doc = emailDoc(Map.of(
                "email.subject", "Out of Office: Re: Meeting",
                "email.from", "alice@example.com",
                "email.autoSubmitted", "auto-replied",
                "email.isAutoReply", true
        ));

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity msg = findEntityByType(result, "EMAIL_MESSAGE");
        assertNotNull(msg, "Should have EMAIL_MESSAGE entity");
        assertEquals("auto-replied", msg.properties().get("autoSubmitted"),
                "Auto-Submitted header value should be stored in message properties");
        assertEquals("true", msg.properties().get("isAutoReply"),
                "isAutoReply=true should appear as 'true' in message properties");
    }

    @Test
    void returnPathAppearsInMessageEntityProperties() {
        Document doc = emailDoc(Map.of(
                "email.subject", "Bounce Test",
                "email.from", "sender@example.com",
                "email.returnPath", "<bounce@example.com>"
        ));

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity msg = findEntityByType(result, "EMAIL_MESSAGE");
        assertNotNull(msg, "Should have EMAIL_MESSAGE entity");
        assertEquals("<bounce@example.com>", msg.properties().get("returnPath"),
                "Return-Path header should be stored as 'returnPath' in message properties");
    }

    private ExtractedEntity findEntityByType(ExtractionResult result, String type) {
        return result.entities().stream()
                .filter(e -> type.equals(e.type()))
                .findFirst().orElse(null);
    }

    private ExtractedRelation findRelationByType(ExtractionResult result, String type) {
        return result.relations().stream()
                .filter(r -> type.equals(r.type()))
                .findFirst().orElse(null);
    }
}
