package ai.kompile.cli.main.chat.tui;

import ai.kompile.cli.main.chat.tui.AgentTuiDecoder.TuiLineKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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
}
