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
 * Source-specific normalization for WEB documents ({@code source_type=WEB_CRAWL}
 * or {@code URL}, or {@code content_type_hint=html}).
 *
 * <p>This step goes beyond the generic {@link BoilerplateRemovalPreprocessor}
 * by applying web-specific structural cleanup that is too aggressive to apply
 * to non-HTML sources:</p>
 *
 * <ol>
 *   <li><b>Breadcrumb trails</b> — "Home &gt; Category &gt; Article" navigation
 *       strings left by Tika after stripping HTML tags.</li>
 *   <li><b>Jump-navigation anchors</b> — "Skip to content", "Back to top" etc.</li>
 *   <li><b>Table-of-contents link lists</b> — bullet lists composed entirely of
 *       short lines that look like section headings (≤60 chars, no sentence
 *       punctuation).</li>
 *   <li><b>Share / print / citation button text</b> residue left after Tika
 *       strips {@code <button>} and {@code <a>} tags.</li>
 *   <li><b>Inline metadata noise</b> — "Last updated: …", "Published: …",
 *       "Reading time: N min" labels often embedded outside structured fields.</li>
 *   <li><b>Excessive whitespace collapse</b> — runs of 3+ blank lines reduced
 *       to a single blank line (paragraph break).</li>
 * </ol>
 *
 * <p>Order: 316 — runs immediately after the email normalizer (315) in the
 * filtering band, before deduplication (400). The generic boilerplate remover
 * (310) runs first, so this step handles the web-specific residue it leaves.</p>
 *
 * <p>No-ops on non-web documents.</p>
 */
@Component
public class WebContentNormalizationPreprocessor implements DocumentPreprocessor {

    private static final Logger log = LoggerFactory.getLogger(WebContentNormalizationPreprocessor.class);

    static final String STEP_ID = "source-normalization-web";

    // ── Source-type detection ─────────────────────────────────────────────────
    private static final String SRC_WEB_CRAWL = "WEB_CRAWL";
    private static final String SRC_URL       = "URL";

    // ── Patterns ──────────────────────────────────────────────────────────────

    /**
     * Breadcrumb navigation: "Home > Products > Details" or "Home › Category".
     * Requires at least two separator-joined segments so we don't strip real
     * mathematical inequalities.
     */
    private static final Pattern BREADCRUMB = Pattern.compile(
            "(?m)^[ \\t]*(?:[\\w &\\-]+\\s*[>›/»|]+\\s*){2,}[\\w &\\-]+\\s*$");

    /**
     * Jump-navigation text fragments: short lines that are exactly one of the
     * common nav-anchor strings.
     */
    private static final Pattern JUMP_NAV = Pattern.compile(
            "(?mi)^[ \\t]*(?:skip(?:\\s+to)?\\s+(?:content|main|navigation)|" +
                    "back\\s+to\\s+top|jump\\s+to\\s+(?:content|section)|" +
                    "go\\s+to\\s+(?:main|content)|toggle\\s+(?:menu|navigation)|" +
                    "open\\s+(?:menu|navigation)|close\\s+(?:menu|navigation))[ \\t]*$");

    /**
     * Share/print/citation button residue: short lines that are exactly one of
     * the common UI-action strings.
     */
    private static final Pattern BUTTON_RESIDUE = Pattern.compile(
            "(?mi)^[ \\t]*(?:share|print|cite|citation|copy(?:\\s+link)?|" +
                    "save|bookmark|add\\s+to\\s+(?:list|favorites|wishlist)|" +
                    "report\\s+(?:a\\s+)?(?:bug|problem|issue)|feedback|" +
                    "suggest\\s+(?:an\\s+)?edit|edit\\s+this\\s+(?:page|article)|" +
                    "download\\s+(?:pdf|doc|article))[ \\t]*$");

    /**
     * Inline metadata noise: publisher, date, and reading-time labels.
     */
    private static final Pattern INLINE_META = Pattern.compile(
            "(?mi)^[ \\t]*(?:(?:last\\s+)?updated?|published?(?:\\s+on)?|" +
                    "posted(?:\\s+on)?|authored?(?:\\s+by)?|" +
                    "reading\\s+time|estimated\\s+read|" +
                    "modified|reviewed(?:\\s+by)?|fact.?checked(?:\\s+by)?)\\s*:.*$");

    /**
     * TOC-like bullet lists: blocks where ≥3 consecutive short (≤60 char)
     * lines start with a list marker and contain no sentence-ending punctuation.
     * We detect these by finding runs of such lines and removing the entire run.
     */
    private static final Pattern TOC_LIST_LINE = Pattern.compile(
            "(?m)^[ \\t]*(?:[\\*\\-•·▪▸▹►]|\\d+[.)]) .{1,58}(?<![.!?:;])\\s*$");

    /** Collapse runs of 3+ blank lines. */
    private static final Pattern EXCESS_BLANK = Pattern.compile("\\n{3,}");

    @Override
    public String id() {
        return STEP_ID;
    }

    @Override
    public String displayName() {
        return "Web Content Normalization";
    }

    @Override
    public int order() {
        return 316;
    }

    @Override
    public boolean appliesTo(Document document, PreprocessingConfig config) {
        if (config.getBoilerplateRemoval() == null || !config.getBoilerplateRemoval().isEnabled()) {
            return false;
        }
        return isWebDocument(document);
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

            String cleaned = normalizeWebText(text);
            if (!cleaned.equals(text) && cleaned.length() >= minRemaining) {
                Document out = new Document(cleaned);
                out.getMetadata().putAll(doc.getMetadata());
                out.getMetadata().put("web_normalized", true);
                out.getMetadata().put("web_normalization_chars_removed", text.length() - cleaned.length());
                result.add(out);
                modified++;
            } else {
                result.add(doc);
            }
        }

        log.debug("Web normalization: {}/{} documents modified", modified, documents.size());
        return result;
    }

    // ── Core normalization ────────────────────────────────────────────────────

    static String normalizeWebText(String text) {
        String result = BREADCRUMB.matcher(text).replaceAll("");
        result = JUMP_NAV.matcher(result).replaceAll("");
        result = BUTTON_RESIDUE.matcher(result).replaceAll("");
        result = INLINE_META.matcher(result).replaceAll("");
        result = stripTocBlocks(result);
        result = EXCESS_BLANK.matcher(result).replaceAll("\n\n").trim();
        return result;
    }

    /**
     * Removes runs of 3+ consecutive TOC-like bullet lines. A single bullet
     * item (e.g. "- See also") is not stripped since it might be real content.
     */
    static String stripTocBlocks(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int consecutiveToc = 0;
        List<String> pending = new ArrayList<>();

        for (String line : lines) {
            if (TOC_LIST_LINE.matcher(line).matches()) {
                consecutiveToc++;
                pending.add(line);
            } else {
                if (consecutiveToc < 3) {
                    // Not a TOC block — emit the pending lines as-is
                    for (String p : pending) {
                        sb.append(p).append("\n");
                    }
                }
                // else: discard the TOC block (3+ consecutive TOC lines)
                pending.clear();
                consecutiveToc = 0;
                sb.append(line).append("\n");
            }
        }
        // Flush remaining pending lines (end-of-document)
        if (consecutiveToc < 3) {
            for (String p : pending) {
                sb.append(p).append("\n");
            }
        }
        return sb.toString().stripTrailing();
    }

    // ── Source-type detection ─────────────────────────────────────────────────

    private static boolean isWebDocument(Document doc) {
        Object srcType    = doc.getMetadata().get("source_type");
        Object hint       = doc.getMetadata().get("content_type_hint");
        Object contentType = doc.getMetadata().get("content_type");

        return SRC_WEB_CRAWL.equals(srcType)
                || SRC_URL.equals(srcType)
                || (hint instanceof String h && h.contains("html"))
                || (contentType instanceof String ct && ct.contains("html"));
    }
}
