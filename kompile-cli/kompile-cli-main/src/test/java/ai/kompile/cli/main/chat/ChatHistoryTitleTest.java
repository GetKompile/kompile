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
            history.logSessionTitle("Final custom title");
            history.close();

            assertEquals("Final custom title",
                    new ChatHistory("title-history-test").readSessionTitle());
            ChatHistory.ConversationSummary summary = ChatHistory.listConversations().stream()
                    .filter(conversation -> "title-history-test".equals(conversation.sessionId()))
                    .findFirst()
                    .orElseThrow();
            assertEquals("Final custom title", summary.title());
        } finally {
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
        }
    }
}
