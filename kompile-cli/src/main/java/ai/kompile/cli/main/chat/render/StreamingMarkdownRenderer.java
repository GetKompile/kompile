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

package ai.kompile.cli.main.chat.render;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Incremental markdown renderer for streaming LLM output.
 * <p>
 * Tokens arrive one fragment at a time. This renderer buffers them into
 * complete lines, then applies markdown formatting (headings, bold, italic,
 * inline code, lists, blockquotes, code fences, horizontal rules) and prints
 * each line as soon as it's complete — preserving the streaming feel while
 * producing formatted output.
 * <p>
 * Code blocks are buffered entirely and rendered as a bordered box with line
 * numbers when the closing fence arrives.
 */
public class StreamingMarkdownRenderer {

    private final AsciiRenderer ascii;
    private final TerminalRenderer term;

    // Line buffer — accumulates tokens until a newline arrives
    private final StringBuilder lineBuffer = new StringBuilder();

    // Code block state
    private boolean inCodeBlock = false;
    private String codeBlockLang = null;
    private final StringBuilder codeBuffer = new StringBuilder();

    // Patterns (same as AsciiRenderer)
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern ITALIC_PATTERN = Pattern.compile("(?<![*])\\*(?![*])(.+?)(?<![*])\\*(?![*])");
    private static final Pattern INLINE_CODE_PATTERN = Pattern.compile("`([^`]+)`");
    private static final Pattern STRIKETHROUGH_PATTERN = Pattern.compile("~~(.+?)~~");
    private static final Pattern LINK_PATTERN = Pattern.compile("\\[([^]]+)]\\(([^)]+)\\)");
    private static final Pattern UNORDERED_LIST_PATTERN = Pattern.compile("^(\\s*)[-*+]\\s+(.+)$");
    private static final Pattern ORDERED_LIST_PATTERN = Pattern.compile("^(\\s*)\\d+\\.\\s+(.+)$");
    private static final Pattern BLOCKQUOTE_PATTERN = Pattern.compile("^>\\s?(.*)$");
    private static final Pattern HR_PATTERN = Pattern.compile("^([-*_])\\1{2,}\\s*$");

    public StreamingMarkdownRenderer(AsciiRenderer ascii) {
        this.ascii = ascii;
        this.term = ascii.getTerminalRenderer();
    }

    /**
     * Accept a chunk of streaming text. May contain zero, one, or multiple
     * newlines. Complete lines are rendered and printed immediately.
     */
    public void accept(String chunk) {
        if (chunk == null || chunk.isEmpty()) return;

        for (int i = 0; i < chunk.length(); i++) {
            char c = chunk.charAt(i);
            if (c == '\n') {
                // Line is complete — render and print it
                String line = lineBuffer.toString();
                lineBuffer.setLength(0);
                processLine(line);
            } else {
                lineBuffer.append(c);
            }
        }
    }

    /**
     * Flush any remaining buffered content (partial line at end of stream).
     */
    public void flush() {
        if (lineBuffer.length() > 0) {
            String line = lineBuffer.toString();
            lineBuffer.setLength(0);

            if (inCodeBlock) {
                // Unclosed code block — render what we have
                if (codeBuffer.length() > 0) codeBuffer.append("\n");
                codeBuffer.append(line);
                System.out.println(ascii.renderCodeBlock(
                        codeBuffer.toString().stripTrailing(), codeBlockLang));
                codeBuffer.setLength(0);
                inCodeBlock = false;
                codeBlockLang = null;
            } else {
                // Partial line — render with inline formatting
                System.out.print(renderInline(line));
            }
            System.out.flush();
        } else if (inCodeBlock && codeBuffer.length() > 0) {
            // Unclosed code block at end of stream
            System.out.println(ascii.renderCodeBlock(
                    codeBuffer.toString().stripTrailing(), codeBlockLang));
            codeBuffer.setLength(0);
            inCodeBlock = false;
            codeBlockLang = null;
            System.out.flush();
        }
    }

    /**
     * Reset state for a new response.
     */
    public void reset() {
        lineBuffer.setLength(0);
        codeBuffer.setLength(0);
        inCodeBlock = false;
        codeBlockLang = null;
    }

    // ── Line processing ──────────────────────────────────────────────────

    private void processLine(String line) {
        // Code block fence
        if (line.startsWith("```")) {
            if (inCodeBlock) {
                // End code block — render the entire block
                System.out.println(ascii.renderCodeBlock(
                        codeBuffer.toString().stripTrailing(), codeBlockLang));
                System.out.flush();
                codeBuffer.setLength(0);
                inCodeBlock = false;
                codeBlockLang = null;
            } else {
                inCodeBlock = true;
                codeBlockLang = line.length() > 3 ? line.substring(3).trim() : null;
                if (codeBlockLang != null && codeBlockLang.isEmpty()) codeBlockLang = null;
            }
            return;
        }

        // Inside code block — buffer without formatting
        if (inCodeBlock) {
            if (codeBuffer.length() > 0) codeBuffer.append("\n");
            codeBuffer.append(line);
            return;
        }

        // Horizontal rule
        if (HR_PATTERN.matcher(line).matches()) {
            System.out.println(ascii.horizontalRule());
            System.out.flush();
            return;
        }

        // Heading
        Matcher headingMatcher = HEADING_PATTERN.matcher(line);
        if (headingMatcher.matches()) {
            int level = headingMatcher.group(1).length();
            String text = headingMatcher.group(2);
            System.out.println(renderHeading(text, level));
            System.out.flush();
            return;
        }

        // Blockquote
        Matcher quoteMatcher = BLOCKQUOTE_PATTERN.matcher(line);
        if (quoteMatcher.matches()) {
            String quoteText = quoteMatcher.group(1);
            String bar = term.dim(term.cyan("│"));
            System.out.println(bar + " " + term.dim(renderInline(quoteText)));
            System.out.flush();
            return;
        }

        // Unordered list
        Matcher ulMatcher = UNORDERED_LIST_PATTERN.matcher(line);
        if (ulMatcher.matches()) {
            String indent = ulMatcher.group(1);
            String text = ulMatcher.group(2);
            int depth = indent.length() / 2;
            String[] bullets = {"●", "○", "▪", "▫"};
            String sym = bullets[Math.min(depth, bullets.length - 1)];
            String bullet = term.dim("  " + indent) + term.cyan(sym) + " ";
            System.out.println(bullet + renderInline(text));
            System.out.flush();
            return;
        }

        // Ordered list
        Matcher olMatcher = ORDERED_LIST_PATTERN.matcher(line);
        if (olMatcher.matches()) {
            String indent = olMatcher.group(1);
            String text = olMatcher.group(2);
            String bullet = term.dim("  " + indent) + term.cyan("•") + " ";
            System.out.println(bullet + renderInline(text));
            System.out.flush();
            return;
        }

        // Empty line
        if (line.isEmpty()) {
            System.out.println();
            System.out.flush();
            return;
        }

        // Regular text — apply inline formatting
        System.out.println(renderInline(line));
        System.out.flush();
    }

    // ── Inline formatting ────────────────────────────────────────────────

    private String renderInline(String text) {
        // Bold before italic to avoid conflict on single *
        text = replacePatterned(text, BOLD_PATTERN, m -> term.bold(m.group(1)));
        text = replacePatterned(text, ITALIC_PATTERN, m -> term.dim(m.group(1)));
        text = replacePatterned(text, INLINE_CODE_PATTERN, m ->
                term.yellow("`" + m.group(1) + "`"));
        text = replacePatterned(text, STRIKETHROUGH_PATTERN, m -> {
            if (term.isAnsiEnabled()) {
                return "\033[9m" + m.group(1) + "\033[0m";
            }
            return "~" + m.group(1) + "~";
        });
        text = replacePatterned(text, LINK_PATTERN, m ->
                term.cyan(m.group(1)) + term.dim(" (" + m.group(2) + ")"));
        return text;
    }

    private String renderHeading(String text, int level) {
        String formatted = renderInline(text);
        String plain = AsciiRenderer.stripAnsi(formatted);
        switch (level) {
            case 1:
                return "\n" + term.bold(term.cyan(formatted)) + "\n"
                        + term.cyan("═".repeat(plain.length()));
            case 2:
                return "\n" + term.bold(term.blue(formatted)) + "\n"
                        + term.dim("─".repeat(plain.length()));
            case 3:
                return "\n" + term.bold(formatted);
            case 4:
                return term.bold(term.dim(formatted));
            default:
                return term.dim(formatted);
        }
    }

    private static String replacePatterned(String text, Pattern pattern,
                                           java.util.function.Function<Matcher, String> replacer) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) return text;
        StringBuilder sb = new StringBuilder();
        m.reset();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(replacer.apply(m)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
