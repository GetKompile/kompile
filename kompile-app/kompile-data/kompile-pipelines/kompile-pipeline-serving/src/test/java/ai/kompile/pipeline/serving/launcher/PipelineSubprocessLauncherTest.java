package ai.kompile.pipeline.serving.launcher;

import ai.kompile.app.subprocess.SubprocessBackendFlags;
import ai.kompile.app.subprocess.SubprocessPlacement;
import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Placement snapshots are checked without starting a runtime or initializing ND4J. */
@DisabledOnOs(OS.WINDOWS)
class PipelineSubprocessLauncherTest {
    private static final String EXECUTABLE_PROPERTY = "kompile.pipeline.serving.executable";
    private static final String DEVICE_FLAG = "-Dnd4j.placement.defaultDevice=";
    private static final String MEMORY_FLAG = "-D" + SubprocessBackendFlags.MAX_DEVICE_MEMORY_PROPERTY + "=";
    private static final List<String> CHANGED_PROPERTIES = List.of(
            EXECUTABLE_PROPERTY, "org.nd4j.cpu.priority", "org.nd4j.gpu.priority",
            "nd4j.placement.defaultDevice", SubprocessBackendFlags.MAX_DEVICE_MEMORY_PROPERTY);

    @TempDir
    Path dir;
    private final Map<String, String> saved = new HashMap<>();

    @BeforeEach
    void isolateCommandProperties() throws IOException {
        CHANGED_PROPERTIES.forEach(key -> {
            saved.put(key, System.getProperty(key));
            System.clearProperty(key);
        });
        Path executable = Files.createFile(dir.resolve("runtime"));
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        System.setProperty(EXECUTABLE_PROPERTY, executable.toString());
    }

    @AfterEach
    void restoreProperties() {
        saved.forEach((key, value) -> {
            if (value == null) System.clearProperty(key);
            else System.setProperty(key, value);
        });
    }

    @Test
    void replacingGpuPlacementUpdatesDeviceAndCeilingPerCommand() throws Exception {
        PipelineSubprocessLauncher launcher = new PipelineSubprocessLauncher();
        launcher.applyPlacement(SubprocessPlacement.gpu(0, 1024L));
        List<String> first = command(launcher);
        List<String> snapshot = List.copyOf(first);

        launcher.applyPlacement(SubprocessPlacement.gpu(1, 2048L));
        List<String> second = command(launcher);

        assertTrue(first.containsAll(List.of(DEVICE_FLAG + "0", MEMORY_FLAG + "1024")), first.toString());
        assertTrue(second.containsAll(List.of(DEVICE_FLAG + "1", MEMORY_FLAG + "2048")), second.toString());
        assertFalse(second.contains(DEVICE_FLAG + "0"), second.toString());
        assertFalse(second.contains(MEMORY_FLAG + "1024"), second.toString());
        assertEquals(snapshot, first, "a later placement must not mutate an earlier command");
    }

    @Test
    void cpuPlacementDoesNotKeepPreviousGpuDeviceOrCap() throws Exception {
        PipelineSubprocessLauncher launcher = new PipelineSubprocessLauncher();
        launcher.applyPlacement(SubprocessPlacement.gpu(1, 2048L));
        command(launcher);
        launcher.applyPlacement(SubprocessPlacement.cpu());

        List<String> command = command(launcher);

        assertTrue(command.containsAll(List.of(
                "-Dorg.nd4j.cpu.priority=1000", "-Dorg.nd4j.gpu.priority=0")), command.toString());
        assertTrue(command.stream().noneMatch(flag -> flag.startsWith(DEVICE_FLAG)
                || flag.startsWith(MEMORY_FLAG)), command.toString());
    }

    @Test
    void clearingPlacementAddsNoFlagsWithoutParentOverrides() throws Exception {
        PipelineSubprocessLauncher launcher = new PipelineSubprocessLauncher();
        launcher.applyPlacement(SubprocessPlacement.gpu(1, 2048L));
        command(launcher);
        launcher.applyPlacement(null);

        List<String> command = command(launcher);

        assertTrue(command.stream().noneMatch(flag -> flag.startsWith(DEVICE_FLAG)
                || flag.startsWith(MEMORY_FLAG) || flag.startsWith("-Dorg.nd4j.cpu.priority=")
                || flag.startsWith("-Dorg.nd4j.gpu.priority=")), command.toString());
    }

    @Test
    void clearingPlacementRestoresParentDeviceAndCeiling() throws Exception {
        System.setProperty("org.nd4j.cpu.priority", "123");
        System.setProperty("org.nd4j.gpu.priority", "456");
        System.setProperty("nd4j.placement.defaultDevice", "0");
        System.setProperty(SubprocessBackendFlags.MAX_DEVICE_MEMORY_PROPERTY, "4096");
        PipelineSubprocessLauncher launcher = new PipelineSubprocessLauncher();
        List<String> inherited = command(launcher);
        assertTrue(inherited.containsAll(List.of(
                "-Dorg.nd4j.cpu.priority=123", "-Dorg.nd4j.gpu.priority=456",
                DEVICE_FLAG + "0", MEMORY_FLAG + "4096")), inherited.toString());

        launcher.applyPlacement(SubprocessPlacement.gpu(1, 2048L));
        List<String> explicit = command(launcher);
        assertTrue(explicit.containsAll(List.of(DEVICE_FLAG + "1", MEMORY_FLAG + "2048")),
                explicit.toString());
        launcher.applyPlacement(null);

        assertEquals(inherited, command(launcher),
                "clearing placement must restore, not erase, inherited parent settings");
    }

    private List<String> command(PipelineSubprocessLauncher launcher) throws IOException {
        return launcher.buildCommand(UnifiedPipelineDefinition.builder().pipelineId("placement-test").build(),
                dir.resolve("args.json"));
    }
}
