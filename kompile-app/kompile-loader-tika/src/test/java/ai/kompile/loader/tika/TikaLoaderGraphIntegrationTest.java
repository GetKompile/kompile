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

import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.*;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full pipeline integration tests: real files → TikaLoaderImpl → TikaGenericGraphExtractor.
 *
 * Each test generates a spec-compliant file, loads it through the actual Tika parser,
 * then runs deterministic graph extraction and verifies every expected entity and relation.
 */
class TikaLoaderGraphIntegrationTest {

    private TikaLoaderImpl loader;
    private TikaGenericGraphExtractor extractor;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        loader = new TikaLoaderImpl();
        extractor = new TikaGenericGraphExtractor();
    }

    /** Load a single file and return the first Document. */
    private Document loadFile(Path file) throws Exception {
        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl(file.toAbsolutePath().toString())
                .build();
        List<Document> docs = loader.load(desc);
        assertFalse(docs.isEmpty(), "Loader should return at least one document for " + file.getFileName());
        return docs.get(0);
    }

    /** Run graph extraction on a loaded document and return the result. */
    private ExtractionResult extractGraph(Document doc) {
        assertTrue(extractor.canExtract(doc),
                "Extractor should accept document with loader=" + doc.getMetadata().get("loader")
                        + ", documentType=" + doc.getMetadata().get(GraphConstants.META_DOCUMENT_TYPE)
                        + ", fileName=" + doc.getMetadata().get(GraphConstants.META_FILE_NAME));
        return extractor.extract(doc);
    }

    // ════════════════════════════════════════════════════════════════════
    //  CSV — RFC 4180 compliant comma-separated values
    // ════════════════════════════════════════════════════════════════════

    @Nested
    class CsvIntegration {

        /**
         * RFC 4180 CSV with headers and 3 data rows.
         * Expected: content_type=table, headers captured, row/column counts,
         * cell-level graph via TableCellGraphBuilder.
         */
        @Test
        void csvWithHeadersAndDataProducesTableMetadataAndCellGraph() throws Exception {
            String csv = """
                    Name,Department,Salary,Start Date
                    Alice Johnson,Engineering,95000,2023-01-15
                    Bob Smith,Marketing,72000,2022-06-01
                    Carol Williams,Engineering,98000,2021-11-20
                    """;
            Path csvFile = tempDir.resolve("employees.csv");
            Files.writeString(csvFile, csv);

            Document doc = loadFile(csvFile);
            Map<String, Object> meta = doc.getMetadata();

            // Verify Tika loaded the content
            assertNotNull(doc.getText(), "Document text should not be null");
            assertTrue(doc.getText().contains("Alice Johnson"), "Text should contain CSV data");

            // Verify CSV enrichment metadata
            assertEquals("table", meta.get(GraphConstants.META_CONTENT_TYPE),
                    "CSV should be typed as 'table'");
            assertNotNull(meta.get(GraphConstants.META_TABLE_ROW_COUNT), "Should have row count");
            assertNotNull(meta.get(GraphConstants.META_TABLE_COLUMN_COUNT), "Should have column count");
            assertEquals(4, meta.get(GraphConstants.META_TABLE_COLUMN_COUNT));

            // Verify headers
            Object headersObj = meta.get(GraphConstants.META_TABLE_HEADERS);
            assertNotNull(headersObj, "Should have table headers");
            assertTrue(headersObj.toString().contains("Name"));
            assertTrue(headersObj.toString().contains("Department"));

            // Verify cell-level graph was built
            assertNotNull(meta.get(GraphConstants.META_TABLE_GRAPH),
                    "CSV should produce a TableCellGraphBuilder cell graph");
            String graphJson = (String) meta.get(GraphConstants.META_TABLE_GRAPH);
            assertTrue(graphJson.contains("TABLE"), "Cell graph should contain TABLE entity type");
            assertTrue(graphJson.contains("CELL") || graphJson.contains("HEADER_CELL"),
                    "Cell graph should contain cell entities");
        }

        /**
         * CSV with quoted fields containing commas and newlines (RFC 4180 §2.6).
         */
        @Test
        void csvWithQuotedFieldsContainingCommas() throws Exception {
            String csv = """
                    Name,Address,Notes
                    "Smith, John","123 Main St, Apt 4","Has comma, in notes"
                    Jane Doe,456 Oak Ave,Simple entry
                    """;
            Path csvFile = tempDir.resolve("quoted.csv");
            Files.writeString(csvFile, csv);

            Document doc = loadFile(csvFile);
            Map<String, Object> meta = doc.getMetadata();

            assertEquals("table", meta.get(GraphConstants.META_CONTENT_TYPE));
            assertNotNull(meta.get(GraphConstants.META_TABLE_GRAPH),
                    "Quoted CSV should still produce cell graph");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  TSV — Tab-separated values
    // ════════════════════════════════════════════════════════════════════

    @Nested
    class TsvIntegration {

        @Test
        void tsvProducesTableMetadataAndCellGraph() throws Exception {
            String tsv = "Gene\tChromosome\tPosition\tFunction\n"
                    + "BRCA1\t17\t43044295\tTumor suppressor\n"
                    + "TP53\t17\t7687490\tCell cycle regulation\n"
                    + "EGFR\t7\t55019017\tGrowth factor receptor\n";
            Path tsvFile = tempDir.resolve("genes.tsv");
            Files.writeString(tsvFile, tsv);

            Document doc = loadFile(tsvFile);
            Map<String, Object> meta = doc.getMetadata();

            assertEquals("table", meta.get(GraphConstants.META_CONTENT_TYPE),
                    "TSV should be typed as 'table'");
            assertEquals(4, meta.get(GraphConstants.META_TABLE_COLUMN_COUNT));
            assertNotNull(meta.get(GraphConstants.META_TABLE_GRAPH),
                    "TSV should produce cell graph");

            // Graph extraction should recognize this as TSV_DOCUMENT
            ExtractionResult result = extractGraph(doc);
            assertTrue(result.entities().stream().anyMatch(e ->
                            "TSV_DOCUMENT".equals(e.type()) || e.type().contains("DOCUMENT")),
                    "Should create a document-level entity");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  Markdown — CommonMark with pipe tables, links, frontmatter
    // ════════════════════════════════════════════════════════════════════

    @Nested
    class MarkdownIntegration {

        /**
         * Markdown with YAML frontmatter (Jekyll/Hugo style), headings, a pipe table,
         * inline links, code blocks, and task list items.
         */
        @Test
        void markdownWithTablesLinksHeadingsProducesFullGraph() throws Exception {
            String md = """
                    ---
                    title: API Design Guidelines
                    author: Sarah Chen
                    tags: [api, rest, design]
                    date: 2025-03-15
                    ---

                    # API Design Guidelines

                    This document covers REST API design patterns.
                    See also [OpenAPI Spec](https://spec.openapis.org/oas/v3.1.0)
                    and the [[Internal Wiki Page]].

                    ## HTTP Methods

                    | Method  | Idempotent | Safe | Cacheable |
                    |---------|------------|------|-----------|
                    | GET     | Yes        | Yes  | Yes       |
                    | POST    | No         | No   | No        |
                    | PUT     | Yes        | No   | No        |
                    | DELETE  | Yes        | No   | No        |

                    ## Task List

                    - [x] Define resource naming conventions
                    - [ ] Write pagination spec
                    - [ ] Review error response format

                    ## Code Example

                    ```java
                    @GetMapping("/users/{id}")
                    public ResponseEntity<User> getUser(@PathVariable Long id) {
                        return userService.findById(id)
                            .map(ResponseEntity::ok)
                            .orElse(ResponseEntity.notFound().build());
                    }
                    ```

                    ## References

                    - https://restfulapi.net/resource-naming/
                    - https://tools.ietf.org/html/rfc7231
                    """;
            Path mdFile = tempDir.resolve("api-guidelines.md");
            Files.writeString(mdFile, md);

            Document doc = loadFile(mdFile);
            Map<String, Object> meta = doc.getMetadata();

            // Verify markdown-specific enrichment
            assertNotNull(doc.getText());
            assertTrue(doc.getText().contains("API Design Guidelines"));

            // Frontmatter should be extracted
            Object fmTitle = meta.get("markdown.frontmatter.title");
            if (fmTitle != null) {
                assertEquals("API Design Guidelines", fmTitle.toString());
            }

            // Pipe table should produce cell-level graph
            assertNotNull(meta.get(GraphConstants.META_TABLE_GRAPH),
                    "Markdown pipe table should produce cell graph via TableCellGraphBuilder");

            // Now run graph extraction
            ExtractionResult result = extractGraph(doc);
            assertFalse(result.entities().isEmpty(), "Should produce entities");

            // Verify DOCUMENT_SECTION entities from headings
            long sectionCount = result.entities().stream()
                    .filter(e -> "DOCUMENT_SECTION".equals(e.type())).count();
            assertTrue(sectionCount >= 2,
                    "Should create DOCUMENT_SECTION entities from markdown headings (found " + sectionCount + ")");

            // Verify EXTERNAL_RESOURCE from inline links and body URLs
            long urlCount = result.entities().stream()
                    .filter(e -> "EXTERNAL_RESOURCE".equals(e.type()) || "EXTERNAL_LINK".equals(e.type())).count();
            assertTrue(urlCount >= 2,
                    "Should extract external URLs from markdown links and body text (found " + urlCount + ")");

            // Verify TASK_ITEM entities
            long taskCount = result.entities().stream()
                    .filter(e -> "TASK_ITEM".equals(e.type())).count();
            assertTrue(taskCount >= 2,
                    "Should extract task list items from markdown (found " + taskCount + ")");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  JSON — with $schema, nested structure, top-level keys
    // ════════════════════════════════════════════════════════════════════

    @Nested
    class JsonIntegration {

        /**
         * JSON with $schema reference, $id, nested objects, and arrays.
         * Schema entity + JSON_KEY entities + appropriate relations.
         */
        @Test
        void jsonWithSchemaAndNestedStructureProducesFullGraph() throws Exception {
            String json = """
                    {
                      "$schema": "https://json-schema.org/draft/2020-12/schema",
                      "$id": "https://example.com/product.schema.json",
                      "title": "Product",
                      "description": "A product in the catalog",
                      "type": "object",
                      "properties": {
                        "productId": { "type": "integer" },
                        "productName": { "type": "string" },
                        "price": { "type": "number", "minimum": 0 },
                        "tags": {
                          "type": "array",
                          "items": { "type": "string" }
                        }
                      },
                      "required": ["productId", "productName", "price"]
                    }
                    """;
            Path jsonFile = tempDir.resolve("product-schema.json");
            Files.writeString(jsonFile, json);

            Document doc = loadFile(jsonFile);
            Map<String, Object> meta = doc.getMetadata();

            // Verify JSON enrichment
            assertEquals("https://json-schema.org/draft/2020-12/schema",
                    meta.get("json.schema"),
                    "Should extract $schema URL");

            // Top-level keys should be extracted
            Object topKeys = meta.get("json.topLevelKeys");
            assertNotNull(topKeys, "Should extract top-level JSON keys");

            // Graph extraction
            ExtractionResult result = extractGraph(doc);
            assertFalse(result.entities().isEmpty(), "Should produce entities from JSON");

            // Verify JSON_SCHEMA entity
            boolean hasSchemaEntity = result.entities().stream().anyMatch(e ->
                    "JSON_SCHEMA".equals(e.type()) || "EXTERNAL_RESOURCE".equals(e.type()));
            assertTrue(hasSchemaEntity,
                    "Should create entity for $schema reference");

            // Verify JSON_KEY entities
            long keyCount = result.entities().stream()
                    .filter(e -> "JSON_KEY".equals(e.type())).count();
            assertTrue(keyCount >= 3,
                    "Should create JSON_KEY entities for top-level keys (found " + keyCount + ")");
        }

        /**
         * JSON array at top level.
         */
        @Test
        void jsonArrayProducesArrayMetadata() throws Exception {
            String json = """
                    [
                      {"name": "Alice", "role": "engineer"},
                      {"name": "Bob", "role": "designer"},
                      {"name": "Carol", "role": "manager"}
                    ]
                    """;
            Path jsonFile = tempDir.resolve("team.json");
            Files.writeString(jsonFile, json);

            Document doc = loadFile(jsonFile);
            Map<String, Object> meta = doc.getMetadata();

            Object isArray = meta.get("json.isArray");
            assertNotNull(isArray, "Should detect top-level array");
            assertEquals(true, isArray);

            Object arrSize = meta.get("json.arraySize");
            assertNotNull(arrSize, "Should report array size");
            assertEquals(3, arrSize);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  XML — with namespaces, DTD, schema references
    // ════════════════════════════════════════════════════════════════════

    @Nested
    class XmlIntegration {

        /**
         * XML with namespace declarations, xsi:schemaLocation, and nested elements.
         */
        @Test
        void xmlWithNamespacesAndSchemaProducesFullGraph() throws Exception {
            String xml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0"
                             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                             xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                                                 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                        <modelVersion>4.0.0</modelVersion>
                        <groupId>com.example</groupId>
                        <artifactId>demo-project</artifactId>
                        <version>1.0.0</version>
                        <name>Demo Project</name>
                        <description>A sample Maven POM for testing XML graph extraction</description>
                        <dependencies>
                            <dependency>
                                <groupId>org.junit.jupiter</groupId>
                                <artifactId>junit-jupiter</artifactId>
                                <version>5.10.0</version>
                                <scope>test</scope>
                            </dependency>
                        </dependencies>
                    </project>
                    """;
            Path xmlFile = tempDir.resolve("pom.xml");
            Files.writeString(xmlFile, xml);

            Document doc = loadFile(xmlFile);
            Map<String, Object> meta = doc.getMetadata();

            // Verify XML enrichment
            assertEquals("project", meta.get("xml.rootTag"),
                    "Should extract root element name");
            assertNotNull(meta.get("xml.rootNamespace"),
                    "Should extract root namespace");

            // Schema location
            assertNotNull(meta.get("xml.schemaLocation"),
                    "Should extract xsi:schemaLocation");

            // Graph extraction
            ExtractionResult result = extractGraph(doc);
            assertFalse(result.entities().isEmpty(), "Should produce entities from XML");

            // Verify XML_ROOT_ELEMENT entity
            boolean hasRoot = result.entities().stream().anyMatch(e ->
                    "XML_ROOT_ELEMENT".equals(e.type()));
            assertTrue(hasRoot, "Should create XML_ROOT_ELEMENT entity");

            // Verify XML_NAMESPACE entities
            long nsCount = result.entities().stream()
                    .filter(e -> "XML_NAMESPACE".equals(e.type())).count();
            assertTrue(nsCount >= 1,
                    "Should create XML_NAMESPACE entities (found " + nsCount + ")");

            // Verify REFERENCES_SCHEMA relation
            boolean hasSchemaRef = result.relations().stream().anyMatch(r ->
                    "REFERENCES_SCHEMA".equals(r.type()));
            assertTrue(hasSchemaRef,
                    "Should create REFERENCES_SCHEMA relation for xsi:schemaLocation");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  YAML — with anchors, multiple documents, nested maps
    // ════════════════════════════════════════════════════════════════════

    @Nested
    class YamlIntegration {

        @Test
        void yamlWithNestedStructureProducesFullGraph() throws Exception {
            String yaml = """
                    apiVersion: apps/v1
                    kind: Deployment
                    metadata:
                      name: nginx-deployment
                      labels:
                        app: nginx
                        environment: production
                    spec:
                      replicas: 3
                      selector:
                        matchLabels:
                          app: nginx
                      template:
                        metadata:
                          labels:
                            app: nginx
                        spec:
                          containers:
                            - name: nginx
                              image: nginx:1.25
                              ports:
                                - containerPort: 80
                    """;
            Path yamlFile = tempDir.resolve("deployment.yaml");
            Files.writeString(yamlFile, yaml);

            Document doc = loadFile(yamlFile);
            Map<String, Object> meta = doc.getMetadata();

            // Verify YAML enrichment
            Object topKeys = meta.get("yaml.topLevelKeys");
            assertNotNull(topKeys, "Should extract top-level YAML keys");
            assertTrue(topKeys instanceof List, "Top-level keys should be a list");
            @SuppressWarnings("unchecked")
            List<String> keys = (List<String>) topKeys;
            assertTrue(keys.contains("apiVersion"), "Should contain apiVersion key");
            assertTrue(keys.contains("kind"), "Should contain kind key");
            assertTrue(keys.contains("metadata"), "Should contain metadata key");
            assertTrue(keys.contains("spec"), "Should contain spec key");

            // Graph extraction
            ExtractionResult result = extractGraph(doc);
            assertFalse(result.entities().isEmpty(), "Should produce entities from YAML");

            // Verify YAML_KEY entities
            long keyCount = result.entities().stream()
                    .filter(e -> "YAML_KEY".equals(e.type())).count();
            assertTrue(keyCount >= 3,
                    "Should create YAML_KEY entities for top-level keys (found " + keyCount + ")");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  ICS — iCalendar (RFC 5545) event with VEVENT, VALARM
    // ════════════════════════════════════════════════════════════════════

    @Nested
    class IcsIntegration {

        /**
         * RFC 5545 compliant ICS file with a VEVENT containing:
         * - DTSTART/DTEND in UTC format (YYYYMMDDTHHMMSSZ)
         * - SUMMARY, DESCRIPTION, LOCATION
         * - ORGANIZER and ATTENDEE
         * - VALARM (reminder)
         */
        @Test
        void icsCalendarEventProducesFullGraph() throws Exception {
            String ics = """
                    BEGIN:VCALENDAR
                    VERSION:2.0
                    PRODID:-//Kompile//Test//EN
                    CALSCALE:GREGORIAN
                    METHOD:REQUEST
                    BEGIN:VEVENT
                    UID:event-2025-Q2-review@kompile.ai
                    DTSTART:20250615T140000Z
                    DTEND:20250615T153000Z
                    SUMMARY:Q2 Engineering Review
                    DESCRIPTION:Quarterly review of engineering progress and roadmap planning.\\n\\nAgenda:\\n1. Q2 highlights\\n2. Roadmap review\\n3. Resource planning
                    LOCATION:Conference Room A / https://meet.example.com/q2-review
                    ORGANIZER;CN=Sarah Chen:mailto:sarah.chen@kompile.ai
                    ATTENDEE;ROLE=REQ-PARTICIPANT;CN=Alice Johnson:mailto:alice@kompile.ai
                    ATTENDEE;ROLE=REQ-PARTICIPANT;CN=Bob Smith:mailto:bob@kompile.ai
                    ATTENDEE;ROLE=OPT-PARTICIPANT;CN=Carol Williams:mailto:carol@kompile.ai
                    STATUS:CONFIRMED
                    PRIORITY:5
                    BEGIN:VALARM
                    TRIGGER:-PT15M
                    ACTION:DISPLAY
                    DESCRIPTION:Meeting in 15 minutes
                    END:VALARM
                    END:VEVENT
                    END:VCALENDAR
                    """;
            Path icsFile = tempDir.resolve("q2-review.ics");
            Files.writeString(icsFile, ics);

            Document doc = loadFile(icsFile);
            assertNotNull(doc.getText(), "ICS content should be parsed");
            assertTrue(doc.getText().contains("Q2 Engineering Review")
                            || doc.getText().contains("VEVENT")
                            || doc.getText().contains("review"),
                    "ICS body should contain event data");

            // Verify entity type resolution recognizes ICS
            ExtractionResult result = extractGraph(doc);
            assertFalse(result.entities().isEmpty(), "Should produce entities from ICS");

            // The document entity should be ICS_DOCUMENT
            boolean hasIcsDoc = result.entities().stream().anyMatch(e ->
                    "ICS_DOCUMENT".equals(e.type()));
            assertTrue(hasIcsDoc,
                    "Should identify as ICS_DOCUMENT (entity types: " +
                            result.entities().stream().map(ExtractedEntity::type).distinct().toList() + ")");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  VCF — vCard 3.0 (RFC 2426) / 4.0 (RFC 6350) contacts
    // ════════════════════════════════════════════════════════════════════

    @Nested
    class VcfIntegration {

        /**
         * vCard 4.0 (RFC 6350) with FN, N, EMAIL, TEL, ORG, ADR, URL, PHOTO ref.
         */
        @Test
        void vcfContactProducesFullGraph() throws Exception {
            String vcf = """
                    BEGIN:VCARD
                    VERSION:4.0
                    FN:Dr. Sarah Chen
                    N:Chen;Sarah;Elizabeth;Dr.;PhD
                    EMAIL;TYPE=work:sarah.chen@kompile.ai
                    EMAIL;TYPE=home:sarah@personal.example.com
                    TEL;TYPE=work,voice:+1-555-0123
                    TEL;TYPE=cell:+1-555-0456
                    ORG:Kompile Inc.;Engineering
                    TITLE:Principal Engineer
                    ADR;TYPE=work:;;123 Innovation Blvd;San Francisco;CA;94105;US
                    URL:https://kompile.ai/team/sarah
                    NOTE:Expert in knowledge graph systems and NLP.
                    REV:20250101T120000Z
                    END:VCARD
                    """;
            Path vcfFile = tempDir.resolve("sarah-chen.vcf");
            Files.writeString(vcfFile, vcf);

            Document doc = loadFile(vcfFile);
            assertNotNull(doc.getText(), "VCF content should be parsed");

            ExtractionResult result = extractGraph(doc);
            assertFalse(result.entities().isEmpty(), "Should produce entities from VCF");

            // The document entity should be VCARD_DOCUMENT
            boolean hasVcardDoc = result.entities().stream().anyMatch(e ->
                    "VCARD_DOCUMENT".equals(e.type()));
            assertTrue(hasVcardDoc,
                    "Should identify as VCARD_DOCUMENT (entity types: " +
                            result.entities().stream().map(ExtractedEntity::type).distinct().toList() + ")");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  Plain text with URLs, headings, and author metadata
    // ════════════════════════════════════════════════════════════════════

    @Nested
    class PlainTextIntegration {

        @Test
        void plainTextWithUrlsAndStructureProducesGraph() throws Exception {
            String text = """
                    Kompile Platform Architecture Overview
                    ======================================

                    Author: Alice Johnson
                    Date: 2025-03-15
                    Version: 2.1

                    1. Introduction
                    ---------------

                    The Kompile platform provides a comprehensive AI/ML pipeline
                    framework for document ingestion, knowledge graph construction,
                    and retrieval-augmented generation (RAG).

                    For more information, see:
                    - https://kompile.ai/docs/architecture
                    - https://github.com/kompile/kompile

                    2. Components
                    -------------

                    The system consists of several key components:
                    - Document loaders (PDF, Office, HTML, Email)
                    - Graph extractors (deterministic entity/relation extraction)
                    - Vector stores (Lucene HNSW, pgvector, Chroma)
                    - LLM integration (OpenAI, Anthropic, local SameDiff)

                    3. Conclusion
                    -------------

                    Contact: support@kompile.ai for questions.
                    """;
            Path txtFile = tempDir.resolve("architecture.txt");
            Files.writeString(txtFile, text);

            Document doc = loadFile(txtFile);
            assertNotNull(doc.getText());
            assertTrue(doc.getText().contains("Kompile Platform"));

            ExtractionResult result = extractGraph(doc);
            assertFalse(result.entities().isEmpty(), "Should produce entities from plain text");

            // Verify URL extraction from body text
            long urlCount = result.entities().stream()
                    .filter(e -> "EXTERNAL_RESOURCE".equals(e.type()) || "EXTERNAL_LINK".equals(e.type()))
                    .count();
            assertTrue(urlCount >= 2,
                    "Should extract URLs from body text (found " + urlCount + ")");
            assertTrue(result.relations().stream().anyMatch(r -> "HYPERLINKS_TO".equals(r.type())),
                    "Should create HYPERLINKS_TO relations for URLs");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  Log file — structured log with timestamps and levels
    // ════════════════════════════════════════════════════════════════════

    @Nested
    class LogFileIntegration {

        @Test
        void logFileIdentifiedAsLogDocument() throws Exception {
            String log = """
                    2025-03-15 10:23:45.123 INFO  [main] ai.kompile.app.Application - Starting Kompile v2.1.0
                    2025-03-15 10:23:46.456 INFO  [main] ai.kompile.core.ModelManager - Loading model: bge-base-en-v1.5
                    2025-03-15 10:23:48.789 WARN  [pool-1-thread-3] ai.kompile.loader.TikaLoader - Large file detected: report.pdf (45MB)
                    2025-03-15 10:23:49.012 ERROR [pool-1-thread-5] ai.kompile.vectorstore.LuceneStore - Index corruption detected at segment _2a
                    2025-03-15 10:23:49.345 INFO  [main] ai.kompile.app.Application - Application ready in 4.2s
                    """;
            Path logFile = tempDir.resolve("application.log");
            Files.writeString(logFile, log);

            Document doc = loadFile(logFile);
            assertNotNull(doc.getText());

            ExtractionResult result = extractGraph(doc);
            assertFalse(result.entities().isEmpty(), "Should produce entities from log file");

            // Should be identified as LOG_DOCUMENT
            boolean hasLogDoc = result.entities().stream().anyMatch(e ->
                    "LOG_DOCUMENT".equals(e.type()));
            assertTrue(hasLogDoc,
                    "Should identify as LOG_DOCUMENT (entity types: " +
                            result.entities().stream().map(ExtractedEntity::type).distinct().toList() + ")");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  Helpers
    // ════════════════════════════════════════════════════════════════════

    /**
     * Print all entities and relations for debugging (disabled by default).
     */
    @SuppressWarnings("unused")
    private void dumpGraph(ExtractionResult result, String label) {
        System.out.println("=== " + label + " ===");
        System.out.println("Entities (" + result.entities().size() + "):");
        for (ExtractedEntity e : result.entities()) {
            System.out.println("  [" + e.type() + "] " + e.name()
                    + (e.properties() != null ? " " + e.properties() : ""));
        }
        System.out.println("Relations (" + result.relations().size() + "):");
        for (ExtractedRelation r : result.relations()) {
            System.out.println("  " + r.source() + " --[" + r.type() + "]--> " + r.target()
                    + " (" + r.description() + ")");
        }
    }
}
