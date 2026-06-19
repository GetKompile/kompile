package ai.kompile.cli.main.chat.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeCodeDecoderTest {

    @Test
    void ownsRenderingAndFiltersClaudeWelcomeChrome() {
        ClaudeCodeDecoder decoder = new ClaudeCodeDecoder();
        VirtualTerminal vt = new VirtualTerminal(30, 100);

        vt.feed("\033[1;1HClaudeCode[v2.1.18]");
        vt.feed("\033[4;20HWelcome back Adam!");
        vt.feed("\033[4;70HWhat's new");
        vt.feed("\033[5;70HAdded `/config key...`");
        vt.feed("\033[6;70HAdded `sandbox.all...`");
        vt.feed("\033[10;1HHere are the MCP tools available:");
        vt.feed("\033[11;1H- read: read files from the workspace");
        vt.feed("\033[27;1H/ running (claude)");
        vt.feed("\033[28;1Hctrl+g to edit in nano");

        assertFalse(decoder.renderRawTui());

        String content = decoder.extractContent(vt);
        assertTrue(content.contains("Here are the MCP tools available:"));
        assertTrue(content.contains("- read: read files from the workspace"));
        assertFalse(content.contains("ClaudeCode"));
        assertFalse(content.contains("Welcome back"));
        assertFalse(content.contains("What's new"));
        assertFalse(content.contains("Added `/config"));
        assertFalse(content.contains("/ running"));
        assertFalse(content.contains("ctrl+g"));
    }

    @Test
    void filtersBlockArtBannerAndSetupWarning() {
        ClaudeCodeDecoder decoder = new ClaudeCodeDecoder();
        VirtualTerminal vt = new VirtualTerminal(30, 120);

        // Real banner: logo art prefixes the version, model/plan, and cwd lines.
        vt.feed("\033[1;1H▐▛███▜▌   Claude Code v2.1.181");
        vt.feed("\033[2;1H▝▜█████▛▘  Opus 4.8 with max effort · Claude Max");
        vt.feed("\033[3;1H  ▘▘ ▝▝    ~/Documents/GitHub/kompile");
        vt.feed("\033[5;1H  1 setup issue: MCP · /doctor");
        vt.feed("\033[10;1HThis is the real assistant answer.");

        String content = decoder.extractContent(vt);
        assertTrue(content.contains("This is the real assistant answer."), content);
        assertFalse(content.contains("Claude Code v"), content);
        assertFalse(content.contains("Claude Max"), content);
        assertFalse(content.contains("Documents/GitHub/kompile"), content);
        assertFalse(content.contains("setup issue"), content);
        assertFalse(content.contains("/doctor"), content);
    }
}
