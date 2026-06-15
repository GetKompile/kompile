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

/**
 * Tests that {@link WebHtmlLoaderImpl} runs the email extractor in flat mode,
 * i.e. for HTML without tables (which would otherwise never trigger the
 * structural-mode email extraction path that pre-dates this fix).
 */
class WebHtmlLoaderFlatModeEmailTest {

    private WebHtmlLoaderImpl loader;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        loader = new WebHtmlLoaderImpl();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void writeFile(Path path, String content) {
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * A simple HTML email page with From/To/Subject text patterns and no tables
     * must be loaded in flat mode and must expose email.from and email.to in the
     * returned document's metadata.
     */
    @Test
    void flatMode_detectsEmailMetadata_setsEmailKeys() throws Exception {
        String html = "<html><body>"
                + "<p>From: Alice Sender &lt;alice@example.com&gt;</p>"
                + "<p>To: Bob Receiver &lt;bob@example.com&gt;</p>"
                + "<p>Subject: Project Status Update</p>"
                + "<p>Hi Bob, please see the project status below.</p>"
                + "</body></html>";

        Path htmlFile = tempDir.resolve("flat_email.html");
        writeFile(htmlFile, html);

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl(htmlFile.toString())
                .build();

        List<Document> docs = loader.load(desc);

        // Flat mode: must produce exactly one document
        assertEquals(1, docs.size(), "Flat mode should produce a single document");

        Map<String, Object> meta = docs.get(0).getMetadata();
        assertNotNull(meta.get("email.from"), "email.from should be extracted in flat mode");
        assertNotNull(meta.get("email.to"),   "email.to should be extracted in flat mode");
        assertTrue(meta.get("email.from").toString().contains("alice@example.com"),
                "email.from should contain the sender address");
    }

    /**
     * A regular HTML page without any From/To/Subject patterns must not have any
     * email.* keys in its metadata when loaded in flat mode.
     */
    @Test
    void flatMode_nonEmailHtml_noEmailMetadata() throws Exception {
        String html = "<html><body>"
                + "<h1>Company Blog Post</h1>"
                + "<p>Welcome to our latest blog post about quarterly earnings.</p>"
                + "<p>Revenue grew by 12% this quarter compared to last year.</p>"
                + "</body></html>";

        Path htmlFile = tempDir.resolve("blog.html");
        writeFile(htmlFile, html);

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl(htmlFile.toString())
                .build();

        List<Document> docs = loader.load(desc);

        assertEquals(1, docs.size(), "Non-email flat HTML should produce a single document");

        Map<String, Object> meta = docs.get(0).getMetadata();
        assertNull(meta.get("email.from"),        "Non-email HTML should not set email.from");
        assertNull(meta.get("email.to"),          "Non-email HTML should not set email.to");
        assertNull(meta.get("content_type_hint"), "Non-email HTML should not set content_type_hint");
    }

    /**
     * An HTML email page loaded in flat mode (no tables) must have
     * content_type_hint set to "email".
     */
    @Test
    void flatMode_emailHtml_setsContentTypeHint() throws Exception {
        String html = "<html><body>"
                + "<p>From: Carol Manager &lt;carol@corp.io&gt;</p>"
                + "<p>To: Dave Engineer &lt;dave@corp.io&gt;</p>"
                + "<p>Subject: Deployment Approval Required</p>"
                + "<p>Please approve the deployment before 5pm.</p>"
                + "</body></html>";

        Path htmlFile = tempDir.resolve("email_hint.html");
        writeFile(htmlFile, html);

        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl(htmlFile.toString())
                .build();

        List<Document> docs = loader.load(desc);

        assertEquals(1, docs.size(), "Flat email should produce one document");

        Map<String, Object> meta = docs.get(0).getMetadata();
        assertEquals("email", meta.get("content_type_hint"),
                "Flat-mode email detection must set content_type_hint to 'email'");
    }

    /**
     * The email extractor must be invoked even when the HTML has no tables
     * (i.e. the loader takes the flat-mode path rather than the structural-mode
     * path that was the only path to call the extractor before this fix).
     *
     * This is verified by using CSS class-based email signals (.from-line /
     * .to-line) which the extractor's Strategy-1 branch recognises, and
     * confirming that metadata is populated without any {@code <table>} element
     * present in the HTML.
     */
    @Test
    void flatMode_emailWithoutTables_triggersExtractor() throws Exception {
        // CSS-class style email that the extractor's Strategy-1 branch handles —
        // deliberately contains NO <table> so the loader must use flat mode.
        String html = "<html><body>"
                + "<h1 class=\"subject\">Budget Review Q2</h1>"
                + "<div class=\"from-line\">Eve Finance &lt;eve@finance.org&gt;</div>"
                + "<div class=\"to-line\">Frank Exec &lt;frank@finance.org&gt;</div>"
                + "<div class=\"date-line\">2026-05-20</div>"
                + "<p>Please review the Q2 budget figures attached.</p>"
                + "</body></html>";

        Path htmlFile = tempDir.resolve("flat_css_email.html");
        writeFile(htmlFile, html);

        // Do not set structuralMode in metadata — relying on auto-detection.
        // Since there is no <table>, the loader selects flat mode automatically.
        DocumentSourceDescriptor desc = DocumentSourceDescriptor.builder()
                .type(SourceType.FILE)
                .pathOrUrl(htmlFile.toString())
                .build();

        List<Document> docs = loader.load(desc);

        // Sanity: must be flat mode (single document, no content_type discriminator)
        assertEquals(1, docs.size(), "No-table HTML must use flat mode");
        assertNull(docs.get(0).getMetadata().get("content_type"),
                "Flat mode should not set content_type");

        // Email extractor must still have run and found the CSS signals
        Map<String, Object> meta = docs.get(0).getMetadata();
        assertEquals("email", meta.get("content_type_hint"),
                "Extractor should be triggered in flat mode (regression guard for pre-fix behaviour)");
        assertNotNull(meta.get("email.from"),
                "email.from should be populated from .from-line CSS class in flat mode");
        assertTrue(meta.get("email.from").toString().contains("eve@finance.org"),
                "email.from should contain the sender address parsed from .from-line");
        assertEquals("Budget Review Q2", meta.get("email.subject"),
                "email.subject should be populated from .subject CSS class in flat mode");
    }
}
