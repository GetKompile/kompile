package ai.kompile.cli.main.chat.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GeminiCliDecoder}.
 * <p>
 * All tests feed escape sequences into a {@link VirtualTerminal} and verify the
 * decoder's behaviour on the resulting synthetic screen, following the same style
 * as {@link ClaudeCodeDecoderTest} and {@link CodexDecoderTest}.
 * <p>
 * No live gemini process is launched. Formats for the known dialogs (folder-trust,
 * auth-method, tool-approval) are based on the Ink-framework numbered-option layout
 * that gemini-cli uses (same framework as claude-code). Where exact gemini wording
 * is unverified, the test comment says ASSUMED.
 */
class GeminiCliDecoderTest {

    // -----------------------------------------------------------------------
    // alt-screen determination
    // -----------------------------------------------------------------------

    /**
     * Gemini CLI's entire TUI lives in the alternate screen (same Ink architecture
     * as Claude Code). The alt-screen flag must NOT signal a dialog.
     */
    @Test
    void altScreenIsNotDialogBecauseWholeAppLivesInAltScreen() {
        GeminiCliDecoder decoder = new GeminiCliDecoder();
        assertFalse(decoder.altScreenIsDialog(),
                "Gemini CLI's full TUI lives in alt-screen; altScreenIsDialog must be false");
    }

    @Test
    void ownsRendering() {
        GeminiCliDecoder decoder = new GeminiCliDecoder();
        assertFalse(decoder.renderRawTui(),
                "Decoder-owned agents must return false so Kompile renders extracted text");
    }

    // -----------------------------------------------------------------------
    // Chrome filtering — content survives, banners/chrome dropped
    // -----------------------------------------------------------------------

    @Test
    void filtersGeminiBannerVersionAndKeybindingChrome() {
        GeminiCliDecoder decoder = new GeminiCliDecoder();
        VirtualTerminal vt = new VirtualTerminal(30, 120);

        // Block-art logo banner (same Ink pattern as claude)
        vt.feed("\033[1;1H▐▛███▜▌   Gemini CLI v1.2.3");
        vt.feed("\033[2;1H▝▜█████▛▘  gemini-2.0-flash");
        // Keybinding hint rows (ASSUMED wording)
        vt.feed("\033[3;1H  ? for help · ctrl+c to exit");
        vt.feed("\033[4;1H  esc to cancel · shift+tab to cycle");
        // Real content row
        vt.feed("\033[10;1HHere is the answer you requested.");

        assertFalse(decoder.renderRawTui());
        String content = decoder.extractContent(vt);

        assertTrue(content.contains("Here is the answer you requested."), content);
        assertFalse(content.contains("Gemini CLI v"), content);
        assertFalse(content.contains("? for help"), content);
        assertFalse(content.contains("esc to cancel"), content);
        assertFalse(content.contains("shift+tab"), content);
    }

    @Test
    void filtersTokenStatusBarButPreservesContent() {
        GeminiCliDecoder decoder = new GeminiCliDecoder();
        VirtualTerminal vt = new VirtualTerminal(30, 120);

        // Status bar fragment that includes a model name + "tokens" (ASSUMED)
        vt.feed("\033[28;1H  gemini-2.0-flash · 1024 tokens");
        // Content row
        vt.feed("\033[10;1HThe answer is 42.");

        String content = decoder.extractContent(vt);
        assertTrue(content.contains("The answer is 42."), content);
        assertFalse(content.contains("1024 tokens"), content);
    }

    // -----------------------------------------------------------------------
    // Folder-trust prompt: auto-accept once
    // -----------------------------------------------------------------------

    /**
     * The folder-trust prompt is auto-accepted by buildInputResponses (returning CR),
     * and must NOT surface as a user decision via isAwaitingUserInput.
     * ASSUMED prompt wording — gemini shows a "Trust this folder?" dialog on first run.
     */
    @Test
    void autoAcceptsFolderTrustOnceAndThenStops() {
        GeminiCliDecoder decoder = new GeminiCliDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 100);

        vt.feed("\033[5;1H  Trust this folder?");
        vt.feed("\033[7;1H❯ 1. Yes, I trust this folder");
        vt.feed("\033[8;1H  2. No, exit");
        vt.feed("\033[10;1HEnter to confirm");

        // First observation: must auto-accept with a bare CR.
        String resp = decoder.buildInputResponses(null, vt);
        assertEquals("\r", resp, "first trust prompt must return CR to accept the default option");

        // Latches: second call must not re-send.
        assertEquals("", decoder.buildInputResponses(null, vt),
                "trust prompt must be accepted at most once");
    }

    @Test
    void doesNotAutoAcceptWhenNoTrustPromptPresent() {
        GeminiCliDecoder decoder = new GeminiCliDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 100);
        vt.feed("\033[5;1HHere is a normal response that mentions folders and yes.");
        assertEquals("", decoder.buildInputResponses(null, vt),
                "must not fire on ordinary response text");
    }

    /**
     * The folder-trust prompt is auto-accepted; it must NOT also surface to the user via
     * isAwaitingUserInput, which would double-prompt them on a decision already resolved.
     */
    @Test
    void folderTrustPromptDoesNotSurfaceAsUserDecision() {
        GeminiCliDecoder decoder = new GeminiCliDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 100);

        vt.feed("\033[5;1H  Trust this folder?");
        vt.feed("\033[7;1H❯ 1. Yes, I trust this folder");
        vt.feed("\033[8;1H  2. No, exit");
        vt.feed("\033[10;1HEnter to confirm");

        assertFalse(decoder.isAwaitingUserInput(vt),
                "auto-accepted folder-trust prompt must not surface as a user decision");
    }

    // -----------------------------------------------------------------------
    // Tool-execution approval dialog
    // -----------------------------------------------------------------------

    /**
     * Tool-execution approval ("Allow this tool call?") is a user decision that must be
     * detected. ASSUMED wording — gemini shows a numbered menu with Yes/No/Always options.
     */
    @Test
    void detectsToolApprovalDialogAsAwaitingUserInput() {
        GeminiCliDecoder decoder = new GeminiCliDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 100);

        vt.feed("\033[5;1H  Allow this tool call?");
        vt.feed("\033[7;1H❯ 1. Yes, allow once");
        vt.feed("\033[8;1H  2. Yes, always allow");
        vt.feed("\033[9;1H  3. No, deny");
        vt.feed("\033[11;1HEnter to confirm");

        assertTrue(decoder.isAwaitingUserInput(vt),
                "tool-approval dialog must be detected as awaiting user input");
    }

    @Test
    void extractsToolApprovalPromptTextAndOptions() {
        GeminiCliDecoder decoder = new GeminiCliDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 100);

        vt.feed("\033[5;1H  Allow this tool call?");
        vt.feed("\033[7;1H❯ 1. Yes, allow once");
        vt.feed("\033[8;1H  2. Yes, always allow");
        vt.feed("\033[9;1H  3. No, deny");

        String prompt = decoder.extractPromptText(vt);
        assertTrue(prompt.contains("Allow this tool call?"), prompt);
        assertTrue(prompt.contains("1. Yes, allow once"), prompt);
        assertTrue(prompt.contains("2. Yes, always allow"), prompt);
        assertTrue(prompt.contains("3. No, deny"), prompt);
    }

    @Test
    void readsHighlightedOptionDigitFromToolApprovalMenu() {
        GeminiCliDecoder decoder = new GeminiCliDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 100);

        vt.feed("\033[7;1H  1. Yes, allow once");
        vt.feed("\033[8;1H❯ 2. Yes, always allow");
        vt.feed("\033[9;1H  3. No, deny");

        assertEquals("2", decoder.selectedOptionDigit(vt),
                "must read the ❯-highlighted option number");
    }

    // -----------------------------------------------------------------------
    // Auth-method choice dialog (ASSUMED)
    // -----------------------------------------------------------------------

    /**
     * Auth method selection dialog — gemini shows this on first run or when not authenticated.
     * ASSUMED wording and layout (numbered Ink SelectInput menu).
     */
    @Test
    void detectsAuthMethodChoiceDialogAsAwaitingUserInput() {
        GeminiCliDecoder decoder = new GeminiCliDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 100);

        vt.feed("\033[4;1H  How do you want to use Gemini?");
        vt.feed("\033[6;1H❯ 1. Login with Google");
        vt.feed("\033[7;1H  2. Use API Key");
        vt.feed("\033[8;1H  3. Exit");
        vt.feed("\033[10;1HEnter to confirm");

        assertTrue(decoder.isAwaitingUserInput(vt),
                "auth method selection must be detected as awaiting input");
    }

    @Test
    void extractsAuthMethodDialogTextAndOptions() {
        GeminiCliDecoder decoder = new GeminiCliDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 100);

        vt.feed("\033[4;1H  How do you want to use Gemini?");
        vt.feed("\033[6;1H❯ 1. Login with Google");
        vt.feed("\033[7;1H  2. Use API Key");

        String prompt = decoder.extractPromptText(vt);
        assertTrue(prompt.contains("1. Login with Google"), prompt);
        assertTrue(prompt.contains("2. Use API Key"), prompt);
    }

    // -----------------------------------------------------------------------
    // Box-bordered dialog parsing (Ink border variant)
    // -----------------------------------------------------------------------

    /**
     * Some Ink-based CLIs wrap their selection dialogs in a box with │ borders.
     * The AbstractTuiDecoder's stripBoxBorders helper must handle this for gemini too.
     */
    @Test
    void parsesBoxBorderedSelectionMenuCorrectly() {
        GeminiCliDecoder decoder = new GeminiCliDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 100);

        vt.feed("\033[4;1H  Proceed with this action?");
        vt.feed("\033[6;1H│ ❯ 1. Yes, proceed │");
        vt.feed("\033[7;1H│   2. No, cancel   │");
        vt.feed("\033[9;1HEnter to confirm");

        assertTrue(decoder.isAwaitingUserInput(vt),
                "box-bordered selection menu must be detected");

        String digit = decoder.selectedOptionDigit(vt);
        assertEquals("1", digit, "highlighted option inside box borders must be read");

        String prompt = decoder.extractPromptText(vt);
        assertTrue(prompt.contains("Proceed with this action?"), prompt);
        assertTrue(prompt.contains("1. Yes, proceed"), prompt);
        assertTrue(prompt.contains("2. No, cancel"), prompt);
    }

    // -----------------------------------------------------------------------
    // Stale-prompt non-redetection (live-region scoping)
    // -----------------------------------------------------------------------

    /**
     * After a turn completes, old question text above the response must NOT re-assert
     * "awaiting user input." For gemini, since no confident done-marker is known,
     * the full screen is scanned — BUT the base-class "is generating" guard and
     * the "trust this folder" auto-suppress filter still prevent the two startup
     * prompts from re-firing. For a general mid-turn question, once the menu rows
     * are gone from the screen (the agent rendered a response), detection must clear.
     */
    @Test
    void clearsAwaitingInputOnceMenuLeavesTheScreen() {
        GeminiCliDecoder decoder = new GeminiCliDecoder();

        // Screen with an active numbered menu → awaiting.
        VirtualTerminal withMenu = new VirtualTerminal(20, 90);
        withMenu.feed("\033[5;1HProceed?");
        withMenu.feed("\033[6;1H❯ 1. Yes");
        withMenu.feed("\033[7;1H  2. No");
        withMenu.feed("\033[9;1HEnter to confirm");
        assertTrue(decoder.isAwaitingUserInput(withMenu), "menu present → must be awaiting");

        // After the agent renders the response the menu rows are gone.
        VirtualTerminal afterResponse = new VirtualTerminal(20, 90);
        afterResponse.feed("\033[5;1HDone! I have proceeded with your request.");
        afterResponse.feed("\033[6;1HHere is a summary of what was done.");
        assertFalse(decoder.isAwaitingUserInput(afterResponse),
                "must clear once the menu is gone from the screen");
    }

    // -----------------------------------------------------------------------
    // Responding / idle state detection
    // -----------------------------------------------------------------------

    @Test
    void detectsRespondingWhenEscToInterruptVisible() {
        GeminiCliDecoder decoder = new GeminiCliDecoder();

        VirtualTerminal busy = new VirtualTerminal(20, 100);
        busy.feed("\033[18;1H  Working (4s)");
        busy.feed("\033[19;1H  Esc to interrupt");
        assertTrue(decoder.isResponding(busy), "esc to interrupt → isResponding must be true");
        assertFalse(decoder.isIdle(busy));
    }

    @Test
    void detectsIdleWhenComposerAndHintVisible() {
        GeminiCliDecoder decoder = new GeminiCliDecoder();

        // ASSUMED: gemini idle screen shows a hint row and model line.
        VirtualTerminal idle = new VirtualTerminal(20, 100);
        idle.feed("\033[18;1H  gemini-2.0-flash · model");
        idle.feed("\033[20;1H  ? for help");
        assertTrue(decoder.isIdle(idle), "hint row + model → isIdle must be true");
        assertFalse(decoder.isResponding(idle));
    }

    // -----------------------------------------------------------------------
    // Blocking-notice detection (quota / auth errors)
    // -----------------------------------------------------------------------

    @Test
    void detectsQuotaExceededBlockingNotice() {
        GeminiCliDecoder decoder = new GeminiCliDecoder();

        VirtualTerminal blocked = new VirtualTerminal(20, 100);
        blocked.feed("\033[10;1H  Resource has been exhausted. Try again tomorrow.");
        String notice = decoder.detectBlockingNotice(blocked);
        assertNotNull(notice, "quota-exhausted line must be detected as a blocking notice");
    }

    @Test
    void detectsBaseClassBlockingPhrases() {
        GeminiCliDecoder decoder = new GeminiCliDecoder();

        VirtualTerminal rateLimit = new VirtualTerminal(20, 100);
        rateLimit.feed("\033[10;1H  429 Too Many Requests — rate limit exceeded.");
        String notice = decoder.detectBlockingNotice(rateLimit);
        assertNotNull(notice, "rate-limit-exceeded line (base phrase) must be detected");
    }

    @Test
    void doesNotFireBlockingNoticeOnNormalResponse() {
        GeminiCliDecoder decoder = new GeminiCliDecoder();

        VirtualTerminal normal = new VirtualTerminal(20, 100);
        normal.feed("\033[10;1HHere is the answer you requested. It covers all the topics.");
        assertNull(decoder.detectBlockingNotice(normal),
                "normal response must not trigger a blocking notice");
    }

    // -----------------------------------------------------------------------
    // No numbered-menu → no extractPromptText / no awaiting
    // -----------------------------------------------------------------------

    @Test
    void noPromptDetectedOnNormalProseResponse() {
        GeminiCliDecoder decoder = new GeminiCliDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 100);
        vt.feed("\033[5;1HHere is a full-sentence response with no decision menu at all.");

        assertFalse(decoder.isAwaitingUserInput(vt),
                "prose response without a menu must not trigger awaiting");
        assertEquals("", decoder.extractPromptText(vt),
                "no numbered menu → extractPromptText must return empty");
        assertNull(decoder.selectedOptionDigit(vt),
                "no menu → selectedOptionDigit must return null");
    }

    // -----------------------------------------------------------------------
    // Agent name and factory wiring
    // -----------------------------------------------------------------------

    @Test
    void agentNameIsGemini() {
        assertEquals("gemini", new GeminiCliDecoder().agentName());
    }

    @Test
    void factoryReturnsGeminiDecoderForGeminiBinaryName() {
        AgentTuiDecoder decoder = AgentTuiDecoder.forAgent("gemini");
        assertInstanceOf(GeminiCliDecoder.class, decoder,
                "forAgent(\"gemini\") must return a GeminiCliDecoder");
    }

    @Test
    void factoryReturnsGeminiDecoderForGeminiCliName() {
        AgentTuiDecoder decoder = AgentTuiDecoder.forAgent("gemini-cli");
        assertInstanceOf(GeminiCliDecoder.class, decoder,
                "forAgent(\"gemini-cli\") must return a GeminiCliDecoder");
    }
}
