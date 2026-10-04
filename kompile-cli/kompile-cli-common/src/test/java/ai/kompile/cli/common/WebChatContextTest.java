package ai.kompile.cli.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class WebChatContextTest {

    @TempDir Path directory;

    @Test
    void nativeEnvironmentAndJarPropertiesResolveTheSameValidatedContext() throws Exception {
        withCleanProperties(() -> {
            Map<String, String> nativeContext = WebChatContext.environment(directory, true, " team ", true);
            assertEquals(directory.toRealPath(), WebChatContext.workingDirectory(nativeContext));
            assertEquals(true, WebChatContext.globalConfig(nativeContext));
            assertEquals("team", WebChatContext.workflow(nativeContext));
            assertEquals(true, WebChatContext.workspace(nativeContext));
            for (String argument : WebChatContext.jvmArguments(directory, true, " team ", true)) {
                int equals = argument.indexOf('=');
                System.setProperty(argument.substring(2, equals), argument.substring(equals + 1));
            }
            assertEquals(WebChatContext.workingDirectory(nativeContext), WebChatContext.workingDirectory(Map.of()));
            assertEquals(WebChatContext.globalConfig(nativeContext), WebChatContext.globalConfig(Map.of()));
            assertEquals(WebChatContext.workflow(nativeContext), WebChatContext.workflow(Map.of()));
            assertEquals(WebChatContext.workspace(nativeContext), WebChatContext.workspace(Map.of()));
            // Existing explicit JVM properties remain authoritative.
            System.setProperty(WebChatContext.CONFIG_SCOPE, "project");
            assertEquals(false, WebChatContext.globalConfig(nativeContext));
        });
    }

    @Test
    void nativeDefaultsAndFailuresNeverBecomeGlobalContext() throws Exception {
        withCleanProperties(() -> {
            Map<String, String> context = WebChatContext.environment(directory, false, null, false);
            assertEquals(false, WebChatContext.globalConfig(context));
            assertEquals(false, WebChatContext.workspace(context));
            assertNull(WebChatContext.workflow(context));
            assertThrows(IllegalStateException.class,
                    () -> WebChatContext.globalConfig(Map.of(WebChatContext.ENV_CONFIG_SCOPE, "bad")));
            assertThrows(IllegalStateException.class,
                    () -> WebChatContext.workspace(Map.of(WebChatContext.ENV_MODE, "bad")));
            assertThrows(IllegalStateException.class,
                    () -> WebChatContext.workflow(Map.of(WebChatContext.ENV_WORKFLOW, "a\nb")));
            assertThrows(java.io.IOException.class, () -> WebChatContext.workingDirectory(
                    Map.of(WebChatContext.ENV_WORKING_DIRECTORY, directory.resolve("missing").toString())));
            assertThrows(IllegalArgumentException.class,
                    () -> WebChatContext.environment(directory, false, "a\nb", true));
            assertThrows(IllegalArgumentException.class,
                    () -> WebChatContext.environment(directory, false, "t".repeat(257), true));
            Map<String, String> inherited = new HashMap<>(context);
            inherited.put("KOMPILE_DIST_HOME", "dist");
            WebChatContext.clearEnvironment(inherited);
            assertEquals(Map.of("KOMPILE_DIST_HOME", "dist"), inherited);
            assertNull(WebChatContext.workingDirectory(inherited));
            assertEquals(false, WebChatContext.globalConfig(inherited));
        });
    }

    private static void withCleanProperties(ContextCheck check) throws Exception {
        Map<String, String> previous = new HashMap<>();
        List<String> keys = List.of(WebChatContext.WORKING_DIRECTORY, WebChatContext.CONFIG_SCOPE,
                WebChatContext.WORKFLOW, WebChatContext.MODE);
        for (String key : keys) {
            previous.put(key, System.getProperty(key));
            System.clearProperty(key);
        }
        try {
            check.run();
        } finally {
            for (String key : keys) {
                if (previous.get(key) == null) System.clearProperty(key);
                else System.setProperty(key, previous.get(key));
            }
        }
    }

    @FunctionalInterface
    private interface ContextCheck { void run() throws Exception; }

    @Test
    void theWorkflowTeamReachesTheWebChatServerOnlyWhenOneIsNamed() throws Exception {
        String real = directory.toRealPath().toString();
        List<String> base = List.of("-D" + WebChatContext.WORKING_DIRECTORY + "=" + real,
                "-D" + WebChatContext.CONFIG_SCOPE + "=project");
        assertEquals(base, WebChatContext.jvmArguments(directory, false));
        assertEquals(base, WebChatContext.jvmArguments(directory, false, " "));
        assertEquals(List.of(base.get(0), base.get(1), "-D" + WebChatContext.WORKFLOW + "=session-team"),
                WebChatContext.jvmArguments(directory, false, " session-team "));
        assertThrows(IllegalArgumentException.class, () -> WebChatContext.jvmArguments(directory, false, "a\nb"));
        assertThrows(IllegalArgumentException.class,
                () -> WebChatContext.jvmArguments(directory, false, "t".repeat(257)));
    }

    @Test void workspaceIsExplicitAndDoesNotChangeSingleChatArguments() throws Exception {
        var single = WebChatContext.jvmArguments(directory, false, null);
        var workspace = WebChatContext.jvmArguments(directory, false, null, true);
        assertEquals(single.size() + 1, workspace.size());
        assertEquals("-D" + WebChatContext.MODE + "=workspace", workspace.get(workspace.size() - 1));
        String previous = System.getProperty(WebChatContext.MODE);
        try {
            System.clearProperty(WebChatContext.MODE);
            assertEquals(false, WebChatContext.workspace());
            System.setProperty(WebChatContext.MODE, "workspace");
            assertEquals(true, WebChatContext.workspace());
            System.setProperty(WebChatContext.MODE, "bad");
            assertThrows(IllegalStateException.class, WebChatContext::workspace);
        } finally {
            if (previous == null) System.clearProperty(WebChatContext.MODE);
            else System.setProperty(WebChatContext.MODE, previous);
        }
    }

    @Test
    void theServerReadsTheTeamNewSessionsStartWith() {
        String previous = System.getProperty(WebChatContext.WORKFLOW);
        try {
            System.clearProperty(WebChatContext.WORKFLOW);
            assertNull(WebChatContext.workflow());
            System.setProperty(WebChatContext.WORKFLOW, " ");
            assertNull(WebChatContext.workflow());
            System.setProperty(WebChatContext.WORKFLOW, " session-team ");
            assertEquals("session-team", WebChatContext.workflow());
            System.setProperty(WebChatContext.WORKFLOW, "a\tb");
            assertThrows(IllegalStateException.class, WebChatContext::workflow);
        } finally {
            if (previous == null) System.clearProperty(WebChatContext.WORKFLOW);
            else System.setProperty(WebChatContext.WORKFLOW, previous);
        }
    }
}
