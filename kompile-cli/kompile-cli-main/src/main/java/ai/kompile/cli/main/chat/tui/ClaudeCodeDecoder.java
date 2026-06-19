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

    @Override
    public String agentName() {
        return "claude";
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
        return lower.contains("for shortcuts") || lower.contains("/effort")
                || lower.contains("? for shortcuts");
    }

    @Override
    public String buildInputResponses(String rawChunk, VirtualTerminal vt) {
        if (projectMcpPromptConfirmed) return "";
        String haystack = (rawChunk == null ? "" : rawChunk) + '\n' + (vt == null ? "" : vt.getFullScreen());
        if (haystack.contains("new")
                && haystack.contains("MCP")
                && haystack.contains("servers")
                && haystack.contains("Enter")
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
}
