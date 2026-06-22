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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Source-specific normalization for EMAIL documents (source_type=GMAIL|IMAP,
 * or documentType=email).
 *
 * <p>Tasks performed:</p>
 * <ol>
 *   <li>Strip quoted reply chains (lines starting with {@code >}, and
 *       "On [date], [person] wrote:" leader lines).</li>
 *   <li>Remove trailing email signatures (content after a bare {@code --} line
 *       or repeated-dash/equals separators not already caught by
 *       {@link BoilerplateRemovalPreprocessor}).</li>
 *   <li>Canonicalize header address lines embedded in the body — e.g. produced
 *       by plain-text forwarding — into a compact single-line form so that
 *       entity extraction reads "From: alice@example.com" as one fact, not
 *       several lines of noise.</li>
 *   <li>Collapse excessive blank lines left by the above removals.</li>
 * </ol>
 *
 * <p>Order: 315 — runs immediately after {@link BoilerplateRemovalPreprocessor}
 * (310) so the generic boilerplate pass goes first; runs before deduplication
 * (400).</p>
 *
 * <p>No-ops on documents whose source type is not an email variant, so it is
 * safe to include in all crawl configurations.</p>
 *
 * <p>Registration: declare the corresponding step key
 * {@code source-normalization-email} in {@link PreprocessingConfig} — until
 * that config key is added to {@code hasAnyStepEnabled()}, gate it by checking
 * the {@code boilerplateRemoval} step or introduce a dedicated
 * {@code sourceNormalization} config block (see KbConfig tunables note).</p>
 */
@Component
public class EmailNormalizationPreprocessor implements DocumentPreprocessor {

    private static final Logger log = LoggerFactory.getLogger(EmailNormalizationPreprocessor.class);

    // ── Quoted-reply detection ────────────────────────────────────────────────

    /**
     * Lines that begin a quoted block in clients that use {@code >} quoting.
     * Catches both plain {@code >} and variants like {@code >> } or {@code > >}.
     */
    private static final Pattern QUOTED_LINE = Pattern.compile(
            "(?m)^[ \\t]*>.*$");

    /**
     * The "On [date] [person] wrote:" leader that precedes a quoted block.
     * Covers multi-line variants where the line wraps before "wrote:".
     */
    private static final Pattern REPLY_LEADER = Pattern.compile(
            "(?m)^On .{1,200}(?:wrote|schrieb|a écrit|escribió|ha scritto|wrote:)\\s*:?\\s*$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Common forwarding headers embedded in plain-text bodies.
     * Matches e.g. "----- Original Message -----" or "--- Forwarded message ---".
     */
    private static final Pattern FORWARD_HEADER = Pattern.compile(
            "(?m)^\\s*[-=]{2,}\\s*(?:Original Message|Forwarded Message|Forwarded mail|Weitergeleitete Nachricht)\\s*[-=]{2,}\\s*$",
            Pattern.CASE_INSENSITIVE);

    // ── Signature separator ───────────────────────────────────────────────────

    /**
     * RFC 3676 signature delimiter: a line with exactly "-- " (or "--" followed
     * by only whitespace). Also catches "-- \r" variants.
     */
    private static final Pattern SIG_DELIMITER = Pattern.compile(
            "(?m)^-- ?\\r?$");

    // ── Embedded forwarding headers ───────────────────────────────────────────

    /**
     * Header address lines that email clients embed in the body when forwarding
     * as plain text (e.g. "From: Alice <alice@example.com>").
     * We compact these to a single line per header.
     */
    private static final Pattern EMBEDDED_HEADER = Pattern.compile(
            "(?m)^(From|To|Cc|Bcc|Date|Subject|Reply-To|Sent|Received):\\s*(.+)$",
            Pattern.CASE_INSENSITIVE);

    // ── Cleanup ───────────────────────────────────────────────────────────────
    private static final Pattern EXCESS_BLANK_LINES = Pattern.compile("\\n{3,}");

    // ── Source-type constants (mirror GraphConstants to avoid cross-module dep) ─
    private static final String SRC_GMAIL  = "GMAIL";
    private static final String SRC_IMAP   = "IMAP";
    private static final String DOC_EMAIL  = "email";

    // ── PreprocessingConfig step key ─────────────────────────────────────────
    /**
     * The config step key. In the absence of a dedicated
     * {@code sourceNormalization} block in {@link PreprocessingConfig} this
     * preprocessor piggybacks on the {@code boilerplateRemoval} enabled flag
     * so it is activated by the same switch. A future config block should
     * introduce a proper key.
     */
    static final String STEP_ID = "source-normalization-email";

    @Override
    public String id() {
        return STEP_ID;
    }

    @Override
    public String displayName() {
        return "Email Source Normalization";
    }

    /**
     * Runs in the filtering band, just after the generic boilerplate remover.
     */
    @Override
    public int order() {
        return 315;
    }

    @Override
    public boolean appliesTo(Document document, PreprocessingConfig config) {
        // Gate: boilerplateRemoval config drives this step until a dedicated config block exists.
        if (config.getBoilerplateRemoval() == null || !config.getBoilerplateRemoval().isEnabled()) {
            return false;
        }
        return isEmailDocument(document);
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

            String cleaned = stripQuotedChains(text);
            cleaned = stripSignature(cleaned, minRemaining, text);
            cleaned = compactEmbeddedHeaders(cleaned);
            cleaned = EXCESS_BLANK_LINES.matcher(cleaned).replaceAll("\n\n").trim();

            if (!cleaned.equals(text) && cleaned.length() >= minRemaining) {
                Document out = new Document(cleaned);
                out.getMetadata().putAll(doc.getMetadata());
                out.getMetadata().put("email_normalized", true);
                out.getMetadata().put("email_normalization_chars_removed", text.length() - cleaned.length());
                result.add(out);
                modified++;
            } else {
                result.add(doc);
            }
        }

        log.debug("Email normalization: {}/{} documents modified", modified, documents.size());
        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean isEmailDocument(Document doc) {
        Object srcType = doc.getMetadata().get("source_type");
        Object docType = doc.getMetadata().get("documentType");
        return SRC_GMAIL.equals(srcType)
                || SRC_IMAP.equals(srcType)
                || DOC_EMAIL.equals(docType);
    }

    /**
     * Removes quoted reply blocks that start with {@code >} and their leader
     * lines. Also removes forwarding headers (e.g. "--- Original Message ---")
     * and everything after them, since forward chains add noise from prior
     * conversations.
     */
    static String stripQuotedChains(String text) {
        // Remove "On X wrote:" leader lines
        String result = REPLY_LEADER.matcher(text).replaceAll("");
        // Remove all >-prefixed quoted lines
        result = QUOTED_LINE.matcher(result).replaceAll("");
        // Remove forwarding header block and everything that follows it
        Matcher fwdMatcher = FORWARD_HEADER.matcher(result);
        if (fwdMatcher.find()) {
            result = result.substring(0, fwdMatcher.start());
        }
        return result;
    }

    /**
     * Removes signature block that starts with the RFC 3676 delimiter {@code --}
     * on a line by itself. Only removes if the content before the delimiter
     * meets the minimum length threshold.
     */
    static String stripSignature(String text, int minRemaining, String original) {
        Matcher m = SIG_DELIMITER.matcher(text);
        if (m.find()) {
            String before = text.substring(0, m.start()).trim();
            if (before.length() >= minRemaining) {
                return before;
            }
        }
        return text;
    }

    /**
     * Collapses multi-line embedded forwarding headers (From/To/Cc/Date/Subject)
     * into compact single-line form. This prevents the entity extractor from
     * misinterpreting wrapped header continuations as body sentences.
     */
    static String compactEmbeddedHeaders(String text) {
        // Consolidate continuation lines: a line that is indented and follows a header
        // is treated as a continuation. We join them onto the header line.
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher hm = EMBEDDED_HEADER.matcher(line);
            if (hm.matches()) {
                String headerName = hm.group(1);
                String headerValue = hm.group(2).trim();
                // Consume indented continuation lines
                while (i + 1 < lines.length && lines[i + 1].matches("^\\s+.+")) {
                    headerValue += " " + lines[++i].trim();
                }
                sb.append(headerName).append(": ").append(headerValue).append("\n");
            } else {
                sb.append(line).append("\n");
            }
        }
        return sb.toString().stripTrailing();
    }
}
