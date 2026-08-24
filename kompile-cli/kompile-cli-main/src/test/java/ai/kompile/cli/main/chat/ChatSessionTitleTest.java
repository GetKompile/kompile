package ai.kompile.cli.main.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatSessionTitleTest {

    @Test
    void firstPromptSetsNormalizedTitleOnlyOnce() {
        ChatSessionTitle title = new ChatSessionTitle();

        assertTrue(title.initializeFromPrompt("  Investigate the CLI\n title   behavior  "));
        assertEquals("Investigate the CLI title behavior", title.get());
        assertFalse(title.initializeFromPrompt("A later prompt must not replace the title"));
        assertEquals("Investigate the CLI title behavior", title.get());
    }

    @Test
    void promptTitleUsesOnlyTheLeadingEightyCharacters() {
        String prompt = "x".repeat(ChatSessionTitle.MAX_LENGTH + 20);

        String title = ChatSessionTitle.fromPrompt(prompt);

        assertEquals(ChatSessionTitle.MAX_LENGTH, title.length());
        assertTrue(title.endsWith("..."));
    }

    @Test
    void explicitTitleReplacesPromptTitleAndIsSanitized() {
        ChatSessionTitle title = new ChatSessionTitle();
        title.initializeFromPrompt("Original prompt");

        assertEquals("My custom title", title.replace("  My\033 custom\007\n title "));
        assertFalse(title.initializeFromPrompt("Another prompt"));
        assertEquals("My custom title", title.get());
    }

    @Test
    void blankPromptDoesNotInitializeTitle() {
        ChatSessionTitle title = new ChatSessionTitle();

        assertFalse(title.initializeFromPrompt(" \n\t "));
        assertNull(title.get());
    }
}
