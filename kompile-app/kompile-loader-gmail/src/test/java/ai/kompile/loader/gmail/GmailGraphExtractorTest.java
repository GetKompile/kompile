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

package ai.kompile.loader.gmail;

import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedEntity;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedRelation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GmailGraphExtractorTest {

    private GmailGraphExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new GmailGraphExtractor();
    }

    // ── extract() — message entities ────────────────────────────────────

    @Test
    void extractsGmailMessageEntity() {
        Document doc = gmailDoc(Map.of(
                "gmail.messageId", "msg001",
                "gmail.threadId", "thread001",
                "gmail.subject", "Test Subject",
                "gmail.from", "alice@example.com",
                "gmail.to", "bob@example.com"
        ));

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity msgEntity = findEntityByType(result.entities(), "GMAIL_MESSAGE");
        assertNotNull(msgEntity, "Should have a GMAIL_MESSAGE entity");
        assertEquals("Test Subject", msgEntity.name());
    }

    @Test
    void extractsThreadEntity() {
        Document doc = gmailDoc(Map.of(
                "gmail.messageId", "msg001",
                "gmail.threadId", "thread001",
                "gmail.subject", "Test",
                "gmail.from", "a@b.com",
                "gmail.to", "c@d.com"
        ));

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity threadEntity = findEntityByType(result.entities(), "GMAIL_THREAD");
        assertNotNull(threadEntity);

        assertTrue(result.relations().stream().anyMatch(r -> "IN_THREAD".equals(r.type())),
                "Should have IN_THREAD relationship");
    }

    // ── extract() — person entities ─────────────────────────────────────

    @Test
    void extractsSenderAsPerson() {
        Document doc = gmailDoc(Map.of(
                "gmail.messageId", "msg001",
                "gmail.threadId", "thread001",
                "gmail.subject", "Test",
                "gmail.from", "Alice Smith <alice@example.com>",
                "gmail.to", "bob@example.com"
        ));

        ExtractionResult result = extractor.extract(doc);

        List<ExtractedEntity> persons = result.entities().stream()
                .filter(e -> "PERSON".equals(e.type()))
                .toList();
        assertTrue(persons.size() >= 1, "Should have at least one PERSON entity");

        ExtractedEntity sender = persons.stream()
                .filter(e -> "Alice Smith".equals(e.name()))
                .findFirst().orElse(null);
        assertNotNull(sender, "Sender should have extracted name 'Alice Smith'");

        assertTrue(result.relations().stream().anyMatch(r -> "SENT_BY".equals(r.type())),
                "Should have SENT_BY relationship");
    }

    @Test
    void extractsRecipientPersons() {
        Document doc = gmailDoc(Map.of(
                "gmail.messageId", "msg001",
                "gmail.threadId", "thread001",
                "gmail.subject", "Test",
                "gmail.from", "alice@example.com",
                "gmail.to", "Bob Jones <bob@example.com>, carol@example.com"
        ));

        ExtractionResult result = extractor.extract(doc);

        long sentToCount = result.relations().stream()
                .filter(r -> "SENT_TO".equals(r.type()))
                .count();
        assertEquals(2, sentToCount, "Should have 2 SENT_TO relationships");
    }

    @Test
    void extractsCcPersons() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("gmail.messageId", "msg001");
        meta.put("gmail.threadId", "thread001");
        meta.put("gmail.subject", "Test");
        meta.put("gmail.from", "alice@example.com");
        meta.put("gmail.to", "bob@example.com");
        meta.put("gmail.cc", "carol@example.com, dave@example.com");
        Document doc = gmailDoc(meta);

        ExtractionResult result = extractor.extract(doc);

        long ccCount = result.relations().stream()
                .filter(r -> "CC_TO".equals(r.type()))
                .count();
        assertEquals(2, ccCount, "Should have 2 CC_TO relationships");
    }

    // ── extract() — BCC persons ─────────────────────────────────────────

    @Test
    void extractsBccPersons() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("gmail.messageId", "msg001");
        meta.put("gmail.threadId", "thread001");
        meta.put("gmail.subject", "Test");
        meta.put("gmail.from", "alice@example.com");
        meta.put("gmail.to", "bob@example.com");
        meta.put("gmail.bcc", "secret@example.com, hidden@example.com");
        Document doc = gmailDoc(meta);

        ExtractionResult result = extractor.extract(doc);

        long bccCount = result.relations().stream()
                .filter(r -> "BCC_TO".equals(r.type()))
                .count();
        assertEquals(2, bccCount, "Should have 2 BCC_TO relationships");
    }

    // ── extract() — reply chain ─────────────────────────────────────────

    @Test
    void extractsReplyToRelation() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("gmail.messageId", "msg002");
        meta.put("gmail.threadId", "thread001");
        meta.put("gmail.subject", "Re: Test");
        meta.put("gmail.from", "bob@example.com");
        meta.put("gmail.to", "alice@example.com");
        meta.put("gmail.inReplyTo", "<original-msg-id@example.com>");
        Document doc = gmailDoc(meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.relations().stream().anyMatch(r -> "REPLIED_TO".equals(r.type())),
                "Should have REPLIED_TO relationship");
    }

    // ── extract() — references chain ────────────────────────────────────

    @Test
    void extractsReferencesRelations() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("gmail.messageId", "msg003");
        meta.put("gmail.threadId", "thread001");
        meta.put("gmail.subject", "Re: Re: Test");
        meta.put("gmail.from", "carol@example.com");
        meta.put("gmail.to", "alice@example.com");
        meta.put("gmail.inReplyTo", "<msg002@example.com>");
        meta.put("gmail.references", List.of("<msg001@example.com>", "<msg002@example.com>"));
        Document doc = gmailDoc(meta);

        ExtractionResult result = extractor.extract(doc);

        // REPLIED_TO for inReplyTo
        assertTrue(result.relations().stream().anyMatch(r -> "REPLIED_TO".equals(r.type())),
                "Should have REPLIED_TO relationship");

        // REFERENCES for the reference that isn't the same as inReplyTo
        long refsCount = result.relations().stream()
                .filter(r -> "REFERENCES".equals(r.type()))
                .count();
        assertEquals(1, refsCount, "Should have 1 REFERENCES relationship (inReplyTo is excluded)");
    }

    @Test
    void extractsReferencesFromStringFormat() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("gmail.messageId", "msg003");
        meta.put("gmail.threadId", "thread001");
        meta.put("gmail.subject", "Re: Test");
        meta.put("gmail.from", "carol@example.com");
        meta.put("gmail.to", "alice@example.com");
        meta.put("gmail.references", "<ref1@example.com> <ref2@example.com>");
        Document doc = gmailDoc(meta);

        ExtractionResult result = extractor.extract(doc);

        long refsCount = result.relations().stream()
                .filter(r -> "REFERENCES".equals(r.type()))
                .count();
        assertEquals(2, refsCount, "Should have 2 REFERENCES relationships from space-separated string");
    }

    // ── extract() — mailing list ────────────────────────────────────────

    @Test
    void extractsMailingListEntity() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("gmail.messageId", "msg001");
        meta.put("gmail.threadId", "thread001");
        meta.put("gmail.subject", "Test");
        meta.put("gmail.from", "alice@example.com");
        meta.put("gmail.to", "list@example.com");
        meta.put("gmail.listId", "dev-team.example.com");
        Document doc = gmailDoc(meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity listEntity = findEntityByType(result.entities(), "MAILING_LIST");
        assertNotNull(listEntity, "Should have a MAILING_LIST entity");
        assertEquals("dev-team.example.com", listEntity.name());

        assertTrue(result.relations().stream().anyMatch(r -> "POSTED_TO".equals(r.type())),
                "Should have POSTED_TO relationship");
    }

    // ── extract() — labels ──────────────────────────────────────────────

    @Test
    void extractsLabelEntitiesAndRelations() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("gmail.messageId", "msg001");
        meta.put("gmail.threadId", "thread001");
        meta.put("gmail.subject", "Test");
        meta.put("gmail.from", "alice@example.com");
        meta.put("gmail.to", "bob@example.com");
        meta.put("gmail.labels", List.of("INBOX", "IMPORTANT"));
        Document doc = gmailDoc(meta);

        ExtractionResult result = extractor.extract(doc);

        long labelCount = result.entities().stream()
                .filter(e -> "GMAIL_LABEL".equals(e.type()))
                .count();
        assertEquals(2, labelCount, "Should have 2 GMAIL_LABEL entities");

        long hasLabelCount = result.relations().stream()
                .filter(r -> "HAS_LABEL".equals(r.type()))
                .count();
        assertEquals(2, hasLabelCount, "Should have 2 HAS_LABEL relationships");
    }

    // ── extract() — attachments ─────────────────────────────────────────

    @Test
    void extractsAttachmentEntitiesFromMessageMetadata() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("gmail.messageId", "msg001");
        meta.put("gmail.threadId", "thread001");
        meta.put("gmail.subject", "Test");
        meta.put("gmail.from", "alice@example.com");
        meta.put("gmail.to", "bob@example.com");
        meta.put("gmail.attachmentCount", 1);
        meta.put("gmail.attachments", List.of(
                Map.of("filename", "doc.pdf", "mimeType", "application/pdf")
        ));
        Document doc = gmailDoc(meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity attEntity = findEntityByType(result.entities(), "GMAIL_ATTACHMENT");
        assertNotNull(attEntity, "Should have a GMAIL_ATTACHMENT entity");
        assertEquals("doc.pdf", attEntity.name());

        assertTrue(result.relations().stream().anyMatch(r -> "HAS_ATTACHMENT".equals(r.type())),
                "Should have HAS_ATTACHMENT relationship");
    }

    @Test
    void extractsAttachmentDocumentGraph() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source_type", "gmail_attachment");
        meta.put("gmail.messageId", "msg001");
        meta.put("gmail.attachment.filename", "notes.txt");
        meta.put("gmail.attachment.mimeType", "text/plain");

        Document doc = new Document("attachment content");
        doc.getMetadata().putAll(meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity attEntity = findEntityByType(result.entities(), "GMAIL_ATTACHMENT");
        assertNotNull(attEntity);
        assertEquals("notes.txt", attEntity.name());

        assertTrue(result.relations().stream().anyMatch(r -> "HAS_ATTACHMENT".equals(r.type())));
    }

    // ── extract() — non-Gmail documents ─────────────────────────────────

    @Test
    void ignoresNonGmailDocuments() {
        Document doc = new Document("some content");
        doc.getMetadata().put("source_type", "web");

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().isEmpty());
        assertTrue(result.relations().isEmpty());
    }

    @Test
    void ignoresDocumentsWithNoSourceType() {
        Document doc = new Document("some content");

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().isEmpty());
        assertTrue(result.relations().isEmpty());
    }

    @Test
    void ignoresMessageWithNoMessageId() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source_type", "gmail");
        meta.put("gmail.from", "alice@example.com");
        Document doc = new Document("body");
        doc.getMetadata().putAll(meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().isEmpty());
    }

    // ── extractBatch() ──────────────────────────────────────────────────

    @Test
    void batchDeduplicatesPersonsByEmail() {
        Document doc1 = gmailDoc(Map.of(
                "gmail.messageId", "msg001",
                "gmail.threadId", "thread001",
                "gmail.subject", "First",
                "gmail.from", "Alice Smith <alice@example.com>",
                "gmail.to", "bob@example.com"
        ));
        Document doc2 = gmailDoc(Map.of(
                "gmail.messageId", "msg002",
                "gmail.threadId", "thread001",
                "gmail.subject", "Second",
                "gmail.from", "alice@example.com",
                "gmail.to", "bob@example.com"
        ));

        ExtractionResult result = extractor.extractBatch(List.of(doc1, doc2));

        long aliceCount = result.entities().stream()
                .filter(e -> "PERSON".equals(e.type()))
                .filter(e -> e.properties() != null && "alice@example.com".equals(e.properties().get("email")))
                .count();
        assertEquals(1, aliceCount, "Alice should be deduplicated to a single entity");
    }

    @Test
    void batchDeduplicatesRelationships() {
        Document doc1 = gmailDoc(Map.of(
                "gmail.messageId", "msg001",
                "gmail.threadId", "thread001",
                "gmail.subject", "First",
                "gmail.from", "alice@example.com",
                "gmail.to", "bob@example.com"
        ));

        ExtractionResult result = extractor.extractBatch(List.of(doc1, doc1));

        long sentByCount = result.relations().stream()
                .filter(r -> "SENT_BY".equals(r.type()))
                .count();
        assertEquals(1, sentByCount, "Duplicate relationships should be deduplicated");
    }

    // ── parseAddressList() ──────────────────────────────────────────────

    @Test
    void parseAddressListSplitsComma() {
        List<String> result = GmailGraphExtractor.parseAddressList(
                "alice@example.com, bob@example.com");
        assertEquals(2, result.size());
        assertEquals("alice@example.com", result.get(0));
        assertEquals("bob@example.com", result.get(1));
    }

    @Test
    void parseAddressListHandlesNameAngleBrackets() {
        List<String> result = GmailGraphExtractor.parseAddressList(
                "Alice Smith <alice@example.com>, Bob <bob@example.com>");
        assertEquals(2, result.size());
        assertTrue(result.get(0).contains("alice@example.com"));
        assertTrue(result.get(1).contains("bob@example.com"));
    }

    @Test
    void parseAddressListHandlesQuotedComma() {
        List<String> result = GmailGraphExtractor.parseAddressList(
                "\"Smith, Alice\" <alice@example.com>, bob@example.com");
        assertEquals(2, result.size());
        assertTrue(result.get(0).contains("alice@example.com"));
        assertEquals("bob@example.com", result.get(1));
    }

    @Test
    void parseAddressListReturnsEmptyForNull() {
        assertTrue(GmailGraphExtractor.parseAddressList(null).isEmpty());
    }

    @Test
    void parseAddressListReturnsEmptyForBlank() {
        assertTrue(GmailGraphExtractor.parseAddressList("  ").isEmpty());
    }

    @Test
    void parseAddressListHandlesSingleAddress() {
        List<String> result = GmailGraphExtractor.parseAddressList("alice@example.com");
        assertEquals(1, result.size());
        assertEquals("alice@example.com", result.get(0));
    }

    // ── extractEmail() ──────────────────────────────────────────────────

    @Test
    void extractEmailFromAngleBrackets() {
        assertEquals("alice@example.com",
                GmailGraphExtractor.extractEmail("Alice Smith <alice@example.com>"));
    }

    @Test
    void extractEmailFromBareAddress() {
        assertEquals("alice@example.com",
                GmailGraphExtractor.extractEmail("alice@example.com"));
    }

    @Test
    void extractEmailReturnsNullForNull() {
        assertNull(GmailGraphExtractor.extractEmail(null));
    }

    @Test
    void extractEmailReturnsNullForGarbage() {
        assertNull(GmailGraphExtractor.extractEmail("not an email"));
    }

    // ── extractName() ───────────────────────────────────────────────────

    @Test
    void extractNameFromNameAngleBracketFormat() {
        assertEquals("Alice Smith",
                GmailGraphExtractor.extractName("Alice Smith <alice@example.com>"));
    }

    @Test
    void extractNameFromQuotedNameFormat() {
        assertEquals("Smith, Alice",
                GmailGraphExtractor.extractName("\"Smith, Alice\" <alice@example.com>"));
    }

    @Test
    void extractNameReturnsNullForBareEmail() {
        assertNull(GmailGraphExtractor.extractName("alice@example.com"));
    }

    @Test
    void extractNameReturnsNullForNull() {
        assertNull(GmailGraphExtractor.extractName(null));
    }

    // ── ICS Calendar Event extraction ─────────────────────────────────

    @Test
    void icsAttachmentByMimeTypeCreatesCalendarEventEntity() {
        Document doc = gmailDoc(Map.of(
                "gmail.messageId", "msg-ics-1",
                "gmail.subject", "Meeting Invite",
                "gmail.attachmentCount", 1,
                "gmail.attachments", List.of(
                        Map.of("filename", "invite.ics", "mimeType", "text/calendar")
                )
        ));

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e -> "CALENDAR_EVENT".equals(e.type())),
                "ICS attachment should create CALENDAR_EVENT entity");
        assertTrue(result.relations().stream().anyMatch(r -> "HAS_CALENDAR_EVENT".equals(r.type())),
                "Should have HAS_CALENDAR_EVENT relation");
        // Should NOT create a GMAIL_ATTACHMENT for .ics
        assertFalse(result.entities().stream().anyMatch(e ->
                        "GMAIL_ATTACHMENT".equals(e.type()) && "invite.ics".equals(e.name())),
                "ICS should NOT produce a GMAIL_ATTACHMENT entity");
    }

    @Test
    void icsAttachmentByFilenameCreatesCalendarEventEntity() {
        Document doc = gmailDoc(Map.of(
                "gmail.messageId", "msg-ics-2",
                "gmail.subject", "Event",
                "gmail.attachmentCount", 1,
                "gmail.attachments", List.of(
                        Map.of("filename", "meeting.ics", "mimeType", "application/octet-stream")
                )
        ));

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e -> "CALENDAR_EVENT".equals(e.type())),
                "ICS filename extension should trigger CALENDAR_EVENT even with generic mimeType");
    }

    @Test
    void nonIcsAttachmentRemainsGmailAttachment() {
        Document doc = gmailDoc(Map.of(
                "gmail.messageId", "msg-ics-3",
                "gmail.subject", "Report",
                "gmail.attachmentCount", 1,
                "gmail.attachments", List.of(
                        Map.of("filename", "report.pdf", "mimeType", "application/pdf")
                )
        ));

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e -> "GMAIL_ATTACHMENT".equals(e.type())),
                "Non-ICS attachment should still be GMAIL_ATTACHMENT");
        assertFalse(result.entities().stream().anyMatch(e -> "CALENDAR_EVENT".equals(e.type())),
                "PDF attachment should not create CALENDAR_EVENT");
    }

    @Test
    void standaloneIcsAttachmentDocCreatesCalendarEvent() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source_type", "gmail_attachment");
        meta.put("gmail.messageId", "msg-ics-4");
        meta.put("gmail.attachment.filename", "invite.ics");
        meta.put("gmail.attachment.mimeType", "text/calendar");
        meta.put("gmail.attachment.size", 2048);
        Document doc = new Document("calendar content");
        doc.getMetadata().putAll(meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e -> "CALENDAR_EVENT".equals(e.type())),
                "Standalone ICS attachment should produce CALENDAR_EVENT");
        ExtractedEntity cal = result.entities().stream()
                .filter(e -> "CALENDAR_EVENT".equals(e.type())).findFirst().orElseThrow();
        assertEquals("2048", cal.properties().get("size"));
    }

    @Test
    void standaloneNonIcsAttachmentRemainsGmailAttachment() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source_type", "gmail_attachment");
        meta.put("gmail.messageId", "msg-ics-5");
        meta.put("gmail.attachment.filename", "document.pdf");
        meta.put("gmail.attachment.mimeType", "application/pdf");
        Document doc = new Document("pdf content");
        doc.getMetadata().putAll(meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e -> "GMAIL_ATTACHMENT".equals(e.type())));
        assertFalse(result.entities().stream().anyMatch(e -> "CALENDAR_EVENT".equals(e.type())));
    }

    // ── ICS attachment with full field parsing ───────────────────────────

    @Test
    void icsAttachmentDocParsesFullIcsContent() {
        String icsContent = "BEGIN:VCALENDAR\n" +
                "BEGIN:VEVENT\n" +
                "SUMMARY:Sprint Planning\n" +
                "DTSTART:20250601T140000Z\n" +
                "DTEND:20250601T150000Z\n" +
                "LOCATION:Main Office Room 4A\n" +
                "ORGANIZER:MAILTO:pm@company.com\n" +
                "ATTENDEE:MAILTO:dev1@company.com\n" +
                "ATTENDEE:MAILTO:dev2@company.com\n" +
                "END:VEVENT\n" +
                "END:VCALENDAR";

        Map<String, Object> meta = new HashMap<>();
        meta.put("source_type", "gmail_attachment");
        meta.put("gmail.messageId", "msg-ics-123");
        meta.put("gmail.attachment.filename", "meeting.ics");
        meta.put("gmail.attachment.mimeType", "text/calendar");
        Document doc = new Document(icsContent);
        doc.getMetadata().putAll(meta);

        ExtractionResult result = extractor.extract(doc);

        // CALENDAR_EVENT entity with title = summary
        ExtractedEntity calEvent = findEntityByType(result.entities(), "CALENDAR_EVENT");
        assertNotNull(calEvent, "Should have a CALENDAR_EVENT entity");
        assertEquals("Sprint Planning", calEvent.name(), "CALENDAR_EVENT title should equal ICS SUMMARY");

        // CALENDAR_EVENT properties
        Map<String, String> calProps = calEvent.properties();
        assertNotNull(calProps);
        assertEquals("Sprint Planning", calProps.get("summary"));
        assertNotNull(calProps.get("dtstart"), "Should have dtstart property");
        assertNotNull(calProps.get("dtend"), "Should have dtend property");
        assertEquals("Main Office Room 4A", calProps.get("location"));
        assertEquals("pm@company.com", calProps.get("organizer"));

        // PERSON entity for organizer + ORGANIZED_BY relation
        String orgPersonId = UUID.nameUUIDFromBytes(("person:pm@company.com").getBytes()).toString();
        ExtractedEntity organizer = result.entities().stream()
                .filter(e -> orgPersonId.equals(e.id()))
                .findFirst().orElse(null);
        assertNotNull(organizer, "Should have a PERSON entity for organizer pm@company.com");

        String calEventId = UUID.nameUUIDFromBytes(("gmail_calendar:msg-ics-123/meeting.ics").getBytes()).toString();
        assertTrue(result.relations().stream().anyMatch(r ->
                        "ORGANIZED_BY".equals(r.type())
                                && calEventId.equals(r.source())
                                && orgPersonId.equals(r.target())),
                "Should have ORGANIZED_BY relation from calendar event to organizer");

        // PERSON entities for attendees + ATTENDED_BY relations
        String dev1PersonId = UUID.nameUUIDFromBytes(("person:dev1@company.com").getBytes()).toString();
        String dev2PersonId = UUID.nameUUIDFromBytes(("person:dev2@company.com").getBytes()).toString();

        assertTrue(result.entities().stream().anyMatch(e -> dev1PersonId.equals(e.id())),
                "Should have a PERSON entity for dev1@company.com");
        assertTrue(result.entities().stream().anyMatch(e -> dev2PersonId.equals(e.id())),
                "Should have a PERSON entity for dev2@company.com");

        assertTrue(result.relations().stream().anyMatch(r ->
                        "ATTENDED_BY".equals(r.type())
                                && calEventId.equals(r.source())
                                && dev1PersonId.equals(r.target())),
                "Should have ATTENDED_BY relation from calendar event to dev1");
        assertTrue(result.relations().stream().anyMatch(r ->
                        "ATTENDED_BY".equals(r.type())
                                && calEventId.equals(r.source())
                                && dev2PersonId.equals(r.target())),
                "Should have ATTENDED_BY relation from calendar event to dev2");

        // LOCATION entity + AT_LOCATION relation
        String locId = UUID.nameUUIDFromBytes(("location:main office room 4a").getBytes()).toString();
        assertTrue(result.entities().stream().anyMatch(e ->
                        "LOCATION".equals(e.type()) && "Main Office Room 4A".equals(e.name())),
                "Should have a LOCATION entity for 'Main Office Room 4A'");
        assertTrue(result.relations().stream().anyMatch(r ->
                        "AT_LOCATION".equals(r.type())
                                && calEventId.equals(r.source())
                                && locId.equals(r.target())),
                "Should have AT_LOCATION relation from calendar event to location");
    }

    // ── ICS attachment without location ──────────────────────────────────

    @Test
    void icsAttachmentDocWithoutLocationCreatesNoLocationEntity() {
        String icsContent = "BEGIN:VCALENDAR\n" +
                "BEGIN:VEVENT\n" +
                "SUMMARY:Stand-up\n" +
                "DTSTART:20250602T090000Z\n" +
                "DTEND:20250602T091500Z\n" +
                "ORGANIZER:MAILTO:lead@company.com\n" +
                "ATTENDEE:MAILTO:eng@company.com\n" +
                "END:VEVENT\n" +
                "END:VCALENDAR";

        Map<String, Object> meta = new HashMap<>();
        meta.put("source_type", "gmail_attachment");
        meta.put("gmail.messageId", "msg-ics-456");
        meta.put("gmail.attachment.filename", "standup.ics");
        meta.put("gmail.attachment.mimeType", "text/calendar");
        Document doc = new Document(icsContent);
        doc.getMetadata().putAll(meta);

        ExtractionResult result = extractor.extract(doc);

        // CALENDAR_EVENT created with summary
        ExtractedEntity calEvent = findEntityByType(result.entities(), "CALENDAR_EVENT");
        assertNotNull(calEvent, "Should have a CALENDAR_EVENT entity");
        assertEquals("Stand-up", calEvent.name());

        // No LOCATION entity
        assertFalse(result.entities().stream().anyMatch(e -> "LOCATION".equals(e.type())),
                "Should NOT have a LOCATION entity when ICS has no LOCATION field");
        assertFalse(result.relations().stream().anyMatch(r -> "AT_LOCATION".equals(r.type())),
                "Should NOT have AT_LOCATION relation when ICS has no LOCATION field");

        // Organizer PERSON still created
        String orgPersonId = UUID.nameUUIDFromBytes(("person:lead@company.com").getBytes()).toString();
        assertTrue(result.entities().stream().anyMatch(e -> orgPersonId.equals(e.id())),
                "Should still have PERSON entity for organizer");
        assertTrue(result.relations().stream().anyMatch(r -> "ORGANIZED_BY".equals(r.type())),
                "Should still have ORGANIZED_BY relation");

        // Attendee PERSON still created
        String attPersonId = UUID.nameUUIDFromBytes(("person:eng@company.com").getBytes()).toString();
        assertTrue(result.entities().stream().anyMatch(e -> attPersonId.equals(e.id())),
                "Should still have PERSON entity for attendee");
        assertTrue(result.relations().stream().anyMatch(r -> "ATTENDED_BY".equals(r.type())),
                "Should still have ATTENDED_BY relation");
    }

    // ── extractIcsFields unit test ────────────────────────────────────────

    @Test
    void extractIcsFieldsParsesAllSupportedFields() {
        String icsContent = "BEGIN:VCALENDAR\n" +
                "BEGIN:VEVENT\n" +
                "SUMMARY:Q2 Review\n" +
                "DTSTART:20250701T100000Z\n" +
                "DTEND:20250701T110000Z\n" +
                "LOCATION:Conference Room B\n" +
                "ORGANIZER:MAILTO:cto@example.com\n" +
                "ATTENDEE:MAILTO:alice@example.com\n" +
                "ATTENDEE:MAILTO:bob@example.com\n" +
                "END:VEVENT\n" +
                "END:VCALENDAR";

        Map<String, String> props = new LinkedHashMap<>();
        GmailGraphExtractor.extractIcsFields(icsContent, props);

        assertEquals("Q2 Review", props.get("summary"));
        assertEquals("20250701T100000Z", props.get("dtstart"));
        assertEquals("20250701T110000Z", props.get("dtend"));
        assertEquals("Conference Room B", props.get("location"));
        assertEquals("cto@example.com", props.get("organizer"));

        // Attendees should be a comma-joined string
        String attendees = props.get("attendees");
        assertNotNull(attendees, "Should have attendees entry");
        assertTrue(attendees.contains("alice@example.com"), "Attendees should include alice@example.com");
        assertTrue(attendees.contains("bob@example.com"), "Attendees should include bob@example.com");
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private Document gmailDoc(Map<String, Object> metadata) {
        Map<String, Object> fullMeta = new HashMap<>(metadata);
        fullMeta.putIfAbsent("source_type", "gmail");
        Document doc = new Document("email body");
        doc.getMetadata().putAll(fullMeta);
        return doc;
    }

    private ExtractedEntity findEntityByType(List<ExtractedEntity> entities, String type) {
        return entities.stream()
                .filter(e -> type.equals(e.type()))
                .findFirst().orElse(null);
    }
}
