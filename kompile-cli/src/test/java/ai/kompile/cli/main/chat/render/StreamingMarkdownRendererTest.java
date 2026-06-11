package ai.kompile.cli.main.chat.render;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests incremental markdown rendering as tokens stream in.
 */
class StreamingMarkdownRendererTest {

    private StreamingMarkdownRenderer renderer;
    private ByteArrayOutputStream captured;
    private PrintStream originalOut;

    @BeforeEach
    void setUp() {
        TerminalRenderer term = new TerminalRenderer(true); // ANSI enabled
        AsciiRenderer ascii = new AsciiRenderer(term, 80);
        renderer = new StreamingMarkdownRenderer(ascii);
        captured = new ByteArrayOutputStream();
        originalOut = System.out;
    }

    private String capture(Runnable action) {
        System.setOut(new PrintStream(captured, true));
        try {
            action.run();
        } finally {
            System.setOut(originalOut);
        }
        return captured.toString();
    }

    @Test
    void plainTextRenderedLineByLine() {
        String output = capture(() -> {
            renderer.accept("Hello ");
            renderer.accept("world\n");
            renderer.accept("Second line\n");
        });
        assertTrue(output.contains("Hello world"), "Should render complete line: " + output);
        assertTrue(output.contains("Second line"), "Should render second line: " + output);
    }

    @Test
    void boldTextFormatted() {
        String output = capture(() -> {
            renderer.accept("This is **bold** text\n");
        });
        // ANSI bold = ESC[1m
        assertTrue(output.contains("\033[1m"), "Should contain ANSI bold: " + output);
        assertTrue(output.contains("bold"), "Should contain the bold text: " + output);
        assertFalse(output.contains("**"), "Should not contain raw markdown: " + output);
    }

    @Test
    void headingFormatted() {
        String output = capture(() -> {
            renderer.accept("## My Heading\n");
        });
        // H2 headings are bold + blue
        assertTrue(output.contains("My Heading"), "Should contain heading text: " + output);
        // Should have ANSI blue (34m) or bold (1m)
        assertTrue(output.contains("\033["), "Should contain ANSI escapes: " + output);
    }

    @Test
    void inlineCodeFormatted() {
        String output = capture(() -> {
            renderer.accept("Use `foo()` here\n");
        });
        // Inline code rendered in yellow with backticks
        assertTrue(output.contains("foo()"), "Should contain code: " + output);
        assertTrue(output.contains("\033["), "Should contain ANSI escapes: " + output);
    }

    @Test
    void codeBlockBufferedAndRendered() {
        String output = capture(() -> {
            renderer.accept("```java\n");
            renderer.accept("int x = 1;\n");
            renderer.accept("int y = 2;\n");
            renderer.accept("```\n");
        });
        // Code block should be rendered as a bordered box
        assertTrue(output.contains("int x = 1"), "Should contain code content: " + output);
        assertTrue(output.contains("╭") || output.contains("+"), "Should contain box border: " + output);
    }

    @Test
    void unorderedListFormatted() {
        String output = capture(() -> {
            renderer.accept("- First item\n");
            renderer.accept("- Second item\n");
        });
        // List items get bullet characters
        assertTrue(output.contains("●") || output.contains("•") || output.contains("-"),
                "Should contain list bullets: " + output);
        assertTrue(output.contains("First item"), "Should contain item text: " + output);
    }

    @Test
    void partialTokensBufferedUntilNewline() {
        String output = capture(() -> {
            renderer.accept("He");
            renderer.accept("ll");
            renderer.accept("o ");
            renderer.accept("wo");
            renderer.accept("rld");
            // No newline yet — nothing should be printed
        });
        assertEquals("", output, "Partial line should not be printed until newline");

        // Now flush
        output = capture(() -> renderer.flush());
        assertTrue(output.contains("Hello world"), "Flush should print partial line: " + output);
    }

    @Test
    void resetClearsState() {
        capture(() -> {
            renderer.accept("```python\n");
            renderer.accept("x = 1\n");
        });
        renderer.reset();

        // After reset, should not be in code block mode
        String output = capture(() -> {
            renderer.accept("Normal text\n");
        });
        // Should render as normal text, not as code block content
        assertTrue(output.contains("Normal text"), "Reset should clear code block state: " + output);
        assertFalse(output.contains("╭"), "Should not render code block border: " + output);
    }

    @Test
    void blockquoteFormatted() {
        String output = capture(() -> {
            renderer.accept("> This is a quote\n");
        });
        assertTrue(output.contains("This is a quote"), "Should contain quote text: " + output);
        // Blockquote uses dim cyan │ bar
        assertTrue(output.contains("│") || output.contains("\033["), "Should contain blockquote marker: " + output);
    }

    @Test
    void horizontalRuleFormatted() {
        String output = capture(() -> {
            renderer.accept("---\n");
        });
        // HR renders as a line of ─ characters
        assertTrue(output.contains("─"), "Should contain horizontal rule: " + output);
    }

    @Test
    void multipleNewlinesInSingleChunk() {
        String output = capture(() -> {
            renderer.accept("Line 1\nLine 2\nLine 3\n");
        });
        assertTrue(output.contains("Line 1"), "Should contain first line: " + output);
        assertTrue(output.contains("Line 2"), "Should contain second line: " + output);
        assertTrue(output.contains("Line 3"), "Should contain third line: " + output);
    }
}
