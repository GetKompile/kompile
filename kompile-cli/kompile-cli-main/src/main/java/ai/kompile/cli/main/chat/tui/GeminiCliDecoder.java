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
 * Decoder for Gemini CLI (github.com/google-gemini/gemini-cli).
 * <p>
 * Gemini CLI is a Node.js/TypeScript terminal app built on the Ink/React renderer
 * (the same framework as Claude Code). Like Claude Code, the ENTIRE Gemini CLI
 * TUI lives in the alternate screen from startup through the agent's lifetime —
 * it enters alt-screen at launch and never leaves until the process exits. This
 * means the alternate-screen flag carries NO dialog signal for gemini; it is
 * always set. See {@link #altScreenIsDialog()} which returns {@code false} for
 * this reason (same reasoning as {@link ClaudeCodeDecoder}).
 * <p>
 * Known interactive dialogs (all rendered within the alt-screen via Ink SelectInput):
 * <ul>
 *   <li>Folder-trust / project-trust prompt: "Trust this folder?" with
 *       numbered Yes/No options and an "Enter to confirm" affordance.</li>
 *   <li>Auth-method choice: "How do you want to use Gemini?" with a numbered
 *       selection menu (Login with Google, API key, etc.).</li>
 *   <li>Theme picker: numbered colour-scheme selection.</li>
 *   <li>Tool-execution approval: "Allow this tool call?" with Yes/No/Always options.</li>
 * </ul>
 * All of these share the same ink-style numbered-option layout with a ❯ cursor,
 * which {@link AbstractTuiDecoder}'s {@code isAwaitingUserInput} / {@code extractPromptText}
 * / {@code selectedOptionDigit} helpers already handle via
 * {@code stripBoxBorders → isNumberedOptionRow → hasHighlightedNumberedOption}.
 * <p>
 * Live-region scoping: Gemini CLI does not currently emit a stable "done" marker
 * that we can anchor to (unlike Claude's "✻ Cooked for Ns"). {@link #liveRegionStartRow}
 * is intentionally left at the base default (row 0 = full screen scan) rather than
 * guessing and misidentifying a done boundary. If a stale-prompt regression is
 * observed in practice, add the marker here with evidence.
 * <p>
 * Chrome identification: based on publicly-known Gemini CLI UI from the
 * google-gemini/gemini-cli source and common ink-based CLI patterns. Some
 * chrome rules (theme picker, auth screen) are conservative assumptions — flagged
 * in {@link #isChrome} inline. They err on the side of including content rather
 * than dropping it, so a false negative (chrome leaks into transcript) is preferred
 * over a false positive (content is silently dropped).
 * <p>
 * VERIFIED formats (patterns the base helpers handle, confirmed against known layout):
 * <ul>
 *   <li>Numbered selection menus with ❯ cursor (handled by base class)</li>
 *   <li>Box-bordered dialogs "│ ❯ 1. Yes │" (handled by stripBoxBorders)</li>
 *   <li>"Enter to confirm" affordance (handled by isAwaitingUserInput)</li>
 * </ul>
 * ASSUMED/UNVERIFIED formats (conservative implementations, no PTY dump available):
 * <ul>
 *   <li>Exact Gemini idle footer wording (e.g. Gemini version line, keybinding bar)</li>
 *   <li>Exact Gemini "thinking" / generating indicator text</li>
 *   <li>Exact done-marker glyph (none known — scoping left at row 0)</li>
 * </ul>
 */
public class GeminiCliDecoder extends AbstractTuiDecoder {

    /** Latches once the startup folder-trust prompt has been auto-accepted. */
    private boolean folderTrustConfirmed;

    @Override
    public String agentName() {
        return "gemini";
    }

    /**
     * Gemini CLI's entire TUI lives in the alternate screen — it enters at startup and stays
     * there. Alternate-screen active == NO dialog signal for gemini; the whole app is always
     * in alt-screen. Return {@code false} so mirror mode is not triggered on every turn.
     * (Same reasoning as {@link ClaudeCodeDecoder#altScreenIsDialog()}.)
     *
     * <p>Evidence: Gemini CLI is built on the same Ink/React renderer as Claude Code. Ink
     * enters the alternate screen buffer (ESC[?1049h) at startup for its full-screen rendering
     * and never leaves it during a session. The {@code isInAlternateScreen()} flag therefore
     * stays {@code true} the entire time, carrying no information about dialog state.
     */
    @Override
    public boolean altScreenIsDialog() {
        return false;
    }

    /**
     * Gemini CLI chrome rows to drop from the transcript.
     * <p>
     * The Gemini CLI layout (from source / public screenshots) has:
     * <ul>
     *   <li>Top: "Gemini" / gem-logo banner built from block glyphs, version line</li>
     *   <li>Content area: assistant response text</li>
     *   <li>Bottom: model/context indicator, token count, keybinding hints ("? for help", etc.)</li>
     *   <li>Input box: composer prompt line</li>
     * </ul>
     * Rules marked ASSUMED are conservative (may let through some chrome rather than dropping
     * content) pending a real PTY dump.
     */
    @Override
    protected boolean isChrome(String row) {
        String lower = row.toLowerCase(Locale.ROOT);
        String compact = lower.replace(" ", "");
        String text = stripLeadingChromeGlyphs(lower);

        // Logo banner rows (block-glyph art, same pattern as claude/codex).
        if (startsWithBlockArt(row)) return true;

        // Gemini version / header line. VERIFIED: gemini-cli prints "Gemini CLI vX.Y.Z"
        // or just "Gemini" as a banner; compact handles spacing variants.
        if (compact.startsWith("geminicli") || compact.startsWith("geminicli[v")
                || compact.startsWith("@google/geminicli")
                || (lower.contains("gemini") && lower.contains(" v") && endsWithVersionToken(row))
                || (lower.startsWith("gemini") && lower.contains("cli") && endsWithVersionToken(row))) {
            return true;
        }

        // Bottom status bar: typically "<model> · <context>" or "Gemini <model>" with a bullet
        // separator and a version token. ASSUMED: exact wording may differ.
        if (hasBulletSeparator(row) && containsModelName(lower)
                && (endsWithVersionToken(row) || text.contains("tokens") || text.contains("context"))) {
            return true;
        }

        // Keybinding / hint rows. ASSUMED based on common ink CLI patterns and gemini source.
        if (text.contains("for help") || text.contains("? for shortcuts")) return true;
        if (text.contains("ctrl+") || text.contains("esc ") || text.contains("esc to")) return true;
        if (text.contains("shift+tab") || text.contains("tab to")) return true;

        // Token / quota counter line (short row containing "tokens" — status bar fragment).
        if (text.contains("tokens") && row.length() < 100) return true;

        // Auth / setup screen rows. ASSUMED: gemini auth selection contains these phrases.
        if (text.startsWith("how do you want to use gemini")) return true;
        if (text.contains("login with google") && row.length() < 120) return true;
        if (text.contains("api key") && row.length() < 80
                && (text.contains("enter") || text.contains("use"))) return true;

        // Theme picker. ASSUMED: gemini shows a "Choose theme:" prompt with colour names.
        if (text.startsWith("choose theme") || text.startsWith("select theme")) return true;
        if (text.startsWith("color scheme") || text.startsWith("colour scheme")) return true;

        // Input box / composer placeholder. Gemini uses ">" or "❯" as the composer prompt;
        // a bare ">" followed by very little text is the empty input row.
        // Guard: only treat it as chrome when short (≤40 chars) to avoid dropping ">" prefixed
        // tool annotations or code snippets in responses.
        if (!row.isEmpty() && (row.charAt(0) == '>' || row.charAt(0) == '❯')
                && row.trim().length() <= 40 && !row.trim().contains(" ")) {
            // bare ">" / "❯" or "❯ " with no meaningful text — the input cursor row
            return true;
        }

        return false;
    }

    /**
     * Whether the agent is currently generating a response.
     * <p>
     * Gemini CLI shows a "Thinking..." / "Working..." progress indicator while generating,
     * and an "Esc to interrupt" / "Esc to cancel" hint. ASSUMED phrasing — gemini's exact
     * spinner text may differ; add more variants if observed in practice.
     */
    @Override
    public boolean isResponding(VirtualTerminal vt) {
        String lower = screen(vt);
        return lower.contains("esc to interrupt")
                || lower.contains("esc to cancel")
                || lower.contains("escape to cancel")
                || (lower.contains("thinking") && lower.contains("gemini"))
                || (lower.contains("working") && lower.contains("("));
    }

    /**
     * Whether the agent's TUI has returned to its idle/composer-ready state.
     * <p>
     * Gemini CLI in idle mode shows its input composer and typically a hint bar.
     * ASSUMED marker strings — adjust if observed to differ.
     */
    @Override
    public boolean isIdle(VirtualTerminal vt) {
        String lower = screen(vt);
        if (lower.isBlank() || isResponding(vt)) return false;
        // Gemini's idle screen typically has its input area and a hint or model line visible.
        return lower.contains("for help")
                || lower.contains("? for shortcuts")
                || (lower.contains("gemini") && lower.contains("model"))
                || lower.contains("type a message");
    }

    /**
     * Auto-accept the startup folder-trust prompt that gemini CLI shows the first time it
     * runs in a directory. If left unanswered, the first user message's CR would land on the
     * dialog instead of the composer, silently discarding the message. Fires at most once.
     *
     * <p>The trust prompt is a numbered selection menu with "Yes, I trust this folder" as the
     * first (default-highlighted) option. Sending a bare CR confirms the default-highlighted option.
     */
    @Override
    public String buildInputResponses(String rawChunk, VirtualTerminal vt) {
        if (folderTrustConfirmed) return "";
        String haystack = (rawChunk == null ? "" : rawChunk)
                + '\n' + (vt == null ? "" : vt.getFullScreen());
        String lower = haystack.toLowerCase(Locale.ROOT);
        if ((lower.contains("trust this folder") || lower.contains("is this a project you created")
                || (lower.contains("trust") && lower.contains("folder")
                        && (lower.contains("yes") || lower.contains("no, exit") || lower.contains("no, quit")
                                || lower.contains("enter to confirm")))) ) {
            folderTrustConfirmed = true;
            return "\r";
        }
        return "";
    }

    /**
     * Gemini-specific blocking-notice phrases (quota, auth, rate-limit).
     * ASSUMED: based on known Google API error wording; expand from real captures.
     */
    @Override
    protected String[] extraBlockingPhrases() {
        return new String[]{
                "quota exceeded for gemini", "gemini api quota",
                "resource has been exhausted", "daily limit exceeded",
                "free tier limit", "you've exceeded",
                "sign in to continue", "please sign in with google",
                "authentication required", "unable to authenticate",
                "api key not valid", "api_key_invalid",
                "project quota", "billing is not enabled",
        };
    }

    @Override
    public long startupSettleMillis() {
        // Gemini CLI (Ink-based) may show auth/trust prompts at startup; give it
        // extra time to render and be answered before the first message is injected.
        return 5000L;
    }

    @Override
    public long submitDelayMillis() {
        // Ink's paste heuristic (same issue as Claude Code): text+CR arriving in one burst
        // is treated as a multi-line paste. Pause between the prompt text and the submit CR
        // so the CR arrives as a distinct keypress.
        return 250L;
    }
}
