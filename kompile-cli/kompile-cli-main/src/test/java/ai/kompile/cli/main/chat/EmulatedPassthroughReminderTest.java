package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.LineDisciplineTerminal;
import org.jline.utils.AttributedString;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmulatedPassthroughReminderTest {

    @Test
    void managedPassthroughDecoratesPromptsButPreservesNativeSlashCommands() {
        EmulatedPassthroughCommand command = new EmulatedPassthroughCommand();
        command.setReminderManager(ReminderManager.inMemory(
                List.of("Keep project context"), List.of("Be explicit")));

        String prompt = command.preparePromptForAgent("Inspect the build");

        assertTrue(prompt.startsWith("<kompile_reminders>"));
        assertTrue(prompt.contains("[project] Keep project context"));
        assertTrue(prompt.contains("[session] Be explicit"));
        assertTrue(prompt.endsWith("Inspect the build"));
        assertEquals("/model", command.preparePromptForAgent("/model"));

        command.rememberSentMessage(prompt);
        assertTrue(command.isSentMessageEcho("Inspect the build"));
        assertTrue(command.isSentMessageEcho("2. [session] Be explicit"));
    }

    @Test
    void replaySeparatesRemindersFromUserTextWithoutChangingStoredPrompt() throws Exception {
        String prompt = decoratedPrompt("Inspect the build");
        ChatHistory.Turn turn = new ChatHistory.Turn("user", prompt);

        String rendered = replay(turn);

        assertTrue(rendered.contains("REMINDERS APPLIED TO THIS PROMPT"));
        assertTrue(rendered.contains("[project] Keep project context"));
        assertFalse(rendered.contains("<kompile_reminders>"));
        assertFalse(rendered.contains("</kompile_reminders>"));
        assertFalse(rendered.contains("The user configured these reminders"));
        assertTrue(rendered.indexOf("REMINDERS APPLIED TO THIS PROMPT") < rendered.indexOf("You"));
        String userSection = rendered.substring(rendered.indexOf("You"));
        assertTrue(userSection.contains("Inspect the build"));
        assertFalse(userSection.contains("Keep project context"));
        assertEquals(prompt, turn.content(), "replay must not remove reminders from model history");
    }

    @Test
    void replaySeparatesRemindersInRawUserTextBlocks() throws Exception {
        String prompt = decoratedPrompt("Continue the task");
        var blocks = new ObjectMapper().createArrayNode();
        blocks.addObject().put("type", "text").put("text", prompt);
        blocks.add("Additional user text");
        ChatHistory.Turn turn = new ChatHistory.Turn("user", "", blocks);

        String rendered = replay(turn);

        assertTrue(rendered.contains("REMINDERS APPLIED TO THIS PROMPT"));
        assertTrue(rendered.contains("Continue the task"));
        assertTrue(rendered.contains("Additional user text"));
        assertFalse(rendered.contains("<kompile_reminders>"));
        assertEquals(prompt, blocks.get(0).path("text").asText());
    }

    @Test
    void replayHandlesRepeatedBlocksWhitespaceAndChatCommandPrefixes() throws Exception {
        for (String prefix : List.of("", "/ask ", "/agent-chat ")) {
            String prompt = "  \n" + prefix + reminderBlock() + "\n\n" + decoratedPrompt("Inspect the build");

            String rendered = replay(new ChatHistory.Turn("user", prompt));

            assertEquals(2, rendered.split("REMINDERS APPLIED TO THIS PROMPT", -1).length - 1);
            assertFalse(rendered.contains("<kompile_reminders>"));
            assertTrue(rendered.contains(prefix + "Inspect the build"), rendered);
        }
    }

    @Test
    void reminderOnlyReplayDoesNotAddAnEmptyUserTurn() throws Exception {
        String rendered = replay(new ChatHistory.Turn("user", reminderBlock()));

        assertTrue(rendered.contains("REMINDERS APPLIED TO THIS PROMPT"));
        assertFalse(rendered.contains("You"));
    }

    @Test
    void replayPreservesLiteralIncompleteAndAssistantReminderContent() throws Exception {
        for (String prompt : List.of(
                "Explain this literal markup: " + decoratedPrompt("example"),
                "<kompile_reminders>\nAn incomplete block",
                "Ordinary user text")) {
            String rendered = replay(new ChatHistory.Turn("user", prompt));
            assertFalse(rendered.contains("REMINDERS APPLIED TO THIS PROMPT"));
            assertTrue(rendered.contains("You"));
            if (prompt.contains("<kompile_reminders>")) {
                assertTrue(rendered.contains("<kompile_reminders>"), rendered);
            }
        }
        String assistant = replay(new ChatHistory.Turn("assistant", decoratedPrompt("Example response")));
        assertFalse(assistant.contains("REMINDERS APPLIED TO THIS PROMPT"));
        assertTrue(assistant.contains("<kompile_reminders>"));
        assertTrue(assistant.contains("Example response"));
    }

    @Test
    void narrowReminderRenderingRetainsPhysicalRowsWithoutChangingDraft() throws Exception {
        try (Terminal terminal = terminal(36, 24)) {
            EmulatedPassthroughCommand command = renderingCommand(terminal);
            String draft = "Continue editing this draft";
            setField(command, "busyInputBuffer", draft);

            captureOutput(() -> invoke(command, "emitReminderSection",
                    new Class<?>[]{String.class}, decoratedPrompt("Continue")));

            List<String> rows = scrollback(command);
            assertTrue(rows.size() > 4, "a multiline reminder must occupy multiple retained rows");
            for (String row : rows) {
                assertFalse(row.contains("\n"), "a retained row must not contain a newline");
                assertFalse(row.contains("\r"), "a retained row must not contain a carriage return");
                assertTrue(AttributedString.fromAnsi(row).columnLength() <= 35,
                        () -> "a retained row must fit without terminal autowrap: columns="
                                + AttributedString.fromAnsi(row).columnLength()
                                + ", row=" + row.replace("\u001B", "<ESC>"));
            }
            String visible = rows.stream().map(row -> AttributedString.fromAnsi(row).toString())
                    .reduce("", String::concat);
            assertEquals(1, occurrences(visible, "Keep project context"));
            assertEquals(draft, field(command, "busyInputBuffer").toString());
        }
    }

    @Test
    void reminderViewportRepaintAfterShrinkCannotWriteIntoInputRows() throws Exception {
        try (Terminal terminal = terminal(100, 30)) {
            EmulatedPassthroughCommand command = renderingCommand(terminal);
            captureOutput(() -> invoke(command, "emitReminderSection",
                    new Class<?>[]{String.class}, decoratedPrompt("Continue")));

            terminal.setSize(new Size(24, 12));
            setField(command, "scrollBottom", 4);
            String output = captureOutput(() -> invoke(command, "redrawScrollViewportContentLocked",
                    new Class<?>[0]));

            assertFalse(output.contains("\n"), "viewport repaint must never scroll or spill via newlines");
            assertFalse(output.contains("\r"));
            Matcher positions = Pattern.compile("\\u001B\\[([0-9]+);1H\\u001B\\[2K").matcher(output);
            List<Integer> starts = new ArrayList<>();
            List<Integer> ends = new ArrayList<>();
            int expectedRow = 1;
            while (positions.find()) {
                assertEquals(expectedRow++, Integer.parseInt(positions.group(1)),
                        "only retained viewport rows may be repainted");
                starts.add(positions.start());
                ends.add(positions.end());
            }
            assertEquals(5, expectedRow, "the four-row viewport must be repainted exactly once");
            for (int i = 0; i < ends.size(); i++) {
                String payload = output.substring(ends.get(i),
                        i + 1 < starts.size() ? starts.get(i + 1) : output.length());
                assertTrue(AttributedString.fromAnsi(payload).columnLength() <= 23,
                        "after shrinking, old reminder rows must not wrap into the input");
            }
        }
    }

    @Test
    void viewportClippingUsesTerminalColumnsAndPreservesAnsiStyle() throws Exception {
        EmulatedPassthroughCommand command = new EmulatedPassthroughCommand();
        String clipped = (String) invoke(command, "fitScrollLine",
                new Class<?>[]{String.class, int.class}, "\u001B[33m界界界\u001B[0m", 4);
        AttributedString rendered = AttributedString.fromAnsi(clipped);
        assertEquals("界界", rendered.toString());
        assertEquals(4, rendered.columnLength());
        assertTrue(clipped.contains("\u001B["), "clipping must retain reminder highlighting");
    }

    private static Terminal terminal(int width, int height) throws Exception {
        Terminal terminal = new LineDisciplineTerminal("reminder-render-test", "xterm",
                new ByteArrayOutputStream(), StandardCharsets.UTF_8);
        terminal.setSize(new Size(width, height));
        return terminal;
    }

    private static EmulatedPassthroughCommand renderingCommand(Terminal terminal) throws Exception {
        EmulatedPassthroughCommand command = new EmulatedPassthroughCommand();
        setField(command, "terminal", terminal);
        setField(command, "drawLock", new Object());
        setField(command, "renderer", new TerminalRenderer(false));
        setField(command, "agent", "opencode");
        setField(command, "messageQueue", new MessageQueue("reminder-render-" + System.nanoTime()));
        setField(command, "scrollBottom", 10);
        setField(command, "inputRows", 3);
        setField(command, "busyInputActive", true);
        setField(command, "providerTurnBackgroundable", true);
        setField(command, "currentStatus", "running");
        return command;
    }

    @SuppressWarnings("unchecked")
    private static List<String> scrollback(EmulatedPassthroughCommand command) throws Exception {
        return (List<String>) field(command, "scrollbackLines");
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args)
            throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static String captureOutput(ThrowingAction action) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            action.run();
        } finally {
            System.setOut(original);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static int occurrences(String text, String match) {
        return (text.length() - text.replace(match, "").length()) / match.length();
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static String reminderBlock() {
        return "<kompile_reminders>\n"
                + "The user configured these reminders. Apply them to this prompt:\n"
                + "1. [project] Keep project context\n</kompile_reminders>";
    }

    private static String decoratedPrompt(String text) {
        return ReminderManager.inMemory(List.of("Keep project context"), List.of())
                .decorateUserTurn(text);
    }

    private static String replay(ChatHistory.Turn... turns) throws Exception {
        EmulatedPassthroughCommand command = new EmulatedPassthroughCommand();
        TerminalRenderer renderer = new TerminalRenderer(false);
        setField(command, "renderer", renderer);
        setField(command, "ascii", new AsciiRenderer(renderer, 100));
        return String.join("\n", command.renderResumedConversation(List.of(turns)));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void managedPassthroughAddsRestartAwarenessOnlyToFirstPromptAfterResume() {
        EmulatedPassthroughCommand command = new EmulatedPassthroughCommand();
        command.setReminderManager(ReminderManager.inMemory(List.of(), List.of()));
        command.scheduleSessionResumeReminderIfNeeded("resumed-session");

        assertEquals("/model", command.preparePromptForAgent("/model"),
                "a local slash command must not consume restart awareness");
        String first = command.preparePromptForAgent("Continue the task");

        assertTrue(first.contains("[system] " + ReminderManager.SESSION_RESUMED_REMINDER));
        assertTrue(first.endsWith("Continue the task"));
        assertEquals("Next message", command.preparePromptForAgent("Next message"));
    }
}
