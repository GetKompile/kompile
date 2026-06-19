package ai.kompile.cli.main.chat.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OpenCodeDecoderTest {

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
