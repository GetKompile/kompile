package ai.kompile.cli.main.chat.format;

import ai.kompile.cli.main.chat.ChatHistory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exported native sessions must carry the original conversation title, not
 * reminder wrappers or compaction headers.
 */
class ConversationExporterTitleTest {

    @Test
    void reminderWrappedFirstTurnYieldsRealPromptAsTitle() {
        List<ChatHistory.Turn> turns = List.of(
                new ChatHistory.Turn("user",
                        "<kompile_reminders>\n1. [project] Plan.\n</kompile_reminders>\n\n"
                                + "Diagnose the APK DSP logging regression"),
                new ChatHistory.Turn("assistant", "On it."));

        assertEquals("Diagnose the APK DSP logging regression",
                ConversationExporter.deriveTitleFromTurns(turns));
    }

    @Test
    void compactedResumeHeaderIsSkipped() {
        List<ChatHistory.Turn> turns = List.of(
                new ChatHistory.Turn("user",
                        "This is a compacted cross-agent resume context.\n"
                                + "The original transcript had 40 turn(s)."),
                new ChatHistory.Turn("user", "Continue the parser fix"));

        assertEquals("Continue the parser fix",
                ConversationExporter.deriveTitleFromTurns(turns));
    }

    @Test
    void bracketedCompactionMarkerIsSkipped() {
        List<ChatHistory.Turn> turns = List.of(
                new ChatHistory.Turn("user", "[Compacted cross-agent resume context]\nstuff"),
                new ChatHistory.Turn("user", "the real ask"));

        assertEquals("the real ask", ConversationExporter.deriveTitleFromTurns(turns));
    }

    @Test
    void longTitlesAreTruncated() {
        List<ChatHistory.Turn> turns = List.of(new ChatHistory.Turn("user", "x".repeat(200)));

        String title = ConversationExporter.deriveTitleFromTurns(turns);

        assertEquals(80, title.length());
        assertTrue(title.endsWith("..."));
    }

    @Test
    void noUsableTurnFallsBackToResumedConversation() {
        List<ChatHistory.Turn> turns = List.of(
                new ChatHistory.Turn("assistant", "only assistant text"),
                new ChatHistory.Turn("user",
                        "<kompile_reminders>\nx\n</kompile_reminders>"));

        assertEquals("Resumed conversation",
                ConversationExporter.deriveTitleFromTurns(turns));
    }

    private static void assertTrue(boolean condition) {
        org.junit.jupiter.api.Assertions.assertTrue(condition);
    }
}
