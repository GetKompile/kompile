package ai.kompile.cli.main.chat.tui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless framebuffer harness for DECODER mode (the counterpart to {@link MirrorFramebufferTest}).
 * <p>
 * Feed the agent's screen into an agent {@link VirtualTerminal}, run the REAL decoder pipeline
 * ({@code observe()} + {@code renderHistory()} — the extraction/merge that drops chrome/spinners),
 * render the resulting transcript into a 24x80 screen VirtualTerminal bottom-aligned (the live
 * scrollback "follows output"), and dump it. Surfaces decoder redrawing issues headlessly:
 * jumbles leaking, content lost, chrome/spinner bleed, mis-aligned rows.
 */
class DecoderFramebufferTest {

    private static Path dump(String name, VirtualTerminal screen) throws Exception {
        Path dir = Paths.get("target", "framebuffer");
        Files.createDirectories(dir);
        Path out = dir.resolve(name + ".txt");
        Files.writeString(out, screen.screenDump());
        return out;
    }

    /** Push the agent screen through the decoder and render its transcript into a composed screen. */
    private static VirtualTerminal renderDecoded(VirtualTerminal agent, int rows, int cols) {
        AgentTuiDecoder decoder = AgentTuiDecoder.forAgent("claude");
        decoder.observe(agent);
        String transcript = decoder.renderHistory();

        VirtualTerminal screen = new VirtualTerminal(rows, cols);
        int top = 2;
        int regionRows = rows - 3;
        int inputRow = rows - 1;
        int statusRow = rows;
        screen.feed("\033[1;1H\033[2Kkompile [claude]  session: demo  passthrough");
        String[] lines = transcript.isEmpty() ? new String[0] : transcript.split("\n", -1);
        int startLine = Math.max(0, lines.length - regionRows);   // follow output → show the bottom
        for (int i = 0; i < regionRows && startLine + i < lines.length; i++) {
            screen.feed(String.format("\033[%d;1H\033[2K%s", top + i, lines[startLine + i]));
        }
        screen.feed(String.format("\033[%d;1H\033[2Kkompile [claude] > ", inputRow));
        screen.feed(String.format("\033[%d;1H\033[2Kkompile [claude] · idle · /quit exit", statusRow));
        return screen;
    }

    @Test
    @DisplayName("Framebuffer (decoder): rule + spinner filtered, content/tool lines kept")
    void decoderClaudeResponse() throws Exception {
        VirtualTerminal agent = new VirtualTerminal(20, 80);
        agent.feed("I'll search for the available MCP tools from the connected servers.\r\n");
        agent.feed("─".repeat(80) + "\r\n");                              // separator rule (chrome)
        agent.feed("- read — read files\r\n");
        agent.feed("- write — write files\r\n");
        agent.feed("✻ Sautéing… (6s · thinking with max effort)\r\n");    // spinner (progress)

        VirtualTerminal screen = renderDecoded(agent, 24, 80);
        dump("decoder-claude-response", screen);

        String fb = screen.screenDump();
        assertTrue(fb.contains("I'll search for the available MCP tools"), "content kept");
        assertTrue(fb.contains("read — read files"), "tool list kept");
        assertFalse(fb.contains("Sautéing"), "spinner filtered out of the transcript");
    }

    @Test
    @DisplayName("Framebuffer (decoder): a box table renders as content, not mangled tool panels")
    void decoderTable() throws Exception {
        VirtualTerminal agent = new VirtualTerminal(20, 80);
        agent.feed("Here are the tools:\r\n");
        agent.feed("│ Tool  │ Description │\r\n");
        agent.feed("│ read  │ read files  │\r\n");
        agent.feed("│ write │ write files │\r\n");

        VirtualTerminal screen = renderDecoded(agent, 24, 80);
        dump("decoder-table", screen);

        String fb = screen.screenDump();
        assertTrue(fb.contains("read files"), "table content kept");
        assertFalse(fb.contains("[tool:"), "table rows not misclassified as tool panels");
    }

    @Test
    @DisplayName("Framebuffer (decoder): a real tool call still renders as a tool panel")
    void decoderRealToolCall() throws Exception {
        VirtualTerminal agent = new VirtualTerminal(20, 80);
        agent.feed("Let me read the file.\r\n");
        agent.feed("● Read(src/main/java/App.java)\r\n");
        agent.feed("  └ 200 lines\r\n");

        VirtualTerminal screen = renderDecoded(agent, 24, 80);
        dump("decoder-real-tool-call", screen);

        String fb = screen.screenDump();
        assertTrue(fb.contains("[tool:") || fb.contains("Read"),
                "a genuine tool call is still detected after the em-dash fix");
    }

    @Test
    @DisplayName("Framebuffer (decoder): a nested tool tree groups into one clean panel")
    void decoderNestedToolTree() throws Exception {
        VirtualTerminal agent = new VirtualTerminal(20, 80);
        agent.feed("Running the tests now.\r\n");
        agent.feed("● Bash(npm test)\r\n");
        agent.feed("  ├ test 1 passed\r\n");
        agent.feed("  ├ test 2 passed\r\n");
        agent.feed("  └ 2 passed, 0 failed\r\n");

        VirtualTerminal screen = renderDecoded(agent, 24, 80);
        dump("decoder-nested-tree", screen);

        String fb = screen.screenDump();
        assertTrue(fb.contains("[tool:Bash]"), "tool call header detected");
        assertTrue(fb.contains("test 1 passed"), "tree detail kept");
        assertTrue(fb.contains("2 passed, 0 failed"), "final tree detail kept");
        assertTrue(fb.contains("Running the tests now"), "preceding prose kept");
    }

    @Test
    @DisplayName("Framebuffer (decoder): a wide content line wraps without loss")
    void decoderWideContent() throws Exception {
        VirtualTerminal agent = new VirtualTerminal(20, 80);
        String wide = "This is a deliberately very long response line that exceeds eighty columns so it must wrap onto a second row.";
        agent.feed(wide + "\r\n");

        VirtualTerminal screen = renderDecoded(agent, 24, 80);
        dump("decoder-wide-content", screen);

        String fb = screen.screenDump();
        assertTrue(fb.contains("deliberately very long response line"), "start of the wide line kept");
        assertTrue(fb.contains("wrap onto a second row"), "end of the wrapped line kept (no loss)");
    }

    @Test
    @DisplayName("Framebuffer (decoder): consecutive tool calls stay distinguishable")
    void decoderMultiToolSequence() throws Exception {
        VirtualTerminal agent = new VirtualTerminal(20, 80);
        agent.feed("● Read(a.txt)\r\n");
        agent.feed("● Read(b.txt)\r\n");
        agent.feed("● Bash(ls -la)\r\n");

        VirtualTerminal screen = renderDecoded(agent, 24, 80);
        dump("decoder-multi-tool", screen);

        String fb = screen.screenDump();
        assertTrue(fb.contains("a.txt") && fb.contains("b.txt") && fb.contains("ls -la"),
                "every tool call's args are present");
        int panels = 0;
        for (int idx = fb.indexOf("[tool:"); idx >= 0; idx = fb.indexOf("[tool:", idx + 6)) panels++;
        assertTrue(panels >= 3, "each tool call is its OWN panel, not merged into one; found " + panels);
    }

    @Test
    @DisplayName("Framebuffer (decoder): a code block (incl. a bare read() call) is content, not a tool")
    void decoderCodeBlock() throws Exception {
        VirtualTerminal agent = new VirtualTerminal(20, 80);
        agent.feed("Here is the code:\r\n");
        agent.feed("```java\r\n");
        agent.feed("read(buffer);\r\n");        // bare "read(" — was misclassified as a tool pre-fix
        agent.feed("write(buffer);\r\n");
        agent.feed("```\r\n");

        VirtualTerminal screen = renderDecoded(agent, 24, 80);
        dump("decoder-code-block", screen);

        String fb = screen.screenDump();
        assertTrue(fb.contains("read(buffer);"), "code line kept verbatim as content");
        assertFalse(fb.contains("[tool:"), "code is not misclassified as a tool call");
    }

    @Test
    @DisplayName("Framebuffer (decoder): a diff renders +/- lines as content")
    void decoderDiff() throws Exception {
        VirtualTerminal agent = new VirtualTerminal(20, 80);
        agent.feed("Here's the change:\r\n");
        agent.feed("- read the old way\r\n");
        agent.feed("+ write the new way\r\n");

        VirtualTerminal screen = renderDecoded(agent, 24, 80);
        dump("decoder-diff", screen);

        String fb = screen.screenDump();
        assertTrue(fb.contains("read the old way"), "removed line kept");
        assertTrue(fb.contains("write the new way"), "added line kept");
        assertFalse(fb.contains("[tool:"), "diff lines not misclassified as tools");
    }

    @Test
    @DisplayName("Framebuffer (decoder): prose between tool calls is kept, not swallowed into a panel")
    void decoderInterleavedProseAndTools() throws Exception {
        VirtualTerminal agent = new VirtualTerminal(20, 80);
        agent.feed("Let me check the config.\r\n");
        agent.feed("● Read(config.json)\r\n");
        agent.feed("Now I'll run the build.\r\n");       // prose BETWEEN two tool calls
        agent.feed("● Bash(mvn install)\r\n");
        agent.feed("Done.\r\n");

        VirtualTerminal screen = renderDecoded(agent, 24, 80);
        dump("decoder-interleaved", screen);

        String fb = screen.screenDump();
        assertTrue(fb.contains("Let me check the config"), "leading prose kept");
        assertTrue(fb.contains("Now I'll run the build"),
                "interleaved prose kept as content, NOT swallowed into the Read panel");
        assertTrue(fb.contains("Done."), "trailing prose kept");
        assertTrue(fb.contains("config.json") && fb.contains("mvn install"), "both tool args kept");
        int panels = 0;
        for (int idx = fb.indexOf("[tool:"); idx >= 0; idx = fb.indexOf("[tool:", idx + 6)) panels++;
        assertTrue(panels >= 2, "two distinct tool panels survive the interleaved prose; found " + panels);
    }

    @Test
    @DisplayName("Framebuffer (decoder): a [kompile] MCP invocation renders as a result panel")
    void decoderKompileResultBlock() throws Exception {
        VirtualTerminal agent = new VirtualTerminal(20, 80);
        agent.feed("I'll search the codebase.\r\n");
        agent.feed("[kompile] grep(pattern=TODO)\r\n");   // a kompile MCP tool marker
        agent.feed("Found 3 matches.\r\n");

        VirtualTerminal screen = renderDecoded(agent, 24, 80);
        dump("decoder-kompile-result", screen);

        String fb = screen.screenDump();
        assertTrue(fb.contains("[tool-result]"), "kompile marker becomes a result panel");
        assertTrue(fb.contains("grep(pattern=TODO)"), "the kompile invocation is preserved in the panel");
        assertTrue(fb.contains("I'll search the codebase"), "prose before the marker kept");
        assertTrue(fb.contains("Found 3 matches"), "prose after the marker kept, not swallowed");
    }
}
