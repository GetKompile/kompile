package ai.kompile.cli.main.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused coverage for the /continue auto-reply engine: keyword matching,
 * command grammar, persistence, and the runaway-loop budget.
 */
class ContinueManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultKeywordsTriggerOnCommonQuestions() {
        ContinueManager manager = ContinueManager.inMemory();

        assertEquals("shall i proceed", manager.matchKeyword("Shall I proceed with the fix?"));
        assertEquals("proceed?", manager.matchKeyword("The plan is ready. PROCEED?"));
        assertEquals("do you want me to", manager.matchKeyword("Do you want me to delete the file?"));
        assertEquals("please confirm", manager.matchKeyword("Please confirm to continue."));
        assertEquals("y/n", manager.matchKeyword("Approve? y/n"));

        assertNull(manager.matchKeyword("I finished the refactor. All tests pass."));
        assertNull(manager.matchKeyword("Here is the summary of changes."));
    }

    @Test
    void decideFiresOnlyWhenEnabledAndBudgetAllows() throws IOException {
        ContinueManager manager = ContinueManager.inMemory();

        ContinueManager.Decision on = manager.decide("Shall I proceed?", true, false);
        assertTrue(on.fire());
        assertEquals(ContinueManager.DEFAULT_REPLY, on.reply());

        manager.setEnabled(false);
        assertFalse(manager.decide("Shall I proceed?", true, false).fire());
        manager.setEnabled(true);

        // Non-interactive (headless) and plan mode never auto-reply.
        assertFalse(manager.decide("Shall I proceed?", false, false).fire());
        assertFalse(manager.decide("Shall I proceed?", true, true).fire());
    }

    @Test
    void budgetPausesRunawayAutoRepliesAndUserActivityRearms() {
        ContinueManager manager = ContinueManager.inMemory();

        for (int i = 0; i < ContinueManager.MAX_CONSECUTIVE_AUTO_REPLIES; i++) {
            assertTrue(manager.decide("Proceed?", true, false).fire());
            manager.noteAutoReplySent();
        }

        ContinueManager.Decision paused = manager.decide("Proceed?", true, false);
        assertFalse(paused.fire());
        assertTrue(paused.notice() != null && paused.notice().contains("paused"));

        // The warning is one-shot; subsequent matches stay silent.
        ContinueManager.Decision silent = manager.decide("Proceed?", true, false);
        assertFalse(silent.fire());
        assertNull(silent.notice());

        // Any human input re-arms the budget and clears the notice.
        manager.noteUserActivity();
        assertTrue(manager.decide("Proceed?", true, false).fire());
    }

    @Test
    void commandGrammarConfiguresReplyAndKeywords() throws IOException {
        ContinueManager manager = ContinueManager.inMemory();

        assertTrue(manager.handleCommand("reply No, wait for me.").contains("No, wait for me."));
        assertEquals("No, wait for me.", manager.reply());

        assertTrue(manager.handleCommand("add ship it, DEPLOY NOW").contains("Added 2 keywords"));
        assertTrue(manager.handleCommand("test Ready to SHIP IT?").contains("WOULD be auto-replied"));
        assertTrue(manager.handleCommand("test all good").contains("NOT be auto-replied"));

        assertTrue(manager.handleCommand("remove proceed?").contains("Removed 1 keyword"));
        assertNull(manager.matchKeyword("Proceed?"));
        assertTrue(manager.effectiveKeywords().contains("continue?"));

        assertTrue(manager.handleCommand("reset").contains("default keywords"));
        assertTrue(manager.effectiveKeywords().contains("proceed?"));
    }

    @Test
    void fileBackedConfigPersistsAcrossInstances() throws IOException {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        ObjectMapper mapper = new ObjectMapper();

        ContinueManager first = new ContinueManager(mapper, project);
        first.handleCommand("on");
        first.handleCommand("reply Affirmative.");
        first.handleCommand("add ship it");

        ContinueManager second = new ContinueManager(mapper, project);
        assertTrue(second.isEnabled());
        assertEquals("Affirmative.", second.reply());
        assertTrue(second.effectiveKeywords().contains("ship it"));

        ContinueManager.Decision decision = second.decide("Ready to ship it?", true, false);
        assertTrue(decision.fire());
        assertEquals("Affirmative.", decision.reply());

        // Defaults still apply while nothing is customized (no file written yet).
        Path fresh = tempDir.resolve("fresh");
        Files.createDirectories(fresh);
        ContinueManager untouched = new ContinueManager(mapper, fresh);
        assertTrue(untouched.isEnabled());
        assertEquals(ContinueManager.DEFAULT_REPLY, untouched.reply());
        assertEquals(ContinueManager.DEFAULT_KEYWORDS.size(),
                untouched.effectiveKeywords().size());
    }

    @Test
    void slashCommandShowsStatusAndDefaults() {
        ContinueManager manager = ContinueManager.inMemory();
        String status = manager.handleCommand("");
        assertTrue(status.contains("Continue auto-reply: on"));
        assertTrue(status.contains("Yes, proceed."));
        assertTrue(manager.handleCommand("help").contains("Usage: /continue"));
    }
}
