package ai.kompile.cli.main.manage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that ServiceManager correctly distinguishes JAR files from native
 * executables when building process commands.
 *
 * Uses a real process launch with a trivial script/JAR to verify
 * the command structure is correct.
 */
public class ServiceManagerNativeExeTest {

    @TempDir
    Path tempDir;

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
                "#!/bin/bash\necho \"ARGS: $@\"\n");
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
        // The key assertion: the script ran directly, not via "java -jar"
        assertFalse(stdout.contains("java"), "Should NOT be launched via java: " + stdout);
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
