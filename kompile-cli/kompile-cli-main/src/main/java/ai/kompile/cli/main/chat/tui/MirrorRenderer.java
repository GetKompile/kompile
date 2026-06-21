package ai.kompile.cli.main.chat.tui;

/**
 * Pure rendering helpers for mirror-mode passthrough, extracted so they can be exercised
 * HEADLESSLY. The escape sequence returned here is exactly what the live passthrough writes to
 * the terminal; a test/harness can feed it into a screen {@link VirtualTerminal} and dump the
 * result via {@link VirtualTerminal#screenDump()} — letting us SEE the rendered framebuffer
 * without a real terminal, and drive scenarios deterministically (the same code path as prod).
 */
public final class MirrorRenderer {

    private MirrorRenderer() {
    }

    /**
     * Build the escape sequence that blits the agent's screen into the scroll-region rows
     * {@code [topRow .. topRow + regionRows - 1]} (1-indexed), preserving the agent's exact
     * layout/styling, clearing each target row first, and hiding the cursor for the duration of
     * the paint. The caller restores the real cursor to Kompile's own input box afterward — the
     * blit deliberately does NOT position the cursor at the agent's caret.
     */
    public static String buildMirrorBlit(VirtualTerminal agent, int topRow, int regionRows) {
        int vtRows = agent.getRows();
        StringBuilder sb = new StringBuilder();
        sb.append("\033[?25l");                                   // hide cursor during the blit
        for (int r = 0; r < regionRows; r++) {
            sb.append("\033[").append(topRow + r).append(";1H\033[2K");
            if (r < vtRows) sb.append(agent.getStyledRowFull(r));
        }
        sb.append("\033[0m");
        return sb.toString();
    }
}
