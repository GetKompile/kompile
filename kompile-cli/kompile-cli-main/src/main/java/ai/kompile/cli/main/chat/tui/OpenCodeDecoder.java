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

import java.util.Locale;

/**
 * Decoder for OpenCode CLI (github.com/opencode-ai/opencode).
 * <p>
 * Derived from PTY dump analysis (v1.14.48, 2026-06-18):
 * <ul>
 *   <li>Alternate screen, full-screen repaint with absolute cursor positioning</li>
 *   <li>Bottom-anchored chrome: status bar (cwd:branch ... version), input box
 *       with {@code ┃} borders, model bar ("Build · &lt;model&gt;"), keybinding hints,
 *       and a centered logo built from block glyphs</li>
 *   <li>Sends terminal queries: DSR(x3), Kitty(?u), OSC 10/11, DECRPM</li>
 *   <li>Bubble Tea framework: disables DECAWM (ESC[?7l) and enters the alternate
 *       screen at startup (ESC[?1049h). The ENTIRE TUI lives in the alternate screen
 *       — it never leaves it until exit. Transient pickers (agent/model selector)
 *       are drawn IN the same alternate screen, not in an additional nested one.</li>
 * </ul>
 */
public class OpenCodeDecoder extends AbstractTuiDecoder {

    @Override
    public String agentName() {
        return "opencode";
    }

    /**
     * OpenCode's entire TUI lives in the alternate screen — it enters it at startup
     * (ESC[?1049h via Bubble Tea) and stays there until exit. Consequently,
     * {@code isInAlternateScreen()} is always true during normal operation and
     * carries no signal that a picker or dialog is open. Return {@code false} to
     * prevent every detected prompt from triggering full-screen mirror mode.
     * <p>
     * Evidence: (1) The PTY dump shows {@code ESC[?1049h ESC[2J} as the very first
     * bytes; (2) VirtualTerminal's DECAWM comment explicitly names "opencode/bubbletea"
     * as disabling wrap via {@code ESC[?7l} to draw precise box borders — a property
     * of a persistent full-screen TUI, not a transient overlay; (3) the decoder test
     * feeds {@code \033[?1049h\033[2J} as the initialisation sequence.
     */
    @Override
    public boolean altScreenIsDialog() {
        return false;
    }

    @Override
    public boolean isResponding(VirtualTerminal vt) {
        String lower = screen(vt);
        return lower.contains("esc interrupt")
                || lower.contains("escape interrupt")
                || lower.contains("ctrl+c interrupt");
    }

    @Override
    public boolean isIdle(VirtualTerminal vt) {
        String lower = screen(vt);
        if (lower.isBlank() || isResponding(vt)) return false;
        return lower.contains("ask anything")
                || lower.contains("tab agents")
                || lower.contains("ctrl+p commands")
                || lower.contains("ctrl+p command")
                || (lower.contains("opencode") && lower.contains("mcp"));
    }

    /**
     * Override to add OpenCode-specific affordances and generation guard.
     * <p>
     * The base class excludes {@code "esc to interrupt"} as a generation marker, but
     * OpenCode uses {@code "esc interrupt"} (no "to"). Without this override the base
     * class would not suppress prompt detection while OpenCode is actively generating.
     * We gate on {@link #isResponding} which checks OpenCode's exact markers, then
     * delegate to the base-class logic for the actual affordance scan (numbered menus,
     * y/n, decision questions, trailing-question scoping).
     */
    @Override
    public boolean isAwaitingUserInput(VirtualTerminal vt) {
        // Never fire while OpenCode is actively generating — its "esc interrupt" banner
        // is not caught by the base-class "esc to interrupt" exclusion.
        if (isResponding(vt)) return false;
        return super.isAwaitingUserInput(vt);
    }

    @Override
    public long turnIdleMillis() {
        return 1400L;
    }

    @Override
    protected String[] extraBlockingPhrases() {
        // opencode/ZEN surfaces credit-balance and provider-auth failures.
        // deepseek surfaces rate limits as "too many requests" or "rate limit".
        return new String[]{
                "balance too low", "add credits", "top up", "402 payment required",
                "no active subscription", "provider returned an error",
                "failed to generate", "model not found", "connection refused",
                "api key invalid", "invalid api key", "unauthorized",
        };
    }

    @Override
    protected boolean isChrome(String row) {
        String lower = row.toLowerCase(Locale.ROOT);
        String compact = lower.replace(" ", "");
        String chromeText = stripLeadingChromeGlyphs(lower);

        // Status bar: "<cwd>[:branch]      <version>". Distinct from prose because
        // it ends in a version token and carries a path.
        if (endsWithVersionToken(row) && row.indexOf('/') >= 0) return true;

        if (compact.equals("opencode") || compact.equals("opencodezen") || lower.contains("opencode v")) return true;
        if (lower.contains("ask anything")) return true;
        if (lower.contains("esc interrupt") || lower.contains("escape interrupt")) return true;
        if (lower.contains("tab agents")) return true;
        if (lower.contains("ctrl+p commands") || lower.contains("ctrl+p command")) return true;
        if (lower.contains("ctrl+c") || lower.contains("ctrl+d")) return true;
        if (lower.contains("shift+tab") || lower.contains("enter send")) return true;
        if (lower.contains("mcp") && lower.contains("connected") && lower.length() < 80) return true;
        if (chromeText.startsWith("build ") && containsModelName(chromeText)) return true;
        if (chromeText.startsWith("tip:") || chromeText.contains(" for shortcuts")) return true;
        // "● Tip ..." / "• Tip ..." — opencode's tip/advice chrome shown in idle state
        // (e.g. "● Tip Create JSON theme files in .opencode/themes/ directory").
        // The ●/• bullet prefix is the discriminator: plain prose starting with "tip"
        // (e.g. "tip the balance") must NOT be filtered.  The .opencode/ path in the tip
        // also must not reach isIdle() where it would pair with "mcp" on the status bar
        // and spuriously fire the idle detector while the agent is still responding.
        if (lower.startsWith("● tip ") || lower.startsWith("• tip ")) return true;
        if (chromeText.startsWith("try \"") || chromeText.startsWith("try '") || chromeText.contains("try \"")) return true;
        if (lower.contains("what's new in my repo")) return true;
        if (lower.contains("how does opencode")) return true;

        return false;
    }

    @Override
    protected boolean isRawChromeRow(String rawRow, String normalizedRow) {
        // opencode renders its reasoning/thinking block inside a heavy-vertical (┃, U+2503)
        // gutter; the final answer is emitted WITHOUT that gutter. Drop ┃-prefixed rows so the
        // thinking frame never leaks into the transcript. (Backed by
        // DecoderFramebufferTest.decoderOpenCodeDropsHeavyFrameThinkingRows.)
        if (rawRow == null) return false;
        String trimmed = rawRow.stripLeading();
        return !trimmed.isEmpty() && trimmed.charAt(0) == '┃';
    }

    @Override
    public long startupSettleMillis() {
        return 7000L;
    }

    @Override
    public long submitDelayMillis() {
        return 500L;
    }
}
