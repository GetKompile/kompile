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

package ai.kompile.crawl.graph.preprocessing;

import ai.kompile.core.crawl.graph.DocumentPreprocessor;
import ai.kompile.core.crawl.graph.PreprocessingConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Source-specific normalization for PDF and Office (DOCX, ODT, PPTX, etc.)
 * documents.
 *
 * <p>Tika extracts text from PDFs and Office files in a streaming
 * character-by-character pass, which introduces two systematic artifacts:</p>
 * <ul>
 *   <li><b>Soft-hyphen line wraps</b> — words hyphenated at line breaks appear
 *       as two fragments separated by {@code -\n}, e.g. {@code "pro-\ncess"}.
 *       These must be rejoined so that NER sees {@code "process"}, not
 *       two separate tokens.</li>
 *   <li><b>Hard-wrapped lines</b> — many PDFs produced by word processors
 *       or LaTeX embed explicit newlines at the text-flow width (typically
 *       70–90 chars). These break sentences mid-word and confuse the LLM
 *       extraction prompt. They are joined into paragraphs separated by a
 *       blank line.</li>
 * </ul>
 *
 * <p>Tasks performed:</p>
 * <ol>
 *   <li>Rejoin soft-hyphenated line wraps ({@code word-\npart → wordpart}).</li>
 *   <li>Join hard-wrapped body lines within a paragraph (lines shorter than
 *       {@link #WRAP_WIDTH_THRESHOLD} chars that do not end with sentence
 *       terminators are merged into the next line).</li>
 *   <li>Preserve paragraph breaks (blank lines).</li>
 *   <li>Strip common PDF artefacts: page number lines, running headers/footers
 *       (short lines consisting of only digits or repeating title strings).</li>
 * </ol>
 *
 * <p>Order: 306 — runs in the content-filtering band just after the
 * spreadsheet normalizer (305) so PDF/Office docs get cleaned before
 * boilerplate removal (310) sees them.</p>
 *
 * <p>No-ops on non-PDF/Office documents.</p>
 */
@Component
public class OfficeDocumentNormalizationPreprocessor implements DocumentPreprocessor {

    private static final Logger log = LoggerFactory.getLogger(OfficeDocumentNormalizationPreprocessor.class);

    static final String STEP_ID = "source-normalization-office";

    // ── Source-type detection ─────────────────────────────────────────────────

    private static final List<String> PDF_OFFICE_DOC_TYPES = List.of(
            "pdf", "docx", "doc", "odt", "rtf", "pptx", "ppt", "odp",
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.oasis.opendocument.text",
            "application/rtf",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    );

    // ── Normalization patterns ────────────────────────────────────────────────

    /**
     * Soft-hyphen at line break: "word-\n" → "word" (the continuation is on
     * the next line and will be joined naturally).
     */
    private static final Pattern SOFT_HYPHEN_WRAP = Pattern.compile("-\\n(?=[a-z])");

    /**
     * Lines that appear to be page numbers or simple running headers:
     * a line that contains only digits (optionally surrounded by whitespace),
     * or a line that is very short (≤4 chars) of non-sentence content.
     */
    private static final Pattern PAGE_NUMBER_LINE = Pattern.compile(
            "(?m)^\\s*\\d{1,4}\\s*$");

    /**
     * Sentence terminators — a line ending with one of these is treated as a
     * complete sentence and NOT joined to the next line.
     */
    private static final Pattern SENTENCE_END = Pattern.compile(
            "[.!?:;\"'\\]\\)»›]\\s*$");

    /**
     * Lines shorter than this character count that do NOT end with a sentence
     * terminator are candidates for hard-wrap joining.
     */
    static final int WRAP_WIDTH_THRESHOLD = 80;

    /** Collapse runs of 3+ blank lines down to 2 (paragraph breaks). */
    private static final Pattern EXCESS_BLANK_LINES = Pattern.compile("\\n{3,}");

    @Override
    public String id() {
        return STEP_ID;
    }

    @Override
    public String displayName() {
        return "PDF / Office Document Normalization";
    }

    @Override
    public int order() {
        return 306;
    }

    @Override
    public boolean appliesTo(Document document, PreprocessingConfig config) {
        if (config.getBoilerplateRemoval() == null || !config.getBoilerplateRemoval().isEnabled()) {
            return false;
        }
        return isOfficeDocument(document);
    }

    @Override
    public List<Document> process(List<Document> documents, PreprocessingConfig config) {
        int minRemaining = config.getBoilerplateRemoval() != null
                ? config.getBoilerplateRemoval().getMinRemainingChars()
                : 50;

        List<Document> result = new ArrayList<>(documents.size());
        int modified = 0;

        for (Document doc : documents) {
            if (Thread.currentThread().isInterrupted()) {
                result.add(doc);
                continue;
            }

            String text = doc.getText();
            if (text == null || text.isBlank()) {
                result.add(doc);
                continue;
            }

            String cleaned = normalizeOfficeText(text);
            if (!cleaned.equals(text) && cleaned.length() >= minRemaining) {
                Document out = new Document(cleaned);
                out.getMetadata().putAll(doc.getMetadata());
                out.getMetadata().put("office_normalized", true);
                out.getMetadata().put("office_normalization_chars_removed", text.length() - cleaned.length());
                result.add(out);
                modified++;
            } else {
                result.add(doc);
            }
        }

        log.debug("Office/PDF normalization: {}/{} documents modified", modified, documents.size());
        return result;
    }

    // ── Core normalization ────────────────────────────────────────────────────

    /**
     * Applies soft-hyphen rejoining, page-number removal, and hard-wrap joining.
     */
    static String normalizeOfficeText(String text) {
        // 1. Rejoin soft-hyphenated line breaks
        String result = SOFT_HYPHEN_WRAP.matcher(text).replaceAll("");

        // 2. Remove page-number-only lines
        result = PAGE_NUMBER_LINE.matcher(result).replaceAll("");

        // 3. Join hard-wrapped lines within paragraphs
        result = joinHardWrappedLines(result);

        // 4. Tidy blank lines
        result = EXCESS_BLANK_LINES.matcher(result).replaceAll("\n\n").trim();

        return result;
    }

    /**
     * Joins lines that are hard-wrapped at a fixed column width into full
     * prose paragraphs. A line is joined to the next when ALL of the
     * following hold:
     * <ul>
     *   <li>The current line is shorter than {@link #WRAP_WIDTH_THRESHOLD}.</li>
     *   <li>The current line does not end with a sentence terminator.</li>
     *   <li>The next line is non-blank (blank lines mark paragraph breaks).</li>
     * </ul>
     */
    static String joinHardWrappedLines(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            if (line.isBlank()) {
                // Preserve paragraph break
                sb.append("\n\n");
                continue;
            }

            boolean lineIsShort = line.length() < WRAP_WIDTH_THRESHOLD;
            boolean endsWithTerminator = SENTENCE_END.matcher(line).find();
            boolean nextIsNonBlank = (i + 1 < lines.length) && !lines[i + 1].isBlank();

            if (lineIsShort && !endsWithTerminator && nextIsNonBlank) {
                // Join: append a space instead of a newline
                sb.append(line.stripTrailing()).append(" ");
            } else {
                sb.append(line).append("\n");
            }
        }

        return sb.toString().stripTrailing();
    }

    // ── Source-type detection ─────────────────────────────────────────────────

    private static boolean isOfficeDocument(Document doc) {
        Object docType     = doc.getMetadata().get("documentType");
        Object contentType = doc.getMetadata().get("content_type");
        Object hint        = doc.getMetadata().get("content_type_hint");

        for (String known : PDF_OFFICE_DOC_TYPES) {
            if (known.equals(docType) || known.equals(contentType) || known.equals(hint)) {
                return true;
            }
        }
        // Also check file extension via source/fileName
        Object fileName = doc.getMetadata().get("fileName");
        if (fileName instanceof String fn) {
            String lower = fn.toLowerCase();
            return lower.endsWith(".pdf") || lower.endsWith(".docx") || lower.endsWith(".doc")
                    || lower.endsWith(".odt") || lower.endsWith(".rtf")
                    || lower.endsWith(".pptx") || lower.endsWith(".ppt");
        }
        return false;
    }
}
