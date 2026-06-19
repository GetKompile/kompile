package ai.kompile.cli.main.chat.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CodexDecoderTest {

    @Test
    void ownsRenderingAndExtractsOnlyBulletResponseFromCodexScreen() {
        CodexDecoder decoder = new CodexDecoder();
        VirtualTerminal vt = new VirtualTerminal(40, 120);

        // Welcome / update cards (box-drawing chrome) + composer + status bar.
        vt.feed("\033[1;1H╭─────────────────────────────────────────────────╮");
        vt.feed("\033[2;1H│ ✨ Update available! 0.137.0 -> 0.140.0         │");
        vt.feed("\033[3;1H│ Run npm install -g @openai/codex to update.     │");
        vt.feed("\033[4;1H╰─────────────────────────────────────────────────╯");
        vt.feed("\033[6;1H╭─────────────────────────────────────────────╮");
        vt.feed("\033[7;1H│ >_ OpenAI Codex (v0.137.0)                  │");
        vt.feed("\033[8;1H│ model:     gpt-5.5 xhigh   /model to change │");
        vt.feed("\033[9;1H│ directory: ~/Documents/GitHub/kompile       │");
        vt.feed("\033[10;1H╰─────────────────────────────────────────────╯");
        vt.feed("\033[12;1H  Tip: Use /mcp to list configured MCP tools.");
        // User echo + assistant response + composer placeholder + status bar.
        vt.feed("\033[18;1H› Reply with exactly: READY-SENTINEL");
        vt.feed("\033[21;1H• Here is the actual answer line.");
        vt.feed("\033[24;1H› Improve documentation in @filename");
        vt.feed("\033[26;1H  gpt-5.5 xhigh · ~/Documents/GitHub/kompile");

        assertFalse(decoder.renderRawTui());

        String content = decoder.extractContent(vt);
        // Only the assistant bullet line survives.
        assertTrue(content.contains("Here is the actual answer line."), content);
        // All chrome is filtered.
        assertFalse(content.contains("Update available"), content);
        assertFalse(content.contains("npm install"), content);
        assertFalse(content.contains("OpenAI Codex"), content);
        assertFalse(content.contains("model:"), content);
        assertFalse(content.contains("directory:"), content);
        assertFalse(content.contains("Tip:"), content);
        assertFalse(content.contains("Reply with exactly"), content);   // user echo (›)
        assertFalse(content.contains("Improve documentation"), content); // composer (›)
        assertFalse(content.contains("/model to change"), content);
        assertFalse(content.contains("gpt-5.5 xhigh ·"), content);  // status bar
    }

    @Test
    void detectsRespondingAndIdleState() {
        CodexDecoder decoder = new CodexDecoder();

        VirtualTerminal idle = new VirtualTerminal(40, 120);
        idle.feed("\033[24;1H› Improve documentation in @filename");
        idle.feed("\033[26;1H  gpt-5.5 xhigh · ~/Documents/GitHub/kompile");
        assertTrue(decoder.isIdle(idle));
        assertFalse(decoder.isResponding(idle));

        VirtualTerminal busy = new VirtualTerminal(40, 120);
        busy.feed("\033[21;1H• Working on it...");
        busy.feed("\033[26;1H  Esc to interrupt");
        assertTrue(decoder.isResponding(busy));
        assertFalse(decoder.isIdle(busy));
    }
}
