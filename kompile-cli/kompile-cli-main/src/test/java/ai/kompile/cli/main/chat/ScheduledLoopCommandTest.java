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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduledLoopCommandTest {

    @TempDir
    Path tempDir;

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void loopSlashCommandsKeepSessionAndProjectScopesSeparate() throws Exception {
        String previousHome = System.getProperty("user.home");
        String previousDirectory = System.getProperty("user.dir");
        Path home = tempDir.resolve("home");
        Path project = tempDir.resolve("project");
        Files.createDirectories(home);
        Files.createDirectories(project);
        System.setProperty("user.home", home.toString());
        System.setProperty("user.dir", project.toString());

        String legacyGlobalId;
        ScheduledLoopManager legacyGlobal = new ScheduledLoopManager(
                ignored -> { }, ScheduledLoopManager.stateFileForProject(project));
        try {
            ScheduledLoopManager.ScheduledLoop loop =
                    legacyGlobal.create("3h", "legacy project schedule");
            assertNotNull(loop);
            legacyGlobalId = loop.getId();
            assertTrue(legacyGlobal.pause(legacyGlobalId));
        } finally {
            legacyGlobal.shutdown();
        }

        ChatRepl repl = null;
        try {
            ChatConfig config = new ChatConfig(
                    "custom", null, "loop-command-test", "http://unused.invalid");
            repl = new ChatRepl(
                    null, null, "loop-command-test", false, "default", false, config);
            ChatCommandRouter router = field(repl, "commandRouter", ChatCommandRouter.class);
            ScheduledLoopManager sessionLoops = repl.getScheduledLoopManager();
            ScheduledLoopManager globalLoops = repl.getGlobalScheduledLoopManager();

            assertTrue(sessionLoops.list().isEmpty());
            assertEquals(1, globalLoops.list().size());
            assertEquals("legacy project schedule", globalLoops.get(legacyGlobalId).getPrompt());
            assertEquals(ScheduledLoopManager.ScheduledLoop.LoopStatus.PAUSED,
                    globalLoops.get(legacyGlobalId).getStatus());

            assertTrue(router.handleSlashCommand("/loop add 1h review this conversation"));
            assertTrue(router.handleSlashCommand("/loop-global add 2h review project changes"));
            assertEquals(1, sessionLoops.list().size());
            assertEquals(2, globalLoops.list().size());
            ScheduledLoopManager.ScheduledLoop sessionLoop = sessionLoops.list().get(0);
            ScheduledLoopManager.ScheduledLoop globalLoop = globalLoops.list().get(1);
            String sessionPrefix = sessionLoop.getId().substring(0, 4);
            String globalPrefix = globalLoop.getId().substring(0, 4);

            assertTrue(router.handleSlashCommand("/loop list"));
            assertTrue(router.handleSlashCommand("/loop-global list"));
            assertTrue(router.handleSlashCommand("/loop pause " + sessionPrefix));
            assertEquals(ScheduledLoopManager.ScheduledLoop.LoopStatus.PAUSED,
                    sessionLoops.get(sessionPrefix).getStatus());
            assertEquals(ScheduledLoopManager.ScheduledLoop.LoopStatus.ACTIVE,
                    globalLoops.get(globalPrefix).getStatus());
            assertTrue(router.handleSlashCommand("/loop resume " + sessionPrefix));
            assertEquals(ScheduledLoopManager.ScheduledLoop.LoopStatus.ACTIVE,
                    sessionLoops.get(sessionPrefix).getStatus());
            assertTrue(router.handleSlashCommand("/loop-global pause " + globalPrefix));
            assertEquals(ScheduledLoopManager.ScheduledLoop.LoopStatus.PAUSED,
                    globalLoops.get(globalPrefix).getStatus());
            assertTrue(router.handleSlashCommand("/loop-global resume " + globalPrefix));
            assertEquals(ScheduledLoopManager.ScheduledLoop.LoopStatus.ACTIVE,
                    globalLoops.get(globalPrefix).getStatus());

            assertTrue(router.handleSlashCommand("/loop remove " + sessionPrefix));
            assertTrue(router.handleSlashCommand("/loop add 4h first session clear target"));
            assertTrue(router.handleSlashCommand("/loop add 5h second session clear target"));
            assertEquals(2, sessionLoops.list().size());
            assertTrue(router.handleSlashCommand("/loop clear"));
            assertTrue(sessionLoops.list().isEmpty());
            assertEquals(2, globalLoops.list().size(),
                    "session clear must not remove project-global loops");

            assertTrue(router.handleSlashCommand("/loop-global remove " + globalPrefix));
            assertTrue(router.handleSlashCommand(
                    "/loop-global add 4h project clear target"));
            assertEquals(2, globalLoops.list().size());
            assertTrue(router.handleSlashCommand("/loop-global clear"));
            assertTrue(globalLoops.list().isEmpty());
            assertTrue(Files.isRegularFile(
                    ScheduledLoopManager.stateFileForSession("loop-command-test")));
            assertTrue(Files.isRegularFile(
                    ScheduledLoopManager.stateFileForProject(project)));
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
