package ai.kompile.cli.main.chat.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import ai.kompile.cli.main.chat.tui.AgentTuiDecoder.TuiLineKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Isolates the decoder's per-row classification — the layer that keeps mid-redraw "jumbles" and
 * transient spinners out of the transcript while preserving real prose. These cases mirror the
 * exact artifacts seen in passthrough screenshots, paired with the do-no-harm cases they must NOT
 * misfire on (spaced em-dashes, hyphenated words, ordinary sentences).
 */
class ClaudeCodeDecoderClassifyTest {

    private final AgentTuiDecoder dec = AgentTuiDecoder.forAgent("claude");

    // ---- jumbles: a horizontal rule / repeated dash collided with text mid-redraw ----

    @Test
    @DisplayName("A box rule jammed inside text is a mid-redraw jumble → CHROME")
    void boxRuleJammedInText() {
        assertEquals(TuiLineKind.CHROME, dec.classify("se─tings, MCP · /doctor"));
        assertEquals(TuiLineKind.CHROME, dec.classify("✻─Sautéing…──(6s)"));
        assertEquals(TuiLineKind.CHROME, dec.classify("──2──s: MCP · /doctor"));
    }

    @Test
    @DisplayName("Repeated em-dashes jammed against letters are a jumble → CHROME")
    void emDashJumble() {
        // "──read──read─files" with em-dashes (Claude's own separator, duplicated mid-redraw)
        assertEquals(TuiLineKind.CHROME,
                dec.classify("——read——read—files"));
    }

    // ---- spinners: transient progress, never content ----

    @Test
    @DisplayName("A spinner glyph line is PROGRESS")
    void spinnerIsProgress() {
        assertEquals(TuiLineKind.PROGRESS, dec.classify("✻ Sautéing…"));
        assertEquals(TuiLineKind.PROGRESS, dec.classify("✶ Mustering… (6s · thinking with max effort)"));
        assertEquals(TuiLineKind.PROGRESS,
                dec.classify("✶ Photosynthesizing… (10s · ↓ 495 tokens)"));
    }

    // ---- do-no-harm: legitimate content must survive ----

    @Test
    @DisplayName("Ordinary prose is CONTENT")
    void proseIsContent() {
        assertEquals(TuiLineKind.CONTENT, dec.classify("I'll search for the available MCP tools."));
        assertEquals(TuiLineKind.CONTENT,
                dec.classify("Found the kompile server tools. Let me check the other two."));
    }

    @Test
    @DisplayName("A SPACED em-dash line is NOT treated as a jumble (kept — CONTENT or TOOL)")
    void spacedEmDashIsNotJumble() {
        // "name — description" is how Claude lists MCP tools, so this legitimately classifies
        // as TOOL; the do-no-harm property is that it is NEVER dropped as a mid-redraw jumble.
        assertNotEquals(TuiLineKind.CHROME, dec.classify("read — read files"));
        assertNotEquals(TuiLineKind.CHROME, dec.classify("write — write files"));
    }

    @Test
    @DisplayName("A hyphenated word stays CONTENT")
    void hyphenatedWordIsContent() {
        assertEquals(TuiLineKind.CONTENT, dec.classify("this is a well-known approach"));
    }
}
