package ai.kompile.cli.main.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatHistoryTitleTest {

    @TempDir
    Path tempDir;

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void explicitTitlePersistsAndOverridesConversationListTitle() throws Exception {
        String previousHome = System.getProperty("user.home");
        Path home = tempDir.resolve("home");
        Files.createDirectories(home);
        System.setProperty("user.home", home.toString());

        try {
            ChatHistory history = new ChatHistory("title-history-test");
            history.open("", "coder", false, tempDir);
            history.logUserMessage("Original first prompt");
            history.logSessionTitle("First custom title");
            String fullTitle = "A very long custom title ".repeat(20) + "important ending";
            history.renameSession(fullTitle);
            assertEquals(fullTitle, history.readTitleOverride());
            history.logUserMessage("A later prompt must not replace the rename");
            history.close();

            assertEquals(fullTitle,
                    new ChatHistory("title-history-test").readSessionTitle());
            ChatHistory.ConversationSummary summary = ChatHistory.listConversations().stream()
                    .filter(conversation -> "title-history-test".equals(conversation.sessionId()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(fullTitle, summary.title());
            var adapter = new ai.kompile.cli.common.chat.sources.adapters.KompileAdapter(home.resolve(".kompile/conversations"));
            adapter.rename(tempDir, "title-history-test", "Renamed from web");
            assertEquals("Renamed from web", history.readTitleOverride());
        } finally {
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
        }
    }
}
