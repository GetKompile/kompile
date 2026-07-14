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

import java.util.List;
import java.util.Locale;

/**
 * Decoder for Claude Code CLI (github.com/anthropics/claude-code).
 * <p>
 * From PTY dump analysis (v2.1.181, 2026-06-18):
 * <ul>
 *   <li>Top banner box: logo built from block glyphs + "Claude Code vX.Y.Z",
 *       model/plan line, and cwd line — all prefixed by the logo art</li>
 *   <li>Setup-warning row ("N setup issue: MCP · /doctor")</li>
 *   <li>Bottom: input box ({@code ❯ Try "..."}), hints ("? for shortcuts"),
 *       status bar ("◈ max · /effort")</li>
 *   <li>Sends DA1/DSR queries; mis-handles the Kitty keyboard reply
 *       (ESC[?0u launches nano) so we must NOT answer it.</li>
 * </ul>
 */
public class ClaudeCodeDecoder extends AbstractTuiDecoder {

    private boolean projectMcpPromptConfirmed;
    private boolean folderTrustConfirmed;

    @Override
    public String agentName() {
        return "claude";
    }

    @Override
    public boolean supportsNativeBackgrounding() {
        // Claude Code backgrounds a running command itself; Kompile forwards the
        // backgrounding key (Ctrl+B) rather than running its own backgrounding.
        return true;
    }

    /**
     * Claude Code's entire TUI lives in the alternate screen — it enters it at startup and stays
     * there. So "is in alternate screen" carries no dialog signal for claude; return false to
     * prevent every detected prompt from triggering full-screen mirror mode.
     */
    @Override
    public boolean altScreenIsDialog() {
        return false;
    }

    /**
     * Find the first row BELOW claude's last done-marker ("✻ &lt;Word&gt; for Ns", e.g.
     * "✻ Cooked for 1s") so that old question text from before the done-marker is not
     * mistaken for a new input prompt. When no done-marker is found, returns 0 (full screen).
     */
    @Override
    protected int liveRegionStartRow(List<String> rows) {
        // Claude's done-marker: a row whose first visible char is a star/asterisk dingbat
        // and that contains a "(Ns" or "(Nm" elapsed timer — matches "✻ Cooked for 1s" etc.
        // We do a simple two-signal test: star-dingbat lead AND "for" followed by a digit.
        int lastMarker = -1;
        for (int r = 0; r < rows.size(); r++) {
            String raw = rows.get(r);
            if (raw == null) continue;
            String stripped = raw.strip();
            if (stripped.isEmpty()) continue;
            char lead = '\0';
            for (int i = 0; i < stripped.length(); i++) {
                char c = stripped.charAt(i);
                if (!Character.isWhitespace(c)) { lead = c; break; }
            }
            // Star/asterisk dingbat lead (U+2720–U+274F) — the "✻" family.
            boolean starLead = (lead >= '✠' && lead <= '❏');
            if (starLead) {
                String lower = stripped.toLowerCase(Locale.ROOT);
                // "for Ns" or "for Nm" elapsed timer suffix.
                int forIdx = lower.indexOf(" for ");
                if (forIdx >= 0 && forIdx + 5 < lower.length()
                        && Character.isDigit(lower.charAt(forIdx + 5))) {
                    lastMarker = r;
                }
            }
        }
        return lastMarker >= 0 ? lastMarker + 1 : 0;
    }

    @Override
    protected boolean answersKittyKeyboard() {
        // Claude Code launches nano when it receives ESC[?0u.
        return false;
    }

    @Override
    protected boolean isChrome(String row) {
        String lower = row.toLowerCase(Locale.ROOT);
        String compact = lower.replace(" ", "");
        String text = stripLeadingChromeGlyphs(lower);

        // The top banner (logo + version, model/plan, cwd) is prefixed by block art.
        if (startsWithBlockArt(row)) return true;

        if (compact.startsWith("claudecode[") || compact.startsWith("claudecodev")
                || compact.equals("claudecode") || lower.contains("claude code v")) return true;
        if (lower.contains("claude code") && lower.contains("welcome")) return true;
        if (text.startsWith("welcome back")) return true;
        if (text.startsWith("what's new")) return true;
        if (text.startsWith("added `")) return true;
        if (text.startsWith("try ")) return true;
        if (text.contains("setup issue") || lower.contains("/doctor")) return true;
        if (text.contains(" for shortcuts")) return true;
        if (text.contains("ctrl+") || text.contains("esc ")) return true;
        if (text.contains("auto-accept") || text.contains("bypassing permissions")) return true;
        if (text.contains("accept edits") || text.contains("shift+tab")) return true;
        if (text.contains("tokens") && text.length() < 100) return true;
        if (text.contains("/effort") && text.length() < 100) return true;
        if (text.contains("/model") && text.length() < 120
                && (text.contains("switch") || text.contains("model"))) return true;
        if (text.contains("fast mode is now available")) return true;
        if (text.contains("newest model") || text.contains("meet fable")) return true;
        if (text.contains("included in your plan limits") || text.contains("usage credits to")) return true;
        if (text.startsWith("/ ") && text.length() < 100) return true;
        if (text.startsWith("? ") && text.length() < 100) return true;
        if (text.startsWith("mcp ") && text.contains("connected") && text.length() < 100) return true;

        return false;
    }

    @Override
    public boolean isResponding(VirtualTerminal vt) {
        String lower = screen(vt);
        return lower.contains("esc to interrupt") || lower.contains("esc to cancel");
    }

    @Override
    public boolean isIdle(VirtualTerminal vt) {
        String lower = screen(vt);
        if (lower.isBlank() || isResponding(vt)) return false;
        // Standard idle footer ("? for shortcuts", "/effort") OR
        // bypass-permissions footer ("⏵⏵ bypass permissions on (shift+tab to cycle) · ← for agents").
        return lower.contains("for shortcuts") || lower.contains("/effort")
                || lower.contains("? for shortcuts")
                || lower.contains("bypass permissions on")
                || lower.contains("shift+tab to cycle")
                || lower.contains("← for agents");
    }

    @Override
    public String buildInputResponses(String rawChunk, VirtualTerminal vt) {
        String haystack = (rawChunk == null ? "" : rawChunk) + '\n' + (vt == null ? "" : vt.getFullScreen());
        String lower = haystack.toLowerCase(Locale.ROOT);

        // Startup "trust this folder" prompt: "Is this a project you created or one you trust?"
        // with "Yes, I trust this folder" (default) / "No, exit". Left unanswered, the first user
        // message's submit CR selects the default and the message TEXT is discarded — so the first
        // turn is silently lost. Accept it (default option 1) once, before the message is sent.
        if (!folderTrustConfirmed
                && (lower.contains("trust this folder") || lower.contains("is this a project you created"))) {
            folderTrustConfirmed = true;
            return "\r";
        }

        // Project MCP-servers confirmation prompt.
        if (!projectMcpPromptConfirmed
                && haystack.contains("new") && haystack.contains("MCP")
                && haystack.contains("servers") && haystack.contains("Enter")
                && haystack.contains("confirm")) {
            projectMcpPromptConfirmed = true;
            return "\r";
        }
        return "";
    }

    @Override
    public long startupSettleMillis() {
        return 5000L;
    }

    @Override
    public long submitDelayMillis() {
        // ink's paste heuristic treats text+CR arriving in one burst as a single
        // multi-line paste and swallows the CR instead of submitting — the message
        // then sits in the input box forever. A pause between the prompt text and
        // the submit key (same pattern as codex 300ms / opencode 500ms) makes the
        // CR arrive as a distinct keypress.
        return 250L;
    }
}
