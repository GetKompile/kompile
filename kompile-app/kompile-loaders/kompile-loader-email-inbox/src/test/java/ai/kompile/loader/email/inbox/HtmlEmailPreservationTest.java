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

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that raw HTML content is preserved in email.htmlBody metadata
 * across Mime4jMessageParser for both plain HTML emails and
 * multipart/alternative emails.
 */
class HtmlEmailPreservationTest {

    // ── HTML-only email preserves raw HTML ──────────────────────────────────

    private static final String HTML_ONLY_EMAIL =
            "From: alice@example.com\r\n" +
            "To: bob@example.com\r\n" +
            "Subject: HTML Only\r\n" +
            "Message-ID: <html1@example.com>\r\n" +
            "MIME-Version: 1.0\r\n" +
            "Content-Type: text/html; charset=UTF-8\r\n" +
            "\r\n" +
            "<html><head><style>body{color:red}</style></head>" +
            "<body><h1>Hello World</h1><p>This is <b>bold</b> and <i>italic</i>.</p></body></html>\r\n";

    @Test
    void htmlOnlyEmailPreservesRawHtml() throws Exception {
        Mime4jMessageParser parser = new Mime4jMessageParser(false, true);
        List<Document> docs = parser.parse(
                new ByteArrayInputStream(HTML_ONLY_EMAIL.getBytes(StandardCharsets.UTF_8)),
                "/test/html-only.eml");

        assertEquals(1, docs.size());
        Document doc = docs.get(0);

        // Raw HTML should be in metadata
        String rawHtml = (String) doc.getMetadata().get("email.htmlBody");
        assertNotNull(rawHtml, "email.htmlBody should be present for HTML-only emails");
        assertTrue(rawHtml.contains("<h1>Hello World</h1>"), "Raw HTML should contain original tags");
        assertTrue(rawHtml.contains("<b>bold</b>"), "Raw HTML should preserve inline formatting");

        // Display text should be converted from HTML (not raw HTML)
        String content = doc.getText();
        assertTrue(content.contains("Hello World"));
        assertFalse(content.contains("<h1>"), "Display text should not contain raw HTML tags");
    }

    // ── Multipart/alternative preserves HTML from HTML part ─────────────────

    private static final String MULTIPART_ALT_EMAIL =
            "From: alice@example.com\r\n" +
            "To: bob@example.com\r\n" +
            "Subject: Multipart Alternative\r\n" +
            "Message-ID: <alt1@example.com>\r\n" +
            "MIME-Version: 1.0\r\n" +
            "Content-Type: multipart/alternative; boundary=\"alt-boundary\"\r\n" +
            "\r\n" +
            "--alt-boundary\r\n" +
            "Content-Type: text/plain; charset=UTF-8\r\n" +
            "\r\n" +
            "Plain text version of the email.\r\n" +
            "--alt-boundary\r\n" +
            "Content-Type: text/html; charset=UTF-8\r\n" +
            "\r\n" +
            "<html><body><p>Plain text version of the email.</p><img src=\"logo.png\" alt=\"Logo\"/></body></html>\r\n" +
            "--alt-boundary--\r\n";

    @Test
    void multipartAlternativePreservesHtmlInMetadata() throws Exception {
        Mime4jMessageParser parser = new Mime4jMessageParser(false, true);
        List<Document> docs = parser.parse(
                new ByteArrayInputStream(MULTIPART_ALT_EMAIL.getBytes(StandardCharsets.UTF_8)),
                "/test/multipart-alt.eml");

        assertEquals(1, docs.size());
        Document doc = docs.get(0);

        // Display text should use plain text part (preferred over HTML)
        String content = doc.getText();
        assertTrue(content.contains("Plain text version"));

        // Raw HTML should still be in metadata
        String rawHtml = (String) doc.getMetadata().get("email.htmlBody");
        assertNotNull(rawHtml, "email.htmlBody should be present even when plain text is used for display");
        assertTrue(rawHtml.contains("<img src=\"logo.png\""), "HTML should preserve image tags");
        assertTrue(rawHtml.contains("<p>"), "HTML should contain paragraph tags");
    }

    // ── HTML fallback when no plain text ────────────────────────────────────

    @Test
    void htmlFallbackWhenNoPlainTextWithIncludeHtml() throws Exception {
        Mime4jMessageParser parser = new Mime4jMessageParser(false, true);
        List<Document> docs = parser.parse(
                new ByteArrayInputStream(HTML_ONLY_EMAIL.getBytes(StandardCharsets.UTF_8)),
                "/test/html-fallback.eml");

        Document doc = docs.get(0);
        String content = doc.getText();

        // With includeHtmlBody=true, should use convertHtmlToText (preserves structure)
        assertTrue(content.contains("Hello World"));
        assertTrue(content.contains("bold"));
    }

    @Test
    void htmlFallbackWithoutIncludeHtmlStillPreservesRaw() throws Exception {
        Mime4jMessageParser parser = new Mime4jMessageParser(false, false);
        List<Document> docs = parser.parse(
                new ByteArrayInputStream(HTML_ONLY_EMAIL.getBytes(StandardCharsets.UTF_8)),
                "/test/html-no-include.eml");

        Document doc = docs.get(0);

        // Raw HTML should still be in metadata regardless of includeHtmlBody
        String rawHtml = (String) doc.getMetadata().get("email.htmlBody");
        assertNotNull(rawHtml, "email.htmlBody should be present even with includeHtmlBody=false");
        assertTrue(rawHtml.contains("<h1>Hello World</h1>"));
    }

    // ── Plain-text-only email has no htmlBody metadata ──────────────────────

    private static final String PLAIN_TEXT_EMAIL =
            "From: alice@example.com\r\n" +
            "To: bob@example.com\r\n" +
            "Subject: Plain Text Only\r\n" +
            "Message-ID: <plain1@example.com>\r\n" +
            "MIME-Version: 1.0\r\n" +
            "Content-Type: text/plain; charset=UTF-8\r\n" +
            "\r\n" +
            "This is a plain text email with no HTML.\r\n";

    @Test
    void plainTextEmailHasNoHtmlBodyMetadata() throws Exception {
        Mime4jMessageParser parser = new Mime4jMessageParser(false, true);
        List<Document> docs = parser.parse(
                new ByteArrayInputStream(PLAIN_TEXT_EMAIL.getBytes(StandardCharsets.UTF_8)),
                "/test/plain.eml");

        Document doc = docs.get(0);
        assertNull(doc.getMetadata().get("email.htmlBody"),
                "Plain text email should not have email.htmlBody metadata");
        assertTrue(doc.getText().contains("This is a plain text email"));
    }

    // ── Complex HTML with tables and links ──────────────────────────────────

    private static final String COMPLEX_HTML_EMAIL =
            "From: newsletter@example.com\r\n" +
            "To: subscriber@example.com\r\n" +
            "Subject: Weekly Newsletter\r\n" +
            "Message-ID: <news1@example.com>\r\n" +
            "MIME-Version: 1.0\r\n" +
            "Content-Type: text/html; charset=UTF-8\r\n" +
            "\r\n" +
            "<!DOCTYPE html>\r\n" +
            "<html>\r\n" +
            "<head><style>table { border-collapse: collapse; }</style></head>\r\n" +
            "<body>\r\n" +
            "<table>\r\n" +
            "  <tr><th>Item</th><th>Price</th></tr>\r\n" +
            "  <tr><td>Widget</td><td>$10</td></tr>\r\n" +
            "  <tr><td>Gadget</td><td>$20</td></tr>\r\n" +
            "</table>\r\n" +
            "<a href=\"https://example.com/unsubscribe\">Unsubscribe</a>\r\n" +
            "</body>\r\n" +
            "</html>\r\n";

    @Test
    void complexHtmlPreservesTableStructure() throws Exception {
        Mime4jMessageParser parser = new Mime4jMessageParser(false, true);
        List<Document> docs = parser.parse(
                new ByteArrayInputStream(COMPLEX_HTML_EMAIL.getBytes(StandardCharsets.UTF_8)),
                "/test/newsletter.eml");

        Document doc = docs.get(0);
        String rawHtml = (String) doc.getMetadata().get("email.htmlBody");

        assertNotNull(rawHtml);
        assertTrue(rawHtml.contains("<table>"));
        assertTrue(rawHtml.contains("<th>Item</th>"));
        assertTrue(rawHtml.contains("https://example.com/unsubscribe"));
    }

    // ── Nested multipart/mixed with HTML body ───────────────────────────────

    private static final String MIXED_WITH_HTML =
            "From: alice@example.com\r\n" +
            "To: bob@example.com\r\n" +
            "Subject: Mixed With HTML\r\n" +
            "Message-ID: <mixed1@example.com>\r\n" +
            "MIME-Version: 1.0\r\n" +
            "Content-Type: multipart/mixed; boundary=\"mixed-boundary\"\r\n" +
            "\r\n" +
            "--mixed-boundary\r\n" +
            "Content-Type: multipart/alternative; boundary=\"alt-boundary\"\r\n" +
            "\r\n" +
            "--alt-boundary\r\n" +
            "Content-Type: text/plain; charset=UTF-8\r\n" +
            "\r\n" +
            "See attached report.\r\n" +
            "--alt-boundary\r\n" +
            "Content-Type: text/html; charset=UTF-8\r\n" +
            "\r\n" +
            "<html><body><p>See attached <strong>report</strong>.</p></body></html>\r\n" +
            "--alt-boundary--\r\n" +
            "--mixed-boundary\r\n" +
            "Content-Type: application/pdf; name=\"report.pdf\"\r\n" +
            "Content-Disposition: attachment; filename=\"report.pdf\"\r\n" +
            "Content-Transfer-Encoding: base64\r\n" +
            "\r\n" +
            "JVBERi0xLjQK\r\n" +
            "--mixed-boundary--\r\n";

    @Test
    void nestedMultipartPreservesHtmlFromAlternativePart() throws Exception {
        Mime4jMessageParser parser = new Mime4jMessageParser(true, true);
        List<Document> docs = parser.parse(
                new ByteArrayInputStream(MIXED_WITH_HTML.getBytes(StandardCharsets.UTF_8)),
                "/test/mixed.eml");

        // First doc is the email body
        Document emailDoc = docs.get(0);
        String rawHtml = (String) emailDoc.getMetadata().get("email.htmlBody");
        assertNotNull(rawHtml, "Should preserve HTML from alternative part within mixed message");
        assertTrue(rawHtml.contains("<strong>report</strong>"));

        // Display text should use plain text version
        assertTrue(emailDoc.getText().contains("See attached report"));
    }
}
