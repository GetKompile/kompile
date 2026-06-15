package ai.kompile.cli.main.chat.render;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the AsciiRenderer - rich ASCII/Unicode TUI rendering primitives.
 * Covers panels, tables, markdown, code blocks, diffs, progress bars, trees,
 * text utilities, and cross-agent rendering consistency.
 */
class AsciiRendererTest {

    private AsciiRenderer renderer;
    private AsciiRenderer plainRenderer;

    @BeforeEach
    void setUp() {
        // ANSI-enabled renderer (Unicode box drawing)
        TerminalRenderer ansiTerm = new TerminalRenderer(true);
        renderer = new AsciiRenderer(ansiTerm, 100);

        // Plain text renderer (ASCII fallback)
        TerminalRenderer plainTerm = new TerminalRenderer(false);
        plainRenderer = new AsciiRenderer(plainTerm, 100);
    }

    // ========================================================================
    // Panel rendering
    // ========================================================================

    @Nested
    class PanelRendering {

        @Test
        void basicPanelWithTitle() {
            String panel = renderer.panel("Test Title", "Body content here");
            assertTrue(panel.contains("Test Title"), "Should contain title");
            assertTrue(panel.contains("Body content here"), "Should contain body");
            assertTrue(panel.contains("╭") || panel.contains("┌"),
                    "Should contain top-left border char");
            assertTrue(panel.contains("╯") || panel.contains("┘"),
                    "Should contain bottom-right border char");
        }

        @Test
        void panelWithoutTitle() {
            String panel = renderer.panel(null, "Just body text");
            assertTrue(panel.contains("Just body text"));
            // No title text in the border
            assertFalse(panel.contains("null"));
        }

        @Test
        void panelWithEmptyTitle() {
            String panel = renderer.panel("", "Body here");
            assertTrue(panel.contains("Body here"));
        }

        @Test
        void panelWrapsLongBody() {
            String longBody = "word ".repeat(50);
            String panel = renderer.panel("Title", longBody);
            // Panel should contain the content, wrapped across lines
            assertTrue(panel.contains("word"), "Should contain wrapped content");
            // Multiple lines (border chars appear on each line)
            long borderCount = panel.chars().filter(c -> c == '│').count();
            assertTrue(borderCount > 2, "Long body should create multiple bordered lines");
        }

        @Test
        void panelMultilineBody() {
            String panel = renderer.panel("Title", "Line 1\nLine 2\nLine 3");
            assertTrue(panel.contains("Line 1"));
            assertTrue(panel.contains("Line 2"));
            assertTrue(panel.contains("Line 3"));
        }

        @Test
        void infoPanelHasBlueTheme() {
            String panel = renderer.infoPanel("Info", "Message");
            assertTrue(panel.contains("Info"));
            assertTrue(panel.contains("Message"));
            // With ANSI enabled, should contain blue color code (34m)
            assertTrue(panel.contains("\033[34m"), "Info panel should use blue");
        }

        @Test
        void successPanelHasGreenTheme() {
            String panel = renderer.successPanel("OK", "All good");
            assertTrue(panel.contains("\033[32m"), "Success panel should use green");
        }

        @Test
        void warningPanelHasYellowTheme() {
            String panel = renderer.warningPanel("Warn", "Be careful");
            assertTrue(panel.contains("\033[33m"), "Warning panel should use yellow");
        }

        @Test
        void errorPanelHasRedTheme() {
            String panel = renderer.errorPanel("Error", "Something broke");
            assertTrue(panel.contains("\033[31m"), "Error panel should use red");
        }

        @Test
        void plainTextPanelUsesAsciiChars() {
            String panel = plainRenderer.panel("Title", "Body");
            assertTrue(panel.contains("+"), "ASCII mode should use + for corners");
            assertTrue(panel.contains("-"), "ASCII mode should use - for horizontal");
            assertTrue(panel.contains("|"), "ASCII mode should use | for vertical");
        }

        @Test
        void panelWithSpecificBorderStyle() {
            String panel = renderer.panel("Title", "Body", AsciiRenderer.DOUBLE, null);
            assertTrue(panel.contains("╔"), "Double border should use ╔");
            assertTrue(panel.contains("═"), "Double border should use ═");
            assertTrue(panel.contains("║"), "Double border should use ║");
        }

        @Test
        void panelWithHeavyBorderStyle() {
            String panel = renderer.panel("Title", "Body", AsciiRenderer.HEAVY, null);
            assertTrue(panel.contains("┏"), "Heavy border should use ┏");
            assertTrue(panel.contains("━"), "Heavy border should use ━");
            assertTrue(panel.contains("┃"), "Heavy border should use ┃");
        }
    }

    // ========================================================================
    // Table rendering
    // ========================================================================

    @Nested
    class TableRendering {

        @Test
        void basicTable() {
            List<String> headers = List.of("Name", "Value");
            List<List<String>> rows = List.of(
                    List.of("foo", "123"),
                    List.of("bar", "456")
            );

            String table = renderer.table(headers, rows);
            assertTrue(table.contains("Name"), "Should contain header");
            assertTrue(table.contains("Value"), "Should contain header");
            assertTrue(table.contains("foo"), "Should contain data");
            assertTrue(table.contains("123"), "Should contain data");
            assertTrue(table.contains("bar"), "Should contain data");
            assertTrue(table.contains("456"), "Should contain data");
        }

        @Test
        void tableAutoSizesColumns() {
            List<String> headers = List.of("Short", "A Much Longer Header");
            List<List<String>> rows = List.of(
                    List.of("X", "Y"),
                    List.of("Data", "More data value")
            );

            String table = renderer.table(headers, rows);
            // Both columns should be present and readable
            assertTrue(table.contains("A Much Longer Header"));
            assertTrue(table.contains("More data value"));
        }

        @Test
        void tableWithEmptyRows() {
            List<String> headers = List.of("Col1", "Col2");
            List<List<String>> rows = List.of();
            String table = renderer.table(headers, rows);
            assertTrue(table.contains("Col1"));
            assertTrue(table.contains("Col2"));
        }

        @Test
        void tableWithNullHeaders() {
            String table = renderer.table(null, List.of());
            assertEquals("", table);
        }

        @Test
        void tableWithEmptyHeaders() {
            String table = renderer.table(List.of(), List.of());
            assertEquals("", table);
        }

        @Test
        void tableWithMismatchedColumnCounts() {
            List<String> headers = List.of("A", "B", "C");
            List<List<String>> rows = List.of(
                    List.of("1"), // fewer columns
                    List.of("1", "2", "3", "4") // more columns (extra ignored)
            );
            String table = renderer.table(headers, rows);
            assertTrue(table.contains("A"));
            assertTrue(table.contains("1"));
        }
    }

    // ========================================================================
    // Tree rendering
    // ========================================================================

    @Nested
    class TreeRendering {

        @Test
        void simpleTree() {
            AsciiRenderer.TreeNode root = AsciiRenderer.TreeNode.of("root",
                    AsciiRenderer.TreeNode.of("child1"),
                    AsciiRenderer.TreeNode.of("child2")
            );

            String tree = renderer.tree(root);
            assertTrue(tree.contains("root"));
            assertTrue(tree.contains("child1"));
            assertTrue(tree.contains("child2"));
            assertTrue(tree.contains("├──"), "Should contain tree connector");
            assertTrue(tree.contains("└──"), "Should contain last-child connector");
        }

        @Test
        void nestedTree() {
            AsciiRenderer.TreeNode root = AsciiRenderer.TreeNode.of("root",
                    AsciiRenderer.TreeNode.of("parent",
                            AsciiRenderer.TreeNode.of("grandchild")
                    )
            );

            String tree = renderer.tree(root);
            assertTrue(tree.contains("grandchild"), "Should contain grandchild: " + tree);
            // Nested tree uses │ for vertical connector in the prefix
            String stripped = AsciiRenderer.stripAnsi(tree);
            assertTrue(stripped.contains("│") || stripped.contains("└"),
                    "Nested tree should have tree connectors: " + stripped);
        }

        @Test
        void treeWithIconsAndAnnotations() {
            AsciiRenderer.TreeNode root = AsciiRenderer.TreeNode.of("Project");
            AsciiRenderer.TreeNode child = AsciiRenderer.TreeNode.leaf("file.java", "📄", "200 lines");

            AsciiRenderer.TreeNode full = AsciiRenderer.TreeNode.branch("Project", null, null, child);
            String tree = renderer.tree(full);
            assertTrue(tree.contains("📄"), "Should contain icon");
            assertTrue(tree.contains("200 lines"), "Should contain annotation");
        }
    }

    // ========================================================================
    // Markdown rendering
    // ========================================================================

    @Nested
    class MarkdownRendering {

        @Test
        void headingLevel1() {
            String result = renderer.renderMarkdown("# Main Heading");
            assertTrue(result.contains("Main Heading"));
            // H1 uses cyan and ═ underline
            assertTrue(result.contains("═"), "H1 should have double underline");
        }

        @Test
        void headingLevel2() {
            String result = renderer.renderMarkdown("## Section");
            assertTrue(result.contains("Section"));
            assertTrue(result.contains("─"), "H2 should have single underline");
        }

        @Test
        void headingLevel3() {
            String result = renderer.renderMarkdown("### Subsection");
            assertTrue(result.contains("Subsection"));
            assertTrue(result.contains("\033[1m"), "H3 should be bold");
        }

        @Test
        void boldText() {
            String result = renderer.renderMarkdown("This is **bold** text");
            assertTrue(result.contains("bold"));
            assertTrue(result.contains("\033[1m"), "Should contain ANSI bold");
            assertFalse(result.contains("**"), "Should not contain raw markdown");
        }

        @Test
        void italicText() {
            String result = renderer.renderMarkdown("This is *italic* text");
            assertTrue(result.contains("italic"));
            assertFalse(result.contains("*italic*"), "Should not contain raw markdown");
        }

        @Test
        void inlineCode() {
            String result = renderer.renderMarkdown("Use `println()` here");
            assertTrue(result.contains("println()"));
            assertTrue(result.contains("\033[33m"), "Inline code should be yellow");
        }

        @Test
        void strikethrough() {
            String result = renderer.renderMarkdown("~~removed~~ text");
            assertTrue(result.contains("removed"));
            assertTrue(result.contains("\033[9m"), "Should contain strikethrough");
            assertFalse(result.contains("~~"), "Should not contain raw markdown");
        }

        @Test
        void link() {
            String result = renderer.renderMarkdown("[Click here](https://example.com)");
            assertTrue(result.contains("Click here"));
            assertTrue(result.contains("https://example.com"));
        }

        @Test
        void unorderedList() {
            String result = renderer.renderMarkdown("- First\n- Second\n- Third");
            assertTrue(result.contains("First"));
            assertTrue(result.contains("Second"));
            assertTrue(result.contains("Third"));
            // Should have bullet characters
            assertTrue(result.contains("●") || result.contains("•"));
        }

        @Test
        void orderedList() {
            String result = renderer.renderMarkdown("1. First\n2. Second");
            assertTrue(result.contains("First"));
            assertTrue(result.contains("Second"));
        }

        @Test
        void blockquote() {
            String result = renderer.renderMarkdown("> This is a quote");
            assertTrue(result.contains("This is a quote"));
            assertTrue(result.contains("│"), "Blockquote should have bar");
        }

        @Test
        void horizontalRule() {
            String result = renderer.renderMarkdown("---");
            assertTrue(result.contains("─"), "HR should render as line");
        }

        @Test
        void codeBlock() {
            String result = renderer.renderMarkdown("```java\nint x = 1;\n```");
            assertTrue(result.contains("int x = 1"));
            assertTrue(result.contains("java"), "Should show language label");
            assertTrue(result.contains("╭") || result.contains("+"),
                    "Code block should have border");
        }

        @Test
        void codeBlockWithoutLanguage() {
            String result = renderer.renderMarkdown("```\nplain code\n```");
            assertTrue(result.contains("plain code"));
        }

        @Test
        void multipleElements() {
            String md = "# Title\n\n**Bold** and `code`\n\n- Item 1\n- Item 2\n\n---\n\n> Quote";
            String result = renderer.renderMarkdown(md);
            assertTrue(result.contains("Title"));
            assertTrue(result.contains("Item 1"));
            assertTrue(result.contains("Item 2"));
            assertTrue(result.contains("Quote"));
        }

        @Test
        void emptyMarkdown() {
            assertEquals("", renderer.renderMarkdown(""));
            assertEquals("", renderer.renderMarkdown(null));
        }

        @Test
        void plainMarkdownNoAnsi() {
            String result = plainRenderer.renderMarkdown("**bold** and `code`");
            assertTrue(result.contains("bold"));
            assertTrue(result.contains("code"));
            // Should NOT contain ANSI escape codes
            assertFalse(result.contains("\033["), "Plain mode should not have ANSI");
        }

        @Test
        void nestedListIndentation() {
            String result = renderer.renderMarkdown("- Top\n  - Nested\n    - Deep");
            assertTrue(result.contains("Top"));
            assertTrue(result.contains("Nested"));
            assertTrue(result.contains("Deep"));
        }

        @Test
        void unclosedCodeBlock() {
            String result = renderer.renderMarkdown("```\ncode here\nno closing fence");
            assertTrue(result.contains("code here"));
        }
    }

    // ========================================================================
    // Inline formatting
    // ========================================================================

    @Nested
    class InlineFormatting {

        @Test
        void renderInlineFormattingBold() {
            String result = renderer.renderInlineFormatting("Hello **world**");
            assertTrue(result.contains("world"));
            assertFalse(result.contains("**"));
        }

        @Test
        void renderInlineFormattingMultiple() {
            String result = renderer.renderInlineFormatting("**bold** and `code` and *italic*");
            assertFalse(result.contains("**"));
            assertFalse(result.contains("*italic*"));
        }

        @Test
        void renderInlineFormattingNoMarkdown() {
            String result = renderer.renderInlineFormatting("Plain text without markdown");
            assertEquals("Plain text without markdown", AsciiRenderer.stripAnsi(result));
        }
    }

    // ========================================================================
    // Code block rendering
    // ========================================================================

    @Nested
    class CodeBlockRendering {

        @Test
        void codeBlockWithLanguage() {
            String result = renderer.renderCodeBlock("int x = 1;\nint y = 2;", "java");
            assertTrue(result.contains("int x = 1"));
            assertTrue(result.contains("int y = 2"));
            assertTrue(result.contains("java"), "Should show language");
            assertTrue(result.contains("1"), "Should have line numbers");
            assertTrue(result.contains("2"), "Should have line numbers");
        }

        @Test
        void codeBlockWithoutLanguage() {
            String result = renderer.renderCodeBlock("echo hello", null);
            assertTrue(result.contains("echo hello"));
        }

        @Test
        void codeBlockEmpty() {
            assertEquals("", renderer.renderCodeBlock("", "java"));
            assertEquals("", renderer.renderCodeBlock(null, null));
        }

        @Test
        void codeBlockLineNumbers() {
            String code = "line1\nline2\nline3\nline4\nline5\n"
                    + "line6\nline7\nline8\nline9\nline10";
            String result = renderer.renderCodeBlock(code, null);
            // Line 10 needs 2-digit gutter
            assertTrue(result.contains("10"), "Should show line number 10");
        }

        @Test
        void codeBlockLongLineTruncated() {
            String longLine = "x".repeat(200);
            String result = renderer.renderCodeBlock(longLine, null);
            assertTrue(result.contains("…"), "Long line should be truncated with ellipsis");
        }
    }

    // ========================================================================
    // Diff rendering
    // ========================================================================

    @Nested
    class DiffRendering {

        @Test
        void unifiedDiff() {
            String diff = "--- a/file.java\n+++ b/file.java\n@@ -1,3 +1,3 @@\n"
                    + " unchanged\n-old line\n+new line\n unchanged\n";
            String result = renderer.renderDiff(diff);
            assertTrue(result.contains("old line"), "Should show removed line");
            assertTrue(result.contains("new line"), "Should show added line");
            // Added line should be green (32m)
            assertTrue(result.contains("\033[32m"), "Added should be green");
            // Removed line should be red (31m)
            assertTrue(result.contains("\033[31m"), "Removed should be red");
        }

        @Test
        void diffEmpty() {
            assertEquals("", renderer.renderDiff(""));
            assertEquals("", renderer.renderDiff(null));
        }

        @Test
        void sideBySideDiff() {
            String result = renderer.renderSideBySideDiff(
                    "old line 1\nold line 2",
                    "new line 1\nnew line 2",
                    "Before", "After");
            assertTrue(result.contains("Before"), "Should show old label");
            assertTrue(result.contains("After"), "Should show new label");
            assertTrue(result.contains("old line 1"), "Should show old content");
            assertTrue(result.contains("new line 1"), "Should show new content");
        }
    }

    // ========================================================================
    // Progress bar rendering
    // ========================================================================

    @Nested
    class ProgressBarRendering {

        @Test
        void progressBarZero() {
            String bar = renderer.progressBar("Loading", 0.0, 20);
            assertTrue(bar.contains("Loading"));
            assertTrue(bar.contains("0%"));
            assertTrue(bar.contains("░"), "Empty bar should have empty blocks");
        }

        @Test
        void progressBarFull() {
            String bar = renderer.progressBar("Done", 1.0, 20);
            assertTrue(bar.contains("100%"));
            assertTrue(bar.contains("█"), "Full bar should have filled blocks");
        }

        @Test
        void progressBarHalf() {
            String bar = renderer.progressBar("Progress", 0.5, 20);
            assertTrue(bar.contains("50%"));
            assertTrue(bar.contains("█"), "Should have some filled");
            assertTrue(bar.contains("░"), "Should have some empty");
        }

        @Test
        void progressBarClampedAboveOne() {
            String bar = renderer.progressBar(null, 1.5, 10);
            assertTrue(bar.contains("100%"), "Should clamp to 100%");
        }

        @Test
        void progressBarClampedBelowZero() {
            String bar = renderer.progressBar(null, -0.5, 10);
            assertTrue(bar.contains("0%"), "Should clamp to 0%");
        }

        @Test
        void multiProgressBar() {
            Map<String, Integer> segments = new LinkedHashMap<>();
            segments.put("success", 7);
            segments.put("pending", 3);
            String bar = renderer.multiProgressBar(segments, 20);
            assertTrue(bar.contains("success"), "Should show legend");
            assertTrue(bar.contains("pending"), "Should show legend");
            assertTrue(bar.contains("█"), "Should have filled blocks");
        }

        @Test
        void multiProgressBarEmpty() {
            Map<String, Integer> segments = new LinkedHashMap<>();
            segments.put("none", 0);
            String bar = renderer.multiProgressBar(segments, 20);
            assertTrue(bar.contains("░"), "Empty progress should show empty blocks");
        }
    }

    // ========================================================================
    // File content rendering
    // ========================================================================

    @Nested
    class FileContentRendering {

        @Test
        void fileContentWithFilename() {
            String result = renderer.renderFileContent("int x = 1;\nint y = 2;",
                    "Foo.java", 1, null);
            assertTrue(result.contains("Foo.java"), "Should show filename");
            assertTrue(result.contains("int x = 1"), "Should show content");
            assertTrue(result.contains("1"), "Should show line numbers");
        }

        @Test
        void fileContentWithHighlights() {
            String result = renderer.renderFileContent("line 1\nline 2\nline 3",
                    null, 1, Set.of(2));
            // Highlighted line should use yellow (33m)
            assertTrue(result.contains("\033[33m"), "Highlight should use yellow");
        }

        @Test
        void fileContentWithOffset() {
            String result = renderer.renderFileContent("code here\nmore code",
                    "file.py", 50, null);
            assertTrue(result.contains("50"), "Should start at line 50");
            assertTrue(result.contains("51"), "Should have line 51");
        }

        @Test
        void fileContentEmpty() {
            assertEquals("", renderer.renderFileContent("", null, 1, null));
            assertEquals("", renderer.renderFileContent(null, null, 1, null));
        }
    }

    // ========================================================================
    // Status bar and section header
    // ========================================================================

    @Nested
    class StatusBarAndHeaders {

        @Test
        void statusBar() {
            String bar = renderer.statusBar("Left", "Center", "Right");
            assertTrue(bar.contains("Left"));
            assertTrue(bar.contains("Center"));
            assertTrue(bar.contains("Right"));
            // Should have inverse video with ANSI enabled
            assertTrue(bar.contains("\033[7m"), "Should use inverse video");
        }

        @Test
        void sectionHeader() {
            String header = renderer.sectionHeader("My Section");
            assertTrue(header.contains("My Section"));
            assertTrue(header.contains("─"), "Should have horizontal rules");
        }

        @Test
        void horizontalRule() {
            String hr = renderer.horizontalRule();
            assertTrue(hr.contains("─"));
            // Should be reasonable width (up to 80 or terminalWidth)
            assertTrue(AsciiRenderer.stripAnsi(hr).length() <= 100);
        }
    }

    // ========================================================================
    // Badge rendering
    // ========================================================================

    @Nested
    class BadgeRendering {

        @Test
        void badge() {
            String badge = renderer.badge("OK", "green");
            assertTrue(badge.contains("OK"));
            // With ANSI: inverse video + color
            assertTrue(badge.contains("\033[7m"), "Should use inverse video");
        }

        @Test
        void plainBadge() {
            String badge = plainRenderer.badge("OK", "green");
            assertEquals("[OK]", badge, "Plain mode should use brackets");
        }

        @Test
        void statusBadgeSuccess() {
            String badge = renderer.statusBadge("success");
            assertTrue(badge.contains("success"));
            assertTrue(badge.contains("\033[32m"), "Success should be green");
        }

        @Test
        void statusBadgeError() {
            String badge = renderer.statusBadge("error");
            assertTrue(badge.contains("error"));
            assertTrue(badge.contains("\033[31m"), "Error should be red");
        }

        @Test
        void statusBadgeWarning() {
            String badge = renderer.statusBadge("warning");
            assertTrue(badge.contains("warning"));
            assertTrue(badge.contains("\033[33m"), "Warning should be yellow");
        }

        @Test
        void statusBadgeUnknown() {
            String badge = renderer.statusBadge("custom");
            assertTrue(badge.contains("custom"));
            assertTrue(badge.contains("\033[34m"), "Unknown should default to blue");
        }
    }

    // ========================================================================
    // Banner and welcome panel
    // ========================================================================

    @Nested
    class BannerAndWelcome {

        @Test
        void banner() {
            String banner = renderer.banner();
            assertTrue(banner.contains("KOMPILE") || banner.contains("╦╔═"),
                    "Banner should contain ASCII art");
        }

        @Test
        void welcomePanel() {
            String panel = renderer.welcomePanel("session-123", "claude-cli", true);
            assertTrue(panel.contains("session-123"), "Should show session ID");
            assertTrue(panel.contains("claude-cli"), "Should show agent");
            assertTrue(panel.contains("enabled"), "Should show RAG status");
        }

        @Test
        void welcomePanelWithModes() {
            String panel = renderer.welcomePanelWithModes("s1", "coder", true, false);
            assertTrue(panel.contains("Chat"), "Should show chat mode");
            assertTrue(panel.contains("Passthrough"), "Should show passthrough mode");
            assertTrue(panel.contains("Resume"), "Should show resume mode");
        }

        @Test
        void welcomePanelLocalMode() {
            String panel = renderer.welcomePanelWithModes("s1", "coder", false, true);
            assertTrue(panel.contains("local"), "Should show local mode");
            assertFalse(panel.contains("RAG"), "Local mode should not show RAG");
        }
    }

    // ========================================================================
    // Sparkline and score rendering
    // ========================================================================

    @Nested
    class SparklineAndScores {

        @Test
        void sparkline() {
            double[] values = {1.0, 2.0, 3.0, 4.0, 5.0};
            String result = AsciiRenderer.sparkline(values);
            assertEquals(5, result.length(), "Sparkline should have one char per value");
            // First should be lowest block, last should be highest
            assertEquals('▁', result.charAt(0));
            assertEquals('█', result.charAt(4));
        }

        @Test
        void sparklineAllEqual() {
            double[] values = {3.0, 3.0, 3.0};
            String result = AsciiRenderer.sparkline(values);
            assertEquals(3, result.length());
            // All middle blocks when values are equal
            assertEquals(result.charAt(0), result.charAt(1));
        }

        @Test
        void sparklineEmpty() {
            assertEquals("", AsciiRenderer.sparkline(new double[0]));
            assertEquals("", AsciiRenderer.sparkline(null));
        }

        @Test
        void scoreHeatmapHigh() {
            String result = renderer.scoreHeatmap(4.5);
            assertTrue(result.contains("4.5"));
            assertTrue(result.contains("\033[32m"), "High score should be green");
        }

        @Test
        void scoreHeatmapMedium() {
            String result = renderer.scoreHeatmap(3.0);
            assertTrue(result.contains("3.0"));
            assertTrue(result.contains("\033[33m"), "Medium score should be yellow");
        }

        @Test
        void scoreHeatmapLow() {
            String result = renderer.scoreHeatmap(1.5);
            assertTrue(result.contains("1.5"));
            assertTrue(result.contains("\033[31m"), "Low score should be red");
        }

        @Test
        void scoreTrend() {
            double[] trend = {1.0, 2.0, 3.0, 4.0, 5.0};
            String result = renderer.scoreTrend(trend, 4.5);
            assertFalse(result.isEmpty());
            assertTrue(result.contains("\033[32m"), "High avg should be green");
        }

        @Test
        void coloredSparklineRising() {
            double[] values = {1.0, 2.0, 3.0, 4.0, 5.0};
            String result = renderer.coloredSparkline(values);
            assertTrue(result.contains("\033[32m"), "Rising trend should be green");
        }

        @Test
        void coloredSparklineFalling() {
            double[] values = {5.0, 4.0, 3.0, 2.0, 1.0};
            String result = renderer.coloredSparkline(values);
            assertTrue(result.contains("\033[31m"), "Falling trend should be red");
        }

        @Test
        void coloredSparklineFlat() {
            double[] values = {3.0, 3.0};
            String result = renderer.coloredSparkline(values);
            // Flat trend uses dim
            assertTrue(result.contains("\033[2m"), "Flat trend should be dim");
        }
    }

    // ========================================================================
    // Layout composition
    // ========================================================================

    @Nested
    class LayoutComposition {

        @Test
        void joinVertical() {
            String result = renderer.joinVertical("Block A", "Block B", "Block C");
            assertTrue(result.contains("Block A"));
            assertTrue(result.contains("Block B"));
            assertTrue(result.contains("Block C"));
            assertTrue(result.indexOf("Block A") < result.indexOf("Block B"));
        }

        @Test
        void joinVerticalSkipsNulls() {
            String result = renderer.joinVertical("A", null, "", "B");
            assertTrue(result.contains("A"));
            assertTrue(result.contains("B"));
        }

        @Test
        void joinHorizontal() {
            String result = renderer.joinHorizontal("Left\nLeft2", "Right\nRight2", " | ");
            assertTrue(result.contains("Left"));
            assertTrue(result.contains("Right"));
            assertTrue(result.contains(" | "), "Should contain separator");
        }

        @Test
        void indent() {
            String result = renderer.indent("Line 1\nLine 2", 4);
            assertTrue(result.startsWith("    Line 1"), "Should indent with spaces");
            assertTrue(result.contains("    Line 2"), "All lines should be indented");
        }
    }

    // ========================================================================
    // Key/value list
    // ========================================================================

    @Nested
    class KeyValueList {

        @Test
        void keyValueListAligned() {
            Map<String, String> entries = new LinkedHashMap<>();
            entries.put("Name", "kompile");
            entries.put("Version", "1.0.0");
            entries.put("Status", "running");

            String result = renderer.keyValueList(entries);
            assertTrue(result.contains("Name"));
            assertTrue(result.contains("kompile"));
            assertTrue(result.contains("Version"));
            assertTrue(result.contains("1.0.0"));
        }
    }

    // ========================================================================
    // Text utilities
    // ========================================================================

    @Nested
    class TextUtilities {

        @Test
        void stripAnsiRemovesEscapes() {
            String ansi = "\033[1m\033[31mHello\033[0m World";
            assertEquals("Hello World", AsciiRenderer.stripAnsi(ansi));
        }

        @Test
        void stripAnsiNoOp() {
            assertEquals("Plain text", AsciiRenderer.stripAnsi("Plain text"));
        }

        @Test
        void stripAnsiNull() {
            assertEquals("", AsciiRenderer.stripAnsi(null));
        }

        @Test
        void padRightPadsToWidth() {
            String result = AsciiRenderer.padRight("Hi", 10);
            assertEquals("Hi        ", result);
        }

        @Test
        void padRightNoOpIfLonger() {
            String result = AsciiRenderer.padRight("Hello World", 5);
            assertEquals("Hello World", result);
        }

        @Test
        void padRightAccountsForAnsi() {
            String ansi = "\033[1mHi\033[0m";
            String result = AsciiRenderer.padRight(ansi, 10);
            // Visible length of "Hi" is 2, so 8 spaces should be added
            assertEquals(10, AsciiRenderer.stripAnsi(result).length());
        }

        @Test
        void wrapTextShortLine() {
            List<String> lines = AsciiRenderer.wrapText("Short", 80);
            assertEquals(1, lines.size());
            assertEquals("Short", lines.get(0));
        }

        @Test
        void wrapTextLongLine() {
            String long_ = "word ".repeat(30);
            List<String> lines = AsciiRenderer.wrapText(long_, 40);
            assertTrue(lines.size() > 1, "Long text should wrap to multiple lines");
            for (String line : lines) {
                assertTrue(line.length() <= 40, "Each line should be <= maxWidth");
            }
        }

        @Test
        void wrapTextPreservesNewlines() {
            List<String> lines = AsciiRenderer.wrapText("Line 1\nLine 2\nLine 3", 80);
            assertEquals(3, lines.size());
        }

        @Test
        void wrapTextEmptyInput() {
            List<String> lines = AsciiRenderer.wrapText("", 80);
            assertEquals(1, lines.size());
            assertEquals("", lines.get(0));
        }

        @Test
        void wrapTextNullInput() {
            List<String> lines = AsciiRenderer.wrapText(null, 80);
            assertEquals(1, lines.size());
        }

        @Test
        void wrapTextAnsiNotWrapped() {
            // Lines with ANSI should not be word-wrapped (complex to measure)
            String ansiLine = "\033[1mBold content that is longer than the max width\033[0m";
            List<String> lines = AsciiRenderer.wrapText(ansiLine, 20);
            assertEquals(1, lines.size(), "ANSI lines should not be wrapped");
        }
    }

    // ========================================================================
    // Cross-rendering consistency
    // ========================================================================

    @Nested
    class CrossRenderingConsistency {

        @Test
        void ansiAndPlainProduceSameVisibleText() {
            String ansiResult = AsciiRenderer.stripAnsi(renderer.renderMarkdown("**Hello** world"));
            String plainResult = AsciiRenderer.stripAnsi(plainRenderer.renderMarkdown("**Hello** world"));
            assertEquals(ansiResult, plainResult,
                    "Visible text should be identical in ANSI and plain modes");
        }

        @Test
        void differentWidthsRenderConsistently() {
            TerminalRenderer term = new TerminalRenderer(false);
            AsciiRenderer narrow = new AsciiRenderer(term, 40);
            AsciiRenderer wide = new AsciiRenderer(term, 200);

            String narrowPanel = narrow.panel("Title", "Body content");
            String widePanel = wide.panel("Title", "Body content");

            // Both should contain the same text
            assertTrue(narrowPanel.contains("Title"));
            assertTrue(narrowPanel.contains("Body content"));
            assertTrue(widePanel.contains("Title"));
            assertTrue(widePanel.contains("Body content"));
        }

        @Test
        void borderCharsHaveConsistentParts() {
            // All border character sets should have all 11 required chars
            AsciiRenderer.BorderChars[] sets = {
                    AsciiRenderer.SINGLE, AsciiRenderer.DOUBLE,
                    AsciiRenderer.ROUNDED, AsciiRenderer.HEAVY,
                    AsciiRenderer.ASCII
            };
            for (AsciiRenderer.BorderChars bc : sets) {
                assertNotEquals('\0', bc.topLeft);
                assertNotEquals('\0', bc.topRight);
                assertNotEquals('\0', bc.bottomLeft);
                assertNotEquals('\0', bc.bottomRight);
                assertNotEquals('\0', bc.horizontal);
                assertNotEquals('\0', bc.vertical);
                assertNotEquals('\0', bc.teeRight);
                assertNotEquals('\0', bc.teeLeft);
                assertNotEquals('\0', bc.teeDown);
                assertNotEquals('\0', bc.teeUp);
                assertNotEquals('\0', bc.cross);
            }
        }
    }

    // ========================================================================
    // Agent-specific markdown blocks
    // ========================================================================

    @Nested
    class AgentBlocks {

        @Test
        void toolBlockRendered() {
            String md = "Before\n[tool:Read]\n{\"file\":\"foo.java\"}\n[/tool]\nAfter";
            String result = renderer.renderMarkdown(md);
            assertTrue(result.contains("Read"), "Should render tool name");
            assertTrue(result.contains("foo.java"), "Should render tool body");
        }

        @Test
        void toolResultRendered() {
            String md = "Before\n[tool-result]\nSuccess: 200 lines\n[/tool-result]\nAfter";
            String result = renderer.renderMarkdown(md);
            assertTrue(result.contains("Success: 200 lines"),
                    "Should render tool result body");
        }

        @Test
        void thinkingBlockRendered() {
            String md = "Before\n<thinking>\nLet me analyze this...\n</thinking>\nAfter";
            String result = renderer.renderMarkdown(md);
            assertTrue(result.contains("analyze this"), "Should render thinking body");
        }
    }
}
