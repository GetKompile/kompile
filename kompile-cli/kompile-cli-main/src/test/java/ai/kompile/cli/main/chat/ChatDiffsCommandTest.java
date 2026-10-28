package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.tools.ToolResult;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock(Resources.SYSTEM_OUT)
class ChatDiffsCommandTest {
    @TempDir Path home;
    private String previousHome;
    private Path journals;

    @BeforeEach
    void isolateHome() {
        previousHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        journals = home.resolve(".kompile/conversations/tool-calls");
    }

    @AfterEach
    void restoreHome() {
        if (previousHome == null) System.clearProperty("user.home");
        else System.setProperty("user.home", previousHome);
    }

    @Test
    void defaultCountShowsTwentyLatestEditCallsNotTwentyLatestTools() throws Exception {
        for (int i = 0; i < 25; i++) append("current", "edit-" + i, "edit", edit("file-" + i), false);
        for (int i = 0; i < 30; i++) append("current", "read-" + i, "read", Map.of("file_path", "read-only"), false);
        List<String> output = show("", "current");
        assertEquals("Recent diffs in this session (20, newest first):", output.get(0));
        assertTrue(output.get(1).contains("[edit-24]"));
        assertTrue(output.stream().anyMatch(line -> line.contains("[edit-5]")));
        assertFalse(output.stream().anyMatch(line -> line.contains("[edit-4]") || line.contains("read-only")));
    }

    @Test
    void onlyCurrentSessionIsReadWithoutCreatingADiffIndex() throws Exception {
        append("other", "other", "edit", edit("other-session.java"), false);
        append("current", "current", "edit", edit("current.java"), false);
        // Even a foreign record placed in this session's file cannot leak through.
        Files.writeString(journals.resolve("current.jsonl"), record("other", "misfiled", "edit",
                edit("foreign.java"), false), StandardOpenOption.APPEND);
        Files.writeString(journals.resolve("all-tool-calls.jsonl"), "invalid global catalog");
        List<String> output = show("1", "current");
        assertTrue(output.contains("current.java"));
        assertFalse(output.toString().contains("other-session.java"));
        assertFalse(output.toString().contains("foreign.java"));
        assertFalse(Files.exists(home.resolve(".kompile/agent-state/diff-index")));
    }

    @Test
    void newlyAppendedEditsInTheSameSessionAreVisibleImmediately() throws Exception {
        append("current", "first", "edit", edit("before.java"), false);
        var command = new ChatDiffsCommand(new TerminalRenderer(false));
        List<String> first = new ArrayList<>();
        command.show("1", "current", first::add);
        append("current", "second", "edit", edit("after.java"), false);
        List<String> second = new ArrayList<>();
        command.show("1", "current", second::add);
        assertTrue(first.contains("before.java"));
        assertTrue(second.contains("after.java"));
        assertFalse(second.contains("before.java"));
    }

    @Test
    void filtersErrorsAndPreservesFullPatchEditAndWriteArguments() throws Exception {
        String diff = "--- a/file.java\n+++ b/file.java\n" + "+added line\n".repeat(100);
        append("current", "patch", "functions.apply_patch", Map.of("patch", diff), false);
        append("current", "edit", "mcp__kompile__Edit", edit("file.txt"), false);
        append("current", "write", "Write", Map.of("file_path", "new.txt", "content", "written\ncontent"), false);
        append("current", "failed", "edit", edit("failed.txt"), true);
        List<String> output = show("100", "current");
        assertTrue(output.contains(diff), "Do not truncate explicitly requested patch content");
        assertTrue(output.contains("- before\n+ after"));
        assertTrue(output.contains("written text:\n+ written\n+ content"));
        assertFalse(String.join("\n", output).contains("\033"), "Plain rendering must not emit ANSI styles");
        assertFalse(output.contains("failed.txt"));
    }

    @Test
    void displaysBatchAndEffectiveArguments() throws Exception {
        append("current", "batch", "edit_batch", Map.of("edits", List.of(edit("one.txt"), edit("two.txt"))), false);
        append("current", "corrected", "edit", Map.of("arguments", edit("original.txt"),
                "effectiveArguments", edit("effective.txt"), "audit", Map.of("decision", "corrected")), false);
        List<String> output = show("2", "current");
        assertTrue(output.contains("one.txt"));
        assertTrue(output.contains("two.txt"));
        assertTrue(output.contains("effective.txt"));
        assertFalse(output.contains("original.txt"));
        assertEquals("Recent diffs in this session (2, newest first):", output.get(0));
    }

    @Test
    void editColorsMatchInlineTranscriptIncludingMultilineReplacements() throws Exception {
        Map<String, String> input = Map.of("file_path", "example.java",
                "old_string", "before\n\nold tail", "new_string", "after\n\nnew tail");
        append("current", "edit", "mcp__kompile__edit", input, false);
        TerminalRenderer renderer = new TerminalRenderer(true);
        List<String> output = new ArrayList<>();
        new ChatDiffsCommand(renderer).show("1", "current", output::add);
        String rendered = String.join("\n", output);
        String inline = renderer.renderToolResultDetail("edit", json(input), ToolResult.success("done"));
        assertStyle(rendered, "example.java", AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN));
        for (String token : List.of("- before", "- old tail", "+ after", "+ new tail")) {
            assertEquals(styleAt(inline, token), styleAt(rendered, token), "Inline parity for " + token);
        }
        assertStyle(rendered, "- before", AttributedStyle.DEFAULT.foreground(AttributedStyle.RED));
        assertStyle(rendered, "+ after", AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN));
        assertTrue(AttributedString.fromAnsi(rendered).toString().contains("- before\n- \n- old tail\n+ after\n+ \n+ new tail"));
    }

    @Test
    void colorsPatchHeadersContextAndBatchAliasesWithoutTruncation() throws Exception {
        String patch = "--- a/file\n+++ b/file\n@@ -1 +1 @@\n context\n-removed\n+added\n"
                + "+more\n".repeat(100) + "+last";
        append("current", "patch", "functions.apply_patch", Map.of("patch", patch), false);
        append("current", "batch", "edit_patch", Map.of("patches",
                List.of(Map.of("file_path", "batch.txt", "patch", "-batch old\n+batch new"))), false);
        append("current", "aliases", "edit_batch", Map.of("effectiveArguments", Map.of("edits",
                List.of(Map.of("path", "alias.txt", "old_str", "alias old", "new_str", "alias new")))), false);
        append("current", "write", "Write", Map.of("file_path", "written.txt", "content", "written\ncontent"), false);
        List<String> output = new ArrayList<>();
        new ChatDiffsCommand(new TerminalRenderer(true)).show("4", "current", output::add);
        String rendered = String.join("\n", output);
        for (String token : List.of("--- a/file", "+++ b/file", "@@ -1 +1 @@")) {
            assertStyle(rendered, token, AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN));
        }
        assertStyle(rendered, " context", AttributedStyle.DEFAULT.faint());
        for (String token : List.of("-removed", "-batch old", "- alias old")) {
            assertStyle(rendered, token, AttributedStyle.DEFAULT.foreground(AttributedStyle.RED));
        }
        for (String token : List.of("+added", "+last", "+batch new", "+ alias new", "+ written", "+ content")) {
            assertStyle(rendered, token, AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN));
        }
        String plain = AttributedString.fromAnsi(rendered).toString();
        assertTrue(plain.contains(patch), "Explicit history must retain the full patch, not the inline preview");
        assertTrue(plain.contains("written text:\n+ written\n+ content"));
    }

    @Test
    void recordedTerminalControlsCannotOverrideDiffColorsOrClearTheScreen() throws Exception {
        append("current", "edit", "edit", Map.of("file_path", "\033[2Jsafe.txt",
                "old_string", "\033[34mbefore\033[0m", "new_string", "after\007"), false);
        List<String> output = new ArrayList<>();
        new ChatDiffsCommand(new TerminalRenderer(true)).show("1", "current", output::add);
        String rendered = String.join("\n", output);
        assertFalse(rendered.contains("\033[2J"));
        assertFalse(rendered.contains("\033[34m"));
        assertFalse(rendered.contains("\007"));
        assertStyle(rendered, "safe.txt", AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN));
        assertStyle(rendered, "- before", AttributedStyle.DEFAULT.foreground(AttributedStyle.RED));
        assertStyle(rendered, "+ after", AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN));
    }

    @Test
    void skipsMalformedAndUnfinishedRecordsUntilTheyAreCompleted() throws Exception {
        append("current", "first", "edit", edit("first.txt"), false);
        String next = record("current", "next", "edit", edit("next.txt"), false);
        Files.writeString(journals.resolve("current.jsonl"), "broken JSON\n" + next.substring(0, next.length() - 1),
                StandardOpenOption.APPEND);
        assertFalse(show("20", "current").contains("next.txt"));
        Files.writeString(journals.resolve("current.jsonl"), "\n", StandardOpenOption.APPEND);
        assertTrue(show("20", "current").contains("next.txt"));
    }

    @Test
    void boundsReadsAndReportsWhenOlderCallsWereNotInspected() throws Exception {
        append("current", "old", "edit", edit("old.txt"), false);
        Files.writeString(journals.resolve("current.jsonl"), "x".repeat(1024 * 1024) + "\n", StandardOpenOption.APPEND);
        append("current", "new", "edit", edit("new.txt"), false);
        var command = new ChatDiffsCommand(journals, 1024, new TerminalRenderer(false));
        List<String> output = new ArrayList<>();
        assertTimeout(Duration.ofSeconds(3), () -> command.show("100", "current", output::add));
        assertTrue(output.contains("new.txt"));
        assertFalse(output.contains("old.txt"));
        assertTrue(output.get(output.size() - 1).contains("1024 bytes"));
    }

    @Test
    void emptyAndMissingSessionsDoNotCreateStorage() {
        assertEquals(List.of("No recent diffs found in this session."), show("7", "empty"));
        assertEquals(List.of("No active session for /diffs."), show("", null));
        assertFalse(Files.exists(journals));
    }

    @Test
    void invalidCountsAreRejectedBeforeReadingAndUnsafeSessionPathsAreRejected() throws Exception {
        for (String value : List.of("0", "-1", "101", "9999999999999", "abc", "2 extra", "help")) {
            assertEquals(List.of(ChatDiffsCommand.USAGE), show(value, "../unsafe"));
        }
        assertTrue(show("1", "../unsafe").get(0).startsWith("Could not load recent diffs:"));
        Files.createDirectories(journals.resolve("directory.jsonl"));
        assertTrue(show("1", "directory").get(0).contains("not a regular file"));
    }

    @Test
    void malformedTimestampsCannotInjectTerminalControls() throws Exception {
        Files.createDirectories(journals);
        Files.writeString(journals.resolve("current.jsonl"),
                record("current", "edit", "edit", edit("safe.txt"), false)
                        .replace("2026-10-13T12:00:00Z", "bad\\u001b[2J"));
        String rendered = String.join("\n", show("1", "current"));
        assertTrue(rendered.contains("safe.txt"), rendered);
        assertFalse(rendered.contains("\033"), "Malformed timestamps must be printable text");
    }

    @Test
    void timestampsRenderInViewerLocalTimeNotRawUtc() throws Exception {
        // Fixture instant 2026-10-13T12:00:00Z renders as Oct 13 21:00 in Asia/Tokyo (+0900).
        java.util.TimeZone previousDefault = java.util.TimeZone.getDefault();
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tokyo"));
        try {
            append("current", "edit", "edit", edit("tz.java"), false);
            List<String> output = show("1", "current");
            String rendered = String.join("\n", output);
            assertTrue(rendered.contains("Oct 13 21:00:00"),
                    "Timestamp must render in the viewer's local zone, got: " + rendered);
            assertFalse(rendered.contains("Z "), "Raw UTC instant must not be shown");
        } finally {
            java.util.TimeZone.setDefault(previousDefault);
        }
    }

    @Test
    void staleSessionFallsBackToNewerEditsInOtherRecentSessions() throws Exception {
        // This session's newest activity (a read, 10:00Z) is newer than its newest edit (07:00Z).
        append("current", "stale-edit", "edit", edit("stale.txt"), false, "2026-10-13T07:00:00Z");
        Files.writeString(journals.resolve("current.jsonl"), record("current", "fresh-read", "read",
                Map.of("file_path", "read-only"), false, "2026-10-13T10:00:00Z"), StandardOpenOption.APPEND);
        // A sibling session edited at 09:00Z — newer than the stale edit, older than the read.
        append("fresher", "sibling-edit", "edit", edit("fresh-work.txt"), false, "2026-10-13T09:00:00Z");
        List<String> output = show("1", "current");
        assertTrue(output.contains("stale.txt"), "Session-scoped diffs still list this session's newest edit");
        assertTrue(output.stream().anyMatch(line -> line.contains("Newer edit activity recorded in other recent sessions")),
                "Stale session must be told about fresher sibling edits");
        assertTrue(output.stream().anyMatch(line -> line.contains("fresh-work.txt")));
    }

    @Test
    void registerEditLocksCountAsNativeEditSignalsInFallback() throws Exception {
        // This session's newest activity (a read, 23:00Z) is newer than its newest edit (07:00Z).
        append("current", "stale-edit", "edit", edit("stale.txt"), false, "2026-10-13T07:00:00Z");
        Files.writeString(journals.resolve("current.jsonl"), record("current", "fresh-read", "read",
                Map.of("file_path", "read-only"), false, "2026-10-13T23:00:00Z"), StandardOpenOption.APPEND);
        // A sibling session natively edited a file, locking it through edit_coordinator at 20:00Z.
        Files.writeString(journals.resolve("native.jsonl"), record("native", "lock-1", "edit_coordinator",
                Map.of("action", "register_edit", "file_path", "/proj/NativeKernel.cpp",
                        "agent_name", "claude", "edit_type", "exclusive"),
                false, "2026-10-13T20:00:00Z"), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        // Read-only edit_coordinator calls must not count as edits.
        Files.writeString(journals.resolve("native.jsonl"), record("native", "lock-query", "edit_coordinator",
                Map.of("action", "query_edits"), false, "2026-10-13T22:00:00Z"), StandardOpenOption.APPEND);
        List<String> output = show("1", "current");
        assertTrue(output.stream().anyMatch(line -> line.contains("Newer edit activity recorded in other recent sessions")),
                "register_edit must surface as native edit activity");
        assertTrue(output.stream().anyMatch(line -> line.contains("NativeKernel.cpp")));
        assertTrue(output.stream().anyMatch(line -> line.contains("native edit, diff not captured")));
    }

    @Test
    void fallbackIgnoresOlderAndForeignProjectEdits() throws Exception {
        append("current", "mine", "edit", edit("mine.txt"), false, "2026-10-13T07:00:00Z");
        append("current", "read", "read", Map.of("file_path", "read-only"), false,
                "2026-10-13T10:00:00Z");
        append("older", "old", "edit", edit("older-sibling.txt"), false, "2026-10-13T06:00:00Z");
        String foreign = record("foreign", "foreign-edit", "edit", edit("foreign-project.txt"), false,
                "2026-10-13T09:00:00Z").replace(home.toString(), home.resolve("elsewhere").toString());
        Files.writeString(journals.resolve("foreign.jsonl"), foreign);
        String rendered = String.join("\n", show("5", "current"));
        assertFalse(rendered.contains("older-sibling.txt"), rendered);
        assertFalse(rendered.contains("foreign-project.txt"), rendered);
        assertFalse(rendered.contains("Newer edit activity"), rendered);
    }

    @Test
    void fallbackRecognizesNamespacedLocksAndSanitizesUntrustedMetadata() throws Exception {
        append("current", "mine", "edit", edit("mine.txt"), false, "2026-10-13T07:00:00Z");
        append("current", "read", "read", Map.of("file_path", "read-only"), false,
                "2026-10-13T10:00:00Z");
        append("native", "lock", "mcp__kompile__edit_coordinator",
                Map.of("action", "register_edits", "file_paths", List.of("unsafe\033[2J.txt")),
                false, "2026-10-13T09:00:00Z");
        String rendered = String.join("\n", show("5", "current"));
        assertTrue(rendered.contains("unsafe"), rendered);
        assertTrue(rendered.contains("native edit, diff not captured"), rendered);
        assertFalse(rendered.contains("\033"), "Journal metadata must not execute terminal escapes");
    }

    @Test
    void fallbackSkipsNullArgumentsWithoutHidingOtherRecentEdits() throws Exception {
        append("current", "mine", "edit", edit("mine.txt"), false, "2026-10-13T07:00:00Z");
        append("current", "read", "read", Map.of("file_path", "read-only"), false,
                "2026-10-13T10:00:00Z");
        append("native", "lock", "edit_coordinator", null, false, "2026-10-13T09:00:00Z");
        append("valid", "edit", "edit", edit("valid-sibling.txt"), false, "2026-10-13T09:00:00Z");
        String rendered = String.join("\n", assertDoesNotThrow(() -> show("5", "current")));
        assertTrue(rendered.contains("valid-sibling.txt"), rendered);
    }

    @Test
    void freshSessionDoesNotGetCrossSessionFallbackNoise() throws Exception {
        // Newest edit (12:00Z) is also the newest activity → not stale → no fallback section.
        append("current", "latest-edit", "edit", edit("mine.txt"), false);
        append("other", "newer-sibling-edit", "edit", edit("theirs.txt"), false, "2026-10-13T23:00:00Z");
        List<String> output = show("5", "current");
        assertTrue(output.contains("mine.txt"));
        assertFalse(output.stream().anyMatch(line -> line.contains("Newer edit activity recorded in other recent sessions")),
                "A session whose newest edit is also its newest activity is not stale");
    }

    @Test
    void readsActualLocalAndMcpToolRecords() {
        ToolCallIndex.getInstance().record("current", "edit", json(edit("local.txt")),
                "kompile-agent", "local-chat", false, 1, home.toString());
        ToolCallIndex.getInstance().record("current", "mcp__kompile__write",
                json(Map.of("file_path", "mcp.txt", "content", "data")), "codex", "mcp", false, 1, home.toString());
        List<String> output = show("2", "current");
        assertTrue(output.contains("local.txt"));
        assertTrue(output.contains("mcp.txt"));
    }

    @Test
    void bothChatRoutersUseTheirCurrentSessionWithoutAModelTurn() throws Exception {
        append("standard", "standard-edit", "edit", edit("standard-only.txt"), false);
        append("managed", "managed-edit", "edit", edit("managed-only.txt"), false);
        var repl = mock(ChatRepl.class);
        ChatCommandRouter router = new ChatCommandRouter(repl, null, null, null, null,
                JsonUtils.standardMapper(), "standard", true, null, null, new TerminalRenderer(true), null,
                null, null, null, null, null, null, null, null, null, List.of(), null);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
             var terminal = new org.jline.terminal.impl.LineDisciplineTerminal("diffs-test", "xterm",
                     new ByteArrayOutputStream(), StandardCharsets.UTF_8)) {
            System.setOut(out);
            assertTrue(router.handleSlashCommand("/diffs 1"));
            assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("standard-only.txt"));
            assertFalse(bytes.toString(StandardCharsets.UTF_8).contains("managed-only.txt"));
            assertStyle(bytes.toString(StandardCharsets.UTF_8), "- before", AttributedStyle.DEFAULT.foreground(AttributedStyle.RED));
            assertStyle(bytes.toString(StandardCharsets.UTF_8), "+ after", AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN));
            verifyNoInteractions(repl);
            bytes.reset();
            var managed = new EmulatedPassthroughCommand();
            terminal.setSize(new org.jline.terminal.Size(120, 30));
            org.springframework.test.util.ReflectionTestUtils.setField(managed, "terminal", terminal);
            org.springframework.test.util.ReflectionTestUtils.setField(managed, "drawLock", new Object());
            org.springframework.test.util.ReflectionTestUtils.setField(managed, "renderer",
                    new TerminalRenderer(true));
            org.springframework.test.util.ReflectionTestUtils.setField(managed, "busyInputActive", true);
            org.springframework.test.util.ReflectionTestUtils.setField(managed, "scrollBottom", 10);
            org.springframework.test.util.ReflectionTestUtils.setField(managed, "inputRows", 3);
            assertNull(managed.handleSlashCommand("/diffs 1", null, null, new ChatSessionMetrics("managed")));
            assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("managed-only.txt"));
            assertFalse(bytes.toString(StandardCharsets.UTF_8).contains("standard-only.txt"));
            assertStyle(bytes.toString(StandardCharsets.UTF_8), "- before", AttributedStyle.DEFAULT.foreground(AttributedStyle.RED));
            assertStyle(bytes.toString(StandardCharsets.UTF_8), "+ after", AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN));
        } finally {
            System.setOut(originalOut);
        }
        assertTrue(ChatCommandCatalog.isBuiltin("diffs"));
        assertFalse(new ai.kompile.cli.main.chat.skill.SkillRegistry().isInvokableName("diffs"));
    }

    private static AttributedStyle styleAt(String rendered, String token) {
        AttributedString styled = AttributedString.fromAnsi(rendered);
        int index = styled.toString().indexOf(token);
        assertTrue(index >= 0, "Missing rendered token: " + token);
        return styled.styleAt(index);
    }

    private static void assertStyle(String rendered, String token, AttributedStyle expected) {
        assertEquals(expected, styleAt(rendered, token), "Style for " + token);
    }

    private List<String> show(String count, String session) {
        List<String> output = new ArrayList<>();
        new ChatDiffsCommand(new TerminalRenderer(false)).show(count, session, output::add);
        return output;
    }

    private Map<String, String> edit(String path) {
        return Map.of("file_path", path, "old_string", "before", "new_string", "after");
    }

    private void append(String session, String id, String tool, Object arguments, boolean error) throws Exception {
        append(session, id, tool, arguments, error, "2026-10-13T12:00:00Z");
    }

    private void append(String session, String id, String tool, Object arguments, boolean error,
                        String timestamp) throws Exception {
        Files.createDirectories(journals);
        Files.writeString(journals.resolve(session + ".jsonl"),
                record(session, id, tool, arguments, error, timestamp),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private String record(String session, String id, String tool, Object arguments, boolean error) {
        return record(session, id, tool, arguments, error, "2026-10-13T12:00:00Z");
    }

    private String record(String session, String id, String tool, Object arguments, boolean error, String timestamp) {
        return json(new ToolCallRecord(id, session, tool, json(arguments), "", Instant.parse(timestamp),
                "local-chat", "codex", error, 1, "filesystem", home.toString())) + "\n";
    }

    private String json(Object value) {
        try {
            return JsonUtils.standardMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
