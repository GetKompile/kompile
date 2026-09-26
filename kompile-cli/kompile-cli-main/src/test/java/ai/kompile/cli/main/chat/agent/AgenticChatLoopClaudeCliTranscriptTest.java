package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.ChatCompleter;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        ChatCompleter.setContentOutput(lines::add);
        ChatCompleter.setTranscriptBlockOutput((key, content) -> {
            blocks.put(key, content);
            return true;
        });
        var mapper = JsonUtils.standardMapper();
        ChatConfig config = new ChatConfig("anthropic", null, "claude-opus-5-5", null);
        config.setAuthenticationMethod("oauth");
        config.setDefaultMemory(false);
        config.setContextWindowTokens(200_000);
        config.setMaxOutputTokens(4_096);
        try (DirectLlmClient client = new DirectLlmClient(config, mapper, directory)) {
            installFakeClaude(client);
            AgenticChatLoop loop = new AgenticChatLoop(null, mapper, new ToolRegistry(mapper),
                    new PermissionService(), new AgentRegistry(), directory, client, null);
            String session = "claude-transcript-test";
            loop.configureConversationSession(session);
            loop.setToolActivityListener(new AgenticChatLoop.ToolActivityListener() {
                @Override
                public void onToolStart(String callId, String toolName, String rawInput) {
                    panelStarts.add(callId + ":" + toolName);
                }

                @Override
                public void onToolComplete(String callId, String toolName, String rawInput,
                                           ToolResult result) { }
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
        } finally {
            ChatCompleter.setContentOutput(null);
            ChatCompleter.setTranscriptBlockOutput(null);
            ChatCompleter.setActivity(null);
            if (home == null) System.clearProperty("user.home");
            else System.setProperty("user.home", home);
        }
    }

    private void installFakeClaude(DirectLlmClient client) throws Exception {
        Path stream = directory.resolve("claude-stream.jsonl");
        try (InputStream fixture = getClass().getResourceAsStream("claude-stream-mcp-tool.jsonl")) {
            assertNotNull(fixture, "fixture");
            Files.write(stream, fixture.readAllBytes());
        }
        Path fake = directory.resolve("fake-claude");
        Files.writeString(fake, "#!/bin/bash\ncat > /dev/null 2>&1 || true\ncat '"
                + stream + "'\nexit 0\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(fake, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        Class<?> transport = Class.forName("ai.kompile.cli.main.chat.config.ClaudeCliClient");
        Constructor<?> constructor = transport.getDeclaredConstructor(
                Path.class, String.class, String.class);
        constructor.setAccessible(true);
        Object claude = constructor.newInstance(directory, "claude-native-session", fake.toString());
        Field field = DirectLlmClient.class.getDeclaredField("claudeServeClient");
        field.setAccessible(true);
        field.set(client, claude);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + 1)) {
            count++;
        }
        return count;
    }
}
