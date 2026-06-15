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

package ai.kompile.loader.web;

import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.*;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full pipeline integration tests: real HTML files -> WebHtmlLoaderImpl -> HtmlWebGraphExtractor.
 *
 * Each test generates a real HTML file with proper markup (valid HTML5, meta tags, tables, etc.),
 * loads it through the Jsoup-based HTML loader, then runs graph extraction and verifies
 * entities and relationships.
 */
class HtmlGraphIntegrationTest {

    private WebHtmlLoaderImpl loader;
    private HtmlWebGraphExtractor extractor;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        loader = new WebHtmlLoaderImpl();
        extractor = new HtmlWebGraphExtractor();
    }

    /** Load a local HTML file and return ALL documents (main + tables). */
    private List<Document> loadHtml(Path file) throws Exception {
        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl(file.toAbsolutePath().toString())
                .build();
        return loader.load(desc);
    }

    /** Run extraction and assert extractor accepts the document. */
    private ExtractionResult extractGraph(Document doc) {
        assertTrue(extractor.canExtract(doc),
                "HtmlWebGraphExtractor should accept document with meta: " + doc.getMetadata().keySet());
        return extractor.extract(doc);
    }

    // ================================================================
    //  1. Simple blog post with OpenGraph + Dublin Core metadata
    // ================================================================

    @Nested
    class BlogPostHtml {

        @Test
        void blogPostWithMetadataProducesFullGraph() throws Exception {
            String html = """
                    <!DOCTYPE html>
                    <html lang="en">
                    <head>
                        <meta charset="UTF-8">
                        <title>Understanding Knowledge Graphs - TechBlog</title>
                        <meta name="author" content="Dr. Sarah Chen">
                        <meta name="keywords" content="knowledge graphs, graph databases, NLP, entity extraction">
                        <meta name="description" content="A deep dive into knowledge graph construction.">
                        <meta property="og:title" content="Understanding Knowledge Graphs">
                        <meta property="og:description" content="A deep dive into knowledge graph construction and querying.">
                        <meta property="og:site_name" content="TechBlog">
                        <meta property="og:type" content="article">
                        <meta property="article:published_time" content="2025-05-15T10:00:00Z">
                        <meta property="article:modified_time" content="2025-05-20T14:30:00Z">
                        <meta property="article:author" content="Dr. Sarah Chen">
                        <meta property="article:section" content="Data Engineering">
                        <meta property="article:tag" content="graph databases">
                        <meta property="article:tag" content="knowledge graphs">
                        <meta name="twitter:card" content="summary_large_image">
                        <meta name="twitter:site" content="@techblog">
                        <meta name="DC.creator" content="Sarah Chen">
                        <meta name="DC.publisher" content="TechBlog Inc.">
                        <meta name="DC.date" content="2025-05-15">
                        <link rel="canonical" href="https://techblog.example.com/knowledge-graphs">
                    </head>
                    <body>
                        <article>
                            <h1>Understanding Knowledge Graphs</h1>
                            <p>Knowledge graphs are a powerful way to represent structured information
                            about the world. They consist of entities (nodes) connected by
                            relationships (edges).</p>
                            <h2>Key Concepts</h2>
                            <p>Entity extraction, relation extraction, and graph construction
                            form the core pipeline. See
                            <a href="https://en.wikipedia.org/wiki/Knowledge_graph">Wikipedia</a>
                            for a general overview.</p>
                            <h2>Further Reading</h2>
                            <p>Check out <a href="https://arxiv.org/abs/2003.02320">this paper</a>
                            on knowledge graph embeddings.</p>
                        </article>
                    </body>
                    </html>
                    """;

            Path file = tempDir.resolve("blog-post.html");
            Files.writeString(file, html);

            List<Document> docs = loadHtml(file);
            assertFalse(docs.isEmpty());
            Document mainDoc = docs.get(0);

            ExtractionResult result = extractGraph(mainDoc);

            // WEB_PAGE entity
            ExtractedEntity page = findEntity(result, "WEB_PAGE");
            assertNotNull(page, "Should have WEB_PAGE entity");
            assertTrue(page.name().contains("Knowledge Graphs"),
                    "Page title should contain 'Knowledge Graphs', got: " + page.name());

            // PERSON from author
            List<ExtractedEntity> persons = findEntities(result, "PERSON");
            assertTrue(persons.stream().anyMatch(p -> p.name().contains("Sarah") || p.name().contains("Chen")),
                    "Should extract author PERSON entity");

            // AUTHORED_BY relation
            assertNotNull(findRelation(result, "AUTHORED_BY"), "Should have AUTHORED_BY relation");

            // TOPIC entities from keywords
            List<ExtractedEntity> topics = findEntities(result, "TOPIC");
            assertTrue(topics.size() >= 2,
                    "Should have at least 2 TOPIC entities from keywords, got " + topics.size());

            // HAS_TOPIC relations
            List<ExtractedRelation> topicRels = findRelations(result, "HAS_TOPIC");
            assertTrue(topicRels.size() >= 2, "Should have at least 2 HAS_TOPIC relations");

            // WEBSITE from og:site_name
            ExtractedEntity website = findEntity(result, "WEBSITE");
            assertNotNull(website, "Should extract WEBSITE from og:site_name");
            assertEquals("TechBlog", website.name());
            assertNotNull(findRelation(result, "HOSTED_ON"), "Should have HOSTED_ON relation");

            // SOCIAL_ACCOUNT from twitter:site
            ExtractedEntity social = findEntity(result, "SOCIAL_ACCOUNT");
            assertNotNull(social, "Should extract SOCIAL_ACCOUNT from twitter:site");
            assertTrue(social.name().contains("@techblog"));

            // DATE entities
            List<ExtractedEntity> dates = findEntities(result, "DATE");
            assertTrue(dates.size() >= 1, "Should have DATE entity from published_time");
            assertNotNull(findRelation(result, "PUBLISHED_ON"), "Should have PUBLISHED_ON relation");

            // ORGANIZATION from DC.publisher
            ExtractedEntity org = findEntity(result, "ORGANIZATION");
            assertNotNull(org, "Should extract ORGANIZATION from DC.publisher");
            assertTrue(org.name().contains("TechBlog"));
            assertNotNull(findRelation(result, "PRODUCED_BY"), "Should have PRODUCED_BY relation");

            // Hyperlinks extracted from body
            List<ExtractedEntity> extLinks = findEntities(result, "EXTERNAL_LINK");
            List<ExtractedEntity> extResources = findEntities(result, "EXTERNAL_RESOURCE");
            int totalLinks = extLinks.size() + extResources.size();
            assertTrue(totalLinks >= 1,
                    "Should extract at least 1 external link from body, got " + totalLinks);
        }
    }

    // ================================================================
    //  2. HTML page with data tables
    // ================================================================

    @Nested
    class HtmlWithTables {

        @Test
        void htmlTableProducesTableDocument() throws Exception {
            String html = """
                    <!DOCTYPE html>
                    <html>
                    <head><title>Q3 Sales Report</title></head>
                    <body>
                        <h1>Quarterly Sales Report</h1>
                        <p>Below is the Q3 2025 sales summary by region.</p>
                        <table>
                            <tr><th>Region</th><th>Revenue</th><th>Units Sold</th><th>Growth</th></tr>
                            <tr><td>North America</td><td>$2.4M</td><td>12,450</td><td>+15%</td></tr>
                            <tr><td>Europe</td><td>$1.8M</td><td>9,200</td><td>+8%</td></tr>
                            <tr><td>Asia Pacific</td><td>$3.1M</td><td>18,700</td><td>+22%</td></tr>
                            <tr><td>Latin America</td><td>$0.9M</td><td>4,800</td><td>+12%</td></tr>
                        </table>
                    </body>
                    </html>
                    """;

            Path file = tempDir.resolve("sales-report.html");
            Files.writeString(file, html);

            List<Document> docs = loadHtml(file);

            // Should have main doc + table doc
            assertTrue(docs.size() >= 2,
                    "Should have at least 2 docs (main + table), got " + docs.size());

            // Main document
            Document mainDoc = docs.get(0);
            ExtractionResult mainResult = extractGraph(mainDoc);
            ExtractedEntity page = findEntity(mainResult, "WEB_PAGE");
            assertNotNull(page);
            assertTrue(page.name().contains("Sales Report"));

            // Table document
            Document tableDoc = docs.stream()
                    .filter(d -> "table".equals(d.getMetadata().get(GraphConstants.META_CONTENT_TYPE)))
                    .findFirst().orElse(null);
            assertNotNull(tableDoc, "Should have a table sub-document");

            // Table metadata
            Object rowCount = tableDoc.getMetadata().get("table_row_count");
            assertNotNull(rowCount, "Table doc should have row count");
            assertTrue(Integer.parseInt(rowCount.toString()) >= 4,
                    "Table should have at least 4 rows (header + 4 data)");

            Object headers = tableDoc.getMetadata().get("table_headers");
            assertNotNull(headers, "Table doc should have headers");
            assertTrue(headers.toString().contains("Region"),
                    "Table headers should contain 'Region'");

            // Table content should be markdown
            assertTrue(tableDoc.getText().contains("North America"),
                    "Table content should contain cell data");

            // Graph extraction on table doc
            if (extractor.canExtract(tableDoc)) {
                ExtractionResult tableResult = extractor.extract(tableDoc);
                // Table sub-documents may produce a WEB_PAGE entity for the table
                assertFalse(tableResult.entities().isEmpty(),
                        "Table extraction should produce entities");
            }
        }

        @Test
        void multipleTablesProduceMultipleDocuments() throws Exception {
            String html = """
                    <!DOCTYPE html>
                    <html>
                    <head><title>Financial Overview</title></head>
                    <body>
                        <h1>Financial Overview 2025</h1>
                        <h2>Income Statement</h2>
                        <table>
                            <tr><th>Item</th><th>Q1</th><th>Q2</th></tr>
                            <tr><td>Revenue</td><td>$10M</td><td>$12M</td></tr>
                            <tr><td>Expenses</td><td>$7M</td><td>$8M</td></tr>
                            <tr><td>Net Income</td><td>$3M</td><td>$4M</td></tr>
                        </table>
                        <h2>Balance Sheet</h2>
                        <table>
                            <tr><th>Asset</th><th>Value</th><th>Change</th></tr>
                            <tr><td>Cash</td><td>$25M</td><td>+5%</td></tr>
                            <tr><td>Receivables</td><td>$8M</td><td>-2%</td></tr>
                            <tr><td>Inventory</td><td>$12M</td><td>+10%</td></tr>
                        </table>
                    </body>
                    </html>
                    """;

            Path file = tempDir.resolve("financial-overview.html");
            Files.writeString(file, html);

            List<Document> docs = loadHtml(file);

            // Main doc + 2 table docs
            long tableCount = docs.stream()
                    .filter(d -> "table".equals(d.getMetadata().get(GraphConstants.META_CONTENT_TYPE)))
                    .count();
            assertTrue(tableCount >= 2,
                    "Should have at least 2 table documents, got " + tableCount);
        }
    }

    // ================================================================
    //  3. HTML-rendered email (the email detection path)
    // ================================================================

    @Nested
    class HtmlEmail {

        @Test
        void htmlRenderedEmailProducesEmailEntities() throws Exception {
            // An HTML page that looks like a rendered email — e.g. exported from Outlook
            String html = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <title>Re: Budget Approval</title>
                        <meta name="author" content="Alice Johnson">
                    </head>
                    <body>
                        <div class="email-header">
                            <table>
                                <tr><td><b>From:</b></td><td>Alice Johnson &lt;alice@example.com&gt;</td></tr>
                                <tr><td><b>To:</b></td><td>Bob Smith &lt;bob@example.com&gt;; Carol White &lt;carol@example.com&gt;</td></tr>
                                <tr><td><b>Cc:</b></td><td>Dave Lee &lt;dave@example.com&gt;</td></tr>
                                <tr><td><b>Date:</b></td><td>May 15, 2025 10:30 AM</td></tr>
                                <tr><td><b>Subject:</b></td><td>Re: Budget Approval</td></tr>
                            </table>
                        </div>
                        <div class="email-body">
                            <p>Hi team,</p>
                            <p>The Q3 budget has been approved. Please review the breakdown below.</p>
                            <table>
                                <tr><th>Department</th><th>Budget</th><th>Status</th></tr>
                                <tr><td>Engineering</td><td>$500K</td><td>Approved</td></tr>
                                <tr><td>Marketing</td><td>$200K</td><td>Approved</td></tr>
                                <tr><td>Operations</td><td>$150K</td><td>Pending</td></tr>
                            </table>
                            <p>Best regards,<br>Alice</p>
                        </div>
                    </body>
                    </html>
                    """;

            Path file = tempDir.resolve("email-export.html");
            Files.writeString(file, html);

            List<Document> docs = loadHtml(file);
            assertFalse(docs.isEmpty());

            Document mainDoc = docs.get(0);

            // Check if the email detector ran (content_type_hint=email)
            String contentTypeHint = mainDoc.getMetadata().get(GraphConstants.META_CONTENT_TYPE_HINT) != null
                    ? mainDoc.getMetadata().get(GraphConstants.META_CONTENT_TYPE_HINT).toString() : null;

            ExtractionResult result = extractGraph(mainDoc);

            // WEB_PAGE entity should always be created
            ExtractedEntity page = findEntity(result, "WEB_PAGE");
            assertNotNull(page, "Should have WEB_PAGE entity");

            if ("email".equals(contentTypeHint)) {
                // Email detection worked — verify email entities
                // The extractor should create email-related entities
                List<ExtractedEntity> persons = findEntities(result, "PERSON");
                assertTrue(persons.size() >= 1,
                        "Should have PERSON entities from email headers");

                // Check for SENT_BY or AUTHORED_BY relation
                boolean hasSendRelation = findRelation(result, "SENT_BY") != null
                        || findRelation(result, "AUTHORED_BY") != null;
                assertTrue(hasSendRelation,
                        "Should have SENT_BY or AUTHORED_BY relation");
            }

            // Either way, the page text should be extracted
            assertTrue(mainDoc.getText().contains("Q3 budget") || mainDoc.getText().contains("budget"),
                    "Body text should be extracted");
        }
    }

    // ================================================================
    //  4. HTML page with hyperlinks and images
    // ================================================================

    @Nested
    class HtmlWithLinksAndImages {

        @Test
        void htmlWithLinksProducesExternalLinkEntities() throws Exception {
            String html = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <title>Resource Collection</title>
                        <meta name="keywords" content="resources, tools, guides">
                    </head>
                    <body>
                        <h1>Useful Resources</h1>
                        <ul>
                            <li><a href="https://docs.example.com/api">API Documentation</a></li>
                            <li><a href="https://github.com/example/project">GitHub Repository</a></li>
                            <li><a href="https://blog.example.com/tutorial">Getting Started Tutorial</a></li>
                        </ul>
                        <p>For questions, email <a href="mailto:support@example.com">support</a>.</p>
                        <img src="https://cdn.example.com/diagram.png" alt="Architecture Diagram">
                    </body>
                    </html>
                    """;

            Path file = tempDir.resolve("resources.html");
            Files.writeString(file, html);

            List<Document> docs = loadHtml(file);
            Document mainDoc = docs.get(0);

            ExtractionResult result = extractGraph(mainDoc);

            // WEB_PAGE
            ExtractedEntity page = findEntity(result, "WEB_PAGE");
            assertNotNull(page);
            assertEquals("Resource Collection", page.name());

            // TOPIC entities from keywords
            List<ExtractedEntity> topics = findEntities(result, "TOPIC");
            assertTrue(topics.size() >= 2, "Should have topics from keywords");

            // External link entities (EXTERNAL_LINK or EXTERNAL_RESOURCE)
            List<ExtractedEntity> extLinks = findEntities(result, "EXTERNAL_LINK");
            List<ExtractedEntity> extResources = findEntities(result, "EXTERNAL_RESOURCE");
            int totalLinks = extLinks.size() + extResources.size();
            assertTrue(totalLinks >= 2,
                    "Should have at least 2 external link entities from body hrefs, got " + totalLinks);

            // HYPERLINKS_TO relations
            List<ExtractedRelation> hyperlinkRels = findRelations(result, "HYPERLINKS_TO");
            assertTrue(hyperlinkRels.size() >= 2,
                    "Should have at least 2 HYPERLINKS_TO relations, got " + hyperlinkRels.size());

            // Hyperlinks metadata should be populated
            Object hyperlinks = mainDoc.getMetadata().get("html.hyperlinks");
            assertNotNull(hyperlinks, "html.hyperlinks should be set");
            assertTrue(hyperlinks instanceof List<?>,
                    "html.hyperlinks should be a list");
            assertTrue(((List<?>) hyperlinks).size() >= 3,
                    "Should have at least 3 hyperlinks in metadata");
        }
    }

    // ================================================================
    //  5. HTML with headings structure (H1-H6)
    // ================================================================

    @Nested
    class HtmlWithHeadings {

        @Test
        void htmlHeadingsExtractedInMetadata() throws Exception {
            String html = """
                    <!DOCTYPE html>
                    <html>
                    <head><title>API Reference Guide</title></head>
                    <body>
                        <h1>API Reference</h1>
                        <h2>Authentication</h2>
                        <p>Use Bearer tokens for authentication.</p>
                        <h2>Endpoints</h2>
                        <h3>GET /users</h3>
                        <p>Returns a list of users.</p>
                        <h3>POST /users</h3>
                        <p>Creates a new user.</p>
                        <h2>Error Codes</h2>
                        <p>Standard HTTP error codes are used.</p>
                    </body>
                    </html>
                    """;

            Path file = tempDir.resolve("api-reference.html");
            Files.writeString(file, html);

            List<Document> docs = loadHtml(file);
            Document mainDoc = docs.get(0);

            // Headings should be in metadata
            Object headings = mainDoc.getMetadata().get("html.headings");
            assertNotNull(headings, "html.headings should be populated");
            assertTrue(headings instanceof List<?>, "Headings should be a list");
            List<?> headingList = (List<?>) headings;
            assertTrue(headingList.size() >= 5,
                    "Should have at least 5 headings (1xH1 + 3xH2 + 2xH3), got " + headingList.size());

            // Body text should include heading content
            assertTrue(mainDoc.getText().contains("Authentication"),
                    "Body text should contain heading text");
            assertTrue(mainDoc.getText().contains("Bearer tokens"),
                    "Body text should contain paragraph text");

            ExtractionResult result = extractGraph(mainDoc);
            assertNotNull(findEntity(result, "WEB_PAGE"));
        }
    }

    // ================================================================
    //  6. HTML with JSON-LD structured data
    // ================================================================

    @Nested
    class HtmlWithJsonLd {

        @Test
        void htmlWithJsonLdExtractsStructuredData() throws Exception {
            String html = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <title>Chocolate Chip Cookies Recipe</title>
                        <script type="application/ld+json">
                        {
                          "@context": "https://schema.org",
                          "@type": "Recipe",
                          "name": "Chocolate Chip Cookies",
                          "author": {
                            "@type": "Person",
                            "name": "Julia Baker"
                          },
                          "datePublished": "2025-03-10",
                          "description": "Classic chocolate chip cookies recipe.",
                          "prepTime": "PT15M",
                          "cookTime": "PT12M"
                        }
                        </script>
                    </head>
                    <body>
                        <h1>Chocolate Chip Cookies</h1>
                        <p>A classic recipe for the perfect chocolate chip cookies.</p>
                        <h2>Ingredients</h2>
                        <ul>
                            <li>2 cups flour</li>
                            <li>1 cup butter</li>
                            <li>1 cup chocolate chips</li>
                        </ul>
                    </body>
                    </html>
                    """;

            Path file = tempDir.resolve("recipe.html");
            Files.writeString(file, html);

            List<Document> docs = loadHtml(file);
            Document mainDoc = docs.get(0);

            // JSON-LD should be in metadata
            Object jsonLd = mainDoc.getMetadata().get("html.jsonld");
            assertNotNull(jsonLd, "html.jsonld should be populated");
            assertTrue(jsonLd instanceof List<?>, "JSON-LD should be a list");
            List<?> jsonLdList = (List<?>) jsonLd;
            assertFalse(jsonLdList.isEmpty(), "Should have at least one JSON-LD block");
            assertTrue(jsonLdList.get(0).toString().contains("Recipe"),
                    "JSON-LD should contain Recipe type");

            ExtractionResult result = extractGraph(mainDoc);
            ExtractedEntity page = findEntity(result, "WEB_PAGE");
            assertNotNull(page);
            assertTrue(page.name().contains("Chocolate Chip Cookies"));
        }
    }

    // ================================================================
    //  7. HTML with forms
    // ================================================================

    @Nested
    class HtmlWithForms {

        @Test
        void htmlFormsExtractedInMetadata() throws Exception {
            String html = """
                    <!DOCTYPE html>
                    <html>
                    <head><title>Contact Us</title></head>
                    <body>
                        <h1>Contact Form</h1>
                        <form action="https://api.example.com/contact" method="POST">
                            <label for="name">Name:</label>
                            <input type="text" id="name" name="name" required>
                            <label for="email">Email:</label>
                            <input type="email" id="email" name="email" required>
                            <label for="message">Message:</label>
                            <textarea id="message" name="message"></textarea>
                            <button type="submit">Send</button>
                        </form>
                    </body>
                    </html>
                    """;

            Path file = tempDir.resolve("contact.html");
            Files.writeString(file, html);

            List<Document> docs = loadHtml(file);
            Document mainDoc = docs.get(0);

            // Forms should be in metadata
            Object forms = mainDoc.getMetadata().get("html.forms");
            assertNotNull(forms, "html.forms should be populated");
            assertTrue(forms instanceof List<?>, "Forms should be a list");
            assertFalse(((List<?>) forms).isEmpty(), "Should have at least one form");

            ExtractionResult result = extractGraph(mainDoc);
            ExtractedEntity page = findEntity(result, "WEB_PAGE");
            assertNotNull(page);
            assertEquals("Contact Us", page.name());
        }
    }

    // ================================================================
    //  8. Complex page combining many features
    // ================================================================

    @Nested
    class ComplexHtmlPage {

        @Test
        void complexPageWithAllFeaturesProducesRichGraph() throws Exception {
            String html = """
                    <!DOCTYPE html>
                    <html lang="en">
                    <head>
                        <meta charset="UTF-8">
                        <title>Annual Technology Report 2025</title>
                        <meta name="author" content="Research Team">
                        <meta name="keywords" content="AI, machine learning, cloud computing, cybersecurity">
                        <meta name="description" content="Comprehensive review of technology trends in 2025.">
                        <meta property="og:site_name" content="TechInsights">
                        <meta property="article:published_time" content="2025-05-01T00:00:00Z">
                        <link rel="alternate" hreflang="es" href="https://techinsights.example.com/es/report-2025">
                    </head>
                    <body>
                        <h1>Annual Technology Report 2025</h1>
                        <p>Published by the Research Team at TechInsights.</p>

                        <h2>AI Adoption by Industry</h2>
                        <table>
                            <tr><th>Industry</th><th>Adoption Rate</th><th>YoY Change</th></tr>
                            <tr><td>Healthcare</td><td>72%</td><td>+18%</td></tr>
                            <tr><td>Finance</td><td>85%</td><td>+12%</td></tr>
                            <tr><td>Manufacturing</td><td>64%</td><td>+25%</td></tr>
                            <tr><td>Education</td><td>58%</td><td>+30%</td></tr>
                        </table>

                        <h2>Key Findings</h2>
                        <p>Large language models have seen explosive growth. See
                        <a href="https://research.example.com/llm-survey-2025">our LLM survey</a>
                        for details.</p>

                        <h2>Cloud Market Share</h2>
                        <table>
                            <tr><th>Provider</th><th>Market Share</th><th>Revenue</th></tr>
                            <tr><td>AWS</td><td>31%</td><td>$105B</td></tr>
                            <tr><td>Azure</td><td>25%</td><td>$85B</td></tr>
                            <tr><td>GCP</td><td>12%</td><td>$41B</td></tr>
                        </table>

                        <p>For more information, visit
                        <a href="https://techinsights.example.com/methodology">our methodology page</a>.</p>
                    </body>
                    </html>
                    """;

            Path file = tempDir.resolve("tech-report.html");
            Files.writeString(file, html);

            List<Document> docs = loadHtml(file);

            // Should have main doc + 2 table docs
            assertTrue(docs.size() >= 3,
                    "Should have at least 3 docs (main + 2 tables), got " + docs.size());

            Document mainDoc = docs.get(0);
            ExtractionResult result = extractGraph(mainDoc);

            // WEB_PAGE
            ExtractedEntity page = findEntity(result, "WEB_PAGE");
            assertNotNull(page);
            assertTrue(page.name().contains("Technology Report"));

            // PERSON from author
            assertNotNull(findRelation(result, "AUTHORED_BY"),
                    "Should have AUTHORED_BY from meta author");

            // TOPIC from keywords (4 keywords)
            List<ExtractedEntity> topics = findEntities(result, "TOPIC");
            assertTrue(topics.size() >= 3,
                    "Should have at least 3 TOPIC entities from keywords, got " + topics.size());

            // WEBSITE from og:site_name
            ExtractedEntity website = findEntity(result, "WEBSITE");
            assertNotNull(website);
            assertEquals("TechInsights", website.name());

            // DATE from published_time
            assertNotNull(findRelation(result, "PUBLISHED_ON"),
                    "Should have PUBLISHED_ON date relation");

            // External links from body
            int totalLinkEntities = findEntities(result, "EXTERNAL_LINK").size()
                    + findEntities(result, "EXTERNAL_RESOURCE").size();
            assertTrue(totalLinkEntities >= 1,
                    "Should have at least 1 external link entity");
        }
    }

    // ================================================================
    //  9. Minimal HTML (edge case)
    // ================================================================

    @Nested
    class MinimalHtml {

        @Test
        void minimalHtmlWithNoMetadata() throws Exception {
            String html = """
                    <html>
                    <body>
                    <p>Just some text with no metadata at all.</p>
                    </body>
                    </html>
                    """;

            Path file = tempDir.resolve("minimal.html");
            Files.writeString(file, html);

            List<Document> docs = loadHtml(file);
            assertFalse(docs.isEmpty());

            Document mainDoc = docs.get(0);
            assertTrue(mainDoc.getText().contains("Just some text"),
                    "Body text should be extracted even with minimal HTML");

            ExtractionResult result = extractGraph(mainDoc);
            ExtractedEntity page = findEntity(result, "WEB_PAGE");
            assertNotNull(page, "Should still produce WEB_PAGE entity");
        }

        @Test
        void htmlWithOnlySmallTableNoTableDoc() throws Exception {
            // Tables with fewer than 2 rows or 2 columns should not produce table docs
            String html = """
                    <!DOCTYPE html>
                    <html>
                    <head><title>Small Table</title></head>
                    <body>
                        <table>
                            <tr><td>Only one cell</td></tr>
                        </table>
                    </body>
                    </html>
                    """;

            Path file = tempDir.resolve("small-table.html");
            Files.writeString(file, html);

            List<Document> docs = loadHtml(file);
            // Should have only the main doc, no table doc
            long tableCount = docs.stream()
                    .filter(d -> "table".equals(d.getMetadata().get(GraphConstants.META_CONTENT_TYPE)))
                    .count();
            assertEquals(0, tableCount,
                    "Single-row/single-column tables should not produce table documents");
        }
    }

    // ================================================================
    //  10. Embedded media (iframes, videos)
    // ================================================================

    @Nested
    class HtmlWithEmbeddedMedia {

        @Test
        void htmlWithEmbeddedMediaExtractsMetadata() throws Exception {
            String html = """
                    <!DOCTYPE html>
                    <html>
                    <head><title>Product Demo</title></head>
                    <body>
                        <h1>Product Demo Video</h1>
                        <p>Watch our product demonstration below.</p>
                        <iframe src="https://www.youtube.com/embed/dQw4w9WgXcQ"
                                title="Product Demo" width="560" height="315"></iframe>
                        <video controls>
                            <source src="https://cdn.example.com/demo.mp4" type="video/mp4">
                        </video>
                    </body>
                    </html>
                    """;

            Path file = tempDir.resolve("demo.html");
            Files.writeString(file, html);

            List<Document> docs = loadHtml(file);
            Document mainDoc = docs.get(0);

            // Embedded media should be in metadata
            Object media = mainDoc.getMetadata().get("html.embeddedMedia");
            assertNotNull(media, "html.embeddedMedia should be populated");
            assertTrue(media instanceof List<?>);
            assertTrue(((List<?>) media).size() >= 1,
                    "Should have at least 1 embedded media entry");

            ExtractionResult result = extractGraph(mainDoc);
            assertNotNull(findEntity(result, "WEB_PAGE"));
        }
    }

    // ================================================================
    //  Helper methods
    // ================================================================

    private ExtractedEntity findEntity(ExtractionResult result, String type) {
        return result.entities().stream()
                .filter(e -> type.equals(e.type()))
                .findFirst().orElse(null);
    }

    private List<ExtractedEntity> findEntities(ExtractionResult result, String type) {
        return result.entities().stream()
                .filter(e -> type.equals(e.type()))
                .toList();
    }

    private ExtractedRelation findRelation(ExtractionResult result, String type) {
        return result.relations().stream()
                .filter(r -> type.equals(r.type()))
                .findFirst().orElse(null);
    }

    private List<ExtractedRelation> findRelations(ExtractionResult result, String type) {
        return result.relations().stream()
                .filter(r -> type.equals(r.type()))
                .toList();
    }
}
