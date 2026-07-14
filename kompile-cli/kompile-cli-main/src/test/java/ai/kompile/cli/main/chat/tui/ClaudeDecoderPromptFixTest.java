/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the FIX 1–5 prompt-rendering and dialog-detection bug fixes
 * (Claude Code v2.1.181, managed TUI lane).
 */
class ClaudeDecoderPromptFixTest {

    // ── FIX 2: box-bordered dialog — extractPromptText ─────────────────────────

    /**
     * Claude draws permission prompts in ╭─╮│╰╯ boxes.  extractPromptText must
     * strip those borders and return "Do you want to proceed?" as the question.
     */
    @Test
    void extractPromptText_borderedDialog_returnsQuestion() {
        ClaudeCodeDecoder decoder = new ClaudeCodeDecoder();
        VirtualTerminal vt = new VirtualTerminal(15, 60);

        // Simulate a claude-style box dialog
        vt.feed("\033[1;1H╭──────────────────────────────────────────────╮");
        vt.feed("\033[2;1H│ Do you want to proceed?                      │");
        vt.feed("\033[3;1H│ ❯ 1. Yes                                     │");
        vt.feed("\033[4;1H│   2. Yes, don't ask again                    │");
        vt.feed("\033[5;1H│   3. No (esc)                                │");
        vt.feed("\033[6;1H╰──────────────────────────────────────────────╯");

        String prompt = decoder.extractPromptText(vt);
        assertFalse(prompt.isBlank(), "extractPromptText should return non-blank text for a bordered dialog");
        assertTrue(prompt.contains("Do you want to proceed?"),
                "Should contain the question line, got: " + prompt);
    }

    // ── FIX 2: box-bordered dialog — selectedOptionDigit ───────────────────────

    /**
     * selectedOptionDigit must strip the leading "│ " so "│ ❯ 1. Yes │" yields "1".
     */
    @Test
    void selectedOptionDigit_borderedDialog_returnsHighlightedNumber() {
        ClaudeCodeDecoder decoder = new ClaudeCodeDecoder();
        VirtualTerminal vt = new VirtualTerminal(10, 60);

        vt.feed("\033[1;1H╭──────────────────────────────────────────────╮");
        vt.feed("\033[2;1H│ ❯ 1. Yes                                     │");
        vt.feed("\033[3;1H│   2. No                                      │");
        vt.feed("\033[4;1H╰──────────────────────────────────────────────╯");

        String digit = decoder.selectedOptionDigit(vt);
        assertEquals("1", digit, "selectedOptionDigit should return '1' for '│ ❯ 1. Yes │'");
    }

    // ── FIX 2: box-bordered dialog — hasHighlightedNumberedOption (via isAwaitingUserInput) ──

    /**
     * isAwaitingUserInput must detect a selection menu inside box borders.
     * The ❯ cursor on a numbered option inside "│ ❯ 1. Yes │" should count.
     */
    @Test
    void isAwaitingUserInput_borderedDialogWithMenu_returnsTrue() {
        ClaudeCodeDecoder decoder = new ClaudeCodeDecoder();
        VirtualTerminal vt = new VirtualTerminal(10, 60);

        // Idle footer ensures isResponding=false; bordered menu supplies the affordance
        vt.feed("\033[1;1H╭──────────────────────────────────────────────╮");
        vt.feed("\033[2;1H│ ❯ 1. Yes                                     │");
        vt.feed("\033[3;1H│   2. No                                      │");
        vt.feed("\033[4;1H╰──────────────────────────────────────────────╯");
        vt.feed("\033[9;1H? for shortcuts");

        assertTrue(decoder.isAwaitingUserInput(vt),
                "isAwaitingUserInput should be true when a bordered numbered menu is on screen");
    }

    // ── FIX 3: stale question above done-marker ─────────────────────────────────

    /**
     * An old question from a previous response ("do you want to X?") above a
     * "✻ Cooked for 1s" done-marker must NOT trigger isAwaitingUserInput.
     */
    @Test
    void isAwaitingUserInput_staleQuestionAboveDoneMarker_returnsFalse() {
        ClaudeCodeDecoder decoder = new ClaudeCodeDecoder();
        VirtualTerminal vt = new VirtualTerminal(15, 80);

        // Old question text above the done-marker
        vt.feed("\033[1;1HDo you want to configure this project now?");
        vt.feed("\033[2;1H  1. Yes");
        vt.feed("\033[3;1H  2. No");
        // Done-marker: "✻ Cooked for 1s" — ✻ is U+273B, in the Dingbats star/asterisk range
        vt.feed("\033[5;1H✻ Cooked for 1s");
        // New idle content below — no new question
        vt.feed("\033[7;1HHere is the summary of what I did.");
        vt.feed("\033[14;1H? for shortcuts");

        assertFalse(decoder.isAwaitingUserInput(vt),
                "Old question above a done-marker should not trigger isAwaitingUserInput");
    }

    // ── FIX 5: bypass-permissions footer → isIdle ───────────────────────────────

    /**
     * The bypass-permissions footer "⏵⏵ bypass permissions on (shift+tab to cycle) · ← for agents"
     * must make isIdle() return true, and must NOT interfere with isResponding().
     */
    @Test
    void isIdle_bypassPermissionsFooter_returnsTrue() {
        ClaudeCodeDecoder decoder = new ClaudeCodeDecoder();
        VirtualTerminal vt = new VirtualTerminal(10, 120);

        vt.feed("\033[1;1HHere is your answer.");
        // Bypass-permissions footer (no "esc to interrupt" so isResponding=false)
        vt.feed("\033[9;1H⏵⏵ bypass permissions on (shift+tab to cycle) · ← for agents");

        assertFalse(decoder.isResponding(vt), "Should not be responding with bypass footer");
        assertTrue(decoder.isIdle(vt), "isIdle should be true when bypass-permissions footer is visible");
    }

    // ── FIX 1: altScreenIsDialog ─────────────────────────────────────────────────

    /**
     * AbstractTuiDecoder.altScreenIsDialog() must return true by default (the GenericDecoder
     * keeps the default — unknown/unsupported agents treat alt-screen as a dialog signal).
     * Full-screen TUI decoders (claude, opencode, codex, gemini) all override it to false
     * because their entire TUI lives in the alternate screen.
     */
    @Test
    void altScreenIsDialog_defaultTrue_claudeFalse() {
        // The GenericDecoder (unknown agent) keeps the default true: alt-screen → dialog.
        AgentTuiDecoder generic = AgentTuiDecoder.forAgent("unknown-agent-xyz");
        assertTrue(generic.altScreenIsDialog(),
                "Unknown/generic decoders must return altScreenIsDialog()=true by default");

        // ClaudeCodeDecoder must override it to false.
        AgentTuiDecoder claude = AgentTuiDecoder.forAgent("claude");
        assertFalse(claude.altScreenIsDialog(),
                "ClaudeCodeDecoder must return altScreenIsDialog()=false");

        // OpenCodeDecoder also overrides it to false: Bubble Tea full-screen TUI.
        AgentTuiDecoder opencode = AgentTuiDecoder.forAgent("opencode");
        assertFalse(opencode.altScreenIsDialog(),
                "OpenCodeDecoder must return altScreenIsDialog()=false (Bubble Tea full-screen TUI)");
    }
}
