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
 * </ul>
 */
public class OpenCodeDecoder extends AbstractTuiDecoder {

    @Override
    public String agentName() {
        return "opencode";
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

    @Override
    public long turnIdleMillis() {
        return 1400L;
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
        if (chromeText.startsWith("try \"") || chromeText.startsWith("try '") || chromeText.contains("try \"")) return true;
        if (lower.contains("what's new in my repo")) return true;
        if (lower.contains("how does opencode")) return true;

        return false;
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
