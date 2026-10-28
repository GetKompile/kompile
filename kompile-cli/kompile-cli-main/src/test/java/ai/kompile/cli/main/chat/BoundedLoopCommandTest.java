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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedLoopCommandTest {

    @TempDir
    Path tempDir;

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void loopBoundedSlashCommandsDriveTheBoundedManager() throws Exception {
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
                    "custom", null, "bounded-loop-command-test", "http://unused.invalid");
            repl = new ChatRepl(
                    null, null, "bounded-loop-command-test", false, "default", false, config);
            ChatCommandRouter router = field(repl, "commandRouter", ChatCommandRouter.class);
            BoundedLoopManager bounded = repl.getBoundedLoopManager();

            assertTrue(bounded.list().isEmpty());

            assertTrue(router.handleSlashCommand("/loop-bounded list"));
            assertTrue(bounded.list().isEmpty(), "list on empty must not create state");

            assertTrue(router.handleSlashCommand(
                    "/loop-bounded add 1h 5 count-bound prompt"));
            assertEquals(1, bounded.list().size());
            BoundedLoopManager.BoundedLoop count = bounded.list().get(0);
            assertEquals(BoundedLoopManager.BoundedLoop.BoundType.COUNT, count.getBoundType());
            assertEquals(5, count.getBoundCount());

            assertTrue(router.handleSlashCommand(
                    "/loop-bounded add 30m 1h duration-bound prompt"));
            assertEquals(2, bounded.list().size());
            BoundedLoopManager.BoundedLoop duration = bounded.list().get(1);
            assertEquals(BoundedLoopManager.BoundedLoop.BoundType.DURATION,
                    duration.getBoundType());
            assertNotNull(duration.getDeadline());

            assertTrue(router.handleSlashCommand("/loop-bounded list"));
            String prefix = count.getId().substring(0, 4);

            assertTrue(router.handleSlashCommand("/loop-bounded pause " + prefix));
            assertEquals(BoundedLoopManager.BoundedLoop.LoopStatus.PAUSED, count.getStatus());
            assertTrue(router.handleSlashCommand("/loop-bounded resume " + prefix));
            assertEquals(BoundedLoopManager.BoundedLoop.LoopStatus.ACTIVE, count.getStatus());
            assertTrue(router.handleSlashCommand("/loop-bounded run " + prefix));
            assertEquals(1, count.getFireCount(), "run counts as one attempt");
            assertTrue(router.handleSlashCommand("/loop-bounded remove " + duration.getId()));
            assertEquals(1, bounded.list().size());

            assertTrue(router.handleSlashCommand("/loop-bounded add 2h 3 shorthand prompt"));
            assertEquals(2, bounded.list().size(), "shorthand must add without the add keyword");

            assertTrue(router.handleSlashCommand("/loop-bounded clear"));
            assertTrue(bounded.list().isEmpty());
            assertTrue(Files.isRegularFile(
                    BoundedLoopManager.stateFileForSession("bounded-loop-command-test")));

            // Invalid bound must not create a loop and must keep the session usable.
            assertTrue(router.handleSlashCommand("/loop-bounded add 5m abc bad bound prompt"));
            assertTrue(bounded.list().isEmpty(), "invalid bound must not create a loop");
        } finally {
            if (repl != null) {
                repl.close();
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
