package ai.kompile.cli.plugin.api.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Standalone API/provider qualification, not full CLI-host or interactive-policy qualification. */
@EnabledIfSystemProperty(named = "kompile.nativeImageExecutable", matches = ".+")
class ToolProviderNativeImageTest {
    @Test
    void unrelatedProviderCompilesDiscoversAndExecutesWithoutHostOnJvmAndNative() throws Exception {
        Path basedir = Path.of(System.getProperty("basedir", ".")).toAbsolutePath();
        Path output = Files.createDirectories(basedir.resolve("target/native-tool-provider"));
        Path classes = Files.createDirectories(output.resolve("classes"));
        Path dependenciesFile = basedir.resolve("target/native-runtime-classpath.txt");
        assertTrue(Files.isRegularFile(dependenciesFile),
                "Generate runtime classpath: dependency:build-classpath -DincludeScope=runtime "
                        + "-Dmdep.outputFile=target/native-runtime-classpath.txt");
        String dependencies = Files.readString(dependenciesFile).trim();
        for (String element : dependencies.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            String path = element.replace('\\', '/');
            assertFalse(path.contains("/kompile-cli-main/") || path.contains("/kompile-cli-common/")
                    || path.contains("/kompile-utils/") || path.contains("/org/eclipse/deeplearning4j/")
                    || path.contains("/kompile-graph-") || path.contains("/kompile-app-"),
                    "Host/model dependencies leaked into tool-api: " + element);
        }
        String productionClasspath = basedir.resolve("target/classes") + File.pathSeparator + dependencies;
        Path nativeImage = Path.of(System.getProperty("kompile.nativeImageExecutable")).toAbsolutePath();
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        Path javac = nativeImage.getParent().resolve(windows ? "javac.exe" : "javac");
        Path java = nativeImage.getParent().resolve(windows ? "java.exe" : "java");
        String main = "ai.kompile.cli.plugin.api.tools.nativefixture.NativeToolProviderSmoke";
        Path compileLog = output.resolve("compile.log");
        assertEquals(0, run(List.of(javac.toString(), "--release", "17", "-proc:none", "-sourcepath", classes.toString(),
                "-cp", productionClasspath, "-d", classes.toString(),
                basedir.resolve("src/test/native/NativeToolProviderSmoke.java").toString()), compileLog, 30),
                () -> contents(compileLog));
        Path service = classes.resolve("META-INF/services/ai.kompile.cli.plugin.api.CliExtension");
        Files.createDirectories(service.getParent());
        Files.writeString(service, main + "$FixtureProvider\n");
        String fixtureClasspath = classes + File.pathSeparator + productionClasspath;
        Path jvmLog = output.resolve("jvm.log");
        assertEquals(0, run(List.of(java.toString(), "-cp", fixtureClasspath, main), jvmLog, 20),
                () -> contents(jvmLog));
        assertTrue(Files.readString(jvmLog).contains("PORTABLE_TOOL_PROVIDER_NATIVE_OK"));
        Path binary = output.resolve(windows ? "tool-provider.exe" : "tool-provider");
        Path buildLog = output.resolve("build.log");
        assertEquals(0, run(List.of(nativeImage.toString(), "--no-fallback", "-O0", "-J-Xmx1536m",
                "-H:NumberOfThreads=2", "-cp", fixtureClasspath, "-o", binary.toString(), main), buildLog, 240),
                () -> contents(buildLog));
        Path nativeLog = output.resolve("native.log");
        assertEquals(0, run(List.of(binary.toString()), nativeLog, 20), () -> contents(nativeLog));
        assertTrue(Files.readString(nativeLog).contains("PORTABLE_TOOL_PROVIDER_NATIVE_OK"));
    }

    private static int run(List<String> command, Path log, long timeoutSeconds) throws Exception {
        Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(timeoutSeconds, TimeUnit.SECONDS),
                    () -> "Qualification exceeded " + timeoutSeconds + "s; see " + log + "\n" + contents(log));
            return child.exitValue();
        } finally {
            if (child.isAlive()) {
                child.descendants().forEach(ProcessHandle::destroyForcibly);
                child.destroyForcibly();
                assertTrue(child.waitFor(10, TimeUnit.SECONDS), "Owned fixture process did not terminate");
            }
        }
    }

    private static String contents(Path log) {
        try { return Files.readString(log); }
        catch (Exception failure) { return "Log unavailable: " + failure.getMessage(); }
    }
}
