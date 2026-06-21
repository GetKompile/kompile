package ai.kompile.cli.main.chat.tui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless framebuffer harness for the passthrough renderer.
 * <p>
 * Each scenario: feed the agent's output into an agent {@link VirtualTerminal}, blit it through
 * the REAL renderer ({@link MirrorRenderer#buildMirrorBlit}) into a 24x80 "screen" VirtualTerminal
 * alongside simulated Kompile chrome (top bar + input box + status), then dump the screen to
 * {@code target/framebuffer/<name>.txt}. The dump is a readable grid of exactly what would render
 * on a real terminal — so the rendering can be SEEN and DRIVEN without screenshots. Add a scenario
 * = add a test method.
 */
class MirrorFramebufferTest {

    private static Path dump(String name, VirtualTerminal screen) throws Exception {
        Path dir = Paths.get("target", "framebuffer");
        Files.createDirectories(dir);
        Path out = dir.resolve(name + ".txt");
        Files.writeString(out, screen.screenDump());
        return out;
    }

    /**
     * Compose a screen the way the live passthrough does in mirror mode: Kompile top bar (row 1),
     * the agent blit in the scroll region (rows 2..rows-2), Kompile input box (row rows-1) and
     * status bar (row rows). The blit uses the production code path.
     */
    private static VirtualTerminal compose(VirtualTerminal agent, int rows, int cols) {
        VirtualTerminal screen = new VirtualTerminal(rows, cols);
        int top = 2;
        int regionRows = rows - 3;          // top bar + input box + status = 3 chrome rows
        int inputRow = rows - 1;
        int statusRow = rows;
        screen.feed("\033[1;1H\033[2Kkompile [claude]  session: demo  passthrough");
        screen.feed(MirrorRenderer.buildMirrorBlit(agent, top, regionRows));
        screen.feed(String.format("\033[%d;1H\033[2Kkompile [claude] > ", inputRow));
        screen.feed(String.format("\033[%d;1H\033[2Kkompile [claude] · idle · /quit exit", statusRow));
        // Model the live cursor-restore: after the blit, the real passthrough returns the caret to
        // Kompile's input box (drawActivePromptLine), NOT the agent's mirrored box. The prompt
        // "kompile [claude] > " is 19 cols, so the caret sits at col 20 of the input row.
        screen.feed(String.format("\033[%d;20H\033[?25h", inputRow));
        return screen;
    }

    @Test
    @DisplayName("Framebuffer: mirror blit of a Claude thinking screen")
    void mirrorBlitClaudeThinking() throws Exception {
        VirtualTerminal agent = new VirtualTerminal(20, 80);
        agent.feed("Claude Code v2.1.181\r\n");
        agent.feed("Opus 4.8 with max effort · Claude Max\r\n\r\n");
        agent.feed("I'll search for the available MCP tools from the connected servers.\r\n");
        agent.feed("✻ Sautéing… (6s · thinking with max effort)\r\n\r\n");
        agent.feed("> ");

        VirtualTerminal screen = compose(agent, 24, 80);
        Path out = dump("mirror-claude-thinking", screen);
        assertTrue(Files.exists(out));

        String fb = screen.screenDump();
        assertTrue(fb.contains("I'll search for the available MCP tools"), "agent content blitted");
        assertTrue(fb.contains("kompile [claude] > "), "Kompile input box present below the blit");
        assertTrue(fb.contains("· idle · /quit exit"), "Kompile status bar present");
        assertTrue(screen.getCursorRow() == 22,
                "caret restored to Kompile's input box (row 22), not the agent's mirrored box; was row "
                        + screen.getCursorRow());
    }

    @Test
    @DisplayName("Framebuffer: a full-width rule then text does NOT collide (deferred wrap)")
    void mirrorBlitRuleThenText() throws Exception {
        VirtualTerminal agent = new VirtualTerminal(20, 80);
        agent.feed("─".repeat(80));   // a full-width rule — used to shift the next line early
        agent.feed("\r\n");
        agent.feed("read — read files\r\n");
        agent.feed("write — write files\r\n");

        VirtualTerminal screen = compose(agent, 24, 80);
        dump("mirror-rule-then-text", screen);

        String fb = screen.screenDump();
        assertTrue(fb.contains("read — read files"), "tool line intact, no rule collision");
        assertTrue(fb.contains("write — write files"), "second tool line intact");
    }

    @Test
    @DisplayName("Framebuffer: a rule row rewritten in place as a spinner shows no rule bleed")
    void mirrorBlitSpinnerRewriteOverRule() throws Exception {
        VirtualTerminal agent = new VirtualTerminal(20, 80);
        agent.feed("Found the kompile server tools.\r\n");
        agent.feed("─".repeat(80) + "\r\n");                  // a full-width rule on row 1
        agent.feed("✻ Photosynthesizing… (15s · ↓ 853 tokens)\r\n");
        // Claude rewrites that rule row (1-indexed row 2) in place as a new spinner frame:
        agent.feed("\033[2;1H\033[2K");                        // position to the row, clear it
        agent.feed("✶ Mustering… (16s · ↓ 1.0k tokens)");

        VirtualTerminal screen = compose(agent, 24, 80);
        dump("mirror-spinner-over-rule", screen);

        String fb = screen.screenDump();
        assertTrue(fb.contains("Found the kompile server tools."), "content line intact");
        assertTrue(fb.contains("✶ Mustering…"), "rewritten spinner present in the mirror");
        assertFalse(agent.getRow(1).contains("─"),
                "the row was cleared + rewritten — no rule bleeds through the spinner");
    }

    @Test
    @DisplayName("Framebuffer: scrolled agent content shows the latest screen, not stale rows")
    void mirrorBlitScrollingContent() throws Exception {
        VirtualTerminal agent = new VirtualTerminal(8, 80);   // small agent screen → it scrolls
        for (int i = 1; i <= 20; i++) {
            agent.feed("response line " + i + "\r\n");
        }
        agent.feed("✻ Working…");

        VirtualTerminal screen = compose(agent, 24, 80);
        dump("mirror-scrolling-content", screen);

        String fb = screen.screenDump();
        assertTrue(fb.contains("response line 20"), "latest line visible after scroll");
        assertTrue(fb.contains("✻ Working…"), "current spinner visible");
    }

    @Test
    @DisplayName("Framebuffer: re-blitting a CHANGED (shorter) agent frame leaves no stale rows")
    void mirrorReblitClearsStaleRows() throws Exception {
        int rows = 24, cols = 80, top = 2, regionRows = rows - 3;
        VirtualTerminal screen = new VirtualTerminal(rows, cols);
        screen.feed("\033[1;1H\033[2Kkompile [claude]  session: demo  passthrough");
        screen.feed(String.format("\033[%d;1H\033[2Kkompile [claude] > ", rows - 1));
        screen.feed(String.format("\033[%d;1H\033[2Kkompile [claude] · idle · /quit exit", rows));

        // Frame A — a long agent screen (12 lines).
        VirtualTerminal agentA = new VirtualTerminal(20, cols);
        for (int i = 1; i <= 12; i++) agentA.feed("LONG response line " + i + "\r\n");
        screen.feed(MirrorRenderer.buildMirrorBlit(agentA, top, regionRows));
        dump("mirror-change-frameA", screen);
        assertTrue(screen.screenDump().contains("LONG response line 12"), "frame A rendered");

        // Frame B — the agent redrew to a SHORTER screen. The re-blit must wipe A's extra rows.
        VirtualTerminal agentB = new VirtualTerminal(20, cols);
        agentB.feed("brief reply\r\n");
        agentB.feed("✻ Working…");
        screen.feed(MirrorRenderer.buildMirrorBlit(agentB, top, regionRows));
        dump("mirror-change-frameB", screen);

        String fb = screen.screenDump();
        assertTrue(fb.contains("brief reply"), "new short content present after the re-blit");
        assertFalse(fb.contains("LONG response line"), "no stale rows from the previous longer frame");
        assertTrue(fb.contains("kompile [claude] >"), "Kompile input box untouched by the re-blit");
    }
}
