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

    @Override
    public String agentName() {
        return "codex";
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
        if (text.contains("esc to go back") || text.contains("ctrl+")) return true;
        if (text.startsWith("press enter")) return true;

        return false;
    }

    private boolean containsEffortWord(String lower) {
        return lower.contains("xhigh") || lower.contains("high") || lower.contains("medium")
                || lower.contains("low") || lower.contains("minimal");
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
