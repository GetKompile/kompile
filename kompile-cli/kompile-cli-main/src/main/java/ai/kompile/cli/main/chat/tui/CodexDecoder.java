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
 * Decoder for OpenAI Codex CLI (github.com/openai/codex).
 * <p>
 * From PTY dump analysis (v0.137.0, 2026-06-18) — a ratatui/crossterm TUI:
 * <ul>
 *   <li>Box-drawing frames ({@code ╭─╮ │ │ ╰─╯}) for the update notice and the
 *       welcome card (">_ OpenAI Codex (vX.Y.Z)", "model: ...", "directory: ...")</li>
 *   <li>Assistant responses are prefixed with a bullet: {@code • &lt;text&gt;}</li>
 *   <li>User messages and the composer placeholder are prefixed with {@code › }
 *       (U+203A), e.g. {@code › Improve documentation in @filename}</li>
 *   <li>Status bar: {@code &lt;model&gt; &lt;effort&gt; · &lt;cwd&gt;}</li>
 *   <li>Sends DSR / DA1 / DA2 / Kitty(?u) / XTVERSION / OSC 10-11 queries and
 *       waits for replies before rendering — all answered by the base decoder.</li>
 * </ul>
 */
public class CodexDecoder extends AbstractTuiDecoder {

    private static final char COMPOSER_ARROW = '›'; // U+203A

    /** Latches once the startup directory-trust modal has been auto-accepted. */
    private boolean directoryTrustConfirmed;

    @Override
    public String agentName() {
        return "codex";
    }

    /**
     * Codex opens on a directory-trust modal ("Do you trust the contents of this directory?")
     * before it will accept any input. Unlike Claude, it has no flag to skip it — the interactive
     * bypass flag only covers command approvals. Left unanswered, the first user message's submit
     * CR lands on the modal and (because the trust option is not the default highlight) quits the
     * agent. Auto-accept it: arrow up to the first option ("Yes, allow …", always topmost) — extra
     * Ups clamp harmlessly at the boundary — then Enter to confirm. Fires exactly once.
     */
    @Override
    public String buildInputResponses(String rawChunk, VirtualTerminal vt) {
        if (directoryTrustConfirmed) return "";
        String haystack = (rawChunk == null ? "" : rawChunk)
                + '\n' + (vt == null ? "" : vt.getFullScreen());
        String lower = haystack.toLowerCase(Locale.ROOT);
        // Anchor on the exact prompt question so this can never fire on ordinary response text
        // in an already-trusted directory (where no modal appears).
        if ((lower.contains("do you trust") || lower.contains("trust the contents"))
                && (lower.contains("yes") || lower.contains("quit") || lower.contains("proceed"))) {
            directoryTrustConfirmed = true;
            return "\033[A\033[A\r"; // Up, Up (clamp to topmost "Yes" option), Enter
        }
        return "";
    }

    @Override
    protected boolean isChrome(String row) {
        String trimmed = row.strip();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        String text = stripLeadingChromeGlyphs(lower);

        // Composer placeholder and user-message echo are arrow-prefixed.
        if (!trimmed.isEmpty() && trimmed.charAt(0) == COMPOSER_ARROW) return true;

        // Status bar: "<model> <effort> · <cwd>" — robust to a truncated/absent path
        // by also recognising the effort token that always sits next to the model.
        if (hasBulletSeparator(row) && containsModelName(lower)
                && (row.indexOf('/') >= 0 || containsEffortWord(lower))) return true;

        // Welcome / update cards.
        if (text.startsWith("openai codex") || text.contains("openai codex (")) return true;
        if (text.startsWith("model:") || text.startsWith("directory:")) return true;
        if (text.contains("/model to change")) return true;
        if (text.contains("update available")) return true;
        if (text.contains("npm install") || text.contains("to update.")) return true;
        if (text.contains("release notes")) return true;
        if (lower.contains("openai/codex")) return true;            // update URL
        if (text.startsWith("tip:")) return true;
        if (text.startsWith("heads up,") && text.contains("limit")) return true;
        if (text.contains("run /status") && text.contains("breakdown")) return true;
        if (text.contains("esc to go back") || text.contains("ctrl+")) return true;
        if (text.startsWith("press enter")) return true;

        // Codex settings / permission hook screens are full-screen UI chrome. If these rows enter
        // the managed transcript they look like stale scrollback after the picker closes.
        if (text.equals("hooks") || text.equals("pretooluse hooks")) return true;
        if (text.contains("lifecycle hooks") && text.contains("config")) return true;
        if (text.contains("event") && text.contains("installed") && text.contains("active")) return true;
        if (text.startsWith("pretooluse") && text.contains("before a tool executes")) return true;
        if (text.startsWith("permissionrequest") && text.contains("permission is requested")) return true;
        if (text.contains("turn hooks on or off") || text.contains("saved automatically")) return true;
        if (text.startsWith("[x] hook") || text.startsWith("[ ] hook")) return true;
        if (text.startsWith("event") && text.contains("pretooluse")) return true;

        // Inline decision-prompt affordances are mirrored by Kompile's prompt bridge, not assistant
        // output. Keep the exact Codex wording narrow so numbered prose lists still survive.
        if (text.contains("type the option number") && text.contains("navigate")) return true;
        if (text.equals("1. yes, continue") || text.equals("2. no, quit")) return true;

        return false;
    }

    private boolean containsEffortWord(String lower) {
        return lower.contains("xhigh") || lower.contains("high") || lower.contains("medium")
                || lower.contains("low") || lower.contains("minimal");
    }

    @Override
    protected String[] extraBlockingPhrases() {
        // Codex wording for exhaustion. NOT the benign "1 usage limit reset available" /
        // "less than 25% of your 5h limit left" hints — those are handled by isIdle/chrome.
        return new String[]{
                "you've hit your usage limit", "weekly limit reached",
                "run /login", "to continue, run", "please run codex login",
        };
    }

    @Override
    public boolean isResponding(VirtualTerminal vt) {
        String lower = screen(vt);
        return lower.contains("esc to interrupt")
                || lower.contains("esc to cancel")
                || lower.contains("working") && lower.contains("esc")
                || lower.contains("thinking");
    }

    @Override
    public boolean isIdle(VirtualTerminal vt) {
        String lower = screen(vt);
        if (lower.isBlank() || isResponding(vt)) return false;
        // Composer ready: arrow prompt + model/path status bar visible.
        return lower.indexOf(COMPOSER_ARROW) >= 0
                || lower.contains("/model to change")
                || (lower.indexOf('·') >= 0 && containsModelName(lower));
    }

    @Override
    public long turnIdleMillis() {
        return 1500L;
    }

    @Override
    public long startupSettleMillis() {
        return 4000L;
    }

    @Override
    public long submitDelayMillis() {
        return 300L;
    }
}
