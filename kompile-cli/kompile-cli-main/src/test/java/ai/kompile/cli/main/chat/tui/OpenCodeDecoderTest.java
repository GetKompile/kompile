package ai.kompile.cli.main.chat.tui;

import ai.kompile.cli.main.chat.tui.AgentTuiDecoder.TuiLineKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/*
 * NOTE ON LIVE CAPTURE: live opencode captures are not available on this machine
 * (opencode renders nothing in the managed lane due to provider balance/auth).
 * All tests are synthetic: we construct VirtualTerminal frames that faithfully
 * reproduce the known Bubble Tea screen layout (absolute-position cursor writes,
 * chrome at known rows, footer at last 3-4 rows) based on PTY dump analysis
 * v1.14.48 and the existing test fixtures. Dialog formats that cannot be confirmed
 * from existing fixtures are flagged inline.
 */

class OpenCodeDecoderTest {

    /**
     * Regression: "● Tip Create JSON theme files in .opencode/themes/ directory" was classified
     * as CONTENT (not CHROME) because isChrome only checked "tip:" not "tip " (space). This
     * caused the tip to leak into the transcript AND ".opencode" in the path could pair with
     * "mcp" in the status bar to trip isIdle() while the agent was actually responding.
     */
    @Test
    void tipLineSuppressedAsChrome() {
        OpenCodeDecoder decoder = new OpenCodeDecoder();

        // Simulate the real byte layout: response at row 8 (1-indexed), tip at row 21.
        // This mirrors the 45-row PTY layout observed in the pty-dump.bin.
        VirtualTerminal vt = new VirtualTerminal(45, 120);
        // Response (the content we want)
        vt.feed("\033[8;6HBANANA");
        // Tip line at row 21 (0-indexed = row 20) with the ● prefix and path containing ".opencode"
        vt.feed("\033[21;32H●\033[21;33H Tip Create JSON theme files in .opencode/themes/ directory");
        // Status bar that contains "mcp" (the false-idle trigger when paired with ".opencode")
        vt.feed("\033[24;30H1 MCP /status");
        // Keybinding chrome rows that confirm idle state
        vt.feed("\033[25;1Htab agents   ctrl+p commands");
        vt.feed("\033[26;1HAsk anything");

        // Tip is CHROME, not content
        assertEquals(TuiLineKind.CHROME, decoder.classify(
                "● Tip Create JSON theme files in .opencode/themes/ directory"),
                "● Tip line must be CHROME, not CONTENT");

        // extractContent must contain BANANA but NOT the tip text
        String content = decoder.extractContent(vt);
        assertTrue(content.contains("BANANA"), "response text must be extracted: " + content);
        assertFalse(content.contains("Tip"), "● Tip chrome must not leak into transcript: " + content);
        assertFalse(content.contains(".opencode"), "tip path must not leak: " + content);

        // isIdle must work correctly: "ask anything" + "tab agents" both visible
        assertTrue(decoder.isIdle(vt), "screen with 'ask anything' must be detected as idle");
    }

    @Test
    void bulletPrefixedTipVariantsAreSuppressed() {
        OpenCodeDecoder decoder = new OpenCodeDecoder();
        // Different bullet variants for the tip line
        assertEquals(TuiLineKind.CHROME, decoder.classify("● Tip Use {file:path} syntax to reference files"),
                "● Tip with space is CHROME");
        assertEquals(TuiLineKind.CHROME, decoder.classify("• Tip Press ctrl+p for commands"),
                "• Tip with space is CHROME");
        // "tip:" colon variant (already handled)
        assertEquals(TuiLineKind.CHROME, decoder.classify("tip: use ctrl+p for the commands panel"),
                "tip: colon variant is CHROME");
        // Plain prose starting with "tip" (no bullet prefix) must NOT be suppressed
        assertEquals(TuiLineKind.CONTENT, decoder.classify("tip the balance toward conciseness"),
                "plain prose 'tip ...' (no ●/• bullet) must remain CONTENT");
        assertEquals(TuiLineKind.CONTENT, decoder.classify("Here is a tip for you to remember"),
                "prose containing 'tip' in the middle must remain CONTENT");
    }

    @Test
    void ownsRenderingAndExtractsAssistantTextFromOpenCodeScreen() {
        OpenCodeDecoder decoder = new OpenCodeDecoder();
        VirtualTerminal vt = new VirtualTerminal(30, 100);

        vt.feed("\033[?1049h\033[2J");
        vt.feed("\033[1;1HOpenCode Zen");
        vt.feed("\033[3;1HHere are the MCP tools available:");
        vt.feed("\033[4;1H- read: read files from the workspace");
        vt.feed("\033[22;1H□ Build · DeepSeek V4 Flash Free · 4.9s");
        vt.feed("\033[23;1H□ Build · DeepSeek V4 Flash Fagen OpenCode Zen · max");
        vt.feed("\033[24;1Hesc interrupt");
        vt.feed("\033[25;1Htab agents   ctrl+p commands");
        // Bottom status bar: "<cwd>      <version>" — must not leak as content.
        vt.feed("\033[29;1H  /tmp/kompile-sample-project                                  1.14.48");

        assertFalse(decoder.renderRawTui());
        assertTrue(decoder.isResponding(vt));
        assertFalse(decoder.isIdle(vt));

        String content = decoder.extractContent(vt);
        assertTrue(content.contains("Here are the MCP tools available:"));
        assertTrue(content.contains("- read: read files from the workspace"));
        assertFalse(content.contains("OpenCode"));
        assertFalse(content.contains("Build · DeepSeek"));
        assertFalse(content.contains("esc interrupt"));
        assertFalse(content.contains("ctrl+p commands"));
        assertFalse(content.contains("1.14.48"), "status bar leaked: " + content);
        assertFalse(content.contains("kompile-sample-project"), "status bar path leaked: " + content);

        vt.feed("\033[24;1HAsk anything                                      ");
        vt.feed("\033[25;1Htab agents   ctrl+p commands             ");

        assertFalse(decoder.isResponding(vt));
        assertTrue(decoder.isIdle(vt));
    }

    // ── FIX: altScreenIsDialog → false for OpenCode ─────────────────────────────

    /**
     * OpenCode's whole TUI lives in the alternate screen (Bubble Tea enters it at startup
     * via ESC[?1049h and never leaves until exit). altScreenIsDialog() must return false
     * so that "isInAlternateScreen() == true" (which is ALWAYS true for OpenCode) does not
     * trigger full-screen mirror mode on every response.
     */
    @Test
    void altScreenIsDialogFalse_openCodeLivesInAltScreen() {
        OpenCodeDecoder decoder = new OpenCodeDecoder();
        assertFalse(decoder.altScreenIsDialog(),
                "OpenCode's TUI lives entirely in the alternate screen; altScreenIsDialog must be false");

        // Verify via the factory method used at runtime.
        AgentTuiDecoder via_factory = AgentTuiDecoder.forAgent("opencode");
        assertFalse(via_factory.altScreenIsDialog(),
                "Factory-created OpenCodeDecoder must also return altScreenIsDialog()=false");
    }

    // ── isAwaitingUserInput: numbered selection menu (❯-cursor) ─────────────────

    /**
     * A numbered selection menu (agent picker, model picker) in OpenCode uses the
     * same ❯-cursor-on-number format as claude's menus. The base class detects it;
     * verify OpenCode surfaces it correctly via isAwaitingUserInput.
     * <p>
     * Layout approximated from OpenCode's Bubble Tea picker (format confirmed from
     * the base-class affordance detection; exact dialog text unconfirmed from live
     * capture — flagged per task scope).
     */
    @Test
    void isAwaitingUserInput_numberedSelectionMenu_returnsTrue() {
        OpenCodeDecoder decoder = new OpenCodeDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 80);

        // No "esc interrupt" → isResponding=false
        // A ❯-cursor on option 1 and a visible option 2 → selectionMenu fires
        vt.feed("\033[5;1H❯ 1. deepseek-v4-pro");
        vt.feed("\033[6;1H  2. deepseek-v4-flash");
        vt.feed("\033[7;1H  3. gpt-4o-mini");
        // Idle footer — confirms not responding
        vt.feed("\033[18;1Henter send   shift+tab agents");
        vt.feed("\033[19;1HAsk anything");

        assertFalse(decoder.isResponding(vt), "No esc interrupt → should not be responding");
        assertTrue(decoder.isAwaitingUserInput(vt),
                "A ❯-cursor numbered menu should fire isAwaitingUserInput");
    }

    /**
     * extractPromptText must return the question and options from an OpenCode numbered menu.
     */
    @Test
    void extractPromptText_numberedMenu_returnsQuestionAndOptions() {
        OpenCodeDecoder decoder = new OpenCodeDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 80);

        vt.feed("\033[4;1HSelect an agent:");
        vt.feed("\033[5;1H❯ 1. code");
        vt.feed("\033[6;1H  2. research");
        vt.feed("\033[7;1H  3. architect");
        vt.feed("\033[18;1Henter send   shift+tab agents");

        String prompt = decoder.extractPromptText(vt);
        assertFalse(prompt.isBlank(), "extractPromptText should return non-blank for a numbered menu");
        assertTrue(prompt.contains("Select an agent:") || prompt.contains("1."),
                "Prompt text should contain the question or options, got: " + prompt);
    }

    /**
     * selectedOptionDigit must return the highlighted option number for an OpenCode menu.
     */
    @Test
    void selectedOptionDigit_numberedMenu_returnsHighlightedNumber() {
        OpenCodeDecoder decoder = new OpenCodeDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 80);

        vt.feed("\033[5;1H  1. code");
        vt.feed("\033[6;1H❯ 2. research");
        vt.feed("\033[7;1H  3. architect");

        String digit = decoder.selectedOptionDigit(vt);
        assertEquals("2", digit,
                "selectedOptionDigit should return '2' when ❯ is on option 2");
    }

    // ── isAwaitingUserInput: NOT while responding (esc interrupt guard) ──────────

    /**
     * isAwaitingUserInput must return false while OpenCode shows its "esc interrupt"
     * generating banner — even if the screen happens to contain other text that could
     * look like a prompt. The base-class exclusion checks "esc to interrupt"; OpenCode
     * uses "esc interrupt" (no "to"), so OpenCodeDecoder must supply the guard itself.
     */
    @Test
    void isAwaitingUserInput_whileResponding_returnsFalse() {
        OpenCodeDecoder decoder = new OpenCodeDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 80);

        // Responding — "esc interrupt" banner is up
        vt.feed("\033[18;1Hesc interrupt");
        // Rows that look like a question — must NOT fire while responding
        vt.feed("\033[5;1HDo you want to proceed?");
        vt.feed("\033[6;1H❯ 1. Yes");
        vt.feed("\033[7;1H  2. No");

        assertTrue(decoder.isResponding(vt), "esc interrupt should make isResponding=true");
        assertFalse(decoder.isAwaitingUserInput(vt),
                "isAwaitingUserInput must be false while OpenCode is generating (esc interrupt)");
    }

    // ── Live-region scoping: stale question guard ────────────────────────────────

    /**
     * OpenCode uses Bubble Tea's full-screen repaint model: when a turn finishes, the
     * TUI redraws the entire viewport. Consequently, a "do you want to X?" question
     * that appeared during a previous turn is NOT retained on screen across turns —
     * OpenCode clears and redraws the full screen between turns.
     * <p>
     * What CAN linger is a question that appeared in the SAME ongoing turn's output
     * and has now been answered (i.e., is visible in scroll-back above new content).
     * The base-class {@code liveRegionStartRow} returns 0 (full screen scan) for
     * OpenCode, which is correct: the only stale-detection guard needed is
     * {@link #isAwaitingUserInput} returning false when {@link #isResponding} is true
     * (which {@link OpenCodeDecoder#isAwaitingUserInput} provides).
     * <p>
     * This test verifies that the isIdle state (ask anything / tab agents on screen)
     * is correctly NOT confused with an active dialog just because old question text
     * appears above the idle footer — which OpenCode clears on its next full repaint.
     */
    @Test
    void isAwaitingUserInput_idleStateWithNoActiveDialog_returnsFalse() {
        OpenCodeDecoder decoder = new OpenCodeDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 80);

        // Idle screen: the agent has redrawn after completing the turn.
        // An idle OpenCode screen shows its placeholder and footer, not old prompts.
        vt.feed("\033[5;1HHere is the summary of what was done.");
        vt.feed("\033[18;1HAsk anything");
        vt.feed("\033[19;1Htab agents   ctrl+p commands");

        assertFalse(decoder.isResponding(vt), "Idle footer → not responding");
        assertTrue(decoder.isIdle(vt), "ask anything + tab agents → idle");
        assertFalse(decoder.isAwaitingUserInput(vt),
                "An idle OpenCode screen (ask anything) with only prose must not trigger isAwaitingUserInput");
    }

    // ── y/n confirmation affordance ──────────────────────────────────────────────

    /**
     * OpenCode may show a (y/n) inline confirmation. The base-class affordance
     * already detects "(y/n)" in the live region; verify it works for OpenCode.
     * <p>
     * Note: the exact wording of OpenCode's (y/n) prompts is not confirmed from
     * live capture (provider auth unavailable). This test uses a synthetic screen
     * that matches the base-class affordance pattern "(y/n)".
     */
    @Test
    void isAwaitingUserInput_ynConfirmation_returnsTrue() {
        OpenCodeDecoder decoder = new OpenCodeDecoder();
        VirtualTerminal vt = new VirtualTerminal(20, 80);

        // Not responding (no esc interrupt)
        vt.feed("\033[5;1HApply this change? (y/n)");
        // No enter-send footer, so liveRegionStartRow=0 (full screen)

        assertFalse(decoder.isResponding(vt));
        assertTrue(decoder.isAwaitingUserInput(vt),
                "(y/n) affordance must fire isAwaitingUserInput for OpenCode");
    }

    // ── altScreenIsDialog consistency across base and concrete class ─────────────

    /**
     * Consistency check: decoders whose whole TUI lives in the alternate screen return
     * false; generic/unknown agents inherit the AbstractTuiDecoder default (true).
     * <p>
     * Agents known to live entirely in the alt-screen (never leave until exit):
     *   - OpenCode (Bubble Tea, ESC[?1049h at startup)
     *   - Claude Code (returns false per ClaudeCodeDecoder.altScreenIsDialog())
     *   - Codex (ratatui/crossterm, ESC[?1049h at startup — documented in CodexDecoder)
     * Agents not verified / generic: Gemini CLI and the generic fallback inherit true.
     */
    @Test
    void altScreenIsDialog_consistencyAcrossDecoders() {
        // OpenCode: false — whole TUI in alt-screen (Bubble Tea)
        assertFalse(AgentTuiDecoder.forAgent("opencode").altScreenIsDialog(),
                "opencode must return altScreenIsDialog()=false");
        // Claude: false — whole TUI in alt-screen (established by ClaudeDecoderPromptFixTest)
        assertFalse(AgentTuiDecoder.forAgent("claude").altScreenIsDialog(),
                "claude must return altScreenIsDialog()=false");
        // Codex: false — whole TUI in alt-screen (ratatui/crossterm, documented in CodexDecoder)
        assertFalse(AgentTuiDecoder.forAgent("codex").altScreenIsDialog(),
                "codex must return altScreenIsDialog()=false (whole TUI in alt-screen)");
        // Unknown/generic inherits the AbstractTuiDecoder default (true).
        assertTrue(AgentTuiDecoder.forAgent("unknown-agent").altScreenIsDialog(),
                "unknown/generic agent must inherit altScreenIsDialog()=true");
    }
}
