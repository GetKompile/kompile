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

package ai.kompile.loader.gworkspace;

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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link GWorkspaceGraphExtractor}, focusing on:
 * <ol>
 *   <li>Entity type differentiation from Google Drive MIME types</li>
 *   <li>IN_FOLDER relationship generation from parent folder metadata</li>
 *   <li>canExtract routing for all supported services</li>
 * </ol>
 */
class GWorkspaceGraphExtractorTest {

    private GWorkspaceGraphExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new GWorkspaceGraphExtractor();
    }

    // ── resolveEntityType ────────────────────────────────────────────────

    @Test
    void resolveEntityType_spreadsheet() {
        assertEquals("SPREADSHEET",
                GWorkspaceGraphExtractor.resolveEntityType("application/vnd.google-apps.spreadsheet"));
    }

    @Test
    void resolveEntityType_presentation() {
        assertEquals("PRESENTATION",
                GWorkspaceGraphExtractor.resolveEntityType("application/vnd.google-apps.presentation"));
    }

    @Test
    void resolveEntityType_document() {
        assertEquals("DOCUMENT",
                GWorkspaceGraphExtractor.resolveEntityType("application/vnd.google-apps.document"));
    }

    @Test
    void resolveEntityType_form() {
        assertEquals("GOOGLE_FORM",
                GWorkspaceGraphExtractor.resolveEntityType("application/vnd.google-apps.form"));
    }

    @Test
    void resolveEntityType_drawing() {
        assertEquals("GOOGLE_DRAWING",
                GWorkspaceGraphExtractor.resolveEntityType("application/vnd.google-apps.drawing"));
    }

    @Test
    void resolveEntityType_folder() {
        assertEquals("DRIVE_FOLDER",
                GWorkspaceGraphExtractor.resolveEntityType("application/vnd.google-apps.folder"));
    }

    @Test
    void resolveEntityType_binaryFile_fallsBackToDriveFile() {
        assertEquals("DRIVE_FILE",
                GWorkspaceGraphExtractor.resolveEntityType("application/pdf"));
    }

    @Test
    void resolveEntityType_null_fallsBackToDriveFile() {
        assertEquals("DRIVE_FILE",
                GWorkspaceGraphExtractor.resolveEntityType(null));
    }

    @Test
    void resolveEntityType_unknownGoogleApps_fallsBackToDriveFile() {
        assertEquals("DRIVE_FILE",
                GWorkspaceGraphExtractor.resolveEntityType("application/vnd.google-apps.unknown-type"));
    }

    // ── extractDrive — entity type from mimeType ─────────────────────────

    @Test
    void extractDrive_spreadsheet_producesSpreadsheetEntity() {
        Document doc = driveDoc("fileSheet1", "Budget.sheets",
                "application/vnd.google-apps.spreadsheet", null, null);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity entity = findEntityByType(result.entities(), "SPREADSHEET");
        assertNotNull(entity, "Expected a SPREADSHEET entity");
        assertEquals("Budget.sheets", entity.name());
    }

    @Test
    void extractDrive_presentation_producesPresentationEntity() {
        Document doc = driveDoc("fileSlides1", "Deck.slides",
                "application/vnd.google-apps.presentation", null, null);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity entity = findEntityByType(result.entities(), "PRESENTATION");
        assertNotNull(entity, "Expected a PRESENTATION entity");
        assertEquals("Deck.slides", entity.name());
    }

    @Test
    void extractDrive_document_producesDocumentEntity() {
        Document doc = driveDoc("fileDoc1", "Report.gdoc",
                "application/vnd.google-apps.document", null, null);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity entity = findEntityByType(result.entities(), "DOCUMENT");
        assertNotNull(entity, "Expected a DOCUMENT entity");
        assertEquals("Report.gdoc", entity.name());
    }

    @Test
    void extractDrive_form_producesGoogleFormEntity() {
        Document doc = driveDoc("fileForm1", "Survey.form",
                "application/vnd.google-apps.form", null, null);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity entity = findEntityByType(result.entities(), "GOOGLE_FORM");
        assertNotNull(entity, "Expected a GOOGLE_FORM entity");
    }

    @Test
    void extractDrive_drawing_producesGoogleDrawingEntity() {
        Document doc = driveDoc("fileDraw1", "Diagram.drawing",
                "application/vnd.google-apps.drawing", null, null);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity entity = findEntityByType(result.entities(), "GOOGLE_DRAWING");
        assertNotNull(entity, "Expected a GOOGLE_DRAWING entity");
    }

    @Test
    void extractDrive_genericBinaryFile_producesDriveFileEntity() {
        Document doc = driveDoc("filePdf1", "Annual Report.pdf",
                "application/pdf", null, null);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity entity = findEntityByType(result.entities(), "DRIVE_FILE");
        assertNotNull(entity, "Expected a DRIVE_FILE entity for non-Google-Apps mime type");
    }

    @Test
    void extractDrive_nullMimeType_producesDriveFileEntity() {
        Document doc = driveDoc("fileUnknown1", "Unknown File", null, null, null);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity entity = findEntityByType(result.entities(), "DRIVE_FILE");
        assertNotNull(entity, "Expected a DRIVE_FILE entity when mimeType is absent");
    }

    @Test
    void extractDrive_folder_producesDriveFolderEntity() {
        Document doc = driveDoc("folder1", "My Folder",
                "application/vnd.google-apps.folder", null, null);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity entity = findEntityByType(result.entities(), "DRIVE_FOLDER");
        assertNotNull(entity, "Expected a DRIVE_FOLDER entity");
        assertEquals("My Folder", entity.name());
    }

    // ── extractDrive — IN_FOLDER relationship ────────────────────────────

    @Test
    void extractDrive_withParentFolderId_producesInFolderRelation() {
        Document doc = driveDoc("fileDoc1", "Report.gdoc",
                "application/vnd.google-apps.document", "parentFolder1", null);

        ExtractionResult result = extractor.extract(doc);

        ExtractedRelation inFolder = findRelationByType(result.relations(), "IN_FOLDER");
        assertNotNull(inFolder, "Expected an IN_FOLDER relationship");
    }

    @Test
    void extractDrive_withParentFolderId_folderEntityIsCreated() {
        Document doc = driveDoc("fileDoc1", "Report.gdoc",
                "application/vnd.google-apps.document", "parentFolder1", null);

        ExtractionResult result = extractor.extract(doc);

        // The folder entity should be present (as a DRIVE_FOLDER stub)
        boolean hasFolderEntity = result.entities().stream()
                .anyMatch(e -> "DRIVE_FOLDER".equals(e.type())
                        && "parentFolder1".equals(e.properties().get("fileId")));
        assertTrue(hasFolderEntity, "Expected a DRIVE_FOLDER entity for the parent folder");
    }

    @Test
    void extractDrive_withParentFolderIdAndName_folderEntityUsesName() {
        Document doc = driveDoc("fileDoc1", "Report.gdoc",
                "application/vnd.google-apps.document", "parentFolder1", "Projects");

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity folderEntity = result.entities().stream()
                .filter(e -> "DRIVE_FOLDER".equals(e.type()))
                .findFirst()
                .orElse(null);
        assertNotNull(folderEntity);
        assertEquals("Projects", folderEntity.name());
    }

    @Test
    void extractDrive_withParentFolderIdNoName_folderEntityUsesFallbackLabel() {
        Document doc = driveDoc("fileDoc1", "Report.gdoc",
                "application/vnd.google-apps.document", "parentFolder1", null);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity folderEntity = result.entities().stream()
                .filter(e -> "DRIVE_FOLDER".equals(e.type()))
                .findFirst()
                .orElse(null);
        assertNotNull(folderEntity);
        // Name should reference the folder ID when no name is provided
        assertTrue(folderEntity.name().contains("parentFolder1"),
                "Folder entity name should include the folder ID when no name is available");
    }

    @Test
    void extractDrive_withoutParentFolderId_noInFolderRelation() {
        Document doc = driveDoc("fileDoc1", "Report.gdoc",
                "application/vnd.google-apps.document", null, null);

        ExtractionResult result = extractor.extract(doc);

        boolean hasInFolder = result.relations().stream()
                .anyMatch(r -> "IN_FOLDER".equals(r.type()));
        assertFalse(hasInFolder, "Should not produce IN_FOLDER when no parent folder is set");
    }

    @Test
    void extractDrive_inFolderRelation_pointsFromFileToFolder() {
        Document doc = driveDoc("fileDoc1", "Report.gdoc",
                "application/vnd.google-apps.document", "parentFolder1", null);

        ExtractionResult result = extractor.extract(doc);

        ExtractedRelation inFolder = findRelationByType(result.relations(), "IN_FOLDER");
        assertNotNull(inFolder);

        // The file entity and folder entity should be linked
        ExtractedEntity fileEntity = findEntityByType(result.entities(), "DOCUMENT");
        ExtractedEntity folderEntity = findEntityByType(result.entities(), "DRIVE_FOLDER");
        assertNotNull(fileEntity);
        assertNotNull(folderEntity);

        assertEquals(fileEntity.id(), inFolder.source(),
                "IN_FOLDER source should be the file entity");
        assertEquals(folderEntity.id(), inFolder.target(),
                "IN_FOLDER target should be the folder entity");
    }

    // ── canExtract routing ───────────────────────────────────────────────

    @Test
    void canExtract_gworkspaceServiceDrive_returnsTrue() {
        Document doc = new Document("content");
        doc.getMetadata().put("gworkspace.service", "drive");
        assertTrue(extractor.canExtract(doc));
    }

    @Test
    void canExtract_sourceTypeGdrive_returnsTrue() {
        Document doc = new Document("content");
        doc.getMetadata().put("source_type", "GDRIVE");
        doc.getMetadata().put("gdrive_file_id", "someFileId");
        assertTrue(extractor.canExtract(doc));
    }

    @Test
    void canExtract_gworkspaceServiceGmail_returnsTrue() {
        Document doc = new Document("content");
        doc.getMetadata().put("gworkspace.service", "gmail");
        assertTrue(extractor.canExtract(doc));
    }

    @Test
    void canExtract_noRelevantMetadata_returnsFalse() {
        Document doc = new Document("content");
        assertFalse(extractor.canExtract(doc));
    }

    @Test
    void canExtract_nullDocument_returnsFalse() {
        assertFalse(extractor.canExtract(null));
    }

    // ── GDRIVE fallback path (source_type=GDRIVE without gworkspace.service) ──

    @Test
    void extract_gdriveSourceType_resolvesMimeTypeToSpreadsheet() {
        Document doc = new Document("Spreadsheet content");
        Map<String, Object> meta = doc.getMetadata();
        meta.put("source_type", "GDRIVE");
        meta.put("gdrive_file_id", "sheetABC");
        meta.put("gdrive_file_name", "Sales Data");
        meta.put("gdrive_mime_type", "application/vnd.google-apps.spreadsheet");

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity entity = findEntityByType(result.entities(), "SPREADSHEET");
        assertNotNull(entity, "GDRIVE fallback path should produce SPREADSHEET entity for spreadsheet mimeType");
    }

    @Test
    void extract_gdriveSourceType_withParentFolderId_producesInFolderRelation() {
        Document doc = new Document("content");
        Map<String, Object> meta = doc.getMetadata();
        meta.put("source_type", "GDRIVE");
        meta.put("gdrive_file_id", "docXYZ");
        meta.put("gdrive_file_name", "My Doc");
        meta.put("gdrive_mime_type", "application/vnd.google-apps.document");
        meta.put("gworkspace.drive.parentFolderId", "folderParent1");

        ExtractionResult result = extractor.extract(doc);

        ExtractedRelation inFolder = findRelationByType(result.relations(), "IN_FOLDER");
        assertNotNull(inFolder, "GDRIVE fallback path should produce IN_FOLDER when parentFolderId is set");
    }

    // ── SPREADSHEET_SHEET from Google Sheets per-sheet documents ───────

    @Test
    void extractDrive_spreadsheetWithSheetName_producesSheetEntity() {
        Document doc = driveDoc("sheetFile1", "Budget",
                "application/vnd.google-apps.spreadsheet", null, null);
        doc.getMetadata().put("sheetName", "Q1 Expenses");
        doc.getMetadata().put("sheetId", 12345);
        doc.getMetadata().put("sheetIndex", 0);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity sheet = findEntityByType(result.entities(), "SPREADSHEET_SHEET");
        assertNotNull(sheet, "Spreadsheet with sheetName should produce SPREADSHEET_SHEET entity");
        assertEquals("Q1 Expenses", sheet.name());
        assertEquals("Q1 Expenses", sheet.properties().get("sheetName"));
        assertEquals("12345", sheet.properties().get("sheetId"));
        assertEquals("0", sheet.properties().get("sheetIndex"));
    }

    @Test
    void extractDrive_spreadsheetWithSheetName_producesHasSheetRelation() {
        Document doc = driveDoc("sheetFile1", "Budget",
                "application/vnd.google-apps.spreadsheet", null, null);
        doc.getMetadata().put("sheetName", "Q1 Expenses");

        ExtractionResult result = extractor.extract(doc);

        ExtractedRelation hasSheet = findRelationByType(result.relations(), "HAS_SHEET");
        assertNotNull(hasSheet, "Expected HAS_SHEET relation from spreadsheet to sheet");
    }

    @Test
    void extractDrive_nonSpreadsheetWithSheetName_noSheetEntity() {
        // A document type file shouldn't produce a sheet even if sheetName is present
        Document doc = driveDoc("docFile1", "Report",
                "application/vnd.google-apps.document", null, null);
        doc.getMetadata().put("sheetName", "Sheet1");

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity sheet = findEntityByType(result.entities(), "SPREADSHEET_SHEET");
        assertNull(sheet, "Non-spreadsheet should not produce SPREADSHEET_SHEET entity");
    }

    @Test
    void extractDrive_spreadsheetWithoutSheetName_noSheetEntity() {
        Document doc = driveDoc("sheetFile2", "Budget",
                "application/vnd.google-apps.spreadsheet", null, null);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity sheet = findEntityByType(result.entities(), "SPREADSHEET_SHEET");
        assertNull(sheet, "Spreadsheet without sheetName should not produce SPREADSHEET_SHEET entity");
    }

    @Test
    void extractDrive_spreadsheetViaGdriveFallback_producesSheetEntity() {
        Document doc = new Document("Sheet content");
        Map<String, Object> meta = doc.getMetadata();
        meta.put("source_type", "GDRIVE");
        meta.put("gdrive_file_id", "sheetFallback1");
        meta.put("gdrive_file_name", "Sales");
        meta.put("gdrive_mime_type", "application/vnd.google-apps.spreadsheet");
        meta.put("sheetName", "Revenue");

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity sheet = findEntityByType(result.entities(), "SPREADSHEET_SHEET");
        assertNotNull(sheet, "GDRIVE fallback path should also produce SPREADSHEET_SHEET entity");
        assertEquals("Revenue", sheet.name());
    }

    // ── extractCalendar — LOCATION entity ──────────────────────────────

    @Test
    void extractCalendar_withLocation_producesLocationEntity() {
        Document doc = calendarDoc("evt1", "Team Standup", "Conference Room B",
                "alice@example.com", null);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity location = findEntityByType(result.entities(), "LOCATION");
        assertNotNull(location, "Expected a LOCATION entity when calendar event has a location");
        assertEquals("Conference Room B", location.name());
    }

    @Test
    void extractCalendar_withLocation_producesAtLocationRelation() {
        Document doc = calendarDoc("evt1", "Team Standup", "Conference Room B",
                "alice@example.com", null);

        ExtractionResult result = extractor.extract(doc);

        ExtractedRelation atLocation = findRelationByType(result.relations(), "AT_LOCATION");
        assertNotNull(atLocation, "Expected AT_LOCATION relationship from event to location");
    }

    @Test
    void extractCalendar_withoutLocation_noLocationEntity() {
        Document doc = calendarDoc("evt2", "Quick Chat", null,
                "bob@example.com", null);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity location = findEntityByType(result.entities(), "LOCATION");
        assertNull(location, "No LOCATION entity should be created when event has no location");
    }

    @Test
    void extractCalendar_blankLocation_noLocationEntity() {
        Document doc = calendarDoc("evt3", "No Room", "   ",
                "carol@example.com", null);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity location = findEntityByType(result.entities(), "LOCATION");
        assertNull(location, "Blank location should not create a LOCATION entity");
    }

    // ── extractBatch deduplication ───────────────────────────────────────

    @Test
    void extractBatch_deduplicatesSameFileAcrossDocuments() {
        // Two documents about the same Drive file (e.g. different sheets of same spreadsheet)
        Document doc1 = driveDoc("fileSheet1", "Budget",
                "application/vnd.google-apps.spreadsheet", "folder1", null);
        Document doc2 = driveDoc("fileSheet1", "Budget",
                "application/vnd.google-apps.spreadsheet", "folder1", null);

        ExtractionResult batch = extractor.extractBatch(List.of(doc1, doc2));

        long spreadsheetCount = batch.entities().stream()
                .filter(e -> "SPREADSHEET".equals(e.type()))
                .count();
        assertEquals(1, spreadsheetCount, "Same spreadsheet file should be deduplicated to one entity");

        long inFolderCount = batch.relations().stream()
                .filter(r -> "IN_FOLDER".equals(r.type()))
                .count();
        assertEquals(1, inFolderCount, "Duplicate IN_FOLDER relations should be deduplicated");
    }

    // ── extractGmail — internalDate property ─────────────────────────────

    @Test
    void extractGmail_internalDateStoredOnMessageEntity() {
        Document doc = gmailDoc("msg1", "thread1", "Test Subject",
                "alice@example.com", "bob@example.com", null);
        doc.getMetadata().put("gworkspace.gmail.internalDate", "1716800000000");

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity msg = findEntityByType(result.entities(), "GMAIL_MESSAGE");
        assertNotNull(msg);
        assertEquals("2024-05-27T08:53:20Z", msg.properties().get("internalDate"));
    }

    @Test
    void extractGmail_noInternalDate_propertyAbsent() {
        Document doc = gmailDoc("msg2", "thread2", "No Date",
                "alice@example.com", "bob@example.com", null);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity msg = findEntityByType(result.entities(), "GMAIL_MESSAGE");
        assertNotNull(msg);
        assertNull(msg.properties().get("internalDate"));
    }

    // ── extractGmail — references chain ───────────────────────────────────

    @Test
    void extractGmail_referencesListCreatesEntitiesAndRelations() {
        Document doc = gmailDoc("msg3", "thread3", "Re: Discussion",
                "alice@example.com", "bob@example.com", null);
        doc.getMetadata().put("gworkspace.gmail.references",
                List.of("<ref1@example.com>", "<ref2@example.com>"));

        ExtractionResult result = extractor.extract(doc);

        // Should have GMAIL_MESSAGE entities for each reference
        long gmailMsgCount = result.entities().stream()
                .filter(e -> "GMAIL_MESSAGE".equals(e.type()))
                .count();
        // 1 main message + 2 references = 3
        assertTrue(gmailMsgCount >= 3, "Expected at least 3 GMAIL_MESSAGE entities (main + 2 refs)");

        long refRelCount = result.relations().stream()
                .filter(r -> "REFERENCES".equals(r.type()))
                .count();
        assertEquals(2, refRelCount, "Expected 2 REFERENCES relations");
    }

    @Test
    void extractGmail_referencesStringParsedByWhitespace() {
        Document doc = gmailDoc("msg4", "thread4", "Re: Thread",
                "alice@example.com", "bob@example.com", null);
        doc.getMetadata().put("gworkspace.gmail.references",
                "<ancestor1@example.com> <ancestor2@example.com>");

        ExtractionResult result = extractor.extract(doc);

        long refRelCount = result.relations().stream()
                .filter(r -> "REFERENCES".equals(r.type()))
                .count();
        assertEquals(2, refRelCount, "String references should be split by whitespace");
    }

    @Test
    void extractGmail_referencesSkipsDuplicateInReplyTo() {
        Document doc = gmailDoc("msg5", "thread5", "Re: Reply",
                "alice@example.com", "bob@example.com", null);
        doc.getMetadata().put("gworkspace.gmail.inReplyTo", "<parent@example.com>");
        doc.getMetadata().put("gworkspace.gmail.references",
                List.of("<parent@example.com>", "<grandparent@example.com>"));

        ExtractionResult result = extractor.extract(doc);

        // inReplyTo creates REPLIED_TO; references should skip parent but include grandparent
        long repliedToCount = result.relations().stream()
                .filter(r -> "REPLIED_TO".equals(r.type()))
                .count();
        assertEquals(1, repliedToCount, "Should have 1 REPLIED_TO relation");

        long refRelCount = result.relations().stream()
                .filter(r -> "REFERENCES".equals(r.type()))
                .count();
        assertEquals(1, refRelCount, "Should have 1 REFERENCES relation (grandparent only, parent skipped)");
    }

    @Test
    void extractGmail_referencesEntityHasRfc822MessageIdProperty() {
        Document doc = gmailDoc("msg6", "thread6", "Re: Props",
                "alice@example.com", "bob@example.com", null);
        doc.getMetadata().put("gworkspace.gmail.references",
                List.of("<unique-ref@example.com>"));

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity refEntity = result.entities().stream()
                .filter(e -> "GMAIL_MESSAGE".equals(e.type())
                        && e.properties().containsKey("rfc822MessageId")
                        && "unique-ref@example.com".equals(e.properties().get("rfc822MessageId")))
                .findFirst().orElse(null);
        assertNotNull(refEntity, "Referenced message entity should have rfc822MessageId property");
    }

    // ── extractGmail — mailing list (listId) ─────────────────────────────

    @Test
    void extractGmail_listIdCreatesMailingListEntityAndRelation() {
        Document doc = gmailDoc("msg7", "thread7", "List Post",
                "alice@example.com", "list@example.com", null);
        doc.getMetadata().put("gworkspace.gmail.listId", "dev-team.example.com");

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity listEntity = findEntityByType(result.entities(), "MAILING_LIST");
        assertNotNull(listEntity, "Expected a MAILING_LIST entity");
        assertEquals("dev-team.example.com", listEntity.name());
        assertEquals("dev-team.example.com", listEntity.properties().get("listId"));

        ExtractedRelation postedTo = findRelationByType(result.relations(), "POSTED_TO");
        assertNotNull(postedTo, "Expected a POSTED_TO relation from message to mailing list");
    }

    @Test
    void extractGmail_noListId_noMailingListEntity() {
        Document doc = gmailDoc("msg8", "thread8", "Direct Email",
                "alice@example.com", "bob@example.com", null);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity listEntity = findEntityByType(result.entities(), "MAILING_LIST");
        assertNull(listEntity, "No MAILING_LIST entity should exist without listId");
    }

    @Test
    void extractGmail_blankListId_noMailingListEntity() {
        Document doc = gmailDoc("msg9", "thread9", "Blank List",
                "alice@example.com", "bob@example.com", null);
        doc.getMetadata().put("gworkspace.gmail.listId", "   ");

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity listEntity = findEntityByType(result.entities(), "MAILING_LIST");
        assertNull(listEntity, "Blank listId should not create a MAILING_LIST entity");
    }

    // ── extractDrive — description property ────────────────────────────────

    @Test
    void extractDrive_descriptionStoredOnFileEntity() {
        Document doc = driveDoc("fileDoc1", "Design Doc",
                "application/vnd.google-apps.document", null, null);
        doc.getMetadata().put("gworkspace.drive.description", "Architecture overview for Q3");

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity fileEntity = findEntityByType(result.entities(), "DOCUMENT");
        assertNotNull(fileEntity);
        assertEquals("Architecture overview for Q3", fileEntity.properties().get("description"));
    }

    @Test
    void extractDrive_noDescription_propertyAbsent() {
        Document doc = driveDoc("fileDoc2", "Plain Doc",
                "application/vnd.google-apps.document", null, null);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity fileEntity = findEntityByType(result.entities(), "DOCUMENT");
        assertNotNull(fileEntity);
        assertNull(fileEntity.properties().get("description"));
    }

    // ── extractCalendar — htmlLink property ──────────────────────────────

    @Test
    void extractCalendar_htmlLinkStoredOnEventEntity() {
        Document doc = calendarDoc("evt10", "Sprint Planning", null,
                "pm@example.com", null);
        doc.getMetadata().put("gworkspace.calendar.htmlLink",
                "https://calendar.google.com/event?eid=evt10");

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity event = findEntityByType(result.entities(), "CALENDAR_EVENT");
        assertNotNull(event);
        assertEquals("https://calendar.google.com/event?eid=evt10",
                event.properties().get("htmlLink"));
    }

    @Test
    void extractCalendar_noHtmlLink_propertyAbsent() {
        Document doc = calendarDoc("evt11", "Quick Sync", null,
                "dev@example.com", null);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity event = findEntityByType(result.entities(), "CALENDAR_EVENT");
        assertNotNull(event);
        assertNull(event.properties().get("htmlLink"));
    }

    // ── extractCalendar — recurringEventId → INSTANCE_OF ─────────────────

    @Test
    void extractCalendar_recurringEventIdCreatesInstanceOfRelation() {
        Document doc = calendarDoc("evt_instance_1", "Weekly Standup", null,
                "lead@example.com", null);
        doc.getMetadata().put("gworkspace.calendar.recurringEventId", "evt_series_abc");

        ExtractionResult result = extractor.extract(doc);

        ExtractedRelation instanceOf = findRelationByType(result.relations(), "INSTANCE_OF");
        assertNotNull(instanceOf, "Expected INSTANCE_OF relation linking instance to recurring series");

        // Parent series entity should be created as a stub
        boolean hasParentSeries = result.entities().stream()
                .anyMatch(e -> "CALENDAR_EVENT".equals(e.type())
                        && "true".equals(e.properties().get("isRecurringSeries")));
        assertTrue(hasParentSeries, "Expected parent recurring series entity");
    }

    @Test
    void extractCalendar_recurringEventIdSameAsEventId_noInstanceOfRelation() {
        Document doc = calendarDoc("evt_series_abc", "Weekly Standup", null,
                "lead@example.com", null);
        // Series itself has recurringEventId matching its own eventId
        doc.getMetadata().put("gworkspace.calendar.recurringEventId", "evt_series_abc");

        ExtractionResult result = extractor.extract(doc);

        ExtractedRelation instanceOf = findRelationByType(result.relations(), "INSTANCE_OF");
        assertNull(instanceOf, "Series event should not create INSTANCE_OF to itself");
    }

    @Test
    void extractCalendar_noRecurringEventId_noInstanceOfRelation() {
        Document doc = calendarDoc("evt12", "One-off Meeting", null,
                "alice@example.com", null);

        ExtractionResult result = extractor.extract(doc);

        ExtractedRelation instanceOf = findRelationByType(result.relations(), "INSTANCE_OF");
        assertNull(instanceOf, "Non-recurring event should have no INSTANCE_OF relation");
    }

    // ── extractGmail — attachment-only document (no messageId) ────────────

    @Test
    void extractGmail_attachmentDocWithParentMessageId_createsAttachmentEntity() {
        Document doc = new Document("Attachment content");
        Map<String, Object> meta = doc.getMetadata();
        meta.put("gworkspace.service", "gmail");
        meta.put("gworkspace.gmail.parentMessageId", "parentMsg1");
        meta.put("gworkspace.gmail.subject", "Invoice");
        meta.put("gworkspace.gmail.threadId", "thread99");
        meta.put("gworkspace.gmail.attachmentFilename", "invoice.pdf");
        meta.put("gworkspace.gmail.attachmentMimeType", "application/pdf");
        meta.put("gworkspace.gmail.attachmentId", "att123");

        ExtractionResult result = extractor.extract(doc);

        // Should create stub parent message
        ExtractedEntity parentMsg = findEntityByType(result.entities(), "GMAIL_MESSAGE");
        assertNotNull(parentMsg, "Expected stub parent GMAIL_MESSAGE entity");

        // Should create attachment entity
        ExtractedEntity attachment = findEntityByType(result.entities(), "GMAIL_ATTACHMENT");
        assertNotNull(attachment, "Expected GMAIL_ATTACHMENT entity for attachment-only doc");
        assertEquals("invoice.pdf", attachment.name());
        assertEquals("application/pdf", attachment.properties().get("mimeType"));

        // Should create HAS_ATTACHMENT relation
        ExtractedRelation hasAtt = findRelationByType(result.relations(), "HAS_ATTACHMENT");
        assertNotNull(hasAtt, "Expected HAS_ATTACHMENT relation from parent to attachment");

        // Should link parent to thread
        ExtractedRelation inThread = findRelationByType(result.relations(), "IN_THREAD");
        assertNotNull(inThread, "Expected IN_THREAD relation for parent message");
    }

    @Test
    void extractGmail_noMessageIdNoParentMessageId_returnsEmpty() {
        Document doc = new Document("Orphan content");
        Map<String, Object> meta = doc.getMetadata();
        meta.put("gworkspace.service", "gmail");
        // No messageId, no parentMessageId

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().isEmpty(),
                "Gmail doc without messageId or parentMessageId should produce no entities");
    }

    // ── Helper factories ─────────────────────────────────────────────────

    /**
     * Build a Drive document using the gworkspace.service=drive metadata namespace.
     *
     * @param fileId         Drive file ID
     * @param fileName       file display name
     * @param mimeType       Google Drive MIME type (may be null)
     * @param parentFolderId parent folder ID (may be null — no IN_FOLDER relationship)
     * @param parentFolderName optional human-readable name for the parent folder (may be null)
     */
    private Document driveDoc(String fileId, String fileName, String mimeType,
                               String parentFolderId, String parentFolderName) {
        Document doc = new Document("Drive file: " + fileName);
        Map<String, Object> meta = doc.getMetadata();
        meta.put("gworkspace.service", "drive");
        meta.put("gworkspace.drive.fileId", fileId);
        meta.put("gworkspace.drive.fileName", fileName);
        if (mimeType != null) meta.put("gworkspace.drive.mimeType", mimeType);
        if (parentFolderId != null) meta.put("gworkspace.drive.parentFolderId", parentFolderId);
        if (parentFolderName != null) meta.put("gworkspace.drive.parentFolderName", parentFolderName);
        return doc;
    }

    /**
     * Build a Calendar document using the gworkspace.service=calendar metadata namespace.
     */
    private Document calendarDoc(String eventId, String summary, String location,
                                  String organizerEmail, String calendarId) {
        Document doc = new Document("Calendar event: " + summary);
        Map<String, Object> meta = doc.getMetadata();
        meta.put("gworkspace.service", "calendar");
        meta.put("gworkspace.calendar.eventId", eventId);
        if (summary != null) meta.put("gworkspace.calendar.summary", summary);
        if (location != null) meta.put("gworkspace.calendar.location", location);
        if (organizerEmail != null) meta.put("gworkspace.calendar.organizerEmail", organizerEmail);
        if (calendarId != null) meta.put("gworkspace.calendar.calendarId", calendarId);
        return doc;
    }

    /**
     * Build a Gmail document using the gworkspace.service=gmail metadata namespace.
     */
    private Document gmailDoc(String messageId, String threadId, String subject,
                               String from, String to, String inReplyTo) {
        Document doc = new Document("Email body for " + subject);
        Map<String, Object> meta = doc.getMetadata();
        meta.put("gworkspace.service", "gmail");
        meta.put("gworkspace.gmail.messageId", messageId);
        if (threadId != null) meta.put("gworkspace.gmail.threadId", threadId);
        if (subject != null) meta.put("gworkspace.gmail.subject", subject);
        if (from != null) meta.put("gworkspace.gmail.from", from);
        if (to != null) meta.put("gworkspace.gmail.to", to);
        if (inReplyTo != null) meta.put("gworkspace.gmail.inReplyTo", inReplyTo);
        return doc;
    }

    // ── ICS Calendar Event detection in Gmail attachments ────────────

    @Test
    void gmailIcsAttachmentByMimeTypeCreatesCalendarEventEntity() {
        Document doc = new Document("email body");
        Map<String, Object> meta = doc.getMetadata();
        meta.put("gworkspace.service", "gmail");
        meta.put("gworkspace.gmail.messageId", "msg-ics-1");
        meta.put("gworkspace.gmail.subject", "Meeting Invite");
        meta.put("gworkspace.gmail.attachments", List.of(
                Map.of("filename", "invite.ics", "mimeType", "text/calendar", "attachmentId", "att1")
        ));

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e -> "CALENDAR_EVENT".equals(e.type())),
                "ICS attachment should create CALENDAR_EVENT entity");
        assertTrue(result.relations().stream().anyMatch(r -> "HAS_CALENDAR_EVENT".equals(r.type())),
                "Should have HAS_CALENDAR_EVENT relation");
        assertFalse(result.entities().stream().anyMatch(e ->
                        "GMAIL_ATTACHMENT".equals(e.type()) && "invite.ics".equals(e.name())),
                "ICS should NOT produce a GMAIL_ATTACHMENT entity");
    }

    @Test
    void gmailIcsAttachmentByFilenameCreatesCalendarEvent() {
        Document doc = new Document("email body");
        Map<String, Object> meta = doc.getMetadata();
        meta.put("gworkspace.service", "gmail");
        meta.put("gworkspace.gmail.messageId", "msg-ics-2");
        meta.put("gworkspace.gmail.attachments", List.of(
                Map.of("filename", "meeting.ics", "mimeType", "application/octet-stream", "attachmentId", "att2")
        ));

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e -> "CALENDAR_EVENT".equals(e.type())),
                "ICS filename extension should trigger CALENDAR_EVENT");
    }

    @Test
    void gmailNonIcsAttachmentRemainsGmailAttachment() {
        Document doc = new Document("email body");
        Map<String, Object> meta = doc.getMetadata();
        meta.put("gworkspace.service", "gmail");
        meta.put("gworkspace.gmail.messageId", "msg-ics-3");
        meta.put("gworkspace.gmail.attachments", List.of(
                Map.of("filename", "report.pdf", "mimeType", "application/pdf", "attachmentId", "att3")
        ));

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e -> "GMAIL_ATTACHMENT".equals(e.type())));
        assertFalse(result.entities().stream().anyMatch(e -> "CALENDAR_EVENT".equals(e.type())));
    }

    @Test
    void gmailStandaloneIcsAttachmentCreatesCalendarEvent() {
        Document doc = new Document("ICS content");
        Map<String, Object> meta = doc.getMetadata();
        meta.put("gworkspace.service", "gmail");
        meta.put("gworkspace.gmail.parentMessageId", "msg-ics-4");
        meta.put("gworkspace.gmail.threadId", "thread-ics-4");
        meta.put("gworkspace.gmail.attachmentFilename", "invite.ics");
        meta.put("gworkspace.gmail.attachmentMimeType", "text/calendar");
        meta.put("gworkspace.gmail.attachmentId", "att4");

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e -> "CALENDAR_EVENT".equals(e.type())),
                "Standalone ICS attachment should produce CALENDAR_EVENT");
        assertTrue(result.relations().stream().anyMatch(r -> "HAS_CALENDAR_EVENT".equals(r.type())));
    }

    // ── ICS content parsing in standalone attachment docs ─────────────────

    @Test
    void standaloneIcsWithBodyParsesVeventFields() {
        String icsBody = "BEGIN:VCALENDAR\r\n" +
                "BEGIN:VEVENT\r\n" +
                "SUMMARY:Sprint Planning\r\n" +
                "DTSTART:20260601T090000Z\r\n" +
                "DTEND:20260601T100000Z\r\n" +
                "LOCATION:Room 42\r\n" +
                "ORGANIZER;CN=Alice:MAILTO:alice@example.com\r\n" +
                "ATTENDEE;CN=Bob:MAILTO:bob@example.com\r\n" +
                "ATTENDEE;CN=Carol:MAILTO:carol@example.com\r\n" +
                "RRULE:FREQ=WEEKLY;BYDAY=MO\r\n" +
                "SEQUENCE:3\r\n" +
                "UID:unique-event-123\r\n" +
                "END:VEVENT\r\n" +
                "END:VCALENDAR";

        Document doc = new Document(icsBody);
        Map<String, Object> meta = doc.getMetadata();
        meta.put("gworkspace.service", "gmail");
        meta.put("gworkspace.gmail.parentMessageId", "msg-ics-parse");
        meta.put("gworkspace.gmail.attachmentFilename", "sprint.ics");
        meta.put("gworkspace.gmail.attachmentMimeType", "text/calendar");
        meta.put("gworkspace.gmail.attachmentId", "att-ics-parse");

        ExtractionResult result = extractor.extract(doc);

        // CALENDAR_EVENT entity should have parsed ICS fields
        ExtractedEntity calEvent = result.entities().stream()
                .filter(e -> "CALENDAR_EVENT".equals(e.type()))
                .findFirst().orElse(null);
        assertNotNull(calEvent, "Expected CALENDAR_EVENT entity");
        assertEquals("Sprint Planning", calEvent.name(), "Entity name should use ICS SUMMARY");
        assertEquals("20260601T090000Z", calEvent.properties().get("dtstart"));
        assertEquals("20260601T100000Z", calEvent.properties().get("dtend"));
        assertEquals("Room 42", calEvent.properties().get("location"));
        assertEquals("alice@example.com", calEvent.properties().get("organizer"));
        assertEquals("FREQ=WEEKLY;BYDAY=MO", calEvent.properties().get("rrule"));
        assertEquals("3", calEvent.properties().get("sequence"));
        assertEquals("unique-event-123", calEvent.properties().get("uid"));
    }

    @Test
    void standaloneIcsCreatesOrganizerPersonEntity() {
        String icsBody = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\n" +
                "SUMMARY:Meeting\r\nORGANIZER:MAILTO:org@example.com\r\n" +
                "END:VEVENT\r\nEND:VCALENDAR";

        Document doc = new Document(icsBody);
        Map<String, Object> meta = doc.getMetadata();
        meta.put("gworkspace.service", "gmail");
        meta.put("gworkspace.gmail.parentMessageId", "msg-org");
        meta.put("gworkspace.gmail.attachmentFilename", "invite.ics");
        meta.put("gworkspace.gmail.attachmentMimeType", "text/calendar");

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(
                        e -> "GOOGLE_PERSON".equals(e.type())
                                && "org@example.com".equals(e.properties().get("email"))),
                "Should create GOOGLE_PERSON entity for ICS organizer");
        assertTrue(result.relations().stream().anyMatch(r -> "ORGANIZED_BY".equals(r.type())),
                "Should create ORGANIZED_BY relation from calendar event to organizer");
    }

    @Test
    void standaloneIcsCreatesAttendeePersonEntities() {
        String icsBody = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\n" +
                "SUMMARY:Sync\r\n" +
                "ATTENDEE:MAILTO:a@example.com\r\n" +
                "ATTENDEE:MAILTO:b@example.com\r\n" +
                "END:VEVENT\r\nEND:VCALENDAR";

        Document doc = new Document(icsBody);
        Map<String, Object> meta = doc.getMetadata();
        meta.put("gworkspace.service", "gmail");
        meta.put("gworkspace.gmail.parentMessageId", "msg-att");
        meta.put("gworkspace.gmail.attachmentFilename", "sync.ics");
        meta.put("gworkspace.gmail.attachmentMimeType", "text/calendar");

        ExtractionResult result = extractor.extract(doc);

        long attendedByCount = result.relations().stream()
                .filter(r -> "ATTENDED_BY".equals(r.type())).count();
        assertEquals(2, attendedByCount, "Should have 2 ATTENDED_BY relations for 2 attendees");
    }

    @Test
    void standaloneIcsCreatesLocationEntity() {
        String icsBody = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\n" +
                "SUMMARY:Offsite\r\nLOCATION:Building 5\r\n" +
                "END:VEVENT\r\nEND:VCALENDAR";

        Document doc = new Document(icsBody);
        Map<String, Object> meta = doc.getMetadata();
        meta.put("gworkspace.service", "gmail");
        meta.put("gworkspace.gmail.parentMessageId", "msg-loc");
        meta.put("gworkspace.gmail.attachmentFilename", "offsite.ics");
        meta.put("gworkspace.gmail.attachmentMimeType", "text/calendar");

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(
                        e -> "LOCATION".equals(e.type()) && "Building 5".equals(e.name())),
                "Should create LOCATION entity from ICS LOCATION field");
        assertTrue(result.relations().stream().anyMatch(r -> "AT_LOCATION".equals(r.type())),
                "Should create AT_LOCATION relation");
    }

    @Test
    void standaloneIcsWithoutBodyStillCreatesCalendarEvent() {
        // Empty body — no ICS fields to parse, but entity should still be created
        Document doc = new Document("");
        Map<String, Object> meta = doc.getMetadata();
        meta.put("gworkspace.service", "gmail");
        meta.put("gworkspace.gmail.parentMessageId", "msg-empty");
        meta.put("gworkspace.gmail.attachmentFilename", "blank.ics");
        meta.put("gworkspace.gmail.attachmentMimeType", "text/calendar");
        meta.put("gworkspace.gmail.attachmentId", "att-empty");

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e -> "CALENDAR_EVENT".equals(e.type())),
                "Should still create CALENDAR_EVENT even with empty body");
        // Name should fall back to filename since no SUMMARY
        ExtractedEntity calEvent = result.entities().stream()
                .filter(e -> "CALENDAR_EVENT".equals(e.type()))
                .findFirst().orElse(null);
        assertNotNull(calEvent);
        assertEquals("blank.ics", calEvent.name(),
                "Without ICS SUMMARY, entity name should fall back to filename");
    }

    // ── extractIcsFields unit tests ─────────────────────────────────────

    @Test
    void extractIcsFieldsBasicParsing() {
        Map<String, String> props = new LinkedHashMap<>();
        String ics = "BEGIN:VEVENT\nSUMMARY:Test\nDTSTART:20260101\nDTEND:20260102\nEND:VEVENT";
        GWorkspaceGraphExtractor.extractIcsFields(ics, props);

        assertEquals("Test", props.get("summary"));
        assertEquals("20260101", props.get("dtstart"));
        assertEquals("20260102", props.get("dtend"));
    }

    @Test
    void extractIcsFieldsIgnoresLinesOutsideVevent() {
        Map<String, String> props = new LinkedHashMap<>();
        String ics = "SUMMARY:OutsideEvent\nBEGIN:VEVENT\nSUMMARY:Inside\nEND:VEVENT\nSUMMARY:AfterEvent";
        GWorkspaceGraphExtractor.extractIcsFields(ics, props);

        assertEquals("Inside", props.get("summary"));
    }

    @Test
    void extractIcsFieldsNullAndBlank() {
        Map<String, String> props = new LinkedHashMap<>();
        GWorkspaceGraphExtractor.extractIcsFields(null, props);
        assertTrue(props.isEmpty());
        GWorkspaceGraphExtractor.extractIcsFields("   ", props);
        assertTrue(props.isEmpty());
    }

    @Test
    void extractIcsFieldsStripsParametersFromKey() {
        Map<String, String> props = new LinkedHashMap<>();
        String ics = "BEGIN:VEVENT\nDTSTART;TZID=US/Eastern:20260601T090000\nEND:VEVENT";
        GWorkspaceGraphExtractor.extractIcsFields(ics, props);
        assertEquals("20260601T090000", props.get("dtstart"));
    }

    private ExtractedEntity findEntityByType(List<ExtractedEntity> entities, String type) {
        return entities.stream()
                .filter(e -> type.equals(e.type()))
                .findFirst()
                .orElse(null);
    }

    private ExtractedRelation findRelationByType(List<ExtractedRelation> relations, String type) {
        return relations.stream()
                .filter(r -> type.equals(r.type()))
                .findFirst()
                .orElse(null);
    }
}
