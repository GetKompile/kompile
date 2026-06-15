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

package ai.kompile.crawl.graph;

import java.util.regex.Pattern;

/**
 * Static text normalization utilities for the crawl pipeline.
 * Extracted from {@link UnifiedCrawlGraphServiceImpl} to reduce class size.
 */
final class CrawlTextNormalizer {

    private CrawlTextNormalizer() {}

    // Combined pattern: null chars + control chars + zero-width chars — all replaced with ""
    // Merging eliminates 2 intermediate String allocations per normalizeText call
    static final Pattern JUNK_CHARS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F\\u200B-\\u200D\\uFEFF]");
    private static final Pattern CARRIAGE_RETURN = Pattern.compile("\r\n?");
    private static final Pattern FORM_FEED = Pattern.compile("\f");
    private static final Pattern TAB_TO_SPACE = Pattern.compile("\t");
    private static final Pattern BINARY_INDICATOR = Pattern.compile("(?i)\\[?(?:binary|image|figure|table|chart|graph)(?:\\s*\\d+)?\\]?");
    private static final Pattern PAGE_HEADER_FOOTER = Pattern.compile("(?m)^\\s*(?:Page\\s+\\d+|\\d+\\s*of\\s*\\d+|^-\\s*\\d+\\s*-)\\s*$");
    private static final Pattern MULTIPLE_SPACES = Pattern.compile(" {2,}");
    private static final Pattern MULTIPLE_NEWLINES = Pattern.compile("\n{3,}");

    /**
     * Full normalization for plain text content — removes binary indicators,
     * page headers/footers, and normalizes whitespace.
     */
    static String normalizeText(String text) {
        String result = JUNK_CHARS.matcher(text).replaceAll("");
        result = CARRIAGE_RETURN.matcher(result).replaceAll("\n");
        result = FORM_FEED.matcher(result).replaceAll("\n\n");
        result = TAB_TO_SPACE.matcher(result).replaceAll("    ");
        result = BINARY_INDICATOR.matcher(result).replaceAll("");
        result = PAGE_HEADER_FOOTER.matcher(result).replaceAll("");
        result = MULTIPLE_SPACES.matcher(result).replaceAll(" ");
        result = MULTIPLE_NEWLINES.matcher(result).replaceAll("\n\n");
        return result.trim();
    }

    /**
     * Lighter normalization for VLM/structured content — preserves markdown tables,
     * structural markers like [Table], section headings, and DocTags markup.
     */
    static String normalizeStructuredText(String text) {
        String result = JUNK_CHARS.matcher(text).replaceAll("");
        result = CARRIAGE_RETURN.matcher(result).replaceAll("\n");
        result = FORM_FEED.matcher(result).replaceAll("\n\n");
        // Skip BINARY_INDICATOR and PAGE_HEADER_FOOTER removal — meaningful in VLM output
        result = MULTIPLE_SPACES.matcher(result).replaceAll(" ");
        result = MULTIPLE_NEWLINES.matcher(result).replaceAll("\n\n");
        return result.trim();
    }
}
