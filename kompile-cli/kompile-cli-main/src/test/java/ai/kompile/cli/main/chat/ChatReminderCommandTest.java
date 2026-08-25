package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatReminderCommandTest {

    @TempDir
    Path tempDir;

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void slashCommandsKeepSessionAndProjectScopesSeparateAcrossRepls() throws Exception {
        String previousHome = System.getProperty("user.home");
        Path home = tempDir.resolve("home");
        Path project = tempDir.resolve("project");
        Files.createDirectories(home);
        Files.createDirectories(project);
        System.setProperty("user.home", home.toString());

        ChatRepl first = null;
        ChatRepl resumed = null;
        ChatRepl otherSession = null;
        try {
            first = repl("reminder-session", project);
            ChatCommandRouter router = field(first, "commandRouter", ChatCommandRouter.class);

            assertTrue(router.handleSlashCommand("/reminder Keep this turn concise"));
            assertTrue(router.handleSlashCommand("/reminder-global Preserve generated files"));
            assertEquals(List.of("Keep this turn concise"),
                    first.getReminderManager().list(ReminderManager.Scope.SESSION));
            assertEquals(List.of("Preserve generated files"),
                    first.getReminderManager().list(ReminderManager.Scope.PROJECT));

            resumed = repl("reminder-session", project);
            assertEquals(List.of("Keep this turn concise"),
                    resumed.getReminderManager().list(ReminderManager.Scope.SESSION));
            assertEquals(List.of("Preserve generated files"),
                    resumed.getReminderManager().list(ReminderManager.Scope.PROJECT));

            otherSession = repl("different-session", project);
            assertTrue(otherSession.getReminderManager()
                    .list(ReminderManager.Scope.SESSION).isEmpty());
            assertEquals(List.of("Preserve generated files"),
                    otherSession.getReminderManager().list(ReminderManager.Scope.PROJECT));

            assertTrue(router.handleSlashCommand("/reminder clear"));
            assertTrue(first.getReminderManager()
                    .list(ReminderManager.Scope.SESSION).isEmpty());
            assertEquals(List.of("Preserve generated files"),
                    first.getReminderManager().list(ReminderManager.Scope.PROJECT));
        } finally {
            close(first);
            close(resumed);
            close(otherSession);
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
            ChatCompleter.setActivity(null);
        }
    }

    private static ChatRepl repl(String sessionId, Path project) {
        ChatConfig config = new ChatConfig(
                "custom", null, "reminder-command-test", "http://unused.invalid");
        return new ChatRepl(
                null, null, sessionId, false, "default", false, config, project);
    }

    private static void close(ChatRepl repl) throws Exception {
        if (repl == null) return;
        field(repl, "processManager", BackgroundProcessManager.class).close();
        DirectLlmClient client = field(repl, "directClient", DirectLlmClient.class);
        if (client != null) client.close();
    }

    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }
}
