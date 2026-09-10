package ai.kompile.cli.main.chat.tools;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Manual real-terminal fixture; never contacts providers or reads stored credentials. */
public final class ResumeViewportPty {
    public static void main(String[] args) throws Exception {
        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
            if (args.length > 0 && args[0].equals("auth")) {
                runAuth(terminal, reader);
            } else {
                runResume(terminal, reader);
            }
        }
    }

    private static void runAuth(Terminal terminal, LineReader reader) throws Exception {
        Class<?> type = Class.forName("ai.kompile.cli.main.auth.AuthWizard$TerminalPrompter");
        Constructor<?> constructor = type.getDeclaredConstructor(Terminal.class, LineReader.class);
        constructor.setAccessible(true);
        Object prompter = constructor.newInstance(terminal, reader);
        Method select = type.getDeclaredMethod("select", String.class, List.class);
        select.setAccessible(true);
        List<String> options = new ArrayList<>();
        for (int i = 1; i <= 60; i++) {
            options.add("Provider " + i + " — long authentication provider label");
        }
        Object result = select.invoke(prompter, "Select an authentication provider", options);
        terminal.writer().println("AUTH_RESULT=" + result);
        terminal.flush();
    }

    private static void runResume(Terminal terminal, LineReader reader) throws Exception {
        ResumeTool tool = new ResumeTool(terminal, reader, null, null, null, null);
        List<?> entries = ResumeToolViewportTest.Fixture.entries(61);
        set(tool, "allConversations", entries);
        set(tool, "filteredConversations", entries);
        Method render = ResumeTool.class.getDeclaredMethod("renderMainView");
        render.setAccessible(true);
        Method command = ResumeTool.class.getDeclaredMethod("processCommand", String.class);
        command.setAccessible(true);
        while (true) {
            render.invoke(tool);
            String input = reader.readLine("> ");
            if (input == null || input.equals("q")) {
                break;
            }
            command.invoke(tool, input);
        }
        terminal.writer().println("RESUME_EXIT");
        terminal.flush();
    }

    private static void set(ResumeTool tool, String name, Object value) throws Exception {
        Field field = ResumeTool.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(tool, value);
    }
}
