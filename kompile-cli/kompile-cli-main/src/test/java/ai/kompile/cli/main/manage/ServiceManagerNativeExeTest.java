package ai.kompile.cli.main.manage;

import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import ai.kompile.cli.common.WebChatContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that ServiceManager correctly distinguishes JAR files from native
 * executables when building process commands.
 *
 * Uses a real process launch with a trivial script/JAR to verify
 * the command structure is correct.
 */
@TemporaryUserHome
public class ServiceManagerNativeExeTest {

    @TempDir
    Path tempDir;

    @Test
    public void testNativeWebContextTravelsThroughEnvironmentNotJvmArguments() throws Exception {
        Path script = tempDir.resolve("kompile-chat");
        Files.writeString(script, "#!/bin/bash\n"
                + "echo \"ARGS: $@\"\n"
                + "echo \"WORK: $KOMPILE_CHAT_HANDOFF_WORKING_DIRECTORY\"\n"
                + "echo \"SCOPE: $KOMPILE_CHAT_HANDOFF_CONFIG_SCOPE\"\n"
                + "echo \"MODE: $KOMPILE_CHAT_HANDOFF_MODE\"\n"
                + "echo \"TEAM: $KOMPILE_CHAT_HANDOFF_WORKFLOW\"\n");
        Files.setPosixFilePermissions(script, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        Process process = new ServiceManager().startProjectComponent("test-native-web-instance",
                "kompile-app-chat", script.toFile(), 19879, tempDir.toFile(), List.of(),
                List.of("--server.address=127.0.0.1"), null, false,
                WebChatContext.environment(tempDir, false, "team", true));
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor());
        assertTrue(output.contains("--server.port=19879"), output);
        assertTrue(output.contains("--server.address=127.0.0.1"), output);
        assertFalse(output.contains("-Dkompile.chat.handoff"), output);
        assertTrue(output.contains("WORK: " + tempDir.toRealPath()), output);
        assertTrue(output.contains("SCOPE: project"), output);
        assertTrue(output.contains("MODE: workspace"), output);
        assertTrue(output.contains("TEAM: team"), output);
    }

    @Test
    public void testManagedChildNeverInheritsAnotherWebServersContext() throws Exception {
        Map<String, String> inherited = new HashMap<>(WebChatContext.environment(tempDir, true, "old-team", true));
        inherited.put("UNRELATED", "preserved");
        Map<String, String> child = new HashMap<>(inherited);
        ServiceManager.configureProjectEnvironment(child, null, tempDir.toFile(), Map.of());
        assertFalse(child.containsKey(WebChatContext.ENV_WORKING_DIRECTORY));
        assertFalse(child.containsKey(WebChatContext.ENV_CONFIG_SCOPE));
        assertFalse(child.containsKey(WebChatContext.ENV_WORKFLOW));
        assertFalse(child.containsKey(WebChatContext.ENV_MODE));
        assertEquals("preserved", child.get("UNRELATED"));
        assertEquals(tempDir.toFile().getAbsolutePath(), child.get("KOMPILE_PROJECT_ROOT"));
        // A fresh single/project context replaces all optional enclosing workspace/global values.
        Map<String, String> launch = WebChatContext.environment(tempDir, false, null, false);
        child = new HashMap<>(inherited);
        ServiceManager.configureProjectEnvironment(child, null, tempDir.toFile(), launch);
        assertEquals("project", child.get(WebChatContext.ENV_CONFIG_SCOPE));
        assertEquals("single", child.get(WebChatContext.ENV_MODE));
        assertEquals("", child.get(WebChatContext.ENV_WORKFLOW));
    }

    /**
     * Verify that a native executable is launched directly, NOT via "java -jar".
     * We create a small shell script that prints its own argv, then check the
     * output to confirm "java" was not prepended.
     */
    @Test
    public void testNativeExecutableLaunchedDirectly() throws Exception {
        // Create a fake native executable that prints its arguments and exits
        Path script = tempDir.resolve("kompile-app-main");
        Files.writeString(script,
                "#!/bin/bash\necho \"ARGS: $@\"\necho \"PROJECT_ROOT: $KOMPILE_PROJECT_ROOT\"\n");
        Set<PosixFilePermission> perms = Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE);
        Files.setPosixFilePermissions(script, perms);

        File exe = script.toFile();
        File workDir = tempDir.toFile();

        ServiceManager sm = new ServiceManager();
        // Launch in non-foreground mode so we can capture output
        Process process = sm.startProjectComponent(
                "test-native-instance", "test-type",
                exe, 19876, workDir,
                null, null, null, false);

        // Read stdout
        byte[] output = process.getInputStream().readAllBytes();
        int exitCode = process.waitFor();
        String stdout = new String(output).trim();

        assertEquals(0, exitCode, "Script should exit cleanly");
        assertTrue(stdout.contains("ARGS:"), "Should see args output: " + stdout);
        assertTrue(stdout.contains("--server.port=19876"),
                "Should pass port arg: " + stdout);
        assertTrue(stdout.contains("PROJECT_ROOT: " + tempDir.toAbsolutePath()),
                "Should propagate the managed project launch context: " + stdout);
        // The key assertion: the script ran directly, not via "java -jar"
        assertFalse(stdout.contains("java"), "Should NOT be launched via java: " + stdout);
    }

    @Test
    public void testDistributionNativeReceivesSideLoadedLibraryEnvironment() throws Exception {
        Path distRoot = tempDir.resolve("dist");
        Path binDir = Files.createDirectories(distRoot.resolve("bin"));
        Path libDir = Files.createDirectories(distRoot.resolve("lib"));
        Path script = binDir.resolve("kompile-chat");
        Files.writeString(script, "#!/bin/bash\n"
                + "echo \"ARGS: $@\"\n"
                + "echo \"DIST: $KOMPILE_DIST_HOME\"\n"
                + "echo \"NATIVE: $KOMPILE_NATIVE_LIB_DIR\"\n"
                + "echo \"LD: $LD_LIBRARY_PATH\"\n");
        Files.setPosixFilePermissions(script, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));

        ServiceManager sm = new ServiceManager();
        Process process = sm.startProjectComponent(
                "test-dist-native-instance", "kompile-app-chat",
                script.toFile(), 19878, tempDir.toFile(),
                null, null, null, false);

        String stdout = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor());
        assertTrue(stdout.contains("-Dkompile.dist.home=" + distRoot.toAbsolutePath()));
        assertTrue(stdout.contains("DIST: " + distRoot.toAbsolutePath()));
        assertTrue(stdout.contains("NATIVE: " + libDir.toAbsolutePath()));
        assertTrue(stdout.contains(binDir.toAbsolutePath().toString()));
        assertTrue(stdout.contains(libDir.toAbsolutePath().toString()));
    }

    @Test
    public void testHealthCheckUsesKompileReadinessWhenActuatorIsAbsent() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/actuator/health", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.createContext("/api/setup/status", exchange -> {
            byte[] body = "{\"setupComplete\":true}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            assertTrue(new ServiceManager().checkHealth(server.getAddress().getPort(), 1_000));
        } finally {
            server.stop(0);
        }
    }

    /**
     * Verify that a .jar file IS launched via "java -jar".
     * We create a fake (invalid) JAR; java -jar will fail with an error
     * about an invalid/corrupt jarfile, confirming it was launched via java.
     */
    @Test
    public void testJarFileLaunchedViaJava() throws Exception {
        // Create a fake JAR file (not a real JAR, just to test command building)
        Path fakeJar = tempDir.resolve("kompile-app-main-1.0.0.jar");
        Files.writeString(fakeJar, "not a real jar");

        File jarFile = fakeJar.toFile();
        File workDir = tempDir.toFile();

        ServiceManager sm = new ServiceManager();
        Process process = sm.startProjectComponent(
                "test-jar-instance", "test-type",
                jarFile, 19877, workDir,
                null, null, null, false);

        // ServiceManager merges stderr into stdout via redirectErrorStream(true)
        // when logDir is null and foreground is false, so read from getInputStream()
        byte[] output = process.getInputStream().readAllBytes();
        int exitCode = process.waitFor();
        String combined = new String(output).toLowerCase();

        // java -jar <invalid.jar> produces: "Error: Invalid or corrupt jarfile ..."
        // This confirms it was launched via java, not directly executed
        assertNotEquals(0, exitCode, "Fake JAR should fail to launch");
        assertTrue(
                combined.contains("jarfile") || combined.contains("jar"),
                "Java should complain about invalid JAR. output: " + combined);
    }
}
