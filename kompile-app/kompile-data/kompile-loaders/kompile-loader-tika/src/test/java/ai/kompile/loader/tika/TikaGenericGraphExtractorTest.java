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

package ai.kompile.loader.tika;

import ai.kompile.core.graphrag.format.GraphExtractionSchema.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TikaGenericGraphExtractorTest {

    private TikaGenericGraphExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new TikaGenericGraphExtractor();
    }

    // --- supportedDocumentTypes ---

    @Test
    void supportedDocumentTypesReturnsExpected() {
        List<String> types = extractor.supportedDocumentTypes();
        assertTrue(types.contains("rtf"));
        assertTrue(types.contains("epub"));
        assertTrue(types.contains("text"));
        assertTrue(types.contains("image"));
        assertTrue(types.contains("tika"));
    }

    // --- canExtract ---

    @Test
    void canExtractReturnsTrueForTikaRtfWithAuthor() {
        Document doc = new Document("content", Map.of("loader", "tika-loader", "documentType", "rtf", "author", "Alice"));
        assertTrue(extractor.canExtract(doc));
    }

    @Test
    void canExtractReturnsTrueForTikaEpubWithTitle() {
        Document doc = new Document("content", Map.of("loader", "tika-generic", "documentType", "epub", "title", "My Book"));
        assertTrue(extractor.canExtract(doc));
    }

    @Test
    void canExtractReturnsTrueForTikaTextWithKeywords() {
        Document doc = new Document("content", Map.of("loader", "tika-loader", "documentType", "text", "keywords", "java, code"));
        assertTrue(extractor.canExtract(doc));
    }

    @Test
    void canExtractReturnsTrueForTikaImageWithSubject() {
        Document doc = new Document("content", Map.of("loader", "tika-parser", "documentType", "image", "subject", "Nature"));
        assertTrue(extractor.canExtract(doc));
    }

    @Test
    void canExtractReturnsFalseForNonTikaLoader() {
        Document doc = new Document("content", Map.of("loader", "web/html-loader", "author", "Alice"));
        assertFalse(extractor.canExtract(doc));
    }

    @Test
    void canExtractReturnsFalseForPdfDocumentType() {
        Document doc = new Document("content", Map.of("loader", "tika-loader", "documentType", "pdf", "author", "Alice"));
        assertFalse(extractor.canExtract(doc));
    }

    @Test
    void canExtractReturnsFalseForWordDocumentType() {
        Document doc = new Document("content", Map.of("loader", "tika-loader", "documentType", "word", "author", "Alice"));
        assertFalse(extractor.canExtract(doc));
    }

    @Test
    void canExtractReturnsFalseForSpreadsheetDocumentType() {
        Document doc = new Document("content", Map.of("loader", "tika-loader", "documentType", "spreadsheet", "author", "Alice"));
        assertFalse(extractor.canExtract(doc));
    }

    @Test
    void canExtractReturnsFalseForHtmlDocumentType() {
        Document doc = new Document("content", Map.of("loader", "tika-loader", "documentType", "html", "author", "Alice"));
        assertFalse(extractor.canExtract(doc));
    }

    @Test
    void canExtractReturnsFalseForExcelDocumentType() {
        Document doc = new Document("content", Map.of("loader", "tika-loader", "documentType", "excel report", "author", "Alice"));
        assertFalse(extractor.canExtract(doc));
    }

    @Test
    void canExtractReturnsTrueForTikaDocWithNoRichMetadata() {
        Document doc = new Document("content", Map.of("loader", "tika-loader", "documentType", "text"));
        assertTrue(extractor.canExtract(doc));
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
    void canExtractReturnsFalseForPresentationType() {
        Document doc = new Document("content", Map.of("loader", "tika-loader", "documentType", "presentation slides", "author", "Alice"));
        assertFalse(extractor.canExtract(doc));
    }

    @Test
    void canExtractReturnsFalseForStreamingDocxType() {
        // StreamingOfficeLoaderImpl sets documentType="DOCX" — Tika should defer to OfficeGraphExtractor
        Document doc = new Document("content", Map.of("loader", "tika-loader", "documentType", "DOCX"));
        assertFalse(extractor.canExtract(doc));
    }

    @Test
    void canExtractReturnsFalseForStreamingXlsxType() {
        Document doc = new Document("content", Map.of("loader", "tika-loader", "documentType", "XLSX"));
        assertFalse(extractor.canExtract(doc));
    }

    @Test
    void canExtractReturnsFalseForStreamingPptxType() {
        Document doc = new Document("content", Map.of("loader", "tika-loader", "documentType", "PPTX"));
        assertFalse(extractor.canExtract(doc));
    }

    // --- extract ---

    @Test
    void extractCreatesRtfDocumentEntity() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("documentType", "rtf");
        meta.put("title", "My RTF Doc");
        meta.put("source", "/docs/file.rtf");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertFalse(result.entities().isEmpty());
        ExtractedEntity docEntity = result.entities().get(0);
        assertEquals("My RTF Doc", docEntity.name());
        assertEquals("RTF_DOCUMENT", docEntity.type());
        assertEquals(1.0, docEntity.confidence());
    }

    @Test
    void extractCreatesEpubDocumentEntity() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("documentType", "epub");
        meta.put("title", "E-Book");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertEquals("EPUB_DOCUMENT", result.entities().get(0).type());
    }

    @Test
    void extractCreatesTextDocumentEntity() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("documentType", "plain text");
        meta.put("title", "Notes");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertEquals("TEXT_DOCUMENT", result.entities().get(0).type());
    }

    @Test
    void extractCreatesImageDocumentEntity() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("documentType", "image");
        meta.put("title", "Photo");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertEquals("IMAGE_DOCUMENT", result.entities().get(0).type());
    }

    @Test
    void extractCreatesCsvDocumentEntity() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("documentType", "CSV Document");
        meta.put("title", "Sales Data");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertEquals("CSV_DOCUMENT", result.entities().get(0).type());
    }

    @Test
    void extractCreatesMarkdownDocumentEntity() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("documentType", "Markdown Document");
        meta.put("title", "README");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertEquals("MARKDOWN_DOCUMENT", result.entities().get(0).type());
    }

    @Test
    void extractCreatesFallbackDocumentEntity() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("title", "Unknown");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertEquals("DOCUMENT", result.entities().get(0).type());
    }

    @Test
    void extractUsesFileNameWhenNoTitle() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("fileName", "readme.txt");
        meta.put("author", "Bob");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertEquals("readme.txt", result.entities().get(0).name());
    }

    @Test
    void extractUsesUntitledWhenNoTitleOrFileName() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("author", "Bob");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertEquals("Untitled Document", result.entities().get(0).name());
    }

    @Test
    void extractCreatesAuthoredByRelation() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("documentType", "rtf");
        meta.put("title", "Doc");
        meta.put("author", "Jane Smith");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e -> e.type().equals("PERSON") && e.name().equals("Jane Smith")));
        assertTrue(result.relations().stream().anyMatch(r -> r.type().equals("AUTHORED_BY")));
        ExtractedEntity person = result.entities().stream()
                .filter(e -> e.type().equals("PERSON")).findFirst().orElseThrow();
        assertEquals(0.9, person.confidence());
    }

    @Test
    void extractSplitsMultipleAuthors() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("title", "Doc");
        meta.put("author", "Alice; Bob and Carol");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        long personCount = result.entities().stream().filter(e -> e.type().equals("PERSON")).count();
        assertEquals(3, personCount);
    }

    @Test
    void extractCreatesProducedByFromProducer() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("title", "Doc");
        meta.put("producer", "LibreOffice 7.5");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e ->
                e.type().equals("ORGANIZATION") && e.name().equals("LibreOffice 7.5")));
        assertTrue(result.relations().stream().anyMatch(r -> r.type().equals("PRODUCED_BY")));
    }

    @Test
    void extractCreatesProducedByFromApplicationName() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("title", "Doc");
        meta.put("applicationName", "TextEdit");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e ->
                e.type().equals("ORGANIZATION") && e.name().equals("TextEdit")));
    }

    @Test
    void extractPrefersProducerOverApplicationName() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("title", "Doc");
        meta.put("producer", "AbiWord");
        meta.put("applicationName", "TextEdit");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e ->
                e.type().equals("ORGANIZATION") && e.name().equals("AbiWord")));
        assertFalse(result.entities().stream().anyMatch(e ->
                e.type().equals("ORGANIZATION") && e.name().equals("TextEdit")));
    }

    @Test
    void extractCreatesHasTopicFromKeywords() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("title", "Doc");
        meta.put("keywords", "ai, machine learning, nlp");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        long topicCount = result.entities().stream().filter(e -> e.type().equals("TOPIC")).count();
        assertEquals(3, topicCount);
        long hasTopicCount = result.relations().stream().filter(r -> r.type().equals("HAS_TOPIC")).count();
        assertEquals(3, hasTopicCount);
    }

    @Test
    void extractIgnoresSingleCharKeywords() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("title", "Doc");
        meta.put("keywords", "a, java, b, spring");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        long topicCount = result.entities().stream().filter(e -> e.type().equals("TOPIC")).count();
        assertEquals(2, topicCount);
    }

    @Test
    void extractReturnsDocEntityWithSparseMetadata() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("title", "Sparse Doc");
        Document doc = new Document("content", meta);
        ExtractionResult result = extractor.extract(doc);
        assertFalse(result.entities().isEmpty());
        assertEquals("Sparse Doc", result.entities().get(0).name());
    }

    @Test
    void extractIncludesMetadataProperties() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("title", "Doc");
        meta.put("subject", "Science");
        meta.put("description", "Scientific paper");
        meta.put("language", "en");
        meta.put("fileSize", 12345);
        meta.put("pageCount", 10);
        meta.put("creationDate", "2025-01-01");
        meta.put("modificationDate", "2025-06-01");
        meta.put("tika.contentType", "application/rtf");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity docEntity = result.entities().get(0);
        assertEquals("Science", docEntity.properties().get("subject"));
        assertEquals("Scientific paper", docEntity.properties().get("description"));
        assertEquals("en", docEntity.properties().get("language"));
        assertEquals("12345", docEntity.properties().get("fileSize"));
        assertEquals("10", docEntity.properties().get("pageCount"));
        assertEquals("application/rtf", docEntity.properties().get("contentType"));
    }

    @Test
    void extractSetsExtractionMetadata() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("title", "Doc");
        meta.put("source", "/path/doc.rtf");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertNotNull(result.metadata());
        assertEquals("tika-generic-extractor", result.metadata().extractionModel());
    }

    // --- extractBatch ---

    @Test
    void extractBatchDeduplicatesEntities() {
        Map<String, Object> meta1 = new HashMap<>();
        meta1.put("loader", "tika-loader");
        meta1.put("title", "Doc1");
        meta1.put("author", "Alice");
        Document doc1 = new Document("content1", meta1);

        Map<String, Object> meta2 = new HashMap<>();
        meta2.put("loader", "tika-loader");
        meta2.put("title", "Doc2");
        meta2.put("author", "Alice");
        Document doc2 = new Document("content2", meta2);

        ExtractionResult result = extractor.extractBatch(List.of(doc1, doc2));

        long aliceCount = result.entities().stream()
                .filter(e -> e.type().equals("PERSON") && e.name().equals("Alice")).count();
        assertEquals(1, aliceCount);
        assertEquals(2, result.relations().stream().filter(r -> r.type().equals("AUTHORED_BY")).count());
    }

    @Test
    void extractBatchMergesEntityProperties() {
        Map<String, Object> meta1 = new HashMap<>();
        meta1.put("loader", "tika-loader");
        meta1.put("title", "Doc1");
        meta1.put("author", "Alice");
        Document doc1 = new Document("content1", meta1);

        Map<String, Object> meta2 = new HashMap<>();
        meta2.put("loader", "tika-loader");
        meta2.put("title", "Doc2");
        meta2.put("author", "Alice");
        Document doc2 = new Document("content2", meta2);

        ExtractionResult result = extractor.extractBatch(List.of(doc1, doc2));

        long docCount = result.entities().stream().filter(e -> e.name().startsWith("Doc")).count();
        assertEquals(2, docCount);
    }

    @Test
    void extractBatchSetsExtractionMetadata() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("title", "Doc");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extractBatch(List.of(doc));

        assertNotNull(result.metadata());
        assertEquals("tika-generic-extractor", result.metadata().extractionModel());
    }

    // --- EXIF / image metadata ---

    @Test
    void extractIncludesExifPropertiesForImage() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("documentType", "image");
        meta.put("title", "Sunset Photo");
        meta.put("image.width", "4032");
        meta.put("image.height", "3024");
        meta.put("image.make", "Canon");
        meta.put("image.model", "EOS R5");
        meta.put("exif.focalLength", "85mm");
        meta.put("exif.isoSpeedRatings", "400");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity docEntity = result.entities().get(0);
        assertEquals("IMAGE_DOCUMENT", docEntity.type());
        assertEquals("4032", docEntity.properties().get("imageWidth"));
        assertEquals("3024", docEntity.properties().get("imageHeight"));
        assertEquals("Canon", docEntity.properties().get("cameraMake"));
        assertEquals("EOS R5", docEntity.properties().get("cameraModel"));
        assertEquals("85mm", docEntity.properties().get("focalLength"));
        assertEquals("400", docEntity.properties().get("isoSpeed"));
    }

    @Test
    void extractCreatesCameraEntityFromExif() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("documentType", "image");
        meta.put("title", "Photo");
        meta.put("image.make", "Nikon");
        meta.put("image.model", "Z9");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e ->
                e.type().equals("CAMERA") && e.name().equals("Nikon Z9")));
        assertTrue(result.relations().stream().anyMatch(r -> r.type().equals("TAKEN_WITH")));
    }

    @Test
    void extractCreatesCameraEntityFromMakeOnly() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("documentType", "image");
        meta.put("title", "Photo");
        meta.put("image.make", "Apple");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e ->
                e.type().equals("CAMERA") && e.name().equals("Apple")));
    }

    @Test
    void extractCreatesGeoLocationFromGps() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("documentType", "image");
        meta.put("title", "GeoPhoto");
        meta.put("geo.lat", "37.7749");
        meta.put("geo.long", "-122.4194");
        meta.put("geo.altitude", "15m");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e ->
                e.type().equals("GEO_LOCATION")));
        assertTrue(result.relations().stream().anyMatch(r -> r.type().equals("LOCATED_AT")));

        ExtractedEntity loc = result.entities().stream()
                .filter(e -> e.type().equals("GEO_LOCATION")).findFirst().orElseThrow();
        assertEquals("37.7749", loc.properties().get("latitude"));
        assertEquals("-122.4194", loc.properties().get("longitude"));
        assertEquals("15m", loc.properties().get("altitude"));
    }

    @Test
    void extractNoCameraOrGeoWithoutExifData() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("documentType", "image");
        meta.put("title", "Simple Image");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertFalse(result.entities().stream().anyMatch(e -> e.type().equals("CAMERA")));
        assertFalse(result.entities().stream().anyMatch(e -> e.type().equals("GEO_LOCATION")));
        assertFalse(result.relations().stream().anyMatch(r -> r.type().equals("TAKEN_WITH")));
        assertFalse(result.relations().stream().anyMatch(r -> r.type().equals("LOCATED_AT")));
    }

    @Test
    void extractResolvesOcrImageDocumentType() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("fileName", "scan.png");
        meta.put("ocr_processed", true);
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertEquals("OCR_IMAGE_DOCUMENT", result.entities().get(0).type());
        assertEquals("true", result.entities().get(0).properties().get("ocrProcessed"));
    }

    @Test
    void extractResolvesOcrDocumentTypeForNonImage() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("fileName", "scanned.tiff");
        meta.put("ocr_processed", true);
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertEquals("OCR_IMAGE_DOCUMENT", result.entities().get(0).type());
    }

    // --- CSV TABLE entity extraction ---

    @Test
    void extractCsvDocumentCreatesTableEntity() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("documentType", "CSV Document");
        meta.put("fileName", "sales.csv");
        meta.put("source", "/data/sales.csv");
        meta.put("content_type", "table");
        meta.put("table_row_count", 100);
        meta.put("table_column_count", 5);
        meta.put("table_headers", "Date, Product, Quantity, Price, Total");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e ->
                "TABLE".equals(e.type()) && "sales.csv".equals(e.name())));
        assertTrue(result.relations().stream().anyMatch(r -> "HAS_TABLE".equals(r.type())));
    }

    @Test
    void extractCsvTableEntityIncludesRowColumnCounts() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("documentType", "CSV Document");
        meta.put("fileName", "data.csv");
        meta.put("source", "/data/data.csv");
        meta.put("content_type", "table");
        meta.put("table_row_count", 50);
        meta.put("table_column_count", 3);
        meta.put("table_headers", "Name, Age, City");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity table = result.entities().stream()
                .filter(e -> "TABLE".equals(e.type())).findFirst().orElseThrow();
        assertEquals("50", table.properties().get("rowCount"));
        assertEquals("3", table.properties().get("columnCount"));
        assertEquals("Name, Age, City", table.properties().get("headers"));
    }

    @Test
    void extractCsvDocumentWithoutContentTypeStillCreatesTableEntity() {
        // CSV_DOCUMENT entity type alone should trigger TABLE creation
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("documentType", "CSV Document");
        meta.put("fileName", "report.csv");
        meta.put("source", "/data/report.csv");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e -> "TABLE".equals(e.type())),
                "CSV documents should create a TABLE entity even without content_type=table");
    }

    @Test
    void extractNonCsvDocumentDoesNotCreateTableEntity() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("documentType", "rtf");
        meta.put("title", "Notes");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertFalse(result.entities().stream().anyMatch(e -> "TABLE".equals(e.type())),
                "Non-CSV/table documents should not create TABLE entities");
    }

    @Test
    void extractGeoRequiresBothLatAndLong() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("documentType", "image");
        meta.put("title", "Partial GPS");
        meta.put("geo.lat", "37.7749");
        // geo.long intentionally missing
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertFalse(result.entities().stream().anyMatch(e -> e.type().equals("GEO_LOCATION")));
    }

    // --- CSV line parser ---

    @Test
    void parseCsvLineHandlesSimpleFields() {
        List<String> fields = TikaLoaderImpl.parseCsvLine("Name,Age,City");
        assertEquals(List.of("Name", "Age", "City"), fields);
    }

    @Test
    void parseCsvLineHandlesQuotedFieldsWithCommas() {
        List<String> fields = TikaLoaderImpl.parseCsvLine("\"Smith, John\",30,\"New York, NY\"");
        assertEquals(3, fields.size());
        assertEquals("Smith, John", fields.get(0));
        assertEquals("30", fields.get(1));
        assertEquals("New York, NY", fields.get(2));
    }

    @Test
    void parseCsvLineHandlesEmptyFields() {
        List<String> fields = TikaLoaderImpl.parseCsvLine("a,,c");
        assertEquals(3, fields.size());
        assertEquals("a", fields.get(0));
        assertEquals("", fields.get(1));
        assertEquals("c", fields.get(2));
    }

    // --- Audio/Video/JSON/XML entity type resolution ---

    @Test
    void extractAudioDocumentCreatesAudioDocumentEntity() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Audio File");
        meta.put("source", "podcast.mp3");
        meta.put("fileName", "podcast.mp3");
        Document doc = new Document("Audio metadata", meta);

        ExtractionResult result = extractor.extract(doc);

        assertFalse(result.entities().isEmpty());
        assertEquals("AUDIO_DOCUMENT", result.entities().get(0).type(),
                "Audio files should resolve to AUDIO_DOCUMENT entity type");
    }

    @Test
    void extractVideoDocumentCreatesVideoDocumentEntity() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Video File");
        meta.put("source", "lecture.mp4");
        meta.put("fileName", "lecture.mp4");
        Document doc = new Document("Video metadata", meta);

        ExtractionResult result = extractor.extract(doc);

        assertFalse(result.entities().isEmpty());
        assertEquals("VIDEO_DOCUMENT", result.entities().get(0).type(),
                "Video files should resolve to VIDEO_DOCUMENT entity type");
    }

    @Test
    void extractJsonDocumentCreatesJsonDocumentEntity() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "JSON Document");
        meta.put("source", "config.json");
        meta.put("fileName", "config.json");
        Document doc = new Document("{\"key\":\"value\"}", meta);

        ExtractionResult result = extractor.extract(doc);

        assertFalse(result.entities().isEmpty());
        assertEquals("JSON_DOCUMENT", result.entities().get(0).type());
    }

    // --- lastModified fallback ---

    @Test
    void extractStoresLastModifiedWhenModificationDateAbsent() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("title", "Doc");
        meta.put("lastModified", "2025-03-15T10:00:00Z");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity docEntity = result.entities().get(0);
        assertEquals("2025-03-15T10:00:00Z", docEntity.properties().get("lastModified"));
        assertNull(docEntity.properties().get("modificationDate"));
    }

    @Test
    void extractDoesNotOverrideModificationDateWithLastModified() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("title", "Doc");
        meta.put("modificationDate", "2025-06-01");
        meta.put("lastModified", "2025-03-15T10:00:00Z");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity docEntity = result.entities().get(0);
        assertEquals("2025-06-01", docEntity.properties().get("modificationDate"));
        assertNull(docEntity.properties().get("lastModified"),
                "lastModified should not be stored when modificationDate is present");
    }

    // --- Audio/Video metadata enrichment ---

    @Test
    void extractAudioDocumentIncludesMediaProperties() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("documentType", "Audio File");
        meta.put("fileName", "song.mp3");
        meta.put("source", "/music/song.mp3");
        meta.put("media.duration", "234.5");
        meta.put("media.codec", "MP3");
        meta.put("media.sampleRate", "44100");
        meta.put("media.channels", "Stereo");
        meta.put("media.bitRate", "320000");
        meta.put("media.artist", "The Band");
        meta.put("media.album", "Greatest Hits");
        meta.put("media.genre", "Rock");
        meta.put("media.trackNumber", "3");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity docEntity = result.entities().get(0);
        assertEquals("AUDIO_DOCUMENT", docEntity.type());
        assertEquals("234.5", docEntity.properties().get("duration"));
        assertEquals("MP3", docEntity.properties().get("codec"));
        assertEquals("44100", docEntity.properties().get("sampleRate"));
        assertEquals("Stereo", docEntity.properties().get("channels"));
        assertEquals("320000", docEntity.properties().get("bitRate"));
        assertEquals("The Band", docEntity.properties().get("artist"));
        assertEquals("Greatest Hits", docEntity.properties().get("album"));
        assertEquals("Rock", docEntity.properties().get("genre"));
        assertEquals("3", docEntity.properties().get("trackNumber"));
    }

    @Test
    void extractAudioDocumentCreatesArtistPersonEntity() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("documentType", "Audio File");
        meta.put("fileName", "track.mp3");
        meta.put("media.artist", "John Doe");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e ->
                e.type().equals("PERSON") && e.name().equals("John Doe")),
                "Audio artist should be extracted as PERSON entity");
        assertTrue(result.relations().stream().anyMatch(r -> r.type().equals("AUTHORED_BY")),
                "Audio artist should have AUTHORED_BY relation");
    }

    @Test
    void extractVideoDocumentIncludesVideoSpecificProperties() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("documentType", "Video File");
        meta.put("fileName", "lecture.mp4");
        meta.put("source", "/videos/lecture.mp4");
        meta.put("media.duration", "3600.0");
        meta.put("media.codec", "H.264");
        meta.put("media.videoFrameRate", "30");
        meta.put("media.videoWidth", "1920");
        meta.put("media.videoHeight", "1080");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity docEntity = result.entities().get(0);
        assertEquals("VIDEO_DOCUMENT", docEntity.type());
        assertEquals("3600.0", docEntity.properties().get("duration"));
        assertEquals("H.264", docEntity.properties().get("codec"));
        assertEquals("30", docEntity.properties().get("videoFrameRate"));
        assertEquals("1920", docEntity.properties().get("videoWidth"));
        assertEquals("1080", docEntity.properties().get("videoHeight"));
    }

    @Test
    void extractAudioDocumentWithoutMediaPropsStillWorks() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("documentType", "Audio File");
        meta.put("fileName", "unknown.wav");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertFalse(result.entities().isEmpty());
        assertEquals("AUDIO_DOCUMENT", result.entities().get(0).type());
        // No media properties should be set
        assertNull(result.entities().get(0).properties().get("duration"));
    }

    // --- Image software → ORGANIZATION entity ---

    @Test
    void extractImageSoftwareCreatesOrganizationEntity() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("documentType", "image");
        meta.put("title", "Edited Photo");
        meta.put("image.software", "Adobe Photoshop 25.0");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e ->
                e.type().equals("ORGANIZATION") && e.name().equals("Adobe Photoshop 25.0")),
                "Image software should be extracted as ORGANIZATION entity");
        assertTrue(result.relations().stream().anyMatch(r -> r.type().equals("PROCESSED_BY")),
                "Image software should have PROCESSED_BY relation");
    }

    @Test
    void extractImageSoftwareNotCreatedWhenProducerExists() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("documentType", "image");
        meta.put("title", "Photo");
        meta.put("producer", "GIMP");
        meta.put("image.software", "GIMP 2.10");
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        // Producer should be an ORGANIZATION with PRODUCED_BY, but imageSoftware should NOT duplicate it
        assertTrue(result.entities().stream().anyMatch(e ->
                e.type().equals("ORGANIZATION") && e.name().equals("GIMP")));
        assertFalse(result.relations().stream().anyMatch(r -> r.type().equals("PROCESSED_BY")),
                "PROCESSED_BY should not exist when producer already covers the software");
    }

    @Test
    void extractXmlDocumentCreatesXmlDocumentEntity() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "XML Document");
        meta.put("source", "data.xml");
        meta.put("fileName", "data.xml");
        Document doc = new Document("<root><item/></root>", meta);

        ExtractionResult result = extractor.extract(doc);

        assertFalse(result.entities().isEmpty());
        assertEquals("XML_DOCUMENT", result.entities().get(0).type());
    }

    // --- tika.headings → DOCUMENT_SECTION entities ---

    @Test
    void extractEpubWithHeadingsCreatesDocumentSectionEntities() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "Apache Tika Loader");
        meta.put("documentType", "EPUB Document");
        meta.put("title", "My E-Book");
        meta.put("source", "/docs/book.epub");
        List<Map<String, String>> headings = new ArrayList<>();
        Map<String, String> h1 = new LinkedHashMap<>();
        h1.put("text", "Chapter 1: Introduction");
        h1.put("level", "1");
        h1.put("index", "0");
        headings.add(h1);
        Map<String, String> h2 = new LinkedHashMap<>();
        h2.put("text", "Chapter 2: Background");
        h2.put("level", "1");
        h2.put("index", "1");
        headings.add(h2);
        meta.put("tika.headings", headings);
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        long sectionCount = result.entities().stream()
                .filter(e -> "DOCUMENT_SECTION".equals(e.type())).count();
        assertEquals(2, sectionCount, "Should create one DOCUMENT_SECTION per heading");

        assertTrue(result.entities().stream().anyMatch(e ->
                        "DOCUMENT_SECTION".equals(e.type()) && "Chapter 1: Introduction".equals(e.name())),
                "First chapter heading should be a DOCUMENT_SECTION entity");
        assertTrue(result.entities().stream().anyMatch(e ->
                        "DOCUMENT_SECTION".equals(e.type()) && "Chapter 2: Background".equals(e.name())),
                "Second chapter heading should be a DOCUMENT_SECTION entity");

        long hasSectionCount = result.relations().stream()
                .filter(r -> "HAS_SECTION".equals(r.type())).count();
        assertEquals(2, hasSectionCount, "Should create one HAS_SECTION relation per heading");
    }

    @Test
    void extractRtfWithHeadingsCreatesDocumentSectionEntities() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "Apache Tika Loader");
        meta.put("documentType", "RTF Document");
        meta.put("title", "Technical Report");
        meta.put("source", "/docs/report.rtf");
        List<Map<String, String>> headings = new ArrayList<>();
        Map<String, String> h1 = new LinkedHashMap<>();
        h1.put("text", "Executive Summary");
        h1.put("level", "1");
        h1.put("index", "0");
        headings.add(h1);
        Map<String, String> h2 = new LinkedHashMap<>();
        h2.put("text", "Findings");
        h2.put("level", "2");
        h2.put("index", "1");
        headings.add(h2);
        meta.put("tika.headings", headings);
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e ->
                        "DOCUMENT_SECTION".equals(e.type()) && "Executive Summary".equals(e.name())),
                "RTF h1 heading should produce a DOCUMENT_SECTION");
        assertTrue(result.entities().stream().anyMatch(e ->
                        "DOCUMENT_SECTION".equals(e.type()) && "Findings".equals(e.name())),
                "RTF h2 heading should produce a DOCUMENT_SECTION");

        assertTrue(result.relations().stream().anyMatch(r ->
                        "HAS_SECTION".equals(r.type()) && r.description().contains("Executive Summary")),
                "HAS_SECTION relation should reference the heading text");
    }

    @Test
    void extractDocumentSectionPropertiesContainHeadingLevelAndIndex() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "Apache Tika Loader");
        meta.put("documentType", "EPUB Document");
        meta.put("title", "Test Book");
        meta.put("source", "/docs/test.epub");
        Map<String, String> heading = new LinkedHashMap<>();
        heading.put("text", "Preface");
        heading.put("level", "2");
        heading.put("index", "3");
        meta.put("tika.headings", List.of(heading));
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity section = result.entities().stream()
                .filter(e -> "DOCUMENT_SECTION".equals(e.type())).findFirst().orElseThrow();
        assertEquals("Preface", section.name());
        assertEquals("Preface", section.properties().get("headingText"));
        assertEquals("2", section.properties().get("headingLevel"));
        assertEquals("3", section.properties().get("sectionIndex"));
        assertEquals(0.85, section.confidence());
    }

    @Test
    void extractDocumentSectionConfidenceIs085() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "Apache Tika Loader");
        meta.put("documentType", "RTF Document");
        meta.put("title", "Doc");
        meta.put("source", "/docs/doc.rtf");
        Map<String, String> heading = new LinkedHashMap<>();
        heading.put("text", "Section A");
        heading.put("level", "1");
        heading.put("index", "0");
        meta.put("tika.headings", List.of(heading));
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity section = result.entities().stream()
                .filter(e -> "DOCUMENT_SECTION".equals(e.type())).findFirst().orElseThrow();
        assertEquals(0.85, section.confidence());

        var rel = result.relations().stream()
                .filter(r -> "HAS_SECTION".equals(r.type())).findFirst().orElseThrow();
        assertEquals(0.85, rel.confidence());
    }

    @Test
    void extractSkipsBlankHeadingText() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "Apache Tika Loader");
        meta.put("documentType", "EPUB Document");
        meta.put("title", "Book");
        meta.put("source", "/docs/book.epub");
        List<Map<String, String>> headings = new ArrayList<>();
        Map<String, String> blank = new LinkedHashMap<>();
        blank.put("text", "   ");
        blank.put("level", "1");
        blank.put("index", "0");
        headings.add(blank);
        Map<String, String> real = new LinkedHashMap<>();
        real.put("text", "Chapter One");
        real.put("level", "1");
        real.put("index", "1");
        headings.add(real);
        meta.put("tika.headings", headings);
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        long sectionCount = result.entities().stream()
                .filter(e -> "DOCUMENT_SECTION".equals(e.type())).count();
        assertEquals(1, sectionCount, "Blank heading text should be skipped");
        assertEquals("Chapter One", result.entities().stream()
                .filter(e -> "DOCUMENT_SECTION".equals(e.type()))
                .findFirst().orElseThrow().name());
    }

    @Test
    void extractNoHeadingsMetadataProducesNoSectionEntities() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "Apache Tika Loader");
        meta.put("documentType", "EPUB Document");
        meta.put("title", "Plain E-Book");
        meta.put("source", "/docs/plain.epub");
        // no tika.headings key
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertFalse(result.entities().stream().anyMatch(e -> "DOCUMENT_SECTION".equals(e.type())),
                "No tika.headings should produce no DOCUMENT_SECTION entities");
        assertFalse(result.relations().stream().anyMatch(r -> "HAS_SECTION".equals(r.type())));
    }

    @Test
    void extractHeadingsCoexistWithOtherEntities() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "Apache Tika Loader");
        meta.put("documentType", "EPUB Document");
        meta.put("title", "Novel");
        meta.put("source", "/docs/novel.epub");
        meta.put("author", "Jane Doe");
        meta.put("keywords", "fiction, drama");
        Map<String, String> heading = new LinkedHashMap<>();
        heading.put("text", "Prologue");
        heading.put("level", "1");
        heading.put("index", "0");
        meta.put("tika.headings", List.of(heading));
        Document doc = new Document("content", meta);

        ExtractionResult result = extractor.extract(doc);

        assertTrue(result.entities().stream().anyMatch(e -> "PERSON".equals(e.type())),
                "Author should still be extracted as PERSON");
        assertTrue(result.entities().stream().anyMatch(e -> "TOPIC".equals(e.type())),
                "Keywords should still produce TOPIC entities");
        assertTrue(result.entities().stream().anyMatch(e -> "DOCUMENT_SECTION".equals(e.type())),
                "Heading should still produce DOCUMENT_SECTION entity");
        assertTrue(result.relations().stream().anyMatch(r -> "AUTHORED_BY".equals(r.type())));
        assertTrue(result.relations().stream().anyMatch(r -> "HAS_SECTION".equals(r.type())));
    }

    // ── ocrConfidence property ────────────────────────────────────────────

    @Test
    void extractOcrProcessedDocument_withConfidence_storesOcrConfidenceProperty() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("fileName", "scanned.png");
        meta.put("ocr_processed", true);
        meta.put("pdf_processing_mode", "vlm");
        meta.put("vlm_model", "gotocr2");
        meta.put("ocrConfidence", "0.92");

        ExtractionResult result = extractor.extract(new Document("text", meta));

        assertFalse(result.entities().isEmpty());
        ExtractedEntity docEntity = result.entities().get(0);
        assertEquals("true", docEntity.properties().get("ocrProcessed"),
                "ocrProcessed must be set when ocr_processed=true");
        assertEquals("0.92", docEntity.properties().get("ocrConfidence"),
                "ocrConfidence must be stored on the document entity when ocr_processed=true");
    }

    @Test
    void extractOcrProcessedDocument_withoutConfidence_noOcrConfidenceProperty() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("fileName", "scanned.png");
        meta.put("ocr_processed", true);
        meta.put("vlm_model", "gotocr2");
        // ocrConfidence NOT set

        ExtractionResult result = extractor.extract(new Document("text", meta));

        ExtractedEntity docEntity = result.entities().get(0);
        assertEquals("true", docEntity.properties().get("ocrProcessed"));
        assertNull(docEntity.properties().get("ocrConfidence"),
                "ocrConfidence must not be set when absent from metadata");
    }

    @Test
    void extractNonOcrDocument_withConfidenceKey_doesNotStoreOcrConfidence() {
        // ocrConfidence present but ocr_processed is false — must not be captured
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("documentType", "text");
        meta.put("title", "Normal Doc");
        meta.put("ocrConfidence", "0.99");
        // ocr_processed NOT set

        ExtractionResult result = extractor.extract(new Document("content", meta));

        ExtractedEntity docEntity = result.entities().get(0);
        assertNull(docEntity.properties().get("ocrConfidence"),
                "ocrConfidence must not be stored when ocr_processed is not true");
    }

    // ── Markdown heading extraction (TikaLoaderImpl helper) ─────────────

    @Test
    void extractMarkdownHeadings_parsesAtxHeadings() {
        String md = "# Title\n\nSome text.\n\n## Section One\n\nMore text.\n\n### Subsection\n";
        List<Map<String, String>> headings = TikaLoaderImpl.extractMarkdownHeadings(md);

        assertEquals(3, headings.size());
        assertEquals("Title", headings.get(0).get("text"));
        assertEquals("1", headings.get(0).get("level"));
        assertEquals("Section One", headings.get(1).get("text"));
        assertEquals("2", headings.get(1).get("level"));
        assertEquals("Subsection", headings.get(2).get("text"));
        assertEquals("3", headings.get(2).get("level"));
    }

    @Test
    void extractMarkdownHeadings_stripsTrailingHashes() {
        String md = "## My Section ##\n\n### Another ###\n";
        List<Map<String, String>> headings = TikaLoaderImpl.extractMarkdownHeadings(md);

        assertEquals(2, headings.size());
        assertEquals("My Section", headings.get(0).get("text"));
        assertEquals("Another", headings.get(1).get("text"));
    }

    @Test
    void extractMarkdownHeadings_returnsEmptyForNoHeadings() {
        String md = "No headings here.\nJust plain text.\n";
        List<Map<String, String>> headings = TikaLoaderImpl.extractMarkdownHeadings(md);
        assertTrue(headings.isEmpty());
    }

    @Test
    void extractMarkdownHeadings_skipsHashWithoutSpace() {
        String md = "#NotAHeading\n## Valid Heading\n#Also Not\n";
        List<Map<String, String>> headings = TikaLoaderImpl.extractMarkdownHeadings(md);

        assertEquals(1, headings.size());
        assertEquals("Valid Heading", headings.get(0).get("text"));
    }

    @Test
    void markdownDocumentWithHeadingsProducesDocumentSections() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("loader", "tika-loader");
        meta.put("documentType", "Markdown Document");
        meta.put("title", "README");
        meta.put("tika.headings", List.of(
                Map.of("text", "Installation", "level", "1", "index", "0"),
                Map.of("text", "Usage", "level", "2", "index", "1")
        ));

        ExtractionResult result = extractor.extract(new Document("# Installation\nInstall steps.\n## Usage\nHow to use.", meta));

        List<ExtractedEntity> sections = result.entities().stream()
                .filter(e -> "DOCUMENT_SECTION".equals(e.type())).toList();
        assertEquals(2, sections.size());
        assertEquals("Installation", sections.get(0).name());
        assertEquals("Usage", sections.get(1).name());

        // Level 1 heading gets HAS_SECTION to document, level 2 gets SUBSECTION_OF to level 1
        List<ExtractedRelation> sectionRels = result.relations().stream()
                .filter(r -> "HAS_SECTION".equals(r.type())).toList();
        assertEquals(1, sectionRels.size());
        List<ExtractedRelation> subsectionRels = result.relations().stream()
                .filter(r -> "SUBSECTION_OF".equals(r.type())).toList();
        assertEquals(1, subsectionRels.size());
    }

    // ── YouTube transcript tests ────────────────────────────────────────

    @Test
    void testYouTubeTranscriptCanExtract() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "youtube_transcript");
        assertTrue(extractor.canExtract(new Document("transcript text", meta)));
    }

    @Test
    void testYouTubeTranscriptEntityType() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "youtube_transcript");
        meta.put("source_path", "https://www.youtube.com/watch?v=abc123");
        meta.put("file_name", "My Video Title");
        meta.put("videoId", "abc123");
        meta.put("videoUrl", "https://www.youtube.com/watch?v=abc123");
        meta.put("title", "My Video Title");
        meta.put("language", "en");
        meta.put("segmentCount", 42);
        meta.put("durationSeconds", 300.5);

        ExtractionResult result = extractor.extract(new Document("Hello world transcript text", meta));

        // Primary entity should be YOUTUBE_TRANSCRIPT type
        ExtractedEntity docEntity = result.entities().stream()
                .filter(e -> "YOUTUBE_TRANSCRIPT".equals(e.type())).findFirst().orElse(null);
        assertNotNull(docEntity, "Should have YOUTUBE_TRANSCRIPT entity");
        assertEquals("My Video Title", docEntity.name());
    }

    @Test
    void testYouTubeTranscriptVideoEntity() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "youtube_transcript");
        meta.put("source_path", "https://www.youtube.com/watch?v=abc123");
        meta.put("file_name", "My Video Title");
        meta.put("videoId", "abc123");
        meta.put("videoUrl", "https://www.youtube.com/watch?v=abc123");
        meta.put("title", "My Video Title");
        meta.put("language", "en");
        meta.put("durationSeconds", 300.5);

        ExtractionResult result = extractor.extract(new Document("Hello world transcript", meta));

        // Should have a YOUTUBE_VIDEO entity
        ExtractedEntity videoEntity = result.entities().stream()
                .filter(e -> "YOUTUBE_VIDEO".equals(e.type())).findFirst().orElse(null);
        assertNotNull(videoEntity, "Should create YOUTUBE_VIDEO entity");
        assertEquals("My Video Title", videoEntity.name());
        assertEquals("abc123", videoEntity.properties().get("videoId"));
        assertEquals("en", videoEntity.properties().get("language"));
        assertEquals("300.5", videoEntity.properties().get("durationSeconds"));

        // Should have TRANSCRIPT_OF relation from transcript to video
        List<ExtractedRelation> transcriptRels = result.relations().stream()
                .filter(r -> "TRANSCRIPT_OF".equals(r.type())).toList();
        assertEquals(1, transcriptRels.size());
    }

    @Test
    void testYouTubeTranscriptChannelEntity() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "youtube_transcript");
        meta.put("source_path", "https://www.youtube.com/watch?v=abc123");
        meta.put("file_name", "My Video Title");
        meta.put("videoId", "abc123");
        meta.put("channelName", "TechChannel");
        meta.put("channelId", "UC12345");

        ExtractionResult result = extractor.extract(new Document("transcript text", meta));

        // Should have YOUTUBE_CHANNEL entity
        ExtractedEntity channelEntity = result.entities().stream()
                .filter(e -> "YOUTUBE_CHANNEL".equals(e.type())).findFirst().orElse(null);
        assertNotNull(channelEntity, "Should create YOUTUBE_CHANNEL entity");
        assertEquals("TechChannel", channelEntity.name());
        assertEquals("UC12345", channelEntity.properties().get("channelId"));

        // Should have FROM_CHANNEL relation
        List<ExtractedRelation> channelRels = result.relations().stream()
                .filter(r -> "FROM_CHANNEL".equals(r.type())).toList();
        assertEquals(1, channelRels.size());
    }

    // ── Fallback for unknown/generic document types ─────────────────────

    @Test
    void testCanExtractUnknownParseError() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Unknown (Parse Error)");
        assertTrue(extractor.canExtract(new Document("content", meta)),
                "Unknown (Parse Error) should be accepted as fallback");
    }

    @Test
    void testCanExtractGenericDocument() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Document");
        assertTrue(extractor.canExtract(new Document("content", meta)),
                "Generic 'Document' type should be accepted as fallback");
    }

    @Test
    void testCanExtractGoogleDriveFile() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Google Drive File");
        assertTrue(extractor.canExtract(new Document("content", meta)),
                "Google Drive File should be accepted as fallback");
    }

    @Test
    void testCanExtractStillRejectsPdf() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "PDF Document");
        assertFalse(extractor.canExtract(new Document("content", meta)),
                "PDF documents should still be rejected (handled by PdfGraphExtractor)");
    }

    @Test
    void testCanExtractStillRejectsWord() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "Word Document");
        assertFalse(extractor.canExtract(new Document("content", meta)),
                "Word documents should still be rejected (handled by OfficeGraphExtractor)");
    }
}
