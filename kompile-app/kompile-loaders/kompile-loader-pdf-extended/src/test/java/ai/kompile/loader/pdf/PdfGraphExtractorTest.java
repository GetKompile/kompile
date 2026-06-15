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

package ai.kompile.loader.pdf;

import ai.kompile.core.graphrag.format.GraphExtractionSchema.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PdfGraphExtractor}.
 *
 * <p>All tests are pure unit tests — no mocks, no Spring context, no file I/O.
 * The extractor operates entirely on document metadata, so tests build
 * {@link Document} instances with hand-crafted metadata maps.</p>
 */
class PdfGraphExtractorTest {

    private PdfGraphExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new PdfGraphExtractor();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** Build a Document with arbitrary metadata. Text defaults to empty string. */
    private static Document doc(Map<String, Object> metadata) {
        return new Document("", metadata);
    }

    /** Build a Document with arbitrary metadata and body text. */
    private static Document doc(String text, Map<String, Object> metadata) {
        return new Document(text, metadata);
    }

    /** Recompute the entity ID the same way PdfGraphExtractor does. */
    private static String entityId(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    /** Collect entity IDs by type from an extraction result. */
    private static List<String> entityIdsByType(ExtractionResult result, String type) {
        return result.entities().stream()
                .filter(e -> type.equals(e.type()))
                .map(ExtractedEntity::id)
                .collect(Collectors.toList());
    }

    /** Collect relation types from an extraction result. */
    private static List<String> relationTypes(ExtractionResult result) {
        return result.relations().stream()
                .map(ExtractedRelation::type)
                .collect(Collectors.toList());
    }

    /** Find entity by type; fails if count != 1. */
    private static ExtractedEntity singleEntityOfType(ExtractionResult result, String type) {
        List<ExtractedEntity> found = result.entities().stream()
                .filter(e -> type.equals(e.type()))
                .collect(Collectors.toList());
        assertEquals(1, found.size(),
                "Expected exactly 1 entity of type " + type + " but found " + found.size());
        return found.get(0);
    }

    /** Collect all entities of a given type. */
    private static List<ExtractedEntity> entitiesOfType(ExtractionResult result, String type) {
        return result.entities().stream()
                .filter(e -> type.equals(e.type()))
                .collect(Collectors.toList());
    }

    /** Collect relations of a given type. */
    private static List<ExtractedRelation> relationsOfType(ExtractionResult result, String type) {
        return result.relations().stream()
                .filter(r -> type.equals(r.type()))
                .collect(Collectors.toList());
    }

    // ── canExtract() ───────────────────────────────────────────────────────

    @Test
    void canExtract_returnsTrueForPdfDocumentType() {
        Document d = doc(Map.of("documentType", "PDF Document"));
        assertTrue(extractor.canExtract(d));
    }

    @Test
    void canExtract_returnsTrueForPdfDocumentTypeLowerCase() {
        Document d = doc(Map.of("documentType", "pdf"));
        assertTrue(extractor.canExtract(d));
    }

    @Test
    void canExtract_returnsTrueForPdfLoaderName() {
        Document d = doc(Map.of("loader", "PDF Extended Loader"));
        assertTrue(extractor.canExtract(d));
    }

    @Test
    void canExtract_returnsTrueForLoaderContainingPdf() {
        Document d = doc(Map.of("loader", "my-pdf-extractor"));
        assertTrue(extractor.canExtract(d));
    }

    @Test
    void canExtract_returnsTrueForPdfFileName() {
        Document d = doc(Map.of("fileName", "report.pdf"));
        assertTrue(extractor.canExtract(d));
    }

    @Test
    void canExtract_returnsTrueForUpperCasePdfFileName() {
        Document d = doc(Map.of("fileName", "REPORT.PDF"));
        assertTrue(extractor.canExtract(d));
    }

    @Test
    void canExtract_returnsFalseForNonPdfDocumentType() {
        Document d = doc(Map.of("documentType", "Word Document", "fileName", "report.docx"));
        assertFalse(extractor.canExtract(d));
    }

    @Test
    void canExtract_returnsFalseForNonPdfFileName() {
        Document d = doc(Map.of("documentType", "spreadsheet", "fileName", "data.xlsx"));
        assertFalse(extractor.canExtract(d));
    }

    @Test
    void canExtract_returnsFalseForNonPdfLoader() {
        Document d = doc(Map.of("loader", "Excel Loader", "fileName", "data.xlsx"));
        assertFalse(extractor.canExtract(d));
    }

    @Test
    void canExtract_returnsFalseForNullDocument() {
        assertFalse(extractor.canExtract(null));
    }

    @Test
    void canExtract_returnsFalseForDocumentWithEmptyMetadata() {
        Document d = doc(Map.of());
        assertFalse(extractor.canExtract(d));
    }

    @Test
    void canExtract_returnsTrueForOcrProcessedPdf() {
        Document d = doc(Map.of("ocr_processed", true, "fileName", "scanned.pdf"));
        assertTrue(extractor.canExtract(d));
    }

    @Test
    void canExtract_returnsFalseForOcrProcessedNonPdf() {
        Document d = doc(Map.of("ocr_processed", true, "fileName", "photo.png"));
        assertFalse(extractor.canExtract(d));
    }

    // ── extract() — OCR metadata enrichment ────────────────────────────────

    @Test
    void extract_ocrProcessedPdf_setsOcrProperties() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "scanned_invoice.pdf");
        meta.put("ocr_processed", true);
        meta.put("pdf_processing_mode", "vlm");
        meta.put("vlm_model", "gotocr2");
        ExtractionResult result = extractor.extract(doc(meta));

        assertFalse(result.entities().isEmpty());
        ExtractedEntity docEntity = result.entities().get(0);
        assertEquals("true", docEntity.properties().get("ocrProcessed"));
        assertEquals("vlm", docEntity.properties().get("processingMode"));
        assertEquals("gotocr2", docEntity.properties().get("vlmModel"));
    }

    @Test
    void extract_nonOcrPdf_doesNotSetOcrProperties() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "normal.pdf");
        ExtractionResult result = extractor.extract(doc(meta));

        assertFalse(result.entities().isEmpty());
        ExtractedEntity docEntity = result.entities().get(0);
        assertNull(docEntity.properties().get("ocrProcessed"));
    }

    // ── extract() — full metadata ─────────────────────────────────────────

    @Test
    void extract_fullMetadata_producesPdfDocumentEntityWithCorrectProperties() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("loader", "PDF Extended Loader");
        meta.put("fileName", "annual-report.pdf");
        meta.put("title", "Annual Report 2024");
        meta.put("author", "Jane Smith");
        meta.put("producer", "Adobe Acrobat");
        meta.put("keywords", "finance, report, annual");
        meta.put("subject", "Corporate Finance");
        meta.put("pageCount", 42);
        meta.put("source", "/docs/annual-report.pdf");

        ExtractionResult result = extractor.extract(doc(meta));

        assertNotNull(result);
        assertNotNull(result.entities());
        assertFalse(result.entities().isEmpty());

        // Must always include the PDF_DOCUMENT entity
        ExtractedEntity docEntity = singleEntityOfType(result, "PDF_DOCUMENT");
        assertEquals("Annual Report 2024", docEntity.name());
        assertEquals("42", docEntity.properties().get("pageCount"));
        assertEquals("annual-report.pdf", docEntity.properties().get("fileName"));
        assertEquals("Annual Report 2024", docEntity.properties().get("title"));
        assertEquals("Corporate Finance", docEntity.properties().get("subject"));

        // Verify all expected entity types are present
        List<String> types = result.entities().stream()
                .map(ExtractedEntity::type)
                .collect(Collectors.toList());
        assertTrue(types.contains("PDF_DOCUMENT"), "Must have PDF_DOCUMENT");
        assertTrue(types.contains("PERSON"), "Must have PERSON from author");
        assertTrue(types.contains("ORGANIZATION"), "Must have ORGANIZATION from producer");
        assertTrue(types.contains("TOPIC"), "Must have TOPIC from keywords");

        // Verify relation types cover expected set
        List<String> relTypes = relationTypes(result);
        assertTrue(relTypes.contains("AUTHORED_BY"));
        assertTrue(relTypes.contains("PRODUCED_BY"));
        assertTrue(relTypes.contains("HAS_TOPIC"));
    }

    // ── extract() — PERSON from author ────────────────────────────────────

    @Test
    void extract_withAuthor_createsSinglePersonEntityAndAuthoredByRelation() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "doc.pdf");
        meta.put("title", "Test Doc");
        meta.put("author", "Alice Johnson");
        meta.put("source", "/tmp/doc.pdf");

        ExtractionResult result = extractor.extract(doc(meta));

        ExtractedEntity person = singleEntityOfType(result, "PERSON");
        assertEquals("Alice Johnson", person.name());
        assertEquals("author", person.properties().get("source_field"));
        assertEquals(0.9, person.confidence(), 1e-9);

        List<ExtractedRelation> authored = relationsOfType(result, "AUTHORED_BY");
        assertEquals(1, authored.size());
        assertEquals(person.id(), authored.get(0).target());
        assertNotNull(authored.get(0).type());
    }

    @Test
    void extract_authorEntityId_isDeterministic() {
        // Same author in two separate calls must produce the same entity ID
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "doc.pdf");
        meta.put("author", "Bob Marley");
        meta.put("source", "/tmp/doc.pdf");

        ExtractionResult r1 = extractor.extract(doc(meta));
        ExtractionResult r2 = extractor.extract(doc(meta));

        String id1 = singleEntityOfType(r1, "PERSON").id();
        String id2 = singleEntityOfType(r2, "PERSON").id();
        assertEquals(id1, id2, "Person entity ID must be deterministic for the same author");
    }

    @Test
    void extract_authorEntityId_matchesExpectedUUID() {
        // Verify the ID formula: UUID.nameUUIDFromBytes("person:<lowercase>".getBytes())
        String authorName = "Carol White";
        String expectedId = entityId("person:" + authorName.toLowerCase());

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "doc.pdf");
        meta.put("author", authorName);
        meta.put("source", "/tmp/doc.pdf");

        ExtractionResult result = extractor.extract(doc(meta));
        ExtractedEntity person = singleEntityOfType(result, "PERSON");
        assertEquals(expectedId, person.id());
    }

    // ── extract() — multiple authors ──────────────────────────────────────

    @Test
    void extract_multipleAuthorsCommaSeparated_createsMultiplePersonEntities() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "paper.pdf");
        meta.put("author", "Alice Smith, Bob Jones, Carol Lee");
        meta.put("source", "/tmp/paper.pdf");

        ExtractionResult result = extractor.extract(doc(meta));

        List<ExtractedEntity> persons = entitiesOfType(result, "PERSON");
        assertEquals(3, persons.size(), "Should create one PERSON per author");

        Set<String> names = persons.stream().map(ExtractedEntity::name).collect(Collectors.toSet());
        assertTrue(names.contains("Alice Smith"));
        assertTrue(names.contains("Bob Jones"));
        assertTrue(names.contains("Carol Lee"));

        // Each person should have an AUTHORED_BY relation
        List<ExtractedRelation> authored = relationsOfType(result, "AUTHORED_BY");
        assertEquals(3, authored.size(), "One AUTHORED_BY per author");
    }

    @Test
    void extract_multipleAuthorsSemicolonSeparated_createsMultiplePersonEntities() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "thesis.pdf");
        meta.put("author", "Dana Brown; Eve Green");
        meta.put("source", "/tmp/thesis.pdf");

        ExtractionResult result = extractor.extract(doc(meta));

        List<ExtractedEntity> persons = entitiesOfType(result, "PERSON");
        assertEquals(2, persons.size());

        Set<String> names = persons.stream().map(ExtractedEntity::name).collect(Collectors.toSet());
        assertTrue(names.contains("Dana Brown"));
        assertTrue(names.contains("Eve Green"));
    }

    @Test
    void extract_multipleAuthorsAndSeparated_createsMultiplePersonEntities() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "study.pdf");
        meta.put("author", "Frank Hall and Grace King");
        meta.put("source", "/tmp/study.pdf");

        ExtractionResult result = extractor.extract(doc(meta));

        List<ExtractedEntity> persons = entitiesOfType(result, "PERSON");
        assertEquals(2, persons.size());

        Set<String> names = persons.stream().map(ExtractedEntity::name).collect(Collectors.toSet());
        assertTrue(names.contains("Frank Hall"));
        assertTrue(names.contains("Grace King"));
    }

    // ── extract() — creator (PERSON, different from author) ──────────────

    @Test
    void extract_creatorDiffersFromAuthor_createsPersonWithCreatedByRelation() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "doc.pdf");
        meta.put("author", "Alice Smith");
        meta.put("creator", "Henry Ford");     // different person, not software
        meta.put("source", "/tmp/doc.pdf");

        ExtractionResult result = extractor.extract(doc(meta));

        // Both PERSON entities should be present
        List<ExtractedEntity> persons = entitiesOfType(result, "PERSON");
        Set<String> names = persons.stream().map(ExtractedEntity::name).collect(Collectors.toSet());
        assertTrue(names.contains("Alice Smith"), "Author should be extracted as PERSON");
        assertTrue(names.contains("Henry Ford"), "Creator should be extracted as PERSON");

        // Creator entity should have source_field = creator
        ExtractedEntity creatorEntity = persons.stream()
                .filter(e -> "Henry Ford".equals(e.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Henry Ford entity not found"));
        assertEquals("creator", creatorEntity.properties().get("source_field"));

        // CREATED_BY relation must exist
        List<ExtractedRelation> createdBy = relationsOfType(result, "CREATED_BY");
        assertEquals(1, createdBy.size());
        assertEquals(creatorEntity.id(), createdBy.get(0).target());
        assertEquals(0.8, createdBy.get(0).confidence(), 1e-9);
    }

    @Test
    void extract_creatorSameAsAuthor_doesNotCreateDuplicatePersonOrCreatedByRelation() {
        // When creator == author the creator block is skipped
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "doc.pdf");
        meta.put("author", "Alice Smith");
        meta.put("creator", "Alice Smith");    // same string as author
        meta.put("source", "/tmp/doc.pdf");

        ExtractionResult result = extractor.extract(doc(meta));

        List<ExtractedEntity> persons = entitiesOfType(result, "PERSON");
        assertEquals(1, persons.size(), "Same author/creator should yield only 1 PERSON");

        List<ExtractedRelation> createdBy = relationsOfType(result, "CREATED_BY");
        assertTrue(createdBy.isEmpty(), "No CREATED_BY when creator == author");
    }

    // ── extract() — creator that looks like software ───────────────────────

    @Test
    void extract_creatorIsMicrosoftWord_doesNotCreatePersonEntity() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "doc.pdf");
        meta.put("creator", "Microsoft Word");
        meta.put("source", "/tmp/doc.pdf");

        ExtractionResult result = extractor.extract(doc(meta));

        List<ExtractedEntity> persons = entitiesOfType(result, "PERSON");
        assertTrue(persons.isEmpty(),
                "Software creator 'Microsoft Word' must not create a PERSON entity");
        List<ExtractedRelation> createdBy = relationsOfType(result, "CREATED_BY");
        assertTrue(createdBy.isEmpty(),
                "No CREATED_BY relation expected for software creator");
    }

    @Test
    void extract_creatorIsAdobeAcrobat_doesNotCreatePersonEntity() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "doc.pdf");
        meta.put("creator", "Adobe Acrobat 11.0");
        meta.put("source", "/tmp/doc.pdf");

        ExtractionResult result = extractor.extract(doc(meta));

        List<ExtractedEntity> persons = entitiesOfType(result, "PERSON");
        assertTrue(persons.isEmpty(), "Adobe Acrobat creator must not create a PERSON");
    }

    @Test
    void extract_creatorIsLibreOffice_doesNotCreatePersonEntity() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "doc.pdf");
        meta.put("creator", "LibreOffice Writer");
        meta.put("source", "/tmp/doc.pdf");

        ExtractionResult result = extractor.extract(doc(meta));

        List<ExtractedEntity> persons = entitiesOfType(result, "PERSON");
        assertTrue(persons.isEmpty(), "LibreOffice creator must not create a PERSON");
    }

    @Test
    void extract_creatorIsLaTeX_doesNotCreatePersonEntity() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "doc.pdf");
        meta.put("creator", "LaTeX with hyperref");
        meta.put("source", "/tmp/doc.pdf");

        ExtractionResult result = extractor.extract(doc(meta));

        List<ExtractedEntity> persons = entitiesOfType(result, "PERSON");
        assertTrue(persons.isEmpty(), "LaTeX creator must not create a PERSON");
    }

    // ── extract() — ORGANIZATION from producer ────────────────────────────

    @Test
    void extract_withProducer_createsOrganizationAndProducedByRelation() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "report.pdf");
        meta.put("producer", "Adobe PDF Library 15.0");
        meta.put("source", "/tmp/report.pdf");

        ExtractionResult result = extractor.extract(doc(meta));

        ExtractedEntity org = singleEntityOfType(result, "ORGANIZATION");
        assertEquals("Adobe PDF Library 15.0", org.name());
        assertEquals("producer", org.properties().get("source_field"));
        assertEquals(0.7, org.confidence(), 1e-9);

        List<ExtractedRelation> producedBy = relationsOfType(result, "PRODUCED_BY");
        assertEquals(1, producedBy.size());
        assertEquals(org.id(), producedBy.get(0).target());
        assertNotNull(producedBy.get(0).type());
    }

    @Test
    void extract_producerEntityId_isDeterministic() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "doc.pdf");
        meta.put("producer", "iText 7.0");
        meta.put("source", "/tmp/doc.pdf");

        ExtractionResult r1 = extractor.extract(doc(meta));
        ExtractionResult r2 = extractor.extract(doc(meta));

        String id1 = singleEntityOfType(r1, "ORGANIZATION").id();
        String id2 = singleEntityOfType(r2, "ORGANIZATION").id();
        assertEquals(id1, id2, "Producer entity ID must be deterministic");
    }

    // ── extract() — TOPICs from keywords ──────────────────────────────────

    @Test
    void extract_withKeywords_createsTopicEntitiesAndHasTopicRelations() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "whitepaper.pdf");
        meta.put("keywords", "machine learning, neural networks, deep learning");
        meta.put("source", "/tmp/whitepaper.pdf");

        ExtractionResult result = extractor.extract(doc(meta));

        List<ExtractedEntity> topics = entitiesOfType(result, "TOPIC");
        assertEquals(3, topics.size(), "One TOPIC per keyword");

        Set<String> names = topics.stream().map(ExtractedEntity::name).collect(Collectors.toSet());
        assertTrue(names.contains("machine learning"));
        assertTrue(names.contains("neural networks"));
        assertTrue(names.contains("deep learning"));

        List<ExtractedRelation> hasTopics = relationsOfType(result, "HAS_TOPIC");
        assertEquals(3, hasTopics.size(), "One HAS_TOPIC per keyword");
    }

    @Test
    void extract_keywordsSemicolonSeparated_createsTopicEntities() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "paper.pdf");
        meta.put("keywords", "graph rag; knowledge graph; vector search");
        meta.put("source", "/tmp/paper.pdf");

        ExtractionResult result = extractor.extract(doc(meta));

        List<ExtractedEntity> topics = entitiesOfType(result, "TOPIC");
        assertEquals(3, topics.size());

        Set<String> names = topics.stream().map(ExtractedEntity::name).collect(Collectors.toSet());
        assertTrue(names.contains("graph rag"));
        assertTrue(names.contains("knowledge graph"));
        assertTrue(names.contains("vector search"));
    }

    @Test
    void extract_keywordsPipeSeparated_createsTopicEntities() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "doc.pdf");
        meta.put("keywords", "java|spring|testing");
        meta.put("source", "/tmp/doc.pdf");

        ExtractionResult result = extractor.extract(doc(meta));

        List<ExtractedEntity> topics = entitiesOfType(result, "TOPIC");
        assertEquals(3, topics.size());
    }

    @Test
    void extract_topicEntityId_isDeterministic() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "doc.pdf");
        meta.put("keywords", "artificial intelligence");
        meta.put("source", "/tmp/doc.pdf");

        ExtractionResult r1 = extractor.extract(doc(meta));
        ExtractionResult r2 = extractor.extract(doc(meta));

        String id1 = singleEntityOfType(r1, "TOPIC").id();
        String id2 = singleEntityOfType(r2, "TOPIC").id();
        assertEquals(id1, id2, "Topic entity ID must be deterministic");
    }

    // ── extract() — EXTERNAL_RESOURCE from annotations ────────────────────

    @Test
    void extract_annotationsDocument_createsExternalResourceAndHyperlinksTo() {
        String text = "Link on page 1: https://example.com/page\n"
                + "Link on page 2: https://www.kompile.ai/docs";

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "annotated.pdf");
        meta.put("extractionType", "annotations");
        meta.put("source", "/tmp/annotated.pdf");

        ExtractionResult result = extractor.extract(doc(text, meta));

        List<ExtractedEntity> resources = entitiesOfType(result, "EXTERNAL_RESOURCE");
        assertEquals(2, resources.size(), "One EXTERNAL_RESOURCE per unique URL");

        Set<String> urls = resources.stream()
                .map(e -> e.properties().get("url"))
                .collect(Collectors.toSet());
        assertTrue(urls.contains("https://example.com/page"));
        assertTrue(urls.contains("https://www.kompile.ai/docs"));

        List<ExtractedRelation> links = relationsOfType(result, "HYPERLINKS_TO");
        assertEquals(2, links.size(), "One HYPERLINKS_TO per unique URL");
        links.forEach(r -> assertNotNull(r.type()));
    }

    @Test
    void extract_annotationsDuplicateUrls_deduplicatedToSingleEntity() {
        // The same URL appears twice — should produce only one EXTERNAL_RESOURCE
        String text = "https://example.com/dup\nSome text https://example.com/dup";

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "dup.pdf");
        meta.put("extractionType", "annotations");
        meta.put("source", "/tmp/dup.pdf");

        ExtractionResult result = extractor.extract(doc(text, meta));

        List<ExtractedEntity> resources = entitiesOfType(result, "EXTERNAL_RESOURCE");
        assertEquals(1, resources.size(),
                "Duplicate URLs must be deduplicated to a single EXTERNAL_RESOURCE");
    }

    @Test
    void extract_nonAnnotationsDocument_createsExternalResourceFromBodyText() {
        // extractionType is NOT "annotations" — URLs in body text are now extracted
        String text = "Visit https://example.com for more info.";

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "doc.pdf");
        meta.put("extractionType", "fullDocument");
        meta.put("source", "/tmp/doc.pdf");

        ExtractionResult result = extractor.extract(doc(text, meta));

        List<ExtractedEntity> resources = entitiesOfType(result, "EXTERNAL_RESOURCE");
        assertEquals(1, resources.size(),
                "EXTERNAL_RESOURCE should be created from body text URLs for non-annotations extraction types");
        assertEquals("https://example.com", resources.get(0).properties().get("url"));
    }

    // ── extract() — PDF_SECTION from bookmarks ────────────────────────────

    @Test
    void extract_bookmarksDocument_createsPdfSectionAndHasSectionRelation() {
        String text = "Introduction\n  Background\n  Motivation\nChapter 1\nConclusion";

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "bookmarked.pdf");
        meta.put("extractionType", "bookmarks");
        meta.put("source", "/tmp/bookmarked.pdf");

        ExtractionResult result = extractor.extract(doc(text, meta));

        List<ExtractedEntity> sections = entitiesOfType(result, "PDF_SECTION");
        assertFalse(sections.isEmpty(), "Should create PDF_SECTION entities from bookmarks");

        Set<String> names = sections.stream().map(ExtractedEntity::name).collect(Collectors.toSet());
        assertTrue(names.contains("Introduction"), "Must extract 'Introduction' bookmark");
        assertTrue(names.contains("Chapter 1"), "Must extract 'Chapter 1' bookmark");
        assertTrue(names.contains("Conclusion"), "Must extract 'Conclusion' bookmark");

        // Top-level sections get HAS_SECTION, nested ones get SUBSECTION_OF
        List<ExtractedRelation> hasSections = relationsOfType(result, "HAS_SECTION");
        List<ExtractedRelation> subsections = relationsOfType(result, "SUBSECTION_OF");
        assertEquals(3, hasSections.size(),
                "Top-level bookmarks (Introduction, Chapter 1, Conclusion) should have HAS_SECTION");
        assertEquals(2, subsections.size(),
                "Nested bookmarks (Background, Motivation) should have SUBSECTION_OF");
        hasSections.forEach(r -> assertNotNull(r.type()));
        subsections.forEach(r -> assertNotNull(r.type()));
    }

    @Test
    void extract_bookmarkSectionId_isDeterministic() {
        String text = "Introduction\nChapter 1";

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "doc.pdf");
        meta.put("extractionType", "bookmarks");
        meta.put("source", "/tmp/doc.pdf");

        ExtractionResult r1 = extractor.extract(doc(text, meta));
        ExtractionResult r2 = extractor.extract(doc(text, meta));

        List<String> ids1 = entityIdsByType(r1, "PDF_SECTION");
        List<String> ids2 = entityIdsByType(r2, "PDF_SECTION");
        assertEquals(ids1, ids2, "Section entity IDs must be deterministic");
    }

    @Test
    void extract_nonBookmarksDocument_doesNotCreatePdfSection() {
        String text = "Introduction\nChapter 1";

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "doc.pdf");
        meta.put("extractionType", "fullDocument");
        meta.put("source", "/tmp/doc.pdf");

        ExtractionResult result = extractor.extract(doc(text, meta));
        List<ExtractedEntity> sections = entitiesOfType(result, "PDF_SECTION");
        assertTrue(sections.isEmpty(),
                "PDF_SECTION must not be created when extractionType != bookmarks");
    }

    // ── extract() — FORM_FIELD from form fields ────────────────────────────

    @Test
    void extract_formFieldsDocument_createsFormFieldEntitiesAndHasFormFieldRelations() {
        String text = "Field: FirstName (text) = John\n"
                + "Field: LastName (text) = Doe\n"
                + "Field: Agree (checkbox) = true";

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "form.pdf");
        meta.put("extractionType", "formFields");
        meta.put("source", "/tmp/form.pdf");

        ExtractionResult result = extractor.extract(doc(text, meta));

        List<ExtractedEntity> fields = entitiesOfType(result, "FORM_FIELD");
        assertEquals(3, fields.size(), "One FORM_FIELD per parsed field line");

        Set<String> names = fields.stream().map(ExtractedEntity::name).collect(Collectors.toSet());
        assertTrue(names.contains("FirstName"));
        assertTrue(names.contains("LastName"));
        assertTrue(names.contains("Agree"));

        // Verify field type is stored in properties
        ExtractedEntity checkBox = fields.stream()
                .filter(e -> "Agree".equals(e.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Agree field not found"));
        assertEquals("checkbox", checkBox.properties().get("fieldType"));

        // Verify HAS_FORM_FIELD relations
        List<ExtractedRelation> formRelations = relationsOfType(result, "HAS_FORM_FIELD");
        assertEquals(3, formRelations.size(), "One HAS_FORM_FIELD per FORM_FIELD");
        formRelations.forEach(r -> assertNotNull(r.type()));
    }

    @Test
    void extract_formFieldWithValue_valueStoredInProperties() {
        // Verify that a non-empty field value is stored in the entity's properties.
        // This also implicitly tests the branch: if (!fieldValue.isEmpty()) put("value", ...).
        String text = "Field: UserName (text) = jdoe\nField: Score (number) = 42";

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "form.pdf");
        meta.put("extractionType", "formFields");
        meta.put("source", "/tmp/form.pdf");

        ExtractionResult result = extractor.extract(doc(text, meta));

        List<ExtractedEntity> fields = entitiesOfType(result, "FORM_FIELD");
        assertEquals(2, fields.size());

        ExtractedEntity userField = fields.stream()
                .filter(e -> "UserName".equals(e.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("UserName field not found"));
        assertEquals("jdoe", userField.properties().get("value"),
                "Non-empty field value must be stored in properties");
        assertEquals("text", userField.properties().get("fieldType"));

        ExtractedEntity scoreField = fields.stream()
                .filter(e -> "Score".equals(e.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Score field not found"));
        assertEquals("42", scoreField.properties().get("value"));
        assertEquals("number", scoreField.properties().get("fieldType"));
    }

    @Test
    void extract_nonFormFieldsDocument_doesNotCreateFormField() {
        String text = "Field: SomeField (text) = someValue";

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "doc.pdf");
        meta.put("extractionType", "fullDocument");
        meta.put("source", "/tmp/doc.pdf");

        ExtractionResult result = extractor.extract(doc(text, meta));
        List<ExtractedEntity> fields = entitiesOfType(result, "FORM_FIELD");
        assertTrue(fields.isEmpty(),
                "FORM_FIELD must not be created when extractionType != formFields");
    }

    // ── extract() — null/empty metadata ───────────────────────────────────

    @Test
    void extract_documentWithMinimalMetadata_returnsPdfDocumentEntityOnly() {
        // A document with no recognized PDF metadata should still produce a PDF_DOCUMENT
        // entity but no PERSON, ORGANIZATION, TOPIC, etc.
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("source", "/tmp/unknown.pdf");

        ExtractionResult result = extractor.extract(doc(meta));

        assertNotNull(result);
        List<ExtractedEntity> entities = result.entities();
        assertFalse(entities.isEmpty(), "Should always have at least the PDF_DOCUMENT entity");

        singleEntityOfType(result, "PDF_DOCUMENT"); // assert exactly one

        assertTrue(entitiesOfType(result, "PERSON").isEmpty(), "No PERSON without author/creator");
        assertTrue(entitiesOfType(result, "ORGANIZATION").isEmpty(), "No ORGANIZATION without producer");
        assertTrue(entitiesOfType(result, "TOPIC").isEmpty(), "No TOPIC without keywords");
        assertTrue(result.relations().isEmpty(), "No relations when no metadata extracted");
    }

    @Test
    void extract_emptyMetadataMap_producesOnlyPdfDocumentEntityWithNoRelations() {
        // With an empty (but non-null) metadata map, the extractor runs the full body:
        // produces one PDF_DOCUMENT entity ("Untitled PDF") and zero relations.
        // This test guards against NPE and verifies graceful degradation.
        Document d = doc(Map.of());
        ExtractionResult result = extractor.extract(d);
        assertNotNull(result);
        assertNotNull(result.entities());
        assertNotNull(result.relations());
        // At least the PDF_DOCUMENT entity should be produced
        assertFalse(result.entities().isEmpty(), "Should produce at least the PDF_DOCUMENT entity");
        singleEntityOfType(result, "PDF_DOCUMENT");
        assertTrue(result.relations().isEmpty(), "No relations expected with empty metadata");
    }

    // ── Entity ID determinism — same input yields same IDs ─────────────────

    @Test
    void extract_calledTwiceWithIdenticalInput_yieldsIdenticalEntityIds() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "stable.pdf");
        meta.put("title", "Stability Test");
        meta.put("author", "Iris Newton");
        meta.put("producer", "PdfKit 3.0");
        meta.put("keywords", "determinism, testing");
        meta.put("source", "/tmp/stable.pdf");

        ExtractionResult r1 = extractor.extract(doc(meta));
        ExtractionResult r2 = extractor.extract(doc(meta));

        // Collect IDs from both runs and assert they are identical sets
        Set<String> ids1 = r1.entities().stream()
                .map(ExtractedEntity::id)
                .collect(Collectors.toSet());
        Set<String> ids2 = r2.entities().stream()
                .map(ExtractedEntity::id)
                .collect(Collectors.toSet());

        assertEquals(ids1, ids2,
                "Identical inputs must produce identical entity IDs across invocations");
    }

    @Test
    void extract_pdfDocumentEntityId_isStableAcrossCallsForSameSource() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("source", "/data/shared.pdf");
        meta.put("title", "Shared Document");

        ExtractionResult r1 = extractor.extract(doc(meta));
        ExtractionResult r2 = extractor.extract(doc(meta));

        String docId1 = singleEntityOfType(r1, "PDF_DOCUMENT").id();
        String docId2 = singleEntityOfType(r2, "PDF_DOCUMENT").id();
        assertEquals(docId1, docId2, "PDF_DOCUMENT entity ID must be stable for the same source");
    }

    // ── extractBatch() — deduplication ─────────────────────────────────────

    @Test
    void extractBatch_sameAuthorInTwoPdfs_producesSinglePersonEntity() {
        Map<String, Object> meta1 = new LinkedHashMap<>();
        meta1.put("documentType", "PDF Document");
        meta1.put("fileName", "doc1.pdf");
        meta1.put("author", "Jane Author");
        meta1.put("source", "/tmp/doc1.pdf");

        Map<String, Object> meta2 = new LinkedHashMap<>();
        meta2.put("documentType", "PDF Document");
        meta2.put("fileName", "doc2.pdf");
        meta2.put("author", "Jane Author");  // same author
        meta2.put("source", "/tmp/doc2.pdf");

        ExtractionResult batch = extractor.extractBatch(List.of(doc(meta1), doc(meta2)));

        // Two PDF_DOCUMENT entities, but only one PERSON
        List<ExtractedEntity> docs = entitiesOfType(batch, "PDF_DOCUMENT");
        assertEquals(2, docs.size(), "Two separate PDF_DOCUMENT entities");

        List<ExtractedEntity> persons = entitiesOfType(batch, "PERSON");
        assertEquals(1, persons.size(),
                "Same author across two PDFs must be deduplicated to a single PERSON");

        // Two AUTHORED_BY relations (one per document), both pointing to the same person
        List<ExtractedRelation> authored = relationsOfType(batch, "AUTHORED_BY");
        assertEquals(2, authored.size(), "One AUTHORED_BY per source document");
        String personId = persons.get(0).id();
        assertTrue(authored.stream().allMatch(r -> personId.equals(r.target())),
                "Both AUTHORED_BY relations must target the same PERSON");
    }

    @Test
    void extractBatch_differentAuthors_producesMultiplePersonEntities() {
        Map<String, Object> meta1 = new LinkedHashMap<>();
        meta1.put("documentType", "PDF Document");
        meta1.put("fileName", "doc1.pdf");
        meta1.put("author", "Author One");
        meta1.put("source", "/tmp/doc1.pdf");

        Map<String, Object> meta2 = new LinkedHashMap<>();
        meta2.put("documentType", "PDF Document");
        meta2.put("fileName", "doc2.pdf");
        meta2.put("author", "Author Two");
        meta2.put("source", "/tmp/doc2.pdf");

        ExtractionResult batch = extractor.extractBatch(List.of(doc(meta1), doc(meta2)));

        List<ExtractedEntity> persons = entitiesOfType(batch, "PERSON");
        assertEquals(2, persons.size(), "Two distinct authors must produce two PERSON entities");
    }

    @Test
    void extractBatch_sameProducerInMultiplePdfs_producesSingleOrganizationEntity() {
        Map<String, Object> meta1 = new LinkedHashMap<>();
        meta1.put("documentType", "PDF Document");
        meta1.put("fileName", "doc1.pdf");
        meta1.put("producer", "Adobe Acrobat");
        meta1.put("source", "/tmp/doc1.pdf");

        Map<String, Object> meta2 = new LinkedHashMap<>();
        meta2.put("documentType", "PDF Document");
        meta2.put("fileName", "doc2.pdf");
        meta2.put("producer", "Adobe Acrobat");  // same producer
        meta2.put("source", "/tmp/doc2.pdf");

        ExtractionResult batch = extractor.extractBatch(List.of(doc(meta1), doc(meta2)));

        List<ExtractedEntity> orgs = entitiesOfType(batch, "ORGANIZATION");
        assertEquals(1, orgs.size(),
                "Same producer across multiple PDFs must be deduplicated to a single ORGANIZATION");
    }

    @Test
    void extractBatch_sameKeywordInMultiplePdfs_producesSingleTopicEntity() {
        Map<String, Object> meta1 = new LinkedHashMap<>();
        meta1.put("documentType", "PDF Document");
        meta1.put("fileName", "doc1.pdf");
        meta1.put("keywords", "machine learning");
        meta1.put("source", "/tmp/doc1.pdf");

        Map<String, Object> meta2 = new LinkedHashMap<>();
        meta2.put("documentType", "PDF Document");
        meta2.put("fileName", "doc2.pdf");
        meta2.put("keywords", "machine learning");  // same keyword
        meta2.put("source", "/tmp/doc2.pdf");

        ExtractionResult batch = extractor.extractBatch(List.of(doc(meta1), doc(meta2)));

        List<ExtractedEntity> topics = entitiesOfType(batch, "TOPIC");
        assertEquals(1, topics.size(),
                "Same keyword across multiple PDFs must be deduplicated to a single TOPIC");
    }

    @Test
    void extractBatch_emptyList_returnsEmptyResult() {
        ExtractionResult result = extractor.extractBatch(List.of());
        assertNotNull(result);
        assertTrue(result.entities().isEmpty());
        assertTrue(result.relations().isEmpty());
    }

    // ── supportedDocumentTypes() ─────────────────────────────────────────

    @Test
    void supportedDocumentTypes_containsPdf() {
        List<String> types = extractor.supportedDocumentTypes();
        assertNotNull(types);
        assertFalse(types.isEmpty());
        assertTrue(types.contains("pdf"),
                "supportedDocumentTypes() must include \"pdf\"");
    }

    // ── ExtractionResult schema version ──────────────────────────────────

    @Test
    void extract_result_hasExpectedSchemaVersion() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("source", "/tmp/doc.pdf");

        ExtractionResult result = extractor.extract(doc(meta));
        assertEquals("kompile-graph-extraction/v1", result.schema());
    }

    // ── Relation provenance and structure ─────────────────────────────────

    @Test
    void extract_allRelations_haveNonNullType() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "full.pdf");
        meta.put("author", "Test Author");
        meta.put("producer", "TestPDF 1.0");
        meta.put("keywords", "test, data");
        meta.put("source", "/tmp/full.pdf");

        ExtractionResult result = extractor.extract(doc(meta));

        result.relations().forEach(r ->
                assertNotNull(r.type(),
                        "All relations must have a non-null type")
        );
    }

    @Test
    void extract_allRelations_haveSourceDocEntityIdAsSource() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("title", "Relation Source Test");
        meta.put("author", "Test Author");
        meta.put("producer", "TestPDF");
        meta.put("keywords", "keyword");
        meta.put("source", "/tmp/relsrc.pdf");

        ExtractionResult result = extractor.extract(doc(meta));

        String expectedDocId = singleEntityOfType(result, "PDF_DOCUMENT").id();
        result.relations().forEach(r ->
                assertEquals(expectedDocId, r.source(),
                        "All relations must originate from the PDF_DOCUMENT entity")
        );
    }

    // ── PDF_DOCUMENT entity uses title if available, falls back to fileName ──

    @Test
    void extract_documentWithTitle_usesTitle() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("title", "My Important Report");
        meta.put("fileName", "doc.pdf");
        meta.put("source", "/tmp/doc.pdf");

        ExtractionResult result = extractor.extract(doc(meta));
        ExtractedEntity docEntity = singleEntityOfType(result, "PDF_DOCUMENT");
        assertEquals("My Important Report", docEntity.name());
    }

    @Test
    void extract_documentWithoutTitle_usesFileName() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "untitled-report.pdf");
        meta.put("source", "/tmp/untitled-report.pdf");

        ExtractionResult result = extractor.extract(doc(meta));
        ExtractedEntity docEntity = singleEntityOfType(result, "PDF_DOCUMENT");
        assertEquals("untitled-report.pdf", docEntity.name());
    }

    @Test
    void extract_documentWithoutTitleOrFileName_usesUntitledPdf() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("source", "/tmp/nodisplay.pdf");

        ExtractionResult result = extractor.extract(doc(meta));
        ExtractedEntity docEntity = singleEntityOfType(result, "PDF_DOCUMENT");
        assertEquals("Untitled PDF", docEntity.name());
    }

    // ── Confidence values ─────────────────────────────────────────────────

    @Test
    void extract_confidenceValues_matchExpectedByEntityType() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "conf.pdf");
        meta.put("author", "Conf Author");
        meta.put("creator", "Conf Creator");
        meta.put("producer", "Conf Producer");
        meta.put("keywords", "confidence");
        meta.put("source", "/tmp/conf.pdf");

        ExtractionResult result = extractor.extract(doc(meta));

        // PDF_DOCUMENT: 1.0
        singleEntityOfType(result, "PDF_DOCUMENT");
        assertEquals(1.0,
                singleEntityOfType(result, "PDF_DOCUMENT").confidence(), 1e-9,
                "PDF_DOCUMENT confidence should be 1.0");

        // PERSON (from author): 0.9
        List<ExtractedEntity> persons = entitiesOfType(result, "PERSON");
        Optional<ExtractedEntity> authorPerson = persons.stream()
                .filter(e -> "author".equals(e.properties().get("source_field")))
                .findFirst();
        assertTrue(authorPerson.isPresent());
        assertEquals(0.9, authorPerson.get().confidence(), 1e-9,
                "Author PERSON confidence should be 0.9");

        // ORGANIZATION: 0.7
        assertEquals(0.7, singleEntityOfType(result, "ORGANIZATION").confidence(), 1e-9,
                "ORGANIZATION confidence should be 0.7");

        // TOPIC: 0.8
        assertEquals(0.8, entitiesOfType(result, "TOPIC").get(0).confidence(), 1e-9,
                "TOPIC confidence should be 0.8");
    }

    // --- linkCount / fieldCount / lastModified metadata ---

    @Test
    void annotationsDocStoreLinkCountOnDocEntity() {
        Map<String, Object> meta = pdfMeta("report.pdf", "Report", "/docs/report.pdf");
        meta.put("extractionType", "annotations");
        meta.put("linkCount", 5);
        Document doc = new Document("Link on page 1: https://example.com", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity pdfDoc = singleEntityOfType(result, "PDF_DOCUMENT");
        assertEquals("5", pdfDoc.properties().get("linkCount"),
                "linkCount from annotation sub-document should be stored on PDF_DOCUMENT entity");
    }

    @Test
    void formFieldsDocStoreFieldCountOnDocEntity() {
        Map<String, Object> meta = pdfMeta("form.pdf", "Form", "/docs/form.pdf");
        meta.put("extractionType", "formFields");
        meta.put("fieldCount", 10);
        Document doc = new Document("Field: name (Text) = John", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity pdfDoc = singleEntityOfType(result, "PDF_DOCUMENT");
        assertEquals("10", pdfDoc.properties().get("fieldCount"),
                "fieldCount from form sub-document should be stored on PDF_DOCUMENT entity");
    }

    @Test
    void lastModifiedStoredWhenModificationDateAbsent() {
        Map<String, Object> meta = pdfMeta("old.pdf", "Old Doc", "/docs/old.pdf");
        meta.put("lastModified", "2025-01-15T10:00:00Z");
        // no modificationDate
        Document doc = new Document("Content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity pdfDoc = singleEntityOfType(result, "PDF_DOCUMENT");
        assertEquals("2025-01-15T10:00:00Z", pdfDoc.properties().get("lastModified"));
    }

    @Test
    void lastModifiedNotOverrideModificationDate() {
        Map<String, Object> meta = pdfMeta("doc.pdf", "Doc", "/docs/doc.pdf");
        meta.put("modificationDate", "2025-03-01");
        meta.put("lastModified", "2025-01-15");
        Document doc = new Document("Content", meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity pdfDoc = singleEntityOfType(result, "PDF_DOCUMENT");
        assertEquals("2025-03-01", pdfDoc.properties().get("modificationDate"),
                "modificationDate should remain when present");
    }

    // --- Enriched Annotation Properties ---

    @Test
    void annotationSubtypeStoredOnEntity() {
        Map<String, Object> meta = pdfMeta("annotated.pdf", "Annotated", "/docs/annotated.pdf");
        meta.put("extractionType", "annotations");
        String content = "Annotation on page 3: Review this section [subtype=Highlight] [author=John Doe] [modified=2025-06-01T10:00:00Z]";
        Document doc = new Document(content, meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity annot = result.entities().stream()
                .filter(e -> "PDF_ANNOTATION".equals(e.type()))
                .findFirst().orElseThrow();
        assertEquals("Highlight", annot.properties().get("subtype"));
        assertEquals("John Doe", annot.properties().get("author"));
        assertEquals("2025-06-01T10:00:00Z", annot.properties().get("modifiedDate"));
        assertEquals("3", annot.properties().get("pageNumber"));
    }

    @Test
    void annotationWithOnlySubtypeParsesCorrectly() {
        Map<String, Object> meta = pdfMeta("partial.pdf", "Partial", "/docs/partial.pdf");
        meta.put("extractionType", "annotations");
        String content = "Annotation on page 1: Some note [subtype=Text]";
        Document doc = new Document(content, meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity annot = result.entities().stream()
                .filter(e -> "PDF_ANNOTATION".equals(e.type()))
                .findFirst().orElseThrow();
        assertEquals("Text", annot.properties().get("subtype"));
        assertNull(annot.properties().get("author"), "No author should be null");
        assertNull(annot.properties().get("modifiedDate"), "No date should be null");
    }

    @Test
    void annotationWithoutEnrichedFieldsStillWorks() {
        Map<String, Object> meta = pdfMeta("plain.pdf", "Plain", "/docs/plain.pdf");
        meta.put("extractionType", "annotations");
        String content = "Annotation on page 5: A simple comment";
        Document doc = new Document(content, meta);

        ExtractionResult result = extractor.extract(doc);

        ExtractedEntity annot = result.entities().stream()
                .filter(e -> "PDF_ANNOTATION".equals(e.type()))
                .findFirst().orElseThrow();
        assertEquals("A simple comment", annot.properties().get("text"));
        assertEquals("5", annot.properties().get("pageNumber"));
        assertNull(annot.properties().get("subtype"));
        assertNull(annot.properties().get("author"));
    }

    // ── extract() — ANNOTATED_BY cross-link ──────────────────────────────

    @Test
    void annotationWithAuthor_createsAnnotatedByRelationToPersonEntity() {
        Map<String, Object> meta = pdfMeta("review.pdf", "Review Doc", "/docs/review.pdf");
        meta.put("extractionType", "annotations");
        String content = "Annotation on page 2: Please clarify this. [subtype=Text] [author=Alice Smith] [modified=2025-04-01T09:00:00Z]";
        Document d = new Document(content, meta);

        ExtractionResult result = extractor.extract(d);

        // Must have a PDF_ANNOTATION entity
        ExtractedEntity annot = result.entities().stream()
                .filter(e -> "PDF_ANNOTATION".equals(e.type()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No PDF_ANNOTATION entity found"));
        assertEquals("Alice Smith", annot.properties().get("author"));

        // Must have a PERSON entity for the annotation author
        List<ExtractedEntity> persons = entitiesOfType(result, "PERSON");
        Optional<ExtractedEntity> annotAuthorPerson = persons.stream()
                .filter(p -> "Alice Smith".equals(p.name()))
                .findFirst();
        assertTrue(annotAuthorPerson.isPresent(), "PERSON entity for annotation author must exist");
        assertEquals("annotation_author", annotAuthorPerson.get().properties().get("source_field"));

        // Must have an ANNOTATED_BY relation from the annotation to the person
        List<ExtractedRelation> annotatedBy = relationsOfType(result, "ANNOTATED_BY");
        assertEquals(1, annotatedBy.size(), "One ANNOTATED_BY relation expected");
        assertEquals(annot.id(), annotatedBy.get(0).source(), "ANNOTATED_BY source must be the annotation entity");
        assertEquals(annotAuthorPerson.get().id(), annotatedBy.get(0).target(), "ANNOTATED_BY target must be the PERSON entity");
    }

    @Test
    void annotationWithoutAuthor_doesNotCreateAnnotatedByRelation() {
        Map<String, Object> meta = pdfMeta("plain.pdf", "Plain Doc", "/docs/plain.pdf");
        meta.put("extractionType", "annotations");
        String content = "Annotation on page 1: A note without an author";
        Document d = new Document(content, meta);

        ExtractionResult result = extractor.extract(d);

        List<ExtractedRelation> annotatedBy = relationsOfType(result, "ANNOTATED_BY");
        assertTrue(annotatedBy.isEmpty(), "No ANNOTATED_BY relation expected when annotation has no author");
    }

    @Test
    void multipleAnnotationsWithSameAuthor_sharesSinglePersonEntity() {
        Map<String, Object> meta = pdfMeta("shared-author.pdf", "Shared Author", "/docs/shared-author.pdf");
        meta.put("extractionType", "annotations");
        String content =
                "Annotation on page 1: First note [subtype=Text] [author=Bob Jones]\n" +
                "Annotation on page 3: Second note [subtype=Highlight] [author=Bob Jones]";
        Document d = new Document(content, meta);

        ExtractionResult result = extractor.extract(d);

        // Two annotations
        List<ExtractedEntity> annots = entitiesOfType(result, "PDF_ANNOTATION");
        assertEquals(2, annots.size());

        // One shared PERSON entity for the author
        List<ExtractedEntity> persons = entitiesOfType(result, "PERSON");
        long annotAuthorCount = persons.stream()
                .filter(p -> "annotation_author".equals(p.properties().get("source_field")))
                .count();
        assertEquals(1, annotAuthorCount, "Same annotation author must be deduplicated to a single PERSON entity");

        // Two ANNOTATED_BY relations
        List<ExtractedRelation> annotatedBy = relationsOfType(result, "ANNOTATED_BY");
        assertEquals(2, annotatedBy.size(), "One ANNOTATED_BY per annotation with an author");
    }

    @Test
    void annotationAuthorAlsoDocumentAuthor_mergesIntoSinglePersonEntity() {
        // When the annotation author name exactly matches the document author,
        // the addEntity merge logic must result in only one PERSON entity.
        Map<String, Object> meta = pdfMeta("merged.pdf", "Merged", "/docs/merged.pdf");
        meta.put("author", "Carol White");
        meta.put("extractionType", "annotations");
        String content = "Annotation on page 1: A note [subtype=Text] [author=Carol White]";
        Document d = new Document(content, meta);

        ExtractionResult result = extractor.extract(d);

        long carolCount = result.entities().stream()
                .filter(e -> "PERSON".equals(e.type()) && "Carol White".equals(e.name()))
                .count();
        assertEquals(1, carolCount, "Document author and annotation author with same name must be a single PERSON entity");
    }

    // ── extract() — EMBEDDED_FILE entities ───────────────────────────────

    @Test
    void embeddedFilesMetadata_createsEmbeddedFileEntitiesAndRelations() {
        Map<String, Object> meta = pdfMeta("portfolio.pdf", "Portfolio", "/docs/portfolio.pdf");
        List<Map<String, String>> embedded = new ArrayList<>();
        Map<String, String> f1 = new HashMap<>();
        f1.put("name", "attachment.docx");
        f1.put("mimeType", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        f1.put("size", "102400");
        Map<String, String> f2 = new HashMap<>();
        f2.put("name", "data.csv");
        f2.put("mimeType", "text/csv");
        f2.put("size", "2048");
        embedded.add(f1);
        embedded.add(f2);
        meta.put("pdf.embeddedFiles", embedded);
        Document d = new Document("", meta);

        ExtractionResult result = extractor.extract(d);

        List<ExtractedEntity> efEntities = entitiesOfType(result, "EMBEDDED_FILE");
        assertEquals(2, efEntities.size(), "One EMBEDDED_FILE entity per embedded file");

        Set<String> names = efEntities.stream().map(ExtractedEntity::name).collect(Collectors.toSet());
        assertTrue(names.contains("attachment.docx"), "attachment.docx must be an EMBEDDED_FILE entity");
        assertTrue(names.contains("data.csv"), "data.csv must be an EMBEDDED_FILE entity");

        // Verify properties stored on one of the entities
        ExtractedEntity docxEntity = efEntities.stream()
                .filter(e -> "attachment.docx".equals(e.name()))
                .findFirst().orElseThrow();
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docxEntity.properties().get("mimeType"));
        assertEquals("102400", docxEntity.properties().get("size"));

        // Each embedded file must have a HAS_EMBEDDED_FILE relation
        List<ExtractedRelation> hasEmbedded = relationsOfType(result, "HAS_EMBEDDED_FILE");
        assertEquals(2, hasEmbedded.size(), "One HAS_EMBEDDED_FILE per embedded file");
        String docEntityId = singleEntityOfType(result, "PDF_DOCUMENT").id();
        hasEmbedded.forEach(r ->
                assertEquals(docEntityId, r.source(), "HAS_EMBEDDED_FILE must originate from the PDF_DOCUMENT entity"));
    }

    @Test
    void embeddedFileWithoutMimeTypeOrSize_createsEntityWithNameOnly() {
        Map<String, Object> meta = pdfMeta("sparse.pdf", "Sparse", "/docs/sparse.pdf");
        List<Map<String, String>> embedded = new ArrayList<>();
        Map<String, String> f1 = new HashMap<>();
        f1.put("name", "readme.txt");
        // no mimeType, no size
        embedded.add(f1);
        meta.put("pdf.embeddedFiles", embedded);
        Document d = new Document("", meta);

        ExtractionResult result = extractor.extract(d);

        List<ExtractedEntity> efEntities = entitiesOfType(result, "EMBEDDED_FILE");
        assertEquals(1, efEntities.size());
        ExtractedEntity entity = efEntities.get(0);
        assertEquals("readme.txt", entity.name());
        assertNull(entity.properties().get("mimeType"), "mimeType should be absent");
        assertNull(entity.properties().get("size"), "size should be absent");
    }

    @Test
    void embeddedFileEntityId_isDeterministic() {
        Map<String, Object> meta = pdfMeta("stable.pdf", "Stable", "/docs/stable.pdf");
        List<Map<String, String>> embedded = new ArrayList<>();
        Map<String, String> f1 = new HashMap<>();
        f1.put("name", "chart.xlsx");
        embedded.add(f1);
        meta.put("pdf.embeddedFiles", embedded);

        ExtractionResult r1 = extractor.extract(new Document("", meta));
        ExtractionResult r2 = extractor.extract(new Document("", meta));

        String id1 = entitiesOfType(r1, "EMBEDDED_FILE").get(0).id();
        String id2 = entitiesOfType(r2, "EMBEDDED_FILE").get(0).id();
        assertEquals(id1, id2, "EMBEDDED_FILE entity ID must be deterministic");
    }

    @Test
    void noEmbeddedFilesMetadata_doesNotCreateEmbeddedFileEntities() {
        Map<String, Object> meta = pdfMeta("normal.pdf", "Normal", "/docs/normal.pdf");
        // No pdf.embeddedFiles key
        ExtractionResult result = extractor.extract(new Document("", meta));
        assertTrue(entitiesOfType(result, "EMBEDDED_FILE").isEmpty(),
                "No EMBEDDED_FILE entities expected when metadata key is absent");
    }

    // ── extract() — PDF_SIGNATURE entities ───────────────────────────────

    @Test
    void signaturesMetadata_createsPdfSignatureEntitiesAndRelations() {
        Map<String, Object> meta = pdfMeta("signed.pdf", "Signed Contract", "/docs/signed.pdf");
        List<Map<String, String>> sigs = new ArrayList<>();
        Map<String, String> sig1 = new HashMap<>();
        sig1.put("name", "Jane Doe");
        sig1.put("reason", "I approve this document");
        sig1.put("location", "New York");
        sig1.put("signDate", "1748736000000");
        sigs.add(sig1);
        meta.put("pdf.signatures", sigs);
        Document d = new Document("", meta);

        ExtractionResult result = extractor.extract(d);

        List<ExtractedEntity> sigEntities = entitiesOfType(result, "PDF_SIGNATURE");
        assertEquals(1, sigEntities.size(), "One PDF_SIGNATURE entity expected");

        ExtractedEntity sigEntity = sigEntities.get(0);
        assertEquals("Jane Doe", sigEntity.name());
        assertEquals("I approve this document", sigEntity.properties().get("reason"));
        assertEquals("New York", sigEntity.properties().get("location"));
        assertEquals("1748736000000", sigEntity.properties().get("signDate"));
        assertEquals(0.95, sigEntity.confidence(), 1e-9);

        // HAS_SIGNATURE relation from document to signature
        List<ExtractedRelation> hasSig = relationsOfType(result, "HAS_SIGNATURE");
        assertEquals(1, hasSig.size(), "One HAS_SIGNATURE relation expected");
        String docEntityId = singleEntityOfType(result, "PDF_DOCUMENT").id();
        assertEquals(docEntityId, hasSig.get(0).source());
        assertEquals(sigEntity.id(), hasSig.get(0).target());

        // SIGNED_BY relation from signature to person (signer name)
        List<ExtractedRelation> signedBy = relationsOfType(result, "SIGNED_BY");
        assertEquals(1, signedBy.size(), "One SIGNED_BY relation expected when signature has a name");
        assertEquals(sigEntity.id(), signedBy.get(0).source());
        List<ExtractedEntity> persons = entitiesOfType(result, "PERSON");
        Optional<ExtractedEntity> signerPerson = persons.stream()
                .filter(p -> "Jane Doe".equals(p.name()))
                .findFirst();
        assertTrue(signerPerson.isPresent(), "PERSON entity for signer must exist");
        assertEquals(signerPerson.get().id(), signedBy.get(0).target());
    }

    @Test
    void signatureWithoutName_usesDefaultDisplayName_noSignedByRelation() {
        Map<String, Object> meta = pdfMeta("unsigned-name.pdf", "No Name Sig", "/docs/noname.pdf");
        List<Map<String, String>> sigs = new ArrayList<>();
        Map<String, String> sig1 = new HashMap<>();
        sig1.put("reason", "Approved");
        // no name
        sigs.add(sig1);
        meta.put("pdf.signatures", sigs);
        Document d = new Document("", meta);

        ExtractionResult result = extractor.extract(d);

        List<ExtractedEntity> sigEntities = entitiesOfType(result, "PDF_SIGNATURE");
        assertEquals(1, sigEntities.size());
        assertEquals("Signature 1", sigEntities.get(0).name(), "Default name 'Signature N' must be used");

        // No SIGNED_BY when there is no signer name
        List<ExtractedRelation> signedBy = relationsOfType(result, "SIGNED_BY");
        assertTrue(signedBy.isEmpty(), "No SIGNED_BY relation expected when signature has no name");
    }

    @Test
    void multipleSignatures_createsMultipleEntities() {
        Map<String, Object> meta = pdfMeta("multi-sig.pdf", "Multi Sig", "/docs/multi-sig.pdf");
        List<Map<String, String>> sigs = new ArrayList<>();
        Map<String, String> sig1 = new HashMap<>();
        sig1.put("name", "Signer One");
        Map<String, String> sig2 = new HashMap<>();
        sig2.put("name", "Signer Two");
        sigs.add(sig1);
        sigs.add(sig2);
        meta.put("pdf.signatures", sigs);
        Document d = new Document("", meta);

        ExtractionResult result = extractor.extract(d);

        List<ExtractedEntity> sigEntities = entitiesOfType(result, "PDF_SIGNATURE");
        assertEquals(2, sigEntities.size(), "Two PDF_SIGNATURE entities expected");

        List<ExtractedRelation> hasSig = relationsOfType(result, "HAS_SIGNATURE");
        assertEquals(2, hasSig.size(), "Two HAS_SIGNATURE relations expected");

        List<ExtractedRelation> signedBy = relationsOfType(result, "SIGNED_BY");
        assertEquals(2, signedBy.size(), "Two SIGNED_BY relations expected");
    }

    @Test
    void noSignaturesMetadata_doesNotCreatePdfSignatureEntities() {
        Map<String, Object> meta = pdfMeta("unsigned.pdf", "Unsigned", "/docs/unsigned.pdf");
        ExtractionResult result = extractor.extract(new Document("", meta));
        assertTrue(entitiesOfType(result, "PDF_SIGNATURE").isEmpty(),
                "No PDF_SIGNATURE entities expected when metadata key is absent");
    }

    @Test
    void signatureEntityId_isDeterministic() {
        Map<String, Object> meta = pdfMeta("idstable.pdf", "ID Stable", "/docs/idstable.pdf");
        List<Map<String, String>> sigs = new ArrayList<>();
        Map<String, String> sig1 = new HashMap<>();
        sig1.put("name", "Stable Signer");
        sigs.add(sig1);
        meta.put("pdf.signatures", sigs);

        ExtractionResult r1 = extractor.extract(new Document("", meta));
        ExtractionResult r2 = extractor.extract(new Document("", meta));

        String id1 = entitiesOfType(r1, "PDF_SIGNATURE").get(0).id();
        String id2 = entitiesOfType(r2, "PDF_SIGNATURE").get(0).id();
        assertEquals(id1, id2, "PDF_SIGNATURE entity ID must be deterministic");
    }

    // ── extract() — PDF_FORM parent entity ────────────────────────────────

    @Test
    void extract_formFieldsDocument_createsPdfFormParentWithHasFormRelation() {
        String text = "Field: FirstName (text) = John\nField: LastName (text) = Doe";

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "form.pdf");
        meta.put("extractionType", "formFields");
        meta.put("fieldCount", 2);
        meta.put("source", "/tmp/form.pdf");

        ExtractionResult result = extractor.extract(doc(text, meta));

        // PDF_FORM parent entity must be created
        ExtractedEntity formEntity = singleEntityOfType(result, "PDF_FORM");
        assertNotNull(formEntity);
        assertEquals("2", formEntity.properties().get("fieldCount"),
                "PDF_FORM entity must carry fieldCount from metadata");
        assertEquals(0.95, formEntity.confidence(), 1e-9);

        // HAS_FORM relation from PDF_DOCUMENT to PDF_FORM
        List<ExtractedRelation> hasForms = relationsOfType(result, "HAS_FORM");
        assertEquals(1, hasForms.size(), "One HAS_FORM relation expected");
        String docEntityId = singleEntityOfType(result, "PDF_DOCUMENT").id();
        assertEquals(docEntityId, hasForms.get(0).source(),
                "HAS_FORM must originate from the PDF_DOCUMENT entity");
        assertEquals(formEntity.id(), hasForms.get(0).target(),
                "HAS_FORM must point to the PDF_FORM entity");
    }

    @Test
    void extract_formFieldsLinkedToFormEntityNotDocument_viaStructuredMetadata() {
        // When pdf.formFields structured metadata is present, fields link to PDF_FORM
        List<Map<String, String>> formFields = new ArrayList<>();
        Map<String, String> f1 = new LinkedHashMap<>();
        f1.put("name", "Email");
        f1.put("fieldType", "text");
        f1.put("value", "user@example.com");
        f1.put("required", "true");
        f1.put("readOnly", "false");
        Map<String, String> f2 = new LinkedHashMap<>();
        f2.put("name", "Subscribe");
        f2.put("fieldType", "checkbox");
        f2.put("value", "");
        f2.put("required", "false");
        f2.put("readOnly", "false");
        formFields.add(f1);
        formFields.add(f2);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "structured-form.pdf");
        meta.put("extractionType", "formFields");
        meta.put("fieldCount", 2);
        meta.put("pdf.formFields", formFields);
        meta.put("source", "/tmp/structured-form.pdf");

        ExtractionResult result = extractor.extract(doc("", meta));

        ExtractedEntity formEntity = singleEntityOfType(result, "PDF_FORM");
        List<ExtractedEntity> fields = entitiesOfType(result, "FORM_FIELD");
        assertEquals(2, fields.size(), "Two FORM_FIELD entities expected");

        // HAS_FORM_FIELD relations must originate from PDF_FORM, not PDF_DOCUMENT
        List<ExtractedRelation> hasFormField = relationsOfType(result, "HAS_FORM_FIELD");
        assertEquals(2, hasFormField.size());
        hasFormField.forEach(r ->
                assertEquals(formEntity.id(), r.source(),
                        "HAS_FORM_FIELD must originate from the PDF_FORM entity, not PDF_DOCUMENT"));

        // Email field properties
        ExtractedEntity emailField = fields.stream()
                .filter(e -> "Email".equals(e.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Email field not found"));
        assertEquals("text", emailField.properties().get("fieldType"));
        assertEquals("user@example.com", emailField.properties().get("value"));
    }

    @Test
    void extract_formFields_requiredAndReadOnlyPropertiesCaptured() {
        List<Map<String, String>> formFields = new ArrayList<>();
        Map<String, String> required = new LinkedHashMap<>();
        required.put("name", "SSN");
        required.put("fieldType", "text");
        required.put("value", "");
        required.put("required", "true");
        required.put("readOnly", "false");
        Map<String, String> readOnly = new LinkedHashMap<>();
        readOnly.put("name", "DocId");
        readOnly.put("fieldType", "text");
        readOnly.put("value", "DOC-12345");
        readOnly.put("required", "false");
        readOnly.put("readOnly", "true");
        Map<String, String> optional = new LinkedHashMap<>();
        optional.put("name", "Comments");
        optional.put("fieldType", "textarea");
        optional.put("value", "");
        optional.put("required", "false");
        optional.put("readOnly", "false");
        formFields.add(required);
        formFields.add(readOnly);
        formFields.add(optional);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "props-form.pdf");
        meta.put("extractionType", "formFields");
        meta.put("fieldCount", 3);
        meta.put("pdf.formFields", formFields);
        meta.put("source", "/tmp/props-form.pdf");

        ExtractionResult result = extractor.extract(doc("", meta));

        List<ExtractedEntity> fields = entitiesOfType(result, "FORM_FIELD");
        assertEquals(3, fields.size());

        // SSN: required=true must be stored
        ExtractedEntity ssnField = fields.stream()
                .filter(e -> "SSN".equals(e.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("SSN field not found"));
        assertEquals("true", ssnField.properties().get("required"),
                "required=true must be stored on entity");
        assertNull(ssnField.properties().get("readOnly"),
                "readOnly=false must not be stored (only true values stored)");

        // DocId: readOnly=true must be stored
        ExtractedEntity docIdField = fields.stream()
                .filter(e -> "DocId".equals(e.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("DocId field not found"));
        assertEquals("true", docIdField.properties().get("readOnly"),
                "readOnly=true must be stored on entity");
        assertNull(docIdField.properties().get("required"),
                "required=false must not be stored (only true values stored)");

        // Comments: neither required nor readOnly
        ExtractedEntity commentsField = fields.stream()
                .filter(e -> "Comments".equals(e.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Comments field not found"));
        assertNull(commentsField.properties().get("required"),
                "required=false must not be stored");
        assertNull(commentsField.properties().get("readOnly"),
                "readOnly=false must not be stored");
    }

    @Test
    void extract_formFieldsFallback_textParsing_linksToFormEntity() {
        // When pdf.formFields is absent (backward compat), text parsing still links fields to PDF_FORM
        String text = "Field: Name (text) = Alice\nField: Age (number) = 30";

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "fallback-form.pdf");
        meta.put("extractionType", "formFields");
        meta.put("fieldCount", 2);
        // No pdf.formFields key — forces text fallback
        meta.put("source", "/tmp/fallback-form.pdf");

        ExtractionResult result = extractor.extract(doc(text, meta));

        ExtractedEntity formEntity = singleEntityOfType(result, "PDF_FORM");
        List<ExtractedEntity> fields = entitiesOfType(result, "FORM_FIELD");
        assertEquals(2, fields.size(), "Fallback text parsing must still produce FORM_FIELD entities");

        // HAS_FORM_FIELD relations must originate from PDF_FORM
        List<ExtractedRelation> hasFormField = relationsOfType(result, "HAS_FORM_FIELD");
        assertEquals(2, hasFormField.size());
        hasFormField.forEach(r ->
                assertEquals(formEntity.id(), r.source(),
                        "Fallback HAS_FORM_FIELD must originate from PDF_FORM entity"));
    }

    @Test
    void extract_formFieldEntityId_isDeterministic_withStructuredMetadata() {
        List<Map<String, String>> formFields = new ArrayList<>();
        Map<String, String> f1 = new LinkedHashMap<>();
        f1.put("name", "StableField");
        f1.put("fieldType", "text");
        f1.put("value", "");
        f1.put("required", "false");
        f1.put("readOnly", "false");
        formFields.add(f1);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "stable.pdf");
        meta.put("extractionType", "formFields");
        meta.put("fieldCount", 1);
        meta.put("pdf.formFields", formFields);
        meta.put("source", "/tmp/stable.pdf");

        ExtractionResult r1 = extractor.extract(doc("", meta));
        ExtractionResult r2 = extractor.extract(doc("", meta));

        String id1 = entitiesOfType(r1, "FORM_FIELD").get(0).id();
        String id2 = entitiesOfType(r2, "FORM_FIELD").get(0).id();
        assertEquals(id1, id2, "FORM_FIELD entity ID must be deterministic with structured metadata");

        String formId1 = entitiesOfType(r1, "PDF_FORM").get(0).id();
        String formId2 = entitiesOfType(r2, "PDF_FORM").get(0).id();
        assertEquals(formId1, formId2, "PDF_FORM entity ID must be deterministic");
    }

    // ── extract() — ocrConfidence property ───────────────────────────────

    @Test
    void extract_ocrProcessedPdf_withConfidence_storesOcrConfidenceProperty() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "scanned.pdf");
        meta.put("ocr_processed", true);
        meta.put("pdf_processing_mode", "vlm");
        meta.put("vlm_model", "gotocr2");
        meta.put("ocrConfidence", "0.97");

        ExtractionResult result = extractor.extract(doc(meta));

        ExtractedEntity docEntity = result.entities().get(0);
        assertEquals("0.97", docEntity.properties().get("ocrConfidence"),
                "ocrConfidence must be stored on the document entity when ocr_processed=true");
    }

    @Test
    void extract_ocrProcessedPdf_withoutConfidence_noOcrConfidenceProperty() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "scanned.pdf");
        meta.put("ocr_processed", true);
        meta.put("pdf_processing_mode", "vlm");

        ExtractionResult result = extractor.extract(doc(meta));

        ExtractedEntity docEntity = result.entities().get(0);
        assertNull(docEntity.properties().get("ocrConfidence"),
                "ocrConfidence must not be set when absent from metadata");
    }

    @Test
    void extract_nonOcrPdf_withConfidenceKey_doesNotStoreOcrConfidence() {
        // ocrConfidence key present but ocr_processed is false — should not be captured
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", "normal.pdf");
        meta.put("ocrConfidence", "0.99");
        // ocr_processed NOT set

        ExtractionResult result = extractor.extract(doc(meta));

        ExtractedEntity docEntity = result.entities().get(0);
        assertNull(docEntity.properties().get("ocrConfidence"),
                "ocrConfidence must not be stored when ocr_processed is not true");
    }

    // ── Markdown heading SUBSECTION_OF hierarchy ─────────────────────────

    @Test
    void extract_markdownHeadings_createsSubsectionOfHierarchy() {
        String bodyText = """
                # Introduction
                Some intro text here.
                ## Background
                Background details.
                ## Methodology
                Method details.
                ### Data Collection
                Data collection info.
                # Conclusion
                Final remarks.
                """;

        Map<String, Object> meta = pdfMeta("report.pdf", "Research Report", "/docs/report.pdf");
        // No extractionType set — not bookmarks, so markdown headings are used
        ExtractionResult result = extractor.extract(doc(bodyText, meta));

        List<ExtractedEntity> sections = entitiesOfType(result, "PDF_SECTION");
        assertEquals(5, sections.size(), "Should extract 5 sections from markdown headings");

        // H1 sections get HAS_SECTION to document
        List<ExtractedRelation> hasSections = relationsOfType(result, "HAS_SECTION");
        assertTrue(hasSections.size() >= 2, "H1 headings (Introduction, Conclusion) should have HAS_SECTION");

        // H2/H3 sections get SUBSECTION_OF parent
        List<ExtractedRelation> subsections = relationsOfType(result, "SUBSECTION_OF");
        assertTrue(subsections.size() >= 2,
                "Nested headings (Background, Methodology under Introduction; Data Collection under Methodology) should have SUBSECTION_OF");

        // All sections should have source=markdown_heading
        sections.forEach(s -> assertEquals("markdown_heading", s.properties().get("source")));
    }

    @Test
    void extract_markdownHeadings_notUsedWhenBookmarksPresent() {
        String bodyText = "# Should Not Be Parsed\nSome text\n## Also Ignored\n";

        Map<String, Object> meta = pdfMeta("with-bookmarks.pdf", "Bookmarked PDF", "/docs/bookmarked.pdf");
        meta.put("extractionType", "bookmarks");
        // Add a bookmark too
        meta.put("pdf.bookmarks", List.of(Map.of("title", "Chapter 1", "level", 0)));

        ExtractionResult result = extractor.extract(doc(bodyText, meta));

        // Sections from bookmarks, not markdown
        List<ExtractedEntity> sections = entitiesOfType(result, "PDF_SECTION");
        boolean anyFromMarkdown = sections.stream()
                .anyMatch(s -> "markdown_heading".equals(s.properties().get("source")));
        assertFalse(anyFromMarkdown, "Markdown headings should not be parsed when extractionType=bookmarks");
    }

    private Map<String, Object> pdfMeta(String fileName, String title, String source) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("documentType", "PDF Document");
        meta.put("fileName", fileName);
        meta.put("title", title);
        meta.put("source", source);
        return meta;
    }

    // ── extract() — XMP publishers → ORGANIZATION + PRODUCED_BY ─────────

    @Test
    void xmpPublishers_singlePublisher_createsOrganizationAndProducedByRelation() {
        Map<String, Object> meta = pdfMeta("journal.pdf", "Journal Article", "/docs/journal.pdf");
        meta.put("pdf.xmpPublishers", List.of("Acme Publishing Group"));

        ExtractionResult result = extractor.extract(new Document("", meta));

        // Should have exactly one ORGANIZATION from the XMP publisher
        List<ExtractedEntity> orgs = entitiesOfType(result, "ORGANIZATION");
        assertEquals(1, orgs.size(), "One ORGANIZATION entity expected for the XMP publisher");
        ExtractedEntity org = orgs.get(0);
        assertEquals("Acme Publishing Group", org.name());
        assertEquals("xmpPublisher", org.properties().get("source_field"));
        assertEquals(0.8, org.confidence(), 1e-9);

        // Must have a PRODUCED_BY relation from the document to the publisher
        List<ExtractedRelation> producedBy = relationsOfType(result, "PRODUCED_BY");
        assertEquals(1, producedBy.size(), "One PRODUCED_BY relation expected");
        String docId = singleEntityOfType(result, "PDF_DOCUMENT").id();
        assertEquals(docId, producedBy.get(0).source());
        assertEquals(org.id(), producedBy.get(0).target());
    }

    @Test
    void xmpPublishers_multiplePublishers_createsOneOrganizationPerPublisher() {
        Map<String, Object> meta = pdfMeta("multi.pdf", "Multi-Publisher Doc", "/docs/multi.pdf");
        meta.put("pdf.xmpPublishers", List.of("Publisher Alpha", "Publisher Beta", "Publisher Gamma"));

        ExtractionResult result = extractor.extract(new Document("", meta));

        List<ExtractedEntity> orgs = entitiesOfType(result, "ORGANIZATION");
        assertEquals(3, orgs.size(), "One ORGANIZATION per XMP publisher");

        Set<String> names = orgs.stream().map(ExtractedEntity::name).collect(Collectors.toSet());
        assertTrue(names.contains("Publisher Alpha"));
        assertTrue(names.contains("Publisher Beta"));
        assertTrue(names.contains("Publisher Gamma"));

        List<ExtractedRelation> producedBy = relationsOfType(result, "PRODUCED_BY");
        assertEquals(3, producedBy.size(), "One PRODUCED_BY per XMP publisher");
    }

    @Test
    void xmpPublishers_nullAndBlankEntriesAreSkipped() {
        Map<String, Object> meta = pdfMeta("sparse.pdf", "Sparse Publishers", "/docs/sparse.pdf");
        // List contains one real publisher, one null, and one blank string
        List<String> publishers = new ArrayList<>();
        publishers.add("Real Publisher");
        publishers.add(null);
        publishers.add("   ");
        meta.put("pdf.xmpPublishers", publishers);

        ExtractionResult result = extractor.extract(new Document("", meta));

        List<ExtractedEntity> orgs = entitiesOfType(result, "ORGANIZATION");
        assertEquals(1, orgs.size(),
                "Null and blank XMP publisher entries must be skipped; only one ORGANIZATION expected");
        assertEquals("Real Publisher", orgs.get(0).name());

        List<ExtractedRelation> producedBy = relationsOfType(result, "PRODUCED_BY");
        assertEquals(1, producedBy.size(), "Only one PRODUCED_BY relation for the non-blank publisher");
    }

    // ── extract() — XMP languages → document property ────────────────────

    @Test
    void xmpLanguages_nonEmptyList_addedAsDocumentProperty() {
        Map<String, Object> meta = pdfMeta("multilang.pdf", "Multilingual Doc", "/docs/multilang.pdf");
        meta.put("pdf.xmpLanguages", List.of("en", "fr", "de"));

        ExtractionResult result = extractor.extract(new Document("", meta));

        ExtractedEntity docEntity = singleEntityOfType(result, "PDF_DOCUMENT");
        assertEquals("en, fr, de", docEntity.properties().get("xmpLanguages"),
                "xmpLanguages must be joined with \", \" and stored on the PDF_DOCUMENT entity");
    }

    @Test
    void xmpLanguages_emptyList_doesNotAddDocumentProperty() {
        Map<String, Object> meta = pdfMeta("nolang.pdf", "No Languages", "/docs/nolang.pdf");
        meta.put("pdf.xmpLanguages", List.of());

        ExtractionResult result = extractor.extract(new Document("", meta));

        ExtractedEntity docEntity = singleEntityOfType(result, "PDF_DOCUMENT");
        assertNull(docEntity.properties().get("xmpLanguages"),
                "xmpLanguages property must not be set when the list is empty");
    }
}
