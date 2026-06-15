package ai.kompile.cli.main.chat.tui;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that the VirtualTerminal produces consistent, artifact-free output
 * across all configured CLI agents. Each agent has different terminal dimensions
 * and output modes; this test ensures the VT emulator + content extraction
 * pipeline behaves consistently regardless of configuration.
 *
 * Agent configurations from cli-agents.json:
 * - claude-cli:   50x200, stream-json
 * - codex-cli:    24x80,  tui-scrape
 * - gemini-cli:   50x200, stream-json
 * - opencode-cli: 24x80,  tui-scrape
 * - qwen-cli:     50x200, stream-json
 * - pi-cli:       50x200, stream-json
 */
class VirtualTerminalAgentConsistencyTest {

    // ========================================================================
    // Agent terminal configurations (from cli-agents.json)
    // ========================================================================

    static Stream<Arguments> agentConfigs() {
        return Stream.of(
                Arguments.of("claude-cli",   50,  200),
                Arguments.of("codex-cli",    24,   80),
                Arguments.of("gemini-cli",   50,  200),
                Arguments.of("opencode-cli", 24,   80),
                Arguments.of("qwen-cli",     50,  200),
                Arguments.of("pi-cli",       50,  200)
        );
    }

    static Stream<Arguments> tuiScrapeAgents() {
        return Stream.of(
                Arguments.of("codex-cli",    24,  80),
                Arguments.of("opencode-cli", 24,  80)
        );
    }

    static Stream<Arguments> streamJsonAgents() {
        return Stream.of(
                Arguments.of("claude-cli",  50,  200),
                Arguments.of("gemini-cli",  50,  200),
                Arguments.of("qwen-cli",    50,  200),
                Arguments.of("pi-cli",      50,  200)
        );
    }

    // ========================================================================
    // Cross-agent: basic text extraction consistency
    // ========================================================================

    @ParameterizedTest(name = "{0}: plain text extraction")
    @MethodSource("agentConfigs")
    void plainTextExtractedConsistentlyAcrossAgents(String agent, int rows, int cols) {
        VirtualTerminal vt = new VirtualTerminal(rows, cols);
        String content = "I'll help you fix the authentication bug in the login flow.";
        vt.feed(content);

        String extracted = vt.getNewText();
        assertTrue(extracted.contains("authentication bug"),
                agent + ": should extract plain text content");
    }

    @ParameterizedTest(name = "{0}: multiline text extraction")
    @MethodSource("agentConfigs")
    void multilineTextExtractedConsistently(String agent, int rows, int cols) {
        VirtualTerminal vt = new VirtualTerminal(rows, cols);
        vt.feed("First paragraph with enough words to pass the filter.\n");
        vt.feed("Second paragraph also with enough words to pass.\n");
        vt.feed("Third paragraph completing the response text here.\n");

        String extracted = vt.getNewText();
        assertTrue(extracted.contains("First paragraph"), agent + ": line 1");
        assertTrue(extracted.contains("Second paragraph"), agent + ": line 2");
        assertTrue(extracted.contains("Third paragraph"), agent + ": line 3");
    }

    @ParameterizedTest(name = "{0}: code content extraction")
    @MethodSource("agentConfigs")
    void codeContentExtractedConsistently(String agent, int rows, int cols) {
        VirtualTerminal vt = new VirtualTerminal(rows, cols);
        vt.feed("public class AuthenticationService implements Service {\n");
        vt.feed("    private final UserRepository userRepo;\n");
        vt.feed("    public boolean authenticate(String username, String password) {\n");

        String extracted = vt.getNewText();
        assertTrue(extracted.contains("AuthenticationService"),
                agent + ": should extract class declaration");
        assertTrue(extracted.contains("authenticate"),
                agent + ": should extract method name");
    }

    // ========================================================================
    // Cross-agent: chrome filtering consistency
    // ========================================================================

    @ParameterizedTest(name = "{0}: status bar chrome filtered")
    @MethodSource("agentConfigs")
    void statusBarChromeFilteredConsistently(String agent, int rows, int cols) {
        VirtualTerminal vt = new VirtualTerminal(rows, cols);

        // Common status bar patterns across agents
        String[] chromeLines = {
                "ctrl+c to cancel  /quit to exit  /help for commands",
                "Generating... please wait for the response",
                "Thinking... max effort reasoning enabled",
                "Enter a prompt to get started with the agent",
                "cost: $0.12 tokens: 5432 input/output ratio",
        };

        for (String chrome : chromeLines) {
            VirtualTerminal fresh = new VirtualTerminal(rows, cols);
            fresh.feed(chrome);
            String extracted = fresh.getNewText();
            assertEquals("", extracted,
                    agent + ": should filter chrome line: '" + chrome + "'");
        }
    }

    @ParameterizedTest(name = "{0}: real content passes filter")
    @MethodSource("agentConfigs")
    void realContentPassesFilterConsistently(String agent, int rows, int cols) {
        String[] contentLines = {
                "The issue is in the handleRequest method at line 42.",
                "You need to add a null check before accessing the user object.",
                "Here's the corrected version of the authentication logic:",
                "public static void main(String[] args) throws Exception {",
                "SELECT * FROM users WHERE active = true ORDER BY created_at;",
        };

        for (String content : contentLines) {
            VirtualTerminal fresh = new VirtualTerminal(rows, cols);
            fresh.feed(content);
            String extracted = fresh.getNewText();
            assertFalse(extracted.isEmpty(),
                    agent + ": should pass real content: '" + content + "'");
        }
    }

    // ========================================================================
    // TUI-scrape agents: alternate screen + full repaint patterns
    // ========================================================================

    @Nested
    class TuiScrapeAgents {

        @ParameterizedTest(name = "{0}: Bubble Tea full-screen render")
        @MethodSource("ai.kompile.cli.main.chat.tui.VirtualTerminalAgentConsistencyTest#tuiScrapeAgents")
        void bubbleTeaFullScreenRender(String agent, int rows, int cols) {
            VirtualTerminal vt = new VirtualTerminal(rows, cols);

            // Enter alternate screen (Bubble Tea pattern)
            vt.feed("\033[?1049h");
            assertTrue(vt.isInAlternateScreen(), agent + ": should enter alt screen");

            // Typical Bubble Tea frame: clear + position + draw
            vt.feed("\033[H\033[2J");

            // Header chrome (should be filtered)
            vt.feed("\033[1;1H");
            vt.feed("opencode session abc123 model gpt-4\n");
            vt.feed("─────────────────────────────────────\n");

            // Real content
            vt.feed("\033[3;1H");
            vt.feed("Here is the analysis of your codebase structure.\n");
            vt.feed("The main entry point is in the App.java file.\n");

            // Status bar chrome (should be filtered)
            vt.feed("\033[" + rows + ";1H");
            vt.feed("ctrl+c quit  tab switch view  cost: $0.01");

            String extracted = vt.getAllContentText();
            assertTrue(extracted.contains("analysis of your codebase"),
                    agent + ": should extract content from TUI frame");
            assertFalse(extracted.contains("ctrl+c"),
                    agent + ": should filter status bar chrome");
        }

        @ParameterizedTest(name = "{0}: screen repaint delta consistency")
        @MethodSource("ai.kompile.cli.main.chat.tui.VirtualTerminalAgentConsistencyTest#tuiScrapeAgents")
        void screenRepaintDeltaConsistency(String agent, int rows, int cols) {
            VirtualTerminal vt = new VirtualTerminal(rows, cols);

            // First frame
            vt.feed("The first version of the response with real words.\n");
            String first = vt.getNewText();
            assertFalse(first.isEmpty(), agent + ": first frame should have content");

            // Full repaint — simulate TUI clearing and redrawing
            vt.feed("\033[H\033[2J");
            vt.feed("The updated version with different real words here.\n");

            String delta = vt.getNewText();
            assertTrue(delta.contains("updated version"),
                    agent + ": delta should contain new content after repaint: " + delta);
        }

        @ParameterizedTest(name = "{0}: progressive character painting")
        @MethodSource("ai.kompile.cli.main.chat.tui.VirtualTerminalAgentConsistencyTest#tuiScrapeAgents")
        void progressiveCharacterPainting(String agent, int rows, int cols) {
            VirtualTerminal vt = new VirtualTerminal(rows, cols);

            // Some TUIs paint characters one at a time with cursor positioning
            String text = "Authentication error in the login handler";
            for (int i = 0; i < text.length(); i++) {
                vt.feed("\033[3;" + (i + 1) + "H" + text.charAt(i));
            }

            // The full row should eventually be extractable
            String extracted = vt.getAllContentText();
            assertTrue(extracted.contains("Authentication error"),
                    agent + ": should extract progressively painted text: " + extracted);
        }

        @ParameterizedTest(name = "{0}: alt screen leave restores main content")
        @MethodSource("ai.kompile.cli.main.chat.tui.VirtualTerminalAgentConsistencyTest#tuiScrapeAgents")
        void altScreenLeaveRestoresMainContent(String agent, int rows, int cols) {
            VirtualTerminal vt = new VirtualTerminal(rows, cols);

            // Write to main screen
            vt.feed("Main screen content with enough words here\n");
            vt.getNewText(); // snapshot

            // Enter alt screen, write something
            vt.feed("\033[?1049h");
            vt.feed("Alt screen temporary content displayed\n");

            // Leave alt screen
            vt.feed("\033[?1049l");
            assertFalse(vt.isInAlternateScreen());

            // Main screen content should be intact
            String mainContent = vt.getRow(0);
            assertTrue(mainContent.contains("Main screen content"),
                    agent + ": main screen should be restored after alt screen");
        }
    }

    // ========================================================================
    // Stream-JSON agents: line-oriented output patterns
    // ========================================================================

    @Nested
    class StreamJsonAgents {

        @ParameterizedTest(name = "{0}: streaming token output")
        @MethodSource("ai.kompile.cli.main.chat.tui.VirtualTerminalAgentConsistencyTest#streamJsonAgents")
        void streamingTokenOutput(String agent, int rows, int cols) {
            VirtualTerminal vt = new VirtualTerminal(rows, cols);

            // Stream-JSON agents typically produce line-oriented output
            // that gets fed through VT for terminal response generation
            vt.feed("I'll analyze the codebase and find the bug.\n");
            vt.feed("The issue is in the UserService.authenticate() method.\n");
            vt.feed("It's not checking for null before calling getUser().\n");

            String extracted = vt.getNewText();
            assertTrue(extracted.contains("analyze the codebase"), agent);
            assertTrue(extracted.contains("UserService"), agent);
        }

        @ParameterizedTest(name = "{0}: tool call rendering in wide terminal")
        @MethodSource("ai.kompile.cli.main.chat.tui.VirtualTerminalAgentConsistencyTest#streamJsonAgents")
        void toolCallRenderingInWideTerminal(String agent, int rows, int cols) {
            VirtualTerminal vt = new VirtualTerminal(rows, cols);

            // Simulate tool call output in a wide (200-col) terminal
            String toolLine = "  \uD83D\uDCD6 Read src/main/java/com/example/service/AuthenticationService.java";
            vt.feed(toolLine + "\n");
            vt.feed("  \u2714 Read complete (245 lines, 8.2KB)\n");

            String extracted = vt.getNewText();
            // Wide terminals should not truncate or wrap these lines
            assertTrue(extracted.contains("AuthenticationService"),
                    agent + ": wide terminal should not clip tool paths");
        }

        @ParameterizedTest(name = "{0}: long line handling")
        @MethodSource("ai.kompile.cli.main.chat.tui.VirtualTerminalAgentConsistencyTest#streamJsonAgents")
        void longLineHandling(String agent, int rows, int cols) {
            VirtualTerminal vt = new VirtualTerminal(rows, cols);

            // Line longer than terminal width should wrap
            String longLine = "This is a very long line that exceeds the terminal width ".repeat(5);
            vt.feed(longLine);

            String fullScreen = vt.getFullScreen();
            // Content should be present even if wrapped across rows
            assertTrue(fullScreen.contains("very long line"),
                    agent + ": long lines should be present in full screen");
        }
    }

    // ========================================================================
    // Cross-agent: terminal response consistency
    // ========================================================================

    @ParameterizedTest(name = "{0}: cursor position report correct for dimensions")
    @MethodSource("agentConfigs")
    void cursorPositionReportCorrectForDimensions(String agent, int rows, int cols) {
        VirtualTerminal vt = new VirtualTerminal(rows, cols);

        // Position cursor at bottom-right
        vt.feed("\033[" + rows + ";" + cols + "H");
        assertEquals(rows - 1, vt.getCursorRow(), agent + ": row");
        assertEquals(cols - 1, vt.getCursorCol(), agent + ": col");

        // CPR should report the 1-indexed position
        String response = vt.terminalResponsesFor("\033[6n");
        assertEquals("\033[" + rows + ";" + cols + "R", response,
                agent + ": CPR should match dimensions");
    }

    @ParameterizedTest(name = "{0}: window size report matches dimensions")
    @MethodSource("agentConfigs")
    void windowSizeReportMatchesDimensions(String agent, int rows, int cols) {
        VirtualTerminal vt = new VirtualTerminal(rows, cols);
        String response = vt.terminalResponsesFor("\033[18t");
        String expected = "\033[8;" + rows + ";" + cols + "t";
        assertEquals(expected, response, agent + ": window size should match");
    }

    // ========================================================================
    // Cross-agent: scroll behavior consistency
    // ========================================================================

    @ParameterizedTest(name = "{0}: scroll at bottom preserves content")
    @MethodSource("agentConfigs")
    void scrollAtBottomPreservesContent(String agent, int rows, int cols) {
        VirtualTerminal vt = new VirtualTerminal(rows, cols);

        // Fill screen completely using \r\n (CR+LF) to reset column to 0 per line.
        // Bare \n only does linefeed without carriage return, so col carries over.
        for (int i = 0; i < rows; i++) {
            vt.feed("Row " + i + " content with enough real words\r\n");
        }
        // The last \r\n scrolls once: Row 0 pushed off, Row 1 is now at top.

        // Overflow line triggers another scroll
        vt.feed("Overflow content with real words here.\r\n");

        // Two scrolls: Row 0 and Row 1 pushed off. Top row is "Row 2..."
        String topRow = vt.getRow(0).trim();
        assertTrue(topRow.startsWith("Row 2"),
                agent + ": top row after 2 scrolls should be Row 2, got: " + topRow);
    }

    // ========================================================================
    // Cross-agent: screen hash consistency
    // ========================================================================

    @ParameterizedTest(name = "{0}: screen hash stable for identical content")
    @MethodSource("agentConfigs")
    void screenHashStableForIdenticalContent(String agent, int rows, int cols) {
        VirtualTerminal vt1 = new VirtualTerminal(rows, cols);
        VirtualTerminal vt2 = new VirtualTerminal(rows, cols);

        String content = "Identical content for hash comparison across agents.";
        vt1.feed(content);
        vt2.feed(content);

        assertEquals(vt1.getScreenHash(), vt2.getScreenHash(),
                agent + ": same content should produce same hash");
    }

    // ========================================================================
    // Artifact-specific regression tests
    // ========================================================================

    @Nested
    class ArtifactRegressions {

        @Test
        void noGhostCharactersAfterEraseAndRewrite() {
            // Bug: erased characters leaking into getNewText()
            VirtualTerminal vt = new VirtualTerminal(24, 80);
            vt.feed("ABCDEFGHIJ this is some old content here");
            vt.getNewText(); // snapshot

            // Erase line and rewrite
            vt.feed("\033[1;1H\033[2K");
            vt.feed("New content replaces old content entirely");

            String delta = vt.getNewText();
            assertFalse(delta.contains("ABCDEFGHIJ"),
                    "Old erased content should not appear in delta: " + delta);
            assertTrue(delta.contains("New content"),
                    "New content should appear: " + delta);
        }

        @Test
        void noDoubleOutputFromOverlappingWrites() {
            // Bug: cursor-positioned rewrites of the same row appearing twice
            VirtualTerminal vt = new VirtualTerminal(24, 80);
            vt.feed("First pass content with enough words here");
            vt.getNewText(); // snapshot

            // Overwrite same row at same position
            vt.feed("\033[1;1H");
            vt.feed("Second pass content replacing the first one");

            String delta = vt.getNewText();
            // Should only show the new text, not both
            assertFalse(delta.contains("First pass"),
                    "Overwritten content should not reappear: " + delta);
        }

        @Test
        void noScatteredCharsLeakingAsTuiChrome() {
            // Bug: TUI frameworks painting characters one at a time
            // producing scattered fragments that pass the word filter
            VirtualTerminal vt = new VirtualTerminal(24, 80);

            // Simulate scattered single-character paints (TUI chrome)
            vt.feed("\033[1;1HP");
            vt.feed("\033[1;10HB");
            vt.feed("\033[1;20HD");
            vt.feed("\033[1;30HO");
            vt.feed("\033[1;40Hm");

            String extracted = vt.getNewText();
            // Scattered chars should NOT form meaningful output
            assertEquals("", extracted,
                    "Scattered TUI chrome characters should be filtered: " + extracted);
        }

        @Test
        void spinnerLineNotLeaked() {
            // Bug: animated spinners (braille chars) leaking into output
            VirtualTerminal vt = new VirtualTerminal(24, 80);

            String[] spinnerFrames = {
                    "\r\033[2K  \u2819 Generating...",
                    "\r\033[2K  \u2839 Generating...",
                    "\r\033[2K  \u2838 Generating...",
                    "\r\033[2K  \u283C Generating...",
            };
            for (String frame : spinnerFrames) {
                vt.feed(frame);
            }

            String extracted = vt.getNewText();
            assertEquals("", extracted,
                    "Spinner frames should be filtered as TUI chrome: " + extracted);
        }

        @Test
        void decorativeBorderRowFiltered() {
            // Bug: box-drawing borders appearing in content
            VirtualTerminal vt = new VirtualTerminal(24, 80);
            vt.feed("\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500"
                    + "\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500");

            String extracted = vt.getAllContentText();
            assertEquals("", extracted,
                    "Pure decorative rows should be filtered: " + extracted);
        }

        @Test
        void interleavesChromeAndContentCorrectly() {
            // Real scenario: content interleaved with chrome.
            // Use CUP to position each line on its own row (like a real terminal).
            VirtualTerminal vt = new VirtualTerminal(24, 80);

            vt.feed("\033[1;1HHere is the solution to the problem you described.");
            vt.feed("\033[2;1HGenerating... please wait");
            vt.feed("\033[3;1HThe fix involves changing the return type to boolean.");
            vt.feed("\033[4;1Hctrl+c to cancel /quit to exit");
            vt.feed("\033[5;1HYou also need to update the interface definition.");

            String extracted = vt.getAllContentText();
            assertTrue(extracted.contains("solution to the problem"),
                    "Should pass real content, got: " + extracted);
            assertTrue(extracted.contains("fix involves"),
                    "Should pass real content, got: " + extracted);
            assertTrue(extracted.contains("update the interface"),
                    "Should pass real content, got: " + extracted);
            assertFalse(extracted.contains("Generating..."),
                    "Should filter chrome");
            assertFalse(extracted.contains("ctrl+c"),
                    "Should filter chrome");
        }

        @Test
        void unicodeEllipsisHandledInChromeDetection() {
            // Bug: Unicode ellipsis (U+2026) not matching "..." chrome pattern
            VirtualTerminal vt = new VirtualTerminal(24, 80);
            vt.feed("Generating\u2026 please wait for response");

            String extracted = vt.getNewText();
            assertEquals("", extracted,
                    "Unicode ellipsis chrome should be filtered: " + extracted);
        }

        @Test
        void shiftTabBypassPermissionsFiltered() {
            VirtualTerminal vt = new VirtualTerminal(24, 80);
            vt.feed("shift+tab to bypass permissions for this tool");

            String extracted = vt.getNewText();
            assertEquals("", extracted,
                    "Bypass permissions hint should be filtered");
        }
    }

    // ========================================================================
    // Cross-agent: complete lifecycle simulation
    // ========================================================================

    @ParameterizedTest(name = "{0}: full interaction lifecycle")
    @MethodSource("agentConfigs")
    void fullInteractionLifecycle(String agent, int rows, int cols) {
        VirtualTerminal vt = new VirtualTerminal(rows, cols);

        // Phase 1: Initial clear
        vt.feed("\033[H\033[2J");
        assertEquals("", vt.getNewText(), agent + ": clean after clear");

        // Phase 2: Content arrives
        vt.feed("I'll read the file and analyze the issue.\n");
        String phase2 = vt.getNewText();
        assertTrue(phase2.contains("read the file"), agent + ": phase 2");

        // Phase 3: Tool call output
        vt.feed("  Reading src/main/java/Service.java\n");
        vt.feed("  Read complete with two hundred lines\n");
        String phase3 = vt.getNewText();
        assertFalse(phase3.isEmpty(), agent + ": phase 3 should have content");

        // Phase 4: Response continues
        vt.feed("The bug is on line 42 where the null check is missing.\n");
        String phase4 = vt.getNewText();
        assertTrue(phase4.contains("line 42"), agent + ": phase 4");

        // Phase 5: No new content
        String phase5 = vt.getNewText();
        assertEquals("", phase5, agent + ": no change = empty delta");
    }
}
