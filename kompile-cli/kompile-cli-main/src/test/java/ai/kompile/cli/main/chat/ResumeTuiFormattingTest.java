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
import org.jline.terminal.Size;
import org.jline.terminal.impl.LineDisciplineTerminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that resume transcript display uses TUI markdown formatting
 * (AsciiRenderer.renderMarkdown) instead of printing raw text.
 */
class ResumeTuiFormattingTest {

    private final TerminalRenderer renderer = new TerminalRenderer(true);
    private final AsciiRenderer ascii = new AsciiRenderer(renderer);

    @TempDir
    Path tempDir;

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
    void managedResumeRendersEveryTurnAndPreservesFullMarkdownBodies() throws Exception {
        EmulatedPassthroughCommand command = new EmulatedPassthroughCommand();
        TerminalRenderer plainRenderer = new TerminalRenderer(false);
        setField(command, "renderer", plainRenderer);
        setField(command, "ascii", new AsciiRenderer(plainRenderer, 100));

        List<ChatHistory.Turn> turns = new ArrayList<>();
        String longUserBody = "x".repeat(240) + " FULL_USER_TAIL";
        for (int index = 0; index < 12; index++) {
            if (index % 2 == 0) {
                String content = index == 0
                        ? "## Request zero\n\n" + longUserBody + "\n\n- final request item"
                        : "user turn " + index;
                turns.add(new ChatHistory.Turn("user", content));
            } else {
                turns.add(new ChatHistory.Turn(
                        "assistant", "**assistant turn " + index + "** with `code`"));
            }
        }

        List<String> renderedLines = command.renderResumedConversation(turns);
        String rendered = String.join("\n", renderedLines);

        assertTrue(rendered.contains("Resumed conversation (12 turns)"));
        assertTrue(rendered.contains("FULL_USER_TAIL"),
                "resumed user bodies must not be shortened to a preview");
        assertTrue(renderedLines.stream().filter(line -> line.contains("xxxxxxxxxx")).count() >= 2,
                "long resumed paragraphs must wrap instead of clipping at terminal width");
        assertTrue(renderedLines.stream()
                        .map(AsciiRenderer::stripAnsi)
                        .allMatch(line -> line.length() <= 119),
                "wrapped resume rows must preserve the terminal's safe last-column margin");
        assertTrue(rendered.contains("final request item"));
        assertTrue(rendered.contains("user turn 10"), "older and newer turns must all render");
        assertTrue(rendered.contains("assistant turn 11"));
        assertTrue(rendered.contains("You:"));
        assertTrue(rendered.contains("Assistant:"));
        assertFalse(rendered.contains("## Request zero"), "headings must use Markdown rendering");
        assertFalse(rendered.contains("**assistant turn"), "bold markers must be rendered");
        assertFalse(rendered.contains("earlier turns"), "resume must not replace turns with a summary row");
    }

    @Test
    @SuppressWarnings("unchecked")
    void managedReplayDoesNotAppendDuplicateCopiesToPersistedTranscript() throws Exception {
        String originalHome = System.getProperty("user.home");
        PrintStream originalOut = System.out;
        LineDisciplineTerminal terminal = null;
        ChatHistory activeHistory = null;
        try {
            System.setProperty("user.home", tempDir.toString());
            String sessionId = "managed-resume-no-duplicates";
            String fullUserBody = "## Persisted request\n\n" + "u".repeat(160)
                    + " PERSISTED_USER_TAIL";
            ChatHistory stored = new ChatHistory(sessionId);
            stored.open("", "codex (emulated)", false, tempDir);
            stored.logUserMessage(fullUserBody);
            stored.logAssistantMessage("The **persisted answer** uses `code`.", 0, 0);
            stored.close();

            TerminalRenderer plainRenderer = new TerminalRenderer(false);
            EmulatedPassthroughCommand command = new EmulatedPassthroughCommand();
            terminal = new LineDisciplineTerminal(
                    "resume-replay-test", "xterm", new ByteArrayOutputStream(),
                    StandardCharsets.UTF_8);
            terminal.setSize(new Size(80, 30));
            setField(command, "terminal", terminal);
            setField(command, "drawLock", new Object());
            setField(command, "scrollBottom", 20);
            setField(command, "inputRows", 3);
            setField(command, "agent", "codex");
            setField(command, "messageQueue", new MessageQueue("resume-replay-queue"));
            setField(command, "renderer", plainRenderer);
            setField(command, "ascii", new AsciiRenderer(plainRenderer, 80));

            activeHistory = new ChatHistory(sessionId);
            activeHistory.open("", "codex (emulated)", false, tempDir);
            System.setOut(new PrintStream(
                    new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            Method replay = EmulatedPassthroughCommand.class.getDeclaredMethod(
                    "replayConversationHistory", String.class, ChatHistory.class);
            replay.setAccessible(true);
            replay.invoke(command, sessionId, activeHistory);

            List<String> retained = (List<String>) getField(command, "scrollbackLines");
            String rendered = String.join("\n", retained);
            assertTrue(rendered.contains("PERSISTED_USER_TAIL"));
            assertTrue(rendered.contains("Persisted request"));
            assertFalse(rendered.contains("## Persisted request"));
            assertTrue(rendered.contains("persisted answer"));
            assertTrue(rendered.contains("You:"));
            assertTrue(rendered.contains("Assistant:"));

            activeHistory.close();
            activeHistory = null;
            assertEquals(2, new ChatHistory(sessionId).readTurns().size(),
                    "visually replaying a stored session must not persist its turns again");
        } finally {
            if (activeHistory != null) activeHistory.close();
            System.setOut(originalOut);
            if (terminal != null) terminal.close();
            if (originalHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", originalHome);
            }
        }
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

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
