package ai.kompile.cli.main.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
        assertTrue(status.contains("Stall detectors: on"));
        assertTrue(manager.handleCommand("help").contains("Usage: /continue"));
    }

    // ── Detector lane ────────────────────────────────────────────────────────

    @Test
    void detectorFiresWhenNoKeywordMatches() {
        // The shipped keyword set contains the whole announced-action family, so a
        // promise always matches a keyword there and PROMISED_ACTION never speaks.
        // A user who configures their own keywords still gets detector coverage.
        ContinueManager custom = ContinueManager.inMemory(List.of("proceed?"));
        ContinueManager.Decision detected = custom.decide(
                "The schema drift is the root cause.\n\nI'll wire the migration next.", true, false);
        assertTrue(detected.fire());
        assertEquals(List.of(ContinueDetector.Kind.PROMISED_ACTION), detected.detectors());
        assertTrue(detected.describe().contains("promised action"));

        // With the defaults, the same promise is a keyword hit, not a detector hit.
        ContinueManager defaults = ContinueManager.inMemory();
        ContinueManager.Decision keyword = defaults.decide(
                "The schema drift is the root cause.\n\nI'll wire the migration next.", true, false);
        assertTrue(keyword.fire());
        assertEquals("keyword", keyword.describe());
        assertEquals("Yes, proceed.", keyword.reply());
    }

    @Test
    void letMeDoIsAnExplicitKeyword() {
        ContinueManager manager = ContinueManager.inMemory();
        assertTrue(ContinueManager.DEFAULT_KEYWORDS.contains("let me do"));
        assertEquals("let me do", manager.matchKeyword("The cache layer is stale. Let Me Do that now."));

        // The keyword lane owns it, so it fires wherever the phrase appears —
        // including mid-report, where the closing-only detector stays silent.
        ContinueManager.Decision midReport = manager.decide(
                "Let me do the migration first.\n\nThat also fixed the stale cache entry.",
                true, false);
        assertTrue(midReport.fire());
        assertEquals("keyword", midReport.describe());
    }

    @Test
    void announcedActionFamilyTriggersAnywhereInTheTurn() {
        ContinueManager manager = ContinueManager.inMemory();
        // The family is in the default list, so mid-report announcements fire too.
        for (String phrase : List.of("let me", "let's", "i'll", "i will")) {
            assertTrue(ContinueManager.DEFAULT_KEYWORDS.contains(phrase), phrase);
        }
        assertTrue(manager.decide("Let me trace that call path.\n\nThe guard is the cause.",
                true, false).fire(), "mid-report 'let me'");
        assertTrue(manager.decide("I'll start with the resolver.\n\nIt reads the route table.",
                true, false).fire(), "mid-report i'll");
        assertTrue(manager.decide("Now let's wire the flag through.\n\nThe build passed.",
                true, false).fire(), "mid-report let's");
    }

    @Test
    void closingCourtesiesDoNotTriggerTheFamily() {
        ContinueManager manager = ContinueManager.inMemory();
        // "let me know" asks for nothing; answering it would invent a request.
        assertNull(manager.matchKeyword("All done. Let me know if you want the short version."));
        assertFalse(manager.decide("All done. Let me know if you want the short version.",
                true, false).fire());
        assertFalse(manager.decide("Shipped. I'll let you know once it lands.", true, false).fire());

        // A user who adds the courtesy explicitly still gets it honoured.
        ContinueManager custom = ContinueManager.inMemory(List.of("let me know"));
        assertEquals("let me know", custom.matchKeyword("All done. Let me know if you want more."));
        assertTrue(custom.decide("All done. Let me know if you want more.", true, false).fire());
    }

    @Test
    void reportedGapClosesTheTurnWithoutAKeyword() {
        ContinueManager manager = ContinueManager.inMemory();
        ContinueManager.Decision decision = manager.decide(
                "The typed fields propagate end to end.\n\n"
                        + "Remaining gap: no test drives the failover path.", true, false);
        assertTrue(decision.fire());
        assertEquals(List.of(ContinueDetector.Kind.REPORTED_GAP), decision.detectors());
        assertTrue(decision.evidence().toLowerCase().contains("remaining gap"));
    }

    @Test
    void detectorLaneRespectsEveryExistingGate() throws IOException {
        ContinueManager manager = ContinueManager.inMemory();
        String stalling = "Root cause found.\n\nI'll wire the migration next.";

        assertFalse(manager.decide(stalling, false, false).fire(), "headless runs never sleep");
        assertFalse(manager.decide(stalling, true, true).fire(), "plan mode defeats auto-reply");

        manager.setEnabled(false);
        assertFalse(manager.decide(stalling, true, false).fire(), "disabled means disabled");
        manager.setEnabled(true);

        // The runaway budget is shared by both lanes.
        for (int i = 0; i < ContinueManager.MAX_CONSECUTIVE_AUTO_REPLIES; i++) {
            assertTrue(manager.decide(stalling, true, false).fire());
            manager.noteAutoReplySent();
        }
        ContinueManager.Decision paused = manager.decide(stalling, true, false);
        assertFalse(paused.fire());
        assertTrue(paused.notice().contains("paused"));
    }

    @Test
    void keywordLaneWinsOverDetectors() {
        ContinueManager manager = ContinueManager.inMemory();
        // Both would fire; the keyword answer is the one that is reported.
        ContinueManager.Decision decision = manager.decide(
                "Shall I proceed?\n\nI'll start with the migration next.", true, false);
        assertTrue(decision.fire());
        assertEquals("keyword", decision.describe());
        assertTrue(decision.detectors().isEmpty());
    }

    @Test
    void testCommandPreviewNamesTheDetector() throws IOException {
        // A custom keyword set leaves the announced-action family unclaimed, so the
        // preview reports the detector and its evidence rather than a bare "keyword".
        ContinueManager custom = ContinueManager.inMemory(List.of("proceed?"));
        String detected = custom.handleCommand("test Root cause found. I'll wire it next.");
        assertTrue(detected.contains("promised action"), detected);
        assertTrue(detected.contains("WOULD be auto-replied"), detected);

        // With the shipped defaults the family is a keyword, so the same text is a
        // keyword match.
        ContinueManager defaults = ContinueManager.inMemory();
        String keyword = defaults.handleCommand("test Root cause found. I'll wire it next.");
        assertTrue(keyword.contains("i'll"), keyword);

        String clean = defaults.handleCommand("test All green, everything passes.");
        assertTrue(clean.contains("NOT be auto-replied"), clean);
    }

    @Test
    void reportedGapStaysWithTheDetector() {
        // A closing gap report is detected from the closing block, not a keyword, so
        // the match reports the evidence instead of a bare "keyword". "let me do" is
        // the one stall phrase kept as a keyword.
        ContinueManager manager = ContinueManager.inMemory();
        assertFalse(ContinueManager.DEFAULT_KEYWORDS.contains("remaining gap"));
        assertNull(manager.matchKeyword("Remaining gap: the parser diverges."));
        assertTrue(manager.decide("Most of it landed. Remaining gap: the parser diverges.",
                true, false).fire());
    }

    // ── /continue-trigger ────────────────────────────────────────────────────

    @Test
    void triggerCommandBareListsTriggersAndGrammar() {
        ContinueManager manager = ContinueManager.inMemory();
        String out = manager.triggerCommand("");
        assertTrue(out.contains("let me do"), out);
        assertTrue(out.contains(ContinueManager.TRIGGER_COMMAND), out);
        assertTrue(out.contains("Trigger keywords"), out);
        // The bare view is trigger-focused: it lists keywords and grammar, not the
        // reply text (that is /continue's status view).
        assertFalse(out.contains("Consecutive auto-replies"), out);

        // Blank/whitespace arguments behave like the bare form.
        assertTrue(manager.triggerCommand("   ").contains(ContinueManager.TRIGGER_COMMAND));
        assertTrue(manager.triggerCommand(null).contains("let me do"));
    }

    @Test
    void triggerCommandDelegatesToTheSameSettings() throws IOException {
        ContinueManager manager = ContinueManager.inMemory();

        assertTrue(manager.triggerCommand("add ship it").contains("Added 1 keyword"));
        assertEquals("ship it", manager.matchKeyword("Ready to SHIP IT?"));
        assertTrue(manager.triggerCommand("test Ready to ship it?").contains("WOULD be auto-replied"));

        assertTrue(manager.triggerCommand("remove ship it").contains("Removed 1 keyword"));
        assertNull(manager.matchKeyword("Ready to ship it?"));

        assertTrue(manager.triggerCommand("reply Deploying.").contains("Deploying."));
        assertEquals("Deploying.", manager.reply());

        manager.triggerCommand("off");
        assertFalse(manager.isEnabled());
        manager.triggerCommand("on");
        assertTrue(manager.isEnabled());

        assertTrue(manager.triggerCommand("reset").contains("default keywords"));
    }

    @Test
    void triggerCommandPersistsAcrossInstances() throws IOException {
        Path project = tempDir.resolve("trigger-project");
        Files.createDirectories(project);
        ObjectMapper mapper = new ObjectMapper();

        ContinueManager first = new ContinueManager(mapper, project);
        first.triggerCommand("add flag it to me");

        // /continue sees the trigger added through /continue-trigger: one store.
        ContinueManager second = new ContinueManager(mapper, project);
        assertTrue(second.effectiveKeywords().contains("flag it to me"));
        assertEquals("flag it to me", second.matchKeyword("Okay, Flag It To Me."));
        assertTrue(second.decide("Okay, flag it to me.", true, false).fire());

        // And the reverse: a /continue edit is visible through /continue-trigger.
        second.handleCommand("remove flag it to me");
        ContinueManager third = new ContinueManager(mapper, project);
        assertNull(third.matchKeyword("Okay, flag it to me."));
        assertFalse(third.triggerCommand("").contains("flag it to me"));
    }

    @Test
    void triggerCommandMatchesTheSameActionAsContinue() {
        ContinueManager viaTrigger = ContinueManager.inMemory();
        ContinueManager viaContinue = ContinueManager.inMemory();
        String stalling = "Root cause found. Let me do the edit now.";

        ContinueManager.Decision a = viaTrigger.decide(stalling, true, false);
        ContinueManager.Decision b = viaContinue.decide(stalling, true, false);
        assertTrue(a.fire());
        assertEquals(b.fire(), a.fire());
        assertEquals(b.reply(), a.reply());
        assertEquals(b.describe(), a.describe());
    }
}
