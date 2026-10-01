package ai.kompile.pipeline.serving.launcher;

import ai.kompile.app.subprocess.SubprocessPlacement;
import ai.kompile.app.subprocess.SubprocessProtocolChannel;
import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import ai.kompile.pipeline.serving.protocol.PipelineRuntimeProtocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The command a runtime child starts with, in both launch forms, the protocol channel it gets,
 * and the cleanup when taking over a started child fails. The children are shell scripts, so no
 * pipeline runtime or ND4J loads.
 */
@DisabledOnOs(OS.WINDOWS)
class PipelineSubprocessLauncherLaunchTest {
    private static final String EXECUTABLE_PROPERTY = "kompile.pipeline.serving.executable";
    private static final String JAR_PROPERTY = "kompile.pipeline.serving.jar";
    private static final String FORWARDED_PROPERTY = "org.nd4j.test.forwarded";
    private static final List<String> CHANGED_PROPERTIES =
            List.of("user.home", EXECUTABLE_PROPERTY, JAR_PROPERTY, FORWARDED_PROPERTY);
    /** What the child writes to fd 1, as libnd4j's printf does. */
    private static final String NATIVE_TEXT = "native text written to fd 1";

    @TempDir
    Path dir;

    private final Map<String, String> saved = new HashMap<>();
    private Path argsFile;

    @BeforeEach
    void isolateLauncherResolution() throws IOException {
        CHANGED_PROPERTIES.forEach(key -> saved.put(key, System.getProperty(key)));
        // The launcher also looks for a runtime under ~/.kompile; keep it from finding a real one.
        System.setProperty("user.home", Files.createDirectory(dir.resolve("home")).toString());
        argsFile = dir.resolve("args.json");
    }

    @AfterEach
    void restoreProperties() {
        saved.forEach((key, value) -> {
            if (value == null) System.clearProperty(key);
            else System.setProperty(key, value);
        });
    }

    @Test
    void nativeRuntimeGetsHeapPropertiesAndPlacementButNoHotSpotFlags() throws Exception {
        Path executable = executable("exit 0");
        System.setProperty(EXECUTABLE_PROPERTY, executable.toString());
        System.setProperty(FORWARDED_PROPERTY, "yes");
        PipelineSubprocessLauncher launcher = new PipelineSubprocessLauncher();
        launcher.applyPlacement(SubprocessPlacement.gpu(1, 0L));

        List<String> command = launcher.buildCommand(definition("2g"), argsFile);

        assertEquals(executable.toString(), command.get(0));
        assertEquals(argsFile.toString(), command.get(command.size() - 1));
        assertTrue(command.containsAll(List.of("-Xmx2g", "-Dorg.bytedeco.javacpp.nopointergc=true",
                "-Dorg.nd4j.test.forwarded=yes", "-Dorg.nd4j.gpu.priority=1000",
                "-Dnd4j.placement.defaultDevice=1")), command.toString());
        // A GraalVM native image refuses to start on a HotSpot -XX flag.
        assertTrue(command.stream().noneMatch(flag -> flag.startsWith("-XX")), command.toString());
        assertFalse(command.contains("-jar"), command.toString());
    }

    @Test
    void runtimeWithoutAHeapSizeKeepsItsDefaultHeap() throws Exception {
        System.setProperty(EXECUTABLE_PROPERTY, executable("exit 0").toString());

        List<String> command = new PipelineSubprocessLauncher()
                .buildCommand(definition(null), argsFile);

        assertTrue(command.stream().noneMatch(flag -> flag.startsWith("-Xmx")), command.toString());
    }

    @Test
    void jvmRuntimeGetsItsOptionsBeforeTheJar() throws Exception {
        assumeTrue(System.getenv("KOMPILE_PIPELINE_SERVING_EXECUTABLE") == null,
                "a configured native runtime is chosen over the jar");
        String installDir = System.getenv("KOMPILE_INSTALL_DIR");
        assumeTrue(installDir == null
                        || !Files.isExecutable(Path.of(installDir, "bin", "kompile-pipeline-serving")),
                "an installed native runtime is chosen over the jar");
        Path jar = Files.createFile(dir.resolve("kompile-pipeline-serving-exec.jar"));
        System.setProperty(JAR_PROPERTY, jar.toString());
        PipelineSubprocessLauncher launcher = new PipelineSubprocessLauncher();
        launcher.applyPlacement(SubprocessPlacement.gpu(1, 0L));

        List<String> command = launcher.buildCommand(definition("2g"), argsFile);

        int jarFlag = command.indexOf("-jar");
        assertTrue(jarFlag > 0, command.toString());
        assertEquals(jar.toString(), command.get(jarFlag + 1));
        assertEquals(argsFile.toString(), command.get(command.size() - 1));
        assertTrue(command.containsAll(List.of("-XX:+UseG1GC", "-XX:MaxGCPauseMillis=200",
                "-XX:+ExitOnOutOfMemoryError", "-Xmx2g", "-Dorg.bytedeco.javacpp.nopointergc=true",
                "-Dnd4j.placement.defaultDevice=1")), command.toString());
        // After -jar a flag reaches main() as an argument instead of configuring the JVM.
        assertTrue(command.indexOf("-Dnd4j.placement.defaultDevice=1") < jarFlag, command.toString());
    }

    @Test
    void startedChildIsStoppedWhenTakingItOverFailsWithAnError() throws Exception {
        System.setProperty(EXECUTABLE_PROPERTY, executable("exec sleep 600").toString());
        AtomicReference<Process> started = new AtomicReference<>();
        AtomicReference<List<String>> startedWith = new AtomicReference<>();
        PipelineSubprocessLauncher launcher = new PipelineSubprocessLauncher() {
            @Override
            PipelineRuntimeSession newSession(UnifiedPipelineDefinition definition, Process process,
                                              List<String> command, String heapSize, boolean wrapped) {
                started.set(process);
                startedWith.set(command);
                // What starting the session's reader threads throws when none can be created.
                throw new OutOfMemoryError("unable to create native thread: possibly out of memory");
            }
        };

        try {
            assertThrows(OutOfMemoryError.class, () -> launcher.launch(definition("1g")));
            assertTrue(started.get().waitFor(10, TimeUnit.SECONDS), "the started child was left running");
            Path launchArgs = Path.of(startedWith.get().get(startedWith.get().size() - 1));
            assertFalse(Files.exists(launchArgs), "the definition's args file was left behind");
        } finally {
            if (started.get() != null) started.get().destroyForcibly();
        }
    }

    @Test
    void runtimeGetsAProtocolChannelAndItsFd1ReachesStderr() throws Exception {
        String ready = PipelineRuntimeProtocol.encode(PipelineRuntimeProtocol.message(
                PipelineRuntimeProtocol.READY, null, "p1", Map.of()));
        assertFalse(ready.contains("'"), "the script single-quotes it: " + ready);
        System.setProperty(EXECUTABLE_PROPERTY, executable(String.join("\n",
                "[ \"$" + SubprocessProtocolChannel.ENV_PROTOCOL_FD + "\" = 3 ] || exit 7",
                "printf '%s\\n' '" + NATIVE_TEXT + "'",
                "printf '%s\\n' '" + ready + "' >&3 || exit 8",
                // Exits once it reads SHUTDOWN, as a runtime does.
                "read line",
                "exit 0")).toString());
        AtomicBoolean sessionWrapped = new AtomicBoolean();
        PipelineSubprocessLauncher launcher = new PipelineSubprocessLauncher() {
            @Override
            PipelineRuntimeSession newSession(UnifiedPipelineDefinition definition, Process process,
                                              List<String> command, String heapSize, boolean wrapped) {
                sessionWrapped.set(wrapped);
                return super.newSession(definition, process, command, heapSize, wrapped);
            }
        };

        PipelineRuntimeSession session = launcher.launch(definition("1g"));
        try {
            assertTrue(sessionWrapped.get(), "a wrapped child's protocol can still reach its stderr");
            assertTrue(session.isAlive());
        } finally {
            session.close();
        }

        List<String> records = runLog();
        assertEquals(List.of("\"stream\":\"STDERR\""), streamsOf(records, NATIVE_TEXT), records.toString());
        assertEquals(List.of("\"stream\":\"STDOUT\""),
                streamsOf(records, PipelineRuntimeProtocol.PREFIX), records.toString());
    }

    @Test
    void developmentRuntimeIsTheNewestExecJarInTarget() throws Exception {
        // A version bump built without clean leaves the old jar beside the new one.
        Path target = Files.createDirectories(
                dir.resolve("kompile-app/kompile-data/kompile-pipelines/kompile-pipeline-serving/target"));
        Path first = Files.createFile(target.resolve("kompile-pipeline-serving-1.0.0-exec.jar"));
        Path second = Files.createFile(target.resolve("kompile-pipeline-serving-2.0.0-exec.jar"));
        Files.createFile(target.resolve("kompile-pipeline-serving-3.0.0.jar"));
        Path start = Files.createDirectories(dir.resolve("kompile-app/kompile-cli"));

        // Each jar is newest once, so the directory's listing order cannot pick it by chance.
        Files.setLastModifiedTime(first, FileTime.fromMillis(2_000_000L));
        Files.setLastModifiedTime(second, FileTime.fromMillis(1_000_000L));
        assertEquals(first, PipelineSubprocessLauncher.findDevelopmentExecJar(start));
        Files.setLastModifiedTime(second, FileTime.fromMillis(3_000_000L));
        assertEquals(second, PipelineSubprocessLauncher.findDevelopmentExecJar(start));
        Files.setLastModifiedTime(first, FileTime.fromMillis(3_000_000L));
        assertEquals(second, PipelineSubprocessLauncher.findDevelopmentExecJar(start),
                "a tie goes to the later name, the same one every time");
        // Some filesystems list entries in creation order, others newest first: tie again with
        // the earlier name created last.
        Files.delete(first);
        Files.setLastModifiedTime(Files.createFile(first), FileTime.fromMillis(3_000_000L));
        assertEquals(second, PipelineSubprocessLauncher.findDevelopmentExecJar(start),
                "a tie goes to the later name, whichever is listed first");
    }

    private Path executable(String body) throws IOException {
        Path script = dir.resolve("kompile-pipeline-serving");
        Files.writeString(script, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script;
    }

    /** The records of the runtime's run log, once its end record is written. */
    private List<String> runLog() throws Exception {
        Path logs = dir.resolve("home").resolve(".kompile").resolve("logs").resolve("subprocesses");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (Files.isDirectory(logs)) {
                List<Path> runLogs;
                try (Stream<Path> files = Files.walk(logs)) {
                    runLogs = files.filter(file -> file.toString().endsWith(".log")).toList();
                }
                for (Path runLog : runLogs) {
                    List<String> records = Files.readAllLines(runLog);
                    if (records.stream().anyMatch(record ->
                            record.contains("subprocess run ended") && record.endsWith("}"))) {
                        return records;
                    }
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no end record under " + logs);
    }

    /** The stream field of each record that logged a line holding {@code text}. */
    private static List<String> streamsOf(List<String> records, String text) {
        return records.stream().filter(record -> record.contains(text))
                .map(record -> record.replaceAll(".*(\"stream\":\"[A-Z]+\").*", "$1"))
                .toList();
    }

    private static UnifiedPipelineDefinition definition(String heapSize) {
        return UnifiedPipelineDefinition.builder()
                .pipelineId("p1")
                .serving(UnifiedPipelineDefinition.ServingConfig.builder().heapSize(heapSize).build())
                .build();
    }
}
