/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.main.project;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link LocalDocumentLoaderRegistry}'s own responsibilities as the bridge from the local
 * (non-Spring) CLI crawl to application {@code DocumentLoader}s:
 * <ul>
 *   <li>{@link LocalDocumentLoaderRegistry#supports} name normalization.</li>
 *   <li>Dispatch of {@code mail}/{@code office}/{@code rtf} loader names through the full
 *       {@link LocalDocumentLoaderRegistry#load} bridge - including {@code sanitizeMetadata} and
 *       the {@code LoadedDocument} text-joining seam - not just the underlying loader in
 *       isolation (each underlying loader has its own dedicated unit tests elsewhere).</li>
 *   <li>The {@code pdftotext}/{@code pdfinfo} watchdog timeout via the package-private
 *       injectable-timeout {@code runPoppler} overload.</li>
 *   <li>Pure, unconditional coverage of {@link LocalDocumentLoaderRegistry#pdfPagesFromFormFeedText}:
 *       no process spawning, no {@code pdftotext} on {@code PATH} required. This is the algorithm
 *       the native-image PDF path relies on to keep page citations at parity with PDFBox's
 *       per-page extractor - see {@code ProjectCrawlNativeImagePdfTest} for the end-to-end,
 *       pdftotext-spawning proof that "pages":[1,3] survives a full local-knowledge crawl.</li>
 * </ul>
 *
 * <p>No {@code .msg}-specific happy-path test exists here (or anywhere in the repo): POI's HSMF
 * {@code MAPIMessage} reader has no writer API, so a real {@code .msg} fixture cannot be
 * synthesized - see the sibling {@code MailLoaderImplMsgTest}, which documents the same
 * constraint at the {@code MailLoaderImpl} level. The {@code .msg} coverage here instead proves
 * the full bridge degrades a corrupt/non-CFBF {@code .msg} to the documented error-document
 * contract rather than throwing, which is the only feasible {@code .msg} coverage without a real
 * external fixture. Likewise there is no dedicated {@code .mbox} test: the canonical loader name
 * this class dispatches on is always {@code "mail"} regardless of the underlying file's
 * extension (extension-to-loader mapping is {@link LocalCrawlCapabilities}'s job, not this
 * class's), so the {@code .eml} test below already exercises 100% of this class's own "mail"
 * dispatch logic and a {@code .mbox} variant would be redundant; the same reasoning excludes
 * {@code .xls}/{@code .xlsx} (excel already has its own dedicated coverage elsewhere).</p>
 */
class LocalDocumentLoaderRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void supportsNormalizesCaseUnderscoresAndSpacesAndRejectsUnknownOrNullLoaderNames() {
        assertTrue(LocalDocumentLoaderRegistry.supports("excel"));
        assertTrue(LocalDocumentLoaderRegistry.supports("HTML"));
        assertTrue(LocalDocumentLoaderRegistry.supports(" pdf "));
        assertTrue(LocalDocumentLoaderRegistry.supports("external_materialized"));
        assertTrue(LocalDocumentLoaderRegistry.supports("External Materialized"));
        assertTrue(LocalDocumentLoaderRegistry.supports("Mail"));
        assertTrue(LocalDocumentLoaderRegistry.supports("OFFICE"));
        assertTrue(LocalDocumentLoaderRegistry.supports("Rtf"));

        assertFalse(LocalDocumentLoaderRegistry.supports("text"));
        assertFalse(LocalDocumentLoaderRegistry.supports("markdown"));
        assertFalse(LocalDocumentLoaderRegistry.supports(null));
        assertFalse(LocalDocumentLoaderRegistry.supports(""));
    }

    @Test
    void mailLoaderDispatchesEmlFilesThroughTheFullBridgeAndSurfacesSubjectAndBody() throws Exception {
        Path eml = tempDir.resolve("message.eml");
        String emlContent = "Subject: Hello From Bridge Test\r\n"
                + "From: sender@example.com\r\n"
                + "To: receiver@example.com\r\n"
                + "Date: Mon, 1 Jan 2024 00:00:00 +0000\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "\r\n"
                + "This is the plain text body of the test message.\r\n";
        Files.writeString(eml, emlContent, StandardCharsets.US_ASCII);

        LocalDocumentLoaderRegistry.LoadedDocument loaded =
                LocalDocumentLoaderRegistry.load(eml, "mail", Map.of());

        assertTrue(loaded.text().contains("Hello From Bridge Test"), loaded.text());
        assertTrue(loaded.text().contains("This is the plain text body of the test message."), loaded.text());
    }

    @Test
    void mailLoaderDegradesANonCfbfMsgFileToAnErrorDocumentThroughTheFullBridgeInsteadOfThrowing()
            throws Exception {
        // A real .msg fixture cannot be synthesized (see the class javadoc); this instead proves
        // the bridge - not just MailLoaderImpl in isolation - surfaces the documented
        // parseError/errorMessage error-document contract for a corrupt/non-CFBF .msg without
        // throwing and without sanitizeMetadata dropping those keys.
        Path msg = tempDir.resolve("broken.msg");
        Files.writeString(msg, "not a real OLE2/CFBF container");

        LocalDocumentLoaderRegistry.LoadedDocument loaded =
                LocalDocumentLoaderRegistry.load(msg, "mail", Map.of());

        assertTrue(loaded.text().contains("Unable to parse mail file"), loaded.text());
        assertEquals(1, loaded.outputs().size());
        assertEquals(Boolean.TRUE, loaded.outputs().get(0).metadata().get("parseError"));
    }

    @Test
    void officeLoaderExtractsTextFromADocxFileThroughTheFullBridge() throws Exception {
        Path docx = tempDir.resolve("memo.docx");
        try (XWPFDocument document = new XWPFDocument();
             OutputStream out = Files.newOutputStream(docx)) {
            document.createParagraph().createRun().setText("Hello from a generated docx fixture.");
            document.write(out);
        }

        LocalDocumentLoaderRegistry.LoadedDocument loaded =
                LocalDocumentLoaderRegistry.load(docx, "office", Map.of());

        assertTrue(loaded.text().contains("Hello from a generated docx fixture."), loaded.text());
    }

    @Test
    void officeLoaderExtractsTextFromAPptxFileThroughTheFullBridge() throws Exception {
        Path pptx = tempDir.resolve("deck.pptx");
        try (XMLSlideShow slideShow = new XMLSlideShow();
             OutputStream out = Files.newOutputStream(pptx)) {
            XSLFSlide slide = slideShow.createSlide();
            XSLFTextBox textBox = slide.createTextBox();
            textBox.setText("Hello from a generated pptx fixture.");
            slideShow.write(out);
        }

        LocalDocumentLoaderRegistry.LoadedDocument loaded =
                LocalDocumentLoaderRegistry.load(pptx, "office", Map.of());

        assertTrue(loaded.text().contains("Hello from a generated pptx fixture."), loaded.text());
    }

    @Test
    void rtfLoaderProducesPlainTextAndItsMetadataSurvivesSanitization() throws Exception {
        Path rtf = tempDir.resolve("memo.rtf");
        Files.writeString(rtf, "{\\rtf1\\ansi Hello Bridge}", StandardCharsets.ISO_8859_1);

        LocalDocumentLoaderRegistry.LoadedDocument loaded =
                LocalDocumentLoaderRegistry.load(rtf, "rtf", Map.of());

        assertEquals("Hello Bridge", loaded.text());
        assertEquals(1, loaded.outputs().size());
        assertEquals("rtf", loaded.outputs().get(0).metadata().get("documentType"));
        assertEquals("RtfText", loaded.outputs().get(0).metadata().get("loader"));
    }

    @Test
    void runPopplerThrowsATimedOutIOExceptionWhenTheProcessExceedsTheInjectedTimeout() {
        // The command array (not the Path argument, which is only used for error-message/temp-file
        // naming purposes) is what actually runs, so a plain "sleep 5" deliberately outlives the
        // short injected timeout below without needing a real PDF fixture or poppler-utils at all.
        Path dummy = tempDir.resolve("dummy.pdf");

        IOException thrown = assertThrows(IOException.class, () ->
                LocalDocumentLoaderRegistry.runPoppler(dummy, 300, TimeUnit.MILLISECONDS, "sleep", "5"));

        assertTrue(thrown.getMessage().contains("timed out"), thrown.getMessage());
    }

    @Test
    void splitsOnFormFeedSkipsBlankPagesKeepsPhysicalPageNumbers() {
        // Poppler always terminates the last page with a trailing form-feed too (verified against
        // the real pdftotext 22.01.0 binary), so this is the normal shape: page 1 "p1", blank page
        // 2, page 3 "p3".
        List<Document> pages = LocalDocumentLoaderRegistry.pdfPagesFromFormFeedText("p1\f\fp3\f");

        assertEquals(2, pages.size());
        assertEquals("p1", pages.get(0).getText());
        assertEquals(1, pages.get(0).getMetadata().get("pageNumber"));
        assertEquals(3, pages.get(0).getMetadata().get("totalPages"));
        assertEquals("singlePage", pages.get(0).getMetadata().get("extractionType"));
        assertEquals("p3", pages.get(1).getText());
        assertEquals(3, pages.get(1).getMetadata().get("pageNumber"));
        assertEquals(3, pages.get(1).getMetadata().get("totalPages"));
    }

    @Test
    void missingTrailingFormFeedDoesNotDropTheLastPage() {
        // Defensive case: if some pdftotext build/version ever omits the final form-feed, the last
        // page must still survive instead of being silently swallowed as an "empty tail".
        List<Document> pages = LocalDocumentLoaderRegistry.pdfPagesFromFormFeedText("p1\fp2");

        assertEquals(2, pages.size());
        assertEquals("p1", pages.get(0).getText());
        assertEquals(1, pages.get(0).getMetadata().get("pageNumber"));
        assertEquals("p2", pages.get(1).getText());
        assertEquals(2, pages.get(1).getMetadata().get("pageNumber"));
        assertEquals(2, pages.get(1).getMetadata().get("totalPages"));
    }

    @Test
    void singleBlankPageYieldsNoDocuments() {
        // Empirically, `pdftotext -f 2 -l 2` on a lone blank page emits exactly one byte: "\f".
        List<Document> pages = LocalDocumentLoaderRegistry.pdfPagesFromFormFeedText("\f");

        assertTrue(pages.isEmpty());
    }

    @Test
    void nullOrEmptyInputYieldsNoDocuments() {
        assertTrue(LocalDocumentLoaderRegistry.pdfPagesFromFormFeedText(null).isEmpty());
        assertTrue(LocalDocumentLoaderRegistry.pdfPagesFromFormFeedText("").isEmpty());
    }

    @Test
    void singlePageWithNoFormFeedAtAllIsStillCaptured() {
        List<Document> pages = LocalDocumentLoaderRegistry.pdfPagesFromFormFeedText("only page");

        assertEquals(1, pages.size());
        assertEquals("only page", pages.get(0).getText());
        assertEquals(1, pages.get(0).getMetadata().get("pageNumber"));
        assertEquals(1, pages.get(0).getMetadata().get("totalPages"));
    }
}
