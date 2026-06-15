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

package ai.kompile.core.graphrag.format;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Lightweight text compressor for extraction input sent to LLMs during
 * graph/entity/relation extraction crawls.
 *
 * <p>Designed to reduce token consumption <em>before</em> the hard character
 * truncation, so more meaningful content survives the limit. Typical savings
 * are 15-40% depending on document type (PDF-extracted text tends to compress
 * more due to whitespace artifacts).
 *
 * <p>All methods are static and stateless.
 */
public final class ExtractionTextCompressor {

    private ExtractionTextCompressor() {}

    // ═══════════════════════════════════════════════════════════════════════
    // PATTERNS
    // ═══════════════════════════════════════════════════════════════════════

    /** Three or more consecutive blank lines collapse to one. */
    private static final Pattern MULTI_BLANK_LINES = Pattern.compile("\\n{3,}");

    /** Lines that are purely whitespace (spaces/tabs only). */
    private static final Pattern WHITESPACE_ONLY_LINE = Pattern.compile("(?m)^[ \\t]+$");

    /** Trailing whitespace on each line. */
    private static final Pattern TRAILING_WHITESPACE = Pattern.compile("(?m)[ \\t]+$");

    /** Page number patterns: "Page N", "Page N of M", "- N -", "N / M" standalone on a line. */
    private static final Pattern PAGE_NUMBERS = Pattern.compile(
            "(?mi)^\\s*(?:" +
                    "page\\s+\\d+(?:\\s+of\\s+\\d+)?" +
                    "|\\d+\\s*/\\s*\\d+" +
                    "|-\\s*\\d+\\s*-" +
                    "|\\d+\\s*$" +  // bare number on its own line (common PDF footer)
                    ")\\s*$"
    );

    /** Common copyright/legal boilerplate lines. */
    private static final Pattern COPYRIGHT_LINES = Pattern.compile(
            "(?mi)^\\s*(?:" +
                    "(?:copyright|\\u00a9|\\(c\\))\\s+\\d{4}.*" +
                    "|all\\s+rights\\s+reserved\\.?" +
                    "|confidential\\s+and\\s+proprietary\\.?" +
                    "|this\\s+document\\s+is\\s+(?:confidential|proprietary).*" +
                    "|do\\s+not\\s+(?:copy|distribute|reproduce).*" +
                    ")\\s*$"
    );

    /** Navigation / breadcrumb lines common in web crawls. */
    private static final Pattern NAVIGATION_LINES = Pattern.compile(
            "(?mi)^\\s*(?:" +
                    "home\\s*[>|/\\\\]\\s*.*" +
                    "|breadcrumb[s]?:.*" +
                    "|skip\\s+to\\s+(?:main\\s+)?content" +
                    "|back\\s+to\\s+top" +
                    ")\\s*$"
    );

    /** Repeated separator lines (====, ----, ****, etc.). */
    private static final Pattern SEPARATOR_LINES = Pattern.compile(
            "(?m)^\\s*([=\\-*_~#]{4,})\\s*$"
    );

    /** Two or more consecutive spaces within a line (not at line start — preserve indentation). */
    private static final Pattern INNER_MULTI_SPACES = Pattern.compile("(?<=\\S)  +");

    // ═══════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Compress text for extraction prompts. Applies all compression passes
     * in order: boilerplate removal, whitespace normalization, separator
     * deduplication.
     *
     * @param text          raw document text (may be null)
     * @param preserveTables when true, avoids aggressive whitespace collapse
     *                       inside lines that look like table rows (preserves
     *                       column alignment)
     * @return compressed text, or empty string if input is null/blank
     */
    public static String compress(String text, boolean preserveTables) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String result = text;

        // 1. Strip boilerplate lines
        result = COPYRIGHT_LINES.matcher(result).replaceAll("");
        result = NAVIGATION_LINES.matcher(result).replaceAll("");

        // 2. Strip page numbers (bare numbers on their own line)
        result = PAGE_NUMBERS.matcher(result).replaceAll("");

        // 3. Deduplicate separator lines — keep at most one consecutive
        result = deduplicateSeparators(result);

        // 4. Normalize whitespace
        result = WHITESPACE_ONLY_LINE.matcher(result).replaceAll("");
        result = TRAILING_WHITESPACE.matcher(result).replaceAll("");
        if (!preserveTables) {
            result = INNER_MULTI_SPACES.matcher(result).replaceAll(" ");
        }

        // 5. Collapse runs of blank lines to a single blank line
        result = MULTI_BLANK_LINES.matcher(result).replaceAll("\n\n");

        // 6. Trim leading/trailing whitespace
        result = result.strip();

        return result;
    }

    /**
     * Convenience overload — defaults to non-table-preserving mode.
     */
    public static String compress(String text) {
        return compress(text, false);
    }

    /**
     * Strip repeated header/footer lines that appear across multiple chunks
     * from the same document. Detects lines that appear in at least
     * {@code minOccurrences} of the provided texts and removes them from all.
     *
     * <p>Useful when processing multiple chunks from a single PDF where every
     * page repeats the same header/footer.
     *
     * @param texts          list of chunk texts
     * @param minOccurrences minimum number of chunks a line must appear in to
     *                       be considered a repeated header/footer (typically 3+)
     * @return new list with repeated lines removed from each chunk
     */
    public static List<String> stripRepeatedHeaders(List<String> texts, int minOccurrences) {
        if (texts == null || texts.size() < minOccurrences) {
            return texts;
        }

        // Count how many chunks each normalized line appears in.
        // Only consider the first 5 and last 5 lines of each chunk (headers/footers).
        Map<String, Integer> lineCounts = new HashMap<>();
        int boundaryLines = 5;

        for (String text : texts) {
            if (text == null || text.isBlank()) continue;
            String[] lines = text.split("\\n");
            // Use a set to count each line at most once per chunk
            Map<String, Boolean> seen = new HashMap<>();
            for (int i = 0; i < Math.min(boundaryLines, lines.length); i++) {
                String normalized = lines[i].strip().toLowerCase();
                if (!normalized.isEmpty() && normalized.length() < 200) {
                    seen.putIfAbsent(normalized, true);
                }
            }
            for (int i = Math.max(lines.length - boundaryLines, boundaryLines); i < lines.length; i++) {
                String normalized = lines[i].strip().toLowerCase();
                if (!normalized.isEmpty() && normalized.length() < 200) {
                    seen.putIfAbsent(normalized, true);
                }
            }
            for (String key : seen.keySet()) {
                lineCounts.merge(key, 1, Integer::sum);
            }
        }

        // Collect lines that appear in >= minOccurrences chunks
        Map<String, Boolean> repeatedLines = new HashMap<>();
        for (Map.Entry<String, Integer> entry : lineCounts.entrySet()) {
            if (entry.getValue() >= minOccurrences) {
                repeatedLines.put(entry.getKey(), true);
            }
        }

        if (repeatedLines.isEmpty()) {
            return texts;
        }

        // Remove repeated lines from each chunk
        List<String> result = new ArrayList<>(texts.size());
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                result.add(text);
                continue;
            }
            StringBuilder sb = new StringBuilder();
            for (String line : text.split("\\n")) {
                String normalized = line.strip().toLowerCase();
                if (!repeatedLines.containsKey(normalized)) {
                    sb.append(line).append('\n');
                }
            }
            result.add(sb.toString().strip());
        }
        return result;
    }

    /**
     * Remove overlapping text between adjacent chunks. When a chunker uses
     * overlap, the tail of chunk N is duplicated at the head of chunk N+1.
     * This method detects and removes that duplication.
     *
     * @param texts ordered list of chunk texts from the same document
     * @param maxOverlapChars maximum characters to scan for overlap (typically
     *                        the chunker's overlap size * 2)
     * @return new list with overlap removed from the start of each chunk
     *         (except the first)
     */
    public static List<String> deduplicateChunkOverlap(List<String> texts, int maxOverlapChars) {
        if (texts == null || texts.size() <= 1) {
            return texts;
        }

        List<String> result = new ArrayList<>(texts.size());
        result.add(texts.get(0));

        for (int i = 1; i < texts.size(); i++) {
            String prev = texts.get(i - 1);
            String curr = texts.get(i);

            if (prev == null || curr == null || prev.isEmpty() || curr.isEmpty()) {
                result.add(curr);
                continue;
            }

            // Take the tail of the previous chunk
            int tailStart = Math.max(0, prev.length() - maxOverlapChars);
            String prevTail = prev.substring(tailStart);

            // Find the longest suffix of prevTail that is a prefix of curr
            int overlapLen = findOverlap(prevTail, curr, maxOverlapChars);
            if (overlapLen > 20) {  // minimum meaningful overlap
                result.add(curr.substring(overlapLen).stripLeading());
            } else {
                result.add(curr);
            }
        }
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // INTERNALS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Find the length of the longest suffix of {@code a} that matches a
     * prefix of {@code b}, up to {@code maxLen} characters.
     */
    static int findOverlap(String a, String b, int maxLen) {
        int limit = Math.min(Math.min(a.length(), b.length()), maxLen);
        int bestOverlap = 0;

        for (int len = limit; len > 20; len--) {
            String suffix = a.substring(a.length() - len);
            if (b.startsWith(suffix)) {
                bestOverlap = len;
                break;
            }
        }
        return bestOverlap;
    }

    /**
     * Deduplicate consecutive separator lines — keep only the first in a run.
     */
    private static String deduplicateSeparators(String text) {
        String[] lines = text.split("\\n", -1);
        StringBuilder sb = new StringBuilder(text.length());
        boolean lastWasSeparator = false;

        for (String line : lines) {
            boolean isSep = SEPARATOR_LINES.matcher(line).matches();
            if (isSep && lastWasSeparator) {
                continue;  // skip consecutive separators
            }
            lastWasSeparator = isSep;
            sb.append(line).append('\n');
        }

        // Remove trailing newline added by the loop
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }
}
