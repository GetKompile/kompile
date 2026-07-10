package ai.kompile.cli.main.chat.tui;

import ai.kompile.cli.main.chat.tui.AgentTuiDecoder.TuiLineKind;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the decoder message-history accumulation and line classification
 * (tool calls vs progress wheels vs prose) — built from real codex/opencode
 * PTY layouts.
 */
class TuiHistoryTest {

    private static int count(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    @Test
    void classifiesProgressToolAndContent() {
        CodexDecoder d = new CodexDecoder();
        assertEquals(TuiLineKind.PROGRESS, d.classify("• Working (4s • esc to interrupt)"));
        assertEquals(TuiLineKind.TOOL, d.classify("• Explored"));
        assertEquals(TuiLineKind.TOOL, d.classify("└ List ls -1"));
        assertEquals(TuiLineKind.CONTENT, d.classify("ls -1 printed 162 entries in the current directory."));
        assertEquals(TuiLineKind.CHROME, d.classify("gpt-5.5 xhigh · ~/Documents/GitHub/kompile"));
    }

    @Test
    void opencodeClassifiesArrowToolAndKompileResult() {
        OpenCodeDecoder d = new OpenCodeDecoder();
        assertEquals(TuiLineKind.TOOL, d.classify("→ Read ."));
        assertEquals(TuiLineKind.TOOL, d.classify("[kompile] read done (6ms)"));
        assertEquals(TuiLineKind.PROGRESS, d.classify("esc interrupt"));
    }

    @Test
    void accumulatesToolThenAnswerExcludingTransients() {
        CodexDecoder d = new CodexDecoder();
        d.resetHistory();

        VirtualTerminal s1 = new VirtualTerminal(40, 120);
        s1.feed("\033[18;1H› Run the shell command ls -1 and count files.");
        s1.feed("\033[21;1H• Explored");
        s1.feed("\033[22;1H  └ List ls -1");
        s1.feed("\033[24;1H• Working (4s • esc to interrupt)");
        s1.feed("\033[26;1H  gpt-5.5 xhigh · ~/Documents/GitHub/kompile");
        d.observe(s1);

        VirtualTerminal s2 = new VirtualTerminal(40, 120);
        s2.feed("\033[18;1H› Run the shell command ls -1 and count files.");
        s2.feed("\033[21;1H• Explored");
        s2.feed("\033[22;1H  └ List ls -1");
        s2.feed("\033[25;1H• ls -1 printed 162 entries in the current directory.");
        s2.feed("\033[26;1H  gpt-5.5 xhigh · ~/Documents/GitHub/kompile");
        d.observe(s2);

        String h = d.renderHistory();
        assertTrue(h.contains("Explored"), h);
        assertTrue(h.contains("List ls -1"), h);
        assertTrue(h.contains("ls -1 printed 162 entries"), h);
        assertFalse(h.contains("Working"), h);              // spinner excluded
        assertFalse(h.contains("esc to interrupt"), h);
        assertFalse(h.contains("Run the shell command"), h); // user echo (›) excluded
        assertFalse(h.contains("gpt-5.5 xhigh ·"), h);       // status bar excluded
        assertEquals(1, count(h, "Explored"), h);            // no cross-snapshot dup
        assertEquals(1, count(h, "List ls -1"), h);

        // Detected tool lines are emitted as renderer tool-block markup (→ panel).
        assertTrue(h.contains("[tool:Explored]"), h);
        assertTrue(h.contains("[/tool]"), h);

        long tools = d.history().stream().filter(e -> e.kind() == TuiLineKind.TOOL).count();
        assertEquals(2, tools, d.history().toString());      // "• Explored" + "└ List ls -1"
    }

    @Test
    void toolGroupBecomesToolMarkupAndContentStaysProse() {
        CodexDecoder d = new CodexDecoder();
        d.resetHistory();
        VirtualTerminal s = new VirtualTerminal(40, 120);
        s.feed("\033[10;1H• Explored");
        s.feed("\033[11;1H  └ List ls -1");
        s.feed("\033[12;1H• Here is the prose answer.");
        d.observe(s);

        String h = d.renderHistory();
        assertTrue(h.contains("[tool:Explored]"), h);
        assertTrue(h.contains("List ls -1"), h);          // tool detail = panel body
        assertTrue(h.contains("[/tool]"), h);
        assertTrue(h.contains("Here is the prose answer."), h);
        assertFalse(h.contains("[tool:Here]"), h);        // prose is not a tool
    }

    @Test
    void arrowToolAndKompileResultBecomeMarkup() {
        OpenCodeDecoder arrow = new OpenCodeDecoder();
        arrow.resetHistory();
        VirtualTerminal s1 = new VirtualTerminal(40, 120);
        s1.feed("\033[10;1H→ Read .");
        arrow.observe(s1);
        assertTrue(arrow.renderHistory().contains("[tool:Read]"), arrow.renderHistory());

        OpenCodeDecoder result = new OpenCodeDecoder();
        result.resetHistory();
        VirtualTerminal s2 = new VirtualTerminal(40, 120);
        s2.feed("\033[10;1H[kompile] read done (6ms)");
        result.observe(s2);
        String h = result.renderHistory();
        assertTrue(h.contains("[tool-result]"), h);
        assertTrue(h.contains("read done"), h);
    }

    @Test
    void codexBulletPrefixedRewrapReplacesLiveTailInsteadOfDuplicating() {
        CodexDecoder d = new CodexDecoder();
        d.resetHistory();

        VirtualTerminal s1 = new VirtualTerminal(30, 100);
        s1.feed("\033[1;1H• The answer is forty two and");
        s1.feed("\033[2;1H• that is the final result here");
        d.observe(s1);

        VirtualTerminal s2 = new VirtualTerminal(30, 100);
        s2.feed("\033[1;1H• The answer is forty two");
        s2.feed("\033[2;1H• and that is the final result here");
        d.observe(s2);

        String h = d.renderHistory();
        assertEquals(2, d.history().size(), d.history().toString());
        assertEquals(1, count(h, "The answer is forty two"), h);
        assertEquals(1, count(h, "and that is the final result here"), h);
    }

    @Test
    void retainsLinesThatScrollOffAgentViewport() {
        CodexDecoder d = new CodexDecoder();
        d.resetHistory();

        VirtualTerminal s1 = new VirtualTerminal(40, 120);
        s1.feed("\033[10;1H• line one");
        s1.feed("\033[11;1H• line two");
        s1.feed("\033[12;1H• line three");
        d.observe(s1);

        // line one scrolled off the agent's screen; line four is new.
        VirtualTerminal s2 = new VirtualTerminal(40, 120);
        s2.feed("\033[10;1H• line two");
        s2.feed("\033[11;1H• line three");
        s2.feed("\033[12;1H• line four");
        d.observe(s2);

        String h = d.renderHistory();
        assertTrue(h.contains("line one"), "scrolled-off line lost: " + h);
        assertTrue(h.contains("line four"), h);
        assertEquals(1, count(h, "line one"), h);
        assertEquals(1, count(h, "line two"), h);
        assertEquals(4, d.history().size(), d.history().toString());
    }

    @Test
    void mergesStreamingGrowthWithoutDuplication() {
        CodexDecoder d = new CodexDecoder();
        d.resetHistory();

        VirtualTerminal s1 = new VirtualTerminal(40, 120);
        s1.feed("\033[10;1H• The ans");
        d.observe(s1);

        VirtualTerminal s2 = new VirtualTerminal(40, 120);
        s2.feed("\033[10;1H• The answer is 4.");
        d.observe(s2);

        String h = d.renderHistory();
        assertTrue(h.contains("The answer is 4."), h);
        assertEquals(1, d.history().size(), d.history().toString());
    }

    @Test
    void toolMarkupRendersToPanelThroughMarkdownRenderer() {
        CodexDecoder d = new CodexDecoder();
        d.resetHistory();
        VirtualTerminal s = new VirtualTerminal(40, 120);
        s.feed("\033[10;1H• Explored");
        s.feed("\033[11;1H  └ List ls -1");
        s.feed("\033[13;1H• **Bold** answer with a heading.");
        d.observe(s);

        String markup = d.renderHistory();
        String rendered = new AsciiRenderer(new TerminalRenderer(true), 120).renderMarkdown(markup);

        // The [tool:...] markup is consumed by the renderer (formatted into a panel),
        // not left as literal text — i.e. formatting was applied to the detected tool.
        assertFalse(rendered.contains("[tool:Explored]"), rendered);
        assertFalse(rendered.contains("[/tool]"), rendered);
        assertTrue(rendered.contains("Explored"), rendered);    // tool name in panel
        assertTrue(rendered.contains("List ls -1"), rendered);  // tool body in panel
        assertTrue(rendered.contains("answer with a heading"), rendered); // prose retained
    }

    @Test
    void contentPreservesAgentAnsiStyling() {
        CodexDecoder d = new CodexDecoder();
        d.resetHistory();
        VirtualTerminal s = new VirtualTerminal(40, 120);
        // Agent renders a bold, coloured answer line.
        s.feed("\033[10;1H\033[1;32mThe answer is bold green.\033[0m");
        d.observe(s);

        String h = d.renderHistory();
        assertTrue(h.contains("The answer is bold green."), h);
        assertTrue(h.contains("\033["), "agent ANSI styling not preserved: " + h);
        assertTrue(h.contains("1"), h);        // bold
        assertTrue(h.contains("38;5;2"), h);   // green (indexed)

        // The structured plain text remains clean (no escapes) for matching/logging.
        String plain = d.history().get(0).text();
        assertFalse(plain.contains("\033"), plain);
        assertEquals("The answer is bold green.", plain);
    }

    @Test
    void resetClearsHistory() {
        CodexDecoder d = new CodexDecoder();
        VirtualTerminal s = new VirtualTerminal(40, 120);
        s.feed("\033[10;1H• something");
        d.observe(s);
        assertFalse(d.history().isEmpty());
        d.resetHistory();
        assertTrue(d.history().isEmpty());
        assertEquals("", d.renderHistory());
    }

    // -----------------------------------------------------------------------
    // BUG 2 — GFM table separator row must survive into history / renderHistory
    // -----------------------------------------------------------------------

    @Test
    void gfmTableSeparatorRowSurvivesHistory() {
        // "| --- | --- |" has no letters/digits so containsContentCharacter drops it,
        // and isSeparatorOrBorder / containsMostlyDecorative both fire on the dashes.
        // The fix: isGfmTableRow (≥2 pipes) bypasses those checks so the separator
        // row survives — without it the markdown renderer never sees the table structure
        // and renders the header/cells as raw pipe-delimited prose instead of a box.
        OpenCodeDecoder d = new OpenCodeDecoder();
        d.resetHistory();

        VirtualTerminal s = new VirtualTerminal(20, 60);
        s.feed("\033[2;1H| Name | Role |");
        s.feed("\033[3;1H| --- | --- |");
        s.feed("\033[4;1H| Alice | Engineer |");
        d.observe(s);

        // All three rows must be in history (header, separator, data).
        String histPlain = d.history().stream()
                .map(AgentTuiDecoder.HistoryEntry::text)
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(histPlain.contains("Name"), "header row lost from history: " + histPlain);
        assertTrue(histPlain.contains("---"), "separator row lost from history: " + histPlain);
        assertTrue(histPlain.contains("Alice"), "data row lost from history: " + histPlain);

        String h = d.renderHistory();
        assertTrue(h.contains("Name"), "header row lost from renderHistory: " + h);
        assertTrue(h.contains("---"), "separator row lost from renderHistory: " + h);
        assertTrue(h.contains("Alice"), "data row lost from renderHistory: " + h);
    }

    // -----------------------------------------------------------------------
    // BUG 8 — content after a tool call must not be duplicated via appendNovelTail
    // -----------------------------------------------------------------------

    @Test
    void contentAfterToolIsNotDuplicatedOnReflow() {
        // findReflowAnchor used allContent(tail) which aborted the whole window when
        // any TOOL entry was present.  Result: content reflowed across viewport widths
        // after a tool call was never reflow-anchored and got appended a second time
        // via appendNovelTail.  Fix: skip (not abort) TOOL entries in both the history
        // tail and the visible prefix when building the reflow text comparison.
        CodexDecoder d = new CodexDecoder();
        d.resetHistory();

        // First frame: a tool call followed by prose content long enough for the
        // tailFlow ≥ 20 char guard (the reflow anchor ignores short strings).
        VirtualTerminal s1 = new VirtualTerminal(30, 100);
        s1.feed("\033[1;1H• Explored the repository structure");
        s1.feed("\033[2;1HThe answer spans multiple words here now");
        d.observe(s1);

        // Second frame: the same content reflowed across a narrower viewport.
        // The TOOL line is still visible; the prose was wrapped onto two rows.
        VirtualTerminal s2 = new VirtualTerminal(30, 80);
        s2.feed("\033[1;1H• Explored the repository structure");
        s2.feed("\033[2;1HThe answer spans multiple words");
        s2.feed("\033[3;1Hhere now");
        d.observe(s2);

        String h = d.renderHistory();
        // The prose content must appear exactly once — the reflow anchor must fire
        // and dedup the two wrapped rows against the original single-row entry.
        assertEquals(1, count(h, "The answer spans"),
                "content after tool duplicated on reflow:\n" + h);
    }

    @Test
    void gfmPipeRowAndBoxRerenderDeduplicate() {
        // opencode streams a raw GFM row, then repaints the same row box-drawn. The two must
        // collapse to one transcript entry (dedupKey unifies │ with |), not appear twice.
        OpenCodeDecoder d = new OpenCodeDecoder();
        d.resetHistory();

        VirtualTerminal s1 = new VirtualTerminal(20, 60);
        s1.feed("\033[3;1HHere is the summary table below:");
        s1.feed("\033[4;1H| Alice | Engineer |");
        d.observe(s1);

        VirtualTerminal s2 = new VirtualTerminal(20, 60);
        s2.feed("\033[3;1HHere is the summary table below:");
        s2.feed("\033[4;1H│ Alice │ Engineer │");
        d.observe(s2);

        String h = d.renderHistory();
        assertTrue(h.contains("Alice"), h);
        // The row's data must appear exactly once, not as both "| Alice | Engineer |" and "Alice │ Engineer".
        assertEquals(1, count(h, "Alice"),
                "GFM row + its box re-render must dedup to one entry:\n" + h);
    }
}
