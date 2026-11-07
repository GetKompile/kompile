package ai.kompile.cli.main.extensions.process;

import ai.kompile.cli.plugin.api.CliExtensionDescriptor;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** A native host installs and launches independently compiled code created after the host image. */
@EnabledIfSystemProperty(named = "kompile.nativeImageExecutable", matches = ".+")
@EnabledOnOs({OS.LINUX, OS.MAC})
class ProcessExtensionNativeImageTest {
    @TempDir Path temporary;

    @Test
    void nativeHostInstallsAndRunsIndependentNativePackageWithoutRebuild() throws Exception {
        Path basedir = Path.of(System.getProperty("basedir", ".")).toAbsolutePath();
        Path output = basedir.resolve("target/native-process-extension-smoke");
        Path classes = output.resolve("classes");
        Files.createDirectories(classes);
        String dependencies = List.of(CliExtensionDescriptor.class, CommandLine.class, ObjectMapper.class,
                        JsonFactory.class, JsonProperty.class).stream()
                .map(ProcessExtensionNativeImageTest::location).distinct().collect(Collectors.joining(File.pathSeparator));
        Path nativeImage = Path.of(System.getProperty("kompile.nativeImageExecutable")).toAbsolutePath();
        Path javac = nativeImage.getParent().resolve("javac");
        Path main = basedir.resolve("src/main/java/ai/kompile/cli/main/extensions/process");
        Path compileLog = output.resolve("compile.log");
        assertEquals(0, run(List.of(javac.toString(), "--release", "17", "-proc:none", "-cp", dependencies,
                "-d", classes.toString(), main.resolve("ProcessExtensionManifest.java").toString(),
                main.resolve("ProcessExtensionStore.java").toString(),
                main.resolve("ProcessExtensionCommandRunner.java").toString(),
                basedir.resolve("src/test/native/NativeProcessExtensionSmoke.java").toString()), compileLog, 30),
                () -> contents(compileLog));
        Path host = output.resolve("process-extension-host");
        Path buildLog = output.resolve("build.log");
        assertEquals(0, run(List.of(nativeImage.toString(), "--no-fallback", "-O0", "-J-Xmx2048m",
                "-H:NumberOfThreads=2", "-cp", classes + File.pathSeparator + dependencies,
                "-o", host.toString(), "ai.kompile.cli.main.extensions.nativefixture.NativeProcessExtensionSmoke"),
                buildLog, 240), () -> contents(buildLog));

        // This code is deliberately NOT in the native host's image or classpath.
        Path source = Files.createDirectory(temporary.resolve("package"));
        Path worker = source.resolve("worker");
        Path workerSource = temporary.resolve("worker.c");
        Files.writeString(workerSource, """
                #include <stdio.h>
                #include <stdlib.h>
                int main(int argc, char **argv) {
                    const char *id = getenv("KOMPILE_EXTENSION_ID");
                    const char *version = getenv("KOMPILE_EXTENSION_VERSION");
                    const char *root = getenv("KOMPILE_EXTENSION_ROOT");
                    if (argc != 2 || !id || !version || !root) return 22;
                    FILE *out = fopen(argv[1], "w");
                    if (!out) return 23;
                    fprintf(out, "%s%c%s%c%s", id, 10, version, 10, root);
                    return fclose(out) == 0 ? 0 : 24;
                }
                """);
        Path workerLog = output.resolve("worker-build.log");
        assertEquals(0, run(List.of(System.getProperty("kompile.testCCompiler", "cc"),
                workerSource.toString(), "-o", worker.toString()), workerLog, 30), () -> contents(workerLog));
        Path receipt = temporary.resolve("receipt");
        ObjectMapper mapper = new ObjectMapper();
        var manifest = mapper.createObjectNode();
        manifest.put("schemaVersion", 1).put("apiVersion", 1).put("id", "native-demo").put("version", "1.0");
        manifest.putObject("commands").putObject("native-plugin")
                .put("kind", "executable").put("path", "worker").putArray("args").add(receipt.toString());
        Files.writeString(source.resolve("extension.json"), manifest.toString());
        Path runLog = output.resolve("run.log");
        assertEquals(0, run(List.of(host.toString(), temporary.resolve("store").toString(), source.toString(),
                receipt.toString()), runLog, 30), () -> contents(runLog));
        assertTrue(Files.readString(runLog).contains("NATIVE_PROCESS_EXTENSION_SMOKE_OK"));
    }

    private static int run(List<String> command, Path log, long timeoutSeconds) throws Exception {
        Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(timeoutSeconds, TimeUnit.SECONDS),
                    () -> "Process exceeded " + timeoutSeconds + " seconds; " + log + "\n" + contents(log));
            return child.exitValue();
        } finally {
            if (child.isAlive()) {
                child.descendants().forEach(ProcessHandle::destroyForcibly);
                child.destroyForcibly();
                child.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    private static String location(Class<?> type) {
        try { return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private static String contents(Path log) {
        try { return Files.readString(log); }
        catch (Exception failure) { return "Log unavailable: " + failure.getMessage(); }
    }
}
