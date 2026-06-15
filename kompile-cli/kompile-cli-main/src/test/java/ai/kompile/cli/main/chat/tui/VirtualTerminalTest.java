package ai.kompile.cli.main.chat.tui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for the VirtualTerminal VT100/xterm emulator.
 * Covers escape sequence processing, cursor movement, scrolling,
 * alternate screen buffers, content extraction, and TUI chrome filtering.
 */
class VirtualTerminalTest {

    private VirtualTerminal vt;

    @BeforeEach
    void setUp() {
        vt = new VirtualTerminal(24, 80);
    }

    // ========================================================================
    // Basic text input
    // ========================================================================

    @Nested
    class BasicTextInput {

        @Test
        void plainTextPlacedAtCursor() {
            vt.feed("Hello");
            assertEquals("Hello", vt.getRow(0).trim());
            assertEquals(5, vt.getCursorCol());
            assertEquals(0, vt.getCursorRow());
        }

        @Test
        void newlineAdvancesCursorRow() {
            vt.feed("Line 1\nLine 2");
            assertEquals("Line 1", vt.getRow(0).trim());
            assertEquals("Line 2", vt.getRow(1).trim());
            assertEquals(1, vt.getCursorRow());
        }

        @Test
        void carriageReturnResetsCursorCol() {
            vt.feed("Hello\rWorld");
            assertEquals("World", vt.getRow(0).substring(0, 5));
            assertEquals(5, vt.getCursorCol());
        }

        @Test
        void carriageReturnLinefeedSequence() {
            vt.feed("Line 1\r\nLine 2");
            assertEquals("Line 1", vt.getRow(0).trim());
            assertEquals("Line 2", vt.getRow(1).trim());
        }

        @Test
        void tabAdvancesToNextTabStop() {
            vt.feed("A\tB");
            assertEquals(0, vt.getCursorRow());
            // Tab goes to position 8, then 'B' is at position 8
            String row = vt.getRow(0);
            assertTrue(row.indexOf('B') == 8, "Tab should advance to col 8, B at: " + row.indexOf('B'));
        }

        @Test
        void backspaceMovesBack() {
            vt.feed("ABC\bX");
            // 'X' overwrites 'C'
            assertEquals("ABX", vt.getRow(0).trim());
        }

        @Test
        void backspaceDoesNotGoPastColumn0() {
            vt.feed("\b\bA");
            assertEquals("A", vt.getRow(0).trim());
            assertEquals(1, vt.getCursorCol());
        }

        @Test
        void wrapAtEndOfLine() {
            String text = "A".repeat(80);
            vt.feed(text);
            // 80 chars fills the row, cursor wraps to next line
            assertEquals("A".repeat(80), vt.getRow(0));
            assertEquals(1, vt.getCursorRow());
            assertEquals(0, vt.getCursorCol());
        }

        @Test
        void controlCharactersIgnored() {
            vt.feed("A\0B\u0001C");
            assertEquals("ABC", vt.getRow(0).trim());
        }

        @Test
        void bellIgnored() {
            vt.feed("A\u0007B");
            assertEquals("AB", vt.getRow(0).trim());
        }

        @Test
        void unicodeCharactersPlaced() {
            vt.feed("Hello \u2603 World");
            assertTrue(vt.getRow(0).contains("\u2603"), "Should contain snowman");
        }
    }

    // ========================================================================
    // CSI cursor movement
    // ========================================================================

    @Nested
    class CursorMovement {

        @Test
        void cupPositionsCursor() {
            // CSI row;col H — 1-indexed
            vt.feed("\033[5;10H");
            assertEquals(4, vt.getCursorRow()); // 0-indexed
            assertEquals(9, vt.getCursorCol());
        }

        @Test
        void cupDefaultsToTopLeft() {
            vt.feed("XXX");
            vt.feed("\033[H");
            assertEquals(0, vt.getCursorRow());
            assertEquals(0, vt.getCursorCol());
        }

        @Test
        void cursorUpCUU() {
            vt.feed("\033[5;1H"); // row 5
            vt.feed("\033[2A");   // up 2
            assertEquals(2, vt.getCursorRow());
        }

        @Test
        void cursorUpClampedToRow0() {
            vt.feed("\033[2;1H"); // row 2
            vt.feed("\033[10A");  // up 10 — should clamp
            assertEquals(0, vt.getCursorRow());
        }

        @Test
        void cursorDownCUD() {
            vt.feed("\033[5;1H");
            vt.feed("\033[3B");
            assertEquals(7, vt.getCursorRow());
        }

        @Test
        void cursorDownClampedToBottom() {
            vt.feed("\033[20;1H");
            vt.feed("\033[100B");
            assertEquals(23, vt.getCursorRow()); // 24 rows, 0-indexed
        }

        @Test
        void cursorForwardCUF() {
            vt.feed("\033[1;5H");
            vt.feed("\033[10C");
            assertEquals(14, vt.getCursorCol());
        }

        @Test
        void cursorBackCUB() {
            vt.feed("\033[1;20H");
            vt.feed("\033[5D");
            assertEquals(14, vt.getCursorCol());
        }

        @Test
        void cursorNextLineCNL() {
            vt.feed("\033[3;10H");
            vt.feed("\033[2E");
            assertEquals(4, vt.getCursorRow());
            assertEquals(0, vt.getCursorCol()); // CNL resets col
        }

        @Test
        void cursorPreviousLineCPL() {
            vt.feed("\033[5;10H");
            vt.feed("\033[2F");
            assertEquals(2, vt.getCursorRow());
            assertEquals(0, vt.getCursorCol());
        }

        @Test
        void cursorHorizontalAbsoluteCHA() {
            vt.feed("\033[1;1H");
            vt.feed("\033[15G");
            assertEquals(14, vt.getCursorCol()); // 1-indexed → 0-indexed
        }

        @Test
        void verticalPositionAbsoluteVPA() {
            vt.feed("\033[10d");
            assertEquals(9, vt.getCursorRow());
        }

        @Test
        void cursorSaveAndRestore() {
            vt.feed("\033[5;10H");
            vt.feed("\033[s");    // save
            vt.feed("\033[1;1H"); // move away
            vt.feed("\033[u");    // restore
            assertEquals(4, vt.getCursorRow());
            assertEquals(9, vt.getCursorCol());
        }

        @Test
        void decSaveRestoreCursor() {
            vt.feed("\033[5;10H");
            vt.feed("\0337");     // DECSC
            vt.feed("\033[1;1H");
            vt.feed("\0338");     // DECRC
            assertEquals(4, vt.getCursorRow());
            assertEquals(9, vt.getCursorCol());
        }
    }

    // ========================================================================
    // Erase operations
    // ========================================================================

    @Nested
    class EraseOperations {

        @Test
        void eraseDisplayFromCursor() {
            vt.feed("AAAAAAAAAA");
            vt.feed("\r\n");
            vt.feed("BBBBBBBBBB");
            vt.feed("\033[1;5H"); // row 1, col 5 (0-indexed: row 0, col 4)
            vt.feed("\033[0J");    // ED 0 — erase from cursor to end
            assertEquals("AAAA", vt.getRow(0).trim());
            assertEquals("", vt.getRow(1).trim());
        }

        @Test
        void eraseDisplayToCursor() {
            // Use \r\n (not just \n) to reset column before writing row 1
            vt.feed("AAAAAAAAAA");
            vt.feed("\r\n");
            vt.feed("BBBBBBBBBB");
            // Verify initial state
            assertEquals('A', vt.getRow(0).charAt(0));
            assertEquals('B', vt.getRow(1).charAt(0));
            // CUP is 1-indexed: row 2 = 0-indexed row 1, col 6 = 0-indexed col 5
            vt.feed("\033[2;6H");
            vt.feed("\033[1J");    // ED 1 — erase from start to cursor
            // Row 0 is fully erased (above cursor row)
            assertEquals("", vt.getRow(0).trim());
            // Row 1 (cursor row) is erased from col 0 through cursor col (5 inclusive)
            String row1 = vt.getRow(1);
            assertEquals(' ', row1.charAt(0));
            assertEquals(' ', row1.charAt(4));
            assertEquals(' ', row1.charAt(5)); // cursor col is erased too
            assertEquals('B', row1.charAt(6)); // beyond cursor is preserved
        }

        @Test
        void eraseDisplayEntire() {
            vt.feed("AAAAAAAAAA\nBBBBBBBBBB");
            vt.feed("\033[2J"); // ED 2 — erase all
            assertEquals("", vt.getRow(0).trim());
            assertEquals("", vt.getRow(1).trim());
        }

        @Test
        void eraseLineFromCursor() {
            vt.feed("ABCDEFGHIJ");
            vt.feed("\033[1;5H"); // col 5
            vt.feed("\033[0K");    // EL 0 — erase from cursor to end of line
            assertEquals("ABCD", vt.getRow(0).trim());
        }

        @Test
        void eraseLineToCursor() {
            vt.feed("ABCDEFGHIJ");
            vt.feed("\033[1;5H");
            vt.feed("\033[1K"); // EL 1 — erase from start to cursor
            String row = vt.getRow(0);
            assertEquals(' ', row.charAt(0));
            assertEquals(' ', row.charAt(4));
            assertEquals('F', row.charAt(5));
        }

        @Test
        void eraseEntireLine() {
            vt.feed("ABCDEFGHIJ");
            vt.feed("\033[2K"); // EL 2 — erase entire line
            assertEquals("", vt.getRow(0).trim());
        }

        @Test
        void eraseCharactersECH() {
            vt.feed("ABCDEFGHIJ");
            vt.feed("\033[1;3H"); // col 3
            vt.feed("\033[4X");    // ECH — erase 4 characters
            String row = vt.getRow(0);
            assertEquals('A', row.charAt(0));
            assertEquals('B', row.charAt(1));
            assertEquals(' ', row.charAt(2)); // erased
            assertEquals(' ', row.charAt(5)); // erased
            assertEquals('G', row.charAt(6)); // kept
        }
    }

    // ========================================================================
    // Character insert and delete
    // ========================================================================

    @Nested
    class CharacterInsertDelete {

        @Test
        void deleteCharactersDCH() {
            vt.feed("ABCDEFGH");
            vt.feed("\033[1;3H"); // col 3 (at 'C')
            vt.feed("\033[2P");    // DCH — delete 2 chars
            // C and D removed, EFGH shift left
            assertEquals("ABEFGH", vt.getRow(0).trim());
        }

        @Test
        void insertCharactersICH() {
            vt.feed("ABCDEFGH");
            vt.feed("\033[1;3H"); // col 3
            vt.feed("\033[2@");    // ICH — insert 2 blanks
            String row = vt.getRow(0);
            assertEquals('A', row.charAt(0));
            assertEquals('B', row.charAt(1));
            assertEquals(' ', row.charAt(2)); // inserted blank
            assertEquals(' ', row.charAt(3)); // inserted blank
            assertEquals('C', row.charAt(4)); // shifted right
        }
    }

    // ========================================================================
    // Scrolling
    // ========================================================================

    @Nested
    class Scrolling {

        @Test
        void scrollUpSU() {
            vt.feed("Line 0\nLine 1\nLine 2\nLine 3");
            vt.feed("\033[2S"); // scroll up 2 lines
            assertEquals("Line 2", vt.getRow(0).trim());
            assertEquals("Line 3", vt.getRow(1).trim());
            assertEquals("", vt.getRow(2).trim()); // new blank lines
        }

        @Test
        void scrollDownSD() {
            vt.feed("Line 0\nLine 1\nLine 2\nLine 3");
            vt.feed("\033[2T"); // scroll down 2 lines
            assertEquals("", vt.getRow(0).trim());
            assertEquals("", vt.getRow(1).trim());
            assertEquals("Line 0", vt.getRow(2).trim());
            assertEquals("Line 1", vt.getRow(3).trim());
        }

        @Test
        void insertLinesIL() {
            vt.feed("Line 0\nLine 1\nLine 2");
            vt.feed("\033[2;1H"); // row 2 (at Line 1)
            vt.feed("\033[1L");    // IL — insert 1 blank line
            assertEquals("Line 0", vt.getRow(0).trim());
            assertEquals("", vt.getRow(1).trim()); // inserted blank
            assertEquals("Line 1", vt.getRow(2).trim()); // pushed down
        }

        @Test
        void deleteLinesML() {
            vt.feed("Line 0\nLine 1\nLine 2\nLine 3");
            vt.feed("\033[2;1H"); // row 2
            vt.feed("\033[1M");    // DL — delete 1 line
            assertEquals("Line 0", vt.getRow(0).trim());
            assertEquals("Line 2", vt.getRow(1).trim()); // shifted up
            assertEquals("Line 3", vt.getRow(2).trim());
        }

        @Test
        void linefeedAtBottomScrolls() {
            VirtualTerminal small = new VirtualTerminal(3, 20);
            // Fill all 3 rows. After "Row 2", cursor is at row 2.
            small.feed("Row 0\nRow 1\nRow 2");
            assertEquals("Row 0", small.getRow(0).trim());
            assertEquals("Row 1", small.getRow(1).trim());
            assertEquals("Row 2", small.getRow(2).trim());

            // LF at bottom row triggers scroll: Row 0 pushed off screen
            small.feed("\r\nRow 3");
            // After scroll: old Row 1 → row 0, old Row 2 → row 1, blank → row 2
            // Then "Row 3" writes on row 2
            assertTrue(small.getRow(0).trim().startsWith("Row 1"),
                    "Row 0 after scroll: " + small.getRow(0).trim());
            assertTrue(small.getRow(1).trim().startsWith("Row 2"),
                    "Row 1 after scroll: " + small.getRow(1).trim());
            assertTrue(small.getRow(2).contains("Row 3"),
                    "Row 2 after scroll should have Row 3: " + small.getRow(2).trim());
        }

        @Test
        void reverseIndexRI() {
            VirtualTerminal small = new VirtualTerminal(3, 20);
            small.feed("Row 0\nRow 1\nRow 2");
            small.feed("\033[1;1H"); // go to top
            small.feed("\033M");     // RI — reverse index
            // Should scroll down, inserting blank at top
            assertEquals("", small.getRow(0).trim());
            assertEquals("Row 0", small.getRow(1).trim());
            assertEquals("Row 1", small.getRow(2).trim());
        }
    }

    // ========================================================================
    // Scroll region (DECSTBM)
    // ========================================================================

    @Nested
    class ScrollRegion {

        @Test
        void scrollRegionLimitsScrolling() {
            VirtualTerminal vt5 = new VirtualTerminal(5, 40);
            // Set scroll region to rows 2-4 (1-indexed)
            vt5.feed("\033[2;4r");
            // Fill the region
            vt5.feed("\033[2;1H"); // row 2
            vt5.feed("Region A\nRegion B\nRegion C");
            // Cursor is at bottom of region. Another LF should only scroll the region.
            vt5.feed("\nRegion D");
            // Row 0 should be untouched (blank)
            assertEquals("", vt5.getRow(0).trim());
            // Region scrolled: B, C, D
            assertEquals("Region B", vt5.getRow(1).trim());
            assertEquals("Region C", vt5.getRow(2).trim());
            assertEquals("Region D", vt5.getRow(3).trim());
            // Row 4 (below region) untouched
            assertEquals("", vt5.getRow(4).trim());
        }

        @Test
        void scrollRegionResetOnFullRange() {
            VirtualTerminal vt5 = new VirtualTerminal(5, 40);
            vt5.feed("\033[2;4r"); // set region
            vt5.feed("\033[r");     // reset (default = full screen)
            // Cursor should be at 0,0 after DECSTBM reset
            assertEquals(0, vt5.getCursorRow());
            assertEquals(0, vt5.getCursorCol());
        }
    }

    // ========================================================================
    // Alternate screen buffer
    // ========================================================================

    @Nested
    class AlternateScreen {

        @Test
        void enterAlternateScreen1049() {
            vt.feed("Main content");
            vt.feed("\033[?1049h"); // enter alternate screen
            assertTrue(vt.isInAlternateScreen());
            // Alternate screen should be clear
            assertEquals("", vt.getRow(0).trim());
        }

        @Test
        void leaveAlternateScreen1049() {
            vt.feed("Main content");
            vt.feed("\033[?1049h"); // enter
            vt.feed("Alt content");
            vt.feed("\033[?1049l"); // leave
            assertFalse(vt.isInAlternateScreen());
            // Main screen content should be restored
            assertEquals("Main content", vt.getRow(0).trim());
        }

        @Test
        void alternateScreen47() {
            vt.feed("Main");
            vt.feed("\033[?47h"); // enter
            assertTrue(vt.isInAlternateScreen());
            vt.feed("\033[?47l"); // leave
            assertFalse(vt.isInAlternateScreen());
            assertEquals("Main", vt.getRow(0).trim());
        }

        @Test
        void alternateScreen1047() {
            vt.feed("Main");
            vt.feed("\033[?1047h"); // enter
            assertTrue(vt.isInAlternateScreen());
            vt.feed("Alt");
            vt.feed("\033[?1047l"); // leave — clears alt before leaving
            assertFalse(vt.isInAlternateScreen());
            assertEquals("Main", vt.getRow(0).trim());
        }

        @Test
        void cursorSavedAndRestoredWith1049() {
            vt.feed("\033[5;10H"); // position cursor
            vt.feed("\033[?1049h"); // enter — should save cursor
            assertEquals(0, vt.getCursorRow()); // reset on alt screen
            assertEquals(0, vt.getCursorCol());
            vt.feed("\033[?1049l"); // leave — should restore cursor
            assertEquals(4, vt.getCursorRow());
            assertEquals(9, vt.getCursorCol());
        }

        @Test
        void doubleEnterNoOp() {
            vt.feed("Main");
            vt.feed("\033[?1049h");
            vt.feed("\033[?1049h"); // second enter is no-op
            assertTrue(vt.isInAlternateScreen());
            vt.feed("\033[?1049l");
            assertFalse(vt.isInAlternateScreen());
            assertEquals("Main", vt.getRow(0).trim());
        }

        @Test
        void scrollRegionResetOnAlternateScreen() {
            vt.feed("\033[5;20r"); // set scroll region
            vt.feed("\033[?1049h"); // enter alt
            // Scroll region should be reset to full screen
            vt.feed("\033[?1049l"); // leave
            // Verify we can write to all rows without scroll region artifacts
            vt.feed("\033[24;1H");
            vt.feed("Bottom row");
            assertEquals("Bottom row", vt.getRow(23).trim());
        }
    }

    // ========================================================================
    // Full reset
    // ========================================================================

    @Nested
    class FullReset {

        @Test
        void risResetsEverything() {
            vt.feed("Content");
            vt.feed("\033[?1049h"); // enter alt screen
            vt.feed("\033c");        // RIS — full reset
            assertFalse(vt.isInAlternateScreen());
            assertEquals(0, vt.getCursorRow());
            assertEquals(0, vt.getCursorCol());
            assertEquals("", vt.getRow(0).trim());
        }
    }

    // ========================================================================
    // SGR (colors) — should be ignored, no crash
    // ========================================================================

    @Nested
    class SgrHandling {

        @Test
        void sgrIgnoredButTextRendered() {
            vt.feed("\033[1;31mHello\033[0m World");
            assertTrue(vt.getRow(0).contains("Hello"));
            assertTrue(vt.getRow(0).contains("World"));
        }

        @Test
        void complexSgrSequences() {
            // Bold + underline + 256-color + RGB color
            vt.feed("\033[1;4;38;5;196;48;2;0;128;255mColored\033[0m");
            assertTrue(vt.getRow(0).contains("Colored"));
        }
    }

    // ========================================================================
    // Content extraction — getNewText()
    // ========================================================================

    @Nested
    class ContentExtraction {

        @Test
        void getNewTextReturnsNewContent() {
            vt.feed("Hello world from the terminal emulator");
            String text = vt.getNewText();
            assertTrue(text.contains("Hello world"), "Should extract new text: " + text);
        }

        @Test
        void getNewTextDeltaTracking() {
            vt.feed("First line of real content here");
            String first = vt.getNewText();
            assertTrue(first.contains("First line"), "First call: " + first);

            // Same content — no new text
            String second = vt.getNewText();
            assertEquals("", second, "No change should produce empty: " + second);
        }

        @Test
        void getNewTextTracksMultipleRows() {
            vt.feed("Row zero has some real content\nRow one also has real content");
            String text = vt.getNewText();
            assertTrue(text.contains("Row zero"), "Should contain row 0");
            assertTrue(text.contains("Row one"), "Should contain row 1");
        }

        @Test
        void getNewTextFiltersShortFragments() {
            // Fragments shorter than 5 chars or without 3+ char words should be filtered
            vt.feed("AB");
            String text = vt.getNewText();
            assertEquals("", text, "Short fragments should be filtered");
        }

        @Test
        void getAllContentTextFullScrape() {
            vt.feed("Content line one with real words here\nContent line two also with words");
            vt.getNewText(); // consume the delta
            // getAllContentText should still return everything
            String all = vt.getAllContentText();
            assertTrue(all.contains("Content line one"), "Full scrape should return all");
            assertTrue(all.contains("Content line two"), "Full scrape should return all");
        }

        @Test
        void getAllContentTextDoesNotAffectDelta() {
            vt.feed("Some real content with multiple words");
            String all = vt.getAllContentText();
            assertFalse(all.isEmpty(), "Should have content");
            // Delta should still be available since getAllContentText doesn't snapshot
            String delta = vt.getNewText();
            assertFalse(delta.isEmpty(), "Delta should not be consumed by getAllContentText");
        }
    }

    // ========================================================================
    // TUI chrome filtering
    // ========================================================================

    @Nested
    class TuiChromeFiltering {

        @Test
        void filtersCtrCLine() {
            vt.feed("ctrl+c to cancel or /quit to exit");
            assertEquals("", vt.getNewText());
        }

        @Test
        void filtersGeneratingSpinner() {
            vt.feed("Generating... please wait");
            assertEquals("", vt.getNewText());
        }

        @Test
        void filtersThinkingSpinner() {
            vt.feed("Thinking... max effort");
            assertEquals("", vt.getNewText());
        }

        @Test
        void filtersOpenCodeSessionInfo() {
            vt.feed("opencode session 12345 model gpt-4");
            assertEquals("", vt.getNewText());
        }

        @Test
        void filtersTokenCostLine() {
            vt.feed("cost: $0.05 tokens: 1234 input/output");
            assertEquals("", vt.getNewText());
        }

        @Test
        void filtersInputPromptLine() {
            vt.feed("Enter a prompt or type a message here");
            assertEquals("", vt.getNewText());
        }

        @Test
        void filtersHelpHints() {
            vt.feed("/help for commands /agent to switch /compact context");
            assertEquals("", vt.getNewText());
        }

        @Test
        void filtersEscCancelHints() {
            vt.feed("Press esc to cancel the current operation");
            assertEquals("", vt.getNewText());
        }

        @Test
        void filtersSketchingSpinner() {
            vt.feed("Sketching the solution plan now");
            assertEquals("", vt.getNewText());
        }

        @Test
        void passesRealLlmContent() {
            vt.feed("I'll help you fix that authentication bug in the login flow.");
            String text = vt.getNewText();
            assertTrue(text.contains("authentication bug"), "Should pass real content: " + text);
        }

        @Test
        void passesCodeContent() {
            vt.feed("public class MyService implements ServiceInterface {");
            String text = vt.getNewText();
            assertTrue(text.contains("MyService"), "Should pass code: " + text);
        }

        @Test
        void filtersDecorativeRows() {
            // Box-drawing characters dominating the row
            vt.feed("────────────────────────────────");
            String text = vt.getAllContentText();
            assertEquals("", text, "Should filter decorative rows");
        }

        @Test
        void passesDecorativeRowsWithText() {
            // Mix of box drawing and real text should pass if enough text
            vt.feed("│ File: src/main/java/Foo.java │");
            String text = vt.getAllContentText();
            // The row has enough alnum content to pass through
            assertFalse(text.isEmpty(), "Row with real text should pass");
        }
    }

    // ========================================================================
    // Screen hash stability detection
    // ========================================================================

    @Nested
    class ScreenHash {

        @Test
        void hashChangesOnUpdate() {
            long hash1 = vt.getScreenHash();
            vt.feed("New content");
            long hash2 = vt.getScreenHash();
            assertNotEquals(hash1, hash2, "Hash should change after content update");
        }

        @Test
        void hashStableWhenNoChange() {
            vt.feed("Content");
            long hash1 = vt.getScreenHash();
            long hash2 = vt.getScreenHash();
            assertEquals(hash1, hash2, "Hash should be stable without changes");
        }

        @Test
        void hashDiffersForDifferentContent() {
            VirtualTerminal vt1 = new VirtualTerminal(10, 40);
            VirtualTerminal vt2 = new VirtualTerminal(10, 40);
            vt1.feed("Content A");
            vt2.feed("Content B");
            assertNotEquals(vt1.getScreenHash(), vt2.getScreenHash());
        }
    }

    // ========================================================================
    // Terminal response generation
    // ========================================================================

    @Nested
    class TerminalResponses {

        @Test
        void cursorPositionReport() {
            vt.feed("\033[5;10H");
            String response = vt.terminalResponsesFor("\033[6n");
            assertEquals("\033[5;10R", response);
        }

        @Test
        void deviceStatusOk() {
            String response = vt.terminalResponsesFor("\033[5n");
            assertEquals("\033[0n", response);
        }

        @Test
        void primaryDeviceAttributes() {
            String response = vt.terminalResponsesFor("\033[c");
            assertTrue(response.startsWith("\033[?"), "Should return DA1 response");
        }

        @Test
        void secondaryDeviceAttributes() {
            String response = vt.terminalResponsesFor("\033[>c");
            assertTrue(response.startsWith("\033[>"), "Should return DA2 response");
        }

        @Test
        void kittyKeyboardProtocol() {
            String response = vt.terminalResponsesFor("\033[?u");
            assertEquals("\033[?0u", response);
        }

        @Test
        void windowSizeReport() {
            String response = vt.terminalResponsesFor("\033[18t");
            assertTrue(response.contains(String.valueOf(vt.getRows())));
            assertTrue(response.contains(String.valueOf(vt.getCols())));
        }

        @Test
        void oscColorQuery() {
            String response = vt.terminalResponsesFor("\033]10;?\007");
            assertTrue(response.contains("rgb:"), "Should return foreground color");
        }

        @Test
        void emptyDataReturnsEmpty() {
            assertEquals("", vt.terminalResponsesFor(""));
            assertEquals("", vt.terminalResponsesFor(null));
        }

        @Test
        void multipleQueriesInOneData() {
            String response = vt.terminalResponsesFor("\033[6n\033[5n");
            // Should contain both responses
            assertTrue(response.contains("R"), "Should contain CPR");
            assertTrue(response.contains("0n"), "Should contain DSR OK");
        }
    }

    // ========================================================================
    // Private mode handling
    // ========================================================================

    @Nested
    class PrivateModes {

        @Test
        void synchronizedOutputIgnored() {
            // ?2026 — synchronized output
            vt.feed("\033[?2026h");
            vt.feed("Text");
            vt.feed("\033[?2026l");
            assertTrue(vt.getRow(0).contains("Text"));
        }

        @Test
        void bracketedPasteIgnored() {
            vt.feed("\033[?2004h");
            vt.feed("Pasted");
            vt.feed("\033[?2004l");
            assertTrue(vt.getRow(0).contains("Pasted"));
        }

        @Test
        void mouseTrackingIgnored() {
            vt.feed("\033[?1000h");
            vt.feed("Text");
            vt.feed("\033[?1000l");
            assertTrue(vt.getRow(0).contains("Text"));
        }
    }

    // ========================================================================
    // ESC sequences (non-CSI)
    // ========================================================================

    @Nested
    class EscSequences {

        @Test
        void indexIND() {
            vt.feed("\033[3;1H");
            vt.feed("\033D"); // IND — index (linefeed)
            assertEquals(3, vt.getCursorRow());
        }

        @Test
        void nextLineNEL() {
            vt.feed("\033[3;10H");
            vt.feed("\033E"); // NEL — col resets to 0
            assertEquals(3, vt.getCursorRow());
            assertEquals(0, vt.getCursorCol());
        }

        @Test
        void charsetDesignationConsumed() {
            // ESC ( B — set G0 to US-ASCII
            vt.feed("\033(B");
            vt.feed("Text");
            assertTrue(vt.getRow(0).contains("Text"));
        }

        @Test
        void applicationKeypadModeIgnored() {
            vt.feed("\033="); // DECKPAM
            vt.feed("A");
            vt.feed("\033>"); // DECKPNM
            vt.feed("B");
            assertTrue(vt.getRow(0).contains("AB"));
        }
    }

    // ========================================================================
    // 8-bit control codes
    // ========================================================================

    @Nested
    class EightBitControls {

        @Test
        void eightBitCSI() {
            // U+009B is the 8-bit CSI
            vt.feed("Hello");
            vt.feed("\u009B" + "1;1H");
            assertEquals(0, vt.getCursorRow());
            assertEquals(0, vt.getCursorCol());
        }

        @Test
        void eightBitOSC() {
            // U+009D is the 8-bit OSC — should be consumed without crash
            vt.feed("\u009D" + "0;Title\007Text");
            assertTrue(vt.getRow(0).contains("Text"));
        }

        @Test
        void eightBitDCS() {
            // U+0090 is the 8-bit DCS
            vt.feed("\u0090" + "payload\033\\Text");
            assertTrue(vt.getRow(0).contains("Text"));
        }
    }

    // ========================================================================
    // Edge cases and robustness
    // ========================================================================

    @Nested
    class EdgeCases {

        @Test
        void emptyFeedNoOp() {
            vt.feed("");
            assertEquals("", vt.getRow(0).trim());
        }

        @Test
        void partialEscapeSequenceAcrossFeeds() {
            // Split ESC [ 2 J across two feed() calls
            vt.feed("Content");
            vt.feed("\033");
            vt.feed("[2J");
            assertEquals("", vt.getRow(0).trim());
        }

        @Test
        void malformedCsiAborted() {
            // Invalid char in CSI sequence
            vt.feed("\033[\u007F");
            vt.feed("OK");
            assertTrue(vt.getRow(0).contains("OK"), "Should recover from malformed CSI");
        }

        @Test
        void oscTerminatedByBEL() {
            vt.feed("\033]0;Window Title\007Text");
            assertTrue(vt.getRow(0).contains("Text"));
        }

        @Test
        void oscTerminatedByST() {
            vt.feed("\033]0;Window Title\033\\Text");
            assertTrue(vt.getRow(0).contains("Text"));
        }

        @Test
        void dcsConsumedUntilST() {
            vt.feed("\033Psome;payload\033\\After");
            assertTrue(vt.getRow(0).contains("After"));
        }

        @Test
        void cursorClampedToScreenBounds() {
            vt.feed("\033[999;999H");
            assertEquals(23, vt.getCursorRow()); // clamped to rows-1
            assertEquals(79, vt.getCursorCol()); // clamped to cols-1
        }

        @Test
        void getRowOutOfBounds() {
            assertEquals("", vt.getRow(-1));
            assertEquals("", vt.getRow(100));
        }

        @Test
        void fullScreenReturnsContent() {
            vt.feed("Line 0\nLine 1\nLine 2");
            String screen = vt.getFullScreen();
            assertTrue(screen.contains("Line 0"));
            assertTrue(screen.contains("Line 1"));
            assertTrue(screen.contains("Line 2"));
        }

        @Test
        void dimensionsAccessors() {
            assertEquals(24, vt.getRows());
            assertEquals(80, vt.getCols());
        }
    }

    // ========================================================================
    // Simulated real-world agent output
    // ========================================================================

    @Nested
    class RealWorldSimulation {

        @Test
        void claudeStreamJsonToolCallOutput() {
            // Simulates Claude Code's stream-json tool call rendering
            // with cursor positioning and status updates
            vt.feed("\033[H\033[2J"); // clear screen
            vt.feed("  \u2728 Reading file src/main/java/App.java\n");
            vt.feed("  \u2714 Read complete (200 lines)\n");
            vt.feed("\n");
            vt.feed("The file contains a Spring Boot application entry point.\n");

            String text = vt.getAllContentText();
            assertTrue(text.contains("Reading file"), "Should extract tool call info");
            assertTrue(text.contains("Spring Boot"), "Should extract response text");
        }

        @Test
        void tuiScreenRedraws() {
            // Simulates a TUI app redrawing its full screen (Bubble Tea pattern)
            VirtualTerminal tuiVt = new VirtualTerminal(24, 80);

            // First frame — initial render
            tuiVt.feed("\033[?1049h"); // enter alt screen
            tuiVt.feed("\033[H");     // home
            tuiVt.feed("=== OpenCode ===\n");
            tuiVt.feed("Model: gpt-4\n");
            tuiVt.feed("\n");
            tuiVt.feed("This is the first response with real content words.\n");

            String text1 = tuiVt.getNewText();
            assertTrue(text1.contains("first response"), "Should extract from first frame");

            // Second frame — full repaint
            tuiVt.feed("\033[H\033[2J"); // clear
            tuiVt.feed("=== OpenCode ===\n");
            tuiVt.feed("Model: gpt-4\n");
            tuiVt.feed("\n");
            tuiVt.feed("This is the updated response with new real content.\n");

            // Use getAllContentText for full-repaint TUIs
            String text2 = tuiVt.getAllContentText();
            assertTrue(text2.contains("updated response"), "Should extract from repaint");
        }

        @Test
        void statusBarWithScrollRegion() {
            // Simulates a status bar pinned at bottom via scroll region
            VirtualTerminal statusVt = new VirtualTerminal(10, 60);

            // Set scroll region to leave last 2 rows for status
            statusVt.feed("\033[1;8r"); // scroll rows 1-8

            // Write content in the scroll region
            statusVt.feed("\033[1;1H");
            statusVt.feed("Content line 1\n");
            statusVt.feed("Content line 2\n");
            statusVt.feed("Content line 3\n");

            // Write status bar at the bottom (outside scroll region)
            statusVt.feed("\033[9;1H");
            statusVt.feed("──────────────────────────────");
            statusVt.feed("\033[10;1H");
            statusVt.feed("Status: Ready | Agent: coder");

            String fullScreen = statusVt.getFullScreen();
            assertTrue(fullScreen.contains("Content line 1"), "Should have content");
            assertTrue(fullScreen.contains("Status: Ready"), "Should have status bar");
        }
    }
}
