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

import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.loaders.DocumentSourceDescriptor.SourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebHtmlLoaderImplTest {

    private WebHtmlLoaderImpl loader;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        loader = new WebHtmlLoaderImpl();
    }

    // ── Name ────────────────────────────────────────────────────────────────

    @Test
    void nameIsDescriptive() {
        assertEquals("Web/HTML Loader", loader.getName());
    }

    // ── supports() ──────────────────────────────────────────────────────────

    @Test
    void supportsUrlSourceType() {
        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.URL)
                .pathOrUrl("https://example.com")
                .build();
        assertTrue(loader.supports(desc));
    }

    @Test
    void supportsHtmlFileByExtension() {
        Path htmlFile = tempDir.resolve("page.html");
        writeFile(htmlFile, "<html><body>test</body></html>");

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl(htmlFile.toString())
                .build();
        assertTrue(loader.supports(desc));
    }

    @Test
    void supportsHtmExtension() {
        Path htmFile = tempDir.resolve("page.htm");
        writeFile(htmFile, "<html><body>test</body></html>");

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl(htmFile.toString())
                .build();
        assertTrue(loader.supports(desc));
    }

    @Test
    void supportsXhtmlExtension() {
        Path xhtmlFile = tempDir.resolve("page.xhtml");
        writeFile(xhtmlFile, "<html><body>test</body></html>");

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl(xhtmlFile.toString())
                .build();
        assertTrue(loader.supports(desc));
    }

    @Test
    void supportsShtmlExtension() {
        Path shtmlFile = tempDir.resolve("page.shtml");
        writeFile(shtmlFile, "<html><body>test</body></html>");

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl(shtmlFile.toString())
                .build();
        assertTrue(loader.supports(desc));
    }

    @Test
    void supportsFileWithHtmlContentButNoExtension() {
        Path noExtFile = tempDir.resolve("noext");
        writeFile(noExtFile, "<!doctype html><html><head><title>Test</title></head><body>content</body></html>");

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl(noExtFile.toString())
                .build();
        assertTrue(loader.supports(desc), "Should detect HTML content via sniffing");
    }

    @Test
    void doesNotSupportNonHtmlFile() {
        Path txtFile = tempDir.resolve("readme.txt");
        writeFile(txtFile, "This is plain text, not HTML at all.");

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl(txtFile.toString())
                .build();
        assertFalse(loader.supports(desc));
    }

    @Test
    void doesNotSupportNull() {
        assertFalse(loader.supports(null));
    }

    @Test
    void doesNotSupportNullPath() {
        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .build();
        assertFalse(loader.supports(desc));
    }

    @Test
    void doesNotSupportEmptyPath() {
        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl("")
                .build();
        assertFalse(loader.supports(desc));
    }

    @Test
    void doesNotSupportDirectorySourceType() {
        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.DIRECTORY)
                .pathOrUrl(tempDir.toString())
                .build();
        assertFalse(loader.supports(desc));
    }

    // ── load() for FILE ─────────────────────────────────────────────────────

    @Test
    void loadsHtmlFileAndExtractsText() throws Exception {
        String html = "<!DOCTYPE html><html><head><title>Test Page</title></head>"
                + "<body><h1>Hello World</h1><p>This is a test paragraph.</p></body></html>";
        Path htmlFile = tempDir.resolve("test.html");
        writeFile(htmlFile, html);

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl(htmlFile.toString())
                .build();

        List<Document> docs = loader.load(desc);
        assertEquals(1, docs.size());

        Document doc = docs.get(0);
        String text = doc.getText();
        assertTrue(text.contains("Hello World"));
        assertTrue(text.contains("This is a test paragraph"));
        assertFalse(text.contains("<h1>"), "Should strip HTML tags");
    }

    @Test
    void loadsHtmlFileWithMetadata() throws Exception {
        String html = "<!DOCTYPE html>" +
                "<html lang=\"en\">" +
                "<head>" +
                "  <title>Metadata Page</title>" +
                "  <meta name=\"description\" content=\"A page about testing\">" +
                "  <meta name=\"author\" content=\"Test Author\">" +
                "  <meta name=\"keywords\" content=\"test, html\">" +
                "  <meta property=\"og:title\" content=\"OG Title\">" +
                "  <meta property=\"og:description\" content=\"OG Description\">" +
                "  <meta property=\"og:site_name\" content=\"Test Site\">" +
                "  <link rel=\"canonical\" href=\"https://example.com/test\">" +
                "</head>" +
                "<body><p>Content</p></body>" +
                "</html>";
        Path htmlFile = tempDir.resolve("meta.html");
        writeFile(htmlFile, html);

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl(htmlFile.toString())
                .build();

        List<Document> docs = loader.load(desc);
        Document doc = docs.get(0);
        Map<String, Object> meta = doc.getMetadata();

        assertEquals("Metadata Page", meta.get("title"));
        assertEquals("A page about testing", meta.get("description"));
        assertEquals("Test Author", meta.get("author"));
        assertEquals("test, html", meta.get("keywords"));
        assertEquals("OG Title", meta.get("ogTitle"));
        assertEquals("OG Description", meta.get("ogDescription"));
        assertEquals("Test Site", meta.get("siteName"));
        assertEquals("https://example.com/test", meta.get("canonicalUrl"));
        assertEquals("en", meta.get("language"));
        assertEquals(htmlFile.toString(), meta.get("source"));
        assertEquals("Web/HTML Loader", meta.get("loader"));
    }

    @Test
    void stripsScriptAndStyleElements() throws Exception {
        String html = "<html><head><style>body{color:red}</style></head>"
                + "<body>"
                + "<script>alert('hello')</script>"
                + "<p>Visible content</p>"
                + "<noscript>No JS fallback</noscript>"
                + "</body></html>";
        Path htmlFile = tempDir.resolve("scripts.html");
        writeFile(htmlFile, html);

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl(htmlFile.toString())
                .build();

        List<Document> docs = loader.load(desc);
        String text = docs.get(0).getText();
        assertTrue(text.contains("Visible content"));
        assertFalse(text.contains("alert"), "Should strip script content");
        assertFalse(text.contains("color:red"), "Should strip style content");
    }

    @Test
    void stripsHiddenElements() throws Exception {
        String html = "<html><body>"
                + "<p>Visible</p>"
                + "<div hidden>Hidden via attribute</div>"
                + "<div style=\"display: none\">Hidden via style</div>"
                + "<p>Also visible</p>"
                + "</body></html>";
        Path htmlFile = tempDir.resolve("hidden.html");
        writeFile(htmlFile, html);

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl(htmlFile.toString())
                .build();

        List<Document> docs = loader.load(desc);
        String text = docs.get(0).getText();
        assertTrue(text.contains("Visible"));
        assertTrue(text.contains("Also visible"));
        assertFalse(text.contains("Hidden via attribute"));
        assertFalse(text.contains("Hidden via style"));
    }

    @Test
    void preservesOriginalFileName() throws Exception {
        Path htmlFile = tempDir.resolve("report.html");
        writeFile(htmlFile, "<html><body>Report</body></html>");

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl(htmlFile.toString())
                .originalFileName("quarterly-report.html")
                .build();

        List<Document> docs = loader.load(desc);
        assertEquals("quarterly-report.html", docs.get(0).getMetadata().get("fileName"));
    }

    @Test
    void includesSourceDescriptorMetadata() throws Exception {
        Path htmlFile = tempDir.resolve("page.html");
        writeFile(htmlFile, "<html><body>Content</body></html>");

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl(htmlFile.toString())
                .metadata(Map.of("custom.tag", "value123"))
                .build();

        List<Document> docs = loader.load(desc);
        assertEquals("value123", docs.get(0).getMetadata().get("custom.tag"));
    }

    // ── Error handling ──────────────────────────────────────────────────────

    @Test
    void throwsForNonexistentFile() {
        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl("/nonexistent/file.html")
                .build();

        assertThrows(Exception.class, () -> loader.load(desc));
    }

    @Test
    void throwsForDirectory() {
        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl(tempDir.toString())
                .build();

        assertThrows(Exception.class, () -> loader.load(desc));
    }

    @Test
    void handlesEmptyHtmlFile() throws Exception {
        Path emptyFile = tempDir.resolve("empty.html");
        writeFile(emptyFile, "");

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl(emptyFile.toString())
                .build();

        List<Document> docs = loader.load(desc);
        assertEquals(1, docs.size());
    }

    @Test
    void handlesMinimalHtml() throws Exception {
        Path htmlFile = tempDir.resolve("minimal.html");
        writeFile(htmlFile, "<p>Just a paragraph</p>");

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl(htmlFile.toString())
                .build();

        List<Document> docs = loader.load(desc);
        assertTrue(docs.get(0).getText().contains("Just a paragraph"));
    }

    // ── Twitter card metadata ───────────────────────────────────────────────

    @Test
    void extractsTwitterCardMetadata() throws Exception {
        String html = "<html><head>" +
                "<meta name=\"twitter:title\" content=\"Tweet Title\">" +
                "<meta name=\"twitter:description\" content=\"Tweet Description\">" +
                "</head><body>Content</body></html>";
        Path htmlFile = tempDir.resolve("twitter.html");
        writeFile(htmlFile, html);

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl(htmlFile.toString())
                .build();

        List<Document> docs = loader.load(desc);
        Map<String, Object> meta = docs.get(0).getMetadata();
        assertEquals("Tweet Title", meta.get("twitterTitle"));
        assertEquals("Tweet Description", meta.get("twitterDescription"));
    }

    // ── Structural mode: table extraction ──────────────────────────────────

    @Test
    void structuralModeExtractsTablesAsMarkdown() throws Exception {
        String html = "<html><body>"
                + "<h2>Revenue Summary</h2>"
                + "<table>"
                + "<tr><th>Region</th><th>Q1</th><th>Q2</th></tr>"
                + "<tr><td>North</td><td>100</td><td>120</td></tr>"
                + "<tr><td>South</td><td>80</td><td>95</td></tr>"
                + "</table>"
                + "<p>Overall growth was positive.</p>"
                + "</body></html>";
        Path htmlFile = tempDir.resolve("tables.html");
        writeFile(htmlFile, html);

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl(htmlFile.toString())
                .metadata(new java.util.HashMap<>(Map.of("structuralMode", true)))
                .build();

        List<Document> docs = loader.load(desc);

        // Should have at least 1 table doc + 1 prose doc
        assertTrue(docs.size() >= 2, "Expected table + prose documents, got " + docs.size());

        Document tablDoc = docs.stream()
                .filter(d -> "table".equals(d.getMetadata().get("content_type")))
                .findFirst().orElse(null);
        assertNotNull(tablDoc, "Should produce a table document");

        // Table metadata
        assertEquals("table", tablDoc.getMetadata().get("content_type"));
        assertEquals("html-jsoup", tablDoc.getMetadata().get("table_extraction_method"));
        assertNotNull(tablDoc.getMetadata().get("table_row_count"));
        assertNotNull(tablDoc.getMetadata().get("table_column_count"));

        // full_table_content should be markdown with pipe delimiters
        String fullTable = (String) tablDoc.getMetadata().get("full_table_content");
        assertNotNull(fullTable, "full_table_content should be set");
        assertTrue(fullTable.contains("|"), "Table markdown should use pipe delimiters");
        assertTrue(fullTable.contains("Region"), "Table should contain header text");
        assertTrue(fullTable.contains("120"), "Table should contain cell data");

        // Prose section should contain the paragraph text
        Document proseDoc = docs.stream()
                .filter(d -> "text".equals(d.getMetadata().get("content_type")))
                .findFirst().orElse(null);
        assertNotNull(proseDoc, "Should produce a prose document");
        assertTrue(proseDoc.getText().contains("growth was positive"));
    }

    @Test
    void structuralModeExtractsTableHeaders() throws Exception {
        String html = "<html><body>"
                + "<table><thead><tr><th>Name</th><th>Amount</th></tr></thead>"
                + "<tbody><tr><td>Item A</td><td>50</td></tr></tbody></table>"
                + "</body></html>";
        Path htmlFile = tempDir.resolve("headers.html");
        writeFile(htmlFile, html);

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl(htmlFile.toString())
                .metadata(new java.util.HashMap<>(Map.of("structuralMode", true)))
                .build();

        List<Document> docs = loader.load(desc);
        Document tablDoc = docs.stream()
                .filter(d -> "table".equals(d.getMetadata().get("content_type")))
                .findFirst().orElse(null);
        assertNotNull(tablDoc);

        String headers = (String) tablDoc.getMetadata().get("table_headers");
        assertNotNull(headers, "table_headers should be set");
        assertTrue(headers.contains("Name"));
        assertTrue(headers.contains("Amount"));
    }

    @Test
    void structuralModeNestedTablesNotDuplicated() throws Exception {
        // Outer table has 2 direct rows (header + data); inner table nested inside a cell.
        // The fix: only direct <tr> children are counted, so inner rows don't bleed into outer.
        String html = "<html><body>"
                + "<table>"
                + "  <tr><th>Col A</th><th>Col B</th></tr>"
                + "  <tr><td>Outer row"
                + "    <table><tr><td>Inner A</td><td>Inner B</td></tr>"
                + "           <tr><td>Inner C</td><td>Inner D</td></tr></table>"
                + "  </td><td>Val</td></tr>"
                + "</table>"
                + "</body></html>";
        Path htmlFile = tempDir.resolve("nested.html");
        writeFile(htmlFile, html);

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl(htmlFile.toString())
                .metadata(new java.util.HashMap<>(Map.of("structuralMode", true)))
                .build();

        List<Document> docs = loader.load(desc);
        List<Document> tableDocs = docs.stream()
                .filter(d -> "table".equals(d.getMetadata().get("content_type")))
                .toList();
        // Outer table should be extracted (2 direct rows); inner rows must not bleed into it
        assertTrue(tableDocs.size() >= 1, "Should extract at least the outer table");
        for (Document td : tableDocs) {
            int rowCount = ((Number) td.getMetadata().getOrDefault("table_row_count", 0)).intValue();
            // Outer table has exactly 2 direct rows, not 4
            assertTrue(rowCount <= 2, "Outer table should not include inner table rows; got " + rowCount);
        }
    }

    @Test
    void autoDetectsStructuralModeWhenTablesPresent() throws Exception {
        String html = "<html><body>"
                + "<p>Introduction text</p>"
                + "<table><tr><th>A</th><th>B</th></tr><tr><td>1</td><td>2</td></tr></table>"
                + "</body></html>";
        Path htmlFile = tempDir.resolve("auto_structural.html");
        writeFile(htmlFile, html);

        // No explicit structuralMode — should auto-detect because tables are present
        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl(htmlFile.toString())
                .build();

        List<Document> docs = loader.load(desc);
        // Should have produced table + prose sections via structural mode
        assertTrue(docs.size() >= 2, "Auto-detect should activate structural mode when tables exist");
        assertTrue(docs.stream().anyMatch(d -> "table".equals(d.getMetadata().get("content_type"))),
                "Should extract table as separate document");
    }

    @Test
    void flatModeForSimpleHtmlWithNoTables() throws Exception {
        String html = "<html><body><p>Simple content</p></body></html>";
        Path htmlFile = tempDir.resolve("flat.html");
        writeFile(htmlFile, html);

        // No structuralMode in metadata, no tables -> flat extraction
        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl(htmlFile.toString())
                .build();

        List<Document> docs = loader.load(desc);
        assertEquals(1, docs.size(), "No-table HTML should use flat mode");
        assertNull(docs.get(0).getMetadata().get("content_type"),
                "Flat mode should not set content_type");
    }

    @Test
    void flatModeCanBeExplicitlyForced() throws Exception {
        String html = "<html><body>"
                + "<table><tr><td>data</td></tr></table>"
                + "</body></html>";
        Path htmlFile = tempDir.resolve("forced_flat.html");
        writeFile(htmlFile, html);

        // Force flat mode even though tables exist
        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl(htmlFile.toString())
                .metadata(new java.util.HashMap<>(Map.of("structuralMode", false)))
                .build();

        List<Document> docs = loader.load(desc);
        assertEquals(1, docs.size(), "Forced flat mode should produce single document");
        assertNull(docs.get(0).getMetadata().get("content_type"),
                "Forced flat mode should not set content_type");
    }

    // ── Structural mode: email detection ────────────────────────────────────

    @Test
    void structuralModeDetectsEmailByCssClasses() throws Exception {
        String html = "<html><body>"
                + "<h1 class=\"subject\">Q3 Forecast Update</h1>"
                + "<div class=\"from-line\">Alice Baker &lt;alice@example.com&gt;</div>"
                + "<div class=\"to-line\">Bob Clark &lt;bob@example.com&gt;</div>"
                + "<div class=\"date-line\">2026-05-15</div>"
                + "<p>Please review the attached forecast.</p>"
                + "<a href=\"forecast_v2.xlsx\">Download Forecast</a>"
                + "</body></html>";
        Path htmlFile = tempDir.resolve("email_css.html");
        writeFile(htmlFile, html);

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl(htmlFile.toString())
                .metadata(new java.util.HashMap<>(Map.of("structuralMode", true)))
                .build();

        List<Document> docs = loader.load(desc);
        assertFalse(docs.isEmpty());

        // All docs should inherit email metadata
        Map<String, Object> meta = docs.get(0).getMetadata();
        assertEquals("email", meta.get("content_type_hint"));
        assertEquals("Q3 Forecast Update", meta.get("email.subject"));
        assertNotNull(meta.get("email.from"), "email.from should be populated");
        assertTrue(meta.get("email.from").toString().contains("alice@example.com"));
    }

    @Test
    void structuralModeDetectsEmailByTextPatterns() throws Exception {
        String html = "<html><body>"
                + "<p>From: Jane Doe &lt;jane@corp.co&gt;</p>"
                + "<p>To: John Smith &lt;john@corp.co&gt;</p>"
                + "<p>Subject: Monthly Report</p>"
                + "<p>Date: 2026-05-20</p>"
                + "<p>Here is the monthly report.</p>"
                + "</body></html>";
        Path htmlFile = tempDir.resolve("email_text.html");
        writeFile(htmlFile, html);

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl(htmlFile.toString())
                .metadata(new java.util.HashMap<>(Map.of("structuralMode", true)))
                .build();

        List<Document> docs = loader.load(desc);
        assertFalse(docs.isEmpty());

        Map<String, Object> meta = docs.get(0).getMetadata();
        assertEquals("email", meta.get("content_type_hint"));
        assertNotNull(meta.get("email.from"));
        assertTrue(meta.get("email.from").toString().contains("jane@corp.co"));
    }

    @Test
    void structuralModeExtractsAttachmentLinks() throws Exception {
        String html = "<html><body>"
                + "<div class=\"subject\">Report</div>"
                + "<div class=\"from-line\">sender@test.com</div>"
                + "<div class=\"to-line\">recipient@test.com</div>"
                + "<p>See attachments:</p>"
                + "<a href=\"data/report.pdf\">Annual Report</a>"
                + "<a href=\"data/numbers.xlsx\">Numbers Spreadsheet</a>"
                + "<a href=\"https://example.com/page\">External Link</a>"
                + "</body></html>";
        Path htmlFile = tempDir.resolve("email_attach.html");
        writeFile(htmlFile, html);

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl(htmlFile.toString())
                .metadata(new java.util.HashMap<>(Map.of("structuralMode", true)))
                .build();

        List<Document> docs = loader.load(desc);
        Map<String, Object> meta = docs.get(0).getMetadata();

        @SuppressWarnings("unchecked")
        List<String> attachments = (List<String>) meta.get("email.attachmentNames");
        assertNotNull(attachments, "email.attachmentNames should be populated");
        assertEquals(2, attachments.size(), "Should find 2 attachment links (pdf + xlsx)");
    }

    @Test
    void nonEmailHtmlDoesNotSetEmailMetadata() throws Exception {
        String html = "<html><body>"
                + "<h1>Regular Web Page</h1>"
                + "<p>No email fields here at all.</p>"
                + "</body></html>";
        Path htmlFile = tempDir.resolve("not_email.html");
        writeFile(htmlFile, html);

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl(htmlFile.toString())
                .metadata(new java.util.HashMap<>(Map.of("structuralMode", true)))
                .build();

        List<Document> docs = loader.load(desc);
        Map<String, Object> meta = docs.get(0).getMetadata();
        assertNull(meta.get("content_type_hint"), "Non-email should not set content_type_hint");
        assertNull(meta.get("email.from"), "Non-email should not set email.from");
    }

    // ── HtmlEmailMetadataExtractor standalone tests ─────────────────────────

    @Test
    void emailExtractorParsesSenderNameAndEmail() {
        HtmlEmailMetadataExtractor extractor = new HtmlEmailMetadataExtractor();
        String html = "<html><body>"
                + "<div class=\"from-line\">"
                + "  <span class=\"name\">Jane Doe</span>"
                + "  <span class=\"email\">&lt;j.doe@example.com&gt;</span>"
                + "</div>"
                + "<div class=\"to-line\">Bob Smith &lt;b.smith@example.com&gt;</div>"
                + "<div class=\"date-line\">May 20, 2026</div>"
                + "</body></html>";

        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html);
        Map<String, Object> metadata = new java.util.HashMap<>();
        boolean detected = extractor.detectAndExtract(doc, metadata);

        assertTrue(detected, "Should detect email");
        assertEquals("Jane Doe", metadata.get("email.fromName"));
        assertEquals("j.doe@example.com", metadata.get("email.fromAddress"));
    }

    @Test
    void emailExtractorRequiresMultipleSignals() {
        HtmlEmailMetadataExtractor extractor = new HtmlEmailMetadataExtractor();
        // Only one signal (date) is not enough
        String html = "<html><body>"
                + "<div class=\"date-line\">May 20, 2026</div>"
                + "<p>Regular content</p>"
                + "</body></html>";

        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html);
        Map<String, Object> metadata = new java.util.HashMap<>();
        boolean detected = extractor.detectAndExtract(doc, metadata);

        assertFalse(detected, "Single signal should not be enough to detect email");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void writeFile(Path path, String content) {
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
