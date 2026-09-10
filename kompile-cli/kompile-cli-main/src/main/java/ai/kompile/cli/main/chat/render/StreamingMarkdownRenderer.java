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
import java.util.function.Consumer;

/**
 * Incremental markdown renderer for streaming LLM output.
 * <p>
 * Tokens arrive one fragment at a time. This renderer buffers them into
 * complete lines, then applies markdown formatting (headings, bold, italic,
 * inline code, lists, blockquotes, code fences, horizontal rules) and prints
 * each line as soon as it's complete — preserving the streaming feel while
 * producing formatted output.
 * <p>
 * Fenced code is rendered through a streaming gutter so a long block remains
 * visible while the model is still generating it. Holding the complete block
 * until its closing fence can make a healthy token stream look stalled.
 */
public class StreamingMarkdownRenderer {

    private final AsciiRenderer ascii;
    private final TerminalRenderer term;
    private final Consumer<String> linePrinter;
    private final SyntaxHighlighter syntaxHighlighter;

    // Line buffer — accumulates tokens until a newline arrives
    private final StringBuilder lineBuffer = new StringBuilder();

    // Code block state
    private boolean inCodeBlock = false;
    private String codeBlockLang = null;
    private int codeLineNumber = 1;

    // Patterns (same as AsciiRenderer)
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern ITALIC_PATTERN = Pattern.compile("(?<![*])\\*(?![*])(.+?)(?<![*])\\*(?![*])");
    private static final Pattern INLINE_CODE_PATTERN = Pattern.compile("`([^`]+)`");
    private static final Pattern STRIKETHROUGH_PATTERN = Pattern.compile("~~(.+?)~~");
    private static final Pattern LINK_PATTERN = Pattern.compile("\\[([^]]+)]\\(([^)]+)\\)");
    private static final Pattern UNORDERED_LIST_PATTERN = Pattern.compile("^(\\s*)[-*+]\\s+(.+)$");
    private static final Pattern ORDERED_LIST_PATTERN = Pattern.compile("^(\\s*)(\\d+)\\.\\s+(.+)$");
    private static final Pattern BLOCKQUOTE_PATTERN = Pattern.compile("^>\\s?(.*)$");
    private static final Pattern HR_PATTERN = Pattern.compile("^([-*_])\\1{2,}\\s*$");

    public StreamingMarkdownRenderer(AsciiRenderer ascii) {
        this(ascii, line -> System.out.println(line));
    }

    /**
     * Create a streaming renderer with a caller-owned complete-line sink.
     * Interactive REPLs use this to route asynchronous model output through
     * JLine's {@code printAbove} path without corrupting the active input buffer.
     */
    public StreamingMarkdownRenderer(AsciiRenderer ascii, Consumer<String> linePrinter) {
        this.ascii = ascii;
        this.term = ascii.getTerminalRenderer();
        this.linePrinter = linePrinter != null ? linePrinter : line -> System.out.println(line);
        this.syntaxHighlighter = new SyntaxHighlighter(term);
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
                if (!line.startsWith("```")) {
                    printCodeLine(line);
                }
                closeCodeBlock();
            } else {
                // Partial line — render with inline formatting
                printLine(renderInline(line));
            }
        } else if (inCodeBlock) {
            closeCodeBlock();
        }
    }

    /**
     * Discard buffered state without emitting it. Normal stream completion
     * should call {@link #flush()} so an open code gutter is closed.
     */
    public void reset() {
        lineBuffer.setLength(0);
        inCodeBlock = false;
        codeBlockLang = null;
        codeLineNumber = 1;
    }

    // ── Line processing ──────────────────────────────────────────────────

    private void processLine(String line) {
        // Code block fence
        if (line.startsWith("```")) {
            if (inCodeBlock) {
                closeCodeBlock();
            } else {
                inCodeBlock = true;
                codeBlockLang = line.length() > 3 ? line.substring(3).trim() : null;
                if (codeBlockLang != null && codeBlockLang.isEmpty()) codeBlockLang = null;
                codeLineNumber = 1;
                printLine(codeBlockLang == null
                        ? term.dim("╭─")
                        : term.dim("╭─") + " " + term.dim(term.cyan(codeBlockLang)));
            }
            return;
        }

        // Emit complete code lines immediately. The direct provider parser runs
        // this callback on its stream-reading thread, so buffering until the
        // closing fence otherwise hides seconds of already-received output.
        if (inCodeBlock) {
            printCodeLine(line);
            return;
        }

        // Horizontal rule
        if (HR_PATTERN.matcher(line).matches()) {
            printLine(ascii.horizontalRule());
            return;
        }

        // Heading
        Matcher headingMatcher = HEADING_PATTERN.matcher(line);
        if (headingMatcher.matches()) {
            int level = headingMatcher.group(1).length();
            String text = headingMatcher.group(2);
            printLine(renderHeading(text, level));
            return;
        }

        // Blockquote
        Matcher quoteMatcher = BLOCKQUOTE_PATTERN.matcher(line);
        if (quoteMatcher.matches()) {
            String quoteText = quoteMatcher.group(1);
            String bar = term.dim(term.cyan("│"));
            printLine(bar + " " + term.dim(renderInline(quoteText)));
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
            printLine(bullet + renderInline(text));
            return;
        }

        // Ordered list
        Matcher olMatcher = ORDERED_LIST_PATTERN.matcher(line);
        if (olMatcher.matches()) {
            String indent = olMatcher.group(1);
            String number = olMatcher.group(2);
            String text = olMatcher.group(3);
            String bullet = term.dim("  " + indent) + term.cyan(number + ".") + " ";
            printLine(bullet + renderInline(text));
            return;
        }

        // Empty line
        if (line.isEmpty()) {
            printLine("");
            return;
        }

        // Regular text — apply inline formatting
        printLine(renderInline(line));
    }

    private void printLine(String line) {
        linePrinter.accept(line == null ? "" : line);
    }

    private void printCodeLine(String line) {
        String number = Integer.toString(codeLineNumber++);
        number = " ".repeat(Math.max(0, 4 - number.length())) + number;
        String highlighted = syntaxHighlighter.highlight(line, codeBlockLang);
        printLine(term.dim("│ " + number + " │") + " " + highlighted);
    }

    private void closeCodeBlock() {
        printLine(term.dim("╰─"));
        inCodeBlock = false;
        codeBlockLang = null;
        codeLineNumber = 1;
    }

    // ── Inline formatting ────────────────────────────────────────────────

    private String renderInline(String text) {
        // Bold before italic to avoid conflict on single *
        text = replacePatterned(text, BOLD_PATTERN, m -> term.bold(m.group(1)));
        text = replacePatterned(text, ITALIC_PATTERN, m -> term.italic(m.group(1)));
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
