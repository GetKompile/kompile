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

package ai.kompile.loader.onedrive;

import ai.kompile.core.graphrag.format.GraphExtractionSchema.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class OneDriveGraphExtractorTest {

    private OneDriveGraphExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new OneDriveGraphExtractor();
    }

    // --- canExtract ---

    @Test
    void canExtractReturnsTrueForSourceTypeOneDrive() {
        Document doc = new Document("content", Map.of("source_type", "ONEDRIVE"));
        assertTrue(extractor.canExtract(doc));
    }

    @Test
    void canExtractReturnsTrueForOneDriveItemId() {
        Document doc = new Document("content", Map.of("onedrive_item_id", "abc123"));
        assertTrue(extractor.canExtract(doc));
    }

    @Test
    void canExtractReturnsFalseForUnrelatedDoc() {
        Document doc = new Document("content", Map.of("loader", "tika-loader"));
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

    // --- Entity type resolution by mimeType ---

    @Test
    void extractResolvesSpreadsheetFromMimeType() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source_type", "ONEDRIVE");
        meta.put("onedrive_item_id", "item1");
        meta.put("onedrive_name", "Budget.xlsx");
        meta.put("onedrive_mime_type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);
        ExtractedEntity fileEntity = result.entities().get(0);
        assertEquals("ONEDRIVE_SPREADSHEET", fileEntity.type());
    }

    @Test
    void extractResolvesPresentationFromMimeType() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source_type", "ONEDRIVE");
        meta.put("onedrive_item_id", "item2");
        meta.put("onedrive_name", "Slides.pptx");
        meta.put("onedrive_mime_type", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);
        assertEquals("ONEDRIVE_PRESENTATION", result.entities().get(0).type());
    }

    @Test
    void extractResolvesDocumentFromMimeType() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source_type", "ONEDRIVE");
        meta.put("onedrive_item_id", "item3");
        meta.put("onedrive_name", "Report.docx");
        meta.put("onedrive_mime_type", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);
        assertEquals("ONEDRIVE_DOCUMENT", result.entities().get(0).type());
    }

    @Test
    void extractResolvesPdfFromMimeType() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source_type", "ONEDRIVE");
        meta.put("onedrive_item_id", "item4");
        meta.put("onedrive_name", "paper.pdf");
        meta.put("onedrive_mime_type", "application/pdf");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);
        assertEquals("ONEDRIVE_PDF", result.entities().get(0).type());
    }

    @Test
    void extractResolvesImageFromMimeType() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source_type", "ONEDRIVE");
        meta.put("onedrive_item_id", "item5");
        meta.put("onedrive_name", "photo.jpg");
        meta.put("onedrive_mime_type", "image/jpeg");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);
        assertEquals("ONEDRIVE_IMAGE", result.entities().get(0).type());
    }

    @Test
    void extractResolvesVideoFromMimeType() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source_type", "ONEDRIVE");
        meta.put("onedrive_item_id", "item6");
        meta.put("onedrive_name", "video.mp4");
        meta.put("onedrive_mime_type", "video/mp4");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);
        assertEquals("ONEDRIVE_VIDEO", result.entities().get(0).type());
    }

    @Test
    void extractFallsBackToExtensionWhenNoMimeType() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source_type", "ONEDRIVE");
        meta.put("onedrive_item_id", "item7");
        meta.put("onedrive_name", "data.xlsx");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);
        assertEquals("ONEDRIVE_SPREADSHEET", result.entities().get(0).type());
    }

    @Test
    void extractFallsBackToGenericFileForUnknownType() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source_type", "ONEDRIVE");
        meta.put("onedrive_item_id", "item8");
        meta.put("onedrive_name", "archive.zip");
        meta.put("onedrive_mime_type", "application/zip");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);
        assertEquals("ONEDRIVE_FILE", result.entities().get(0).type());
    }

    // --- Relationships ---

    @Test
    void extractCreatesCreatedByRelation() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source_type", "ONEDRIVE");
        meta.put("onedrive_item_id", "item1");
        meta.put("onedrive_name", "doc.docx");
        meta.put("onedrive.createdBy", "Alice Smith");
        meta.put("onedrive.createdByEmail", "alice@example.com");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e -> e.type().equals("PERSON") && e.name().equals("Alice Smith")));
        assertTrue(result.relations().stream().anyMatch(r -> r.type().equals("CREATED_BY")));
    }

    @Test
    void extractCreatesLastModifiedByRelation() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source_type", "ONEDRIVE");
        meta.put("onedrive_item_id", "item1");
        meta.put("onedrive_name", "doc.docx");
        meta.put("onedrive.lastModifiedBy", "Bob Jones");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e -> e.type().equals("PERSON") && e.name().equals("Bob Jones")));
        assertTrue(result.relations().stream().anyMatch(r -> r.type().equals("LAST_MODIFIED_BY")));
    }

    @Test
    void extractCreatesContainedInFolderRelation() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source_type", "ONEDRIVE");
        meta.put("onedrive_item_id", "item1");
        meta.put("onedrive_name", "doc.docx");
        meta.put("onedrive.parentPath", "/drive/root:/Documents/Reports");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e -> e.type().equals("ONEDRIVE_FOLDER") && e.name().equals("Reports")));
        assertTrue(result.relations().stream().anyMatch(r -> r.type().equals("CONTAINED_IN")));
    }

    @Test
    void extractCreatesInDriveRelation() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source_type", "ONEDRIVE");
        meta.put("onedrive_item_id", "item1");
        meta.put("onedrive_name", "doc.docx");
        meta.put("onedrive_drive_id", "drive123");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e -> e.type().equals("ONEDRIVE_DRIVE")));
        assertTrue(result.relations().stream().anyMatch(r -> r.type().equals("IN_DRIVE")));
    }

    @Test
    void extractCreatesSharedLinkRelation() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source_type", "ONEDRIVE");
        meta.put("onedrive_item_id", "item1");
        meta.put("onedrive_name", "shared.pdf");
        meta.put("onedrive.sharedLink", "https://1drv.ms/x/abc123");
        meta.put("onedrive.sharedScope", "organization");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e -> e.type().equals("ONEDRIVE_SHARED_LINK")));
        assertTrue(result.relations().stream().anyMatch(r -> r.type().equals("HAS_SHARED_LINK")));
        ExtractedEntity linkEntity = result.entities().stream()
                .filter(e -> e.type().equals("ONEDRIVE_SHARED_LINK")).findFirst().orElseThrow();
        assertEquals("organization", linkEntity.properties().get("scope"));
    }

    @Test
    void extractReturnsEmptyWhenNoItemIdOrSource() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source_type", "ONEDRIVE");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);
        assertTrue(result.entities().isEmpty());
    }

    @Test
    void extractSetsExtractionMetadata() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source_type", "ONEDRIVE");
        meta.put("onedrive_item_id", "item1");
        meta.put("onedrive_name", "doc.docx");
        meta.put("source", "onedrive:drive/item1");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);
        assertNotNull(result.metadata());
        assertEquals("onedrive-graph-extractor", result.metadata().extractionModel());
    }

    // --- extractBatch ---

    @Test
    void extractBatchDeduplicatesPersonEntities() {
        Map<String, Object> meta1 = new HashMap<>();
        meta1.put("source_type", "ONEDRIVE");
        meta1.put("onedrive_item_id", "item1");
        meta1.put("onedrive_name", "doc1.docx");
        meta1.put("onedrive.createdBy", "Alice");
        meta1.put("onedrive.createdByEmail", "alice@example.com");

        Map<String, Object> meta2 = new HashMap<>();
        meta2.put("source_type", "ONEDRIVE");
        meta2.put("onedrive_item_id", "item2");
        meta2.put("onedrive_name", "doc2.docx");
        meta2.put("onedrive.createdBy", "Alice");
        meta2.put("onedrive.createdByEmail", "alice@example.com");

        Document doc1 = new Document("content1", meta1);
        Document doc2 = new Document("content2", meta2);

        ExtractionResult result = extractor.extractBatch(List.of(doc1, doc2));

        long aliceCount = result.entities().stream()
                .filter(e -> e.type().equals("PERSON") && e.name().equals("Alice")).count();
        assertEquals(1, aliceCount, "Alice PERSON entity should be deduplicated");
        assertEquals(2, result.relations().stream().filter(r -> r.type().equals("CREATED_BY")).count());
    }

    @Test
    void extractResolvesLegacyMsWordMimeType() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source_type", "ONEDRIVE");
        meta.put("onedrive_item_id", "item1");
        meta.put("onedrive_name", "old.doc");
        meta.put("onedrive_mime_type", "application/msword");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);
        assertEquals("ONEDRIVE_DOCUMENT", result.entities().get(0).type());
    }

    @Test
    void extractResolvesAudioFromMimeType() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source_type", "ONEDRIVE");
        meta.put("onedrive_item_id", "item1");
        meta.put("onedrive_name", "podcast.mp3");
        meta.put("onedrive_mime_type", "audio/mpeg");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);
        assertEquals("ONEDRIVE_AUDIO", result.entities().get(0).type());
    }

    @Test
    void extractResolvesTextFromMimeType() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source_type", "ONEDRIVE");
        meta.put("onedrive_item_id", "item1");
        meta.put("onedrive_name", "notes.txt");
        meta.put("onedrive_mime_type", "text/plain");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);
        assertEquals("ONEDRIVE_TEXT", result.entities().get(0).type());
    }

    @Test
    void extractCreatesSharedByFromSharedOwner() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source_type", "ONEDRIVE");
        meta.put("onedrive_item_id", "item1");
        meta.put("onedrive_name", "shared.docx");
        meta.put("onedrive.sharedOwner", "Jane Doe");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e ->
                "PERSON".equals(e.type()) && "Jane Doe".equals(e.name())),
                "Should create PERSON entity from sharedOwner");
        assertTrue(result.relations().stream().anyMatch(r ->
                "SHARED_BY".equals(r.type())),
                "Should create SHARED_BY relation for shared owner");
    }

    @Test
    void extractCreatesSharedLinkAndSharedOwner() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source_type", "ONEDRIVE");
        meta.put("onedrive_item_id", "item1");
        meta.put("onedrive_name", "report.xlsx");
        meta.put("onedrive.sharedLink", "https://1drv.ms/x/abc123");
        meta.put("onedrive.sharedScope", "organization");
        meta.put("onedrive.sharedOwner", "Bob Smith");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e -> "ONEDRIVE_SHARED_LINK".equals(e.type())));
        assertTrue(result.relations().stream().anyMatch(r -> "HAS_SHARED_LINK".equals(r.type())));
        assertTrue(result.entities().stream().anyMatch(e ->
                "PERSON".equals(e.type()) && "Bob Smith".equals(e.name())));
        assertTrue(result.relations().stream().anyMatch(r -> "SHARED_BY".equals(r.type())));
    }
}
