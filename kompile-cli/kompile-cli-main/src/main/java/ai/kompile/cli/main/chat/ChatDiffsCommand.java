package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.metrics.ToolCallUsageJournal;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/** Recent edit calls from this session's journal; never queries or builds an index. */
final class ChatDiffsCommand {
    static final String USAGE = "Usage: /diffs [count] — recent session diffs, newest first (default 20; 1–100).";
    static final int MAX_TAIL_BYTES = 8 * 1024 * 1024;
    private static final Set<String> EDIT_TOOLS = Set.of(
            "edit", "write", "patch", "apply_patch", "apply_diff", "edit_batch", "edit_patch", "write_batch",
            "edit_file", "write_file", "write_to_file", "replace_in_file", "insert_code_block",
            "create_file", "str_replace_editor", "file_editor", "notebookedit");
    private final Path toolCallsDirectory;
    private final int maxTailBytes;
    private final TerminalRenderer renderer;

    ChatDiffsCommand(TerminalRenderer renderer) {
        this(KompileHome.homeDirectory().toPath().resolve("conversations/tool-calls"), MAX_TAIL_BYTES, renderer);
    }

    ChatDiffsCommand(Path toolCallsDirectory, int maxTailBytes, TerminalRenderer renderer) {
        this.toolCallsDirectory = toolCallsDirectory;
        if (maxTailBytes <= 0) throw new IllegalArgumentException("Tail size must be positive");
        this.maxTailBytes = maxTailBytes;
        this.renderer = renderer;
    }

    void show(String arguments, String sessionId, Consumer<String> output) {
        String value = arguments == null ? "" : arguments.trim();
        int count = 20;
        if (!value.isEmpty()) {
            try {
                count = Integer.parseInt(value);
                if (count < 1 || count > 100) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                output.accept(USAGE);
                return;
            }
        }
        if (sessionId == null || sessionId.isBlank()) {
            output.accept("No active session for /diffs.");
            return;
        }

        try {
            Path file = toolCallsDirectory.resolve(ToolCallUsageJournal.validateSessionKey(sessionId) + ".jsonl");
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                output.accept("No recent diffs found in this session.");
                return;
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Session tool-call journal is not a regular file");
            }
            List<JsonNode> calls = new ArrayList<>();
            boolean bounded;
            String tail;
            // Snapshot only the end of this one session file. No catalog locks, discovery,
            // full-session scan, or waiting for a concurrent writer's incomplete last line.
            try (var channel = Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                long size = channel.size();
                long start = Math.max(0, size - maxTailBytes);
                bounded = start > 0;
                ByteBuffer bytes = ByteBuffer.allocate((int) (size - start));
                channel.position(start);
                while (bytes.hasRemaining() && channel.read(bytes) > 0) { }
                tail = new String(bytes.array(), 0, bytes.position(), StandardCharsets.UTF_8);
            }
            int end = tail.lastIndexOf('\n');
            int first = bounded ? tail.indexOf('\n') + 1 : 0;
            String newestActivity = null;
            String projectDirectory = null;
            while (end >= first && calls.size() < count) {
                int previous = tail.lastIndexOf('\n', end - 1);
                int begin = Math.max(first, previous + 1);
                try {
                    JsonNode call = JsonUtils.standardMapper().readTree(tail.substring(begin, end));
                    boolean thisSession = call != null
                            && sessionId.equals(call.path("sessionId").asText());
                    if (thisSession && projectDirectory == null) {
                        projectDirectory = call.path("projectDirectory").asText(null);
                    }
                    if (thisSession && !call.path("isError").asBoolean()
                            && EDIT_TOOLS.contains(toolName(call.path("toolName").asText()))) {
                        calls.add(call);
                    } else if (thisSession && newestActivity == null) {
                        // The newest parseable record of this session, whatever tool it was.
                        newestActivity = call.path("timestamp").asText(null);
                    }
                } catch (IOException ignored) {
                    // A malformed record must not hide the remaining recent calls.
                }
                end = previous;
            }
            if (calls.isEmpty()) {
                output.accept("No recent diffs found in this session.");
            } else {
                output.accept("Recent diffs in this session (" + calls.size() + ", newest first):");
                for (JsonNode call : calls) {
                    output.accept("\n" + renderer.bold(TerminalRenderer.printableToolOutput(call.path("toolName").asText()))
                            + renderer.dim("  " + localTimestamp(call.path("timestamp").asText(null)) + "  ["
                            + TerminalRenderer.printableToolOutput(call.path("id").asText()) + "]"));
                    JsonNode input = call.path("toolInput");
                    if (input.isTextual()) {
                        try {
                            input = JsonUtils.standardMapper().readTree(input.asText());
                        } catch (IOException e) {
                            output.accept("Recorded arguments:\n" + TerminalRenderer.printableToolOutput(input.asText()));
                            continue;
                        }
                    }
                    if (input != null && input.has("effectiveArguments")) input = input.get("effectiveArguments");
                    else if (input != null && input.has("arguments")) input = input.get("arguments");
                    showInput(input, output);
                }
            }
            if (bounded) output.accept("Only the most recent " + maxTailBytes
                    + " bytes of this session's tool calls were inspected.");
            appendNewerEditsInOtherSessions(file, calls, newestActivity, projectDirectory, output);
        } catch (IOException | IllegalArgumentException e) {
            output.accept("Could not load recent diffs: " + e.getMessage());
        }
    }

    private static String toolName(String name) {
        // MCP/provider wrappers name the same tools differently.
        int prefix = name.lastIndexOf("__");
        if (prefix >= 0) name = name.substring(prefix + 2);
        int dot = name.lastIndexOf('.');
        return (dot >= 0 ? name.substring(dot + 1) : name).toLowerCase(Locale.ROOT);
    }

    /**
     * Agents hosting kompile through MCP (claude/codex/gemini via mcp-stdio) edit files with
     * their own native tools, which never enter kompile's journal — so this session's newest
     * recorded edit can be hours old while other sessions on the same project just edited.
     * When this session's newest edit is older than its newest recorded activity, surface the
     * newest edits from the freshest sibling journals, labeled, instead of stale-looking output.
     */
    private void appendNewerEditsInOtherSessions(Path ownFile, List<JsonNode> ownCalls,
                                                 String ownNewestActivity, String projectDirectory,
                                                 Consumer<String> output) {
        Instant newestOwnEdit = ownCalls.isEmpty() ? null
                : parseInstant(ownCalls.get(0).path("timestamp").asText(null));
        Instant newestActivity = parseInstant(ownNewestActivity);
        boolean ownJournalLooksStale = ownCalls.isEmpty()
                || (newestOwnEdit != null && newestActivity != null && newestActivity.isAfter(newestOwnEdit));
        // Never guess a project for legacy records: sibling journals span all projects.
        if (!ownJournalLooksStale || projectDirectory == null || projectDirectory.isBlank()) return;
        Instant cutoff = newestOwnEdit == null ? Instant.MIN : newestOwnEdit;
        try (var stream = Files.list(toolCallsDirectory)) {
            List<Path> siblings = stream
                    .filter(p -> !p.equals(ownFile))
                    .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(".jsonl")
                                && !name.equals("all-tool-calls.jsonl")
                                && !name.equals("tool-usage.jsonl")
                                && !name.startsWith("agent-")
                                && !name.startsWith("subagent-");
                    })
                    .sorted(java.util.Comparator.comparingLong(
                            (Path p) -> safeLastModified(p)).reversed())
                    .limit(5)
                    .toList();
            List<JsonNode> found = new ArrayList<>();
            for (Path sibling : siblings) {
                JsonNode edit = newestEditIn(sibling, projectDirectory, cutoff);
                if (edit != null) found.add(edit);
                if (found.size() >= 5) break;
            }
            found.sort(java.util.Comparator.comparing(
                    (JsonNode n) -> parseInstant(n.path("timestamp").asText(null))).reversed());
            if (found.isEmpty()) return;
            output.accept(renderer.dim("\nNewer edit activity recorded in other recent sessions"
                    + " (this session's newest edit is older than its newest activity):"));
            for (JsonNode edit : found) {
                boolean lockOnly = isRegisterEditCall(edit);
                String agent = TerminalRenderer.printableToolOutput(edit.path("agentName").asText("unknown"));
                output.accept(renderer.dim("  " + localTimestamp(edit.path("timestamp").asText(null))
                        + "  " + (lockOnly ? "edit lock" : TerminalRenderer.printableToolOutput(edit.path("toolName").asText()))
                        + "  " + TerminalRenderer.printableToolOutput(firstEditPath(edit))
                        + "  [session " + TerminalRenderer.printableToolOutput(shortSessionId(edit.path("sessionId").asText()))
                        + ", agent " + agent
                        + (lockOnly ? " — native edit, diff not captured" : "")
                        + "]"));
            }
        } catch (IOException ignored) {
            // The primary per-session output is already correct; the cross-session hint is best-effort.
        }
    }

    /** Newest non-error edit record in one journal file, read from the tail. */
    private JsonNode newestEditIn(Path file, String projectDirectory, Instant cutoff) {
        try (var channel = Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            long start = Math.max(0, size - MAX_TAIL_BYTES);
            boolean bounded = start > 0;
            ByteBuffer bytes = ByteBuffer.allocate((int) (size - start));
            channel.position(start);
            while (bytes.hasRemaining() && channel.read(bytes) > 0) { }
            String tail = new String(bytes.array(), 0, bytes.position(), StandardCharsets.UTF_8);
            int end = tail.lastIndexOf('\n');
            int first = bounded ? tail.indexOf('\n') + 1 : 0;
            while (end >= first) {
                int previous = tail.lastIndexOf('\n', end - 1);
                int begin = Math.max(first, previous + 1);
                try {
                    JsonNode call = JsonUtils.standardMapper().readTree(tail.substring(begin, end));
                    Instant timestamp = call == null ? null : parseInstant(call.path("timestamp").asText(null));
                    if (call != null && timestamp != null && timestamp.isAfter(cutoff)
                            && sameProject(projectDirectory, call.path("projectDirectory").asText(null))
                            && !call.path("isError").asBoolean()
                            && (EDIT_TOOLS.contains(toolName(call.path("toolName").asText()))
                            || isRegisterEditCall(call))) {
                        return call;
                    }
                } catch (IOException ignored) {
                    // Skip malformed lines.
                }
                end = previous;
            }
        } catch (IOException ignored) {
            // Unreadable sibling: skip it.
        }
        return null;
    }

    /**
     * Agents editing natively must lock through edit_coordinator first (the kompile mandate),
     * so a {@code register_edit} call is the earliest observable signal of a native edit —
     * journaled like any other tool call, with the target path and agent name.
     */
    private static boolean isRegisterEditCall(JsonNode call) {
        if (!"edit_coordinator".equals(toolName(call.path("toolName").asText()))) return false;
        try {
            String action = recordedInput(call).path("action").asText();
            return "register_edit".equals(action) || "register_edits".equals(action);
        } catch (IOException ignored) {
            return false;
        }
    }

    private static JsonNode recordedInput(JsonNode call) throws IOException {
        JsonNode input = call.path("toolInput");
        if (input.isTextual()) input = JsonUtils.standardMapper().readTree(input.asText());
        if (input == null) return JsonUtils.standardMapper().createObjectNode();
        if (input.has("effectiveArguments")) input = input.get("effectiveArguments");
        else if (input.has("arguments")) input = input.get("arguments");
        return input;
    }

    private static Instant parseInstant(String value) {
        try { return value == null ? null : Instant.parse(value); }
        catch (java.time.format.DateTimeParseException ignored) { return null; }
    }

    private static boolean sameProject(String expected, String actual) {
        if (actual == null || actual.isBlank()) return false;
        try { return Path.of(expected).toAbsolutePath().normalize().equals(Path.of(actual).toAbsolutePath().normalize()); }
        catch (java.nio.file.InvalidPathException ignored) { return false; }
    }

    private static long safeLastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /** First path-ish argument of an edit record, for the one-line cross-session listing. */
    private static String firstEditPath(JsonNode edit) {
        JsonNode input;
        try {
            input = recordedInput(edit);
        } catch (IOException e) {
            return "(unparsed arguments)";
        }
        for (String key : List.of("file_path", "path", "notebook_path")) {
            if (input.path(key).isTextual()) return input.get(key).asText();
        }
        JsonNode batch = input.path("edits");
        if (batch.isArray() && batch.size() > 0) {
            String nested = batch.get(0).path("file_path").asText("");
            if (!nested.isEmpty()) return nested + " (+" + (batch.size() - 1) + " more)";
        }
        JsonNode paths = input.path("file_paths");
        if (paths.isArray() && paths.size() > 0) {
            String first = paths.get(0).asText("");
            return first.isEmpty() ? "(no path)" : first + (paths.size() > 1 ? " (+" + (paths.size() - 1) + " more)" : "");
        }
        return "(no path)";
    }

    /** Short display form of a session id: keeps enough to recognize, drops the noise. */
    private static String shortSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return "?";
        String trimmed = sessionId.startsWith("emulated-") ? sessionId.substring("emulated-".length())
                : sessionId;
        return trimmed.length() <= 8 ? trimmed : trimmed.substring(0, 8);
    }

    /**
     * Journal timestamps are stored as UTC {@link java.time.Instant} strings; render them in
     * the viewer's local zone. Falls back to the raw text when a record predates the field.
     */
    private static String localTimestamp(String rawTimestamp) {
        if (rawTimestamp == null || rawTimestamp.isBlank()) return "";
        try {
            return DateTimeFormatter.ofPattern("MMM d HH:mm:ss", Locale.ROOT)
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.parse(rawTimestamp));
        } catch (DateTimeParseException badTimestamp) {
            return TerminalRenderer.printableToolOutput(rawTimestamp);
        }
    }

    private void showInput(JsonNode input, Consumer<String> output) {
        if (input == null || input.isMissingNode()) {
            output.accept("No edit arguments recorded.");
            return;
        }
        for (String batch : List.of("edits", "patches", "files")) {
            if (input.path(batch).isArray()) {
                for (JsonNode item : input.get(batch)) showInput(item, output);
                return;
            }
        }
        String path = text(input, "file_path", "path", "file", "filename");
        if (path != null) output.accept(renderer.cyan(path));
        String patch = text(input, "patch", "diff", "unified_diff");
        if (patch != null) {
            output.accept(renderer.renderDiff(patch));
            return;
        }
        String oldText = text(input, "old_string", "old_str", "search");
        String newText = text(input, "new_string", "new_str", "replace");
        if (oldText != null || newText != null) {
            List<String> lines = new ArrayList<>();
            appendLines(lines, "- ", oldText);
            appendLines(lines, "+ ", newText);
            output.accept(renderer.renderDiff(String.join("\n", lines)));
        } else {
            String content = text(input, "content", "file_text", "new_content", "code", "text");
            // Write calls don't retain the prior file contents; don't invent a before-image.
            if (content == null) {
                output.accept("Recorded arguments:\n" + input.toPrettyString());
            } else {
                List<String> lines = new ArrayList<>();
                appendLines(lines, "+ ", content);
                output.accept("written text:\n" + renderer.renderDiff(String.join("\n", lines)));
            }
        }
    }

    private static void appendLines(List<String> lines, String prefix, String text) {
        if (text == null || text.isEmpty()) return;
        for (String line : text.split("\\R", -1)) lines.add(prefix + line);
    }

    private static String text(JsonNode input, String... keys) {
        for (String key : keys) {
            if (input.path(key).isTextual()) return TerminalRenderer.printableToolOutput(input.get(key).asText());
        }
        return null;
    }
}
