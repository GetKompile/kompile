package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.render.ChatDisplayBlock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatDisplayBlockOutputTest {
    @Test
    void sinkIsSessionOwnedCapturedAndClearedOnClose() {
        ChatUiSession first = new ChatUiSession();
        ChatUiSession second = new ChatUiSession();
        List<ChatDisplayBlock> received = new ArrayList<>();
        ChatDisplayBlock block = ChatDisplayBlock.text("assistant:one", "assistant", "hello");
        Runnable captured;
        try (var ignored = first.bind()) {
            ChatCompleter.setDisplayBlockOutput(value -> { received.add(value); return true; });
            captured = first.capture((Runnable) () -> assertTrue(ChatCompleter.upsertDisplayBlock(block)));
        }
        try (var ignored = second.bind()) {
            assertFalse(ChatCompleter.upsertDisplayBlock(block));
            captured.run();
            assertSame(second, ChatUiSession.current());
        }
        assertEquals(List.of(block), received);
        first.close();
        assertNull(first.displayBlockOutput);
        first.capture((Runnable) () -> {
            ChatCompleter.setDisplayBlockOutput(value -> fail("closed owner must reject registration"));
            assertFalse(ChatCompleter.upsertDisplayBlock(block));
        }).run();
        assertEquals(1, received.size());
        second.close();
    }

    @Test
    void missingDecliningAndFailingSinksAllowLegacyFallback() {
        try (ChatUiSession session = new ChatUiSession(); var ignored = session.bind()) {
            ChatDisplayBlock block = ChatDisplayBlock.text("tool:one", "tool", "body");
            assertFalse(ChatCompleter.upsertDisplayBlock(block));
            ChatCompleter.setDisplayBlockOutput(value -> false);
            assertFalse(ChatCompleter.upsertDisplayBlock(block));
            ChatCompleter.setDisplayBlockOutput(value -> { throw new IllegalStateException("viewer failed"); });
            assertFalse(ChatCompleter.upsertDisplayBlock(block));
            ChatCompleter.setDisplayBlockOutput(value -> true);
            assertFalse(ChatCompleter.upsertDisplayBlock(null));
            assertFalse(ChatCompleter.upsertDisplayBlock(ChatDisplayBlock.text("", "custom", "")));
            assertTrue(ChatCompleter.upsertDisplayBlock(block));
            ChatCompleter.clearTerminalRef(null);
            assertFalse(ChatCompleter.upsertDisplayBlock(block));
        }
    }
}
