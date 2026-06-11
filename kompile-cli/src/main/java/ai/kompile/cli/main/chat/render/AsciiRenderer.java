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

import java.util.*;
import java.util.function.IntSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Custom ASCII/Unicode TUI renderer for the kompile CLI chat interface.
 * Provides rich terminal rendering with box-drawing, markdown, tables,
 * tree views, diffs, code blocks, and layout composition.
 *
 * Inspired by Charmbracelet's Lipgloss/Bubbles ecosystem, adapted for
 * a Java CLI chat assistant. Supports both Unicode box-drawing (modern
 * terminals) and pure ASCII fallback.
 *
 * Usage:
 * <pre>
 *   AsciiRenderer r = new AsciiRenderer(terminalRenderer);
 *   System.out.println(r.panel("Title", "Body content here"));
 *   System.out.println(r.table(headers, rows));
 *   System.out.println(r.renderMarkdown("# Hello\n**bold** text"));
 * </pre>
 */
public class AsciiRenderer {

    // ========================================================================
    // Border character sets
    // ========================================================================

    /** Unicode single-line box drawing */
    public static final BorderChars SINGLE = new BorderChars(
            '┌', '┐', '└', '┘', '─', '│', '├', '┤', '┬', '┴', '┼');

    /** Unicode double-line box drawing */
    public static final BorderChars DOUBLE = new BorderChars(
            '╔', '╗', '╚', '╝', '═', '║', '╠', '╣', '╦', '╩', '╬');

    /** Unicode rounded box drawing */
    public static final BorderChars ROUNDED = new BorderChars(
            '╭', '╮', '╰', '╯', '─', '│', '├', '┤', '┬', '┴', '┼');

    /** Unicode heavy/thick box drawing */
    public static final BorderChars HEAVY = new BorderChars(
            '┏', '┓', '┗', '┛', '━', '┃', '┣', '┫', '┳', '┻', '╋');

    /** Pure ASCII fallback */
    public static final BorderChars ASCII = new BorderChars(
            '+', '+', '+', '+', '-', '|', '+', '+', '+', '+', '+');

    // ========================================================================
    // Markdown patterns
    // ========================================================================

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

    // Tool block and thinking patterns (agent-specific markers)
    private static final Pattern TOOL_BLOCK_PATTERN = Pattern.compile(
            "\\[tool:(\\w+)]\\n?(.*?)\\[/tool]", Pattern.DOTALL);
    private static final Pattern TOOL_RESULT_PATTERN = Pattern.compile(
            "\\[tool-result]\\n?(.*?)\\[/tool-result]", Pattern.DOTALL);
    private static final Pattern THINKING_PATTERN = Pattern.compile(
            "<thinking>\\n?(.*?)</thinking>", Pattern.DOTALL);

    // ========================================================================
    // Diff patterns
    // ========================================================================

    private static final Pattern DIFF_HEADER = Pattern.compile("^(diff|index|---\\s|\\+\\+\\+\\s|@@).*");
    private static final Pattern DIFF_ADD = Pattern.compile("^\\+(.*)$");
    private static final Pattern DIFF_REMOVE = Pattern.compile("^-(.*)$");

    // ========================================================================
    // Fields
    // ========================================================================

    private final TerminalRenderer term;
    private final BorderChars defaultBorder;
    private final int terminalWidth;

    public AsciiRenderer(TerminalRenderer terminalRenderer) {
        this(terminalRenderer, 100);
    }

    public AsciiRenderer(TerminalRenderer terminalRenderer, int terminalWidth) {
        this.term = terminalRenderer;
        this.defaultBorder = term.isAnsiEnabled() ? ROUNDED : ASCII;
        this.terminalWidth = Math.max(40, terminalWidth);
    }

    /**
     * Constructor with a dynamic width supplier. The width is sampled once at
     * construction time; for fully dynamic resizing the caller should recreate
     * the renderer.
     */
    public AsciiRenderer(TerminalRenderer terminalRenderer, IntSupplier widthSupplier) {
        this(terminalRenderer, widthSupplier != null ? widthSupplier.getAsInt() : 100);
    }

    // ========================================================================
    // Panel / Box rendering
    // ========================================================================

    /**
     * Render content inside a bordered panel with optional title.
     */
    public String panel(String title, String body) {
        return panel(title, body, defaultBorder, null);
    }

    /**
     * Render content inside a bordered panel with a specific border style and color.
     */
    public String panel(String title, String body, BorderChars border, String color) {
        int maxContentWidth = terminalWidth - 4; // 2 border chars + 2 padding
        List<String> bodyLines = wrapText(body, maxContentWidth);

        int contentWidth = 0;
        for (String line : bodyLines) {
            contentWidth = Math.max(contentWidth, stripAnsi(line).length());
        }
        if (title != null && !title.isEmpty()) {
            contentWidth = Math.max(contentWidth, stripAnsi(title).length() + 2);
        }
        contentWidth = Math.min(contentWidth, maxContentWidth);

        StringBuilder sb = new StringBuilder();

        // Top border with title
        String topBar;
        if (title != null && !title.isEmpty()) {
            String titleRendered = " " + term.bold(title) + " ";
            int titleVisLen = stripAnsi(titleRendered).length();
            int remaining = contentWidth + 2 - titleVisLen;
            int leftPad = 1;
            int rightPad = Math.max(0, remaining - leftPad);
            topBar = colorize(String.valueOf(border.topLeft), color)
                    + colorize(repeat(border.horizontal, leftPad), color)
                    + titleRendered
                    + colorize(repeat(border.horizontal, rightPad), color)
                    + colorize(String.valueOf(border.topRight), color);
        } else {
            topBar = colorize(String.valueOf(border.topLeft), color)
                    + colorize(repeat(border.horizontal, contentWidth + 2), color)
                    + colorize(String.valueOf(border.topRight), color);
        }
        sb.append(topBar).append("\n");

        // Body lines
        for (String line : bodyLines) {
            int lineVisLen = stripAnsi(line).length();
            int padding = contentWidth - lineVisLen;
            sb.append(colorize(String.valueOf(border.vertical), color))
                    .append(" ").append(line).append(repeat(' ', Math.max(0, padding)))
                    .append(" ")
                    .append(colorize(String.valueOf(border.vertical), color))
                    .append("\n");
        }

        // Bottom border
        sb.append(colorize(String.valueOf(border.bottomLeft), color))
                .append(colorize(repeat(border.horizontal, contentWidth + 2), color))
                .append(colorize(String.valueOf(border.bottomRight), color));

        return sb.toString();
    }

    /**
     * Render a panel with a specific style (info, success, warning, error).
     */
    public String infoPanel(String title, String body) {
        return panel(title, body, defaultBorder, "blue");
    }

    public String successPanel(String title, String body) {
        return panel(title, body, defaultBorder, "green");
    }

    public String warningPanel(String title, String body) {
        return panel(title, body, defaultBorder, "yellow");
    }

    public String errorPanel(String title, String body) {
        return panel(title, body, defaultBorder, "red");
    }

    // ========================================================================
    // Table rendering
    // ========================================================================

    /**
     * Render a table with headers and rows. Auto-sizes columns.
     */
    public String table(List<String> headers, List<List<String>> rows) {
        return table(headers, rows, defaultBorder);
    }

    /**
     * Render a table with specific border style.
     */
    public String table(List<String> headers, List<List<String>> rows, BorderChars border) {
        if (headers == null || headers.isEmpty()) return "";

        int cols = headers.size();

        // Calculate column widths
        int[] widths = new int[cols];
        for (int i = 0; i < cols; i++) {
            widths[i] = stripAnsi(headers.get(i)).length();
        }
        for (List<String> row : rows) {
            for (int i = 0; i < Math.min(cols, row.size()); i++) {
                widths[i] = Math.max(widths[i], stripAnsi(row.get(i)).length());
            }
        }

        // Cap columns to fit terminal
        int totalWidth = 1; // left border
        for (int w : widths) totalWidth += w + 3; // content + padding + separator
        if (totalWidth > terminalWidth) {
            // Shrink widest columns proportionally
            int excess = totalWidth - terminalWidth;
            for (int i = 0; i < cols && excess > 0; i++) {
                int maxShrink = widths[i] - 3; // minimum 3 chars
                if (maxShrink > 0) {
                    int shrink = Math.min(maxShrink, excess);
                    widths[i] -= shrink;
                    excess -= shrink;
                }
            }
        }

        StringBuilder sb = new StringBuilder();

        // Top border
        sb.append(border.topLeft);
        for (int i = 0; i < cols; i++) {
            sb.append(repeat(border.horizontal, widths[i] + 2));
            sb.append(i < cols - 1 ? border.teeDown : border.topRight);
        }
        sb.append("\n");

        // Header row
        sb.append(border.vertical);
        for (int i = 0; i < cols; i++) {
            sb.append(" ").append(term.bold(padRight(headers.get(i), widths[i]))).append(" ");
            sb.append(border.vertical);
        }
        sb.append("\n");

        // Header separator
        sb.append(border.teeRight);
        for (int i = 0; i < cols; i++) {
            sb.append(repeat(border.horizontal, widths[i] + 2));
            sb.append(i < cols - 1 ? border.cross : border.teeLeft);
        }
        sb.append("\n");

        // Data rows
        for (List<String> row : rows) {
            sb.append(border.vertical);
            for (int i = 0; i < cols; i++) {
                String cell = i < row.size() ? row.get(i) : "";
                sb.append(" ").append(padRight(cell, widths[i])).append(" ");
                sb.append(border.vertical);
            }
            sb.append("\n");
        }

        // Bottom border
        sb.append(border.bottomLeft);
        for (int i = 0; i < cols; i++) {
            sb.append(repeat(border.horizontal, widths[i] + 2));
            sb.append(i < cols - 1 ? border.teeUp : border.bottomRight);
        }

        return sb.toString();
    }

    // ========================================================================
    // Tree view rendering
    // ========================================================================

    /**
     * Render a tree structure using box-drawing characters.
     */
    public String tree(TreeNode root) {
        StringBuilder sb = new StringBuilder();
        sb.append(term.bold(root.label));
        if (root.annotation != null) {
            sb.append(" ").append(term.dim(root.annotation));
        }
        sb.append("\n");
        renderTreeChildren(sb, root.children, "");
        return sb.toString().stripTrailing();
    }

    private void renderTreeChildren(StringBuilder sb, List<TreeNode> children, String prefix) {
        if (children == null) return;
        for (int i = 0; i < children.size(); i++) {
            boolean isLast = (i == children.size() - 1);
            TreeNode child = children.get(i);

            String connector = isLast ? "└── " : "├── ";
            String childPrefix = isLast ? "    " : "│   ";

            sb.append(prefix).append(connector);
            if (child.icon != null) sb.append(child.icon).append(" ");
            sb.append(child.label);
            if (child.annotation != null) {
                sb.append(" ").append(term.dim(child.annotation));
            }
            sb.append("\n");

            if (child.children != null && !child.children.isEmpty()) {
                renderTreeChildren(sb, child.children, prefix + childPrefix);
            }
        }
    }

    // ========================================================================
    // Markdown rendering
    // ========================================================================

    /**
     * Render markdown text with ANSI formatting for terminal display.
     * Supports headers, bold, italic, inline code, code blocks, lists,
     * blockquotes, horizontal rules, links, and strikethrough.
     */
    public String renderMarkdown(String markdown) {
        if (markdown == null || markdown.isEmpty()) return "";

        // Pre-process agent-specific blocks before line-by-line markdown rendering
        markdown = preprocessAgentBlocks(markdown);

        StringBuilder sb = new StringBuilder();
        String[] lines = markdown.split("\n", -1);
        boolean inCodeBlock = false;
        String codeBlockLang = null;
        StringBuilder codeBuffer = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            // Code block fence
            if (line.startsWith("```")) {
                if (inCodeBlock) {
                    // End code block - render it
                    sb.append(renderCodeBlock(codeBuffer.toString().stripTrailing(), codeBlockLang));
                    sb.append("\n");
                    codeBuffer.setLength(0);
                    inCodeBlock = false;
                    codeBlockLang = null;
                } else {
                    inCodeBlock = true;
                    codeBlockLang = line.length() > 3 ? line.substring(3).trim() : null;
                }
                continue;
            }

            if (inCodeBlock) {
                if (codeBuffer.length() > 0) codeBuffer.append("\n");
                codeBuffer.append(line);
                continue;
            }

            // Horizontal rule
            if (HR_PATTERN.matcher(line).matches()) {
                sb.append(horizontalRule()).append("\n");
                continue;
            }

            // Heading
            Matcher headingMatcher = HEADING_PATTERN.matcher(line);
            if (headingMatcher.matches()) {
                int level = headingMatcher.group(1).length();
                String text = headingMatcher.group(2);
                sb.append(renderHeading(text, level)).append("\n");
                continue;
            }

            // Blockquote
            Matcher quoteMatcher = BLOCKQUOTE_PATTERN.matcher(line);
            if (quoteMatcher.matches()) {
                String quoteText = quoteMatcher.group(1);
                sb.append(renderBlockquote(quoteText)).append("\n");
                continue;
            }

            // Unordered list
            Matcher ulMatcher = UNORDERED_LIST_PATTERN.matcher(line);
            if (ulMatcher.matches()) {
                String indent = ulMatcher.group(1);
                String text = ulMatcher.group(2);
                int depth = indent.length() / 2;
                sb.append(renderListItem(text, depth, false)).append("\n");
                continue;
            }

            // Ordered list
            Matcher olMatcher = ORDERED_LIST_PATTERN.matcher(line);
            if (olMatcher.matches()) {
                String indent = olMatcher.group(1);
                String text = olMatcher.group(2);
                int depth = indent.length() / 2;
                sb.append(renderListItem(text, depth, true)).append("\n");
                continue;
            }

            // Empty line
            if (line.isEmpty()) {
                sb.append("\n");
                continue;
            }

            // Regular paragraph text with inline formatting
            sb.append(renderInlineFormatting(line)).append("\n");
        }

        // Handle unclosed code block
        if (inCodeBlock && codeBuffer.length() > 0) {
            sb.append(renderCodeBlock(codeBuffer.toString().stripTrailing(), codeBlockLang));
        }

        return sb.toString().stripTrailing();
    }

    /**
     * Replace agent-specific block markers ([tool:X]...[/tool], [tool-result]...[/tool-result],
     * &lt;thinking&gt;...&lt;/thinking&gt;) with rendered panel equivalents before line-by-line
     * markdown processing.
     */
    private String preprocessAgentBlocks(String text) {
        // [tool:Name]\n{...}\n[/tool] → rendered panel with tool name header
        text = replacePatterned(text, TOOL_BLOCK_PATTERN, m -> {
            String toolName = m.group(1);
            String body = m.group(2).trim();
            return panel(term.bold(toolName), body, SINGLE, "dim");
        });

        // [tool-result]\n...\n[/tool-result] → rendered panel
        text = replacePatterned(text, TOOL_RESULT_PATTERN, m -> {
            String body = m.group(1).trim();
            return panel(term.dim("Result"), body, SINGLE, "dim");
        });

        // <thinking>\n...\n</thinking> → rendered dim panel
        text = replacePatterned(text, THINKING_PATTERN, m -> {
            String body = m.group(1).trim();
            return panel(term.dim("Thinking"), term.dim(body), SINGLE, "dim");
        });

        return text;
    }

    private String renderHeading(String text, int level) {
        String formatted = renderInlineFormatting(text);
        switch (level) {
            case 1:
                String underline = repeat('═', stripAnsi(formatted).length());
                return "\n" + term.bold(term.cyan(formatted)) + "\n" + term.cyan(underline);
            case 2:
                String underline2 = repeat('─', stripAnsi(formatted).length());
                return "\n" + term.bold(term.blue(formatted)) + "\n" + term.dim(underline2);
            case 3:
                return "\n" + term.bold(formatted);
            case 4:
                return term.bold(term.dim(formatted));
            default:
                return term.dim(formatted);
        }
    }

    private String renderBlockquote(String text) {
        String bar = term.dim(term.cyan("│"));
        return bar + " " + term.dim(renderInlineFormatting(text));
    }

    private String renderListItem(String text, int depth, boolean ordered) {
        String indent = "  ".repeat(depth);
        String bullet;
        if (ordered) {
            bullet = term.dim("  " + indent) + term.cyan("•") + " ";
        } else {
            String[] bullets = {"●", "○", "▪", "▫"};
            String sym = bullets[Math.min(depth, bullets.length - 1)];
            bullet = term.dim("  " + indent) + term.cyan(sym) + " ";
        }
        return bullet + renderInlineFormatting(text);
    }

    /**
     * Render inline markdown formatting: **bold**, *italic*, `code`,
     * ~~strikethrough~~, [links](url).
     */
    public String renderInlineFormatting(String text) {
        // Order matters - process bold before italic to avoid conflict
        text = replacePatterned(text, BOLD_PATTERN, m -> term.bold(m.group(1)));
        text = replacePatterned(text, ITALIC_PATTERN, m -> term.dim(m.group(1)));  // italic → dim
        text = replacePatterned(text, INLINE_CODE_PATTERN, m ->
                term.yellow("`" + m.group(1) + "`"));
        text = replacePatterned(text, STRIKETHROUGH_PATTERN, m -> {
            if (term.isAnsiEnabled()) {
                return "\033[9m" + m.group(1) + "\033[0m"; // strikethrough
            }
            return "~" + m.group(1) + "~";
        });
        text = replacePatterned(text, LINK_PATTERN, m ->
                term.cyan(m.group(1)) + term.dim(" (" + m.group(2) + ")"));
        return text;
    }

    // ========================================================================
    // Code block rendering
    // ========================================================================

    /**
     * Render a code block with line numbers and optional language label.
     */
    public String renderCodeBlock(String code, String language) {
        if (code == null || code.isEmpty()) return "";

        String[] lines = code.split("\n", -1);
        int gutterWidth = String.valueOf(lines.length).length();
        int contentWidth = 0;
        for (String line : lines) {
            contentWidth = Math.max(contentWidth, line.length());
        }
        contentWidth = Math.min(contentWidth, terminalWidth - gutterWidth - 6);

        StringBuilder sb = new StringBuilder();

        // Top bar with language
        String langLabel = (language != null && !language.isEmpty())
                ? " " + term.dim(term.cyan(language)) + " " : "";
        int topBarWidth = contentWidth + gutterWidth + 3;
        int labelVisLen = stripAnsi(langLabel).length();
        sb.append(term.dim("╭"))
                .append(langLabel)
                .append(term.dim(repeat('─', Math.max(0, topBarWidth - labelVisLen))))
                .append(term.dim("╮"))
                .append("\n");

        // Code lines with gutter
        for (int i = 0; i < lines.length; i++) {
            String lineNum = String.format("%" + gutterWidth + "d", i + 1);
            String codeLine = lines[i];
            if (codeLine.length() > contentWidth) {
                codeLine = codeLine.substring(0, contentWidth - 1) + "…";
            }
            int pad = contentWidth - codeLine.length();

            sb.append(term.dim("│"))
                    .append(term.dim(lineNum))
                    .append(term.dim(" │"))
                    .append(" ").append(codeLine).append(repeat(' ', Math.max(0, pad)))
                    .append(term.dim(" │"))
                    .append("\n");
        }

        // Bottom bar
        sb.append(term.dim("╰"))
                .append(term.dim(repeat('─', topBarWidth)))
                .append(term.dim("╯"));

        return sb.toString();
    }

    // ========================================================================
    // Diff rendering
    // ========================================================================

    /**
     * Render a unified diff with colored +/- lines.
     */
    public String renderDiff(String diff) {
        if (diff == null || diff.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        String[] lines = diff.split("\n", -1);

        for (String line : lines) {
            if (line.startsWith("@@")) {
                // Hunk header
                sb.append(term.cyan(line)).append("\n");
            } else if (line.startsWith("+++") || line.startsWith("---")) {
                // File headers
                sb.append(term.bold(line)).append("\n");
            } else if (line.startsWith("+")) {
                sb.append(term.green("+ " + line.substring(1))).append("\n");
            } else if (line.startsWith("-")) {
                sb.append(term.red("- " + line.substring(1))).append("\n");
            } else if (line.startsWith("diff ") || line.startsWith("index ")) {
                sb.append(term.dim(line)).append("\n");
            } else {
                sb.append("  ").append(line).append("\n");
            }
        }

        return sb.toString().stripTrailing();
    }

    /**
     * Render a side-by-side diff for two pieces of content.
     */
    public String renderSideBySideDiff(String oldContent, String newContent,
                                        String oldLabel, String newLabel) {
        String[] oldLines = oldContent.split("\n", -1);
        String[] newLines = newContent.split("\n", -1);
        int maxLines = Math.max(oldLines.length, newLines.length);

        int halfWidth = (terminalWidth - 3) / 2; // 3 for separator
        StringBuilder sb = new StringBuilder();

        // Header
        sb.append(term.red(padRight(oldLabel != null ? oldLabel : "old", halfWidth)));
        sb.append(term.dim(" │ "));
        sb.append(term.green(padRight(newLabel != null ? newLabel : "new", halfWidth)));
        sb.append("\n");
        sb.append(repeat('─', halfWidth));
        sb.append(term.dim("─┼─"));
        sb.append(repeat('─', halfWidth));
        sb.append("\n");

        for (int i = 0; i < maxLines; i++) {
            String oldLine = i < oldLines.length ? oldLines[i] : "";
            String newLine = i < newLines.length ? newLines[i] : "";

            if (oldLine.length() > halfWidth) oldLine = oldLine.substring(0, halfWidth - 1) + "…";
            if (newLine.length() > halfWidth) newLine = newLine.substring(0, halfWidth - 1) + "…";

            boolean changed = !oldLine.equals(newLine);
            String oldFormatted = changed ? term.red(padRight(oldLine, halfWidth))
                    : padRight(oldLine, halfWidth);
            String newFormatted = changed ? term.green(padRight(newLine, halfWidth))
                    : padRight(newLine, halfWidth);

            sb.append(oldFormatted);
            sb.append(term.dim(" │ "));
            sb.append(newFormatted);
            sb.append("\n");
        }

        return sb.toString().stripTrailing();
    }

    // ========================================================================
    // File content rendering with line numbers
    // ========================================================================

    /**
     * Render file content with line numbers, optional highlights.
     */
    public String renderFileContent(String content, String filename, int startLine,
                                     Set<Integer> highlightLines) {
        if (content == null || content.isEmpty()) return "";

        String[] lines = content.split("\n", -1);
        int endLine = startLine + lines.length - 1;
        int gutterWidth = String.valueOf(endLine).length();

        StringBuilder sb = new StringBuilder();

        // File header
        if (filename != null) {
            sb.append(term.dim("─── ")).append(term.bold(term.cyan(filename)));
            sb.append(term.dim(" ───")).append("\n");
        }

        for (int i = 0; i < lines.length; i++) {
            int lineNum = startLine + i;
            String gutter = String.format("%" + gutterWidth + "d", lineNum);
            boolean highlight = highlightLines != null && highlightLines.contains(lineNum);

            if (highlight) {
                sb.append(term.yellow(gutter)).append(term.dim(" │ "))
                        .append(term.yellow(lines[i])).append("\n");
            } else {
                sb.append(term.dim(gutter)).append(term.dim(" │ "))
                        .append(lines[i]).append("\n");
            }
        }

        return sb.toString().stripTrailing();
    }

    // ========================================================================
    // Progress bar rendering
    // ========================================================================

    /**
     * Render a progress bar with label and percentage.
     */
    public String progressBar(String label, double progress, int width) {
        progress = Math.max(0, Math.min(1, progress));
        int filled = (int) (width * progress);
        int empty = width - filled;
        int percent = (int) (progress * 100);

        StringBuilder sb = new StringBuilder();
        if (label != null && !label.isEmpty()) {
            sb.append(label).append(" ");
        }

        sb.append(term.dim("["));
        sb.append(term.green("█".repeat(filled)));
        sb.append(term.dim("░".repeat(empty)));
        sb.append(term.dim("]"));
        sb.append(" ").append(percent).append("%");

        return sb.toString();
    }

    /**
     * Render a multi-segment progress bar (e.g., for task status breakdown).
     */
    public String multiProgressBar(Map<String, Integer> segments, int width) {
        int total = segments.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) return term.dim("[" + "░".repeat(width) + "]");

        StringBuilder bar = new StringBuilder();
        bar.append(term.dim("["));

        String[] colors = {"green", "yellow", "blue", "red", "magenta", "cyan"};
        int colorIdx = 0;
        int usedWidth = 0;

        for (Map.Entry<String, Integer> entry : segments.entrySet()) {
            int segWidth = Math.max(0, (entry.getValue() * width) / total);
            if (usedWidth + segWidth > width) segWidth = width - usedWidth;
            String block = "█".repeat(segWidth);
            bar.append(colorize(block, colors[colorIdx % colors.length]));
            usedWidth += segWidth;
            colorIdx++;
        }

        // Fill remaining
        if (usedWidth < width) {
            bar.append(term.dim("░".repeat(width - usedWidth)));
        }

        bar.append(term.dim("]"));

        // Legend
        StringBuilder legend = new StringBuilder();
        colorIdx = 0;
        for (Map.Entry<String, Integer> entry : segments.entrySet()) {
            if (legend.length() > 0) legend.append(term.dim(" · "));
            String dot = colorize("●", colors[colorIdx % colors.length]);
            legend.append(dot).append(" ").append(entry.getKey())
                    .append(term.dim("(" + entry.getValue() + ")"));
            colorIdx++;
        }

        return bar.toString() + "\n" + legend.toString();
    }

    // ========================================================================
    // Status bar / header bar
    // ========================================================================

    /**
     * Render a status bar spanning the terminal width.
     */
    public String statusBar(String left, String center, String right) {
        int leftLen = left != null ? stripAnsi(left).length() : 0;
        int centerLen = center != null ? stripAnsi(center).length() : 0;
        int rightLen = right != null ? stripAnsi(right).length() : 0;

        int totalContent = leftLen + centerLen + rightLen;
        int totalPadding = Math.max(0, terminalWidth - totalContent);
        int leftPad = totalPadding / 2;
        int rightPad = totalPadding - leftPad;

        StringBuilder sb = new StringBuilder();
        if (term.isAnsiEnabled()) {
            sb.append("\033[7m"); // inverse video
        }

        if (left != null) sb.append(left);
        sb.append(repeat(' ', leftPad));
        if (center != null) sb.append(center);
        sb.append(repeat(' ', rightPad));
        if (right != null) sb.append(right);

        if (term.isAnsiEnabled()) {
            sb.append("\033[0m"); // reset
        }

        return sb.toString();
    }

    /**
     * Render a section header with horizontal rules.
     */
    public String sectionHeader(String title) {
        int titleLen = stripAnsi(title).length();
        int sideLen = Math.max(2, (terminalWidth - titleLen - 4) / 2);
        return term.dim(repeat('─', sideLen))
                + " " + term.bold(title) + " "
                + term.dim(repeat('─', sideLen));
    }

    // ========================================================================
    // Horizontal rule
    // ========================================================================

    /**
     * Render a horizontal rule.
     */
    public String horizontalRule() {
        return term.dim(repeat('─', Math.min(terminalWidth, 80)));
    }

    /**
     * Render a horizontal rule with a specific character.
     */
    public String horizontalRule(char c) {
        return term.dim(repeat(c, Math.min(terminalWidth, 80)));
    }

    // ========================================================================
    // Layout composition
    // ========================================================================

    /**
     * Join multiple blocks vertically (stacked).
     */
    public String joinVertical(String... blocks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < blocks.length; i++) {
            if (blocks[i] != null && !blocks[i].isEmpty()) {
                if (i > 0) sb.append("\n");
                sb.append(blocks[i]);
            }
        }
        return sb.toString();
    }

    /**
     * Join two blocks side by side (horizontal layout).
     */
    public String joinHorizontal(String left, String right, String separator) {
        String[] leftLines = left.split("\n", -1);
        String[] rightLines = right.split("\n", -1);
        int maxLines = Math.max(leftLines.length, rightLines.length);

        int leftWidth = 0;
        for (String l : leftLines) leftWidth = Math.max(leftWidth, stripAnsi(l).length());

        String sep = separator != null ? separator : " │ ";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxLines; i++) {
            String l = i < leftLines.length ? leftLines[i] : "";
            String r = i < rightLines.length ? rightLines[i] : "";
            sb.append(padRight(l, leftWidth)).append(sep).append(r);
            if (i < maxLines - 1) sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Indent a block of text.
     */
    public String indent(String text, int spaces) {
        String prefix = repeat(' ', spaces);
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            sb.append(prefix).append(line).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    // ========================================================================
    // Key/value list rendering
    // ========================================================================

    /**
     * Render a key-value list with aligned values.
     */
    public String keyValueList(Map<String, String> entries) {
        int maxKeyLen = 0;
        for (String key : entries.keySet()) {
            maxKeyLen = Math.max(maxKeyLen, key.length());
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            sb.append("  ")
                    .append(term.bold(String.format("%-" + maxKeyLen + "s", entry.getKey())))
                    .append(term.dim("  "))
                    .append(entry.getValue())
                    .append("\n");
        }
        return sb.toString().stripTrailing();
    }

    // ========================================================================
    // Badge / tag rendering
    // ========================================================================

    /**
     * Render a colored badge/tag.
     */
    public String badge(String text, String color) {
        if (term.isAnsiEnabled()) {
            String colorCode = getColorCode(color);
            return colorCode + "\033[7m " + text + " \033[0m";
        }
        return "[" + text + "]";
    }

    /**
     * Render a status badge (success, warning, error, info).
     */
    public String statusBadge(String status) {
        switch (status.toLowerCase()) {
            case "success":
            case "ok":
            case "pass":
            case "completed":
                return badge(status, "green");
            case "warning":
            case "pending":
            case "in_progress":
                return badge(status, "yellow");
            case "error":
            case "fail":
            case "failed":
            case "cancelled":
                return badge(status, "red");
            default:
                return badge(status, "blue");
        }
    }

    // ========================================================================
    // Banner / ASCII art
    // ========================================================================

    /**
     * Render the kompile CLI banner.
     */
    public String banner() {
        String art = String.join("\n",
                "  ╦╔═╔═╗╔╦╗╔═╗╦╦  ╔═╗",
                "  ╠╩╗║ ║║║║╠═╝║║  ║╣ ",
                "  ╩ ╩╚═╝╩ ╩╩  ╩╩═╝╚═╝"
        );
        return term.cyan(art) + "\n" +
                term.dim("  AI/ML Platform CLI — v0.1.0");
    }

    /**
     * Render a welcome panel for the chat session.
     */
    public String welcomePanel(String sessionId, String agent, boolean ragEnabled) {
        StringBuilder body = new StringBuilder();
        body.append(term.dim("Session: ")).append(sessionId).append("\n");
        body.append(term.dim("Agent:   ")).append(term.cyan(agent)).append("\n");
        body.append(term.dim("RAG:     ")).append(ragEnabled ? term.green("enabled") : term.dim("disabled")).append("\n");
        body.append("\n");
        body.append(term.dim("Type a message to chat, or /help for commands."));
        return panel("kompile chat", body.toString(), ROUNDED, "cyan");
    }

    /**
     * Render welcome panel with mode selection options (chat, passthrough, resume).
     * Shown when first opening the chat TUI to merge all three tools.
     */
    public String welcomePanelWithModes(String sessionId, String agent, boolean ragEnabled, boolean localMode) {
        StringBuilder body = new StringBuilder();
        body.append(term.dim("Session: ")).append(sessionId).append("\n");
        body.append(term.dim("Agent:   ")).append(term.cyan(agent)).append("\n");
        if (!localMode) {
            body.append(term.dim("RAG:     ")).append(ragEnabled ? term.green("enabled") : term.dim("disabled")).append("\n");
        } else {
            body.append(term.dim("Mode:    ")).append(term.cyan("local")).append("\n");
        }
        body.append("\n");
        body.append(term.bold("Quick Start")).append("\n");
        body.append("\n");
        body.append("  ").append(term.cyan("1")).append(". ").append(term.bold("Chat")).append(" — Start chatting immediately\n");
        body.append("     ").append(term.dim("Type a message or use /help for commands")).append("\n");
        body.append("\n");
        body.append("  ").append(term.cyan("2")).append(". ").append(term.bold("Passthrough")).append(" — Launch external CLI agent\n");
        body.append("     ").append(term.dim("Type /passthrough or /mode passthrough")).append("\n");
        body.append("\n");
        body.append("  ").append(term.cyan("3")).append(". ").append(term.bold("Resume")).append(" — Browse & resume past conversations\n");
        body.append("     ").append(term.dim("Type /resume to open the resume browser")).append("\n");
        body.append("\n");
        body.append(term.dim("Type a message to start chatting, or /help for all commands."));
        return panel("kompile chat", body.toString(), ROUNDED, "cyan");
    }

    // ========================================================================
    // Utility methods
    // ========================================================================

    private String colorize(String text, String color) {
        if (color == null || !term.isAnsiEnabled()) return text;
        switch (color) {
            case "red": return term.red(text);
            case "green": return term.green(text);
            case "yellow": return term.yellow(text);
            case "blue": return term.blue(text);
            case "magenta": return term.magenta(text);
            case "cyan": return term.cyan(text);
            default: return text;
        }
    }

    private String getColorCode(String color) {
        if (!term.isAnsiEnabled()) return "";
        switch (color) {
            case "red": return "\033[31m";
            case "green": return "\033[32m";
            case "yellow": return "\033[33m";
            case "blue": return "\033[34m";
            case "magenta": return "\033[35m";
            case "cyan": return "\033[36m";
            default: return "";
        }
    }

    // ========================================================================
    // Sparklines, heatmaps, and score trends
    // ========================================================================

    /**
     * Render a score as a colored heatmap cell (e.g., "3.8" colored green/yellow/red).
     * Score is expected in 0.0–5.0 range. Returns a fixed-width colored string.
     */
    public String scoreHeatmap(double score) {
        String formatted = String.format("%.1f", score);
        if (score >= 4.0) {
            return term.green(formatted);
        } else if (score >= 2.5) {
            return term.yellow(formatted);
        } else {
            return term.red(formatted);
        }
    }

    /**
     * Render a score trend sparkline with a colored indicator based on current average.
     * trend is an array of score values; avg is the current rolling average.
     */
    public String scoreTrend(double[] trend, double avg) {
        String sparkline = sparkline(trend);
        if (avg >= 4.0) {
            return term.green(sparkline);
        } else if (avg >= 2.5) {
            return term.yellow(sparkline);
        } else {
            return term.red(sparkline);
        }
    }

    /**
     * Render a sparkline with color based on the trend direction (rising=green, falling=red).
     */
    public String coloredSparkline(double[] values) {
        if (values == null || values.length == 0) return "";
        String sparkline = sparkline(values);
        if (values.length >= 2) {
            double last = values[values.length - 1];
            double first = values[0];
            if (last > first) {
                return term.green(sparkline);
            } else if (last < first) {
                return term.red(sparkline);
            }
        }
        return term.dim(sparkline);
    }

    /**
     * Render a raw sparkline string from an array of values.
     * Uses Unicode block characters: ▁▂▃▄▅▆▇█
     */
    public static String sparkline(double[] values) {
        if (values == null || values.length == 0) return "";
        final char[] BLOCKS = {'▁', '▂', '▃', '▄', '▅', '▆', '▇', '█'};
        double min = Double.MAX_VALUE, max = Double.MIN_VALUE;
        for (double v : values) {
            if (v < min) min = v;
            if (v > max) max = v;
        }
        double range = max - min;
        StringBuilder sb = new StringBuilder();
        for (double v : values) {
            int idx;
            if (range == 0) {
                idx = 3; // middle block when all values are equal
            } else {
                idx = (int) Math.round((v - min) / range * (BLOCKS.length - 1));
                idx = Math.max(0, Math.min(BLOCKS.length - 1, idx));
            }
            sb.append(BLOCKS[idx]);
        }
        return sb.toString();
    }

    /**
     * Strip ANSI escape sequences for measuring visible string length.
     */
    public static String stripAnsi(String text) {
        if (text == null) return "";
        return text.replaceAll("\033\\[[0-9;]*m", "");
    }

    /**
     * Pad a string to a minimum visible width (accounting for ANSI).
     */
    public static String padRight(String text, int width) {
        int visLen = stripAnsi(text).length();
        if (visLen >= width) return text;
        return text + repeat(' ', width - visLen);
    }

    /**
     * Wrap text to fit within a maximum width.
     */
    public static List<String> wrapText(String text, int maxWidth) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            result.add("");
            return result;
        }

        for (String paragraph : text.split("\n", -1)) {
            if (paragraph.isEmpty()) {
                result.add("");
                continue;
            }

            // Don't wrap lines that contain ANSI (complex to measure)
            if (paragraph.contains("\033[")) {
                result.add(paragraph);
                continue;
            }

            if (paragraph.length() <= maxWidth) {
                result.add(paragraph);
                continue;
            }

            // Word wrap
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split("\\s+")) {
                if (line.length() == 0) {
                    line.append(word);
                } else if (line.length() + 1 + word.length() <= maxWidth) {
                    line.append(" ").append(word);
                } else {
                    result.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                }
            }
            if (line.length() > 0) {
                result.add(line.toString());
            }
        }

        return result;
    }

    private static String repeat(char c, int count) {
        if (count <= 0) return "";
        return String.valueOf(c).repeat(count);
    }

    private static String replacePatterned(String text, Pattern pattern,
                                            java.util.function.Function<Matcher, String> replacer) {
        Matcher m = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(replacer.apply(m)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public TerminalRenderer getTerminalRenderer() {
        return term;
    }

    // ========================================================================
    // Data types
    // ========================================================================

    /**
     * Box-drawing character set for borders.
     */
    public static class BorderChars {
        public final char topLeft, topRight, bottomLeft, bottomRight;
        public final char horizontal, vertical;
        public final char teeRight, teeLeft, teeDown, teeUp, cross;

        public BorderChars(char topLeft, char topRight, char bottomLeft, char bottomRight,
                           char horizontal, char vertical,
                           char teeRight, char teeLeft, char teeDown, char teeUp, char cross) {
            this.topLeft = topLeft;
            this.topRight = topRight;
            this.bottomLeft = bottomLeft;
            this.bottomRight = bottomRight;
            this.horizontal = horizontal;
            this.vertical = vertical;
            this.teeRight = teeRight;
            this.teeLeft = teeLeft;
            this.teeDown = teeDown;
            this.teeUp = teeUp;
            this.cross = cross;
        }
    }

    /**
     * Node in a tree structure for tree rendering.
     */
    public static class TreeNode {
        public final String label;
        public final String icon;
        public final String annotation;
        public final List<TreeNode> children;

        public TreeNode(String label) {
            this(label, null, null, null);
        }

        public TreeNode(String label, String icon, String annotation, List<TreeNode> children) {
            this.label = label;
            this.icon = icon;
            this.annotation = annotation;
            this.children = children;
        }

        public static TreeNode of(String label, TreeNode... children) {
            return new TreeNode(label, null, null,
                    children.length > 0 ? Arrays.asList(children) : null);
        }

        public static TreeNode leaf(String label, String icon, String annotation) {
            return new TreeNode(label, icon, annotation, null);
        }

        public static TreeNode branch(String label, String icon, String annotation,
                                       TreeNode... children) {
            return new TreeNode(label, icon, annotation, Arrays.asList(children));
        }
    }
}
