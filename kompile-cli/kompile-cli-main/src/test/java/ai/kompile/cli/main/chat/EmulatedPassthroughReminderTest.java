package ai.kompile.cli.main.chat;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmulatedPassthroughReminderTest {

    @Test
    void managedPassthroughDecoratesPromptsButPreservesNativeSlashCommands() throws Exception {
        EmulatedPassthroughCommand command = new EmulatedPassthroughCommand();
        setField(command, "reminderManager", ReminderManager.inMemory(
                List.of("Keep project context"), List.of("Be explicit")));

        String prompt = command.preparePromptForAgent("Inspect the build");

        assertTrue(prompt.startsWith("<kompile_reminders>"));
        assertTrue(prompt.contains("[project] Keep project context"));
        assertTrue(prompt.contains("[session] Be explicit"));
        assertTrue(prompt.endsWith("Inspect the build"));
        assertEquals("/model", command.preparePromptForAgent("/model"));

        invokeRememberSentMessage(command, prompt);
        assertTrue(invokeIsSentMessageEcho(command, "Inspect the build"));
        assertTrue(invokeIsSentMessageEcho(command, "2. [session] Be explicit"));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void invokeRememberSentMessage(
            EmulatedPassthroughCommand target, String message) throws Exception {
        var method = EmulatedPassthroughCommand.class
                .getDeclaredMethod("rememberSentMessage", String.class);
        method.setAccessible(true);
        method.invoke(target, message);
    }

    private static boolean invokeIsSentMessageEcho(
            EmulatedPassthroughCommand target, String line) throws Exception {
        var method = EmulatedPassthroughCommand.class
                .getDeclaredMethod("isSentMessageEcho", String.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(target, line);
    }
}
