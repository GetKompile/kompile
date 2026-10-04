package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.ChatCompleter;
import ai.kompile.cli.main.chat.ChatSessionMetrics;
import ai.kompile.cli.main.chat.ForegroundRequestProgress;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.config.FakeClaudeCode;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Standard chat on the Claude subscription route: provider-executed (MCP) tool
 * calls observed on the {@code claude -p} stream must render inline in the main
 * transcript, not only in the bottom activity panel. Drives the real transport,
 * parser, DirectLlmClient and AgenticChatLoop against a fake {@code claude}
 * binary replaying the CLI's stream-json shape.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class AgenticChatLoopClaudeCliTranscriptTest {
    @TempDir Path directory;

    @Test
    void providerMcpToolCallRendersInlineInMainTranscript() throws Exception {
        String home = System.getProperty("user.home");
        System.setProperty("user.home", directory.toString());
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        Map<String, String> blocks = Collections.synchronizedMap(new LinkedHashMap<>());
        List<String> panelStarts = Collections.synchronizedList(new ArrayList<>());
        List<ToolResult> panelResults = Collections.synchronizedList(new ArrayList<>());
        ChatCompleter.setContentOutput(lines::add);
        ChatCompleter.setTranscriptBlockOutput((key, content) -> {
            blocks.put(key, content);
            return true;
        });
        var mapper = JsonUtils.standardMapper();
        try (DirectLlmClient client = new DirectLlmClient(claudeConfig(), mapper, directory)) {
            installFakeClaude(client);
            AgenticChatLoop loop = new AgenticChatLoop(null, mapper, new ToolRegistry(mapper),
                    new PermissionService(), new AgentRegistry(), directory, client, null);
            String session = "claude-transcript-test";
            loop.configureConversationSession(session);
            ChatSessionMetrics metrics = new ChatSessionMetrics(session);
            loop.setSessionMetrics(metrics);
            loop.setToolActivityListener(new AgenticChatLoop.ToolActivityListener() {
                @Override
                public void onToolStart(String callId, String toolName, String rawInput) {
                    panelStarts.add(callId + ":" + toolName);
                }

                @Override
                public void onToolComplete(String callId, String toolName, String rawInput,
                                           ToolResult result) {
                    panelResults.add(result);
                }
            });

            String response = loop.chat("read the notes", session, "coder", "default", false);

            String transcript = String.join("\n", lines) + "\n"
                    + String.join("\n", blocks.values());
            String diagnostics = "response=" + response + "\nlines=" + lines + "\nblocks=" + blocks
                    + "\npanel=" + panelStarts;
            System.out.println("[claude-transcript-diagnostics]\n" + diagnostics);
            assertEquals(List.of("toolu_mcp_read_1:mcp__kompile__read"), panelStarts, diagnostics);
            assertTrue(blocks.keySet().stream().anyMatch(key -> key.contains("toolu_mcp_read_1")),
                    "the provider tool must own a main-transcript block\n" + diagnostics);
            assertTrue(transcript.contains("NOTES.md"), diagnostics);
            assertEquals(1, occurrences(transcript, "Reading the notes file now."),
                    "streamed text must not be repeated by the aggregate assistant event\n"
                            + diagnostics);
            assertEquals(List.of("Reading the notes file now.", "The notes file is short."),
                    response.strip().lines().toList(),
                    "prose on either side of a provider tool call stays on separate lines\n"
                            + diagnostics);
            String plain = plain(transcript);
            assertTrue(plain.contains("context compacted by Claude Code (manual) · 100 tokens before"),
                    "a mid-stream compact_boundary carrying a session_id must render as a "
                            + "compaction, not be eaten as a session start\n" + diagnostics);
            assertEquals(1, metrics.getCompactionEvents(), diagnostics);
            assertEquals(12, loop.lastReportedInputTokens(),
                    "the request after the compaction measures the compacted context, not the "
                            + "turn's usage summed over every request\n" + diagnostics);
            for (String bookkeeping : List.of("requesting", "thinking_tokens", "hook_started",
                    "PreToolUse", "compact_boundary")) {
                assertFalse(plain.contains(bookkeeping),
                        "stream bookkeeping is not transcript text: " + bookkeeping + "\n" + diagnostics);
            }
            // Claude Code hands back the Kompile tool's structured result as JSON; it
            // must render as the tool's output, in the transcript and the panel alike.
            assertTrue(plain.contains("NOTES_FILE_BODY"), diagnostics);
            assertFalse(plain.contains("{\"title\"") || plain.contains("\"metadata\""),
                    "the structured result must not render as JSON\n" + diagnostics);
            assertEquals(1, panelResults.size(), diagnostics);
            assertEquals("NOTES.md", panelResults.get(0).getTitle());
            assertEquals("     1\tNOTES_FILE_BODY", panelResults.get(0).getOutput());
            assertFalse(panelResults.get(0).isError());
        } finally {
            ChatCompleter.setContentOutput(null);
            ChatCompleter.setTranscriptBlockOutput(null);
            ChatCompleter.setActivity(null);
            if (home == null) System.clearProperty("user.home");
            else System.setProperty("user.home", home);
        }
    }

    @Test
    void appendOnlyOutputPrintsTheProviderToolHeaderOnceItsArgumentsArrive() throws Exception {
        String home = System.getProperty("user.home");
        System.setProperty("user.home", directory.toString());
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        ChatCompleter.setContentOutput(lines::add);
        ChatCompleter.setTranscriptBlockOutput(null);
        var mapper = JsonUtils.standardMapper();
        try (DirectLlmClient client = new DirectLlmClient(claudeConfig(), mapper, directory)) {
            installFakeClaude(client);
            AgenticChatLoop loop = new AgenticChatLoop(null, mapper, new ToolRegistry(mapper),
                    new PermissionService(), new AgentRegistry(), directory, client, null);
            String session = "claude-append-only-test";
            loop.configureConversationSession(session);

            loop.chat("read the notes", session, "coder", "default", false);

            String transcript = plain(String.join("\n", lines));
            System.out.println("[claude-append-only-transcript]\n" + transcript);
            // The call opens with no arguments. Printing its header then would leave a
            // bare "Read" row and the arguments as a raw JSON line after it.
            List<String> headers = transcript.lines()
                    .filter(line -> line.matches("\\s*\\S+ Read( .*)?")).toList();
            assertEquals(2, headers.size(), "running header and completion row\n" + transcript);
            assertTrue(headers.stream().allMatch(header -> header.contains("NOTES.md")),
                    "no header without its arguments\n" + transcript);
            assertFalse(headers.get(0).contains("✓"), transcript);
            assertTrue(headers.get(1).contains("✓"), transcript);
            assertFalse(transcript.contains("arguments:"), transcript);
            assertFalse(transcript.contains("\"file_path\""), transcript);
            assertTrue(transcript.contains("NOTES_FILE_BODY"), transcript);
            assertFalse(transcript.contains("{\"title\""), transcript);
        } finally {
            ChatCompleter.setContentOutput(null);
            ChatCompleter.setActivity(null);
            if (home == null) System.clearProperty("user.home");
            else System.setProperty("user.home", home);
        }
    }

    @Test
    void aToolCallsTokensCountInBothPanesWhileTheTurnRuns() throws Exception {
        String home = System.getProperty("user.home");
        System.setProperty("user.home", directory.toString());
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        ChatCompleter.setContentOutput(lines::add);
        ChatCompleter.setTranscriptBlockOutput(null);
        var mapper = JsonUtils.standardMapper();
        try (DirectLlmClient client = new DirectLlmClient(claudeConfig(), mapper, directory)) {
            installFakeClaude(client);
            AgenticChatLoop loop = new AgenticChatLoop(null, mapper, new ToolRegistry(mapper),
                    new PermissionService(), new AgentRegistry(), directory, client, null);
            String session = "claude-token-usage-test";
            loop.configureConversationSession(session);
            ChatSessionMetrics metrics = new ChatSessionMetrics(session);
            loop.setSessionMetrics(metrics);
            ForegroundRequestProgress progress = new ForegroundRequestProgress();
            loop.setForegroundProgress(progress);
            List<String> usage = Collections.synchronizedList(new ArrayList<>());
            StringBuilder arguments = new StringBuilder();
            List<ForegroundRequestProgress.Snapshot> toolStarted =
                    Collections.synchronizedList(new ArrayList<>());
            List<ForegroundRequestProgress.Snapshot> argumentsStreamed =
                    Collections.synchronizedList(new ArrayList<>());
            List<ForegroundRequestProgress.Snapshot> toolDone =
                    Collections.synchronizedList(new ArrayList<>());
            List<String> topBarAtToolDone = Collections.synchronizedList(new ArrayList<>());
            // The loop updates both panes first, then hands each event on to this listener.
            client.setProviderActivityListener(new DirectLlmClient.ProviderActivityListener() {
                @Override
                public void onToolStart(String callId, String name, String input) {
                    toolStarted.add(progress.snapshot());
                }

                @Override
                public void onToolInputDelta(String delta) {
                    arguments.append(delta);
                    argumentsStreamed.add(progress.snapshot());
                }

                @Override
                public void onToolComplete(String callId, String name, String output,
                                           int exitCode, boolean error) {
                    toolDone.add(progress.snapshot());
                    topBarAtToolDone.add(metrics.compactTokenSummary());
                }

                @Override
                public void onTokenUsage(long input, long output, long cacheRead, long cacheCreation) {
                    usage.add(input + "/" + output + "/" + cacheRead + "/" + cacheCreation);
                }
            });
            progress.begin();

            loop.chat("read the notes", session, "coder", "default", false);

            String diagnostics = "usage=" + usage + "\ntoolStarted=" + toolStarted
                    + "\nargumentsStreamed=" + argumentsStreamed + "\ntoolDone=" + toolDone
                    + "\ntopBar=" + topBarAtToolDone + "\nlines=" + lines;
            // Each request counts as it runs: msg_1 opens, its message_delta settles the
            // output that wrote the tool call, then msg_2 runs after the tool. The result
            // adds nothing the requests already reported.
            assertEquals(List.of("10/1/0/0", "0/41/0/0", "12/1/0/0", "0/11/0/0"), usage, diagnostics);
            // Bottom pane: the tool call's arguments are estimated as they stream...
            assertEquals("{\"file_path\": \"/tmp/project/NOTES.md\"}", arguments.toString(), diagnostics);
            assertEquals(1, toolStarted.size(), diagnostics);
            assertEquals(1, argumentsStreamed.size(), diagnostics);
            assertTrue(argumentsStreamed.get(0).estimate(), diagnostics);
            assertTrue(argumentsStreamed.get(0).tokens() - toolStarted.get(0).tokens()
                    >= arguments.length() / 4, diagnostics);
            // ...and the request's usage replaces the estimate before the tool's result.
            assertEquals(1, toolDone.size(), diagnostics);
            assertEquals(42, toolDone.get(0).tokens(), diagnostics);
            assertFalse(toolDone.get(0).estimate(), diagnostics);
            // Top pane: the request that wrote the tool call counts while the tool runs.
            assertEquals(List.of("↑10 ↓42 Σ52"), topBarAtToolDone, diagnostics);
            // The turn ends exact on both panes, every token counted once.
            ForegroundRequestProgress.Snapshot end = progress.snapshot();
            assertEquals(54, end.tokens(), diagnostics);
            assertFalse(end.estimate(), diagnostics);
            assertEquals(22, metrics.getInputTokens(), diagnostics);
            assertEquals(54, metrics.getOutputTokens(), diagnostics);
            assertEquals(76, metrics.getTotalTokens(), diagnostics);
        } finally {
            ChatCompleter.setContentOutput(null);
            ChatCompleter.setActivity(null);
            if (home == null) System.clearProperty("user.home");
            else System.setProperty("user.home", home);
        }
    }

    private static ChatConfig claudeConfig() {
        ChatConfig config = new ChatConfig("anthropic", null, "claude-opus-5-5", null);
        config.setAuthenticationMethod("oauth");
        config.setDefaultMemory(false);
        config.setContextWindowTokens(200_000);
        config.setMaxOutputTokens(4_096);
        return config;
    }

    /** Visible text: SGR styling and OSC 8 hyperlinks removed. */
    private static String plain(String text) {
        return text.replaceAll("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)", "")
                .replaceAll("\u001B\\[[0-9;?]*[A-Za-z]", "");
    }

    /** Answers the message with the fixture: the stream of one recorded turn. */
    private void installFakeClaude(DirectLlmClient client) throws Exception {
        FakeClaudeCode fake = new FakeClaudeCode(directory.resolve("claude"), """
                turn() { cat "$DIR/claude-stream.jsonl"; }
                """);
        try (InputStream fixture = getClass().getResourceAsStream("claude-stream-mcp-tool.jsonl")) {
            assertNotNull(fixture, "fixture");
            Files.write(fake.path("claude-stream.jsonl"), fixture.readAllBytes());
        }
        fake.install(client, directory, "claude-native-session");
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + 1)) {
            count++;
        }
        return count;
    }
}
