package ai.kompile.cli.main.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resumed kompile chat sessions must keep their original title even when the
 * transcript's first user turn is reminder-decorated or a compaction summary.
 */
class ResumeTitlePreservationTest {

    @TempDir
    Path tempDir;

    private static final String DECORATED_PROMPT =
            "<kompile_reminders>\n"
            + "The user configured these reminders. Apply them to this prompt:\n"
            + "1. [project] Plan before making changes.\n"
            + "</kompile_reminders>\n"
            + "\n"
            + "Investigate the resume title bug";

    @Test
    void reminderBlockHelpersDetectOpenAndCloseTags() {
        assertTrue(ReminderManager.opensReminderBlock("<kompile_reminders>"));
        assertTrue(ReminderManager.opensReminderBlock("  <kompile_reminders>"));
        assertTrue(ReminderManager.closesReminderBlock("</kompile_reminders>"));
        assertFalse(ReminderManager.opensReminderBlock("Fix the parser"));
        assertFalse(ReminderManager.closesReminderBlock("Fix the parser"));
        assertFalse(ReminderManager.opensReminderBlock(null));
    }

    @Test
    void chatHistorySkipsReminderBlockWhenListingTitles() {
        withIsolatedHome(() -> {
            ChatHistory history = new ChatHistory("reminder-title-test");
            history.open("", "coder", false, tempDir);
            history.logUserMessage(DECORATED_PROMPT);
            history.close();

            ChatHistory.ConversationSummary summary = ChatHistory.listConversations().stream()
                    .filter(c -> "reminder-title-test".equals(c.sessionId()))
                    .findFirst()
                    .orElseThrow();
            assertEquals("Investigate the resume title bug", summary.title());
        });
    }

    @Test
    void listConversationsStillUsesExplicitTitleMarkerWhenPresent() {
        withIsolatedHome(() -> {
            ChatHistory history = new ChatHistory("marker-title-test");
            history.open("", "coder", false, tempDir);
            history.logUserMessage(DECORATED_PROMPT);
            history.logSessionTitle("Custom user title");
            history.close();

            ChatHistory.ConversationSummary summary = ChatHistory.listConversations().stream()
                    .filter(c -> "marker-title-test".equals(c.sessionId()))
                    .findFirst()
                    .orElseThrow();
            assertEquals("Custom user title", summary.title());
        });
    }

    @Test
    void reminderOnlyPromptYieldsNoTitleFromTranscriptScan() {
        withIsolatedHome(() -> {
            // A transcript whose only user turn is a bare reminder block must not
            // surface the tag as a title.
            ChatHistory history = new ChatHistory("reminder-only-test");
            history.open("", "coder", false, tempDir);
            history.logUserMessage(
                    "<kompile_reminders>\n1. [project] Plan.\n</kompile_reminders>\n");
            history.close();

            assertTrue(ChatHistory.listConversations().stream()
                    .noneMatch(c -> "reminder-only-test".equals(c.sessionId())),
                    "reminder-only transcript must be skipped like other empty sessions");
        });
    }

    @FunctionalInterface
    interface ThrowingTest {
        void run() throws Exception;
    }

    private void withIsolatedHome(ThrowingTest test) {
        String previousHome = System.getProperty("user.home");
        Path home = tempDir.resolve("home");
        try {
            Files.createDirectories(home);
            System.setProperty("user.home", home.toString());
            test.run();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }
}
