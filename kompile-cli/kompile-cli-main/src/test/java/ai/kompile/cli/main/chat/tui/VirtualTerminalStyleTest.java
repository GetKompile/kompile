package ai.kompile.cli.main.chat.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies VirtualTerminal tracks SGR per cell and re-emits it via getStyledRow(). */
class VirtualTerminalStyleTest {

    @Test
    void getRowStaysPlainGetStyledRowCarriesAnsi() {
        VirtualTerminal vt = new VirtualTerminal(5, 40);
        vt.feed("\033[1;31mBold red\033[0m plain");

        // Plain accessor: no escape sequences.
        assertEquals("Bold red plain", vt.getRow(0));
        assertFalse(vt.getRow(0).contains("\033"));

        // Styled accessor: bold + foreground colour preserved.
        String styled = vt.getStyledRow(0);
        assertTrue(styled.contains("\033["), styled);
        assertTrue(styled.contains("1"), styled);       // bold
        assertTrue(styled.contains("38;5;1"), styled);  // red (indexed)
        assertTrue(styled.contains("Bold red"), styled);
        assertTrue(styled.contains("plain"), styled);
        assertTrue(styled.contains("\033[0m"), styled); // reset emitted when style ends
    }

    @Test
    void truecolorForegroundPreserved() {
        VirtualTerminal vt = new VirtualTerminal(3, 40);
        vt.feed("\033[38;2;10;20;30mRGB\033[0m");
        String styled = vt.getStyledRow(0);
        assertTrue(styled.contains("38;2;10;20;30"), styled);
        assertEquals("RGB", vt.getRow(0));
    }

    @Test
    void resetClearsStyleForSubsequentText() {
        VirtualTerminal vt = new VirtualTerminal(3, 60);
        vt.feed("\033[1mBOLD\033[0m normal");
        String styled = vt.getStyledRow(0);
        // "normal" must not be inside a bold run — a reset precedes it.
        int normalIdx = styled.indexOf("normal");
        assertTrue(normalIdx > 0, styled);
        String beforeNormal = styled.substring(0, normalIdx);
        assertTrue(beforeNormal.contains("\033[0m"), styled);
    }

    @Test
    void plainTextHasNoAnsiOverhead() {
        VirtualTerminal vt = new VirtualTerminal(3, 40);
        vt.feed("just plain text");
        assertEquals("just plain text", vt.getStyledRow(0)); // no escapes when unstyled
    }

    @Test
    void underlineAndItalicPreserved() {
        VirtualTerminal vt = new VirtualTerminal(3, 40);
        vt.feed("\033[3;4mfancy\033[0m");
        String styled = vt.getStyledRow(0);
        assertTrue(styled.contains("3"), styled); // italic
        assertTrue(styled.contains("4"), styled); // underline
        assertTrue(styled.contains("fancy"), styled);
    }
}
