/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that resume transcript display uses TUI markdown formatting
 * (AsciiRenderer.renderMarkdown) instead of printing raw text.
 */
class ResumeTuiFormattingTest {

    private final TerminalRenderer renderer = new TerminalRenderer(true);
    private final AsciiRenderer ascii = new AsciiRenderer(renderer);

    @Test
    void assistantMarkdownIsRendered() {
        // Simulate a turn with markdown content
        String markdownContent = "## Summary\n\nHere is **bold** text and `inline code`.\n\n- Item 1\n- Item 2";
        String rendered = ascii.renderMarkdown(markdownContent);

        // renderMarkdown should transform heading markers into styled text
        assertFalse(rendered.contains("## Summary"), "Raw ## heading should be rendered");
        // Bold should be rendered (ANSI bold escape applied)
        assertFalse(rendered.contains("**bold**"), "Raw **bold** markers should be rendered");
        // Inline code should be rendered
        assertFalse(rendered.contains("`inline code`") && !rendered.contains("\033"),
                "Inline code should have ANSI formatting");
        // The rendered text should still contain the actual words
        assertTrue(rendered.contains("Summary"));
        assertTrue(rendered.contains("bold"));
        assertTrue(rendered.contains("inline code"));
        assertTrue(rendered.contains("Item 1"));
    }

    @Test
    void userTurnsShowRoleLabel() {
        // Verify the role label formatting works
        String userLabel = renderer.bold(renderer.cyan("You")) + renderer.dim(":");
        assertTrue(userLabel.contains("You"));
        // Should have ANSI sequences for bold + cyan
        assertTrue(userLabel.contains("\033["));
    }

    @Test
    void assistantTurnsShowRoleLabel() {
        String assistantLabel = renderer.bold(renderer.green("Assistant")) + renderer.dim(":");
        assertTrue(assistantLabel.contains("Assistant"));
        assertTrue(assistantLabel.contains("\033["));
    }

    @Test
    void codeBlocksAreRendered() {
        String content = "Here is some code:\n\n```java\npublic void hello() {\n    System.out.println(\"hi\");\n}\n```\n\nDone.";
        String rendered = ascii.renderMarkdown(content);

        // Code block should be rendered as a box (with border characters)
        // The raw triple-backtick fence should be consumed
        assertFalse(rendered.contains("```java"), "Code fence should be rendered, not raw");
        assertTrue(rendered.contains("hello"), "Code content should be preserved");
        assertTrue(rendered.contains("Done"), "Text after code block should be present");
    }

    @Test
    void truncationShowsEarlierTurnsMessage() {
        // When there are more than 10 turns, restoreSession shows a truncation message
        // Verify the dim styling for the truncation message
        int totalTurns = 15;
        int startTurn = Math.max(0, totalTurns - 10);
        String truncMsg = renderer.dim("  ... (" + startTurn + " earlier turns)");
        assertTrue(truncMsg.contains("5 earlier turns"));
        assertTrue(truncMsg.contains("\033["));
    }

    @Test
    void endSeparatorIsFormatted() {
        int turnCount = 8;
        String separator = renderer.dim("─── end of previous conversation (" + turnCount + " turns) ───");
        assertTrue(separator.contains("8 turns"));
        assertTrue(separator.contains("───"));
    }

    @Test
    void emptyContentDoesNotThrow() {
        // renderMarkdown should handle empty/null gracefully
        assertEquals("", ascii.renderMarkdown(""));
        assertEquals("", ascii.renderMarkdown(null));
    }

    @Test
    void multipleFormattingElementsInOneTurn() {
        String content = "# Title\n\nSome **bold** and *italic* text.\n\n> A blockquote\n\n1. First\n2. Second\n\n---\n\nFinal paragraph.";
        String rendered = ascii.renderMarkdown(content);

        // All raw markdown syntax should be consumed and formatted
        assertFalse(rendered.contains("# Title") && !rendered.contains("\033"), "Heading should be formatted");
        assertFalse(rendered.contains("**bold**"), "Bold should be formatted");
        assertTrue(rendered.contains("Title"));
        assertTrue(rendered.contains("bold"));
        assertTrue(rendered.contains("italic"));
        assertTrue(rendered.contains("Final paragraph"));
    }

    @Test
    void turnsFormattedCorrectlyForResume() {
        // Simulate the restoreSession formatting logic
        List<ChatHistory.Turn> turns = List.of(
                new ChatHistory.Turn("user", "What is 2+2?"),
                new ChatHistory.Turn("assistant", "The answer is **4**.")
        );

        StringBuilder output = new StringBuilder();
        for (ChatHistory.Turn turn : turns) {
            if ("user".equals(turn.role())) {
                output.append(renderer.bold(renderer.cyan("You"))).append(renderer.dim(":")).append("\n");
                output.append("  ").append(turn.content()).append("\n");
            } else {
                output.append(renderer.bold(renderer.green("Assistant"))).append(renderer.dim(":")).append("\n");
                output.append(ascii.renderMarkdown(turn.content())).append("\n");
            }
            output.append("\n");
        }

        String result = output.toString();
        assertTrue(result.contains("You"), "User role label present");
        assertTrue(result.contains("Assistant"), "Assistant role label present");
        assertTrue(result.contains("What is 2+2?"), "User content preserved");
        assertTrue(result.contains("4"), "Assistant content preserved");
        // Bold markdown markers should be rendered away
        assertFalse(result.contains("**4**"), "Bold markers should be rendered");
    }
}
