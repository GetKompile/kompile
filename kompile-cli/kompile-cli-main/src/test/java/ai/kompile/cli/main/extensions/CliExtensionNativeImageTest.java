package ai.kompile.cli.main.extensions;

import ai.kompile.cli.plugin.api.CliExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import picocli.CommandLine;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in qualification of the actual discovery/command bridge in a model-free native image. */
@EnabledIfSystemProperty(named = "kompile.nativeImageExecutable", matches = ".+")
class CliExtensionNativeImageTest {
    @Test
    void serviceDiscoveredExtensionAndCommandRunInNativeImage() throws Exception {
        Path basedir = Path.of(System.getProperty("basedir", ".")).toAbsolutePath();
        Path output = basedir.resolve("target/native-extension-smoke");
        Path classes = output.resolve("classes");
        Files.createDirectories(classes);
        String dependencies = location(CliExtension.class) + File.pathSeparator + location(CommandLine.class);
        // Compile outside the full CLI test JVM: its classpath carries native-build
        // service providers that must not contaminate the isolated fixture/toolchain.
        Path javac = Path.of(System.getProperty("kompile.nativeImageExecutable"))
                .toAbsolutePath().getParent().resolve(System.getProperty("os.name").startsWith("Windows")
                        ? "javac.exe" : "javac");
        assertTrue(Files.isExecutable(javac), "Expected javac beside native-image: " + javac);
        Path compileLog = output.resolve("compile.log");
        int compiled = run(List.of(javac.toString(),
                "--release", "17", "-cp", dependencies, "-d", classes.toString(),
                basedir.resolve("src/main/java/ai/kompile/cli/main/extensions/CliExtensionDiscovery.java").toString(),
                basedir.resolve("src/test/native/NativeExtensionSmoke.java").toString()), compileLog, 30);
        assertEquals(0, compiled, () -> "Fixture compile failed:\n" + contents(compileLog));
        Path service = classes.resolve("META-INF/services/ai.kompile.cli.plugin.api.CliExtension");
        Files.createDirectories(service.getParent());
        Files.writeString(service,
                "ai.kompile.cli.main.extensions.nativefixture.NativeExtensionSmoke$FixtureExtension\n");
        Path binary = output.resolve(System.getProperty("os.name").startsWith("Windows")
                ? "extension-smoke.exe" : "extension-smoke");
        Path buildLog = output.resolve("build.log");
        List<String> command = new ArrayList<>(List.of(
                System.getProperty("kompile.nativeImageExecutable"),
                "--no-fallback", "-O0", "-J-Xmx1536m", "-H:NumberOfThreads=2",
                "-cp", classes + File.pathSeparator + dependencies,
                "-o", binary.toString(),
                "ai.kompile.cli.main.extensions.nativefixture.NativeExtensionSmoke"));
        int buildExit = run(command, buildLog, 180);
        assertEquals(0, buildExit, () -> "Native build failed; see " + buildLog + "\n" + contents(buildLog));
        Path runLog = output.resolve("run.log");
        assertEquals(0, run(List.of(binary.toString()), runLog, 20),
                () -> "Native extension dispatch failed:\n" + contents(runLog));
        assertTrue(Files.readString(runLog).contains("NATIVE_EXTENSION_SMOKE_OK"));
    }

    private static int run(List<String> command, Path log, long timeoutSeconds) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(timeoutSeconds, TimeUnit.SECONDS),
                    () -> "Process exceeded " + timeoutSeconds + " seconds; see " + log + "\n" + contents(log));
            return process.exitValue();
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    private static String location(Class<?> type) throws URISyntaxException {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    private static String contents(Path path) {
        try { return Files.readString(path); }
        catch (Exception failure) { return "(log unavailable: " + failure.getMessage() + ")"; }
    }
}
