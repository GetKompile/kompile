package ai.kompile.cli.main.codeindex;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Standalone core image; neither the CLI host nor any model runtime is in its production classpath. */
@EnabledIfSystemProperty(named = "kompile.nativeImageExecutable", matches = ".+")
class CodeIndexCoreNativeImageTest {
    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS) Path temporary;

    @Test
    void independentlyPackagedCoreIndexesAndQueriesWithoutNumericalRuntime() throws Exception {
        Path basedir = Path.of(System.getProperty("basedir", ".")).toAbsolutePath();
        Path output = Files.createDirectories(basedir.resolve("target/native-core-smoke"));
        Path classes = Files.createDirectories(output.resolve("classes"));
        Path classpathFile = basedir.resolve("target/native-runtime-classpath.txt");
        assertTrue(Files.isRegularFile(classpathFile),
                "Generate the runtime-only classpath first: mvn -pl :kompile-code-index-core dependency:build-classpath "
                        + "-DincludeScope=runtime -Dmdep.outputFile=target/native-runtime-classpath.txt");
        String dependencies = Files.readString(classpathFile).trim();
        for (String element : dependencies.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            String path = element.replace('\\', '/');
            assertFalse(path.contains("/org/eclipse/deeplearning4j/") || path.contains("/kompile-cli-main/")
                    || path.contains("/kompile-graph-reasoning/") || path.contains("/kompile-app-core/"),
                    "Numerical/host runtime leaked into the standalone core: " + element);
        }
        String productionClasspath = basedir.resolve("target/classes") + File.pathSeparator + dependencies;
        Path nativeImage = Path.of(System.getProperty("kompile.nativeImageExecutable")).toAbsolutePath();
        Path javac = nativeImage.getParent().resolve(System.getProperty("os.name").startsWith("Windows")
                ? "javac.exe" : "javac");
        Path compileLog = output.resolve("compile.log");
        assertEquals(0, run(List.of(javac.toString(), "--release", "17", "-proc:none", "-sourcepath", classes.toString(),
                "-cp", productionClasspath, "-d", classes.toString(),
                basedir.resolve("src/test/native/NativeCodeIndexCoreSmoke.java").toString()), compileLog, 30),
                () -> contents(compileLog));
        Path binary = output.resolve(System.getProperty("os.name").startsWith("Windows") ? "index-core.exe" : "index-core");
        Path buildLog = output.resolve("build.log");
        assertEquals(0, run(List.of(nativeImage.toString(), "--no-fallback", "-O0", "-J-Xmx2048m",
                "-H:NumberOfThreads=2", "-cp", classes + File.pathSeparator + productionClasspath,
                "-o", binary.toString(), "ai.kompile.cli.main.codeindex.NativeCodeIndexCoreSmoke"), buildLog, 240),
                () -> contents(buildLog));
        Path runLog = output.resolve("run.log");
        assertEquals(0, run(List.of(binary.toString(), temporary.resolve("workspace").toString(),
                temporary.resolve("home").toString()), runLog, 30), () -> contents(runLog));
        assertTrue(Files.readString(runLog).contains("CODE_INDEX_CORE_NATIVE_OK"));
    }

    private static int run(List<String> command, Path log, long timeoutSeconds) throws Exception {
        Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(timeoutSeconds, TimeUnit.SECONDS),
                    () -> "Qualification process exceeded " + timeoutSeconds + " seconds; see " + log + "\n" + contents(log));
            return child.exitValue();
        } finally {
            if (child.isAlive()) {
                child.descendants().forEach(ProcessHandle::destroyForcibly);
                child.destroyForcibly();
                assertTrue(child.waitFor(10, TimeUnit.SECONDS), "Owned qualification process did not terminate");
            }
        }
    }

    private static String contents(Path log) {
        try { return Files.readString(log); }
        catch (Exception failure) { return "Log unavailable: " + failure.getMessage(); }
    }
}
