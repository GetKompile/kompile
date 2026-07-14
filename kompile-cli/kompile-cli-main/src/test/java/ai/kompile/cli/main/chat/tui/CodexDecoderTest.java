package ai.kompile.cli.main.chat.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CodexDecoderTest {

    @Test
    void ownsRenderingAndExtractsOnlyBulletResponseFromCodexScreen() {
        CodexDecoder decoder = new CodexDecoder();
        VirtualTerminal vt = new VirtualTerminal(40, 120);

        // Welcome / update cards (box-drawing chrome) + composer + status bar.
        vt.feed("\033[1;1H╭─────────────────────────────────────────────────╮");
        vt.feed("\033[2;1H│ ✨ Update available! 0.137.0 -> 0.140.0         │");
        vt.feed("\033[3;1H│ Run npm install -g @openai/codex to update.     │");
        vt.feed("\033[4;1H╰─────────────────────────────────────────────────╯");
        vt.feed("\033[6;1H╭─────────────────────────────────────────────╮");
        vt.feed("\033[7;1H│ >_ OpenAI Codex (v0.137.0)                  │");
        vt.feed("\033[8;1H│ model:     gpt-5.5 xhigh   /model to change │");
        vt.feed("\033[9;1H│ directory: ~/Documents/GitHub/kompile       │");
        vt.feed("\033[10;1H╰─────────────────────────────────────────────╯");
        vt.feed("\033[12;1H  Tip: Use /mcp to list configured MCP tools.");
        vt.feed("\033[14;1H⚠ Heads up, you have less than 25% of your 5h limit left. Run /status for a breakdown.");
        // User echo + assistant response + composer placeholder + status bar.
        vt.feed("\033[18;1H› Reply with exactly: READY-SENTINEL");
        vt.feed("\033[21;1H• Here is the actual answer line.");
        vt.feed("\033[24;1H› Improve documentation in @filename");
        vt.feed("\033[26;1H  gpt-5.5 xhigh · ~/Documents/GitHub/kompile");

        assertFalse(decoder.renderRawTui());

        String content = decoder.extractContent(vt);
        // Only the assistant bullet line survives.
        assertTrue(content.contains("Here is the actual answer line."), content);
        // All chrome is filtered.
        assertFalse(content.contains("Update available"), content);
        assertFalse(content.contains("npm install"), content);
        assertFalse(content.contains("OpenAI Codex"), content);
        assertFalse(content.contains("model:"), content);
        assertFalse(content.contains("directory:"), content);
        assertFalse(content.contains("Tip:"), content);
        assertFalse(content.contains("Heads up"), content);
        assertFalse(content.contains("/status"), content);
        assertFalse(content.contains("Reply with exactly"), content);   // user echo (›)
        assertFalse(content.contains("Improve documentation"), content); // composer (›)
        assertFalse(content.contains("/model to change"), content);
        assertFalse(content.contains("gpt-5.5 xhigh ·"), content);  // status bar
    }

    @Test
    void filtersHookMenuAndDecisionPromptChromeFromScrollback() {
        CodexDecoder decoder = new CodexDecoder();
        VirtualTerminal vt = new VirtualTerminal(24, 100);

        vt.feed("\033[3;1H  2. No, quit");
        vt.feed("\033[4;1H    → type the option number, or ↑↓/←→/Tab to navigate, then Enter");
        vt.feed("\033[6;1H  Hooks");
        vt.feed("\033[7;1H  Lifecycle hooks from config and enabled plugins.");
        vt.feed("\033[8;1H  Event                 Installed   Active      Description");
        vt.feed("\033[9;1H  PreToolUse            1           1           Before a tool executes");
        vt.feed("\033[10;1H PermissionRequest     0           0           When permission is requested");
        vt.feed("\033[11;1H PreToolUse hooks");
        vt.feed("\033[12;1H Turn hooks on or off. Your changes are saved automatically.");
        vt.feed("\033[13;1H [x] Hook 1");
        vt.feed("\033[14;1H Event     PreToolUse");
        vt.feed("\033[18;1H• SCROLL-ANSWER");

        String content = decoder.extractContent(vt);

        assertTrue(content.contains("SCROLL-ANSWER"), content);
        assertFalse(content.contains("Hooks"), content);
        assertFalse(content.contains("PreToolUse"), content);
        assertFalse(content.contains("PermissionRequest"), content);
        assertFalse(content.contains("type the option number"), content);
        assertFalse(content.contains("No, quit"), content);
    }

    @Test
    void detectsRespondingAndIdleState() {
        CodexDecoder decoder = new CodexDecoder();

        VirtualTerminal idle = new VirtualTerminal(40, 120);
        idle.feed("\033[24;1H› Improve documentation in @filename");
        idle.feed("\033[26;1H  gpt-5.5 xhigh · ~/Documents/GitHub/kompile");
        assertTrue(decoder.isIdle(idle));
        assertFalse(decoder.isResponding(idle));

        VirtualTerminal busy = new VirtualTerminal(40, 120);
        busy.feed("\033[21;1H• Working on it...");
        busy.feed("\033[26;1H  Esc to interrupt");
        assertTrue(decoder.isResponding(busy));
        assertFalse(decoder.isIdle(busy));
    }

    @Test
    void autoAcceptsDirectoryTrustModalOnceThenStopsAnswering() {
        CodexDecoder decoder = new CodexDecoder();
        VirtualTerminal vt = new VirtualTerminal(40, 120);
        vt.feed("\033[10;1H  Do you trust the contents of this directory?");
        vt.feed("\033[12;1H  1. Yes, allow Codex to work in this folder");
        vt.feed("\033[13;1H  2. No, quit");

        // First observation of the modal → navigate to the top ("Yes") option and confirm.
        String resp = decoder.buildInputResponses(null, vt);
        assertEquals("\033[A\033[A\r", resp);

        // Latches: a second pass (or any later screen) must not re-send keys.
        assertEquals("", decoder.buildInputResponses(null, vt));
    }

    @Test
    void doesNotAnswerWhenNoTrustModalPresent() {
        CodexDecoder decoder = new CodexDecoder();
        VirtualTerminal vt = new VirtualTerminal(40, 120);
        // Ordinary response text that merely mentions the words must not trigger keystrokes.
        vt.feed("\033[21;1H• The directory layout looks fine; yes, proceed with the change.");
        assertEquals("", decoder.buildInputResponses(null, vt));
    }

    @Test
    void detectsUsageLimitBlockButIgnoresBenignQuotaHint() {
        CodexDecoder decoder = new CodexDecoder();

        VirtualTerminal blocked = new VirtualTerminal(40, 120);
        blocked.feed("\033[10;1H  You've hit your usage limit. Try again later.");
        String notice = decoder.detectBlockingNotice(blocked);
        assertNotNull(notice);
        assertTrue(notice.toLowerCase().contains("usage limit"), notice);

        // The benign "reset available" / "5h limit left" hints must NOT be treated as a block.
        VirtualTerminal hint = new VirtualTerminal(40, 120);
        hint.feed("\033[14;1H⚠ Heads up, you have less than 25% of your 5h limit left. Run /status.");
        hint.feed("\033[15;1H• You have 1 usage limit reset available. Run /usage to use one.");
        assertNull(decoder.detectBlockingNotice(hint));
    }

    @Test
    void detectsAwaitingUserInputOnPromptButNotDuringGenerationOrProse() {
        CodexDecoder d = new CodexDecoder();

        // A confirmation prompt with a selection menu + confirm affordance → awaiting input.
        VirtualTerminal prompt = new VirtualTerminal(20, 90);
        prompt.feed("\033[5;1HApply this change to config.toml?");
        prompt.feed("\033[7;1H❯ 1. Yes, apply");
        prompt.feed("\033[8;1H  2. No, skip");
        prompt.feed("\033[10;1HEnter to confirm · Esc to cancel");
        assertTrue(d.isAwaitingUserInput(prompt), "confirm prompt must be detected");

        // Same menu but the agent is still generating (esc to interrupt) → NOT awaiting.
        VirtualTerminal generating = new VirtualTerminal(20, 90);
        generating.feed("\033[5;1H❯ 1. Yes");
        generating.feed("\033[6;1H  2. No");
        generating.feed("\033[8;1H• Working (3s · esc to interrupt)");
        assertFalse(d.isAwaitingUserInput(generating), "must not fire while generating");

        // Ordinary prose response → NOT awaiting.
        VirtualTerminal prose = new VirtualTerminal(20, 90);
        prose.feed("\033[5;1HHere is the summary you asked for; it has two main parts.");
        assertFalse(d.isAwaitingUserInput(prose), "prose must not fire");

        // Startup folder-trust prompt is auto-accepted → must NOT surface as a user decision.
        VirtualTerminal trust = new VirtualTerminal(20, 90);
        trust.feed("\033[5;1HIs this a project you created or one you trust?");
        trust.feed("\033[7;1H❯ 1. Yes, I trust this folder");
        trust.feed("\033[8;1H  2. No, exit");
        trust.feed("\033[10;1HEnter to confirm · Esc to cancel");
        assertFalse(d.isAwaitingUserInput(trust), "auto-accepted trust prompt must not fire");
    }

    @Test
    void detectsSelectionMenuWithCursorOnAnyOptionAndClearsWhenGone() {
        // claude's /model highlights the CURRENT model (e.g. option 5 = Haiku), not option 1, and the
        // user arrows the cursor around. Detection must match the ❯ cursor on ANY numbered option, not
        // just "❯ 1." — otherwise the picker (and every mid-navigation state) is missed. This drives
        // the awaiting/mirror state INSTEAD of the alternate-screen flag, which claude never clears.
        CodexDecoder d = new CodexDecoder();

        VirtualTerminal onFive = new VirtualTerminal(20, 90);
        onFive.feed("\033[5;1HSelect model");
        onFive.feed("\033[6;1H  1. Default");
        onFive.feed("\033[7;1H  2. Opus");
        onFive.feed("\033[10;1H❯ 5. Haiku");
        assertTrue(d.isAwaitingUserInput(onFive), "cursor on option 5 must still detect the menu");

        // Once the picker closes and the agent shows normal output, it MUST go false again — the bug
        // was the alternate-screen flag sticking true and wedging every later turn at "awaiting input".
        VirtualTerminal closed = new VirtualTerminal(20, 90);
        closed.feed("\033[5;1H| Kept model as Haiku 4.5");
        closed.feed("\033[7;1H* Hello! How can I help you today?");
        assertFalse(d.isAwaitingUserInput(closed), "must clear once the menu is gone");
    }

    @Test
    void extractsPromptQuestionAndNumberedOptions() {
        CodexDecoder d = new CodexDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 90);
        vt.feed("\033[8;1HClaude has written up a plan. Would you like to proceed?");
        vt.feed("\033[10;1H❯ 1. Yes, and bypass permissions");
        vt.feed("\033[11;1H  2. Yes, manually approve edits");
        vt.feed("\033[12;1H  3. No, keep planning");

        String prompt = d.extractPromptText(vt);
        assertTrue(prompt.contains("Would you like to proceed?"), prompt);
        assertTrue(prompt.contains("1. Yes, and bypass permissions"), prompt);
        assertTrue(prompt.contains("2. Yes, manually approve edits"), prompt);
        assertTrue(prompt.contains("3. No, keep planning"), prompt);

        // No numbered menu on screen → empty.
        VirtualTerminal prose = new VirtualTerminal(20, 90);
        prose.feed("\033[8;1HHere is a normal response with no menu.");
        assertEquals("", d.extractPromptText(prose));
    }

    @Test
    void readsHighlightedOptionDigitForConfirm() {
        CodexDecoder d = new CodexDecoder();

        VirtualTerminal onTwo = new VirtualTerminal(20, 90);
        onTwo.feed("\033[8;1HWould you like to proceed?");
        onTwo.feed("\033[10;1H  1. Yes, and bypass permissions");
        onTwo.feed("\033[11;1H❯ 2. Yes, manually approve edits");
        onTwo.feed("\033[12;1H  3. No");
        assertEquals("2", d.selectedOptionDigit(onTwo), "must read the ❯-highlighted option number");

        VirtualTerminal onOne = new VirtualTerminal(20, 90);
        onOne.feed("\033[10;1H❯ 1. Yes");
        onOne.feed("\033[11;1H  2. No");
        assertEquals("1", d.selectedOptionDigit(onOne));

        // A pure arrow/tab dialog with no numbered options → null (caller sends a bare CR).
        VirtualTerminal noNumbers = new VirtualTerminal(20, 90);
        noNumbers.feed("\033[10;1H❯ Development");
        noNumbers.feed("\033[11;1H  Production");
        assertNull(d.selectedOptionDigit(noNumbers));
    }

    @Test
    void detectsAuthLoginBlock() {
        CodexDecoder decoder = new CodexDecoder();
        VirtualTerminal vt = new VirtualTerminal(40, 120);
        vt.feed("\033[10;1H  Not authenticated. To continue, run codex login.");
        assertNotNull(decoder.detectBlockingNotice(vt));
    }

    @Test
    void detectsBlockAfterItScrollsOutOfViewportViaHistory() {
        // Regression: codex renders its quota error, then redraws its idle composer — the error
        // scrolls out of the fixed VT viewport. Detection must still find it via the accumulated
        // decoder history, not only the live screen.
        CodexDecoder decoder = new CodexDecoder();
        VirtualTerminal vt = new VirtualTerminal(24, 120);

        // Frame 1: the quota error is on screen; observe() folds it into history.
        vt.feed("\033[6;1H■ You've hit your usage limit. Purchase more credits to continue.");
        decoder.observe(vt);

        // Frame 2: codex repaints the idle composer; the error is no longer on the live screen.
        vt.feed("\033[2J\033[6;1H› Summarize recent commits");
        vt.feed("\033[8;1H  gpt-5.5 xhigh · /tmp/x");
        decoder.observe(vt);
        assertNull(scanScreenOnly(vt), "precondition: error is gone from the live screen");

        String notice = decoder.detectBlockingNotice(vt);
        assertNotNull(notice, "block must still be detected from history after it scrolled away");
        assertTrue(notice.toLowerCase().contains("usage limit"), notice);
    }

    /** Helper mirroring the old screen-only scan, to prove the error left the live viewport. */
    private static String scanScreenOnly(VirtualTerminal vt) {
        String screen = vt.getFullScreen().toLowerCase();
        return screen.contains("usage limit") ? "found" : null;
    }

    @Test
    void detectsCodexRealUsageLimitWording() {
        CodexDecoder decoder = new CodexDecoder();
        // Codex’s actual out-of-quota line, captured live (curly apostrophe in "You’ve").
        // Match must survive the apostrophe via the "hit your usage limit" substring.
        VirtualTerminal curly = new VirtualTerminal(40, 120);
        curly.feed("\033[10;1H■ You’ve hit your usage limit. Visit https://chatgpt.com/codex/settings/usage "
                + "to purchase more credits or try again at Jul 7th.");
        String notice = decoder.detectBlockingNotice(curly);
        assertNotNull(notice, "codex real quota wording must be detected");
        assertTrue(notice.toLowerCase().contains("usage limit"), notice);

        // Straight-apostrophe variant too.
        VirtualTerminal straight = new VirtualTerminal(40, 120);
        straight.feed("\033[10;1H■ You’ve hit your usage limit. Purchase more credits.");
        assertNotNull(decoder.detectBlockingNotice(straight));
    }

    // ------------------------------------------------------------------
    // altScreenIsDialog — Prompt rendering correctness tests
    // ------------------------------------------------------------------

    /**
     * Codex’s entire TUI lives in the alternate screen (it enters ESC[?1049h at startup and never
     * exits it). altScreenIsDialog() must return false so that alternate-screen presence alone does
     * NOT trigger full-screen mirror mode on every turn — mirrors are only activated by
     * isAwaitingUserInput when a real decision prompt is actually on screen.
     */
    @Test
    void altScreenIsDialogReturnsFalseForCodex() {
        CodexDecoder decoder = new CodexDecoder();
        assertFalse(decoder.altScreenIsDialog(),
                "Codex is a ratatui full-screen TUI (always in alt-screen); " +
                "altScreenIsDialog must be false so it does not mirror every turn");
    }

    /**
     * A stale answered prompt ABOVE a completed assistant bullet must not re-fire
     * isAwaitingUserInput after the response is committed. This verifies liveRegionStartRow
     * scopes detection to at/below the last ‘•’ bullet so the previous question is outside
     * the live region.
     *
     * Layout (codex viewport after turn completes):
     *   row 3:  Apply this change?          ← stale answered question
     *   row 5:  ❯ 1. Yes, apply             ← stale selection (already answered)
     *   row 6:    2. No, skip
     *   row 10: • Here is what I changed.   ← completed response (last bullet)
     *   row 12: › Next question              ← composer (idle)
     *   row 14:   gpt-5.5 xhigh · ~/proj   ← status bar
     */
    @Test
    void stalePromptAboveCompletedResponseDoesNotReassertAwaitingInput() {
        CodexDecoder d = new CodexDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 90);

        // Stale question + menu from a PREVIOUSLY answered turn (rows 3-6).
        vt.feed("\033[3;1HApply this change?");
        vt.feed("\033[5;1H❯ 1. Yes, apply");
        vt.feed("\033[6;1H  2. No, skip");
        // The completed response bullet pins below the stale prompt (row 10).
        vt.feed("\033[10;1H• Here is what I changed.");
        // Idle composer and status bar below the response.
        vt.feed("\033[12;1H› Next question");
        vt.feed("\033[14;1H  gpt-5.5 xhigh · ~/proj");

        assertFalse(d.isAwaitingUserInput(vt),
                "Stale prompt above the last ‘•’ bullet must not re-assert awaiting-input " +
                "after the response is committed");
    }

    /**
     * A live prompt appearing AFTER the last assistant bullet IS detected correctly —
     * verifying that scoping to the bullet row doesn’t break legitimate mid-turn prompts.
     *
     * Layout (codex viewport mid-turn, agent blocked on input):
     *   row 5:  • I’ve analysed the code.  ← previous response bullet
     *   row 8:  Apply the patch?            ← NEW prompt below the bullet
     *   row 9:  ❯ 1. Yes
     *   row 10:   2. No
     *   row 12: Enter to confirm
     */
    @Test
    void livePromptBelowLastBulletIsStillDetected() {
        CodexDecoder d = new CodexDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 90);

        vt.feed("\033[5;1H• I’ve analysed the code.");
        vt.feed("\033[8;1HApply the patch?");
        vt.feed("\033[9;1H❯ 1. Yes");
        vt.feed("\033[10;1H  2. No");
        vt.feed("\033[12;1HEnter to confirm");

        assertTrue(d.isAwaitingUserInput(vt),
                "A prompt appearing AFTER the last bullet must still be detected");
    }

    /**
     * A prompt screen that includes "Esc to cancel" (e.g. "Enter to confirm · Esc to cancel")
     * must NOT cause isResponding() to return true, which would block isAwaitingUserInput
     * from detecting the prompt via the trailingQuestion path.
     */
    @Test
    void escToCancelInPromptFooterDoesNotTriggerIsResponding() {
        CodexDecoder d = new CodexDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 90);

        vt.feed("\033[5;1HApply this change?");
        vt.feed("\033[7;1H❯ 1. Yes, apply");
        vt.feed("\033[8;1H  2. No, skip");
        vt.feed("\033[10;1HEnter to confirm · Esc to cancel");

        assertFalse(d.isResponding(vt),
                "’Esc to cancel’ in a prompt footer must NOT trigger isResponding — " +
                "it would incorrectly block prompt detection via trailingQuestion");
        assertTrue(d.isAwaitingUserInput(vt),
                "Confirm prompt with ‘Esc to cancel’ footer must be detected as awaiting input");
    }
}
