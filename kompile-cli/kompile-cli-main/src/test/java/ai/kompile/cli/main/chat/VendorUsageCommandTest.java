package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.config.ChatConfig;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VendorUsageCommandTest {
    @Test void cliResolvesUsageWithoutSendingItToAModelOrWritingChatHistory() {
        ChatRepl repl = mock(ChatRepl.class);
        ChatConfig config = new ChatConfig("kompile-local", null, "local-model", null);
        when(repl.getChatConfig()).thenReturn(config);
        ChatMessageHandler handler = mock(ChatMessageHandler.class);
        ChatHistory history = mock(ChatHistory.class);
        var router = new ChatCommandRouter(repl, handler, null, null, null,
                JsonUtils.standardMapper(), "usage-test", true, history, null,
                null, null, null, null, null, null, null, null, null, null, null, List.of(), null);
        var previous = System.out;
        var bytes = new ByteArrayOutputStream();
        try (var output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setOut(output);
            assertTrue(router.handleSlashCommand("/usage"));
            assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("no remote vendor allowance"));
            bytes.reset();
            assertTrue(router.handleSlashCommand("/usage unexpected"));
            assertTrue(bytes.toString(StandardCharsets.UTF_8).startsWith("Usage: /usage"));
        } finally { System.setOut(previous); }
        verifyNoInteractions(handler, history);
        verify(repl, times(1)).getChatConfig();
        assertTrue(ChatCommandCatalog.isBuiltin("usage"));
        assertEquals(ChatCommandCatalog.WebSupport.SUPPORTED, ChatCommandCatalog.webSupport("usage"));
    }
}
