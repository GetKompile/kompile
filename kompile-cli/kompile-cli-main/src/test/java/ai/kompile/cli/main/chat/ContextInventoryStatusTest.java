package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.agent.AgenticChatLoop;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.config.ProviderCompactionCapabilities;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@TemporaryUserHome
@ResourceLock(Resources.SYSTEM_OUT)
class ContextInventoryStatusTest {
    @TempDir Path directory;

    @Test
    void compactStatusContextUsesActualLoopWithoutProviderOrCapabilityProbes() throws Exception {
        var mapper = JsonUtils.standardMapper();
        var tools = new ToolRegistry(mapper);
        var client = mock(DirectLlmClient.class);
        when(client.compactionCapabilities(null)).thenReturn(ProviderCompactionCapabilities.claudeCodeSession());
        var loop = new AgenticChatLoop(null, mapper, tools,
                new PermissionService(), new AgentRegistry(), directory, client, null);
        var repl = mock(ChatRepl.class);
        var router = router(repl, loop, tools, null);
        clearInvocations(client);

        String output = printed(() -> assertTrue(router.handleSlashCommand("/status context")));
        assertTrue(output.contains("Context inventory"), output);
        assertTrue(output.contains("estimated"), output);
        assertTrue(output.contains("not request/billed tokens"), output);
        assertTrue(output.contains("unobserved (not zero)"), output);
        assertTrue(output.contains("Claude Code session/tools opaque"), output);
        assertTrue(output.contains("not additive"), output);
        assertTrue(output.length() < 6000, "status must remain bounded");
        verifyNoInteractions(repl); // No full-status model/staging probe or memory status call.
        verify(client).compactionCapabilities(null); // Only pure local route metadata.
        verifyNoMoreInteractions(client);
    }

    @Test
    void ordinaryStatusAlsoDisplaysInventoryNotJustAnUnusedHelper() throws Exception {
        var mapper = JsonUtils.standardMapper();
        var tools = new ToolRegistry(mapper);
        var loop = new AgenticChatLoop(null, mapper, tools,
                new PermissionService(), new AgentRegistry(), directory);
        var repl = mock(ChatRepl.class);
        when(repl.getLocalAgentName()).thenReturn("coder");
        // No chat config: ordinary status must still show the local inventory.
        var history = mock(ChatHistory.class);
        when(history.getTranscriptFile()).thenReturn(directory.resolve("session.txt"));
        var router = router(repl, loop, tools, history);

        String output = printed(() -> assertTrue(router.handleSlashCommand("/status")));
        assertTrue(output.contains("Context inventory"), output);
        assertTrue(output.contains("History (current active)"), output);
        assertTrue(output.contains("0/0 entries; 0 chars; estimated 0 text tokens"), output);
        assertTrue(output.contains("Memory not separately identifiable"), output);
        assertTrue(output.contains("Provider/wire additions unknown"), output);
    }

    @Test
    void unavailableLocalLoopDoesNotInventZeroNativeCoverage() throws Exception {
        String output = printed(() -> assertTrue(router(null, null, null, null)
                .handleSlashCommand("/status context")));
        assertTrue(output.contains("provider-owned context unattributed"), output);
        assertFalse(output.contains("0 tokens"), output);
    }

    private ChatCommandRouter router(ChatRepl repl, AgenticChatLoop loop,
                                     ToolRegistry tools, ChatHistory history) {
        var renderer = new TerminalRenderer(false);
        return new ChatCommandRouter(repl, null, null, null, null,
                JsonUtils.standardMapper(), "inventory-test", true, history, null,
                renderer, new AsciiRenderer(renderer), null, null, null, tools, null,
                loop, null, null, null, List.of(), null);
    }

    private static String printed(Runnable action) throws Exception {
        PrintStream original = System.out;
        var bytes = new ByteArrayOutputStream();
        try (var stream = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setOut(stream);
            action.run();
        } finally {
            System.setOut(original);
        }
        assertTrue(bytes.size() < 12_000, "rendered status must remain bounded");
        // Panels wrap to terminal width; assertions concern labels, not incidental line breaks.
        return bytes.toString(StandardCharsets.UTF_8).replace('│', ' ').replaceAll("\\s+", " ").strip();
    }
}
