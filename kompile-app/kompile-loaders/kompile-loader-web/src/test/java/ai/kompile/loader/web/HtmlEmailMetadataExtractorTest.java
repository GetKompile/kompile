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

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HtmlEmailMetadataExtractor}.
 * All tests use synthetic HTML — no dataset-specific content.
 */
class HtmlEmailMetadataExtractorTest {

    private HtmlEmailMetadataExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new HtmlEmailMetadataExtractor();
    }

    @Test
    void detectsEmailWithCssClassPatterns() {
        String html = """
                <html><body>
                  <h1 class="subject">Monthly Report</h1>
                  <div class="from-line">Alice Smith &lt;alice@example.com&gt;</div>
                  <div class="to-line">Bob Jones &lt;bob@example.com&gt;</div>
                  <div class="date-line">2025-05-20</div>
                  <p>Please review the attached report.</p>
                </body></html>
                """;
        Document doc = Jsoup.parse(html);
        Map<String, Object> metadata = new HashMap<>();

        boolean detected = extractor.detectAndExtract(doc, metadata);

        assertTrue(detected, "Should detect email via CSS class patterns");
        assertEquals("Monthly Report", metadata.get("email.subject"));
        assertEquals("2025-05-20", metadata.get("email.date"));
    }

    @Test
    void detectsEmailWithTextPatterns() {
        String html = """
                <html><body>
                From: Jane Doe &lt;jane@example.com&gt;
                To: John Smith &lt;john@example.com&gt;
                Cc: Team &lt;team@example.com&gt;
                Date: May 20, 2025
                Subject: Quarterly Update

                Hi John,
                Please see the attached spreadsheet.
                </body></html>
                """;
        Document doc = Jsoup.parse(html);
        Map<String, Object> metadata = new HashMap<>();

        boolean detected = extractor.detectAndExtract(doc, metadata);

        assertTrue(detected, "Should detect email via text patterns");
        assertNotNull(metadata.get("email.from"));
    }

    @Test
    void doesNotDetectNonEmailHtml() {
        String html = """
                <html><body>
                  <h1>Company Website</h1>
                  <p>Welcome to our company website. Click here to learn more.</p>
                  <nav><a href="/about">About</a><a href="/contact">Contact</a></nav>
                </body></html>
                """;
        Document doc = Jsoup.parse(html);
        Map<String, Object> metadata = new HashMap<>();

        boolean detected = extractor.detectAndExtract(doc, metadata);

        assertFalse(detected, "Should not detect a regular web page as email");
        assertTrue(metadata.isEmpty(), "Should not add metadata for non-email HTML");
    }

    @Test
    void extractsAttachmentFilenameFromHref() {
        String html = """
                <html><body>
                  <div class="subject">Report Distribution</div>
                  <div class="from-line">Sender &lt;sender@example.com&gt;</div>
                  <div class="to-line">Recipient &lt;recipient@example.com&gt;</div>
                  <p>Please find the attached file:</p>
                  <a href="/attachments/Q3_Report_Final.xlsx">Q3_Report_Final.xlsx</a>
                  <a href="/attachments/Q3_Report_Final.xlsx">Download</a>
                  <a href="/attachments/Q3_Report_Final.xlsx">Open</a>
                </body></html>
                """;
        Document doc = Jsoup.parse(html);
        Map<String, Object> metadata = new HashMap<>();

        extractor.detectAndExtract(doc, metadata);

        @SuppressWarnings("unchecked")
        List<String> attachments = (List<String>) metadata.get("email.attachmentNames");
        assertNotNull(attachments, "Should extract attachment names");
        assertEquals(1, attachments.size(), "Should deduplicate links pointing to the same file");
        assertEquals("Q3_Report_Final.xlsx", attachments.get(0),
                "Should use filename from href path, not link text");
    }

    @Test
    void extractsMultipleDistinctAttachments() {
        String html = """
                <html><body>
                  <div class="subject">Multi-attachment email</div>
                  <div class="from-line">Sender &lt;sender@example.com&gt;</div>
                  <div class="to-line">Recipient &lt;recipient@example.com&gt;</div>
                  <a href="/files/budget.xlsx">Budget Spreadsheet</a>
                  <a href="/files/summary.pdf">Summary Document</a>
                  <a href="/files/slides.pptx">Presentation</a>
                </body></html>
                """;
        Document doc = Jsoup.parse(html);
        Map<String, Object> metadata = new HashMap<>();

        extractor.detectAndExtract(doc, metadata);

        @SuppressWarnings("unchecked")
        List<String> attachments = (List<String>) metadata.get("email.attachmentNames");
        assertNotNull(attachments);
        assertEquals(3, attachments.size());
        assertTrue(attachments.contains("budget.xlsx"));
        assertTrue(attachments.contains("summary.pdf"));
        assertTrue(attachments.contains("slides.pptx"));
    }

    @Test
    void handlesAttachmentLinksWithQueryParams() {
        String html = """
                <html><body>
                  <div class="subject">Download Link</div>
                  <div class="from-line">Sender &lt;sender@example.com&gt;</div>
                  <div class="to-line">Recipient &lt;recipient@example.com&gt;</div>
                  <a href="/download/report.xlsx?token=abc123&version=2">report.xlsx</a>
                </body></html>
                """;
        Document doc = Jsoup.parse(html);
        Map<String, Object> metadata = new HashMap<>();

        extractor.detectAndExtract(doc, metadata);

        @SuppressWarnings("unchecked")
        List<String> attachments = (List<String>) metadata.get("email.attachmentNames");
        assertNotNull(attachments);
        assertEquals(1, attachments.size());
        assertEquals("report.xlsx", attachments.get(0));
    }

    @Test
    void needsMinimumTwoSignalsForCssDetection() {
        // Only one CSS signal (subject only) — not enough
        String html = """
                <html><body>
                  <h1 class="subject">Some Title</h1>
                  <p>This is just a page with a subject class.</p>
                </body></html>
                """;
        Document doc = Jsoup.parse(html);
        Map<String, Object> metadata = new HashMap<>();

        boolean detected = extractor.detectAndExtract(doc, metadata);
        assertFalse(detected, "Single CSS signal should not trigger email detection");
    }
}
