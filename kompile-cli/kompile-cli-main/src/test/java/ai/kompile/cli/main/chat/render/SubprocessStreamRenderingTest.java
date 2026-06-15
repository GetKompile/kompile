package ai.kompile.cli.main.chat.render;

import ai.kompile.cli.main.chat.agent.SubprocessAgentRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for subprocess streaming rendering improvements:
 * - System prompt / tool listing noise filtering
 * - StreamingMarkdownRenderer output consumer routing
 */
class SubprocessStreamRenderingTest {

    private final PrintStream originalOut = System.out;
    private ByteArrayOutputStream capturedOut;

    @BeforeEach
    void captureSystemOut() {
        capturedOut = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedOut));
    }

    @AfterEach
    void restoreSystemOut() {
        System.setOut(originalOut);
    }

    /** Collect captured output lines (non-empty). */
    private List<String> capturedLines() {
        System.out.flush();
        String raw = capturedOut.toString();
        return Arrays.stream(raw.split("\n", -1))
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toList());
    }

    // ========================================================================
    // Noise filter tests
    // ========================================================================

    @Test
    void filterDetectsMcpToolListings() {
        // Typical garbled output from Claude subprocess
        String noise = "mcp__kompile__task Spawn a single subagent multi_task mcp__kompile__quorum_task Spawn agents for consensus";
        assertTrue(SubprocessAgentRunner.isSystemPromptNoise(noise),
                "Should detect MCP tool listing noise");
    }

    @Test
    void filterDetectsToolDescriptionHeaders() {
        String noise = "Tool Description webfetch Fetch web page content Tool Description websearch Search the web";
        assertTrue(SubprocessAgentRunner.isSystemPromptNoise(noise),
                "Should detect 'Tool Description' repeated headers");
    }

    @Test
    void filterDetectsToolDescriptionFragments() {
        String noise = "Spawn a single subagent Lock files for multi-agent editing Search conversation transcripts";
        assertTrue(SubprocessAgentRunner.isSystemPromptNoise(noise),
                "Should detect dense tool description fragments");
    }

    @Test
    void filterPassesLegitimateText() {
        String legit = "I'll help you fix that bug. Let me read the file first.";
        assertFalse(SubprocessAgentRunner.isSystemPromptNoise(legit),
                "Should not filter legitimate response text");
    }

    @Test
    void filterPassesSingleToolMention() {
        String legit = "You can use the read tool to view the file contents.";
        assertFalse(SubprocessAgentRunner.isSystemPromptNoise(legit),
                "Should not filter text mentioning a single tool");
    }

    @Test
    void filterPassesShortText() {
        assertFalse(SubprocessAgentRunner.isSystemPromptNoise("OK"),
                "Should not filter very short text");
        assertFalse(SubprocessAgentRunner.isSystemPromptNoise(null),
                "Should not filter null");
        assertFalse(SubprocessAgentRunner.isSystemPromptNoise(""),
                "Should not filter empty string");
    }

    @Test
    void filterDetectsClaudeAiMcpTools() {
        String noise = "mcp__claude_ai_Gmail__authenticate and mcp__claude_ai_Google_Calendar__authenticate available";
        assertTrue(SubprocessAgentRunner.isSystemPromptNoise(noise),
                "Should detect claude_ai MCP tool listings");
    }

    @Test
    void filterDetectsCompactToolListing() {
        // The exact format from the user's bug report
        String noise = "ile__tool_call_catalog Catalog of tool calls Web Tool Description "
                + "Agent Orchestration Tool Description mcp__kompile__task Spawn a single subagent";
        assertTrue(SubprocessAgentRunner.isSystemPromptNoise(noise),
                "Should detect compact tool listing from TUI extraction");
    }

    @Test
    void filterDetectsAvailableMcpToolsPreamble() {
        String noise = "Here are the available MCP tools from the kompile server: File I/O max /effort Tool Description max /";
        assertTrue(SubprocessAgentRunner.isSystemPromptNoise(noise),
                "Should detect 'available MCP tools' preamble text");
    }

    @Test
    void filterDetectsSingleToolDescriptionWithCategory() {
        String noise = "File I/O Tool Description mcp__kompile__read Read a file from disk";
        assertTrue(SubprocessAgentRunner.isSystemPromptNoise(noise),
                "Should detect single Tool Description combined with category header");
    }

    @Test
    void filterDetectsMultipleCategoryHeaders() {
        String noise = "File I/O Agent Orchestration Knowledge Execution DevOps tools available";
        assertTrue(SubprocessAgentRunner.isSystemPromptNoise(noise),
                "Should detect multiple tool category headers");
    }

    @Test
    void filterDetectsKompileServerToolReference() {
        String noise = "The kompile server exposes these Tool functions for file operations";
        assertTrue(SubprocessAgentRunner.isSystemPromptNoise(noise),
                "Should detect 'kompile server' combined with 'Tool'");
    }

    // ========================================================================
    // StreamingMarkdownRenderer output tests (captured via System.out)
    // ========================================================================

    @Test
    void customOutputConsumerReceivesLines() {
        TerminalRenderer term = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(term, 80);
        StreamingMarkdownRenderer renderer = new StreamingMarkdownRenderer(ascii);

        renderer.accept("Hello world\n");
        renderer.accept("Second line\n");

        List<String> lines = capturedLines();
        assertEquals(2, lines.size(), "Should emit 2 lines");
        assertTrue(lines.get(0).contains("Hello world"));
        assertTrue(lines.get(1).contains("Second line"));
    }

    @Test
    void customConsumerReceivesBoldFormatting() {
        TerminalRenderer term = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(term, 80);
        StreamingMarkdownRenderer renderer = new StreamingMarkdownRenderer(ascii);

        renderer.accept("This is **bold** text\n");

        List<String> lines = capturedLines();
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("\033[1m"), "Should contain ANSI bold");
        assertTrue(lines.get(0).contains("bold"), "Should contain bold text");
        assertFalse(lines.get(0).contains("**"), "Should not contain raw markdown");
    }

    @Test
    void customConsumerReceivesCodeBlock() {
        TerminalRenderer term = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(term, 80);
        StreamingMarkdownRenderer renderer = new StreamingMarkdownRenderer(ascii);

        renderer.accept("```java\n");
        renderer.accept("int x = 1;\n");
        renderer.accept("```\n");

        List<String> lines = capturedLines();
        assertFalse(lines.isEmpty(), "Should emit code block lines");
        String combined = String.join("\n", lines);
        assertTrue(combined.contains("int x = 1"), "Code block should contain code");
    }

    @Test
    void customConsumerReceivesFlush() {
        TerminalRenderer term = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(term, 80);
        StreamingMarkdownRenderer renderer = new StreamingMarkdownRenderer(ascii);

        renderer.accept("Partial");
        List<String> beforeFlush = capturedLines();
        assertTrue(beforeFlush.isEmpty(), "No output before flush");

        renderer.flush();
        List<String> afterFlush = capturedLines();
        assertFalse(afterFlush.isEmpty(), "Should emit partial line on flush");
        String combined = String.join("", afterFlush);
        assertTrue(combined.contains("Partial"));
    }

    @Test
    void customConsumerReceivesListItems() {
        TerminalRenderer term = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(term, 80);
        StreamingMarkdownRenderer renderer = new StreamingMarkdownRenderer(ascii);

        renderer.accept("- First\n");
        renderer.accept("- Second\n");

        List<String> lines = capturedLines();
        assertEquals(2, lines.size());
        String combined = String.join("\n", lines);
        assertTrue(combined.contains("First"), "Should contain first item");
        assertTrue(combined.contains("Second"), "Should contain second item");
    }

    @Test
    void defaultConstructorStillWorks() {
        // The no-arg constructor should still work (uses System.out)
        TerminalRenderer term = new TerminalRenderer(true);
        AsciiRenderer ascii = new AsciiRenderer(term, 80);
        StreamingMarkdownRenderer renderer = new StreamingMarkdownRenderer(ascii);
        // Just verify it doesn't throw
        assertNotNull(renderer);
    }
}
