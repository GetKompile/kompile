/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.crawl.graph.preprocessing;

import ai.kompile.core.crawl.graph.PreprocessingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the per-source preprocessing trio:
 * {@link EmailNormalizationPreprocessor},
 * {@link SpreadsheetNormalizationPreprocessor},
 * {@link OfficeDocumentNormalizationPreprocessor}, and
 * {@link WebContentNormalizationPreprocessor}.
 *
 * <p>Each preprocessor must: (a) transform its own source type, and
 * (b) pass through (no-op) documents of the wrong source type.</p>
 */
class SourceNormalizationPreprocessorTest {

    // ── Test fixtures ─────────────────────────────────────────────────────────

    private static PreprocessingConfig enabledConfig() {
        return PreprocessingConfig.builder()
                .enabled(true)
                .boilerplateRemoval(
                        PreprocessingConfig.BoilerplateRemovalConfig.builder()
                                .enabled(true)
                                .minRemainingChars(10)
                                .build())
                .build();
    }

    private static Document emailDoc(String text) {
        Document doc = new Document(text);
        doc.getMetadata().put("source_type", "GMAIL");
        doc.getMetadata().put("documentType", "email");
        return doc;
    }

    private static Document csvDoc(String text) {
        Document doc = new Document(text);
        doc.getMetadata().put("documentType", "csv");
        return doc;
    }

    private static Document pdfDoc(String text) {
        Document doc = new Document(text);
        doc.getMetadata().put("documentType", "pdf");
        return doc;
    }

    private static Document webDoc(String text) {
        Document doc = new Document(text);
        doc.getMetadata().put("source_type", "WEB_CRAWL");
        doc.getMetadata().put("content_type_hint", "html");
        return doc;
    }

    private static Document plainDoc(String text) {
        // No source_type or documentType — represents an unknown/generic doc
        return new Document(text);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // EmailNormalizationPreprocessor
    // ═════════════════════════════════════════════════════════════════════════

    private final EmailNormalizationPreprocessor emailPreprocessor = new EmailNormalizationPreprocessor();

    @Test
    void email_stripsQuotedReplyChain() {
        String text = "Hello Alice,\n\nThanks for the update.\n\n" +
                "> On Mon, Jan 1, 2024, Alice wrote:\n" +
                "> I wanted to follow up on the project.\n" +
                "> The deadline is next week.\n";

        List<Document> result = emailPreprocessor.process(List.of(emailDoc(text)), enabledConfig());

        assertThat(result).hasSize(1);
        String cleaned = result.get(0).getText();
        assertThat(cleaned).contains("Thanks for the update");
        assertThat(cleaned).doesNotContain("> On Mon");
        assertThat(cleaned).doesNotContain("> I wanted to");
    }

    @Test
    void email_stripsRfc3676Signature() {
        String body = "Please find the report attached.\n\n-- \nJohn Doe\nSenior Analyst\njohn@example.com";

        List<Document> result = emailPreprocessor.process(List.of(emailDoc(body)), enabledConfig());

        String cleaned = result.get(0).getText();
        assertThat(cleaned).contains("Please find the report attached");
        assertThat(cleaned).doesNotContain("John Doe");
        assertThat(cleaned).doesNotContain("Senior Analyst");
    }

    @Test
    void email_compactsEmbeddedForwardingHeaders() {
        String body = "---------- Forwarded message ----------\n" +
                "From: Alice Smith\n" +
                "    <alice@example.com>\n" +
                "To: Bob Jones <bob@example.com>\n" +
                "Subject: Q3 Report\n\n" +
                "Please see the attached Q3 report.";

        String result = EmailNormalizationPreprocessor.compactEmbeddedHeaders(body);
        // Continuation line should be merged onto the From: line
        assertThat(result).contains("From: Alice Smith <alice@example.com>");
        // Original multi-line form should be gone
        assertThat(result).doesNotContain("\n    <alice@example.com>");
    }

    @Test
    void email_noopsOnNonEmailDocument() {
        PreprocessingConfig cfg = enabledConfig();
        Document webDocument = webDoc("Some web page content with lots of text here.");

        // appliesTo must return false for non-email docs
        assertThat(emailPreprocessor.appliesTo(webDocument, cfg)).isFalse();

        // process is only called on applicable docs, but if called it should pass through
        List<Document> result = emailPreprocessor.process(List.of(webDocument), cfg);
        assertThat(result.get(0).getText()).isEqualTo("Some web page content with lots of text here.");
    }

    @Test
    void email_noopsWhenBoilerplateRemovalDisabled() {
        PreprocessingConfig noopCfg = PreprocessingConfig.builder()
                .enabled(true)
                .boilerplateRemoval(PreprocessingConfig.BoilerplateRemovalConfig.builder()
                        .enabled(false).build())
                .build();
        assertThat(emailPreprocessor.appliesTo(emailDoc("anything"), noopCfg)).isFalse();
    }

    @Test
    void email_appliesToGmailSourceType() {
        Document gmail = new Document("body");
        gmail.getMetadata().put("source_type", "GMAIL");
        assertThat(emailPreprocessor.appliesTo(gmail, enabledConfig())).isTrue();
    }

    @Test
    void email_appliesToImapSourceType() {
        Document imap = new Document("body");
        imap.getMetadata().put("source_type", "IMAP");
        assertThat(emailPreprocessor.appliesTo(imap, enabledConfig())).isTrue();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SpreadsheetNormalizationPreprocessor
    // ═════════════════════════════════════════════════════════════════════════

    private final SpreadsheetNormalizationPreprocessor sheetPreprocessor = new SpreadsheetNormalizationPreprocessor();

    @Test
    void spreadsheet_normalizesColumnHeaders() {
        String[] rawHeaders = {"First Name ", " Last Name", "Employee ID#", "Is Active"};
        String[] normalized = SpreadsheetNormalizationPreprocessor.normalizeHeaders(rawHeaders);

        assertThat(normalized[0]).isEqualTo("first_name");
        assertThat(normalized[1]).isEqualTo("last_name");
        assertThat(normalized[2]).isEqualTo("employee_id");
        assertThat(normalized[3]).isEqualTo("is_active");
    }

    @Test
    void spreadsheet_deduplicatesColumnHeaders() {
        String[] raw = {"Value", "Value", "Value"};
        String[] normalized = SpreadsheetNormalizationPreprocessor.normalizeHeaders(raw);

        assertThat(normalized[0]).isEqualTo("value");
        assertThat(normalized[1]).isEqualTo("value_2");
        assertThat(normalized[2]).isEqualTo("value_3");
    }

    @Test
    void spreadsheet_dropsEmptyRows() {
        String csv = "name,age,city\nAlice,30,NYC\n,,\nBob,25,LA\n , , \n";
        String result = SpreadsheetNormalizationPreprocessor.normalizeSpreadsheetText(csv);

        String[] lines = result.split("\n");
        // Should have: header + Alice + Bob = 3 lines
        assertThat(lines).hasSize(3);
        assertThat(result).contains("name");   // header row preserved
        assertThat(result).contains("Alice");  // data preserved
        assertThat(result).contains("Bob");
    }

    @Test
    void spreadsheet_coercesBooleanSynonyms() {
        String[] cells = {"Yes", "NO", "on", "Off", "1", "0"};
        String[] coerced = SpreadsheetNormalizationPreprocessor.coerceCells(cells);

        assertThat(coerced[0]).isEqualTo("true");
        assertThat(coerced[1]).isEqualTo("false");
        assertThat(coerced[2]).isEqualTo("true");
        assertThat(coerced[3]).isEqualTo("false");
        assertThat(coerced[4]).isEqualTo("true");
        assertThat(coerced[5]).isEqualTo("false");
    }

    @Test
    void spreadsheet_stripsThousandSeparatorsFromNumericCells() {
        String[] cells = {"1,234,567", "3.14", "42"};
        String[] coerced = SpreadsheetNormalizationPreprocessor.coerceCells(cells);

        assertThat(coerced[0]).isEqualTo("1234567");
        assertThat(coerced[1]).isEqualTo("3.14");
        assertThat(coerced[2]).isEqualTo("42");
    }

    @Test
    void spreadsheet_noopsOnEmailDocument() {
        assertThat(sheetPreprocessor.appliesTo(emailDoc("x,y\n1,2"), enabledConfig())).isFalse();
    }

    @Test
    void spreadsheet_appliesToCsvDocumentType() {
        assertThat(sheetPreprocessor.appliesTo(csvDoc("a,b\n1,2"), enabledConfig())).isTrue();
    }

    @Test
    void spreadsheet_processTransformsDocument() {
        String csv = "Product Name ,Price ,In Stock\nWidget,1,234.56,Yes\n,,,\nGadget,99.99,No";
        List<Document> result = sheetPreprocessor.process(List.of(csvDoc(csv)), enabledConfig());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMetadata()).containsKey("spreadsheet_normalized");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // OfficeDocumentNormalizationPreprocessor
    // ═════════════════════════════════════════════════════════════════════════

    private final OfficeDocumentNormalizationPreprocessor officePreprocessor = new OfficeDocumentNormalizationPreprocessor();

    @Test
    void office_rejoinsHyphenatedLineWraps() {
        String text = "The pro-\ncess of data nor-\nmalization ensures con-\nsistent results.";
        String result = OfficeDocumentNormalizationPreprocessor.normalizeOfficeText(text);

        assertThat(result).contains("process");
        assertThat(result).contains("normalization");
        assertThat(result).contains("consistent");
        assertThat(result).doesNotContain("pro-\ncess");
    }

    @Test
    void office_joinsHardWrappedBodyLines() {
        // Lines that are short and don't end with sentence terminators should be joined
        String text = "This is the beginning of a very long sentence that has been\n" +
                "wrapped at column 60 by the PDF renderer and the second\n" +
                "fragment continues here before finally ending.\n\n" +
                "New paragraph starts here.";

        String result = OfficeDocumentNormalizationPreprocessor.joinHardWrappedLines(text);

        // The first paragraph's lines should be merged into one block
        assertThat(result).contains("wrapped at column 60 by the PDF renderer and the second fragment");
        // Paragraph break preserved
        assertThat(result).contains("New paragraph starts here");
    }

    @Test
    void office_preservesParagraphBreaks() {
        String text = "First paragraph.\n\nSecond paragraph.\n\nThird paragraph.";
        String result = OfficeDocumentNormalizationPreprocessor.normalizeOfficeText(text);

        assertThat(result).contains("First paragraph");
        assertThat(result).contains("Second paragraph");
        assertThat(result).contains("Third paragraph");
    }

    @Test
    void office_doesNotJoinLinesThatEndWithSentenceTerminator() {
        String text = "Line ending with period.\nThis is a new sentence.\n";
        String result = OfficeDocumentNormalizationPreprocessor.joinHardWrappedLines(text);

        // Both lines should stay separate since they end with "."
        assertThat(result).contains("Line ending with period.\nThis is a new sentence.");
    }

    @Test
    void office_noopsOnEmailDocument() {
        assertThat(officePreprocessor.appliesTo(emailDoc("content"), enabledConfig())).isFalse();
    }

    @Test
    void office_noopsOnWebDocument() {
        assertThat(officePreprocessor.appliesTo(webDoc("content"), enabledConfig())).isFalse();
    }

    @Test
    void office_appliesToPdfDocumentType() {
        assertThat(officePreprocessor.appliesTo(pdfDoc("content"), enabledConfig())).isTrue();
    }

    @Test
    void office_appliesToFileNameExtension() {
        Document doc = new Document("content");
        doc.getMetadata().put("fileName", "report.docx");
        assertThat(officePreprocessor.appliesTo(doc, enabledConfig())).isTrue();
    }

    @Test
    void office_processTransformsDocument() {
        String text = "This is a long line that extends to just under eighty\n" +
                "characters and wraps here before ending the sentence now.";
        List<Document> result = officePreprocessor.process(List.of(pdfDoc(text)), enabledConfig());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMetadata()).containsKey("office_normalized");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // WebContentNormalizationPreprocessor
    // ═════════════════════════════════════════════════════════════════════════

    private final WebContentNormalizationPreprocessor webPreprocessor = new WebContentNormalizationPreprocessor();

    @Test
    void web_stripsBreadcrumbNavigation() {
        String text = "Home > Products > Widgets > Super Widget 3000\n\n" +
                "Super Widget 3000 is our flagship product with 10 years of warranty.";

        String result = WebContentNormalizationPreprocessor.normalizeWebText(text);

        assertThat(result).doesNotContain("Home > Products");
        assertThat(result).contains("Super Widget 3000 is our flagship product");
    }

    @Test
    void web_stripsJumpNavigation() {
        String text = "Skip to content\n\nWelcome to our website. We offer a range of services.";
        String result = WebContentNormalizationPreprocessor.normalizeWebText(text);

        assertThat(result).doesNotContain("Skip to content");
        assertThat(result).contains("Welcome to our website");
    }

    @Test
    void web_stripsButtonResidue() {
        String text = "This article explores machine learning.\n\nShare\nPrint\nCite\n\nThe history of ML dates to the 1950s.";
        String result = WebContentNormalizationPreprocessor.normalizeWebText(text);

        assertThat(result).doesNotContain("\nShare\n");
        assertThat(result).doesNotContain("\nPrint\n");
        assertThat(result).contains("The history of ML dates to the 1950s");
    }

    @Test
    void web_stripsInlineMetadataLabels() {
        String text = "Last updated: January 5, 2024\nPublished: December 1, 2023\n\nThe report covers Q4 results.";
        String result = WebContentNormalizationPreprocessor.normalizeWebText(text);

        assertThat(result).doesNotContain("Last updated");
        assertThat(result).doesNotContain("Published:");
        assertThat(result).contains("The report covers Q4 results");
    }

    @Test
    void web_stripsTocBlocks() {
        // 3+ consecutive short bullet lines without sentence punctuation = TOC block
        String text = "- Introduction\n- Background\n- Methodology\n- Results\n- Conclusion\n\n" +
                "This paper presents a novel approach to graph normalization.";

        String result = WebContentNormalizationPreprocessor.normalizeWebText(text);

        assertThat(result).doesNotContain("- Introduction");
        assertThat(result).doesNotContain("- Methodology");
        assertThat(result).contains("This paper presents");
    }

    @Test
    void web_preservesShortBulletListsBelowTocThreshold() {
        // Only 2 bullet items — should NOT be stripped
        String text = "- See also\n- References\n\nThe main content is here.";
        String result = WebContentNormalizationPreprocessor.stripTocBlocks(text);

        assertThat(result).contains("- See also");
        assertThat(result).contains("- References");
    }

    @Test
    void web_noopsOnEmailDocument() {
        assertThat(webPreprocessor.appliesTo(emailDoc("content"), enabledConfig())).isFalse();
    }

    @Test
    void web_noopsOnPdfDocument() {
        assertThat(webPreprocessor.appliesTo(pdfDoc("content"), enabledConfig())).isFalse();
    }

    @Test
    void web_noopsOnPlainDocument() {
        assertThat(webPreprocessor.appliesTo(plainDoc("content"), enabledConfig())).isFalse();
    }

    @Test
    void web_appliesToWebCrawlSourceType() {
        Document doc = new Document("content");
        doc.getMetadata().put("source_type", "WEB_CRAWL");
        assertThat(webPreprocessor.appliesTo(doc, enabledConfig())).isTrue();
    }

    @Test
    void web_appliesToHtmlContentTypeHint() {
        Document doc = new Document("content");
        doc.getMetadata().put("content_type_hint", "text/html");
        assertThat(webPreprocessor.appliesTo(doc, enabledConfig())).isTrue();
    }

    @Test
    void web_processTransformsDocument() {
        String text = "Skip to content\n\nHome > Blog > Article\n\n" +
                "This article covers the topic in depth with many references.";
        List<Document> result = webPreprocessor.process(List.of(webDoc(text)), enabledConfig());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMetadata()).containsKey("web_normalized");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Order contracts
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    void ordersAreWithinFilteringBand() {
        assertThat(emailPreprocessor.order()).isBetween(300, 399);
        assertThat(sheetPreprocessor.order()).isBetween(300, 399);
        assertThat(officePreprocessor.order()).isBetween(300, 399);
        assertThat(webPreprocessor.order()).isBetween(300, 399);
    }

    @Test
    void spreadsheetRunsBeforeBoilerplateRemover() {
        assertThat(sheetPreprocessor.order()).isLessThan(310);
    }

    @Test
    void officeRunsBeforeBoilerplateRemover() {
        assertThat(officePreprocessor.order()).isLessThan(310);
    }

    @Test
    void emailRunsAfterBoilerplateRemover() {
        assertThat(emailPreprocessor.order()).isGreaterThan(310);
    }

    @Test
    void webRunsAfterEmailNormalizer() {
        assertThat(webPreprocessor.order()).isGreaterThan(emailPreprocessor.order());
    }
}
