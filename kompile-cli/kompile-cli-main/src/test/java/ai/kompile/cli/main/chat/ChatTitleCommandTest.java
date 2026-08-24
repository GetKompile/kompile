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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatTitleCommandTest {

    @TempDir
    Path tempDir;

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void titleSlashCommandReplacesPromptDerivedTitle() throws Exception {
        String previousHome = System.getProperty("user.home");
        String previousDirectory = System.getProperty("user.dir");
        Path home = tempDir.resolve("home");
        Path project = tempDir.resolve("project");
        Files.createDirectories(home);
        Files.createDirectories(project);
        System.setProperty("user.home", home.toString());
        System.setProperty("user.dir", project.toString());

        ChatRepl repl = null;
        try {
            ChatConfig config = new ChatConfig(
                    "custom", null, "title-command-test", "http://unused.invalid");
            repl = new ChatRepl(
                    null, null, "title-command-test", false, "default", false, config);
            ChatCommandRouter router = field(repl, "commandRouter", ChatCommandRouter.class);

            repl.initializeSessionTitleFromPrompt("Initial prompt title");
            assertEquals("Initial prompt title", repl.currentSessionTitle());

            assertTrue(router.handleSlashCommand("/title Release planning"));
            assertEquals("Release planning", repl.currentSessionTitle());
            assertTrue(router.handleSlashCommand("/title"));
            assertEquals("Release planning", repl.currentSessionTitle());
        } finally {
            if (repl != null) {
                field(repl, "processManager", BackgroundProcessManager.class).close();
                DirectLlmClient client = field(repl, "directClient", DirectLlmClient.class);
                if (client != null) client.close();
            }
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
            if (previousDirectory == null) System.clearProperty("user.dir");
            else System.setProperty("user.dir", previousDirectory);
            ChatCompleter.setActivity(null);
        }
    }

    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }
}
