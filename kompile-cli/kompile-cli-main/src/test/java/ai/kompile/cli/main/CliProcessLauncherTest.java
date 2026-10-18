package ai.kompile.cli.main;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliProcessLauncherTest {

    @TempDir Path tempDir;

    @Test
    void jvmCliDefaultsToSmallStartupHeapAndIdleCollectionWithoutHardMax() {
        assertEquals(List.of("-Xms64m", "-XX:G1PeriodicGCInterval=60000"),
                CliProcessLauncher.jvmMemoryArguments(Map.of()));
        assertEquals(List.of("-Xms128m", "-XX:G1PeriodicGCInterval=0", "-Xmx4g"),
                CliProcessLauncher.jvmMemoryArguments(Map.of("KOMPILE_CLI_INITIAL_HEAP", "128m",
                        "KOMPILE_CLI_IDLE_GC_MS", "0", "KOMPILE_CLI_MAX_HEAP", "4g")));
    }

    @Test
    void distributionLauncherAppliesTheSameMemoryPolicyToChatAndMcp() throws Exception {
        Path repository = Path.of("").toAbsolutePath();
        while (repository != null && !Files.isRegularFile(repository.resolve("kompile-dist/src/main/scripts/kompile.sh"))) {
            repository = repository.getParent();
        }
        assertTrue(repository != null, "repository launcher must be available to this source regression test");
        Path bin = Files.createDirectories(tempDir.resolve("bin"));
        Files.createDirectories(tempDir.resolve("lib"));
        Files.createFile(tempDir.resolve("lib/kompile-cli.jar"));
        Path script = Files.copy(repository.resolve("kompile-dist/src/main/scripts/kompile.sh"), bin.resolve("kompile"));
        Path fakeJava = Files.writeString(tempDir.resolve("fake-java"), "#!/usr/bin/env bash\nprintf '%s\\n' \"$@\"\n");
        assertTrue(fakeJava.toFile().setExecutable(true));
        for (boolean scoped : List.of(false, true)) {
            List<String> command = scoped
                    ? List.of("bash", script.toString(), "mcp-stdio", "--transcript-id", "11111111-1111-1111-1111-111111111111")
                    : List.of("bash", script.toString(), "--version");
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            builder.environment().put("HOME", tempDir.toString());
            builder.environment().put("KOMPILE_JAVA", fakeJava.toString());
            for (String key : List.of("KOMPILE_CLI_INITIAL_HEAP", "KOMPILE_CLI_IDLE_GC_MS", "KOMPILE_CLI_MAX_HEAP", "KOMPILE_TRANSCRIPT_UUID")) {
                builder.environment().remove(key);
            }
            Process process = builder.start();
            try {
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "fake JVM launcher must terminate");
                String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertEquals(0, process.exitValue(), output);
                assertTrue(output.contains("-Xms64m\n"), output);
                assertTrue(output.contains("-XX:G1PeriodicGCInterval=60000\n"), output);
                assertFalse(output.contains("-Xmx"), "no hard maximum until projection peaks are bounded");
            } finally {
                process.destroyForcibly();
            }
        }
    }

    @Test
    void nativeParentAcceptsOnlyNativeChildren() {
        assertTrue(CliProcessLauncher.childArtifactCompatible(true, true));
        assertFalse(CliProcessLauncher.childArtifactCompatible(true, false));
    }

    @Test
    void jvmParentRetainsNativeAndExecutableJarDevelopmentTiers() {
        assertTrue(CliProcessLauncher.childArtifactCompatible(false, true));
        assertTrue(CliProcessLauncher.childArtifactCompatible(false, false));
    }

    @Test
    void nativeParentReportsMixedArtifactAsDistributionError() {
        Path jar = Path.of("/opt/kompile/lib/kompile-model-staging.jar");

        IOException error = assertThrows(IOException.class,
                () -> CliProcessLauncher.requireCompatibleChild(
                        "kompile-model-staging", false, jar, true));

        assertTrue(error.getMessage().contains("Native Kompile execution"));
        assertTrue(error.getMessage().contains("bin/kompile-model-staging"));
        assertTrue(error.getMessage().contains("JAR fallbacks are available only"));
        assertDoesNotThrow(() -> CliProcessLauncher.requireCompatibleChild(
                "kompile-model-staging", true,
                Path.of("/opt/kompile/bin/kompile-model-staging"), true));
    }
}
