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

package ai.kompile.app.core.chunking;

import ai.kompile.core.retrievers.RetrievedDoc;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structure-aware HTML chunker that preserves semantic blocks from HTML documents.
 *
 * <p>Key design principles from RAG research (2025-2026):</p>
 * <ul>
 *   <li><b>Tables as atomic units</b>: Tables are kept intact and serialized as
 *       key-value text ({@code column: value}) so column semantics survive chunking</li>
 *   <li><b>Lists as atomic units</b>: {@code <ul>}/{@code <ol>} blocks are kept together</li>
 *   <li><b>Heading context</b>: The nearest heading is prepended to each chunk so
 *       retrieval has section context</li>
 *   <li><b>Noise filtering</b>: Single emojis, bare numbers, symbol fragments, and
 *       short UI artifacts are removed</li>
 *   <li><b>Oversized blocks</b>: Tables or text blocks exceeding the chunk limit are
 *       split at row/sentence boundaries, never mid-cell</li>
 * </ul>
 */
@Component("htmlChunker")
public class HtmlChunker implements TextChunker {

    private static final int DEFAULT_CHUNK_SIZE = 1500;
    private static final int DEFAULT_OVERLAP = 100;
    private static final int MIN_CHUNK_TOKENS = 8;
    private static final int MIN_CHUNK_CHARS = 30;

    // ---------- regex patterns ----------

    // Match complete <table>...</table> blocks (greedy within each table)
    private static final Pattern TABLE_BLOCK = Pattern.compile(
            "<table[^>]*>([\\s\\S]*?)</table>",
            Pattern.CASE_INSENSITIVE);

    // Match complete <ul>/<ol> list blocks
    private static final Pattern LIST_BLOCK = Pattern.compile(
            "<(ul|ol)[^>]*>([\\s\\S]*?)</\\1>",
            Pattern.CASE_INSENSITIVE);

    // Table rows
    private static final Pattern TR_PATTERN = Pattern.compile(
            "<tr[^>]*>([\\s\\S]*?)</tr>",
            Pattern.CASE_INSENSITIVE);

    // Table header cells
    private static final Pattern TH_PATTERN = Pattern.compile(
            "<th[^>]*>([\\s\\S]*?)</th>",
            Pattern.CASE_INSENSITIVE);

    // Table data cells
    private static final Pattern TD_PATTERN = Pattern.compile(
            "<td[^>]*>([\\s\\S]*?)</td>",
            Pattern.CASE_INSENSITIVE);

    // List items
    private static final Pattern LI_PATTERN = Pattern.compile(
            "<li[^>]*>([\\s\\S]*?)</li>",
            Pattern.CASE_INSENSITIVE);

    // Block-level HTML elements that represent semantic boundaries (non-table, non-list)
    private static final Pattern BLOCK_SPLIT = Pattern.compile(
            "</?(?:p|div|section|article|aside|main|header|footer|nav|" +
                    "h[1-6]|blockquote|pre|figure|figcaption|details|summary)" +
                    "(?:\\s[^>]*)?>",
            Pattern.CASE_INSENSITIVE);

    // Non-content blocks to strip before processing
    private static final Pattern STYLE_BLOCK = Pattern.compile(
            "<style[^>]*>[\\s\\S]*?</style>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCRIPT_BLOCK = Pattern.compile(
            "<script[^>]*>[\\s\\S]*?</script>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SVG_BLOCK = Pattern.compile(
            "<svg[^>]*>[\\s\\S]*?</svg>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEAD_BLOCK = Pattern.compile(
            "<head[^>]*>[\\s\\S]*?</head>", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAV_BLOCK = Pattern.compile(
            "<nav[^>]*>[\\s\\S]*?</nav>", Pattern.CASE_INSENSITIVE);
    private static final Pattern BUTTON_TAG = Pattern.compile(
            "<button[^>]*>[\\s\\S]*?</button>", Pattern.CASE_INSENSITIVE);
    private static final Pattern INPUT_TAG = Pattern.compile(
            "<input[^>]*/?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern FOOTER_BLOCK = Pattern.compile(
            "<footer[^>]*>[\\s\\S]*?</footer>", Pattern.CASE_INSENSITIVE);
    // Title extraction (before <head> gets stripped)
    private static final Pattern TITLE_TAG = Pattern.compile(
            "<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // All HTML tags
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");

    // HTML entities
    private static final Pattern HTML_ENTITY = Pattern.compile("&(?:#\\d+|#x[0-9a-fA-F]+|\\w+);");

    // Consecutive whitespace (including nbsp)
    private static final Pattern MULTI_SPACE = Pattern.compile("[\\s\\u00A0]{2,}");

    // Noise patterns
    private static final Pattern NOISE_EMOJI_ONLY = Pattern.compile(
            "^[\\s\\p{So}\\p{Sk}\\p{Sc}\\p{Sm}\\u200d\\ufe0f]+$");
    private static final Pattern NOISE_NUMBER_ONLY = Pattern.compile(
            "^\\s*[~\u2248<>\u2264\u2265\u00b1]?\\s*-?\\d+([.,]\\d+)?\\s*[%xX\u00d7]?\\s*$");
    private static final Pattern NOISE_SYMBOL_ONLY = Pattern.compile(
            "^\\s*[/\\\\|·•→←↑↓▸▾◀▶«»–—…]+\\s*$");

    // Heading tags for context extraction
    private static final Pattern HEADING_OPEN = Pattern.compile(
            "<(h[1-6])[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADING_CLOSE = Pattern.compile(
            "</(h[1-6])>", Pattern.CASE_INSENSITIVE);

    @Override
    public String getName() {
        return "html";
    }

    @Override
    public List<String> getSupportedLanguages() {
        return List.of("*");
    }

    @Override
    public Map<String, Object> getDefaultOptions() {
        Map<String, Object> defaults = new HashMap<>();
        defaults.put("chunkSize", DEFAULT_CHUNK_SIZE);
        defaults.put("overlap", DEFAULT_OVERLAP);
        defaults.put("minChunkTokens", MIN_CHUNK_TOKENS);
        defaults.put("minChunkChars", MIN_CHUNK_CHARS);
        defaults.put("preserveParagraphs", true);
        defaults.put(OPTION_COLLECT_GARBAGE, false);
        defaults.put(OPTION_INCLUDE_GARBAGE_CHUNK, false);
        return defaults;
    }

    // ======================== main entry point ========================

    @Override
    public List<RetrievedDoc> chunk(RetrievedDoc document, Map<String, Object> options) {
        validateDocument(document);
        Map<String, Object> opts = prepareOptions(options);

        int chunkSize = (Integer) opts.get("chunkSize");
        int overlap = (Integer) opts.get("overlap");
        int minTokens = (Integer) opts.getOrDefault("minChunkTokens", MIN_CHUNK_TOKENS);
        int minChars = (Integer) opts.getOrDefault("minChunkChars", MIN_CHUNK_CHARS);

        String html = document.getText();

        if (!looksLikeHtml(html)) {
            return fallbackChunk(document, html, chunkSize, overlap, minTokens, minChars);
        }

        // Phase 0: strip non-content blocks (CSS, JS, SVG, <head>, nav)
        html = stripNonContent(html);

        // Phase 1: extract atomic structural blocks (tables, lists) and text blocks
        List<SemanticBlock> blocks = extractSemanticBlocks(html);

        // Phase 2: clean, filter noise, add heading context
        List<String> chunks = new ArrayList<>();
        for (SemanticBlock block : blocks) {
            String text = block.toText();
            if (text.isEmpty() || isNoise(text, minTokens, minChars)) {
                continue;
            }
            // Prepend heading context if available
            if (block.headingContext != null && !block.headingContext.isBlank()) {
                text = block.headingContext + "\n" + text;
            }
            chunks.add(text);
        }

        // Phase 3: merge short adjacent chunks, split oversized ones
        List<String> merged = mergeShortBlocks(chunks, chunkSize);
        List<String> finalChunks = new ArrayList<>();
        for (String block : merged) {
            if (block.length() > chunkSize) {
                finalChunks.addAll(recursiveSplit(block, chunkSize, overlap));
            } else {
                finalChunks.add(block);
            }
        }

        // Final noise filter
        List<String> filtered = new ArrayList<>();
        for (String chunk : finalChunks) {
            if (!isNoise(chunk, minTokens, minChars)) {
                filtered.add(chunk);
            }
        }

        if (filtered.isEmpty()) {
            return List.of();
        }

        return buildChunkDocs(document, filtered);
    }

    // ======================== pre-processing ========================

    /**
     * Remove non-content HTML: CSS, JavaScript, SVG graphics, head block, nav, footer.
     * Extracts document title before stripping.
     * Converts br tags to newlines so inline-numbered lists (common in emails) survive.
     */
    private String stripNonContent(String html) {
        // Extract document title before stripping <head>
        Matcher titleM = TITLE_TAG.matcher(html);
        if (titleM.find()) {
            this.lastDocTitle = cleanHtml(titleM.group(1));
        } else {
            this.lastDocTitle = null;
        }

        html = HEAD_BLOCK.matcher(html).replaceAll("");
        html = STYLE_BLOCK.matcher(html).replaceAll("");
        html = SCRIPT_BLOCK.matcher(html).replaceAll("");
        html = SVG_BLOCK.matcher(html).replaceAll("");
        html = NAV_BLOCK.matcher(html).replaceAll("");
        html = FOOTER_BLOCK.matcher(html).replaceAll("");
        html = BUTTON_TAG.matcher(html).replaceAll("");
        html = INPUT_TAG.matcher(html).replaceAll("");
        // Strip HTML comments
        html = html.replaceAll("<!--[\\s\\S]*?-->", "");
        // Convert <br> to newline so inline-numbered lists survive tag stripping
        html = html.replaceAll("<br\\s*/?>", "\n");
        return html;
    }

    // Thread-local state for title extraction (set during stripNonContent)
    private String lastDocTitle;

    // ======================== semantic block extraction ========================

    /**
     * A semantic block is one of: TABLE, LIST, or TEXT.
     * Tables and lists are kept as atomic units; text is everything between them.
     */
    private enum BlockType { TABLE, LIST, TEXT }

    private static class SemanticBlock {
        final BlockType type;
        final String rawHtml;
        String headingContext;

        SemanticBlock(BlockType type, String rawHtml) {
            this.type = type;
            this.rawHtml = rawHtml;
        }

        String toText() {
            return switch (type) {
                case TABLE -> tableToKeyValue(rawHtml);
                case LIST -> listToText(rawHtml);
                case TEXT -> cleanHtml(rawHtml);
            };
        }
    }

    /**
     * Walk the HTML, pulling out tables and lists as atomic blocks,
     * and everything else as TEXT blocks. Assign nearest heading context.
     */
    private List<SemanticBlock> extractSemanticBlocks(String html) {
        List<SemanticBlock> blocks = new ArrayList<>();

        // Collect table and list spans
        List<int[]> atomicSpans = new ArrayList<>(); // [start, end, type] where type: 0=table, 1=list

        Matcher tm = TABLE_BLOCK.matcher(html);
        while (tm.find()) {
            atomicSpans.add(new int[]{tm.start(), tm.end(), 0});
        }
        Matcher lm = LIST_BLOCK.matcher(html);
        while (lm.find()) {
            atomicSpans.add(new int[]{lm.start(), lm.end(), 1});
        }

        // Sort by start position
        atomicSpans.sort(Comparator.comparingInt(a -> a[0]));

        // Remove overlapping spans (table containing a list, etc.)
        List<int[]> cleanSpans = new ArrayList<>();
        int lastEnd = -1;
        for (int[] span : atomicSpans) {
            if (span[0] >= lastEnd) {
                cleanSpans.add(span);
                lastEnd = span[1];
            }
        }

        // Walk through HTML building blocks
        String currentHeading = null;
        int cursor = 0;

        for (int[] span : cleanSpans) {
            // Text between cursor and this atomic block
            if (span[0] > cursor) {
                String gap = html.substring(cursor, span[0]);
                currentHeading = updateHeading(gap, currentHeading);
                List<String> textBlocks = splitTextOnBlockElements(gap);
                for (String tb : textBlocks) {
                    String clean = cleanHtml(tb);
                    if (!clean.isBlank()) {
                        SemanticBlock b = new SemanticBlock(BlockType.TEXT, tb);
                        b.headingContext = currentHeading;
                        blocks.add(b);
                    }
                }
            }

            // The atomic block itself
            BlockType type = span[2] == 0 ? BlockType.TABLE : BlockType.LIST;
            SemanticBlock b = new SemanticBlock(type, html.substring(span[0], span[1]));
            b.headingContext = currentHeading;
            blocks.add(b);
            cursor = span[1];
        }

        // Remaining text after last atomic block
        if (cursor < html.length()) {
            String tail = html.substring(cursor);
            currentHeading = updateHeading(tail, currentHeading);
            List<String> textBlocks = splitTextOnBlockElements(tail);
            for (String tb : textBlocks) {
                String clean = cleanHtml(tb);
                if (!clean.isBlank()) {
                    SemanticBlock b = new SemanticBlock(BlockType.TEXT, tb);
                    b.headingContext = currentHeading;
                    blocks.add(b);
                }
            }
        }

        return blocks;
    }

    /**
     * Scan HTML fragment for heading tags, return the latest heading found
     * (or the previous heading if none found).
     */
    private String updateHeading(String htmlFragment, String currentHeading) {
        Matcher openM = HEADING_OPEN.matcher(htmlFragment);
        int lastHeadingStart = -1;
        while (openM.find()) {
            lastHeadingStart = openM.end();
        }
        if (lastHeadingStart >= 0) {
            // Find the corresponding close tag
            Matcher closeM = HEADING_CLOSE.matcher(htmlFragment);
            int closePos = htmlFragment.length();
            while (closeM.find()) {
                if (closeM.start() >= lastHeadingStart) {
                    closePos = closeM.start();
                    break;
                }
            }
            String headingHtml = htmlFragment.substring(lastHeadingStart, closePos);
            String headingText = cleanHtml(headingHtml);
            if (!headingText.isBlank()) {
                return headingText;
            }
        }
        return currentHeading;
    }

    // ======================== table serialization ========================

    /**
     * Convert an HTML table to key-value text format.
     * Each row becomes: "column1: value1 | column2: value2 | ..."
     * This preserves column semantics across chunking.
     */
    static String tableToKeyValue(String tableHtml) {
        // Extract header row
        List<String> headers = new ArrayList<>();
        Matcher trM = TR_PATTERN.matcher(tableHtml);

        // First row with <th> cells is the header
        boolean foundHeaders = false;
        List<String> rows = new ArrayList<>();

        while (trM.find()) {
            String rowHtml = trM.group(1);
            if (!foundHeaders) {
                Matcher thM = TH_PATTERN.matcher(rowHtml);
                while (thM.find()) {
                    headers.add(cleanHtml(thM.group(1)).trim());
                }
                if (!headers.isEmpty()) {
                    foundHeaders = true;
                    continue; // skip header row from data
                }
                // No <th> in first row — check if it looks like a header (first row of <td>)
                // Fall through and treat all rows as data
            }

            // Data row
            List<String> cells = new ArrayList<>();
            Matcher tdM = TD_PATTERN.matcher(rowHtml);
            while (tdM.find()) {
                cells.add(cleanHtml(tdM.group(1)).trim());
            }
            // If no <td> found, try <th> (some tables use <th> for all cells)
            if (cells.isEmpty()) {
                Matcher thM = TH_PATTERN.matcher(rowHtml);
                while (thM.find()) {
                    cells.add(cleanHtml(thM.group(1)).trim());
                }
            }
            if (!cells.isEmpty()) {
                rows.add(formatRow(headers, cells));
            }
        }

        // If no data rows extracted (e.g. JS-populated table with empty tbody),
        // return empty string so the block gets filtered as noise
        if (rows.isEmpty()) {
            String fallback = cleanHtml(tableHtml);
            // If the cleaned text is just header labels with no data, skip it
            return fallback.isBlank() ? "" : fallback;
        }

        StringBuilder sb = new StringBuilder();
        if (!headers.isEmpty()) {
            sb.append("[Table: ").append(String.join(" | ", headers)).append("]\n");
        }
        for (String row : rows) {
            sb.append(row).append('\n');
        }
        return sb.toString().trim();
    }

    private static String formatRow(List<String> headers, List<String> cells) {
        if (headers.isEmpty()) {
            // No headers — just join cells
            return String.join(" | ", cells);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(" | ");
            if (i < headers.size() && !headers.get(i).isEmpty()) {
                sb.append(headers.get(i)).append(": ").append(cells.get(i));
            } else {
                sb.append(cells.get(i));
            }
        }
        return sb.toString();
    }

    // ======================== list serialization ========================

    /**
     * Convert an HTML list to clean bulleted text.
     */
    static String listToText(String listHtml) {
        Matcher liM = LI_PATTERN.matcher(listHtml);
        StringBuilder sb = new StringBuilder();
        while (liM.find()) {
            String item = cleanHtml(liM.group(1)).trim();
            if (!item.isBlank()) {
                sb.append("- ").append(item).append('\n');
            }
        }
        String result = sb.toString().trim();
        return result.isEmpty() ? cleanHtml(listHtml) : result;
    }

    // ======================== text block splitting ========================

    private List<String> splitTextOnBlockElements(String html) {
        List<String> blocks = new ArrayList<>();
        Matcher m = BLOCK_SPLIT.matcher(html);
        int lastEnd = 0;

        while (m.find()) {
            if (m.start() > lastEnd) {
                String segment = html.substring(lastEnd, m.start());
                if (!segment.isBlank()) {
                    blocks.add(segment);
                }
            }
            lastEnd = m.end();
        }

        if (lastEnd < html.length()) {
            String tail = html.substring(lastEnd);
            if (!tail.isBlank()) {
                blocks.add(tail);
            }
        }

        return blocks;
    }

    // ======================== HTML cleaning ========================

    private boolean looksLikeHtml(String text) {
        int tagCount = 0;
        int idx = 0;
        while ((idx = text.indexOf('<', idx)) >= 0 && tagCount < 3) {
            int end = text.indexOf('>', idx);
            if (end > idx && end - idx < 200) {
                tagCount++;
            }
            idx++;
        }
        return tagCount >= 2;
    }

    static String cleanHtml(String html) {
        // Preserve emphasis: <strong>text</strong> -> *text*
        String text = html.replaceAll("<strong[^>]*>", "*").replaceAll("</strong>", "*");
        text = text.replaceAll("<em[^>]*>", "_").replaceAll("</em>", "_");
        // Strip remaining HTML tags
        text = HTML_TAG.matcher(text).replaceAll(" ");
        text = decodeEntities(text);
        text = MULTI_SPACE.matcher(text).replaceAll(" ");
        return text.trim();
    }

    private static String decodeEntities(String text) {
        text = text.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'")
                .replace("&mdash;", "\u2014")
                .replace("&ndash;", "\u2013")
                .replace("&hellip;", "\u2026")
                .replace("&rsquo;", "\u2019")
                .replace("&lsquo;", "\u2018")
                .replace("&rdquo;", "\u201D")
                .replace("&ldquo;", "\u201C");
        Matcher m = HTML_ENTITY.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String entity = m.group();
            try {
                int codePoint;
                if (entity.startsWith("&#x") || entity.startsWith("&#X")) {
                    codePoint = Integer.parseInt(entity.substring(3, entity.length() - 1), 16);
                } else if (entity.startsWith("&#")) {
                    codePoint = Integer.parseInt(entity.substring(2, entity.length() - 1));
                } else {
                    m.appendReplacement(sb, Matcher.quoteReplacement(entity));
                    continue;
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(codePoint))));
            } catch (NumberFormatException e) {
                m.appendReplacement(sb, Matcher.quoteReplacement(entity));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ======================== noise filtering ========================

    static boolean isNoise(String text, int minTokens, int minChars) {
        if (text == null || text.isBlank()) return true;
        String trimmed = text.trim();
        if (trimmed.length() < minChars) return true;
        if (NOISE_EMOJI_ONLY.matcher(trimmed).matches()) return true;
        if (NOISE_NUMBER_ONLY.matcher(trimmed).matches()) return true;
        if (NOISE_SYMBOL_ONLY.matcher(trimmed).matches()) return true;
        return countTokens(trimmed) < minTokens;
    }

    private static int countTokens(String text) {
        int count = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (!inWord) {
                    count++;
                    inWord = true;
                }
            } else {
                inWord = false;
            }
        }
        return count;
    }

    // ======================== merge / split ========================

    private List<String> mergeShortBlocks(List<String> blocks, int maxSize) {
        if (blocks.size() <= 1) return blocks;

        List<String> merged = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String block : blocks) {
            if (current.length() == 0) {
                current.append(block);
            } else if (current.length() + 1 + block.length() <= maxSize) {
                current.append('\n').append(block);
            } else {
                merged.add(current.toString());
                current = new StringBuilder(block);
            }
        }

        if (current.length() > 0) {
            merged.add(current.toString());
        }

        return merged;
    }

    private List<String> recursiveSplit(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        String[] separators = {"\n\n", "\n", ". ", "! ", "? ", "; ", ", ", " "};

        for (String sep : separators) {
            if (text.contains(sep)) {
                String[] parts = text.split(Pattern.quote(sep));
                StringBuilder current = new StringBuilder();
                for (String part : parts) {
                    if (part.isEmpty()) continue;
                    if (current.length() == 0) {
                        current.append(part);
                    } else if (current.length() + sep.length() + part.length() <= chunkSize) {
                        current.append(sep).append(part);
                    } else {
                        chunks.add(current.toString());
                        String prev = current.toString();
                        current = new StringBuilder();
                        if (overlap > 0 && prev.length() > overlap) {
                            int overlapStart = prev.length() - overlap;
                            int spaceIdx = prev.indexOf(' ', overlapStart);
                            if (spaceIdx > overlapStart && spaceIdx < prev.length()) {
                                current.append(prev.substring(spaceIdx + 1));
                            }
                        }
                        if (current.length() + sep.length() + part.length() <= chunkSize) {
                            if (current.length() > 0) current.append(sep);
                            current.append(part);
                        } else {
                            if (current.length() > 0) chunks.add(current.toString());
                            current = new StringBuilder(part);
                        }
                    }
                }
                if (current.length() > 0) {
                    chunks.add(current.toString());
                }
                return chunks;
            }
        }

        // Final fallback: character splitting
        for (int i = 0; i < text.length(); i += chunkSize - overlap) {
            chunks.add(text.substring(i, Math.min(i + chunkSize, text.length())));
        }
        return chunks;
    }

    // ======================== output builders ========================

    private List<RetrievedDoc> buildChunkDocs(RetrievedDoc original, List<String> chunks) {
        List<RetrievedDoc> result = new ArrayList<>(chunks.size());
        int total = chunks.size();

        for (int i = 0; i < total; i++) {
            Map<String, Object> meta = new HashMap<>(original.getMetadata());
            meta.put("chunk.strategy", getName());
            meta.put("chunk.index", i);
            meta.put("chunk.total", total);
            meta.put("chunk.originalId", original.getId());
            meta.put("chunk.size", chunks.get(i).length());
            if (lastDocTitle != null && !lastDocTitle.isBlank()) {
                meta.put("chunk.documentTitle", lastDocTitle);
            }

            result.add(RetrievedDoc.builder()
                    .id(original.getId() + "-chunk-" + i)
                    .text(chunks.get(i))
                    .metadata(meta)
                    .score(original.getScore())
                    .build());
        }

        return result;
    }

    private List<RetrievedDoc> fallbackChunk(RetrievedDoc document, String text,
                                              int chunkSize, int overlap,
                                              int minTokens, int minChars) {
        List<String> chunks = recursiveSplit(text, chunkSize, overlap);
        List<String> filtered = new ArrayList<>();
        for (String chunk : chunks) {
            if (!isNoise(chunk, minTokens, minChars)) {
                filtered.add(chunk);
            }
        }
        return buildChunkDocs(document, filtered);
    }
}
