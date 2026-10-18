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

import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.tools.TodoWriteTool;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.utils.AnsiConstants;
import com.fasterxml.jackson.databind.JsonNode;
import org.jline.terminal.Terminal;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ANSI terminal renderer for tool calls, subagents, todos, and agent output.
 * Provides colored, structured output comparable to OpenCode's TUI rendering.
 *
 * Detects terminal capability and falls back to plain text when ANSI is not supported.
 */
public class TerminalRenderer {

    // ANSI escape codes
    private static final String ESC = "\033[";
    private static final String RESET = ESC + "0m";
    private static final String BOLD = ESC + "1m";
    private static final String DIM = ESC + "2m";
    private static final String ITALIC = ESC + "3m";
    private static final String UNDERLINE = ESC + "4m";

    // Colors
    private static final String FG_RED = ESC + "31m";
    private static final String FG_GREEN = ESC + "32m";
    private static final String FG_YELLOW = ESC + "33m";
    private static final String FG_BLUE = ESC + "34m";
    private static final String FG_MAGENTA = ESC + "35m";
    private static final String FG_CYAN = ESC + "36m";
    private static final String FG_WHITE = ESC + "37m";
    private static final String FG_GRAY = ESC + "90m";

    // Braille spinner frames (like OpenCode's)
    private static final String[] SPINNER_FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧"};
    private static final int MAX_CONTEXT_TOOL_BUCKETS = 4;
    private static final int MAX_SUBAGENT_TOOL_OUTPUT_CHARS = 8_000;
    private static final int MAX_SUBAGENT_EDIT_DIFF_CHARS = 8_000;
    /**
     * Interactive tool rows are printed above a live JLine prompt. Keep their
     * inline payload small enough that a single completion cannot consume the
     * entire viewport; the full result remains available in the activity panel.
     */
    private static final int MAX_INLINE_TOOL_DETAIL_CHARS = 2_400;
    private static final int MAX_INLINE_TOOL_DETAIL_LINES = 48;
    /** Closes a detail section cut at the inline bounds — in the terminal and in the web chat. */
    public static final String DETAIL_TRUNCATED_NOTE = "… (tool detail truncated at "
            + MAX_INLINE_TOOL_DETAIL_CHARS + " chars / " + MAX_INLINE_TOOL_DETAIL_LINES + " lines)";

    // Tool markers — bold white text, no emojis
    private static final Map<String, String> TOOL_ICONS = Map.ofEntries(
            Map.entry("read", "▸"),
            Map.entry("read_batch", "▸"),
            Map.entry("write", "▸"),
            Map.entry("edit", "▸"),
            Map.entry("edit_batch", "▸"),
            Map.entry("edit_patch", "▸"),
            Map.entry("patch", "▸"),
            Map.entry("bash", "▸"),
            Map.entry("grep", "▸"),
            Map.entry("glob", "▸"),
            Map.entry("list", "▸"),
            Map.entry("webfetch", "▸"),
            Map.entry("websearch", "▸"),
            Map.entry("task", "▸"),
            Map.entry("multi_task", "▸"),
            Map.entry("quorum_task", "▸"),
            Map.entry("todowrite", "▸"),
            Map.entry("todoread", "▸"),
            Map.entry("exit_plan_mode", "▸"),
            Map.entry("code_search", "▸"),
            Map.entry("code_graph", "▸"),
            Map.entry("rag_search", "▸"),
            Map.entry("graph_search", "▸"),
            Map.entry("memory", "▸"),
            Map.entry("semantic_memory", "▸"),
            Map.entry("explore", "▸"),
            Map.entry("process", "▸"),
            Map.entry("browser", "▸"),
            Map.entry("edit_coordinator", "▸"),
            Map.entry("toolsearch", "▸"),
            Map.entry("agent", "▸"),
            Map.entry("exec", "▸")
    );

    /** Shared Jackson mapper for JSON input parsing. */
    private static final ObjectMapper JSON = JsonUtils.standardMapper();

    /**
     * Primary parameter keys per tool — these are extracted from JSON input
     * and shown as the human-readable description instead of raw JSON.
     * Order matters: first key found wins for single-line display.
     */
    private static final Map<String, String[]> PRIMARY_PARAMS = Map.ofEntries(
            Map.entry("read", new String[]{"file_path"}),
            Map.entry("read_batch", new String[]{"files"}),
            Map.entry("write", new String[]{"file_path"}),
            Map.entry("edit", new String[]{"file_path"}),
            Map.entry("edit_batch", new String[]{"edits"}),
            Map.entry("edit_patch", new String[]{"patches"}),
            Map.entry("patch", new String[]{"file_path"}),
            Map.entry("glob", new String[]{"pattern"}),
            Map.entry("grep", new String[]{"pattern", "path"}),
            Map.entry("grep_batch", new String[]{"queries"}),
            Map.entry("bash", new String[]{"command"}),
            Map.entry("list", new String[]{"path"}),
            Map.entry("webfetch", new String[]{"url"}),
            Map.entry("websearch", new String[]{"query"}),
            Map.entry("task", new String[]{"description"}),
            Map.entry("multi_task", new String[]{"description"}),
            Map.entry("quorum_task", new String[]{"description"}),
            Map.entry("toolsearch", new String[]{"query"}),
            Map.entry("code_search", new String[]{"query"}),
            Map.entry("code_graph", new String[]{"query"}),
            Map.entry("rag_search", new String[]{"query"}),
            Map.entry("graph_search", new String[]{"query"}),
            Map.entry("memory", new String[]{"query", "key"}),
            Map.entry("explore", new String[]{"query"}),
            Map.entry("agent", new String[]{"description"}),
            Map.entry("browser", new String[]{"url"}),
            Map.entry("process", new String[]{"action", "process_id", "description", "command"}),
            Map.entry("todowrite", new String[]{"action", "subject", "task_id", "status"}),
            Map.entry("todoread", new String[]{"action"}),
            Map.entry("edit_coordinator", new String[]{"action", "file_path", "process_id", "agent_name"})
    );

    private static final Set<String> SENSITIVE_PARAMS = Set.of(
            "api_key", "apikey", "authorization", "cookie", "credential",
            "password", "private_key", "secret", "token");

    private static final Set<String> VERBOSE_PARAMS = Set.of(
            "body", "config_json", "content", "new_string", "old_string", "patch", "prompt");

    private final boolean ansiEnabled;
    private final SyntaxHighlighter highlighter;
    private volatile TerminalTitleController titleController;

    public TerminalRenderer() {
        this.ansiEnabled = detectAnsiSupport();
        this.highlighter = new SyntaxHighlighter(this);
    }

    public TerminalRenderer(boolean ansiEnabled) {
        this.ansiEnabled = ansiEnabled;
        this.highlighter = new SyntaxHighlighter(this);
    }

    // ========================================================================
    // Tool call rendering
    // ========================================================================

    /**
     * Render a tool call start (pending/running state).
     * Automatically prettifies MCP-prefixed tool names and parses JSON input
     * into human-readable descriptions.
     * <p>
     * Example: {@code mcp__kompile__read} with input
     * {@code {"file_path":"src/app/foo.ts"}} renders as:
     * <pre>  ▸ Read src/app/foo.ts</pre>
     */
    public String renderToolCallStart(String toolName, String description) {
        String cleanName = stripMcpPrefix(toolName);
        String displayName = prettifyToolName(toolName);
        String icon = TOOL_ICONS.getOrDefault(cleanName, "▸");

        // Try to prettify JSON input into a readable summary
        String prettyDesc = prettifyToolInput(cleanName, description, 80);
        String desc = !prettyDesc.isEmpty() ? " " + dim(prettyDesc) : "";
        return "  " + icon + " " + bold(cyan(displayName)) + desc;
    }

    /**
     * Render a tool call running with spinner.
     */
    public String renderToolCallRunning(String toolName, int spinnerFrame) {
        String cleanName = stripMcpPrefix(toolName);
        String displayName = prettifyToolName(toolName);
        String spinner = ansiEnabled ? yellow(SPINNER_FRAMES[spinnerFrame % SPINNER_FRAMES.length]) : "...";
        return "  " + spinner + " " + cyan(displayName) + " " + dim("running...");
    }

    /**
     * Render a completed tool call with result summary.
     */
    public String renderToolCallComplete(String toolName, ToolResult result) {
        return renderToolCallComplete(toolName, "", result);
    }

    /**
     * Render a completed tool call while retaining the action that was attempted.
     * This is the durable row shown after the transient running state, so it must
     * carry enough context to remain useful on its own.
     */
    public String renderToolCallComplete(String toolName, String rawInput, ToolResult result) {
        return renderToolCallComplete(toolName, rawInput, result, true);
    }

    /** Render only the stable completion row for a managed live tool block. */
    public String renderToolCallSummary(String toolName, String rawInput, ToolResult result) {
        return renderToolCallComplete(toolName, rawInput, result, false);
    }

    private String renderToolCallComplete(String toolName, String rawInput, ToolResult result,
                                          boolean includeDetail) {
        String icon = TOOL_ICONS.getOrDefault(stripMcpPrefix(toolName), "▸");
        ToolRow row = toolRow(toolName, rawInput, result, result.isOutputStreamed());
        StringBuilder sb = new StringBuilder("  ").append(icon).append(" ")
                .append(row.ok() ? bold(green(row.displayName())) : bold(red(row.displayName())));
        if (!row.action().isBlank()) {
            sb.append(" ").append(dim(row.action()));
        }
        sb.append(" ").append(row.ok() ? green("✓") : red("✗"));
        if (row.errorPreview() != null) {
            sb.append(" ").append(red(row.errorPreview()));
        }
        if (row.title() != null) {
            sb.append(" ").append(dim(row.title()));
        }
        if (row.metadata() != null) {
            sb.append(" ").append(dim(row.metadata()));
        }
        if (row.preview() != null) {
            sb.append(" ").append(dim("· " + row.preview()));
        }

        if (includeDetail) {
            String detail = renderToolResultDetail(toolName, rawInput, result);
            if (!detail.isBlank()) {
                sb.append("\n").append(detail);
            }
        }

        return sb.toString();
    }

    /**
     * Render the durable body of a tool result. The compact tool row remains useful
     * as a summary, but the transcript must retain the actual read/output payload and
     * the source-side changes for edit/patch calls.
     */
    public String renderToolResultDetail(String toolName, String rawInput, ToolResult result) {
        return renderToolResultDetail(toolName, rawInput, result,
                result != null && result.isOutputStreamed());
    }

    /**
     * Render detail for a particular viewer. A reopened tool view did not receive
     * the live stream, even when the main transcript did; pass false to retain
     * its output body without changing the shared result's streaming metadata.
     */
    public String renderToolResultDetail(String toolName, String rawInput, ToolResult result,
                                         boolean outputShownLive) {
        if (result == null || outputShownLive) return "";
        List<TodoWriteTool.TodoItem> todos = todoItems(toolName, rawInput, result);
        if (todos != null) return renderTodoList(todos);

        StringBuilder detail = new StringBuilder();
        for (DetailSection section : toolResultDetailSections(toolName, rawInput, result)) {
            // Every block starts on its own line; a result block used to be glued
            // onto the last line of the diff above it.
            if (detail.length() > 0) detail.append('\n');
            detail.append("  ").append(dim("↳ " + section.label() + ":"));
            for (DetailLine line : section.lines()) {
                // Lines were cut on printable text; styling afterwards means escape
                // sequences are never sliced mid-span.
                detail.append("\n     ").append(renderDetailLine(line, section.diff()));
            }
            if (section.truncated()) {
                detail.append("\n     ").append(dim(DETAIL_TRUNCATED_NOTE));
            }
        }
        return detail.toString();
    }

    /**
     * The completion row of a tool call as data: what {@link #renderToolCallComplete} prints,
     * unstyled, so other transports (the web chat) show the same row. {@code metadata} is null
     * when the result has none and can be blank when every key is filtered; {@code title},
     * {@code preview} and {@code errorPreview} are null when absent.
     */
    public record ToolRow(String displayName, String action, boolean ok, String title,
                          String metadata, String preview, String errorPreview) { }

    /**
     * @param outputShownLive the viewer already saw the output stream live, so the row repeats
     *                        none of it — the terminal passes {@link ToolResult#isOutputStreamed()},
     *                        a client that never saw the stream passes false
     */
    public static ToolRow toolRow(String toolName, String rawInput, ToolResult result,
                                  boolean outputShownLive) {
        String cleanName = stripMcpPrefix(toolName);
        // Row text comes from the model's input and the tool's output. Keep it printable so a
        // colored or cursor-moving result cannot restyle or clear the screen that prints it.
        String displayName = printableToolOutput(prettifyToolName(toolName));
        String action = printableToolOutput(prettifyToolInput(cleanName, rawInput, 96));
        if (result.isError()) {
            String errorPreview = outputShownLive ? ""
                    : truncatePreview(printableToolOutput(result.getOutput()), 120);
            return new ToolRow(displayName, action, false, null, null, null,
                    errorPreview.isBlank() ? null : errorPreview);
        }
        Map<String, Object> meta = result.getMetadata();
        String title = result.getTitle();
        String shownTitle = title != null && !title.isEmpty() && !"error".equals(title)
                && !title.equals(action) ? truncatePreview(printableToolOutput(title), 96) : null;
        // Output preview for command/search tools, or when nothing else summarizes the result.
        String preview = null;
        String output = result.getOutput();
        if (!outputShownLive && output != null && !output.isEmpty()
                && todoItems(toolName, rawInput, result) == null
                && (shouldShowPreview(cleanName, meta)
                    || ((title == null || title.isBlank()) && meta.isEmpty()))) {
            String first = firstOutputLine(printableToolOutput(output), 140);
            if (!first.isBlank()) preview = first;
        }
        return new ToolRow(displayName, action, true, shownTitle,
                meta.isEmpty() ? null : printableToolOutput(renderMetadata(meta)), preview, null);
    }

    /**
     * One bounded block of a tool call's detail: the edit diff, written content, or the result
     * body. {@code lineScoped} marks lines whose hints were inferred from each line itself
     * (search hits, compiler output): they are unrelated fragments and style independently,
     * so an unclosed quote or comment on one cannot run into the next.
     */
    public record DetailSection(String label, boolean diff, boolean truncated, boolean lineScoped,
                                List<DetailLine> lines) { }

    /**
     * One visible detail line, printable (see {@link #printableToolOutput}). {@code hint} names
     * the file whose language styles the line (a path or bare filename; null = unstyled). Diff
     * lines are colored by prefix instead.
     */
    public record DetailLine(String text, String hint) { }

    /**
     * The detail body as data, under the same bounds and with the same highlight hints the
     * terminal uses — the single source for {@link #renderToolResultDetail} and the web chat.
     * Unlike the terminal it ignores {@link ToolResult#isOutputStreamed()}: whether streamed
     * output was already seen depends on the viewer.
     */
    public static List<DetailSection> toolResultDetailSections(String toolName, String rawInput,
                                                               ToolResult result) {
        if (result == null) return List.of();
        String cleanName = stripMcpPrefix(toolName);
        List<DetailSection> sections = new ArrayList<>(2);
        List<String> changeLines = renderEditDiffLines(toolName, rawInput);
        if (!changeLines.isEmpty()) {
            addBoundedSection(sections, "diff", changeLines, true, null, false, false);
        } else if ("write".equals(cleanName)) {
            List<String> contentLines = renderWriteContentLines(rawInput);
            if (!contentLines.isEmpty()) {
                addBoundedSection(sections, "content", contentLines, false,
                        languageFromRawInput(rawInput), false, false);
            }
        }

        String output = result.getOutput();
        if (output != null && !output.isBlank()) {
            // Content tools echo file/source payloads — highlight them with the
            // language from the tool input's file path when it names a file;
            // otherwise (grep/glob/list results carry their paths inline, one
            // per output line) fall back to per-line filename inference.
            String label = !changeLines.isEmpty() ? "result" : isContentTool(cleanName) ? "content" : "output";
            String languageHint = isContentTool(cleanName) ? languageFromRawInput(rawInput) : null;
            boolean hintUsable = languageHint != null
                    && SyntaxHighlighter.familyForFilename(languageHint)
                            != SyntaxHighlighter.Family.NONE;
            addBoundedSection(sections, label,
                    List.of(output.stripTrailing().split("\\R", -1)), false,
                    hintUsable ? languageHint : null,
                    !hintUsable && isContentTool(cleanName),
                    "read_batch".equals(cleanName));
        }
        return sections;
    }

    /**
     * Retained inspector payloads, deliberately independent of the terminal/web
     * 2400-char/48-line preview. The caller explicitly chooses a char ceiling.
     * A streamed result is still included: a newly opened viewer has not seen it.
     * Completed output supersedes overlapping live text; complementary live logs
     * stay available in a separate section.
     */
    public static List<ChatDisplayBlock.Section> toolDisplaySections(
            String toolName, String rawInput, ToolResult result, String liveOutput, int maxChars) {
        if (maxChars <= 0 || maxChars > ChatDisplayBlock.MAX_SECTION_CHARS) {
            throw new IllegalArgumentException("Inspector limit must be 1.."
                    + ChatDisplayBlock.MAX_SECTION_CHARS);
        }
        List<ChatDisplayBlock.Section> sections = new ArrayList<>();
        addDisplaySection(sections, "arguments", "arguments", "Arguments", rawInput, maxChars);
        List<String> diff = renderEditDiffLines(toolName, rawInput);
        if (!diff.isEmpty()) {
            addDisplaySection(sections, "diff", "diff", "Diff", String.join("\n", diff), maxChars);
        } else if ("write".equals(stripMcpPrefix(toolName))) {
            // Keep the source text rather than a stripped/split inline preview.
            try {
                addDisplaySection(sections, "content", "content", "Content",
                        textValue(JSON.readTree(rawInput), "content"), maxChars);
            } catch (Exception ignored) { }
        }
        String output = result == null ? liveOutput : result.getOutput();
        if (output == null || output.isEmpty()) output = liveOutput;
        if (result != null && liveOutput != null && !liveOutput.isBlank()
                && output != null && !output.isEmpty()) {
            String live = printableToolOutput(liveOutput).strip();
            String completed = printableToolOutput(output).strip();
            if (!completed.contains(live)) {
                if (result.isOutputStreamed() && !completed.isEmpty() && live.contains(completed)) {
                    output = liveOutput;
                } else {
                    addDisplaySection(sections, "live-output", "output", "Live output", liveOutput, maxChars);
                }
            }
        }
        addDisplaySection(sections, "output", "output", "Output", output, maxChars);
        if (result != null && !result.getMetadata().isEmpty()) {
            try {
                addDisplaySection(sections, "metadata", "metadata", "Metadata",
                        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result.getMetadata()), maxChars);
            } catch (Exception ignored) {
                addDisplaySection(sections, "metadata", "metadata", "Metadata",
                        String.valueOf(result.getMetadata()), maxChars);
            }
        }
        return List.copyOf(sections);
    }

    /** Same syntax/diff styling as transcript details, with the retained viewer's larger budget. */
    public List<ChatDisplayBlock.Section> renderToolDisplaySections(
            String toolName, String rawInput, ToolResult result, String liveOutput, int maxChars) {
        if (maxChars <= 0 || maxChars > ChatDisplayBlock.MAX_SECTION_CHARS) {
            throw new IllegalArgumentException("Inspector limit must be 1.." + ChatDisplayBlock.MAX_SECTION_CHARS);
        }
        List<TodoWriteTool.TodoItem> todos = todoItems(toolName, rawInput, result);
        if (todos != null) {
            String formatted = ChatDisplayBlock.boundedStyledText(renderTodoList(todos), maxChars);
            return List.of(new ChatDisplayBlock.Section("todos", "todos", "Tasks",
                    printableToolOutput(formatted), formatted));
        }
        String cleanName = stripMcpPrefix(toolName);
        List<ChatDisplayBlock.Section> rendered = new ArrayList<>();
        for (ChatDisplayBlock.Section section : toolDisplaySections(toolName, rawInput, result, liveOutput, maxChars)) {
            String formatted;
            if (section.kind().equals("arguments") || section.kind().equals("metadata")) {
                formatted = highlighter.highlight(section.content(), "json");
            } else {
                boolean diff = section.kind().equals("diff");
                String hint = section.id().equals("content") || isContentTool(cleanName) ? languageFromRawInput(rawInput) : null;
                boolean usable = hint != null && SyntaxHighlighter.familyForFilename(hint) != SyntaxHighlighter.Family.NONE;
                List<DetailSection> details = new ArrayList<>();
                addBoundedSection(details, section.title(), List.of(section.content().split("\\R", -1)), diff,
                        usable ? hint : null, !usable && isContentTool(cleanName), "read_batch".equals(cleanName),
                        maxChars, maxChars + 1);
                StringBuilder body = new StringBuilder();
                for (DetailSection detail : details) {
                    for (int index = 0; index < detail.lines().size(); index++) {
                        if (index > 0) body.append('\n');
                        body.append(renderDetailLine(detail.lines().get(index), detail.diff()));
                    }
                }
                formatted = body.toString();
            }
            rendered.add(new ChatDisplayBlock.Section(section.id(), section.kind(), section.title(), section.content(), formatted));
        }
        return List.copyOf(rendered);
    }

    private String renderDetailLine(DetailLine line, boolean diff) {
        String visible = ansiEnabled && line.hint() != null ? highlighter.highlight(line.text(), line.hint()) : line.text();
        return diff ? colorDiffLine(visible) : visible;
    }

    /** Plain fallback sections (including explicit diff sections not produced by a tool). */
    public String renderDisplaySection(ChatDisplayBlock.Section section) {
        String formatted = section.formattedContent();
        if (!ansiEnabled) return printableToolOutput(formatted);
        if (section.kind().equals("diff") && formatted.equals(section.content())) {
            return renderDiff(formatted);
        }
        return formatted;
    }

    /** Full diff text using the same line colors as inline edit details, without preview limits. */
    public String renderDiff(String diff) {
        return java.util.Arrays.stream(printableToolOutput(diff).split("\\R", -1))
                .map(this::colorDiffLine).collect(java.util.stream.Collectors.joining("\n"));
    }

    private static void addDisplaySection(List<ChatDisplayBlock.Section> sections,
                                          String id, String kind, String title,
                                          String content, int maxChars) {
        if (content == null || content.isEmpty()) return;
        sections.add(new ChatDisplayBlock.Section(id, kind, title,
                ChatDisplayBlock.boundedPlainText(content, maxChars)));
    }

    /**
     * Best-effort language hint from a tool-call input JSON body:
     * looks for file_path / path / filename / file keys and maps the value
     * through {@link SyntaxHighlighter#familyForFilename}. Returns null when
     * nothing recognizable is present (no styling).
     */
    private static String languageFromRawInput(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) return null;
        try {
            JsonNode input = JSON.readTree(rawInput.trim());
            for (String key : new String[]{"file_path", "path", "filename", "file"}) {
                String value = textValue(input, key);
                if (!value.isBlank()) {
                    return value;
                }
            }
        } catch (Exception ignored) {
            // non-JSON input (e.g. shell command) — no language hint
        }
        return null;
    }

    /**
     * Extract the file path from a read_batch section header such as
     * {@code == src/App.java (lines 1-20 of 80)}. A new unrecognized or error
     * section deliberately returns null so its body cannot inherit the prior
     * file's highlighting.
     */
    private static String languageFromBatchSectionHeader(String header) {
        if (header == null) return null;
        String line = header.strip();
        if (!line.startsWith("== ")) return null;
        if (line.contains(" — ERROR:")) return null;
        int suffix = line.lastIndexOf(" (");
        if (suffix <= 3) return null;
        String candidate = line.substring(3, suffix).strip();
        return SyntaxHighlighter.familyForFilename(candidate) == SyntaxHighlighter.Family.NONE
                ? null : candidate;
    }

    /**
     * Render live tool/process output as a nested transcript body. Cursor-control
     * escapes are stripped because the managed TUI owns cursor positioning.
     */
    public String renderToolOutput(String output) {
        String normalized = printableToolOutput(output)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        StringBuilder rendered = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) rendered.append('\n');
            rendered.append("  ").append(cyan("│")).append(" ").append(lines[i]);
        }
        return rendered.toString();
    }

    /** Retain only SGR styling in presentation snapshots, never tool/model cursor controls. */
    public static String safeStyledDisplayText(String text) {
        if (text == null || text.isEmpty()) return "";
        var styles = SAFE_DISPLAY_STYLE.matcher(text);
        StringBuilder safe = new StringBuilder();
        int previous = 0;
        while (styles.find()) {
            safe.append(printableToolOutput(text.substring(previous, styles.start())));
            safe.append(styles.group());
            previous = styles.end();
        }
        safe.append(printableToolOutput(text.substring(previous)));
        return safe.toString();
    }

    private static final java.util.regex.Pattern SAFE_DISPLAY_STYLE =
            java.util.regex.Pattern.compile("\033\\[[0-9;:]*m");

    /** Tool output without ANSI escapes or control characters other than line breaks and tabs. */
    public static String printableToolOutput(String output) {
        String stripped = AnsiConstants.stripAnsi(output == null ? "" : output);
        StringBuilder safe = new StringBuilder(stripped.length());
        stripped.codePoints().forEach(codePoint -> {
            if (codePoint == '\n' || codePoint == '\r' || codePoint == '\t'
                    || (codePoint >= 0x20 && codePoint != 0x7f
                    && !(codePoint >= 0x80 && codePoint <= 0x9f))) {
                safe.appendCodePoint(codePoint);
            }
        });
        return safe.toString();
    }

    private static List<String> renderWriteContentLines(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) return List.of();
        try {
            JsonNode input = JSON.readTree(rawInput.trim());
            String content = textValue(input, "content");
            if (content.isBlank()) return List.of();
            return List.of(content.stripTrailing().split("\\R", -1));
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static void addBoundedSection(List<DetailSection> sections, String label,
                                          List<String> lines, boolean diff,
                                          String languageHint, boolean perLineHints,
                                          boolean batchSectionHints) {
        addBoundedSection(sections, label, lines, diff, languageHint, perLineHints, batchSectionHints,
                MAX_INLINE_TOOL_DETAIL_CHARS, MAX_INLINE_TOOL_DETAIL_LINES);
    }

    private static void addBoundedSection(List<DetailSection> sections, String label,
                                          List<String> lines, boolean diff, String languageHint,
                                          boolean perLineHints, boolean batchSectionHints,
                                          int maxChars, int maxLines) {
        if (lines == null || lines.isEmpty()) return;
        List<DetailLine> shown = new ArrayList<>(Math.min(lines.size(), maxLines));
        int shownChars = 0;
        boolean truncated = false;
        boolean inferPerLine = !diff && perLineHints && languageHint == null;
        boolean inferBatchSections = inferPerLine && batchSectionHints;
        String sectionLanguageHint = null;
        boolean insideBatchSection = false;
        for (String line : lines) {
            if (shownChars >= maxChars
                    || shown.size() >= maxLines) {
                truncated = true;
                break;
            }
            // Printable before the cut: the bounds count what is shown, a tool's own escape
            // sequence is never sliced or styled into, and every client shows the same text.
            String raw = printableToolOutput(line);
            String plainLine = raw.strip();
            boolean batchSectionHeader = inferBatchSections && plainLine.startsWith("== ");
            if (batchSectionHeader) {
                insideBatchSection = true;
                sectionLanguageHint = languageFromBatchSectionHeader(plainLine);
            }
            int remaining = maxChars - shownChars;
            String visible = raw;
            if (visible.length() > remaining) {
                visible = visible.substring(0, remaining);
                truncated = true;
            }
            // read_batch bodies inherit their enclosing file header; other
            // path-bearing outputs infer a recognized family per line.
            String hint = languageHint;
            if (inferPerLine && !batchSectionHeader) {
                hint = inferBatchSections && insideBatchSection
                        ? sectionLanguageHint
                        : SyntaxHighlighter.filenameFromToolResultLine(raw);
            }
            shown.add(new DetailLine(visible, hint));
            shownChars += raw.length();
        }
        sections.add(new DetailSection(label, diff, truncated, inferPerLine && !inferBatchSections,
                List.copyOf(shown)));
    }

    private static boolean isContentTool(String toolName) {
        return Set.of("read", "read_batch", "grep", "grep_batch", "glob", "list", "bash", "webfetch",
                "websearch", "code_search", "code_graph", "rag_search", "graph_search",
                "memory", "semantic_memory", "process", "exec").contains(toolName);
    }

    /**
     * Render a tool call that was denied (permission).
     */
    public String renderToolCallDenied(String toolName, String reason) {
        String cleanName = stripMcpPrefix(toolName);
        String displayName = prettifyToolName(toolName);
        String icon = TOOL_ICONS.getOrDefault(cleanName, "▸");
        return "  " + icon + " " + bold(yellow(displayName)) + " " + yellow("⊘ denied") +
                (reason != null ? " " + dim(reason) : "");
    }

    /** Compact fallback for surfaces without interactive result cards. */
    public String renderReminderSection(String reminderContent) {
        String summary = reminderSummary(reminderLines(reminderContent));
        return summary.isEmpty() ? "" : summary + " " + dim("— /reminder list · /reminder-global list");
    }

    /** Retain the exact applied reminders for expansion without flooding the transcript. */
    public ChatDisplayBlock renderReminderBlock(String reminderContent) {
        List<String> lines = reminderLines(reminderContent);
        String summary = reminderSummary(lines);
        if (summary.isEmpty()) return null;
        return new ChatDisplayBlock("reminders:" + java.util.UUID.randomUUID(), "reminders", "",
                summary, summary + " " + dim("— /ui results"),
                List.of(new ChatDisplayBlock.Section("reminders", "reminders", "Applied reminders",
                        String.join("\n", lines))), false, false);
    }

    private String reminderSummary(List<String> lines) {
        if (lines.isEmpty()) return "";
        long count = lines.stream().filter(line -> line.matches("[0-9]+\\. .*" )).count();
        return "  " + bold(yellow("⚑ REMINDERS APPLIED TO THIS PROMPT")) + " " + dim("(" + count + ")");
    }

    private static List<String> reminderLines(String reminderContent) {
        if (reminderContent == null || reminderContent.isBlank()) return List.of();
        return printableToolOutput(reminderContent).lines().map(String::strip)
                .filter(line -> !line.isEmpty()
                        && !line.startsWith("The user configured these reminders")
                        && !line.startsWith("Kompile added an automatic session reminder"))
                .toList();
    }

    // ========================================================================
    // Subagent rendering
    // ========================================================================

    /**
     * Render subagent start.
     */
    public String renderSubagentStart(String agentType, String description) {
        return "\n  " + magenta("┌─") + " " + bold(magenta("Subagent: " + agentType)) +
                (description != null ? " " + dim("— " + description) : "") +
                "\n  " + magenta("│");
    }

    /**
     * Render a mid-run subagent status line (compaction, recovery, retries) inside
     * the subagent transcript block.
     */
    public String renderSubagentStatus(String agentType, String message) {
        return "  " + magenta("│") + "  " + dim("⟳ " + agentType + ": " + message);
    }

    /**
     * Render a subagent tool call (indented under the subagent block).
     */
    public String renderSubagentToolCall(String toolName, boolean isError) {
        String cleanName = stripMcpPrefix(toolName);
        String displayName = prettifyToolName(toolName);
        String icon = TOOL_ICONS.getOrDefault(cleanName, "▸");
        String status = isError ? red("✗") : green("✓");
        return "  " + magenta("│") + "  " + icon + " " + cyan(displayName) + " " + status;
    }

    /**
     * Render a complete tool row and its bounded result inside a subagent transcript.
     *
     * The regular tool row intentionally stays compact for the main activity stream.
     * A subagent transcript is the place where the operator needs to see what the
     * nested call actually returned, so keep the summary and add the result below it.
     */
    public String renderSubagentToolCall(String toolName, String rawInput, ToolResult result) {
        String cleanName = stripMcpPrefix(toolName);
        String rendered = renderToolCallComplete(toolName, rawInput, result, false).stripLeading();
        StringBuilder detailed = new StringBuilder("  ").append(magenta("│")).append("  ")
                .append(rendered.replace("\n", "\n  │  "));

        List<TodoWriteTool.TodoItem> todos = todoItems(toolName, rawInput, result);
        if (todos != null) {
            return detailed.append('\n').append(renderTodoList(todos)
                    .replace("\n", "\n  │  ")).toString();
        }
        List<String> changeLines = renderEditDiffLines(toolName, rawInput);
        if (!changeLines.isEmpty()) {
            detailed.append("\n  ").append(magenta("│")).append("  ").append(dim("↳ changes:"));
            int shownChars = 0;
            boolean truncated = false;
            for (String changeLine : changeLines) {
                if (shownChars >= MAX_SUBAGENT_EDIT_DIFF_CHARS) {
                    truncated = true;
                    break;
                }
                int remaining = MAX_SUBAGENT_EDIT_DIFF_CHARS - shownChars;
                String visibleLine = changeLine;
                if (visibleLine.length() > remaining) {
                    visibleLine = visibleLine.substring(0, remaining);
                    truncated = true;
                }
                detailed.append("\n  ").append(magenta("│")).append("    ")
                        .append(colorDiffLine(visibleLine));
                shownChars += visibleLine.length();
                if (truncated) break;
            }
            if (truncated) {
                detailed.append("\n  ").append(magenta("│")).append("    ")
                        .append(dim("… (change preview truncated at "
                                + MAX_SUBAGENT_EDIT_DIFF_CHARS + " chars)"));
            }
        }

        String output = result.getOutput();
        if (output == null || output.isBlank()) {
            return detailed.toString();
        }

        String visibleOutput = output.stripTrailing();
        boolean truncated = visibleOutput.length() > MAX_SUBAGENT_TOOL_OUTPUT_CHARS;
        if (truncated) {
            visibleOutput = visibleOutput.substring(0, MAX_SUBAGENT_TOOL_OUTPUT_CHARS);
        }

        detailed.append("\n  ").append(magenta("│")).append("  ").append(dim("↳ output:"));
        // Style the output body exactly like the main transcript path does:
        // file-naming tools get their input-derived language; grep-style tools
        // infer the filename per line (with read_batch section inheritance).
        // Before this, subagent tool output printed raw and unstyled — the
        // 2026-08-29 grep_batch fix only landed in the main path.
        String languageHint = isContentTool(cleanName) ? languageFromRawInput(rawInput) : null;
        boolean hintUsable = languageHint != null
                && SyntaxHighlighter.familyForFilename(languageHint)
                        != SyntaxHighlighter.Family.NONE;
        boolean perLineHints = !hintUsable && isContentTool(cleanName);
        boolean batchSectionHints = "read_batch".equals(cleanName);
        String sectionHint = null;
        boolean insideBatchSection = false;
        for (String line : visibleOutput.split("\\R", -1)) {
            String renderedLine = line;
            if (ansiEnabled) {
                String hint = languageHint;
                if (perLineHints) {
                    String plain = AsciiRenderer.stripAnsi(line == null ? "" : line).strip();
                    if (batchSectionHints && plain.startsWith("== ")) {
                        insideBatchSection = true;
                        sectionHint = languageFromBatchSectionHeader(plain);
                        // Section headers themselves stay unstyled.
                    } else if (batchSectionHints && insideBatchSection) {
                        hint = sectionHint;
                    } else {
                        hint = SyntaxHighlighter.filenameFromToolResultLine(line);
                    }
                }
                if (hint != null) {
                    renderedLine = highlighter.highlight(renderedLine, hint);
                }
            }
            detailed.append("\n  ").append(magenta("│")).append("    ").append(renderedLine);
        }
        if (truncated) {
            detailed.append("\n  ").append(magenta("│")).append("    ")
                    .append(dim("… (tool output truncated at " + MAX_SUBAGENT_TOOL_OUTPUT_CHARS + " chars)"));
        }
        return detailed.toString();
    }

    /** Extract edit/patch input into diff-like lines for the detailed transcript. */
    private static List<String> renderEditDiffLines(String toolName, String rawInput) {
        String cleanName = stripMcpPrefix(toolName);
        if (!Set.of("edit", "edit_batch", "edit_patch", "patch").contains(cleanName)
                || rawInput == null || rawInput.isBlank()) {
            return List.of();
        }

        String trimmed = rawInput.trim();
        try {
            if (!trimmed.startsWith("{")) {
                return "patch".equals(cleanName) ? patchLines(trimmed) : List.of();
            }
            JsonNode input = JSON.readTree(trimmed);
            List<String> lines = new ArrayList<>();
            switch (cleanName) {
                case "edit" -> appendReplacementDiff(lines, input);
                case "edit_batch" -> {
                    JsonNode edits = input.path("edits");
                    if (edits.isArray()) {
                        for (JsonNode edit : edits) appendReplacementDiff(lines, edit);
                    }
                }
                case "edit_patch" -> {
                    JsonNode patches = input.path("patches");
                    if (patches.isArray()) {
                        for (JsonNode patch : patches) {
                            String path = textValue(patch, "file_path");
                            if (!path.isBlank()) lines.add("  " + path);
                            appendPatchLines(lines, textValue(patch, "patch"));
                        }
                    }
                }
                case "patch" -> {
                    String path = firstTextValue(input, "file_path", "path", "file");
                    if (!path.isBlank()) lines.add("  " + path);
                    appendPatchLines(lines, firstTextValue(input, "patch", "diff", "unified_diff"));
                }
                default -> { }
            }
            return lines;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static void appendReplacementDiff(List<String> lines, JsonNode edit) {
        String path = textValue(edit, "file_path");
        if (!path.isBlank()) lines.add("  " + path);
        appendPrefixedLines(lines, "- ", textValue(edit, "old_string"));
        appendPrefixedLines(lines, "+ ", textValue(edit, "new_string"));
    }

    private static void appendPatchLines(List<String> lines, String patch) {
        if (patch == null || patch.isBlank()) return;
        lines.addAll(patchLines(patch));
    }

    private static List<String> patchLines(String patch) {
        return List.of(patch.split("\\R", -1));
    }

    private static void appendPrefixedLines(List<String> lines, String prefix, String text) {
        if (text == null || text.isBlank()) return;
        for (String line : text.stripTrailing().split("\\R", -1)) {
            lines.add(prefix + line);
        }
    }

    private static String textValue(JsonNode node, String key) {
        if (node == null || !node.has(key) || node.get(key).isNull()) return "";
        return node.get(key).asText("");
    }

    private static String firstTextValue(JsonNode node, String... keys) {
        for (String key : keys) {
            String value = textValue(node, key);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private String colorDiffLine(String line) {
        if (line.startsWith("+++") || line.startsWith("---") || line.startsWith("@@")
                || line.startsWith("***")) {
            return cyan(line);
        }
        if (line.startsWith("+")) return green(line);
        if (line.startsWith("-")) return red(line);
        return dim(line);
    }

    /**
     * Render subagent completion.
     */
    public String renderSubagentComplete(String agentType, long durationMs) {
        String timing = durationMs > 0 ? " " + dim("(" + durationMs + "ms)") : "";
        return "  " + magenta("│") + "\n  " + magenta("└─") + " " +
                bold(green("Subagent complete")) + timing;
    }

    /**
     * Render subagent error.
     */
    public String renderSubagentError(String agentType, String error) {
        return "  " + magenta("│") + "\n  " + magenta("└─") + " " +
                bold(red("Subagent failed: ")) + red(truncatePreview(error, 120));
    }

    // ========================================================================
    // Todo list rendering
    // ========================================================================

    public static boolean isTodoTool(String toolName) {
        String name = stripMcpPrefix(toolName).replace("_", "");
        return "todowrite".equals(name) || "todoread".equals(name);
    }

    /** Result snapshots take precedence over arguments (which may describe pre-update state). */
    private static List<TodoWriteTool.TodoItem> todoItems(String toolName, String rawInput, ToolResult result) {
        if (!isTodoTool(toolName) || result == null || result.isError()) return null;
        JsonNode tasks = null;
        Object snapshot = result.getMetadata().get(TodoWriteTool.TASKS_METADATA);
        if (snapshot != null) tasks = JSON.valueToTree(snapshot);
        if (tasks == null) {
            // Native provider tools can return an array or {todos:[...]}, without MCP metadata.
            try {
                JsonNode output = JSON.readTree(result.getOutput());
                if (output != null) tasks = output.isArray() ? output : output.get("todos");
            } catch (Exception ignored) { }
        }
        if (tasks == null && stripMcpPrefix(toolName).replace("_", "").equals("todowrite")) {
            try { tasks = JSON.readTree(rawInput).get("todos"); } catch (Exception ignored) { }
        }
        if (tasks != null && tasks.isArray()) {
            List<TodoWriteTool.TodoItem> items = new ArrayList<>();
            for (JsonNode task : tasks) {
                String subject = task.isTextual() ? task.asText()
                        : task.path("subject").asText(task.path("content").asText(""));
                if (subject.isBlank()) continue;
                String status = task.path("status").asText("pending");
                if (!Set.of("pending", "in_progress", "completed", "cancelled").contains(status)) status = "pending";
                items.add(new TodoWriteTool.TodoItem(task.path("id").asText(String.valueOf(items.size() + 1)),
                        subject, task.path("description").asText(task.path("task_description").asText("")),
                        status, task.path("priority").asText("medium")));
            }
            return items;
        }
        // Read results from older MCP servers used checkbox text, not structured snapshots.
        if (stripMcpPrefix(toolName).replace("_", "").equals("todoread")) {
            String output = printableToolOutput(result.getOutput());
            if (output.strip().startsWith("No tasks")) return List.of();
            List<TodoWriteTool.TodoItem> items = new ArrayList<>();
            var checkbox = java.util.regex.Pattern.compile("^\\[([x~ !])\\] #([^:]+): (.*)$");
            for (String line : output.split("\\R")) {
                var match = checkbox.matcher(line);
                if (match.matches()) {
                    String status = switch (match.group(1)) {
                        case "x" -> "completed";
                        case "~" -> "in_progress";
                        case "!" -> "cancelled";
                        default -> "pending";
                    };
                    items.add(new TodoWriteTool.TodoItem(match.group(2), match.group(3), "", status, "medium"));
                } else if (line.startsWith("    ") && !items.isEmpty()) {
                    TodoWriteTool.TodoItem item = items.get(items.size() - 1);
                    item.description += (item.description.isEmpty() ? "" : "\n") + line.strip();
                }
            }
            if (!items.isEmpty()) return items;
        }
        return null; // Unknown payloads and errors must remain visible, never masquerade as an empty list.
    }

    /**
     * Render the full todo list with progress indicator.
     */
    public String renderTodoList(List<TodoWriteTool.TodoItem> todos) {
        if (todos.isEmpty()) {
            return dim("  No tasks");
        }

        int total = todos.size();
        int completed = 0, inProgress = 0, pending = 0, cancelled = 0;
        for (TodoWriteTool.TodoItem item : todos) {
            switch (item.status) {
                case "completed": completed++; break;
                case "in_progress": inProgress++; break;
                case "cancelled": cancelled++; break;
                default: pending++; break;
            }
        }

        StringBuilder sb = new StringBuilder();

        // Progress header
        sb.append("  ").append(bold("Tasks"));
        sb.append(" ").append(dim("["));
        sb.append(green(String.valueOf(completed)));
        sb.append(dim("/"));
        sb.append(String.valueOf(total));
        sb.append(dim("]"));

        // Progress bar
        int barWidth = 20;
        int filled = total > 0 ? (completed * barWidth) / total : 0;
        sb.append(" ");
        sb.append(green("█".repeat(filled)));
        sb.append(dim("░".repeat(barWidth - filled)));
        sb.append("\n");

        // Individual items
        for (TodoWriteTool.TodoItem item : todos) {
            sb.append(renderTodoItem(item)).append("\n");
        }

        return sb.toString().stripTrailing();
    }

    /**
     * Render a single todo item with status icon and color.
     */
    public String renderTodoItem(TodoWriteTool.TodoItem item) {
        boolean current = "in_progress".equals(item.status);
        String icon;
        String color;
        boolean strikethrough = false;

        switch (item.status) {
            case "completed":
                icon = green("✓");
                color = "dim";
                strikethrough = true;
                break;
            case "in_progress":
                icon = bold(yellow("▶"));
                color = "yellow";
                break;
            case "cancelled":
                icon = red("✗");
                color = "dim";
                strikethrough = true;
                break;
            default: // pending
                icon = dim("○");
                color = "normal";
                break;
        }

        String subject = printableToolOutput(item.subject);
        if (strikethrough && ansiEnabled) {
            subject = ESC + "9m" + subject + RESET; // strikethrough
        }

        switch (color) {
            case "dim": subject = dim(subject); break;
            case "yellow": subject = bold(yellow(subject)); break;
        }

        StringBuilder sb = new StringBuilder();
        String id = printableToolOutput(item.id);
        String taskId = current ? bold(yellow("#" + id)) : dim("#" + id);
        sb.append("  ").append(icon).append(" ").append(taskId).append(" ").append(subject);
        if (current) {
            sb.append(" ").append(bold(yellow("[current]")));
        }

        // Show priority if not medium (default)
        if (item.priority != null && !"medium".equals(item.priority)) {
            String priorityLabel = "high".equals(item.priority) ? red("!high") : dim("low");
            sb.append(" ").append(priorityLabel);
        }

        if (item.description != null && !item.description.isEmpty()) {
            sb.append("\n    ").append(dim(truncatePreview(printableToolOutput(item.description), 80)));
        }

        return sb.toString();
    }

    /**
     * Render a todo update event (when a task status changes).
     */
    public String renderTodoUpdate(String taskId, String subject, String oldStatus, String newStatus) {
        if ("in_progress".equals(newStatus)) {
            return renderTodoItem(new TodoWriteTool.TodoItem(taskId, subject, null, newStatus, "medium")) +
                    " " + dim(oldStatus + " → " + newStatus);
        }
        String icon = switch (newStatus) {
            case "completed" -> green("✓");
            case "cancelled" -> red("✗");
            default -> dim("○");
        };
        return "  " + icon + " " + dim("#" + taskId) + " " + subject +
                " " + dim(oldStatus + " → " + newStatus);
    }

    // ========================================================================
    // Agent loop rendering
    // ========================================================================

    /**
     * Render context grouping for read-only tools.
     * Tool names in the map are prettified for display.
     */
    public String renderContextGroup(Map<String, Integer> toolCounts) {
        if (toolCounts == null || toolCounts.isEmpty()) {
            return "";
        }

        Map<String, Integer> aggregated = new LinkedHashMap<>();
        int totalCalls = 0;
        for (Map.Entry<String, Integer> entry : toolCounts.entrySet()) {
            int count = entry.getValue() != null ? entry.getValue() : 0;
            if (count <= 0) {
                continue;
            }
            String cleanName = stripMcpPrefix(entry.getKey());
            if (cleanName.isEmpty()) {
                cleanName = "unknown";
            }
            aggregated.merge(cleanName, count, Integer::sum);
            totalCalls += count;
        }

        if (aggregated.isEmpty()) {
            return "";
        }

        List<Map.Entry<String, Integer>> entries = new ArrayList<>(aggregated.entrySet());
        entries.sort(Comparator
                .<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                .reversed()
                .thenComparing(Map.Entry::getKey));

        StringBuilder sb = new StringBuilder();
        sb.append("  ").append(dim("Gathered context: "));
        sb.append(cyan(String.valueOf(totalCalls))).append(dim(totalCalls == 1 ? " call" : " calls"));
        sb.append(dim(" ("));
        boolean first = true;
        int shownCalls = 0;
        int shownBuckets = Math.min(entries.size(), MAX_CONTEXT_TOOL_BUCKETS);
        for (int i = 0; i < shownBuckets; i++) {
            Map.Entry<String, Integer> entry = entries.get(i);
            if (!first) sb.append(dim(", "));
            String displayName = prettifyToolName(entry.getKey());
            sb.append(cyan(String.valueOf(entry.getValue())))
                    .append(dim(" " + displayName));
            if (entry.getValue() > 1) sb.append(dim("s"));
            first = false;
            shownCalls += entry.getValue();
        }

        int remainingCalls = totalCalls - shownCalls;
        int remainingBuckets = entries.size() - shownBuckets;
        if (remainingCalls > 0) {
            if (!first) sb.append(dim(", "));
            sb.append(dim("+")).append(cyan(String.valueOf(remainingCalls))).append(dim(" more"));
            if (remainingBuckets > 0) {
                sb.append(dim(" across ")).append(cyan(String.valueOf(remainingBuckets)))
                        .append(dim(remainingBuckets == 1 ? " tool" : " tools"));
            }
        }
        sb.append(dim(")"));
        return sb.toString();
    }

    /**
     * Render compaction notice.
     */
    public String renderCompactionNotice(int tokensBefore, int tokensAfter) {
        return "\n" + dim("  ─── context compacted · portable history estimate: ~" + tokensBefore
                + " → ~" + tokensAfter + " tokens; provider context not measured ───") + "\n";
    }

    /**
     * Render a compaction the provider ran on a session it owns. The trigger and
     * the size before are shown when reported; the size after is not known.
     */
    public String renderProviderCompactionNotice(String provider, String trigger, long tokensBefore) {
        StringBuilder text = new StringBuilder(" · ").append(provider);
        if (trigger != null && !trigger.isBlank()) text.append(" (").append(trigger.strip()).append(')');
        if (tokensBefore > 0) text.append(" · provider context before: ")
                .append(CompactionProgressIndicator.formatTokens(tokensBefore)).append(" tokens");
        text.append(" · provider context after not reported");
        return "  " + green("✓ Context compacted") + dim(text.toString());
    }

    // ========================================================================
    // Spinner for long-running tools
    // ========================================================================

    /**
     * Start a spinner thread that updates in-place. Returns a handle to stop it.
     */
    public SpinnerHandle startSpinner(String toolName) {
        String displayName = prettifyToolName(toolName);
        if (!ansiEnabled) {
            System.out.print("  " + displayName + "...");
            System.out.flush();
            return new SpinnerHandle(null);
        }

        AtomicBoolean running = new AtomicBoolean(true);
        Thread spinnerThread = new Thread(() -> {
            int frame = 0;
            while (running.get()) {
                String spinner = SPINNER_FRAMES[frame % SPINNER_FRAMES.length];
                // Move cursor to start of line, clear line, print spinner
                System.out.print("\r" + ESC + "2K  " + yellow(spinner) + " " + cyan(displayName) + " " + dim("running..."));
                System.out.flush();
                frame++;
                try {
                    Thread.sleep(80);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "spinner-" + toolName);
        spinnerThread.setDaemon(true);
        spinnerThread.start();

        return new SpinnerHandle(running);
    }

    /**
     * Handle to stop a running spinner.
     */
    public static class SpinnerHandle {
        private final AtomicBoolean running;
        /** Volatile phase override — spinner thread reads this each frame. */
        volatile String phaseOverride;
        /** Reference to the spinner thread so stop() can join it. */
        public volatile Thread spinnerThread;
        /** Optional: row to pin the spinner to (absolute positioning). -1 = use \r. */
        volatile int pinnedRow = -1;

        public SpinnerHandle(AtomicBoolean running) {
            this.running = running;
        }

        public void stop() {
            if (running != null) {
                running.set(false);
                // Wait for the spinner thread to exit so it can't print after we clear
                Thread t = spinnerThread;
                if (t != null) {
                    try { t.join(200); } catch (InterruptedException ignored) {}
                }
                // Clear the spinner line using absolute positioning if pinned
                if (pinnedRow > 0) {
                    System.out.printf("\033[%d;1H\033[2K", pinnedRow);
                } else {
                    System.out.print("\r" + ESC + "2K");
                }
                System.out.flush();
            }
        }

        /**
         * Change the spinner phase text (e.g. from "Generating" to "Thinking").
         * The spinner thread picks this up on the next frame.
         */
        public void setPhase(String phase) {
            this.phaseOverride = phase;
        }
    }

    /**
     * Returns a no-op SpinnerHandle that does nothing on stop/setPhase.
     * Used when the spinner would conflict with an active readline.
     */
    public SpinnerHandle noOpSpinner() {
        return new SpinnerHandle(null);
    }

    // ========================================================================
    // Generating spinner (animated waiting indicator during LLM response)
    // ========================================================================

    /**
     * Start an animated "Generating..." spinner with terminal title update.
     * Shows a braille spinner animation on the current line and sets the
     * terminal tab/title to indicate processing is in progress.
     *
     * @param chainInfo optional queue chain info (e.g., " [2/5]")
     * @return a SpinnerHandle to stop the spinner when the response arrives
     */
    public SpinnerHandle startStaticSpinner(String chainInfo) {
        if (chainInfo == null) chainInfo = "";
        final String chain = chainInfo;
        setActivity(ChatActivityPhase.THINKING, titleChainDetail(chain));

        if (ansiEnabled) {
            System.out.print("\r" + ESC + "2K"
                    + "  " + DIM + "Kompiling..." + RESET + chain
                    + DIM + "  (Esc to cancel)" + RESET);
            System.out.flush();
        } else {
            System.out.println("  Kompiling..." + chain + "  (Esc to cancel)");
            System.out.flush();
        }

        return new SpinnerHandle(null) {
            @Override
            public void stop() {
                if (ansiEnabled) {
                    System.out.print("\r" + ESC + "2K");
                    System.out.flush();
                }
                resetTerminalTitle();
            }

            @Override
            public void setPhase(String phase) {
                super.setPhase(phase);
                setActivity(ChatActivityPhase.fromLabel(phase), titleChainDetail(chain));
                if (ansiEnabled && phase != null) {
                    System.out.print("\r" + ESC + "2K"
                            + "  " + DIM + phase + "..." + RESET + chain
                            + DIM + "  (Esc to cancel)" + RESET);
                    System.out.flush();
                }
            }
        };
    }

    /**
     * Start an animated "Generating..." spinner with terminal title update.
     * Shows a braille spinner animation on the current line and sets the
     * terminal tab/title to indicate processing is in progress.
     *
     * @param chainInfo optional queue chain info (e.g., " [2/5]")
     * @return a SpinnerHandle to stop the spinner when the response arrives
     */
    public SpinnerHandle startGeneratingSpinner(String chainInfo) {
        if (chainInfo == null) chainInfo = "";
        final String chain = chainInfo;

        setActivity(ChatActivityPhase.THINKING, titleChainDetail(chain));

        if (!ansiEnabled) {
            System.out.println("  Kompiling..." + chain + "  (Esc to cancel)");
            System.out.flush();
            return new SpinnerHandle(null) {
                @Override
                public void stop() {
                    resetTerminalTitle();
                }
            };
        }

        AtomicBoolean running = new AtomicBoolean(true);
        SpinnerHandle handle = new SpinnerHandle(running) {
            @Override
            public void stop() {
                super.stop();
                resetTerminalTitle();
            }
        };
        Thread spinnerThread = new Thread(() -> {
            int frame = 0;
            String[][] phasesets = {
                {"Kompiling", "Kompiling.", "Kompiling..", "Kompiling..."},
                {"Thinking", "Thinking.", "Thinking..", "Thinking..."},
            };
            String[] currentPhases = phasesets[0];
            while (running.get()) {
                // Check for phase override from the handle
                String override = handle.phaseOverride;
                if (override != null) {
                    if ("Thinking".equals(override)) {
                        currentPhases = phasesets[1];
                    } else {
                        currentPhases = phasesets[0];
                    }
                }
                String spinner = SPINNER_FRAMES[frame % SPINNER_FRAMES.length];
                String phase = currentPhases[(frame / 3) % currentPhases.length];
                System.out.print("\r" + ESC + "2K"
                        + "  " + yellow(spinner) + " " + DIM + phase + RESET
                        + chain
                        + DIM + "  (Esc to cancel)" + RESET);
                System.out.flush();
                frame++;
                try {
                    Thread.sleep(80);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "kompiling-spinner");
        spinnerThread.setDaemon(true);
        handle.spinnerThread = spinnerThread;
        spinnerThread.start();

        return handle;
    }

    /**
     * Start a spinner pinned to a specific terminal row using absolute positioning.
     * Unlike {@link #startGeneratingSpinner}, this does NOT use \r (carriage return)
     * which depends on the cursor being on the correct row. Instead, every frame
     * explicitly moves to the given row. Use this when a scroll region is active
     * and the cursor position may be unpredictable.
     *
     * @param row       1-indexed terminal row to pin the spinner to
     * @param chainInfo optional label (e.g., " (claude)")
     * @return handle to stop the spinner
     */
    public SpinnerHandle startPinnedSpinner(int row, String chainInfo) {
        if (chainInfo == null) chainInfo = "";
        final String chain = chainInfo;

        setActivity(ChatActivityPhase.THINKING, titleChainDetail(chain));

        if (!ansiEnabled) {
            System.out.println("  Kompiling..." + chain);
            System.out.flush();
            return new SpinnerHandle(null) {
                @Override
                public void stop() {
                    resetTerminalTitle();
                }
            };
        }

        AtomicBoolean running = new AtomicBoolean(true);
        SpinnerHandle handle = new SpinnerHandle(running) {
            @Override
            public void stop() {
                super.stop();
                resetTerminalTitle();
            }
        };
        handle.pinnedRow = row;

        Thread spinnerThread = new Thread(() -> {
            int frame = 0;
            String[][] phasesets = {
                {"Kompiling", "Kompiling.", "Kompiling..", "Kompiling..."},
                {"Thinking", "Thinking.", "Thinking..", "Thinking..."},
            };
            String[] currentPhases = phasesets[0];
            while (running.get()) {
                String override = handle.phaseOverride;
                if (override != null) {
                    if ("Thinking".equals(override)) {
                        currentPhases = phasesets[1];
                    } else {
                        currentPhases = phasesets[0];
                    }
                }
                String spinner = SPINNER_FRAMES[frame % SPINNER_FRAMES.length];
                String phase = currentPhases[(frame / 3) % currentPhases.length];
                // Absolute positioning — immune to cursor drift
                System.out.printf("\033[%d;1H\033[2K  %s %s%s%s%s%s  (Esc to cancel)%s",
                        row,
                        yellow(spinner), DIM, phase, RESET,
                        chain, DIM, RESET);
                System.out.flush();
                frame++;
                try {
                    Thread.sleep(80);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "kompiling-spinner");
        spinnerThread.setDaemon(true);
        handle.spinnerThread = spinnerThread;
        spinnerThread.start();

        return handle;
    }

    // ========================================================================
    // Terminal title management
    // ========================================================================

    /** Attach title output to the actual JLine terminal used by the chat session. */
    public void attachTerminal(Terminal terminal, String readyTitle) {
        if (titleController == null) {
            titleController = new TerminalTitleController();
        }
        titleController.attach(terminal, readyTitle);
    }

    /** Detach title output when the JLine terminal is closing. */
    public void detachTerminal() {
        TerminalTitleController controller = titleController;
        if (controller != null) controller.detach();
    }

    /** Update the tab title from the same activity label shown in the status bar. */
    public void updateActivity(String activity) {
        TerminalTitleController controller = titleController;
        if (controller != null) controller.updateLabel(activity);
    }

    /** Reflect background process activity in the tab title while the REPL is idle. */
    public void updateProcessActivity(int processCount) {
        TerminalTitleController controller = titleController;
        if (controller != null) controller.updateProcessActivity(processCount);
    }

    /** Update the tab title from a typed foreground phase. */
    public void setActivity(ChatActivityPhase phase, String detail) {
        TerminalTitleController controller = titleController;
        if (controller != null) controller.update(phase, detail);
    }

    /**
     * Set the terminal tab/window title using OSC escape sequence.
     * Works on most modern terminals (xterm, iTerm2, GNOME Terminal, Windows Terminal, etc.).
     */
    public void setTerminalTitle(String title) {
        TerminalTitleController controller = titleController;
        if (controller != null) {
            controller.setRawTitle(title);
            return;
        }
        if (!ansiEnabled) return;
        // Keep the pre-attach behavior for callers that use the renderer without JLine.
        System.out.print("\033]0;" + title + "\007");
        System.out.flush();
    }

    /** Update the stable title restored after foreground work completes. */
    public void setReadyTerminalTitle(String title) {
        TerminalTitleController controller = titleController;
        if (controller != null) {
            controller.setReadyTitle(title);
        } else {
            setTerminalTitle(title);
        }
    }

    /** Reset the terminal title to the attached session's ready title. */
    public void resetTerminalTitle() {
        TerminalTitleController controller = titleController;
        if (controller != null) {
            controller.reset();
        } else {
            setTerminalTitle("kompile chat");
        }
    }

    private static String titleChainDetail(String chain) {
        if (chain == null || chain.isBlank()) return "";
        return AnsiConstants.stripAnsi(chain).trim();
    }

    // ========================================================================
    // Tool name & input prettification
    // ========================================================================

    /**
     * Strip MCP server prefixes from a tool name, returning the lowercase short name.
     * E.g., {@code "mcp__kompile__read"} → {@code "read"}, {@code "Read"} → {@code "read"}.
     */
    public static String stripMcpPrefix(String rawName) {
        if (rawName == null || rawName.isEmpty()) return "";
        String name = rawName;
        // mcp__<server>__<tool> → <tool>
        if (name.startsWith("mcp__")) {
            int lastSep = name.lastIndexOf("__");
            if (lastSep > 4) { // "mcp__" is 5 chars
                name = name.substring(lastSep + 2);
            }
        }
        return name.toLowerCase();
    }

    /**
     * Clean up a raw tool name for display. Strips MCP prefixes, capitalizes,
     * and converts underscores to title case.
     * <p>
     * Examples:
     * <ul>
     *   <li>{@code "mcp__kompile__read"} → {@code "Read"}</li>
     *   <li>{@code "mcp__kompile__code_search"} → {@code "Code Search"}</li>
     *   <li>{@code "ToolSearch"} → {@code "ToolSearch"} (unchanged)</li>
     *   <li>{@code "exec"} → {@code "Exec"}</li>
     * </ul>
     */
    public static String prettifyToolName(String rawName) {
        if (rawName == null || rawName.isEmpty()) return "unknown";

        String name = rawName;
        // Strip MCP prefix
        if (name.startsWith("mcp__")) {
            int lastSep = name.lastIndexOf("__");
            if (lastSep > 4) {
                name = name.substring(lastSep + 2);
            }
        }

        // If already CamelCase (contains uppercase after position 0), keep as-is
        if (name.length() > 1) {
            boolean hasMidUppercase = false;
            for (int i = 1; i < name.length(); i++) {
                if (Character.isUpperCase(name.charAt(i))) {
                    hasMidUppercase = true;
                    break;
                }
            }
            if (hasMidUppercase) {
                // Capitalize first letter and return (e.g., "toolSearch" → "ToolSearch")
                return Character.toUpperCase(name.charAt(0)) + name.substring(1);
            }
        }

        // Convert underscore_case to Title Case
        if (name.contains("_")) {
            String[] parts = name.split("_");
            StringBuilder sb = new StringBuilder();
            for (String part : parts) {
                if (part.isEmpty()) continue;
                if (sb.length() > 0) sb.append(" ");
                sb.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) sb.append(part.substring(1));
            }
            return sb.toString();
        }

        // Simple lowercase name → capitalize
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /**
     * Parse tool input (often raw JSON) into a human-readable one-line summary.
     * <p>
     * For known tools, extracts the primary parameter value. For example,
     * a {@code read} tool with input {@code {"file_path":"src/app/foo.ts","limit":100}}
     * returns {@code "src/app/foo.ts"}.
     * <p>
     * For unknown tools or non-JSON input, returns a truncated plain-text version.
     *
     * @param toolName  lowercase short tool name (no MCP prefix)
     * @param rawInput  the raw input string (JSON or plain text)
     * @param maxLen    maximum length of the returned string
     */
    public static String prettifyToolInput(String toolName, String rawInput, int maxLen) {
        if (rawInput == null || rawInput.isBlank()) return "";

        String trimmed = rawInput.trim();

        // If not JSON, return truncated plain text
        if (!trimmed.startsWith("{")) {
            return truncatePreview(trimmed, maxLen);
        }

        try {
            JsonNode node = JSON.readTree(trimmed);
            if (!node.isObject()) {
                return truncatePreview(trimmed, maxLen);
            }

            // Look for primary params by tool name
            String[] primaryKeys = PRIMARY_PARAMS.get(toolName);
            if (primaryKeys != null) {
                StringBuilder sb = new StringBuilder();
                for (String key : primaryKeys) {
                    if (node.has(key) && !node.get(key).isNull()) {
                        JsonNode val = node.get(key);
                        String valStr = summarizeParameter(key, val);
                        if (!valStr.isEmpty()) {
                            if (sb.length() > 0) {
                                sb.append("grep".equals(toolName) && "path".equals(key)
                                        ? " in " : " · ");
                            }
                            sb.append(valStr);
                        }
                    }
                }
                if (sb.length() > 0) {
                    return truncatePreview(sb.toString(), maxLen);
                }
            }

            // Fallback: show fields as "key=value" pairs, skipping noise
            StringBuilder sb = new StringBuilder();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (sb.length() > 0) sb.append(", ");
                String val = summarizeParameter(entry.getKey(), entry.getValue());
                sb.append(entry.getKey()).append("=").append(val);
                if (sb.length() > maxLen) break;
            }
            return truncatePreview(sb.toString(), maxLen);

        } catch (Exception e) {
            // JSON parse failed — return truncated raw text
            return truncatePreview(trimmed, maxLen);
        }
    }

    /** Plain one-line label used by the compact activity panel and status rows. */
    public static String summarizeToolCall(String toolName, String rawInput, int maxLen) {
        String displayName = prettifyToolName(toolName);
        String input = prettifyToolInput(stripMcpPrefix(toolName), rawInput,
                Math.max(1, maxLen - displayName.length() - 1));
        return truncatePreview(input.isBlank() ? displayName : displayName + " " + input, maxLen);
    }

    /** Plain completion detail used where ANSI rendering is inappropriate. */
    public static String summarizeToolResult(ToolResult result, int maxLen) {
        if (result == null) return "";
        StringBuilder summary = new StringBuilder();
        if (result.isError()) {
            summary.append("failed");
        } else if (result.getTitle() != null && !result.getTitle().isBlank()
                && !"error".equals(result.getTitle())) {
            summary.append(result.getTitle());
        }
        if (!result.getMetadata().isEmpty()) {
            for (Map.Entry<String, Object> entry : result.getMetadata().entrySet()) {
                if (isSensitiveParam(entry.getKey()) || "path".equals(entry.getKey())
                        || ToolResult.OUTPUT_STREAMED_METADATA.equals(entry.getKey())
                        || TodoWriteTool.TASKS_METADATA.equals(entry.getKey())) continue;
                if (summary.length() > 0) summary.append(" · ");
                summary.append(entry.getKey()).append("=").append(entry.getValue());
                if (summary.length() >= maxLen) break;
            }
        }
        if ((summary.length() == 0 || result.isError())
                && result.getOutput() != null && !result.getOutput().isBlank()) {
            if (summary.length() > 0) summary.append(" · ");
            summary.append(firstOutputLine(result.getOutput(), maxLen));
        }
        return truncatePreview(summary.toString(), maxLen);
    }

    private static String summarizeParameter(String key, JsonNode value) {
        if (isSensitiveParam(key)) {
            return "[redacted]";
        }
        if (value == null || value.isNull()) {
            return "";
        }
        if (VERBOSE_PARAMS.contains(key.toLowerCase())) {
            int length = value.isTextual() ? value.asText().length() : value.toString().length();
            return length + " chars";
        }
        if (value.isArray()) {
            if (value.size() == 1) {
                JsonNode only = value.get(0);
                if (only.isTextual()) return only.asText();
                if (only.isObject()) {
                    for (String candidate : List.of("file_path", "path", "query", "pattern", "description")) {
                        if (only.hasNonNull(candidate)) {
                            return only.path(candidate).asText();
                        }
                    }
                }
            }
            return key + "=" + value.size();
        }
        if (value.isObject()) {
            return key + "={" + value.size() + " fields}";
        }
        return value.isTextual() ? value.asText() : value.toString();
    }

    private static boolean isSensitiveParam(String key) {
        if (key == null) return false;
        String normalized = key.toLowerCase();
        if (SENSITIVE_PARAMS.contains(normalized)) return true;
        return normalized.endsWith("_token") || normalized.endsWith("_secret")
                || normalized.endsWith("_password") || normalized.endsWith("_credential");
    }

    // ========================================================================
    // Utility methods
    // ========================================================================

    private static boolean shouldShowPreview(String toolName, Map<String, Object> meta) {
        // Show preview for bash (command output), errors, and short results
        String lower = toolName != null ? toolName.toLowerCase() : "";
        return "bash".equals(lower) ||
                "grep".equals(lower) ||
                "glob".equals(lower);
    }

    private static String renderMetadata(Map<String, Object> meta) {
        StringBuilder sb = new StringBuilder("(");
        boolean first = true;
        for (Map.Entry<String, Object> entry : meta.entrySet()) {
            String key = entry.getKey();
            // Skip verbose metadata
            if ("path".equals(key) || "created".equals(key) || "matchType".equals(key)
                    || ToolResult.OUTPUT_STREAMED_METADATA.equals(key)
                    || ToolResult.CHART_METADATA.equals(key)
                    || TodoWriteTool.TASKS_METADATA.equals(key)
                    || isSensitiveParam(key)) continue;
            if (!first) sb.append(", ");
            sb.append(key).append("=").append(entry.getValue());
            first = false;
        }
        sb.append(")");
        return first ? "" : sb.toString();
    }

    public static String truncatePreview(String text, int maxLen) {
        if (text == null) return "";
        if (maxLen <= 0) return "";
        // Replace newlines with spaces for inline preview
        String oneLine = text.replace('\n', ' ').replace('\r', ' ');
        if (oneLine.length() <= maxLen) return oneLine;
        if (maxLen <= 3) return oneLine.substring(0, maxLen);
        return oneLine.substring(0, maxLen - 3) + "...";
    }

    private static String firstOutputLine(String output, int maxLen) {
        if (output == null || output.isBlank()) return "";
        String[] lines = output.split("\\R", -1);
        String first = "";
        int nonBlank = 0;
        for (String line : lines) {
            if (line.isBlank()) continue;
            if (first.isEmpty()) first = line.strip();
            nonBlank++;
        }
        if (nonBlank > 1) {
            first += " (+" + (nonBlank - 1) + " lines)";
        }
        return truncatePreview(first, maxLen);
    }

    private String indentLines(String text, String indent) {
        if (text == null) return "";
        String[] lines = text.split("\n");
        StringBuilder sb = new StringBuilder();
        int maxLines = 5; // Show at most 5 lines in preview
        for (int i = 0; i < Math.min(lines.length, maxLines); i++) {
            if (i > 0) sb.append("\n");
            sb.append(indent).append(lines[i]);
        }
        if (lines.length > maxLines) {
            sb.append("\n").append(indent).append("... (").append(lines.length - maxLines).append(" more lines)");
        }
        return sb.toString();
    }

    // ========================================================================
    // ANSI formatting helpers
    // ========================================================================

    public String bold(String text) {
        return ansiEnabled ? BOLD + text + RESET : text;
    }

    public String dim(String text) {
        return ansiEnabled ? DIM + text + RESET : text;
    }

    public String italic(String text) {
        return ansiEnabled ? ITALIC + text + RESET : text;
    }

    public String red(String text) {
        return ansiEnabled ? FG_RED + text + RESET : text;
    }

    public String green(String text) {
        return ansiEnabled ? FG_GREEN + text + RESET : text;
    }

    public String yellow(String text) {
        return ansiEnabled ? FG_YELLOW + text + RESET : text;
    }

    /**
     * Render a warning message in yellow.
     */
    public String warn(String text) {
        return ansiEnabled ? FG_YELLOW + BOLD + text + RESET : text;
    }

    public String blue(String text) {
        return ansiEnabled ? FG_BLUE + text + RESET : text;
    }

    public String magenta(String text) {
        return ansiEnabled ? FG_MAGENTA + text + RESET : text;
    }

    public String cyan(String text) {
        return ansiEnabled ? FG_CYAN + text + RESET : text;
    }

    public boolean isAnsiEnabled() {
        return ansiEnabled;
    }

    private static boolean detectAnsiSupport() {
        // Check if stdout is a terminal
        if (System.console() == null) {
            return false;
        }
        // Check common environment variables
        String term = System.getenv("TERM");
        if (term != null && !"dumb".equals(term)) {
            return true;
        }
        String colorTerm = System.getenv("COLORTERM");
        if (colorTerm != null) {
            return true;
        }
        // Check if forced
        String forceColor = System.getenv("FORCE_COLOR");
        if (forceColor != null && !"0".equals(forceColor)) {
            return true;
        }
        String noColor = System.getenv("NO_COLOR");
        if (noColor != null) {
            return false;
        }
        // Default: assume ANSI support on non-Windows
        return !System.getProperty("os.name", "").toLowerCase().startsWith("win");
    }
}
