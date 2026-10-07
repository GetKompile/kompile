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
    void longTitlesKeepTheirEnding() {
        String prompt = "x".repeat(240) + " important ending";
        assertEquals(prompt, ChatSessionTitle.fromPrompt(prompt));
        ChatSessionTitle title = new ChatSessionTitle();
        assertEquals(prompt, title.replace(prompt));
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
    void generatedTitleUpgradesOnlyProvisionalName() {
        ChatSessionTitle title = new ChatSessionTitle();
        title.initializeFromPrompt("Please help me debug this very long request");
        assertTrue(title.applyGenerated("Debugging the chat request"));
        assertFalse(title.applyGenerated("Duplicate result"));
        assertEquals("Debugging the chat request", title.get());
    }

    @Test
    void manualRenameWinsEvenWhenItMatchesFallback() {
        ChatSessionTitle title = new ChatSessionTitle();
        title.initializeFromPrompt("Original prompt");
        title.replace("Original prompt");
        assertFalse(title.applyGenerated("Generated summary"));
        assertEquals("Original prompt", title.get());
    }

    @Test
    void restoredTitleIsNotRegenerated() {
        ChatSessionTitle title = new ChatSessionTitle();
        title.replace("Restored title");
        assertFalse(title.initializeFromPrompt("Next message"));
        assertFalse(title.applyGenerated("Generated summary"));
    }

    @Test
    void blankPromptDoesNotInitializeTitle() {
        ChatSessionTitle title = new ChatSessionTitle();

        assertFalse(title.initializeFromPrompt(" \n\t "));
        assertNull(title.get());
    }
}
